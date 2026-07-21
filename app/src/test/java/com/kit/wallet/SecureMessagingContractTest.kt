package com.kit.wallet

import com.kit.wallet.data.messaging.SecureMessagingContract
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureMessagingContractTest {
    @Test
    fun `only the exact ready PQXDH v2 advertisement matches`() {
        assertTrue(
            SecureMessagingContract.matchesServerAdvertisement(
                ready = true,
                version = "v2",
                suite = "signal-pqxdh-kyber1024-double-ratchet-v2",
                postQuantum = true,
            ),
        )
        assertFalse(
            SecureMessagingContract.matchesServerAdvertisement(
                ready = false,
                version = "v2",
                suite = "signal-pqxdh-kyber1024-double-ratchet-v2",
                postQuantum = true,
            ),
        )
        assertFalse(
            SecureMessagingContract.matchesServerAdvertisement(
                ready = true,
                version = "v1",
                suite = "signal-x3dh-double-ratchet-v1",
                postQuantum = false,
            ),
        )
    }

    @Test
    fun `null or partially upgraded protocol metadata fails closed`() {
        assertFalse(
            SecureMessagingContract.matchesServerAdvertisement(
                ready = true,
                version = null,
                suite = SecureMessagingContract.SUITE,
                postQuantum = true,
            ),
        )
        assertFalse(
            SecureMessagingContract.matchesServerAdvertisement(
                ready = true,
                version = SecureMessagingContract.VERSION,
                suite = null,
                postQuantum = true,
            ),
        )
        assertFalse(
            SecureMessagingContract.matchesServerAdvertisement(
                ready = true,
                version = SecureMessagingContract.VERSION,
                suite = SecureMessagingContract.SUITE,
                postQuantum = null,
            ),
        )
    }
}
