// The precedence of the CSS the owner writes (issue #150), checked on the engine itself: the books of
// owner-css-books.py are laid out the way engine_jni.cpp lays a book out, and the styles the elements end
// up with are read back and compared with what the contract says. Each check prints PASS or FAIL; the exit
// code is the number of FAILs. Every reference it compares with is a first opening of a copy of the book
// under another name, which the cache of the openings under check cannot reach.
//
//   owner-css --data DIR --fonts DIR --books DIR --scratch DIR [--legacy-cache DIR]
//
// --data holds epub.css, --fonts Roboto-Variable.ttf, PTSerif-Regular.ttf and Literata-Variable.ttf, --books
// the output of owner-css-books.py. --scratch is emptied and takes the layout cache. --legacy-cache is a cache
// directory an engine without patches/0003 wrote for large.epub with the owner's @font-face (owner-css.sh
// writes one with LEGACY_BUILD); without it that check is left out.

#include <algorithm>
#include <climits>
#include <cmath>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <spawn.h>
#include <sys/wait.h>
#include <map>
#include <memory>
#include <set>
#include <string>
#include <vector>

#include "fb2def.h"
#include "lvdocview.h"
#include "lvdocviewprops.h"
#include "lvfntman.h"
#include "lvrend.h"
#include "lvstring.h"
#include "lvstsheet.h"
#include "lvtinydom.h"

#include "../../src/main/cpp/jni/face_size.h"
#include "../../src/main/cpp/jni/owner_css.h"

extern char** environ;

// Defined by patches/0003-owner-css-layer.patch: how many documents have an owner's sheet. Weak, so that
// the check also links against an engine without the patch and fails there instead.
int perigeeOwnerStyleSheetCount() __attribute__((weak));

namespace {

namespace fs = std::filesystem;

int failures = 0;

void check(bool ok, const std::string& what) {
    if (!ok) failures++;
    std::printf("%s  %s\n", ok ? "PASS" : "FAIL", what.c_str());
    std::fflush(stdout);
}

int ownerSheets() { return perigeeOwnerStyleSheetCount ? perigeeOwnerStyleSheetCount() : -1; }

// What the engine logs about its cache and stylesheet, to tell a layout read back from the cache.
class Log : public CRLog {
public:
    std::vector<std::string> lines;
    bool saw(const char* what) const {
        return std::any_of(lines.begin(), lines.end(), [what](const std::string& l) { return l.find(what) != std::string::npos; });
    }

protected:
    void log(const char* level, const char* msg, va_list args) override {
        char buf[1024];
        std::vsnprintf(buf, sizeof buf, msg, args);
        lines.push_back(std::string(level) + " " + buf);
    }
};
Log* gLog = nullptr;

// --- the app's stylesheet: EngineSettings.extraCss for the settings below

const char* FOOTNOTES[] = {
    "body[name=\"notes\"] section", "body[name=\"comments\"] section",
    "*[type~=\"footnote\"]", "*[type~=\"note\"]", "*[type~=\"endnote\"]", "*[type~=\"rearnote\"]",
    "*[role~=\"doc-footnote\"]", "*[role~=\"doc-endnote\"]",
};

// Rules a check adds to the app's own, for the cases the app's rules do not have.
std::string gAppExtra;

// The reader's face PT Serif drawn over the book's, justified, paragraph spacing 50 %, the indent forced on.
std::string appCss() {
    std::string css = gAppExtra;
    css += "* { font-family: \"PT Serif\" !important; }\n";
    css += "code, pre, tt, samp, kbd { font-family: \"Courier New\", \"Courier\", monospace !important; }\n";
    css += "body, p, li { text-align: justify !important; }\n";
    css += "p { margin-top: 0 !important; margin-bottom: 0.5em !important; }\n";
    css += "p { text-indent: 1.2em !important; }\n";
    std::string all, allP;
    for (const char* n : FOOTNOTES) {
        all += (all.empty() ? "" : ", ") + std::string(n);
        allP += (allP.empty() ? "" : ", ") + std::string(n) + " p";
    }
    css += all + " { -cr-hint: footnote-inpage; margin: 0 !important; -cr-only-if: not-inline; font-size: 80% !important; }\n";
    css += allP + " { margin-top: 0 !important; margin-bottom: 0 !important; }\n";
    css += "section title + section title { page-break-before: auto !important; }\n";
    return css;
}

const char* FACE = "PT Serif";
const int FONT_SIZE = 32;
const int WEIGHT = 400;

// The owner's overlay: ordinary CSS, with the rules each row of the matrix needs.
const char* OWNER = R"css(/* The reader's overlay. A } p { color: red } inside a comment changes nothing. */
p { text-indent: 0; margin-bottom: 0.6em; text-align: left; font-family: "Literata"; margin-left: 0.2em; margin-right: 0.3em; }
p.own { text-indent: 0.3em; }
p.own { text-indent: 0.4em; }
#p_ownimp { text-indent: 0.7em; }
p.own2 { text-indent: 0.5em !important; }
p.own3 { text-indent: 0.8em !important; }
#p_ownimp2 { text-indent: 0.9em !important; }
p.sh { margin: 0.25em; margin-bottom: 0.9em; }
p.sh2 { margin-bottom: 0.9em; margin: 0.25em; }
#d_inherit { text-align: center; font-family: "Literata"; color: #00aa00; }
body { line-height: 1.6; }
p.iu1 { text-align: inherit; }
p.iu2 { margin-right: initial; }
p.iu3 { margin-left: unset; }
@font-face { font-family: "Owner Alias"; src: local("Roboto"); }
p.ff { font-family: "Owner Alias"; }
p.str::before { content: "a;b}c{d\"e"; }
p.str { color: rgb(0, 0, 255); }
@media all { p.sh { padding-left: 0.5em; } }
@media print { p.sup { text-indent: 3.3em; } }
@supports (display: block) { p.sup { text-indent: 0.11em; } }
@supports (perigee-unknown: 1) { p.sup { text-indent: 5em; } }
p.sup { perigee-unsupported: 1; }
#p_ref::before { content: "O"; }
p.ba::after { display: inline; }
p.oc::before { content: "N"; }
p.oi::before { content: "I" !important; }
#p_oi::before { content: "J"; }
p.fl::first-letter { display: inline; color: #0000ff; }
p.fline::first-line { display: inline; color: #00ff00; }
)css";

std::string replaced(std::string s, const std::string& from, const std::string& to) {
    s.replace(s.find(from), from.size(), to);
    return s;
}

// --- the engine, set up as engine_jni.cpp does it

const char* typeName(int t) {
    switch (t) {
        case css_val_inherited: return "inherited";
        case css_val_unspecified: return "num";
        case css_val_px: return "px";
        case css_val_em: return "em";
        case css_val_rem: return "rem";
        case css_val_percent: return "%";
        case css_val_screen_px: return "spx";
        default: return "?";
    }
}

std::string len(const css_length_t& l) {
    char b[64];
    if (l.type == css_val_color) std::snprintf(b, sizeof b, "#%06x", l.value & 0xFFFFFF);
    else if (l.type == css_val_screen_px || l.type == css_val_inherited) std::snprintf(b, sizeof b, "%d%s", l.value, typeName(l.type));
    else if (l.type == css_val_unspecified && l.value < 0) std::snprintf(b, sizeof b, "generic%d", l.value);
    else std::snprintf(b, sizeof b, "%g%s", l.value / 256.0, typeName(l.type));
    return b;
}

const char* alignName(int a) {
    static const char* names[] = {"inherit", "left", "right", "center", "justify", "start", "end"};
    return a >= 0 && a < 7 ? names[a] : "other";
}

// The strings of a parsed content: lvstsheet.cpp keeps each as 's', its length + 1 and its characters.
std::string contentText(const lString32& c) {
    std::string out;
    for (int i = 0; i < c.length(); i++) {
        if ((c[i] == U's' || c[i] == U'a' || c[i] == U'u') && i + 1 < c.length()) {
            const int n = static_cast<int>(c[i + 1]) - 1;
            if (c[i] == U's') out += UnicodeToUtf8(c.substr(i + 2, n)).c_str();
            i += 1 + n;
        } else out += "<" + std::string(1, static_cast<char>(c[i])) + ">";
    }
    return out;
}

using Styles = std::map<std::string, std::string>;

// The id of [node] without the fragment prefix crengine gives EPUB ids, empty for none.
std::string idOf(ldomNode* node) {
    std::string id = UnicodeToUtf8(node->getAttributeValue(attr_id)).c_str();
    const size_t space = id.rfind(' ');
    return space == std::string::npos ? id : id.substr(space + 1);
}

void collect(ldomNode* node, Styles& out) {
    if (!node->isElement()) return;
    const std::string id = idOf(node);
    css_style_ref_t s = node->getStyle();
    if (node->getNodeId() == el_pseudoElem) {
        ldomNode* owner = node->getParentNode();
        while (owner && idOf(owner).empty()) owner = owner->getParentNode();
        const char* which = node->hasAttribute(attr_Before) ? "::before"
                          : node->hasAttribute(attr_After) ? "::after"
                          : node->hasAttribute(attr_FirstLetter) ? "::first-letter" : "::first-line";
        const std::string key = (owner ? idOf(owner) : std::string("?")) + which;
        if (!s.isNull()) {
            out[key + " content"] = contentText(s->content);
            out[key + " color"] = len(s->color);
            out[key + " display"] = s->display == css_d_none ? "none" : "shown";
        }
    }
    if (!id.empty() && !s.isNull()) {
        auto row = [&](const char* prop, const std::string& v) { out[id + " " + prop] = v; };
        row("text-indent", len(s->text_indent));
        row("text-align", alignName(s->text_align));
        row("margin-left", len(s->margin[0]));
        row("margin-right", len(s->margin[1]));
        row("margin-top", len(s->margin[2]));
        row("margin-bottom", len(s->margin[3]));
        row("padding-left", len(s->padding[0]));
        row("line-height", len(s->line_height));
        row("font-family", s->font_name.c_str());
        row("font-style", s->font_style == css_fs_italic ? "italic" : s->font_style == css_fs_normal ? "normal" : "other");
        row("color", len(s->color));
        row("letter-spacing", len(s->letter_spacing));
        row("font-size", len(s->font_size));
        row("first-letter", node->hasAttribute(attr_HasFirstLetter) ? "yes" : "no");
        row("first-line", node->hasAttribute(attr_HasFirstLine) ? "yes" : "no");
        LVFontRef f = node->getFont();
        if (!f.isNull()) row("face", f->getTypeFace().c_str());
    }
    for (int i = 0; i < (int)node->getChildCount(); i++) collect(node->getChildNode(i), out);
}

class Engine {
public:
    explicit Engine(bool cacheable = true) {
        view = new LVDocView(-1, true);
        view->setViewMode(DVM_PAGES, -1);
        view->Resize(WIDTH, HEIGHT);
        view->setPageHeaderInfo(0);
        view->setMaxFontSize(perigee::MAX_FONT_PX);
        CRPropRef props = LVCreatePropsContainer();
        props->setInt(PROP_REQUESTED_DOM_VERSION, gDOMVersionCurrent);
        props->setInt(PROP_RENDER_BLOCK_RENDERING_FLAGS, BLOCK_RENDERING_FULL_FEATURED);
        props->setInt(PROP_FONT_HINTING, 2);
        props->setInt(PROP_FONT_KERNING, 2);
        props->setInt(PROP_TEXTLANG_EMBEDDED_LANGS_ENABLED, 1);
        props->setInt(PROP_FORMAT_MIN_SPACE_CONDENSING_PERCENT, 75);
        props->setInt(PROP_MIN_FILE_SIZE_TO_CACHE, cacheable ? 30000 : INT_MAX);
        view->propsApply(props);
    }
    ~Engine() {
        if (view->getDocument()) view->close();
        delete view;
    }
    Engine(const Engine&) = delete;
    Engine& operator=(const Engine&) = delete;

    static const int WIDTH = 720;
    static const int HEIGHT = 1280;
    static lString8 formatCss;
    LVDocView* view;
    std::string lastStylesheet;

    // NativeEngine.applySettings with [owner] as EngineSettings.ownerCss.
    void apply(const std::string& owner) {
        const bool loaded = view->getDocument() != nullptr;
        ldomXPointer position = loaded ? view->getBookmark() : ldomXPointer();
        CRPropRef props = LVCreatePropsContainer();
        props->setString(PROP_FONT_FACE, FACE);
        props->setInt(PROP_FONT_BASE_WEIGHT, WEIGHT);
        props->setInt(PROP_INTERLINE_SPACE, 100);
        props->setInt(PROP_FOOTNOTES, 1);
        props->setInt(PROP_TEXTLANG_HYPHENATION_ENABLED, 0);
        props->setInt(PROP_FLOATING_PUNCTUATION, 1);
        props->setInt(PROP_LANDSCAPE_PAGES, 1);
        props->setInt(PROP_RENDER_DPI, 192);
        props->setInt(PROP_FONT_HINTING, 2);
        props->setInt(PROP_EMBEDDED_FONTS, 1);
        view->propsApply(props);
        view->setPageMargins(lvRect(24, 24, 24, 24));
        view->setVisiblePageCount(1, false);
        lString8 base = formatCss;
        base << "\r\n" << appCss().c_str();
        ldomDocument* doc = view->getDocument();
        const bool readersFace = !doc || perigee::drawnFace(doc).face == face_;
        base_ = base;
        owner_ = lString8(owner.c_str());
        face_ = lString8(FACE);
        applySize(perigee::sizeOfDrawnFace(readersFace ? nullptr : doc, face_, FONT_SIZE, WEIGHT));
        if (loaded) {
            view->Render();
            if (applySize(perigee::sizeOfDrawnFace(doc, face_, FONT_SIZE, WEIGHT))) view->Render();
            if (!position.isNull()) view->goToBookmark(position);
        }
    }

    // NativeEngine.load.
    bool load(const std::string& path) {
        if (!view->LoadDocument(path.c_str(), false) || !view->getDocument()) return false;
        if (!face_.empty()) applySize(perigee::sizeOfDrawnFace(view->getDocument(), face_, FONT_SIZE, WEIGHT));
        view->Render();
        if (!face_.empty() && applySize(perigee::sizeOfDrawnFace(view->getDocument(), face_, FONT_SIZE, WEIGHT))) view->Render();
        return true;
    }

    Styles styles() {
        Styles out;
        collect(view->getDocument()->getRootNode(), out);
        return out;
    }

    std::string sizeCss() const { return applied_.css.c_str(); }

private:
    lString8 base_, owner_, face_;
    perigee::FaceSize applied_{0, lString8(), lString8()};

    // applySize of engine_jni.cpp.
    bool applySize(const perigee::FaceSize& size) {
        const bool changed = size.em != applied_.em || size.css != applied_.css;
        if (size.em != applied_.em) view->setFontSize(size.em);
        applied_ = size;
        const lString8 css = perigee::composeStylesheet(base_, size.css, owner_);
        if (lastStylesheet != css.c_str()) {
            view->setStyleSheet(css, false);
            lastStylesheet = css.c_str();
        }
        return changed;
    }
};
lString8 Engine::formatCss;

std::string gBooks, gScratch;
std::vector<std::string> gArgs;  // argv of this run, for the process cache() starts

// The book of the check, and its reference: the same bytes under another file name. The engine keys its cache
// by the file's name and CRC, so a reference opening never reads what the openings under check wrote.
std::string book(const char* name) { return gBooks + "/" + name; }
std::string reference(const char* name) { return gBooks + "/reference-" + name; }

// The pixels of [page] as the view draws it.
std::vector<lUInt8> drawn(LVDocView* view, int page) {
    view->goToPage(page);
    LVGrayDrawBuf buf(Engine::WIDTH, Engine::HEIGHT, 8);
    view->Draw(buf, false);
    std::vector<lUInt8> pixels;
    for (int y = 0; y < buf.GetHeight(); y++) pixels.insert(pixels.end(), buf.GetScanLine(y), buf.GetScanLine(y) + buf.GetWidth());
    return pixels;
}

// The page that holds the matrix's pseudo-elements.
int pseudoPage(LVDocView* view) {
    for (int i = 0; i < view->getPageCount(); i++)
        if (view->getPageText(false, i).pos("First letter the book hides") >= 0) return i;
    return -1;
}

// What an opening gives: the styles, the first page and the page of the pseudo-elements as drawn, whether
// the engine found a cache file for the book, took its layout from it as it was, and marks its DOM stale.
struct Opening {
    Styles styles;
    std::vector<lUInt8> firstPage, pseudoPage;
    bool readCache = false, hit = false, stale = false, refusedVersion = false;
};

Opening openBook(const std::string& path, const std::string& owner, bool cacheable, bool save) {
    gLog->lines.clear();
    Engine e(cacheable);
    e.apply(owner);
    if (!e.load(path)) { std::fprintf(stderr, "owner-css: cannot open %s\n", path.c_str()); std::exit(100); }
    Opening o;
    o.readCache = gLog->saw("cache file found");
    o.refusedVersion = gLog->saw("magic doesn't match");
    // The pages come from the cache file and the engine does not lay the book out again.
    o.hit = gLog->saw("pages read from cache file") && !gLog->saw("rendering context is changed");
    o.stale = e.view->getDocument()->isBuiltDomStale();
    o.styles = e.styles();
    o.firstPage = drawn(e.view, 0);
    const int page = pseudoPage(e.view);
    if (page >= 0) o.pseudoPage = drawn(e.view, page);
    // NativeEngine.saveCache.
    if (save) e.view->updateCache();
    return o;
}

int gReferences = 0;
std::vector<std::string> gReferencesRead;

// A first opening of the reference copy of [name], off the cache.
Opening fresh(const char* name, const std::string& owner) {
    Opening o = openBook(reference(name), owner, false, false);
    gReferences++;
    if (o.readCache) gReferencesRead.push_back(std::string(name) + " with " + std::to_string(owner.size()) + " bytes of owner CSS");
    return o;
}

// Lengths equal within the engine's 1/256 fixed point; zero lengths of any unit equal.
bool same(const std::string& got, const std::string& want) {
    if (got == want) return true;
    char* gEnd = nullptr;
    char* wEnd = nullptr;
    const double g = std::strtod(got.c_str(), &gEnd);
    const double w = std::strtod(want.c_str(), &wEnd);
    if (gEnd == got.c_str() || wEnd == want.c_str()) return false;
    if (g == 0 && w == 0) return true;
    return std::string(gEnd) == wEnd && std::fabs(g - w) < 0.01;
}

std::string at(const Styles& s, const std::string& key) {
    auto it = s.find(key);
    return it == s.end() ? "(none)" : it->second;
}

// The first keys where [a] and [b] differ, for a failed comparison; empty when they are the same.
std::string differences(const Styles& a, const Styles& b, int shown = 4) {
    std::string out;
    int n = 0;
    std::set<std::string> keys;
    for (const auto& kv : a) keys.insert(kv.first);
    for (const auto& kv : b) keys.insert(kv.first);
    for (const auto& k : keys) {
        const std::string vx = at(a, k);
        const std::string vy = at(b, k);
        if (vx == vy) continue;
        if (n++ < shown) out += " [" + k + ": " + vx + " / " + vy + "]";
    }
    if (n > shown) out += " and " + std::to_string(n - shown) + " more";
    return out;
}

// The pseudo-elements of [s] and whether an element has them; [s] keeps the rest.
Styles pseudo(Styles& s) {
    Styles out;
    for (auto it = s.begin(); it != s.end();) {
        const std::string& k = it->first;
        const bool dom = k.find("::") != std::string::npos || k.find(" first-letter") != std::string::npos ||
                         k.find(" first-line") != std::string::npos;
        if (dom) {
            out.insert(*it);
            it = s.erase(it);
        } else ++it;
    }
    return out;
}

// Whether [got] differs from [want] only by pseudo-elements that earlier CSS made and that are hidden now.
// crengine creates a pseudo-element as a node of the document when a rule asks for one, and a later restyle
// does not remove it: it hides it and marks the DOM stale (isBuiltDomStale).
bool onlyHiddenLeftovers(const Styles& got, const Styles& want) {
    std::set<std::string> keys;
    for (const auto& kv : got) keys.insert(kv.first);
    for (const auto& kv : want) keys.insert(kv.first);
    for (const auto& k : keys) {
        if (at(got, k) == at(want, k)) continue;
        const std::string node = k.substr(0, k.find(' '));
        const std::string prop = k.substr(k.find(' ') + 1);
        std::string hidden;
        if (node.find("::") != std::string::npos && !want.count(k)) hidden = node + " display";  // a pseudo-element left
        else if ((prop == "first-letter" || prop == "first-line") && at(got, k) == "yes" && at(want, k) == "no")
            hidden = node + "::" + prop + " display";  // the element's mark of one
        else return false;
        if (at(got, hidden) != "none") return false;
    }
    return true;
}

// [got] against [want]: the styles of every element, and the pseudo-elements, of which the only difference
// allowed is hidden ones earlier CSS left in a DOM the engine marks stale.
void sameStyles(Styles got, Styles want, bool stale, const std::string& what) {
    const Styles gotPseudo = pseudo(got);
    const Styles wantPseudo = pseudo(want);
    check(got == want, what + ": the styles of a fresh opening" + differences(got, want));
    if (gotPseudo == wantPseudo) {
        check(true, what + ": and its pseudo-elements");
        return;
    }
    check(stale && onlyHiddenLeftovers(gotPseudo, wantPseudo),
          what + ": the only pseudo-elements more are hidden ones earlier CSS left, in a DOM the engine marks stale" +
              (stale ? "" : " (not stale)") + differences(gotPseudo, wantPseudo, 100));
}

struct Row {
    const char* key;
    const char* want;
    const char* why;
};

// Each row: the element and property, the value the contract gives it, what it is up against.
const Row MATRIX[] = {
    {"p_plain text-indent", "0em", "app p {1.2em !important}, book p {2em}"},
    {"p_plain margin-bottom", "0.6em", "app p {0.5em !important}"},
    {"p_plain text-align", "left", "app body, p, li {justify !important}"},
    {"p_plain face", "Literata", "app * {PT Serif !important}, book p {Roboto}"},
    {"p_plain margin-right", "0.3em", "book p {1em}, ordinary"},
    {"p_plain color", "#222222", "book p; the owner does not set it"},
    {"p_plain letter-spacing", "1px", "book p; the owner does not set it"},
    {"p_bookimp text-indent", "0em", "book p.bimp {4em !important} and the app"},
    {"p_bookimp margin-left", "0.2em", "book p.bimp {3em !important}"},
    {"p_bookimp text-align", "left", "book p.bimp {right !important} and the app"},
    {"p_inline text-indent", "0em", "style= 5em"},
    {"p_inline margin-left", "0.2em", "style= 2em"},
    {"p_inlineimp text-indent", "0em", "style= 6em !important"},
    {"p_inlineimp margin-left", "0.2em", "style= 4em !important"},
    {"p_owncls text-indent", "0.4em", "owner p.own 0.3em then p.own 0.4em, owner p 0"},
    {"p_ownimp text-indent", "0.5em", "owner p.own2 0.5em !important over owner #p_ownimp 0.7em"},
    {"p_ownimp2 text-indent", "0.9em", "owner p.own3 0.8em !important under owner #p_ownimp2 0.9em !important"},
    {"p_sh margin-left", "0.25em", "owner margin shorthand over owner p margin-left"},
    {"p_sh margin-top", "0.25em", "owner margin shorthand over app margin-top 0 !important"},
    {"p_sh margin-bottom", "0.9em", "owner longhand after the shorthand"},
    {"p_sh padding-left", "0.5em", "owner rule inside @media all"},
    {"p_sh2 margin-bottom", "0.25em", "owner shorthand after the longhand"},
    {"wrap line-height", "1.6num", "owner body {1.6} over the size rule body {line-height} of the app"},
    {"p_lh line-height", "1.7num", "book p.lh; the owner's body rule reaches it only by inheritance"},
    {"d_unmatched text-indent", "7em", "book only: no owner rule selects the div"},
    {"d_unmatched text-align", "right", "book only"},
    {"d_unmatched face", "PT Serif", "app *; no owner rule selects the div"},
    {"d_inherit text-align", "center", "owner #d_inherit"},
    {"d_inherit face", "Literata", "owner #d_inherit over app * {!important}"},
    {"s_inherit face", "PT Serif", "app * selects the span; the owner's family is on its div"},
    {"s_inherit color", "#00aa00", "inherited from owner #d_inherit; nothing sets it on the span"},
    {"p_iu1 text-align", "right", "owner text-align: inherit, from the book's div, over app justify !important"},
    {"p_iu2 margin-right", "0em", "owner margin-right: initial over owner p and book p"},
    {"p_iu3 margin-left", "0em", "owner margin-left: unset over book p.iu3 3em"},
    {"p_fn margin-bottom", "0.6em", "app [type~=footnote] p {0 !important}, more specific"},
    {"p_fn text-indent", "0em", "app p important"},
    {"p_ff face", "Roboto", "owner @font-face local(\"Roboto\") as \"Owner Alias\""},
    {"p_str color", "#0000ff", "owner rgb() function"},
    {"p_str::before content", "a;b}c{d\"e", "owner string with ; } { and an escaped quote"},
    {"p_sup text-indent", "0.11em", "owner @supports (display: block); @media print and an unknown @supports skipped"},
    {"p_ref::before content", "O", "book #p_ref::before {\"B\" !important}"},
    {"p_ba::after content", "A", "book p.ba::after {display: none !important}, owner display: inline"},
    {"p_oc::before content", "N", "a ::before only the owner asks for"},
    {"p_oi::before content", "I", "owner p.oi::before \"I\" !important over owner #p_oi::before \"J\""},
    {"p_fl first-letter", "yes", "book p.fl::first-letter {display: none !important}, owner display: inline"},
    {"p_fl::first-letter color", "#0000ff", "owner p.fl::first-letter color"},
    {"p_fline first-line", "yes", "book p.fline::first-line {display: none !important}, owner display: inline"},
    {"i_marker font-style", "italic", "the rule that carries the owner's hash, .perigee-owner-css:not(*), selects nothing"},
};

// Properties the owner's rules set, on every <p> or on the elements named; the rest must stay as they are.
bool ownerSets(const std::string& key) {
    const std::string id = key.substr(0, key.find(' '));
    const std::string prop = key.substr(key.find(' ') + 1);
    if (prop == "line-height" && id != "p_lh") return true;  // the owner's body rule, inherited
    // The owner's face for paragraphs is the face the book is drawn in, and face_size.h sets the em for it.
    if (prop == "font-size") return true;
    if (id.find("::") != std::string::npos || prop == "first-letter" || prop == "first-line") return true;
    static const std::set<std::string> onP{"text-indent", "margin-bottom", "text-align", "font-family", "face", "margin-left", "margin-right"};
    if (id.rfind("p_", 0) == 0 && onP.count(prop)) return true;
    static const std::set<std::string> named{
        "p_sh margin-top", "p_sh padding-left", "p_sh2 margin-top", "p_sh2 padding-left",
        "d_inherit text-align", "d_inherit font-family", "d_inherit face", "d_inherit color", "s_inherit color",
        "s_inherit text-align", "i_marker text-align", "i_marker text-indent",
        "p_str color"};
    return named.count(key) > 0;
}

void matrix() {
    const Styles none = fresh("matrix.epub", "").styles;
    const Styles owner = fresh("matrix.epub", OWNER).styles;
    for (const Row& r : MATRIX) {
        const std::string got = at(owner, r.key);
        check(same(got, r.want), std::string(r.key) + " = " + got + ", want " + r.want + " (" + r.why + ")");
    }
    // Without the owner's CSS the app's settings still hold, so the rows above test the owner's rules.
    check(at(none, "p_plain text-indent") == "1.19531em", "without owner CSS the app's indent: " + at(none, "p_plain text-indent"));
    check(at(none, "p_fn margin-bottom") == "0px", "without owner CSS the notes' spacing: " + at(none, "p_fn margin-bottom"));
    check(!same(at(none, "wrap line-height"), "1.6num"), "without owner CSS the size rule's line height: " + at(none, "wrap line-height"));
    check(at(none, "p_fl first-letter") == "no", "without owner CSS the book hides the first letter");
    int kept = 0, changed = 0;
    for (const auto& kv : none) {
        if (ownerSets(kv.first)) continue;
        if (at(owner, kv.first) == kv.second) kept++;
        else {
            changed++;
            check(false, "untouched " + kv.first + " = " + at(owner, kv.first) + ", was " + kv.second);
        }
    }
    check(changed == 0 && kept > 100, "every property the owner does not set keeps its value: " + std::to_string(kept) + " kept");
}

// The stylesheet text: no marker without owner CSS, and the owner's text after the size rule unchanged.
void composition() {
    const lString8 base("BASE"), size("body { line-height: 1.2; }\r\n");
    check(perigee::composeStylesheet(base, size, lString8()) == "BASE\r\nbody { line-height: 1.2; }\r\n",
          "no owner CSS: the stylesheet of before, no marker");
    const lString8 owner(OWNER);
    const lString8 composed = perigee::composeStylesheet(base, size, owner);
    const int at = composed.pos(perigee::OWNER_CSS_MARKER);
    check(at > composed.pos("line-height"), "the marker comes after the size rule");
    check(composed.substr(at + (int)std::strlen(perigee::OWNER_CSS_MARKER)) == owner, "the owner's text follows it byte for byte");
    Engine e;
    e.apply(OWNER);
    check(!e.sizeCss().empty(), "the reader's face gets a size rule to come before the owner's CSS: " + e.sizeCss());
}

// An open book: each change keeps the place and shows what a fresh opening at that place shows.
void live() {
    Engine e;
    e.apply("");
    if (!e.load(book("large.epub"))) { check(false, "open large.epub"); return; }
    e.view->goToPage(e.view->getPageCount() / 2);
    const std::string alias = replaced(OWNER, "local(\"Roboto\")", "local(\"Literata\")");
    const std::vector<std::pair<const char*, std::string>> steps{
        {"save", OWNER}, {"descriptor change", alias}, {"change back", OWNER}, {"reset", ""}};
    for (const auto& step : steps) {
        ldomXPointer place = e.view->getBookmark();
        const lString32 where = place.toString();
        gLog->lines.clear();
        e.apply(step.second);
        const int page = e.view->getCurPage();
        check(page == e.view->getBookmarkPage(place), std::string(step.first) + ": the page shown holds the place of before");
        check(gLog->saw("Stylesheet hash doesn't match"), std::string(step.first) + ": the engine sees a new stylesheet");
        Engine other(false);
        other.apply(step.second);
        other.load(reference("large.epub"));
        gReferences++;
        other.view->goToBookmark(other.view->getDocument()->createXPointer(where));
        check(other.view->getCurPage() == page,
              std::string(step.first) + ": page " + std::to_string(page) + ", a fresh opening shows " + std::to_string(other.view->getCurPage()));
        sameStyles(e.styles(), other.styles(), e.view->getDocument()->isBuiltDomStale(), step.first);
    }
    check(at(e.styles(), "p_plain text-indent") == "1.19531em", "reset: the app's indent again: " + at(e.styles(), "p_plain text-indent"));
    // The page of the pseudo-elements the owner's CSS made: hidden once it is reset, they draw nothing, and the
    // first letter one held is drawn with its word.
    const Opening none = fresh("large.epub", "");
    const Opening owned = fresh("large.epub", OWNER);
    check(!none.pseudoPage.empty() && owned.pseudoPage != none.pseudoPage, "with the owner's CSS the page of the pseudo-elements is drawn otherwise");
    const int page = pseudoPage(e.view);
    check(page >= 0 && drawn(e.view, page) == none.pseudoPage, "reset: that page is drawn as a fresh opening draws it");
}

// The descriptor of an @font-face alone changes the stylesheet the engine keeps and the face drawn.
void fontFace() {
    const std::string literata = replaced(OWNER, "local(\"Roboto\")", "local(\"Literata\")");
    const Styles fromLiterata = fresh("matrix.epub", literata).styles;
    check(at(fromLiterata, "p_ff face") == "Literata", "@font-face src: local(\"Literata\") draws Literata");
    Engine e;
    e.apply(OWNER);
    e.load(book("matrix.epub"));
    gLog->lines.clear();
    e.apply(literata);
    check(gLog->saw("Stylesheet hash doesn't match"), "a descriptor-only edit changes the stylesheet hash");
    check(at(e.styles(), "p_ff face") == at(fromLiterata, "p_ff face"),
          "the open book draws what a fresh opening draws after the descriptor edit: " + at(e.styles(), "p_ff face"));
    e.apply(OWNER);
    check(at(e.styles(), "p_ff face") == "Roboto", "and back: " + at(e.styles(), "p_ff face"));
}

// An @font-face of the owner's that gives the reader's face, which the app's rule names on every element, the
// name of another face. Taken out, the book is drawn as a fresh opening without it draws it.
const char* ALIAS = "@font-face { font-family: \"PT Serif\"; src: local(\"Literata\"); }\n";

void aliasRemoved() {
    const Opening none = fresh("matrix.epub", "");
    // A fresh opening resolves the reader's face before the alias reaches it; a restyle takes the alias. Both
    // are the engine's own way with an alias of a face the app registers, before #150 as after it.
    std::printf("INFO  a fresh opening with the @font-face draws %s\n", at(fresh("matrix.epub", ALIAS).styles, "p_plain face").c_str());
    Engine e;
    e.apply("");
    e.load(book("matrix.epub"));
    e.apply(ALIAS);
    check(at(e.styles(), "p_plain face") == "Literata" && drawn(e.view, 0) != none.firstPage,
          "the owner's @font-face on the open book draws Literata under the reader's face: " + at(e.styles(), "p_plain face"));
    e.apply("");
    check(at(e.styles(), "p_plain face") == at(none.styles, "p_plain face"),
          "the owner's @font-face taken out on the open book: " + at(e.styles(), "p_plain face") + ", a fresh opening " +
              at(none.styles, "p_plain face"));
    sameStyles(e.styles(), none.styles, e.view->getDocument()->isBuiltDomStale(), "the @font-face taken out");
    check(drawn(e.view, 0) == none.firstPage, "and the first page is drawn as a fresh opening draws it");
    e.apply(ALIAS);
    check(at(e.styles(), "p_plain face") == "Literata", "put back: " + at(e.styles(), "p_plain face"));
}

bool cacheWritten() {
    bool index = false, file = false;
    for (const auto& entry : fs::directory_iterator(gScratch + "/cache")) {
        const std::string name = entry.path().filename().string();
        if (name == "cr3cache.inx") index = true;
        if (name.size() > 4 && name.compare(name.size() - 4, 4, ".cr3") == 0 && entry.file_size() > 0) file = true;
    }
    return index && file;
}

// Owner's CSS a child process is started with, by name.
std::string ownerNamed(const std::string& name) {
    if (name == "owner") return OWNER;
    if (name == "alias") return ALIAS;
    return "";
}

void writeOpening(const std::string& path, const Opening& o) {
    FILE* f = std::fopen(path.c_str(), "wb");
    for (const auto& kv : o.styles) std::fprintf(f, "%s\t%s\n", kv.first.c_str(), kv.second.c_str());
    std::fprintf(f, "#stale\t%d\n#hit\t%d\n#refused\t%d\n", o.stale ? 1 : 0, o.hit ? 1 : 0, o.refusedVersion ? 1 : 0);
    std::fclose(f);
    FILE* p = std::fopen((path + ".pixels").c_str(), "wb");
    std::fwrite(o.firstPage.data(), 1, o.firstPage.size(), p);
    std::fclose(p);
}

Opening readOpening(const std::string& path) {
    Opening o;
    if (FILE* f = std::fopen(path.c_str(), "rb")) {
        char line[4096];
        while (std::fgets(line, sizeof line, f)) {
            std::string l(line);
            if (!l.empty() && l.back() == '\n') l.pop_back();
            const size_t tab = l.find('\t');
            if (tab == std::string::npos) continue;
            const std::string k = l.substr(0, tab), v = l.substr(tab + 1);
            if (k == "#stale") o.stale = v == "1";
            else if (k == "#hit") o.hit = v == "1";
            else if (k == "#refused") o.refusedVersion = v == "1";
            else o.styles[k] = v;
        }
        std::fclose(f);
    }
    if (FILE* p = std::fopen((path + ".pixels").c_str(), "rb")) {
        lUInt8 buf[65536];
        for (size_t n; (n = std::fread(buf, 1, sizeof buf, p)) > 0;) o.firstPage.insert(o.firstPage.end(), buf, buf + n);
        std::fclose(p);
    }
    return o;
}

// Another process, as after a restart of the app: this program again, by argv, opening large.epub through
// the cache with the owner's CSS [owner]. It exits 0 when the cache was [hit] or not as expected.
Opening another(const std::string& owner, bool hit, const std::string& what, const std::string& cacheDir = "") {
    const std::string out = gScratch + "/child " + owner + ".tsv";
    std::vector<std::string> args = gArgs;
    for (const std::string& a : {std::string("--child"), out, std::string("--child-owner"), owner, std::string("--child-hit"), std::string(hit ? "yes" : "no")})
        args.push_back(a);
    if (!cacheDir.empty()) {
        args.push_back("--cache-dir");
        args.push_back(cacheDir);
    }
    std::vector<char*> argv;
    for (std::string& a : args) argv.push_back(&a[0]);
    argv.push_back(nullptr);
    pid_t pid = 0;
    int status = -1;
    const int spawned = posix_spawnp(&pid, argv[0], nullptr, nullptr, argv.data(), environ);
    if (spawned == 0) waitpid(pid, &status, 0);
    check(spawned == 0 && WIFEXITED(status) && WEXITSTATUS(status) == 0,
          what + ": another process opens the book, " + (hit ? "from the cache" : "laid out again over it") + ": status " + std::to_string(status));
    return readOpening(out);
}

int child(const std::string& out, const std::string& owner, bool expectHit) {
    const Opening o = openBook(book("large.epub"), ownerNamed(owner), true, true);
    writeOpening(out, o);
    std::printf("%s  child: %s\n", o.hit == expectHit ? "PASS" : "FAIL", o.hit ? "from the cache" : "laid out again");
    return o.hit == expectHit ? 0 : 1;
}

void cache() {
    ldomDocCache::clear();
    const std::string tested = book("large.epub");
    check(fs::file_size(tested) > 30000, "large.epub is over the 30000 bytes the engine caches from: " + std::to_string(fs::file_size(tested)));
    const Opening plain = fresh("large.epub", OWNER);
    const Opening none = fresh("large.epub", "");
    const Opening first = openBook(tested, OWNER, true, true);
    check(!first.readCache, "first opening: no cache yet");
    check(cacheWritten(), "the cache index and a cache file are written");
    sameStyles(first.styles, plain.styles, first.stale, "first opening");
    const Opening again = openBook(tested, OWNER, true, true);
    check(again.hit, "same owner CSS: the layout comes from the cache");
    sameStyles(again.styles, plain.styles, again.stale, "from the cache");
    check(again.pseudoPage == plain.pseudoPage, "from the cache: the page of the pseudo-elements as a fresh opening draws it");
    const std::string other = replaced(OWNER, "text-indent: 0;", "text-indent: 0.33em;");
    const Opening changed = openBook(tested, other, true, true);
    check(changed.readCache && !changed.hit, "changed owner CSS: the cache is read, its layout not taken as it is");
    sameStyles(changed.styles, fresh("large.epub", other).styles, changed.stale, "changed owner CSS over the cache");
    const Opening removed = openBook(tested, "", true, true);
    check(removed.readCache && !removed.hit, "owner CSS removed: the cache is read, its layout not taken as it is");
    sameStyles(removed.styles, none.styles, removed.stale, "owner CSS removed over the cache");
    check(removed.pseudoPage == none.pseudoPage, "owner CSS removed over the cache: the page of the pseudo-elements as a fresh opening draws it");
    check(removed.firstPage == none.firstPage, "and the first page");
    openBook(tested, OWNER, true, true);
    const Opening child = another("owner", true, "owner CSS");
    sameStyles(child.styles, plain.styles, child.stale, "owner CSS in another process");
    check(child.firstPage == plain.firstPage, "owner CSS in another process: the first page as a fresh opening draws it");
    // The owner's @font-face, then none, over a cache made with it, in this process and in another one.
    openBook(tested, ALIAS, true, true);
    const Opening aliased = openBook(tested, ALIAS, true, true);
    check(aliased.hit, "the owner's @font-face: the layout comes from the cache");
    std::printf("INFO  the @font-face from the cache draws %s\n", at(aliased.styles, "p_plain face").c_str());
    const Opening unaliased = openBook(tested, "", true, true);
    check(unaliased.readCache && !unaliased.hit, "the @font-face taken out: the cache made with it is read, its layout not taken");
    check(at(unaliased.styles, "p_plain face") == at(none.styles, "p_plain face"),
          "the @font-face taken out over a cache made with it: " + at(unaliased.styles, "p_plain face") + ", a fresh opening " +
              at(none.styles, "p_plain face"));
    sameStyles(unaliased.styles, none.styles, unaliased.stale, "the @font-face taken out over the cache");
    check(unaliased.firstPage == none.firstPage, "the @font-face taken out over the cache: the first page as a fresh opening draws it");
    openBook(tested, ALIAS, true, true);
    const Opening childNone = another("none", false, "no owner CSS over a cache made with the @font-face");
    check(at(childNone.styles, "p_plain face") == at(none.styles, "p_plain face"),
          "the @font-face taken out, in another process: " + at(childNone.styles, "p_plain face"));
    sameStyles(childNone.styles, none.styles, childNone.stale, "the @font-face taken out, in another process");
    check(childNone.firstPage == none.firstPage, "the @font-face taken out, in another process: the first page as a fresh opening draws it");
    openBook(tested, ALIAS, true, true);
    const Opening childAlias = another("alias", true, "the @font-face, reopened");
    sameStyles(childAlias.styles, aliased.styles, childAlias.stale, "the @font-face from the cache in another process, against this one");
    check(childAlias.firstPage == aliased.firstPage, "the @font-face from the cache in another process: the first page as in this one");
}

// The faces the document lists, as the layout cache saves them: source, family, fragment.
std::string fontList(LVDocView* view) {
    std::string out;
    LVEmbeddedFontList& list = view->getDocument()->getEmbeddedFontList();
    for (int i = 0; i < list.length(); i++) {
        LVEmbeddedFontDef* d = list.get(i);
        out += "[" + std::string(UnicodeToUtf8(d->getUrl()).c_str()) + " as " + d->getFace().c_str() + ", fragment " +
               std::to_string(d->getDocFragmentIdx()) + (d->getIsLocal() ? ", local" : "") + "]";
    }
    return out;
}

// The entries of fontList() once each, sorted: reading a cache adds its list to the entries the app's rules
// listed before it, so an entry of the app's rules is there twice, with or without owner CSS.
std::string fontSet(LVDocView* view) {
    std::set<std::string> entries;
    LVEmbeddedFontList& list = view->getDocument()->getEmbeddedFontList();
    for (int i = 0; i < list.length(); i++) {
        LVEmbeddedFontDef* d = list.get(i);
        entries.insert(std::string(UnicodeToUtf8(d->getUrl()).c_str()) + " as " + d->getFace().c_str() + ", fragment " +
                       std::to_string(d->getDocFragmentIdx()));
    }
    std::string out;
    for (const auto& e : entries) out += "[" + e + "]";
    return out;
}

// fonts.fb2 lists its "Book Alias" under local("Literata") and no fragment. An owner's @font-face of the same
// source is listed under the same key, and the list changes such an entry in place: the book's entry must come
// out of the owner's sheet as it went in. (The engine parses the book's CSS again on every restyle, which
// lists the book's face again too.)
const char* BOOK_FACE = "p { font-family: \"Book Alias\"; }\n";
const char* SAME_SOURCE = "@font-face { font-family: \"Owner Alias\"; src: local(\"Literata\"); }\np { font-family: \"Book Alias\"; }\n";

void sameSource() {
    const Opening ref = fresh("fonts.fb2", BOOK_FACE);
    check(at(ref.styles, "f_1 face") == "Literata", "fonts.fb2: the book's @font-face draws Literata as \"Book Alias\": " + at(ref.styles, "f_1 face"));
    {
        Engine e;
        e.apply(BOOK_FACE);
        e.load(book("fonts.fb2"));
        const std::string listed = fontList(e.view);
        check(listed.find("Literata as Book Alias, fragment -1, local") != std::string::npos, "the document lists the book's face: " + listed);
        e.apply(SAME_SOURCE);
        check(fontList(e.view) == listed, "an owner's @font-face of the same source leaves the book's entry as it was: " + fontList(e.view));
        e.apply(BOOK_FACE);
        check(at(e.styles(), "f_1 face") == "Literata", "the owner's @font-face taken out: the book's face drawn again: " + at(e.styles(), "f_1 face"));
        sameStyles(e.styles(), ref.styles, e.view->getDocument()->isBuiltDomStale(), "fonts.fb2, the owner's @font-face taken out");
        check(drawn(e.view, 0) == ref.firstPage, "fonts.fb2, the owner's @font-face taken out: the first page as a fresh opening draws it");
    }
}

// A face of the app's rules, which setStyleSheet() parses before the owner's sheet and nothing parses again
// until the next stylesheet, under the same source as an owner's @font-face. The list must keep the app's
// entry as it was; else the layout cache saved after it lists the owner's face, and an opening over that cache
// without owner CSS registers it again.
const char* APP_FACE = "@font-face { font-family: \"App Alias\"; src: local(\"Roboto\"); }\n";
const char* OWNER_SAME_AS_APP = "@font-face { font-family: \"Owner Alias\"; src: local(\"Roboto\"); }\n";

void appSource() {
    gAppExtra = APP_FACE;
    {
        Engine e;
        e.apply("");
        e.load(book("matrix.epub"));
        const std::string listed = fontList(e.view);
        check(listed.find("Roboto as App Alias, fragment -1, local") != std::string::npos, "the document lists the app's face: " + listed);
        e.apply(OWNER_SAME_AS_APP);
        check(fontList(e.view) == listed, "an owner's @font-face of the app's source leaves the app's entry as it was: " + fontList(e.view));
        e.apply("");
        check(fontList(e.view) == listed, "and the owner's @font-face taken out: " + fontList(e.view));
    }
    std::string freshList;
    {
        Engine ref(false);
        ref.apply("");
        ref.load(reference("large.epub"));
        gReferences++;
        freshList = fontSet(ref.view);
    }
    openBook(book("large.epub"), "", true, true);
    {
        Engine e;
        e.apply("");
        e.load(book("large.epub"));
        e.apply(OWNER_SAME_AS_APP);
        e.view->updateCache();
    }
    gLog->lines.clear();
    Engine e;
    e.apply("");
    e.load(book("large.epub"));
    check(gLog->saw("cache file found"), "large.epub reopened without owner CSS over the cache saved with the owner's @font-face");
    check(fontSet(e.view) == freshList, "the faces it lists are a fresh opening's: " + fontSet(e.view) + ", fresh " + freshList);
    gAppExtra.clear();
}

// A cache an engine without 0003 wrote for large.epub with the owner's @font-face in it, which that engine
// lists among the book's faces. Opened by this engine in another process without owner CSS, the book must be
// drawn as a fresh opening draws it: the cache is refused for its format version and the book parsed again.
void legacyCache(const std::string& written) {
    if (written.empty()) {
        std::printf("INFO  no --legacy-cache: the cache of an engine without 0003 is not checked\n");
        return;
    }
    const std::string copy = gScratch + "/legacy cache";
    fs::remove_all(copy);
    fs::copy(written, copy, fs::copy_options::recursive);
    bool cr3 = false;
    for (const auto& entry : fs::directory_iterator(copy)) cr3 = cr3 || entry.path().extension() == ".cr3";
    check(cr3, "the cache of the engine without 0003 holds a cache file");
    const Opening none = fresh("large.epub", "");
    const Opening child = another("none", false, "the cache of the engine without 0003", copy);
    check(child.refusedVersion, "that cache is refused for its format version");
    check(at(child.styles, "p_plain face") == at(none.styles, "p_plain face"),
          "and the book is drawn in the face of a fresh opening: " + at(child.styles, "p_plain face"));
    sameStyles(child.styles, none.styles, child.stale, "over the cache of the engine without 0003");
    check(child.firstPage == none.firstPage, "over the cache of the engine without 0003: the first page as a fresh opening draws it");
}

// Several documents: each has the sheet of its own settings, and none is left behind once they close.
void documents() {
    const std::string matrixBook = book("matrix.epub");
    const int before = ownerSheets();
    check(before == 0, "no owner's sheet before any book: " + std::to_string(before));
    {
        auto a = std::make_unique<Engine>();
        Engine b;
        a->apply(OWNER);
        b.apply("p { text-indent: 0.33em; }");
        a->load(matrixBook);
        b.load(matrixBook);
        a->apply(OWNER);
        check(ownerSheets() == 2, "two books, two owner's sheets: " + std::to_string(ownerSheets()));
        check(at(a->styles(), "p_plain text-indent") == "0px" && at(b.styles(), "p_plain text-indent") == "0.328125em",
              "each book has the owner's CSS of its own settings: " + at(a->styles(), "p_plain text-indent") + ", " +
                  at(b.styles(), "p_plain text-indent"));
        // LVDocView::close() puts an empty document in the book's place, which takes the stylesheet the view
        // keeps: the book's sheet goes with the book, and the view holds one until it goes too.
        a->view->close();
        check(ownerSheets() == 2, "a closed book: its sheet gone, one for the view's empty document: " + std::to_string(ownerSheets()));
        a.reset();
        check(ownerSheets() == 1, "the view destroyed, its sheet too: " + std::to_string(ownerSheets()));
        Engine c;
        c.apply("");
        c.load(matrixBook);
        check(ownerSheets() == 1, "a book without owner CSS gets no sheet: " + std::to_string(ownerSheets()));
        check(at(c.styles(), "p_plain text-indent") == "1.19531em", "and has the app's styles: " + at(c.styles(), "p_plain text-indent"));
        // The spare view of a book open twice, and the view describe() reads a book's metadata with.
        Engine spare(false);
        spare.apply(OWNER);
        spare.load(book("large.epub"));
        check(ownerSheets() == 2, "the spare view has its own: " + std::to_string(ownerSheets()));
        LVDocView meta(-1, true);
        meta.LoadDocument(book("large.epub").c_str(), true);
        meta.close();
        check(ownerSheets() == 2, "the metadata view adds none: " + std::to_string(ownerSheets()));
    }
    check(ownerSheets() == 0, "all closed, no sheet left: " + std::to_string(ownerSheets()));
}

}  // namespace

int main(int argc, char** argv) {
    std::string data, fonts, childOut, childOwner, childHit, cacheDir, legacy;
    gArgs.push_back(argv[0]);
    for (int i = 1; i < argc; i++) {
        const std::string a = argv[i];
        if (i + 1 >= argc) { std::fprintf(stderr, "owner-css: %s wants a value\n", a.c_str()); return 100; }
        const std::string v = argv[++i];
        if (a == "--data") data = v;
        else if (a == "--fonts") fonts = v;
        else if (a == "--books") gBooks = v;
        else if (a == "--scratch") gScratch = v;
        else if (a == "--child") { childOut = v; continue; }
        else if (a == "--child-owner") { childOwner = v; continue; }
        else if (a == "--child-hit") { childHit = v; continue; }
        else if (a == "--cache-dir") { cacheDir = v; continue; }
        else if (a == "--legacy-cache") legacy = v;
        else { std::fprintf(stderr, "owner-css: unknown %s\n", a.c_str()); return 100; }
        gArgs.push_back(a);
        gArgs.push_back(v);
    }
    if (data.empty() || fonts.empty() || gBooks.empty() || gScratch.empty()) {
        std::fprintf(stderr, "usage: owner-css --data DIR --fonts DIR --books DIR --scratch DIR\n");
        return 100;
    }
    gLog = new Log;
    CRLog::setLogger(gLog);
    CRLog::setLogLevel(CRLog::LL_INFO);
    InitFontManager(lString8());
    for (const char* f : {"Roboto-Variable.ttf", "PTSerif-Regular.ttf", "Literata-Variable.ttf"})
        if (!fontMan->RegisterFont(lString8((fonts + "/" + f).c_str()))) { std::fprintf(stderr, "owner-css: font %s\n", f); return 100; }
    if (!LVLoadStylesheetFile(Utf8ToUnicode((data + "/epub.css").c_str()), Engine::formatCss)) {
        std::fprintf(stderr, "owner-css: no epub.css in %s\n", data.c_str());
        return 100;
    }
    if (cacheDir.empty()) cacheDir = gScratch + "/cache";
    if (childOut.empty()) fs::remove_all(cacheDir);
    fs::create_directories(cacheDir);
    ldomDocCache::init(Utf8ToUnicode(cacheDir.c_str()), 64 * 1024 * 1024);
    if (!childOut.empty()) return child(childOut, childOwner, childHit == "yes");

    check(ownerSheets() >= 0, "the engine has the owner's layer (patches/0003-owner-css-layer.patch)");
    composition();
    matrix();
    live();
    fontFace();
    aliasRemoved();
    documents();
    cache();
    sameSource();
    appSource();
    legacyCache(legacy);
    check(gReferences > 10 && gReferencesRead.empty(),
          "no reference opening read a cache: " + std::to_string(gReferences) + " openings" +
              (gReferencesRead.empty() ? "" : ", read by " + gReferencesRead.front()));
    std::printf("%d FAIL\n", failures);
    return failures;
}
