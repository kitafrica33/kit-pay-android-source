package com.kit.wallet

import com.kit.wallet.data.remote.AcknowledgeMessageDeliveryRequest
import com.kit.wallet.data.remote.ConsumeMessagingKeyBundlesRequest
import com.kit.wallet.data.remote.ConsumedMessagingKeyBundlesDto
import com.kit.wallet.data.remote.CreateDirectMessagingConversationRequest
import com.kit.wallet.data.remote.ENCRYPTED_MESSAGE_KIND
import com.kit.wallet.data.remote.EncryptedDeviceEnvelopeRequest
import com.kit.wallet.data.remote.MarkMessagingConversationReadRequest
import com.kit.wallet.data.remote.MessageDeliveryAcknowledgementDto
import com.kit.wallet.data.remote.MessagingConversationListDto
import com.kit.wallet.data.remote.MessagingDeviceRosterDto
import com.kit.wallet.data.remote.MessagingKeyStatusDto
import com.kit.wallet.data.remote.MessagingOneTimePrekeyRequest
import com.kit.wallet.data.remote.MessagingPqPrekeyRequest
import com.kit.wallet.data.remote.MessagingReadReceiptDto
import com.kit.wallet.data.remote.MessagingSignedPrekeyRequest
import com.kit.wallet.data.remote.MessagingSyncDto
import com.kit.wallet.data.remote.PublishMessagingKeyBundleRequest
import com.kit.wallet.data.remote.SecureMessagingWireApi
import com.kit.wallet.data.remote.SendEncryptedMessageRequest
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class MessagingApiContractTest {
    private lateinit var server: MockWebServer
    private lateinit var api: SecureMessagingWireApi
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(SecureMessagingWireApi::class.java)
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `retrofit uses the exact dormant messaging paths and ciphertext-only bodies`() = runTest {
        repeat(11) { server.enqueue(successResponse()) }

        api.messagingKeyStatus()
        assertRequest("GET", "/api/kit-wallet/v1/messaging/keys/status")

        api.publishMessagingKeyBundle(publishRequest())
        val publish = assertRequest("PUT", "/api/kit-wallet/v1/messaging/keys")
        assertTrue(publish.contains("\"protocol_version\":\"v2\""))
        assertFalse(publish.contains("private_key"))

        api.messagingConversations()
        assertRequest("GET", "/api/kit-wallet/v1/messaging/conversations")

        api.createDirectMessagingConversation(
            CreateDirectMessagingConversationRequest(memberIds = listOf(OTHER_USER_ID)),
        )
        val conversation = assertRequest("POST", "/api/kit-wallet/v1/messaging/conversations")
        assertTrue(conversation.contains("\"type\":\"direct\""))
        assertTrue(conversation.contains("\"member_ids\":[\"$OTHER_USER_ID\"]"))

        api.messagingDeviceRoster(CONVERSATION_ID)
        assertRequest(
            "GET",
            "/api/kit-wallet/v1/messaging/conversations/$CONVERSATION_ID/device-roster",
        )

        api.historicalMessagingDeviceRoster(CONVERSATION_ID, ROSTER_REVISION)
        assertRequest(
            "GET",
            "/api/kit-wallet/v1/messaging/conversations/$CONVERSATION_ID/" +
                "device-roster/$ROSTER_REVISION",
        )

        api.consumeMessagingKeyBundles(
            CONVERSATION_ID,
            ConsumeMessagingKeyBundlesRequest(deviceIds = listOf(DEVICE_ID)),
        )
        val consume = assertRequest(
            "POST",
            "/api/kit-wallet/v1/messaging/conversations/$CONVERSATION_ID/key-bundles",
        )
        assertEquals("{\"device_ids\":[\"$DEVICE_ID\"]}", consume)

        api.sendEncryptedMessage(
            CONVERSATION_ID,
            SendEncryptedMessageRequest(
                clientMessageId = MESSAGE_ID,
                rosterRevision = ROSTER_REVISION,
                kind = ENCRYPTED_MESSAGE_KIND,
                envelopes = listOf(
                    EncryptedDeviceEnvelopeRequest(
                        recipientDeviceId = DEVICE_ID,
                        envelopeType = "signal-prekey-v2",
                        ciphertext = "AQID",
                    ),
                ),
            ),
        )
        val send = assertRequest(
            "POST",
            "/api/kit-wallet/v1/messaging/conversations/$CONVERSATION_ID/messages",
        )
        assertTrue(send.contains("\"ciphertext\":\"AQID\""))
        assertTrue(send.contains("\"roster_revision\":\"$ROSTER_REVISION\""))
        assertFalse(send.contains("\"plaintext\""))
        assertFalse(send.contains("\"text\""))
        assertFalse(send.contains("\"body\""))
        assertFalse(send.contains("\"message\""))

        api.syncEncryptedMessages(cursor = "cursor-value", limit = 25)
        assertRequest(
            "GET",
            "/api/kit-wallet/v1/messaging/sync?cursor=cursor-value&limit=25",
        )

        api.acknowledgeMessageDelivery(AcknowledgeMessageDeliveryRequest(listOf(MESSAGE_ID)))
        val acknowledgement = assertRequest(
            "POST",
            "/api/kit-wallet/v1/messaging/messages/delivery-acks",
        )
        assertEquals("{\"message_ids\":[\"$MESSAGE_ID\"]}", acknowledgement)

        api.markMessagingConversationRead(
            CONVERSATION_ID,
            MarkMessagingConversationReadRequest(MESSAGE_ID),
        )
        val receipt = assertRequest(
            "POST",
            "/api/kit-wallet/v1/messaging/conversations/$CONVERSATION_ID/read-receipts",
        )
        assertEquals("{\"message_id\":\"$MESSAGE_ID\"}", receipt)
    }

    @Test
    fun `Moshi decodes v2 key bundles roster ciphertext events and receipts`() {
        val roster = decode<MessagingDeviceRosterDto>(ROSTER_JSON)
        val bundles = decode<ConsumedMessagingKeyBundlesDto>(BUNDLES_JSON)
        val sync = decode<MessagingSyncDto>(SYNC_JSON)
        val acknowledgement = decode<MessageDeliveryAcknowledgementDto>(ACKNOWLEDGEMENT_JSON)
        val receipt = decode<MessagingReadReceiptDto>(READ_RECEIPT_JSON)

        assertEquals(CONVERSATION_ID, roster.conversationId)
        assertEquals(ROSTER_REVISION, roster.rosterRevision)
        assertEquals("sha256", roster.hashAlgorithm)
        assertEquals(DEVICE_ID, roster.devices?.first()?.deviceId)
        assertEquals(42, roster.devices?.first()?.registrationId)
        assertEquals("BQUG", roster.devices?.first()?.signedPrekey?.publicKey)
        assertEquals("2026-07-19T19:00:00Z", roster.devices?.first()?.identityKeyChangedAt)
        assertNull(roster.devices?.last())
        assertEquals("v2", bundles.bundles?.first()?.protocolVersion)
        assertNull(bundles.bundles?.first()?.oneTimePrekey)
        assertEquals(9, bundles.bundles?.first()?.pqPrekey?.prekeyId)
        assertEquals("AQID", sync.events?.first()?.data?.envelope?.ciphertext)
        assertEquals(2, sync.events?.first()?.data?.senderSignalDeviceId)
        assertEquals(42, sync.events?.first()?.data?.senderRegistrationId)
        assertEquals("v2", sync.events?.first()?.data?.senderProtocolVersion)
        assertEquals(4, sync.events?.first()?.data?.senderBundleVersion)
        assertEquals("c".repeat(64), sync.events?.first()?.data?.senderIdentityKeySha256)
        assertEquals(ROSTER_REVISION, sync.events?.first()?.data?.rosterRevision)
        assertEquals(MESSAGE_ID, sync.events?.get(1)?.data?.lastReadMessageId)
        assertNull(sync.events?.last())
        assertEquals("delivered_to_device", acknowledgement.deliveryState)
        assertEquals(1, acknowledgement.acknowledgedCount)
        assertEquals(MESSAGE_ID, acknowledgement.items?.first()?.messageId)
        assertEquals(MESSAGE_ID, receipt.lastReadMessageId)
    }

    @Test
    fun `nullable messaging responses parse without inventing enrollment roster or delivery state`() {
        val keyStatus = decode<MessagingKeyStatusDto>(
            """{"enrolled":null,"protocol_version":null,"available_ec_one_time_prekeys":null,"needs_replenishment":null}""",
        )
        val conversations = decode<MessagingConversationListDto>("""{"items":null}""")
        val roster = decode<MessagingDeviceRosterDto>(
            """{"devices":null,"roster_revision":null,"roster_hash":null,"hash_algorithm":null}""",
        )
        val bundles = decode<ConsumedMessagingKeyBundlesDto>("""{"bundles":null}""")
        val sync = decode<MessagingSyncDto>("""{"events":null,"page":null}""")
        val acknowledgement = decode<MessageDeliveryAcknowledgementDto>(
            """{"delivery_state":null,"device_id":null,"acknowledged_count":null,"newly_acknowledged_count":null,"items":null}""",
        )
        val receipt = decode<MessagingReadReceiptDto>(
            """{"conversation_id":null,"user_id":null,"last_read_message_id":null,"read_at":null}""",
        )

        assertNull(keyStatus.enrolled)
        assertNull(keyStatus.needsReplenishment)
        assertNull(conversations.items)
        assertNull(roster.devices)
        assertNull(bundles.bundles)
        assertNull(sync.events)
        assertNull(acknowledgement.deliveryState)
        assertNull(acknowledgement.acknowledgedCount)
        assertNull(receipt.lastReadMessageId)
    }

    @Test
    fun `outgoing models reject protocol downgrade and non-direct creation`() {
        assertThrows(IllegalArgumentException::class.java) {
            publishRequest().copy(protocolVersion = "v1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            EncryptedDeviceEnvelopeRequest(
                recipientDeviceId = DEVICE_ID,
                envelopeType = "signal-message-v1",
                ciphertext = "AQID",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CreateDirectMessagingConversationRequest(
                memberIds = listOf(OTHER_USER_ID, "44444444-4444-4444-8444-444444444444"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SendEncryptedMessageRequest(
                clientMessageId = MESSAGE_ID,
                rosterRevision = "stale-or-malformed",
                kind = ENCRYPTED_MESSAGE_KIND,
                envelopes = listOf(
                    EncryptedDeviceEnvelopeRequest(DEVICE_ID, "signal-message-v2", "AQID"),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SendEncryptedMessageRequest(
                clientMessageId = MESSAGE_ID,
                rosterRevision = ROSTER_REVISION,
                kind = ENCRYPTED_MESSAGE_KIND,
                envelopes = listOf(
                    EncryptedDeviceEnvelopeRequest(DEVICE_ID, "signal-message-v2", "AQID"),
                    EncryptedDeviceEnvelopeRequest(DEVICE_ID, "signal-message-v2", "BAUG"),
                ),
            )
        }
    }

    private fun publishRequest() = PublishMessagingKeyBundleRequest(
        protocolVersion = "v2",
        registrationId = 42,
        identityKey = "BQID",
        signedPrekey = MessagingSignedPrekeyRequest(
            prekeyId = 7,
            publicKey = "BQUG",
            signature = "BwgJ",
        ),
        oneTimePrekeys = listOf(MessagingOneTimePrekeyRequest(8, "BQoL")),
        pqPrekeys = listOf(MessagingPqPrekeyRequest(9, "CAwN", "Dg8Q")),
        pqLastResortPrekey = MessagingPqPrekeyRequest(10, "CBEQ", "ExQV"),
    )

    private fun successResponse() = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody("""{"ok":true,"data":{}}""")

    private fun assertRequest(method: String, path: String): String {
        val request = server.takeRequest()
        assertEquals(method, request.method)
        assertEquals(path, request.path)
        return request.body.readUtf8()
    }

    private inline fun <reified T> decode(json: String): T = requireNotNull(
        moshi.adapter(T::class.java).fromJson(json),
    )

    private companion object {
        const val CONVERSATION_ID = "11111111-1111-4111-8111-111111111111"
        const val OTHER_USER_ID = "22222222-2222-4222-8222-222222222222"
        const val DEVICE_ID = "33333333-3333-4333-8333-333333333333"
        const val MESSAGE_ID = "55555555-5555-4555-8555-555555555555"
        const val ROSTER_REVISION = "v1:sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

        val ROSTER_JSON = """
            {
              "conversation_id":"$CONVERSATION_ID",
              "roster_revision":"$ROSTER_REVISION",
              "roster_hash":"${"a".repeat(64)}",
              "hash_algorithm":"sha256",
              "devices":[{
                "device_id":"$DEVICE_ID",
                "signal_device_id":2,
                "user_id":"$OTHER_USER_ID",
                "registration_id":42,
                "protocol_version":"v2",
                "bundle_version":4,
                "identity_key":"BQID",
                "identity_key_sha256":"${"a".repeat(64)}",
                "signed_prekey":{
                  "prekey_id":7,
                  "public_key":"BQUG",
                  "public_key_sha256":"${"b".repeat(64)}",
                  "signature":"BwgJ"
                },
                "published_at":"2026-07-19T19:00:00Z",
                "rotated_at":null,
                "identity_key_changed_at":"2026-07-19T19:00:00Z",
                "bundle_version_changed_at":"2026-07-19T19:00:00Z"
              },null]
            }
        """.trimIndent()

        val BUNDLES_JSON = """
            {"bundles":[{
              "device_id":"$DEVICE_ID",
              "signal_device_id":2,
              "user_id":"$OTHER_USER_ID",
              "protocol_version":"v2",
              "registration_id":42,
              "identity_key":"BQID",
              "identity_key_sha256":"${"a".repeat(64)}",
              "signed_prekey":{"prekey_id":7,"public_key":"BQUG","signature":"BwgJ"},
              "one_time_prekey":null,
              "pq_prekey":{"prekey_id":9,"public_key":"CAwN","signature":"Dg8Q"},
              "bundle_version":4,
              "available_ec_one_time_prekeys":19,
              "available_pq_one_time_prekeys":18,
              "needs_replenishment":true,
              "is_current_device":false,
              "published_at":"2026-07-19T19:00:00Z",
              "rotated_at":null,
              "transparency":null
            }]}
        """.trimIndent()

        val SYNC_JSON = """
            {
              "events":[
                {
                  "id":"1",
                  "type":"message.created",
                  "conversation_id":"$CONVERSATION_ID",
                  "resource_type":"message",
                  "resource_id":"$MESSAGE_ID",
                  "data":{
                    "id":"$MESSAGE_ID",
                    "conversation_id":"$CONVERSATION_ID",
                    "client_message_id":"66666666-6666-4666-8666-666666666666",
                    "sender":{"id":"$OTHER_USER_ID","name":"Amina"},
                    "sender_device_id":"$DEVICE_ID",
                    "sender_signal_device_id":2,
                    "sender_registration_id":42,
                    "sender_protocol_version":"v2",
                    "sender_bundle_version":4,
                    "sender_identity_key_sha256":"${"c".repeat(64)}",
                    "roster_revision":"$ROSTER_REVISION",
                    "kind":"encrypted",
                    "reply_to_message_id":null,
                    "envelope":{
                      "recipient_device_id":"$DEVICE_ID",
                      "envelope_type":"signal-prekey-v2",
                      "ciphertext":"AQID",
                      "ciphertext_sha256":"${"b".repeat(64)}"
                    },
                    "attachments":[],
                    "reactions":[],
                    "sent_at":"2026-07-19T20:00:00Z",
                    "revoked_at":null
                  },
                  "occurred_at":"2026-07-19T20:00:00Z"
                },
                {
                  "id":"2",
                  "type":"read_receipt.updated",
                  "conversation_id":"$CONVERSATION_ID",
                  "resource_type":"read_receipt",
                  "resource_id":"$OTHER_USER_ID",
                  "data":{
                    "user_id":"$OTHER_USER_ID",
                    "last_read_message_id":"$MESSAGE_ID",
                    "read_at":"2026-07-19T20:01:00Z"
                  },
                  "occurred_at":"2026-07-19T20:01:00Z"
                },
                null
              ],
              "page":{"next_cursor":"next","has_more":false,"limit":50}
            }
        """.trimIndent()

        val ACKNOWLEDGEMENT_JSON = """
            {
              "delivery_state":"delivered_to_device",
              "device_id":"$DEVICE_ID",
              "acknowledged_count":1,
              "newly_acknowledged_count":1,
              "items":[{
                "message_id":"$MESSAGE_ID",
                "delivered_to_device_at":"2026-07-19T20:02:00Z"
              }]
            }
        """.trimIndent()

        val READ_RECEIPT_JSON = """
            {
              "conversation_id":"$CONVERSATION_ID",
              "user_id":"$OTHER_USER_ID",
              "last_read_message_id":"$MESSAGE_ID",
              "read_at":"2026-07-19T20:03:00Z"
            }
        """.trimIndent()
    }
}
