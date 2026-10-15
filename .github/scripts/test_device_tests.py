"""Tests of device_tests.py: python3 -m unittest discover -s .github/scripts"""

import io
import json
import os
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import device_tests  # noqa: E402

# Stands in for Gradle: on each run takes the next of the given outcomes, writes
# its results where the Android Gradle plugin would and exits as Gradle would.
FAKE_GRADLE = textwrap.dedent(
    """
    import json, sys
    from pathlib import Path
    root = Path(sys.argv[1])
    runs = root / "runs"
    n = int(runs.read_text()) if runs.exists() else 0
    runs.write_text(str(n + 1))
    outcomes = json.loads(sys.argv[2])
    failed = outcomes[min(n, len(outcomes) - 1)]
    if failed is None:
        sys.exit(1)  # the build itself failed: no results
    results = root / "app/build/outputs/androidTest-results/connected"
    results.mkdir(parents=True, exist_ok=True)
    cases = "".join(
        f'<testcase name="{t.split(".")[1]}" classname="org.example.{t.split(".")[0]}">'
        + ("<failure>it failed</failure>" if t in failed else "") + "</testcase>"
        for t in ["ReaderScreenTest.menu", "ReaderScreenTest.pages", "OpdsScreenTest.password"]
    )
    (results / "TEST-device.xml").write_text(f"<testsuites><testsuite>{cases}</testsuite></testsuites>")
    sys.exit(1 if failed else 0)
    """
)


class DeviceTestsTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.summary = self.root / "summary.md"
        os.environ["GITHUB_STEP_SUMMARY"] = str(self.summary)

    def run_with(self, outcomes, attempts=3):
        out = io.StringIO()
        command = [sys.executable, "-c", FAKE_GRADLE, str(self.root), json.dumps(outcomes)]
        code = device_tests.run(command, attempts, self.root, out)
        runs = int((self.root / "runs").read_text())
        return code, runs, out.getvalue()

    def test_passing_run_is_run_once_and_says_nothing(self):
        code, runs, out = self.run_with([[]])
        self.assertEqual((0, 1, ""), (code, runs, out))
        self.assertFalse(self.summary.exists())

    def test_test_that_fails_and_then_passes_is_named_and_the_run_passes(self):
        code, runs, out = self.run_with([["ReaderScreenTest.menu"], ["OpdsScreenTest.password"], []])
        self.assertEqual((0, 3), (code, runs))
        self.assertIn("::warning title=Flaky test::ReaderScreenTest.menu", out)
        self.assertIn("::warning title=Flaky test::OpdsScreenTest.password", out)
        text = self.summary.read_text()
        self.assertIn("failed and then passed", text)
        self.assertIn("`ReaderScreenTest.menu`", text)
        self.assertIn("`OpdsScreenTest.password`", text)

    def test_test_that_fails_every_time_fails_the_run_after_the_last_attempt(self):
        code, runs, out = self.run_with([["ReaderScreenTest.menu", "ReaderScreenTest.pages"], ["ReaderScreenTest.menu"]])
        self.assertEqual((1, 3), (code, runs))
        self.assertNotIn("::warning", out)
        text = self.summary.read_text()
        self.assertIn("failed on every attempt (3)", text)
        self.assertIn("`ReaderScreenTest.menu`", text.split("every attempt")[1])
        self.assertIn("`ReaderScreenTest.pages`", text.split("every attempt")[0])

    def test_failure_without_failed_tests_is_not_run_again(self):
        # Results an earlier run left behind are not taken for this one's.
        stale = self.root / "app/build/outputs/androidTest-results/connected/TEST-old.xml"
        stale.parent.mkdir(parents=True)
        stale.write_text('<testsuite><testcase name="old" classname="Old"><failure/></testcase></testsuite>')
        code, runs, out = self.run_with([None])
        self.assertEqual((1, 1, ""), (code, runs, out))

    def test_one_attempt_is_as_without_the_script(self):
        code, runs, _ = self.run_with([["ReaderScreenTest.menu"], []], attempts=1)
        self.assertEqual((1, 1), (code, runs))
        self.assertIn("### Tests that failed\n", self.summary.read_text())


if __name__ == "__main__":
    unittest.main()
