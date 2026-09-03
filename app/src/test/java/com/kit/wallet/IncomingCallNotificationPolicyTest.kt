package com.kit.wallet

import android.app.NotificationManager
import com.kit.wallet.data.notifications.IncomingCallAlertMode
import com.kit.wallet.data.notifications.IncomingCallNotificationAccess
import com.kit.wallet.data.notifications.IncomingCallNotificationSurface
import com.kit.wallet.data.notifications.foregroundReadinessCopy
import com.kit.wallet.data.notifications.incomingCallAlertPlan
import com.kit.wallet.data.notifications.shouldRelayBlockedIncomingCallInForeground
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingCallNotificationPolicyTest {
    @Test
    fun `android 14 full screen access selects full screen presentation`() {
        val plan = incomingCallAlertPlan(access(fullScreenIntentAllowed = true))
        assertEquals(IncomingCallAlertMode.FULL_SCREEN, plan.mode)
        assertFalse(plan.showSettingsAction)
    }

    @Test
    fun `denied full screen access falls back to heads up with settings action`() {
        val plan = incomingCallAlertPlan(access(fullScreenIntentAllowed = false))
        assertEquals(IncomingCallAlertMode.HEADS_UP, plan.mode)
        assertTrue(plan.showSettingsAction)
    }

    @Test
    fun `notification denial and low importance fail closed`() {
        assertEquals(
            IncomingCallAlertMode.BLOCKED,
            incomingCallAlertPlan(access(postNotificationsGranted = false)).mode,
        )
        assertEquals(
            IncomingCallAlertMode.PASSIVE,
            incomingCallAlertPlan(access(channelImportance = NotificationManager.IMPORTANCE_LOW)).mode,
        )
    }

    @Test
    fun `a silent high importance channel keeps visual call but is not reported ready to ring`() {
        val plan = incomingCallAlertPlan(access(channelHasSound = false))

        assertEquals(IncomingCallAlertMode.FULL_SCREEN, plan.mode)
        assertTrue(plan.useFullScreenIntent)
        assertTrue(plan.showSettingsAction)
        assertEquals(
            "Call alerts are silent · turn on sound in notification settings",
            plan.foregroundReadinessCopy(),
        )
    }

    @Test
    fun `silent channel takes precedence over missing full screen access in readiness copy`() {
        val plan = incomingCallAlertPlan(
            access(channelHasSound = false, fullScreenIntentAllowed = false),
        )

        assertEquals(IncomingCallAlertMode.HEADS_UP, plan.mode)
        assertTrue(plan.showSettingsAction)
        assertEquals(
            "Call alerts are silent · turn on sound in notification settings",
            plan.foregroundReadinessCopy(),
        )
    }

    @Test
    fun `only a foreground primary call bypasses a blocked notification surface`() {
        assertTrue(
            shouldRelayBlockedIncomingCallInForeground(
                IncomingCallAlertMode.BLOCKED,
                foregrounded = true,
                surface = IncomingCallNotificationSurface.FULL_SCREEN_RING,
            ),
        )
        assertFalse(
            shouldRelayBlockedIncomingCallInForeground(
                IncomingCallAlertMode.BLOCKED,
                foregrounded = false,
                surface = IncomingCallNotificationSurface.FULL_SCREEN_RING,
            ),
        )
        assertFalse(
            shouldRelayBlockedIncomingCallInForeground(
                IncomingCallAlertMode.BLOCKED,
                foregrounded = true,
                surface = IncomingCallNotificationSurface.CALL_WAITING,
            ),
        )
        assertFalse(
            shouldRelayBlockedIncomingCallInForeground(
                IncomingCallAlertMode.HEADS_UP,
                foregrounded = true,
                surface = IncomingCallNotificationSurface.FULL_SCREEN_RING,
            ),
        )
    }

    private fun access(
        postNotificationsGranted: Boolean = true,
        appNotificationsEnabled: Boolean = true,
        channelImportance: Int = NotificationManager.IMPORTANCE_HIGH,
        channelHasSound: Boolean = true,
        fullScreenIntentAllowed: Boolean = true,
    ) = IncomingCallNotificationAccess(
        postNotificationsGranted,
        appNotificationsEnabled,
        channelImportance,
        channelHasSound,
        fullScreenIntentAllowed,
    )
}
