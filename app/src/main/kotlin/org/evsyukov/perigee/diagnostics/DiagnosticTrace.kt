package org.evsyukov.perigee.diagnostics

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import org.evsyukov.perigee.engine.EngineTrace

/**
 * The diagnostic build's trace: what the app did in its last few runs, kept in files of its own so that
 * the events before a crash are still there once the app is started again, by the system or the reader.
 *
 * A run is a process: [open] starts a new one and drops all but the last [SESSIONS]. A run writes into a
 * part of up to [PART_BYTES]; a full part becomes the previous one and a new part starts, so a run keeps
 * its latest events, at most two parts of them, and all runs together stay within a mebibyte. Each event
 * is one line of printable ASCII of at most [LINE_BYTES], each part opens with the header of its run.
 *
 * Every event is written straight to the file, with no buffer in the process: once [event] returns, the
 * line is the kernel's, and a process that aborts a moment later does not take it along. The writing is
 * not free, and the timing it measures is not quite the timing without it.
 *
 * Nothing here throws. A file that cannot be made or written stops the trace for the rest of the run, and
 * [failure] says why, for the report to tell.
 */
class DiagnosticTrace private constructor(
    private val dir: File,
    /** The number of this run, one more than that of the run before. */
    val session: Long,
    header: String,
    private val clock: () -> Long,
    private val thread: () -> String,
    private val mirror: (String) -> Unit,
) : EngineTrace.Sink {
    private val header = printable(header.replace('\n', ' '), HEADER_BYTES)
    private val lock = Any()
    private var out: FileOutputStream? = null
    private var part = 0
    private var written = 0L
    private var seq = 0L

    /** Why the trace stopped being written; null while it works. The kind of error only: its message may hold a path. */
    @Volatile
    var failure: String? = null
        private set

    override fun event(name: String, fields: String) {
        synchronized(lock) {
            seq++
            val event = if (fields.isEmpty()) name else "$name $fields"
            val line = printable("$seq ${clock()} ${token(thread(), THREAD_CHARS)} $event", LINE_BYTES - 1)
            mirror(line)
            write("$line\n")
        }
    }

    /** The parts of the runs kept, oldest first, as text; each part is read up to [PART_BYTES] from its end. */
    fun snapshot(): String = synchronized(lock) {
        buildString {
            for (n in sessionsIn(dir)) {
                for (file in listOf(previousOf(dir, n), currentOf(dir, n))) {
                    if (!file.isFile) continue
                    try {
                        append(printable(readTail(file), Int.MAX_VALUE, keepLines = true))
                    } catch (e: IOException) {
                        append("# ${file.name} could not be read: ${e.javaClass.simpleName}\n")
                    }
                }
            }
        }
    }

    private fun write(text: String) {
        if (failure != null) return
        val bytes = text.toByteArray(Charsets.US_ASCII)
        try {
            if (out == null || written + bytes.size > PART_BYTES) roll()
            out?.write(bytes)
            written += bytes.size
        } catch (e: IOException) {
            fail(e)
        } catch (e: SecurityException) {
            fail(e)
        }
    }

    /** Starts a part: the one written so far, if any, becomes the previous one, and the one before that goes. */
    private fun roll() {
        out?.close()
        out = null
        val current = currentOf(dir, session)
        if (current.exists()) {
            val previous = previousOf(dir, session)
            previous.delete()
            // A part that cannot be kept goes: the bound holds over what is kept.
            if (!current.renameTo(previous)) current.delete()
        }
        val stream = FileOutputStream(current, false)
        out = stream
        part++
        val head = "# session=$session part=$part $header\n".toByteArray(Charsets.US_ASCII)
        stream.write(head)
        written = head.size.toLong()
    }

    private fun fail(e: Exception) {
        failure = e.javaClass.simpleName
        try {
            out?.close()
        } catch (_: IOException) {
        }
        out = null
    }

    companion object {
        /** The runs kept, this one with them. */
        const val SESSIONS = 4

        /** A part of a run; a run keeps two. With [SESSIONS] runs that is a mebibyte. */
        const val PART_BYTES = 128 * 1024

        /** An event, its line end included. */
        const val LINE_BYTES = 512

        private const val HEADER_BYTES = 1024
        private const val THREAD_CHARS = 48
        private val NAME = Regex("""session-(\d{1,12})(\.prev)?\.log""")

        /**
         * Starts a run in [dir]: the runs before it beyond the last few are deleted.
         * [header] says what process this is; [thread] names the thread an event comes from;
         * [mirror] gets each line as it is written, as for the system log.
         */
        fun open(
            dir: File,
            header: String,
            clock: () -> Long = System::nanoTime,
            thread: () -> String = { Thread.currentThread().let { "${it.id}:${it.name}" } },
            mirror: (String) -> Unit = {},
        ): DiagnosticTrace {
            val before = sessionsIn(dir)
            val trace = DiagnosticTrace(dir, (before.maxOrNull() ?: 0) + 1, header, clock, thread, mirror)
            synchronized(trace.lock) {
                try {
                    if (!dir.isDirectory && !dir.mkdirs()) throw IOException("no directory")
                    for (n in before.dropLast(SESSIONS - 1)) {
                        previousOf(dir, n).delete()
                        currentOf(dir, n).delete()
                    }
                    trace.roll()
                } catch (e: IOException) {
                    trace.fail(e)
                } catch (e: SecurityException) {
                    trace.fail(e)
                }
            }
            return trace
        }

        /** Letters, digits and `._-:` of [text], others as `_`, at most [max] of them: for names that come from outside. */
        fun token(text: String, max: Int): String =
            text.take(max).map { if (it.isLetterOrDigit() && it.code < 128 || it in "._-:") it else '_' }.joinToString("")

        private fun currentOf(dir: File, n: Long) = File(dir, "session-$n.log")

        private fun previousOf(dir: File, n: Long) = File(dir, "session-$n.prev.log")

        /** The numbers of the runs that have files in [dir], in order. */
        private fun sessionsIn(dir: File): List<Long> =
            dir.list().orEmpty().mapNotNull { NAME.matchEntire(it)?.groupValues?.get(1)?.toLongOrNull() }.distinct().sorted()

        private fun readTail(file: File): String = RandomAccessFile(file, "r").use { raf ->
            val size = minOf(raf.length(), PART_BYTES.toLong()).toInt()
            raf.seek(raf.length() - size)
            ByteArray(size).also { raf.readFully(it) }.toString(Charsets.ISO_8859_1)
        }

        /** [text] as printable ASCII, other characters as `?`, cut to [max]; line ends are kept with [keepLines]. */
        private fun printable(text: String, max: Int, keepLines: Boolean = false): String {
            val out = StringBuilder(minOf(text.length, max))
            for (c in text) {
                if (out.length >= max) break
                out.append(if (c in ' '..'~' || keepLines && c == '\n') c else '?')
            }
            return out.toString()
        }
    }
}
