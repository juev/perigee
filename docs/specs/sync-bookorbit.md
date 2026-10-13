# bookOrbit: the account, the server's books, and finding the reader's books on it

Requirements R6, R7, R9, R10 and R12 of `plan.md`. The reader signs in to a bookOrbit server, looks through its books, downloads them into the library described in `library.md`, and the app finds out which books of the library the server has. The reader's place, status, rating and the pages read are kept in step with the server; highlights and bookmarks are the step that follows and are not described here.

Checked against bookOrbit 3.3.0: by reading its source at tag `v3.3.0`, and by the contract tests against its docker image.

## Two ways of talking to the server

| | Web API | Devices' channel |
|---|---|---|
| What goes through it | signing in, the list of books, covers, files, setting up the channel | which files the server has; later, everything about reading |
| Who the app is | the reader, by a token: `Authorization: Bearer` | a reading device, by `x-auth-user` and `x-auth-key` |
| Server routes | `auth/*`, `app-info`, `books/*`, `koreader/credentials` | `koreader/users/auth`, `koreader/plugin/*` |

The devices' channel is the one the server keeps for KOReader. The app speaks its protocol as observed; no code of the server or of its KOReader plugin is copied.

## Module `:sync`

- `SyncBackend` is what the app needs of a server whatever server it is: a page of its books, a cover, a file, and which of a set of file fingerprints it has. `BookOrbit` is the one implementation.
- `BookOrbit` also does what is bookOrbit's own: signing in and out, renewing the session, setting up the devices' channel, checking the server's version.
- The account lives in `:data` (`Accounts`, table `accounts`): one server at most. Books carry what the server said of them: `serverBookId`, `serverFileId`, `serverCheckedAt`.
- HTTP is `HttpURLConnection` with `org.json`, without new dependencies. Redirects are not followed: tokens and passwords are for the server the reader named.

## Signing in

1. The address is taken as the reader means it: `https://` when no scheme is given, without a trailing slash or `/api/v1`. A path in front of the API is kept, for a server behind a proxy.
2. `GET auth/login-options`, which needs no account, tells whether a bookOrbit server is there and whether it lets anyone in by password. The password is sent only after both are true.
3. `POST auth/login` with `clientKind: "native"` returns a token to ask with (15 minutes) and a token to renew with (7 days, prolonged at every renewal).
4. `GET app-info` gives the server's version.
5. The devices' channel is set up, see below.

Told apart for the reader: not an address; no bookOrbit server there; refused user name and password; too many attempts (HTTP 429, or `errorCode: account_locked`); signing in by password switched off (`passwordLoginEnabled: false`, or `errorCode: password_auth_disabled`); no answer.

The password is kept nowhere. Tokens are stored sealed with a key of the Android Keystore (`Secrets`, as catalog passwords are).

OIDC is not supported: a server that lets nobody in by password cannot be used yet.

### Plain HTTP

Plain HTTP is allowed, because a server at home is often reached by a local address. Before a password is sent to an address that starts with `http://` the app says that it will travel unprotected and asks to confirm. This holds for signing in to the server, and for adding an OPDS catalog with a user name or password; a catalog open to everyone is added without the question.

## The session

- A token within 30 seconds of running out is renewed before it is used; one the server refuses with 401 is renewed and the request is made once more.
- A renewal gives a new pair and makes the old token to renew with useless, so there is one renewal at a time, whoever asks; the new pair is stored before it is used.
- The server takes an old token again within 30 seconds of its renewal, which covers an answer lost on the way. Past that, a repeated old token ends the whole session on the server.
- When a renewal is refused, the tokens are dropped and the account stays: the reader is asked to sign in again to the same server, and nothing is tried until they do.
- Signing out ends the session on the server when it can be reached, and forgets the server and what was known of its books either way. The books stay.

`POST auth/login` is limited to five requests a minute by the server; the app signs in once and renews.

## The devices' channel

The server keeps one user name and password of this channel per reader, the same for all their devices. `x-auth-key` is the MD5 of the password.

- **The reader has none.** The app makes one: the user name is the reader's own, with a random ending when that is taken; the password is random and is never shown. Only its key is stored, sealed.
- **The reader has one already**, as someone who uses KOReader. The server does not give the password back, and replacing it would cut the reader's other devices off. The app asks for the password, checks it with `GET koreader/users/auth`, and stores the key.
- **The key stops being taken**, as after the password was changed in the browser: the key is dropped and the password is asked for again.
- **The reader is not allowed** to sync devices (permission `koreader_sync`), or syncing is switched off in their KOReader settings on the server: the app says so; the server's books can still be seen and downloaded.

A reader for whom the app made the password and who later wants KOReader on the same server sets a new password in the server's settings and gives it to both.

The channel is looked at by one caller at a time: signing in and the screen opening together would otherwise each make a user name, or drop the key the other stored.

The app names itself with a `deviceId` made when the reader signs in (`perigee-<uuid>`), `deviceModel: Perigee` and its own version as `pluginVersion`. It never calls `koreader/plugin/sweeps`: with a version below 1.4.0 that switches off self-update of the reader's real KOReader plugin (`docs/spikes/position-contract.md`).

## The server's version

The app was made for versions 3.3 and later of the third. For any other — an older one, a fourth, one that does not name its version in numbers, as a server built from source does ("Local build") — the devices' channel is not used at all: nothing is asked through it and no user name is made. The reader is told; the list of books and downloads still work. The version is asked anew each time the server's screen is opened.

This is the app's half of R12. The other half, running the contract tests against the pinned and the latest image of the server, belongs to CI (step 12 of the plan); `BOOKORBIT_VERSION` in `contract/docker-compose.yml` is there for it.

## The server's books

- `POST books/query`, 50 books to a page, by title; with a phrase, those the server finds by it (`q`).
- A book has a title, authors, a series with a number, and files, each with a format and a size. A file whose format the app does not read is not offered; of several files in one format the first is.
- Covers are the server's thumbnails (`GET books/:id/thumbnail`), asked for only when the server says the book has one.
- `GET books/files/:fileId/download` gives the file under the name the server names it by. A name cannot lead out of the folder the file is downloaded into, and one that does not end as the format does gives way to the book's title. The download does not resume: this route ignores `Range`.
- A downloaded file goes where downloads from catalogs go (`Arrivals`): the folder the reader chose for downloads, or the app's own storage. It is known to be the server's without asking.

## Finding the reader's books on the server

A book of the library is known by the fingerprint of its file — KOReader's partial MD5, see `FileHash` — which is what the server matches files by. `POST koreader/plugin/match-check` takes up to 500 fingerprints and answers with the book and file of each one it has.

- Books never asked about are asked about after each look through the folders.
- Every book the server is not known to have is asked about again when the server's screen is opened, and when the reader signs in.
- A book the server has not is read as before, without a word.
- A file that matches files of two different books on the server is matched to neither; that is the server's rule.

The server remembers fingerprints it was asked about and did not know, and lists them on its page of unmatched KOReader books. A library with many books the server has not will fill that list.

`GET books/:id` has no fingerprint of the file in 3.3.0, so there is no way to match through the web API.

## Syncing what is read

Requirements R7 and R9. `BookOrbit.sync()` is one round: the reader's places, status and rating, and the pages read, for the books the server has. It goes through the devices' channel, so nothing of it happens for a server of a version the app was not made for, or while the channel is not ready.

### Places

`PUT koreader/syncs/progress` takes a place as KOReader sends it: the file's fingerprint, the xpointer, the share of the book read, the name and id of the device, and the time the reader got there. `GET koreader/syncs/progress/:hash` answers with the latest place the server has from anywhere — a device, or the web reader, whose CFI the server turns into an xpointer for EPUB.

- A place is the reader's to send once they moved: where a book opens the first time, and the same place shown again, are not moves. Only the last place of a book is sent.
- Before a place is sent the server is asked for its latest. One from another device or the web reader, later than the reader's last move here and than anything they were told of before, is not gone to: it is kept as an offer. While the reader has not decided, the place here is not sent over it. This is what keeps a later place elsewhere from being overwritten by an older one here (Lagrange #276).
- An offer names the share of the book and the device. "Go there" is a jump like any other, and Back returns; the place gone to becomes the reader's own and is sent. "Stay here" keeps the place here, which is then sent as theirs. Either way the same place is not offered again.
- A place from elsewhere that is the same share of the book as here, within a thousandth, is not offered: xpointers of one place differ between the engine and the server and cannot be compared as text.
- A place known only as a share — FB2 read in the web reader — is offered and gone to by the share.
- The server answers a book it has no more with 404; the book stops being synced and stays the reader's.
- A place is asked about when its book is opened, and in every round for books being read and books with something to send.

The server orders places by whole seconds, and a later place there is "later" by the clocks of the devices that sent it: a device whose clock is wrong offers, or fails to offer, accordingly.

### Status and rating

- The app's statuses are the server's `unread`, `want_to_read`, `reading`, `read`. The server's others, `on_hold` and `abandoned`, have no word here: a book with one keeps the reader's own status, and it is not replaced on the server unless the reader changes the status here.
- What the reader changed here goes up through `PUT koreader/plugin/catalog/books/:id/read-status` and `…/rating`; only what was changed and differs is sent, so that stars given here do not carry an old status along.
- Everything else is taken as the server has it, from `POST books/query`, 200 books to a page. The later change wins; a change here that the server has not heard of is not undone by what it says meanwhile.
- A rating is one to five stars, set in the book's menu in the library; choosing the same number again takes it off.

### Pages read

The reading screen times each page: from when it is shown to when another is, the book is closed, or the app is put away. Less than 5 seconds is not a page read, and no page counts for more than 120 — the bounds KOReader counts by. The times are kept in `page_reads` and go up through `POST koreader/plugin/page-stats`, at most 500 to a request; the server makes reading sessions and statistics of them, and tells apart what it was sent twice. Pages read in a book the server has not are kept for 90 days, in case the book gets there.

### When

- A round is asked for when the app starts, when a book is closed, when the app is put away with a book open, and when a status or a rating is changed. It runs through WorkManager some seconds later, when there is a network; a round that could not reach the server is tried again with growing pauses.
- What only the reader can put right — a session that ended, the KOReader password, a server of another version — is not tried over and over; it is said on the server's screen.
- There is no round on a timer: what was read elsewhere is learnt when the app is next opened, or the book is.

Sending is safe to repeat: the last place wins by its own time, status and rating are set to a value, and pages read are deduplicated by the server.

## Screen

"bookOrbit server" in the library's menu.

- Signed out: the address, user name and password. After a session that ended, the address and user name are filled in.
- Signed in: the server's books with covers, search, and the next part of the list fetched as its end is reached. A book that is in the library says so and offers "Read"; the others offer "Download" per format. "Sign out" is in the menu, with who is signed in and where.
- Above the list, when reading cannot be synced: the question for the KOReader password, or why not — not allowed, switched off, a version the app was not made for.
- In the library a book the server has is marked "on the server".

## Uploading books to the server

Not built; the form is the reader's to choose. The server has three ways to take a file:

| Way | Routes | Needs | What happens |
|---|---|---|---|
| Straight into a library | `POST libraries/:id/upload` | permission `library_upload`; a library, and a folder when it has several | One request. The book is in the library at once, named by the library's own pattern. The same file under another name is not noticed and becomes a second book. |
| Book Dock | `POST book-dock/upload`, then `book-dock/finalize` | `book_dock_access`; `library_upload` to finalize | The file waits in a staging area, where the reader checks its metadata in the browser and files it. Duplicates are pointed out there. |
| Resumable sessions | `POST uploads`, `uploads/:id/chunks`, `uploads/:id/complete` | as the target: a library, or the Book Dock | Chunks of 16 MB with an idempotency key and checksums; an upload that broke off goes on. The server's own web pages do not use these routes. |

Limits either way: 500 MB by default (`maxUploadSizeMb` of `app-info`); of the formats the app reads the server takes only epub, fb2 and mobi.

## Tests

- `:data` `AccountsTest` — sealed tokens and key, what the server said of books, signing out and in to another server; `MigrationTest` — a database of every earlier version.
- `:sync` `BookOrbitTest` — against a stand-in server (`:sync-fake`, `FakeOrbit`) that answers as 3.3.0 was seen to: addresses, versions, each refusal to sign in, no password to a non-server or across a redirect, renewal before and after a refusal, one renewal for many requests, a session that ended, each state of the devices' channel, the channel looked at from several places at once, a server of another version, the list of books, file names, matching in parts of 500.
- `:app` `ServerScreenTest` — the screen against the same stand-in: signing in with each kind of failure, the plain HTTP question, a download into the library, a file that is not a book, a book the reader already has, the KOReader password, an unknown version, search, the next part of the list, a session that ended, signing out, a server that fails and is tried again. `OpdsScreenTest` — the plain HTTP question for catalogs. `LibraryScreenTest` — the mark on a book the server has.
- `:data` `SyncStateTest` — what is a move and what is not, what is still to be sent, the server's status giving way to the reader's unsent one, offers, pages read, another server told everything anew.
- `:sync` `SyncRoundTest` — rounds against the stand-in server: a place sent once, a later place elsewhere offered and not overwritten, a book just opened, the same place, a place as a share, reading without the server, an answer that was lost, a book the server has no more, status and rating both ways, a status the app has no word for, many pages read, a server of another version, a channel that is not ready.
- `:app` `ReaderScreenTest` — going to an offered place and back, an offer as a share, staying, the time on pages with its bounds and the app put away; `LibraryScreenTest` — stars; `server.SyncRoundTest` — which rounds are done with, tried again, or wait for the reader.
- `:contract` `SyncContractTest` — against bookOrbit 3.3.0: a place sent and seen by the web reader as a CFI, a later place from the web reader offered with an xpointer the engine finds on the right page and not overwritten, the reader staying, FB2 kept word for word, status and rating both ways including `on_hold`, pages read in the book's statistics once.
- `:contract` `AccountContractTest` — against bookOrbit 3.3.0 in docker: signing in, a refused password, the KOReader password asked for and refused, a channel made for a reader who has none, the list and search, a download read by the engine and found back by its fingerprint, a book the server has not, a missing cover, renewal of a token that ran out and of one that is not taken, signing out ending the session on the server.

```
python3 contract/up.py
./gradlew :contract:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.bookorbitUrl=http://10.0.2.2:3070
```

Checked by hand on the emulator against the same server: signing in over `http://` with the question, the KOReader password, the list, a download kept in the app, the mark in the library, the screen reopened still signed in, the book opened from it; and a place read on another device offered when the book was opened, gone to, and come back from.

## Not done

- Server's books in the library's own list: a book that is only on the server is seen on the server's screen, not among the reader's books.
- Uploading, see above.
- OIDC.
- Download progress, cancelling, resuming.
- Highlights and bookmarks.
- A round of syncing on a timer, and one while a book is open: a place read elsewhere meanwhile is learnt when the book is next opened.
- A sign in the library or the reader that something waits to be sent, or could not be.
- The reader's review of a book, and the statuses `on_hold` and `abandoned`.
- KEPUB files of the server.
- A second server.
- Running the contract tests against the latest server image in CI.
