package com.kit.wallet.data.repository

import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.ContributeGroupPaymentRequest
import com.kit.wallet.data.remote.CreateCollaborativeGroupPaymentRequest
import com.kit.wallet.data.remote.GroupPaymentRequestContributionDto
import com.kit.wallet.data.remote.GroupPaymentRequestContributionPageDto
import com.kit.wallet.data.remote.GroupPaymentRequestContributionResultDto
import com.kit.wallet.data.remote.GroupPaymentRequestDto
import com.kit.wallet.data.remote.GroupPaymentRequestStatus
import com.kit.wallet.data.remote.KitWalletApi
import javax.inject.Inject
import javax.inject.Singleton

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
        idempotencyKey: String,
    ): GroupPaymentRequestDto {
        requireRetryKey(idempotencyKey)
        val created = apiCalls.execute {
            api.createGroupPaymentRequest(conversationId, idempotencyKey, request)
        }
        return requireValid(created, conversationId)
    }

    suspend fun get(requestId: String): GroupPaymentRequestDto {
        val request = apiCalls.execute { api.groupPaymentRequest(requestId) }
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
        idempotencyKey: String,
        paymentPin: String,
    ): GroupPaymentRequestContributionResultDto {
        requireRetryKey(idempotencyKey)
        val authority = get(requestId)
        check(authority.knownStatus == GroupPaymentRequestStatus.OPEN && authority.canContribute) {
            "This request is no longer open for contributions"
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
        )
        val result = apiCalls.execute {
            api.contributeToGroupPaymentRequest(
                authority.id,
                idempotencyKey,
                stepUp,
                ContributeGroupPaymentRequest(sourceWalletId.lowercase(), canonicalAmount),
            )
        }
        check(result.isStructurallyValid() && result.request.id == authority.id) {
            "Kit did not confirm this contribution against the request"
        }
        walletSync.refresh()
        return result
    }

    suspend fun cancel(requestId: String, idempotencyKey: String): GroupPaymentRequestDto {
        requireRetryKey(idempotencyKey)
        val authority = get(requestId)
        check(authority.knownStatus == GroupPaymentRequestStatus.OPEN && authority.canCancel) {
            "This request can no longer be cancelled"
        }
        val cancelled = apiCalls.execute { api.cancelGroupPaymentRequest(authority.id, idempotencyKey) }
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

    companion object { const val CONTRIBUTION_PURPOSE = "group_payment_request_contribution" }
}
