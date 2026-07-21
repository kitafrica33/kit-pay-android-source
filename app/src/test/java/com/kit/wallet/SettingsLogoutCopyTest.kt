package com.kit.wallet

import com.kit.wallet.feature.settings.logoutFailureMessage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsLogoutCopyTest {
    @Test
    fun `logout failure copy does not claim a cleared session remains`() {
        val message = logoutFailureMessage("Secure local cleanup needs attention.")

        assertFalse(message.contains("session remains", ignoreCase = true))
        assertTrue(message.contains("If this phone still shows you as signed in"))
    }
}
