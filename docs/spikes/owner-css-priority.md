# The owner's CSS over the app's and the book's (issue #150)

The question: how plain CSS the owner writes, `p { text-indent: 0; }`, can win over the reading settings, which the app sets with `!important` rules, and over the book's own styles, without the owner adding `!important` to every declaration, while the owner's rules keep the usual cascade among themselves.

## How the engine cascades

crengine parses the whole stylesheet the app gives it, the format stylesheet, the app's rules and until now the owner's text after them, as its user-agent sheet (`lxmlDocBase::setStyleSheet`, `useragent_sheet=true`). A declaration there carries importance `0x2`, or `0x3` with `!important`; the book's stylesheets and `style=` carry `0x1` with `!important` and `0` without. `setNodeStyle()` in `lvrend.cpp` applies the user-agent sheet, then the book's, then `style=`. `css_style_rec_t::Apply` (`lvstyles.h`) takes a value when the property has no importance mark yet, or when the value is `0x3`, or `0x1` over a mark that is not `0x3`. So an ordinary declaration of the owner's in the user-agent sheet loses to every `!important` of the app's, and even to an ordinary declaration of the book's. `-cr-hint: late` adds `1<<31` to a selector's weight, which puts it after all the other rules of the same sheet; there is no weight above it.

## Measured on the prototype

To choose the mechanism, before any of it was written: a synthetic EPUB, 34 resolved values (lengths, alignment, the face the font manager returns, colour, `content`), on the engine of 67db238 built for the desktop, each way of giving the engine the owner's CSS tried by hand. That prototype is not in the repository; the check below covers the same cases and more.


| Owner's CSS given to the engine as | Values as the contract has them |
|---|---|
| none | 7 of 34 |
| appended to the app's rules, as before #150 | 13 of 34 |
| every declaration made `!important` | 32 of 34: the owner's own `!important` no longer beats a more specific ordinary rule of the owner's, and the app's more specific note rule still wins |
| the same with `-cr-hint: late` in every rule | 33 of 34: the owner's `!important` and ordinary rules still cannot be told apart |
| a sheet of its own, applied after `style=` | 34 of 34 |

Promoting declarations to `!important` cannot keep the owner's `!important` apart from the owner's ordinary rules: both become the engine's highest importance, and only weight is left to order them, under a single `late` bit. That leaves a sheet of the owner's, applied last.

## What the app does

`patches/0003-owner-css-layer.patch`, `.cpp` files only:

- `lvtinydom.cpp`, `setStyleSheet()`: the text after the marker comment of `owner_css.h` is parsed, after the user-agent part, into a sheet kept per document in a map; replaced with each stylesheet, freed with the document. A rule that selects no element, `.perigee-owner-css:not(*)`, carries that sheet's hash, its `@font-face` rules included, into the user-agent sheet, so the layout cache and the restyling of an open book see a change of it.
- The faces the owner's `@font-face` rules register are the owner's sheet's: they are kept out of the document's font list, which the layout cache saves and registers again when it is read, and come from the sheet each time it is set, before the cache is read. The list is copied before the owner's sheet is parsed and put back after it as a whole, because an `@font-face` of the same source and fragment as an entry there, the app's or the book's, changes that entry in place. When a sheet that had such rules is replaced, the document's faces are unregistered and those of its list registered again in the scope each had; the font manager forgets a document's faces only all together.
- The cache format version goes from `3.05.83k` to `3.05.83p`. A cache written before lists the faces of an owner's `@font-face` among the book's, where nothing tells them apart; the engine refuses such a cache by its magic and parses the book again, once per book after the update.
- `lvrend.cpp`, `setNodeStyle()`: after `style=`, the importance marks of the element and of the slots in which it gathers `::before`, `::after`, `::first-letter` and `::first-line` are put aside, the owner's sheet is applied, and the marks are put back. A property it does not set keeps its value and its mark. What `setNodeStyle()` does after it still holds: the internal MathML styles, the display of images and `<br>`, and the properties a `::first-letter` may take.

`engine_jni.cpp` passes the owner's text apart from the app's rules and puts it after the line-height rule of the face size; the app's text before the marker never holds it.

## Check

`engine/tools/desktop/owner-css.sh` on the books of `owner-css-books.py`, 2026-11-02, the desktop build of the pinned engine, with `LEGACY_BUILD` set to the build without 0003, which writes the cache the check then opens. Every reference is a first opening of a copy of the book under another file name, which the cache of the openings under check cannot reach, and the check fails if one of them reads a cache. The other process is this program again, started with its argv, on paths with a space and an apostrophe.

| Engine | PASS | FAIL |
|---|---|---|
| without 0003 (the app's patches 0001 and 0002) | 103 | 59 |
| with 0003 | 162 | 0 |

Without the patch fail the matrix rows the contract changes, the alias of an `@font-face`, the checks that read the patch's count of sheets, an owner's `@font-face` taken out: on the open book, over a cache made with it and in another process the face it named is still drawn; an owner's `@font-face` of the source of one of the app's rewriting the app's entry, and a cache saved after it listing the owner's face; and a cache of the engine without 0003, written with an owner's `@font-face`, read as it is and drawing that face. An owner's `@font-face` of the source of the book's in an FB2 changes the book's entry too, but the engine parses the book's CSS again on every restyle, which puts it back; that check passes both ways. What passes there too is what the patch must not break: the place kept and the styles of a fresh opening across save, change and reset, the stylesheet hash changing with an `@font-face` descriptor alone, and the layout cache, written to its index and file, taken as it is with the same CSS in this process and in another one, laid out again with other CSS.

## Limits

- A pseudo-element the owner's CSS makes stays a node of the document when the CSS changes or is reset, on an open book and in a layout cache made with that CSS; crengine does not take nodes out of a DOM once built. It is hidden (`display: none`), its first letter is drawn with its word again, and the page is drawn as a fresh opening draws it: checked on the open book and over the cache. The engine marks such a DOM stale (`isBuiltDomStale`); the app does not reload on it, for this as for any other change of the stylesheet.
- An `@font-face` of the owner's that names a face the app registers, as `font-family: "PT Serif"` for the reader's face, is not taken on a first opening: the engine resolves that face before the alias reaches it, and takes the alias when the book is styled again, as on a change of settings. This is the engine's way with such an alias, the same before the patch. An alias under a name of its own is taken on a first opening.
- Every cached layout is refused once after the update, for the new format version, and the book parsed again.
- `src: local()` names a face the app already has: nothing is downloaded or imported.
- The text goes over JNI as modified UTF-8, as the app's rules do.
- The copy constructor of `lxmlDocBase` leaves the copy without the owner's sheet; nothing in the engine calls it.
- A property crengine does not support stays unsupported.
