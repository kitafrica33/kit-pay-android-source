package com.kit.wallet.feature.home

import com.kit.wallet.data.repository.KycStatus
import com.kit.wallet.data.repository.KycVerificationState
import com.kit.wallet.data.repository.kycVerificationStateOf
import com.kit.wallet.ui.model.Transaction
import com.kit.wallet.ui.model.TxStatus

/** The three first steps a new account is walked through, in the order they are shown. */
internal enum class StarterStep(val title: String) {
    VERIFY_IDENTITY("Verify identity"),
    SEND_FIRST_MESSAGE("Send first message"),
    MAKE_FIRST_TRANSACTION("Make first transaction"),
}

/**
 * What the home starter checklist knows, derived only from real account state — never a
 * demo or manually-set flag. Every input fails closed: state that has not loaded, or
 * cannot be read, leaves its step incomplete rather than falsely done. And because the
 * checklist re-derives from live state, an identity that later regresses brings the
 * checklist itself back — there is no separate prompt to keep in sync with it.
 */
internal data class StarterChecklist(
    val identityState: KycVerificationState,
    val firstMessageSent: Boolean,
    val firstTransactionMade: Boolean,
) {
    val identityVerified: Boolean = identityState == KycVerificationState.VERIFIED

    val completedCount: Int =
        listOf(identityVerified, firstMessageSent, firstTransactionMade).count { it }

    /** Once everything is done the checklist has nothing left to say, so it says nothing. */
    val allComplete: Boolean = completedCount == StarterStep.entries.size

    fun completed(step: StarterStep): Boolean = when (step) {
        StarterStep.VERIFY_IDENTITY -> identityVerified
        StarterStep.SEND_FIRST_MESSAGE -> firstMessageSent
        StarterStep.MAKE_FIRST_TRANSACTION -> firstTransactionMade
    }
}

internal object StarterChecklistPolicy {
    /**
     * @param liveKyc the same authoritative status the verification screen refreshes; its
     *   account standing is the answer to "is this person verified?".
     * @param profileKycLabel the profile row's label, used only as the offline fallback
     *   before the live status has ever been fetched — alone it goes stale.
     * @param hasSentMessage whether the durable chat store holds content this account
     *   authored that really left the device — `ChatRepository.sentMessageEvidence`,
     *   already owner-checked by the caller through [ownedEvidenceQualifies].
     * @param firstTransactionMade live evidence ([countsAsFirstTransaction] over the
     *   synced cache) or the recorded account milestone — the caller combines the two,
     *   because the cache alone is neither account-wide nor logout-proof.
     */
    fun checklist(
        liveKyc: KycStatus?,
        profileKycLabel: String?,
        hasSentMessage: Boolean,
        firstTransactionMade: Boolean,
    ): StarterChecklist = StarterChecklist(
        identityState = identityState(liveKyc, profileKycLabel),
        firstMessageSent = hasSentMessage,
        firstTransactionMade = firstTransactionMade,
    )

    /** The live repository first — the source the KYC screen refreshes — profile second. */
    fun identityState(
        liveKyc: KycStatus?,
        profileKycLabel: String?,
    ): KycVerificationState =
        liveKyc?.accountState ?: kycVerificationStateOf(profileKycLabel)

    /**
     * The account-switch fence for milestone evidence. Caches outlive the session that
     * filled them by a beat, so a switch from A to B can emit A's rows beside B's session;
     * evidence therefore qualifies only when it names its owner and that owner is exactly
     * the account signed in at the moment of the decision. Unowned evidence proves nothing.
     */
    fun ownedEvidenceQualifies(
        evidenceOwnerAccountId: String?,
        currentAccountId: String?,
        qualifies: Boolean,
    ): Boolean =
        qualifies &&
            !evidenceOwnerAccountId.isNullOrBlank() &&
            !currentAccountId.isNullOrBlank() &&
            evidenceOwnerAccountId.equals(currentAccountId, ignoreCase = true)

    /**
     * A first transaction is money the user deliberately sent out: a settled Kit → Kit
     * transfer, bill payment or airtime purchase, named by the backend's own type word and
     * leaving the wallet by the backend's own direction word. Everything else fails closed —
     * incoming credits, bank deposits and top-ups, payment requests (which move nothing by
     * themselves), refunds/reversals/chargebacks (money moving *back*), zero amounts,
     * pending or failed rows, and any type this build has never heard of. The raw wire
     * values are the inputs, never the display mapping, which collapses kinds this policy
     * must tell apart.
     */
    fun countsAsFirstTransaction(transaction: Transaction): Boolean =
        transaction.rawDirection?.trim()?.lowercase() == OUTGOING_DIRECTION &&
            transaction.rawType?.trim()?.lowercase() in FIRST_TRANSACTION_TYPES &&
            transaction.status == TxStatus.COMPLETED &&
            transaction.amountMinor != 0L &&
            !isReversalOrRefund(transaction.rawType)

    /** The ledger's word for money leaving this wallet. Anything else is not spending. */
    private const val OUTGOING_DIRECTION = "debit"

    /**
     * The backend type words for the three behaviours that count: send money, pay a bill,
     * buy airtime. An explicit allowlist, so merchant/provider/bank/claim types — and any
     * type invented after this build shipped — prove nothing instead of quietly counting.
     */
    private val FIRST_TRANSACTION_TYPES = setOf(
        "internal_transfer",
        "bill_payment",
        "airtime",
    )

    /**
     * Whether the backend's type word names an undo of money movement rather than a
     * movement. The exact-match allowlist above already excludes every such type; this
     * check stays as an independent guard so a type carelessly added to the allowlist
     * later can still never be a reversal, refund or chargeback.
     */
    fun isReversalOrRefund(rawType: String?): Boolean {
        val normalized = rawType?.trim()?.lowercase() ?: return false
        return REVERSAL_TOKENS.any { it in normalized }
    }

    private val REVERSAL_TOKENS =
        listOf("reversal", "reversed", "refund", "chargeback")

    /** Concise progress for the section header, e.g. "1 of 3 done". */
    fun progressLabel(checklist: StarterChecklist): String =
        "${checklist.completedCount} of ${StarterStep.entries.size} done"
}
