"""Tests of the engine's trace switch on the host: python3 -m unittest discover -s .github/scripts

NativeEngine.setTracing, as engine_jni.cpp has it, is cut out of the file and
built with a C++ compiler of the host against a stand-in for JNI. The stand-in
fails a test for each call JNI forbids: a call other than the exception ones
while an exception is pending, a method looked up in a null class, an exception
of the caller cleared. This checks how the switch handles JNI's failures, not
how Android behaves.
"""

import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

SOURCE = Path(__file__).resolve().parents[2] / "engine/src/main/cpp/jni/engine_jni.cpp"
COMPILER = shutil.which("c++") or shutil.which("g++") or shutil.which("clang++")

MOCK_JNI = r"""
#pragma once
#include <cstdint>
#define JNIEXPORT
#define JNICALL
typedef int32_t jint;
typedef uint8_t jboolean;
struct _jobject {};
typedef _jobject* jobject;
typedef jobject jclass;
struct _jmethodID {};
typedef _jmethodID* jmethodID;
struct JNIEnv {
    jboolean ExceptionCheck();
    void ExceptionClear();
    jclass GetObjectClass(jobject object);
    jobject NewGlobalRef(jobject object);
    void DeleteLocalRef(jobject object);
    jmethodID GetStaticMethodID(jclass type, const char* name, const char* signature);
    void CallStaticVoidMethod(jclass type, jmethodID method, ...);
};
"""

HARNESS = r"""
#include <jni.h>
#include <atomic>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

namespace {
%(trace)s
}  // namespace

extern "C" {
%(set_tracing)s
}

enum Pending { NONE, CALLER, TRACE };
struct Mock {
    Pending pending = NONE;
    bool failObjectClass = false, failGlobalRef = false, failMethod = false;
    int localRefs = 0, calls = 0;
    std::vector<std::string> violations;
} mock;
_jobject self, type, global;
_jmethodID method;

static void guard(const char* call) {
    if (mock.pending != NONE) mock.violations.push_back(std::string(call) + " with an exception pending");
}

jboolean JNIEnv::ExceptionCheck() { return mock.pending != NONE; }
void JNIEnv::ExceptionClear() {
    if (mock.pending == CALLER) mock.violations.push_back("the caller's exception cleared");
    mock.pending = NONE;
}
jclass JNIEnv::GetObjectClass(jobject object) {
    guard("GetObjectClass");
    if (object == nullptr) mock.violations.push_back("GetObjectClass of null");
    if (mock.failObjectClass) { mock.pending = TRACE; return nullptr; }
    mock.localRefs++;
    return &type;
}
jobject JNIEnv::NewGlobalRef(jobject object) {
    guard("NewGlobalRef");
    if (mock.failGlobalRef) { mock.pending = TRACE; return nullptr; }
    return object == nullptr ? nullptr : &global;
}
void JNIEnv::DeleteLocalRef(jobject object) { if (object == &type) mock.localRefs--; }
jmethodID JNIEnv::GetStaticMethodID(jclass type, const char*, const char*) {
    guard("GetStaticMethodID");
    if (type == nullptr) { mock.violations.push_back("GetStaticMethodID in a null class"); return nullptr; }
    if (mock.failMethod) { mock.pending = TRACE; return nullptr; }
    return &method;
}
void JNIEnv::CallStaticVoidMethod(jclass, jmethodID, ...) { guard("CallStaticVoidMethod"); mock.calls++; }

static void turn(JNIEnv& env, bool on) { Java_org_evsyukov_perigee_engine_NativeEngine_setTracing(&env, &self, on ? 1 : 0); }

int main(int argc, char** argv) {
    if (argc != 2) return 2;
    const std::string scenario = argv[1];
    JNIEnv env;
    std::vector<std::string> wrong;
    auto expect = [&](bool ok, const char* what) { if (!ok) wrong.push_back(what); };
    if (scenario == "pending_on_entry") {
        mock.pending = CALLER;
        turn(env, true);
        expect(mock.pending == CALLER, "the caller's exception is still pending");
        expect(gTraceStage.load() == nullptr, "tracing stays off");
    } else if (scenario == "object_class_fails" || scenario == "global_ref_fails" || scenario == "method_missing") {
        mock.failObjectClass = scenario == "object_class_fails";
        mock.failGlobalRef = scenario == "global_ref_fails";
        mock.failMethod = scenario == "method_missing";
        turn(env, true);
        expect(mock.pending == NONE, "the trace's own exception is cleared");
        expect(gTraceStage.load() == nullptr, "tracing stays off");
        expect(mock.localRefs == 0, "the local class reference is deleted");
        traceStage(&env, Stage::ApplyBegin);
        expect(mock.calls == 0, "no stage is reported");
    } else if (scenario == "on_then_off") {
        turn(env, true);
        expect(gTraceStage.load() == &method, "tracing is on");
        expect(mock.localRefs == 0, "the local class reference is deleted");
        traceStage(&env, Stage::ApplyBegin);
        expect(mock.calls == 1, "a stage is reported while on");
        turn(env, false);
        expect(gTraceStage.load() == nullptr, "tracing is off");
        traceStage(&env, Stage::ApplyBegin);
        expect(mock.calls == 1, "no stage is reported once off");
        expect(mock.pending == NONE, "nothing is pending");
    } else {
        return 2;
    }
    for (const auto& v : mock.violations) std::printf("JNI misuse: %%s\n", v.c_str());
    for (const auto& w : wrong) std::printf("expected: %%s\n", w.c_str());
    return mock.violations.empty() && wrong.empty() ? 0 : 1;
}
"""


def extract(source):
    """The trace's declarations and setTracing, as engine_jni.cpp has them."""
    start = source.index("// The diagnostic build's trace of the stages")
    trace = source[start:source.index("}  // namespace", start)]
    head = "JNIEXPORT void JNICALL\nJava_org_evsyukov_perigee_engine_NativeEngine_setTracing("
    begin = source.index(head)
    end = re.compile(r"^}\n", re.M).search(source, begin).end()
    return trace, source[begin:end]


@unittest.skipUnless(COMPILER, "no C++ compiler on this host")
class NativeTraceSwitchTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.dir = tempfile.TemporaryDirectory()
        root = Path(cls.dir.name)
        (root / "jni.h").write_text(MOCK_JNI)
        trace, set_tracing = extract(SOURCE.read_text())
        (root / "harness.cpp").write_text(HARNESS % {"trace": trace, "set_tracing": set_tracing})
        cls.binary = root / "harness"
        built = subprocess.run(
            [COMPILER, "-std=c++17", "-Wall", "-I", str(root), str(root / "harness.cpp"), "-o", str(cls.binary)],
            capture_output=True, text=True,
        )
        if built.returncode != 0:
            cls.dir.cleanup()
            raise AssertionError("the harness does not build:\n" + built.stderr)

    @classmethod
    def tearDownClass(cls):
        cls.dir.cleanup()

    def run_scenario(self, name):
        result = subprocess.run([str(self.binary), name], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, f"{name}:\n{result.stdout}{result.stderr}")

    def test_an_exception_pending_on_entry_is_left_to_the_caller(self):
        self.run_scenario("pending_on_entry")

    def test_a_class_that_cannot_be_had_leaves_tracing_off(self):
        self.run_scenario("object_class_fails")

    def test_a_global_reference_that_cannot_be_made_leaves_tracing_off(self):
        self.run_scenario("global_ref_fails")

    def test_a_missing_callback_leaves_tracing_off(self):
        self.run_scenario("method_missing")

    def test_tracing_turns_on_and_off(self):
        self.run_scenario("on_then_off")


if __name__ == "__main__":
    unittest.main()
