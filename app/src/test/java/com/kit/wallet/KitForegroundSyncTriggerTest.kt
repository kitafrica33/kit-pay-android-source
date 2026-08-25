package com.kit.wallet

import com.kit.wallet.data.messaging.SecureMessagingSyncEngine
import com.kit.wallet.data.realtime.KitForegroundSyncTrigger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The conflator, and the reason it exists rather than the engine's own mutex being
 * enough.
 *
 * `SecureMessagingSyncEngine.synchronize` is serialised by a mutex that **does not
 * collapse**: hand it twenty nudges from a twenty-message burst and it performs
 * twenty full syncs back to back, which is strictly worse than the two-second poll
 * the socket replaced. The contract pinned here is that any number of requests
 * arriving during one in-flight sync collapse into at most one further run, and
 * that the further run observes everything that arrived during the first.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KitForegroundSyncTriggerTest {

    @Test
    fun `twenty nudges during one in-flight sync cost exactly two syncs`() = runTest {
        val engine = GatedSyncEngine()
        val trigger = KitForegroundSyncTrigger(engine, StandardTestDispatcher(testScheduler))

        trigger.request()
        advanceUntilIdle()
        assertEquals("The first request should have started a sync", 1, engine.started)

        // The whole burst lands while the first sync is still running.
        repeat(20) { trigger.request() }
        advanceUntilIdle()
        assertEquals("A running sync must absorb the burst, not queue behind it", 1, engine.started)

        engine.completeCurrent()
        advanceUntilIdle()

        assertEquals(2, engine.started)
        assertEquals(1, engine.finished)

        engine.completeCurrent()
        advanceUntilIdle()

        assertEquals("The burst collapsed into exactly one further run", 2, engine.started)
        assertEquals(2, engine.finished)
    }

    @Test
    fun `a request that lands as a sync is finishing still produces one`() = runTest {
        // The last moment a request can arrive and still find the gate held. It gets
        // no lock and returns, trusting the running loop to pick it up, so the loop
        // has to actually re-read `rerun` rather than assume it is done.
        //
        // Note what this cannot reach: the window *between* the inner loop's final
        // read and `unlock()` contains no suspension point, so the re-check after the
        // unlock is unreachable from a single-threaded scheduler. That guard is for a
        // genuinely concurrent caller and is not pinned here.
        val engine = GatedSyncEngine()
        val trigger = KitForegroundSyncTrigger(engine, StandardTestDispatcher(testScheduler))

        trigger.request()
        advanceUntilIdle()
        assertEquals(1, engine.started)

        engine.completeCurrentAnd { trigger.request() }
        advanceUntilIdle()

        assertEquals("The late request must still produce a sync", 2, engine.started)
    }

    @Test
    fun `sequential requests each get their own sync`() = runTest {
        val engine = GatedSyncEngine()
        val trigger = KitForegroundSyncTrigger(engine, StandardTestDispatcher(testScheduler))

        repeat(3) {
            trigger.request()
            advanceUntilIdle()
            engine.completeCurrent()
            advanceUntilIdle()
        }

        assertEquals(3, engine.started)
        assertEquals(3, engine.finished)
    }

    @Test
    fun `a failing sync neither escapes nor wedges the gate`() = runTest {
        // A failed sync is not an error here: the cursor cannot regress, and the next
        // nudge recovers whatever it missed. What must not happen is the exception
        // escaping the trigger's scope, or the gate staying locked afterwards.
        val engine = GatedSyncEngine()
        val trigger = KitForegroundSyncTrigger(engine, StandardTestDispatcher(testScheduler))

        trigger.request()
        advanceUntilIdle()
        engine.failCurrent(IllegalStateException("sync failed"))
        advanceUntilIdle()

        trigger.request()
        advanceUntilIdle()
        assertEquals("The gate must be usable after a failure", 2, engine.started)

        engine.completeCurrent()
        advanceUntilIdle()
        assertEquals(2, engine.finished)
    }

    /** A sync engine that starts on demand and finishes only when the test says so. */
    private class GatedSyncEngine : SecureMessagingSyncEngine {
        override val isReady: Boolean = true

        var started: Int = 0
            private set

        var finished: Int = 0
            private set

        private var current: CompletableDeferred<Unit>? = null

        override suspend fun synchronize() {
            started++
            val gate = CompletableDeferred<Unit>()
            current = gate
            try {
                gate.await()
            } finally {
                finished++
            }
        }

        fun completeCurrent() {
            val gate = requireNotNull(current) { "No sync is in flight" }
            current = null
            gate.complete(Unit)
        }

        fun failCurrent(error: Throwable) {
            val gate = requireNotNull(current) { "No sync is in flight" }
            current = null
            gate.completeExceptionally(error)
        }

        /** Runs [action] and only then releases the sync, to hit the unlock race. */
        fun completeCurrentAnd(action: () -> Unit) {
            val gate = requireNotNull(current) { "No sync is in flight" }
            current = null
            action()
            gate.complete(Unit)
        }
    }
}
