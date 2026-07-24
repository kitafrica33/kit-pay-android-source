package com.kit.wallet.data.notifications

import java.time.Instant
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
    fun `deadline expires once at the server absolute ring time`() = runTest {
        val expired = mutableListOf<String>()
        val scheduler = RingDeadlineScheduler(
            scope = this,
            now = { Instant.parse("2026-07-24T12:00:00Z") },
            onExpired = expired::add,
        )

        assertTrue(scheduler.schedule("call-1", "2026-07-24T12:00:05Z"))
        advanceTimeBy(4_999)
        runCurrent()
        assertTrue(expired.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("call-1"), expired)

        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(listOf("call-1"), expired)
    }

    @Test
    fun `cancel and replacement suppress obsolete deadline jobs`() = runTest {
        val expired = mutableListOf<String>()
        val scheduler = RingDeadlineScheduler(
            scope = this,
            now = { Instant.parse("2026-07-24T12:00:00Z") },
            onExpired = expired::add,
        )

        assertTrue(scheduler.schedule("replaced", "2026-07-24T12:00:01Z"))
        assertTrue(scheduler.schedule("replaced", "2026-07-24T12:00:03Z"))
        assertTrue(scheduler.schedule("cancelled", "2026-07-24T12:00:01Z"))
        scheduler.cancel("cancelled")
        assertFalse(scheduler.schedule("invalid", "not-an-instant"))

        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(expired.isEmpty())

        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(listOf("replaced"), expired)
    }

    @Test
    fun `already elapsed deadline is dispatched on the next scheduler turn`() = runTest {
        val expired = mutableListOf<String>()
        val scheduler = RingDeadlineScheduler(
            scope = this,
            now = { Instant.parse("2026-07-24T12:00:05Z") },
            onExpired = expired::add,
        )

        assertTrue(scheduler.schedule("expired", "2026-07-24T12:00:00Z"))
        assertTrue(expired.isEmpty())
        runCurrent()

        assertEquals(listOf("expired"), expired)
    }
}
