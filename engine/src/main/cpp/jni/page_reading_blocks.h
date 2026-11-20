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

// Walk each visible leaf in reading order: main flow, then its drawn footers.
// Never query the following leaf when there is none: crengine falls back to the current page.
inline std::vector<ReadingBlock> pageReadingBlocks(LVDocView& view) {
    class Collector : public ldomNodeCallback {
    public:
        Collector(LVDocView& view, std::vector<ReadingBlock>& out, int top, int bottom,
                  int footerX = 0, lvRect footer = {})
                : view(view), out(out), leafTop(top), leafBottom(bottom),
                  footerX(footerX), footer(footer) {}

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
                lvRect displayed;
                if (footer.isEmpty()) {
                    lvPoint top = rect.topLeft(), bottom = rect.bottomRight();
                    if (!view.docToWindowPoint(top) || !view.docToWindowPoint(bottom, true)) continue;
                    displayed = lvRect(top.x, top.y, bottom.x, bottom.y);
                } else {
                    // docToWindowPoint only maps main-flow coordinates, not relocated footers.
                    const int dy = footer.top - leafTop;
                    displayed = lvRect(rect.left + footerX, rect.top + dy,
                                       rect.right + footerX, rect.bottom + dy);
                    if (!displayed.intersect(footer)) continue;
                }
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
        const int footerX;
        const lvRect footer;
        ldomNode* block = nullptr;
        lString32 anchor;
        lString32 text;
        lvRect bounds;
    };

    std::vector<ReadingBlock> blocks;
    const int first = view.getCurPage(true);
    const int leaves = view.getVisiblePageCount();
    const int end = std::min(view.getPageCount(true), first + leaves);
    for (int i = first; i >= 0 && i < end; i++) {
        auto range = view.getPageDocumentRange(i);
        const auto& leaf = *(*view.getPageList())[i];
        if (!range.isNull()) {
            Collector collector(view, blocks, leaf.start, leaf.start + leaf.height);
            range->forEach(&collector);
            collector.flush();
        }
        if (leaf.footnotes.length() == 0) continue;

        // Match LVDocView::drawPageTo: note segments stack above the bottom margin;
        // their horizontal origin follows the leaf rectangle, but the spread clips at mid-screen.
        lvRect pageRect;
        view.getPageRectangle(i - first, pageRect);
        const int width = view.GetWidth();
        const lvRect margins = view.getPageMargins();
        const int left = leaves == 2 && i != first ? width / 2 : 0;
        const int right = leaves == 2 && i == first ? width / 2 : width;
        int y = pageRect.top + view.GetHeight() - margins.bottom;
        for (int j = 0; j < leaf.footnotes.length(); ++j) y -= leaf.footnotes[j].height;
        for (int j = 0; j < leaf.footnotes.length(); ++j) {
            const auto& note = leaf.footnotes[j];
            const int top = note.start;
            const int bottom = top + note.height;
            const lvRect footer(left, y, right, y + note.height);
            y += note.height;
            if (footer.isEmpty()) continue;

            // Bound the logical range to this rendered segment, not to the whole note.
            // Spacing at either end may resolve to a different line or no node.
            auto pointer = [&](int y, bool start) {
                return view.getDocument()->createXPointer(lvPoint(start ? 0 : width, y),
                        start ? PT_DIR_SCAN_FORWARD_LOGICAL_FIRST : PT_DIR_SCAN_BACKWARD_LOGICAL_LAST);
            };
            auto within = [&](const ldomXPointer& at, bool end) {
                if (at.isNull()) return false;
                // The logical end is exclusive. Its caret may already be on the next line.
                const int offset = at.getOffset();
                const int py = end && at.getNode()->isText() && offset > 0
                        ? ldomXPointer(at.getNode(), offset - 1).toPoint(true).y
                        : at.toPoint(true).y;
                return py >= top && py < bottom;
            };
            int startY = top;
            auto start = pointer(startY, true);
            while (!within(start, false) && ++startY < bottom) start = pointer(startY, true);
            if (startY == bottom) continue;
            int endY = bottom - 1;
            auto limit = pointer(endY, false);
            while (!within(limit, true) && --endY >= startY) limit = pointer(endY, false);
            if (endY < startY || ldomXPointerEx(start).compare(ldomXPointerEx(limit)) >= 0) continue;
            ldomXRange noteRange(start, limit);
            Collector collector(view, blocks, top, bottom, pageRect.left + margins.left, footer);
            noteRange.forEach(&collector);
            collector.flush();
        }
    }
    return blocks;
}

} // namespace perigee
