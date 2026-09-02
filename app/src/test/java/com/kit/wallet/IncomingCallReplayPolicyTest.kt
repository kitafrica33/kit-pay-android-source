package com.kit.wallet

import com.kit.wallet.data.notifications.CallRingLease
import com.kit.wallet.data.notifications.CallLifecycleEvent
import com.kit.wallet.data.notifications.CallLifecycleKind
import com.kit.wallet.data.notifications.IncomingCallPublicationAuthorization
import com.kit.wallet.data.notifications.IncomingCallRetirementDisposition
import com.kit.wallet.data.notifications.callRingLease
import com.kit.wallet.data.notifications.reconcilePublishedIncomingCall
import com.kit.wallet.data.notifications.ringingRetirementDisposition
import com.kit.wallet.data.notifications.selectIncomingCallPublicationAuthorization
import com.kit.wallet.data.notifications.selectIncomingCallRetirement
import com.kit.wallet.data.notifications.selectIncomingRingLease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingCallReplayPolicyTest {
    @Test
    fun `trusted notification answer claims Telecom before retiring the shared ring`() {
        val actions = mutableListOf<String>()

        claimAuthorizedIncomingCallAnswer(
            callId = "call-1",
            markAnswering = { actions += "answering:$it" },
            retireRing = { actions += "retired:$it" },
        )

        assertEquals(listOf("answering:call-1", "retired:call-1"), actions)
    }

    @Test
    fun `server timestamps become a bounded lease on the current boot`() {
        val lease = callRingLease(
            ringExpiresAt = "2026-08-28T12:05:00Z",
            serverTime = "2026-08-28T12:00:00Z",
            receivedElapsedRealtimeMillis = 10_000L,
            bootSessionId = 7L,
        )

        assertEquals(
            CallRingLease(
                sourceRingExpiresAt = "2026-08-28T12:05:00Z",
                bootSessionId = 7L,
                receivedElapsedRealtimeMillis = 10_000L,
                deadlineElapsedRealtimeMillis = 70_000L,
            ),
            lease,
        )
        assertEquals(45_000L, lease?.remainingMillis(25_000L, 7L))
        assertNull(lease?.remainingMillis(25_000L, 8L))
    }

    @Test
    fun `missing malformed and elapsed server windows fail closed`() {
        assertNull(callRingLease(RING_EXPIRY, null, 1_000L, 7L))
        assertNull(callRingLease("invalid", SERVER_TIME, 1_000L, 7L))
        assertNull(callRingLease(SERVER_TIME, SERVER_TIME, 1_000L, 7L))
        assertNull(callRingLease(RING_EXPIRY, SERVER_TIME, 1_000L, null))
    }

    @Test
    fun `exact duplicate keeps the earliest admitted monotonic deadline`() {
        val first = lease(deadline = 46_000L)
        val retry = lease(deadline = 48_000L)

        assertEquals(
            first,
            selectIncomingRingLease(
                candidate = retry,
                recorded = first,
                retired = false,
                nowElapsedRealtimeMillis = 5_000L,
                currentBootSessionId = BOOT_ID,
            ),
        )
        assertEquals(
            retry.copy(deadlineElapsedRealtimeMillis = 44_000L),
            selectIncomingRingLease(
                candidate = retry.copy(deadlineElapsedRealtimeMillis = 44_000L),
                recorded = first,
                retired = false,
                nowElapsedRealtimeMillis = 5_000L,
                currentBootSessionId = BOOT_ID,
            ),
        )
    }

    @Test
    fun `terminal changed expiry and elapsed record cannot revive a ring`() {
        val recorded = lease(deadline = 46_000L)
        val candidate = lease(deadline = 48_000L)

        assertNull(selectIncomingRingLease(candidate, recorded, true, 5_000L, BOOT_ID))
        assertNull(
            selectIncomingRingLease(
                candidate.copy(sourceRingExpiresAt = "2026-08-28T12:00:46Z"),
                recorded,
                false,
                5_000L,
                BOOT_ID,
            ),
        )
        assertNull(
            selectIncomingRingLease(candidate, recorded, false, 46_000L, BOOT_ID),
        )
    }

    @Test
    fun `terminal retirement remains distinct from a naturally elapsed lease`() {
        val elapsed = lease(deadline = 46_000L)

        assertEquals(
            IncomingCallPublicationAuthorization.Expired,
            selectIncomingCallPublicationAuthorization(
                ringExpiresAt = RING_EXPIRY,
                recorded = elapsed,
                retirement = null,
                nowElapsedRealtimeMillis = 46_000L,
                currentBootSessionId = BOOT_ID,
            ),
        )
        assertEquals(
            IncomingCallPublicationAuthorization.Retired(
                IncomingCallRetirementDisposition.REJECTED,
            ),
            selectIncomingCallPublicationAuthorization(
                ringExpiresAt = RING_EXPIRY,
                recorded = null,
                retirement = IncomingCallPublicationAuthorization.Retired(
                    IncomingCallRetirementDisposition.REJECTED,
                ),
                nowElapsedRealtimeMillis = 46_000L,
                currentBootSessionId = BOOT_ID,
            ),
        )
        assertEquals(
            IncomingCallPublicationAuthorization.Expired,
            selectIncomingCallPublicationAuthorization(
                ringExpiresAt = RING_EXPIRY,
                recorded = null,
                retirement = IncomingCallPublicationAuthorization.Expired,
                nowElapsedRealtimeMillis = 46_000L,
                currentBootSessionId = BOOT_ID,
            ),
        )
    }

    @Test
    fun `terminal lifecycle retirement preserves its Telecom disposition`() {
        val dispositions = CallLifecycleKind.entries.associateWith { kind ->
            CallLifecycleEvent(callId = "call-1", kind = kind)
                .ringingRetirementDisposition()
        }

        assertEquals(
            mapOf(
                CallLifecycleKind.ANSWERED to
                    IncomingCallRetirementDisposition.ANSWERED_ELSEWHERE,
                CallLifecycleKind.DECLINED to IncomingCallRetirementDisposition.REJECTED,
                CallLifecycleKind.ENDED to IncomingCallRetirementDisposition.REMOTE,
                CallLifecycleKind.MISSED to IncomingCallRetirementDisposition.MISSED,
            ),
            dispositions,
        )
    }

    @Test
    fun `remote terminal disposition survives later local teardown`() {
        val remote = IncomingCallPublicationAuthorization.Retired(
            IncomingCallRetirementDisposition.REMOTE,
        )

        assertEquals(
            remote,
            selectIncomingCallRetirement(
                existing = remote,
                proposed = IncomingCallPublicationAuthorization.Retired(
                    IncomingCallRetirementDisposition.LOCAL,
                ),
            ),
        )
    }

    @Test
    fun `claimed natural expiry survives a later terminal event`() {
        assertEquals(
            IncomingCallPublicationAuthorization.Expired,
            selectIncomingCallRetirement(
                existing = IncomingCallPublicationAuthorization.Expired,
                proposed = IncomingCallPublicationAuthorization.Retired(
                    IncomingCallRetirementDisposition.REJECTED,
                ),
            ),
        )
    }

    @Test
    fun `terminal retirement during publication removes every newly recreated surface`() {
        val actions = mutableListOf("ring admitted", "surfaces published", "deadline scheduled")
        var authorized = false // A terminal path retired the lease while publication was active.

        val kept = reconcilePublishedIncomingCall(
            callId = "call-1",
            authorization = {
                actions += "authorization checked"
                if (authorized) {
                    IncomingCallPublicationAuthorization.Authorized
                } else {
                    IncomingCallPublicationAuthorization.Retired(
                        IncomingCallRetirementDisposition.REJECTED,
                    )
                }
            },
            retireSurfaces = { retiredCallId, disposition ->
                actions += "surfaces retired:${disposition.name}:$retiredCallId"
            },
            finishRinging = { retiredCallId, disposition ->
                actions += "telecom finished:${disposition.name}:$retiredCallId"
            },
        )

        assertFalse(kept)
        assertEquals(
            listOf(
                "ring admitted",
                "surfaces published",
                "deadline scheduled",
                "authorization checked",
                "surfaces retired:REJECTED:call-1",
                "telecom finished:REJECTED:call-1",
            ),
            actions,
        )

        // If retirement starts after the final read, its ordinary path owns cleanup instead.
        actions.clear()
        authorized = true
        assertTrue(
            reconcilePublishedIncomingCall(
                callId = "call-1",
                authorization = { IncomingCallPublicationAuthorization.Authorized },
                retireSurfaces = { retiredCallId, _ ->
                    actions += "surfaces retired:$retiredCallId"
                },
                finishRinging = { retiredCallId, _ ->
                    actions += "telecom finished:$retiredCallId"
                },
            ),
        )
        assertTrue(actions.isEmpty())
    }

    private fun lease(deadline: Long) = CallRingLease(
        sourceRingExpiresAt = RING_EXPIRY,
        bootSessionId = BOOT_ID,
        receivedElapsedRealtimeMillis = 1_000L,
        deadlineElapsedRealtimeMillis = deadline,
    )

    private companion object {
        const val BOOT_ID = 7L
        const val SERVER_TIME = "2026-08-28T12:00:00Z"
        const val RING_EXPIRY = "2026-08-28T12:00:45Z"
    }
}
