package org.evsyukov.perigee.sync

import java.io.InputStream
import java.util.zip.ZipInputStream

private const val PROVISION_FILE = "bookorbit.koplugin/bookorbit_provision.lua"
private const val PACKAGE_LIMIT = 4 * 1024 * 1024
private const val EXPANDED_LIMIT = 12 * 1024 * 1024
private const val PROVISION_LIMIT = 4096
private val quotedKey = Regex("\"([0-9a-fA-F]{32})\"")

/** Reads the one file bookOrbit generates for this account. Its embedded URL and plugin code are unused. */
internal fun readProvisionedDeviceKey(input: InputStream, user: String): String? {
    if (user.any { it.isISOControl() }) return null
    val archive = input.readNBytesUpTo(PACKAGE_LIMIT + 1)
    if (archive.size > PACKAGE_LIMIT) return null

    var provision: String? = null
    var bytesLeft = EXPANDED_LIMIT
    val buffer = ByteArray(16 * 1024)
    ZipInputStream(archive.inputStream()).use { zip ->
        while (true) {
            val entry = try {
                zip.nextEntry
            } catch (_: IllegalArgumentException) {
                return null // The ZIP entry name is malformed.
            } ?: break
            if (entry.name == PROVISION_FILE) {
                if (entry.isDirectory || provision != null) return null
                val bytes = zip.readNBytesUpTo(PROVISION_LIMIT + 1)
                if (bytes.size > PROVISION_LIMIT) return null
                provision = bytes.decodeToString()
                bytesLeft -= bytes.size
            } else {
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    bytesLeft -= count
                    if (bytesLeft < 0) return null
                }
            }
            if (bytesLeft < 0) return null
        }
    }
    val text = provision ?: return null
    val quotedUser = "\"" + user.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    if (text.provisionField("username") != quotedUser) return null
    return text.provisionField("userkey")?.let { quotedKey.matchEntire(it)?.groupValues?.get(1) }
}

private fun String.provisionField(name: String): String? = lineSequence()
    .filter { '=' in it && it.substringBefore('=').trim() == name }
    .map { it.substringAfter('=').trim().removeSuffix(",").trimEnd() }
    .singleOrNull()
