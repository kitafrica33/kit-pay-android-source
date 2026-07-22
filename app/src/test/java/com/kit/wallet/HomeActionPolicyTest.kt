package com.kit.wallet

import com.kit.wallet.data.remote.KitFeature
import com.kit.wallet.feature.home.HomeAction
import com.kit.wallet.feature.home.homeActionAccess
import com.kit.wallet.navigation.AppCapabilities
import com.kit.wallet.navigation.Dest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeActionPolicyTest {
    @Test
    fun `every dashboard action has an explicit route gate and coming-soon message`() {
        val expectedRoutes = mapOf(
            HomeAction.SCAN_QR to Dest.SCAN,
            HomeAction.SEND_MONEY to Dest.SEND,
            HomeAction.RECEIVE_MONEY to Dest.RECEIVE,
            HomeAction.REQUEST_MONEY to Dest.REQUEST,
            HomeAction.VERIFY_IDENTITY to Dest.KYC,
            HomeAction.PAY_BILLS to Dest.BILLS,
            HomeAction.BUY_AIRTIME to Dest.AIRTIME,
            HomeAction.BANK to Dest.BANK,
            HomeAction.MOBILE_MONEY to Dest.MOBILE_MONEY,
            HomeAction.FAVORITE_SEND to Dest.SEND,
            HomeAction.ALL_TRANSACTIONS to Dest.TRANSACTIONS,
            HomeAction.TRANSACTION_DETAIL to Dest.TX_DETAIL,
        )
        val unavailable = AppCapabilities(loaded = true)

        assertEquals(HomeAction.entries.toSet(), expectedRoutes.keys)
        expectedRoutes.forEach { (action, route) ->
            assertEquals(route, action.guardedRoute)
            val access = unavailable.homeActionAccess(action)
            assertFalse(action.name, access.available)
            assertEquals("Coming soon: ${action.displayName}.", access.unavailableMessage)
        }
    }

    @Test
    fun `server activation opens every implemented dashboard flow`() {
        val activated = fullyActivatedCapabilities()

        HomeAction.entries.forEach { action ->
            assertTrue(action.name, activated.homeActionAccess(action).available)
        }
    }

    @Test
    fun `receive and scan stay discoverable but gated until reviewed clients activate`() {
        val serverActivated = fullyActivatedCapabilities().copy(
            qrScannerClientReady = false,
            receiveQrClientReady = false,
        )

        assertFalse(serverActivated.homeActionAccess(HomeAction.SCAN_QR).available)
        assertFalse(serverActivated.homeActionAccess(HomeAction.RECEIVE_MONEY).available)
        HomeAction.entries
            .filterNot { it in setOf(HomeAction.SCAN_QR, HomeAction.RECEIVE_MONEY) }
            .forEach { action ->
                assertTrue(action.name, serverActivated.homeActionAccess(action).available)
            }

        val clientActivated = serverActivated.copy(
            qrScannerClientReady = true,
            receiveQrClientReady = true,
        )
        assertTrue(clientActivated.homeActionAccess(HomeAction.SCAN_QR).available)
        assertTrue(clientActivated.homeActionAccess(HomeAction.RECEIVE_MONEY).available)
    }

    private fun fullyActivatedCapabilities() = AppCapabilities(
        features = mapOf(
            KitFeature.WALLETS to true,
            KitFeature.INTERNAL_TRANSFERS to true,
            KitFeature.PAYMENT_REQUESTS to true,
            KitFeature.MERCHANT_PAYMENTS to true,
            KitFeature.QR_PAYMENTS to true,
            KitFeature.BILLS to true,
            KitFeature.AIRTIME to true,
            KitFeature.BANK_TRANSFERS to true,
            KitFeature.MOBILE_MONEY to true,
            KitFeature.KYC to true,
        ),
        loaded = true,
        qrScannerClientReady = true,
        receiveQrClientReady = true,
    )
}
