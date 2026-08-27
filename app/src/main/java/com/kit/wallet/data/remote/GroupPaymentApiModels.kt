package com.kit.wallet.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * One payment into a group chat, shared out among its members, as the server renders it *for the
 * caller*.
 *
 * The scoping is the server's and not the app's: a recipient of a custom split is sent no total and
 * no amount for anybody but themselves, so the app never holds the other members' shares to leak.
 * Field names and nullability match the iOS `GroupPaymentDTO` so both platforms read one contract.
 */
@JsonClass(generateAdapter = false)
data class GroupPaymentDto(
    val id: String,
    @Json(name = "conversation_id") val conversationId: String? = null,
    @Json(name = "split_mode") val splitMode: String,
    val audience: String,
    val currency: CurrencyDto,
    @Json(name = "recipient_count") val recipientCount: Int? = null,
    /** Absent for a recipient of a custom split: the size of everybody else's share is not theirs. */
    @Json(name = "total_amount") val totalAmount: String? = null,
    val note: String? = null,
    val sender: GroupPaymentPartyDto? = null,
    val status: String,
    @Json(name = "pending_count") val pendingCount: Int? = null,
    @Json(name = "accepted_count") val acceptedCount: Int? = null,
    @Json(name = "returned_count") val returnedCount: Int? = null,
    @Json(name = "your_share") val yourShare: GroupPaymentShareDto? = null,
    @Json(name = "can_reverse_unclaimed") val canReverseUnclaimed: Boolean? = null,
    val recipients: List<GroupPaymentRecipientDto> = emptyList(),
    @Json(name = "expires_at") val expiresAt: String? = null,
    @Json(name = "created_at") val createdAt: String? = null,
)

@JsonClass(generateAdapter = false)
data class GroupPaymentPartyDto(
    val id: String? = null,
    val name: String? = null,
)

/** This account's own share, and what the server will let it do about it. */
@JsonClass(generateAdapter = false)
data class GroupPaymentShareDto(
    val amount: String,
    val status: String,
    @Json(name = "claim_id") val claimId: String? = null,
    @Json(name = "can_accept") val canAccept: Boolean? = null,
    @Json(name = "can_reject") val canReject: Boolean? = null,
)

@JsonClass(generateAdapter = false)
data class GroupPaymentRecipientDto(
    @Json(name = "user_id") val userId: String? = null,
    val name: String? = null,
    val status: String,
    /** Withheld unless the viewer is the sender, the split was even, or this is the viewer's own. */
    val amount: String? = null,
    @Json(name = "resolved_at") val resolvedAt: String? = null,
)

/**
 * A send. `totalAmount` is set for an even split and `recipients` carry per-member amounts for a
 * custom one; `recipients` is null when the payment is for everybody, so the server resolves the
 * roster it holds at the moment of sending rather than whatever this device last synced.
 */
@JsonClass(generateAdapter = false)
data class CreateGroupPaymentRequest(
    @Json(name = "source_wallet_id") val sourceWalletId: String,
    @Json(name = "split_mode") val splitMode: String,
    val audience: String,
    @Json(name = "total_amount") val totalAmount: String? = null,
    val note: String? = null,
    val recipients: List<CreateGroupPaymentRecipient>? = null,
)

@JsonClass(generateAdapter = false)
data class CreateGroupPaymentRecipient(
    @Json(name = "user_id") val userId: String,
    val amount: String? = null,
)

@JsonClass(generateAdapter = false)
data class GroupPaymentResolutionRequest(
    val reason: String? = null,
)
