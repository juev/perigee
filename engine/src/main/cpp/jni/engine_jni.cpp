#include <jni.h>

#include "lvtinydom.h"

extern "C" JNIEXPORT jint JNICALL
Java_org_evsyukov_perigee_engine_NativeEngine_domVersion(JNIEnv*, jobject) {
    return gDOMVersionCurrent;
}
