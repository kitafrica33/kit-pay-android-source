package com.kit.wallet.data.repository

import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.ApiEnvelope
import com.kit.wallet.data.remote.CreateScheduledGroupPaymentRequest
import com.kit.wallet.data.remote.CreateScheduledPaymentRequest
import com.kit.wallet.data.remote.CurrencyDto
import com.kit.wallet.data.remote.CreateGroupPaymentRequest
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.PreviewScheduledGroupPaymentRequest
import com.kit.wallet.data.remote.ScheduledGroupPaymentDto
import com.kit.wallet.data.remote.ScheduledGroupPaymentPageDto
import com.kit.wallet.data.remote.ScheduledGroupPaymentPlanDto
import com.kit.wallet.data.remote.ScheduledPaymentDto
import com.kit.wallet.data.remote.ScheduledPaymentPageDto
import com.kit.wallet.data.remote.ScheduledPaymentStatus
import com.kit.wallet.data.remote.ScheduleContract
import com.kit.wallet.data.session.SessionFence
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
    suspend fun recoverDirect(
        conversationId: String,
        previous: List<ScheduledPaymentDto>,
    ): List<ScheduledPaymentDto> = recoverNonTerminalSchedules(
        previous = previous,
        page = { status, before -> directPage(conversationId, status, before, 100) },
        pageItems = ScheduledPaymentPageDto::items,
        pageHasMore = ScheduledPaymentPageDto::hasMore,
        pageCursor = ScheduledPaymentPageDto::nextBefore,
        exact = { id -> direct(id).takeIf { it.conversationId == conversationId.lowercase() } },
        id = ScheduledPaymentDto::id,
        status = ScheduledPaymentDto::knownStatus,
    )

    suspend fun recoverGroup(
        conversationId: String,
        previous: List<ScheduledGroupPaymentDto>,
    ): List<ScheduledGroupPaymentDto> = recoverNonTerminalSchedules(
        previous = previous,
        page = { status, before -> groupPage(conversationId, status, before, 100) },
        pageItems = ScheduledGroupPaymentPageDto::items,
        pageHasMore = ScheduledGroupPaymentPageDto::hasMore,
        pageCursor = ScheduledGroupPaymentPageDto::nextBefore,
        exact = { id -> group(id).takeIf { it.conversationId == conversationId.lowercase() } },
        id = ScheduledGroupPaymentDto::id,
        status = ScheduledGroupPaymentDto::knownStatus,
    )

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
        idempotencyKey: () -> String,
        paymentPin: String,
        expectedOwner: SessionFence,
    ): ScheduledPaymentDto {
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
            DIRECT_PURPOSE,
            intent,
            paymentPin,
            "Approve this scheduled payment",
            expectedOwner,
        )
        val key = idempotencyKey().also(::requireRetryKey)
        val created = executeFinancialMutation {
            api.createScheduledPayment(key, stepUp, request, expectedOwner)
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
        val current = direct(id)
        if (current.knownStatus == ScheduledPaymentStatus.CANCELLED) return current
        check(current.knownStatus == ScheduledPaymentStatus.SCHEDULED) {
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
        expectedCurrency: CurrencyDto,
        allowedRecipientIds: Set<String>,
        expectedOwner: SessionFence,
    ): ScheduledGroupPaymentPlanDto {
        val plan = apiCalls.execute {
            api.previewScheduledGroupPayment(conversationId, request, expectedOwner)
        }
        check(plan.isStructurallyValid() && plan.conversationId == conversationId.lowercase() &&
            plan.sourceWalletId == request.sourceWalletId && plan.splitMode == request.splitMode &&
            plan.audience == request.audience && plan.note == request.note &&
            plan.scheduledFor == request.scheduledFor &&
            plan.matchesReviewedDraft(request, expectedCurrency, allowedRecipientIds)
        ) { "Kit returned a changed or invalid scheduled group plan" }
        return plan
    }

    /** Consumes only the exact server-frozen plan whose intent the user approves. */
    suspend fun createGroup(
        plan: ScheduledGroupPaymentPlanDto,
        idempotencyKey: () -> String,
        paymentPin: String,
        expectedOwner: SessionFence,
    ): ScheduledGroupPaymentDto = createGroupMutation(
        plan,
        idempotencyKey,
        paymentPin,
        expectedOwner,
        allowExpiredReplay = false,
    )

    /** Replays only a previously submitted immutable operation whose response was ambiguous. */
    internal suspend fun replayGroup(
        plan: ScheduledGroupPaymentPlanDto,
        idempotencyKey: () -> String,
        paymentPin: String,
        expectedOwner: SessionFence,
    ): ScheduledGroupPaymentDto = createGroupMutation(
        plan,
        idempotencyKey,
        paymentPin,
        expectedOwner,
        allowExpiredReplay = true,
    )

    private suspend fun createGroupMutation(
        plan: ScheduledGroupPaymentPlanDto,
        idempotencyKey: () -> String,
        paymentPin: String,
        expectedOwner: SessionFence,
        allowExpiredReplay: Boolean,
    ): ScheduledGroupPaymentDto {
        val validationTime = if (allowExpiredReplay) java.time.Instant.EPOCH else java.time.Instant.now()
        check(plan.isStructurallyValid(validationTime)) {
            "This scheduled group plan expired or is invalid"
        }
        val stepUp = paymentAuthorizer.authorize(
            plan.stepUp.purpose,
            plan.stepUp.intent.fields(),
            paymentPin,
            "Approve this scheduled group payment",
            expectedOwner,
        )
        val key = idempotencyKey().also(::requireRetryKey)
        val schedule = executeFinancialMutation {
            api.createScheduledGroupPayment(
                plan.conversationId,
                key,
                stepUp,
                CreateScheduledGroupPaymentRequest(plan.planId),
                expectedOwner,
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
        val current = group(id)
        if (current.knownStatus == ScheduledPaymentStatus.CANCELLED) return current
        check(current.knownStatus == ScheduledPaymentStatus.SCHEDULED) {
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

    private suspend fun <T> executeFinancialMutation(
        call: suspend () -> ApiEnvelope<T>,
    ): T = try {
        apiCalls.execute(call)
    } catch (error: com.kit.wallet.data.remote.KitWalletApiException) {
        if (error.isDefinitiveFinancialMutationRejection()) {
            throw DefinitiveFinancialMutationRejection(error)
        }
        throw error
    }

    companion object { const val DIRECT_PURPOSE = "scheduled_payment" }
}

/** Exhaustive fresh-install recovery, with exact reconciliation for rows that changed mid-page. */
internal suspend fun <T, P> recoverNonTerminalSchedules(
    previous: List<T>,
    page: suspend (ScheduledPaymentStatus, String?) -> P,
    pageItems: (P) -> List<T>,
    pageHasMore: (P) -> Boolean,
    pageCursor: (P) -> String?,
    exact: suspend (String) -> T?,
    id: (T) -> String,
    status: (T) -> ScheduledPaymentStatus?,
): List<T> {
    val recovered = LinkedHashMap<String, T>()
    for (wanted in NON_TERMINAL_SCHEDULE_STATUSES) {
        var before: String? = null
        val seenCursors = mutableSetOf<String>()
        var pages = 0
        while (true) {
            check(pages++ < MAX_SCHEDULE_PAGES) { "Schedule recovery exceeds its safe bound" }
            val response = page(wanted, before)
            pageItems(response).forEach { item ->
                check(status(item) == wanted) { "A schedule page changed status" }
                recovered[id(item).lowercase()] = item
            }
            if (!pageHasMore(response)) break
            val next = checkNotNull(pageCursor(response)) { "A schedule page lost its cursor" }
            check(seenCursors.add(next)) { "Schedule pagination did not advance" }
            before = next
        }
    }
    previous.forEach { known ->
        val key = id(known).lowercase()
        if (key !in recovered) {
            val refreshed = exact(key)
            if (refreshed != null && status(refreshed)?.terminal == false) {
                recovered[key] = refreshed
            }
        }
    }
    return recovered.values.sortedBy(id)
}

internal fun ScheduledGroupPaymentPlanDto.matchesReviewedDraft(
    request: PreviewScheduledGroupPaymentRequest,
    expectedCurrency: CurrencyDto,
    allowedRecipientIds: Set<String>,
): Boolean {
    val scale = expectedCurrency.scale.toIntOrNull()?.takeIf { it in 0..6 } ?: return false
    if (currency != expectedCurrency || sourceWalletId != request.sourceWalletId ||
        splitMode != request.splitMode || audience != request.audience || note != request.note ||
        scheduledFor != request.scheduledFor
    ) return false
    val allowed = allowedRecipientIds.map(String::lowercase).toSet()
    val planIds = recipients.map { it.userId }
    if (planIds.any { it !in allowed }) return false
    val requestRows = request.recipients.orEmpty()
    val requested = requestRows.associateBy { it.userId.lowercase() }
    if (requestRows.size != requested.size) return false
    when (request.audience) {
        "all" -> if (request.recipients != null || planIds.toSet() != allowed) return false
        "selected" -> if (planIds.toSet() != requested.keys) return false
        else -> return false
    }
    return when (request.splitMode) {
        "even" -> {
            val reviewedTotal = request.totalAmount ?: return false
            val totalMinor = ScheduleContract.minor(reviewedTotal, scale) ?: return false
            if (totalAmount != reviewedTotal || requested.values.any { it.amount != null } ||
                totalMinor < recipients.size
            ) return false
            val base = totalMinor / recipients.size
            val remainder = totalMinor % recipients.size
            val shares = recipients.map {
                ScheduleContract.minor(it.amount, scale) ?: return false
            }
            shares.sum() == totalMinor && shares.all { it == base || it == base + 1L } &&
                shares.count { it == base + 1L } == remainder.toInt()
        }
        "custom" -> {
            if (request.totalAmount != null || requested.isEmpty()) return false
            val plannedAmounts = recipients.associate { it.userId to it.amount }
            requested.all { (userId, row) ->
                val amount = row.amount ?: return@all false
                plannedAmounts[userId] == amount
            } && recipients.sumOf { ScheduleContract.minor(it.amount, scale) ?: return false } ==
                ScheduleContract.minor(totalAmount, scale)
        }
        else -> false
    }
}

private val NON_TERMINAL_SCHEDULE_STATUSES = listOf(
    ScheduledPaymentStatus.SCHEDULED,
    ScheduledPaymentStatus.QUEUED,
    ScheduledPaymentStatus.PROCESSING,
)
private const val MAX_SCHEDULE_PAGES = 100
