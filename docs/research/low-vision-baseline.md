# Low-vision reading: source baseline

Research for issue [#144](https://github.com/juev/perigee/issues/144). This page records what Perigee already does that bears on reading with low vision, as read from the source at commit [`6bce6fa`](https://github.com/juev/perigee/tree/6bce6fa9993b7f9ef93e97516cfb8e3ca1ed0782), published as prerelease v0.1.0-rc11. It is a source inspection, not a device audit: nothing here was run, measured or tried with a screen reader. Where it says something is absent, that means no implementation was found in the production paths inspected, not that the platform rules it out. Options and the evidence for them are weighed in the review, [low-vision-reading.md](low-vision-reading.md).

## Typography

The reading settings are one set for all books ([`ReaderPrefs`][prefs]). Font size is 10 to 40 dp, 18 by default ([`FONT_SIZES`][prefs-ranges]); line spacing 80 to 200 %, 120 by default; boldness is a CSS weight; there are alignment, hyphenation, paragraph spacing and indent, and a margin switch. The settings sheet steps the size by 1 dp ([Sheets.kt][sheet-size]).

The size is normalised by x-height, not by em. The number is Roboto's size; any other face is set at the em that gives it Roboto's x-height, measured on the `x` glyph at 1024 px, and gets Roboto's line height ([`sizeOfFace`][face-size], [`EngineSettings.fontSize`][es-size], [spec][spec-size]). So 18 dp means about the same height of lowercase letters in every face. Capitals and character widths are not matched, so line length in characters still differs by face.

The dp value becomes pixels as `dp × density` ([`laidOut`][laidout]), where `density` is Compose's display density ([ReaderScreen.kt][viewport]). No read of `fontScale` was found in the inspected production code, so the system font-size setting does not reach the page text through this path. The menus and sheets are a different matter: the theme passes no typography of its own ([Theme.kt][theme]), so they use Material 3's default type scale, which is in sp. That they follow the system setting is inferred from the framework, not checked on a device.

## Contrast is a gamma, not a ratio

"Contrast" has five steps, each a FreeType gamma on glyph coverage: 1.0, 1.2, 1.4, 1.6, 1.8 ([`GAMMAS`][gammas], [`contrast`][es-contrast]). Above 1, edge pixels get darker and strokes look heavier; widths do not change, so nothing re-flows ([`repaint`][repaint]). It is not a WCAG contrast ratio. The colour schemes are four fixed pairs: Day black on white, Night `#C8C8C8` on `#1E1E1E`, OLED white on black, Sepia `#644D32` on `#F0DEC7` ([`PageColors`][colors]). The one luminance test on page colours, `isDark`, is a weighted-RGB threshold used to pick how highlights blend ([`isDark`][isdark]). No contrast-ratio computation was found, and there is no custom text or background colour except through user CSS ([`userCss`][es-css]).

## The page is a bitmap

The engine draws each screen into a bitmap. The page area carries one semantics label, "Page N of M" ([ReaderScreen.kt][page-label], [strings.xml][strings-page]); the `Image` holding the bitmap has `contentDescription = null` ([`PageView`][pageview]). No node exposing the page's text to accessibility services was found. A screen reader announcing that node gets the label; whether a screen reader's own OCR or image description can read the bitmap is outside what source inspection can settle. "N" is the engine's page index plus one, and page numbers change with every layout ([spec][spec-pages]).

## Vertical turning is whole pages

`PageTurn` is `Horizontal` or `Vertical` ([prefs][pageturn]). Vertical only changes the swipe axis and the slide animation ([swipe][swipe], [animation][anim]); a turn still moves by whole screens ([`BookReader.turn`][turn]). No continuous scrolling of the book text was found; the `verticalScroll` and `LazyColumn` uses in `:app` are in sheets and library screens, not the page view.

## Text ingredients for speech

The engine API already has what a read-aloud feature would build on: xpointer positions ([`xpointerOfPage`, `pageOf`][positions]), `textBetween(start, end)` ([source][textbetween]), `select` returning a `TextRange` with its text ([source][select]), `rects` for a range on the page shown ([source][rects]), whole-book `search` ([source][search]), and highlights tinted on the page ([`renderPage`][render], [`setHighlights`][sethl]). The font trial already pulls 40 pages of text with `textBetween` ([`startFontTrial`][trial-start]).

These are ingredients, not text-to-speech. No `TextToSpeech` use, speech service query or speech UI was found in `:app`, `:engine` Kotlin, the JNI sources or `:data`. Missing in the inspected code: splitting text into utterances, mapping spoken offsets back to xpointers, turning the page when speech crosses it, following the spoken word with a highlight, and audio focus or media controls. `rects` answers only for the page shown, and `setHighlights` does not re-lay the book out but empties the cache of drawn pages and draws the page shown and its neighbours again ([`setHighlights`][sethl], [`relaidOut`][relaidout], [spec][spec-ahead]); its cost for word-by-word use has not been measured.

## Two pages in landscape

`twoPagesInLandscape` is on by default ([prefs][two-default]): whenever the page area is wider than tall, two pages are laid out side by side ([`twoUp`][twoup]), each about half the width at the same font size. A single switch turns it off ([Sheets.kt][sheet-two]), giving one page across the full width. The font trial always uses one page ([`showTrialPassage`][trial-show]).

## Font trial

"Which font do I read fastest?" shows one passage of about 120 words per face in random order, times the reader's tap on Next and lists words per minute ([`FontTrial`][trial], [`nextFontTrial`][trial-next], [spec][spec-trial]). It is labelled rough on screen: one passage per font, no check that it was understood ([strings.xml][strings-rough]). It is not a clinical assessment: no comprehension, no repeated trials, no acuity or reading-acuity measure, and no clinical angular-size or viewing-distance calibration: every passage is set at the reader's current size and settings on one page, with only the face changing ([`showTrialPassage`][trial-show]). Its passage page is again an unlabelled bitmap ([FontTrialPanel.kt][trial-panel]). One source observation: the spec and KDoc speak of shipped fonts, but the code shuffles every offered face, device families included ([source][trial-faces], [`Engine.fontFaces`][engine-faces]).

## Footnotes are smaller

Notes marked as such by FB2 or EPUB are set at `font-size: 80% !important` ([`FOOTNOTE_CSS`][footnote-css], [spec][spec-footnotes]). The in-page placement flag, `EngineSettings.footnotes`, is not set by `laidOut`, so it stays at its default `true`, and `ReaderPrefs` has no footnote setting. The 80 % rule is applied either way. Code and preformatted text have their own size setting, 60 to 140 % ([`monospaceSizePercent`][es-mono]).

## Scope and limitations

Inspected at `6bce6fa`:

- `docs/specs/reader.md`
- `engine/src/main/kotlin/org/evsyukov/perigee/engine/EngineSettings.kt`; excerpts of `EngineDocument.kt`, `BookReader.kt` and `Engine.kt`
- `engine/src/main/cpp/jni/face_size.h`; grep of `engine_jni.cpp`
- `data/src/main/kotlin/org/evsyukov/perigee/data/ReaderPreferences.kt`
- `app/src/main/kotlin/org/evsyukov/perigee/reader/ReaderScreen.kt`, `FontTrial.kt`, `FontTrialPanel.kt`; excerpts of `ReaderViewModel.kt` and `Sheets.kt`
- `app/src/main/res/values/strings.xml`, `app/src/main/AndroidManifest.xml`, `app/src/main/kotlin/org/evsyukov/perigee/ui/Theme.kt` (grep)
- Repository-wide greps of `app/src/main`, `engine/src/main` and `data/src/main` for `fontScale`, `TextToSpeech`, TTS, accessibility, contrast ratio and luminance

Not inspected: crengine itself, tests, other modules, `ReaderMenu.kt` and the semantics of the menus and sheets in detail. Nothing was built or run; no TalkBack, Select to Speak, magnification or system font-size behaviour was observed. Statements about framework behaviour (sp scaling) are inferences.

[prefs]: https://github.com/juev/perigee/blob/6bce6fa9993b7f9ef93e97516cfb8e3ca1ed0782/data/src/main/kotlin/org/evsyukov/perigee/data/ReaderPreferences.kt#L56-L113
[prefs-ranges]: https://github.com/juev/perigee/blob/6bce6fa9993b7f9ef93e97516cfb8e3ca1ed0782/data/src/main/kotlin/org/evsyukov/perigee/data/ReaderPreferences.kt#L123-L127
[pageturn]: https://github.com/juev/perigee/blob/6bce6fa9993b7f9ef93e97516cfb8e3ca1ed0782/data/src/main/kotlin/org/evsyukov/perigee/data/ReaderPreferences.kt#L16
[two-default]: https://github.com/juev/perigee/blob/6bce6fa9993b7f9ef93e97516cfb8e3ca1ed0782/data/src/main/kotlin/org/evsyukov/perigee/data/ReaderPreferences.kt#L94
[colors]: https://github.com/juev/perigee/blob/6bce6fa9993b7f9ef93e97516cfb8e3ca1ed0782/engine/src/main/kotlin/org/evsyukov/perigee/engine/EngineSettings.kt#L14-L19
[isdark]: https://github.com/juev/perigee/blob/6bce6fa9993b7f9ef93e97516cfb8e3ca1ed0782/engine/src/main/kotlin/org/evsyukov/perigee/engine/EngineSettings.kt#L5-L12
[es-size]: https://github.com/juev/perigee/blob/6bce6fa9993b7f9ef93e97516cfb8e3ca1ed0782/engine/src/main/kotlin/org/evsyukov/perigee/engine/EngineSettings.kt#L44-L45
[es-contrast]: https://github.com/juev/perigee/blob/6bce6fa9993b7f9ef93e97516cfb8e3ca1ed0782/engine/src/main/kotlin/org/evsyukov/perigee/engine/EngineSettings.kt#L60-L64
[es-mono]: https://github.com/juev/perigee/blob/6bce6fa9993b7f9ef93e97516cfb8e3ca1ed0782/engine/src/main/kotlin/org/evsyukov/perigee/engine/EngineSettings.kt#L73-L74
[es-css]: https://github.com/juev/perigee/blob/6bce6fa9993b7f9ef93e97516cfb8e3ca1ed0782/engine/src/main/kotlin/org/evsyukov/perigee/engine/EngineSettings.kt#L88-L89
[gammas]: https://github.com/juev/perigee/blob/6bce6fa9993b7f9ef93e97516cfb8e3ca1ed0782/engine/src/main/kotlin/org/evsyukov/perigee/engine/EngineSettings.kt#L139-L143
[footnote-css]: https://github.com/juev/perigee/blob/6bce6fa9993b7f9ef93e97516cfb8e3ca1ed0782/engine/src/main/kotlin/org/evsyukov/perigee/engine/EngineSettings.kt#L156-L166
[face-size]: https://github.com/juev/perigee/blob/6bce6fa9993b7f9ef93e97516cfb8e3ca1ed0782/engine/src/main/cpp/jni/face_size.h#L52-L84
[repaint]: https://github.com/juev/perigee/blob/6bce6fa9993b7f9ef93e97516cfb8e3ca1ed0782/engine/src/main/kotlin/org/evsyukov/perigee/engine/EngineDocument.kt#L100-L108
[positions]: https://github.com/juev/perigee/blob/6bce6fa9993b7f9ef93e97516cfb8e3ca1ed0782/engine/src/main/kotlin/org/evsyukov/perigee/engine/EngineDocument.kt#L72-L76
[textbetween]: https://github.com/juev/perigee/blob/6bce6fa9993b7f9ef93e97516cfb8e3ca1ed0782/engine/src/main/kotlin/org/evsyukov/perigee/engine/EngineDocument.kt#L113-L114
[select]: https://github.com/juev/perigee/blob/6bce6fa9993b7f9ef93e97516cfb8e3ca1ed0782/engine/src/main/kotlin/org/evsyukov/perigee/engine/EngineDocument.kt#L128-L134
[rects]: https://github.com/juev/perigee/blob/6bce6fa9993b7f9ef93e97516cfb8e3ca1ed0782/engine/src/main/kotlin/org/evsyukov/perigee/engine/EngineDocument.kt#L136-L138
[search]: https://github.com/juev/perigee/blob/6bce6fa9993b7f9ef93e97516cfb8e3ca1ed0782/engine/src/main/kotlin/org/evsyukov/perigee/engine/EngineDocument.kt#L140-L143
[render]: https://github.com/juev/perigee/blob/6bce6fa9993b7f9ef93e97516cfb8e3ca1ed0782/engine/src/main/kotlin/org/evsyukov/perigee/engine/EngineDocument.kt#L150-L170
[turn]: https://github.com/juev/perigee/blob/6bce6fa9993b7f9ef93e97516cfb8e3ca1ed0782/engine/src/main/kotlin/org/evsyukov/perigee/engine/BookReader.kt#L89-L93
[sethl]: https://github.com/juev/perigee/blob/6bce6fa9993b7f9ef93e97516cfb8e3ca1ed0782/engine/src/main/kotlin/org/evsyukov/perigee/engine/BookReader.kt#L186-L191
[relaidout]: https://github.com/juev/perigee/blob/6bce6fa9993b7f9ef93e97516cfb8e3ca1ed0782/engine/src/main/kotlin/org/evsyukov/perigee/engine/BookReader.kt#L228-L246
[engine-faces]: https://github.com/juev/perigee/blob/6bce6fa9993b7f9ef93e97516cfb8e3ca1ed0782/engine/src/main/kotlin/org/evsyukov/perigee/engine/Engine.kt#L44-L45
[viewport]: https://github.com/juev/perigee/blob/6bce6fa9993b7f9ef93e97516cfb8e3ca1ed0782/app/src/main/kotlin/org/evsyukov/perigee/reader/ReaderScreen.kt#L213-L217
[page-label]: https://github.com/juev/perigee/blob/6bce6fa9993b7f9ef93e97516cfb8e3ca1ed0782/app/src/main/kotlin/org/evsyukov/perigee/reader/ReaderScreen.kt#L253-L264
[swipe]: https://github.com/juev/perigee/blob/6bce6fa9993b7f9ef93e97516cfb8e3ca1ed0782/app/src/main/kotlin/org/evsyukov/perigee/reader/ReaderScreen.kt#L311-L323
[anim]: https://github.com/juev/perigee/blob/6bce6fa9993b7f9ef93e97516cfb8e3ca1ed0782/app/src/main/kotlin/org/evsyukov/perigee/reader/ReaderScreen.kt#L467-L478
[pageview]: https://github.com/juev/perigee/blob/6bce6fa9993b7f9ef93e97516cfb8e3ca1ed0782/app/src/main/kotlin/org/evsyukov/perigee/reader/ReaderScreen.kt#L480-L488
[laidout]: https://github.com/juev/perigee/blob/6bce6fa9993b7f9ef93e97516cfb8e3ca1ed0782/app/src/main/kotlin/org/evsyukov/perigee/reader/ReaderViewModel.kt#L812-L821
[twoup]: https://github.com/juev/perigee/blob/6bce6fa9993b7f9ef93e97516cfb8e3ca1ed0782/app/src/main/kotlin/org/evsyukov/perigee/reader/ReaderViewModel.kt#L780-L782
[trial-start]: https://github.com/juev/perigee/blob/6bce6fa9993b7f9ef93e97516cfb8e3ca1ed0782/app/src/main/kotlin/org/evsyukov/perigee/reader/ReaderViewModel.kt#L472-L496
[trial-faces]: https://github.com/juev/perigee/blob/6bce6fa9993b7f9ef93e97516cfb8e3ca1ed0782/app/src/main/kotlin/org/evsyukov/perigee/reader/ReaderViewModel.kt#L473
[trial-next]: https://github.com/juev/perigee/blob/6bce6fa9993b7f9ef93e97516cfb8e3ca1ed0782/app/src/main/kotlin/org/evsyukov/perigee/reader/ReaderViewModel.kt#L499-L507
[trial-show]: https://github.com/juev/perigee/blob/6bce6fa9993b7f9ef93e97516cfb8e3ca1ed0782/app/src/main/kotlin/org/evsyukov/perigee/reader/ReaderViewModel.kt#L514-L522
[trial]: https://github.com/juev/perigee/blob/6bce6fa9993b7f9ef93e97516cfb8e3ca1ed0782/app/src/main/kotlin/org/evsyukov/perigee/reader/FontTrial.kt#L5-L18
[trial-panel]: https://github.com/juev/perigee/blob/6bce6fa9993b7f9ef93e97516cfb8e3ca1ed0782/app/src/main/kotlin/org/evsyukov/perigee/reader/FontTrialPanel.kt#L43
[sheet-size]: https://github.com/juev/perigee/blob/6bce6fa9993b7f9ef93e97516cfb8e3ca1ed0782/app/src/main/kotlin/org/evsyukov/perigee/reader/Sheets.kt#L162-L165
[sheet-two]: https://github.com/juev/perigee/blob/6bce6fa9993b7f9ef93e97516cfb8e3ca1ed0782/app/src/main/kotlin/org/evsyukov/perigee/reader/Sheets.kt#L252
[theme]: https://github.com/juev/perigee/blob/6bce6fa9993b7f9ef93e97516cfb8e3ca1ed0782/app/src/main/kotlin/org/evsyukov/perigee/ui/Theme.kt#L90
[strings-page]: https://github.com/juev/perigee/blob/6bce6fa9993b7f9ef93e97516cfb8e3ca1ed0782/app/src/main/res/values/strings.xml#L8
[strings-rough]: https://github.com/juev/perigee/blob/6bce6fa9993b7f9ef93e97516cfb8e3ca1ed0782/app/src/main/res/values/strings.xml#L56
[spec-pages]: https://github.com/juev/perigee/blob/6bce6fa9993b7f9ef93e97516cfb8e3ca1ed0782/docs/specs/reader.md#L23
[spec-ahead]: https://github.com/juev/perigee/blob/6bce6fa9993b7f9ef93e97516cfb8e3ca1ed0782/docs/specs/reader.md#L32
[spec-size]: https://github.com/juev/perigee/blob/6bce6fa9993b7f9ef93e97516cfb8e3ca1ed0782/docs/specs/reader.md#L65-L71
[spec-footnotes]: https://github.com/juev/perigee/blob/6bce6fa9993b7f9ef93e97516cfb8e3ca1ed0782/docs/specs/reader.md#L130
[spec-trial]: https://github.com/juev/perigee/blob/6bce6fa9993b7f9ef93e97516cfb8e3ca1ed0782/docs/specs/reader.md#L190
