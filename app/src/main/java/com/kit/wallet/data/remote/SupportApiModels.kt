package com.kit.wallet.data.remote

import com.squareup.moshi.FromJson
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.ToJson

// ---------------------------------------------------------------------------
// Support contract DTOs — bound 1:1 to the backend OpenAPI (`/support/...`).
// The binding rules live in docs/support-client.md: required fields are
// non-null constructor parameters (a response missing one fails to parse and
// the call fails closed), `|null` fields are nullable, and the two shapes the
// backend pins with `additionalProperties: false` — the capability protocol
// block and the payment beneficiary — are parsed by hand-written adapters
// that refuse anything but the exact deployed shape.
// ---------------------------------------------------------------------------

/**
 * The `protocols.support` capability block after structural inspection.
 *
 * Parsed only through [SupportProtocolDtoAdapter]. The adapter never throws:
 * a drifted block must darken support alone, not fail the whole capabilities
 * parse the rest of the app depends on. Instead every anomaly — an unknown
 * key anywhere in the block, a wrong primitive type, a beneficiary that is
 * not exactly the two-key company shape — clears [exactShape], and the
 * negotiation in `SupportContract` treats the block as incompatible.
 */
@JsonClass(generateAdapter = false)
data class SupportProtocolDto(
    val ready: Boolean? = null,
    val endToEndEncrypted: Boolean? = null,
    val content: String? = null,
    val transport: String? = null,
    val attachments: Boolean? = null,
    val paymentsReady: Boolean? = null,
    val paymentsBeneficiary: SupportPaymentBeneficiaryDto? = null,
    val aiEnabled: Boolean? = null,
    /** True only when the block matched the deployed contract shape exactly. */
    val exactShape: Boolean = false,
)

/**
 * Server-authored beneficiary of a support payment — always the company.
 *
 * Parsed only through [SupportPaymentBeneficiaryDtoAdapter], which enforces
 * the contract's `additionalProperties: false`: exactly `kind` (const
 * `company`) and `display_name`. Anything else is a coordinated-contract
 * violation and must fail the parse rather than render.
 */
@JsonClass(generateAdapter = false)
data class SupportPaymentBeneficiaryDto(
    val kind: String,
    val displayName: String,
)

/** Strict two-key company beneficiary; any deviation throws and fails the call closed. */
class SupportPaymentBeneficiaryDtoAdapter {
    @FromJson
    fun fromJson(reader: JsonReader): SupportPaymentBeneficiaryDto = readStrictBeneficiary(reader)

    @ToJson
    fun toJson(writer: JsonWriter, value: SupportPaymentBeneficiaryDto) {
        writer.beginObject()
        writer.name("kind").value(value.kind)
        writer.name("display_name").value(value.displayName)
        writer.endObject()
    }
}

internal fun readStrictBeneficiary(reader: JsonReader): SupportPaymentBeneficiaryDto {
    var kind: String? = null
    var displayName: String? = null
    reader.beginObject()
    while (reader.hasNext()) {
        when (val name = reader.nextName()) {
            "kind" -> {
                if (reader.peek() != JsonReader.Token.STRING) {
                    throw JsonDataException("support beneficiary 'kind' must be a string")
                }
                kind = reader.nextString()
            }
            "display_name" -> {
                if (reader.peek() != JsonReader.Token.STRING) {
                    throw JsonDataException("support beneficiary 'display_name' must be a string")
                }
                displayName = reader.nextString()
            }
            else -> throw JsonDataException("support beneficiary carries unexpected key '$name'")
        }
    }
    reader.endObject()
    if (kind != "company") {
        throw JsonDataException("support beneficiary 'kind' must be exactly 'company'")
    }
    return SupportPaymentBeneficiaryDto(
        kind = kind,
        displayName = displayName
            ?: throw JsonDataException("support beneficiary requires 'display_name'"),
    )
}

/**
 * Contained strict parse of `protocols.support`. Never throws; see
 * [SupportProtocolDto]. Registered ahead of the reflective factory.
 */
class SupportProtocolDtoAdapter {
    @FromJson
    fun fromJson(reader: JsonReader): SupportProtocolDto? {
        if (reader.peek() == JsonReader.Token.NULL) return reader.nextNull()
        if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
            reader.skipValue()
            return SupportProtocolDto()
        }
        var exact = true
        var ready: Boolean? = null
        var endToEndEncrypted: Boolean? = null
        var content: String? = null
        var transport: String? = null
        var attachments: Boolean? = null
        var sawPayments = false
        var paymentsReady: Boolean? = null
        var paymentsBeneficiary: SupportPaymentBeneficiaryDto? = null
        var sawAi = false
        var aiEnabled: Boolean? = null

        fun JsonReader.readBooleanOrFlag(): Boolean? =
            if (peek() == JsonReader.Token.BOOLEAN) {
                nextBoolean()
            } else {
                exact = false
                skipValue()
                null
            }

        fun JsonReader.readStringOrFlag(): String? =
            if (peek() == JsonReader.Token.STRING) {
                nextString()
            } else {
                exact = false
                skipValue()
                null
            }

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "ready" -> ready = reader.readBooleanOrFlag()
                "end_to_end_encrypted" -> endToEndEncrypted = reader.readBooleanOrFlag()
                "content" -> content = reader.readStringOrFlag()
                "transport" -> transport = reader.readStringOrFlag()
                "attachments" -> attachments = reader.readBooleanOrFlag()
                "payments" -> {
                    sawPayments = true
                    if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
                        exact = false
                        reader.skipValue()
                    } else {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "ready" -> paymentsReady = reader.readBooleanOrFlag()
                                "beneficiary" -> {
                                    // Strictness with containment: try the exact parse on a
                                    // peeked reader, then advance past the value either way.
                                    paymentsBeneficiary = try {
                                        reader.peekJson().use(::readStrictBeneficiary)
                                    } catch (_: Exception) {
                                        exact = false
                                        null
                                    }
                                    reader.skipValue()
                                }
                                else -> {
                                    exact = false
                                    reader.skipValue()
                                }
                            }
                        }
                        reader.endObject()
                    }
                }
                "ai" -> {
                    sawAi = true
                    if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
                        exact = false
                        reader.skipValue()
                    } else {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "enabled" -> aiEnabled = reader.readBooleanOrFlag()
                                else -> {
                                    exact = false
                                    reader.skipValue()
                                }
                            }
                        }
                        reader.endObject()
                    }
                }
                else -> {
                    exact = false
                    reader.skipValue()
                }
            }
        }
        reader.endObject()

        // Every member of the block is contract-required; an absent member is drift.
        if (ready == null || endToEndEncrypted == null || content == null ||
            transport == null || attachments == null ||
            !sawPayments || paymentsReady == null || paymentsBeneficiary == null ||
            !sawAi || aiEnabled == null
        ) {
            exact = false
        }
        return SupportProtocolDto(
            ready = ready,
            endToEndEncrypted = endToEndEncrypted,
            content = content,
            transport = transport,
            attachments = attachments,
            paymentsReady = paymentsReady,
            paymentsBeneficiary = paymentsBeneficiary,
            aiEnabled = aiEnabled,
            exactShape = exact,
        )
    }

    @ToJson
    fun toJson(writer: JsonWriter, value: SupportProtocolDto?) {
        if (value == null) {
            writer.nullValue()
            return
        }
        writer.beginObject()
        writer.name("ready").value(value.ready)
        writer.name("end_to_end_encrypted").value(value.endToEndEncrypted)
        writer.name("content").value(value.content)
        writer.name("transport").value(value.transport)
        writer.name("attachments").value(value.attachments)
        writer.name("payments").beginObject()
        writer.name("ready").value(value.paymentsReady)
        writer.name("beneficiary")
        val beneficiary = value.paymentsBeneficiary
        if (beneficiary == null) {
            writer.nullValue()
        } else {
            writer.beginObject()
            writer.name("kind").value(beneficiary.kind)
            writer.name("display_name").value(beneficiary.displayName)
            writer.endObject()
        }
        writer.endObject()
        writer.name("ai").beginObject()
        writer.name("enabled").value(value.aiEnabled)
        writer.endObject()
        writer.endObject()
    }
}

// --- Read models -----------------------------------------------------------

@JsonClass(generateAdapter = false)
data class SupportCategoryDto(
    val id: String,
    val key: String,
    val name: String,
    val description: String?,
)

@JsonClass(generateAdapter = false)
data class SupportCategoryListDto(
    val items: List<SupportCategoryDto>,
)

/** Server-authored verification metadata; the badge renders from this structure only. */
@JsonClass(generateAdapter = false)
data class SupportVerificationBadgeDto(
    val designation: String,
)

@JsonClass(generateAdapter = false)
data class SupportOfficialIdentityDto(
    @Json(name = "display_name") val displayName: String,
    val official: Boolean,
    val verification: SupportVerificationBadgeDto,
)

/** Ticket-scoped view of the assigned human agent: work alias and avatar hint only. */
@JsonClass(generateAdapter = false)
data class SupportTicketAgentDto(
    val alias: String,
    @Json(name = "has_avatar") val hasAvatar: Boolean,
)

@JsonClass(generateAdapter = false)
data class SupportTicketClosedDto(
    val at: String,
    @Json(name = "reason_code") val reasonCode: String?,
)

@JsonClass(generateAdapter = false)
data class SupportTicketCategoryDto(
    val key: String,
    val name: String,
)

@JsonClass(generateAdapter = false)
data class SupportTicketDto(
    val id: String,
    val reference: String,
    val subject: String,
    val status: String,
    val category: SupportTicketCategoryDto,
    @Json(name = "support_identity") val supportIdentity: SupportOfficialIdentityDto,
    val agent: SupportTicketAgentDto?,
    @Json(name = "assistant_active") val assistantActive: Boolean,
    @Json(name = "message_count") val messageCount: Int,
    @Json(name = "created_at") val createdAt: String,
    @Json(name = "last_message_at") val lastMessageAt: String?,
    val closed: SupportTicketClosedDto?,
    @Json(name = "end_to_end_encrypted") val endToEndEncrypted: Boolean,
    @Json(name = "content_visibility") val contentVisibility: String,
)

@JsonClass(generateAdapter = false)
data class SupportTicketListDto(
    val items: List<SupportTicketDto>,
)

@JsonClass(generateAdapter = false)
data class SupportMessageSenderDto(
    val type: String,
    @Json(name = "display_name") val displayName: String,
    val official: Boolean,
    val automated: Boolean,
    val verification: SupportVerificationBadgeDto?,
    @Json(name = "agent_alias") val agentAlias: String? = null,
)

@JsonClass(generateAdapter = false)
data class SupportMessageAttachmentDto(
    @Json(name = "media_asset_id") val mediaAssetId: String,
)

@JsonClass(generateAdapter = false)
data class SupportMessageDto(
    val id: String,
    val position: Long,
    val sender: SupportMessageSenderDto,
    val body: String,
    val attachment: SupportMessageAttachmentDto?,
    @Json(name = "created_at") val createdAt: String,
)

@JsonClass(generateAdapter = false)
data class SupportTicketDetailDto(
    val ticket: SupportTicketDto,
    val messages: List<SupportMessageDto>,
    @Json(name = "messages_has_more") val messagesHasMore: Boolean,
    @Json(name = "messages_next_after_position") val messagesNextAfterPosition: Long?,
)

@JsonClass(generateAdapter = false)
data class SupportMessagePageDto(
    val items: List<SupportMessageDto>,
    val ticket: SupportTicketDto,
)

// --- Write models ----------------------------------------------------------

/**
 * Opening command. `media_asset_id` is deliberately not expressible: the
 * deployed handshake pins `attachments` to exactly false, so this client
 * cannot form an attachment-bearing request at the type level.
 */
@JsonClass(generateAdapter = false)
data class OpenSupportTicketRequest(
    @Json(name = "category_key") val categoryKey: String,
    val subject: String,
    val message: String,
    @Json(name = "client_message_id") val clientMessageId: String,
)

@JsonClass(generateAdapter = false)
data class SendSupportMessageRequest(
    val body: String,
    @Json(name = "client_message_id") val clientMessageId: String,
)

/**
 * Support payment command. No destination is expressible — the server routes
 * every support payment to the company commission wallet and rejects any
 * destination key outright (`destination_wallet_id: false` in the contract).
 */
@JsonClass(generateAdapter = false)
data class CreateSupportPaymentRequest(
    @Json(name = "source_wallet_id") val sourceWalletId: String,
    val amount: String,
    val note: String?,
)

@JsonClass(generateAdapter = false)
data class SupportPaymentTransactionDto(
    val id: String,
    val reference: String,
    val amount: String,
    val currency: String,
    val status: String,
    @Json(name = "occurred_at") val occurredAt: String,
)

@JsonClass(generateAdapter = false)
data class SupportPaymentDto(
    val transaction: SupportPaymentTransactionDto,
    val beneficiary: SupportPaymentBeneficiaryDto,
    @Json(name = "ticket_payment_id") val ticketPaymentId: String,
)
