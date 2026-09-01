package com.kit.wallet

import com.kit.wallet.data.mapper.hasVerifiedCustomerProjection
import com.kit.wallet.data.mapper.isCustomerVisibleWalletTransaction
import com.kit.wallet.data.mapper.toEntity
import com.kit.wallet.data.mapper.toUiModel
import com.kit.wallet.data.remote.AccountVerificationDto
import com.kit.wallet.data.remote.CounterpartyDto
import com.kit.wallet.data.remote.CustomerTransactionTotalsDto
import com.kit.wallet.data.remote.CurrencyDto
import com.kit.wallet.data.remote.TransactionDto
import com.kit.wallet.ui.model.AccountVerificationDesignation
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionIdentityMappingTest {
    @Test
    fun `counterparty identity survives the api room and ui round trip`() {
        val entity = transaction(
            CounterpartyDto(
                id = USER_ID,
                name = "Amina",
                avatarUrl = " https://pay.kit.africa/media/amina ",
                verification = AccountVerificationDto(
                    "verified",
                    "2026-08-29T10:11:12Z",
                ),
            ),
        ).toEntity(WALLET_ID, "UGX", 0)
        val presented = entity.toUiModel()

        assertEquals(USER_ID, entity.counterpartyUserId)
        assertEquals(true, entity.customerProjectionVerified)
        assertEquals("https://pay.kit.africa/media/amina", entity.counterpartyAvatarUrl)
        assertEquals("verified", entity.counterpartyVerificationDesignation)
        assertEquals(WALLET_ID, presented.walletId)
        assertTrue(presented.customerProjectionVerified)
        assertEquals(USER_ID, presented.counterpartyUserId)
        assertEquals(entity.counterpartyAvatarUrl, presented.counterpartyAvatarUrl)
        assertEquals(
            AccountVerificationDesignation.VERIFIED,
            presented.accountVerification?.designation,
        )
    }

    @Test
    fun `intrinsic service counterparties are removed before entering room`() {
        val serviceCounterparty = CounterpartyDto(
            id = USER_ID,
            name = "Kit institutional commission",
            phone = "+256700000999",
            accountNumber = "KIT-INTERNAL-1",
            avatarUrl = "https://pay.kit.africa/media/internal-wallet",
            verification = AccountVerificationDto(
                "verified",
                "2026-08-29T10:11:12Z",
            ),
        )

        listOf(
            "airtime",
            "bank_deposit",
            "bank_reversal",
            "bank_transfer",
            "bank_withdrawal",
            "bill_payment",
            "provider_reversal",
            "referral_reward",
            "referral_reward_reversal",
        ).forEach { type ->
            val entity = transaction(serviceCounterparty).copy(type = type)
                .toEntity(WALLET_ID, "UGX", 0)
            val presented = entity.toUiModel()

            assertEquals(type, "Kit Pay", entity.counterpartyName)
            assertNull(type, entity.counterpartyUserId)
            assertNull(type, entity.counterpartyAvatarUrl)
            assertNull(type, entity.counterpartyVerificationDesignation)
            assertNull(type, entity.counterpartyVerificationSince)
            assertEquals(type, "Kit Pay", presented.counterparty)
            assertNull(type, presented.counterpartyUserId)
            assertNull(type, presented.counterpartyAvatarUrl)
            assertNull(type, presented.accountVerification)
        }
    }

    @Test
    fun `person and merchant transaction identities remain public`() {
        val publicCounterparty = CounterpartyDto(
            id = USER_ID,
            name = "Amina",
            avatarUrl = "https://pay.kit.africa/media/amina",
            verification = AccountVerificationDto(
                "verified",
                "2026-08-29T10:11:12Z",
            ),
        )

        listOf(
            "internal_transfer",
            "internal_transfer_reversal",
            "merchant_escrow_release",
            "merchant_payment",
            "merchant_refund",
        ).forEach { type ->
            val entity = transaction(publicCounterparty).copy(type = type)
                .toEntity(WALLET_ID, "UGX", 0)

            assertEquals(type, "Amina", entity.counterpartyName)
            assertEquals(type, USER_ID, entity.counterpartyUserId)
            assertEquals(type, publicCounterparty.avatarUrl, entity.counterpartyAvatarUrl)
            assertEquals(type, "verified", entity.counterpartyVerificationDesignation)
        }
    }

    @Test
    fun `already verified schema sixteen service rows remain eligible but are redacted when read`() {
        val stale = transaction(
            CounterpartyDto(
                id = USER_ID,
                name = "Kit institutional commission",
                avatarUrl = "https://pay.kit.africa/media/internal-wallet",
                verification = AccountVerificationDto(
                    "verified",
                    "2026-08-29T10:11:12Z",
                ),
            ),
        ).toEntity(WALLET_ID, "UGX", 0).copy(
            type = "bank_transfer",
            counterpartyName = "Kit institutional commission",
            counterpartyUserId = USER_ID,
            counterpartyAvatarUrl = "https://pay.kit.africa/media/internal-wallet",
            counterpartyVerificationDesignation = "verified",
            counterpartyVerificationSince = "2026-08-29T10:11:12Z",
            customerProjectionVerified = true,
        )

        listOf(
            "airtime",
            "bank_deposit",
            "bank_reversal",
            "bank_transfer",
            "bank_withdrawal",
            "bill_payment",
            "provider_reversal",
            "referral_reward",
            "referral_reward_reversal",
        ).forEach { type ->
            val cached = stale.copy(type = type)
            val presented = cached.toUiModel()

            assertTrue(type, cached.isCustomerVisibleWalletTransaction())
            assertEquals(type, "Kit Pay", presented.counterparty)
            assertNull(type, presented.counterpartyUserId)
            assertNull(type, presented.counterpartyAvatarUrl)
            assertNull(type, presented.accountVerification)
        }
    }

    @Test
    fun `untrusted photo and malformed verification do not survive transaction mapping`() {
        val entity = transaction(
            CounterpartyDto(
                id = USER_ID,
                name = "Official Support",
                avatarUrl = "https://attacker.example/track",
                verification = AccountVerificationDto("Official", "not-an-instant"),
            ),
        ).toEntity(WALLET_ID, "UGX", 0)

        assertNull(entity.counterpartyAvatarUrl)
        assertNull(entity.counterpartyVerificationDesignation)
        assertNull(entity.counterpartyVerificationSince)
        assertNull(entity.toUiModel().accountVerification)
    }

    @Test
    fun `customer total that disagrees with public amount fails closed`() {
        val transaction = transaction(
            CounterpartyDto(name = "Kit internal settlement"),
        ).copy(
            amount = "53000.00",
            totals = CustomerTransactionTotalsDto(
                added = "0.00",
                deducted = "56000.00",
            ),
            currency = CurrencyDto("UGX", "2"),
        )

        assertFalse(transaction.hasVerifiedCustomerProjection())
        assertThrows(IllegalArgumentException::class.java) {
            transaction.toEntity(WALLET_ID, "UGX", 0)
        }
    }

    @Test
    fun `transaction without authoritative totals cannot enter the customer cache`() {
        val transaction = transaction(CounterpartyDto(name = "Amina")).copy(totals = null)

        assertFalse(transaction.hasVerifiedCustomerProjection())
        assertThrows(IllegalArgumentException::class.java) {
            transaction.toEntity(WALLET_ID, "UGX", 0)
        }
    }

    @Test
    fun `customer projection is bound to the selected wallet and currency`() {
        val transaction = transaction(CounterpartyDto(name = "Amina"))

        assertTrue(transaction.hasVerifiedCustomerProjection(WALLET_ID, "UGX", 0))
        assertFalse(transaction.hasVerifiedCustomerProjection("wallet-other", "UGX", 0))
        assertFalse(transaction.hasVerifiedCustomerProjection(WALLET_ID, "USD", 0))
        assertFalse(transaction.hasVerifiedCustomerProjection(WALLET_ID, "UGX", 2))
        assertThrows(IllegalArgumentException::class.java) {
            transaction.copy(walletId = "wallet-other").toEntity(WALLET_ID, "UGX", 0)
        }
    }

    @Test
    fun `cached projection is bound again before customer display`() {
        val cached = transaction(CounterpartyDto(name = "Amina"))
            .toEntity(WALLET_ID, "UGX", 0)

        assertTrue(cached.isCustomerVisibleWalletTransaction(WALLET_ID, "UGX", 0))
        assertFalse(
            cached.copy(walletUuid = "wallet-other")
                .isCustomerVisibleWalletTransaction(WALLET_ID, "UGX", 0),
        )
        assertFalse(
            cached.copy(currencyCode = "USD")
                .isCustomerVisibleWalletTransaction(WALLET_ID, "UGX", 0),
        )
        assertFalse(
            cached.copy(currencyScale = 2)
                .isCustomerVisibleWalletTransaction(WALLET_ID, "UGX", 0),
        )
    }

    @Test
    fun `customer totals decode from the wallet history contract`() {
        val adapter = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
            .adapter(TransactionDto::class.java)
        val decoded = requireNotNull(
            adapter.fromJson(
                """
                {
                  "id":"transaction-privacy",
                  "wallet_id":"$WALLET_ID",
                  "reference":"KWB-CUSTOMER-TRANSFER",
                  "amount":"56000.00",
                  "totals":{"added":"0.00","deducted":"56000.00"},
                  "currency":{"code":"UGX","scale":"2"},
                  "type":"bank_transfer",
                  "direction":"debit",
                  "status":"completed",
                  "counterparty":null,
                  "occurred_at":"2026-08-31T12:00:00Z"
                }
                """.trimIndent(),
            ),
        )

        assertEquals("0.00", decoded.totals?.added)
        assertEquals("56000.00", decoded.totals?.deducted)
        assertEquals(-5_600_000L, decoded.toEntity(WALLET_ID, "UGX", 2).amountMinor)
    }

    private fun transaction(counterparty: CounterpartyDto) = TransactionDto(
        id = "transaction-1",
        walletId = WALLET_ID,
        reference = "KIT-TEST-1",
        amount = "1000",
        totals = CustomerTransactionTotalsDto(added = "0", deducted = "1000"),
        currency = CurrencyDto("UGX", "0"),
        type = "internal_transfer",
        direction = "debit",
        status = "completed",
        counterparty = counterparty,
        occurredAt = "2026-08-29T10:12:00Z",
    )

    private companion object {
        const val USER_ID = "550e8400-e29b-41d4-a716-446655440000"
        const val WALLET_ID = "019f8c6f-cc57-720c-9a55-000000000001"
    }
}
