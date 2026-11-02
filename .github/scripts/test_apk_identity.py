"""Tests of apk_identity.py: python3 -m unittest discover -s .github/scripts"""

import contextlib
import io
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import apk_identity  # noqa: E402

# Any file that exists stands in for the APK: apkanalyzer is faked and never opens it.
APK = str(Path(__file__).resolve())
EXPECTED = "org.evsyukov.perigee.diagnostic"


# aapt2's badging of the diagnostic build, cut to the lines that matter and some around them.
BADGING = """package: name='org.evsyukov.perigee.diagnostic' versionCode='11' versionName='0.1.0-rc11-diagnostic'
sdkVersion:'30'
application-label:'Perigee Diagnostics'
application-label-ru:'Perigee Diagnostics'
application: label='Perigee Diagnostics' icon='res/mipmap-anydpi/ic_launcher.xml'
application-debuggable
launchable-activity: name='org.evsyukov.perigee.MainActivity'  label='' icon=''
native-code: 'arm64-v8a' 'x86_64'
"""


class FakeAnalyzer:
    """Stands in for subprocess.run of apkanalyzer and aapt2 and keeps the commands it was given."""

    def __init__(self, stdout="", returncode=0, stderr="", missing=False, badging=BADGING):
        self.stdout, self.returncode, self.stderr, self.missing = stdout, returncode, stderr, missing
        self.badging = badging
        self.commands = []

    def __call__(self, command, **kwargs):
        self.commands.append(command)
        if self.missing:
            raise FileNotFoundError(2, "No such file or directory", command[0])
        if command[1:3] == ["dump", "badging"]:
            return subprocess.CompletedProcess(command, 0, self.badging, "")
        return subprocess.CompletedProcess(command, self.returncode, self.stdout, self.stderr)


def check(analyzer, apk=APK, expected=EXPECTED, extra=()):
    """Exit code and output of the script run against the fake tools."""
    output = io.StringIO()
    with contextlib.redirect_stdout(output):
        code = apk_identity.main(
            ["--apk", apk, "--application-id", expected, "--apkanalyzer", "/sdk/apkanalyzer", "--aapt2", "/sdk/aapt2", *extra],
            run=analyzer,
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


class DiagnosticBuildTest(unittest.TestCase):
    """The label, debuggability and native libraries of the diagnostic build."""

    FULL = ["--label", "Perigee Diagnostics", "--debuggable", "--abi", "arm64-v8a", "--abi", "x86_64"]

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)

    def apk(self, abis=("arm64-v8a", "x86_64")):
        path = Path(self.dir.name, "app.apk")
        with zipfile.ZipFile(path, "w") as apk:
            apk.writestr("AndroidManifest.xml", b"binary")
            for abi in abis:
                apk.writestr(f"lib/{abi}/libperigee_engine.so", b"elf")
        return str(path)

    def test_the_diagnostic_build_passes(self):
        analyzer = FakeAnalyzer(stdout=EXPECTED)
        apk = self.apk()
        code, output = check(analyzer, apk=apk, extra=self.FULL)
        self.assertEqual(code, 0, output)
        self.assertIn(["/sdk/aapt2", "dump", "badging", apk], analyzer.commands)

    def test_the_usual_label_is_wrong(self):
        badging = BADGING.replace("application-label:'Perigee Diagnostics'", "application-label:'Perigee'")
        code, output = check(FakeAnalyzer(stdout=EXPECTED, badging=badging), apk=self.apk(), extra=self.FULL)
        self.assertEqual(code, 1)
        self.assertIn("expected Perigee Diagnostics, actual Perigee", output)

    def test_a_build_that_is_not_debuggable_is_wrong(self):
        badging = BADGING.replace("application-debuggable\n", "")
        code, output = check(FakeAnalyzer(stdout=EXPECTED, badging=badging), apk=self.apk(), extra=self.FULL)
        self.assertEqual(code, 1)
        self.assertIn("debuggable", output)

    def test_an_abi_without_the_engine_is_wrong(self):
        code, output = check(FakeAnalyzer(stdout=EXPECTED), apk=self.apk(abis=("x86_64",)), extra=self.FULL)
        self.assertEqual(code, 1)
        self.assertIn("lib/arm64-v8a/libperigee_engine.so", output)

    def test_every_wrong_value_is_named_at_once(self):
        badging = BADGING.replace("Perigee Diagnostics'\n", "Perigee'\n", 1)
        code, output = check(FakeAnalyzer(stdout="org.evsyukov.perigee", badging=badging), apk=self.apk(abis=()), extra=self.FULL)
        self.assertEqual(code, 1)
        self.assertEqual(output.count("::error::"), 4, output)

    def test_badging_with_no_label_is_not_a_wrong_label(self):
        code, _ = check(FakeAnalyzer(stdout=EXPECTED, badging="package: name='x.y'\n"), apk=self.apk(), extra=self.FULL)
        self.assertEqual(code, 2)

    def test_an_apk_that_is_no_zip_is_not_a_wrong_abi(self):
        code, _ = check(FakeAnalyzer(stdout=EXPECTED), extra=["--abi", "arm64-v8a"])
        self.assertEqual(code, 2)


if __name__ == "__main__":
    unittest.main()
