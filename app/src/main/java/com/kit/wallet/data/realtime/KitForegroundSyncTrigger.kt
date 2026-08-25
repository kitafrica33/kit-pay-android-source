package com.kit.wallet.data.realtime

import com.kit.wallet.data.messaging.SecureMessagingSyncEngine
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * Turns any number of nudges into the smallest number of syncs that is still correct.
 *
 * This is a conflator, not a queue, and the distinction is the entire point. The
 * engine's own `synchronizationMutex` **serialises but does not collapse**, so
 * handing it twenty nudges from a twenty-message burst would produce twenty full
 * syncs, one after another — strictly worse than the two-second poll this replaces.
 * Nor can we route through `SecureMessagingSyncScheduler`, whose coalescer is
 * reachable only from the WorkManager path.
 *
 * The contract: while a sync is running, **any** number of further requests
 * collapse into **at most one** additional run, which observes everything that
 * arrived during the first. A burst of twenty messages therefore costs two syncs.
 *
 * [request] must stay non-suspending and non-blocking: it is called from the
 * socket's read callback, and a read thread that waits on a sync is a read thread
 * that is not answering pings.
 */
@Singleton
class KitForegroundSyncTrigger internal constructor(
    private val engine: SecureMessagingSyncEngine,
    dispatcher: CoroutineDispatcher,
) {
    /**
     * The injected form. Dagger does not see Kotlin default arguments, so a
     * defaulted `dispatcher` on the primary constructor would have it demand an
     * unqualified `CoroutineDispatcher` binding this graph deliberately does not
     * have. The dispatcher is therefore a test seam and nothing else.
     */
    @Inject
    internal constructor(engine: SecureMessagingSyncEngine) : this(engine, Dispatchers.IO)

    private val gate = Mutex()
    private val rerun = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    fun request() {
        // Set first, then try the gate. The reverse order would lose a request that
        // arrived between an in-flight run reading `rerun` and releasing the lock.
        rerun.set(true)
        if (!gate.tryLock()) return

        scope.launch {
            while (true) {
                try {
                    // `getAndSet(false)` is what collapses the burst: everything that
                    // arrived during the previous iteration is satisfied by this one.
                    while (rerun.getAndSet(false)) {
                        // A failed sync is not an error here. The cursor cannot
                        // regress, and the next nudge, FCM wake, foreground
                        // transition or idle timer recovers whatever this run missed.
                        runCatching { engine.synchronize() }
                    }
                } finally {
                    gate.unlock()
                }

                // A request that arrived between the loop reading `rerun` for the
                // last time and the unlock above found the gate held and returned
                // assuming this run would see it — but this run was already past
                // looking. Re-checking after the unlock is what makes that
                // assumption true; without it a nudge is silently dropped and the
                // message waits for the next one.
                if (!rerun.get() || !gate.tryLock()) return@launch
            }
        }
    }
}
