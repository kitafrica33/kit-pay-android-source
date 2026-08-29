package com.kit.wallet

import com.kit.wallet.data.remote.KitFeature
import com.kit.wallet.data.support.NegotiatedSupportProtocol
import com.kit.wallet.navigation.AppCapabilities
import com.kit.wallet.navigation.Dest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fail-closed derivation of the support and referral surfaces: the feature
 * flag and the negotiated protocol are BOTH required for support, every
 * payment dependency is required for the payment lane, and a failed refresh
 * darkens everything (support has no retained display-only form).
 */
class AppCapabilitiesSupportTest {
    private val negotiated = NegotiatedSupportProtocol(
        paymentsReady = true,
        companyBeneficiaryName = "Kit Pay",
        aiEnabled = true,
    )

    private val supportReady = AppCapabilities(
        features = mapOf(
            KitFeature.SUPPORT to true,
            KitFeature.SUPPORT_PAYMENTS to true,
            KitFeature.WALLETS to true,
            KitFeature.INTERNAL_TRANSFERS to true,
            KitFeature.SUPPORT_AI to true,
            KitFeature.REFERRALS to true,
        ),
        loaded = true,
        supportProtocol = negotiated,
    )

    @Test
    fun `support needs the feature flag and the negotiated handshake together`() {
        assertTrue(supportReady.supportUsable)
        assertFalse(supportReady.copy(supportProtocol = null).supportUsable)
        assertFalse(
            supportReady.copy(features = supportReady.features - KitFeature.SUPPORT)
                .supportUsable,
        )
        assertFalse(AppCapabilities().supportUsable)
    }

    @Test
    fun `support routes follow the strict handshake`() {
        assertTrue(supportReady.routeUsable(Dest.SUPPORT))
        assertTrue(supportReady.routeUsable(Dest.SUPPORT_NEW_TICKET))
        assertTrue(supportReady.routeUsable(Dest.SUPPORT_TICKET))

        val dark = supportReady.copy(supportProtocol = null)
        assertFalse(dark.routeUsable(Dest.SUPPORT))
        assertFalse(dark.routeUsable(Dest.SUPPORT_NEW_TICKET))
        assertFalse(dark.routeUsable(Dest.SUPPORT_TICKET))
    }

    @Test
    fun `the payment lane requires every dependency and server readiness`() {
        assertTrue(supportReady.supportPaymentsUsable)

        listOf(
            KitFeature.SUPPORT,
            KitFeature.SUPPORT_PAYMENTS,
            KitFeature.WALLETS,
            KitFeature.INTERNAL_TRANSFERS,
        ).forEach { feature ->
            assertFalse(
                feature,
                supportReady.copy(features = supportReady.features - feature)
                    .supportPaymentsUsable,
            )
        }
        assertFalse(
            supportReady.copy(
                supportProtocol = negotiated.copy(paymentsReady = false),
            ).supportPaymentsUsable,
        )
        assertFalse(supportReady.copy(supportProtocol = null).supportPaymentsUsable)
    }

    @Test
    fun `ai is advertised only with both the flag and the protocol saying so`() {
        assertTrue(supportReady.supportAiAdvertised)
        assertFalse(
            supportReady.copy(features = supportReady.features - KitFeature.SUPPORT_AI)
                .supportAiAdvertised,
        )
        assertFalse(
            supportReady.copy(supportProtocol = negotiated.copy(aiEnabled = false))
                .supportAiAdvertised,
        )
    }

    @Test
    fun `referrals bind to exactly the referrals capability`() {
        assertTrue(supportReady.referralsUsable)
        assertTrue(supportReady.routeUsable(Dest.REFERRALS))

        val off = supportReady.copy(features = supportReady.features - KitFeature.REFERRALS)
        assertFalse(off.referralsUsable)
        assertFalse(off.routeUsable(Dest.REFERRALS))
        // Not-yet-loaded discovery is not "on".
        assertFalse(
            AppCapabilities(features = mapOf(KitFeature.REFERRALS to true)).referralsUsable,
        )
    }

    @Test
    fun `a failed refresh darkens support and referrals with no retained form`() {
        // What a transport failure actually leaves behind: features cleared into
        // retainedFeatures, loadFailed set, and the protocol nulled by the VM.
        val offline = supportReady.copy(
            features = emptyMap(),
            retainedFeatures = supportReady.features,
            loadFailed = true,
            supportProtocol = null,
        )

        assertFalse(offline.supportUsable)
        assertFalse(offline.supportPaymentsUsable)
        assertFalse(offline.supportAiAdvertised)
        assertFalse(offline.referralsUsable)
        assertFalse(offline.routeUsable(Dest.SUPPORT))
        assertFalse(offline.routeUsable(Dest.REFERRALS))
    }

    @Test
    fun `support capability constants match the backend response contract`() {
        assertEquals("support", KitFeature.SUPPORT)
        assertEquals("support_payments", KitFeature.SUPPORT_PAYMENTS)
        assertEquals("support_ai", KitFeature.SUPPORT_AI)
        assertEquals("referrals", KitFeature.REFERRALS)
    }
}
