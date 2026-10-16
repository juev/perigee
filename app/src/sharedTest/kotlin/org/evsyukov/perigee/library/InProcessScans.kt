package org.evsyukov.perigee.library

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Looks through the library in the test's own process, as WorkManager would:
 * one at a time, a new one asked [again] stopping the one under way. Tests
 * then reach the folders they make, which WorkManager's worker does not.
 */
internal class InProcessScans(
    private val scan: LibraryScan,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) : Scans {
    private val state = MutableStateFlow<Int?>(null)
    private val requests = Mutex()
    private var look: Job? = null

    override val found: StateFlow<Int?> = state

    /** Counted as under way from the moment it is asked for, as WorkManager counts a look it queued. */
    override fun start(again: Boolean) {
        if (state.value != null && !again) return
        state.value = 0
        scope.launch {
            requests.withLock {
                if (look?.isActive == true && !again) return@withLock
                look?.cancelAndJoin()
                state.value = 0
                look = scope.launch {
                    try {
                        scan.run { state.value = it }
                    } finally {
                        state.value = null
                    }
                }
            }
        }
    }
}
