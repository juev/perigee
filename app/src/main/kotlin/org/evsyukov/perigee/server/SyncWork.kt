package org.evsyukov.perigee.server

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.IOException
import java.util.concurrent.TimeUnit
import org.evsyukov.perigee.data.Accounts
import org.evsyukov.perigee.sync.BookOrbit
import org.evsyukov.perigee.sync.DeviceChannelException
import org.evsyukov.perigee.sync.IncompatibleServerException
import org.evsyukov.perigee.sync.SignedOutException

/** What became of a round of syncing in the background. */
internal enum class SyncOutcome {
    Done,

    /** The server could not be reached; the round is to be tried again. */
    Later,

    /** Nothing can be done until the reader does something: signs in again, gives a password, updates. */
    Waiting,
}

/**
 * One round of syncing, with what is wrong told apart by whether trying again
 * can help. A round that went through, and one that could not reach the
 * server, are remembered with the account for the server's screen to say.
 */
internal suspend fun syncRound(orbit: BookOrbit, accounts: Accounts): SyncOutcome {
    if (accounts.current() == null) return SyncOutcome.Done
    return try {
        orbit.sync()
        accounts.roundDone()
        SyncOutcome.Done
    } catch (e: SignedOutException) {
        SyncOutcome.Waiting
    } catch (e: DeviceChannelException) {
        SyncOutcome.Waiting
    } catch (e: IncompatibleServerException) {
        SyncOutcome.Waiting
    } catch (e: IOException) {
        accounts.roundUnreachable()
        SyncOutcome.Later
    }
}

/** Runs a round of syncing when there is a network, and again later when the server could not be reached. */
internal class SyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result =
        when (syncRound(Server.get(applicationContext), Accounts.get(applicationContext))) {
            SyncOutcome.Later -> Result.retry()
            // What waits for the reader is said on the server's screen; the next change here asks again.
            SyncOutcome.Done, SyncOutcome.Waiting -> Result.success()
        }
}

internal object SyncWork {
    const val NAME = "sync"
    private const val DELAY_SECONDS = 10L

    /**
     * Asks for a round of syncing shortly. Asked again before it began, it
     * waits anew: a reader who is turning pages or setting things is let finish.
     * Does nothing for a reader who is signed in nowhere.
     */
    suspend fun soon(context: Context) {
        val app = context.applicationContext
        if (Accounts.get(app).current() == null) return
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(DELAY_SECONDS, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request)
    }
}
