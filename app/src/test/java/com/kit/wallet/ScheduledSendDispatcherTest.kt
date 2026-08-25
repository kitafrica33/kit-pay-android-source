package com.kit.wallet

import com.kit.wallet.data.messaging.ScheduledSend
import com.kit.wallet.data.messaging.ScheduledSendDispatchOutcome
import com.kit.wallet.data.messaging.ScheduledSendDispatcher
import com.kit.wallet.data.messaging.ScheduledSendGateway
import com.kit.wallet.data.messaging.ScheduledSendKind
import com.kit.wallet.data.messaging.ScheduledSendState
import com.kit.wallet.data.messaging.ScheduledSendStore
import com.kit.wallet.data.session.SessionFence
import com.kit.wallet.data.session.SessionInvalidatedException
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScheduledSendDispatcherTest {
    private val clock = MutableClock(NOW)
    private val disk = TestSecureMessagingStateStore()
    private val sessions = MutableTestSessionStore(testSession(OWNER_A))
    private val store = ScheduledSendStore(disk, sessions)
    private val gateway = RecordingGateway { sessions.current()?.fence() }
    private val dispatcher = ScheduledSendDispatcher(store, gateway, clock)

    @Test fun `a due message is handed over once and then forgotten`() = runTest {
        store.put(textSend())

        assertEquals(ScheduledSendDispatchOutcome.COMMITTED, dispatcher.dispatchDue())

        assertEquals(listOf(CONVERSATION_ID to "see you at six"), gateway.texts)
        // Deleted, because the outbox owns delivery from the instant it committed.
        assertEquals(emptyList<ScheduledSend>(), store.items.value)

        assertEquals(ScheduledSendDispatchOutcome.IDLE, dispatcher.dispatchDue())
        assertEquals(1, gateway.texts.size)
    }

    @Test fun `a send that fails after committing is never repeated`() = runTest {
        store.put(textSend())
        gateway.failAfterCommit = true

        assertEquals(ScheduledSendDispatchOutcome.COMMITTED, dispatcher.dispatchDue())

        assertEquals(emptyList<ScheduledSend>(), store.items.value)
        assertEquals(ScheduledSendDispatchOutcome.IDLE, dispatcher.dispatchDue())
        assertEquals(1, gateway.texts.size)
    }

    @Test fun `a send that fails before committing comes back with one more attempt`() = runTest {
        store.put(textSend())
        gateway.commits = false
        gateway.failAfterCommit = true

        assertEquals(ScheduledSendDispatchOutcome.RETRY, dispatcher.dispatchDue())

        val queued = store.find(ID)
        assertEquals(ScheduledSendState.WAITING, queued?.state)
        assertEquals(1, queued?.attempts)
        assertEquals(0L, queued?.claimedAtEpochMillis)
        assertEquals(NOW_MILLIS, queued?.lastAttemptAtEpochMillis)
    }

    @Test fun `an attempt that committed nothing backs off before it is retried`() = runTest {
        store.put(textSend())
        gateway.commits = false
        gateway.failAfterCommit = true
        dispatcher.dispatchDue()

        // Straight away, and a second short of the backoff, there is nothing to do.
        assertEquals(ScheduledSendDispatchOutcome.IDLE, dispatcher.dispatchDue())
        clock.advanceMillis(59_000L)
        assertEquals(ScheduledSendDispatchOutcome.IDLE, dispatcher.dispatchDue())
        assertEquals(1, gateway.texts.size)

        clock.advanceMillis(1_000L)
        gateway.commits = true
        gateway.failAfterCommit = false
        assertEquals(ScheduledSendDispatchOutcome.COMMITTED, dispatcher.dispatchDue())
        assertEquals(2, gateway.texts.size)
    }

    @Test fun `nothing is sent before its time`() = runTest {
        store.put(textSend(at = NOW_MILLIS + 60_000L))

        assertEquals(ScheduledSendDispatchOutcome.IDLE, dispatcher.dispatchDue())
        assertEquals(emptyList<Pair<String, String>>(), gateway.texts)

        clock.advanceMillis(60_000L)
        assertEquals(ScheduledSendDispatchOutcome.COMMITTED, dispatcher.dispatchDue())
    }

    @Test fun `a device that cannot send yet records the attempt and sends nothing`() = runTest {
        store.put(textSend())
        gateway.ready = false

        assertEquals(ScheduledSendDispatchOutcome.NOT_READY, dispatcher.dispatchDue())

        assertEquals(emptyList<Pair<String, String>>(), gateway.texts)
        assertEquals(1, store.find(ID)?.attempts)
        assertEquals(ScheduledSendState.WAITING, store.find(ID)?.state)
    }

    @Test fun `a scheduled request is created under the item's own identity`() = runTest {
        store.put(requestSend())

        assertEquals(ScheduledSendDispatchOutcome.COMMITTED, dispatcher.dispatchDue())

        assertEquals(listOf(RecordedRequest(CONVERSATION_ID, ID, 250_000, "rent")), gateway.requests)
        assertEquals(emptyList<ScheduledSend>(), store.items.value)
    }

    @Test fun `an abandoned claim becomes a question for the user rather than a resend`() = runTest {
        val stale = textSend().copy(
            state = ScheduledSendState.SENDING,
            claimedAtEpochMillis = NOW_MILLIS - ScheduledSend.STALE_CLAIM_MILLIS,
        )
        store.put(stale)

        assertEquals(ScheduledSendDispatchOutcome.IDLE, dispatcher.dispatchDue())

        assertEquals(ScheduledSendState.UNCONFIRMED, store.find(ID)?.state)
        assertEquals(0L, store.find(ID)?.claimedAtEpochMillis)
        assertEquals(emptyList<Pair<String, String>>(), gateway.texts)

        // And it stays there: no amount of waking sends it, because nobody can tell whether it went.
        clock.advanceMillis(30L * 24 * 60 * 60 * 1_000L)
        assertEquals(ScheduledSendDispatchOutcome.IDLE, dispatcher.dispatchDue())
        assertEquals(emptyList<Pair<String, String>>(), gateway.texts)
    }

    @Test fun `send now releases an unconfirmed item only when a person asks`() = runTest {
        store.put(
            textSend().copy(
                state = ScheduledSendState.UNCONFIRMED,
                attempts = 4,
                lastAttemptAtEpochMillis = NOW_MILLIS - 1_000L,
            ),
        )

        dispatcher.sendNow(ID)

        assertEquals(listOf(CONVERSATION_ID to "see you at six"), gateway.texts)
        assertEquals(emptyList<ScheduledSend>(), store.items.value)
    }

    @Test fun `send now ignores a backoff that has not expired`() = runTest {
        store.put(textSend().copy(attempts = 4, lastAttemptAtEpochMillis = NOW_MILLIS))

        dispatcher.sendNow(ID)

        assertEquals(1, gateway.texts.size)
    }

    @Test fun `send now on something that is gone does nothing`() = runTest {
        store.load()

        dispatcher.sendNow(ID)

        assertEquals(emptyList<Pair<String, String>>(), gateway.texts)
    }

    @Test fun `send now leaves a live claim alone`() = runTest {
        store.put(
            textSend().copy(
                state = ScheduledSendState.SENDING,
                claimedAtEpochMillis = NOW_MILLIS,
            ),
        )

        dispatcher.sendNow(ID)

        assertEquals(emptyList<Pair<String, String>>(), gateway.texts)
        assertEquals(ScheduledSendState.SENDING, store.find(ID)?.state)
    }

    @Test fun `a second dispatch cannot send an item the first one has claimed`() = runTest {
        val original = textSend()
        store.put(original)
        val held = CompletableDeferred<Unit>()
        gateway.holdBeforeCommit = held
        val other = ScheduledSendDispatcher(store, gateway, clock)

        val first = launch { dispatcher.dispatchDue() }
        runCurrent()
        assertEquals(1, gateway.texts.size)
        assertEquals(ScheduledSendState.SENDING, store.find(ID)?.state)
        assertTrue(
            !store.removeIfUnchangedForOwner(
                checkNotNull(sessions.current()).fence(),
                original,
            ),
        )
        assertEquals(ScheduledSendState.SENDING, store.find(ID)?.state)

        // The second dispatcher sees a claimed item, which is not its to send.
        assertEquals(ScheduledSendDispatchOutcome.IDLE, other.dispatchDue())
        assertEquals(1, gateway.texts.size)

        held.complete(Unit)
        first.join()
        assertEquals(1, gateway.texts.size)
        assertNull(store.find(ID))
    }

    @Test fun `send now waits for an in-process stale claim and never resends it`() = runTest {
        store.put(textSend())
        val held = CompletableDeferred<Unit>()
        gateway.holdBeforeCommit = held

        val dispatch = launch { dispatcher.dispatchDue() }
        runCurrent()
        assertEquals(1, gateway.texts.size)
        assertEquals(ScheduledSendState.SENDING, store.find(ID)?.state)

        // Even after the durable claim is old enough to look abandoned, this process still owns
        // it. The singleton dispatch mutex keeps Send Now behind that live gateway handoff.
        clock.advanceMillis(ScheduledSend.STALE_CLAIM_MILLIS)
        val sendNow = launch { dispatcher.sendNow(ID) }
        runCurrent()
        assertTrue(sendNow.isActive)
        assertEquals(1, gateway.texts.size)

        held.complete(Unit)
        dispatch.join()
        sendNow.join()

        assertEquals(1, gateway.texts.size)
        assertNull(store.find(ID))
    }

    @Test fun `account switch before dispatch cannot send the previous owner's item`() = runTest {
        store.put(textSend())
        disk.deleteNamespace("scheduled-send")
        sessions.save(testSession(OWNER_B))

        assertEquals(ScheduledSendDispatchOutcome.IDLE, dispatcher.dispatchDue())

        assertEquals(emptyList<Pair<String, String>>(), gateway.texts)
        assertEquals(emptyList<ScheduledSend>(), store.items.value)
    }

    @Test fun `account switch after claim aborts before gateway side effects`() = runTest {
        store.put(textSend())
        gateway.beforeSend = {
            disk.deleteNamespace("scheduled-send")
            sessions.save(testSession(OWNER_B))
        }

        val failure = runCatching { dispatcher.dispatchDue() }.exceptionOrNull()

        assertTrue(failure is SessionInvalidatedException)
        assertEquals(emptyList<Pair<String, String>>(), gateway.texts)
        assertEquals(emptyList<ScheduledSend>(), store.items.value)
    }

    private data class RecordedRequest(
        val conversationId: String,
        val idempotencyKey: String,
        val amountMinor: Long,
        val note: String?,
    )

    private class RecordingGateway(
        private val currentOwner: () -> SessionFence?,
    ) : ScheduledSendGateway {
        var ready: Boolean = true
        var commits: Boolean = true
        var failAfterCommit: Boolean = false
        var holdBeforeCommit: CompletableDeferred<Unit>? = null
        var beforeSend: suspend () -> Unit = {}
        val texts = mutableListOf<Pair<String, String>>()
        val requests = mutableListOf<RecordedRequest>()

        override fun readyFor(owner: SessionFence): Boolean =
            ready && currentOwner() == owner

        override suspend fun sendText(
            owner: SessionFence,
            conversationId: String,
            text: String,
            onDurablyCommitted: () -> Unit,
        ) {
            beforeSend()
            if (currentOwner() != owner) throw SessionInvalidatedException()
            texts += conversationId to text
            holdBeforeCommit?.await()
            if (commits) onDurablyCommitted()
            if (failAfterCommit) throw IOException("network")
        }

        override suspend fun sendPaymentRequest(
            owner: SessionFence,
            conversationId: String,
            idempotencyKey: String,
            amountMinor: Long,
            note: String?,
            onDurablyCommitted: () -> Unit,
        ) {
            beforeSend()
            if (currentOwner() != owner) throw SessionInvalidatedException()
            requests += RecordedRequest(conversationId, idempotencyKey, amountMinor, note)
            holdBeforeCommit?.await()
            if (commits) onDurablyCommitted()
            if (failAfterCommit) throw IOException("network")
        }
    }

    private class MutableClock(
        private var current: Instant,
        private val zone: ZoneId = ZoneOffset.UTC,
    ) : Clock() {
        override fun getZone(): ZoneId = zone

        override fun withZone(zone: ZoneId): Clock = MutableClock(current, zone)

        override fun instant(): Instant = current

        fun advanceMillis(millis: Long) {
            current = current.plusMillis(millis)
        }
    }

    private fun textSend(at: Long = NOW_MILLIS) = ScheduledSend(
        id = ID,
        conversationId = CONVERSATION_ID,
        kind = ScheduledSendKind.TEXT,
        scheduledAtEpochMillis = at,
        createdAtEpochMillis = NOW_MILLIS - 60_000L,
        text = "see you at six",
    )

    private fun requestSend(at: Long = NOW_MILLIS) = ScheduledSend(
        id = ID,
        conversationId = CONVERSATION_ID,
        kind = ScheduledSendKind.PAYMENT_REQUEST,
        scheduledAtEpochMillis = at,
        createdAtEpochMillis = NOW_MILLIS - 60_000L,
        amountMinor = 250_000,
        note = "rent",
    )

    private companion object {
        const val OWNER_A = "0199aaaa-bbbb-4ccc-8ddd-eeeeffff00a1"
        const val OWNER_B = "0199aaaa-bbbb-4ccc-8ddd-eeeeffff00b2"
        const val ID = "0199aaaa-bbbb-4ccc-8ddd-eeeeffff0001"
        const val CONVERSATION_ID = "0199aaaa-bbbb-4ccc-8ddd-eeeeffff0009"
        val NOW: Instant = Instant.parse("2026-08-25T09:00:00Z")
        val NOW_MILLIS: Long = NOW.toEpochMilli()
    }
}
