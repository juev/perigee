// The size a face is set at, so that the reader's number means one size of letters in every face.
// Shared by engine_jni.cpp and the desktop tool in engine/tools/desktop.
//
// The em is a nominal length: how much of it a face spends on its letters and between its lines is
// the face's own business. The number the reader picks is therefore the size of the reference face;
// any other face takes the em that gives it the reference's x-height, and the reference's line height
// in place of its own. The reference's own pages are what they were before this existed.
#ifndef PERIGEE_FACE_SIZE_H
#define PERIGEE_FACE_SIZE_H

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <utility>
#include <vector>

#include "fb2def.h"
#include "lvfntman.h"
#include "lvrend.h"
#include "lvstring.h"
#include "lvtinydom.h"

namespace perigee {

inline const char* referenceFace() { return "Roboto"; }

/// Above the engine's own limit of 72 px, which a 560 dpi phone reaches at 20 dp: 40 dp, the largest
/// size offered, at the densest screens, in the face with the smallest letters.
constexpr int MAX_FONT_PX = 256;

struct FaceSize {
    /// What to set the face at, in px.
    int em;
    /// A rule for the stylesheet, empty when the face keeps the line height it has.
    lString8 css;
    /// The face the size is worked out for.
    lString8 face;
};

/// The regular text of the book as lvrend.cpp asks for it: a face with an optical size axis gets the
/// one of [drawnPx], the em the text is drawn at, whatever [px] the font is opened at. A font a book
/// carries is registered for its document and the fragment that declares it, and is reached only
/// with both.
inline LVFontRef regularFont(const lString8& face, int px, int weight, int drawnPx, int documentId = -1,
                             int docFragmentIdx = -1) {
    LVFontVariations variations;
    if (gRenderDPI >= 100) variations.set(LVFONT_TAG_OPSZ, drawnPx * 72.0f / static_cast<float>(gRenderDPI));
    return fontMan->GetFont(px, weight, false, css_ff_sans_serif, face, 0, documentId, true,
                            variations.empty() ? nullptr : &variations, docFragmentIdx);
}

/// The x-height in ems of the face drawn at [drawnPx], 0 when the font manager cannot reach the face:
/// it then answers with another one, which must not size the text as this one. Hinting makes a height
/// at the drawn size whole pixels, 3 % of the letter at 18 dp and 10 % at the smallest size, so it is
/// measured far larger.
inline double xHeightEms(const lString8& face, int weight, int drawnPx, int documentId = -1, int docFragmentIdx = -1) {
    const int measuredAt = 1024;
    LVFontRef font = regularFont(face, measuredAt, weight, drawnPx, documentId, docFragmentIdx);
    if (font.isNull() || font->getTypeFace() != face) return 0;
    return font->getExtraMetric(font_metric_x_height, false) / 64.0 / measuredAt;
}

/// Roboto's letters and lines at [size], in [face]. Needs the render DPI and weight applied already.
inline FaceSize sizeOfFace(const lString8& face, int size, int weight, int documentId = -1, int docFragmentIdx = -1) {
    FaceSize plain{size, lString8(), face};
    const lString8 reference(referenceFace());
    if (face == reference) return plain;
    const double wanted = xHeightEms(reference, weight, size);
    if (wanted <= 0) return plain;
    int em = size;
    // Twice: the letters of a face with an optical size change with the em this finds.
    for (int pass = 0; pass < 2; pass++) {
        const double own = xHeightEms(face, weight, em, documentId, docFragmentIdx);
        if (own <= 0) return plain;
        em = static_cast<int>(std::lround(size * wanted / own));
    }
    LVFontRef font = regularFont(reference, size, weight, size);
    if (font.isNull() || em <= 0) return plain;
    // A number, not a length: a heading takes it to its own size, as it does the face's normal height.
    // In the default stylesheet, so a book that sets its line height keeps it.
    char rule[64];
    std::snprintf(rule, sizeof rule, "body { line-height: %.4f; }\r\n", static_cast<double>(font->getHeight()) / em);
    return FaceSize{em, lString8(rule), face};
}

/// The face the book's own text is drawn in, with what the font manager needs to reach it again.
struct DrawnFace {
    lString8 face;          // empty when the document has no block of text yet
    int documentId = -1;
    int docFragmentIdx = -1;
};

/// The commonest face among the first blocks of text in [doc]'s bodies, so that a title page or an
/// epigraph in another face does not decide it. Read from the styles the elements have: the ones
/// LoadDocument resolves, before the first layout, and those of the last layout after it.
inline DrawnFace drawnFace(ldomDocument* doc) {
    struct Tally {
        lString8 face;
        int count;
        ldomNode* first;
    };
    std::vector<Tally> tallies;
    ldomNode* root = doc ? doc->getRootNode() : nullptr;
    // Plain text, and HTML without a body, have their text under the root.
    for (bool everywhere : {false, true}) {
        if (!root) break;
        bool sawBody = false;
        int blocks = 0;
        std::vector<std::pair<ldomNode*, bool>> stack{{root, everywhere}};
        while (!stack.empty() && blocks < 40) {
            ldomNode* node = stack.back().first;
            bool inBody = stack.back().second;
            stack.pop_back();
            const lUInt16 id = node->getNodeId();
            if (id == el_stylesheet || id == el_style || id == el_script) continue;
            css_style_ref_t style = node->getStyle();
            if (!style.isNull() && style->display == css_d_none) continue;
            if (id == el_body) sawBody = inBody = true;
            bool holdsText = false;
            const int count = node->getChildCount();
            for (int i = count - 1; i >= 0; i--) {
                ldomNode* child = node->getChildNode(i);
                if (child->isElement()) stack.emplace_back(child, inBody);
                else if (inBody && child->isText() && !holdsText) holdsText = !child->getText().trim().empty();
            }
            if (!holdsText) continue;
            LVFontRef font = node->getFont();
            if (font.isNull()) continue;
            blocks++;
            const lString8 face = font->getTypeFace();
            auto tally = std::find_if(tallies.begin(), tallies.end(), [&face](const Tally& t) { return t.face == face; });
            if (tally == tallies.end()) tallies.push_back(Tally{face, 1, node});
            else tally->count++;
        }
        if (sawBody) break;
    }
    DrawnFace drawn;
    if (tallies.empty()) return drawn;
    // The first seen wins a tie: max_element keeps the first of equal ones.
    const Tally& commonest =
        *std::max_element(tallies.begin(), tallies.end(), [](const Tally& a, const Tally& b) { return a.count < b.count; });
    drawn.face = commonest.face;
    // As lvrend.cpp asks for the font of an element: the fragment counts only with the document's own fonts on.
    drawn.documentId = doc->getFontContextDocIndex();
    drawn.docFragmentIdx = drawn.documentId != -1 ? commonest.first->getDocFragmentIdx() : -1;
    return drawn;
}

/// The size to set [doc] at, and the rule for its stylesheet, for the face its own text is drawn in:
/// the reader's [pickedFace] when the book's text is drawn in it, or when the book has no face of its
/// own it can be measured in; that face otherwise. A face the book names is drawn at the reader's
/// size and with the reader's line height, as each of the app's faces is.
inline FaceSize sizeOfDrawnFace(ldomDocument* doc, const lString8& pickedFace, int size, int weight) {
    const DrawnFace drawn = drawnFace(doc);
    if (drawn.face.empty() || xHeightEms(drawn.face, weight, size, drawn.documentId, drawn.docFragmentIdx) <= 0)
        return sizeOfFace(pickedFace, size, weight);
    return sizeOfFace(drawn.face, size, weight, drawn.documentId, drawn.docFragmentIdx);
}

}  // namespace perigee

#endif  // PERIGEE_FACE_SIZE_H
