package org.evsyukov.perigee.server

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Why reading does not reach the server, as the app can tell while it is open. */
enum class SyncTrouble {
    /** There is no network, so nothing is asked and nothing waits. */
    Offline,

    /** The server cannot be reached; the next round tries again by itself. */
    Unreachable,

    /** Only the reader can unblock it: a sign-in, a device password, another server version. */
    NeedsReader,
}

/**
 * How syncing stands, for the app to say once when it stops working: a round that goes through clears it, and the
 * same trouble again and again is not repeated. Nothing is said for a reader signed in nowhere.
 */
object SyncHealth {
    private val _trouble = MutableStateFlow<SyncTrouble?>(null)
    val trouble: StateFlow<SyncTrouble?> = _trouble.asStateFlow()

    fun healthy() {
        _trouble.value = null
    }

    fun failed(trouble: SyncTrouble) {
        val now = _trouble.value
        // The first round of an outage, or another reason for it, is news; the same one again is not.
        if (now != trouble) _trouble.value = trouble
    }

    /** The reader signed out, or another server was signed in to: what was wrong with the one before is no news. */
    fun forget() {
        _trouble.value = null
    }
}

/** Whether the phone has a network to ask the server over. */
internal object Network {
    fun online(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
