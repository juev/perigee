package org.evsyukov.perigee.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.evsyukov.perigee.engine.EngineTrace

/**
 * The trace of the diagnostic build (`-PperigeeDiagnostics=true`), started with its process by
 * [DiagnosticsStartup]; every other build never starts it, writes nothing and shows nothing of it.
 *
 * Events carry numbers, modes and names the app makes; the only names from outside are the font family,
 * the device and the version, each cut to [token]. Never the text, title or path of a book, an xpointer,
 * settings as a whole, an account or an address. The trace stays on the phone until the reader saves it.
 */
object Diagnostics {
    /** The system log's tag for the events, apart from the app's other lines. */
    private const val TAG = "PerigeeDiag"
    private const val DIR = "diagnostics"
    private const val EXITS = 8

    @Volatile
    private var trace: DiagnosticTrace? = null

    val enabled: Boolean get() = trace != null

    /** Why the trace stopped being written in this run, if it did. */
    val failure: String? get() = trace?.failure

    /** Records an event; [fields] are made only when the trace is on. */
    inline fun event(name: String, fields: () -> String = { "" }) {
        if (enabled) record(name, fields())
    }

    @PublishedApi
    internal fun record(name: String, fields: String) {
        trace?.event(name, fields)
    }

    /** [text] cut to a short name of letters, digits and `._-:`, as for a font family. */
    fun token(text: String, max: Int = 40): String = DiagnosticTrace.token(text, max)

    /** Starts the trace of this run, once; never throws. [sourceSha] is the commit the build is made from. */
    @Synchronized
    fun start(context: Context, sourceSha: String) {
        if (trace != null) return
        try {
            val started = DiagnosticTrace.open(
                File(context.noBackupFilesDir, DIR),
                header(context, sourceSha),
                thread = { "${Process.myTid()}:${Thread.currentThread().name}" },
                mirror = { Log.i(TAG, it) },
            )
            trace = started
            EngineTrace.sink = started
            event("process.start") { "failure=${started.failure ?: "none"}" }
        } catch (e: RuntimeException) {
            Log.w(TAG, "the trace did not start: ${e.javaClass.simpleName}")
        }
    }

    /** What a run is: the process, the build and the device. */
    private fun header(context: Context, sourceSha: String): String {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return listOf(
            "pid=${Process.myPid()}",
            "wall_ms=${System.currentTimeMillis()}",
            "uptime_ms=${SystemClock.elapsedRealtime()}",
            "process_start_ms=${Process.getStartElapsedRealtime()}",
            "package=${token(context.packageName, 80)}",
            "version=${token(info.versionName.orEmpty())}",
            "code=${info.longVersionCode}",
            "sha=${token(sourceSha)}",
            "maker=${token(Build.MANUFACTURER)}",
            "model=${token(Build.MODEL)}",
            "device=${token(Build.DEVICE)}",
            "android=${token(Build.VERSION.RELEASE)}",
            "sdk=${Build.VERSION.SDK_INT}",
            "os_build=${token(Build.ID)}",
            "abi=${token(Build.SUPPORTED_ABIS.firstOrNull().orEmpty())}",
        ).joinToString(" ")
    }

    /** The name a saved report is offered under. */
    fun fileName(): String = "perigee-diagnostics-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())}.txt"

    /**
     * The report: this run, the ends of the app's last processes as the system recorded them, and the
     * trace of the runs kept. Says so when the trace is off or stopped, rather than leaving it out.
     */
    fun report(context: Context): String = buildString {
        appendLine("Perigee diagnostics report")
        val running = trace
        appendLine("now wall_ms=${System.currentTimeMillis()} pid=${Process.myPid()}")
        when {
            running == null -> appendLine("trace: off in this build")
            running.failure != null -> appendLine("trace: stopped in this run (${running.failure}); what was written before is below")
            else -> appendLine("trace: on, session=${running.session}")
        }
        appendLine()
        appendLine("process exits, latest first:")
        try {
            val manager = context.getSystemService(ActivityManager::class.java)
            for (exit in manager.getHistoricalProcessExitReasons(context.packageName, 0, EXITS)) {
                appendLine(
                    "exit pid=${exit.pid} reason=${exit.reason} status=${exit.status} importance=${exit.importance} " +
                        "wall_ms=${exit.timestamp} process=${token(exit.processName, 80)}",
                )
            }
        } catch (e: RuntimeException) {
            appendLine("exits not available: ${e.javaClass.simpleName}")
        }
        appendLine()
        appendLine("trace:")
        val text = running?.snapshot().orEmpty()
        append(text.ifEmpty { "(no trace files)\n" })
    }

    /** Writes the report to [uri], off the main thread; false when it cannot be written there. */
    suspend fun export(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        event("export.begin")
        val written = try {
            val stream = context.contentResolver.openOutputStream(uri, "wt")
            stream?.use { it.write(report(context).toByteArray()) } != null
        } catch (e: IOException) {
            false
        } catch (e: RuntimeException) {
            // A provider that refuses, or is gone, says so with an exception of its own.
            false
        }
        event("export.end") { "ok=$written" }
        written
    }
}
