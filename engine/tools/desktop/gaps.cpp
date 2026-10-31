// Lays a book out with crengine as Perigee does and leaves one CSV row per justified line, written by
// the measurement hook of lvtextfm-gaps.patch. The settings follow engine_jni.cpp (create,
// applySettings, load) and EngineSettings.extraCss; the page has no margins, so the text width is
// --width-px. With --cap-em the hook leaves lines whose gap would be wider than the ceiling ragged;
// with --lookahead on the line breaker looks one line ahead before a wide gap; with --paragraph on it
// breaks each justified paragraph as a whole; with --repair on it reworks only the lines around a loose
// one; with --png every drawn page is also saved as an 8-bit
// grayscale PGM, with --text the text of every page goes to a file. --antialias, --hinting, --kerning,
// --fractional, --monospace-scale and --embedded-fonts change how the glyphs are drawn (issue #122); left
// out, they are what the app sets today.

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

#include "../../src/main/cpp/jni/face_size.h"

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
    " { margin-top: 0 !important; margin-bottom: 0 !important; }\n"
    // EngineSettings.PART_TITLE_CSS: a part title and the chapter title under it share a page.
    "section title + section title { page-break-before: auto !important; }\n";

[[noreturn]] void usage(const char* message) {
    if (message) std::fprintf(stderr, "gaps: %s\n", message);
    std::fprintf(stderr,
        "usage: gaps --book BOOK.fb2 --font NAME=PATH.ttf [--font ...] --face NAME --size-px 28\n"
        "            --width-px 1032 --height-px 2000 --hyphenation on|off --out CSV\n"
        "            [--data DIR] [--css fb2.css] [--render-dpi 336] [--weight 400] [--line-spacing 120]\n"
        "            [--min-space-condensing 75] [--cap-em 0.75] [--lookahead on|off] [--png DIR [--pages 3,17,42]]\n"
        "            [--paragraph on|off [--tolerance 300] [--hyphen-penalty 50] [--paragraph-log FILE]]\n"
        "            [--repair on|off [--hyphen-penalty 50] [--paragraph-log FILE]]\n"
        "            [--lang ru-US]\n"
        "            [--text FILE] [--same-size on|off] [--switch-face NAME] [--user-css FILE]\n"
        "            [--antialias on|off] [--hinting 0|1|2] [--kerning 0|1|2|3] [--fractional 0|1|2|3]\n"
        "            [--monospace-scale 100] [--embedded-fonts on|off]\n");
    std::exit(2);
}

int toInt(const char* flag, const std::string& value) {
    char* end = nullptr;
    long n = std::strtol(value.c_str(), &end, 10);
    if (value.empty() || *end || n <= 0 || n > INT_MAX) usage((std::string(flag) + " wants a positive number").c_str());
    return static_cast<int>(n);
}

// One of the modes 0..max of a font property. The engine would map a value outside its list to another
// one without a word, so it is refused here.
int toMode(const char* flag, const std::string& value, int max) {
    char* end = nullptr;
    long n = std::strtol(value.c_str(), &end, 10);
    if (value.empty() || *end || n < 0 || n > max)
        usage((std::string(flag) + " wants 0 to " + std::to_string(max)).c_str());
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

// EXTRA_CSS and then the owner's CSS from --user-css, as EngineSettings.extraCss appends ReaderPrefs.userCss.
std::string extraCssWith(const std::string& userCssFile) {
    std::string css = EXTRA_CSS;
    if (userCssFile.empty()) return css;
    FILE* f = std::fopen(userCssFile.c_str(), "rb");
    if (!f) usage(("cannot read " + userCssFile).c_str());
    std::string user;
    char buffer[4096];
    for (size_t n; (n = std::fread(buffer, 1, sizeof buffer, f)) > 0;) user.append(buffer, n);
    std::fclose(f);
    // The app leaves a blank overlay out.
    if (user.find_first_not_of(" \t\r\n") != std::string::npos) css += user + "\n";
    return css;
}

}  // namespace

int main(int argc, char** argv) {
    std::string book, face, out, hyphenation, data = GAPS_DATA_DIR, css = "fb2.css", capEm, png, text;
    std::string lookahead = "off", paragraph = "off", repair = "off", lang, tolerance, hyphenPenalty, paragraphLog, sameSize = "on", switchFace, userCss;
    std::set<int> only;
    std::vector<std::pair<std::string, std::string>> fonts;
    int size = 0, width = 0, height = 0, dpi = 336, weight = 400, lineSpacing = 120, condensing = 75;
    // How the glyphs are drawn; the defaults are the app's.
    std::string antialias = "on", embeddedFonts = "on";
    int hinting = 2, kerning = KERNING_LIGHT, fractional = 0, monospaceScale = 100;
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
        else if (flag == "--repair") repair = value;
        else if (flag == "--lang") lang = value;
        else if (flag == "--tolerance") toNumber("--tolerance", tolerance = value);
        else if (flag == "--hyphen-penalty") toNumber("--hyphen-penalty", hyphenPenalty = value);
        else if (flag == "--paragraph-log") paragraphLog = value;
        else if (flag == "--png") png = value;
        else if (flag == "--text") text = value;
        else if (flag == "--same-size") sameSize = value;
        else if (flag == "--switch-face") switchFace = value;
        else if (flag == "--user-css") userCss = value;
        else if (flag == "--pages") only = toPages(value);
        else if (flag == "--antialias") antialias = value;
        else if (flag == "--hinting") hinting = toMode("--hinting", value, 2);
        else if (flag == "--kerning") kerning = toMode("--kerning", value, 3);
        else if (flag == "--fractional") fractional = toMode("--fractional", value, 3);
        else if (flag == "--monospace-scale") monospaceScale = toInt("--monospace-scale", value);
        else if (flag == "--embedded-fonts") embeddedFonts = value;
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
    if (sameSize != "on" && sameSize != "off") usage("--same-size wants on or off");
    if (repair != "on" && repair != "off") usage("--repair wants on or off");
    if (antialias != "on" && antialias != "off") usage("--antialias wants on or off");
    if (embeddedFonts != "on" && embeddedFonts != "off") usage("--embedded-fonts wants on or off");
    if (paragraph == "on" && repair == "on") usage("--paragraph on and --repair on exclude each other");
    if (paragraph == "off" && !tolerance.empty()) usage("--tolerance goes with --paragraph on");
    if (paragraph == "off" && repair == "off" && (!hyphenPenalty.empty() || !paragraphLog.empty()))
        usage("--hyphen-penalty and --paragraph-log go with --paragraph on or --repair on");
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
    if (repair == "on") setenv("PERIGEE_GAPS_REPAIR", "1", 1);
    else unsetenv("PERIGEE_GAPS_REPAIR");
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
    if (!switchFace.empty()) names.push_back(switchFace);
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
    view.setMaxFontSize(perigee::MAX_FONT_PX);
    CRPropRef props = LVCreatePropsContainer();
    props->setInt(PROP_REQUESTED_DOM_VERSION, gDOMVersionCurrent);
    props->setInt(PROP_RENDER_BLOCK_RENDERING_FLAGS, BLOCK_RENDERING_FULL_FEATURED);
    props->setInt(PROP_FONT_HINTING, hinting);  // the app's 2 unless asked
    props->setInt(PROP_FONT_KERNING, kerning);  // the app's light kerning unless asked
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
    // Not set by the app today, which leaves the engine's defaults 2, 0, 100 and 1; issue #122 adds them here.
    // The font manager changes the instances it already holds, and the embedded fonts flag is kept for the
    // document LoadDocument creates.
    props->setInt(PROP_FONT_ANTIALIASING, antialias == "on" ? 2 : 0);
    props->setInt(PROP_FONT_FRACTIONAL_POSITIONING, fractional);
    props->setInt(PROP_FONT_MONOSPACE_SCALE, monospaceScale);
    props->setInt(PROP_EMBEDDED_FONTS, embeddedFonts == "on" ? 1 : 0);
    view.propsApply(props);
    const perigee::FaceSize faceSize =
        sameSize == "on" ? perigee::sizeOfFace(lString8(face.c_str()), size, weight) : perigee::FaceSize{size, lString8()};
    view.setFontSize(faceSize.em);
    view.setPageMargins(lvRect(0, 0, 0, 0));
    view.setVisiblePageCount(1, false);
    const std::string stylesheetName = css;
    const std::string extraCss = extraCssWith(userCss);
    lString8 stylesheet;
    if (!LVLoadStylesheetFile(Utf8ToUnicode((data + "/" + css).c_str()), stylesheet)) {
        std::fprintf(stderr, "gaps: cannot read %s/%s\n", data.c_str(), css.c_str());
        return 1;
    }
    stylesheet << "\r\n" << extraCss.c_str() << "\r\n" << faceSize.css;
    view.setStyleSheet(stylesheet, false);
    view.Resize(width, height);

    // NativeEngine.load
    if (!view.LoadDocument(book.c_str(), false) || !view.getDocument()) {
        std::fprintf(stderr, "gaps: cannot load %s\n", book.c_str());
        return 1;
    }
    lString32 language = lang.empty() ? view.getLanguage() : Utf8ToUnicode(lang.c_str());
    props = LVCreatePropsContainer();
    if (!language.empty()) props->setString(PROP_TEXTLANG_MAIN_LANG, UnicodeToUtf8(language).c_str());
    // needsFullShaping() is false for the languages measured, so the app sets light kerning here again;
    // --kerning replaces it, as in create.
    props->setInt(PROP_FONT_KERNING, kerning);
    view.propsApply(props);
    auto started = std::chrono::steady_clock::now();
    view.Render();
    auto rendered = std::chrono::steady_clock::now();
    // The rows of the paragraph log after this marker are the paragraphs planned again while drawing.
    if (FILE* log = perigeeGapsParagraphLog()) std::fprintf(log, "#draw\n");
    // What NativeEngine.applySettings does when the reader picks another face in an open book.
    if (!switchFace.empty()) {
        auto asked = std::chrono::steady_clock::now();
        const perigee::FaceSize next = sameSize == "on" ? perigee::sizeOfFace(lString8(switchFace.c_str()), size, weight)
                                                        : perigee::FaceSize{size, lString8()};
        auto measured = std::chrono::steady_clock::now();
        props = LVCreatePropsContainer();
        props->setString(PROP_FONT_FACE, switchFace.c_str());
        view.propsApply(props);
        view.setFontSize(next.em);
        lString8 css;
        LVLoadStylesheetFile(Utf8ToUnicode((data + "/" + stylesheetName).c_str()), css);
        css << "\r\n" << extraCss.c_str() << "\r\n" << next.css;
        view.setStyleSheet(css, false);
        view.Render();
        auto laidOut = std::chrono::steady_clock::now();
        auto us = [](std::chrono::steady_clock::duration d) {
            return static_cast<long>(std::chrono::duration_cast<std::chrono::microseconds>(d).count());
        };
        std::printf("switch to %s: measuring %ld us, laying out %ld ms, %d pages\n", switchFace.c_str(), us(measured - asked),
                    us(laidOut - measured) / 1000, view.getPageCount());
        return 0;
    }

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
    // The drawing settings that differ from the app's, so that runs with other ones keep their own images.
    std::string drawing;
    if (antialias == "off") drawing += "-aa0";
    if (hinting != 2) drawing += "-h" + std::to_string(hinting);
    if (kerning != KERNING_LIGHT) drawing += "-k" + std::to_string(kerning);
    if (fractional != 0) drawing += "-f" + std::to_string(fractional);
    if (monospaceScale != 100) drawing += "-ms" + std::to_string(monospaceScale);
    if (embeddedFonts == "off") drawing += "-noembedded";
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
                           (paragraph == "on" ? "-paragraph" : "") + (repair == "on" ? "-repair" : "") + drawing + ".pgm";
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
    std::printf("%s: %s, %d px, text width %d px, hyphenation %s, language %s, cap %s, lookahead %s, paragraph %s, "
                "antialias %s, hinting %d, kerning %d, fractional %d, monospace scale %d %%, embedded fonts %s: %d pages\n",
                book.c_str(), face.c_str(), size, width, hyphenation.c_str(), UnicodeToUtf8(language).c_str(), cap.c_str(),
                lookahead.c_str(), paragraph.c_str(), antialias.c_str(), hinting, kerning, fractional, monospaceScale,
                embeddedFonts.c_str(), pages);
    {
        LVFontRef font = perigee::regularFont(lString8(face.c_str()), faceSize.em, weight, faceSize.em);
        std::printf("size: em %d px (asked %d, drawn %d), x-height %.2f px, caps %.2f px, font height %d px%s%s\n", faceSize.em, size,
                    view.getFontSize(), font->getExtraMetric(font_metric_x_height, false) / 64.0,
                    font->getExtraMetric(font_metric_cap_height, false) / 64.0, font->getHeight(),
                    faceSize.css.empty() ? "" : ", ", faceSize.css.empty() ? "" : "line height set by the rule");
    }
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
