package com.kit.wallet

import com.kit.wallet.data.repository.SettlementPollResult
import com.kit.wallet.data.repository.SettlementReconciliationPoller
import com.kit.wallet.data.repository.isTerminalSettlementStatus
import com.kit.wallet.data.repository.requireExactSettlementOperationId
import com.kit.wallet.data.repository.settlementFailureRetryDelayMillis
import com.kit.wallet.data.repository.settlementPendingPollDelayMillis
import com.kit.wallet.data.session.SessionFence
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettlementReconciliationTest {
    @Test
    fun `polling continues beyond the old sixty second cap until terminal`() = runTest {
        var reads = 0
        val waits = mutableListOf<Long>()
        val poller = SettlementReconciliationPoller(
            scope = backgroundScope,
            currentSession = { OWNER },
            wait = { waits += it },
        )

        val job = poller.ensure(OWNER, "operation") {
            reads++
            if (reads == 46) SettlementPollResult.TERMINAL else SettlementPollResult.PENDING
        }
        job.join()

        assertEquals(46, reads)
        assertEquals(45, waits.size)
        assertEquals(1_500L, waits[39])
        assertEquals(5_000L, waits[40])
    }

    @Test
    fun `transient failures back off and the same operation still reaches terminal`() = runTest {
        var attempts = 0
        val waits = mutableListOf<Long>()
        val poller = SettlementReconciliationPoller(
            scope = backgroundScope,
            currentSession = { OWNER },
            wait = { waits += it },
        )

        poller.ensure(OWNER, "operation") {
            attempts++
            if (attempts <= 2) throw IOException("offline")
            SettlementPollResult.TERMINAL
        }.join()

        assertEquals(3, attempts)
        assertEquals(listOf(1_000L, 2_000L), waits)
    }

    @Test
    fun `a session replacement cancels authority and duplicate starts share one job`() = runTest {
        var owner: SessionFence? = OWNER
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var reads = 0
        val poller = SettlementReconciliationPoller(
            scope = backgroundScope,
            currentSession = { owner },
            wait = { owner = OTHER_OWNER },
        )
        val first = poller.ensure(OWNER, "operation") {
            reads++
            entered.complete(Unit)
            release.await()
            SettlementPollResult.PENDING
        }
        val duplicate = poller.ensure(OWNER, "operation") {
            error("duplicate poll must never start")
        }
        assertSame(first, duplicate)
        entered.await()
        release.complete(Unit)
        first.join()

        assertEquals(1, reads)
        assertFalse(first.isActive)
    }

    @Test
    fun `closed foreground or connectivity gate performs no request`() = runTest {
        var reads = 0
        val poller = SettlementReconciliationPoller(
            scope = backgroundScope,
            currentSession = { OWNER },
            canPoll = { false },
            wait = { error("A closed gate must never wait") },
        )

        poller.ensure(OWNER, "operation") {
            reads++
            SettlementPollResult.PENDING
        }.join()

        assertEquals(0, reads)
    }

    @Test
    fun `cancel all stops a pending wait and permits a clean restart`() = runTest {
        val waiting = CompletableDeferred<Unit>()
        val neverResume = CompletableDeferred<Unit>()
        var reads = 0
        val poller = SettlementReconciliationPoller(
            scope = backgroundScope,
            currentSession = { OWNER },
            wait = {
                waiting.complete(Unit)
                neverResume.await()
            },
        )
        val first = poller.ensure(OWNER, "operation") {
            reads++
            SettlementPollResult.PENDING
        }
        waiting.await()

        poller.cancelAll()
        first.join()
        val restarted = poller.ensure(OWNER, "operation") {
            reads++
            SettlementPollResult.TERMINAL
        }
        restarted.join()

        assertTrue(first.isCancelled)
        assertFalse(restarted.isCancelled)
        assertEquals(2, reads)
    }

    @Test
    fun `restart interrupts a stale delay and performs an immediate authoritative read`() = runTest {
        val waiting = CompletableDeferred<Unit>()
        val neverResume = CompletableDeferred<Unit>()
        var staleReads = 0
        var freshReads = 0
        val poller = SettlementReconciliationPoller(
            scope = backgroundScope,
            currentSession = { OWNER },
            wait = {
                waiting.complete(Unit)
                neverResume.await()
            },
        )
        val stale = poller.ensure(OWNER, "operation") {
            staleReads++
            SettlementPollResult.PENDING
        }
        waiting.await()

        val fresh = poller.restart(OWNER, "operation") {
            freshReads++
            SettlementPollResult.TERMINAL
        }
        fresh.join()
        stale.join()

        assertTrue(stale.isCancelled)
        assertEquals(1, staleReads)
        assertEquals(1, freshReads)
    }

    @Test
    fun `exact operation identity rejects a mismatched response`() {
        requireExactSettlementOperationId("operation-a", "operation-a")
        assertThrows(IllegalStateException::class.java) {
            requireExactSettlementOperationId("operation-a", "operation-b")
        }
    }

    @Test
    fun `terminal vocabulary and schedules are bounded`() {
        assertTrue("succeeded".isTerminalSettlementStatus())
        assertTrue("COMPLETED".isTerminalSettlementStatus())
        assertTrue("reversed".isTerminalSettlementStatus())
        assertFalse("processing".isTerminalSettlementStatus())
        assertEquals(10_000L, settlementPendingPollDelayMillis(1_000))
        assertEquals(10_000L, settlementFailureRetryDelayMillis(100))
    }

    private companion object {
        val OWNER = SessionFence("session-a", "scope-a", "user-a")
        val OTHER_OWNER = SessionFence("session-b", "scope-b", "user-b")
    }
}
