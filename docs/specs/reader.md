# Reader: engine API and reading settings

This document specifies what the `:engine` module gives the rest of the app and which reading settings Perigee has. The reading screen (`:app`) is built on top of it.

The model for settings and page appearance is ReadEra. The reference section at the end records what ReadEra shows, as observed on a phone on 2026-10-12.

## Engine module

### Threads

crengine is not thread-safe and keeps process-wide state. Every call into it, for every open book, runs on one thread owned by the module (`Engine.thread`).

- `BookReader` is the API for the app. All its methods are `suspend` and safe to call from any thread.
- `EngineDocument` is the synchronous layer underneath. Its caller must already be on the engine thread; `BookReader.read { }` gives access to it there.
- `Engine.fontFaces` and `Engine.registerFonts` also run on the engine thread.

### Positions

A position is a crengine xpointer, the form KOReader exchanges with bookOrbit. Two xpointer strings may differ and still name one place (`/body[1]/…` and `/body/…`), so positions are never compared as strings: resolve both with `pageOf` or go to them.

A text range is a pair of xpointers. The end names the first character that is not part of the range.

Pages are numbered from zero. Page numbers change with every layout change and are never stored.

### Pages

- `BookReader.open` lays the book out for a page size, settings and screen density, and goes to a saved xpointer when one is given and still resolves. A file the engine cannot read gives `IllegalArgumentException`.
- `current`, `turn`, `goToPage`, `goTo` return a `Page`: the bitmap, the page number, the page count, the xpointer of the top of the page and the progress through the book from 0 to 1.
- `turn` moves by screens and stops at either end of the book. With two pages side by side a screen is two pages and the left page is always even.
- `goTo` with an xpointer that does not resolve returns `null` and leaves the reader in place.
- `apply` and `resize` re-lay the book out and keep the reading position: the text that was at the top of the page is on the page shown afterwards.
- The pages one screen before and after the one shown are drawn ahead of time, each as a separate task on the engine thread, so a call from the screen is served between them. A layout change, a size change or new highlights discard what was drawn.

### Contents, search, selection, links

All of these are methods of `EngineDocument`.

- `toc()` lists the table of contents in reading order: title, xpointer, depth (1 is the top level) and page.
- `search(pattern, caseInsensitive, maxHits)` finds a phrase in the whole book, in reading order. A match is found across inline markup, with typographic spaces and apostrophes, and with soft hyphens inside words. Each hit carries its range, its text, a few words before and after it within the paragraph, and its page. Regular expressions are not supported: the engine is built without SRELL.
- `select(x0, y0, x1, y1)` returns the whole words between two points of the page shown, in reading order whichever point comes first. One point selects the word under it.
- `rects(start, end)` returns where a range lies on the page shown, one rectangle per text line. A range on another page gives an empty list.
- `linkAt(x, y)` returns the link under a point: its href, the xpointer of the link and, for a link inside the book, the xpointer of its target.
- `pageOf(xpointer)` and `xpointerOfPage(page)` convert between the two.

Points and rectangles are in pixels of the bitmap the page is drawn into. The app converts from screen coordinates when it shows the bitmap shifted or cropped.

### Highlights

`renderPage(bitmap, highlights)` and `BookReader.setHighlights` tint ranges on the page. On a light page the colour is multiplied in, like a marker on paper: the page takes the colour and the text stays as dark as it was. On a dark page the colour is laid over at partial opacity, because multiplying would leave nothing to see.

### Fonts

Faces offered for reading come from three places:

1. The device: only the families Android names in `/system/etc/fonts.xml` (Roboto, Noto Serif and so on). The unnamed families, one per script, are registered with the engine but not offered. If the config cannot be read, all system fonts are offered.
2. Files registered with `Engine.registerFonts`: fonts shipped with the app and fonts from a folder the reader picked.

The default face is Roboto.

Not done yet: no fallback face is configured, so a character missing from the chosen font is not taken from a script font.

## Settings

`EngineSettings` has the same knobs as ReadEra's reading settings. Sizes are in device pixels.

| Setting | Field | Notes |
|---|---|---|
| Colour scheme | `colors` | Text and page colour. Presets: `Day`, `Night`, `Sepia`. Sepia is `#644D32` on `#F0DEC7`, measured from ReadEra. |
| Font | `fontFace` | A face from `Engine.fontFaces`. |
| Font size | `fontSize` | |
| Font boldness | `fontWeight` | CSS weight of regular text, 400 by default. Values the engine accepts are in `EngineSettings.FONT_WEIGHTS`. Bold text gets heavier with it. |
| Line spacing | `lineSpacingPercent` | 100 is the font's own line height. |
| Align text | `textAlign` | Justify, left, centre, right. |
| Hyphenation | `hyphenation` | By the dictionary of the book's language. |
| Paragraph spacing | `paragraphSpacingPercent` | In percent of the font size; `null` leaves it to the book. |
| Paragraph indent | `paragraphIndent` | On or off; `null` leaves it to the book. |
| Page margins | `marginHorizontal`, `marginVertical` | |
| Footnotes | `footnotes` | Notes at the bottom of the page that cites them. |
| Two pages | `twoPages` | Side by side; the screen decides when to use it. |

Always on, without a setting: hanging punctuation, kerning and shaping through HarfBuzz, and a floor of 75 % on how far a justified line may shrink its spaces. Below that floor the engine hyphenates or stretches instead; its own default of 50 % lets words run together.

### Footnotes

A block is treated as a footnote when the book marks it:

- FB2: sections of `<body name="notes">` and `<body name="comments">`.
- EPUB: elements with `epub:type` of `footnote`, `note`, `endnote`, `rearnote`, or `role` of `doc-footnote`, `doc-endnote`.

Such a note is shown under a rule at the bottom of the page whose text links to it. A book that marks its notes in neither way keeps them where the author put them; the screen can still follow the link with `linkAt`.

## Tests

All of the above is covered by instrumented tests in `engine/src/androidTest`, run with `./gradlew :engine:connectedDebugAndroidTest`:

- `EngineDocumentTest`, `EngineFeaturesTest` — one or more tests per method and per setting, on books generated by the tests.
- `BookReaderTest` — page turning, drawing ahead, layout changes, calls from many threads, closing one book while another opens.
- `ControlPagesTest` — control pages compared with accepted pictures in `engine/src/androidTest/assets/golden`. The pages are set in PT Serif from the test assets, so they do not depend on the fonts of the device. The class comment says how to accept a new look.

## Reference: ReadEra

Observed in ReadEra Premium on a OnePlus phone, 2026-10-12. This is what the reading screen of Perigee is modelled on.

### Reading settings panel

Opened from the gear in the top bar, titled "Settings for EPUB, FB2, MOBI, DOC, DOCX, RTF, TXT and CHM":

- Page turning: horizontal, vertical.
- Colour scheme: Day, Night, Night contrast, Sepia, Sepia contrast, Twilight, Console, and a "switching" entry.
- Font: Comfortaa, Droid Serif, Literata, Merriweather, Noto Serif, PT Sans, PT Serif, Roboto, and a gear for managing fonts.
- Font size: a number with minus and plus buttons.
- Font boldness: a slider.
- Line spacing: a percentage with minus and plus buttons; 120 % on the phone observed.
- Align text: justify, left, centre, right, with a "Hyphenation" switch on top and a "Paragraph" submenu below.
- Paragraph: paragraph spacing as a percentage with minus and plus (20 % on the phone observed) and a "Paragraph indent" switch.
- Page margins: a switch.
- A button to the general settings.

### Reading screen

- Top bar: back, text-to-speech, search, contents, settings, menu; under it the title, author and chapter.
- Bottom: "586 of 853", a progress line with chapter marks, buttons for the page grid, rotation lock and pin.
- The page: text nearly to the screen edges with margins off, hyphens hanging in the right margin, a thin progress line with chapter dots at the bottom.

### General settings that concern reading

- Full-screen mode.
- Page turning and page pointer: turn animation; volume keys turn pages; the page pointer is a line with the current position, or hidden; tap schemes, separately for horizontal and vertical turning — (1) right part forward, left part back, (2) bottom part forward, top part back, (3) any part forward, or off.
- Text-to-speech; translator, dictionary, web search.
- Brightness by sliding along the left edge of the screen.
- Day and night switched by touching the top left corner.
- Bookmark by touching the top right corner.
- Keep the screen on ten minutes longer than the system does.
- Continue the last book on start.
