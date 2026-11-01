"""Tests of repeat_device_test.py: python3 -m unittest discover -s .github/scripts"""

import io
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import repeat_device_test  # noqa: E402
from repeat_device_test import Round  # noqa: E402

TEST = "org.example.SharedBooksTest#back"
OTHER = "org.example.SharedBooksTest#other"


def status(test, code, total=1):
    """A status block as `am instrument -r` prints it for AndroidJUnitRunner."""
    name, method = test.split("#")
    return (
        f"INSTRUMENTATION_STATUS: class={name}\nINSTRUMENTATION_STATUS: current=1\n"
        f"INSTRUMENTATION_STATUS: id=AndroidJUnitRunner\nINSTRUMENTATION_STATUS: numtests={total}\n"
        f"INSTRUMENTATION_STATUS: stream=\nINSTRUMENTATION_STATUS: test={method}\nINSTRUMENTATION_STATUS_CODE: {code}\n"
    )


def finished(stream, code=-1):
    return f"INSTRUMENTATION_RESULT: stream=\nTime: 12.3\n\n{stream}\n\nINSTRUMENTATION_CODE: {code}\n"


KNOWN = (
    "java.lang.AssertionError: waited in vain: the reader's screen has the input focus; resumed [MainActivity]\n"
    "\tat org.example.SharedBooksTest.back"
)
FAILURE = f"INSTRUMENTATION_STATUS: stack={KNOWN}\n"
PASSED = status(TEST, 1) + status(TEST, 0) + finished("OK (1 test)")


class VerdictTest(unittest.TestCase):
    def verdict(self, output, exit_code=0):
        return repeat_device_test.verdict(output, TEST, exit_code)[0]

    def test_the_one_test_passed(self):
        self.assertEqual("passed", self.verdict(PASSED))

    def test_lines_ending_as_a_terminal_ends_them_are_read_the_same(self):
        self.assertEqual("passed", self.verdict(PASSED.replace("\n", "\r\n")))

    def test_the_one_test_failed_with_its_failure(self):
        output = status(TEST, 1) + FAILURE + status(TEST, -2) + finished("FAILURES!!!\nTests run: 1,  Failures: 1")
        self.assertEqual(("failed", KNOWN), repeat_device_test.verdict(output, TEST, 0))

    def test_adb_failing_is_no_pass_whatever_it_printed(self):
        self.assertIn("adb exited with 23", self.verdict(PASSED, exit_code=23))

    def test_a_code_that_only_begins_as_the_runners_is_no_pass(self):
        self.assertNotEqual("passed", self.verdict(status(TEST, 1) + status(TEST, 0) + finished("OK (1 test)", code=-10)))

    def test_the_instrumentation_ending_with_code_0_is_no_pass(self):
        self.assertNotEqual("passed", self.verdict(status(TEST, 1) + status(TEST, 0) + finished("OK (1 test)", code=0)))

    def test_a_crash_whose_message_reads_as_a_pass_is_no_pass(self):
        crash = "INSTRUMENTATION_RESULT: shortMsg=OK (1 test)\nINSTRUMENTATION_CODE: -1\nINSTRUMENTATION_CODE: 0\n"
        self.assertNotEqual("passed", self.verdict(status(TEST, 1) + status(TEST, 0) + crash))
        crash = "INSTRUMENTATION_RESULT: shortMsg=Process crashed.\nINSTRUMENTATION_RESULT: stream=\nOK (1 test)\n\nINSTRUMENTATION_CODE: -1\n"
        self.assertNotEqual("passed", self.verdict(status(TEST, 1) + status(TEST, 0) + crash))

    def test_two_ends_of_the_instrumentation_are_no_pass(self):
        self.assertNotEqual("passed", self.verdict(PASSED + finished("OK (1 test)")))

    def test_the_test_ending_twice_is_no_pass(self):
        self.assertNotEqual("passed", self.verdict(status(TEST, 1) + status(TEST, 0) + status(TEST, 0) + finished("OK (1 test)")))

    def test_an_error_of_am_is_no_pass(self):
        self.assertNotEqual("passed", self.verdict(PASSED + "onError: commandError=false message=INSTRUMENTATION_ABORTED: System has crashed.\n"))
        aborted = "onError: commandError=false message=INSTRUMENTATION_ABORTED: System has crashed.\n"
        self.assertNotEqual("passed", self.verdict(status(TEST, 1) + status(TEST, 0) + "INSTRUMENTATION_RESULT: stream=\n" + aborted + finished("OK (1 test)")[len("INSTRUMENTATION_RESULT: stream=\n"):]))

    def test_no_test_ran(self):
        self.assertIn("not this one test alone", self.verdict(finished("OK (0 tests)")))

    def test_another_test_ran(self):
        self.assertIn("not this one test alone", self.verdict(status(OTHER, 1) + status(OTHER, 0) + finished("OK (1 test)")))

    def test_more_tests_ran(self):
        output = status(TEST, 1, 2) + status(TEST, 0, 2) + status(OTHER, 1, 2) + status(OTHER, 0, 2) + finished("OK (2 tests)")
        self.assertIn("not this one test alone", self.verdict(output))

    def test_the_process_died_in_the_test(self):
        output = status(TEST, 1) + "INSTRUMENTATION_RESULT: shortMsg=Process crashed.\nINSTRUMENTATION_CODE: 0\n"
        self.assertNotEqual("passed", self.verdict(output))

    def test_a_pass_the_instrumentation_does_not_finish(self):
        self.assertNotEqual("passed", self.verdict(status(TEST, 1) + status(TEST, 0)))

    def test_a_pass_without_the_runners_count(self):
        self.assertNotEqual("passed", self.verdict(status(TEST, 1) + status(TEST, 0) + finished("")))

    def test_a_skipped_test_neither_passed_nor_failed(self):
        self.assertIn("neither", self.verdict(status(TEST, 1) + status(TEST, -3) + finished("OK (0 tests)")))


class SettingTest(unittest.TestCase):
    def test_the_value_is_read_from_the_list_by_its_exact_key(self):
        listing = "accessibility_enabled=0\nimmersive_mode_confirmations=confirmed\nlock_screen=a=b\n"
        self.assertEqual("confirmed", repeat_device_test.setting_in(listing))
        self.assertEqual("a=b", repeat_device_test.setting_in("immersive_mode_confirmations=a=b\n"))

    def test_no_line_is_no_setting(self):
        self.assertIsNone(repeat_device_test.setting_in("accessibility_enabled=0\n"))
        self.assertIsNone(repeat_device_test.setting_in("immersive_mode_confirmations_more=1\n"))

    def test_the_word_null_is_a_setting_there(self):
        self.assertEqual("null", repeat_device_test.setting_in("immersive_mode_confirmations=null\r\n"))

    def test_a_pass_that_leaves_any_setting_behind_is_no_pass(self):
        self.assertEqual("passed", repeat_device_test.settled("passed", None))
        for left in ["null", "confirmed", ""]:
            self.assertIn("not put back", repeat_device_test.settled("passed", left))
        self.assertEqual("failed", repeat_device_test.settled("failed", "confirmed"))


NOTICE_FOCUS = "mCurrentFocus=Window{d2375df u0 ImmersiveModeConfirmation}"
READER_FOCUS = "mCurrentFocus=Window{a1b2c3 u0 org.evsyukov.perigee/org.evsyukov.perigee.MainActivity}"
SENDER_FOCUS = "mCurrentFocus=Window{d4e5f6 u0 org.evsyukov.perigee.test/org.evsyukov.perigee.share.SenderActivity}"


def samples(*spans):
    """The focus noted once a second: (from, to, focus) for each stretch."""
    return [(float(t), focus, None) for start, end, focus in spans for t in range(start, end)]


# The notice holds the focus through the twenty seconds of the wait, then the test closes the apps and reports.
HELD = samples((0, 10, READER_FOCUS), (10, 32, NOTICE_FOCUS), (32, 35, SENDER_FOCUS))


class KnownFailureTest(unittest.TestCase):
    def known(self, result, failure, seen, done_at):
        return repeat_device_test.known_failure(Round(result, failure, seen, done_at))

    def test_the_wait_for_the_focus_failing_while_the_notice_holds_it(self):
        self.assertTrue(self.known("failed", KNOWN, HELD, 35.0))

    def test_another_failure_while_the_notice_holds_the_focus(self):
        self.assertFalse(self.known("failed", "java.lang.AssertionError: expected:<[Epub Author]> but was:<[]>", HELD, 35.0))

    def test_a_round_that_did_not_fail(self):
        self.assertFalse(self.known("passed", "", HELD, 35.0))
        self.assertFalse(self.known("not this one test alone: started [], ended []", KNOWN, HELD, 35.0))

    def test_the_notice_seen_for_a_moment(self):
        # Just before the end, but not through the wait: another window held the focus before it.
        seen = samples((0, 10, READER_FOCUS), (10, 28, SENDER_FOCUS), (28, 33, NOTICE_FOCUS), (33, 35, SENDER_FOCUS))
        self.assertFalse(self.known("failed", KNOWN, seen, 35.0))

    def test_the_notice_long_gone_when_the_test_failed(self):
        seen = samples((0, 25, NOTICE_FOCUS), (25, 60, SENDER_FOCUS))
        self.assertFalse(self.known("failed", KNOWN, seen, 60.0))

    def test_another_window_holding_the_focus(self):
        seen = samples((0, 10, READER_FOCUS), (10, 35, "mCurrentFocus=Window{1 u0 NotificationShade}"))
        self.assertFalse(self.known("failed", KNOWN, seen, 35.0))

    def test_no_end_of_the_test_seen(self):
        self.assertFalse(self.known("failed", KNOWN, HELD, None))


class RunTest(unittest.TestCase):
    def run_with(self, outcomes, rounds=5, baseline=None, baseline_rounds=0):
        """Runs with each round taking the next of outcomes; what was played and installed."""
        played, installed = [], []
        outcomes = iter(outcomes)

        def play(test, out):
            played.append(out.name)
            return next(outcomes)

        with redirect_stdout(io.StringIO()):
            code = repeat_device_test.run(
                TEST, "app.apk", "tests.apk", rounds, Path(tempfile.mkdtemp()), baseline, baseline_rounds,
                play=play, install=installed.append,
            )
        return code, played, installed

    PASS = Round("passed", "", [], 30.0)
    RED = Round("failed", KNOWN, HELD, 35.0)

    def test_every_round_passing_passes(self):
        code, played, installed = self.run_with([self.PASS] * 5)
        self.assertEqual(0, code)
        self.assertEqual([f"round-{n}" for n in range(1, 6)], played)
        self.assertEqual(["app.apk", "tests.apk"], installed)

    def test_the_first_round_that_does_not_pass_ends_the_run(self):
        code, played, _ = self.run_with([self.PASS, self.RED, self.PASS])
        self.assertEqual(1, code)
        self.assertEqual(["round-1", "round-2"], played)

    def test_a_round_that_ran_no_test_fails_the_run(self):
        code, played, _ = self.run_with([Round("not this one test alone: started [], ended []", "", [], None)])
        self.assertEqual((1, ["round-1"]), (code, played))

    def test_the_baseline_runs_first_until_it_fails_on_the_notice(self):
        baseline = [self.PASS, Round("failed", "java.lang.AssertionError: other", HELD, 35.0), self.RED]
        code, played, installed = self.run_with(baseline + [self.PASS] * 5, baseline="old.apk", baseline_rounds=10)
        self.assertEqual(0, code)
        self.assertEqual(["baseline-1", "baseline-2", "baseline-3"] + [f"round-{n}" for n in range(1, 6)], played)
        self.assertEqual(["app.apk", "old.apk", "tests.apk"], installed)

    def test_an_unrelated_baseline_failure_after_the_notice_came_and_went_is_not_the_failure(self):
        earlier = samples((0, 5, READER_FOCUS), (5, 25, NOTICE_FOCUS), (25, 60, READER_FOCUS))
        unrelated = Round("failed", "java.lang.AssertionError: expected:<[Epub Author]> but was:<[]>", earlier, 60.0)
        code, played, _ = self.run_with([unrelated] + [self.PASS] * 5, baseline="old.apk", baseline_rounds=1)
        self.assertEqual(1, code)
        self.assertEqual(["baseline-1"] + [f"round-{n}" for n in range(1, 6)], played)

    def test_a_baseline_that_never_fails_on_the_notice_fails_the_run_after_the_rounds(self):
        baseline = [self.PASS, Round("failed", KNOWN, samples((0, 35, SENDER_FOCUS)), 35.0)]
        code, played, _ = self.run_with(baseline + [self.PASS] * 5, baseline="old.apk", baseline_rounds=2)
        self.assertEqual(1, code)
        self.assertEqual(["baseline-1", "baseline-2"] + [f"round-{n}" for n in range(1, 6)], played)


if __name__ == "__main__":
    unittest.main()
