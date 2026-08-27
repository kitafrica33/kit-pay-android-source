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
        // Reading is not exchanging: a transcript and a member list come out of the local
        // encrypted store, so their routes stay open while the session is still preparing.
        assertTrue(serverOnly.routeUsable(Dest.CONVERSATION))
        assertTrue(serverOnly.routeUsable(Dest.GROUP_PROFILE))
        // Starting or changing one is a server-authenticated action and still is not.
        assertFalse(serverOnly.routeUsable(Dest.CONTACTS))
        assertFalse(serverOnly.routeUsable(Dest.NEW_GROUP))
        assertFalse(serverOnly.routeUsable(Dest.GROUP_ADD))
        assertFalse(serverOnly.routeUsable(Dest.GROUP_PHOTO))
        assertTrue(ready.messagingUsable)
        assertTrue(ready.routeUsable(Dest.CONTACTS))
        assertTrue(ready.routeUsable(Dest.CONVERSATION))
    }

    @Test
    fun `a failed refresh keeps local-only surfaces but never re-enables an action`() {
        val loaded = AppCapabilities(
            features = mapOf(KitFeature.MESSAGING to true, KitFeature.CALLS to true),
            retainedFeatures = mapOf(KitFeature.MESSAGING to true, KitFeature.CALLS to true),
            loaded = true,
            secureMessagingClientReady = true,
            messagingProtocolReady = true,
            messagingProtocolVersion = "v2",
            messagingProtocolSuite = "signal-pqxdh-kyber1024-double-ratchet-v2",
            messagingProtocolPostQuantum = true,
        )
        // What a transport failure actually leaves behind: features cleared, retained kept.
        val offline = loaded.copy(
            features = emptyMap(),
            loadFailed = true,
            messagingProtocolReady = false,
            messagingProtocolVersion = null,
            messagingProtocolSuite = null,
            messagingProtocolPostQuantum = null,
        )

        assertTrue(offline.messagingEntryVisible)
        assertTrue(offline.routeUsable(Dest.CHATS))
        assertTrue(offline.routeUsable(Dest.CONVERSATION))
        assertTrue(offline.routeUsable(Dest.CALLS))
        assertTrue(offline.routeUsable(Dest.INCOMING_CALL))
        // Everything that acts stays fail-closed, including messaging itself.
        assertFalse(offline.enabled(KitFeature.MESSAGING))
        assertFalse(offline.messagingServerCompatible)
        assertFalse(offline.messagingUsable)
        assertFalse(offline.routeUsable(Dest.CONTACTS))
        assertFalse(offline.routeUsable(Dest.NEW_GROUP))

        // A server that answers "off" is not a failure, and must switch the surface off.
        val disabled = loaded.copy(
            features = emptyMap(),
            retainedFeatures = emptyMap(),
        )
        assertFalse(disabled.messagingEntryVisible)
        assertFalse(disabled.routeUsable(Dest.CHATS))
        assertFalse(disabled.routeUsable(Dest.CALLS))
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
        assertTrue(capabilities.routeUsable(Dest.SEND_ROUTE))
        assertTrue(capabilities.routeUsable(Dest.send("kit-user-1")))
        assertTrue(capabilities.routeUsable(Dest.REQUEST))
        assertTrue(capabilities.routeUsable(Dest.TRANSACTIONS))
        assertFalse(capabilities.copy(features = capabilities.features - KitFeature.WALLETS)
            .routeUsable(Dest.SEND))
        assertFalse(capabilities.copy(features = capabilities.features - KitFeature.WALLETS)
            .routeUsable(Dest.SEND_ROUTE))
        assertFalse(capabilities.copy(features = capabilities.features - KitFeature.WALLETS)
            .routeUsable(Dest.send("kit-user-1")))
        assertFalse(
            capabilities.copy(features = capabilities.features - KitFeature.INTERNAL_TRANSFERS)
                .routeUsable(Dest.SEND_ROUTE),
        )
        assertFalse(capabilities.copy(features = capabilities.features - KitFeature.WALLETS)
            .routeUsable(Dest.REQUEST))
    }

    @Test
    fun `provider money routes require a wallet and their own rollout capability`() {
        val allProviderFeatures = mapOf(
            KitFeature.WALLETS to true,
            KitFeature.BILLS to true,
            KitFeature.AIRTIME to true,
            KitFeature.BANK_TRANSFERS to true,
            KitFeature.MOBILE_MONEY to true,
        )
        val available = AppCapabilities(features = allProviderFeatures, loaded = true)

        assertTrue(available.billPaymentsUsable)
        assertTrue(available.airtimeUsable)
        assertTrue(available.bankTransfersUsable)
        assertTrue(available.mobileMoneyUsable)
        assertTrue(available.routeUsable(Dest.BILLS))
        assertTrue(available.routeUsable(Dest.BILL_PAY))
        assertTrue(available.routeUsable(Dest.AIRTIME))
        assertTrue(available.routeUsable(Dest.BANK))
        assertTrue(available.routeUsable(Dest.MOBILE_MONEY))

        val noWallet = available.copy(features = allProviderFeatures - KitFeature.WALLETS)
        assertFalse(noWallet.billPaymentsUsable)
        assertFalse(noWallet.airtimeUsable)
        assertFalse(noWallet.bankTransfersUsable)
        assertFalse(noWallet.mobileMoneyUsable)
        assertFalse(noWallet.routeUsable(Dest.BILLS))
        assertFalse(noWallet.routeUsable(Dest.BILL_PAY))
        assertFalse(noWallet.routeUsable(Dest.AIRTIME))
        assertFalse(noWallet.routeUsable(Dest.BANK))
        assertFalse(noWallet.routeUsable(Dest.MOBILE_MONEY))

        listOf(
            KitFeature.BILLS to Dest.BILLS,
            KitFeature.AIRTIME to Dest.AIRTIME,
            KitFeature.BANK_TRANSFERS to Dest.BANK,
            KitFeature.MOBILE_MONEY to Dest.MOBILE_MONEY,
        ).forEach { (feature, route) ->
            assertFalse(
                feature,
                available.copy(features = allProviderFeatures - feature).routeUsable(route),
            )
        }
    }

    @Test
    fun `bank route remains available for deposits when outbound transfers are off`() {
        val depositsOnly = AppCapabilities(
            features = mapOf(
                KitFeature.WALLETS to true,
                KitFeature.BANK_DEPOSITS to true,
                KitFeature.BANK_TRANSFERS to false,
            ),
            loaded = true,
        )

        assertTrue(depositsOnly.bankDepositsUsable)
        assertFalse(depositsOnly.bankTransfersUsable)
        assertTrue(depositsOnly.bankUsable)
        assertTrue(depositsOnly.routeUsable(Dest.BANK))
        assertFalse(
            depositsOnly.copy(features = depositsOnly.features - KitFeature.WALLETS)
                .routeUsable(Dest.BANK),
        )
    }

    @Test
    fun `scanner stays closed without a client or wallet while receive opens for wallets`() {
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
        assertFalse(
            serverOnly.copy(
                features = serverOnly.features - KitFeature.WALLETS,
                qrScannerClientReady = true,
            ).routeUsable(Dest.SCAN),
        )
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
        assertEquals("claimable_transfers", KitFeature.CLAIMABLE_TRANSFERS)
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
        assertEquals("abuse_reporting", KitFeature.ABUSE_REPORTING)
        assertEquals("kyc", KitFeature.KYC)
        assertEquals("email_registration", KitFeature.EMAIL_REGISTRATION)
        assertEquals("email_recovery", KitFeature.EMAIL_RECOVERY)
        assertEquals("account_deletion", KitFeature.ACCOUNT_DELETION)
    }
}
