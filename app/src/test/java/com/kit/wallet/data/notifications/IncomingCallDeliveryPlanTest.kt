package com.kit.wallet.data.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingCallDeliveryPlanTest {
    @Test
    fun `call waiting is tracked in Telecom without a full screen ring`() {
        val plan = incomingCallDeliveryPlan(
            activeCallId = "active-call",
            incomingCallId = "waiting-call",
        )

        assertTrue(plan.trackWithTelecom)
        assertTrue(plan.relayToActiveCall)
        assertEquals(IncomingCallNotificationSurface.CALL_WAITING, plan.notificationSurface)
        assertEquals(IncomingCallNotificationTarget.ACTIVE_CALL, plan.notificationTarget)
        assertNull(
            plan.notificationTarget.deepLink(
                IncomingCallPayload(
                    callId = "550e8400-e29b-41d4-a716-446655440000",
                    callerName = "Waiting caller",
                    video = false,
                ),
            ),
        )
        assertFalse(plan.notificationSurface == IncomingCallNotificationSurface.FULL_SCREEN_RING)
    }

    @Test
    fun `primary incoming call keeps Telecom tracking and full screen ring`() {
        val plan = incomingCallDeliveryPlan(
            activeCallId = null,
            incomingCallId = "primary-call",
        )

        assertTrue(plan.trackWithTelecom)
        assertFalse(plan.relayToActiveCall)
        assertEquals(IncomingCallNotificationSurface.FULL_SCREEN_RING, plan.notificationSurface)
        assertEquals(IncomingCallNotificationTarget.INCOMING_CALL, plan.notificationTarget)
        assertEquals(
            "kitwallet://call/incoming?call_id=550e8400-e29b-41d4-a716-446655440000",
            plan.notificationTarget.deepLink(
                IncomingCallPayload(
                    callId = "550e8400-e29b-41d4-a716-446655440000",
                    callerName = "Primary caller",
                    video = false,
                ),
            ),
        )
    }
}
