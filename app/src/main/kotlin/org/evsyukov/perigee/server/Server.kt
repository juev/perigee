package org.evsyukov.perigee.server

import android.content.Context
import java.io.IOException
import org.evsyukov.perigee.data.Accounts
import org.evsyukov.perigee.data.Library
import org.evsyukov.perigee.sync.BookOrbit

/** The server the reader signed in to, one for the whole app. */
internal object Server {
    @Volatile
    private var orbit: BookOrbit? = null

    fun get(context: Context): BookOrbit = orbit ?: synchronized(this) {
        orbit ?: run {
            val app = context.applicationContext
            val version = app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "0"
            BookOrbit(Accounts.get(app), Library.get(app), version).also { orbit = it }
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
}
