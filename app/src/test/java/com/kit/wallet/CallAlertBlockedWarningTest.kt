package com.kit.wallet

import com.kit.wallet.data.notifications.callAlertsBlocked
import com.kit.wallet.feature.calls.CallAlertRecovery
import com.kit.wallet.feature.calls.callAlertRecovery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallAlertBlockedWarningTest {
    @Test
    fun `the warning tier is exactly android rendering nothing at all`() {
        assertTrue(callAlertsBlocked(postNotificationsGranted = false, appNotificationsEnabled = true))
        assertTrue(callAlertsBlocked(postNotificationsGranted = true, appNotificationsEnabled = false))
        assertTrue(callAlertsBlocked(postNotificationsGranted = false, appNotificationsEnabled = false))
        // Anything quieter than blocked — a demoted channel, a missing full-screen grant — is the
        // call notification's own settings action to explain, never this banner's.
        assertFalse(callAlertsBlocked(postNotificationsGranted = true, appNotificationsEnabled = true))
    }

    @Test
    fun `a denied permission android would still prompt for is asked for in place`() {
        assertEquals(
            CallAlertRecovery.REQUEST_PERMISSION,
            callAlertRecovery(postNotificationsGranted = false, promptAvailable = true),
        )
    }

    @Test
    fun `every unpromptable shape of off leads to the notification settings screen`() {
        // A spent or never-askable permission: launching the prompt would return denied unshown.
        assertEquals(
            CallAlertRecovery.OPEN_NOTIFICATION_SETTINGS,
            callAlertRecovery(postNotificationsGranted = false, promptAvailable = false),
        )
        // Permission held but the app-level toggle is off: only the settings screen can undo it.
        assertEquals(
            CallAlertRecovery.OPEN_NOTIFICATION_SETTINGS,
            callAlertRecovery(postNotificationsGranted = true, promptAvailable = false),
        )
        assertEquals(
            CallAlertRecovery.OPEN_NOTIFICATION_SETTINGS,
            callAlertRecovery(postNotificationsGranted = true, promptAvailable = true),
        )
    }
}
