package com.kit.wallet.data.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    }
}
