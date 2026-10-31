// The size a face is set at, so that the reader's number means one size of letters in every face.
// Shared by engine_jni.cpp and the desktop tool in engine/tools/desktop.
//
// The em is a nominal length: how much of it a face spends on its letters and between its lines is
// the face's own business. The number the reader picks is therefore the size of the reference face;
// any other face takes the em that gives it the reference's x-height, and the reference's line height
// in place of its own. The reference's own pages are what they were before this existed.
#ifndef PERIGEE_FACE_SIZE_H
#define PERIGEE_FACE_SIZE_H

#include <cmath>
#include <cstdio>

#include "lvfntman.h"
#include "lvrend.h"
#include "lvstring.h"

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
};

/// The regular text of the book as lvrend.cpp asks for it: a face with an optical size axis gets the
/// one of [drawnPx], the em the text is drawn at, whatever [px] the font is opened at.
inline LVFontRef regularFont(const lString8& face, int px, int weight, int drawnPx) {
    LVFontVariations variations;
    if (gRenderDPI >= 100) variations.set(LVFONT_TAG_OPSZ, drawnPx * 72.0f / static_cast<float>(gRenderDPI));
    return fontMan->GetFont(px, weight, false, css_ff_sans_serif, face, 0, -1, true,
                            variations.empty() ? nullptr : &variations);
}

/// The x-height in ems of the face drawn at [drawnPx]. Hinting makes a height at the drawn size whole
/// pixels, 3 % of the letter at 18 dp and 10 % at the smallest size, so it is measured far larger.
inline double xHeightEms(const lString8& face, int weight, int drawnPx) {
    const int measuredAt = 1024;
    LVFontRef font = regularFont(face, measuredAt, weight, drawnPx);
    if (font.isNull()) return 0;
    return font->getExtraMetric(font_metric_x_height, false) / 64.0 / measuredAt;
}

/// Roboto's letters and lines at [size], in [face]. Needs the render DPI and weight applied already.
inline FaceSize sizeOfFace(const lString8& face, int size, int weight) {
    FaceSize plain{size, lString8()};
    const lString8 reference(referenceFace());
    if (face == reference) return plain;
    const double wanted = xHeightEms(reference, weight, size);
    if (wanted <= 0) return plain;
    int em = size;
    // Twice: the letters of a face with an optical size change with the em this finds.
    for (int pass = 0; pass < 2; pass++) {
        const double own = xHeightEms(face, weight, em);
        if (own <= 0) return plain;
        em = static_cast<int>(std::lround(size * wanted / own));
    }
    LVFontRef font = regularFont(reference, size, weight, size);
    if (font.isNull() || em <= 0) return plain;
    // A number, not a length: a heading takes it to its own size, as it does the face's normal height.
    // In the default stylesheet, so a book that sets its line height keeps it.
    char rule[64];
    std::snprintf(rule, sizeof rule, "body { line-height: %.4f; }\r\n", static_cast<double>(font->getHeight()) / em);
    return FaceSize{em, lString8(rule)};
}

}  // namespace perigee

#endif  // PERIGEE_FACE_SIZE_H
