// The stylesheet a view is given: the format's, the app's rules, the size rule and, last and apart, the CSS
// the owner wrote. engine_jni.cpp, the desktop gaps tool and its owner-css check build it here alike.
#pragma once

#include "lvstring.h"

namespace perigee {

/// Where the owner's CSS starts in the stylesheet text. patches/0003-owner-css-layer.patch splits the text
/// here: what comes before is the user-agent sheet, what follows is the owner's sheet, which the engine
/// applies to every element after the book's styles and style= (issue #150). It is a comment, so an engine
/// without the patch reads the whole text as one sheet, as before.
constexpr const char* OWNER_CSS_MARKER = "/*perigee:owner-css*/";

/// [base] is the format stylesheet and the app's rules, [sizeCss] the rule of face_size.h. The owner's
/// [ownerCss] is put after the marker as it was written; without any, the text has no marker and the
/// engine no owner's sheet.
inline lString8 composeStylesheet(const lString8& base, const lString8& sizeCss, const lString8& ownerCss) {
    lString8 css = base;
    css << "\r\n" << sizeCss;
    if (!ownerCss.empty()) css << "\r\n" << OWNER_CSS_MARKER << ownerCss;
    return css;
}

}  // namespace perigee
