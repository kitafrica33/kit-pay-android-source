package com.kit.wallet.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import java.math.BigDecimal
import java.time.Instant

enum class ScheduledPaymentStatus(val wire: String) {
    SCHEDULED("scheduled"), QUEUED("queued"), PROCESSING("processing"), COMPLETED("completed"),
    FAILED("failed"), CANCELLED("cancelled");
    val terminal get() = this == COMPLETED || this == FAILED || this == CANCELLED
    companion object { fun fromWire(raw: String) = entries.firstOrNull { it.wire == raw } }
}

@JsonClass(generateAdapter = false)
data class ScheduledPaymentFailureDto(val code: String, val message: String) {
    fun isStructurallyValid() = code.isNotBlank() && code.length <= 120 &&
        message.isNotBlank() && message.length <= 500 && message.none(Char::isISOControl)
}

@JsonClass(generateAdapter = false)
data class ScheduledPaymentDto(
    val id: String,
    val type: String,
    val status: String,
    @Json(name = "conversation_id") val conversationId: String? = null,
    @Json(name = "source_wallet_id") val sourceWalletId: String? = null,
    @Json(name = "destination_wallet_id") val destinationWalletId: String,
    val amount: String,
    val currency: CurrencyDto,
    val note: String? = null,
    @Json(name = "scheduled_for") val scheduledFor: String,
    @Json(name = "payment_execution_id") val paymentExecutionId: String? = null,
    @Json(name = "wallet_transaction_id") val walletTransactionId: String? = null,
    val failure: ScheduledPaymentFailureDto? = null,
    @Json(name = "completed_at") val completedAt: String? = null,
    @Json(name = "cancelled_at") val cancelledAt: String? = null,
    @Json(name = "created_at") val createdAt: String? = null,
) {
    val knownStatus get() = ScheduledPaymentStatus.fromWire(status)
    fun isStructurallyValid(): Boolean {
        val state = knownStatus ?: return false
        val scale = currency.scale.toIntOrNull()?.takeIf { it in 0..6 } ?: return false
        if (type != "scheduled_payment" || !ScheduleContract.uuid(id) ||
            (sourceWalletId == null && state != ScheduledPaymentStatus.COMPLETED) ||
            (sourceWalletId != null && !ScheduleContract.uuid(sourceWalletId)) ||
            !ScheduleContract.uuid(destinationWalletId) || sourceWalletId == destinationWalletId ||
            (conversationId != null && !ScheduleContract.uuid(conversationId)) ||
            !ScheduleContract.amount(amount, scale) || !ScheduleContract.currency(currency.code) ||
            !ScheduleContract.instant(scheduledFor) || (createdAt != null && !ScheduleContract.instant(createdAt)) ||
            (note?.length ?: 0) > 280 ||
            listOf(paymentExecutionId, walletTransactionId).filterNotNull().any { !ScheduleContract.uuid(it) } ||
            listOf(completedAt, cancelledAt).filterNotNull().any { !ScheduleContract.instant(it) } ||
            failure?.isStructurallyValid() == false
        ) return false
        return when (state) {
            ScheduledPaymentStatus.SCHEDULED -> paymentExecutionId == null && walletTransactionId == null &&
                failure == null && completedAt == null && cancelledAt == null
            ScheduledPaymentStatus.QUEUED, ScheduledPaymentStatus.PROCESSING -> paymentExecutionId != null &&
                walletTransactionId == null && failure == null && completedAt == null && cancelledAt == null
            ScheduledPaymentStatus.COMPLETED -> (paymentExecutionId != null || sourceWalletId == null) &&
                walletTransactionId != null && failure == null && completedAt != null && cancelledAt == null
            ScheduledPaymentStatus.FAILED -> paymentExecutionId != null && walletTransactionId == null &&
                failure != null && completedAt != null && cancelledAt == null
            ScheduledPaymentStatus.CANCELLED -> paymentExecutionId == null && walletTransactionId == null &&
                failure == null && completedAt == null && cancelledAt != null
        }
    }
}

@JsonClass(generateAdapter = false)
data class ScheduledPaymentPageDto(
    val items: List<ScheduledPaymentDto>,
    @Json(name = "has_more") val hasMore: Boolean,
    @Json(name = "next_before") val nextBefore: String? = null,
) {
    fun isStructurallyValid(limit: Int) = limit in 1..100 && items.size <= limit &&
        items.all(ScheduledPaymentDto::isStructurallyValid) &&
        if (hasMore) ScheduleContract.uuid(nextBefore) else nextBefore == null
}

@JsonClass(generateAdapter = false)
data class CreateScheduledPaymentRequest(
    @Json(name = "source_wallet_id") val sourceWalletId: String,
    @Json(name = "destination_wallet_id") val destinationWalletId: String,
    val amount: String,
    val note: String? = null,
    @Json(name = "scheduled_for") val scheduledFor: String,
    @Json(name = "conversation_id") val conversationId: String? = null,
)

@JsonClass(generateAdapter = false)
data class PreviewScheduledGroupPaymentRequest(
    @Json(name = "source_wallet_id") val sourceWalletId: String,
    @Json(name = "split_mode") val splitMode: String,
    val audience: String,
    @Json(name = "total_amount") val totalAmount: String? = null,
    val note: String? = null,
    val recipients: List<CreateGroupPaymentRecipient>? = null,
    @Json(name = "scheduled_for") val scheduledFor: String,
)

@JsonClass(generateAdapter = false)
data class CreateScheduledGroupPaymentRequest(@Json(name = "plan_id") val planId: String)

@JsonClass(generateAdapter = false)
data class ScheduledGroupPlanRecipientDto(
    @Json(name = "user_id") val userId: String,
    @Json(name = "destination_wallet_id") val destinationWalletId: String,
    val amount: String,
)

@JsonClass(generateAdapter = false)
data class ScheduledGroupStepUpIntentDto(
    val action: String,
    @Json(name = "plan_id") val planId: String,
    @Json(name = "plan_hash") val planHash: String,
    @Json(name = "conversation_id") val conversationId: String,
    @Json(name = "source_wallet_id") val sourceWalletId: String,
    @Json(name = "split_mode") val splitMode: String,
    val audience: String,
    @Json(name = "total_amount") val totalAmount: String,
    val currency: String,
    val note: String? = null,
    @Json(name = "scheduled_for") val scheduledFor: String,
    @Json(name = "roster_fingerprint") val rosterFingerprint: String,
    @Json(name = "frozen_recipients") val frozenRecipients: String,
) {
    fun fields(): Map<String, Any?> = linkedMapOf(
        "action" to action, "plan_id" to planId, "plan_hash" to planHash,
        "conversation_id" to conversationId, "source_wallet_id" to sourceWalletId,
        "split_mode" to splitMode, "audience" to audience, "total_amount" to totalAmount,
        "currency" to currency, "note" to note, "scheduled_for" to scheduledFor,
        "roster_fingerprint" to rosterFingerprint, "frozen_recipients" to frozenRecipients,
    )
}

@JsonClass(generateAdapter = false)
data class ScheduledGroupStepUpDto(
    val purpose: String,
    val intent: ScheduledGroupStepUpIntentDto,
)

@JsonClass(generateAdapter = false)
data class ScheduledGroupPaymentPlanDto(
    @Json(name = "plan_id") val planId: String,
    @Json(name = "conversation_id") val conversationId: String,
    @Json(name = "source_wallet_id") val sourceWalletId: String,
    @Json(name = "split_mode") val splitMode: String,
    val audience: String,
    @Json(name = "total_amount") val totalAmount: String,
    val currency: CurrencyDto,
    val note: String? = null,
    @Json(name = "recipient_count") val recipientCount: Int,
    val recipients: List<ScheduledGroupPlanRecipientDto>,
    @Json(name = "roster_fingerprint") val rosterFingerprint: String,
    @Json(name = "frozen_recipients") val frozenRecipients: String,
    @Json(name = "plan_hash") val planHash: String,
    @Json(name = "scheduled_for") val scheduledFor: String,
    @Json(name = "expires_at") val expiresAt: String,
    @Json(name = "step_up") val stepUp: ScheduledGroupStepUpDto,
) {
    fun isStructurallyValid(now: Instant = Instant.now()): Boolean {
        val scale = currency.scale.toIntOrNull()?.takeIf { it in 0..6 } ?: return false
        val scheduled = runCatching { Instant.parse(scheduledFor) }.getOrNull() ?: return false
        val expiry = runCatching { Instant.parse(expiresAt) }.getOrNull() ?: return false
        if (!ScheduleContract.uuid(planId) || !ScheduleContract.uuid(conversationId) ||
            !ScheduleContract.uuid(sourceWalletId) || splitMode !in setOf("even", "custom") ||
            audience !in setOf("all", "selected") || !ScheduleContract.amount(totalAmount, scale) ||
            !ScheduleContract.currency(currency.code) || (note?.length ?: 0) > 280 ||
            recipientCount !in 1..50 || recipients.size != recipientCount ||
            !ScheduleContract.hash(rosterFingerprint) || !ScheduleContract.hash(planHash) ||
            scheduled <= now || expiry <= now || expiry >= scheduled || stepUp.purpose != PURPOSE
        ) return false
        val ids = recipients.map { it.userId }
        val wallets = recipients.map { it.destinationWalletId }
        if (ids != ids.sorted() || ids.toSet().size != ids.size || wallets.toSet().size != wallets.size ||
            recipients.any { !ScheduleContract.uuid(it.userId) || !ScheduleContract.uuid(it.destinationWalletId) ||
                !ScheduleContract.amount(it.amount, scale) }
        ) return false
        val frozen = recipients.joinToString(",") {
            "${it.userId}:${it.destinationWalletId}:${ScheduleContract.minor(it.amount, scale)}"
        }
        val intent = stepUp.intent
        return frozen == frozenRecipients && recipients.sumOf { ScheduleContract.minor(it.amount, scale)!! } ==
            ScheduleContract.minor(totalAmount, scale) && intent.action == "create" &&
            intent.planId == planId && intent.planHash == planHash &&
            intent.conversationId == conversationId && intent.sourceWalletId == sourceWalletId &&
            intent.splitMode == splitMode && intent.audience == audience && intent.totalAmount == totalAmount &&
            intent.currency == currency.code && intent.note == note && intent.scheduledFor == scheduledFor &&
            intent.rosterFingerprint == rosterFingerprint && intent.frozenRecipients == frozenRecipients
    }
    companion object { const val PURPOSE = "scheduled_group_payment" }
}

@JsonClass(generateAdapter = false)
data class ScheduledGroupRecipientDto(
    @Json(name = "user_id") val userId: String,
    val name: String? = null,
    val amount: String? = null,
)

@JsonClass(generateAdapter = false)
data class ScheduledGroupPaymentDto(
    val id: String,
    val type: String,
    @Json(name = "conversation_id") val conversationId: String,
    val status: String,
    @Json(name = "source_wallet_id") val sourceWalletId: String? = null,
    @Json(name = "split_mode") val splitMode: String,
    val audience: String,
    @Json(name = "total_amount") val totalAmount: String? = null,
    val currency: CurrencyDto,
    val note: String? = null,
    @Json(name = "recipient_count") val recipientCount: Int,
    val recipients: List<ScheduledGroupRecipientDto>,
    @Json(name = "group_payment_id") val groupPaymentId: String? = null,
    val failure: ScheduledPaymentFailureDto? = null,
    @Json(name = "scheduled_for") val scheduledFor: String,
    @Json(name = "queued_at") val queuedAt: String? = null,
    @Json(name = "started_at") val startedAt: String? = null,
    @Json(name = "completed_at") val completedAt: String? = null,
    @Json(name = "cancelled_at") val cancelledAt: String? = null,
    @Json(name = "created_at") val createdAt: String,
) {
    val knownStatus get() = ScheduledPaymentStatus.fromWire(status)
    fun isStructurallyValid(): Boolean {
        val state = knownStatus ?: return false
        val scale = currency.scale.toIntOrNull()?.takeIf { it in 0..6 } ?: return false
        if (type != "scheduled_group_payment" || !ScheduleContract.uuid(id) ||
            !ScheduleContract.uuid(conversationId) || sourceWalletId?.let { !ScheduleContract.uuid(it) } == true ||
            splitMode !in setOf("even", "custom") || audience !in setOf("all", "selected") ||
            !ScheduleContract.currency(currency.code) || !ScheduleContract.instant(scheduledFor) ||
            !ScheduleContract.instant(createdAt) || recipientCount !in 1..50 || recipients.size != recipientCount ||
            recipients.map { it.userId }.toSet().size != recipients.size || recipients.any {
                !ScheduleContract.uuid(it.userId) || (it.amount != null && !ScheduleContract.amount(it.amount, scale))
            } || groupPaymentId?.let { !ScheduleContract.uuid(it) } == true || failure?.isStructurallyValid() == false ||
            listOf(queuedAt, startedAt, completedAt, cancelledAt).filterNotNull().any { !ScheduleContract.instant(it) }
        ) return false
        if ((splitMode == "even" || sourceWalletId != null) &&
            (totalAmount == null || !ScheduleContract.amount(totalAmount, scale) ||
                recipients.any { it.amount == null } || recipients.sumOf { ScheduleContract.minor(it.amount!!, scale)!! } !=
                ScheduleContract.minor(totalAmount, scale))
        ) return false
        return when (state) {
            ScheduledPaymentStatus.SCHEDULED -> sourceWalletId != null && groupPaymentId == null && failure == null &&
                queuedAt == null && startedAt == null && completedAt == null && cancelledAt == null
            ScheduledPaymentStatus.QUEUED -> sourceWalletId != null && groupPaymentId == null && failure == null &&
                queuedAt != null && startedAt == null && completedAt == null && cancelledAt == null
            ScheduledPaymentStatus.PROCESSING -> sourceWalletId != null && groupPaymentId == null && failure == null &&
                queuedAt != null && startedAt != null && completedAt == null && cancelledAt == null
            ScheduledPaymentStatus.COMPLETED -> groupPaymentId != null && failure == null && completedAt != null && cancelledAt == null
            ScheduledPaymentStatus.FAILED -> sourceWalletId != null && groupPaymentId == null && failure != null && completedAt != null && cancelledAt == null
            ScheduledPaymentStatus.CANCELLED -> sourceWalletId != null && groupPaymentId == null && failure == null &&
                queuedAt == null && startedAt == null && completedAt == null && cancelledAt != null
        }
    }
}

@JsonClass(generateAdapter = false)
data class ScheduledGroupPaymentPageDto(
    val items: List<ScheduledGroupPaymentDto>,
    @Json(name = "has_more") val hasMore: Boolean,
    @Json(name = "next_before") val nextBefore: String? = null,
) {
    fun isStructurallyValid(limit: Int) = limit in 1..100 && items.size <= limit &&
        items.all(ScheduledGroupPaymentDto::isStructurallyValid) &&
        if (hasMore) ScheduleContract.uuid(nextBefore) else nextBefore == null
}

internal object ScheduleContract {
    private val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    private val CURRENCY = Regex("^[A-Z]{3}$")
    private val HASH = Regex("^[0-9a-f]{64}$")
    fun uuid(value: String?) = value != null && UUID.matches(value)
    fun currency(value: String) = CURRENCY.matches(value)
    fun hash(value: String) = HASH.matches(value)
    fun instant(value: String) = runCatching { Instant.parse(value) }.isSuccess
    fun minor(value: String, scale: Int): Long? = runCatching {
        val number = BigDecimal(value)
        if (number.signum() <= 0 || number.scale() != scale || number.toPlainString() != value) return null
        number.movePointRight(scale).longValueExact()
    }.getOrNull()?.takeIf { it in 1..1_000_000_000_000L }
    fun amount(value: String, scale: Int) = minor(value, scale) != null
}
