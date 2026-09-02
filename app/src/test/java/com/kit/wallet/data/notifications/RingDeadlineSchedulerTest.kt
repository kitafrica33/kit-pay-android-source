package com.kit.wallet.data.notifications

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RingDeadlineSchedulerTest {
    @Test
    fun `expiry clears notification and Telecom before signalling missed to foreground UI`() {
        val actions = mutableListOf<String>()
        var lifecycle: CallLifecycleEvent? = null

        dispatchRingDeadlineExpiry(
            callId = "call-1",
            cancelNotification = { actions += "notification:$it" },
            finishTelecom = { actions += "telecom:$it" },
            publishLifecycle = {
                actions += "lifecycle:${it.callId}"
                lifecycle = it
            },
        )

        assertEquals(
            listOf("notification:call-1", "telecom:call-1", "lifecycle:call-1"),
            actions,
        )
        assertEquals(
            CallLifecycleEvent(
                callId = "call-1",
                kind = CallLifecycleKind.MISSED,
                state = "missed",
                reason = "ring_timeout",
            ),
            lifecycle,
        )
    }

    @Test
    fun `retirement fences replay before cancelling deadline and notification`() {
        val actions = mutableListOf<String>()

        dispatchRingRetirement(
            callId = "call-1",
            retireReplay = { actions += "replay:$it" },
            retireRelay = { actions += "relay:$it" },
            cancelDeadline = { actions += "deadline:$it" },
            cancelNotification = { actions += "notification:$it" },
        )

        assertEquals(
            listOf(
                "replay:call-1",
                "relay:call-1",
                "deadline:call-1",
                "notification:call-1",
            ),
            actions,
        )
    }

    @Test
    fun `deadline expires once on the boot bound monotonic clock`() = runTest {
        val expired = mutableListOf<String>()
        val scheduler = scheduler(expired)

        assertTrue(scheduler.schedule("call-1", lease(deadline = 6_000L)))
        advanceTimeBy(4_999L)
        runCurrent()
        assertTrue(expired.isEmpty())

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(listOf("call-1"), expired)

        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals(listOf("call-1"), expired)
    }

    @Test
    fun `duplicate cannot extend and an earlier duplicate tightens the deadline`() = runTest {
        val expired = mutableListOf<String>()
        val scheduler = scheduler(expired)

        assertTrue(scheduler.schedule("kept", lease(deadline = 3_000L)))
        assertTrue(scheduler.schedule("kept", lease(deadline = 5_000L)))
        assertTrue(scheduler.schedule("tightened", lease(deadline = 5_000L)))
        assertTrue(scheduler.schedule("tightened", lease(deadline = 2_000L)))

        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(listOf("tightened"), expired)

        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(listOf("tightened", "kept"), expired)
    }

    @Test
    fun `cancel and mismatched lease suppress obsolete deadline jobs`() = runTest {
        val expired = mutableListOf<String>()
        val scheduler = scheduler(expired)

        assertTrue(scheduler.schedule("cancelled", lease(deadline = 2_000L)))
        scheduler.cancel("cancelled")
        assertFalse(
            scheduler.schedule(
                "wrong-boot",
                lease(deadline = 2_000L).copy(bootSessionId = BOOT_ID + 1L),
            ),
        )
        assertTrue(scheduler.schedule("unchanged", lease(deadline = 2_000L)))
        assertFalse(
            scheduler.schedule(
                "unchanged",
                lease(deadline = 1_500L).copy(
                    sourceRingExpiresAt = "2026-07-24T12:00:01Z",
                ),
            ),
        )

        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(listOf("unchanged"), expired)
    }

    @Test
    fun `already elapsed deadline is dispatched on the next scheduler turn`() = runTest {
        val expired = mutableListOf<String>()
        val scheduler = RingDeadlineScheduler(
            scope = this,
            nowElapsedRealtimeMillis = { 5_000L + testScheduler.currentTime },
            currentBootSessionId = { BOOT_ID },
            onExpired = expired::add,
        )

        assertTrue(scheduler.schedule("expired", lease(deadline = 2_000L)))
        assertTrue(expired.isEmpty())
        runCurrent()

        assertEquals(listOf("expired"), expired)
    }

    @Test
    fun `lease elapsed during publication remains owned by missed deadline`() = runTest {
        val actions = mutableListOf<String>()
        var nowElapsedRealtimeMillis = 1_000L
        val scheduler = RingDeadlineScheduler(
            scope = this,
            nowElapsedRealtimeMillis = { nowElapsedRealtimeMillis },
            currentBootSessionId = { BOOT_ID },
            onExpired = { actions += "missed:$it" },
        )
        val ringLease = lease(deadline = 2_000L)
        assertTrue(scheduler.schedule("call-1", ringLease))

        // Publication used the last live millisecond, then the final ledger authorization read
        // observed expiry before the scheduled coroutine got its dispatcher turn.
        nowElapsedRealtimeMillis = ringLease.deadlineElapsedRealtimeMillis
        assertFalse(
            reconcilePublishedIncomingCall(
                callId = "call-1",
                authorization = { IncomingCallPublicationAuthorization.Expired },
                retireSurfaces = { retiredCallId, disposition ->
                    actions += "retired:${disposition.name.lowercase()}:$retiredCallId"
                    scheduler.cancel(retiredCallId)
                },
                finishRinging = { retiredCallId, _ ->
                    actions += "answered-elsewhere:$retiredCallId"
                },
            ),
        )
        assertTrue(actions.isEmpty())

        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(listOf("missed:call-1"), actions)
    }

    @Test
    fun `terminal retirement before elapsed publication cannot become missed`() = runTest {
        val actions = mutableListOf<String>()
        val scheduler = RingDeadlineScheduler(
            scope = this,
            nowElapsedRealtimeMillis = { 1_000L },
            currentBootSessionId = { BOOT_ID },
            onExpired = { actions += "missed:$it" },
        )
        val ringLease = lease(deadline = 2_000L)
        assertTrue(scheduler.schedule("call-1", ringLease))

        val terminalAfterDeadline = selectIncomingCallPublicationAuthorization(
            ringExpiresAt = ringLease.sourceRingExpiresAt,
            recorded = null,
            retirement = IncomingCallPublicationAuthorization.Retired(
                IncomingCallRetirementDisposition.REJECTED,
            ),
            nowElapsedRealtimeMillis = ringLease.deadlineElapsedRealtimeMillis,
            currentBootSessionId = BOOT_ID,
        )
        assertFalse(
            reconcilePublishedIncomingCall(
                callId = "call-1",
                authorization = { terminalAfterDeadline },
                retireSurfaces = { retiredCallId, disposition ->
                    actions += "retired:${disposition.name.lowercase()}:$retiredCallId"
                    scheduler.cancel(retiredCallId)
                },
                finishRinging = { retiredCallId, disposition ->
                    actions += "${disposition.name.lowercase()}:$retiredCallId"
                },
            ),
        )

        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(
            listOf("retired:rejected:call-1", "rejected:call-1"),
            actions,
        )
    }

    private fun kotlinx.coroutines.test.TestScope.scheduler(expired: MutableList<String>) =
        RingDeadlineScheduler(
            scope = this,
            nowElapsedRealtimeMillis = { 1_000L + testScheduler.currentTime },
            currentBootSessionId = { BOOT_ID },
            onExpired = expired::add,
        )

    private fun lease(deadline: Long) = CallRingLease(
        sourceRingExpiresAt = "2026-07-24T12:00:05Z",
        bootSessionId = BOOT_ID,
        receivedElapsedRealtimeMillis = 1_000L,
        deadlineElapsedRealtimeMillis = deadline,
    )

    private companion object {
        const val BOOT_ID = 11L
    }
}
