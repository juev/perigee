package org.evsyukov.perigee.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID

/**
 * Another app sharing files with Perigee, as the tests in the app's process
 * see it: the files are put into [SenderProvider], and [SenderActivity] shares
 * them, both in the test package's own process and under its own user ID.
 */
internal object Sender {
    /** How a file is given; see [SenderProvider]. */
    enum class Mode(val value: String) {
        None(SenderProvider.NONE),
        NoStream(SenderProvider.NO_STREAM),
        Gone(SenderProvider.GONE),
        BreaksOff(SenderProvider.BREAKS_OFF),
        HoldQuery(SenderProvider.HOLD_QUERY),
        HoldCopy(SenderProvider.HOLD_COPY),
        Stall(SenderProvider.STALL),
    }

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private val own get() = instrumentation.context.packageName
    private val provider get() = Uri.parse("content://$own.sender")

    private fun call(method: String, arg: String? = null, extras: Bundle? = null): Bundle =
        checkNotNull(app.contentResolver.call(provider, method, arg, extras))

    /** The file as the other app has it, named [name] there; a null name is a file it does not name. */
    fun stage(file: File, name: String?, mode: Mode = Mode.None): Uri {
        val extras = Bundle().apply {
            putByteArray("bytes", file.readBytes())
            putString("name", name)
            putString("mode", mode.value)
        }
        return Uri.parse(call("stage", extras = extras).getString("uri"))
    }

    /** Shares the files: one with "Share", several together; with the right to read them unless [grant] is false. */
    fun send(type: String, vararg files: Uri, grant: Boolean = true) {
        val intent = Intent().setClassName(own, SenderActivity::class.java.name)
            .putExtra("action", if (files.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE)
            .putExtra("type", type)
            .putParcelableArrayListExtra("uris", arrayListOf(*files))
            .putExtra("grant", grant)
            .putExtra("to", app.packageName)
            // Each share its own: an intent like one before would only bring that one's task back.
            .setIdentifier(UUID.randomUUID().toString())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        app.startActivity(intent)
    }

    /** How many times the file was opened to be read; an open the system refused is not counted. */
    fun opens(file: Uri): Int = call("state", file.toString()).getInt("opens")

    /** Whether the other app is holding the file back, as its mode says. */
    fun held(file: Uri): Boolean = call("state", file.toString()).getBoolean("held")

    fun release(file: Uri) {
        call("release", file.toString())
    }

    /** The other app takes back the right to read the file it granted. */
    fun revoke(file: Uri) {
        call("revoke", file.toString())
    }

    /** Whether the sharing app's activity is still there. */
    fun alive(): Boolean = call("sender").getBoolean("alive")

    /** How many times the sharing app's activities came to the front. */
    fun resumes(): Int = call("sender").getInt("resumes")

    /** The sharing app is closed, its task with it, and its files are forgotten. */
    fun forget() {
        call("finishSender")
        call("forget")
    }
}
