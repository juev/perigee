# Perigee

An Android e-book reader for text formats, built on [crengine](https://github.com/koreader/crengine), the layout engine of KOReader. The goal is a reader with book-quality typography and simple controls that syncs reading progress, highlights and bookmarks with a self-hosted [bookOrbit](https://github.com/bookorbit/bookorbit) server.

Perigee is a working name.

## Status

Early development. There are no releases yet.

Works today:

- Opening a book and reading it: page turning by touch zones, swipes and volume keys; the place is kept across rotations and restarts.
- Layout by the native engine: hyphenation by the dictionary of the book's language, justification, hanging punctuation, footnotes at the bottom of the page, two pages side by side in landscape.
- Reading settings modelled on ReadEra: colour scheme, font, size, boldness, line spacing, alignment, paragraph spacing and indent, margins. They take the lower half of the screen, so the page above shows each change as it is made.
- Table of contents, bookmarks, highlights with notes, search in the book, return from a jump.

- A library of the books in the folders you pick: shelves, authors, series, collections, search.
- OPDS catalogues, 1.x and 2.0: browsing, search, downloading into the library with progress and a cancel; Project Gutenberg is suggested out of the box.
- A bookOrbit server: signing in, its books with covers and search, downloading them, and finding which of your books the server already has.

- Syncing with bookOrbit: the place you got to, both ways, with a later place read elsewhere offered rather than jumped to; highlights with notes and bookmarks, both ways; status and rating; time spent reading for the server's statistics.

Not there yet: releases in the stores. A spike has shown that exact positions and highlights can be exchanged with bookOrbit for EPUB; see [docs/spikes/position-contract.md](docs/spikes/position-contract.md).

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
./gradlew :app:assembleFdroidDebug
```

The engine and its dependencies are built from source for arm64-v8a, armeabi-v7a and x86_64, so the first build takes a while.

Install on a connected device:

```sh
./gradlew :app:installFdroidDebug
```

There are two builds of the app, `fdroid` and `play`, one for each store. Their code is the same so far. The packages for the stores, shrunk by R8 and unsigned:

```sh
./gradlew :app:assembleFdroidRelease :app:bundlePlayRelease
```

F-Droid builds the app from source and signs it with its own key. Two clean builds of the same commit give the same APK byte for byte, whatever directory they are made in. Google Play takes a bundle signed with an upload key. With `PERIGEE_UPLOAD_KEYSTORE` and `PERIGEE_UPLOAD_PASSWORD` set, `bundlePlayRelease` signs the bundle with the key `perigee` from that keystore.

The [release workflow](.github/workflows/release.yml) builds the signed bundle on a `v`-prefixed tag that matches `versionName`, or when run by hand. It takes the key and its password from the repository secrets `PERIGEE_UPLOAD_KEYSTORE_B64` and `PERIGEE_UPLOAD_PASSWORD`, checks the certificate of the bundle and keeps the bundle as an artifact of the run for 90 days.

The same workflow builds the `fdroid` APK once for each ABI and signs it with the release key: with `PERIGEE_RELEASE_KEYSTORE` and `PERIGEE_RELEASE_PASSWORD` set, `assembleFdroidRelease` signs the APK with the key `perigee` from that keystore. The key comes from the secrets `PERIGEE_RELEASE_KEYSTORE_B64` and `PERIGEE_RELEASE_PASSWORD`. On a tag the APKs and their `SHA256SUMS` go to a draft GitHub release of that tag; write its notes and publish it by hand. Releases are built there only: an unsigned APK does not install on a phone.

On a Mac, `scripts/make-upload-key.sh` makes the upload key once. It writes the keystore to `~/.local/share/perigee/upload.jks`, puts the password into the Keychain (service `org.evsyukov.perigee.upload`, account `perigee`) and sets both secrets. `scripts/build-release-macos.sh` builds the signed bundle from that key. Keep a copy of the keystore and its password elsewhere: Google Play takes bundles signed with the registered upload key only, and a lost one is replaced through its support.

`scripts/make-release-key.sh` makes the release key the same way: `~/.local/share/perigee/release.jks`, Keychain service `org.evsyukov.perigee.release`, account `perigee`. A phone takes an update signed with the same key only, and a lost release key cannot be replaced: keep a copy of it too.

## Tests

Most tests run on a device or an emulator:

```sh
./gradlew build lint
./gradlew connectedAndroidTest
```

`build` runs the tests that need no device. `connectedAndroidTest` runs the rest on the `fdroid` build; the contract tests in it are skipped unless they are told where the server is.

With more than one device attached, pick one with `ANDROID_SERIAL`.

The contract tests talk to a real bookOrbit server in Docker:

```sh
python3 contract/up.py
./gradlew :contract:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.bookorbitUrl=http://10.0.2.2:3070
```

In CI the app's screen tests are run up to three times by `.github/scripts/device_tests.py`, as an emulator on a shared machine fails one now and then for nothing in the code. A test that passed only on a later attempt is listed in the job's summary and as a warning on the run: such a test is to be fixed, not left to the retry. Its own tests: `python3 -m unittest discover -s .github/scripts`.

## Layout of the code

| Module | What it is |
|---|---|
| `engine` | crengine built for Android, a JNI layer and a Kotlin API: pages, positions, selection, search, contents |
| `data` | Room and DataStore: the library, positions, bookmarks, highlights, catalogues, the server account, settings |
| `opds` | OPDS 1.x and 2.0 catalogues: parsing, browsing, downloads |
| `sync` | The bookOrbit server: account, session, its books, matching files; `sync-fake` is a stand-in server for tests |
| `app` | The app in Jetpack Compose: reading screen, library, catalogues, server |
| `contract` | Tests against a real bookOrbit server: positions, OPDS, account |
| `third_party/crengine` | The engine, a git submodule pinned to a commit |

Specifications are in [docs/specs](docs/specs): [reader.md](docs/specs/reader.md) for the engine API and the reading screen, [library.md](docs/specs/library.md), [opds.md](docs/specs/opds.md), and [sync-bookorbit.md](docs/specs/sync-bookorbit.md) for the server.

## Privacy

The app talks only to the servers you add; what it sends to them is in [docs/privacy.md](docs/privacy.md). The texts for the stores are in `fastlane/metadata`.

## License

Perigee is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version. See [LICENSE](LICENSE).

It is distributed in the hope that it will be useful, but without any warranty; without even the implied warranty of merchantability or fitness for a particular purpose.

Third-party code:

- What is built into the app, and under which license, is listed in [NOTICE](NOTICE); the license texts are in [licenses](licenses). The app shows both under "Licenses" in the menu of the library.
- crengine is licensed under the GNU GPL. Its upstream, CoolReader, states "version 2 of the License, or (at your option) any later version"; the KOReader fork carries no statement of its own beyond "GNU GPL license, version 2" in its README.
- FreeType, HarfBuzz, FriBidi, libunibreak, utf8proc, xxHash, Zstandard, libpng, libjpeg-turbo and libwebp are downloaded at build time and linked into the engine. zlib comes from the Android NDK.
- The fonts that come with the app (Comfortaa, Droid Serif, Literata, Merriweather, Noto Serif, PT Sans, PT Serif, Roboto) lie in the engine's assets, unchanged; Droid Serif is under the Apache License 2.0, the rest under the SIL Open Font License 1.1. NOTICE says where each comes from.
- The PT Serif fonts in the engine's test assets are under the SIL Open Font License, included next to them.
