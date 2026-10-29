package org.evsyukov.perigee.server

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import org.evsyukov.perigee.MainActivity

/**
 * Takes the address a provider sends the reader back to after signing in,
 * hands it to the server's screen and brings the app back, which closes the
 * browser's tab the provider's page was shown in.
 */
class SignInRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.data?.let { Server.redirects.value = it.toString() }
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
