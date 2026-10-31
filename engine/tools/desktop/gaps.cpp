// Lays a book out with crengine as Perigee does and leaves one CSV row per justified line, written by
// the measurement hook of lvtextfm-gaps.patch. The settings follow engine_jni.cpp (create,
// applySettings, load) and EngineSettings.extraCss; the page has no margins, so the text width is
// --width-px. With --cap-em the hook leaves lines whose gap would be wider than the ceiling ragged;
// with --lookahead on the line breaker looks one line ahead before a wide gap; with --paragraph on it
// breaks each justified paragraph as a whole; with --png every drawn page is also saved as an 8-bit
// grayscale PGM, with --text the text of every page goes to a file.

#include <cerrno>
#include <chrono>
#include <climits>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <set>
#include <string>
#include <utility>
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

// The stream of PERIGEE_GAPS_CSV, defined by the hook in lvtextfm.cpp: the page markers go through it so
// that they stay in order with the rows the hook writes.
FILE* perigeeGapsCsv();
// The stream of PERIGEE_GAPS_PARAGRAPH_LOG, one row per paragraph the paragraph breaker planned.
FILE* perigeeGapsParagraphLog();

namespace {

// Kerning mode of engine_jni.cpp: HarfBuzz without ligatures.
const int KERNING_LIGHT = 2;

// EngineSettings.extraCss with justified text and the footnote rules, the defaults of the app.
const char* EXTRA_CSS =
    "body, p, li { text-align: justify !important; }\n"
    "body[name=\"notes\"] section, body[name=\"comments\"] section, *[type~=\"footnote\"], *[type~=\"note\"], "
    "*[type~=\"endnote\"], *[type~=\"rearnote\"], *[role~=\"doc-footnote\"], *[role~=\"doc-endnote\"]"
    " { -cr-hint: footnote-inpage; margin: 0 !important; -cr-only-if: not-inline; font-size: 80% !important; }\n"
    "body[name=\"notes\"] section p, body[name=\"comments\"] section p, *[type~=\"footnote\"] p, *[type~=\"note\"] p, "
    "*[type~=\"endnote\"] p, *[type~=\"rearnote\"] p, *[role~=\"doc-footnote\"] p, *[role~=\"doc-endnote\"] p"
    " { margin-top: 0 !important; margin-bottom: 0 !important; }\n";

[[noreturn]] void usage(const char* message) {
    if (message) std::fprintf(stderr, "gaps: %s\n", message);
    std::fprintf(stderr,
        "usage: gaps --book BOOK.fb2 --font NAME=PATH.ttf [--font ...] --face NAME --size-px 28\n"
        "            --width-px 1032 --height-px 2000 --hyphenation on|off --out CSV\n"
        "            [--data DIR] [--css fb2.css] [--render-dpi 336] [--weight 400] [--line-spacing 120]\n"
        "            [--min-space-condensing 75] [--cap-em 0.75] [--lookahead on|off] [--png DIR [--pages 3,17,42]]\n"
        "            [--paragraph on|off [--tolerance 300] [--hyphen-penalty 50] [--paragraph-log FILE]]\n"
        "            [--text FILE]\n");
    std::exit(2);
}

int toInt(const char* flag, const std::string& value) {
    char* end = nullptr;
    long n = std::strtol(value.c_str(), &end, 10);
    if (value.empty() || *end || n <= 0 || n > INT_MAX) usage((std::string(flag) + " wants a positive number").c_str());
    return static_cast<int>(n);
}

// A number of zero or more, as the paragraph breaker's tolerance and hyphen penalty.
void toNumber(const char* flag, const std::string& value) {
    char* end = nullptr;
    errno = 0;
    double number = std::strtod(value.c_str(), &end);
    if (value.empty() || *end || errno || !std::isfinite(number) || number < 0)
        usage((std::string(flag) + " wants a number of zero or more").c_str());
}

double toEm(const char* flag, const std::string& value) {
    char* end = nullptr;
    errno = 0;
    double em = std::strtod(value.c_str(), &end);
    if (value.empty() || *end || errno || !std::isfinite(em) || em <= 0)
        usage((std::string(flag) + " wants a positive number of ems").c_str());
    return em;
}

// "3,17,42": page numbers counted from 1.
std::set<int> toPages(const std::string& value) {
    std::set<int> pages;
    size_t start = 0;
    while (start <= value.size()) {
        size_t comma = value.find(',', start);
        if (comma == std::string::npos) comma = value.size();
        pages.insert(toInt("--pages", value.substr(start, comma - start)));
        start = comma + 1;
    }
    return pages;
}

// A binary PGM of an 8-bit grayscale buffer, one byte a pixel, 0 black and 255 white.
bool writePgm(const std::string& path, LVGrayDrawBuf& buf) {
    FILE* f = std::fopen(path.c_str(), "wb");
    if (!f) return false;
    std::fprintf(f, "P5\n%d %d\n255\n", buf.GetWidth(), buf.GetHeight());
    bool ok = true;
    for (int y = 0; y < buf.GetHeight() && ok; y++)
        ok = std::fwrite(buf.GetScanLine(y), 1, buf.GetWidth(), f) == static_cast<size_t>(buf.GetWidth());
    return std::fclose(f) == 0 && ok;
}

// The name of the book's file without its directory and extension.
std::string stem(const std::string& path) {
    size_t slash = path.find_last_of('/');
    std::string name = slash == std::string::npos ? path : path.substr(slash + 1);
    size_t dot = name.find_last_of('.');
    return dot == std::string::npos || dot == 0 ? name : name.substr(0, dot);
}

}  // namespace

int main(int argc, char** argv) {
    std::string book, face, out, hyphenation, data = GAPS_DATA_DIR, css = "fb2.css", capEm, png, text;
    std::string lookahead = "off", paragraph = "off", tolerance, hyphenPenalty, paragraphLog;
    std::set<int> only;
    std::vector<std::pair<std::string, std::string>> fonts;
    int size = 0, width = 0, height = 0, dpi = 336, weight = 400, lineSpacing = 120, condensing = 75;
    for (int i = 1; i < argc; i++) {
        std::string flag = argv[i];
        if (i + 1 >= argc) usage((flag + " wants a value").c_str());
        std::string value = argv[++i];
        if (flag == "--book") book = value;
        else if (flag == "--face") face = value;
        else if (flag == "--out") out = value;
        else if (flag == "--hyphenation") hyphenation = value;
        else if (flag == "--data") data = value;
        else if (flag == "--css") css = value;
        else if (flag == "--size-px") size = toInt("--size-px", value);
        else if (flag == "--width-px") width = toInt("--width-px", value);
        else if (flag == "--height-px") height = toInt("--height-px", value);
        else if (flag == "--render-dpi") dpi = toInt("--render-dpi", value);
        else if (flag == "--weight") weight = toInt("--weight", value);
        else if (flag == "--line-spacing") lineSpacing = toInt("--line-spacing", value);
        else if (flag == "--min-space-condensing") condensing = toInt("--min-space-condensing", value);
        else if (flag == "--cap-em") toEm("--cap-em", capEm = value);
        else if (flag == "--lookahead") lookahead = value;
        else if (flag == "--paragraph") paragraph = value;
        else if (flag == "--tolerance") toNumber("--tolerance", tolerance = value);
        else if (flag == "--hyphen-penalty") toNumber("--hyphen-penalty", hyphenPenalty = value);
        else if (flag == "--paragraph-log") paragraphLog = value;
        else if (flag == "--png") png = value;
        else if (flag == "--text") text = value;
        else if (flag == "--pages") only = toPages(value);
        else if (flag == "--font") {
            auto eq = value.find('=');
            if (eq == std::string::npos || eq == 0) usage("--font wants NAME=PATH");
            fonts.emplace_back(value.substr(0, eq), value.substr(eq + 1));
        } else usage(("unknown flag " + flag).c_str());
    }
    if (book.empty() || face.empty() || out.empty() || fonts.empty() || !size || !width || !height)
        usage("--book, --font, --face, --size-px, --width-px, --height-px and --out are required");
    if (hyphenation != "on" && hyphenation != "off") usage("--hyphenation wants on or off");
    if (lookahead != "on" && lookahead != "off") usage("--lookahead wants on or off");
    if (paragraph != "on" && paragraph != "off") usage("--paragraph wants on or off");
    if (paragraph == "off" && (!tolerance.empty() || !hyphenPenalty.empty() || !paragraphLog.empty()))
        usage("--tolerance, --hyphen-penalty and --paragraph-log go with --paragraph on");
    if (!only.empty() && png.empty()) usage("--pages names the pages --png writes");

    // The hook opens the file on the first justified line it sees and writes the header to an empty one.
    if (FILE* f = std::fopen(out.c_str(), "w")) std::fclose(f);
    else usage(("cannot write " + out).c_str());
    setenv("PERIGEE_GAPS_CSV", out.c_str(), 1);
    // The hook reads the ceiling once, at the first justified line; without --cap-em none is inherited.
    if (capEm.empty()) unsetenv("PERIGEE_GAPS_CAP_EM");
    else setenv("PERIGEE_GAPS_CAP_EM", capEm.c_str(), 1);
    // The line breaker reads PERIGEE_GAPS_LOOKAHEAD once as well; only --lookahead on sets it.
    if (lookahead == "on") setenv("PERIGEE_GAPS_LOOKAHEAD", "1", 1);
    else unsetenv("PERIGEE_GAPS_LOOKAHEAD");
    // The paragraph breaker and its settings, read once as well; unset unless asked for.
    if (paragraph == "on") setenv("PERIGEE_GAPS_PARAGRAPH", "1", 1);
    else unsetenv("PERIGEE_GAPS_PARAGRAPH");
    if (tolerance.empty()) unsetenv("PERIGEE_GAPS_TOLERANCE");
    else setenv("PERIGEE_GAPS_TOLERANCE", tolerance.c_str(), 1);
    if (hyphenPenalty.empty()) unsetenv("PERIGEE_GAPS_HYPHEN_PENALTY");
    else setenv("PERIGEE_GAPS_HYPHEN_PENALTY", hyphenPenalty.c_str(), 1);
    if (!paragraphLog.empty()) {
        if (FILE* f = std::fopen(paragraphLog.c_str(), "w")) std::fclose(f);
        else usage(("cannot write " + paragraphLog).c_str());
        setenv("PERIGEE_GAPS_PARAGRAPH_LOG", paragraphLog.c_str(), 1);
    } else unsetenv("PERIGEE_GAPS_PARAGRAPH_LOG");

    InitFontManager(lString8());
    HyphMan::initDictionaries(Utf8ToUnicode((data + "/hyph/").c_str()));
    for (const auto& font : fonts) {
        if (!fontMan->RegisterFont(lString8(font.second.c_str()))) {
            std::fprintf(stderr, "gaps: the engine did not register %s\n", font.second.c_str());
            return 1;
        }
    }
    // The engine names a face after the family in the file: every NAME of --font and --face must be one.
    lString32Collection faces;
    fontMan->getFaceList(faces);
    auto registered = [&faces](const std::string& name) {
        for (int i = 0; i < faces.length(); i++)
            if (UnicodeToUtf8(faces[i]) == name.c_str()) return true;
        return false;
    };
    std::vector<std::string> names{face};
    for (const auto& font : fonts) names.push_back(font.first);
    for (const auto& name : names) {
        if (registered(name)) continue;
        std::fprintf(stderr, "gaps: no face %s among the registered ones:", name.c_str());
        for (int i = 0; i < faces.length(); i++) std::fprintf(stderr, " [%s]", UnicodeToUtf8(faces[i]).c_str());
        std::fprintf(stderr, "\n");
        return 1;
    }

    // NativeEngine.create
    LVDocView view(-1, true);
    view.setViewMode(DVM_PAGES, -1);
    view.Resize(width, height);
    view.setPageHeaderInfo(0);
    CRPropRef props = LVCreatePropsContainer();
    props->setInt(PROP_REQUESTED_DOM_VERSION, gDOMVersionCurrent);
    props->setInt(PROP_RENDER_BLOCK_RENDERING_FLAGS, BLOCK_RENDERING_FULL_FEATURED);
    props->setInt(PROP_FONT_HINTING, 2);
    props->setInt(PROP_FONT_KERNING, KERNING_LIGHT);
    props->setInt(PROP_TEXTLANG_EMBEDDED_LANGS_ENABLED, 1);
    props->setInt(PROP_FORMAT_MIN_SPACE_CONDENSING_PERCENT, condensing);  // the app's 75 unless asked
    props->setInt(PROP_MIN_FILE_SIZE_TO_CACHE, INT_MAX);
    view.propsApply(props);

    // NativeEngine.applySettings
    props = LVCreatePropsContainer();
    props->setString(PROP_FONT_FACE, face.c_str());
    props->setInt(PROP_FONT_BASE_WEIGHT, weight);
    props->setInt(PROP_INTERLINE_SPACE, lineSpacing);
    props->setInt(PROP_FOOTNOTES, 1);
    props->setInt(PROP_TEXTLANG_HYPHENATION_ENABLED, hyphenation == "on" ? 1 : 0);
    props->setInt(PROP_FLOATING_PUNCTUATION, 1);
    props->setInt(PROP_LANDSCAPE_PAGES, 1);
    props->setInt(PROP_RENDER_DPI, dpi);
    view.propsApply(props);
    view.setFontSize(size);
    view.setPageMargins(lvRect(0, 0, 0, 0));
    view.setVisiblePageCount(1, false);
    lString8 stylesheet;
    if (!LVLoadStylesheetFile(Utf8ToUnicode((data + "/" + css).c_str()), stylesheet)) {
        std::fprintf(stderr, "gaps: cannot read %s/%s\n", data.c_str(), css.c_str());
        return 1;
    }
    stylesheet << "\r\n" << EXTRA_CSS;
    view.setStyleSheet(stylesheet, false);
    view.Resize(width, height);

    // NativeEngine.load
    if (!view.LoadDocument(book.c_str(), false) || !view.getDocument()) {
        std::fprintf(stderr, "gaps: cannot load %s\n", book.c_str());
        return 1;
    }
    lString32 language = view.getLanguage();
    props = LVCreatePropsContainer();
    if (!language.empty()) props->setString(PROP_TEXTLANG_MAIN_LANG, UnicodeToUtf8(language).c_str());
    props->setInt(PROP_FONT_KERNING, KERNING_LIGHT);  // needsFullShaping() is false for the languages measured
    view.propsApply(props);
    auto started = std::chrono::steady_clock::now();
    view.Render();
    auto rendered = std::chrono::steady_clock::now();
    // The rows of the paragraph log after this marker are the paragraphs planned again while drawing.
    if (FILE* log = perigeeGapsParagraphLog()) std::fprintf(log, "#draw\n");

    // The full rendering breaks the lines but formats them "light", without alignLine(): a page's
    // paragraphs are justified when the page is drawn, as on the screen. A paragraph that runs over
    // two pages may be formatted for each; gaps-report.py drops the repeated lines by node and y.
    // Every page is drawn, so the CSV covers the book; --pages only picks the pages saved. Before
    // each page a marker row "#page,N" (from 1) goes into the CSV: the rows after it are the lines
    // formatted while drawing that page, which takes a paragraph whole when its first line is there.
    const int pages = view.getPageCount();
    const std::string cap = capEm.empty() ? "none" : capEm;
    std::string faceName;
    for (char c : face)
        if (c != ' ') faceName += c;
    LVGrayDrawBuf page(width, height, 8);
    int saved = 0;
    FILE* pageText = nullptr;
    if (!text.empty() && !(pageText = std::fopen(text.c_str(), "w"))) usage(("cannot write " + text).c_str());
    for (int i = 0; i < pages; i++) {
        if (FILE* csv = perigeeGapsCsv()) std::fprintf(csv, "#page,%d\n", i + 1);
        view.goToPage(i, false, true, false);
        page.Clear(0xFFFFFF);
        view.Draw(page, false);
        if (pageText)
            std::fprintf(pageText, "#page,%d\n%s\n", i + 1, UnicodeToUtf8(view.getPageText(false, i)).c_str());
        if (png.empty() || (!only.empty() && !only.count(i + 1))) continue;
        std::string path = png + "/" + stem(book) + "-p" + std::to_string(i + 1) + "-" + faceName + "-cap" + cap +
                           (lookahead == "on" ? "-lookahead" : "") +
                           (paragraph == "on" ? "-paragraph" : "") + ".pgm";
        if (!writePgm(path, page)) {
            std::fprintf(stderr, "gaps: cannot write %s\n", path.c_str());
            return 1;
        }
        saved++;
    }
    auto drawn = std::chrono::steady_clock::now();
    if (pageText && std::fclose(pageText) != 0) {
        std::fprintf(stderr, "gaps: cannot write %s\n", text.c_str());
        return 1;
    }
    for (int number : only)
        if (number > pages) std::fprintf(stderr, "gaps: no page %d, the book has %d\n", number, pages);
    std::printf("%s: %s, %d px, text width %d px, hyphenation %s, language %s, cap %s, lookahead %s, paragraph %s: %d pages\n",
                book.c_str(), face.c_str(), size, width, hyphenation.c_str(), UnicodeToUtf8(language).c_str(), cap.c_str(),
                lookahead.c_str(), paragraph.c_str(), pages);
    if (paragraph == "on")
        std::printf("paragraph breaker: tolerance %s, hyphen penalty %s\n", tolerance.empty() ? "300" : tolerance.c_str(),
                    hyphenPenalty.empty() ? "50" : hyphenPenalty.c_str());
    auto ms = [](std::chrono::steady_clock::duration d) {
        return static_cast<long>(std::chrono::duration_cast<std::chrono::milliseconds>(d).count());
    };
    std::printf("time: Render() %ld ms, drawing the pages %ld ms\n", ms(rendered - started), ms(drawn - rendered));
    if (!png.empty()) std::printf("%d pages written to %s\n", saved, png.c_str());
    return 0;
}
