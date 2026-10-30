# Bold word starts: which look to use

Date: 2026-10-30. Status: research; no code change yet. Task: [issue #95](https://github.com/juev/perigee/issues/95). Feature: #76, merged in `11472b4`.

The owner wants to choose the look of "Bold word starts" (the opening letters of each word in the bold face), comparing the current rule with variants that make the rest of the word thin, split it into bold/normal/thin zones, or lower its brightness. The question is which look is best on a book page, not whether it makes reading faster.

## Chosen look (2026-10-30)

**Variant 12, chosen by the owner:** the first third of each word of five or more letters (rounded up) in the 600 weight, the rest of the word in the 300 weight; words of up to four letters, text already bold, headings, code and scripts without word spaces are left alone. One toggle, no presets. This is what `ReaderPrefs.boldWordStarts` now switches on. The owner chose it from [A–D](bold-word-starts-choose-2.png) after the first shortlist; it is the only tested look within ±3% pages on all three fonts. The research harness and its test-only setter were removed from the shipped patch; they remain in commits `21d4188`/`156df85` for reference.

## The look before the research

- One letter of a word of up to three letters; half of a longer one, rounded up (`perigeeBoldLetters` in the engine patch).
- The bold part is the font's own bold face, weight 700; the rest is the reader's regular weight (400 by default, moved by the Font boldness setting).
- Every word is emphasised; the document is not changed; the option is off by default.
- Toggling it lays the book out again: bold letters are wider and the layout is per-state.

Code anchors: `engine/src/main/cpp/patches/0001-bold-word-starts.patch`, `EngineSettings.boldWordStarts`, `NativeEngine.applySettings`, `ReaderPreferences.boldWordStarts`, `Sheets.kt`. The reader spec's "Bold word starts" section is the contract.

## Evidence

All studies found either no effect or a cost; none supports a speed or comprehension claim.

| Study | Readers and stimuli | Result |
| --- | --- | --- |
| Readwise, n=1916 | reading test | 325 vs 328 wpm, no significant difference |
| Snell 2024, *Acta Psychologica* | 32 readers, 100 paragraphs | no significant difference in reading time; "does not facilitate reading" |
| Beelders 2025, *J Eye Mov Res* | 53 readers, eye tracking | fixation duration, fixation count and reading speed did not differ; fixations stayed spread across the word; no help with low-frequency words |
| Spear et al. 2025, *Atten Percept Psychophys* | five bolding positions | bolding the first half did not help and led to costs against regular unbolded text; access to the first few letters was not enough to recognise the word |
| Zhang et al. 2026, *Acta Psychologica* | L2 academic reading, five conditions | Bold Start and Bold End: no reliable facilitation; Bold to Gray and Gray to Bold (stronger within-word salience): lower reading efficiency, no comprehension gain |

The vendor's method has three parameters — Fixation (how much of a word), Saccade (how often a word is emphasised) and Opacity (how visible the emphasis is) — and publishes no numeric values (https://bionic-reading.com/br-method/). The feature keeps its own name and rules; the trademark and patent notes are in #76.

Consequence for this task: the look is a matter of taste; the default stays off; the design should be the least intrusive one the owner likes, with no claims of faster or easier reading. Stronger within-word salience (a thin or dim tail) is the riskiest direction.

## What the engine and fonts allow

**Positions.** The patch splits a text node into sources with offsets, so xpointers, highlights, bookmarks, search hits and server positions stay on the same text. Any new look must keep that property; more parts (three zones) are still offsets into the same node.

**Weights.** The app ships variable and static families:

| Family | Cuts | 300 tail | 600 bold |
| --- | --- | --- | --- |
| Roboto, Literata, Merriweather, Noto Serif, Comfortaa | variable (`wght`) | real light face | real semibold |
| PT Serif, PT Sans, Droid Serif | regular/bold/italic | falls back to 400 | snaps to bold |

So a thin tail is only honest on variable fonts. The engine does not synthesise a lighter face; asking for 300 on a static family selects 400. A 600 or 650 bold part is likewise a real face only on variable fonts.

**Dimming.** The tail would be blended towards the page colour. WCAG 2.2 asks for at least 4.5:1 for body text (https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html). The app's schemes:

| Scheme | Text / page | Base | Dim 10% | 20% | 30% | 40% | Highest dim at least 4.5:1 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Day | `#000000` / `#FFFFFF` | 21.0 | 17.4 | 12.6 | 8.6 | 5.7 | about 44% |
| Night | `#C8C8C8` / `#1E1E1E` | 9.96 | 8.3 | 6.85 | 5.57 | 4.46 | about 39% |
| OLED | `#FFFFFF` / `#000000` | 21.0 | 16.8 | 13.1 | 9.9 | 7.4 | about 55% |
| Sepia | `#644D32` / `#F0DEC7` | 6.03 | 4.81 | 3.90 | 3.19 | 2.62 | about 13% |

A single global percentage cannot work: 20% already fails Sepia, 40% fails Night. Dimming only makes sense with a per-scheme cap, or not at all. WCAG also warns that thin fonts may render fainter than their nominal colour through anti-aliasing; the app's contrast gamma is global and cannot fix a thin tail alone.

**Page cost.** The reader spec records 932 pages with the option on against 776 without, on a 5.9 MB Russian FB2 at 1080×2200 px — about 20% more. A rough advance-width estimate (Roboto 700 for half of each word, 400 for the rest) is only about 0.8% wider, so glyph width alone does not explain it. Two candidates must be measured: line breaking/justification and loss of hyphenation when a word is split into sources. If splitting kills hyphenation, that is a bug to fix before choosing a look. Lighter variants (one letter, 1/3, 600, long words only, thin tail) should all reduce the cost; measure rather than assume.

**Contrast gamma.** `EngineSettings.contrast` raises glyph coverage for all text; on a high step a 300 tail may look closer to 400, on a low step it may look washed out. Test the tail at contrast 0/2/4.

## Variant matrix

Parameters: bold fraction (Fixation), bold weight, middle zone, tail weight, opacity of the tail or of the bold part (Opacity), and which words are emphasised (Saccade). `1/2` is today's rule; `1/3` and `2/3` round up the same way.

| # | Bold part | Bold weight | Middle | Tail | Emphasis | Risk |
| --- | --- | --- | --- | --- | --- | --- |
| 0 | — | 400 | — | 400 | — | control |
| 1 | 1/2 | 700 | — | 400 | every word | today |
| 2 | one letter | 700 | — | 400 | every word | smallest anchor |
| 3 | 1/3 | 700 | — | 400 | every word | less bold |
| 4 | 2/3 | 700 | — | 400 | every word | more bold |
| 5 | 1/2 | 600 | — | 400 | every word | softer emphasis |
| 6 | 1/2 | 800 | — | 400 | every word | harder emphasis |
| 7 | 1/2 | 700 | — | 300 | every word | variable fonts only |
| 8 | 1/3 | 700 | — | 300 | every word | variable fonts only |
| 9 | 1/2 | 700 | 1/4 at 400 | 1/4 at 300 | every word | three zones; variable fonts only |
| 10 | 1/2 | 700 | — | 400, dim 20% | every word | fails Sepia |
| 11 | 1/2 | 700 | — | 400, dim 30% | every word | fails Sepia; Night borderline |
| 12 | 1/2 | 700 | — | 300, dim 20% | every word | combined; variable fonts only |
| 13 | 1/2 | 700 at 85% | — | 400 | every word | softer emphasis |
| 14 | 1/2 | 700 | — | 400 | words of 5+ letters | calmer page |
| 15 | 1/2 | 700 | — | 400 | every second word | saccade |
| 16 | 1/3 | 600 | — | 300 | words of 5+ letters | least intrusive candidate |

Stage 1: all variants on one page, Roboto at 18 dp, Day. Stage 2: the best three across Roboto, Literata, Noto Serif and PT Serif (static control); 16/18/24 dp; Day, Night, OLED, Sepia; contrast 0/2/4; the reader's font weight 400/450/500 (the tail follows it). Stage 3: the full 5.9 MB FB2 for page count and layout time. Stage 4: the owner picks from contact sheets.

## Testing without a phone

The emulator is enough. The engine renders pages to `Bitmap`s in instrumented tests, and `ControlPagesTest` saves them as PNGs for the accepted pictures. A cold run took 26 s; an incremental one 3 s:

```
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ANDROID_SERIAL=emulator-5554 \
  ./gradlew :engine:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=org.evsyukov.perigee.engine.EngineFeaturesTest#boldWordStartsDarkenTheStartAndLightenTheTail
```

The look harness (`BoldWordStartsLookTest`, with the test-only setter) lived on the research branch; it was removed from the shipped patch after the choice. Its command is in commit `21d4188`.

The AVD is `shareding_api36` (Pixel 6, API 36); start it with:

```
~/Library/Android/sdk/emulator/emulator -avd shareding_api36 -no-window -no-audio -no-snapshot-save -read-only
```

Pictures are saved to `engine/build/outputs/connected_android_test_additional_output/` (see `ControlPagesTest.save`; AGP copies the directory off the device). The last run put `english.png` under `.../debugAndroidTest/connected/shareding_api36(AVD) - 16/`; a new rendering test can save its pages the same way and write one PNG per variant.

Two ways to get the variants:

1. **Markup prototype, no engine change.** Generate an HTML page whose words are split into `<b>`/`<span>` parts with the wanted weights and colours, and render it with the existing engine. Fast, but the document and splitting are not the real patch; hyphenation and positions are approximate. Use it only for the first contact sheet.
2. **Engine prototype, test-only.** Add a setter for the look to the patch and a `NativeEngine` test hook so the real path renders it. This is the only way to get honest page counts and to prove positions and hyphenation still hold. Keep the setter out of the shipped settings; the chosen look then goes through `EngineSettings` and `ReaderPrefs` as usual.

The emulator's LCD and fixed DPI are not the owner's OLED phone. Relative differences in weight, contrast and page density carry over; absolute appearance does not. The phone is needed only for the final in-hand check, and `:app` instrumented tests must not be run on it because they wipe the app's data; install a debug build and read instead.

A desktop crengine build is not worth it: the app's CMake is Android/NDK, desktop targets pull Qt/wxWidgets, and the emulator is the real target and fast enough. A reading-speed trial is not the criterion either: the studies found no effect and a self-test with one reader has no power. A blind preference choice between contact sheets is more useful.

## Measurements

- Contrast of every part against the page, per scheme; 4.5:1 floor for body text, 7:1 desirable. Dimming only with a per-scheme cap.
- Ink of the tail at 16/18/24 dp against the 400 tail. A Pillow check on Roboto at 28 px gives a 300 tail about 70% of the 400 ink; check whether it survives small sizes and high/low contrast.
- Page count and layout time on the 5.9 MB FB2; re-check the 932 vs 776 figure. Target no more than about 3% more pages for the chosen look, or an explicit owner decision.
- Hyphenated line ends and wide word gaps with the look on and off. If splitting words kills hyphenation, fix that first.
- Positions: `EngineFeaturesTest` bold-start cases, highlights, search and `BookReaderTest.boldWordStartsKeepThePlace` stay green with the new look.
- Visual contact sheets at 1:1 and zoomed; owner picks.

## What the run found (2026-10-30)

Harness: 500 generated Russian paragraphs (about 260k characters), 720×1280 at 320 dpi, Roboto 18 dp, Day, justified with hyphenation. One page per look and the page count of the whole book; the same book in Literata (variable) and PT Serif (static) for the page count. Contact sheets: [Roboto, all looks](bold-word-starts-contact-sheet.png), [first lines at 1:1](bold-word-starts-crops.png), [PT Serif](bold-word-starts-ptserif.png).

Page count, plain compared with the look:

| Look | Roboto | Literata | PT Serif |
| --- | --- | --- | --- |
| plain | 334 | 448 | 358 |
| current: 1/2, 700 | 334 (0%) | 474 (+5.8%) | 381 (+6.4%) |
| one letter, 700 | 334 (0%) | 448 (0%) | 381 (+6.4%) |
| one third, 700 | 334 (0%) | 448 (0%) | 381 (+6.4%) |
| one third, 600 | 334 (0%) | 448 (0%) | 381 (+6.4%) |
| one third, 600, words 5+ | 334 (0%) | 448 (0%) | 381 (+6.4%) |
| semibold: 1/2, 600 | 334 (0%) | 448 (0%) | 381 (+6.4%) |
| thin tail: 1/2, 700→300 | 334 (0%) | 448 (0%) | 381 (+6.4%) |
| long words: 1/2, 700, 5+ | 334 (0%) | 474 (+5.8%) | 381 (+6.4%) |
| candidate: 1/3, 600, thin, 5+ | 334 (0%) | 422 (−5.8%) | 358 (0%) |

What this says:

- **Roboto's bold has almost the same advances as its regular.** Every look leaves the line breaks and the page count unchanged: 334 pages. The reader spec's 932 against 776 (+20%) is not reproduced on this book; the cause is likely another font, book or patch version. It must be re-measured on the real 5.9 MB book before it is used as a reason to reject the current look.
- **Literata and PT Serif have wider bold faces.** The current look adds 5.8% and 6.4% of pages. A smaller bold part or a 600 weight removes the cost on Literata; on PT Serif even one bold letter per word still adds 6.4% (381 against 358), because the bold advances are wide. The long-word filter alone does not help Literata: this Russian text is mostly long words.
- **The engine does synthesise a lighter static face.** `loadAndCache` uses `FT_Outline_Embolden` with a negative strength when the requested weight is at least 25 away from the static face (`lvfntman.cpp:7776`), so a 300 tail in PT Serif is not a 400 fallback: it is a thinner PT Serif. The PT Serif candidate page looks like PT Serif, and its 358 pages equal plain. The earlier "thin tail only in variable fonts" constraint is therefore wrong; the question is whether the synthesised face is acceptable.
- **Only the candidate** (1/3 of the word in a 600 semibold, the tail in a 300 thin, only words of five letters or more) keeps the page count within ±3% on all three fonts. It is 5.8% denser on Literata, which should be checked against `charsPerLine` and taste, and it is exactly plain on PT Serif.
- **The word gaps** are visible in the sheets: on PT Serif and Literata the current look has wider spaces and fewer words per line; the thin tail alone does not remove the gaps on Roboto, where the line breaks do not change and justification simply stretches the spaces. The first-line gap metric (31–39 px) was not discriminating; the pictures are the evidence.
- **Ink** of the two halves of "Железнодорожное" in Roboto: plain 1329/1323, current 1898/1382, one letter 1489/1335, one third 1713/1335, semibold 1780/1381, thin tail 1882/1006, candidate 1502/899. The 300 tail is about three quarters of the plain ink of that half: visible, not invisible.

Reading the sheets:

- **Current** is the heaviest and the gappiest; it looks the most "striped".
- **Semibold** (1/2, 600) is a direct softer version of current, page-neutral on Roboto and Literata, but still +6.4% on PT Serif.
- **One letter** and **one third** are subtler anchors; on PT Serif they still widen the spaces because any 700 bold part does.
- **Candidate** is the calmest: short words stay plain, the bold part is a 600 semibold, the tail fades. On PT Serif the 600 is synthesised from bold and still reads as an emphasis; the thin tail is synthesised too.
- **Every other** and **three zones** showed no advantage over the simpler looks; every-other looks arbitrary, three-zones is close to current.

## Follow-up: weight and size gradients (2026-10-30)

The owner asked for a three-zone weight gradient (1/3 bold, 1/3 medium, 1/3 thin) and for a size gradient (large at the start, smaller towards the tail). These were added as variants 19–21; [A–D](bold-word-starts-choose-2.png) shows them next to 12:

| Look | Roboto | Literata | PT Serif |
| --- | --- | --- | --- |
| 19: 1/3, 700 / 500 / 300 | 334 (0%) | 448 (0%) | 381 (+6.4%) |
| 20: size 115 / 100 / 85, weight 400 | 364 (+9.0%) | 500 (+11.6%) | 400 (+11.7%) |
| 21: size 115 / 100 / 85 + 700 / 500 / 300 | 364 (+9.0%) | 530 (+18.3%) | 400 (+11.7%) |

- **19** is a smoother weight gradient than 12: the middle is a 500 medium instead of 400, the first part a 700 bold. It is page-neutral on Roboto and Literata and +6.4% on PT Serif; it looks closer to current than to 12, with the tail fading.
- **20 and 21** make the first part larger. The size gradient is visible as a "trumpet" word shape; the larger first part raises the line height and costs 9–18% more pages. It is a display effect, not a body-text look in this form; a milder 110/100/90 could be tried if the owner likes the idea, but the page cost will stay above the target.
- **A (12)** remains the only look within ±3% on all three fonts. **B (19)** is the closest alternative if the medium middle is preferred.

Shortlist for the owner ([pages A–F](bold-word-starts-shortlist.png), [first lines A–F](bold-word-starts-shortlist-crops.png), [one page with all six](bold-word-starts-choose.png)):

| Letter | Variant | Look | Pages Roboto / Literata / PT Serif |
| --- | --- | --- | --- |
| A | 12 | candidate: 1/3, 600, thin tail, words 5+ | 334 / 422 / 358 |
| B | 5 | semibold: 1/2, 600 | 334 / 448 / 381 |
| C | 16 | one third: 1/3, 600, words 5+, regular tail | 334 / 448 / 381 |
| D | 2 | one letter, 700 | 334 / 448 / 381 |
| E | 1 | current: 1/2, 700 | 334 / 474 / 381 |
| F | 0 | plain, no emphasis | 334 / 448 / 358 |

A is the only look meeting the page target on all tested fonts; it accepts a synthesised thin tail on static fonts. B is the simplest softer current. C is the best look without a thin tail. D is the subtlest. E is the old look. F is the control. **The owner chose A (variant 12) on 2026-10-30.**

The synthesised thin tail on static fonts is accepted with that choice; it remains a font-dependent effect.

## Decisions (2026-10-30)

1. One fixed look and the existing single toggle; no presets.
2. No dimmed tail. The contrast floor and Zhang et al. 2026 rule it out; variants 10–12 stay only as documented controls.
3. Thin tail: the run showed the engine synthesises a lighter static face, so the tail is not limited to variable fonts. The owner accepted the synthesised face by choosing variant 12.
4. Page growth target: no more than about 3% more pages than plain text; if no candidate meets it, the owner decides.
5. **Chosen: variant 12** — the first third of words of five or more letters in 600, the rest in 300. Implemented in the patch and `docs/specs/reader.md`.

Still open: the final phone check. The chosen look is implemented and tested on the emulator; the research harness is not part of the shipped patch.

Done when the contact sheets and measurements exist, the owner has chosen, `docs/specs/reader.md` describes the chosen rule, tests cover it, and neither the app nor the store texts claim faster reading.

## Sources

- https://blog.readwise.io/bionic-reading-results/
- https://doi.org/10.1016/j.actpsy.2024.104304
- https://doi.org/10.3390/jemr18050049
- https://doi.org/10.3758/s13414-025-03067-w
- https://doi.org/10.1016/j.actpsy.2026.107723
- https://bionic-reading.com/br-method/
- https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html
