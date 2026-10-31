# Word gaps of justified lines, measured in crengine

A desktop build of the pinned crengine with a hook in `lvtextfm.cpp` that writes one CSV row per justified line. The `gaps` CLI lays a book out the way the app does; `gaps-report.py` turns the rows into the table of `engine/tools/word-gaps.py`, so the engine's figures and the model's can be compared row by row. Written for issue #99.

## Files

- `lvtextfm-gaps.patch`: the hook. `alignLine()` calls it for every `LTEXT_ALIGN_WIDTH` line, right before the leftover width is spread over the gaps or, when negative, taken out of them. It reads `PERIGEE_GAPS_CSV` once. When the variable is unset it returns and changes nothing. Columns: `face,size_px,space_px,usable_px,natural_px,extra_px,gaps,words,chars,hyphen,node,y`. `space_px` is the font's advance of U+0020 at that size; `extra_px` is `usable_px - natural_px` and goes negative on a condensed line; `chars` counts spaces too; `hyphen` is 1 when the last word ends in a hyphen; `node` and `y` identify the line. With `PERIGEE_GAPS_CAP_EM` (ems of the line's font) a line of two gaps or more whose gap, `space_px + extra_px / gaps`, would be wider is aligned left instead, and the last column `capped` is 1; unset, `capped` is always 0 and the line is laid out as before. With `PERIGEE_GAPS_LOOKAHEAD=1` the line breaker checks, before it adds a justified line, the gap of that line and of the next one; when either is wider than the ceiling (`PERIGEE_GAPS_CAP_EM`, 0.75 em without it) it tries the three wraps before the greedy break and keeps the one that gives the pair a narrower worst gap by a pixel or more. The column `lookahead` after `capped` is 1 on a line whose break moved. With `PERIGEE_GAPS_PARAGRAPH=1` a justified paragraph is broken as a whole: before its line loop, `perigeePlanParagraph()` lists the wraps and the hyphenation points of the paragraph, measures each possible line from the cumulative widths and picks the breaks of the lowest total badness by dynamic programming; the line loop then runs as before and takes the planned break in place of its own (`perigeeTakePlannedBreak()`). `PERIGEE_GAPS_TOLERANCE` (300) is how far a line may stretch on the first pass, in percent of its stretch, and `PERIGEE_GAPS_HYPHEN_PENALTY` (50) what a hyphen costs; the cost function is in `docs/spikes/word-gaps-preview.md`. Paragraphs with images, inline boxes, floats, BiDi, CJK, initial letters or `::first-line` stay greedy, and the lookahead does not run on a planned paragraph. The columns `paragraph` (1 on a line of a planned paragraph) and `cost` (the plan's total, 0 when greedy) follow `lookahead`. `PERIGEE_GAPS_PARAGRAPH_LOG` names a file with one row per planned paragraph: `node,chars,candidates,edges,lines,pass,cost,us,hyphenation_us` (`pass` 0 when no plan fits, a `#dropped` row when the line loop could not take a planned break). `PERIGEE_GAPS_DOUBLE_HYPHEN` (10000) is what it adds for two hyphens in a row. With `PERIGEE_GAPS_REPAIR=1` the greedy lines stay and only the lines around a loose one are reworked (`perigeeRepairParagraph()`): `PERIGEE_GAPS_REPAIR_TRIGGER` (0.75 em) is the gap that opens a window, `PERIGEE_GAPS_REPAIR_BACK` (4) and `PERIGEE_GAPS_REPAIR_FORWARD` (2) its lines, `PERIGEE_GAPS_REPAIR_OK` (0.5 em) the gap that costs nothing, `PERIGEE_GAPS_REPAIR_CHANGE` (1) and `PERIGEE_GAPS_REPAIR_LINE` (5) what a changed line and any line cost, and `PERIGEE_GAPS_DOUBLE_HYPHEN` defaults to 20 there. Its lines are marked `paragraph`, its log rows have `pass` 3, and the log's last columns `changed` and `greedy_lines` give the lines it changed. `gaps` clears `PERIGEE_GAPS_HYPHEN_PENALTY` unless `--hyphen-penalty` is given, so set that one by the flag. Unset, the breaker runs as before. The CSV stream is `perigeeGapsCsv()`, which `gaps` also writes its `#page,N` markers through.
- `build.sh`: copies `third_party/crengine` to `$BUILD/crengine-src`, applies the app's patches from `engine/src/main/cpp/patches` and then this one, and builds. `$BUILD` defaults to `${TMPDIR:-/tmp}/perigee-gaps-build`; downloads, objects and compiler temp files (`TMPDIR`) stay there.
- `CMakeLists.txt`: the source list of `crengine.cmake` with FreeType, HarfBuzz, FriBidi, libunibreak, utf8proc and zlib on; image codecs, zstd, MathML, CHM, antiword, md4c, LunaSVG, stb, SRELL and fontconfig off. The archives and hashes are those of `deps.cmake` (FreeType 2.14.3, HarfBuzz 14.5.1, FriBidi 1.0.17, libunibreak 7.0, utf8proc 2.12.0, xxHash 0.8.4), fetched by `fetch.cmake`. zlib comes from the system.
- `gaps.cpp`: sets what `engine_jni.cpp` sets in `create`, `applySettings` and `load` (the size of `face_size.h`, DOM version, block rendering flags, hinting 2, light kerning, condensing floor 75 %, floating punctuation, `fb2.css` plus `EngineSettings.extraCss`, the book's language). Margins are zero, so the text width is `--width-px`. It then draws every page into an off-screen buffer.
- `mkbook.py`: makes an FB2 from Markdown posts or a plain text file, one `<p>` per paragraph, without code blocks, images and paragraphs holding a URL.
- `pgm2png.py`: converts the PGM pages of `gaps --png` to grayscale PNG with the standard library; `--crop` drops the white rows at the bottom.
- `gaps-report.py`: per CSV and face, gap p50/p90/p99 in em and the share of lines over 1.5, 2, 2.5 and 3 times the space. As in the model, only lines with two gaps or more count. Two more columns follow: characters per line and the share of hyphenated lines; then the `ragged`, `moved` and `para` shares, the `widest` gap and the share of all lines whose gap is over each `--over` em. `--costs` reads paragraph logs instead.

## Commands

```sh
engine/tools/desktop/build.sh            # about 80 s from a clean $BUILD on 8 cores

GAPS=${TMPDIR:-/tmp}/perigee-gaps-build/cmake/gaps
R=runs                       # anywhere; README paths below assume the repository root
F=engine/src/main/assets/crengine/fonts
$GAPS --book BOOK.fb2 --font Roboto=$F/Roboto-Variable.ttf --face Roboto \
    --size-px 28 --width-px 1032 --height-px 2000 --hyphenation on --out $R/Roboto-28-1032-on.csv
python3 engine/tools/desktop/gaps-report.py --lang ru_RU $R/*.csv
```

`--font NAME=PATH` repeats; NAME must be the family the engine reads from the file (`PT Serif`, not `PTSerif`), and `gaps` lists the registered families when it is not. `--cap-em 0.75` sets the ceiling. `--lookahead on` sets `PERIGEE_GAPS_LOOKAHEAD`; image names then end in `-lookahead`. `--paragraph on` sets `PERIGEE_GAPS_PARAGRAPH`, image names end in `-paragraph`; with it `--tolerance`, `--hyphen-penalty` and `--paragraph-log FILE` set the other three variables, and `gaps` writes a `#draw` row into the log after `Render()`. `--repair on` sets `PERIGEE_GAPS_REPAIR`; image names end in `-repair`. `--lang ru-US` replaces the language of the book, which picks the hyphenation dictionary. `--text FILE` writes `view.getPageText()` of every page after a `#page,N` line. `gaps` prints the time of `Render()` and of drawing the pages. `--png DIR` saves every page (or those of `--pages 3,17,42`) as `<book>-p<page>-<face>-cap<cap or none>.pgm`. Before each page `gaps` writes a `#page,N` row; rows before `#page,1` were formatted during loading and count as page 0. `gaps-report.py` skips these rows, reports the `ragged` share and gives gaps of the lines left justified, the `moved` share and the `widest` gap; `--by-page --over 0.75 0.5` lists the pages with the most wide gaps; `--costs LOG` gives the passes, the cost distribution and the time of the paragraphs planned in `Render()`. A paragraph is attributed to the page where it was first formatted, so a line carried to the next page counts on the previous one. `--same-size off` sets the face at `--size-px` itself and leaves its line height alone, as the app did before `face_size.h`; `gaps` prints the em, the x-height, the capitals and the font height drawn. `--switch-face NAME` lays the book out, then changes the face as `applySettings` does in an open book, prints how long the measuring and the layout took and stops. Faces other than Roboto are measured against it, so Roboto must be among the `--font`s. `--user-css FILE` appends the file's text after the app's rules of `EXTRA_CSS`, as the app appends the CSS the owner wrote in the reader's settings, so a run sets the book as the owner sees it. Optional flags: `--render-dpi` (336 by default, the app's value on a 560 dpi phone), `--min-space-condensing` (75), `--weight` (400), `--line-spacing` (120), `--data` and `--css`. A run over the 900 KB test book takes about a second.

On macOS `build.sh` needs cmake on the `PATH`; it sets the aliases for the file calls crengine names with a 64 suffix itself.

## Caveats

- **Lines are justified only when drawn.** During `Render()` crengine formats paragraphs "light", without `alignLine()` (`lvtinydom.cpp`, `requestLightFormatting`). Calling `goToPage()` without drawing gave 825 rows for 158 pages; drawing every page gives all 1500 paragraphs. A paragraph split over two pages could be formatted twice, so the report drops repeats by `node` and `y`. None occurred on the test book.
- **Optical size depends on DPI.** crengine sets `opsz` to `size_px * 72 / render DPI` (`lvrend.cpp:2445`). At 28 px and DPI 336 that is 6 pt, clamped to Literata's minimum of 7. There Literata's space is 0.25 em, not the 0.20 em of the default instance, and its letters are wider. With `--render-dpi 149` the axis gets 13.5 pt, the value the engine sets at 18 dp (63 px) on the owner's 560 dpi phone. The other three faces have no `opsz` axis.
- **The space is whole pixels.** `space_px` is a hinted advance (Roboto 7 px for 6.95, Literata 6 px for 5.6), so the em figures carry up to 0.02 em of rounding. Whether the engine lays lines out with exactly this width was not checked.
- **A condensed line's gap is an average.** The engine takes the shortfall out of the spaces in proportion to how much each may shrink, not exactly evenly.
- No fallback fonts are registered: the test book needs only Cyrillic and Latin.

## Results

Test book: 1500 paragraphs of Russian prose, made into an FB2 of 900 KB (any FB2 or EPUB works). 28 px, render DPI 336 unless the name says otherwise:

```
gap widths in em; the last columns are the share of lines needing a wider gap
font           lang   width  space  lines    p50    p90    p99   >150%   >200%   >250%   >300%  chars   hyph  setting
Roboto         ru_RU     37  0.250   6483   0.34   0.56   2.92  39.4%  14.7%   7.7%   5.7%   71.3  28.5%  Roboto-28-1032-on
Roboto         ru_RU     37  0.250   6621   0.45   0.95   2.99  64.7%  43.2%  28.8%  19.0%   69.5   0.0%  Roboto-28-1032-off
Roboto         ru_RU     30  0.250   7967   0.35   0.60   2.07  41.9%  17.7%   9.0%   5.6%   57.7  34.7%  Roboto-28-840-on
Literata       ru_RU     37  0.250   7648   0.37   0.66   2.52  48.7%  22.6%  11.6%   7.6%   60.3  34.6%  Literata-28-1032-on
Literata       ru_RU     37  0.214   6929   0.31   0.58   2.96  47.8%  22.6%  11.7%   8.0%   66.9  31.9%  Literata-28-1032-on-dpi149
PT Serif       ru_RU     37  0.250   6373   0.34   0.56   2.89  37.9%  14.3%   7.6%   5.8%   72.1  28.7%  PTSerif-28-1032-on
Comfortaa      ru_RU     37  0.286   7667   0.38   0.67   2.53  38.2%  16.9%   8.6%   6.2%   60.5  31.8%  Comfortaa-28-1032-on
```

Between 11 % and 21 % of lines are condensed below the space (Roboto 910 of 6483, Comfortaa 1646 of 7667).

### Against the model

`word-gaps.py` on the same text (paragraphs extracted from the FB2, `--min-para 0 --max-para 100000 --limit-chars 100000000`) at 36.9 em:

| face | model as in repo | model, last line left out | engine, floor 100 % | engine, floor 75 % |
| --- | --- | --- | --- | --- |
| Roboto | 0.44 (7741 lines) | 0.41 (6606) | 0.37 (6554) | 0.34 (6483) |
| PT Serif | 0.43 | 0.40 | 0.36 | 0.34 |
| Literata | 0.41 | 0.38 | 0.34 (DPI 149) | 0.31 (DPI 149) |
| Comfortaa | 0.55 | 0.51 | 0.44 | 0.38 |

The engine's Roboto median is 23 % under the model's, so the harness was checked before these numbers were trusted:

- Text width: 4944 of 6483 rows have `usable_px` 1032. Another 1482 have 999, the first lines with their 1.2 em indent; the rest differ by a pixel for hanging punctuation.
- Font size: every row has `size_px` 28.
- Lines: the justified lines hold 465 008 characters of the book's 521 072. The 56 064 left are the paragraphs' last lines, which are not justified.

Two causes account for most of the gap:

1. **The model justifies the last line of each paragraph.** Its docstring says it does not, but `measure()` adds a gap for every line of three words or more, and a last line's leftover is large. A copy that skips last lines (`if len(line) > 2 and index < len(words)`) brings Roboto from 0.44 to 0.41 em and p90 from 3.01 to 0.81 em. Its line count, 6606, is then close to the engine's. The file in the repository was not changed.
2. **The model never condenses.** The engine shrinks spaces to 75 %, fits more words and leaves less over. With `--min-space-condensing 100` the engine's Roboto median rises from 0.34 to 0.37 em.

About 10 % remains on every face. A guess, not checked: crengine hyphenates more than the model, which splits only plain words of six letters or more, and it also hyphenates a second time and can break inside URLs. On the model's 30 em, where it promises about 60 characters a line, the engine's Roboto gives 57.7 characters and a median gap of 0.35 em.

### Line width on the owner's screen

Not derived. The reader spec gives a 560 dpi phone, narrow margins of 8 dp (28 px a side) and an 18 dp (63 px) font. It does not give the screen width in pixels, and the repository has no other source for it.
