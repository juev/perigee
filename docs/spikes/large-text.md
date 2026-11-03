# Large text: sizes above 40 dp and two pages in landscape (issue #154)

Measured before the range was changed, on the engine built from the pinned crengine with the app's patches and the
app's unchanged `engine_jni.cpp`, called through its JNI entry points with host stand-ins for JNI and bitmaps. This is
native evidence on a desktop, not an Android or Compose run, not a phone timing and not a clinical result. The
maximum chosen from it, 48 dp, is a conservative initial limit with more measured headroom than 56 dp under the
engine's limits; it is not a
recommended size for anyone.

## Inputs

Synthetic books written for the measurement, in English and Russian, each as FB2 and as EPUB (the EPUB with a
stylesheet linked from every chapter): a start and an end marker, headings, six chapters of prose, a long word, a long
URL, a 44-character token with no break opportunity, code (`<pre>` in EPUB, `<code>` paragraphs in FB2) with a
72-character line, two GIF pictures, six short notes and one long note of eight paragraphs. Two more EPUBs check face
sizing with the override off: one whose CSS names Literata, one carrying the repository's test font
`engine/src/androidTest/assets/fonts/PT_Serif-Web-Regular.ttf` (OFL). Settings are `ReaderViewModel.laidOut` with
the default preferences: Roboto, narrow margins, 120 % line spacing, justified, hyphenation on.

The desktop build decodes GIF only; the other picture formats of the app were not covered.

## Size, characters per line, pages

276 cold processes over 100 configurations, three repeats of all but twelve. Every opening laid the book out once, and
the first page drawn laid out nothing more; page counts were the same in every repeat. `cpl` is the app's own
`charsPerLine` at a prose paragraph; "0" means its sample of four pages held under 100 characters, when the app hides
the hint. Load times are medians of three, tens of milliseconds on small books on a desktop: they show no unbounded
growth and nothing about phones.

720 × 1280 at 320 dpi, portrait:

| dp | px | x-height px | en.fb2 pages / cpl | ru.epub pages / cpl | load ms, en.fb2 / ru.epub |
|---|---|---|---|---|---|
| 18 | 36 | 19 | 108 / 42 | 115 / 37 | 30.1 / 29.4 |
| 40 | 80 | 42 | 563 / 19 | 604 / 16 | 35.4 / 41.9 |
| 48 | 96 | 51 | 791 / 16 | 840 / 13 | 37.6 / 42.7 |
| 56 | 112 | 59 | 1171 / 13 | 1236 / 11 | 37.7 / 47.1 |
| 64 | 128 | 68 | 1590 / 12 | 1680 / 10 | 39.1 / 46.1 |

Landscape at 1280 × 720, 320 dpi: one page across the width holds about twice the characters of each of two pages at
the same size (en.fb2 at 40 dp: 35 against 16; at 64 dp: 22 against 10).

## The engine's limits on size

`face_size.h` sets every face at the em that gives it Roboto's x-height, and the view caps the em at 256 px. Largest
em asked for across the eight shipped faces, Literata named by a book's CSS and the embedded test font, with the
override off for the last two:

| dp | at 560 dpi | at 640 dpi |
|---|---|---|
| 48 | 178 | 203 |
| 56 | 207 | 237 |
| 64 | 237 | 271 |

At 64 dp and 640 dpi five of those cases were capped to 256 px and drawn 3 to 5 % smaller than asked; below the cap
every x-height was within 0.7 % of Roboto's. The size of a single element has a limit of its own in `lvrend.cpp`,
340 px: the test books' `h1` at 1.6 em reached it at 56 dp and 640 dpi, and at 64 dp. Densities above 640 dpi and book
faces with smaller letters than those measured were not covered.

## What can be read at the larger sizes

Checked at 40, 48, 56 and 64 dp on the four books, portrait and landscape, one and two pages, with page rectangles
taken from the engine (`getPageRectangle`): every footnote segment of every note was drawn and holds ink, with no gap
and no overlap in each note's coverage; the first and last word of each note were found in every part they were split
into (234 of 2016 were in several parts); the markers, the long word, the URL, the token and the code lines were found
on every screen they occupy. These are checks of known places, not of every glyph of every book.

All the failures were in two pages in landscape: the hyphen of a word broken at the end of a right-hand line hung past
the edge of the screen. The app lays two pages out on a canvas wider than the screen, with side margins twice the
chosen one, and the screen crops the outer half of each; the engine lets hanging punctuation and hyphens use up to
80 % of the margin it was given, which is more than the screen shows once the letters are large. Every screen of the
four books was drawn and the ink counted in the cropped strips: none at 18 dp, some screens of the Russian books
already at 40 dp, the English ones too from 48 dp. Each sampled shape was a thin horizontal bar, as a hyphen is;
none was letter-sized. That sampling is not proof of what every hidden pixel belongs to.

Two ways to stop it were tried on the measurement build. Turning hanging punctuation off removed the cut ends but
changed the drawing of every book at every size, the default included. Limiting the overhang to the margin the screen
shows removed them at every size and left every screen of 18 dp two-page, one-page and portrait layouts the same to
the pixel; the app now does the latter (`docs/specs/reader.md`, Two pages).

`large-text.csv` holds every configuration above with compact notes: book, size, screen, layout, the em drawn, pages,
the app's characters-per-line figure, the median load time and the layouts made on opening and on the first draw.

## The change, checked on the same books

The engine was built again from the changed tree (all four patches, `0004-overhang-limit.patch` included) and driven
through the changed `engine_jni.cpp` with the settings `laidOut` now gives; 122 checks, all passed:

- Every screen drawn, two pages, 18, 40 and 48 dp, at 1280 × 720 / 320 dpi and 2160 × 1080 / 560 dpi: no ink cut off
  on any screen, where the unchanged engine had it on 59 to 73 screens of each Russian book at 40 dp and on 237 to 980
  screens of a book at 48 dp (the English books from 48 dp on the 560 dpi screen); the same number of pages everywhere.
- Unchanged to the pixel against the unchanged engine, every screen: two pages at 18 dp, and one page and portrait
  at 18 and 48 dp on both screens.
- At 48 dp in every layout, with compact notes and, in two pages, notes at the size of the text: every note segment,
  every part of the notes' first and last words and every screen of the marked words, URL, token and code lines
  drawn with ink, inside the screen; one layout per opening.
- On an open book (a Russian EPUB, an English FB2): 18 dp, 48 dp, two pages, one page, the limit taken away, set to
  another value and set again, back to 18 dp. Each state drew what a fresh opening at the same place draws,
  byte for byte. The initial drawing after navigation adds no layout; each subsequent settings/orientation
  change lays out once. The last state reproduces the first raster.
- The layout cache, FB2 and a stored EPUB, in a new process each time: a layout written with the limit was read back
  with no layout at all when the limit was the same; with no limit, another limit or another size the engine read the
  book from the cache, restyled it and laid it out once, and drew what a fresh opening draws.
- Two books open at once, one with the limit and one without, as a book and its spare copy: a change of the limit in
  one, and closing it, left the other's page as it was, byte for byte.

These are native checks on the desktop. Whether the app's screen, its preferences across a restart, rotation and the
spare copy behave so on a phone is for the instrumented tests listed in `docs/specs/reader.md`.
