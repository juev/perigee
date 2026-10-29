package org.evsyukov.perigee.server

import android.content.Context
import android.os.Build
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import org.evsyukov.perigee.data.Accounts
import org.evsyukov.perigee.data.Library
import org.evsyukov.perigee.sync.BookOrbit

/** The server the reader signed in to, one for the whole app. */
internal object Server {
    @Volatile
    private var orbit: BookOrbit? = null

    /** The address a provider sent the reader back to after signing in, until the server's screen takes it. */
    val redirects = MutableStateFlow<String?>(null)

    fun get(context: Context): BookOrbit = orbit ?: synchronized(this) {
        orbit ?: run {
            val app = context.applicationContext
            val version = app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "0"
            // Named with the model: a reader with the app on two devices is told which one a place was read on.
            BookOrbit(Accounts.get(app), Library.get(app), version, "Perigee, ${Build.MODEL}").also { orbit = it }
        }
    }

    /**
     * Asks the server about the books it was never asked about, as after new
     * ones were found in the folders. Says nothing when there is no server,
     * or it cannot be asked now: the books are asked about the next time.
     */
    suspend fun matchNewBooks(context: Context) {
        if (Accounts.get(context).current() == null) return
        try {
            get(context).matchLibrary(all = false)
        } catch (e: IOException) {
            // Not signed in any more, no network, a channel that is not ready: none of it is the library's to report.
        }
    }

    /**
     * A book was opened: asks the server whether the reader got further in it
     * somewhere else, and for the quotes and bookmarks made there. A later
     * place waits in the library as an offer. Says
     * nothing when the server cannot be asked; the next round of syncing asks.
     */
    suspend fun bookOpened(context: Context, bookId: String) {
        // Without an account there is no server to make.
        if (Accounts.get(context).current() == null) return
        bookOpened(Accounts.get(context), get(context), bookId)
    }

    suspend fun bookOpened(accounts: Accounts, orbit: BookOrbit, bookId: String) {
        if (accounts.current() == null) return
        try {
            orbit.syncPlace(bookId)
            // Quotes and bookmarks made elsewhere appear in the open book as they arrive.
            orbit.syncMarks(bookId)
        } catch (e: IOException) {
            // The reader came to read; whatever is wrong with the server is told on its own screen.
        }
    }
}
