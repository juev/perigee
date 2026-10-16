package org.evsyukov.perigee.library

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.evsyukov.perigee.data.Covers
import org.evsyukov.perigee.data.Folder
import org.evsyukov.perigee.data.Library
import org.evsyukov.perigee.engine.Engine
import org.evsyukov.perigee.server.Server

/**
 * One look through the whole library: the books the app keeps, then every
 * folder, then what is to be done once that is over, as asking the server
 * about the books found.
 */
class LibraryScan(
    private val context: Context,
    private val library: Library,
    private val covers: Covers,
    private val describe: Describe = Engine::describe,
    private val afterScan: suspend () -> Unit = {},
    private val sourceOf: (Folder) -> BookSource,
) {
    private val scanner = LibraryScanner(context, library, covers, describe = describe)

    /** @param onFound told how many books the look has found so far, in all the folders */
    suspend fun run(onFound: suspend (Int) -> Unit = {}) {
        describeKept(context, library, covers, describe)
        unpackKept(context, library, covers)
        var before = 0
        for (folder in library.folders().first()) {
            val result = scanner.scan(folder, sourceOf(folder)) { onFound(before + it) }
            before += result?.found ?: 0
        }
        afterScan()
    }
}

/** Looks through the library, one look at a time, whether the screen that asked is there or not. */
interface Scans {
    /** How many books the look under way has found; null while none is under way. */
    val found: Flow<Int?>

    /**
     * Asks for a look. With [again] the look under way is stopped and a new
     * one begins, as after a folder was added; without, one under way is let finish.
     */
    fun start(again: Boolean)
}

/**
 * Looks run by the system's WorkManager: a look outlives the screen, and one
 * the app was stopped in the middle of is run again. A look stopped half way
 * loses little: files read already are passed by the next time.
 */
internal class ScanWork(context: Context) : Scans {
    private val work = WorkManager.getInstance(context.applicationContext)

    override val found: Flow<Int?> = work.getWorkInfosForUniqueWorkFlow(NAME).map { looks ->
        looks.firstOrNull { !it.state.isFinished }?.progress?.getInt(FOUND, 0)
    }.distinctUntilChanged()

    override fun start(again: Boolean) {
        work.enqueueUniqueWork(
            NAME, if (again) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ScanWorker>().build(),
        )
    }

    companion object {
        const val NAME = "library-scan"
        const val FOUND = "found"
    }
}

/** A look through the library, with the app's own library, covers and folders. */
internal class ScanWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        val scan = LibraryScan(
            app, Library.get(app), Covers.get(app),
            afterScan = { Server.matchNewBooks(app) },
            sourceOf = { folder -> TreeSource(app.contentResolver, Uri.parse(folder.uri)) },
        )
        setProgress(workDataOf(ScanWork.FOUND to 0))
        scan.run { setProgress(workDataOf(ScanWork.FOUND to it)) }
        return Result.success()
    }
}
