package com.kit.wallet

import com.kit.wallet.data.remote.ApiEnvelope
import com.kit.wallet.data.remote.CapabilitiesDto
import com.kit.wallet.data.remote.CreateSupportPaymentRequest
import com.kit.wallet.data.remote.OpenSupportTicketRequest
import com.kit.wallet.data.remote.SendSupportMessageRequest
import com.kit.wallet.data.remote.SupportMessageDto
import com.kit.wallet.data.remote.SupportPaymentBeneficiaryDto
import com.kit.wallet.data.remote.SupportPaymentBeneficiaryDtoAdapter
import com.kit.wallet.data.remote.SupportProtocolDto
import com.kit.wallet.data.remote.SupportProtocolDtoAdapter
import com.kit.wallet.data.remote.SupportTicketDto
import com.kit.wallet.data.support.SupportContract
import com.kit.wallet.data.support.SupportSenderType
import com.kit.wallet.data.support.SupportTicketStatus
import com.kit.wallet.data.support.toDomain
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-level binding of the support contract: the strict hand-written adapters
 * (protocol block contains its own drift; payment beneficiary rejects drift
 * outright), the write commands' exact key sets, and the badge/status
 * projections that keep server-authored identity claims server-authored.
 */
class SupportApiContractTest {
    // Mirrors the production Moshi in StorageModule: strict support adapters
    // registered ahead of the reflective factory.
    private val moshi: Moshi = Moshi.Builder()
        .add(SupportProtocolDtoAdapter())
        .add(SupportPaymentBeneficiaryDtoAdapter())
        .add(KotlinJsonAdapterFactory())
        .build()

    private val protocolAdapter = moshi.adapter(SupportProtocolDto::class.java)
    private val beneficiaryAdapter = moshi.adapter(SupportPaymentBeneficiaryDto::class.java)

    private fun deployedBlock(
        payments: String = """{"ready":true,"beneficiary":{"kind":"company","display_name":"Kit Pay"}}""",
        attachments: String = "false",
        e2ee: String = "false",
        content: String = "\"server_readable\"",
        extra: String = "",
    ) = """
        {
          "ready": true,
          "end_to_end_encrypted": $e2ee,
          "content": $content,
          "transport": "poll",
          "attachments": $attachments,
          "payments": $payments,
          "ai": {"enabled": true}$extra
        }
    """.trimIndent()

    // --- protocols.support: strict but contained ----------------------------

    @Test
    fun `the deployed protocol shape parses exact and negotiates`() {
        val dto = protocolAdapter.fromJson(deployedBlock())

        assertNotNull(dto)
        assertTrue(dto!!.exactShape)
        val negotiated = SupportContract.negotiate(dto)
        assertNotNull(negotiated)
        assertEquals("Kit Pay", negotiated!!.companyBeneficiaryName)
    }

    @Test
    fun `an unknown key anywhere in the block clears exactShape without throwing`() {
        val dto = protocolAdapter.fromJson(deployedBlock(extra = ""","voice":true"""))

        assertFalse(dto!!.exactShape)
        assertNull(SupportContract.negotiate(dto))
    }

    @Test
    fun `a wrong primitive type clears exactShape without throwing`() {
        val dto = protocolAdapter.fromJson(deployedBlock(attachments = "\"none\""))

        assertFalse(dto!!.exactShape)
        assertNull(SupportContract.negotiate(dto))
    }

    @Test
    fun `a missing contract-required member is drift`() {
        val withoutAi = """
            {
              "ready": true,
              "end_to_end_encrypted": false,
              "content": "server_readable",
              "transport": "poll",
              "attachments": false,
              "payments": {"ready":true,"beneficiary":{"kind":"company","display_name":"Kit Pay"}}
            }
        """.trimIndent()

        val dto = protocolAdapter.fromJson(withoutAi)

        assertFalse(dto!!.exactShape)
    }

    @Test
    fun `a drifted beneficiary inside the block is contained as inexact`() {
        val extraKey = deployedBlock(
            payments = """{"ready":true,"beneficiary":{"kind":"company","display_name":"Kit Pay","wallet_id":"w-9"}}""",
        )
        val wrongKind = deployedBlock(
            payments = """{"ready":true,"beneficiary":{"kind":"customer","display_name":"Mallory"}}""",
        )

        assertFalse(protocolAdapter.fromJson(extraKey)!!.exactShape)
        assertFalse(protocolAdapter.fromJson(wrongKind)!!.exactShape)
    }

    @Test
    fun `a non-object or null block never fails the surrounding parse`() {
        assertFalse(protocolAdapter.fromJson("\"v2\"")!!.exactShape)
        assertNull(protocolAdapter.fromJson("null"))
    }

    @Test
    fun `a drifted support block darkens support alone, not the capabilities parse`() {
        val envelopeAdapter = moshi.adapter<ApiEnvelope<CapabilitiesDto>>(
            Types.newParameterizedType(ApiEnvelope::class.java, CapabilitiesDto::class.java),
        )
        val envelope = envelopeAdapter.fromJson(
            """
            {
              "ok": true,
              "data": {
                "currency": {"code": "NGN", "scale": "2"},
                "features": {"wallets": true, "support": true},
                "protocols": {"support": {"ready": "yes", "surprise": 1}}
              }
            }
            """.trimIndent(),
        )

        val capabilities = envelope!!.requireData()
        assertEquals(true, capabilities.features?.get("wallets"))
        assertNull(SupportContract.negotiate(capabilities.protocols?.support))
    }

    // --- payment beneficiary standalone: strict and terminal ----------------

    @Test
    fun `the payment response beneficiary parses only the exact company shape`() {
        val parsed = beneficiaryAdapter.fromJson(
            """{"kind":"company","display_name":"Kit Pay Commission"}""",
        )

        assertEquals("company", parsed!!.kind)
        assertEquals("Kit Pay Commission", parsed.displayName)
    }

    @Test
    fun `any beneficiary deviation fails the payment parse closed`() {
        listOf(
            // Extra key: could smuggle routing or identity detail past review.
            """{"kind":"company","display_name":"Kit Pay","account":"077"}""",
            // Non-company payee can never render as a support payment target.
            """{"kind":"customer","display_name":"Mallory"}""",
            """{"display_name":"Kit Pay"}""",
            """{"kind":"company"}""",
            """{"kind":"company","display_name":7}""",
        ).forEach { json ->
            assertThrows(json, JsonDataException::class.java) {
                beneficiaryAdapter.fromJson(json)
            }
        }
    }

    // --- write commands: exact expressible key sets --------------------------

    private fun keysOf(json: String): Set<String> {
        val mapAdapter = moshi.adapter<Map<String, Any?>>(
            Types.newParameterizedType(
                Map::class.java, String::class.java, Any::class.java,
            ),
        )
        return mapAdapter.fromJson(json)!!.keys
    }

    @Test
    fun `the payment command cannot express a destination`() {
        val json = moshi.adapter(CreateSupportPaymentRequest::class.java).toJson(
            CreateSupportPaymentRequest(
                sourceWalletId = "w-1",
                amount = "25.00",
                note = "Thanks",
            ),
        )

        assertEquals(setOf("source_wallet_id", "amount", "note"), keysOf(json))
        assertFalse(json.contains("destination"))
    }

    @Test
    fun `the open-ticket command carries exactly the contract keys`() {
        val json = moshi.adapter(OpenSupportTicketRequest::class.java).toJson(
            OpenSupportTicketRequest(
                categoryKey = "payments",
                subject = "Card failed",
                message = "It happened twice.",
                clientMessageId = "cmid-1",
            ),
        )

        assertEquals(
            setOf("category_key", "subject", "message", "client_message_id"),
            keysOf(json),
        )
        // No attachment is expressible at the type level.
        assertFalse(json.contains("media_asset_id"))
    }

    @Test
    fun `the send-message command carries exactly the contract keys`() {
        val json = moshi.adapter(SendSupportMessageRequest::class.java).toJson(
            SendSupportMessageRequest(body = "hello", clientMessageId = "cmid-2"),
        )

        assertEquals(setOf("body", "client_message_id"), keysOf(json))
    }

    // --- identity and status projections -------------------------------------

    private fun messageJson(sender: String) = """
        {
          "id": "m-1",
          "position": 4,
          "sender": $sender,
          "body": "hello",
          "attachment": null,
          "created_at": "2026-08-28T10:00:00Z"
        }
    """.trimIndent()

    @Test
    fun `only the official_support designation lights the verified badge`() {
        val adapter = moshi.adapter(SupportMessageDto::class.java)
        val designated = adapter.fromJson(
            messageJson(
                """{"type":"agent","display_name":"Kit Pay Support","official":true,
                    "automated":false,"verification":{"designation":"official_support"},
                    "agent_alias":"Ada"}""",
            ),
        )!!.toDomain()
        val wrongWord = adapter.fromJson(
            messageJson(
                """{"type":"agent","display_name":"Kit Pay Support","official":true,
                    "automated":false,"verification":{"designation":"support"}}""",
            ),
        )!!.toDomain()
        val officialFlagOnly = adapter.fromJson(
            messageJson(
                """{"type":"agent","display_name":"Kit Pay Support ✓","official":true,
                    "automated":false,"verification":null}""",
            ),
        )!!.toDomain()

        assertTrue(designated.sender.verifiedOfficialSupport)
        assertEquals("Ada", designated.sender.agentAlias)
        assertFalse(wrongWord.sender.verifiedOfficialSupport)
        // A display name or `official` flag alone never badges.
        assertFalse(officialFlagOnly.sender.verifiedOfficialSupport)
    }

    @Test
    fun `unknown sender types render neutrally, never as the customer`() {
        val unknown = moshi.adapter(SupportMessageDto::class.java).fromJson(
            messageJson(
                """{"type":"supervisor_bot","display_name":"X","official":false,
                    "automated":true,"verification":null}""",
            ),
        )!!.toDomain()

        assertEquals(SupportSenderType.UNKNOWN, unknown.sender.type)
        assertFalse(unknown.sender.verifiedOfficialSupport)
    }

    @Test
    fun `an attachment this build cannot show surfaces as undisplayable`() {
        val withAttachment = moshi.adapter(SupportMessageDto::class.java).fromJson(
            """
            {
              "id": "m-2",
              "position": 5,
              "sender": {"type":"agent","display_name":"Kit Pay Support","official":true,
                          "automated":false,"verification":{"designation":"official_support"}},
              "body": "see attached",
              "attachment": {"media_asset_id": "asset-1"},
              "created_at": "2026-08-28T10:01:00Z"
            }
            """.trimIndent(),
        )!!.toDomain()

        assertTrue(withAttachment.hasUndisplayableAttachment)
    }

    private fun ticketJson(status: String) = """
        {
          "id": "t-1",
          "reference": "SUP-2026-000123",
          "subject": "Card failed",
          "status": "$status",
          "category": {"key": "payments", "name": "Payments"},
          "support_identity": {"display_name": "Kit Pay Support", "official": true,
                                "verification": {"designation": "official_support"}},
          "agent": {"alias": "Ada", "has_avatar": true},
          "assistant_active": false,
          "message_count": 3,
          "created_at": "2026-08-28T09:00:00Z",
          "last_message_at": "2026-08-28T10:00:00Z",
          "closed": null,
          "end_to_end_encrypted": false,
          "content_visibility": "server_readable"
        }
    """.trimIndent()

    @Test
    fun `an unknown ticket status is treated exactly like closed`() {
        val adapter = moshi.adapter(SupportTicketDto::class.java)
        val open = adapter.fromJson(ticketJson("open"))!!.toDomain()
        val unknown = adapter.fromJson(ticketJson("archived"))!!.toDomain()

        assertEquals(SupportTicketStatus.OPEN, open.status)
        assertTrue(open.acceptsWrites)
        assertTrue(open.identityVerified)
        assertEquals("Ada", open.agentAlias)
        assertEquals(SupportTicketStatus.UNKNOWN, unknown.status)
        // Every write affordance keys off this single property.
        assertFalse(unknown.acceptsWrites)
    }
}
