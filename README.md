# Perigee

An Android e-book reader for text formats, built on [crengine](https://github.com/koreader/crengine), the layout engine of KOReader. The goal is a reader with book-quality typography and simple controls that syncs reading progress, highlights and bookmarks with a self-hosted [bookOrbit](https://github.com/bookorbit/bookorbit) server.

Perigee is a working name.

## Status

Early development. There are no releases yet.

Works today:

- Opening a book and reading it: page turning by touch zones, swipes and volume keys; the place is kept across rotations and restarts.
- Layout by the native engine: hyphenation by the dictionary of the book's language, justification, hanging punctuation, footnotes at the bottom of the page, two pages side by side in landscape.
- Reading settings modelled on ReadEra: colour scheme, font, size, boldness, line spacing, alignment, paragraph spacing and indent, margins.
- Table of contents, bookmarks, highlights with notes, search in the book, return from a jump.

- A library of the books in the folders you pick: shelves, authors, series, collections, search.
- OPDS catalogues: browsing, search, downloading into the library.
- A bookOrbit server: signing in, its books with covers and search, downloading them, and finding which of your books the server already has.

- Syncing with bookOrbit: the place you got to, both ways, with a later place read elsewhere offered rather than jumped to; highlights with notes and bookmarks, both ways; status and rating; time spent reading for the server's statistics.

Not there yet: release builds. A spike has shown that exact positions and highlights can be exchanged with bookOrbit for EPUB; see [docs/spikes/position-contract.md](docs/spikes/position-contract.md).

The engine reads EPUB, FB2, FB3, MOBI (not KF8/AZW3), TXT, RTF, DOC, DOCX, ODT, HTML and CHM. The tests cover EPUB and FB2; the other formats have not been checked in Perigee.

## Requirements

Android 11 or later.

## Building

You need:

- JDK 21
- Android SDK with platform 36, NDK 28.2.13676358 and CMake 3.31.6
- Internet access on the first build: the engine's dependencies are downloaded as pinned source archives, verified by SHA-256 and kept in `engine/.deps`

```sh
git clone --recurse-submodules https://github.com/juev/perigee.git
cd perigee
./gradlew :app:assembleDebug
```

The engine and its dependencies are built from source for arm64-v8a, armeabi-v7a and x86_64, so the first build takes a while.

Install on a connected device:

```sh
./gradlew :app:installDebug
```

## Tests

Most tests run on a device or an emulator:

```sh
./gradlew build lint testDebugUnitTest
./gradlew :engine:connectedDebugAndroidTest :data:connectedDebugAndroidTest :app:connectedDebugAndroidTest
```

With more than one device attached, pick one with `ANDROID_SERIAL`.

The contract tests talk to a real bookOrbit server in Docker:

```sh
python3 contract/up.py
./gradlew :contract:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.bookorbitUrl=http://10.0.2.2:3070
```

## Layout of the code

| Module | What it is |
|---|---|
| `engine` | crengine built for Android, a JNI layer and a Kotlin API: pages, positions, selection, search, contents |
| `data` | Room and DataStore: the library, positions, bookmarks, highlights, catalogues, the server account, settings |
| `opds` | OPDS 1.x catalogues: parsing, browsing, downloads |
| `sync` | The bookOrbit server: account, session, its books, matching files; `sync-fake` is a stand-in server for tests |
| `app` | The app in Jetpack Compose: reading screen, library, catalogues, server |
| `contract` | Tests against a real bookOrbit server: positions, OPDS, account |
| `third_party/crengine` | The engine, a git submodule pinned to a commit |

Specifications are in [docs/specs](docs/specs): [reader.md](docs/specs/reader.md) for the engine API and the reading screen, [library.md](docs/specs/library.md), [opds.md](docs/specs/opds.md), and [sync-bookorbit.md](docs/specs/sync-bookorbit.md) for the server.

## License

Perigee is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version. See [LICENSE](LICENSE).

It is distributed in the hope that it will be useful, but without any warranty; without even the implied warranty of merchantability or fitness for a particular purpose.

Third-party code:

- crengine is licensed under the GNU GPL. Its upstream, CoolReader, states "version 2 of the License, or (at your option) any later version"; the KOReader fork carries no statement of its own beyond "GNU GPL license, version 2" in its README.
- antiword (GPL-2.0-or-later) and chmlib (LGPL-2.1) come with the crengine tree.
- FreeType, HarfBuzz, FriBidi, libunibreak, utf8proc, xxHash, Zstandard, libpng, libjpeg-turbo and libwebp are downloaded at build time and linked into the engine; each is under its own license. zlib comes from the Android NDK.
- The PT Serif fonts in the engine's test assets are under the SIL Open Font License, included next to them.
