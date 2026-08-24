package com.kit.wallet.data.repository

import com.kit.wallet.data.messaging.KitPaymentMessage

/** Canonical form shared by the reverse challenge intent and the eventual resolution body. */
internal fun canonicalTransferClaimReason(reason: String?): String? =
    reason?.trim()
        ?.takeIf(String::isNotBlank)
        ?.take(KitPaymentMessage.MAX_REASON_LENGTH)

/** Exact backend contract for an intent-bound sender reversal. */
internal fun transferClaimReverseIntent(
    claimId: String,
    reason: String?,
): LinkedHashMap<String, Any?> = linkedMapOf(
    "action" to "reverse",
    "claim_id" to claimId,
    "reason" to canonicalTransferClaimReason(reason),
)
