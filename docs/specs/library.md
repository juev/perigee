# Library: the reader's books without a server

Requirement R4 of `plan.md`. The library works with no network and no account. What the reading screen keeps per book is described in `reader.md`.

## Where books come from

- **Folders.** The reader picks folders through the system's folder dialog (Storage Access Framework); the app keeps read access to them across restarts. Every folder is looked through together with the folders inside it.
- **Downloads.** Books downloaded from catalogs go into the folder the reader chose for them, which is one of the folders above, or into the app's storage; see `opds.md`.
- **Single files.** "Open a book" picks one file from anywhere. It is copied into the app's storage and listed like the rest.

A file is a book when its name ends in one of `Library.FORMATS`: `epub`, `fb2`, `fb2.zip`, `fb3`, `mobi`, `prc`, `pdb`, `txt`, `rtf`, `doc`, `docx`, `odt`, `html`, `htm`, `chm`. Other files are passed by.

## Looking through a folder

`LibraryScanner.scan` brings the library in line with one folder. It runs for every folder when the library screen is first shown, when a folder is added, and on "Look for new books".

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

The engine opens files by path. A book that lies in a folder is copied into the app's storage (`files/books/<fingerprint>.<format>`) the first time it is opened, and read from the copy afterwards. Opening a book marks it as being read, unless the reader marked it as read already. A book whose file cannot be read says so and stays in the list.

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
- `:app` `LibraryScannerTest` — scanning a directory of generated books: nested folders, unchanged, changed and removed files, an unreachable folder, a file the app died on.
- `:app` `ListingTest` (JVM) — shelves, groups, search and sorting.
- `:app` `LibraryScreenTest` — the screen: empty library, shelves, favourites, statuses, search, authors, collections, opening a book from a folder.

The tests reach folders as plain directories. The system's folder dialog and its documents were checked by hand on the emulator only.

## List, grid and a book's page

The books of a shelf are shown as a list or as a grid of covers; a button in the bar switches between the two and the choice is kept (`LibraryPrefs.grid`). A row of the list has the cover, title, authors, series, how far the book is read, its format and where it stands with the server. A cell of the grid has the cover, a bar for how far the book is read, the title and the authors; its menu button is on the cover, and the favourite mark is in the menu. A book without a cover is drawn as a plate: its first letter in the list, its title in the grid.

"About the book" in a book's menu opens the book's own page: the cover, title, authors, series, format, language, how far it is read, a button to read it, and what the book says it is about.

What a book says it is about is read with the rest of what it says of itself (`Engine.describe`) and kept as plain text, a line a paragraph: the annotation of an FB2, `dc:description` of an EPUB with its markup taken out. A book recorded before this was read has no description yet (`Book.description` is null); its file is read once more the next time the library is looked through, in a folder or in the app's own storage alike. A book opened through "Open a book" is read the same way as one of a folder.

## Not done

- Scanning runs while the library screen is alive; there is no background job and no progress count.
- Copies of opened books are never removed from the app's storage.
- Removing a book from the library by hand.
