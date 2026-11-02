#!/usr/bin/env python3
"""Checks that a built APK is the package it is meant to be.

    apk_identity.py --apk app-fdroid-debug.apk --application-id org.evsyukov.perigee.diagnostic \\
        --label "Perigee Diagnostics" --debuggable --abi arm64-v8a

The application id is read from the manifest inside the APK with the SDK's
apkanalyzer, the label and whether the app is debuggable with aapt2's badging,
and the engine's library for each ABI from the APK's entries: not from the
build scripts, so what is checked is what a device would install. A wrong
value exits with 1 and names the expected and the actual one. An APK that is
not there, or a tool that cannot be run or cannot read it, exits with 2, so
that a broken tool is not taken for a wrong package.
"""

import argparse
import os
import re
import subprocess
import sys
import zipfile
from pathlib import Path

# What an application id looks like: two or more Java-like names joined by dots.
APPLICATION_ID = re.compile(r"[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+")
# The label of the app in the default locale, as aapt2's badging gives it.
LABEL = re.compile(r"application-label:'(.*)'")
ENGINE = "libperigee_engine.so"


class AnalyzerError(Exception):
    """A tool did not say what it was asked."""


def sdk_home():
    return os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")


def default_analyzer():
    home = sdk_home()
    return str(Path(home) / "cmdline-tools/latest/bin/apkanalyzer") if home else "apkanalyzer"


def default_aapt2():
    """aapt2 of the latest build tools installed."""
    home = sdk_home()
    tools = Path(home, "build-tools") if home else None
    versions = sorted(tools.iterdir(), key=lambda p: [int(n) if n.isdigit() else 0 for n in re.split(r"[.-]", p.name)]) if tools and tools.is_dir() else []
    return str(versions[-1] / "aapt2") if versions else "aapt2"


def output_of(command, run):
    try:
        result = run(command, capture_output=True, text=True)
    except OSError as error:
        raise AnalyzerError(str(error)) from error
    if result.returncode != 0:
        raise AnalyzerError(result.stderr.strip() or f"{Path(command[0]).name} exited with {result.returncode}")
    return result.stdout


def application_id(apk, analyzer, run=subprocess.run):
    """The application id in the manifest of apk, as apkanalyzer reads it."""
    stdout = output_of([analyzer, "manifest", "application-id", str(apk)], run)
    # The id is the last line: a warning of the SDK tools, if any, comes before it.
    lines = [line.strip() for line in stdout.splitlines() if line.strip()]
    if not lines or not APPLICATION_ID.fullmatch(lines[-1]):
        raise AnalyzerError(f"no application id in its output: {stdout.strip()!r}")
    return lines[-1]


def badging(apk, aapt2, run=subprocess.run):
    """The label of apk and whether it is debuggable, as aapt2 reads them."""
    lines = [line.strip() for line in output_of([aapt2, "dump", "badging", str(apk)], run).splitlines()]
    labels = [m.group(1) for m in map(LABEL.fullmatch, lines) if m]
    if not labels:
        raise AnalyzerError("no application-label in aapt2's badging")
    return labels[0], "application-debuggable" in lines


def main(argv=None, run=subprocess.run):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--application-id", required=True, help="the id the APK must have")
    parser.add_argument("--label", help="the label the APK must have")
    parser.add_argument("--debuggable", action="store_true", help="the APK must be debuggable")
    parser.add_argument("--abi", action="append", default=[], help=f"an ABI the APK must carry {ENGINE} for")
    parser.add_argument("--apkanalyzer", default=default_analyzer(), help="the SDK's apkanalyzer")
    parser.add_argument("--aapt2", default=None, help="the SDK's aapt2; that of the latest build tools by default")
    args = parser.parse_args(argv)

    if not args.apk.is_file():
        print(f"::error::No APK at {args.apk}")
        return 2
    wrong = []
    try:
        actual = application_id(args.apk, args.apkanalyzer, run)
        if actual != args.application_id:
            wrong.append(f"application id: expected {args.application_id}, actual {actual}")
        if args.label is not None or args.debuggable:
            label, debuggable = badging(args.apk, args.aapt2 or default_aapt2(), run)
            if args.label is not None and label != args.label:
                wrong.append(f"label: expected {args.label}, actual {label}")
            if args.debuggable and not debuggable:
                wrong.append("debuggable: expected yes, actual no")
        if args.abi:
            with zipfile.ZipFile(args.apk) as apk:
                entries = set(apk.namelist())
            for abi in args.abi:
                if f"lib/{abi}/{ENGINE}" not in entries:
                    wrong.append(f"{abi}: expected lib/{abi}/{ENGINE}, actual none")
    except AnalyzerError as error:
        print(f"::error::Could not read {args.apk}: {error}")
        return 2
    except zipfile.BadZipFile as error:
        print(f"::error::Could not read the entries of {args.apk}: {error}")
        return 2
    if wrong:
        for problem in wrong:
            print(f"::error::Wrong {problem} in {args.apk}")
        return 1
    print(f"{args.apk}: application id {actual}" + (f", label {args.label}" if args.label is not None else "")
          + (", debuggable" if args.debuggable else "") + "".join(f", {abi}" for abi in args.abi))
    return 0


if __name__ == "__main__":
    sys.exit(main())
