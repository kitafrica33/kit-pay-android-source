package com.kit.wallet

import com.kit.wallet.data.mapper.toEntity
import com.kit.wallet.data.mapper.toUiModel
import com.kit.wallet.data.remote.AccountVerificationDto
import com.kit.wallet.data.remote.CounterpartyDto
import com.kit.wallet.data.remote.CurrencyDto
import com.kit.wallet.data.remote.TransactionDto
import com.kit.wallet.ui.model.AccountVerificationDesignation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        ).toEntity(WALLET_ID)
        val presented = entity.toUiModel()

        assertEquals(USER_ID, entity.counterpartyUserId)
        assertEquals("https://pay.kit.africa/media/amina", entity.counterpartyAvatarUrl)
        assertEquals("verified", entity.counterpartyVerificationDesignation)
        assertEquals(USER_ID, presented.counterpartyUserId)
        assertEquals(entity.counterpartyAvatarUrl, presented.counterpartyAvatarUrl)
        assertEquals(
            AccountVerificationDesignation.VERIFIED,
            presented.accountVerification?.designation,
        )
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
        ).toEntity(WALLET_ID)

        assertNull(entity.counterpartyAvatarUrl)
        assertNull(entity.counterpartyVerificationDesignation)
        assertNull(entity.counterpartyVerificationSince)
        assertNull(entity.toUiModel().accountVerification)
    }

    private fun transaction(counterparty: CounterpartyDto) = TransactionDto(
        id = "transaction-1",
        walletId = WALLET_ID,
        reference = "KIT-TEST-1",
        amount = "1000",
        currency = CurrencyDto("UGX", "0"),
        type = "transfer",
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
