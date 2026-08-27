package com.kit.wallet.data.repository

import com.kit.wallet.data.mapper.DecimalMoney
import com.kit.wallet.data.messaging.GroupPaymentAudience
import com.kit.wallet.data.messaging.GroupPaymentSplitMode
import com.kit.wallet.data.messaging.KitGroupPaymentMessage
import com.kit.wallet.data.remote.CreateGroupPaymentRecipient
import com.kit.wallet.data.remote.CreateGroupPaymentRequest
import com.kit.wallet.ui.model.Money

/**
 * Everything the composer must settle before it asks anybody to approve a payment.
 *
 * The server checks all of this again and is the authority. The point of repeating it here is that
 * a sender should never be asked for their PIN or their fingerprint to approve a send that was
 * always going to be refused. Rule for rule the same as iOS `GroupPaymentDraftPolicy`.
 */
internal object GroupPaymentDraftPolicy {
    /** Matches the server's own ceiling on how many members one payment may reach. */
    const val MAX_RECIPIENTS = 50

    data class Member(val userId: String, val name: String)

    sealed interface Outcome {
        data class Ready(val request: CreateGroupPaymentRequest) : Outcome

        /**
         * Copy to show under the composer. Never phrased as an error the sender caused when it is
         * really a limit of the currency or of their balance.
         */
        data class Problem(val message: String) : Outcome
    }

    fun draft(
        sourceWalletId: String,
        splitMode: GroupPaymentSplitMode,
        audience: GroupPaymentAudience,
        selected: List<Member>,
        totalInput: String,
        customAmounts: Map<String, String>,
        note: String?,
        scale: Int,
        availableBalanceMinor: Long,
    ): Outcome {
        if (selected.isEmpty()) return Outcome.Problem("Choose at least one member to pay.")
        if (selected.size > MAX_RECIPIENTS) {
            return Outcome.Problem(
                "A group payment can go to at most $MAX_RECIPIENTS members at a time.",
            )
        }
        if (splitMode != GroupPaymentSplitMode.EVEN && audience != GroupPaymentAudience.SELECTED) {
            // Writing an amount for each member is itself the act of choosing them.
            return Outcome.Problem(
                "To give each member a different amount, choose the members you are paying.",
            )
        }

        return when (splitMode) {
            GroupPaymentSplitMode.EVEN -> {
                val total = Money.parseMinor(totalInput, scale)
                if (total == null || total <= 0) {
                    return Outcome.Problem("Enter the amount you are sending.")
                }
                if (total < selected.size) {
                    return Outcome.Problem(
                        "That amount is too small to divide between ${selected.size} members. " +
                            "Each one has to receive at least the smallest unit of the currency.",
                    )
                }
                if (total > availableBalanceMinor) {
                    return Outcome.Problem("Your wallet does not have that much available.")
                }
                Outcome.Ready(
                    CreateGroupPaymentRequest(
                        sourceWalletId = sourceWalletId,
                        splitMode = splitMode.wire,
                        audience = audience.wire,
                        totalAmount = DecimalMoney.fromMinor(total, scale),
                        note = trimmedNote(note),
                        // "Everyone" is left for the server to resolve: the roster it holds at the
                        // moment of sending is the true one, not whatever this device last synced.
                        recipients = if (audience == GroupPaymentAudience.ALL) {
                            null
                        } else {
                            selected.map { CreateGroupPaymentRecipient(it.userId, null) }
                        },
                    ),
                )
            }

            GroupPaymentSplitMode.CUSTOM -> {
                val entries = mutableListOf<CreateGroupPaymentRecipient>()
                var total = 0L
                for (member in selected) {
                    val minor = customAmounts[member.userId]
                        ?.let { Money.parseMinor(it, scale) }
                        ?.takeIf { it > 0 }
                        ?: return Outcome.Problem("Enter an amount for every member you are paying.")
                    total += minor
                    entries += CreateGroupPaymentRecipient(
                        userId = member.userId,
                        amount = DecimalMoney.fromMinor(minor, scale),
                    )
                }
                if (total > availableBalanceMinor) {
                    return Outcome.Problem("Your wallet does not have that much available.")
                }
                Outcome.Ready(
                    CreateGroupPaymentRequest(
                        sourceWalletId = sourceWalletId,
                        splitMode = splitMode.wire,
                        audience = audience.wire,
                        totalAmount = null,
                        note = trimmedNote(note),
                        recipients = entries,
                    ),
                )
            }
        }
    }

    /** What the sender is about to spend, for the review line above the approval control. */
    fun totalMinor(
        splitMode: GroupPaymentSplitMode,
        selected: List<Member>,
        totalInput: String,
        customAmounts: Map<String, String>,
        scale: Int,
    ): Long? = when (splitMode) {
        GroupPaymentSplitMode.EVEN -> Money.parseMinor(totalInput, scale)
        GroupPaymentSplitMode.CUSTOM -> {
            var total = 0L
            var readable = true
            for (member in selected) {
                val minor = customAmounts[member.userId]?.let { Money.parseMinor(it, scale) }
                if (minor == null) {
                    readable = false
                    break
                }
                total += minor
            }
            total.takeIf { readable }
        }
    }

    private fun trimmedNote(note: String?): String? = note
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.take(KitGroupPaymentMessage.MAX_NOTE_LENGTH)
}

/**
 * The intents the server hashes into a step-up challenge.
 *
 * The whole recipient list is flattened into one `id:amount,id:amount` string, so approving
 * "5,000 split between three people" cannot be replayed as "5,000 each", and so the hash does not
 * depend on how either platform's JSON encoder orders keys or renders arrays. The server validates
 * both fields to exclude the separators before it hashes anything.
 */
internal object GroupPaymentStepUpPolicy {
    const val SEND_PURPOSE = "group_payment"
    const val REVERSE_PURPOSE = "group_payment_reverse"

    fun sendIntent(
        request: CreateGroupPaymentRequest,
        conversationId: String,
    ): Map<String, Any?> = mapOf(
        "conversation_id" to conversationId,
        "source_wallet_id" to request.sourceWalletId,
        "split_mode" to request.splitMode,
        "audience" to request.audience,
        "total_amount" to request.totalAmount,
        "note" to request.note,
        "recipients" to request.recipients.orEmpty()
            .joinToString(",") { "${it.userId}:${it.amount ?: ""}" }
            .takeIf(String::isNotEmpty),
    )

    fun reverseIntent(groupPaymentId: String, reason: String?): Map<String, Any?> = mapOf(
        "group_payment_id" to groupPaymentId,
        "reason" to reason,
    )
}
