#!/usr/bin/env python3
"""Runs device tests, again when some of them failed, and says which ones did.

    device_tests.py --attempts 3 -- ./gradlew :app:connectedFdroidDebugAndroidTest

A test on an emulator in CI fails now and then for nothing in the code. The
whole command is run again, up to --attempts times, as long as tests failed in
it; a failure with no failed test, as a build that does not compile, is not
run again. A test that failed on one attempt and the run passed on a later one
is not hidden: it is listed in the job summary and as a warning on the run, so
that such tests are counted and fixed. The results of each attempt with failed
tests are kept in failed-attempts/attempt-<n>: the next attempt clears them, and
a job that ends green uploads no reports.
"""

import argparse
import os
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ElementTree
from pathlib import Path

# Where the Android Gradle plugin leaves the results of connected tests, by module.
RESULTS = "*/build/outputs/androidTest-results"
# Where the results of attempts with failed tests stay.
KEPT = "failed-attempts"


def failed_tests(root):
    """Tests that failed in the results under root, as Class.method."""
    failed = set()
    for report in root.glob(RESULTS + "/**/TEST-*.xml"):
        for case in ElementTree.parse(report).iter("testcase"):
            if case.find("failure") is not None or case.find("error") is not None:
                failed.add(f"{case.get('classname', '').rsplit('.', 1)[-1]}.{case.get('name')}")
    return failed


def clear_results(root):
    # Results of an earlier attempt must not be read as this one's.
    for results in root.glob(RESULTS):
        shutil.rmtree(results)


def keep_results(root, attempt):
    for results in root.glob(RESULTS):
        shutil.copytree(results, root / KEPT / f"attempt-{attempt}" / results.relative_to(root))


def summary(passed, attempts, failed_by_attempt):
    """What the run has to say about failed tests, as Markdown; empty when none failed."""
    last = failed_by_attempt[-1] if failed_by_attempt and not passed else set()
    earlier = set().union(*failed_by_attempt) - last if failed_by_attempt else set()
    lines = []
    if earlier:
        lines.append(f"### Tests that failed and then passed ({attempts} attempts)")
        lines += [f"- `{test}`" for test in sorted(earlier)]
    if last:
        lines.append(f"### Tests that failed on every attempt ({attempts})" if attempts > 1 else "### Tests that failed")
        lines += [f"- `{test}`" for test in sorted(last)]
    return "\n".join(lines)


def run(command, attempts, root, out=sys.stdout):
    failed_by_attempt = []
    code = 0
    attempt = 0
    while attempt < attempts:
        attempt += 1
        clear_results(root)
        code = subprocess.call(command)
        if code == 0:
            break
        failed = failed_tests(root)
        if not failed:
            break
        failed_by_attempt.append(failed)
        keep_results(root, attempt)
        print(f"Attempt {attempt} of {attempts}: {len(failed)} tests failed", file=out, flush=True)
    passed = code == 0
    flaky = set().union(*failed_by_attempt) if passed and failed_by_attempt else set()
    for test in sorted(flaky):
        print(f"::warning title=Flaky test::{test} failed and passed on a later attempt", file=out)
    text = summary(passed, attempt, failed_by_attempt)
    if text:
        print(text, file=out)
        step_summary = os.environ.get("GITHUB_STEP_SUMMARY")
        if step_summary:
            with open(step_summary, "a", encoding="utf-8") as f:
                f.write(text + "\n")
    return code


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--attempts", type=int, default=1)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    if not command or args.attempts < 1:
        parser.error("a command to run and at least one attempt are needed")
    return run(command, args.attempts, Path.cwd())


if __name__ == "__main__":
    sys.exit(main())
