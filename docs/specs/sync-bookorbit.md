# bookOrbit: the account, the server's books, and finding the reader's books on it

Requirements R6 to R10 and R12 of `plan.md`. The reader signs in to a bookOrbit server, looks through its books, downloads them into the library described in `library.md`, and the app finds out which books of the library the server has. The reader's place, quotes and bookmarks, status, rating and the pages read are kept in step with the server. A book the server has not can be sent to it.

Checked against bookOrbit 3.3.0: by reading its source at tag `v3.3.0`, and by the contract tests against its docker image.

## Two ways of talking to the server

| | Web API | Devices' channel |
|---|---|---|
| What goes through it | signing in, the list of books, covers, files, setting up the channel; the home page's shelves, collections, and the reader's status, stars and note set on the server's screen | which files the server has; everything about reading |
| Who the app is | the reader, by a token: `Authorization: Bearer` | a reading device, by `x-auth-user` and `x-auth-key` |
| Server routes | `auth/*`, `app-info`, `books/*`, `dashboard/scrollers/*`, `collections/*`, `libraries`, `koreader/credentials` | `koreader/users/auth`, `koreader/plugin/*` |

The devices' channel is the one the server keeps for KOReader. The app speaks its protocol as observed; no code of the server or of its KOReader plugin is copied.

## Module `:sync`

- `SyncBackend` is what the app needs of a server whatever server it is: a page of its books, a cover, a file, and which of a set of file fingerprints it has. `BookOrbit` is the one implementation.
- `BookOrbit` also does what is bookOrbit's own: signing in and out, renewing the session, setting up the devices' channel, checking the server's version, and the catalogue's filters and orders (`CatalogueQuery`).
- `OrbitArea` (`BookOrbit.area`) is the rest of what the server's screen shows beyond a list of books: the home page's shelves, collections, the books listed beside a book, and the reader's status, stars and note of a book. It is bookOrbit's own and not part of `SyncBackend`. All of it goes through the reader's session.
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

### Through a provider (OIDC)

"Sign in with SSO" signs in through a provider the server is set up with, as its web pages do; the password, if any, is given on the provider's page and never reaches the app.

1. `GET auth/login-options` lists the providers; those switched off are left out. With none, the reader is told so; with one, it is gone to straight away; with several, the reader chooses.
2. `POST auth/oidc/:slug/state` gives a state, which the server keeps for five minutes, and the address of the provider's sign-in page. With no address the server cannot reach the provider.
3. The app makes a PKCE verifier and a nonce and opens the provider's page in a Custom Tab with `response_type=code`, the provider's `client_id` and scopes, the challenge (`S256`), the state, the nonce and the redirect URI `bookorbit://oauth2-callback`. The server takes that one address from every native client unless `NATIVE_REDIRECT_URI` says otherwise, so a server that changed it cannot be signed in to this way.
4. The provider sends the reader back to that address. `SignInRedirectActivity` takes it, hands it to the server's screen and brings the app back, which closes the tab.
5. An answer is taken only with the state the app is waiting for. `POST auth/oidc/callback` with the code, the verifier, the redirect URI, the nonce, the state and `clientKind: "native"` returns the same session as `auth/login`; the user name is the one the server gives. Then as above from step 4.

The verifier, the nonce and the state are kept in memory only: an app closed while the reader is at the provider asks to start again, as the server would after five minutes anyway.

Told apart for the reader: no providers; turned back at the provider (`error=access_denied`), which is no failure; too long (`errorCode: oidc_state_expired`, or an answer not waited for); refused by the provider or the server, with their own words (the server's English message, such as no account for this user); no browser.

Another app that takes `bookorbit://` addresses, such as bookOrbit's own, makes Android ask which app the answer goes to.

### Plain HTTP

Plain HTTP is allowed, because a server at home is often reached by a local address. Before a password is sent to an address that starts with `http://` the app says that it will travel unprotected and asks to confirm. This holds for signing in to the server, and for adding an OPDS catalog with a user name or password; a catalog open to everyone is added without the question.

## The session

- A token within 30 seconds of running out is renewed before it is used; one the server refuses with 401 is renewed and the request is made once more.
- A renewal gives a new pair and makes the old token to renew with useless, so there is one renewal at a time, whoever asks; the new pair is stored before it is used.
- The server takes an old token again within 30 seconds of its renewal, which covers an answer lost on the way. Past that, a repeated old token ends the whole session on the server.
- When a renewal is refused, the tokens are dropped and the account stays: the reader is asked to sign in again to the same server, and nothing is tried until they do.
- Signing out ends the session on the server when it can be reached, and forgets the server and what was known of its books either way. The books stay.
- A request belongs to the account signed in when it began, known by the name the app calls itself to the server (`deviceId`), made anew at each sign-in. Signing in does not wait for a renewal on its way: renewed tokens are kept only for the account they were renewed for, a refused renewal drops only that account's tokens, and a request is not sent once another account is signed in — after waiting for a renewal, and last thing before it goes. A key of the devices' channel the server refused is forgotten only for its own account, and a key is never sent with another account's user name or to its server.

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

The server's screen, once signed in, has three parts under its top bar, as tabs: **Home**, **Catalogue** and **Collections**. Home is shown first. They are the server's books only: a book that is not downloaded is never shown among the library's, and nothing is downloaded without the reader asking for that book. Each part keeps what it was looked up by, the parts of its list that came, and how far it was scrolled, while another part is shown and while a book's page lies over it.

Ordinary browsing, collections and the reader's own marks of a book go through the reader's session: none of it waits for the devices' channel, which is needed only for syncing reading and for sending books.

### Home

- The server's own shelves, in this order: continue reading, want to read, recently added, next in series, and something new (`continue-reading`, `want-to-read`, `recently-added`, `up-next-in-series`, `random`). The selection and the order are the server's; the app does not choose books itself. Shelves are not hidden, reordered or configured here, and the web pages' own arrangement of them is neither read nor changed.
- They are asked for together, 12 books to a shelf, with `POST dashboard/scrollers/batch` (`items` of `id`, `type`, `limit`); the answer is a list of its own, `{items: [{id, books, failed}]}`, without totals. A shelf the server could not fill is said to have failed, with a retry of that shelf alone (`GET dashboard/scrollers/:type?limit=12`); the rest of the page stays as it is. A server without the batch, or one that refuses it for a shelf it has not, is asked shelf by shelf; a shelf it has not (400 or 404) is said not to be on this server, apart from a failure and from an empty answer.
- The single shelf's `total` is not shown: it counts this month for the newest books and nothing for two shelves, and is never the number of books on the shelf.
- The books on a shelf the app does not read are left out of it, and the shelf says that some are; it is not filled up from elsewhere, and no number is shown for it. A shelf with nothing on it is not shown; a page of shelves all empty says so and leads to the catalogue, and a server with none of them says that too.
- A book on a shelf is its cover and title, and whether it is in the library. A tap opens its page, the same as from the catalogue. No book is asked about for being on a shelf.
- "See all" is offered only for a shelf that a query of the catalogue answers in full: want to read (status `want_to_read`) and recently added (all books, newest first). It goes to the catalogue with that query. The others are samples, with no list behind them.
- Above the shelves: the question for the KOReader password or why reading is not synced, and once the channel is ready, how syncing went and how many books wait to be sent. Who is signed in, and "Sign out", are in the bar's menu.
- A status changed from a book's page asks for the shelves again, without taking the books shown off them meanwhile.

### Catalogue

- `POST books/query`, 50 books to a page; with a phrase, those the server finds by it (`q`). Searching from the bar on Home goes here.
- Only the books with a file in a format the app reads are asked for: the query carries a `format` `includesAny` rule over `Library.FORMATS`, which the server matches on any of a book's files. A book in PDF or a comic only is left out of the page and of the total, so paging counts only what can be downloaded. No switch shows them.
- Filters, each a rule of the same query, all to hold: the reader's status (`readStatus` `includesAny`, the six statuses below), an author (`author` `includesAny`, by part of a name), a series (`series` `contains`), a genre (`genre` `includesAny`, the whole name, as a book's page lists it; a genre on a page leads to its books), and a library (`library` `includesAny`, by name, of those `GET libraries` lists; when they cannot be asked for, the filters say why, with a retry when the server did not answer). The order is by title, by author, newest first (`addedAt` descending) or by series. The server has no list of authors or series to choose from in the reader's session that the app uses, so those are typed.
- The filters in force are shown above the list, each taken off by itself; the phrase and the filters are kept apart, so taking one off leaves the other. A filter or an order the server refuses (400) is said not to be on this server, with a way to clear the filters.
- A book has a title, authors, a series with a number, the reader's status when they set one, and files, each with a format and a size. A file whose format the app does not read is not offered; of several files in one format the first is.
- Covers are the server's thumbnails (`GET books/:id/thumbnail`), asked for only when the server says the book has one.
- `GET books/files/:fileId/download` gives the file under the name the server names it by. A name cannot lead out of the folder the file is downloaded into, and one that does not end as the format does gives way to the book's title. The download does not resume: this route ignores `Range`.
- A downloaded file goes where downloads from catalogs go (`Arrivals`): the folder the reader chose for downloads, or the app's own storage. It is known to be the server's without asking.
- Downloads are one state for the whole screen, by the id of the file: a book on two shelves, in the catalogue and in a collection is downloaded once, and wherever it is shown it says it is in the library and offers "Read".

### Collections

- `GET collections`: the reader's own collections and those other readers share (`isPublic`), told apart by `isOwner`. Another reader's is marked as theirs and only looked through: no change of it is offered, and a refusal of the server (403) is said all the same.
- A collection's books: `POST collections/:id/books/query`, the catalogue's query within the collection, with the format rule, by title, 50 to a page; a phrase looks within the collection only, which the search bar says. A server without that route (404 of its router) is asked `GET collections/:id/books?page&size&q`, and the books the app does not read are left out of each part here. A part may then show nothing: what follows is asked for by the parts the server gave, while the end of the list is in sight, so a collection does not seem to end at a part of PDFs alone.
- "New collection" makes one with `POST collections`: the name, the icon `book` (the server wants one) and `isPublic: false`. Syncing to Kobo is not sent, and stays as the server has it for a new collection. A name the reader has already is told (409).
- Renaming sends `PATCH collections/:id` with the name alone: the icon, description, sharing and syncing stay as the server has them.
- Deleting asks first, and says that only the collection is deleted, not its books on the server or on the device. `DELETE collections/:id`; one gone already is gone.
- A book is put into a collection, or taken out, from its page: `POST`/`DELETE collections/:id/books` with `bookIds`. Taking it out does not delete it, which the page says. The collection being looked through, when it is that one, is asked for again as far as it was shown, so that the book after the one taken out is not skipped.
- A collection made from a book's page has the book put into it at once, for the same reader. How that went is said in the choice of collections, whatever part of the screen the page was opened from: the name typed stays until the collection is made; a collection made without the book in it is kept, with why the book is not. Which of the reader's collections a book is in comes from `GET collections?bookIds=:id` (`memberCount`); a server whose list does not carry it is read from the book's own `collections`.

### A book's page

A tap on a book — a line of a list, its cover included, or a cover on a shelf — opens the book's page. The list does not carry what a book is about, so the page asks `GET books/:id` (`BookDetailDto`): one request per book opened, kept while the server's screen lives, so a page opened again asks nothing. The route checks the same view of the book's library as the list does, so it needs no permission beyond it.

- The page shows the cover, title, subtitle, authors, series with its number, the formats the app reads with their sizes, then the publisher, year, pages, language and ISBN, of these only what the server has. The ISBN is the 13-digit one when there is one.
- The description is the server's sanitised HTML. The app flattens it to text as it does a book's own markup (`library.md`): a paragraph to a line. A book without one says so in the words of the library's page.
- Below it are the same downloads as on the book's line, from the same state: a download begun on the page shows on the line, and the other way round. Once the file is in the library both say so and offer to read it. "Read" closes every page and opens the book.
- Then what the reader keeps of the book on the server, see below, and its genres, each leading to the catalogue of the genre.
- Then the books the server lists beside it: like it (`GET books/:id/recommendations`), by its author (`…/author-books`) and of its series (`…/series-books`, asked only for a book in one). They are asked for when the page opens, not before. Their answers are lists of their own, not catalogue cards: they carry no files, so a book among them is downloaded by what its own page is told; audiobooks and comics among them are left out. An empty list is said as such; a server may have no likeness worked out for a book, which is not a failure of the book. A server without the routes says so.
- A book opened from among them lies over the page, and the way back returns to the page before it, at the place it was left, and through it to the part of the screen the first was opened from, with its query and place. A book among them that is in the library says so by the server's book, before its files are known.
- The page is a dialog over the part it was opened from. A request that fails says so on the page, with a retry; the list behind it stays as it was and can be used. A session that is over is the screen's, as anywhere else on it.

### What the reader keeps of a book

On a book's page, for any book of the server, downloaded or not:

- **Status**, in the server's words: not started (`unread`), want to read, reading, on hold, read, abandoned — the six of 3.3.0. A later server's others (`rereading`, `skimmed`) are shown by name and kept until the reader chooses another. Opening a page changes nothing. `PATCH books/:id/status` with `status`; the answer is the status set.
- **Stars**, one to five, the same one again taking them off: `POST books/bulk-set-rating` with the book's id. The server lets readers who may edit the library's books rate through the session; for anyone else (403), and on a server without the route, they go through the devices' channel, `PUT koreader/plugin/catalog/books/:id/rating`, when it is ready. The rating is then read back with `GET books/:id`: a server that keeps a book's rating locked answers that it set it and does not, and that is said as a change not saved.
- **A personal note**, the server's per-reader review: `PATCH books/:id/personal-note`, empty taking it off; the answer is the book with its note.
- **Collections**, see above.

A change is shown only once the server confirmed it; until then the page says it is being saved, and a refusal says why: not allowed (403), not on the server any more (404), not on this server (404 of the router), not saved (400, or a rating that did not change). Nothing is kept to be sent later: these need the server to be reached. A confirmed status is shown wherever the book is, on its lines and shelves, the shelves are asked for again, and a catalogue narrowed by status is asked for again as far as it was shown, so that the book leaves it or joins it and no book after it is skipped.

What is on its way is the book's, not the page's: the reader may open other books meanwhile, and the book's page opened again says its change is on its way and does not ask for it a second time. Only the answer to that asking ends the waiting.

A book that is in the library as well is changed between rounds of syncing, never during one, and the change is taken into the library as the server confirmed it: a status the app has a word for, the stars, the review. Each copy is taken as it was when the change was asked for, field by field, and the database takes it only while the copy is still so:

- What the reader changed of it in the library before, and the server has not heard of yet, is not sent over the confirmed change afterwards — the status changed there no longer waits to be sent, nor the stars, nor the review.
- What the reader changes of it in the library while the server is asked is later than the change on the page: it stays, and still goes up with the next round. A review or stars confirmed meanwhile do not mark it as sent.
- The status, the rating and the review each have a time of their own, the rating since schema v15, and each change counts on from the one before, so that stars changed and changed back are still a change. A book that had a status or rating waiting when the rating got its time is taken to have its rating waiting.
- Stars changed in the library still go up when only the status was set on the page, and the other way round.
- A copy that came to the library while the server was asked, as by a download, takes the confirmed change unless it has a change of its own waiting.

The page says when the book has such changes waiting. A status the app has no word for leaves the library's own as it is.

Everything asked of the server is for the reader and server signed in when it was asked. Once another one is signed in to, nothing more of it is asked — a rating is not read back, a collection made is not filled, a refused request is not asked again with the next reader's token, and their tokens are not dropped for the refusal — and nothing of its answer is taken or shown, its refusal ends no session, and a download from before is not marked by the numbers of the server before.

### Signing out, another reader, another server

Everything the screen was told — books' pages, the books beside them, covers, shelves, collections — is forgotten when another reader or another server is signed in to, or the reader signs out, and an answer that comes after for the one before is not taken.

## Finding the reader's books on the server

A book of the library is known by the fingerprint of its file — KOReader's partial MD5, see `FileHash` — which is what the server matches files by. `POST koreader/plugin/match-check` takes up to 500 fingerprints and answers with the book and file of each one it has.

- Books never asked about are asked about after each look through the folders.
- Every book the server is not known to have is asked about again when the server's screen is opened, and when the reader signs in.
- A book the server has not is read as before, without a word.
- A file that matches files of two different books on the server is matched to neither; that is the server's rule.

The server remembers fingerprints it was asked about and did not know, and lists them on its page of unmatched KOReader books. A library with many books the server has not will fill that list.

`GET books/:id` has no fingerprint of the file in 3.3.0, so there is no way to match through the web API.

## Syncing what is read

Requirements R7 and R9. `BookOrbit.sync()` is one round: the reader's places, status, rating and review, and the pages read, for the books the server has. It goes through the devices' channel, so nothing of it happens for a server of a version the app was not made for, or while the channel is not ready.

### Places

`PUT koreader/syncs/progress` takes a place as KOReader sends it: the file's fingerprint, the xpointer, the share of the book read, the name and id of the device, and the time the reader got there. `GET koreader/syncs/progress/:hash` answers with the latest place the server has from anywhere — a device, or the web reader, whose CFI the server turns into an xpointer for EPUB.

- A place is the reader's to send once they moved: where a book opens the first time, and the same place shown again, are not moves. Only the last place of a book is sent.
- Before a place is sent the server is asked for its latest. One from another device or the web reader, later than the reader's last move here and than anything they were told of before, is not gone to: it is kept as an offer. While the reader has not decided, the place here is not sent over it. This is what keeps a later place elsewhere from being overwritten by an older one here (Lagrange #276).
- An offer names the share of the book and the device. "Go there" is a jump like any other, and Back returns; the place gone to becomes the reader's own and is sent. "Stay here" keeps the place here, which is then sent as theirs. Either way the same place is not offered again.
- A place from elsewhere that is the same share of the book as here, within a thousandth, is not offered: xpointers of one place differ between the engine and the server and cannot be compared as text.
- A place known only as a share — FB2 read in the web reader — is offered and gone to by the share.
- The server answers a book it has no more with 404; the book stops being synced and stays the reader's.
- A place is asked about when its book is opened or the reader comes back to the open book from elsewhere, and in every round for books being read and books with something to send.

The server orders places by whole seconds, and a later place there is "later" by the clocks of the devices that sent it: a device whose clock is wrong offers, or fails to offer, accordingly.

A place is sent with the time the reader moved to it. From 3.4.0 the server drops, without saying so, a place whose time is earlier than the newest place it knows for the file (3.3.0 took it). A reader who was shown a later place from elsewhere and chose to stay has decided for the place here, so that one is sent with a time a second after the place they turned down. A place reached here before another device's later one and never offered against it — the two were the same share of the book — goes the same way.

### Status and rating

- The library's statuses are the server's `unread`, `want_to_read`, `reading`, `read`. The server's others, `on_hold` and `abandoned`, have no word in the library: a book with one keeps the reader's own status there, and it is not replaced on the server unless the reader changes the status in the library. The server's page of a book shows and sets all six, see "What the reader keeps of a book".
- What the reader changed here goes up through `PUT koreader/plugin/catalog/books/:id/read-status` and `…/rating`; only what was changed and differs is sent, so that stars given here do not carry an old status along.
- Everything else is taken as the server has it, from `POST books/query`, 200 books to a page. The later change wins; a change here that the server has not heard of is not undone by what it says meanwhile.
- A rating is one to five stars, set in the book's menu in the library; choosing the same number again takes it off.

### Review

The server keeps a review of a book for each reader, up to 10 000 characters, without white space at its ends; nothing but white space is no review. The web pages call it a personal note.

- A review written or changed here goes up through the reader's session, `PATCH books/:id/personal-note`, as the web pages send it, in the round after it was written; `null` takes it off. The devices' channel takes reviews too, but weighs one against the server's by the day alone, so that a second change on the same day would not reach the server.
- The server's reviews come through the devices' channel, `POST koreader/plugin/book-states` with the files' fingerprints only, 200 to a request: that sets nothing, and the answer carries each book's review. One the reader changed here and the server has not taken yet is not overwritten.
- A review is written from the book's menu in the library, "Write a review", and shown on the book's page under what the book says of itself. Saved empty, it is taken off.
- Another server, or signing in again: a review written here is sent anew.

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

The server drops white space at the ends of a range (`docs/spikes/position-contract.md`); a selection has none at its ends, so the server's copy of a quote ends where the quote does.

### Stars and bookmarks' titles

The devices' channel carries neither the stars of quotes nor a title given to a bookmark on the server: a star changes nothing a device is handed over, and the server never hands down a renamed bookmark (`bookmark-sync.service.ts`). Both go through the reader's session, `GET books/:bookId/annotations` and `…/bookmarks`, after the exchanges of a book; on opening a book, after the second asking.

- **Which of the server's marks.** The session lists marks by the server's numbers. A mark handed over through the devices' channel comes with its number, and is tied to it. A mark sent from here comes back with none: it is tied to the server's mark not tied yet that says what it was sent as — a quote by its words without white space at the ends, a bookmark by the title the server made of it (its words, else its chapter, else "Bookmark"). Marks that say the same are paired in the order they were made (`Pairing`). A bookmark renamed on the server before it was tied stays untied.
- **Stars.** A star given or taken off here goes up through `PATCH books/:bookId/annotations/:id` with `starred`, in the round after it was changed; until it is sent, the server's word does not overrule it. Otherwise a quote takes the star as the server has it.
- **Titles.** A bookmark tied to one of the server's with a title other than the one the server made shows that title in place of the first words of its page. Nothing goes back: a bookmark is sent once, so the server keeps the title it was given.
- **Another server, or signing in again.** The numbers are forgotten, and a starred quote sends its star anew.

### When

- A round is asked for when the app starts, when a book is closed, when the app is put away with a book open, and when a status or a rating is changed. It runs through WorkManager some seconds later, when there is a network; a round that could not reach the server is tried again with growing pauses.
- What only the reader can put right — a session that ended, the KOReader password, a server of another version — is not tried over and over; it is said on the server's screen.
- The account remembers when a round last went through, and when the rounds since began to fail to reach the server (`syncedAt`, `unreachableSince`, schema v12).
- There is no round on a timer. A book left open while the app was put away is asked about again, place and marks, when the reader comes back to it: a place read elsewhere meanwhile is offered then. What was read elsewhere in other books is learnt when the app is next opened, or the book is.

Sending is safe to repeat: the last place wins by its own time, status and rating are set to a value, and pages read are deduplicated by the server.

## Screen

"bookOrbit server" in the library's menu.

- Signed out: the address, user name and password, and signing in with SSO. After a session that ended, the address and user name are filled in.
- Signed in: the tabs Home, Catalogue and Collections, see "The server's books", Home first. Search is in the bar: of the catalogue, or within the collection that is open, which a line under the search field says. "Sign out" is in the menu, with who is signed in and where.
- Back goes from a book's page to the page before it, then out of the search, takes the phrase off a collection or the catalogue, closes a collection, returns to Home, and leaves for the library. The arrow of the bar does the same, without the step to Home: it is the way to the library.
- On a tablet the tabs and the lists keep to a width lines can be followed across, in the middle. The tabs, shelves and buttons have names a screen reader reads; the shelves' and the page's sections are headings.
- In the library a book says where it stands with the server — on it, on it and not synced yet, a similar one on it, not on it — and one that is not there can be sent from its menu, see below.

## Sending books to the server

A book of the library whose file the server has not can be sent there, from the book's menu in the library, or together with others picked by a long press. Nothing is sent without the reader asking for that book.

### What a book is to the server

Each book in the library says where it stands, once the reader is signed in and the server was asked:

| Shown at the book | When | What follows |
|---|---|---|
| on the server | the server has this very file, by its fingerprint | the book is synced |
| a similar one on the server | the server has a book of the same title and authors, and another file | nothing is synced; sending asks first |
| not on the server | neither | the book can be sent |
| waiting to be sent | asked for with others, its turn has not come | the book can be called off |
| sent in part | the file was cut off on its way, and the server keeps what it got | the book can be sent on, or called off |

A likeness is found by what the books say of themselves: the same title, an author in common or none named by either, and not two different books of one series. Case, repeated spaces, punctuation and "ё" against "е" do not tell two titles apart, nor does the order of the words in an author's name. A series tells books apart only when both name one. It is looked for through the server's whole list, 200 books to a page, each time the server is asked about files it was not known to have; `Likeness` holds the rules.

A likeness is a hint and no more. The book here is not tied to the server's one, and nothing of it is synced, until its own file is on the server: it may be another edition, with other places in it, or another book that shares a title.

### Sending

A file goes through an upload session of the server, in parts. `BookOrbit.upload` does, in order:

1. Asks the server for the file by its fingerprint once more (`match-check`). A file that got there meanwhile is not sent; the book is marked as on the server.
2. Looks up the session the book keeps from an earlier try (`GET uploads/:id`, `Book.uploadSession`). One still taking the same file is gone on with; one that has the whole file is done with, as in step 5; one the server let go of — cancelled, run out after 7 days, failed — or one for a file of another size is forgotten.
3. Without a session to go on with: asks what the server takes (`GET uploads/capabilities`) and stops before sending when the reader may not send, the format is not taken by the server or by the library, the library or folder is gone, or the file is larger than the limit. Then opens a session (`POST uploads`: the file's name and size, the library and folder, a key of its own so that asking twice opens one session) and keeps its id with the book.
4. Sends the rest of the file from where the server got, in parts as large as the server says (16 MB), each with `Upload-Offset`. When the server got more or less of a part than was known here, it answers 409 `UPLOAD_OFFSET_MISMATCH` with the offset to go on from. Then `POST uploads/:id/complete`: the server files the book away and answers with it. A token the server no longer takes is renewed and the request is sent once more.
5. Asks for the file by its fingerprint again, to learn which file of which book it has become, and lets go of the session. From then on the book is synced like any other.

The name the file goes under is the one it has in its folder, an FB2 from an archive without the archive's `.zip`. A book with no folder, as one kept in the app's storage, goes under a name made of its title, as does one whose file name does not end as its format does. The server places the file by the library's pattern of names; the name it was sent under counts only where the pattern has nothing to go on, as with an FB2 to this server.

One book is sent at a time, so a second asking for the same book finds it there. Books asked for together go one after another, in the order asked; those whose turn has not come say so.

**Cut off.** When the network goes, the server keeps what it got, and the book says it was sent in part; sending it again goes on from there. The app closed while a book was on its way leaves the same, and the next round of syncing, and every start of the library, looks the session up (`BookOrbit.settleUploads`): a book whose file the server filed away is marked as on the server, one whose session the server let go of is forgotten here. A refusal of the server, as opposed to its own trouble (5xx), ends the session: it is called off on the server and forgotten here.

**Called off.** A book being sent, waiting to be, or sent in part can be called off from its menu. The file stops between two of its bytes, and the server is asked to let go of what it got (`DELETE uploads/:id`). Once the server has the whole file it files it away all the same, and the book turns out to be there.

Sending is offered only for a book whose file the server has not, in a format it takes — of those the app reads, epub, fb2 and mobi — and to a reader allowed to add books (permission `library_upload`). When the server has one library with one folder, the book goes there; otherwise the reader is asked where, once, and the answer is kept with the account. A place that is gone is asked for again. Books sent together are asked about together: where they go, if that is not known, and whether to send those that look like books the server has — all of them, or only the rest.

An FB2 kept in a zip archive is sent unpacked, as the book it is: the server takes no archives, and the library knows such a book by the file inside (`library.md`, "A book in an archive").

Without the devices' channel nothing is sent: the server could not be asked whether it has the file, and the book could not be synced once there.

### What the server does with the file

Checked against 3.3.0 by the contract tests, in a library that keeps a book to a folder and names files by the server's default pattern, author / title / title.

- The server files a book away by what the file says of itself. An EPUB is filed by its own title and author. An FB2 says nothing this server reads before filing: it goes under "Unknown Author" and the name it was sent under, which is why that name is the book's title; its title and author are read afterwards.
- **The same title and author in another EPUB file** would get the name the first has: the server answers 409 `UPLOAD_DESTINATION_CONFLICT` and keeps one book. The reader is told that the server keeps a file under this book's name.
- **The same title in another format** — an FB2 next to an EPUB — is filed elsewhere and becomes a second book.
- **Another title** is another book.
- The fingerprint is known as soon as the server has answered; the title and authors a little later.

Refusals told apart for the reader: too large (`UPLOAD_TOO_LARGE`, or 413), a format not taken (`UPLOAD_FORMAT_NOT_ALLOWED`, `UPLOAD_FORMAT_UNSUPPORTED`), a name taken (`UPLOAD_DESTINATION_CONFLICT`), not the book its name says (`UPLOAD_CONTENT_INVALID`, `UPLOAD_EMPTY`), no room (`UPLOAD_STORAGE_FULL`), a library or folder that is gone (`UPLOAD_TARGET_INVALID`, 404, or 400 about the folder), not allowed (403). What became of the book is shown at the book: how much has gone, then "on the server", or why not.

The server has two other ways to take a file, which the app does not use: the Book Dock (`book-dock/upload`, a staging area the reader files books from in the browser) and a file sent whole, as the one part of a form (`POST libraries/:id/upload`). The limit is 500 MB in 3.3 and 4 GB from 3.4 on, unless the administrator set another.

## Tests

- `:data` `AccountsTest` — sealed tokens and key, what the server said of books, signing out and in to another server; `MigrationTest` — a database of every earlier version.
- `:sync` `BookOrbitTest` — against a stand-in server (`:sync-fake`, `FakeOrbit`) that answers as 3.3.0 was seen to: addresses, versions, each refusal to sign in, no password to a non-server or across a redirect, renewal before and after a refusal, one renewal for many requests, a session that ended, each state of the devices' channel, the channel looked at from several places at once, a server of another version, the list of books, file names, matching in parts of 500.
- `:app` `ServerScreenTest` — the screen against the same stand-in (the catalogue reached by its tab): signing in with each kind of failure, the plain HTTP question, a download into the library, a file that is not a book, a book the reader already has, the KOReader password, an unknown version, search, the next part of the list, a session that ended, signing out, a server that fails and is tried again; a book's page opened by a tap and closed back to the same query and place, its description in paragraphs or the words for none, a download begun on the page shown on the line, the book read from the page, a page that fails and is tried again over a list still usable. `OpdsScreenTest` — the plain HTTP question for catalogs. `LibraryScreenTest` — the mark on a book the server has, and on one with a place not sent yet; `ServerScreenTest` and `ServerViewModelTest` — how syncing went and how many books wait.
- `:data` `SyncStateTest` — what is a move and what is not, what is still to be sent and which books say so, a review as the server keeps it, the server's status giving way to the reader's unsent one, offers, pages read, another server told everything anew.
- `:sync` `SyncRoundTest` — rounds against the stand-in server: a place sent once, a later place elsewhere offered and not overwritten, a book just opened, the same place, a place as a share, reading without the server, an answer that was lost, a book the server has no more, status and rating both ways, a review both ways and one written here while the server could not be reached, a status the app has no word for, many pages read, a server of another version, a channel that is not ready.
- `:app` `ReaderScreenTest` — going to an offered place and back, an offer as a share, staying, the time on pages with its bounds and the app put away; `LibraryScreenTest` — stars, a review written from the menu and shown on the book's page; `server.SyncRoundTest` — which rounds are done with, tried again, or wait for the reader, and how they are remembered. `server.SyncWorkTest` — a round asked for as the app asks, run by the system's WorkManager with the app's own library and account, and run again when it could not reach the server. `ReaderViewModelTest` — the open book asked about again when the reader comes back to it.
- `:data` `MarksSyncTest` — stamps and keys, every change to marks being news even a removal, marks from the server not sent back and not taken twice, another server told of every mark anew.
- `:sync` `MarksRoundTest` — a quote made, changed and removed on either side, an answer lost on the way, a list too long to be whole, hundreds of quotes in parts, bookmarks both ways, a bookmark the server cannot place, marks made before signing in, the reader's own quotes not taken twice after signing in again, a book the server has no more, colours; stars both ways, one changed here standing over the server's, a star going with a new quote and to the next server, bookmarks' titles given on the server. `PairingTest` — which of the server's marks a mark here is taken for.
- `:contract` `MarksContractTest` — against bookOrbit 3.3.0: a quote made here seen by the web reader at an exact CFI, changed here, sent twice without a second one made, changed and removed in the web reader; a quote made in the web reader taken with a place the engine finds the same words by, and removed here; bookmarks made and removed on either side; a star given here and taken off in the web reader; a bookmark renamed in the web reader.
- `:contract` `SyncContractTest` — against bookOrbit 3.3.0: a place sent and seen by the web reader as a CFI, a later place from the web reader offered with an xpointer the engine finds on the right page and not overwritten, the reader staying, FB2 kept word for word, status and rating both ways including `on_hold`, a review both ways and taken off, pages read in the book's statistics once.
- `:sync` `LikenessTest` — which books are taken for one: case, spaces, punctuation and "ё", an author's name either way round, one author in common, no title, series. `UploadTest` — against the stand-in server: what the server takes, a book sent whole under its title and synced from then on, a book not sent twice, one that got there meanwhile, the same book asked for twice at once, what is not sent at all, each refusal, before the file goes and once it is all there, a session renewed, no devices' channel, a likeness pointed out, gone with the server's book, looked for through the whole list, and left behind by a book that was sent; a file in parts cut off and gone on with by an app started anew, the server having got more than was known, a file the server filed away while the answer was lost, a session the server let go of, one still waiting, one for a file that changed since, a book called off on its way, the name a file goes under.
- `:data` `AccountsTest` — a likeness kept until the book's own file is there, the place books are sent to forgotten with the server, a session kept until the file is there, called off, or the server forgotten.
- `:data` `SyncStateTest` — a status, stars and a review confirmed by the server for a book of the library, not undone by older ones not sent from here, and not taken over a status, stars or review changed here while the server was asked, field by field, stars changed and changed back included; two changes in one millisecond; a status the app has no word for, the copies of a server's book. `MigrationTest` — a status or rating not sent before the rating had a time of its own (schema v15) still goes. `AccountsTest` — tokens and a key renewed or forgotten for an account replaced meanwhile are not kept for the next.
- `:sync` `OrbitAreaTest` — against the stand-in server: shelves together and one at a time, failed, absent, without totals; a book's page with status `on_hold`, stars, note, genres, library, files and collections; a status, stars and a note set for a book only on the server, refused, not saved, gone, and set for a book of the library that has older changes waiting; collections made, renamed with nothing else changed, filled, emptied, deleted with their books staying, another reader's refusing changes, a server without them; the books beside a book, without files and without audiobooks; the catalogue's filters, and one the server refuses; answers held back while the reader changes the status, stars or review in the library, a copy that came meanwhile with or without a change of its own, another reader signed in while a rating or a new collection was on its way, a collection made without the book put into it; a renewal held while another reader signs in, given or refused, and a change of the reader before waiting for it; the channel refusing the reader before after another signed in; stars confirmed for a copy downloaded meanwhile whose status alone changed there.
- `:app` `ServerAreaViewModelTest` (JVM) — Home first, the server's shelves in its order, a shelf failed and tried again alone, an older server asked shelf by shelf with a shelf it has not, a home page that cannot be reached beside a catalogue that can, "See all" only for shelves that are queries; the catalogue narrowed and ordered with its phrase kept, a filter refused, pages that count only what the app reads; collections own and shared, a collection's books within it, a server without the collection's query, the collection opened last being the one shown, a collection made private and named twice, deleted with its books staying, another reader's refusing; a book only on the server put into collections and rated, refused, locked, through the devices' channel, gone; a downloaded book rated over older stars waiting to be sent; books beside a book opened and gone back from, none and none offered; a book on two shelves downloaded once; answers for a reader signed out meanwhile; a session ending on Home; an answer and a refusal for the reader of another server signed in before, and a collection made for them, not taken; a save of one book not ending another's, and a page opened again not asking twice; a collection not made from a book's page, and one made without the book; a book taken out of the collection looked through leaving none after it skipped; parts of a collection with no book the app reads, and a phrase within it; a book beside a book known to be in the library; libraries that cannot be asked for; a collection asked for again after a change that fails, and asked for again whole on the next try.
- `:app` `ServerScreenTest` — also: Home first and each part keeping its query and place, Back through them; a shelf failed and tried again alone; "See all" only for a query; a book beside a book opened over its page and gone back from; a book only on the server marked, rated, noted and put into a new collection, and read back from the server; a refusal told; another reader's collection only looked through, and one deleted with its book staying; a book on two shelves downloaded once and read with no page left over; signing out and in again showing nothing of before; the tabs with large text, and on a tablet's width; a collection of an older server going on by itself past parts of PDFs; a catalogue narrowed by status losing a book given another, and paging on without a skip; the way back to a book's page at the place it was left; a collection not made told in the choice of collections with its name kept; a book beside a book said to be in the library.
- `:contract` `AreaContractTest` — against bookOrbit 3.3.0 and the latest: the shelves, or the routes not there; each filter of the catalogue and newest first, or the server refusing it; a book not on the device given a status, `on_hold` kept, stars as the rounds of syncing read them, a note, all put back afterwards; a collection made private with its icon, filled, its books queried within it, renamed with its sharing and description kept, emptied and deleted with its books staying; the books by an author and of a series, without files, and suggestions that may be empty — or each route not there.
- `:app` `LibraryViewModelTest` — a book sent, sending offered only for what the server takes from the reader, the question before a book like one on the server, where to send asked once, a refusal said at the book, a server that does not answer; several books in the order asked, one waiting its turn called off, one cut off gone on with and another called off, books like ones on the server asked about together and left out, where several go asked once, the name of the file in its folder, a book the server filed away while the app was closed. `LibraryScreenTest` — a book sent from its menu, books picked by a long press and sent together, a book sent in part gone on with from its menu.
- `:contract` `UploadContractTest` — against bookOrbit 3.3.0: what it takes, a book sent, found by its fingerprint and not sent twice, an FB2 with a Russian title, a file that is not what its name says, a place that is not there, and the three things the server does with a book that looks like one it has. A book from an archive, sent unpacked and found by the fingerprint the archive was known by. A book larger than a part cut off after the first and gone on with in the same session; a book called off and let go of by the server. The books are taken off the server afterwards.
- `:contract` `AccountContractTest` — against bookOrbit 3.3.0 in docker: signing in, a refused password, the KOReader password asked for and refused, a channel made for a reader who has none, the list and search, a book in PDF only left out of both, a download read by the engine and found back by its fingerprint, a book the server has not, a missing cover, a book's description as the server's HTML and a book without one answered with none, renewal of a token that ran out and of one that is not taken, signing out ending the session on the server.

```
python3 contract/up.py
./gradlew :contract:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.bookorbitUrl=http://10.0.2.2:3070
```

Checked by hand on the emulator against the same server: signing in over `http://` with the question, the KOReader password, the list, a download kept in the app, the mark in the library, the screen reopened still signed in, the book opened from it; a place read on another device offered when the book was opened, gone to, and come back from; and quotes kept on the server listed in a book opened for the first time.

## Not done

- Server's books in the library's own list: a book that is only on the server is seen on the server's screen, not among the reader's books.
- Hiding, reordering or configuring the home page's shelves, the server's smart scopes, its dashboard widgets and reading goals, and the web pages' own arrangement of the shelves.
- Changes on the server's screen kept to be sent later: they need the server to be reached.
- Choosing `on_hold` or `abandoned` in the library's own menu; the server's page of a book has them.
- Audiobooks, comics and PDF on the server's screen.
- Sending books without being asked, or going on with one cut off without being asked.
- Tying a book to the server's one by its title and authors.
- Signing out at the provider when signing out of the server.
- Download progress, cancelling, resuming.
- A round of syncing on a timer: a place read elsewhere while the open book stays on the screen is learnt when the reader next comes back to it.
- A sign in the reader that something waits to be sent; which of a book's changes a round could not send.
- Styles of a quote other than a highlight (underline, strike-through), which the server has and the app shows as highlights.
- A bookmark's title given here.
- KEPUB files of the server.
- A second server.
- Running the contract tests against the latest server image in CI.
