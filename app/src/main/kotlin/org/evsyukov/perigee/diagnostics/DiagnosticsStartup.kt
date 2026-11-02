package org.evsyukov.perigee.diagnostics

import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri

/**
 * Starts the diagnostic build's trace with the process, whatever starts the process: the launcher, a
 * share, the system bringing the app back after a crash, or a background sync. The system makes a
 * provider before any activity, service or worker, and makes this one only where the manifest enables
 * it: in the diagnostic build. It serves nothing.
 */
class DiagnosticsStartup : ContentProvider() {
    override fun onCreate(): Boolean {
        val context = context ?: return false
        val sha = try {
            @Suppress("DEPRECATION") // The flags as an object came with API 33.
            context.packageManager.getProviderInfo(ComponentName(context, javaClass), PackageManager.GET_META_DATA)
                .metaData?.get(SOURCE_SHA)?.toString()
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
        Diagnostics.start(context, sha ?: "unknown")
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private companion object {
        /** The commit the build is made from, put into the manifest by the build. */
        const val SOURCE_SHA = "sourceSha"
    }
}
