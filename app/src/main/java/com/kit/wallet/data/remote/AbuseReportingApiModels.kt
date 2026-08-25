package com.kit.wallet.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/** Plaintext is included only after the reporter explicitly selects this exact message. */
@JsonClass(generateAdapter = false)
data class AbuseReportSelectedMessageDto(
    @Json(name = "message_id") val messageId: String,
    val plaintext: String,
)

@JsonClass(generateAdapter = false)
data class AbuseReportConsentDto(
    @Json(name = "share_report_with_moderators")
    val shareReportWithModerators: Boolean,
    @Json(name = "share_selected_message_plaintext")
    val shareSelectedMessagePlaintext: Boolean,
)

@JsonClass(generateAdapter = false)
data class CreateAbuseReportRequestDto(
    @Json(name = "target_type") val targetType: String,
    @Json(name = "reported_user_id") val reportedUserId: String,
    @Json(name = "conversation_id") val conversationId: String,
    @Json(name = "message_id") val messageId: String? = null,
    @Json(name = "reason_code") val reasonCode: String,
    @Json(name = "reporter_note") val reporterNote: String? = null,
    @Json(name = "selected_messages")
    val selectedMessages: List<AbuseReportSelectedMessageDto>? = null,
    val consent: AbuseReportConsentDto,
)

/** Nullable at the wire boundary so a malformed success response fails in one audited mapper. */
@JsonClass(generateAdapter = false)
data class AbuseReportReceiptDto(
    val id: String? = null,
    val status: String? = null,
    @Json(name = "target_type") val targetType: String? = null,
    @Json(name = "reason_code") val reasonCode: String? = null,
    @Json(name = "conversation_id") val conversationId: String? = null,
    @Json(name = "message_id") val messageId: String? = null,
    @Json(name = "selected_message_count") val selectedMessageCount: Int? = null,
    @Json(name = "submitted_at") val submittedAt: String? = null,
)
