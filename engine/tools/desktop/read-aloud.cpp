#include "read_aloud_span.h"
#include "lvdocview.h"
#include "lvfntman.h"
#include "lvrend.h"
#include "hyphman.h"
#include <climits>
#include <cstdio>
#include <cstdlib>
#include <vector>

static void require(bool condition, const char* label) {
    if (!condition) { std::fprintf(stderr, "FAIL: %s\n", label); std::exit(1); }
    std::printf("PASS: %s\n", label);
}

int main(int argc, char** argv) {
    if (argc != 4) return 2;
    InitFontManager(lString8());
    HyphMan::initDictionaries(lString32());
    require(fontMan->RegisterFont(lString8(argv[1])), "fixture face installed");
    LVDocView view(-1, true);
    view.setViewMode(DVM_PAGES, -1);
    view.Resize(500, 300);
    view.setPageHeaderInfo(0);
    view.setVisiblePageCount(2, false);
    auto props = LVCreatePropsContainer();
    props->setInt(PROP_REQUESTED_DOM_VERSION, gDOMVersionCurrent);
    props->setInt(PROP_RENDER_BLOCK_RENDERING_FLAGS, BLOCK_RENDERING_FULL_FEATURED);
    props->setInt(PROP_MIN_FILE_SIZE_TO_CACHE, INT_MAX);
    props->setInt(PROP_FOOTNOTES, 1);
    props->setString(PROP_FONT_FACE, "Roboto");
    view.propsApply(props);
    lString8 css;
    require(LVLoadStylesheetFile(Utf8ToUnicode(argv[2]), css), "FB2 stylesheet loaded");
    view.setStyleSheet(css, false);
    view.setFontSize(30);
    require(view.LoadDocument(argv[3], false) && view.getDocument(), "FB2 fixture loaded");
    view.Render();
    auto* doc = view.getDocument();
    const auto cover = view.getPageBookmark(0);
    require(!cover.isNull() && cover.getNode()->isElement(), "fixture starts with cover element, not prose");
    const auto first = perigee::readAloudSpan(cover, 2000);
    require(first.text == U"First chapter", "the cover bookmark starts at the first chapter heading");
    auto next = perigee::readAloudSpan(first.next, 2000);
    auto paragraph = next;
    const lString32 expected(U"Начало — это 🌔; латиница and Кириллица. 1 Конец абзаца.");
    require(paragraph.text == expected, "inline fragments and punctuation retain their source adjacency");
    require(doc->createXPointer(next.start.toString()).toString() == next.start.toString(), "start pointer round-trips");
    auto after = perigee::readAloudSpan(next.next, 2000);
    require(after.text == U"Repetition. Repetition.", "repeated prose is not deduplicated");
    after = perigee::readAloudSpan(after.next, 2000);
    require(after.text == U"Second chapter", "walk reaches the following chapter without using page indexes");
    after = perigee::readAloudSpan(after.next, 2000);
    require(after.text == U"Последний абзац. Final tail.", "final main paragraph is spoken in full");
    require(after.next.isNull(), "detached note body is skipped and end terminates");
    next = perigee::readAloudSpan(paragraph.start, 14);
    require(next.text == U"Начало — это ", "split does not cut a supplementary code point under a UTF-16 budget");
    auto remainder = perigee::readAloudSpan(next.next, 2000);
    require(next.text + remainder.text == expected, "resume inside a paragraph preserves every character exactly once");
    require(next.end.toString() == remainder.start.toString(), "split boundary round trips as a logical position");
    ldomXPointer path(paragraph.start.getNode(), -1);
    require(perigee::readAloudSpan(path, 2000).text == expected, "a text-node path without offset begins at zero");
    require(path.getOffset() == -1, "walking a span never mutates the input pointer");
    auto previous = perigee::previousReadAloudStart(remainder.start);
    require(previous.toString() == paragraph.start.toString(), "back from within a paragraph goes to its start");
    previous = perigee::previousReadAloudStart(previous);
    auto firstAgain = perigee::readAloudSpan(previous, 2000);
    require(firstAgain.text == U"First chapter", "back from a paragraph start goes to the previous block");
    require(perigee::previousReadAloudStart(firstAgain.start).isNull(), "back at the first block keeps the place");
}
