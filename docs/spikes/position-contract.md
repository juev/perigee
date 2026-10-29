# Position contract with bookOrbit: spike report

Date: 2026-10-12. Server: `ghcr.io/bookorbit/bookorbit:3.3.0`. Engine: KOReader crengine `ab1981c`, DOM version 20260812, full-featured block rendering. Device: Android 16 emulator, arm64-v8a.

The question: can a crengine-based reader exchange exact reading positions and highlights with bookOrbit through the KOReader channel, so that the web reader and other devices land on the same place?

Answer: yes for EPUB, on every book of the corpus. FB2 syncs between crengine devices only.

## How it was checked

`contract/up.py` starts the server from `contract/docker-compose.yml` and seeds it with a generated corpus (`contract/make_corpus.py`). The tests in `contract/src/androidTest` run on a device, download each book from the server, open it in the engine and talk to the server over HTTP:

```
python3 contract/up.py --reset
./gradlew :contract:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.bookorbitUrl=http://10.0.2.2:3070
```

Result of the run: 8 tests, 0 failures.

## Corpus

| Book | What it stresses |
| --- | --- |
| `plain.epub` | inline markup, entities, whitespace between block elements |
| `spine-nonxhtml.epub` | an SVG item in the spine before the chapters |
| `media-css.epub` | `@media` rules that change `display` |
| `contract.fb2` | a format the server cannot convert positions for |
| `contract.pdf` | a format the app does not read, left out of the server's list |

The corpus is synthetic. No real-world book was tested; publisher EPUBs with deep nesting, tables, ruby or embedded fonts remain unverified.

## Progress: xpointer → CFI → xpointer

For each EPUB, at 15 %, 50 % and 85 % of the book: the engine's xpointer is pushed through `PUT koreader/syncs/progress`; the JWT API returns a CFI for it; that CFI is saved back the way the web reader does; `GET koreader/syncs/progress` returns an xpointer converted from the CFI; the engine jumps to it and lands on the page the position was taken from.

| Book | Sent by the engine | CFI on the server | Same page after the round trip |
| --- | --- | --- | --- |
| plain | `/body[1]/DocFragment[4]/body[1]/div[1]/p[16]/text()[4].45` | `epubcfi(/6/8!/4/2/36/7:45)` | yes, 3 of 3 |
| spine-nonxhtml | `/body[1]/DocFragment[5]/body[1]/div[1]/p[16]/text()[4].45` | `epubcfi(/6/10!/4/2/36/7:45)` | yes, 3 of 3 |
| media-css | `/body[1]/DocFragment[4]/body[1]/div[1]/p[16]/span[1]/text()[1].19` | `epubcfi(/6/8!/4/2/36/6/1:19)` | yes, 3 of 3 |

The SVG spine item is counted on both sides: the first chapter is `DocFragment[2]` in the engine and `/6/4` in the CFI. This is the case where crengine-ng would have shifted by one.

## Highlights

A highlight made on the device goes up through `POST koreader/plugin/annotations/exchange` as a pair of xpointers. The JWT API then returns it with a CFI range and `positionStatus: exact` on all three EPUBs. The server maps collapsed whitespace correctly: the engine range `.7 … .32` became `/5:7 … /5:36` where the source has a line break and indentation inside the text node.

A highlight created through the JWT API at that CFI comes back to the device as xpointers, and the engine selects the same text with them.

## What the client must account for

1. **Xpointers are not comparable as strings.** The engine writes `/body[1]/DocFragment[3]/body[1]/div[1]/…`, the server returns `/body/DocFragment[3]/body/div/…`. Both resolve to the same node. Compare by resolving, never by text.
2. **A web highlight arrives on the second exchange.** The first exchange after it was created only converts the CFI and returns nothing; `more` stays `false`. The next exchange delivers it.
3. **Progress pushes are ordered by whole seconds against the server clock.** A push stamped with the same second as a later web save is dropped, and the response is still 200.
4. **`POST auth/login` is limited to five requests a minute.** Keep the session; refresh instead of logging in again.
5. **A repeated `POST books/:id/annotations` creates a duplicate.** There is no idempotency key on the JWT annotation endpoint. Device highlights go through the exchange endpoint, which is keyed.
6. **`POST koreader/plugin/sweeps` with a plugin version below 1.4.0 switches off self-update for the user's real KOReader plugin.** After a sweep with `pluginVersion: 0.1.0`, `GET koreader/plugin/version` without identity headers returned `pluginVersion: unknown` instead of `1.5.5`. Perigee must not call `sweeps` with its own version.
7. **Highlight colour sent as a name is not preserved.** A web highlight created with `color: blue` came down as `yellow`. Not investigated further.
8. **Whitespace at the ends of a highlight is dropped.** A range ending on a space (`.40 … .65`) was stored as `/7:40 … /7:64` and came back as `.40 … .64`. Trim a selection before sending it, or the device and the server disagree by one character.
9. **`fileHash` is absent from `GET books/:id` on 3.3.0.** It exists on `main` only. Until it ships, a local file is matched through `POST koreader/plugin/match-check`.

## FB2

The xpointer is stored and returned verbatim to crengine devices. The JWT API reports only the percentage: `cfi` is `null`. So an FB2 book resumes exactly between Perigee and KOReader, and approximately in the web reader. Highlights for FB2 were not exercised.

## Not covered

- Bookmarks (`bookmarks/exchange`), deletions, edits, acknowledgements.
- Reading statistics, status and rating.
- Real-world EPUBs, KEPUB, MOBI.
- A server newer than 3.3.0.
