"""Temporary isolated Android runner for the #156 menu regression."""

import os
from pathlib import Path
import subprocess
import time

from repeat_device_test import RUNNER, verdict

TEST = ('org.evsyukov.perigee.reader.ReaderScreenTest'
        '#androidAccessibilityOpensReaderControlsAndBackReturnsToTheSamePage')
ROUNDS = 4


def main():
    assert os.environ.get('GITHUB_ACTIONS') == 'true', 'CI emulator only'
    devices = subprocess.check_output(['adb', 'devices'], text=True).splitlines()[1:]
    attached = [line.split() for line in devices if line.strip()]
    assert len(attached) == 1 and attached[0][0].startswith('emulator-') and attached[0][1] == 'device', attached
    adb = ['adb', '-s', attached[0][0]]
    out = Path('menu-rounds')
    out.mkdir()
    for apk in ('apks/app.apk', 'apks/tests.apk'):
        subprocess.run(adb + ['install', '-r', '-t', apk], check=True, timeout=100)
    passed = 0
    for n in range(1, ROUNDS + 1):
        path = out / f'round-{n}'
        path.mkdir()
        log = path / 'instrument.txt'
        with log.open('w') as dest, (path / 'focus.txt').open('w') as focus:
            with subprocess.Popen(adb + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class', TEST, RUNNER],
                                  stdout=dest, stderr=subprocess.STDOUT) as proc:
                started = time.monotonic()
                while proc.poll() is None and time.monotonic() - started < 100:
                    try:
                        dump = subprocess.run(adb + ['shell', 'dumpsys', 'window', 'displays'], text=True,
                                              capture_output=True, timeout=6, check=False)
                        lines = [line.strip() for line in dump.stdout.splitlines()
                                 if 'mCurrentFocus=' in line or 'mFocusedApp=' in line]
                        print(round(time.monotonic() - started, 1), dump.returncode, *lines, file=focus, flush=True)
                    except subprocess.TimeoutExpired:
                        print('focus sample timed out', file=focus, flush=True)
                    time.sleep(1)
                if proc.poll() is None:
                    proc.kill()
                code = proc.wait(timeout=10)
        status, failure = verdict(log.read_text(), TEST, code)
        print(f'round {n}: {status}', flush=True)
        (path / 'verdict.txt').write_text(status + '\n')
        if status != 'passed':
            (path / 'failure.txt').write_text(failure + '\n')
        else:
            passed += 1
    print(f'{passed} of {ROUNDS} isolated rounds passed', flush=True)
    return 0 if passed == ROUNDS else 1


if __name__ == '__main__':
    raise SystemExit(main())
