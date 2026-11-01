#!/usr/bin/env python3
"""Runs one device test alone, round after round, on a device that has not seen the notice.

    repeat_device_test.py --app app.apk --tests tests.apk --rounds 5 --out rounds \\
        [--baseline-tests old-tests.apk --baseline-rounds 10] Class#method

SystemUI shows a notice over a screen that hides its bars until the secure
setting immersive_mode_confirmations is "confirmed", and the notice takes the
input focus: a Back pressed meanwhile goes to it (#138). Before each round the
setting is removed, as on a fresh emulator. The test then runs alone through
`am instrument`, so that it does run, whatever Gradle has cached, and is not run
again when it fails. A round passes when adb exits with 0, the instrumentation
reports that this one test, and no other, started and passed, it ends once, with
-1 and the runner's "OK (1 test)", with no crash or error of `am`, and the
setting is not in `settings list secure` after the test. The first round that
does not pass ends the run. While the test runs, the window holding the focus
and the setting are noted about once a second, and once more after it ended.

With --baseline-tests, the tests of an earlier commit are run first, until a
round is the failure the fix is for: the test fails waiting for the reader's
screen to have the input focus, and the notice held the focus without a break
for at least MIN_HELD seconds, ending at most LAG seconds before the end of the
test was reported. The wait lasts 20 seconds, so that stretch overlaps it.
Any other failure, or the notice seen only for a while, does not count.

The script removes the setting and installs the APKs: run it on a throwaway
emulator only, as CI's is, not on a device kept for anything else.
"""

import argparse
import os
import re
import subprocess
import sys
import time
from collections import namedtuple
from pathlib import Path

KEY = "immersive_mode_confirmations"
NOTICE = "ImmersiveModeConfirmation"
RUNNER = "org.evsyukov.perigee.test/androidx.test.runner.AndroidJUnitRunner"
# AndroidJUnitRunner's status codes: InstrumentationResultPrinter.REPORT_VALUE_RESULT_*.
START, OK = 1, 0
FAILED = {-1, -2}
ENDS = {OK, *FAILED, -3, -4}
# What the instrumentation ends with when it ran to its end: Activity.RESULT_OK.
FINISHED = -1
# The failure the fix is for, as SharedBooksTest.pressBack words it.
KNOWN = "waited in vain: the reader's screen has the input focus"
MIN_HELD, LAG = 15.0, 10.0
VALUE = re.compile(r"INSTRUMENTATION_(STATUS|RESULT): ([^=]*)=(.*)")
CODE = re.compile(r"INSTRUMENTATION_(STATUS_CODE|CODE): (-?[0-9]+)")

# A round as it went: the verdict, the failure the runner reported, the focus
# noted as (seconds, focus, setting), and when the end of the test was seen.
Round = namedtuple("Round", "result failure samples done_at")


def adb(*args):
    return subprocess.run(["adb", *args], check=True, capture_output=True, text=True).stdout


def setting_in(listing):
    """The value in the output of `settings list secure`; None when the key is not there."""
    for line in listing.splitlines():
        key, sign, value = line.rstrip("\r").partition("=")
        if sign and key == KEY:
            return value
    return None


def setting():
    # Not `settings get`: it prints "null" both for no setting and for the word.
    return setting_in(adb("shell", "settings", "list", "secure"))


def focus():
    lines = [line.strip() for line in adb("shell", "dumpsys", "window", "windows").splitlines() if "mCurrentFocus" in line]
    return lines[0] if lines else "dumpsys names no focus"


def records(output):
    """The records of `am instrument -r`, as (kind, values, code), kind "STATUS_CODE" or "CODE",
    and the lines outside any record. A value goes on to the lines up to the next key or code."""
    found, loose, values, key = [], [], {}, None
    for line in output.splitlines():
        line = line.rstrip("\r")
        value, code = VALUE.fullmatch(line), CODE.fullmatch(line)
        if value:
            key = value[2]
            values[key] = value[3]
        elif code:
            found.append((code[1], values, int(code[2])))
            values, key = {}, None
        elif key is not None:
            values[key] += "\n" + line
        else:
            loose.append(line)
    return found, loose


def verdict(output, test, exit_code):
    """("passed" or "failed", the failure reported) when the one test ran to its end; why not otherwise."""
    if exit_code != 0:
        return f"adb exited with {exit_code}", ""
    found, _ = records(output)
    errors = [line for line in output.splitlines() if line.startswith(("onError:", "INSTRUMENTATION_FAILED", "INSTRUMENTATION_ABORTED"))]
    if errors:
        return f"am reported {errors}", ""
    blocks = [(f"{v.get('class')}#{v.get('test')}", v.get("numtests"), code) for kind, v, code in found if kind == "STATUS_CODE"]
    if len(blocks) != 2 or blocks[0] != (test, "1", START) or blocks[1][:2] != (test, "1") or blocks[1][2] not in ENDS:
        return f"not this one test alone, started and ended: reported {blocks}", ""
    last = [(v, code) for kind, v, code in found if kind == "CODE"]
    if len(last) != 1 or found[-1][0] != "CODE":
        return f"the instrumentation did not end once, after the test: {len(last)} ends", ""
    result, code = last[0]
    if code != FINISHED or "shortMsg" in result or "stream" not in result:
        return f"the instrumentation did not finish: code {code}, {sorted(result)}", ""
    status, failure = blocks[1][2], found[1][1].get("stack", "")
    if status == OK:
        if "OK (1 test)" in result["stream"].splitlines():
            return "passed", ""
        return "passed without the runner's OK (1 test)", ""
    if status in FAILED:
        return "failed", failure
    return f"neither passed nor failed: status {status}", failure


def settled(result, after):
    """The round's verdict once the setting after the test is known: a pass leaves none behind."""
    if result == "passed" and after is not None:
        return f"the setting is {after!r} after the test, not put back"
    return result


def known_failure(round_):
    """Whether the round is the failure the fix is for; see the module's description."""
    if round_.result != "failed" or KNOWN not in round_.failure or round_.done_at is None:
        return False
    start = None
    for at, focus, _ in sorted(round_.samples, key=lambda sample: sample[0]):
        if NOTICE not in focus:
            start = None
            continue
        start = at if start is None else start
        if at - start >= MIN_HELD and at >= round_.done_at - LAG:
            return True
    return False


def play_round(test, out, focus_every=1.0):
    """One round in out: the setting removed, the test run, what came of it."""
    out.mkdir(parents=True)
    before = setting()
    adb("shell", "settings", "delete", "secure", KEY)
    fresh = setting()
    if fresh is not None:
        (out / "setting.txt").write_text(f"before the round: {before!r}\nremoved: {fresh!r}\n", encoding="utf-8")
        return Round(f"the setting is {fresh!r}, not removed", "", [], None)
    adb("logcat", "-c")
    samples, done_at, started = [], None, time.monotonic()
    output = out / "instrument.txt"
    with open(output, "w", encoding="utf-8") as log:
        run = subprocess.Popen(["adb", "shell", "am", "instrument", "-w", "-r", "-e", "class", test, RUNNER], stdout=log, stderr=subprocess.STDOUT)
        while run.poll() is None:
            samples.append((round(time.monotonic() - started, 1), focus(), setting()))
            if done_at is None and any(kind == "STATUS_CODE" and code in ENDS for kind, _, code in records(output.read_text(encoding="utf-8"))[0]):
                done_at = samples[-1][0]
            time.sleep(focus_every)
    # The focus once the instrumentation ended, as a sample of its own.
    samples.append((round(time.monotonic() - started, 1), focus(), setting()))
    if done_at is None:
        done_at = samples[-1][0]
    result, failure = verdict(output.read_text(encoding="utf-8"), test, run.returncode)
    after = samples[-1][2]
    result = settled(result, after)
    (out / "setting.txt").write_text(f"before the round: {before!r}\nremoved: {fresh!r}\nafter the test: {after!r}\n", encoding="utf-8")
    (out / "focus.txt").write_text(
        "".join(f"{at}s {window}; {KEY} {value!r}\n" for at, window, value in samples) + f"end of the test seen at {done_at}s\n",
        encoding="utf-8",
    )
    if failure:
        (out / "failure.txt").write_text(failure + "\n", encoding="utf-8")
    if result != "passed":
        (out / "logcat.txt").write_text(adb("logcat", "-d"), encoding="utf-8")
    return Round(result, failure, samples, done_at)


def run(test, app, tests, rounds, out, baseline=None, baseline_rounds=0, play=play_round, install=lambda apk: adb("install", "-r", "-t", apk)):
    lines = [f"### `{test}` alone, on a device that has not seen the notice"]
    install(app)
    red = None
    if baseline:
        install(baseline)
        red = False
        for n in range(1, baseline_rounds + 1):
            played = play(test, out / f"baseline-{n}")
            red = known_failure(played)
            lines.append(f"- baseline {n}: {played.result}" + (f", the failure the fix is for, {NOTICE} in focus" if red else ""))
            if red:
                break
        if not red:
            lines.append(f"- no baseline round in {baseline_rounds} failed waiting for the focus while {NOTICE} held it")
    install(tests)
    passed = 0
    for n in range(1, rounds + 1):
        result = play(test, out / f"round-{n}").result
        lines.append(f"- round {n}: {result}")
        if result != "passed":
            break
        passed += 1
    lines.append(f"{passed} of {rounds} rounds passed")
    text = "\n".join(lines)
    print(text, flush=True)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as f:
            f.write(text + "\n")
    return 0 if passed == rounds and red is not False else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--app", required=True)
    parser.add_argument("--tests", required=True)
    parser.add_argument("--rounds", type=int, default=5)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--baseline-tests")
    parser.add_argument("--baseline-rounds", type=int, default=10)
    parser.add_argument("test", help="Class#method, the class with its package")
    args = parser.parse_args()
    if args.rounds < 1 or "#" not in args.test:
        parser.error("a test as Class#method and at least one round are needed")
    return run(args.test, args.app, args.tests, args.rounds, args.out, args.baseline_tests, args.baseline_rounds)


if __name__ == "__main__":
    sys.exit(main())
