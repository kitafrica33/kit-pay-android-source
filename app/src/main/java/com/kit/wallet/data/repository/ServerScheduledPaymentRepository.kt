package com.kit.wallet.data.repository

import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.CreateScheduledGroupPaymentRequest
import com.kit.wallet.data.remote.CreateScheduledPaymentRequest
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.PreviewScheduledGroupPaymentRequest
import com.kit.wallet.data.remote.ScheduledGroupPaymentDto
import com.kit.wallet.data.remote.ScheduledGroupPaymentPageDto
import com.kit.wallet.data.remote.ScheduledGroupPaymentPlanDto
import com.kit.wallet.data.remote.ScheduledPaymentDto
import com.kit.wallet.data.remote.ScheduledPaymentPageDto
import com.kit.wallet.data.remote.ScheduledPaymentStatus
import javax.inject.Inject
import javax.inject.Singleton

/** Backend-owned money schedules. Scheduled E2EE chat text remains a separate local outbox. */
@Singleton
class ServerScheduledPaymentRepository @Inject constructor(
    private val api: KitWalletApi,
    private val apiCalls: ApiCallExecutor,
    private val paymentAuthorizer: PaymentAuthorizer,
    private val walletSync: WalletSyncRepository,
) {
    suspend fun directPage(
        conversationId: String?,
        status: ScheduledPaymentStatus,
        before: String? = null,
        limit: Int = 50,
    ): ScheduledPaymentPageDto {
        require(limit in 1..100)
        val page = apiCalls.execute {
            api.scheduledPayments(conversationId, status.wire, before, limit)
        }
        check(page.isStructurallyValid(limit) && page.items.all { it.knownStatus == status }) {
            "Kit returned an invalid scheduled-payment page"
        }
        if (conversationId != null) check(page.items.all { it.conversationId == conversationId.lowercase() }) {
            "A scheduled payment belongs to another conversation"
        }
        return page
    }

    suspend fun direct(id: String): ScheduledPaymentDto {
        val payment = apiCalls.execute { api.scheduledPayment(id) }
        check(payment.id == id.lowercase() && payment.isStructurallyValid()) {
            "Kit returned an invalid scheduled payment"
        }
        return payment
    }

    suspend fun createDirect(
        request: CreateScheduledPaymentRequest,
        currencyCode: String,
        idempotencyKey: String,
        paymentPin: String,
    ): ScheduledPaymentDto {
        requireRetryKey(idempotencyKey)
        val intent = linkedMapOf<String, Any?>(
            "action" to "create",
            "source_wallet_id" to request.sourceWalletId,
            "destination_wallet_id" to request.destinationWalletId,
            "amount" to request.amount,
            "currency" to currencyCode,
            "note" to request.note,
            "scheduled_for" to request.scheduledFor,
        ).apply { request.conversationId?.let { put("conversation_id", it) } }
        val stepUp = paymentAuthorizer.authorize(
            DIRECT_PURPOSE, intent, paymentPin, "Approve this scheduled payment",
        )
        val created = apiCalls.execute {
            api.createScheduledPayment(idempotencyKey, stepUp, request)
        }
        check(created.isStructurallyValid() && created.knownStatus == ScheduledPaymentStatus.SCHEDULED &&
            created.sourceWalletId == request.sourceWalletId &&
            created.destinationWalletId == request.destinationWalletId && created.amount == request.amount &&
            created.currency.code == currencyCode && created.note == request.note &&
            created.scheduledFor == request.scheduledFor && created.conversationId == request.conversationId
        ) { "Kit did not confirm the exact scheduled payment" }
        return created
    }

    suspend fun cancelDirect(id: String, idempotencyKey: String): ScheduledPaymentDto {
        requireRetryKey(idempotencyKey)
        check(direct(id).knownStatus == ScheduledPaymentStatus.SCHEDULED) {
            "This payment can no longer be cancelled"
        }
        return apiCalls.execute { api.cancelScheduledPayment(id, idempotencyKey) }.also {
            check(it.id == id.lowercase() && it.isStructurallyValid() &&
                it.knownStatus == ScheduledPaymentStatus.CANCELLED
            ) { "Kit did not confirm cancellation" }
        }
    }

    suspend fun previewGroup(
        conversationId: String,
        request: PreviewScheduledGroupPaymentRequest,
    ): ScheduledGroupPaymentPlanDto {
        val plan = apiCalls.execute { api.previewScheduledGroupPayment(conversationId, request) }
        check(plan.isStructurallyValid() && plan.conversationId == conversationId.lowercase() &&
            plan.sourceWalletId == request.sourceWalletId && plan.splitMode == request.splitMode &&
            plan.audience == request.audience && plan.note == request.note &&
            plan.scheduledFor == request.scheduledFor
        ) { "Kit returned a changed or invalid scheduled group plan" }
        return plan
    }

    /** Consumes only the exact server-frozen plan whose intent the user approves. */
    suspend fun createGroup(
        plan: ScheduledGroupPaymentPlanDto,
        idempotencyKey: String,
        paymentPin: String,
    ): ScheduledGroupPaymentDto {
        requireRetryKey(idempotencyKey)
        check(plan.isStructurallyValid()) { "This scheduled group plan expired or is invalid" }
        val stepUp = paymentAuthorizer.authorize(
            plan.stepUp.purpose,
            plan.stepUp.intent.fields(),
            paymentPin,
            "Approve this scheduled group payment",
        )
        val schedule = apiCalls.execute {
            api.createScheduledGroupPayment(
                plan.conversationId,
                idempotencyKey,
                stepUp,
                CreateScheduledGroupPaymentRequest(plan.planId),
            )
        }
        check(schedule.isStructurallyValid() && schedule.knownStatus == ScheduledPaymentStatus.SCHEDULED &&
            schedule.conversationId == plan.conversationId && schedule.sourceWalletId == plan.sourceWalletId &&
            schedule.splitMode == plan.splitMode && schedule.audience == plan.audience &&
            schedule.totalAmount == plan.totalAmount && schedule.currency == plan.currency &&
            schedule.note == plan.note && schedule.scheduledFor == plan.scheduledFor &&
            schedule.recipients.map { it.userId } == plan.recipients.map { it.userId } &&
            schedule.recipients.map { it.amount } == plan.recipients.map { it.amount }
        ) { "Kit did not create the exact approved group schedule" }
        return schedule
    }

    suspend fun groupPage(
        conversationId: String,
        status: ScheduledPaymentStatus,
        before: String? = null,
        limit: Int = 50,
    ): ScheduledGroupPaymentPageDto {
        require(limit in 1..100)
        val page = apiCalls.execute {
            api.scheduledGroupPayments(conversationId, status.wire, before, limit)
        }
        check(page.isStructurallyValid(limit) && page.items.all {
            it.conversationId == conversationId.lowercase() && it.knownStatus == status
        }) { "Kit returned an invalid scheduled-group page" }
        return page
    }

    suspend fun group(id: String): ScheduledGroupPaymentDto =
        apiCalls.execute { api.scheduledGroupPayment(id) }.also {
            check(it.id == id.lowercase() && it.isStructurallyValid()) {
                "Kit returned an invalid scheduled group payment"
            }
        }

    suspend fun cancelGroup(id: String, idempotencyKey: String): ScheduledGroupPaymentDto {
        requireRetryKey(idempotencyKey)
        check(group(id).knownStatus == ScheduledPaymentStatus.SCHEDULED) {
            "This group payment can no longer be cancelled"
        }
        return apiCalls.execute { api.cancelScheduledGroupPayment(id, idempotencyKey) }.also {
            check(it.id == id.lowercase() && it.isStructurallyValid() &&
                it.knownStatus == ScheduledPaymentStatus.CANCELLED
            ) { "Kit did not confirm cancellation" }
        }
    }

    /** Terminal events are hints: exact state is fetched before balances or timeline are updated. */
    suspend fun synchronizeDirectTerminal(id: String): ScheduledPaymentDto = direct(id).also {
        check(it.knownStatus?.terminal == true) { "Scheduled payment is not terminal" }
        if (it.knownStatus == ScheduledPaymentStatus.COMPLETED) walletSync.refresh()
    }

    suspend fun synchronizeGroupTerminal(id: String): ScheduledGroupPaymentDto = group(id).also {
        check(it.knownStatus?.terminal == true) { "Scheduled group payment is not terminal" }
        if (it.knownStatus == ScheduledPaymentStatus.COMPLETED) walletSync.refresh()
    }

    private fun requireRetryKey(key: String) {
        require(key.length in 16..128 && key.matches(Regex("^[A-Za-z0-9._:-]+$")))
    }

    companion object { const val DIRECT_PURPOSE = "scheduled_payment" }
}
