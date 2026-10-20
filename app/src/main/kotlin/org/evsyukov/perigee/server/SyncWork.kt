package org.evsyukov.perigee.server

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    const val PERIODIC_NAME = "sync-periodic"
    private const val DELAY_SECONDS = 10L

    /** The least period WorkManager allows for work that repeats. */
    private const val PERIOD_MINUTES = 15L
    private val IN_SIGHT_PERIOD = 1.minutes

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /**
     * Asks for a round of syncing shortly. Asked again before it began, it
     * waits anew: a reader who is turning pages or setting things is let finish.
     * Does nothing for a reader who is signed in nowhere.
     */
    suspend fun soon(context: Context) = ask(context, ExistingWorkPolicy.REPLACE)

    /** Asks for a round of syncing, unless one is already waiting or under way: that one is let be. */
    private suspend fun again(context: Context) = ask(context, ExistingWorkPolicy.KEEP)

    private suspend fun ask(context: Context, policy: ExistingWorkPolicy) {
        val app = context.applicationContext
        if (Accounts.get(app).current() == null) return
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(network)
            .setInitialDelay(DELAY_SECONDS, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(NAME, policy, request)
    }

    /**
     * While the app is in sight, a round a minute, so that what was done on
     * other devices shows up here; the first one shortly. Runs until cancelled.
     */
    suspend fun whileInSight(context: Context): Nothing {
        soon(context)
        while (true) {
            delay(IN_SIGHT_PERIOD)
            again(context)
        }
    }

    /**
     * Keeps a round every [PERIOD_MINUTES] minutes, run by the system while
     * the app is put away, for as long as the reader is signed in; signing out
     * stops it. Runs until cancelled.
     */
    suspend fun followAccount(context: Context) {
        val app = context.applicationContext
        val work = WorkManager.getInstance(app)
        Accounts.get(app).account().map { it != null }.distinctUntilChanged().collect { signedIn ->
            if (!signedIn) {
                work.cancelUniqueWork(PERIODIC_NAME)
                return@collect
            }
            val request = PeriodicWorkRequestBuilder<SyncWorker>(PERIOD_MINUTES, TimeUnit.MINUTES)
                .setConstraints(network)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            work.enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
