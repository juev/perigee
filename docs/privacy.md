# Privacy policy

Perigee is an e-book reader for Android. This page says what the app does with your data. It applies to the builds from F-Droid and from Google Play alike.

## In short

The app has no account with its developer, no advertising and no analytics. It sends nothing to the developer or to any third party. It talks only to the servers you add yourself: OPDS catalogues and a bookOrbit server.

## What stays on the device

- The list of your books, their covers, where you stopped, bookmarks, highlights with notes, collections, status, rating, time spent on pages, and the reading settings.
- Copies of the books you opened or downloaded, in the app's own storage, and the books you asked to be kept in a folder of yours.
- The addresses of your catalogues and of your server with the user names. Passwords and session keys are stored encrypted with a key held by the Android Keystore.

The app reads only the folders and files you pick in the system dialogue. It is left out of Android's backup. Removing the app removes all of this, except the files in your own folders.

## What goes to an OPDS catalogue you added

Requests for the pages of the catalogue, your searches in it and the downloads, with the user name and password if you gave them. They go to the server of that catalogue and to the addresses its pages lead to; the password goes only to the server of the catalogue itself.

## What goes to a bookOrbit server you signed in to

- Your user name and password when you sign in, then the session keys the server gave.
- A name for this device: "Perigee" and the model of the device.
- A fingerprint (a partial MD5) of each book file in your library, so that the server can say which of them it has. The server keeps the fingerprints it does not know.
- For the books the server has: where you stopped, bookmarks, highlights and notes, status, rating, and the time spent on pages.
- A book file, when you choose "Send to the server" for it.

All of this goes to that server and nowhere else. What the server does with it is up to whoever runs it; Perigee is not affiliated with bookOrbit.

## Plain HTTP

If the address of a catalogue or of the server starts with `http://`, everything above, the password included, crosses the network unencrypted. The app says so and asks before sending a password that way. Use `https://` wherever you can.

## Other apps

When you share a piece of text, look it up in a dictionary or search the web for it, the text is handed to the app you pick. That app's policy applies from there.

## Changes and contact

Changes to this page are in the history of the repository: https://github.com/juev/perigee. Questions go to its issues: https://github.com/juev/perigee/issues.
