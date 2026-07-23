package com.kit.wallet

import com.kit.wallet.data.remote.KitFeature
import com.kit.wallet.navigation.AppCapabilities
import com.kit.wallet.navigation.Dest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppCapabilitiesTest {
    @Test
    fun `unknown and unavailable capabilities fail closed`() {
        val capabilities = AppCapabilities()

        assertFalse(capabilities.enabled("mobile_money"))
        assertFalse(capabilities.messagingEntryVisible)
        assertFalse(capabilities.messagingUsable)
    }

    @Test
    fun `messaging is discoverable from server support but use needs reviewed client`() {
        val serverOnly = AppCapabilities(
            features = mapOf(KitFeature.MESSAGING to true),
            loaded = true,
        )
        val clientOnly = serverOnly.copy(secureMessagingClientReady = true)
        val ready = clientOnly.copy(
            messagingProtocolReady = true,
            messagingProtocolVersion = "v2",
            messagingProtocolSuite = "signal-pqxdh-kyber1024-double-ratchet-v2",
            messagingProtocolPostQuantum = true,
        )

        assertTrue(serverOnly.messagingEntryVisible)
        assertTrue(serverOnly.routeUsable(Dest.CHATS))
        assertFalse(serverOnly.messagingUsable)
        assertFalse(clientOnly.messagingUsable)
        assertFalse(serverOnly.routeUsable(Dest.CONTACTS))
        assertFalse(serverOnly.routeUsable(Dest.CONVERSATION))
        assertTrue(ready.messagingUsable)
        assertTrue(ready.routeUsable(Dest.CONTACTS))
        assertTrue(ready.routeUsable(Dest.CONVERSATION))
    }

    @Test
    fun `messaging fails closed for incomplete or mismatched server protocol`() {
        val ready = AppCapabilities(
            features = mapOf(KitFeature.MESSAGING to true),
            loaded = true,
            secureMessagingClientReady = true,
            messagingProtocolReady = true,
            messagingProtocolVersion = "v2",
            messagingProtocolSuite = "signal-pqxdh-kyber1024-double-ratchet-v2",
            messagingProtocolPostQuantum = true,
        )

        assertTrue(ready.messagingUsable)
        assertFalse(ready.copy(messagingProtocolReady = false).messagingUsable)
        assertFalse(ready.copy(messagingProtocolVersion = null).messagingUsable)
        assertFalse(ready.copy(messagingProtocolVersion = "v1").messagingUsable)
        assertFalse(ready.copy(messagingProtocolSuite = null).messagingUsable)
        assertFalse(ready.copy(messagingProtocolSuite = "unknown").messagingUsable)
        assertFalse(ready.copy(messagingProtocolPostQuantum = null).messagingUsable)
        assertFalse(ready.copy(messagingProtocolPostQuantum = false).messagingUsable)
    }

    @Test
    fun `failed or incomplete discovery never enables a server feature`() {
        val stillLoading = AppCapabilities(features = mapOf(KitFeature.WALLETS to true))
        val failed = stillLoading.copy(loaded = true, loadFailed = true)

        assertFalse(stillLoading.enabled(KitFeature.WALLETS))
        assertFalse(failed.enabled(KitFeature.WALLETS))
    }

    @Test
    fun `money routes require every backend feature they use`() {
        val capabilities = AppCapabilities(
            features = mapOf(
                KitFeature.WALLETS to true,
                KitFeature.INTERNAL_TRANSFERS to true,
                KitFeature.PAYMENT_REQUESTS to true,
            ),
            loaded = true,
        )

        assertTrue(capabilities.routeUsable(Dest.SEND))
        assertTrue(capabilities.routeUsable(Dest.REQUEST))
        assertTrue(capabilities.routeUsable(Dest.TRANSACTIONS))
        assertFalse(capabilities.copy(features = capabilities.features - KitFeature.WALLETS)
            .routeUsable(Dest.SEND))
        assertFalse(capabilities.copy(features = capabilities.features - KitFeature.WALLETS)
            .routeUsable(Dest.REQUEST))
    }

    @Test
    fun `scanner stays closed without a client while receive opens for wallets`() {
        val serverOnly = AppCapabilities(
            features = mapOf(
                KitFeature.WALLETS to true,
                KitFeature.MERCHANT_PAYMENTS to true,
                KitFeature.QR_PAYMENTS to true,
            ),
            loaded = true,
        )

        assertFalse(serverOnly.routeUsable(Dest.SCAN))
        assertTrue(serverOnly.routeUsable(Dest.RECEIVE))
        assertTrue(serverOnly.copy(qrScannerClientReady = true).routeUsable(Dest.SCAN))
    }

    @Test
    fun `route guard covers stale messaging contacts and account access`() {
        val disabled = AppCapabilities(loaded = true)

        assertFalse(disabled.routeUsable(Dest.CONTACTS))
        assertFalse(disabled.routeUsable(Dest.REGISTER))
        assertFalse(disabled.routeUsable(Dest.FORGOT_PASSWORD))
        assertTrue(
            disabled.copy(features = mapOf(KitFeature.EMAIL_REGISTRATION to true))
                .routeUsable(Dest.REGISTER),
        )
        assertTrue(
            disabled.copy(features = mapOf(KitFeature.EMAIL_RECOVERY to true))
                .routeUsable(Dest.FORGOT_PASSWORD),
        )
    }

    @Test
    fun `incoming call route is guarded by the calls capability`() {
        val disabled = AppCapabilities(loaded = true)
        val enabled = disabled.copy(features = mapOf(KitFeature.CALLS to true))

        assertFalse(disabled.routeUsable(Dest.INCOMING_CALL))
        assertFalse(disabled.routeUsable(Dest.CALL_CONTACTS))
        assertTrue(enabled.routeUsable(Dest.INCOMING_CALL))
        assertTrue(enabled.routeUsable(Dest.CALL_CONTACTS))
        assertFalse(enabled.routeUsable(Dest.CONTACTS))
    }

    @Test
    fun `android capability constants match the backend response contract`() {
        assertEquals("wallets", KitFeature.WALLETS)
        assertEquals("internal_transfers", KitFeature.INTERNAL_TRANSFERS)
        assertEquals("payment_requests", KitFeature.PAYMENT_REQUESTS)
        assertEquals("merchant_payments", KitFeature.MERCHANT_PAYMENTS)
        assertEquals("qr_payments", KitFeature.QR_PAYMENTS)
        assertEquals("mobile_money", KitFeature.MOBILE_MONEY)
        assertEquals("bank_transfers", KitFeature.BANK_TRANSFERS)
        assertEquals("airtime", KitFeature.AIRTIME)
        assertEquals("bills", KitFeature.BILLS)
        assertEquals("messaging", KitFeature.MESSAGING)
        assertEquals("calls", KitFeature.CALLS)
        assertEquals("notifications", KitFeature.NOTIFICATIONS)
        assertEquals("kyc", KitFeature.KYC)
        assertEquals("email_registration", KitFeature.EMAIL_REGISTRATION)
        assertEquals("email_recovery", KitFeature.EMAIL_RECOVERY)
        assertEquals("account_deletion", KitFeature.ACCOUNT_DELETION)
    }
}
