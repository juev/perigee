// JNI surface of the engine module. crengine is not thread-safe and keeps
// process-wide state (string allocator, font manager, document cache), so
// callers serialize every call, across all documents.

#include <jni.h>
#include <android/bitmap.h>
#include <sys/mman.h>
#include <sys/stat.h>

#include <climits>
#include <string>
#include <vector>

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

/// A cover larger than this is not worth a thumbnail.
const lvsize_t MAX_COVER_BYTES = 16 * 1024 * 1024;

LVDocView* viewOf(jlong handle) {
    return reinterpret_cast<LVDocView*>(handle);
}

void throwState(JNIEnv* env, const char* message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}

lString32 toCre(JNIEnv* env, jstring s) {
    return Utf8ToUnicode(toStd(env, s).c_str());
}

jobjectArray toJava(JNIEnv* env, const std::vector<lString32>& strings) {
    jobjectArray out = env->NewObjectArray(
            static_cast<jsize>(strings.size()), env->FindClass("java/lang/String"), nullptr);
    for (size_t i = 0; i < strings.size(); i++) {
        jstring item = toJava(env, strings[i]);
        env->SetObjectArrayElement(out, static_cast<jsize>(i), item);
        env->DeleteLocalRef(item);
    }
    return out;
}

/// Range between two xpointers in document order; null when either does not resolve.
bool rangeOf(JNIEnv* env, LVDocView* view, jstring start, jstring end, ldomXRange& range) {
    ldomDocument* doc = view->getDocument();
    ldomXPointer from = doc->createXPointer(toCre(env, start));
    ldomXPointer to = doc->createXPointer(toCre(env, end));
    if (from.isNull() || to.isNull()) return false;
    range = ldomXRange(from, to);
    range.sort();
    return true;
}

void collectToc(LVTocItem* parent, std::vector<lString32>& out) {
    for (int i = 0; i < parent->getChildCount(); i++) {
        LVTocItem* item = parent->getChild(i);
        out.push_back(item->getName());
        // The path is there even when the item came from the cache and its
        // xpointer has not been rebuilt yet.
        out.push_back(item->getPath());
        out.push_back(lString32::itoa(item->getLevel()));
        out.push_back(lString32::itoa(item->getPage()));
        collectToc(item, out);
    }
}

/// Up to `words` words next to a search hit, within its paragraph.
lString32 contextBefore(const ldomXPointerEx& hitStart, int words) {
    ldomXPointerEx from = hitStart;
    for (int i = 0; i < words; i++) {
        if (!from.prevVisibleWordStart(true)) break;
    }
    return ldomXRange(from, hitStart).getRangeText();
}

lString32 contextAfter(const ldomXPointerEx& hitEnd, int words) {
    ldomXPointerEx to = hitEnd;
    for (int i = 0; i < words; i++) {
        ldomXPointerEx next = to;
        // At the end of the book the walk lands on the root node; stop before it.
        if (!next.nextVisibleWordEnd(true) || next.getNode()->isRoot()) break;
        to = next;
    }
    return ldomXRange(hitEnd, to).getRangeText();
}

/// Authors one to a line. FB2 names come in parts and the engine joins the
/// people with a comma by default, which a name may contain itself.
lString32 authorsOf(LVDocView* view) {
    if (view->getDocFormat() == doc_format_fb2) return extractDocAuthors(view->getDocument(), cs32("\n"), false);
    return view->getAuthors();
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_org_evsyukov_perigee_engine_NativeEngine_domVersion(JNIEnv*, jobject) {
    return gDOMVersionCurrent;
}

JNIEXPORT void JNICALL
Java_org_evsyukov_perigee_engine_NativeEngine_init(
        JNIEnv* env, jobject, jstring hyphDir, jstring cacheDir, jlong cacheBytes) {
    InitFontManager(lString8());
    HyphMan::initDictionaries(Utf8ToUnicode(toStd(env, hyphDir).c_str()));
    ldomDocCache::init(Utf8ToUnicode(toStd(env, cacheDir).c_str()), static_cast<lvsize_t>(cacheBytes));
}

JNIEXPORT void JNICALL
Java_org_evsyukov_perigee_engine_NativeEngine_registerFonts(JNIEnv* env, jobject, jobjectArray fontFiles) {
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
    // A justified line may shrink its spaces to three quarters at most. The
    // engine's own floor of one half lets words run together before it hyphenates.
    props->setInt(PROP_FORMAT_MIN_SPACE_CONDENSING_PERCENT, 75);
    // Books from 30 KB up are worth caching: parsing and laying out a novel
    // takes seconds, reading it back from the cache does not.
    props->setInt(PROP_MIN_FILE_SIZE_TO_CACHE, 30000);
    view->propsApply(props);
    return reinterpret_cast<jlong>(view);
}

JNIEXPORT void JNICALL
Java_org_evsyukov_perigee_engine_NativeEngine_applySettings(
        JNIEnv* env, jobject, jlong handle, jstring cssFile, jstring extraCss, jstring fontFace,
        jint fontSize, jint fontWeight, jint lineSpacingPercent, jint marginHorizontal, jint marginVertical,
        jboolean hyphenation, jboolean footnotes, jboolean twoPages, jint textColor, jint backgroundColor,
        jint renderDpi) {
    LVDocView* view = viewOf(handle);
    // A new layout moves page boundaries; the reader stays where they were.
    const bool loaded = view->getDocument() != nullptr;
    ldomXPointer position = loaded ? view->getBookmark() : ldomXPointer();

    CRPropRef props = LVCreatePropsContainer();
    props->setString(PROP_FONT_FACE, toStd(env, fontFace).c_str());
    props->setInt(PROP_FONT_BASE_WEIGHT, fontWeight);
    props->setInt(PROP_INTERLINE_SPACE, lineSpacingPercent);
    props->setInt(PROP_FOOTNOTES, footnotes ? 1 : 0);
    props->setInt(PROP_TEXTLANG_HYPHENATION_ENABLED, hyphenation ? 1 : 0);
    props->setInt(PROP_FLOATING_PUNCTUATION, 1);
    props->setInt(PROP_LANDSCAPE_PAGES, twoPages ? 2 : 1);
    props->setInt(PROP_RENDER_DPI, renderDpi);
    view->propsApply(props);
    view->setFontSize(fontSize);
    view->setTextColor(static_cast<lUInt32>(textColor) & 0xFFFFFF);
    view->setBackgroundColor(static_cast<lUInt32>(backgroundColor) & 0xFFFFFF);
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
Java_org_evsyukov_perigee_engine_NativeEngine_saveCache(JNIEnv*, jobject, jlong handle) {
    LVDocView* view = viewOf(handle);
    if (view->getDocument()) view->updateCache();
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

JNIEXPORT jobjectArray JNICALL
Java_org_evsyukov_perigee_engine_NativeEngine_metadata(JNIEnv* env, jobject, jlong handle) {
    LVDocView* view = viewOf(handle);
    return toJava(env, {view->getTitle(), authorsOf(view), view->getLanguage(), view->getSeries()});
}

JNIEXPORT jobjectArray JNICALL
Java_org_evsyukov_perigee_engine_NativeEngine_describe(JNIEnv* env, jobject, jint fd, jstring name) {
    struct stat st {};
    if (fstat(fd, &st) != 0 || st.st_size <= 0 || st.st_size > INT_MAX) return nullptr;
    const auto size = static_cast<size_t>(st.st_size);
    // The file is read where it lies: a document picked through the system has
    // a descriptor but no path the engine could open.
    void* bytes = mmap(nullptr, size, PROT_READ, MAP_PRIVATE, fd, 0);
    if (bytes == MAP_FAILED) return nullptr;
    jobjectArray out = nullptr;
    {
        // The view and all its streams go before the mapping does.
        LVDocView view(-1, true);
        CRPropRef props = LVCreatePropsContainer();
        props->setInt(PROP_MIN_FILE_SIZE_TO_CACHE, INT_MAX);
        view.propsApply(props);
        const lString32 fileName = toCre(env, name);
        view.getDocProps()->setString(DOC_PROP_FILE_NAME, fileName);
        view.getDocProps()->setString(DOC_PROP_FILE_SIZE, lString32::itoa(static_cast<int>(size)));
        LVStreamRef stream = LVCreateMemoryStream(bytes, static_cast<int>(size), false, LVOM_READ);
        stream->SetName(fileName.c_str());
        if (view.LoadDocument(stream, true) && view.getDocument()) {
            out = env->NewObjectArray(2, env->FindClass("java/lang/Object"), nullptr);
            CRPropRef doc = view.getDocProps();
            jobjectArray texts = toJava(env, {view.getTitle(), authorsOf(&view), view.getLanguage(),
                                              doc->getStringDef(DOC_PROP_SERIES_NAME, ""),
                                              doc->getStringDef(DOC_PROP_SERIES_NUMBER, "")});
            env->SetObjectArrayElement(out, 0, texts);
            LVStreamRef cover = view.getCoverPageImageStream();
            const lvsize_t coverSize = cover.isNull() ? 0 : cover->GetSize();
            if (coverSize > 0 && coverSize <= MAX_COVER_BYTES) {
                std::vector<lUInt8> image(coverSize);
                lvsize_t read = 0;
                cover->SetPos(0);
                if (cover->Read(image.data(), coverSize, &read) == LVERR_OK && read == coverSize) {
                    jbyteArray data = env->NewByteArray(static_cast<jsize>(coverSize));
                    env->SetByteArrayRegion(
                            data, 0, static_cast<jsize>(coverSize), reinterpret_cast<const jbyte*>(image.data()));
                    env->SetObjectArrayElement(out, 1, data);
                }
            }
        }
    }
    munmap(bytes, size);
    return out;
}

JNIEXPORT jint JNICALL
Java_org_evsyukov_perigee_engine_NativeEngine_pageOf(JNIEnv* env, jobject, jlong handle, jstring xpointer) {
    LVDocView* view = viewOf(handle);
    ldomXPointer xp = view->getDocument()->createXPointer(toCre(env, xpointer));
    return xp.isNull() ? -1 : view->getBookmarkPage(xp);
}

JNIEXPORT jstring JNICALL
Java_org_evsyukov_perigee_engine_NativeEngine_xpointerOfPage(JNIEnv* env, jobject, jlong handle, jint page) {
    LVDocView* view = viewOf(handle);
    if (page < 0 || page >= view->getPageCount()) return nullptr;
    return toJava(env, view->getPageBookmark(page).toString());
}

JNIEXPORT jobjectArray JNICALL
Java_org_evsyukov_perigee_engine_NativeEngine_toc(JNIEnv* env, jobject, jlong handle) {
    std::vector<lString32> out;
    LVTocItem* root = viewOf(handle)->getToc();
    if (root) collectToc(root, out);
    return toJava(env, out);
}

JNIEXPORT jobjectArray JNICALL
Java_org_evsyukov_perigee_engine_NativeEngine_selectText(
        JNIEnv* env, jobject, jlong handle, jint x0, jint y0, jint x1, jint y1) {
    LVDocView* view = viewOf(handle);
    ldomXPointer from = view->getNodeByPoint(lvPoint(x0, y0), false, true);
    ldomXPointer to = view->getNodeByPoint(lvPoint(x1, y1), false, true);
    if (from.isNull() || to.isNull()) return nullptr;
    ldomXRange range(from, to);
    range.sort();
    if (range.getStart() == range.getEnd()) {
        // One point selects the word under it; between two words, the one before.
        if (!range.getStart().isVisibleWordStart()) range.getStart().prevVisibleWordStart(true);
        range.getEnd() = range.getStart();
        range.getEnd().nextVisibleWordEnd(true);
    } else {
        // An xpointer names the glyph it stands before, so the end of a selection
        // is the glyph after the last one taken. A space under the finger is not taken.
        const lChar32 last = range.getEnd().getChar();
        if (last != 0 && last != ' ' && last != 0x00A0) {
            range.getEnd().setOffset(range.getEnd().getOffset() + 1);
        }
        // A touch lands inside a word; the selection takes the word whole.
        if (range.getStart().isVisibleWordChar() && !range.getStart().isVisibleWordStart()) {
            range.getStart().prevVisibleWordStart(true);
        }
        if (range.getEnd().isVisibleWordChar() && !range.getEnd().isVisibleWordStart()) {
            range.getEnd().nextVisibleWordEnd(true);
        }
    }
    if (range.isNull()) return nullptr;
    lString32 text = range.getRangeText();
    if (lString32(text).trim().empty()) return nullptr;
    return toJava(env, {range.getStart().toString(), range.getEnd().toString(), text});
}

JNIEXPORT jintArray JNICALL
Java_org_evsyukov_perigee_engine_NativeEngine_rangeRects(
        JNIEnv* env, jobject, jlong handle, jstring start, jstring end) {
    LVDocView* view = viewOf(handle);
    std::vector<jint> out;
    ldomXRange range;
    if (rangeOf(env, view, start, end, range)) {
        LVArray<lvRect> rects;
        range.getSegmentRects(rects);
        for (int i = 0; i < rects.length(); i++) {
            if (rects[i].isEmpty()) continue;
            // One rect is one text line, and a line never straddles two pages:
            // it is on the page shown with both corners or not at all.
            lvPoint topLeft = rects[i].topLeft();
            lvPoint bottomRight = rects[i].bottomRight();
            if (!view->docToWindowPoint(topLeft) || !view->docToWindowPoint(bottomRight, true)) continue;
            out.insert(out.end(), {topLeft.x, topLeft.y, bottomRight.x, bottomRight.y});
        }
    }
    jintArray result = env->NewIntArray(static_cast<jsize>(out.size()));
    env->SetIntArrayRegion(result, 0, static_cast<jsize>(out.size()), out.data());
    return result;
}

JNIEXPORT jobjectArray JNICALL
Java_org_evsyukov_perigee_engine_NativeEngine_search(
        JNIEnv* env, jobject, jlong handle, jstring pattern, jboolean caseInsensitive, jint maxHits,
        jint contextWords) {
    LVDocView* view = viewOf(handle);
    std::vector<lString32> out;
    lString32 needle = toCre(env, pattern);
    if (needle.empty()) return toJava(env, out);
    // A phrase is found even when the book splits it with markup, writes it
    // with typographic spaces and apostrophes, or with soft hyphens inside.
    const lUInt32 flags = LDOM_FIND_TEXT_MATCH_ACROSS_TEXT_NODES | LDOM_FIND_TEXT_NORMALIZE_CANONICAL |
                          LDOM_FIND_TEXT_IGNORE_FORMAT_CONTROL_CHARS | LDOM_FIND_TEXT_FOLD_SPACES |
                          LDOM_FIND_TEXT_FOLD_APOSTROPHES;
    ldomXRangeList hits;
    view->getDocument()->findText(needle, caseInsensitive, false, -1, -1, hits, maxHits, -1, -1, false, flags);
    for (int i = 0; i < hits.length(); i++) {
        ldomXRange* hit = hits[i];
        out.push_back(hit->getStart().toString());
        out.push_back(hit->getEnd().toString());
        out.push_back(contextBefore(hit->getStart(), contextWords));
        out.push_back(hit->getRangeText());
        out.push_back(contextAfter(hit->getEnd(), contextWords));
        out.push_back(lString32::itoa(view->getBookmarkPage(hit->getStart())));
    }
    return toJava(env, out);
}

JNIEXPORT jobjectArray JNICALL
Java_org_evsyukov_perigee_engine_NativeEngine_linkAt(JNIEnv* env, jobject, jlong handle, jint x, jint y) {
    LVDocView* view = viewOf(handle);
    ldomXPointer at = view->getNodeByPoint(lvPoint(x, y), true);
    if (at.isNull()) return nullptr;
    ldomXPointer anchor;
    lString32 href = at.getHRef(anchor);
    if (href.empty()) return nullptr;
    lString32 target;
    if (href[0] == '#' && href.length() > 1) {
        ldomNode* node = view->getDocument()->getElementById(href.c_str() + 1);
        if (node) target = ldomXPointer(node, 0).toString();
    }
    return toJava(env, {href, anchor.isNull() ? lString32() : anchor.toString(), target});
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
