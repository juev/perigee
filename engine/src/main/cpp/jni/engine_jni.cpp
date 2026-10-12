// JNI surface of the engine module. crengine is not thread-safe and keeps
// process-wide state (string allocator, font manager, document cache), so
// callers serialize every call, across all documents.

#include <jni.h>
#include <android/bitmap.h>

#include <string>

#include "hyphman.h"
#include "lvdocview.h"
#include "lvdocviewprops.h"
#include "lvdrawbuf.h"
#include "lvfntman.h"
#include "lvrend.h"
#include "lvstring.h"
#include "lvstsheet.h"
#include "lvtinydom.h"

namespace {

std::string toStd(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

jstring toJava(JNIEnv* env, const lString32& s) {
    // NewStringUTF wants modified UTF-8, which differs for code points above
    // U+FFFF; building UTF-16 directly avoids the mismatch.
    std::u16string utf16;
    utf16.reserve(s.length());
    for (int i = 0; i < s.length(); i++) {
        lUInt32 c = static_cast<lUInt32>(s[i]);
        if (c >= 0x10000) {
            c -= 0x10000;
            utf16.push_back(static_cast<char16_t>(0xD800 + (c >> 10)));
            utf16.push_back(static_cast<char16_t>(0xDC00 + (c & 0x3FF)));
        } else {
            utf16.push_back(static_cast<char16_t>(c));
        }
    }
    return env->NewString(reinterpret_cast<const jchar*>(utf16.data()), static_cast<jsize>(utf16.size()));
}

LVDocView* viewOf(jlong handle) {
    return reinterpret_cast<LVDocView*>(handle);
}

void throwState(JNIEnv* env, const char* message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_org_evsyukov_perigee_engine_NativeEngine_domVersion(JNIEnv*, jobject) {
    return gDOMVersionCurrent;
}

JNIEXPORT void JNICALL
Java_org_evsyukov_perigee_engine_NativeEngine_init(
        JNIEnv* env, jobject, jstring hyphDir, jstring cacheDir, jlong cacheBytes, jobjectArray fontFiles) {
    InitFontManager(lString8());
    HyphMan::initDictionaries(Utf8ToUnicode(toStd(env, hyphDir).c_str()));
    ldomDocCache::init(Utf8ToUnicode(toStd(env, cacheDir).c_str()), static_cast<lvsize_t>(cacheBytes));
    const jsize count = env->GetArrayLength(fontFiles);
    for (jsize i = 0; i < count; i++) {
        auto file = static_cast<jstring>(env->GetObjectArrayElement(fontFiles, i));
        // A font the engine cannot read is skipped; the rest still register.
        fontMan->RegisterFont(lString8(toStd(env, file).c_str()));
        env->DeleteLocalRef(file);
    }
}

JNIEXPORT jobjectArray JNICALL
Java_org_evsyukov_perigee_engine_NativeEngine_fontFaces(JNIEnv* env, jobject) {
    lString32Collection faces;
    fontMan->getFaceList(faces);
    jobjectArray out = env->NewObjectArray(faces.length(), env->FindClass("java/lang/String"), nullptr);
    for (int i = 0; i < faces.length(); i++) {
        jstring face = toJava(env, faces[i]);
        env->SetObjectArrayElement(out, i, face);
        env->DeleteLocalRef(face);
    }
    return out;
}

JNIEXPORT jlong JNICALL
Java_org_evsyukov_perigee_engine_NativeEngine_create(JNIEnv*, jobject, jint width, jint height) {
    auto* view = new LVDocView(-1, true);  // bitsPerPixel=-1, noDefaultDocument=true
    view->setViewMode(DVM_PAGES, -1);
    view->Resize(width, height);
    view->setPageHeaderInfo(0);

    CRPropRef props = LVCreatePropsContainer();
    // New books get the newest DOM and "web" block rendering, as in KOReader:
    // xpointers exchanged with bookOrbit are only comparable under the same pair.
    props->setInt(PROP_REQUESTED_DOM_VERSION, gDOMVersionCurrent);
    props->setInt(PROP_RENDER_BLOCK_RENDERING_FLAGS, BLOCK_RENDERING_FULL_FEATURED);
    props->setInt(PROP_FONT_HINTING, 2);
    props->setInt(PROP_FONT_KERNING, 3);  // HarfBuzz shaping
    props->setInt(PROP_TEXTLANG_EMBEDDED_LANGS_ENABLED, 1);
    view->propsApply(props);
    return reinterpret_cast<jlong>(view);
}

JNIEXPORT void JNICALL
Java_org_evsyukov_perigee_engine_NativeEngine_applySettings(
        JNIEnv* env, jobject, jlong handle, jstring cssFile, jstring extraCss, jstring fontFace,
        jint fontSize, jint lineSpacingPercent, jint marginHorizontal, jint marginVertical,
        jboolean hyphenation, jboolean twoPages, jint renderDpi) {
    LVDocView* view = viewOf(handle);
    // A new layout moves page boundaries; the reader stays where they were.
    const bool loaded = view->getDocument() != nullptr;
    ldomXPointer position = loaded ? view->getBookmark() : ldomXPointer();

    CRPropRef props = LVCreatePropsContainer();
    props->setString(PROP_FONT_FACE, toStd(env, fontFace).c_str());
    props->setInt(PROP_INTERLINE_SPACE, lineSpacingPercent);
    props->setInt(PROP_TEXTLANG_HYPHENATION_ENABLED, hyphenation ? 1 : 0);
    props->setInt(PROP_FLOATING_PUNCTUATION, 1);
    props->setInt(PROP_LANDSCAPE_PAGES, twoPages ? 2 : 1);
    props->setInt(PROP_RENDER_DPI, renderDpi);
    view->propsApply(props);
    view->setFontSize(fontSize);
    view->setPageMargins(lvRect(marginHorizontal, marginVertical, marginHorizontal, marginVertical));
    view->setVisiblePageCount(twoPages ? 2 : 1, false);

    lString8 css;
    LVLoadStylesheetFile(Utf8ToUnicode(toStd(env, cssFile).c_str()), css);
    css << "\r\n" << lString8(toStd(env, extraCss).c_str());
    view->setStyleSheet(css, false);

    if (loaded) {
        view->Render();
        if (!position.isNull()) view->goToBookmark(position);
    }
}

JNIEXPORT jboolean JNICALL
Java_org_evsyukov_perigee_engine_NativeEngine_load(JNIEnv* env, jobject, jlong handle, jstring path) {
    LVDocView* view = viewOf(handle);
    if (!view->LoadDocument(toStd(env, path).c_str(), false) || !view->getDocument()) return JNI_FALSE;
    // Hyphenation and line breaking follow the book's language unless a
    // fragment declares its own.
    lString32 language = view->getLanguage();
    if (!language.empty()) {
        CRPropRef props = LVCreatePropsContainer();
        props->setString(PROP_TEXTLANG_MAIN_LANG, UnicodeToUtf8(language).c_str());
        view->propsApply(props);
    }
    view->Render();
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_org_evsyukov_perigee_engine_NativeEngine_close(JNIEnv*, jobject, jlong handle) {
    LVDocView* view = viewOf(handle);
    if (view->getDocument()) view->close();
    delete view;
}

JNIEXPORT jint JNICALL
Java_org_evsyukov_perigee_engine_NativeEngine_pageCount(JNIEnv*, jobject, jlong handle) {
    return viewOf(handle)->getPageCount();
}

JNIEXPORT jint JNICALL
Java_org_evsyukov_perigee_engine_NativeEngine_currentPage(JNIEnv*, jobject, jlong handle) {
    return viewOf(handle)->getCurPage();
}

JNIEXPORT jboolean JNICALL
Java_org_evsyukov_perigee_engine_NativeEngine_goToPage(JNIEnv*, jobject, jlong handle, jint page) {
    return viewOf(handle)->goToPage(page, false, true, false);
}

JNIEXPORT jstring JNICALL
Java_org_evsyukov_perigee_engine_NativeEngine_xpointer(JNIEnv* env, jobject, jlong handle) {
    return toJava(env, viewOf(handle)->getBookmark().toString());
}

JNIEXPORT jboolean JNICALL
Java_org_evsyukov_perigee_engine_NativeEngine_goToXPointer(JNIEnv* env, jobject, jlong handle, jstring xpointer) {
    LVDocView* view = viewOf(handle);
    ldomXPointer xp = view->getDocument()->createXPointer(Utf8ToUnicode(toStd(env, xpointer).c_str()));
    if (xp.isNull()) return JNI_FALSE;
    view->goToBookmark(xp);
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_org_evsyukov_perigee_engine_NativeEngine_textBetween(
        JNIEnv* env, jobject, jlong handle, jstring start, jstring end) {
    ldomDocument* doc = viewOf(handle)->getDocument();
    ldomXPointer from = doc->createXPointer(Utf8ToUnicode(toStd(env, start).c_str()));
    ldomXPointer to = doc->createXPointer(Utf8ToUnicode(toStd(env, end).c_str()));
    if (from.isNull() || to.isNull()) return nullptr;
    ldomXRange range(from, to);
    return toJava(env, range.getRangeText());
}

JNIEXPORT jint JNICALL
Java_org_evsyukov_perigee_engine_NativeEngine_positionPermyriad(JNIEnv*, jobject, jlong handle) {
    return viewOf(handle)->getPosPercent();
}

JNIEXPORT void JNICALL
Java_org_evsyukov_perigee_engine_NativeEngine_renderPage(JNIEnv* env, jobject, jlong handle, jobject bitmap) {
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || info.stride != info.width * 4) {
        throwState(env, "renderPage needs an ARGB_8888 bitmap without row padding");
        return;
    }
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        throwState(env, "cannot lock bitmap pixels");
        return;
    }
    LVDocView* view = viewOf(handle);
    const int w = static_cast<int>(info.width);
    const int h = static_cast<int>(info.height);
    view->Resize(w, h);
    view->Render();
    {
        LVColorDrawBuf buf(w, h, static_cast<lUInt8*>(pixels), 32);
        view->Draw(buf, false);
    }
    // crengine writes BGRA with inverted alpha; Android expects RGBA.
    auto* p = static_cast<uint8_t*>(pixels);
    for (size_t n = static_cast<size_t>(w) * h; n > 0; n--, p += 4) {
        const uint8_t b = p[0];
        p[0] = p[2];
        p[2] = b;
        p[3] ^= 0xFFu;
    }
    AndroidBitmap_unlockPixels(env, bitmap);
}

}  // extern "C"
