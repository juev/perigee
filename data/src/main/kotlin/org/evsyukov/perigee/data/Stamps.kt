package org.evsyukov.perigee.data

import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The time a bookmark or quote was made, as reading devices tell it to a
 * server: the wall clock of the device, to the second, without a time zone.
 * A mark is known to the server by this time and its place together.
 */
object Stamps {
    private const val PATTERN = "yyyy-MM-dd HH:mm:ss"

    fun of(millis: Long): String = SimpleDateFormat(PATTERN, Locale.US).format(Date(millis))

    /** What the server knows a mark by: the MD5 of its stamp and its place. */
    fun key(stamp: String, place: String): String =
        MessageDigest.getInstance("MD5").digest("$stamp|$place".toByteArray()).joinToString("") { "%02x".format(it) }
}
