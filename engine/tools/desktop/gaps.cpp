// Lays a book out with crengine as Perigee does and leaves one CSV row per justified line, written by
// the measurement hook of lvtextfm-gaps.patch. The settings follow engine_jni.cpp (create,
// applySettings, load) and EngineSettings.extraCss; the page has no margins, so the text width is
// --width-px.

#include <climits>
#include <cstdio>
#include <cstdlib>
#include <cstring>
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
        "            [--min-space-condensing 75]\n");
    std::exit(2);
}

int toInt(const char* flag, const std::string& value) {
    char* end = nullptr;
    long n = std::strtol(value.c_str(), &end, 10);
    if (value.empty() || *end || n <= 0 || n > INT_MAX) usage((std::string(flag) + " wants a positive number").c_str());
    return static_cast<int>(n);
}

}  // namespace

int main(int argc, char** argv) {
    std::string book, face, out, hyphenation, data = GAPS_DATA_DIR, css = "fb2.css";
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
        else if (flag == "--font") {
            auto eq = value.find('=');
            if (eq == std::string::npos || eq == 0) usage("--font wants NAME=PATH");
            fonts.emplace_back(value.substr(0, eq), value.substr(eq + 1));
        } else usage(("unknown flag " + flag).c_str());
    }
    if (book.empty() || face.empty() || out.empty() || fonts.empty() || !size || !width || !height)
        usage("--book, --font, --face, --size-px, --width-px, --height-px and --out are required");
    if (hyphenation != "on" && hyphenation != "off") usage("--hyphenation wants on or off");

    // The hook opens the file on the first justified line it sees and writes the header to an empty one.
    if (FILE* f = std::fopen(out.c_str(), "w")) std::fclose(f);
    else usage(("cannot write " + out).c_str());
    setenv("PERIGEE_GAPS_CSV", out.c_str(), 1);

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
    view.Render();

    // The full rendering breaks the lines but formats them "light", without alignLine(): a page's
    // paragraphs are justified when the page is drawn, as on the screen. A paragraph that runs over
    // two pages may be formatted for each; gaps-report.py drops the repeated lines by node and y.
    const int pages = view.getPageCount();
    LVGrayDrawBuf page(width, height, 2);
    for (int i = 0; i < pages; i++) {
        view.goToPage(i, false, true, false);
        view.Draw(page, false);
    }
    std::printf("%s: %s, %d px, text width %d px, hyphenation %s, language %s: %d pages\n", book.c_str(), face.c_str(),
                size, width, hyphenation.c_str(), UnicodeToUtf8(language).c_str(), pages);
    return 0;
}
