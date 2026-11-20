#pragma once

#include <algorithm>
#include <vector>

#include "lvdocview.h"
#include "lvtinydom.h"

namespace perigee {

struct ReadingBlock {
    lString32 anchor;
    lString32 text;
    lvRect bounds;
    bool heading;
};

// Walk the rendered main-flow range of each visible leaf in document order.
// Relocated footers are not in these ranges. Never query the following leaf
// when there is none: crengine silently falls back to the current page.
inline std::vector<ReadingBlock> pageReadingBlocks(LVDocView& view) {
    class Collector : public ldomNodeCallback {
    public:
        Collector(LVDocView& view, std::vector<ReadingBlock>& out, int top, int bottom)
                : view(view), out(out), leafTop(top), leafBottom(bottom) {}

        bool onElement(ldomXPointerEx* at) override {
            return at->getNode()->getRendMethod() != erm_invisible;
        }

        void onText(ldomXRange* range) override {
            auto& start = range->getStart();
            int begin = start.getOffset();
            int end = range->getEnd().getOffset();
            if (begin >= end) return;
            for (auto* node = start.getNode()->getParentNode(); node; node = node->getParentNode()) {
                auto style = node->getStyle();
                if (node->getRendMethod() == erm_invisible ||
                        (!style.isNull() && style->visibility >= css_v_hidden)) return;
            }
            LVArray<lvRect> segments;
            range->getSegmentRects(segments);
            lvRect fragmentBounds;
            bool crossesLeaf = false;
            for (int i = 0; i < segments.length(); i++) {
                const auto& rect = segments[i];
                // Adjacent DOM page ranges can overlap. A rectangle on the other leaf of the
                // same spread still maps to the bitmap, but does not belong to this traversal.
                if (rect.isEmpty()) continue;
                if (rect.top < leafTop || rect.top >= leafBottom) {
                    crossesLeaf = true;
                    continue;
                }
                lvPoint top = rect.topLeft(), bottom = rect.bottomRight();
                if (!view.docToWindowPoint(top) || !view.docToWindowPoint(bottom, true)) continue;
                lvRect displayed(top.x, top.y, bottom.x, bottom.y);
                fragmentBounds.extend(displayed);
            }
            const auto source = start.getNode()->getText();
            auto* owner = start.getThisBlockNode();
            if (fragmentBounds.isEmpty()) {
                // Preserve a separator between visible inline runs when the separating text
                // node has no geometry. Never attach undrawn non-whitespace to another run.
                auto whitespace = source.substr(begin, end - begin);
                if (!crossesLeaf && owner == block && !text.empty() && lString32(whitespace).trim().empty()) {
                    text << whitespace;
                }
                return;
            }
            // One DOM text node can span both leaves. Clipping its rectangle alone still
            // emits the characters from the other page. Resolve the offsets at the edges
            // only when the actual line geometry crosses the leaf boundary.
            if (crossesLeaf) {
                ldomXPointer character(start.getNode(), begin);
                auto onLeaf = [&](int offset) {
                    character.setOffset(offset);
                    lvRect rect;
                    return character.getRectEx(rect, true) && rect.top >= leafTop && rect.top < leafBottom;
                };
                while (begin < end && !onLeaf(begin)) ++begin;
                while (begin < end && !onLeaf(end - 1)) --end;
                if (begin == end) return;
            }
            if (owner != block) {
                flush();
                block = owner;
            }
            if (text.empty()) anchor = ldomXPointer(start.getNode(), begin).toString();
            text << source.substr(begin, end - begin);
            bounds.extend(fragmentBounds);
        }

        void flush() {
            text.trim();
            if (!bounds.isEmpty() && !text.empty() && !anchor.empty()) {
                bool heading = false;
                for (auto* node = block; node; node = node->getParentNode()) {
                    const auto& name = node->getNodeName();
                    if (name == U"title" || name == U"h1" || name == U"h2" || name == U"h3" ||
                            name == U"h4" || name == U"h5" || name == U"h6") {
                        heading = true;
                        break;
                    }
                }
                out.push_back({anchor, text, bounds, heading});
            }
            text.clear();
            bounds = lvRect();
        }

    private:
        LVDocView& view;
        std::vector<ReadingBlock>& out;
        const int leafTop;
        const int leafBottom;
        ldomNode* block = nullptr;
        lString32 anchor;
        lString32 text;
        lvRect bounds;
    };

    std::vector<ReadingBlock> blocks;
    const int first = view.getCurPage(true);
    const int end = std::min(view.getPageCount(true), first + view.getVisiblePageCount());
    for (int i = first; i >= 0 && i < end; i++) {
        auto range = view.getPageDocumentRange(i);
        if (range.isNull()) continue;
        const auto& leaf = *(*view.getPageList())[i];
        Collector collector(view, blocks, leaf.start, leaf.start + leaf.height);
        range->forEach(&collector);
        collector.flush();
    }
    return blocks;
}

} // namespace perigee
