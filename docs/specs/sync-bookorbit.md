# bookOrbit: the account, the server's books, and finding the reader's books on it

Requirements R6 to R10 and R12 of `plan.md`. The reader signs in to a bookOrbit server, looks through its books, downloads them into the library described in `library.md`, and the app finds out which books of the library the server has. The reader's place, quotes and bookmarks, status, rating and the pages read are kept in step with the server. A book the server has not can be sent to it.

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
- **The reader does not know the password**: the app made it up and was installed anew since, or signed out — the key went with its data — or they forgot. They type a new one, six characters at least, and the app sets it (`PATCH koreader/credentials`). Their other reading devices then need the new one too, which the screen says.
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

### Quotes and bookmarks

Requirement R8. They go through `POST koreader/plugin/annotations/exchange` and `…/bookmarks/exchange`, one book to a request, each followed by `…/exchange-ack` when the server handed something over.

- **What a mark is known by.** The server knows a device's mark by the MD5 of the time it was made and its place: `md5("yyyy-MM-dd HH:mm:ss|xpointer")`, the time being the device's wall clock without a zone, as KOReader writes it. A quote and a bookmark keep that time as their `stamp` from the moment they are made; one that came from the server keeps the time the server gave it, and its place in the server's own words, so that the key the app sends is the key the server holds.
- **Up.** A request carries the quotes made or changed here since the server last had them, at most 50, and the keys of all the quotes of the book. A change goes with the same stamp and place, so the server changes the quote it has; sent twice, as after an answer lost on the way, it makes nothing new.
- **Removals.** Nothing is sent about a removal. The server takes a quote of this device for removed when its key is missing from a list that is said to be whole — so the list is said to be whole only when it is: with more quotes than the 5000 keys a request takes (500 for bookmarks), it is sent as not whole and nothing is removed. A removal leaves no row behind to remember it by; the book is marked as having marks changed, and that is what brings it into the next round.
- **Down.** The server hands over quotes made elsewhere, changes to quotes the device has, and removals, a hundred at a time, and is told of each one taken. A quote the library has already, by its stamp and place, is not taken twice. A quote made in the web reader has no place the engine knows until the server has made one: it is handed over on the exchange after the one that asked, which is why a book is asked about twice when it is opened.
- **Colours.** A quote goes up with its colour as `#RRGGBB`; the server tells devices a colour by one of KOReader's nine names, which are shown here as the server's web pages show them.
- **Bookmarks** are the same, with two differences. The server hands a bookmark over without a time; the app stamps it and tells the server the key it will know it by. And the server has no places of its own in formats other than EPUB: a bookmark in FB2 is refused, kept here, and not sent again.
- **Which books.** Books being read, books whose marks changed here, and books with marks that the server was never told of, as those marked before the reader signed in. And the book that is opened, at once.
- **Another server, or signing in again.** Every mark is to be sent anew. To the server the app is then a device it has not seen, and it hands the reader's own quotes back as new ones; they are known by stamp and place and not taken twice.

The ends of a quote are sent as selected. The server drops white space at the ends of a range, so its copy of a quote that ends on a space is a character shorter (`docs/spikes/position-contract.md`).

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
- In the library a book says where it stands with the server — on it, a similar one on it, not on it — and one that is not there can be sent from its menu, see below.

## Sending books to the server

A book of the library whose file the server has not can be sent there, from the book's menu in the library. Nothing is sent without the reader asking for that book.

### What a book is to the server

Each book in the library says where it stands, once the reader is signed in and the server was asked:

| Shown at the book | When | What follows |
|---|---|---|
| on the server | the server has this very file, by its fingerprint | the book is synced |
| a similar one on the server | the server has a book of the same title and authors, and another file | nothing is synced; sending asks first |
| not on the server | neither | the book can be sent |

A likeness is found by what the books say of themselves: the same title, an author in common or none named by either, and not two different books of one series. Case, repeated spaces, punctuation and "ё" against "е" do not tell two titles apart, nor does the order of the words in an author's name. A series tells books apart only when both name one. It is looked for through the server's whole list, 200 books to a page, each time the server is asked about files it was not known to have; `Likeness` holds the rules.

A likeness is a hint and no more. The book here is not tied to the server's one, and nothing of it is synced, until its own file is on the server: it may be another edition, with other places in it, or another book that shares a title.

### Sending

`POST libraries/:id/upload`, the file as the one part of a form; `?folderId=` when the library has several folders. `BookOrbit.upload` does, in order:

1. Asks the server for the file by its fingerprint once more (`match-check`). A file that got there meanwhile is not sent; the book is marked as on the server.
2. Asks what the server takes (`GET uploads/capabilities`) and stops before sending when the reader may not send, the format is not taken by the server or by the library, the library or folder is gone, or the file is larger than the limit.
3. Sends the file, streamed, under a name made of the book's title. A token the server no longer takes is renewed and the file is sent once more.
4. Asks for the file by its fingerprint again, to learn which file of which book it has become. From then on the book is synced like any other.

One book is sent at a time, so a second asking for the same book finds it there.

Sending is offered only for a book whose file the server has not, in a format it takes — of those the app reads, epub, fb2 and mobi — and to a reader allowed to add books (permission `library_upload`). When the server has one library with one folder, the book goes there; otherwise the reader is asked where, once, and the answer is kept with the account. A place that is gone is asked for again.

Without the devices' channel nothing is sent: the server could not be asked whether it has the file, and the book could not be synced once there.

### What the server does with the file

Checked against 3.3.0 by the contract tests, in a library that keeps a book to a folder and names files by the server's default pattern, author / title / title.

- The server files a book away by what the file says of itself. An EPUB is filed by its own title and author. An FB2 says nothing this server reads before filing: it goes under "Unknown Author" and the name it was sent under, which is why that name is the book's title; its title and author are read afterwards.
- **The same title and author in another EPUB file** would get the name the first has: the server answers 409 `UPLOAD_DESTINATION_CONFLICT` and keeps one book. The reader is told that the server keeps a file under this book's name.
- **The same title in another format** — an FB2 next to an EPUB — is filed elsewhere and becomes a second book.
- **Another title** is another book.
- The fingerprint is known as soon as the server has answered; the title and authors a little later.

Refusals told apart for the reader: too large (`UPLOAD_TOO_LARGE`, or 413), a format not taken (`UPLOAD_FORMAT_NOT_ALLOWED`, `UPLOAD_FORMAT_UNSUPPORTED`), a name taken (`UPLOAD_DESTINATION_CONFLICT`), not the book its name says (`UPLOAD_CONTENT_INVALID`, `UPLOAD_EMPTY`), no room (`UPLOAD_STORAGE_FULL`), a library or folder that is gone (404, or 400 about the folder), not allowed (403). What became of the book is shown at the book: how much has gone, then "on the server", or why not.

The server has two other ways to take a file, which the app does not use: the Book Dock (`book-dock/upload`, a staging area the reader files books from in the browser) and resumable sessions (`uploads`, in chunks of 16 MB). The limit is 500 MB unless the administrator set another.

## Tests

- `:data` `AccountsTest` — sealed tokens and key, what the server said of books, signing out and in to another server; `MigrationTest` — a database of every earlier version.
- `:sync` `BookOrbitTest` — against a stand-in server (`:sync-fake`, `FakeOrbit`) that answers as 3.3.0 was seen to: addresses, versions, each refusal to sign in, no password to a non-server or across a redirect, renewal before and after a refusal, one renewal for many requests, a session that ended, each state of the devices' channel, the channel looked at from several places at once, a server of another version, the list of books, file names, matching in parts of 500.
- `:app` `ServerScreenTest` — the screen against the same stand-in: signing in with each kind of failure, the plain HTTP question, a download into the library, a file that is not a book, a book the reader already has, the KOReader password, an unknown version, search, the next part of the list, a session that ended, signing out, a server that fails and is tried again. `OpdsScreenTest` — the plain HTTP question for catalogs. `LibraryScreenTest` — the mark on a book the server has.
- `:data` `SyncStateTest` — what is a move and what is not, what is still to be sent, the server's status giving way to the reader's unsent one, offers, pages read, another server told everything anew.
- `:sync` `SyncRoundTest` — rounds against the stand-in server: a place sent once, a later place elsewhere offered and not overwritten, a book just opened, the same place, a place as a share, reading without the server, an answer that was lost, a book the server has no more, status and rating both ways, a status the app has no word for, many pages read, a server of another version, a channel that is not ready.
- `:app` `ReaderScreenTest` — going to an offered place and back, an offer as a share, staying, the time on pages with its bounds and the app put away; `LibraryScreenTest` — stars; `server.SyncRoundTest` — which rounds are done with, tried again, or wait for the reader.
- `:data` `MarksSyncTest` — stamps and keys, every change to marks being news even a removal, marks from the server not sent back and not taken twice, another server told of every mark anew.
- `:sync` `MarksRoundTest` — a quote made, changed and removed on either side, an answer lost on the way, a list too long to be whole, hundreds of quotes in parts, bookmarks both ways, a bookmark the server cannot place, marks made before signing in, the reader's own quotes not taken twice after signing in again, a book the server has no more, colours.
- `:contract` `MarksContractTest` — against bookOrbit 3.3.0: a quote made here seen by the web reader at an exact CFI, changed here, sent twice without a second one made, changed and removed in the web reader; a quote made in the web reader taken with a place the engine finds the same words by, and removed here; bookmarks made and removed on either side.
- `:contract` `SyncContractTest` — against bookOrbit 3.3.0: a place sent and seen by the web reader as a CFI, a later place from the web reader offered with an xpointer the engine finds on the right page and not overwritten, the reader staying, FB2 kept word for word, status and rating both ways including `on_hold`, pages read in the book's statistics once.
- `:sync` `LikenessTest` — which books are taken for one: case, spaces, punctuation and "ё", an author's name either way round, one author in common, no title, series. `UploadTest` — against the stand-in server: what the server takes, a book sent whole under its title and synced from then on, a book not sent twice, one that got there meanwhile, the same book asked for twice at once, what is not sent at all, each refusal, a session renewed, no devices' channel, a likeness pointed out, gone with the server's book, looked for through the whole list, and left behind by a book that was sent.
- `:data` `AccountsTest` — a likeness kept until the book's own file is there, the place books are sent to forgotten with the server.
- `:app` `LibraryScreenTest` — a book sent from its menu, sending offered only for what the server takes from the reader, the question before a book like one on the server, where to send asked once, a refusal said at the book, a server that does not answer.
- `:contract` `UploadContractTest` — against bookOrbit 3.3.0: what it takes, a book sent, found by its fingerprint and not sent twice, an FB2 with a Russian title, a file that is not what its name says, a place that is not there, and the three things the server does with a book that looks like one it has. The books are taken off the server afterwards.
- `:contract` `AccountContractTest` — against bookOrbit 3.3.0 in docker: signing in, a refused password, the KOReader password asked for and refused, a channel made for a reader who has none, the list and search, a download read by the engine and found back by its fingerprint, a book the server has not, a missing cover, renewal of a token that ran out and of one that is not taken, signing out ending the session on the server.

```
python3 contract/up.py
./gradlew :contract:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.bookorbitUrl=http://10.0.2.2:3070
```

Checked by hand on the emulator against the same server: signing in over `http://` with the question, the KOReader password, the list, a download kept in the app, the mark in the library, the screen reopened still signed in, the book opened from it; a place read on another device offered when the book was opened, gone to, and come back from; and quotes kept on the server listed in a book opened for the first time.

## Not done

- Server's books in the library's own list: a book that is only on the server is seen on the server's screen, not among the reader's books.
- Sending several books at once, or without being asked; cancelling a book on its way; going on with one that broke off.
- Tying a book to the server's one by its title and authors.
- Sending `fb2.zip`: the server takes no such file, and the app does not unpack it to send.
- OIDC.
- Download progress, cancelling, resuming.
- A round of syncing on a timer, and one while a book is open: a place read elsewhere meanwhile is learnt when the book is next opened.
- A sign in the library or the reader that something waits to be sent, or could not be.
- The reader's review of a book, and the statuses `on_hold` and `abandoned`.
- Styles of a quote other than a highlight (underline, strike-through), which the server has and the app shows as highlights; the server's stars on quotes.
- Trimming a quote to its words before it is sent.
- A bookmark's words changed elsewhere: the server does not hand such a change over.
- KEPUB files of the server.
- A second server.
- Running the contract tests against the latest server image in CI.
