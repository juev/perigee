package org.evsyukov.perigee.contract

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue

/** The server under test, shared by every contract test in the run. */
internal object ContractServer {
    // The server allows five logins a minute, fewer than there are test cases.
    private var connected: BookOrbitServer? = null

    /** Skips the calling test when no server URL was passed to the run. */
    @Synchronized
    fun connect(): BookOrbitServer {
        val url = InstrumentationRegistry.getArguments().getString("bookorbitUrl")
        assumeTrue("pass -Pandroid.testInstrumentationRunnerArguments.bookorbitUrl=…", url != null)
        return connected ?: BookOrbitServer(url!!).also {
            it.login()
            it.ensureKoreaderCredentials()
            connected = it
        }
    }
}
