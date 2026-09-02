package com.kit.wallet.data.remote

import com.kit.wallet.data.messaging.deterministicUuid
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

enum class GroupPaymentRequestStatus(val wire: String) {
    OPEN("open"), COMPLETED("completed"), CANCELLED("cancelled"), EXPIRED("expired");

    companion object {
        fun fromWire(raw: String): GroupPaymentRequestStatus? = entries.firstOrNull { it.wire == raw }
    }
}

@JsonClass(generateAdapter = false)
data class GroupPaymentRequestContributionDto(
    val id: String,
    @Json(name = "contributor_user_id") val contributorUserId: String,
    val amount: String,
    @Json(name = "amount_minor") val amountMinor: String,
    @Json(name = "wallet_transaction_id") val walletTransactionId: String? = null,
    @Json(name = "created_at") val createdAt: String? = null,
    @Json(name = "is_yours") val isYours: Boolean,
) {
    fun isStructurallyValid(scale: Int): Boolean {
        val minor = GroupPaymentRequestContract.minorUnits(amountMinor) ?: return false
        return GroupPaymentRequestContract.isCanonicalUuid(id) &&
            GroupPaymentRequestContract.isCanonicalUuid(contributorUserId) && minor in 1..MAX_MINOR &&
            amount == GroupPaymentRequestContract.decimal(minor, scale) &&
            (walletTransactionId == null || GroupPaymentRequestContract.isCanonicalUuid(walletTransactionId))
    }

    companion object { const val MAX_MINOR = 1_000_000_000_000L }
}

@JsonClass(generateAdapter = false)
data class GroupPaymentRequestDto(
    val id: String,
    val type: String,
    @Json(name = "conversation_id") val conversationId: String,
    @Json(name = "requester_user_id") val requesterUserId: String,
    val status: String,
    @Json(name = "destination_wallet_id") val destinationWalletId: String? = null,
    @Json(name = "target_amount") val targetAmount: String,
    @Json(name = "target_amount_minor") val targetAmountMinor: String,
    @Json(name = "contributed_amount") val contributedAmount: String,
    @Json(name = "contributed_amount_minor") val contributedAmountMinor: String,
    @Json(name = "remaining_amount") val remainingAmount: String,
    @Json(name = "remaining_amount_minor") val remainingAmountMinor: String,
    @Json(name = "progress_basis_points") val progressBasisPoints: Int,
    /** Successful contribution rows, not distinct contributors and not the embedded window size. */
    @Json(name = "contribution_count") val contributionCount: Int,
    @Json(name = "contributor_count") val contributorCount: Int,
    @Json(name = "your_contributed_amount") val yourContributedAmount: String,
    @Json(name = "your_contributed_amount_minor") val yourContributedAmountMinor: String,
    val currency: CurrencyDto,
    val note: String? = null,
    @Json(name = "expires_at") val expiresAt: String? = null,
    @Json(name = "completed_at") val completedAt: String? = null,
    @Json(name = "cancelled_at") val cancelledAt: String? = null,
    @Json(name = "expired_at") val expiredAt: String? = null,
    @Json(name = "created_at") val createdAt: String? = null,
    @Json(name = "updated_at") val updatedAt: String? = null,
    @Json(name = "can_contribute") val canContribute: Boolean,
    @Json(name = "can_cancel") val canCancel: Boolean,
    @Json(name = "contributions_has_more") val contributionsHasMore: Boolean,
    @Json(name = "contributions_next_before") val contributionsNextBefore: String? = null,
    val contributions: List<GroupPaymentRequestContributionDto>,
) {
    val knownStatus get() = GroupPaymentRequestStatus.fromWire(status)
    val currencyScale get() = currency.scale.toIntOrNull()
    val targetMinor get() = GroupPaymentRequestContract.minorUnits(targetAmountMinor)
    val contributedMinor get() = GroupPaymentRequestContract.minorUnits(contributedAmountMinor)
    val remainingMinor get() = GroupPaymentRequestContract.minorUnits(remainingAmountMinor)

    fun isStructurallyValid(): Boolean {
        val scale = currencyScale?.takeIf { it in 0..6 } ?: return false
        val target = targetMinor ?: return false
        val contributed = contributedMinor ?: return false
        val remaining = remainingMinor ?: return false
        val yours = GroupPaymentRequestContract.minorUnits(yourContributedAmountMinor) ?: return false
        val contributionTotal = contributions.fold(0L) { total, row ->
            val value = GroupPaymentRequestContract.minorUnits(row.amountMinor) ?: return false
            val next = total + value
            if (next < total) return false
            next
        }
        val uniqueContributors = contributions.map { it.contributorUserId }.toSet().size
        val expectedEmbedded = minOf(contributionCount, GroupPaymentRequestContract.EMBEDDED_LIMIT)
        return type == "group_payment_request" && GroupPaymentRequestContract.isCanonicalUuid(id) &&
            GroupPaymentRequestContract.isCanonicalUuid(conversationId) &&
            GroupPaymentRequestContract.isCanonicalUuid(requesterUserId) &&
            (destinationWalletId == null || GroupPaymentRequestContract.isCanonicalUuid(destinationWalletId)) &&
            knownStatus != null && GroupPaymentRequestContract.CURRENCY.matches(currency.code) &&
            target in 1..GroupPaymentRequestContributionDto.MAX_MINOR && contributed in 0..target &&
            remaining == target - contributed && yours in 0..contributed &&
            targetAmount == GroupPaymentRequestContract.decimal(target, scale) &&
            contributedAmount == GroupPaymentRequestContract.decimal(contributed, scale) &&
            remainingAmount == GroupPaymentRequestContract.decimal(remaining, scale) &&
            yourContributedAmount == GroupPaymentRequestContract.decimal(yours, scale) &&
            progressBasisPoints == GroupPaymentRequestContract.progress(contributed, target) &&
            contributionCount >= 0 && contributorCount in 0..contributionCount &&
            contributions.size == expectedEmbedded && contributions.map { it.id }.toSet().size == contributions.size &&
            contributions.all { it.isStructurallyValid(scale) } && contributionTotal <= contributed &&
            uniqueContributors <= contributorCount && (!contributionsHasMore && uniqueContributors == contributorCount || contributionsHasMore) &&
            contributionsHasMore == (contributionCount > contributions.size) &&
            (if (contributionsHasMore) contributions.firstOrNull()?.id == contributionsNextBefore
            else contributionsNextBefore == null && contributionTotal == contributed) &&
            (knownStatus != GroupPaymentRequestStatus.COMPLETED || remaining == 0L) &&
            (knownStatus != GroupPaymentRequestStatus.OPEN || remaining > 0L) &&
            (!canContribute || knownStatus == GroupPaymentRequestStatus.OPEN) &&
            (!canCancel || knownStatus == GroupPaymentRequestStatus.OPEN) &&
            (note?.length ?: 0) <= 280
    }

    /** Chat bubbles show a bounded recent sample; the full ledger remains pageable. */
    fun bubbleContributions(maxRows: Int = 5): List<GroupPaymentRequestContributionDto> =
        contributions.takeLast(maxRows.coerceIn(0, 5))
}

@JsonClass(generateAdapter = false)
data class GroupPaymentRequestListDto(val items: List<GroupPaymentRequestDto>)

@JsonClass(generateAdapter = false)
data class GroupPaymentRequestContributionPageDto(
    val items: List<GroupPaymentRequestContributionDto>,
    @Json(name = "has_more") val hasMore: Boolean,
    @Json(name = "next_before") val nextBefore: String? = null,
) {
    fun isStructurallyValid(scale: Int, limit: Int): Boolean = limit in 1..100 && items.size <= limit &&
        items.all { it.isStructurallyValid(scale) } && items.map { it.id }.toSet().size == items.size &&
        if (hasMore) GroupPaymentRequestContract.isCanonicalUuid(nextBefore)
        else nextBefore == null
}

@JsonClass(generateAdapter = false)
data class GroupPaymentRequestContributionResultDto(
    val request: GroupPaymentRequestDto,
    val contribution: GroupPaymentRequestContributionDto,
) {
    fun isStructurallyValid(): Boolean {
        if (!request.isStructurallyValid() ||
            !contribution.isStructurallyValid(request.currencyScale ?: -1)
        ) return false
        val embedded = request.contributions.firstOrNull { it.id == contribution.id }
        return if (embedded == null) request.contributionsHasMore else embedded == contribution
    }
}

@JsonClass(generateAdapter = false)
data class CreateCollaborativeGroupPaymentRequest(
    @Json(name = "destination_wallet_id") val destinationWalletId: String,
    @Json(name = "total_amount") val totalAmount: String,
    val note: String? = null,
    @Json(name = "expires_at") val expiresAt: String? = null,
)

@JsonClass(generateAdapter = false)
data class ContributeGroupPaymentRequest(
    @Json(name = "source_wallet_id") val sourceWalletId: String,
    val amount: String,
)

internal object GroupPaymentRequestContract {
    const val EMBEDDED_LIMIT = 50
    val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    val CURRENCY = Regex("^[A-Z]{3}$")

    fun isCanonicalUuid(raw: String?): Boolean = raw != null && UUID.matches(raw)
    fun minorUnits(raw: String): Long? = raw.takeIf {
        it.matches(Regex("^(0|[1-9][0-9]*)$"))
    }?.toLongOrNull()?.takeIf { it >= 0 }
    fun decimal(minor: Long, scale: Int): String {
        if (scale == 0) return minor.toString()
        val digits = minor.toString().padStart(scale + 1, '0')
        return digits.dropLast(scale) + "." + digits.takeLast(scale)
    }
    fun progress(contributed: Long, target: Long): Int = when {
        contributed <= 0L || target <= 0L -> 0
        contributed >= target -> 10_000
        else -> ((contributed * 10_000L) / target).toInt()
    }
}

internal object GroupPaymentRequestPresentation {
    fun progress(request: GroupPaymentRequestDto): String = when {
        request.knownStatus == GroupPaymentRequestStatus.COMPLETED ->
            "Payment request complete — ${money(request.targetAmount, request.currency.code)} collected."
        request.contributedMinor == 0L -> "No contributions yet"
        else -> "${money(request.contributedAmount, request.currency.code)} of " +
            "${money(request.targetAmount, request.currency.code)} collected"
    }

    fun contributed(actorName: String?, amount: String, currencyCode: String, isViewer: Boolean): String =
        if (isViewer) "You contributed ${money(amount, currencyCode)} to this request."
        else "${actorName?.trim()?.takeIf(String::isNotEmpty) ?: "A group member"} contributed " +
            "${money(amount, currencyCode)} to this request."

    fun completed(
        actorName: String?,
        contributionAmount: String,
        collectedAmount: String,
        currencyCode: String,
        isViewer: Boolean,
    ): String {
        val actor = if (isViewer) "You" else actorName?.trim()?.takeIf(String::isNotEmpty)
            ?: "A group member"
        return "$actor completed this request with ${money(contributionAmount, currencyCode)} — " +
            "${money(collectedAmount, currencyCode)} collected."
    }

    private fun money(amount: String, currencyCode: String) = "$currencyCode $amount"
}

enum class KitGroupPaymentRequestAction(val wire: String) {
    REQUESTED("requested"), CONTRIBUTED("contributed"), COMPLETED("completed"),
    CANCELLED("cancelled"), EXPIRED("expired");
    companion object { fun fromWire(raw: String) = entries.firstOrNull { it.wire == raw } }
}

/** Strict canonical KITGREQ1 rendering hint. API state remains authoritative. */
data class KitGroupPaymentRequestMessage private constructor(
    val action: KitGroupPaymentRequestAction,
    val requestId: String,
    val contributionId: String? = null,
    val amountMinor: Long? = null,
    val currencyCode: String? = null,
    val currencyScale: Int? = null,
    val note: String? = null,
) {
    fun encode(): String = buildString {
        append(PREFIX).append("v=1&a=").append(action.wire).append("&id=").append(requestId)
        contributionId?.let { append("&cid=").append(it) }
        amountMinor?.let { append("&amt=").append(it) }
        currencyCode?.let { append("&cur=").append(it) }
        currencyScale?.let { append("&sc=").append(it) }
        note?.let { append("&note=").append(percentEncode(it)) }
    }

    /** One stable encrypted-outbox identity for this exact server-owned request event. */
    fun deterministicMessageId(): String = deterministicUuid(
        buildString {
            append("kit-group-payment-request-event-v1|")
            append(requestId.lowercase()).append('|').append(action.wire)
            contributionId?.let { append('|').append(it.lowercase()) }
        },
    )

    companion object {
        const val PREFIX = "KITGREQ1:"
        const val MAX_LENGTH = 2_048

        fun create(
            action: KitGroupPaymentRequestAction,
            requestId: String,
            contributionId: String? = null,
            amountMinor: Long? = null,
            currencyCode: String? = null,
            currencyScale: Int? = null,
            note: String? = null,
        ): KitGroupPaymentRequestMessage? {
            if (!GroupPaymentRequestContract.isCanonicalUuid(requestId)) return null
            val normalizedNote = note?.trim()?.takeIf(String::isNotEmpty)
            when (action) {
                KitGroupPaymentRequestAction.REQUESTED -> if (
                    contributionId != null || amountMinor == null ||
                    amountMinor !in 1..GroupPaymentRequestContributionDto.MAX_MINOR ||
                    currencyCode == null || !GroupPaymentRequestContract.CURRENCY.matches(currencyCode) ||
                    currencyScale == null || currencyScale !in 0..6 || (normalizedNote?.length ?: 0) > 280
                ) return null
                KitGroupPaymentRequestAction.CONTRIBUTED -> if (
                    !GroupPaymentRequestContract.isCanonicalUuid(contributionId) ||
                    amountMinor == null || amountMinor !in 1..GroupPaymentRequestContributionDto.MAX_MINOR || currencyCode != null ||
                    currencyScale != null || normalizedNote != null
                ) return null
                else -> if (contributionId != null || amountMinor != null || currencyCode != null ||
                    currencyScale != null || normalizedNote != null
                ) return null
            }
            return KitGroupPaymentRequestMessage(
                action, requestId, contributionId, amountMinor, currencyCode, currencyScale, normalizedNote,
            ).takeIf { it.encode().toByteArray(StandardCharsets.UTF_8).size <= MAX_LENGTH }
        }

        fun parse(text: String): KitGroupPaymentRequestMessage? {
            if (!text.startsWith(PREFIX) || text.toByteArray(StandardCharsets.UTF_8).size > MAX_LENGTH) return null
            val fields = linkedMapOf<String, String>()
            text.removePrefix(PREFIX).split('&').forEach { pair ->
                val separator = pair.indexOf('=')
                if (separator <= 0) return null
                val key = pair.substring(0, separator)
                if (fields.containsKey(key)) return null
                fields[key] = percentDecode(pair.substring(separator + 1)) ?: return null
            }
            if (fields["v"] != "1") return null
            val descriptor = create(
                action = fields["a"]?.let(KitGroupPaymentRequestAction::fromWire) ?: return null,
                requestId = fields["id"] ?: return null,
                contributionId = fields["cid"],
                amountMinor = fields["amt"]?.toLongOrNull(),
                currencyCode = fields["cur"],
                currencyScale = fields["sc"]?.toIntOrNull(),
                note = fields["note"],
            ) ?: return null
            return descriptor.takeIf { it.encode() == text }
        }

        private fun percentEncode(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
                .replace("%7E", "~")

        private fun percentDecode(value: String): String? = runCatching {
            if ('+' in value) return null
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        }.getOrNull()
    }
}
