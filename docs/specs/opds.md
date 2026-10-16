# Catalogs: downloading books over OPDS

Requirement R5 of `plan.md`. A catalog is a library on the network that lists books as OPDS pages: 1.x (Atom) or 2.0 (JSON). The reader adds catalogs by address, looks through them, searches, and downloads books into the library described in `library.md`.

## Module `:opds`

- `OpdsParser` reads a catalog page into `OpdsFeed`: title, entries, the address of the page that follows (`rel="next"`), and the search the page offers. It also reads an OpenSearch description for its search template. Relative links are resolved against the address the page came from. Only http and https addresses are kept: pictures written into the page (`data:`) and other schemes are dropped.
- An `OpdsEntry` may lead somewhere (`feed`: the first Atom link that is not `self`, `related`, `search`, `start`, `up` or `replies`), offer files (`downloads`: links with rel `http://opds-spec.org/acquisition` or `…/open-access`; buying, borrowing and samples are left out), or both. `OpdsLink.format` tells which of the app's formats a media type is, or that it is none.
- A page that is neither an Atom feed nor an OPDS 2.0 page, or that ends in the middle, is refused (`NotACatalogException`) rather than shown in part.

### OPDS 2.0

A page is read as JSON when it starts with a brace, whatever type the server gives it. `OpdsJson` turns it into the same `OpdsFeed`:

- `navigation` links become sections; `publications` become books; a group with a page of its own (a `self` link and a title) becomes one section that leads to it, and a group without one is shown in place.
- A publication offers the files linked with the same acquisition relations as in Atom; buying, borrowing, subscribing and samples are left out. Its authors may be a name, an object with a name, or a list of either. A title or description given in several languages is read in the first one. The cover is the smallest of its pictures.
- A page that is a single publication is read as a page with that one book.
- Search is the `search` link with `"templated": true`: a URI template such as `…/search.json{?query}`. `query` is filled in, other variables are left out.
- `OpdsClient` fetches pages, searches, downloads files and fetches cover pictures with `HttpURLConnection`. It sends HTTP Basic credentials when a user name is given.

### Passwords

- The user name and password go to the catalog's own server only — same scheme, host and port as the catalog's address. Covers and files on other servers are fetched without them.
- Redirects are followed by the client itself, at most six, under the same rule: a redirect to another server does not carry the password along.
- In the app a password is stored sealed with a key kept by the Android Keystore (`Secrets`, AES-GCM). When it cannot be opened any more, as after the app's files were moved to another device, the catalog says so and has to be added again.
- Plain HTTP is allowed for the whole app, because a catalog is often a server at home reached by a local address. Over plain HTTP the password travels unprotected: before a catalog whose address starts with `http://` is added with a user name or password, the app says so and asks to confirm. A catalog open to everyone is added without the question.

### Search

A page may give a search address template outright (`rel="search"`, an Atom type, `{searchTerms}` in the address) or point to an OpenSearch description that holds one; the first is used when both are there. Other placeholders of a template are left empty.

### Downloads

A file is downloaded into a folder of its own in the cache and read by the engine (`readBook`: title, authors, series, cover, fingerprint). A file the engine cannot read is deleted and reported as not a book. A name the server suggests cannot lead out of the download folder.

- **Progress.** The line of the book shows how much has come: a bar and "3.1 MB of 12 MB" when the server gives the size, a running bar and the bytes so far when it does not. The screen is told at most a hundred times a download, or once a megabyte when the size is not known.
- **Cancel.** A download under way can be cancelled: the connection is closed at once, what came is deleted, and the book can be downloaded again. Nothing it was doing comes back after the cancel.
- **Size.** A file larger than 200 MB (`OpdsClient.BOOK_LIMIT`) is refused: before anything is read when the server gives the size, as soon as it grows past it when it does not. The number has room above the largest EPUB with pictures among some 120 popular and illustrated books of Project Gutenberg, looked at on 2026-10-16: 85 MB, most under 3 MB. The book joins the library unopened, titled by what the file says, not by what the catalog listed.

Where the file is kept is the reader's choice, asked for at the first download and changed under "Folders with books":

- **A folder of the reader's choice.** The book is written there as an ordinary file, under the name the server gives or the catalog's title, and registered as found in that folder. The folder is one the library looks through like any other, so the book stays the reader's: seen by other apps, left in place when the app is removed, gone from the library when the file is deleted. A book that lies in some folder of the library already is not written again. When the folder cannot be written to, the book is kept in the app rather than lost.
- **The app's own storage.** The file is moved to `files/books/<fingerprint>.<format>` and registered with `Library.keep`. A book that was in the library already keeps the reader's place and marks.

## Screen

"Catalogs (OPDS)" in the library's menu.

- The list of catalogs, with adding and removing. Adding asks for the address, and a user name and password if the catalog wants them; an address without a scheme is taken as https. The catalog is fetched before it is added, and titled as it titles itself. What went wrong is told apart: not an address, refused credentials, no catalog there, no answer.
- Inside a catalog: sections lead to pages, Back returns page by page and then to the list. Reaching the end of a page fetches the part that follows. Search is offered when the page shown offers it.
- A book shows its cover, title, authors and description, and one "Download" per format the app reads — the catalog's first file of each. After a download: "In the library" and "Read", which opens the book; closing it returns to the catalog.

## Tests

- `:opds` `OpdsParserTest` — pages recorded on 2026-10-13 from bookOrbit 3.3.0 and from Project Gutenberg (`opds/src/androidTest/assets/feeds`); OPDS 2.0 pages recorded on 2026-10-16 from Feedbooks (cut down to a few books) and from the OPDS test catalog `test.opds.io`; plus hand-written pages for paid links, broken links and things that are not catalogs.
- `:opds` `OpdsClientTest` — against servers the test runs itself: credentials, refusals, redirects within and across servers, search by template and by description, download names, oversized pictures and books, the progress of a download, and a cancel that stops a download waiting on a quiet server at once.
- `:data` `CatalogsTest` — sealed passwords, adding twice, removal, a downloaded file joining the library.
- `:app` `OpdsViewModelTest` (JVM) — the screen's model against a small catalog served by the test: adding with each kind of failure, browsing, the next part of a page, search, a broken section, and downloads: the question at the first one, into the app, into a folder, a book that is in a folder already, a folder that cannot be written to, progress and a cancel, a file too large. The folder is a plain directory there.
- `:app` `OpdsScreenTest` (device) — the screen itself: the question before a password goes over plain HTTP, a book downloaded and opened, the progress bar and its cancel, the message about a file too large.
- `:contract` `OpdsContractTest` — the client against the catalog of a real bookOrbit 3.3.0 in docker: browsing, pages, search, download checked by the engine, refused passwords. The server has OPDS off by default and separate OPDS users; the test switches it on (`PATCH app-settings/opds_enabled`) and makes a user (`POST opds-users`).

Checked by hand on the emulator against the same server: adding the catalog, browsing, downloading an EPUB, reading it, finding it in the library; and downloading an FB2 into a folder picked through the system's dialog, where it appeared as a file and in the library.

## Not done

- A message when the downloads folder could not be written to and the book went into the app instead.
- Facets, and files a catalog offers only after a purchase or a loan.
- Editing a catalog; it is removed and added again.
- Catalogs suggested out of the box.
