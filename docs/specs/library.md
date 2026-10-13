# Library: the reader's books without a server

Requirement R4 of `plan.md`. The library works with no network and no account. What the reading screen keeps per book is described in `reader.md`.

## Where books come from

- **Folders.** The reader picks folders through the system's folder dialog (Storage Access Framework); the app keeps read access to them across restarts. Every folder is looked through together with the folders inside it.
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

## Not done

- Scanning runs while the library screen is alive; there is no background job and no progress count.
- Copies of opened books are never removed from the app's storage.
- A grid of covers, and a book's own page with its description.
- Removing a book from the library by hand.
