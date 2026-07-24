package com.kit.wallet

import android.content.pm.PackageManager
import com.kit.wallet.feature.calls.supportsTelecomRegistration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KitTelecomRegistrationGateTest {
    @Test
    @Suppress("DEPRECATION")
    fun `Android 9 connection service feature passes Telecom registration gate`() {
        assertTrue(
            supportsTelecomRegistration { feature ->
                feature == PackageManager.FEATURE_CONNECTION_SERVICE
            },
        )
    }

    @Test
    fun `Telecom feature remains accepted by registration gate`() {
        assertTrue(
            supportsTelecomRegistration { feature ->
                feature == PackageManager.FEATURE_TELECOM
            },
        )
    }

    @Test
    fun `device without a connection service is rejected`() {
        assertFalse(supportsTelecomRegistration { false })
    }
}
