---
title: "Comfortable reading with low vision: evidence review"
date: 2026-11-02T00:00:00+03:00
---

# Comfortable reading with low vision: evidence review

- **Date:** 2026-11-02
- **Issue:** [#144](https://github.com/juev/perigee/issues/144)
- **Perigee baseline:** commit [`6bce6fa9993b7f9ef93e97516cfb8e3ca1ed0782`](https://github.com/juev/perigee/tree/6bce6fa9993b7f9ef93e97516cfb8e3ca1ed0782), prerelease v0.1.0-rc11, as inspected in [low-vision-baseline.md](low-vision-baseline.md)
- **Companion files:** [source notes](low-vision-source-notes.md) (the 28 works read, with quotes and limits), [low-vision-candidates.csv](low-vision-candidates.csv) (URLs found while searching, mostly not read)

**Status: research only.** This review selects no prototype, default, preset or trial, and changes nothing in the app. It does not diagnose anyone and does not infer a condition from a reading preference. Where it makes a suggestion, the text says it is ours. Everything said about Perigee comes from reading the source, not from a device audit.

The question is how a reader with reduced vision can read a book comfortably for a long time when the obvious remedy, a larger font, leaves few words on a phone screen. The outcomes that matter here are comprehension, sustained comfort and the reader's autonomy. Words per screen and words per minute matter only as far as they serve these.

## Key conclusions

1. **Larger print helps up to a point, then the screen width sets the limit.** Below the critical print size (CPS) reading slows; above it, more size buys little speed but costs characters per line. Below some number of characters per line, reading slows again. On a narrow display the two limits can leave no size at which reading reaches near-maximum speed.[^3] This is a mechanism, not a prescription for any reader.
2. **There is no measured minimum line length for Perigee, and none in the literature fits all readers.** The 2020 study found 8 characters per line for 80 % of maximum speed in 10 readers with low vision; a 2022 report on the same work says 9 and picks 13 for its own procedure.[^3][^2] These are lower bounds from English-language tests, not an ideal line length.
3. **Typeface labels alone do not predict reading performance, and gains trade against each other.** Fonts designed for macular degeneration were no better than Courier, and the fonts most legible at the smallest sizes were read more slowly at large sizes.[^8] Perigee already sets every face to Roboto's x-height ([baseline](low-vision-baseline.md#typography)); that removes a confound but proves nothing about which face reads best. The sources read do not establish low-vision efficacy for a specific Cyrillic font.
4. **Polarity and contrast are individual.** A subset of readers with low vision read faster with light text on dark, according to a narrative review; this is not universal and does not show less fatigue.[^5] Perigee's "Contrast" control is a glyph gamma, not a WCAG contrast ratio.
5. **No evidence read decides paging against vertical scrolling.** The positive "scrolling" study tested one line of text moving sideways, not a scrolled page: comprehension was higher than with static text or RSVP, but reading was slower than static text.[^10]
6. **RSVP gave the worst comprehension in the one patient study read**, and the tested RSVP presentation did not permit ordinary rereading.[^10]
7. **Line-tracking aids are promising but unproven for comprehension or comfort.** A gaze-aware line highlight shortened line switching in a small desktop study without making it significantly more accurate.[^12] For a simple ruler or typoscope only practice guidance was found.[^13]
8. **Listening is a recognised way to read; text plus synchronised speech is plausible but not shown to be better.** Clinical guidance lists reading aloud by devices;[^14][^26] the main source on audio-supported reading says its own case still needs data.[^15] The Cochrane review of reading aids excludes audio aids, so it is no evidence either way.[^1]
9. **Platform accessibility is a separate goal from reading efficacy.** Android documents how a custom view can expose text to accessibility services,[^17] and WCAG 2.2 sets testable criteria for web content.[^21] Meeting them makes text reachable; it does not show that anyone reads better or longer.
10. **Training and "special" fonts should not be promised as treatments.** A 200-person RCT of eccentric viewing training found no group differences;[^11] Bionic Reading and Dyslexie showed no benefit in non-low-vision groups.[^23][^24]

## Why font size alone runs into the screen

Three measures from reading tests such as MNREAD describe how print size affects reading, and they are not the same thing.[^5][^4]

- **Reading acuity (RA):** the smallest print that can be read at all.
- **Critical print size (CPS):** the smallest print at which reading reaches, or nearly reaches, its maximum speed.
- **Maximum reading speed (MRS):** the plateau, where larger print no longer speeds reading.

"Can make out" is therefore not "can read fluently". Enlarging print from RA to CPS can help a great deal; enlarging beyond CPS need not help.[^5] RA predicted the size needed for fluent reading and CPS better than letter acuity did, in 58 readers with low vision.[^4] A reserve between the print used and the threshold is generally needed, but the 2022 report warns that estimating CPS from acuity is rough and can differ much from a person's true CPS.[^2]

The studies do not define CPS alike: 90 % of MRS in the acuity study,[^4] 95 % in the 2018 font study,[^8] and 80 % in the 2020 display study.[^3] Their numbers cannot be pooled as one threshold, and none of them can be applied to a reader whose RA, CPS and MRS have not been measured.

Size is not the only limit. The visual span, the number of letters recognised without moving the eyes, and crowding, the interference of neighbouring letters that is stronger in peripheral vision, are proposed reasons why maximum speed can stay low even when print is large enough; very wide letter spacing can push letters further from fixation.[^5][^6] In a study that trained 27 normally sighted students to recognise letters 10° below fixation, reducing crowding enlarged the visual span, but did not help reading where an adverse attentional bias was present.[^6] That work explains a mechanism; it is not a treatment.

The second constraint is line length. With the screen width fixed, every step up in print size removes characters from the line. The 2020 study measured the critical character count needed for 80 % of each reader's maximum speed: 13 characters for normally sighted readers and 8 for readers with low vision.[^3] Its point is the interaction: print large enough to pass CPS may leave too few characters per line, and then no available size gives near-maximum speed; a wider display widens the usable range without shrinking the letters.[^3] The 2022 report adds that reflow (re-wrapping text at the new size) removes the horizontal panning that plain magnification needs, but cannot add characters that do not fit.[^2]

What this means for Perigee, from the source:

- Font size is 10 to 40 dp and normalised to Roboto's x-height ([baseline](low-vision-baseline.md#typography)). A dp is not an angular size: the same setting subtends different visual angles at different viewing distances, so the setting cannot be read as a print size in the clinical sense.
- The settings sheet already shows an estimate of characters per line under the margin switch ([spec](https://github.com/juev/perigee/blob/6bce6fa9993b7f9ef93e97516cfb8e3ca1ed0782/docs/specs/reader.md#L176)). Nobody has measured what minimum is comfortable for a reader of Perigee, and the 8, 9 and 13 above are not targets for it.
- Text is reflowed by the engine at every size: Perigee does not magnify a bitmap of a fixed page.

The conclusion is not "use smaller print to fit more text". Print below a reader's CPS slows reading for its own reason. Size, line length and display width have to be chosen together, for a reader, with comprehension and comfort as the test.

## Comparison matrix

"Perigee now" lists existing behaviour at the baseline; nothing in this column is proposed.

| Lever | Perigee now | Strongest evidence read | Supports | Does not support |
|---|---|---|---|---|
| Print size, x-height | 10–40 dp; x-height normalised across faces | RA/CPS/MRS psychophysics[^4][^3] | Size up to CPS matters; reserve needed | A universal size; dp as visual angle |
| Typeface | Shipped and device faces; rough speed trial | MD fonts, 19 patients;[^8] simulated LV;[^7] vendor study[^25] | No universal winner | Any "low-vision font"; Cyrillic claims |
| Letter width, letter spacing | Width by face only; no letter-spacing setting found in `ReaderPrefs` (user CSS not checked for it) | Abstract, 20 AMD;[^9] MD fonts[^8] | Better RA near CPS | Faster MRS; condensed faces to fit text |
| Line and word spacing | Line spacing 80–200 %, paragraph spacing; no word-spacing setting found | Narrative review[^5] | Some benefit for some readers | A default value |
| Weight, stroke darkening | Weight; "Contrast" as gamma 1.0–1.8 | Indirect, normal vision[^5] | Heavier than standard did not speed reading | A WCAG ratio; benefit of bold |
| Polarity, colours | Day, Night, OLED, Sepia | Narrative review[^5] | Light-on-dark faster for a subset | Universal benefit; less fatigue |
| Line width, reflow | Reflowed pages; chars-per-line hint | Display studies[^3][^2] | Reflow avoids panning; a line-length floor exists | A Perigee minimum; 8 or 13 as targets |
| Landscape, two pages | Two pages in landscape by default; one-page switch | Display width interaction[^3] | Width widens the usable size range | That two half-width pages are better or worse |
| Tablet, larger display | Not app-specific | Cochrane: tablet vs CCTV, low certainty[^1] | No important difference detected | Equivalence; a ranking of current devices |
| Paging vs vertical scroll | Whole-page turns; vertical turns too | None direct[^10][^12] | — | Either as superior |
| Horizontal moving line | None found | 37 with macular degeneration[^10] | Better comprehension than static text or RSVP | Faster reading; long-form reading |
| RSVP | None found | Same study[^10] | — | Use for comprehension |
| Ruler, line highlight | None found | GazePrompt, 13 on desktop;[^12] typoscope guidance[^13] | Faster line switching with gaze cues | Accuracy, comprehension; manual rulers |
| TTS | None found; text APIs exist | Clinical guidance[^14][^26] | A recognised access route | Superiority to reading |
| Synchronised audio and text | Highlight ranges exist | Educational sources[^15][^16]; API[^20] | Feasible; worth trying per reader | Proven benefit for adults with low vision |
| Text semantics | Page bitmap labelled "Page N of M" | Android guidance[^17] | Virtual hierarchy can expose text | A Compose recipe; any outcome claim |
| Eccentric viewing training | Out of scope for an app | RCT, 200 with AMD[^11] | — | Routine training in progressing AMD |
| Bionic, Dyslexie fonts | "Bold word starts" option exists | Normal vision;[^23] children[^24] | — | A treatment for low vision |

## Evidence and trade-offs

### Typography

Equal nominal sizes do not mean equal letters. In the review literature, an advantage of one face at the same point size vanished once the space the text took was equated.[^5] Perigee's x-height normalisation already handles this for size, but faces still differ in width, so the same setting gives different characters per line.

Width and spacing pull in two directions. In readers with macular degeneration, wider spacing improved RA and CPS but lowered MRS; a narrow variant of one font gave an 18 % higher MRS but worse RA.[^8] The 2021 abstract reports a small, measurable effect of spacing and width on RA near critical sizes in 20 readers with AMD; it gives no speed or long-form result.[^9] The 2022 report notes that a wider font's smaller CPS is offset by the space it takes, so font choice hardly changes the display width needed.[^2] A vendor study found width benefits on a distance recognition task with four-letter Latin strings;[^25] that task is not reading connected text, and the vendor tested its own font.

A recent study of fonts designed for accessibility used simulated reduced acuity in normally sighted young adults: one new face matched Courier for RA, with no MRS differences, and comprehension was not measured.[^7]

Our reading of this: neither condensed faces nor smaller print are a sound way to fit more text, and "wider" or "more spaced" helps the threshold more reliably than it helps sustained reading. Whether any of this holds for Cyrillic text is not established by anything read.

Footnotes in Perigee are set at 80 % of the text size ([baseline](low-vision-baseline.md#footnotes-are-smaller)). For a reader at or just above CPS, notes may fall below it. This is an inference from the mechanism, not a measured problem.

### Contrast and polarity

Reduced contrast sensitivity makes text contrast more important, and for some readers any loss of contrast slows reading.[^5] The same review reports that a subset read 10 % to 50 % faster with bright letters on black; reduced light scatter is one proposed reason.[^5] Light-on-dark is therefore a choice for some readers, not a default for all, and nothing read shows that it reduces fatigue. Coloured filters are a different intervention: one small trial in Cochrane found no speed gain, with low certainty.[^1]

Perigee's four schemes and gamma steps exist already. Gamma darkens glyph edges; it does not compute or guarantee a contrast ratio between text and page ([baseline](low-vision-baseline.md#contrast-is-a-gamma-not-a-ratio)).

### Width, reflow, landscape and larger displays

RNIB describes enlarging the print in an e-book reader as a solution for many people;[^28] reflow is what lets that work without panning, and Perigee has it. A wider display lets larger print keep enough characters per line.[^3] Perigee lays out two pages side by side in landscape by default, so each page is about half the width; one page across the full width is a single switch away ([baseline](low-vision-baseline.md#two-pages-in-landscape)). For a reader near the line-length floor, the one-page landscape layout gives more characters per line at the same size. That is geometry, not a tested outcome.

For hardware, Cochrane found that electronic devices allowed longer reading, with moderate certainty, against optical aids; reading duration was defined as time until discomfort forced a pause.[^1] The tablet against stand-mounted CCTV comparison detected no important difference in speed, with low certainty and imprecise estimates.[^1] That is not evidence that the two are equivalent, and it predates current phones and e-readers.

System magnification enlarges a part of the screen up to 8×.[^22] The documented zoom/pan mechanism is distinct from text reflow.[^21] Our engineering inference is that it can help inspect a detail but cannot by itself solve short reflowed lines; comparative sustained-reading benefit was not established.

### Motion: paging, scrolling, moving line, RSVP

The one patient study read compared static one-line and three-line text, a single line moving horizontally, and RSVP, in 37 people with macular degeneration reading short passages aloud at twice their CPS.[^10] The moving line gave the highest total comprehension (about 74 %, against about 58 % for three static lines and about 46 % for RSVP). The static text was read faster (about 105 against 72 words per minute). RSVP produced the most errors, and it ran on a different tablet than the other formats.[^10] For inferential comprehension alone, the moving line was not significantly better than three static lines.[^10]

What this does not show: that vertical scrolling beats paging, that the moving line suits long novels, or that the effect holds for readers without central loss. The authors note that RSVP rules out rereading.[^10] Our inference is that any mode which takes control of pace from the reader needs pause and back-up controls.

Perigee turns whole pages, horizontally or vertically ([baseline](low-vision-baseline.md#vertical-turning-is-whole-pages)). No direct low-vision comparison of paging and vertical scrolling was found. GazePrompt observed people scrolling one or two lines at a time to keep their place,[^12] a sign that place-keeping is costly, not a verdict for pages.

### Line-tracking aids

GazePrompt highlighted the current or next line, or showed an arrow, driven by an eye tracker on a desktop screen.[^12] Line-switching time fell (p = .014); accuracy did not improve significantly, and for difficult words neither recognition time nor misread words changed significantly, although the abstract reads more positively.[^12] One participant found the highlight distracting.[^12] The study says nothing about a manual ruler or a phone's front camera.

A typoscope, a dark card with a slot, is clinical practice that for some people reduces visual confusion and dazzle.[^13] No controlled outcome was found. A window that hides surrounding lines may also hide context; that is our design concern, not a measured harm.

### Audio: TTS and synchronised highlighting

Reading aloud by a device is listed among the options by the NEI and the American Academy of Ophthalmology, alongside larger text and contrast settings.[^26][^14] The AAO stresses matching tools to needs with a rehabilitation team and that consumer technology is not a cure-all.[^14]

Audio-supported reading shows enlarged text in step with speech. Its main sources are educational and describe students, braille and print.[^15] One says its effectiveness is "logically compelling and anecdotally substantiated" but needs empirical work;[^15] its companion guide recommends trying looking alone, listening alone and both, and letting the student choose by task.[^16] No controlled study of adults with low vision was found. Cochrane excluded audio aids,[^1] so the lack of evidence there says nothing against audio.

On Android, `UtteranceProgressListener.onRangeStart` reports which part of an utterance is about to be spoken, and the documentation names highlighting as a use.[^20] It is called only when the engine supplies timing, it reports character ranges rather than words or screen positions, and its callbacks can come from several threads.[^20] Perigee has xpointers, text between positions, selections, range rectangles and highlights, but no TTS ([baseline](low-vision-baseline.md#text-ingredients-for-speech)). These are ingredients; mapping spoken offsets to positions, turning pages with speech and keeping the place would all be new work.

### Training and special fonts

The EFFECT trial randomised 200 people with AMD to four arms, three 45-minute sessions with home practice, and found no differences in task ability or any secondary outcome at six months.[^11] The authors do not support routine eccentric viewing training in progressing disease but leave open benefit in end-stage disease.[^11] This tests one protocol, not rehabilitation as a whole; NEI points readers to rehabilitation services.[^27]

Bionic Reading made no significant difference to fixations or speed in 53 adults with normal or corrected vision.[^23] Dyslexie did not help children with or without dyslexia, and their preference did not track performance.[^24] Neither population has low vision. These results do not forbid offering a look someone prefers; they forbid calling it a treatment. Perigee's "Bold word starts" option is already presented as a matter of taste in its [spec](https://github.com/juev/perigee/blob/6bce6fa9993b7f9ef93e97516cfb8e3ca1ed0782/docs/specs/reader.md#L198).

## Android and W3C: compatibility criteria, not efficacy

Accessibility services need information that pixels do not carry. The Android guidance says such services sometimes cannot get adequate information from the view hierarchy, and describes a virtual view hierarchy that matches what is on screen.[^17] The guidance is written for Views; Perigee's reader is Compose over an engine bitmap, so the guidance shows the direction, not a recipe. How to cut a page into nodes, how to order them, and how to keep them in step with repagination are open design questions.

At the baseline, the page carries one label, "Page N of M", and the bitmap has no description ([baseline](low-vision-baseline.md#the-page-is-a-bitmap)). That is a source observation. It does not show what TalkBack does on a device, and nothing read says TalkBack never uses OCR.

System features have stated limits. Reading mode works "on websites or apps with long articles" and excludes PDF files and some other content;[^18] whether it handles Perigee was not checked. Select to Speak's selected-text Read aloud works only on readable text, while its camera OCR is a separate path.[^19] Magnification enlarges without reflow.[^22]

WCAG 2.2 is a web standard of testable, technology-neutral criteria.[^21] Among the relevant ones: text contrast of 4.5:1 (3:1 for large text) at AA and 7:1 (4.5:1) at AAA; text resizable to 200 % with the criterion’s exceptions; reflow at a width equivalent to 320 CSS pixels, with exceptions; and text spacing, which requires that content survive a user's spacing overrides, not that content use those values.[^21] CSS pixels are not Android dp, and native conformance would need an interpretation for non-web software, which was not read. This review does not assess or claim native WCAG conformance.

These criteria tell whether text can be reached and adjusted. They are not evidence of comprehension, speed or comfort, and the clinical sources are not conformance tests. Both kinds of evidence are needed, and neither substitutes for the other.

## Contradictions and tensions

- **8 or 9 characters.** display2020 states 8 for low vision; display2022, describing it, says 9 and adopts 13.[^3][^2] We keep both and treat neither as a threshold for Perigee.
- **Spacing helps or not.** The 2016 review concludes that extra letter spacing is not helpful;[^5] later work finds it helps acuity but can lower maximum speed.[^8][^9] The disagreement largely dissolves once the outcome (RA against MRS) is named.
- **Comprehension against speed.** The moving line gave better comprehension at lower speed;[^10] a speed-only test would have put it at the bottom, level with RSVP.
- **Abstract against results.** GazePrompt's abstract speaks of fewer errors; its results report no significant change.[^12]
- **Practice against trials.** Charity guidance describes eccentric viewing and typoscopes as aids;[^13] the RCT found no benefit of training,[^11] and no trial of typoscopes was found. Availability of a service and its measured effect are different questions.
- **Width in vendor work and in display work.** A vendor reports width benefits on recognition;[^25] the display report finds font choice barely changes the width needed.[^2]

## Evidence and data gaps

- **Unknown reader.** Low vision has several kinds, central, peripheral and others;[^26] much of the evidence is from macular degeneration. Nothing here assumes which kind applies to anyone.
- **Long-form reading.** Most outcomes are one-minute trials or short passages; comfort over an hour of a novel was not measured in any study read.[^3][^10]
- **Comprehension.** Often unmeasured or used only as an attention check.[^7][^3]
- **Language.** Participants and texts were English or Latin-script;[^4][^25] the sources read do not establish comparative efficacy for Cyrillic low-vision reading.
- **Hardware.** Cochrane's search ended in January 2018.[^1]
- **Paging against scrolling, rulers, synchronised audio** for adults with low vision: no direct controlled comparison found.
- **Perigee itself.** No device measurement: no visual angle at a viewing distance, no TalkBack behaviour, no characters-per-line minimum, no test of footnote size.
- **Search scope.** Not a systematic review; candidates found but not read are listed in [low-vision-candidates.csv](low-vision-candidates.csv) and are not evidence.

## Possible future assessment (not selected, not planned)

This section is our proposal of how options could be judged later, should the owner decide to compare anything. It is not a trial protocol, and nothing here has been chosen or started.

- **Consent and clinical context first.** Any individual comparison would be voluntary, use no health details in project documents, and leave diagnosis and clinical measurement (for example RA, CPS and MRS at a known distance) to eye-care professionals; the guidance read points readers to their eye doctor or rehabilitation team.[^14][^27]
- **Outcomes, in order:**
  1. Comprehension: retelling or a few open questions per session on the book being read.
  2. Sustained comfort: time until the reader wants a break, and self-rated strain at the end, in the spirit of Cochrane's reading-duration outcome.[^1]
  3. Autonomy: whether the reader can set up, navigate, find the place and resume without help.
  4. Lost place: rereads, skipped lines, wrong-line returns noted by the reader.
  5. Preference, stated after use.
  6. Speed, last, and only together with the above.
- **Design:** within-person comparisons of existing settings first (size, one-page landscape, scheme, line spacing), counterbalanced over several days, on natural long text in the reader's language; viewing distance and device recorded; the reader free to stop.
- **Interpretation:** a single reader's result guides that reader's settings only; it is not evidence for defaults.

## Decisions deliberately left open

1. Which methods, if any, to prototype: text semantics, TTS, synchronised highlighting, line aids, a moving-line mode, or none.
2. Whether and how an individual, consent-based comparison is held.
3. Any change to defaults (including two-page landscape and footnote size), presets, or reading modes.
4. Whether to aim at a WCAG-style interpretation for native content at all.
5. Whether to add letter- or word-spacing controls.

## Quality Metrics

| Metric | Value |
|---|---|
| Sources found | 170 |
| Sources read | 28 |
| Sources cited | 28 |
| Citation coverage | 66% of substantive paragraphs (29/44); excludes table rows |
| Read corpus | 27 full texts or relevant full-text passages; 1 abstract-only work (`spacing2021`) |
| Discovery unit | Distinct candidate source URLs, not independent publications; alternate representations remain in the discovery log |
| Search failures excluded from Sources found | 3 arXiv API request URLs returning HTTP 429 |
| Cited mix | 14 academic publications (including 1 abstract-only work and 1 preprint), 6 platform/standards documents, 7 patient/educational/charity guidance documents, 1 vendor case study |
| Search type | Bounded multi-source review, not a systematic review or a clinical recommendation |
| Counter-arguments searched | Yes |
| Research rounds | 2 |

[^1]: Reading aids for adults with low vision. Cochrane Database of Systematic Reviews, 2018. https://pmc.ncbi.nlm.nih.gov/articles/PMC6494537 — [notes](low-vision-source-notes.md#cochrane2018)
[^2]: Technical Report: Digital Reading with Low Vision: Principles for Selecting Display Size. Optom Vis Sci, 2022. https://pmc.ncbi.nlm.nih.gov/articles/PMC9357187 — [notes](low-vision-source-notes.md#display2022)
[^3]: Reconciling print-size and display-size constraints on reading. PNAS, 2020. https://pmc.ncbi.nlm.nih.gov/articles/PMC7720185 — [notes](low-vision-source-notes.md#display2020)
[^4]: Reading Acuity as a Predictor of Low-Vision Reading Performance. IOVS, 2018. https://pmc.ncbi.nlm.nih.gov/articles/PMC6181187 — [notes](low-vision-source-notes.md#acuity2018)
[^5]: Reading Digital with Low Vision. Visible Language, 2016. https://pmc.ncbi.nlm.nih.gov/articles/PMC5726769 — [notes](low-vision-source-notes.md#legge2016)
[^6]: Linking crowding, visual span, and reading. Journal of Vision, 2017. https://pmc.ncbi.nlm.nih.gov/articles/PMC5853154 — [notes](low-vision-source-notes.md#crowding2017)
[^7]: Assessment of newly designed fonts for visual accessibility. PLOS ONE, 2026. https://pmc.ncbi.nlm.nih.gov/articles/PMC13012447 — [notes](low-vision-source-notes.md#font2026)
[^8]: Fonts Designed for Macular Degeneration: Impact on Reading. IOVS, 2018. https://pmc.ncbi.nlm.nih.gov/articles/PMC6100668 — [notes](low-vision-source-notes.md#font2018)
[^9]: Increased letter spacing and greater letter width improve reading acuity in low vision readers. Information Design Journal, 2021; abstract only. https://adk.elsevierpure.com/en/publications/increased-letter-spacing-and-greater-letter-width-improve-reading — [notes](low-vision-source-notes.md#spacing2021)
[^10]: A comparison of reading, in people with simulated and actual central vision loss, with static text, horizontally scrolling text, and rapid serial visual presentation. Journal of Vision, 2021. https://pmc.ncbi.nlm.nih.gov/articles/PMC8590178 — [notes](low-vision-source-notes.md#scrolling2021)
[^11]: Eccentric Viewing Training for Age-Related Macular Disease: Results of a Randomized Controlled Trial (the EFFECT Study). Ophthalmology Science, 2023. https://pmc.ncbi.nlm.nih.gov/articles/PMC10767206 — [notes](low-vision-source-notes.md#effect2023)
[^12]: GazePrompt: Enhancing Low Vision People’s Reading Experience with Gaze-Aware Augmentations. arXiv:2402.12772v2, 2024. https://arxiv.org/html/2402.12772v2 — [notes](low-vision-source-notes.md#gazeprompt2024)
[^13]: Sight loss aids for reading printed text. Blind Veterans UK, 2023. https://www.blindveterans.org.uk/sight-loss-resources/sight-loss-aids-for-reading-printed-text — [notes](low-vision-source-notes.md#blindveterans2023)
[^14]: Low Vision Assistive Devices. American Academy of Ophthalmology, 2026. https://www.aao.org/eye-health/diseases/low-vision-assistive-devices — [notes](low-vision-source-notes.md#aao2026)
[^15]: Audio-Supported Reading for Students who are Blind or Visually Impaired. CAST, 2012. https://aem.cast.org/binaries/content/assets/common/publications/aem/audio-supported-reading-blind-visually-impaired-2012-01.docx — [notes](low-vision-source-notes.md#cast2012)
[^16]: Getting Started with ASR: Exploring Audio-Supported Reading with Students who are Blind or Visually Impaired: A Resource for Parents, Teachers, and Students. CAST/AEM, 2014. https://files.eric.ed.gov/fulltext/ED601786.pdf — [notes](low-vision-source-notes.md#asr2014)
[^17]: Make custom views more accessible (Views). Android Developers. https://developer.android.com/guide/topics/ui/accessibility/views/custom-views?hl=en — [notes](low-vision-source-notes.md#android-custom-views)
[^18]: Use Reading mode. Android Accessibility Help. https://support.google.com/accessibility/android/answer/13026948?hl=en — [notes](low-vision-source-notes.md#reading-mode)
[^19]: Use Select to Speak. Android Accessibility Help. https://support.google.com/accessibility/android/answer/7349565?hl=en — [notes](low-vision-source-notes.md#select-to-speak)
[^20]: UtteranceProgressListener. Android API reference. https://developer.android.com/reference/android/speech/tts/UtteranceProgressListener — [notes](low-vision-source-notes.md#utterance-progress-listener)
[^21]: Web Content Accessibility Guidelines (WCAG) 2.2. W3C Recommendation. https://www.w3.org/TR/2024/REC-WCAG22-20241212/ — [notes](low-vision-source-notes.md#wcag22)
[^22]: Use magnification on your screen. Android Accessibility Help. https://support.google.com/accessibility/android/answer/6006949?hl=en — [notes](low-vision-source-notes.md#magnification)
[^23]: Guiding the Gaze: How Bionic Reading Influences Eye Movements. 2025. https://pmc.ncbi.nlm.nih.gov/articles/PMC12565662 — [notes](low-vision-source-notes.md#bionic2025)
[^24]: Dyslexie font does not benefit reading in children with or without dyslexia. 2017. https://pmc.ncbi.nlm.nih.gov/articles/PMC5934461 — [notes](low-vision-source-notes.md#dyslexie2017)
[^25]: Designing fonts with low-vision readers in mind: A reading acuity experiment. Typotheque, no verified date. https://www.typotheque.com/research/designing-fonts-with-low-vision-readers-in-mind — [notes](low-vision-source-notes.md#typotheque-zed)
[^26]: Low Vision. National Eye Institute, updated 2025. https://www.nei.nih.gov/eye-health-information/eye-conditions-and-diseases/low-vision — [notes](low-vision-source-notes.md#nei-low-vision)
[^27]: Vision Rehabilitation. National Eye Institute, updated 2026. https://www.nei.nih.gov/eye-health-information/vision-rehabilitation — [notes](low-vision-source-notes.md#nei-vision-rehabilitation)
[^28]: Large and giant print. RNIB, no verified date. https://www.rnib.org.uk/living-with-sight-loss/independent-living/leisure/reading-and-books/large-and-giant-print/ — [notes](low-vision-source-notes.md#rnib-large-print)
