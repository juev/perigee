"""Tests of apk_identity.py: python3 -m unittest discover -s .github/scripts"""

import contextlib
import io
import subprocess
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import apk_identity  # noqa: E402

# Any file that exists stands in for the APK: apkanalyzer is faked and never opens it.
APK = str(Path(__file__).resolve())
EXPECTED = "org.evsyukov.perigee.diagnostic"


class FakeAnalyzer:
    """Stands in for subprocess.run of apkanalyzer and keeps the commands it was given."""

    def __init__(self, stdout="", returncode=0, stderr="", missing=False):
        self.stdout, self.returncode, self.stderr, self.missing = stdout, returncode, stderr, missing
        self.commands = []

    def __call__(self, command, **kwargs):
        self.commands.append(command)
        if self.missing:
            raise FileNotFoundError(2, "No such file or directory", command[0])
        return subprocess.CompletedProcess(command, self.returncode, self.stdout, self.stderr)


def check(analyzer, apk=APK, expected=EXPECTED):
    """Exit code and output of the script run against the fake apkanalyzer."""
    output = io.StringIO()
    with contextlib.redirect_stdout(output):
        code = apk_identity.main(
            ["--apk", apk, "--application-id", expected, "--apkanalyzer", "/sdk/apkanalyzer"], run=analyzer
        )
    return code, output.getvalue()


class ApkIdentityTest(unittest.TestCase):
    def test_the_expected_id_passes(self):
        analyzer = FakeAnalyzer(stdout=EXPECTED + "\n")
        code, output = check(analyzer)
        self.assertEqual(code, 0)
        self.assertIn(EXPECTED, output)
        self.assertEqual(analyzer.commands, [["/sdk/apkanalyzer", "manifest", "application-id", APK]])

    def test_the_usual_app_is_a_wrong_id_with_both_named(self):
        code, output = check(FakeAnalyzer(stdout="org.evsyukov.perigee\n"))
        self.assertEqual(code, 1)
        self.assertIn(f"expected {EXPECTED}, actual org.evsyukov.perigee", output)

    def test_a_longer_id_is_not_taken_for_the_expected_one(self):
        code, _ = check(FakeAnalyzer(stdout=EXPECTED + ".debug\n"))
        self.assertEqual(code, 1)

    def test_a_warning_before_the_id_is_skipped(self):
        code, _ = check(FakeAnalyzer(stdout="Warning: an SDK XML file of version 4 was encountered\n" + EXPECTED))
        self.assertEqual(code, 0)

    def test_a_failed_apkanalyzer_is_not_a_wrong_id(self):
        code, output = check(FakeAnalyzer(returncode=1, stderr="java.util.zip.ZipException: zip END header not found"))
        self.assertEqual(code, 2)
        self.assertIn("ZipException", output)

    def test_an_apkanalyzer_that_is_not_there_is_not_a_wrong_id(self):
        code, output = check(FakeAnalyzer(missing=True))
        self.assertEqual(code, 2)
        self.assertIn("/sdk/apkanalyzer", output)

    def test_output_with_no_id_is_not_a_wrong_id(self):
        code, _ = check(FakeAnalyzer(stdout="ERROR: something went wrong\n"))
        self.assertEqual(code, 2)

    def test_no_apk_is_not_a_wrong_id_and_apkanalyzer_is_not_run(self):
        analyzer = FakeAnalyzer(stdout=EXPECTED)
        code, output = check(analyzer, apk=str(Path(APK).with_name("no-such.apk")))
        self.assertEqual(code, 2)
        self.assertIn("No APK at", output)
        self.assertEqual(analyzer.commands, [])


if __name__ == "__main__":
    unittest.main()
