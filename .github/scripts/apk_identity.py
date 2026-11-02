#!/usr/bin/env python3
"""Checks that a built APK is the package it is meant to be.

    apk_identity.py --apk app-fdroid-debug.apk --application-id org.evsyukov.perigee.diagnostic

The application id is read from the manifest inside the APK with the SDK's
apkanalyzer, not from the build scripts: what is checked is what a device
would install. A wrong id exits with 1 and names the expected and the actual
one. An APK that is not there, or an apkanalyzer that cannot be run or cannot
read it, exits with 2, so that a broken tool is not taken for a wrong package.
"""

import argparse
import os
import re
import subprocess
import sys
from pathlib import Path

# What an application id looks like: two or more Java-like names joined by dots.
APPLICATION_ID = re.compile(r"[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+")


class AnalyzerError(Exception):
    """apkanalyzer did not give an application id."""


def default_analyzer():
    home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    return str(Path(home) / "cmdline-tools/latest/bin/apkanalyzer") if home else "apkanalyzer"


def application_id(apk, analyzer, run=subprocess.run):
    """The application id in the manifest of apk, as apkanalyzer reads it."""
    try:
        result = run([analyzer, "manifest", "application-id", str(apk)], capture_output=True, text=True)
    except OSError as error:
        raise AnalyzerError(str(error)) from error
    if result.returncode != 0:
        raise AnalyzerError(result.stderr.strip() or f"apkanalyzer exited with {result.returncode}")
    # The id is the last line: a warning of the SDK tools, if any, comes before it.
    lines = [line.strip() for line in result.stdout.splitlines() if line.strip()]
    if not lines or not APPLICATION_ID.fullmatch(lines[-1]):
        raise AnalyzerError(f"no application id in its output: {result.stdout.strip()!r}")
    return lines[-1]


def main(argv=None, run=subprocess.run):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--application-id", required=True, help="the id the APK must have")
    parser.add_argument("--apkanalyzer", default=default_analyzer(), help="the SDK's apkanalyzer")
    args = parser.parse_args(argv)

    if not args.apk.is_file():
        print(f"::error::No APK at {args.apk}")
        return 2
    try:
        actual = application_id(args.apk, args.apkanalyzer, run)
    except AnalyzerError as error:
        print(f"::error::apkanalyzer could not read the application id of {args.apk}: {error}")
        return 2
    if actual != args.application_id:
        print(f"::error::Wrong application id in {args.apk}: expected {args.application_id}, actual {actual}")
        return 1
    print(f"Application id of {args.apk}: {actual}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
