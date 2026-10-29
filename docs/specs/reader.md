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
- `select(x0, y0, x1, y1)` returns the whole words between two points of the page shown, in reading order whichever point comes first. One point selects the word under it. A selection has no white space at its ends.
- `rects(start, end)` returns where a range lies on the page shown, one rectangle per text line. A range on another page gives an empty list.
- `linkAt(x, y)` returns the link under a point: its href, the xpointer of the link and, for a link inside the book, the xpointer of its target.
- `pageOf(xpointer)` and `xpointerOfPage(page)` convert between the two.

Points and rectangles are in pixels of the bitmap the page is drawn into. The app converts from screen coordinates when it shows the bitmap shifted or cropped.

### Highlights

`renderPage(bitmap, highlights)` and `BookReader.setHighlights` tint ranges on the page. On a light page the colour is multiplied in, like a marker on paper: the page takes the colour and the text stays as dark as it was. On a dark page the colour is laid over at partial opacity, because multiplying would leave nothing to see.

### Fonts

Faces offered for reading come from three places:

1. The app: ReadEra's set — Comfortaa, Droid Serif, Literata, Merriweather, Noto Serif, PT Sans, PT Serif, Roboto. They lie in the engine's assets (`engine/src/main/assets/crengine/fonts`), are unpacked with the rest of them and registered first when the engine starts, so that a family the device has too is drawn the same on every device. Where the authors publish a variable font it is shipped as such, so that the boldness setting takes the font's own weights instead of making them up; PT Sans, PT Serif and Droid Serif come as four styles each. Every file is the authors' own, unchanged; where each comes from and its license are in `NOTICE` and `licenses/`. Droid Serif is the Android one (`android-4.4.4_r1`), as the one in Google Fonts has no Cyrillic.
2. The device: only the families Android names in `/system/etc/fonts.xml` (Roboto, Noto Serif and so on). The unnamed families, one per script, are registered with the engine but not offered. If the config cannot be read, all system fonts are offered.
3. Files registered with `Engine.registerFonts`, as fonts from a folder the reader picked.

The default face is Roboto.

A character the chosen font lacks is taken from Noto Serif, the app's font with the most scripts, then from the device's fonts of single scripts, in that order (`Engine.FALLBACK_FACE`).

Not done yet: fonts of the reader's own, from a folder.

## Settings

`EngineSettings` has the same knobs as ReadEra's reading settings. Sizes are in device pixels.

| Setting | Field | Notes |
|---|---|---|
| Colour scheme | `colors` | Text and page colour. Presets: `Day`, `Night`, `Oled`, `Sepia`. Night is `#C8C8C8` on dark grey `#1E1E1E`, not on black. OLED is `#FFFFFF` on pure black, so the black pixels of an OLED screen stay off; its colours are those of ReadEra's "Night contrast", measured on the reader's phone on 2026-10-29 (ReadEra's "Night" is `#A1A1A1` and its "Console" `#008000`, both on black). Sepia is `#644D32` on `#F0DEC7`, measured from ReadEra. |
| Font | `fontFace` | A face from `Engine.fontFaces`. |
| Font size | `fontSize` | |
| Font boldness | `fontWeight` | CSS weight of regular text, 400 by default. Values the engine accepts are in `EngineSettings.FONT_WEIGHTS`. Bold text gets heavier with it. |
| Contrast | `contrast` | How dark the strokes are drawn, in five steps from 0, the font as it is: the engine's gamma on glyph coverage, 1.0, 1.2, 1.4, 1.6 and 1.8. Letters keep their widths, so a change paints the pages again without laying the book out, as new colours do. The engine keeps one gamma for all books. |
| Line spacing | `lineSpacingPercent` | 100 is the font's own line height. |
| Align text | `textAlign` | Justify, left, centre, right. |
| Hyphenation | `hyphenation` | By the dictionary of the book's language. |
| Paragraph spacing | `paragraphSpacingPercent` | In percent of the font size; `null` leaves it to the book. |
| Paragraph indent | `paragraphIndent` | On or off; `null` leaves it to the book. |
| Page margins | `marginHorizontal`, `marginTop`, `marginBottom` | |
| Footnotes | `footnotes` | Notes at the bottom of the page that cites them. |
| Two pages | `twoPages` | Side by side; the screen decides when to use it. |

Always on, without a setting: hanging punctuation, kerning and shaping through HarfBuzz, and a floor of 75 % on how far a justified line may shrink its spaces. Below that floor the engine hyphenates or stretches instead; its own default of 50 % lets words run together.

### Footnotes

A block is treated as a footnote when the book marks it:

- FB2: sections of `<body name="notes">` and `<body name="comments">`.
- EPUB: elements with `epub:type` of `footnote`, `note`, `endnote`, `rearnote`, or `role` of `doc-footnote`, `doc-endnote`.

Such a note is shown under a rule at the bottom of the page whose text links to it. A book that marks its notes in neither way keeps them where the author put them; the screen can still follow the link with `linkAt`.

Notes are set at 80 % of the text size, with line height to match, so that a note of a few lines fits on the page that cites it; a longer one goes on to the next page. The paragraph spacing setting does not reach note paragraphs. The FB2 reference in the text keeps its own size from `fb2.css`. The size is the same with footnotes off, where notes stay at the end of the chapter or the book.

## Reading screen

`ReaderScreen` in `:app` shows one book on the whole screen. `ReaderViewModel` owns the open book and outlives rotations; the screen only tells it the size of the page area.

### State that is kept

- Per book, in `:data` (`Library`, Room): the reading position, bookmarks, quotes and the last ten places jumped from, so that Back returns after the app is started again. A book is identified by a fingerprint of its file (`FileHash`, the scheme KOReader uses), so the same file in another folder is the same book.
- For all books, in `ReaderPreferences` (DataStore): the reading settings and the control options below.
- The app starts on the book that was open last, if its file is still there.

The position stored is `BookReader.position`: the top of the page the reader last went to. A layout change shows the page that position falls on without moving it, so turning the screen and back returns to the very same page.

### Opening and laying out

Laying a novel out takes seconds on a phone, so the screen does it as rarely as it can:

- The page area settles over a few frames, as the cutout inset arrives or a rotation finishes. The screen waits for the last size before it asks for a layout.
- While a book is being opened or laid out again a progress ring is shown and touches are dropped. At most two page turns wait for the engine; a third touch in the meantime is dropped. Touches never pile up to fire all at once.
- When the app goes to the background the engine writes the parsed and laid out book to its cache. The next opening with the same settings reads it back instead of doing the work again.

### Touches

A touch is sorted out in this order:

1. With a selection or a quote's actions open, it closes them.
2. On a link: inside the book it jumps to the target and remembers where the reader came from; outside the book it is handed to the system.
3. On a quote, it opens the quote's actions.
4. With the menu open, it closes the menu.
5. Otherwise by zone (`zoneOf`): the top left corner switches between the light scheme and the dark scheme (Night or OLED) chosen last, the top right corner sets or clears a bookmark (both can be switched off), the middle of the page opens the menu, and the rest turns pages by the scheme chosen — right forward and left back, bottom forward and top back, anywhere forward, or not at all.

Swipes turn pages along the direction chosen in "Turn pages": sideways or up and down. Volume keys turn pages. Sliding along the left edge changes brightness when that is switched on. With "Darker without flicker" on as well (`ReaderPrefs.dimBelowFlicker`, off by default), the slide lowers the backlight only down to 30 % of its range; further down the backlight stays there and the app darkens the page itself, up to 80 % at the bottom of the slide (`Dimming`). Many OLED screens flicker at low backlight, and readers differ widely in how much that bothers them; keeping the backlight up and darkening the page is community practice, not research. The menus and sheets are not darkened.

Holding a word selects it; dragging on without lifting the finger stretches the selection by whole words, across lines, and does not turn the page. Handles hang under both ends of the selection: dragging one moves that end, and the other stays. The selection offers four highlight colours, a note, copy, share, the system's text-processing apps (translators, dictionaries) and a web search. A quote's actions are the same, with a star and deleting in place of the web search. Not done: stretching a selection onto the next page.

### Menu

The middle of the page shows two bars. Top: back, title, author, chapter, search, contents, settings. Bottom: a button that returns to where the last jump was made from, "N of M", the bookmark toggle and a position slider. Back also returns from a jump before it closes the book.

Contents, bookmarks and quotes are three tabs of one sheet; each entry jumps to its place. A quote with a star shows it in the list. Search lists every match with the words around it.

A thin line at the bottom edge of the page shows the position in the book, with a dot where each top-level chapter starts.

### Settings sheet

In ReadEra's order: turn pages, colour scheme, font, font size, boldness, contrast, line spacing, alignment, hyphenation, paragraph spacing, paragraph indent, page margins; then two pages in landscape, the turn scheme, the gesture options, the warm filter and how long the screen stays on. Sizes are stored in dp. While text is justified and hyphenation is off, a note under the hyphenation switch says that the gaps between words will be wide; the reader's choice stays as it is. Under the page margins switch a line says about how many characters a line holds (`EngineDocument.charsPerLine`): the width between the side margins of one page over the average width of a character, measured in the regular font, size and weight on up to 1000 characters of the book's own text from the page shown on, so the letters are those of the book's language. It is counted when the book is opened and laid out again, and left out when the pages there have too little text, as pictures and title pages do. Readers prefer lines of about 55 characters, and Butterick recommends 45 to 90 (https://practicaltypography.com/line-length.html).

Differences from ReadEra, all open to change: the schemes are Day, Night, OLED and Sepia only; margins are a switch between wide (34 dp at the sides, 16 dp at the top, 23 dp at the bottom) and narrow (8 dp, 12 dp and 20 dp), narrow by default; the strip the page leaves clear under a camera cutout counts as top margin, so the text starts right under it. The widths are ReadEra's, measured on screenshots of both apps on the same 560 dpi phone on 2026-10-13: ReadEra's sides go from 30 px to 120 px with the switch, its top from 156 px to 160 px, counted to the nearest letter; its last line ended 75 to 161 px from the bottom edge over seven pages. At ReadEra's font size 62 and Perigee's 18 dp, both Roboto, the letters are the same: x-height 33 px, stroke about 7 px. Lines are 86 px apart in ReadEra at its 120 % and 81 px in Perigee at 110 %, which is the same spacing per 100 %; line spacing is 120 % by default since, as in the reader's ReadEra; font size is in dp, not ReadEra's units.

### Colour schemes

Day is the scheme a new reader starts with, and it stays so (`ColorSchemeTest`). Dark text on a light page gives better proofreading and acuity than light text on a dark one, mostly through a smaller pupil, and the effect is strongest at small sizes; evidence that a dark scheme reduces fatigue is thin and mixed (https://cogsci.nl/blog/is-bright-text-on-a-dark-background-a-good-idea). Night and the other dark or warm schemes are offered for taste and for dark rooms. Neither the app nor the store texts present them as better for the eyes or for sleep.

### Warm filter

"Warm page by the clock" (`ReaderPrefs.warmFilter`, off by default) tints the page warm between two hours the reader chooses, 22:00 to 07:00 by default, the hours may run over midnight (`Warmth`). The page and the page-coloured strips around it are multiplied by a warm colour: red stays, green and more of blue go, by five steps of strength, the strongest being `#FFB266`; the menus and sheets keep their colours. It is offered for comfort only. Cochrane 2023 found that blue-light filtering lenses probably do not reduce eye strain, and a randomised study of iOS Night Shift found no effect on sleep, so neither the app nor the store texts claim either.

### Screen on

While a book is on screen the reader keeps the screen on (`FLAG_KEEP_SCREEN_ON`) for the time chosen in "Keep the screen on": as the system sets, 2, 5, 10 or 20 minutes, or while the book is open; 10 minutes by default, as ReadEra keeps it ten minutes longer than the system does. Every touch and every new page, volume keys included, start the time again (`keepScreenOn`). When it runs out the flag is cleared and the system timeout takes over, so the screen goes off that much later. Leaving the book or the app clears the flag.

## Tests

The engine is covered by instrumented tests in `engine/src/androidTest`, run with `./gradlew :engine:connectedDebugAndroidTest`:

- `EngineDocumentTest`, `EngineFeaturesTest` — one or more tests per method and per setting, on books generated by the tests.
- `BookReaderTest` — page turning, drawing ahead, layout changes, calls from many threads, closing one book while another opens.
- `:app`, `ReaderScreenTest` — the screen as the reader uses it: opening, turning by touch and swipe, reopening at the saved place, rotation, a changing screen size, menu, contents, bookmarks, search, settings, quotes, notes and stars, a quote made on the server appearing in the open book. `ZoneTest` covers the touch zones, `ScreenOnTest` the time the screen stays on. `:data`, `LibraryTest` covers what is stored.
- `ShippedFontsTest` — the app's fonts are offered, each draws a Russian page its own way, and Noto Serif is the first fallback.
- `ControlPagesTest` — control pages compared with accepted pictures in `engine/src/androidTest/assets/golden`. The pages are set in PT Serif from the test assets, so they do not depend on the fonts of the device. The class comment says how to accept a new look.

## Rotation

While a book is open the screen keeps the orientation it has, as in ReadEra: the activity is locked (`SCREEN_ORIENTATION_LOCKED`). When the device is held another way for a moment, a button in the bottom corner offers to turn the screen, for four seconds; pressed, it lets the system turn the screen the way the device is held, and the new orientation is locked in turn. The activity is not made anew on a rotation (`configChanges`): Compose lays the screen out again in it. The button asks for the very orientation it showed, not for whatever the sensor says when it is pressed; a turn that does not come within two seconds leaves the screen locked again. Upside down is not offered. A rotation to the area the screen settled at before in that orientation is laid out at once; any other new size waits 100 ms for the screen to settle. Touches count again as soon as the new page is shown; the contents' page numbers are counted after it. The reader logs page areas, layouts and the second copy with their times under the tag `Perigee.Reader`.

A second copy of the book is kept laid out for the screen turned the other way (`BookReader.prepareSpare`); a rotation to that page size swaps the copies and shows the book at once, and the copy shown before waits for the way back. The copy is opened without the engine's cache, which belongs to the first. It is laid out 0.3 s after the book was laid out — opened, turned, set anew — and three seconds after the reader last turned a page, as it holds the engine for as long as a rotation would. On the reader's phone, with the delay at three seconds after every layout, a reader turning the screen every three seconds never got the copy: every rotation was laid out anew in 0.4 to 0.5 s, the system's turning of the screen taking under 0.1 s of that. The page area of the other orientation has to be the very one the screen gives: it is learnt from the screen the first time the book is shown in that orientation and kept across starts (`KnownViewports`), so the first rotation after the app is installed is the slow one. Settings changed since the copy was laid out make it miss, and it is laid out again after the next pause. On the emulator, after a pause, the text was on screen 0.5 to 0.8 s after the button was pressed, the system's turning of the screen included. Meanwhile the screen is the colour of the page with the busy mark, not the old page cut to the new shape.

A new page size and new settings are laid out in one go, at the new size (`EngineDocument.apply` with a width and height), and the stylesheet is given to the engine again only when it changed: before, a rotation laid the book out twice and styled it afresh. Text is shaped by HarfBuzz without ligatures (the engine's kerning mode 2), and in full (mode 3) only for books in scripts whose letters join or reorder: Arabic, Persian, Urdu, Hebrew, Yiddish, the scripts of India and Sri Lanka, Thai, Lao, Khmer, Burmese, Tibetan and a few more, by the book's language. Measured on the reader's phone on a 2.8 MB FB2 of 1620 pages, a rotation lays the book out in 4.0 s with full shaping, 0.8 s with light shaping, 0.75 s with FreeType kerning or none; hyphenation does not count. A Russian page comes out the same to the pixel either way; an English one loses the fi, fl and ffi ligatures and keeps its kerning pairs. A Russian book with Arabic passages in it gets light shaping, and the Arabic is drawn wrong.

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
