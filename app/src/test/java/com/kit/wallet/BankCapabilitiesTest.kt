package com.kit.wallet

import com.kit.wallet.data.mapper.toBankInstitution
import com.kit.wallet.data.remote.BankDto
import com.kit.wallet.ui.model.BankCapability
import com.kit.wallet.ui.model.BankInstitution
import com.kit.wallet.ui.model.BankOperationKind
import com.kit.wallet.ui.model.Beneficiary
import com.kit.wallet.ui.model.eligibleBankBeneficiaries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BankCapabilitiesTest {
    @Test
    fun `bank API mapping preserves each action capability`() {
        val bank = BankDto(
            id = "bank-1",
            code = "UG-001",
            name = "Ruka Bank",
            countryCode = "UG",
            currency = "UGX",
            capabilities = mapOf(
                BankCapability.ACCOUNT_VERIFICATION to true,
                BankCapability.DEPOSITS to false,
                BankCapability.WITHDRAWALS to true,
                BankCapability.TRANSFERS to true,
            ),
        ).toBankInstitution()

        assertTrue(bank.supports(BankCapability.ACCOUNT_VERIFICATION))
        assertFalse(bank.supports(BankCapability.DEPOSITS))
        assertTrue(bank.supports(BankCapability.WITHDRAWALS))
        assertTrue(bank.supports(BankCapability.TRANSFERS))
    }

    @Test
    fun `missing and unknown bank capabilities fail closed`() {
        val bank = BankInstitution("bank-1", "Unknown Bank", "UGX")

        assertFalse(bank.supports(BankCapability.DEPOSITS))
        assertFalse(bank.supports("future_action"))
    }

    @Test
    fun `operation eligibility follows the beneficiary bank and ownership`() {
        val banks = listOf(
            bank(
                id = "rukapay-bank",
                deposits = false,
                withdrawals = true,
                transfers = true,
            ),
            bank(
                id = "collection-bank",
                deposits = true,
                withdrawals = false,
                transfers = false,
            ),
        )
        val beneficiaries = listOf(
            beneficiary("own-rukapay", "rukapay-bank", kind = "own"),
            beneficiary("third-rukapay", "rukapay-bank", kind = "third_party"),
            beneficiary("own-collection", "collection-bank", kind = "own"),
            beneficiary("unverified", "collection-bank", kind = "own", verified = false),
            beneficiary("missing-bank", "missing", kind = "own"),
        )

        assertEquals(
            listOf("own-collection"),
            eligibleBankBeneficiaries(BankOperationKind.DEPOSIT, banks, beneficiaries)
                .map(Beneficiary::id),
        )
        assertEquals(
            listOf("own-rukapay"),
            eligibleBankBeneficiaries(BankOperationKind.WITHDRAWAL, banks, beneficiaries)
                .map(Beneficiary::id),
        )
        assertEquals(
            listOf("own-rukapay", "third-rukapay"),
            eligibleBankBeneficiaries(BankOperationKind.TRANSFER, banks, beneficiaries)
                .map(Beneficiary::id),
        )
    }

    @Test
    fun `only known bank operation wire values are accepted`() {
        assertEquals(BankOperationKind.DEPOSIT, BankOperationKind.fromApiType("deposit"))
        assertEquals(BankOperationKind.WITHDRAWAL, BankOperationKind.fromApiType("withdrawal"))
        assertEquals(BankOperationKind.TRANSFER, BankOperationKind.fromApiType("bank_transfer"))
        assertEquals(null, BankOperationKind.fromApiType("future_operation"))
    }

    private fun bank(
        id: String,
        deposits: Boolean,
        withdrawals: Boolean,
        transfers: Boolean,
    ) = BankInstitution(
        id = id,
        name = id,
        currency = "UGX",
        capabilities = mapOf(
            BankCapability.ACCOUNT_VERIFICATION to true,
            BankCapability.DEPOSITS to deposits,
            BankCapability.WITHDRAWALS to withdrawals,
            BankCapability.TRANSFERS to transfers,
        ),
    )

    private fun beneficiary(
        id: String,
        bankId: String,
        kind: String,
        verified: Boolean = true,
    ) = Beneficiary(
        id = id,
        name = id,
        bank = bankId,
        accountMasked = "•••• 1234",
        verified = verified,
        kind = kind,
        bankId = bankId,
    )
}
