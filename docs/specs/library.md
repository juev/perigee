# Library: the reader's books without a server

Requirement R4 of `plan.md`. The library works with no network and no account. What the reading screen keeps per book is described in `reader.md`.

## Where books come from

- **Folders.** The reader picks folders through the system's folder dialog (Storage Access Framework); the app keeps access to them across restarts, to read and to write: writing is what lets the reader delete a book there. Folders added before the app asked to write are read only until the reader picks them again. Every folder is looked through together with the folders inside it.
- **Downloads.** Books downloaded from catalogs go into the folder the reader chose for them, which is one of the folders above, or into the app's storage; see `opds.md`.
- **Single files.** "Open a book" picks one file from anywhere. It is copied into the app's storage and listed like the rest.
- **Shared files.** A file manager or any other app shares one book or several with the app through its "Share" menu. They are copied into the app's storage the same way; see "Books shared from other apps".

A file is a book when its name ends in one of `Library.FORMATS`: `epub`, `fb2`, `fb2.zip`, `fb3`, `mobi`, `prc`, `pdb`, `txt`, `rtf`, `doc`, `docx`, `odt`, `html`, `htm`, `chm`. Other files are passed by.

## Books shared from other apps

The app takes `ACTION_SEND` (one file) and `ACTION_SEND_MULTIPLE` (several) in `ShareActivity`, an activity for that alone; the reader's screen, `MainActivity`, takes no share itself. Nothing else is needed: no folder, no access to the folder the file lies in, no permission to the device's storage, no account.

**Which shares offer the app.** The share menu lists the app for these types: `application/epub+zip`, `application/x-fictionbook+xml`, `application/x-fictionbook`, `application/x-zip-compressed-fb2`, `application/x-mobipocket-ebook`, `application/rtf`, `application/msword`, the Word and OpenDocument text types, the two CHM types, `application/zip` and `application/octet-stream`. Many file managers know no type for an FB2 or for a format of an older reader and give the binary type; an FB2 in an archive comes as `application/zip`. So the app is also offered for any zip or unnamed binary file, and takes only the books among them. Several files of different types come under a type they share, such as `application/*`, which the system matches against the same list. No text type is listed: plain text and links shared from a browser or a messenger are not books, and the app is not offered for them; TXT and HTML books come in through "Open a book" or a folder. PDF is not a format the engine reads, and the app is not offered for it.

**One file** that becomes a book is opened in the reader at once, whatever screen was shown. **Several files** are taken in one after another and are not opened: a list shows each book that joined the library with a "Read" button, and each file that did not with the reason. The list is shown over whatever screen there is, whatever shelf, sorting or search the library has; closing it leaves the library as it was. One file that did not become a book is shown the same way. A file that fails does not stop the rest.

**What is checked.** Only `content://` addresses are read, through the system's `ContentResolver` and the right to read that comes with the share. A file is a book when the name its app gives it ends as one of `Library.FORMATS` does (with no name given, the address's last part is taken), and the engine reads it (`Engine.describe`). The reasons a file is not taken, in English and Russian:

- the file could not be opened — no file came with the share, its app gave no stream, or the address is not `content://`;
- the app it came from does not let the app read it — the right to read is gone;
- not a book the app reads — its name ends otherwise;
- the book could not be read — the engine cannot read the file;
- the file could not be copied — reading broke off half way, or the app's storage could not be written.

**How it is kept.** The file shared is only read; it is never moved, changed or deleted. It is copied to a name of its own in `files/books/` (`.import-…`) and read there; only a whole copy that the engine read is kept, as `files/books/<fingerprint>.<format>`, the way a book of a folder is copied for reading. An FB2 in an archive is kept as the FB2 inside, known by its fingerprint ("A book in an archive"). Two different books that came under one name are two files. A book the library has already keeps its row and everything the reader left in it — the place, bookmarks, quotes, status, collections — and is read from the copy the app has; that copy is not written again, so a book that is open stays as it is. "Open a book" takes a picked file the same way (`BookImport`).

When anything fails, the copy is removed before the reason is shown, and the library is as it was; so is a cover read from the file, unless it is the cover of a book the library has. A copy left by an app that died while copying is removed with the other files no book is read from, an hour after it was last written (see "Opening a book"); the hour also keeps a copy being made from being removed by a look through the folders.

**Where the files are copied.** `ShareActivity` is shown over the app that shared the files, in that app's task, with a sign that books are being added and a Cancel button, while it copies them; the system gives the right to read a shared file to the activity it is shared with, and the right lasts while that activity does. The copying is held by the activity's model: it goes on while the activity is made anew, and begins again if the app died meanwhile and the system brings the activity back. Cancel, or Back, ends the activity, calls the copying off and removes the part copied; that is not a failure to show. The copying waits for an app slow to give its file only as long as it is not called off. An app that hangs when it is asked for the file's name, or for the file, keeps the import waiting until it answers: the app cannot be made to. Files are copied off the main thread, one after another in the order they came.

**How the reader sees them.** Once the files are taken in, what became of them is handed to the reader's screen and `ShareActivity` ends:

- **The app is running.** Its task is brought to the front as it is (`ActivityManager.AppTask.moveToFront`): no activity in it is closed and no second reader's screen is made. When the reader's screen is in front, the shared book opens there, on whatever screen the app showed; a book being read is closed as when another is opened from the library, and keeps its place. When something is over it — the system's dialog for picking a file, or the browser's page for signing in to the server — that stays in front and is left to the reader; the share waits and is shown once the reader's screen is in front again.
- **The app is not running.** It is started on the shared book, not on the book the reader left, in a task of its own, with the intent the launcher starts it with: the launcher brings that task back rather than making another.

What is handed over is kept in memory only (`SharedBooks`): the books are in the library as soon as they are taken in, and if the app is closed by the system before the reader's screen shows them, what is lost is that one is opened, or that the list is shown. A book shared, or picked, while the app is starting and looks up the book the reader left is opened, and the book the reader left is not opened over it.

**Back and tasks.** The app that shared the files stays in its own task, as it was. In Perigee, Back does what it always does: from a book to the library, from the library out of the app; out of the app, the reader is back where they were before, as a rule in the app that shared the files. `ShareActivity` is not listed among the recent apps; Perigee's own task is, once.

`SharedFiles.kt` reads the intent: the files are taken from `EXTRA_STREAM`, or from the clip when an app puts them only there; the system copies the stream into the clip as well, and a file found in both is taken once. An extra of another kind, or one that cannot be unparcelled, counts as no file.

"Open with" (`ACTION_VIEW`) is not taken.

## Looking through a folder

`LibraryScanner.scan` brings the library in line with one folder. A look goes through every folder, after the books the app keeps (`LibraryScan`).

Looks run in the background, through the system's WorkManager (`ScanWork`, unique work `library-scan`), so that a large folder does not keep the screen waiting and leaving the screen does not stop the look. One look runs at a time:

- When the library screen is first shown, a look begins unless one is under way already.
- When a folder is added or removed, the folder for downloads is chosen, or the reader asks to "Look for new books", the look under way is stopped and a new one begins.

While a look is under way, the screen shows a bar and how many books it has found so far; books appear in the list as they are found. A look stopped half way, by the system or by a new one, loses little: files read already are passed by the next time. A look that was under way when a folder was removed does not put that folder's books back. Once a look is over, the server is asked about the books found.

- A file whose address, size and modification time are as they were is not read again.
- A new or changed file is read where it lies, through its descriptor: `Engine.describe` returns title, authors, language, series with its number, and the cover picture, without laying the book out and without touching the engine's cache. Nothing is copied.
- A book is identified by the fingerprint of its file (`FileHash`), as before. The same file found at another address is the same book and keeps its position, bookmarks, quotes, favourite mark, status and collections.
- An address holds one book: when the file there changes, the book that was there leaves the library and the new one takes its place.
- A file that is gone takes its book out of the library, unless the reader opened the book: then the app has its own copy and the book stays. What the reader left in a book is kept in either case and returns with the book.
- A folder that cannot be listed (a card taken out, access withdrawn) changes nothing.
- A book that does not name itself is titled by its file name.
- Covers are kept reduced to 480 px in height, as JPEG, in the app's storage (`Covers`).

The engine is native code reading arbitrary files. The scanner notes which file it is reading; a note still there at the next scan means the app died on that file. A file this happened to twice is passed by until it changes, so that one bad file cannot take the app down at every start.

## Opening a book

The engine opens files by path. A book that lies in a folder is copied into the app's storage (`files/books/<fingerprint>.<format>`) the first time it is opened, and read from the copy afterwards. A file in `files/books/` that no book is read from is removed at the end of every look through the folders, unless it was written within the last hour: it may be a copy or a download on its way to the library. Opening a book marks it as being read, unless the reader marked it as read already. A book whose file cannot be read says so and stays in the list.

## Deleting a book

"Delete from the device" in a book's menu deletes the book from the device; the server is not touched, and a book it has can be downloaded again. The file goes: from its folder, or from the app's storage, with the app's copy of it and its cover. A book whose file stayed in its folder would be found again by the next look, so the file itself is deleted. The book's rows go with it: the place, bookmarks, quotes, status, rating, review, collections and pages read. Nothing of a deleted book is kept on the device.

The reader is asked first, and told what is lost:

- **The server does not have the book.** Everything the reader left in it goes with it. To keep it, the reader cancels, sends the book to the server the usual way, and deletes it then.
- **The server has the book.** A round of syncing runs first; deleting waits for it. When the server took everything, the reader is told that it stays there. When the server could not be reached and something made here is not there yet — a place, a mark, a status, a review, pages read — the reader is told that it will be lost.

A folder the app may only read is to be picked again: the reader is asked to, and the book is deleted once the folder is picked with the right to write. A file the system would not delete keeps its book, and the reader is told so.

## A book in an archive

An FB2 is often kept zipped, as `book.fb2.zip`. The book is the FB2, the archive is how it lies on the disk:

- It is known by the fingerprint of the FB2 inside, and listed as `FB2`. The archive and the same FB2 beside it are one book, and the server, which takes no archives, knows the book by the same fingerprint.
- The file in the reader's folder is not touched. What the app keeps for reading — on first opening, or for a book downloaded into its own storage or picked by hand — is the FB2 unpacked; that is also what is sent to a server. A downloaded archive put into the reader's folder for downloads lies there as it came.
- An archive that holds anything but one FB2 — several books, a book and a picture — stays what it was: read through the engine as an archive, known by the archive's own fingerprint, and not offered to be sent.
- Books that an earlier version knew by their archives are known by the book inside from the next look through the folders. What the reader left in them goes along: the place, status, stars, favourites, bookmarks, quotes, collections and pages read. A book that was already read under the new fingerprint keeps its own place.

`Fb2Archive` in `:data` reads the archive; `Library.reidentify` moves what the reader left.

## Shelves

One row of shelves above the list:

- **Reading**, **To read**, **Have read** — the status of a book. Opening sets "Reading"; the reader sets any of the three from the book's menu, and choosing the current one again clears it.
- **Favourites** — the heart on a book's row.
- **All books**.
- **Authors**, **Series**, **Collections** — groups with the number of books in each; picking one shows its books. A book with several authors is under each of them. Books naming no author are under "Author not named". Collections are made by the reader, from the book's menu or on the shelf itself, and can be renamed and removed; removing a collection leaves its books in the library.

Search narrows what the shelf shows: books by title, author or series, groups by name.

Sorting applies to every list of books: last read first (then last added), by title, by author, last added first. Text is ordered by the rules of the reader's language. A series is always listed by number within the series. The shelf and the sorting chosen are remembered (`LibraryPreferences`).

A book's row shows the cover (or the first letter of the title), title, authors, series with the number, how much is read, and the format.

## Storage

Room database `reading.db`, version 2 (`data/schemas`): `books` gained the document address, folder, file size and time, format, language, series, cover flag, added time, favourite mark and status; new tables `folders`, `collections`, `collection_books`. Version 1 databases are migrated automatically, and `MigrationTest` checks that nothing a reader left in a book is lost.

## Tests

- `:engine` `DescribeTest` — what `Engine.describe` reads from FB2 and EPUB, covers, no traces in the engine's cache, files that are not books.
- `:data` `LibraryTest`, `MigrationTest` — folders, found and lost books, statuses, collections, covers, migration from version 1.
- `:app` `LibraryScanTest` (JVM) — a look through several folders counts the books it finds, asks the server once, puts nothing back into a removed folder, and removes the copies no book is read from.
- `:app` `ScanWorkTest` — a look is run through the system's WorkManager.
- `:app` `LibraryScannerTest` — scanning a directory of generated books: nested folders, unchanged, changed and removed files, an unreachable folder, a file the app died on.
- `:app` `LibraryViewModelTest` (JVM) — deleting a book: in the app's storage, in a folder, a folder only read, a book synced first, one with changes the server could not be told of.
- `:app` `ListingTest` (JVM) — shelves, groups, search and sorting.
- `:app` `LibraryScreenTest` — the screen: empty library, shelves, favourites, statuses, search, authors, collections, opening a book from a folder.
- `:app` `BookImportTest` (JVM) — a picked or shared file: kept under its fingerprint, the file shared only read, two books of one name, a book shared again with its place and marks, an FB2 in an archive, a file of another kind, a name that climbs out of the folder, an address that is not `content://`, a damaged file, no stream, a file gone, no right to read, reading broken off, storage that cannot be written, a copy that cannot be put in place, copying called off; nothing left behind each time, and the cover of a book the library has kept.
- `:app` `SharedFilesTest` (JVM) — which files an intent shares: the stream, the clip, both, several, none, an extra of another kind.
- `:app` `SharedBooksTest` — sharing through the system from another app: the test package shares its own files (`SenderActivity`, `SenderProvider`, in the test package only), in a process and under a user ID of its own, and Perigee reads them only with the right the share grants. The share menu's types; one book with the app closed, opened rather than the book left, in a task the launcher brings back, not taken again when the screen is made anew; one book while another is read, reaching the same screen, the first keeping its place, Back leading to the library and then to the app that shared it; a share while a stand-in for the file dialog is over the reader's screen, which stays and is shown the book only after it closes; a share while the book left is being looked up; a book shared again; one file that is no book; a share without the right to read; the right taken back half way; the share made anew while copying; a share called off while the other app is slow; several books with the Reading shelf shown, an EPUB, an FB2 in an archive, two of one name, read from the list; every kind of failure in one share; a copy that cannot be put in place, with its cover.
- `:app` `LibraryScannerTest` also checks that a picked file is copied under its fingerprint and read for its cover, authors, series and description.

The tests reach folders as plain directories. The system's folder dialog and its documents were checked by hand on the emulator only. No file manager is installed on the test emulator: the share menu is checked by asking the system which activities take each type.

## List, grid and a book's page

The books of a shelf are shown as a list or as a grid of covers; a button in the bar switches between the two and the choice is kept (`LibraryPrefs.grid`). A row of the list has the cover, title, authors, series, how far the book is read, its format and where it stands with the server. A cell of the grid has the cover, a bar for how far the book is read, the title and the authors; its menu button is on the cover, and the favourite mark is in the menu. A book without a cover is drawn as a plate: its first letter in the list, its title in the grid.

A long press on a book picks it, and while any are picked a tap picks or lets go of a book rather than opening it; the bar says how many are picked and offers to send them to the server together (`sync-bookorbit.md`, "Sending books to the server"). Back, or the cross in the bar, lets go of them all.

"About the book" in a book's menu opens the book's own page: the cover, title, authors, series, format, language, how far it is read, a button to read it, and what the book says it is about.

What a book says it is about is read with the rest of what it says of itself (`Engine.describe`) and kept as plain text, a line a paragraph: the annotation of an FB2, `dc:description` of an EPUB with its markup taken out. A book recorded before this was read has no description yet (`Book.description` is null); its file is read once more the next time the library is looked through, in a folder or in the app's own storage alike. A book opened through "Open a book" is read the same way as one of a folder.
