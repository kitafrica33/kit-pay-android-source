package com.kit.wallet.data.repository

import com.kit.wallet.data.mapper.toUiModel
import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.CreateGroupPaymentRequest
import com.kit.wallet.data.remote.GroupPaymentResolutionRequest
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.ui.model.GroupPaymentShareStatus
import com.kit.wallet.ui.model.GroupPaymentSummary
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Server-authoritative group payments.
 *
 * The announcement in a thread is a label, not a source of truth: what a member may do and what
 * they are owed is re-read from the payment itself immediately before every action. Note what is
 * missing — there is no settle-by-claim-id here. Money sent into a group is answered through the
 * group, so accepting and declining go to the group payment's own endpoints and never appear in the
 * one-to-one transfers inbox.
 *
 * Every call that moves money re-reads the result and refuses to report success unless the server
 * came back with the state that was asked for; a settle that "worked" but did not change anything
 * would leave a card offering the same button over money that is already gone.
 */
@Singleton
class GroupPaymentRepository @Inject constructor(
    private val api: KitWalletApi,
    private val apiCalls: ApiCallExecutor,
    private val paymentAuthorizer: PaymentAuthorizer,
    private val walletSync: WalletSyncRepository,
) {
    suspend fun groupPayment(groupPaymentId: String): GroupPaymentSummary {
        require(groupPaymentId.isNotBlank()) { "This payment has no id to verify" }
        val payment = apiCalls.execute { api.groupPayment(groupPaymentId) }.toUiModel()
            ?: error("The group payment state could not be read")
        check(payment.id.equals(groupPaymentId, ignoreCase = true)) {
            "The group payment state did not match this announcement"
        }
        return payment
    }

    /**
     * Sends one payment into the conversation.
     *
     * The step-up approval covers the whole send — the split, the members and their amounts — so it
     * cannot be replayed against a different one. The idempotency key belongs to the composer and
     * stays the same across retries, so a timeout that actually succeeded cannot pay twice.
     */
    suspend fun send(
        conversationId: String,
        request: CreateGroupPaymentRequest,
        idempotencyKey: String,
        paymentPin: String,
    ): GroupPaymentSummary {
        require(conversationId.isNotBlank()) { "This conversation cannot receive a payment" }
        require(idempotencyKey.isNotBlank()) { "This payment has no retry key" }
        val stepUpToken = paymentAuthorizer.authorize(
            GroupPaymentStepUpPolicy.SEND_PURPOSE,
            GroupPaymentStepUpPolicy.sendIntent(request, conversationId),
            paymentPin,
            "Approve this group payment",
        )
        val payment = apiCalls.execute {
            api.createGroupPayment(conversationId, idempotencyKey, stepUpToken, request)
        }.toUiModel() ?: error("The group payment was sent, but its state could not be read")
        walletSync.refresh()
        return payment
    }

    /** Takes your own share. Never a step-up: this releases money already held for you. */
    suspend fun acceptShare(groupPaymentId: String): GroupPaymentSummary =
        settleShare(groupPaymentId, GroupPaymentShareStatus.ACCEPTED) {
            apiCalls.execute { api.acceptGroupPaymentShare(groupPaymentId) }
        }

    /** Turns down your own share; it goes back to the sender, and nobody else's moves. */
    suspend fun rejectShare(groupPaymentId: String, reason: String?): GroupPaymentSummary =
        settleShare(groupPaymentId, GroupPaymentShareStatus.REJECTED) {
            apiCalls.execute {
                api.rejectGroupPaymentShare(
                    groupPaymentId,
                    GroupPaymentResolutionRequest(reason.orNullIfBlank()),
                )
            }
        }

    /**
     * The sender pulls back every share nobody has taken, in one approved move. Shares already
     * taken are untouched, which is why success is "nothing is pending" and not "everything came
     * back".
     */
    suspend fun reverseUnclaimed(
        groupPaymentId: String,
        reason: String?,
        paymentPin: String,
    ): GroupPaymentSummary {
        require(paymentPin.isEmpty() || paymentPin.matches(Regex("^[0-9]{4}$"))) {
            "Enter the four-digit wallet PIN"
        }
        val canonicalReason = reason.orNullIfBlank()
        val payment = groupPayment(groupPaymentId)
        check(payment.canReverseUnclaimed && payment.pendingCount > 0) {
            "There is nothing left to return on this payment."
        }
        val stepUpToken = paymentAuthorizer.authorize(
            GroupPaymentStepUpPolicy.REVERSE_PURPOSE,
            GroupPaymentStepUpPolicy.reverseIntent(payment.id, canonicalReason),
            paymentPin,
            "Approve returning the unclaimed shares",
        )
        val resolved = apiCalls.execute {
            api.reverseUnclaimedGroupPayment(
                payment.id,
                stepUpToken,
                GroupPaymentResolutionRequest(canonicalReason),
            )
        }.toUiModel() ?: error("The shares were returned, but the payment state could not be read")
        walletSync.refresh()
        check(resolved.id.equals(payment.id, ignoreCase = true) && resolved.pendingCount == 0) {
            "Kit did not confirm returning the unclaimed shares. Refresh and try again."
        }
        return resolved
    }

    /**
     * Re-reads the payment, checks this account really may act, settles, and checks the server
     * agrees about the outcome. The balance is stale the instant a share settles, so the wallet is
     * refreshed before the caller reads it.
     */
    private suspend fun settleShare(
        groupPaymentId: String,
        expected: GroupPaymentShareStatus,
        operation: suspend () -> com.kit.wallet.data.remote.GroupPaymentDto,
    ): GroupPaymentSummary {
        val payment = groupPayment(groupPaymentId)
        val share = payment.yourShare
        val allowed = when (expected) {
            GroupPaymentShareStatus.ACCEPTED -> share?.canAccept == true
            GroupPaymentShareStatus.REJECTED -> share?.canReject == true
            else -> false
        }
        check(share?.status == GroupPaymentShareStatus.PENDING && allowed) {
            "Your share is not waiting anymore. Refresh to see its latest state."
        }
        val resolved = operation().toUiModel()
            ?: error("Your share was settled, but its new state could not be read")
        walletSync.refresh()
        check(
            resolved.id.equals(payment.id, ignoreCase = true) &&
                resolved.yourShare?.status == expected,
        ) {
            "Kit did not confirm your decision. Refresh and try again."
        }
        return resolved
    }

    private fun String?.orNullIfBlank(): String? = this?.trim()?.takeIf(String::isNotBlank)
}
