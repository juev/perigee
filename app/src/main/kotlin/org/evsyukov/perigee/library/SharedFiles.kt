package org.evsyukov.perigee.library

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

/**
 * The files another app shares with this one: one through "Share", several
 * when a selection is shared together. Null when the intent shares nothing,
 * as one that starts the app; empty when it is a share that came without a
 * file. The files are taken from the stream extra, or from the clip when an
 * app puts them only there; the system copies the extra into the clip, so a
 * file found in both is taken once. An extra that cannot be read counts as
 * no file.
 */
internal fun sharedFiles(intent: Intent): List<Uri>? {
    val action = intent.action
    if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return null
    val streams = try {
        if (action == Intent.ACTION_SEND) {
            listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        } else {
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty().filterNotNull()
        }
    } catch (e: RuntimeException) {
        // An extra of another kind, or one that does not unparcel.
        emptyList()
    }
    if (streams.isNotEmpty()) return streams.distinct()
    return try {
        val clip = intent.clipData ?: return emptyList()
        (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }.distinct()
    } catch (e: RuntimeException) {
        emptyList()
    }
}
