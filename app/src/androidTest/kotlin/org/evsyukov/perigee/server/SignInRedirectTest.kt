package org.evsyukov.perigee.server

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** The address a provider sends the reader back to reaches the app through the system, as from a browser. */
@RunWith(AndroidJUnit4::class)
class SignInRedirectTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun forget() {
        Server.redirects.value = null
    }

    @Test
    fun providersAnswerIsHandedToTheServersScreen() {
        val answer = "bookorbit://oauth2-callback?code=the-code&state=the-state"
        // Sent as a browser sends it: to whatever app takes the address, not to a named activity.
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(answer)).addCategory(Intent.CATEGORY_BROWSABLE)
            .setPackage(context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        val deadline = System.currentTimeMillis() + 10_000
        while (Server.redirects.value == null && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals(answer, Server.redirects.value)
    }
}
