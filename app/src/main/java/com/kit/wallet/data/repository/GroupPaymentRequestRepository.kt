package com.kit.wallet.data.repository

import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.ApiEnvelope
import com.kit.wallet.data.remote.ContributeGroupPaymentRequest
import com.kit.wallet.data.remote.CreateCollaborativeGroupPaymentRequest
import com.kit.wallet.data.remote.GroupPaymentRequestContributionDto
import com.kit.wallet.data.remote.GroupPaymentRequestContributionPageDto
import com.kit.wallet.data.remote.GroupPaymentRequestContributionResultDto
import com.kit.wallet.data.remote.GroupPaymentRequestDto
import com.kit.wallet.data.remote.GroupPaymentRequestStatus
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.KitWalletApiException
import com.kit.wallet.data.session.SessionFence
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

sealed interface GroupPaymentRequestContributionResolution {
    data class Confirmed(
        val result: GroupPaymentRequestContributionResultDto,
    ) : GroupPaymentRequestContributionResolution

    /** Exact state proves that this request can no longer accept the pending operation. */
    data class Reconciled(
        val request: GroupPaymentRequestDto,
    ) : GroupPaymentRequestContributionResolution
}

internal class DefinitiveFinancialMutationRejection(
    val rejection: KitWalletApiException,
) : Exception(rejection.message, rejection)

internal fun KitWalletApiException.isDefinitiveFinancialMutationRejection(): Boolean {
    if (connectivity) return false
    val status = statusCode ?: return false
    return status in 400..499 && status !in setOf(401, 403, 408, 425, 428, 429) &&
        !(status == 409 && code == "IDEMPOTENCY_REQUEST_IN_PROGRESS")
}

/** Money authority for collaborative requests; encrypted timeline descriptors never authorize. */
@Singleton
class GroupPaymentRequestRepository @Inject constructor(
    private val api: KitWalletApi,
    private val apiCalls: ApiCallExecutor,
    private val paymentAuthorizer: PaymentAuthorizer,
    private val walletSync: WalletSyncRepository,
) {
    suspend fun list(conversationId: String, status: GroupPaymentRequestStatus? = null): List<GroupPaymentRequestDto> {
        require(conversationId.isNotBlank())
        return apiCalls.execute { api.groupPaymentRequests(conversationId, status?.wire) }.items
            .onEach { requireValid(it, conversationId) }
    }

    suspend fun create(
        conversationId: String,
        request: CreateCollaborativeGroupPaymentRequest,
        idempotencyKey: () -> String,
        expectedOwner: SessionFence? = null,
    ): GroupPaymentRequestDto {
        val key = idempotencyKey().also(::requireRetryKey)
        val created = executeFinancialMutation {
            api.createGroupPaymentRequest(conversationId, key, request, expectedOwner)
        }
        return requireValid(created, conversationId).also { confirmed ->
            check(
                confirmed.destinationWalletId == request.destinationWalletId.lowercase() &&
                    confirmed.targetAmount == request.totalAmount &&
                    confirmed.note == request.note && confirmed.expiresAt == request.expiresAt,
            ) { "Kit did not confirm the exact group payment request" }
        }
    }

    suspend fun get(
        requestId: String,
        expectedOwner: SessionFence? = null,
    ): GroupPaymentRequestDto {
        val request = apiCalls.execute { api.groupPaymentRequest(requestId, expectedOwner) }
        check(request.id == requestId.lowercase()) { "The request response did not match the requested id" }
        return requireValid(request)
    }

    suspend fun contributions(
        requestId: String,
        before: String? = null,
        limit: Int = 50,
    ): GroupPaymentRequestContributionPageDto {
        require(limit in 1..100)
        before?.let { require(it == it.lowercase()) }
        val authority = get(requestId)
        val page = apiCalls.execute { api.groupPaymentRequestContributions(requestId, before, limit) }
        check(page.isStructurallyValid(authority.currencyScale ?: -1, limit)) {
            "Kit returned an invalid contribution page"
        }
        return page
    }

    /** Resolves a KITGREQ1 contribution outside the bounded embedded newest-50 window. */
    suspend fun exactContribution(
        requestId: String,
        contributionId: String,
    ): GroupPaymentRequestContributionDto {
        val authority = get(requestId)
        val row = apiCalls.execute {
            api.groupPaymentRequestContribution(requestId, contributionId)
        }
        check(row.id == contributionId.lowercase() && row.isStructurallyValid(authority.currencyScale ?: -1)) {
            "The contribution response did not match this event"
        }
        return row
    }

    suspend fun contribute(
        requestId: String,
        sourceWalletId: String,
        amount: String,
        idempotencyKey: () -> String,
        paymentPin: String,
        expectedOwner: SessionFence? = null,
    ): GroupPaymentRequestContributionResolution {
        val authority = get(requestId, expectedOwner)
        if (authority.knownStatus != GroupPaymentRequestStatus.OPEN || !authority.canContribute) {
            return GroupPaymentRequestContributionResolution.Reconciled(authority)
        }
        val scale = checkNotNull(authority.currencyScale)
        val requestedMinor = amount.toBigDecimalOrNull()?.takeIf {
            it.scale() <= scale && it.signum() > 0
        }?.movePointRight(scale)?.longValueExact()
            ?: error("Enter a contribution amount")
        if (requestedMinor > checkNotNull(authority.remainingMinor)) {
            return GroupPaymentRequestContributionResolution.Reconciled(authority)
        }
        val canonicalAmount = canonicalContribution(amount, authority)
        val stepUp = paymentAuthorizer.authorize(
            CONTRIBUTION_PURPOSE,
            linkedMapOf(
                "action" to "contribute",
                "group_payment_request_id" to authority.id,
                "source_wallet_id" to sourceWalletId.lowercase(),
                "amount" to canonicalAmount,
                "currency" to authority.currency.code,
            ),
            paymentPin,
            "Approve this group contribution",
            expectedOwner,
        )
        val key = idempotencyKey().also(::requireRetryKey)
        val result = executeFinancialMutation {
            api.contributeToGroupPaymentRequest(
                authority.id,
                key,
                stepUp,
                ContributeGroupPaymentRequest(sourceWalletId.lowercase(), canonicalAmount),
                expectedOwner,
            )
        }
        check(result.matchesContributionIntent(authority, canonicalAmount)) {
            "Kit did not confirm this contribution against the request"
        }
        // The validated POST response is authoritative. A secondary cache refresh must neither
        // be cancelled nor turn success into an ambiguous failure that invites another debit.
        withContext(NonCancellable) { runCatching { walletSync.refresh() } }
        return GroupPaymentRequestContributionResolution.Confirmed(result)
    }

    suspend fun cancel(
        requestId: String,
        idempotencyKey: String,
        expectedOwner: SessionFence? = null,
    ): GroupPaymentRequestDto {
        requireRetryKey(idempotencyKey)
        val authority = get(requestId, expectedOwner)
        if (authority.knownStatus == GroupPaymentRequestStatus.CANCELLED) return authority
        check(authority.knownStatus == GroupPaymentRequestStatus.OPEN && authority.canCancel) {
            "This request can no longer be cancelled"
        }
        val cancelled = apiCalls.execute {
            api.cancelGroupPaymentRequest(authority.id, idempotencyKey, expectedOwner)
        }
        check(cancelled.id == authority.id && cancelled.isStructurallyValid() &&
            cancelled.knownStatus == GroupPaymentRequestStatus.CANCELLED
        ) { "Kit did not confirm cancellation" }
        return cancelled
    }

    private fun canonicalContribution(input: String, request: GroupPaymentRequestDto): String {
        val scale = checkNotNull(request.currencyScale)
        val cleaned = input.trim().replace(",", "")
        val decimal = cleaned.toBigDecimalOrNull() ?: error("Enter a contribution amount")
        check(decimal.scale() <= scale && decimal.signum() > 0) { "Enter a contribution amount" }
        val canonical = decimal.setScale(scale).toPlainString()
        val minor = decimal.movePointRight(scale).longValueExact()
        check(minor <= checkNotNull(request.remainingMinor)) {
            "This contribution is more than the amount still needed."
        }
        return canonical
    }

    private fun requireValid(
        request: GroupPaymentRequestDto,
        conversationId: String? = null,
    ): GroupPaymentRequestDto {
        check(request.isStructurallyValid()) { "Kit returned an invalid group payment request" }
        check(conversationId == null || request.conversationId == conversationId.lowercase()) {
            "The request belongs to another conversation"
        }
        return request
    }

    private fun requireRetryKey(key: String) {
        require(key.length in 16..128 && key.matches(Regex("^[A-Za-z0-9._:-]+$"))) {
            "This operation has no valid retry key"
        }
    }

    private suspend fun <T> executeFinancialMutation(
        call: suspend () -> ApiEnvelope<T>,
    ): T = try {
        apiCalls.execute(call)
    } catch (error: KitWalletApiException) {
        if (error.isDefinitiveFinancialMutationRejection()) {
            throw DefinitiveFinancialMutationRejection(error)
        }
        throw error
    }

    companion object { const val CONTRIBUTION_PURPOSE = "group_payment_request_contribution" }
}

internal fun GroupPaymentRequestContributionResultDto.matchesContributionIntent(
    authority: GroupPaymentRequestDto,
    canonicalAmount: String,
): Boolean = isStructurallyValid() && request.id == authority.id &&
    request.conversationId == authority.conversationId &&
    request.requesterUserId == authority.requesterUserId &&
    request.destinationWalletId == authority.destinationWalletId &&
    request.targetAmountMinor == authority.targetAmountMinor && request.currency == authority.currency &&
    request.note == authority.note && contribution.isYours && contribution.amount == canonicalAmount
