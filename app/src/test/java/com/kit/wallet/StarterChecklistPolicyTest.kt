package com.kit.wallet

import com.kit.wallet.feature.home.StarterChecklistPolicy
import com.kit.wallet.ui.model.Transaction
import com.kit.wallet.ui.model.TxStatus
import com.kit.wallet.ui.model.TxType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The first-transaction policy is a money-truth gate: it decides when the app tells a new
 * user they have "made their first transaction". Every case here pins a way that claim
 * could be made falsely — incoming money, undone money, unsettled money, or a type this
 * build has never heard of — against the backend's own type/direction vocabulary.
 */
class StarterChecklistPolicyTest {

    // ---- what counts: the three deliberate outgoing behaviours, settled and non-zero ----

    @Test
    fun `settled outgoing transfer, bill payment and airtime count`() {
        assertTrue(counts(transaction(rawType = "internal_transfer")))
        assertTrue(counts(transaction(rawType = "bill_payment")))
        assertTrue(counts(transaction(rawType = "airtime")))
    }

    @Test
    fun `wire values are matched after trimming and case folding`() {
        assertTrue(counts(transaction(rawType = " Internal_Transfer ", rawDirection = " DEBIT ")))
    }

    // ---- incoming money can never be "your first transaction" ----

    @Test
    fun `credits never count, even for allowlisted types`() {
        // An incoming Kit → Kit transfer serializes with the same type word as an outgoing
        // one; only the ledger's direction word separates them.
        assertFalse(counts(transaction(rawType = "internal_transfer", rawDirection = "credit")))
        assertFalse(counts(transaction(rawType = "bank_funding_received", rawDirection = "credit")))
        assertFalse(counts(transaction(rawType = "provider_float_credit", rawDirection = "credit")))
    }

    @Test
    fun `a missing direction fails closed`() {
        assertFalse(counts(transaction(rawDirection = null)))
        assertFalse(counts(transaction(rawDirection = "  ")))
    }

    // ---- types outside the allowlist prove nothing, however they are shaped ----

    @Test
    fun `bank movements, merchant flows, requests and unknown types never count`() {
        for (type in listOf(
            "bank_deposit",
            "merchant_payment",
            "merchant_escrow_release",
            "payment_request",
            "crypto_transfer", // invented after this build shipped
        )) {
            assertFalse("$type must not count", counts(transaction(rawType = type)))
        }
        assertFalse(counts(transaction(rawType = null)))
        assertFalse(counts(transaction(rawType = "")))
    }

    @Test
    fun `reversals, refunds and chargebacks never count`() {
        for (type in listOf(
            "internal_transfer_reversal",
            "provider_reversal",
            "merchant_refund",
            "chargeback",
        )) {
            assertFalse("$type must not count", counts(transaction(rawType = type)))
            assertTrue("$type must read as an undo", StarterChecklistPolicy.isReversalOrRefund(type))
        }
        assertFalse(StarterChecklistPolicy.isReversalOrRefund("internal_transfer"))
        assertFalse(StarterChecklistPolicy.isReversalOrRefund(null))
    }

    // ---- unsettled or empty movements prove nothing ----

    @Test
    fun `pending and failed rows never count`() {
        assertFalse(counts(transaction(status = TxStatus.PENDING)))
        assertFalse(counts(transaction(status = TxStatus.FAILED)))
    }

    @Test
    fun `zero amounts never count`() {
        assertFalse(counts(transaction(amountMinor = 0L)))
    }

    // ---- the display mapping is never the input ----

    @Test
    fun `a SEND-looking display row without raw wire values proves nothing`() {
        // Legacy cached rows mapped before raw values were stored: display says SEND and
        // COMPLETED, but without the ledger's own words the policy refuses to guess.
        assertFalse(
            counts(
                transaction(rawType = null, rawDirection = null).copy(type = TxType.SEND),
            ),
        )
    }

    // ---- the account-switch fence for milestone evidence ----

    @Test
    fun `evidence qualifies only when it names the exact signed-in account`() {
        assertTrue(qualifies("account-a", "account-a"))
        assertTrue(qualifies("Account-A", "account-a")) // ids compare case-insensitively
        assertFalse(qualifies("account-a", "account-b"))
        assertFalse(qualifies(null, "account-a"))
        assertFalse(qualifies("  ", "account-a"))
        assertFalse(qualifies("account-a", null))
        assertFalse(qualifies("account-a", ""))
        assertFalse(qualifies(null, null))
    }

    @Test
    fun `matching ownership cannot upgrade evidence that does not qualify`() {
        assertFalse(
            StarterChecklistPolicy.ownedEvidenceQualifies(
                evidenceOwnerAccountId = "account-a",
                currentAccountId = "account-a",
                qualifies = false,
            ),
        )
    }

    private fun counts(transaction: Transaction): Boolean =
        StarterChecklistPolicy.countsAsFirstTransaction(transaction)

    private fun qualifies(owner: String?, current: String?): Boolean =
        StarterChecklistPolicy.ownedEvidenceQualifies(
            evidenceOwnerAccountId = owner,
            currentAccountId = current,
            qualifies = true,
        )

    private fun transaction(
        rawType: String? = "internal_transfer",
        rawDirection: String? = "debit",
        status: TxStatus = TxStatus.COMPLETED,
        amountMinor: Long = -25_000L,
    ): Transaction = Transaction(
        id = "tx-1",
        counterparty = "Amina Yusuf",
        note = null,
        amountMinor = amountMinor,
        time = "12:00",
        dateGroup = "Today",
        type = TxType.SEND,
        status = status,
        reference = "REF-1",
        rawType = rawType,
        rawDirection = rawDirection,
    )
}
