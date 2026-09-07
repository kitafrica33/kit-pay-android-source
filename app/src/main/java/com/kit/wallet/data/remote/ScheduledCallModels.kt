package com.kit.wallet.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = false)
data class ScheduledCallParticipantDto(
    @Json(name = "user_id") val userId: String,
    val name: String? = null,
    val response: String? = null,
)

@JsonClass(generateAdapter = false)
data class ScheduledCallDto(
    val id: String,
    @Json(name = "client_schedule_id") val clientScheduleId: String,
    @Json(name = "organizer_user_id") val organizerUserId: String,
    val title: String? = null,
    val type: String,
    @Json(name = "conversation_id") val conversationId: String? = null,
    @Json(name = "starts_at") val startsAt: String,
    val status: String,
    val revision: Long,
    @Json(name = "call_id") val callId: String? = null,
    @Json(name = "my_response") val myResponse: String? = null,
    val participants: List<ScheduledCallParticipantDto> = emptyList(),
    @Json(name = "server_time") val serverTime: String,
)

@JsonClass(generateAdapter = false)
data class ScheduledCallsPageDto(
    val items: List<ScheduledCallDto>,
    val page: CursorPageDto,
)

@JsonClass(generateAdapter = false)
data class CreateScheduledCallRequest(
    @Json(name = "client_schedule_id") val clientScheduleId: String,
    @Json(name = "recipient_user_ids") val recipientUserIds: List<String>,
    val type: String,
    @Json(name = "starts_at") val startsAt: String,
    val title: String? = null,
)

@JsonClass(generateAdapter = false)
data class UpdateScheduledCallRequest(
    val revision: Long,
    @Json(name = "starts_at") val startsAt: String,
    val title: String? = null,
)

@JsonClass(generateAdapter = false)
data class ScheduledCallRevisionRequest(val revision: Long)

@JsonClass(generateAdapter = false)
data class ScheduledCallResponseRequest(val response: String)

@JsonClass(generateAdapter = false)
data class CallInviteLinkDto(
    val id: String,
    val token: String,
    @Json(name = "share_url") val shareUrl: String,
    @Json(name = "expires_at") val expiresAt: String,
) {
    override fun toString() = "CallInviteLinkDto([redacted])"
}

@JsonClass(generateAdapter = false)
data class CallInvitePreviewDto(
    val kind: String,
    val call: CallDto? = null,
    @Json(name = "scheduled_call") val scheduledCall: ScheduledCallDto? = null,
    @Json(name = "expires_at") val expiresAt: String,
    @Json(name = "server_time") val serverTime: String,
)

@JsonClass(generateAdapter = false)
data class RedeemCallInviteRequest(@Json(name = "supports_hold") val supportsHold: Boolean = true)

@JsonClass(generateAdapter = false)
data class RedeemedCallInviteDto(
    val kind: String,
    val call: CallDto? = null,
    val rtc: RtcCredentialsDto? = null,
    @Json(name = "scheduled_call") val scheduledCall: ScheduledCallDto? = null,
    @Json(name = "expires_at") val expiresAt: String? = null,
    @Json(name = "server_time") val serverTime: String,
) {
    override fun toString() = "RedeemedCallInviteDto(kind=$kind, [credentials redacted])"
}
