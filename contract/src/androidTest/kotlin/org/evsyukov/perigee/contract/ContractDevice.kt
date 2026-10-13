package org.evsyukov.perigee.contract

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.evsyukov.perigee.data.Accounts
import org.evsyukov.perigee.data.Library
import org.evsyukov.perigee.sync.BookOrbit
import org.evsyukov.perigee.sync.DeviceChannel
import org.evsyukov.perigee.sync.TooManyAttemptsException
import org.junit.Assert.assertEquals

/** A reader's device as the app is on it: a library of its own and the account of the contract server. */
internal class ContractDevice(context: Context) {
    val library = Library.inMemory(context)
    val accounts = Accounts.of(library)
    val orbit = BookOrbit(accounts, library, "0.0.1")

    /** The server takes five sign-ins a minute, and a run of the tests may ask for more. */
    suspend fun signIn(password: String = BookOrbitServer.PASSWORD): DeviceChannel {
        val url = checkNotNull(InstrumentationRegistry.getArguments().getString("bookorbitUrl"))
        repeat(8) {
            try {
                return orbit.signIn(url, BookOrbitServer.USERNAME, password)
            } catch (e: TooManyAttemptsException) {
                delay(10_000)
            }
        }
        error("the server kept asking to wait")
    }

    companion object {
        private var shared: ContractDevice? = null

        /** Signed in once for all the tests that need no sign-in of their own: each counts against the server's limit. */
        @Synchronized
        fun signedIn(context: Context): ContractDevice = shared ?: ContractDevice(context).also { device ->
            runBlocking {
                // The contract reader has a user name in the devices' channel already, as one who uses KOReader would.
                assertEquals(DeviceChannel.PasswordNeeded, device.signIn())
                assertEquals(DeviceChannel.Ready, device.orbit.devicePassword(BookOrbitServer.KO_PASSWORD))
            }
            shared = device
        }
    }
}
