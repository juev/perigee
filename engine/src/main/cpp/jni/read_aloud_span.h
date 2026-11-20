#pragma once

#include "lvtinydom.h"

namespace perigee {

struct ReadAloudSpan {
    lString32 text;
    ldomXPointer start;  // First included code point.
    ldomXPointer end;    // Exclusive, even at the end of the book.
    ldomXPointer next;   // Start of the next span; null at end of the main text.
};

namespace speech {
inline bool hasToken(const lString32& value, const lChar32* token) {
    const auto separator = [](lChar32 ch) { return ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r'; };
    for (int i = 0; i < value.length();) {
        if (separator(value[i])) { ++i; continue; }
        int start = i;
        while (i < value.length() && !separator(value[i])) ++i;
        if (value.substr(start, i - start) == token) return true;
    }
    return false;
}

inline bool readable(ldomXPointerEx& at) {
    if (!at.isText() || !at.isVisible()) return false;
    bool inBody = false;
    for (auto* node = at.getNode()->getParentNode(); node; node = node->getParentNode()) {
        const auto style = node->getStyle();
        if (!style.isNull() && (STYLE_HAS_CR_HINT(style, TEXT_SELECTION_SKIP) ||
                                STYLE_HAS_CR_HINT(style, FOOTNOTE_INPAGE) ||
                                STYLE_HAS_CR_HINT(style, INSIDE_FOOTNOTE_INPAGE))) return false;
        const auto& name = node->getNodeName();
        if (name == U"body") {
            inBody = true;
            const auto& kind = node->getAttributeValue(U"name");
            if (kind == U"notes" || kind == U"comments") return false;
        }
        if (name == U"FictionBook" && !inBody) return false;
        if (name == U"head") return false;
        const auto& type = node->getAttributeValue(U"epub", U"type");
        const auto& role = node->getAttributeValue(U"role");
        if (hasToken(type, U"footnote") || hasToken(type, U"footnotes") ||
            hasToken(type, U"endnote") || hasToken(type, U"endnotes") ||
            hasToken(role, U"doc-footnote") || hasToken(role, U"doc-endnote") ||
            hasToken(role, U"doc-endnotes")) return false;
    }
    return true;
}

inline bool normalize(ldomXPointerEx& at) {
    if (at.isNull()) return false;
    do {
        if (readable(at) && at.getOffset() < at.getNode()->getText().length()) {
            if (at.getOffset() < 0) at.setOffset(0);
            return true;
        }
    } while (at.nextVisibleText());
    return false;
}

// ldomXPointer copies share offset storage. Take an independent value before walking on.
inline ldomXPointer position(ldomXPointerEx& at) {
    return ldomXPointer(at.getNode(), at.getOffset());
}
}

// Return at most one visible text block, or the remainder of a split block. No
// dependence on page boundaries, spread width or the engine's displayed page.
inline ReadAloudSpan readAloudSpan(const ldomXPointer& from, int maxUtf16) {
    ReadAloudSpan span;
    if (from.isNull() || maxUtf16 < 2) return span;
    ldomXPointerEx at(from);
    int remaining = maxUtf16;
    while (speech::normalize(at)) {
        auto* block = at.getThisBlockNode();
        span.start = speech::position(at);
        do {
            const auto text = at.getNode()->getText();
            int i = at.getOffset();
            for (; i < text.length(); ++i) {
                const lChar32 codePoint = text[i];
                const int width = codePoint > 0xffff ? 2 : 1;
                if (width > remaining) break;
                span.text << codePoint;
                remaining -= width;
            }
            at.setOffset(i);
            span.end = speech::position(at);
            if (i < text.length()) {
                span.next = span.end;
                return span;
            }
            if (!at.nextVisibleText() || !speech::normalize(at)) return span;
        } while (remaining > 0 && at.getThisBlockNode() == block);
        span.next = speech::position(at);
        return span;
    }
    return span;
}

// Return to the start of the current block when inside it, otherwise to the
// start of the preceding block. No page size or prior playback history needed.
inline ldomXPointer previousReadAloudStart(const ldomXPointer& from) {
    ldomXPointerEx at(from);
    if (!speech::normalize(at)) return ldomXPointer();
    auto* block = at.getThisBlockNode();
    auto start = ldomXPointer(at.getNode(), 0);
    bool foundEarlier = at.getOffset() > 0;
    while (at.prevVisibleText()) {
        if (!speech::readable(at) || at.getNode()->getText().empty()) continue;
        auto* preceding = at.getThisBlockNode();
        if (preceding != block) {
            if (foundEarlier) break;
            block = preceding;
        }
        start = ldomXPointer(at.getNode(), 0);
        foundEarlier = true;
    }
    return foundEarlier ? start : ldomXPointer();
}

}
