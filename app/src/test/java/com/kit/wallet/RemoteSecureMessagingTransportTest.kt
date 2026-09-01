package com.kit.wallet

import com.kit.wallet.data.messaging.RemoteSecureMessagingTransport
import com.kit.wallet.data.messaging.FailClosedSecureMessagingCryptoTransaction
import com.kit.wallet.data.messaging.LibSignalCompanionDirection
import com.kit.wallet.data.messaging.LibSignalCompanionStateReader
import com.kit.wallet.data.messaging.OpaqueCryptoBytes
import com.kit.wallet.data.messaging.SecureMessagingActivationCapability
import com.kit.wallet.data.messaging.SecureMessagingCommittedResult
import com.kit.wallet.data.messaging.SecureMessagingConversationCapabilityUnavailableException
import com.kit.wallet.data.messaging.SecureMessagingCompanionStateIntent
import com.kit.wallet.data.messaging.SecureMessagingCryptoAddress
import com.kit.wallet.data.messaging.SecureMessagingCryptoOperation
import com.kit.wallet.data.messaging.SecureMessagingCryptoWireMapper
import com.kit.wallet.data.messaging.SecureMessagingDecryptionRequest
import com.kit.wallet.data.messaging.SecureMessagingEnvelopeKind
import com.kit.wallet.data.messaging.SecureMessagingEncryptedSend
import com.kit.wallet.data.messaging.SecureMessagingEncryptionRequest
import com.kit.wallet.data.messaging.SecureMessagingEncryptionPlan
import com.kit.wallet.data.messaging.SecureMessagingPreparedEnvelope
import com.kit.wallet.data.messaging.SecureMessagingPreparedFanout
import com.kit.wallet.data.messaging.SecureMessagingLifecycleGuard
import com.kit.wallet.data.messaging.SecureMessagingProtocolUnavailableException
import com.kit.wallet.data.messaging.SecureMessagingProjectionDeliveryState
import com.kit.wallet.data.messaging.SecureMessagingProjectionStore
import com.kit.wallet.data.messaging.SecureMessagingProvisioningPlan
import com.kit.wallet.data.messaging.SecureMessagingRemoteContext
import com.kit.wallet.data.messaging.SecureMessagingSessionEstablishmentRequest
import com.kit.wallet.data.messaging.SecureMessagingSessionBinding
import com.kit.wallet.data.messaging.SecureMessagingSessionFence
import com.kit.wallet.data.remote.CursorPageDto
import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.ConsumedMessagingKeyBundleDto
import com.kit.wallet.data.remote.ConsumedMessagingKeyBundlesDto
import com.kit.wallet.data.remote.EncryptedMessageEnvelopeDto
import com.kit.wallet.data.remote.EncryptedMessageSenderDto
import com.kit.wallet.data.remote.EncryptedMessageDto
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.MessageDeliveryAcknowledgementDto
import com.kit.wallet.data.remote.MessageDeliveryReceiptDto
import com.kit.wallet.data.remote.MessagingKeyTransparencyDto
import com.kit.wallet.data.remote.MessagingReadReceiptDto
import com.kit.wallet.data.remote.MessagingOneTimePrekeyDto
import com.kit.wallet.data.remote.MessagingPqPrekeyDto
import com.kit.wallet.data.remote.MESSAGING_MEDIA_MESSAGE_V2_DEVICE_CAPABILITY
import com.kit.wallet.data.remote.MESSAGING_MESSAGE_EDITS_DEVICE_CAPABILITY
import com.kit.wallet.data.remote.MESSAGING_REACTIONS_DEVICE_CAPABILITY
import com.kit.wallet.data.remote.MessagingDeviceClientDto
import com.kit.wallet.data.remote.MediaMessageProtocolDtoAdapter
import com.kit.wallet.data.remote.ResumableAttachmentProtocolDtoAdapter
import com.kit.wallet.data.remote.MessagingDeviceRosterDto
import com.kit.wallet.data.remote.MessagingDeviceRosterEntryDto
import com.kit.wallet.data.remote.MessagingSignedPrekeyDto
import com.kit.wallet.data.remote.MessagingSyncDto
import com.kit.wallet.data.remote.MessagingSyncEventDataDto
import com.kit.wallet.data.remote.MessagingSyncEventDto
import com.kit.wallet.data.remote.SecureMessagingWireApi
import com.kit.wallet.data.remote.SessionHeaderInterceptor
import com.kit.wallet.data.session.SessionInvalidatedException
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.nio.charset.StandardCharsets
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit

class RemoteSecureMessagingTransportTest {
    private lateinit var server: MockWebServer
    private lateinit var transport: RemoteSecureMessagingTransport
    private lateinit var moshi: Moshi

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        // Registered ahead of the reflective factory, exactly as the app's Moshi is built: the
        // `media_message` block must decode tolerantly here too, or a malformed advertisement
        // would fail the whole capabilities document instead of turning one feature off.
        moshi = Moshi.Builder()
            .add(MediaMessageProtocolDtoAdapter())
            .add(ResumableAttachmentProtocolDtoAdapter())
            .add(KotlinJsonAdapterFactory())
            .build()
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
        val api = retrofit.create(KitWalletApi::class.java)
        val messagingApi = retrofit.create(SecureMessagingWireApi::class.java)
        transport = RemoteSecureMessagingTransport(api, messagingApi, ApiCallExecutor(moshi))
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `ready gate requires the exact enabled post quantum protocol before returning a handle`() = runTest {
        val (lifecycle, fence) = newActivation()
        enqueueActivation()

        val session = transport.openSession(lifecycle, fence)

        assertEquals(BINDING, session.binding)
        assertActivationRequests()

        val (downgradedLifecycle, downgradedFence) = newActivation()
        server.enqueue(jsonResponse(READY_CAPABILITIES.replace("\"ready\":true", "\"ready\":false")))
        val failure = runCatching {
            transport.openSession(downgradedLifecycle, downgradedFence)
        }.exceptionOrNull()
        assertTrue(failure is SecureMessagingProtocolUnavailableException)
        assertEquals("/api/kit-wallet/v1/capabilities", server.takeRequest().path)
    }

    @Test
    fun `activation binds profile and current device to the same login fence`() = runTest {
        val (lifecycle, fence) = newActivation()
        enqueueActivation()

        val session = transport.openSession(lifecycle, fence)

        assertEquals(CURRENT_USER_ID, session.binding.userId)
        assertEquals(CURRENT_DEVICE_ID, session.binding.serverDeviceId)
        assertActivationRequests()
    }

    @Test
    fun `mutating conversation operation makes no request until lifecycle is ready`() = runTest {
        val (lifecycle, fence) = newActivation()
        enqueueActivation()
        val session = transport.openSession(lifecycle, fence)
        assertActivationRequests()

        val failure = runCatching { session.createDirectConversation(PEER_USER_ID) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `removing a member and leaving remain available when the group rollout gate is off`() = runTest {
        val (lifecycle, fence) = newActivation()
        server.enqueue(
            jsonResponse(
                READY_CAPABILITIES.replace("\"messaging_groups\":true", "\"messaging_groups\":false"),
            ),
        )
        server.enqueue(jsonResponse(PROFILE))
        server.enqueue(jsonResponse(DEVICES))
        val session = transport.openSession(lifecycle, fence)
        assertActivationRequests()
        finishActivation(lifecycle, fence)
        server.enqueue(jsonResponse(GROUP_CONVERSATIONS))
        val group = session.conversations().single()
        assertEquals("/api/kit-wallet/v1/messaging/conversations", server.takeRequest().path)

        server.enqueue(jsonResponse("""{"ok":true,"data":${GROUP_CONVERSATION_JSON.replace(THIRD_MEMBER_ROW, "")}}"""))
        val afterRemoval = session.removeGroupMember(group, OTHER_USER_ID)
        assertEquals(
            "/api/kit-wallet/v1/messaging/conversations/$CONVERSATION_ID/members/$OTHER_USER_ID",
            server.takeRequest().path,
        )

        server.enqueue(jsonResponse("""{"ok":true,"data":$GROUP_CONVERSATION_JSON}"""))
        session.leaveGroup(afterRemoval)
        assertEquals(
            "/api/kit-wallet/v1/messaging/conversations/$CONVERSATION_ID/members/$CURRENT_USER_ID",
            server.takeRequest().path,
        )
    }

    @Test
    fun `read receipt accepts a different canonical marker from monotonic server state`() = runTest {
        val (lifecycle, fence) = newActivation()
        enqueueActivation()
        val session = transport.openSession(lifecycle, fence)
        assertActivationRequests()
        finishActivation(lifecycle, fence)
        server.enqueue(jsonResponse(DIRECT_CONVERSATIONS))
        val conversation = session.conversations().first()
        assertEquals("/api/kit-wallet/v1/messaging/conversations", server.takeRequest().path)
        val response = MessagingReadReceiptDto(
            conversationId = CONVERSATION_ID,
            userId = CURRENT_USER_ID,
            lastReadMessageId = CANONICAL_READ_MESSAGE_ID,
            readAt = TIMESTAMP,
        )
        val encoded = moshi.adapter(MessagingReadReceiptDto::class.java).toJson(response)
        server.enqueue(jsonResponse("""{"ok":true,"data":$encoded}"""))

        val receipt = session.markConversationRead(conversation, INCOMING_MESSAGE_ID)

        val request = server.takeRequest()
        assertEquals(
            "/api/kit-wallet/v1/messaging/conversations/$CONVERSATION_ID/read-receipts",
            request.path,
        )
        assertEquals("{\"message_id\":\"$INCOMING_MESSAGE_ID\"}", request.body.readUtf8())
        assertEquals(CANONICAL_READ_MESSAGE_ID, receipt.lastReadMessageId)
    }

    @Test
    fun `chunked attachment download stops at the authenticated byte bound`() = runTest {
        val (lifecycle, fence) = newActivation()
        enqueueActivation()
        val session = transport.openSession(lifecycle, fence)
        assertActivationRequests()
        finishActivation(lifecycle, fence)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/octet-stream")
                .setChunkedBody("12345", 1),
        )

        val failure = runCatching {
            session.downloadAttachment("0f0e0d0c-0b0a-4a0b-8c0d-0e0f10111213", 4)
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(
            "/api/kit-wallet/v1/messaging/attachments/0f0e0d0c-0b0a-4a0b-8c0d-0e0f10111213",
            server.takeRequest().path,
        )
    }

    @Test
    fun `attachment upload binds permanent media id and exact ciphertext digest`() = runTest {
        val (lifecycle, fence) = newActivation()
        enqueueActivation()
        val session = transport.openSession(lifecycle, fence)
        assertActivationRequests()
        finishActivation(lifecycle, fence)
        val clientMediaId = "7a4d529e-47e4-4cd8-a51c-f88f247f87a2"
        val storageKey = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val ciphertext = "durable byte-identical ciphertext".toByteArray()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(ciphertext)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        server.enqueue(
            jsonResponse(
                """{"ok":true,"data":{"storage_key":"$storageKey","client_media_id":"$clientMediaId","byte_size":${ciphertext.size},"ciphertext_sha256":"$digest"}}""",
            ),
        )

        val uploaded = session.uploadAttachment(clientMediaId, "video/mp4", ciphertext)

        assertEquals(storageKey, uploaded.storageKey)
        val request = server.takeRequest()
        assertEquals("/api/kit-wallet/v1/messaging/attachments", request.path)
        val multipart = request.body.readUtf8()
        assertTrue(multipart.contains("name=\"client_media_id\""))
        assertTrue(multipart.contains(clientMediaId))
        assertTrue(multipart.contains("name=\"ciphertext_sha256\""))
        assertTrue(multipart.contains(digest))
        assertTrue(multipart.contains("name=\"media_type\""))
        assertTrue(multipart.contains("video/mp4"))
    }

    @Test
    fun `resumable start response resumes directly without redundant status round trip`() =
        runTest {
            val (lifecycle, fence) = newActivation()
            val resumableCapabilities = READY_CAPABILITIES.replace(
                "\"post_quantum\":true",
                """"post_quantum":true,"resumable_attachments":{"ready":true,
                    "profile":"kit-attachment-upload-v1","max_chunk_bytes":5242880,
                    "offset_unit":"ciphertext_byte","chunk_digest":"sha256",
                    "full_digest":"sha256"}""".trimIndent(),
            )
            enqueueActivation(resumableCapabilities)
            val session = transport.openSession(lifecycle, fence)
            assertActivationRequests()
            finishActivation(lifecycle, fence)

            val clientMediaId = "7a4d529e-47e4-4cd8-a51c-f88f247f87a2"
            val storageKey = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
            val ciphertext = "abcdef".toByteArray()
            val fullDigest = sha256(ciphertext)
            fun uploadDataJson(nextOffset: Int, complete: Boolean, state: String) =
                """{"client_media_id":"$clientMediaId","storage_key":"$storageKey",
                    "media_type":"video/mp4","byte_size":${ciphertext.size},
                    "ciphertext_sha256":"$fullDigest","state":"$state",
                    "next_offset":$nextOffset,"max_chunk_bytes":5242880,
                    "complete":$complete,"expires_at":null}"""
            fun uploadJson(nextOffset: Int, complete: Boolean, state: String) =
                """{"ok":true,"data":${uploadDataJson(nextOffset, complete, state)}}"""

            // A previous process uploaded abc and died. Replaying the exact idempotent start
            // returns the authoritative offset, so this invocation transmits only def without a
            // redundant GET status round trip first.
            server.enqueue(jsonResponse(uploadJson(3, false, "pending")))
            val remaining = "def".toByteArray()
            val chunkDigest = sha256(remaining)
            server.enqueue(
                jsonResponse(
                    """{"ok":true,"data":{
                        "upload":${uploadDataJson(6, false, "pending")},
                        "chunk":{"byte_offset":3,"byte_size":3,
                        "ciphertext_sha256":"$chunkDigest","replayed":false}}}""",
                ),
            )
            server.enqueue(jsonResponse(uploadJson(6, true, "ready")))
            val file = File.createTempFile("kit-resumable-test-", ".bin")
            try {
                file.writeBytes(ciphertext)
                val uploaded = session.uploadAttachment(clientMediaId, "video/mp4", file)
                assertEquals(storageKey, uploaded.storageKey)
                assertEquals(ciphertext.size.toLong(), uploaded.byteSize)
                assertEquals(fullDigest, uploaded.ciphertextSha256)
            } finally {
                file.delete()
            }

            val start = server.takeRequest()
            assertEquals("/api/kit-wallet/v1/messaging/attachment-uploads", start.path)
            assertTrue(start.body.readUtf8().contains("\"client_media_id\":\"$clientMediaId\""))
            val patch = server.takeRequest()
            assertEquals("PATCH", patch.method)
            assertEquals("3", patch.getHeader("Upload-Offset"))
            assertEquals(chunkDigest, patch.getHeader("Upload-Chunk-SHA256"))
            assertEquals("def", patch.body.readUtf8())
            val complete = server.takeRequest()
            assertEquals("POST", complete.method)
            assertEquals(
                "/api/kit-wallet/v1/messaging/attachment-uploads/$clientMediaId/complete",
                complete.path,
            )
        }

    @Test
    fun `an expired start response cannot masquerade as a renewable upload`() = runTest {
        val (lifecycle, fence) = newActivation()
        val resumableCapabilities = READY_CAPABILITIES.replace(
            "\"post_quantum\":true",
            """"post_quantum":true,"resumable_attachments":{"ready":true,
                "profile":"kit-attachment-upload-v1","max_chunk_bytes":5242880,
                "offset_unit":"ciphertext_byte","chunk_digest":"sha256",
                "full_digest":"sha256"}""".trimIndent(),
        )
        enqueueActivation(resumableCapabilities)
        val session = transport.openSession(lifecycle, fence)
        assertActivationRequests()
        finishActivation(lifecycle, fence)

        val clientMediaId = "7a4d529e-47e4-4cd8-a51c-f88f247f87a2"
        val storageKey = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val ciphertext = "abcdef".toByteArray()
        val fullDigest = sha256(ciphertext)
        server.enqueue(
            jsonResponse(
                """{"ok":true,"data":{"client_media_id":"$clientMediaId",
                    "storage_key":"$storageKey","media_type":"video/mp4",
                    "byte_size":${ciphertext.size},"ciphertext_sha256":"$fullDigest",
                    "state":"expired","next_offset":3,"max_chunk_bytes":5242880,
                    "complete":false,"expires_at":null}}""",
            ),
        )
        val file = File.createTempFile("kit-resumable-expired-test-", ".bin")
        try {
            file.writeBytes(ciphertext)
            val failure = runCatching {
                session.uploadAttachment(clientMediaId, "video/mp4", file)
            }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
        } finally {
            file.delete()
        }

        val start = server.takeRequest()
        assertEquals("POST", start.method)
        assertEquals("/api/kit-wallet/v1/messaging/attachment-uploads", start.path)
    }

    @Test
    fun `completed resumable object is renewed before message sealing without retransmitting bytes`() =
        runTest {
            val (lifecycle, fence) = newActivation()
            val resumableCapabilities = READY_CAPABILITIES.replace(
                "\"post_quantum\":true",
                """"post_quantum":true,"resumable_attachments":{"ready":true,
                    "profile":"kit-attachment-upload-v1","max_chunk_bytes":5242880,
                    "offset_unit":"ciphertext_byte","chunk_digest":"sha256",
                    "full_digest":"sha256"}""".trimIndent(),
            )
            enqueueActivation(resumableCapabilities)
            val session = transport.openSession(lifecycle, fence)
            assertActivationRequests()
            finishActivation(lifecycle, fence)

            val clientMediaId = "7a4d529e-47e4-4cd8-a51c-f88f247f87a2"
            // A sweep may have removed the former unclaimed object while the app was dead. Exact
            // replay keeps the permanent media ID but is allowed to return this replacement key.
            val replacementStorageKey = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
            val ciphertext = "already complete".toByteArray()
            val fullDigest = sha256(ciphertext)
            val completeUpload = """{"client_media_id":"$clientMediaId",
                "storage_key":"$replacementStorageKey","media_type":"video/mp4",
                "byte_size":${ciphertext.size},"ciphertext_sha256":"$fullDigest",
                "state":"ready","next_offset":${ciphertext.size},"max_chunk_bytes":5242880,
                "complete":true,"expires_at":"2026-09-01T00:00:00Z"}"""
            server.enqueue(jsonResponse("""{"ok":true,"data":$completeUpload}"""))
            server.enqueue(jsonResponse("""{"ok":true,"data":$completeUpload}"""))
            val file = File.createTempFile("kit-resumable-renewal-test-", ".bin")
            try {
                file.writeBytes(ciphertext)
                val renewed = checkNotNull(
                    session.renewAttachmentIfResumable(
                        clientMediaId = clientMediaId,
                        mediaType = "video/mp4",
                        ciphertext = file,
                        expectedByteSize = ciphertext.size.toLong(),
                        expectedSha256 = fullDigest,
                    ),
                )
                assertEquals(replacementStorageKey, renewed.storageKey)
            } finally {
                file.delete()
            }

            val start = server.takeRequest()
            assertEquals("POST", start.method)
            assertEquals("/api/kit-wallet/v1/messaging/attachment-uploads", start.path)
            val complete = server.takeRequest()
            assertEquals("POST", complete.method)
            assertEquals(
                "/api/kit-wallet/v1/messaging/attachment-uploads/$clientMediaId/complete",
                complete.path,
            )
            // A completed checkpoint needs no PATCH and no ciphertext body replay.
            assertEquals(0L, complete.body.size)
        }

    @Test
    fun `attachment bytes are discarded when the session changes during streaming`() = runTest {
        val (lifecycle, fence) = newActivation()
        enqueueActivation()
        val session = transport.openSession(lifecycle, fence)
        assertActivationRequests()
        finishActivation(lifecycle, fence)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/octet-stream")
                .setBody("1234")
                .throttleBody(1, 100, TimeUnit.MILLISECONDS),
        )

        val pending = async(Dispatchers.IO) {
            runCatching {
                session.downloadAttachment(
                    "0f0e0d0c-0b0a-4a0b-8c0d-0e0f10111213",
                    4,
                )
            }.exceptionOrNull()
        }
        val request = withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) }
        checkNotNull(request)
        lifecycle.beginErasure()
        lifecycle.finishErasure()

        assertTrue(pending.await() is IllegalStateException)
    }

    @Test
    fun `stale session handle makes no request after erasure or replacement`() = runTest {
        val (lifecycle, fence) = newActivation()
        enqueueActivation()
        val session = transport.openSession(lifecycle, fence)
        assertActivationRequests()
        lifecycle.beginErasure()
        lifecycle.finishErasure()
        lifecycle.beginSession(BINDING.copy(sessionEpoch = "epoch-2"))

        val failure = runCatching { session.keyStatus() }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `forged session handle is rejected before any request`() = runTest {
        val (lifecycle, fence) = newActivation()
        lifecycle.beginCapabilityCheck(fence)
        val forged = RemoteSecureMessagingTransport.Session(
            owner = transport,
            issuanceIdentity = Any(),
            lifecycle = lifecycle,
            fence = fence,
            activation = lifecycle.activationCapability(fence),
            context = SecureMessagingRemoteContext(CURRENT_USER_ID, CURRENT_DEVICE_ID),
        )

        val failure = runCatching { forged.keyStatus() }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `in flight remote failure is discarded when activation changes`() = runTest {
        val (lifecycle, fence) = newActivation()
        enqueueActivation()
        val session = transport.openSession(lifecycle, fence)
        assertActivationRequests()
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setHeadersDelay(1, TimeUnit.SECONDS)
                .setBody("""{"ok":false,"error":{"code":"remote_failure","message":"obsolete"}}"""),
        )

        val pending = async { runCatching { session.keyStatus() }.exceptionOrNull() }
        val request = withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) }
        checkNotNull(request)
        lifecycle.beginErasure()
        lifecycle.finishErasure()
        lifecycle.beginSession(BINDING.copy(sessionEpoch = "epoch-2"))

        val failure = pending.await()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message?.contains("activation generation changed") == true)
    }

    @Test
    fun `profile substitution returns no handle and quarantines activation`() = runTest {
        val (lifecycle, fence) = newActivation()
        server.enqueue(jsonResponse(READY_CAPABILITIES))
        server.enqueue(jsonResponse(PROFILE.replace(CURRENT_USER_ID, OTHER_USER_ID)))

        val failure = runCatching { transport.openSession(lifecycle, fence) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("QUARANTINED", lifecycle.snapshot().stage.name)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `issued conversation handle works only with the session that issued it`() = runTest {
        val (firstLifecycle, firstFence) = newActivation()
        enqueueActivation()
        val first = transport.openSession(firstLifecycle, firstFence)
        assertActivationRequests()
        server.enqueue(jsonResponse(DIRECT_CONVERSATIONS))
        val conversation = first.conversations().first()
        assertEquals("/api/kit-wallet/v1/messaging/conversations", server.takeRequest().path)

        val (secondLifecycle, secondFence) = newActivation()
        enqueueActivation()
        val second = transport.openSession(secondLifecycle, secondFence)
        assertActivationRequests()
        val before = server.requestCount

        val failure = runCatching { second.roster(conversation) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(before, server.requestCount)
    }

    @Test
    fun `current and off roster key claims fail before the consuming post`() = runTest {
        val (lifecycle, fence) = newActivation()
        enqueueActivation()
        val session = transport.openSession(lifecycle, fence)
        assertActivationRequests()
        server.enqueue(jsonResponse(DIRECT_CONVERSATIONS))
        val conversation = session.conversations().first()
        assertEquals("/api/kit-wallet/v1/messaging/conversations", server.takeRequest().path)
        enqueueRoster(authoritativeRoster())
        val roster = session.roster(conversation)
        val plan = session.encryptionPlan(conversation, roster)
        assertEquals(
            "/api/kit-wallet/v1/messaging/conversations/$CONVERSATION_ID/device-roster",
            server.takeRequest().path,
        )
        val before = server.requestCount

        listOf(setOf(CURRENT_DEVICE_ID), setOf(OUTSIDER_DEVICE_ID)).forEach { targets ->
            assertTrue(
                runCatching {
                    session.consumeKeyBundles(conversation, roster, plan, targets)
                }.isFailure,
            )
            assertEquals(before, server.requestCount)
        }
    }

    @Test
    fun `an edit is refused while the account gate is off however capable the roster`() = runTest {
        val (lifecycle, fence) = newActivation()
        enqueueActivation()
        val session = transport.openSession(lifecycle, fence)
        assertActivationRequests()
        server.enqueue(jsonResponse(DIRECT_CONVERSATIONS))
        val conversation = session.conversations().first()
        server.takeRequest()
        enqueueRoster(authoritativeRoster(EVERY_CAPABILITY))
        val roster = session.roster(conversation)
        server.takeRequest()
        val before = server.requestCount

        val failure = runCatching {
            session.requireMessageEditCapability(conversation, roster)
        }.exceptionOrNull()

        assertTrue(failure is SecureMessagingConversationCapabilityUnavailableException)
        assertEquals(before, server.requestCount)
        // Reactions ride the same roster and the same account, and they are on. So the refusal
        // above is this account's edit gate rather than a roster that said nothing at all.
        session.requireReactionCapability(conversation, roster)
    }

    @Test
    fun `an edit is refused when the direct peer never attested corrections`() = runTest {
        val (lifecycle, fence) = newActivation()
        enqueueActivation(EDITS_READY_CAPABILITIES)
        val session = transport.openSession(lifecycle, fence)
        assertActivationRequests()
        server.enqueue(jsonResponse(DIRECT_CONVERSATIONS))
        val conversation = session.conversations().first()
        server.takeRequest()
        enqueueRoster(authoritativeRoster(REACTIONS_ONLY_CAPABILITY))
        val roster = session.roster(conversation)
        server.takeRequest()
        val before = server.requestCount

        val failure = runCatching {
            session.requireMessageEditCapability(conversation, roster)
        }.exceptionOrNull()

        assertTrue(failure is SecureMessagingConversationCapabilityUnavailableException)
        assertEquals(before, server.requestCount)
        session.requireReactionCapability(conversation, roster)
    }

    @Test
    fun `an edit is refused when a roster device says nothing about its capabilities`() = runTest {
        val (lifecycle, fence) = newActivation()
        enqueueActivation(EDITS_READY_CAPABILITIES)
        val session = transport.openSession(lifecycle, fence)
        assertActivationRequests()
        server.enqueue(jsonResponse(DIRECT_CONVERSATIONS))
        val conversation = session.conversations().first()
        server.takeRequest()
        // No client block at all — the shape every build that predates the attestation sends.
        enqueueRoster(authoritativeRoster(peerCapabilities = null))
        val roster = session.roster(conversation)
        server.takeRequest()

        val failure = runCatching {
            session.requireMessageEditCapability(conversation, roster)
        }.exceptionOrNull()

        assertTrue(failure is SecureMessagingConversationCapabilityUnavailableException)
    }

    @Test
    fun `an edit is allowed once the account gate and every direct peer agree`() = runTest {
        val (lifecycle, fence) = newActivation()
        enqueueActivation(EDITS_READY_CAPABILITIES)
        val session = transport.openSession(lifecycle, fence)
        assertActivationRequests()
        server.enqueue(jsonResponse(DIRECT_CONVERSATIONS))
        val conversation = session.conversations().first()
        server.takeRequest()
        enqueueRoster(authoritativeRoster(EVERY_CAPABILITY))
        val roster = session.roster(conversation)
        server.takeRequest()
        val before = server.requestCount

        session.requireMessageEditCapability(conversation, roster)

        // Deciding costs nothing on the wire: the roster it reads was already fetched.
        assertEquals(before, server.requestCount)
    }

    @Test
    fun `a media album is refused while the account gate is off however capable the roster`() =
        runTest {
            val (lifecycle, fence) = newActivation()
            enqueueActivation(EDITS_READY_CAPABILITIES)
            val session = transport.openSession(lifecycle, fence)
            assertActivationRequests()
            server.enqueue(jsonResponse(DIRECT_CONVERSATIONS))
            val conversation = session.conversations().first()
            server.takeRequest()
            enqueueRoster(authoritativeRoster(EVERY_CAPABILITY))
            val roster = session.roster(conversation)
            server.takeRequest()
            val before = server.requestCount

            val failure = runCatching {
                session.requireMediaMessageV2Capability(conversation, roster)
            }.exceptionOrNull()

            assertTrue(failure is SecureMessagingConversationCapabilityUnavailableException)
            assertEquals(before, server.requestCount)
            // Edits ride the same roster and the same account, and they are on. So the refusal
            // above is this account's album gate rather than a roster that said nothing at all.
            session.requireMessageEditCapability(conversation, roster)
        }

    @Test
    fun `a media album is refused when the direct peer never attested the profile`() = runTest {
        val (lifecycle, fence) = newActivation()
        enqueueActivation(MEDIA_V2_READY_CAPABILITIES)
        val session = transport.openSession(lifecycle, fence)
        assertActivationRequests()
        server.enqueue(jsonResponse(DIRECT_CONVERSATIONS))
        val conversation = session.conversations().first()
        server.takeRequest()
        enqueueRoster(authoritativeRoster(EVERY_CAPABILITY_BUT_MEDIA_V2))
        val roster = session.roster(conversation)
        server.takeRequest()
        val before = server.requestCount

        val failure = runCatching {
            session.requireMediaMessageV2Capability(conversation, roster)
        }.exceptionOrNull()

        assertTrue(failure is SecureMessagingConversationCapabilityUnavailableException)
        assertEquals(before, server.requestCount)
        session.requireMessageEditCapability(conversation, roster)
    }

    @Test
    fun `a media album is refused when a roster device says nothing about its capabilities`() =
        runTest {
            val (lifecycle, fence) = newActivation()
            enqueueActivation(MEDIA_V2_READY_CAPABILITIES)
            val session = transport.openSession(lifecycle, fence)
            assertActivationRequests()
            server.enqueue(jsonResponse(DIRECT_CONVERSATIONS))
            val conversation = session.conversations().first()
            server.takeRequest()
            // No client block at all — the shape every build that predates the attestation sends.
            enqueueRoster(authoritativeRoster(peerCapabilities = null))
            val roster = session.roster(conversation)
            server.takeRequest()

            val failure = runCatching {
                session.requireMediaMessageV2Capability(conversation, roster)
            }.exceptionOrNull()

            assertTrue(failure is SecureMessagingConversationCapabilityUnavailableException)
        }

    @Test
    fun `a media album is allowed once the account gate and every direct peer agree`() = runTest {
        val (lifecycle, fence) = newActivation()
        enqueueActivation(MEDIA_V2_READY_CAPABILITIES)
        val session = transport.openSession(lifecycle, fence)
        assertActivationRequests()
        server.enqueue(jsonResponse(DIRECT_CONVERSATIONS))
        val conversation = session.conversations().first()
        server.takeRequest()
        enqueueRoster(authoritativeRoster(EVERY_CAPABILITY))
        val roster = session.roster(conversation)
        server.takeRequest()
        val before = server.requestCount

        session.requireMediaMessageV2Capability(conversation, roster)

        // Deciding costs nothing on the wire: the roster it reads was already fetched.
        assertEquals(before, server.requestCount)
    }

    @Test
    fun `a media album is refused when the account flag arrives without the advertisement`() =
        runTest {
            val (lifecycle, fence) = newActivation()
            // §5a violation: `messaging_media_message_v2` is true, but the server publishes no
            // `protocols.messaging.media_message` block at all.
            enqueueActivation(mediaV2Capabilities(mediaMessageBlock = null))
            val session = transport.openSession(lifecycle, fence)
            assertActivationRequests()
            server.enqueue(jsonResponse(DIRECT_CONVERSATIONS))
            val conversation = session.conversations().first()
            server.takeRequest()
            enqueueRoster(authoritativeRoster(EVERY_CAPABILITY))
            val roster = session.roster(conversation)
            server.takeRequest()
            val before = server.requestCount

            val failure = runCatching {
                session.requireMediaMessageV2Capability(conversation, roster)
            }.exceptionOrNull()

            assertTrue(failure is SecureMessagingConversationCapabilityUnavailableException)
            // Nothing was uploaded and nothing was sent: the refusal never touched the wire.
            assertEquals(before, server.requestCount)
            // The rest of the session is healthy — only the album feature reads as off.
            session.requireMessageEditCapability(conversation, roster)
        }

    @Test
    fun `a media album is refused when the advertisement is malformed`() = runTest {
        val (lifecycle, fence) = newActivation()
        // The block arrives as a bare string. The tolerant adapter decodes the document, keeps
        // every other feature alive, and this one reads as off.
        enqueueActivation(mediaV2Capabilities(mediaMessageBlock = "\"enabled\""))
        val session = transport.openSession(lifecycle, fence)
        assertActivationRequests()
        server.enqueue(jsonResponse(DIRECT_CONVERSATIONS))
        val conversation = session.conversations().first()
        server.takeRequest()
        enqueueRoster(authoritativeRoster(EVERY_CAPABILITY))
        val roster = session.roster(conversation)
        server.takeRequest()
        val before = server.requestCount

        val failure = runCatching {
            session.requireMediaMessageV2Capability(conversation, roster)
        }.exceptionOrNull()

        assertTrue(failure is SecureMessagingConversationCapabilityUnavailableException)
        assertEquals(before, server.requestCount)
        session.requireMessageEditCapability(conversation, roster)
    }

    @Test
    fun `a media album is refused when the advertisement is incoherent`() = runTest {
        val (lifecycle, fence) = newActivation()
        // Structurally exact but incapable: an advertisement whose attachment cap cannot carry
        // even one minimal two-item album is a contract violation, not a small limit.
        enqueueActivation(
            mediaV2Capabilities(
                COHERENT_MEDIA_MESSAGE_BLOCK.replace(
                    "\"max_attachments\":8",
                    "\"max_attachments\":1",
                ),
            ),
        )
        val session = transport.openSession(lifecycle, fence)
        assertActivationRequests()
        server.enqueue(jsonResponse(DIRECT_CONVERSATIONS))
        val conversation = session.conversations().first()
        server.takeRequest()
        enqueueRoster(authoritativeRoster(EVERY_CAPABILITY))
        val roster = session.roster(conversation)
        server.takeRequest()
        val before = server.requestCount

        val failure = runCatching {
            session.requireMediaMessageV2Capability(conversation, roster)
        }.exceptionOrNull()

        assertTrue(failure is SecureMessagingConversationCapabilityUnavailableException)
        assertEquals(before, server.requestCount)
        session.requireMessageEditCapability(conversation, roster)
    }

    @Test
    fun `a media album is refused when this device has not attested the profile itself`() =
        runTest {
            val (lifecycle, fence) = newActivation()
            enqueueActivation(MEDIA_V2_READY_CAPABILITIES)
            val session = transport.openSession(lifecycle, fence)
            assertActivationRequests()
            server.enqueue(jsonResponse(DIRECT_CONVERSATIONS))
            val conversation = session.conversations().first()
            server.takeRequest()
            // Every peer attests; this device's own roster row is the newest build that predates
            // the profile. §6 reads that as "not yet", before a single attachment byte is spent.
            enqueueRoster(
                authoritativeRoster(
                    peerCapabilities = EVERY_CAPABILITY,
                    currentDeviceCapabilities = EVERY_CAPABILITY_BUT_MEDIA_V2,
                ),
            )
            val roster = session.roster(conversation)
            server.takeRequest()
            val before = server.requestCount

            val failure = runCatching {
                session.requireMediaMessageV2Capability(conversation, roster)
            }.exceptionOrNull()

            assertTrue(failure is SecureMessagingConversationCapabilityUnavailableException)
            assertEquals(before, server.requestCount)
            // Corrections keep their sender-excluded reading: the same roster still allows them.
            session.requireMessageEditCapability(conversation, roster)
        }

    @Test
    fun `a media album is refused when this device's roster row says nothing about itself`() =
        runTest {
            val (lifecycle, fence) = newActivation()
            enqueueActivation(MEDIA_V2_READY_CAPABILITIES)
            val session = transport.openSession(lifecycle, fence)
            assertActivationRequests()
            server.enqueue(jsonResponse(DIRECT_CONVERSATIONS))
            val conversation = session.conversations().first()
            server.takeRequest()
            enqueueRoster(
                authoritativeRoster(
                    peerCapabilities = EVERY_CAPABILITY,
                    currentDeviceCapabilities = null,
                ),
            )
            val roster = session.roster(conversation)
            server.takeRequest()

            val albumFailure = runCatching {
                session.requireMediaMessageV2Capability(conversation, roster)
            }.exceptionOrNull()

            assertTrue(
                albumFailure is SecureMessagingConversationCapabilityUnavailableException,
            )
            // The sender exclusion still holds for reactions and corrections: a roster snapshot
            // that lags this device's own enrollment withholds albums only.
            session.requireMessageEditCapability(conversation, roster)
            session.requireReactionCapability(conversation, roster)
        }

    @Test
    fun `one stale device withholds edits from a mixed version group`() = runTest {
        val (lifecycle, fence) = newActivation()
        enqueueActivation(EDITS_READY_CAPABILITIES)
        val session = transport.openSession(lifecycle, fence)
        assertActivationRequests()
        server.enqueue(jsonResponse(GROUP_CONVERSATIONS))
        val group = session.conversations().single()
        server.takeRequest()
        enqueueRoster(
            groupRoster(
                firstPeerCapabilities = EVERY_CAPABILITY,
                secondPeerCapabilities = REACTIONS_ONLY_CAPABILITY,
            ),
        )
        val roster = session.roster(group)
        server.takeRequest()

        val failure = runCatching {
            session.requireMessageEditCapability(group, roster)
        }.exceptionOrNull()

        // The other member's up-to-date phone is not enough: their second device would render the
        // correction as a bubble full of descriptor text, and nobody in the group can see that.
        assertTrue(failure is SecureMessagingConversationCapabilityUnavailableException)
    }

    @Test
    fun `a group allows edits once every enrolled device attests`() = runTest {
        val (lifecycle, fence) = newActivation()
        enqueueActivation(EDITS_READY_CAPABILITIES)
        val session = transport.openSession(lifecycle, fence)
        assertActivationRequests()
        server.enqueue(jsonResponse(GROUP_CONVERSATIONS))
        val group = session.conversations().single()
        server.takeRequest()
        enqueueRoster(
            groupRoster(
                firstPeerCapabilities = EVERY_CAPABILITY,
                secondPeerCapabilities = EVERY_CAPABILITY,
            ),
        )
        val roster = session.roster(group)
        server.takeRequest()

        session.requireMessageEditCapability(group, roster)
    }

    @Test
    fun `key claim freezes targets across its network suspension`() = runTest {
        val (lifecycle, fence) = newActivation()
        enqueueActivation()
        val session = transport.openSession(lifecycle, fence)
        assertActivationRequests()
        server.enqueue(jsonResponse(DIRECT_CONVERSATIONS))
        val conversation = session.conversations().first()
        server.takeRequest()
        enqueueRoster(authoritativeRoster())
        val roster = session.roster(conversation)
        val plan = session.encryptionPlan(conversation, roster)
        server.takeRequest()
        val responseJson = moshi.adapter(ConsumedMessagingKeyBundlesDto::class.java)
            .toJson(consumedPeerBundle())
        server.enqueue(
            jsonResponse("""{"ok":true,"data":$responseJson}""")
                .setHeadersDelay(1, TimeUnit.SECONDS),
        )
        val mutableTargets = mutableSetOf(PEER_DEVICE_ID)

        val pending = async {
            session.consumeKeyBundles(conversation, roster, plan, mutableTargets)
        }
        val request = withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) }
        checkNotNull(request)
        mutableTargets.clear()
        mutableTargets += OUTSIDER_DEVICE_ID

        val consumed = pending.await()

        assertEquals(
            listOf(PEER_DEVICE_ID),
            consumed.bundles().map { it.address.serverDeviceId },
        )
        assertTrue(request.body.readUtf8().contains(PEER_DEVICE_ID))
    }

    @Test
    fun `mapper issued send cannot cross conversations and makes no post`() = runTest {
        val (lifecycle, fence) = newActivation()
        enqueueActivation()
        val session = transport.openSession(lifecycle, fence)
        assertActivationRequests()
        server.enqueue(jsonResponse(DIRECT_CONVERSATIONS))
        val conversations = session.conversations()
        assertEquals("/api/kit-wallet/v1/messaging/conversations", server.takeRequest().path)
        enqueueRoster(authoritativeRoster())
        val roster = session.roster(conversations.first())
        assertEquals(
            "/api/kit-wallet/v1/messaging/conversations/$CONVERSATION_ID/device-roster",
            server.takeRequest().path,
        )
        val encryptedSend = encryptedSendFor(
            session,
            conversations.first(),
            roster,
            lifecycle,
            fence,
        )
        val before = server.requestCount

        val failure = runCatching {
            session.send(conversations.last(), encryptedSend, emptyList())
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(before, server.requestCount)
    }

    @Test
    fun `committed send from an erased activation cannot post in a replacement epoch`() = runTest {
        val (lifecycle, firstFence) = newActivation()
        enqueueActivation()
        val first = transport.openSession(lifecycle, firstFence)
        assertActivationRequests()
        server.enqueue(jsonResponse(DIRECT_CONVERSATIONS))
        val firstConversation = first.conversations().first()
        server.takeRequest()
        enqueueRoster(authoritativeRoster())
        val firstRoster = first.roster(firstConversation)
        server.takeRequest()
        val staleSend = encryptedSendFor(
            first,
            firstConversation,
            firstRoster,
            lifecycle,
            firstFence,
        )

        lifecycle.beginErasure()
        lifecycle.finishErasure()
        val replacementBinding = BINDING.copy(sessionEpoch = "epoch-2")
        val replacementFence = lifecycle.beginSession(replacementBinding)
        enqueueActivation()
        val replacement = transport.openSession(lifecycle, replacementFence)
        assertActivationRequests()
        lifecycle.beginKeyPreparation(replacementFence)
        lifecycle.beginRosterSync(replacementFence)
        lifecycle.finishActivation(replacementFence)
        server.enqueue(jsonResponse(DIRECT_CONVERSATIONS))
        val replacementConversation = replacement.conversations().first()
        server.takeRequest()
        val before = server.requestCount

        val failure = runCatching {
            replacement.send(replacementConversation, staleSend, emptyList())
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message?.contains("authentication activation") == true)
        assertEquals(before, server.requestCount)
    }

    @Test
    fun `owner tagged ciphertext cannot post with a replacement login`() = runTest {
        val authentication = MutableTestSessionStore(
            testSession(CURRENT_USER_ID, sessionId = BINDING.sessionEpoch),
        )
        var replaceBeforeMessage = false
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                if (replaceBeforeMessage && chain.request().url.encodedPath.endsWith("/messages")) {
                    runBlocking {
                        authentication.save(
                            testSession(OTHER_USER_ID, sessionId = "replacement-session"),
                        )
                    }
                }
                chain.proceed(chain.request())
            }
            .addInterceptor(SessionHeaderInterceptor(authentication))
            .build()
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
        val fencedTransport = RemoteSecureMessagingTransport(
            retrofit.create(KitWalletApi::class.java),
            retrofit.create(SecureMessagingWireApi::class.java),
            ApiCallExecutor(moshi),
        )
        val (lifecycle, fence) = newActivation()
        enqueueActivation()
        val session = fencedTransport.openSession(lifecycle, fence)
        assertActivationRequests()
        finishActivation(lifecycle, fence)
        server.enqueue(jsonResponse(DIRECT_CONVERSATIONS))
        val conversation = session.conversations().first()
        server.takeRequest()
        enqueueRoster(authoritativeRoster())
        val roster = session.roster(conversation)
        server.takeRequest()
        val encrypted = encryptedSendFor(session, conversation, roster, lifecycle, fence)
        val owner = checkNotNull(authentication.current()).fence()
        val before = server.requestCount

        replaceBeforeMessage = true
        val failure = runCatching {
            session.send(conversation, encrypted, emptyList(), expectedOwner = owner)
        }.exceptionOrNull()

        assertTrue("expected session invalidation, got $failure", failure is SessionInvalidatedException)
        assertEquals(before, server.requestCount)
    }

    @Test
    fun `authoritative roster and its plan cannot cross session or roster handles`() = runTest {
        val (firstLifecycle, firstFence) = newActivation()
        enqueueActivation()
        val first = transport.openSession(firstLifecycle, firstFence)
        assertActivationRequests()
        server.enqueue(jsonResponse(DIRECT_CONVERSATIONS))
        val firstConversation = first.conversations().first()
        server.takeRequest()
        enqueueRoster(authoritativeRoster())
        val firstRoster = first.roster(firstConversation)
        val firstPlan = first.encryptionPlan(firstConversation, firstRoster)
        server.takeRequest()

        val (secondLifecycle, secondFence) = newActivation()
        enqueueActivation()
        val second = transport.openSession(secondLifecycle, secondFence)
        assertActivationRequests()
        server.enqueue(jsonResponse(DIRECT_CONVERSATIONS))
        val secondConversation = second.conversations().first()
        server.takeRequest()
        val beforeCrossSession = server.requestCount

        assertTrue(
            runCatching { second.encryptionPlan(secondConversation, firstRoster) }.isFailure,
        )
        assertEquals(beforeCrossSession, server.requestCount)

        enqueueRoster(authoritativeRoster())
        val replacementRoster = first.roster(firstConversation)
        server.takeRequest()
        val beforePlanSubstitution = server.requestCount

        assertTrue(
            runCatching {
                first.consumeKeyBundles(
                    firstConversation,
                    replacementRoster,
                    firstPlan,
                    setOf(PEER_DEVICE_ID),
                )
            }.isFailure,
        )
        assertEquals(beforePlanSubstitution, server.requestCount)
    }

    @Test
    fun `historical roster issues receive only authority and cannot consume outbound keys`() = runTest {
        val (lifecycle, fence) = newActivation()
        enqueueActivation()
        val session = transport.openSession(lifecycle, fence)
        assertActivationRequests()
        beginRosterSync(lifecycle, fence)
        server.enqueue(jsonResponse(DIRECT_CONVERSATIONS))
        val conversation = session.conversations().first()
        server.takeRequest()
        val historicalDto = authoritativeRoster()
        enqueueRoster(historicalDto)

        val historical = session.historicalRoster(
            conversation,
            requireNotNull(historicalDto.rosterRevision),
        )
        val request = server.takeRequest()

        assertTrue(request.path?.contains("/device-roster/v1:sha256:") == true)
        assertTrue(runCatching { session.encryptionPlan(conversation, historical) }.isFailure)
        val receiveOnly = session.decryptionPlan(conversation, historical)
        val checkpoint = session.initialSyncCheckpoint()
        enqueueSync(historicalDto, nextCursor = "historical_cursor", hasMore = false, limit = 10)
        val incoming = session.sync(checkpoint, 10).events().single()
            as RemoteSecureMessagingTransport.Session.IncomingEnvelope
        server.takeRequest()
        val decryptionRequest = session.decryptionRequest(incoming, historical, receiveOnly)
        val decryptionSnapshot = SecureMessagingCryptoWireMapper
            .requireDecryptionRequest(decryptionRequest)
        assertEquals(requireNotNull(historicalDto.rosterRevision), decryptionSnapshot.rosterRevision)
        val decrypted = commitDecryptionForTest(
            decryptionRequest,
            lifecycle.activationCapability(fence),
        )
        assertEquals("hello", decrypted.copyText())
        decrypted.close()
        val before = server.requestCount
        assertTrue(
            runCatching {
                session.consumeKeyBundles(
                    conversation,
                    historical,
                    receiveOnly,
                    setOf(PEER_DEVICE_ID),
                )
            }.isFailure,
        )
        assertEquals(before, server.requestCount)
    }

    @Test
    fun `sync cursor advances only after the issued whole batch is confirmed`() = runTest {
        val (lifecycle, fence) = newActivation()
        enqueueActivation()
        val session = transport.openSession(lifecycle, fence)
        assertActivationRequests()
        beginRosterSync(lifecycle, fence)
        val rosterDto = authoritativeRoster()
        val checkpoint = session.initialSyncCheckpoint()
        enqueueSync(rosterDto, nextCursor = "cursor_one", hasMore = true, limit = 10)

        val batch = session.sync(checkpoint, limit = 10)
        val firstRequest = server.takeRequest()

        assertEquals("/api/kit-wallet/v1/messaging/sync?limit=10", firstRequest.path)
        assertEquals(1, batch.size)
        assertTrue(batch.hasMore)
        val incoming = batch.events().single()
            as RemoteSecureMessagingTransport.Session.IncomingEnvelope
        assertEquals(PEER_USER_ID, incoming.senderUserId)
        assertEquals(TIMESTAMP, incoming.sentAt.toString())
        assertEquals(TIMESTAMP, incoming.occurredAt.toString())
        assertTrue(batch.events() !== batch.events())
        val forgedBatch = RemoteSecureMessagingTransport.Session.SyncBatch(
            owner = session,
            issuanceIdentity = Any(),
            events = batch.events(),
            hasMore = batch.hasMore,
        )
        assertTrue(runCatching { session.resumePositionAfter(forgedBatch) }.isFailure)
        val beforeReuse = server.requestCount
        assertTrue(runCatching { session.sync(checkpoint, 10) }.isFailure)
        assertEquals(beforeReuse, server.requestCount)

        val persistedPosition = session.resumePositionAfter(batch)
        val next = session.confirmProcessed(batch, persistedPosition)
        assertTrue(runCatching { session.confirmProcessed(batch, persistedPosition) }.isFailure)
        enqueueEmptySync(nextCursor = "cursor_two", hasMore = false, limit = 10)

        val nextBatch = session.sync(next, limit = 10)
        val nextRequest = server.takeRequest()

        assertTrue(nextRequest.path?.contains("cursor=cursor_one") == true)
        assertTrue(nextRequest.path?.contains("limit=10") == true)
        assertEquals(0, nextBatch.size)
    }

    @Test
    fun `activation sync acknowledgement requires issued matching durable capabilities`() = runTest {
        val (lifecycle, fence) = newActivation()
        enqueueActivation()
        val session = transport.openSession(lifecycle, fence)
        assertActivationRequests()
        beginRosterSync(lifecycle, fence)
        server.enqueue(jsonResponse(DIRECT_CONVERSATIONS))
        val conversation = session.conversations().first()
        server.takeRequest()
        val rosterDto = authoritativeRoster()
        enqueueRoster(rosterDto)
        val roster = session.roster(conversation)
        val plan = session.encryptionPlan(conversation, roster)
        server.takeRequest()
        val checkpoint = session.initialSyncCheckpoint()
        enqueueSync(rosterDto, nextCursor = "cursor_one", hasMore = false, limit = 10)
        val batch = session.sync(checkpoint, 10)
        server.takeRequest()
        val incoming = batch.events().single()
            as RemoteSecureMessagingTransport.Session.IncomingEnvelope

        val forged = RemoteSecureMessagingTransport.Session.IncomingEnvelope(
            owner = session,
            issuanceIdentity = Any(),
            eventId = incoming.eventId,
            conversationId = incoming.conversationId,
            occurredAt = incoming.occurredAt,
            messageId = incoming.messageId,
            clientMessageId = incoming.clientMessageId,
            senderUserId = incoming.senderUserId,
            senderDeviceId = incoming.senderDeviceId,
            senderEnrollmentEpoch = incoming.senderEnrollmentEpoch,
            senderSignalDeviceId = incoming.senderSignalDeviceId,
            sentAt = incoming.sentAt,
            replyToMessageId = incoming.replyToMessageId,
            kind = incoming.kind,
            attachments = incoming.attachments,
        )
        assertTrue(runCatching { session.decryptionRequest(forged, roster, plan) }.isFailure)

        val request = session.decryptionRequest(incoming, roster, plan)
        val committed = commitDecryptionForTest(
            request,
            lifecycle.activationCapability(fence),
        )
        val token = session.deliveryToken(incoming, committed)
        assertTrue(runCatching { session.deliveryToken(incoming, committed) }.isFailure)
        val forgedToken = RemoteSecureMessagingTransport.Session.DeliveryToken(session, Any())
        val beforeForgedAcknowledgement = server.requestCount
        assertTrue(runCatching { session.acknowledgeDelivery(listOf(forgedToken)) }.isFailure)
        assertEquals(beforeForgedAcknowledgement, server.requestCount)
        enqueueDeliveryAcknowledgement()

        val acknowledgement = session.acknowledgeDelivery(listOf(token))
        val acknowledgementRequest = server.takeRequest()

        assertEquals("/api/kit-wallet/v1/messaging/messages/delivery-acks", acknowledgementRequest.path)
        assertTrue(acknowledgementRequest.body.readUtf8().contains(INCOMING_MESSAGE_ID))
        assertEquals(1, acknowledgement.acknowledgedCount)
        assertEquals(1, acknowledgement.newlyAcknowledgedCount)
        val beforeReuse = server.requestCount
        assertTrue(runCatching { session.acknowledgeDelivery(listOf(token)) }.isFailure)
        assertEquals(beforeReuse, server.requestCount)
        committed.close()
    }

    @Test
    fun `durable inbound projection reissues delivery token after session restart`() = runTest {
        val durableState = TestSecureMessagingStateStore()
        val rosterDto = authoritativeRoster()

        val (firstLifecycle, firstFence) = newActivation()
        enqueueActivation()
        val first = transport.openSession(firstLifecycle, firstFence)
        assertActivationRequests()
        beginRosterSync(firstLifecycle, firstFence)
        val firstCheckpoint = first.initialSyncCheckpoint()
        enqueueSync(rosterDto, nextCursor = "replay_cursor", hasMore = false, limit = 10)
        first.sync(firstCheckpoint, 10)
        server.takeRequest()

        val persisted = durableState.persistCompanionRecordForTest(
            namespace = SecureMessagingProjectionStore.COMPANION_NAMESPACE,
            recordKey = SecureMessagingProjectionStore.inboundRecordKey(INCOMING_MESSAGE_ID),
            direction = LibSignalCompanionDirection.INBOUND,
            messageId = INCOMING_MESSAGE_ID,
            clientMessageId = CLIENT_MESSAGE_ID,
            conversationId = CONVERSATION_ID,
            rosterRevision = requireNotNull(rosterDto.rosterRevision),
            sender = SecureMessagingCryptoAddress(PEER_USER_ID, PEER_DEVICE_ID, 2),
            text = "hello",
        )
        SecureMessagingProjectionStore(
            durableState,
            LibSignalCompanionStateReader(durableState),
        ).recordInbound(persisted, java.time.Instant.parse(TIMESTAMP))

        val (restartedLifecycle, restartedFence) = newActivation()
        enqueueActivation()
        val restarted = transport.openSession(restartedLifecycle, restartedFence)
        assertActivationRequests()
        beginRosterSync(restartedLifecycle, restartedFence)
        val replayCheckpoint = restarted.initialSyncCheckpoint()
        enqueueSync(rosterDto, nextCursor = "replay_cursor", hasMore = false, limit = 10)
        val replayedEnvelope = restarted.sync(replayCheckpoint, 10).events().single()
            as RemoteSecureMessagingTransport.Session.IncomingEnvelope
        server.takeRequest()
        val restartedProjection = SecureMessagingProjectionStore(
            durableState,
            LibSignalCompanionStateReader(durableState),
        )
        val durableInbound = checkNotNull(restartedProjection.readInbound(INCOMING_MESSAGE_ID))

        val token = restarted.deliveryTokenFromDurableState(replayedEnvelope, durableInbound)
        assertTrue(
            runCatching {
                restarted.deliveryTokenFromDurableState(replayedEnvelope, durableInbound)
            }.isFailure,
        )
        enqueueDeliveryAcknowledgement()
        val acknowledged = restarted.acknowledgeDelivery(listOf(token))
        val acknowledgementRequest = server.takeRequest()

        assertTrue(acknowledgementRequest.body.readUtf8().contains(INCOMING_MESSAGE_ID))
        assertEquals(1, acknowledged.acknowledgedCount)
        assertEquals(1, acknowledged.newlyAcknowledgedCount)
    }

    @Test
    fun `durable outbound ciphertext is reissued and projection records sent receipt`() = runTest {
        val durableState = TestSecureMessagingStateStore()
        val (lifecycle, fence) = newActivation()
        enqueueActivation()
        val session = transport.openSession(lifecycle, fence)
        assertActivationRequests()
        beginRosterSync(lifecycle, fence)
        server.enqueue(jsonResponse(DIRECT_CONVERSATIONS))
        val conversation = session.conversations().first()
        server.takeRequest()
        val rosterDto = authoritativeRoster()
        enqueueRoster(rosterDto)
        val roster = session.roster(conversation)
        val plan = session.encryptionPlan(conversation, roster)
        server.takeRequest()
        lifecycle.finishActivation(fence)
        val planSnapshot = SecureMessagingCryptoWireMapper.requireEncryptionPlan(plan)
        val recipient = planSnapshot.recipients.addresses().single()
        val ciphertext = byteArrayOf(9, 8, 7, 6)
        val durableOutbound = durableState.persistCompanionRecordForTest(
            namespace = SecureMessagingProjectionStore.COMPANION_NAMESPACE,
            recordKey = SecureMessagingProjectionStore.outboundRecordKey(CLIENT_MESSAGE_ID),
            direction = LibSignalCompanionDirection.OUTBOUND,
            messageId = CLIENT_MESSAGE_ID,
            clientMessageId = CLIENT_MESSAGE_ID,
            conversationId = CONVERSATION_ID,
            rosterRevision = planSnapshot.rosterRevision,
            sender = planSnapshot.sender,
            text = "retry without re-encryption",
            envelopes = listOf(
                PersistedCompanionEnvelopeFixture(
                    recipient,
                    SecureMessagingEnvelopeKind.SESSION,
                    ciphertext,
                ),
            ),
        )
        val projection = SecureMessagingProjectionStore(
            durableState,
            LibSignalCompanionStateReader(durableState),
        )
        projection.recordOutboundPending(durableOutbound, java.time.Instant.parse(TIMESTAMP))

        val restartedProjection = SecureMessagingProjectionStore(
            durableState,
            LibSignalCompanionStateReader(durableState),
        )
        val persistedOutbound = checkNotNull(restartedProjection.readOutbound(CLIENT_MESSAGE_ID))
        val retried = SecureMessagingCryptoWireMapper.retryEncryption(persistedOutbound, plan)
        val retriedRequest = SecureMessagingCryptoWireMapper.requireEncryptedSend(retried).request()
        assertEquals(Base64.getEncoder().encodeToString(ciphertext), retriedRequest.envelopes.single().ciphertext)
        enqueueOutboundReceipt(rosterDto, retriedRequest.clientMessageId)

        val receipt = session.send(conversation, retried, emptyList())
        val sentRequest = server.takeRequest()
        assertTrue(sentRequest.body.readUtf8().contains(Base64.getEncoder().encodeToString(ciphertext)))
        restartedProjection.markOutboundSent(persistedOutbound, receipt)

        val reloaded = SecureMessagingProjectionStore(
            durableState,
            LibSignalCompanionStateReader(durableState),
        ).readPage(limit = 1).messages().single()
        assertEquals(OUTBOUND_MESSAGE_ID, reloaded.serverMessageId)
        assertEquals(SecureMessagingProjectionDeliveryState.OUTBOUND_SENT, reloaded.deliveryState)
        assertEquals(java.time.Instant.parse(TIMESTAMP), reloaded.sentAt)
    }

    private fun newActivation(): Pair<SecureMessagingLifecycleGuard, SecureMessagingSessionFence> {
        val lifecycle = SecureMessagingLifecycleGuard()
        return lifecycle to lifecycle.beginSession(BINDING)
    }

    private fun enqueueActivation(capabilities: String = READY_CAPABILITIES) {
        server.enqueue(jsonResponse(capabilities))
        server.enqueue(jsonResponse(PROFILE))
        server.enqueue(jsonResponse(DEVICES))
    }

    private fun enqueueRoster(roster: MessagingDeviceRosterDto) {
        val encoded = moshi.adapter(MessagingDeviceRosterDto::class.java).toJson(roster)
        server.enqueue(jsonResponse("""{"ok":true,"data":$encoded}"""))
    }

    private fun enqueueSync(
        roster: MessagingDeviceRosterDto,
        nextCursor: String,
        hasMore: Boolean,
        limit: Int,
    ) {
        val response = MessagingSyncDto(
            events = listOf(incomingEvent(roster)),
            page = CursorPageDto(nextCursor = nextCursor, hasMore = hasMore, limit = limit),
        )
        val encoded = moshi.adapter(MessagingSyncDto::class.java).toJson(response)
        server.enqueue(jsonResponse("""{"ok":true,"data":$encoded}"""))
    }

    private fun enqueueEmptySync(nextCursor: String, hasMore: Boolean, limit: Int) {
        val response = MessagingSyncDto(
            events = emptyList(),
            page = CursorPageDto(nextCursor = nextCursor, hasMore = hasMore, limit = limit),
        )
        val encoded = moshi.adapter(MessagingSyncDto::class.java).toJson(response)
        server.enqueue(jsonResponse("""{"ok":true,"data":$encoded}"""))
    }

    private fun incomingEvent(roster: MessagingDeviceRosterDto): MessagingSyncEventDto {
        val peer = roster.devices.orEmpty().filterNotNull().single { it.deviceId == PEER_DEVICE_ID }
        val ciphertext = "opaque incoming ciphertext".toByteArray(StandardCharsets.UTF_8)
        return MessagingSyncEventDto(
            id = "10",
            type = "message.created",
            conversationId = CONVERSATION_ID,
            resourceType = "message",
            resourceId = INCOMING_MESSAGE_ID,
            data = MessagingSyncEventDataDto(
                id = INCOMING_MESSAGE_ID,
                conversationId = CONVERSATION_ID,
                clientMessageId = CLIENT_MESSAGE_ID,
                sender = EncryptedMessageSenderDto(PEER_USER_ID, "Peer"),
                senderDeviceId = PEER_DEVICE_ID,
                senderSignalDeviceId = peer.signalDeviceId,
                senderRegistrationId = peer.registrationId,
                senderProtocolVersion = "v2",
                senderBundleVersion = peer.bundleVersion,
                senderIdentityKeySha256 = peer.identityKeySha256,
                rosterRevision = roster.rosterRevision,
                kind = "encrypted",
                replyToMessageId = null,
                envelope = EncryptedMessageEnvelopeDto(
                    recipientDeviceId = CURRENT_DEVICE_ID,
                    envelopeType = "signal-message-v2",
                    ciphertext = Base64.getEncoder().encodeToString(ciphertext),
                    ciphertextSha256 = sha256(ciphertext),
                ),
                attachments = emptyList(),
                reactions = emptyList(),
                sentAt = TIMESTAMP,
                revokedAt = null,
            ),
            occurredAt = TIMESTAMP,
        )
    }

    private fun enqueueDeliveryAcknowledgement() {
        val response = MessageDeliveryAcknowledgementDto(
            deliveryState = "delivered_to_device",
            deviceId = CURRENT_DEVICE_ID,
            acknowledgedCount = 1,
            newlyAcknowledgedCount = 1,
            items = listOf(
                MessageDeliveryReceiptDto(
                    messageId = INCOMING_MESSAGE_ID,
                    deliveredToDeviceAt = TIMESTAMP,
                ),
            ),
        )
        val encoded = moshi.adapter(MessageDeliveryAcknowledgementDto::class.java).toJson(response)
        server.enqueue(jsonResponse("""{"ok":true,"data":$encoded}"""))
    }

    private fun enqueueOutboundReceipt(
        roster: MessagingDeviceRosterDto,
        clientMessageId: String,
    ) {
        val current = roster.devices.orEmpty().filterNotNull()
            .single { it.deviceId == CURRENT_DEVICE_ID }
        val response = EncryptedMessageDto(
            id = OUTBOUND_MESSAGE_ID,
            conversationId = CONVERSATION_ID,
            clientMessageId = clientMessageId,
            sender = EncryptedMessageSenderDto(CURRENT_USER_ID, "Current User"),
            senderDeviceId = CURRENT_DEVICE_ID,
            senderSignalDeviceId = current.signalDeviceId,
            senderRegistrationId = current.registrationId,
            senderProtocolVersion = "v2",
            senderBundleVersion = current.bundleVersion,
            senderIdentityKeySha256 = current.identityKeySha256,
            rosterRevision = roster.rosterRevision,
            kind = "encrypted",
            replyToMessageId = null,
            envelope = null,
            attachments = emptyList(),
            reactions = emptyList(),
            sentAt = TIMESTAMP,
            revokedAt = null,
        )
        val encoded = moshi.adapter(EncryptedMessageDto::class.java).toJson(response)
        server.enqueue(jsonResponse("""{"ok":true,"data":$encoded}"""))
    }

    private fun finishActivation(
        lifecycle: SecureMessagingLifecycleGuard,
        fence: SecureMessagingSessionFence,
    ) {
        beginRosterSync(lifecycle, fence)
        lifecycle.finishActivation(fence)
    }

    private fun beginRosterSync(
        lifecycle: SecureMessagingLifecycleGuard,
        fence: SecureMessagingSessionFence,
    ) {
        lifecycle.beginKeyPreparation(fence)
        lifecycle.beginRosterSync(fence)
    }

    private suspend fun commitDecryptionForTest(
        request: SecureMessagingDecryptionRequest,
        activation: SecureMessagingActivationCapability,
    ): SecureMessagingCommittedResult.Decrypted {
        val snapshot = SecureMessagingCryptoWireMapper.requireDecryptionRequest(request)
        val reply = snapshot.replyToMessageId?.let { "\"$it\"" } ?: "null"
        val plaintext = (
            "{\"schema\":\"kit.messaging.content.v1\",\"type\":\"text\"," +
                "\"client_message_id\":\"${snapshot.clientMessageId}\"," +
                "\"conversation_id\":\"${snapshot.conversationId}\"," +
                "\"roster_revision\":\"${snapshot.rosterRevision}\"," +
                "\"sender_user_id\":\"${snapshot.sender.userId}\"," +
                "\"sender_device_id\":\"${snapshot.sender.serverDeviceId}\"," +
                "\"sender_signal_device_id\":${snapshot.sender.signalDeviceId}," +
                "\"reply_to_message_id\":$reply,\"text\":\"hello\"}"
            ).toByteArray(StandardCharsets.UTF_8)
        val transaction = TestDecryptionTransaction(
            activation = activation,
            messageId = snapshot.messageId,
            conversationId = snapshot.conversationId,
            sender = snapshot.sender,
            plaintext = plaintext,
        )
        transaction.stageDecryption(
            request,
            SecureMessagingCompanionStateIntent.inbound(
                namespace = "inbox:${snapshot.conversationId}",
                recordKey = snapshot.messageId,
            ),
        )
        return transaction.commit() as SecureMessagingCommittedResult.Decrypted
    }

    private fun authoritativeRoster(
        peerCapabilities: Map<String, Boolean?>? = null,
        currentDeviceCapabilities: Map<String, Boolean?>? = EVERY_CAPABILITY,
    ): MessagingDeviceRosterDto = rosterOf(
        rosterDevice(CURRENT_DEVICE_ID, CURRENT_USER_ID, 1, 42, 0x11, currentDeviceCapabilities),
        rosterDevice(PEER_DEVICE_ID, PEER_USER_ID, 2, 43, 0x21, peerCapabilities),
    )

    /**
     * A group whose other member has two enrolled devices, only the first of which may attest.
     *
     * Mixed-version groups are the case a direct chat cannot express: everyone the person can see
     * is up to date, and one forgotten second phone still has to withhold the feature. Canonical
     * roster order is (userId, signalDeviceId, deviceId), and [CURRENT_USER_ID] sorts before
     * [OTHER_USER_ID], so this listing is already in it.
     */
    private fun groupRoster(
        firstPeerCapabilities: Map<String, Boolean?>?,
        secondPeerCapabilities: Map<String, Boolean?>?,
    ): MessagingDeviceRosterDto = rosterOf(
        rosterDevice(CURRENT_DEVICE_ID, CURRENT_USER_ID, 1, 42, 0x11, EVERY_CAPABILITY),
        rosterDevice(PEER_DEVICE_ID, OTHER_USER_ID, 2, 43, 0x21, firstPeerCapabilities),
        rosterDevice(OUTSIDER_DEVICE_ID, OTHER_USER_ID, 3, 44, 0x31, secondPeerCapabilities),
    )

    private fun rosterOf(vararg devices: MessagingDeviceRosterEntryDto): MessagingDeviceRosterDto {
        val listed = devices.toList()
        // The advertised capabilities deliberately sit outside the canonical bytes, so a roster
        // that gains them keeps the hash the server already published.
        val hash = sha256(canonicalRosterBytes(listed))
        return MessagingDeviceRosterDto(
            conversationId = CONVERSATION_ID,
            rosterRevision = "v1:sha256:$hash",
            rosterHash = hash,
            hashAlgorithm = "sha256",
            devices = listed,
        )
    }

    private suspend fun encryptedSendFor(
        session: RemoteSecureMessagingTransport.Session,
        conversation: RemoteSecureMessagingTransport.Session.SecureConversation,
        roster: RemoteSecureMessagingTransport.Session.AuthoritativeRoster,
        lifecycle: SecureMessagingLifecycleGuard,
        fence: SecureMessagingSessionFence,
    ): SecureMessagingEncryptedSend {
        val plan = session.encryptionPlan(conversation, roster)
        val planSnapshot = SecureMessagingCryptoWireMapper.requireEncryptionPlan(plan)
        val recipients = planSnapshot.recipients
        val fanout = SecureMessagingPreparedFanout(
            conversationId = planSnapshot.conversationId,
            clientMessageId = CLIENT_MESSAGE_ID,
            rosterRevision = planSnapshot.rosterRevision,
            recipients = recipients,
            envelopes = recipients.addresses().map { recipient ->
                SecureMessagingPreparedEnvelope(
                    recipient = recipient,
                    kind = SecureMessagingEnvelopeKind.SESSION,
                    ciphertext = OpaqueCryptoBytes.copyOf(byteArrayOf(1, 2, 3)),
                )
            },
        )
        val committed = commitEncryptedFanoutForTest(
            fanout,
            plan,
            lifecycle.activationCapability(fence),
        )
        return SecureMessagingCryptoWireMapper.encryption(committed)
    }

    private fun consumedPeerBundle(): ConsumedMessagingKeyBundlesDto {
        val identityKey = signalValue(5, 0x21, 33)
        val signedKey = signalValue(5, 0x22, 33)
        val pqKey = signalValue(8, 0x31, 1_569)
        return ConsumedMessagingKeyBundlesDto(
            listOf(
                ConsumedMessagingKeyBundleDto(
                    deviceId = PEER_DEVICE_ID,
                    signalDeviceId = 2,
                    userId = PEER_USER_ID,
                    protocolVersion = "v2",
                    registrationId = 43,
                    identityKey = identityKey,
                    identityKeySha256 = digestBase64(identityKey),
                    signedPrekey = MessagingSignedPrekeyDto(
                        prekeyId = 1_000 + 0x21,
                        publicKey = signedKey,
                        signature = Base64.getEncoder()
                            .encodeToString(ByteArray(64) { 0x23.toByte() }),
                    ),
                    oneTimePrekey = MessagingOneTimePrekeyDto(
                        prekeyId = 2_001,
                        publicKey = signalValue(5, 0x24, 33),
                    ),
                    pqPrekey = MessagingPqPrekeyDto(
                        prekeyId = 3_001,
                        publicKey = pqKey,
                        signature = Base64.getEncoder()
                            .encodeToString(ByteArray(64) { 0x32.toByte() }),
                    ),
                    bundleVersion = 0x21,
                    availableOneTimePrekeys = 3,
                    availableEcOneTimePrekeys = 3,
                    availablePqOneTimePrekeys = 4,
                    needsReplenishment = false,
                    isCurrentDevice = false,
                    publishedAt = TIMESTAMP,
                    rotatedAt = null,
                    transparency = MessagingKeyTransparencyDto(
                        revision = "1",
                        eventType = "device.enrolled",
                        protocolVersion = "v2",
                        eventHash = "a".repeat(64),
                        identityKeySha256 = digestBase64(identityKey),
                        pqLastResortPrekeyId = 3_001,
                        pqLastResortPrekeySha256 = digestBase64(pqKey),
                        occurredAt = TIMESTAMP,
                    ),
                ),
            ),
        )
    }

    private fun rosterDevice(
        deviceId: String,
        userId: String,
        signalDeviceId: Int,
        registrationId: Int,
        seed: Int,
        capabilities: Map<String, Boolean?>? = null,
    ): MessagingDeviceRosterEntryDto {
        val identityKey = signalValue(5, seed, 33)
        val signedKey = signalValue(5, seed + 1, 33)
        return MessagingDeviceRosterEntryDto(
            deviceId = deviceId,
            signalDeviceId = signalDeviceId,
            userId = userId,
            registrationId = registrationId,
            protocolVersion = "v2",
            bundleVersion = seed,
            identityKey = identityKey,
            identityKeySha256 = digestBase64(identityKey),
            signedPrekey = MessagingSignedPrekeyDto(
                prekeyId = 1_000 + seed,
                publicKey = signedKey,
                publicKeySha256 = digestBase64(signedKey),
                signature = Base64.getEncoder().encodeToString(ByteArray(64) { (seed + 2).toByte() }),
            ),
            publishedAt = TIMESTAMP,
            rotatedAt = null,
            identityKeyChangedAt = TIMESTAMP,
            bundleVersionChangedAt = TIMESTAMP,
            client = capabilities?.let {
                MessagingDeviceClientDto(
                    platform = "android",
                    version = "0.2.30",
                    build = 41,
                    capabilities = it,
                )
            },
        )
    }

    private fun canonicalRosterBytes(devices: List<MessagingDeviceRosterEntryDto>): ByteArray = buildString {
        append("{\"schema\":\"kit.messaging.device-roster.v1\",\"conversation_id\":\"")
        append(CONVERSATION_ID)
        append("\",\"devices\":[")
        devices.forEachIndexed { index, device ->
            if (index > 0) append(',')
            val signed = checkNotNull(device.signedPrekey)
            append("{\"device_id\":\"${device.deviceId}\",\"user_id\":\"${device.userId}\"")
            append(",\"signal_device_id\":${device.signalDeviceId}")
            append(",\"registration_id\":${device.registrationId}")
            append(",\"protocol_version\":\"${device.protocolVersion}\"")
            append(",\"bundle_version\":${device.bundleVersion}")
            append(",\"identity_key\":\"${device.identityKey}\"")
            append(",\"identity_key_sha256\":\"${device.identityKeySha256}\"")
            append(",\"signed_prekey\":{\"prekey_id\":${signed.prekeyId}")
            append(",\"public_key\":\"${signed.publicKey}\"")
            append(",\"public_key_sha256\":\"${signed.publicKeySha256}\"")
            append(",\"signature\":\"${signed.signature}\"}")
            append(",\"published_at\":\"${device.publishedAt}\",\"rotated_at\":null")
            append(",\"identity_key_changed_at\":\"${device.identityKeyChangedAt}\"")
            append(",\"bundle_version_changed_at\":\"${device.bundleVersionChangedAt}\"}")
        }
        append("]}")
    }.toByteArray(StandardCharsets.UTF_8)

    private fun signalValue(type: Int, fill: Int, size: Int): String = Base64.getEncoder()
        .encodeToString(ByteArray(size) { fill.toByte() }.also { it[0] = type.toByte() })

    private fun digestBase64(value: String): String = sha256(Base64.getDecoder().decode(value))

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun assertActivationRequests() {
        assertEquals("/api/kit-wallet/v1/capabilities", server.takeRequest().path)
        assertEquals("/api/kit-wallet/v1/profile", server.takeRequest().path)
        assertEquals("/api/kit-wallet/v1/devices", server.takeRequest().path)
    }

    private fun jsonResponse(body: String) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setResponseCode(200)
        .setBody(body)

    private class TestDecryptionTransaction(
        activation: SecureMessagingActivationCapability,
        private val messageId: String,
        private val conversationId: String,
        private val sender: SecureMessagingCryptoAddress,
        plaintext: ByteArray,
    ) : FailClosedSecureMessagingCryptoTransaction(activation) {
        private val immutablePlaintext = plaintext.copyOf()

        override suspend fun stageProvisioningMaterial(plan: SecureMessagingProvisioningPlan) = Unit

        override suspend fun stageSessionMaterial(
            request: SecureMessagingSessionEstablishmentRequest,
        ) = Unit

        override suspend fun findMissingSessionAddresses(
            plan: SecureMessagingEncryptionPlan,
            candidates: List<SecureMessagingCryptoAddress>,
        ): Collection<SecureMessagingCryptoAddress> = emptyList()

        override suspend fun stageEncryptionMaterial(request: SecureMessagingEncryptionRequest) = Unit

        override suspend fun stageDecryptionMaterial(request: SecureMessagingDecryptionRequest) = Unit

        override suspend fun prepareStaged(
            operation: SecureMessagingCryptoOperation,
            companionStateIntent: SecureMessagingCompanionStateIntent?,
        ): PreparedCommit {
            check(operation == SecureMessagingCryptoOperation.DECRYPT)
            companionStateIntent?.let(::companionStateDestination)
            return preparedDecryption(
                messageId = messageId,
                conversationId = conversationId,
                sender = sender,
                plaintext = immutablePlaintext,
            )
        }

        override suspend fun commitPrepared(
            operation: SecureMessagingCryptoOperation,
            preparedResult: PreparedCommit,
        ) = Unit

        override suspend fun abortStaged() = Unit

        override fun wipeStagedSecrets() = immutablePlaintext.fill(0)
    }

    private companion object {
        const val CURRENT_USER_ID = "11111111-1111-4111-8111-111111111111"
        const val PEER_USER_ID = "22222222-2222-4222-8222-222222222222"
        const val OTHER_USER_ID = "33333333-3333-4333-8333-333333333333"
        const val CURRENT_DEVICE_ID = "44444444-4444-4444-8444-444444444444"
        const val PEER_DEVICE_ID = "55555555-5555-4555-8555-555555555555"
        const val CONVERSATION_ID = "66666666-6666-4666-8666-666666666666"
        const val CONVERSATION_TWO_ID = "77777777-7777-4777-8777-777777777777"
        const val CLIENT_MESSAGE_ID = "88888888-8888-4888-8888-888888888888"
        const val OUTSIDER_DEVICE_ID = "99999999-9999-4999-8999-999999999999"
        const val INCOMING_MESSAGE_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val OUTBOUND_MESSAGE_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        const val CANONICAL_READ_MESSAGE_ID = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        const val TIMESTAMP = "2026-07-20T08:00:00Z"
        val BINDING = SecureMessagingSessionBinding(
            sessionEpoch = "epoch-1",
            userId = CURRENT_USER_ID,
            serverDeviceId = CURRENT_DEVICE_ID,
            installationId = "installation-1",
        )
        /** A device new enough to render every reserved-prefix descriptor as what it is. */
        val EVERY_CAPABILITY: Map<String, Boolean?> = mapOf(
            MESSAGING_REACTIONS_DEVICE_CAPABILITY to true,
            MESSAGING_MESSAGE_EDITS_DEVICE_CAPABILITY to true,
            MESSAGING_MEDIA_MESSAGE_V2_DEVICE_CAPABILITY to true,
        )

        /**
         * A device that shipped reactions but not corrections.
         *
         * Spelled with the edit key explicitly false rather than absent, because an older build
         * that simply never mentions the key must be read the same way: not supported.
         */
        val REACTIONS_ONLY_CAPABILITY: Map<String, Boolean?> = mapOf(
            MESSAGING_REACTIONS_DEVICE_CAPABILITY to true,
            MESSAGING_MESSAGE_EDITS_DEVICE_CAPABILITY to false,
        )

        /**
         * A device that shipped corrections but not multi-attachment albums — the newest build in
         * the field on the day the KITMEDIA2 rollout starts, spelled explicitly false for the
         * same reason as [REACTIONS_ONLY_CAPABILITY].
         */
        val EVERY_CAPABILITY_BUT_MEDIA_V2: Map<String, Boolean?> = mapOf(
            MESSAGING_REACTIONS_DEVICE_CAPABILITY to true,
            MESSAGING_MESSAGE_EDITS_DEVICE_CAPABILITY to true,
            MESSAGING_MEDIA_MESSAGE_V2_DEVICE_CAPABILITY to false,
        )

        const val READY_CAPABILITIES = """
            {"ok":true,"data":{"api_version":"v1","currency":{"code":"UGX","scale":"2"},
            "features":{"messaging":true,"messaging_groups":true,"messaging_reactions_e2ee_v1":true},"authentication":{},"protocols":{"messaging":{
            "ready":true,"version":"v2","suite":"signal-pqxdh-kyber1024-double-ratchet-v2",
            "post_quantum":true}}}}
        """
        /**
         * The same activation, with the server having switched corrections on for this account.
         *
         * [READY_CAPABILITIES] deliberately stays without the key so the ordinary fixtures keep
         * exercising the gate-off path that every device sees until the rollout begins.
         */
        val EDITS_READY_CAPABILITIES: String = READY_CAPABILITIES.replace(
            "\"messaging_reactions_e2ee_v1\":true",
            "\"messaging_reactions_e2ee_v1\":true,\"messaging_message_edits_v1\":true",
        )

        /**
         * The §5a `media_message` advertisement exactly as the backend readiness predicate emits
         * it: `kit-media-v2`, the 64-byte envelope floor, and the fixed capacity numbers.
         */
        const val COHERENT_MEDIA_MESSAGE_BLOCK = """{"ready":true,"profile":"kit-media-v2",
            "max_attachments":8,"max_descriptor_bytes":7680,"max_caption_utf8_bytes":2048,
            "min_attachment_ciphertext_bytes":64,"max_attachment_ciphertext_bytes":209715264,
            "max_aggregate_ciphertext_bytes":268435456}"""

        /**
         * [EDITS_READY_CAPABILITIES] with the KITMEDIA2 account flag switched on and, when
         * [mediaMessageBlock] is given, that JSON value advertised as
         * `protocols.messaging.media_message`. A null block is the §5a violation the audit named:
         * the flag alone, with no advertisement to make it usable.
         */
        fun mediaV2Capabilities(mediaMessageBlock: String?): String {
            val withAccountFlag = EDITS_READY_CAPABILITIES.replace(
                "\"messaging_message_edits_v1\":true",
                "\"messaging_message_edits_v1\":true,\"messaging_media_message_v2\":true",
            )
            mediaMessageBlock ?: return withAccountFlag
            return withAccountFlag.replace(
                "\"post_quantum\":true",
                "\"post_quantum\":true,\"media_message\":$mediaMessageBlock",
            )
        }

        /** The account switched on for KITMEDIA2 albums with a coherent §5a advertisement. */
        val MEDIA_V2_READY_CAPABILITIES: String = mediaV2Capabilities(COHERENT_MEDIA_MESSAGE_BLOCK)

        const val PROFILE = """
            {"ok":true,"data":{"id":"$CURRENT_USER_ID","name":"Kit User"}}
        """
        const val DEVICES = """
            {"ok":true,"data":{"items":[{"id":"$CURRENT_DEVICE_ID","name":"Android phone",
            "platform":"android","is_current":true,"created_at":"2026-07-20T08:00:00Z",
            "last_seen_at":"2026-07-20T08:01:00Z"}]}}
        """
        const val DIRECT_CONVERSATIONS = """
            {"ok":true,"data":{"items":[
            {"id":"$CONVERSATION_ID","type":"direct","title":null,"parent_id":null,
            "created_by":"$CURRENT_USER_ID","role":"owner","members":[
            {"user_id":"$CURRENT_USER_ID","name":"Current User","role":"owner",
            "joined_at":"$TIMESTAMP"},{"user_id":"$PEER_USER_ID","name":"Peer",
            "role":"member","joined_at":"$TIMESTAMP"}],"created_at":"$TIMESTAMP",
            "updated_at":"$TIMESTAMP"},
            {"id":"$CONVERSATION_TWO_ID","type":"direct","title":null,"parent_id":null,
            "created_by":"$CURRENT_USER_ID","role":"owner","members":[
            {"user_id":"$CURRENT_USER_ID","name":"Current User","role":"owner",
            "joined_at":"$TIMESTAMP"},{"user_id":"$PEER_USER_ID","name":"Peer",
            "role":"member","joined_at":"$TIMESTAMP"}],"created_at":"$TIMESTAMP",
            "updated_at":"$TIMESTAMP"}]}}
        """
        const val THIRD_MEMBER_ROW = """{"user_id":"$OTHER_USER_ID","name":"Peer","role":"member","joined_at":"$TIMESTAMP"},"""
        const val GROUP_CONVERSATION_JSON = """
            {"id":"$CONVERSATION_ID","type":"group","title":"Weekend savings","parent_id":null,
            "created_by":"$CURRENT_USER_ID","role":"owner","members":[
            $THIRD_MEMBER_ROW
            {"user_id":"$CURRENT_USER_ID","name":"Current User","role":"owner",
            "joined_at":"$TIMESTAMP"}],"created_at":"$TIMESTAMP",
            "updated_at":"$TIMESTAMP"}
        """
        const val GROUP_CONVERSATIONS = """
            {"ok":true,"data":{"items":[$GROUP_CONVERSATION_JSON]}}
        """
    }
}
