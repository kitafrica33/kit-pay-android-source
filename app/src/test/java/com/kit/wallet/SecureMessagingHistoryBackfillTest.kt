package com.kit.wallet

import com.kit.wallet.data.messaging.LibSignalCompanionStateReader
import com.kit.wallet.data.messaging.SecureMessagingHistoryBackfillCodec
import com.kit.wallet.data.messaging.SecureMessagingProjectionStore
import com.kit.wallet.data.messaging.drainSecureMessagingHistoryWork
import com.kit.wallet.data.remote.CursorPageDto
import com.kit.wallet.data.remote.ENCRYPTED_MESSAGE_KIND
import com.kit.wallet.data.remote.EncryptedMessageCryptoSenderDto
import com.kit.wallet.data.remote.EncryptedMessageDto
import com.kit.wallet.data.remote.EncryptedMessageEnvelopeDto
import com.kit.wallet.data.remote.EncryptedMessageSenderDto
import com.kit.wallet.data.remote.MessagingDeviceRosterDto
import com.kit.wallet.data.remote.MessagingDeviceRosterEntryDto
import com.kit.wallet.data.remote.MessagingHistoryBackfillCandidatesDto
import com.kit.wallet.data.remote.MessagingHistoryEnvelopeResultDto
import com.kit.wallet.data.remote.MessagingHistoryTargetCryptoBundleDto
import com.kit.wallet.data.remote.MessagingSignedPrekeyDto
import com.kit.wallet.data.remote.SecureMessagingTransportValidator
import com.kit.wallet.data.remote.SecureMessagingWireValidator
import com.kit.wallet.data.remote.StoreMessagingHistoryEnvelopeRequest
import com.kit.wallet.data.remote.requireHistorySenderEnrollmentEpoch
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureMessagingHistoryBackfillTest {
    @Test
    fun `legacy history sender epoch normalizes only null to epoch one`() {
        assertEquals(1L, requireHistorySenderEnrollmentEpoch(null, "legacy sender epoch"))
        assertEquals(9L, requireHistorySenderEnrollmentEpoch(9L, "current sender epoch"))

        listOf(0L, -1L).forEach { malformed ->
            assertTrue(
                runCatching {
                    requireHistorySenderEnrollmentEpoch(malformed, "malformed sender epoch")
                }.isFailure,
            )
        }
    }

    @Test
    fun `received legacy history wrapper binds null sender epochs as epoch one`() {
        val legacyMessage = historyMessage().let { message ->
            val envelope = requireNotNull(message.envelope)
            message.copy(
                senderEnrollmentEpoch = null,
                envelope = envelope.copy(
                    cryptoSender = envelope.cryptoSender?.copy(enrollmentEpoch = null),
                ),
            )
        }
        val validated = SecureMessagingWireValidator.validateIncomingEncryptedMessage(
            message = legacyMessage,
            expectedConversationId = CONVERSATION_ID,
            currentDeviceId = TARGET_DEVICE_ID,
            currentUserId = CURRENT_USER_ID,
            currentEnrollmentEpoch = 7,
        )

        assertEquals(1L, validated.senderEnrollmentEpoch)
        assertEquals(1L, validated.cryptoSenderEnrollmentEpoch)

        listOf(0L, -1L).forEach { malformed ->
            assertTrue(
                runCatching {
                    SecureMessagingWireValidator.validateIncomingEncryptedMessage(
                        message = historyMessage().copy(senderEnrollmentEpoch = malformed),
                        expectedConversationId = CONVERSATION_ID,
                        currentDeviceId = TARGET_DEVICE_ID,
                        currentUserId = CURRENT_USER_ID,
                        currentEnrollmentEpoch = 7,
                    )
                }.isFailure,
            )
            val malformedCryptoSender = historyMessage().let { message ->
                val envelope = requireNotNull(message.envelope)
                message.copy(
                    envelope = envelope.copy(
                        cryptoSender = envelope.cryptoSender?.copy(
                            enrollmentEpoch = malformed,
                        ),
                    ),
                )
            }
            assertTrue(
                runCatching {
                    SecureMessagingWireValidator.validateIncomingEncryptedMessage(
                        message = malformedCryptoSender,
                        expectedConversationId = CONVERSATION_ID,
                        currentDeviceId = TARGET_DEVICE_ID,
                        currentUserId = CURRENT_USER_ID,
                        currentEnrollmentEpoch = 7,
                    )
                }.isFailure,
            )
        }
    }

    @Test
    fun `legacy candidate page accepts null epoch one but rejects explicit malformed epochs`() {
        val rosterWire = historyCandidateRoster()
        val roster = SecureMessagingWireValidator.validateAuthoritativeRoster(
            roster = rosterWire,
            expectedConversationId = CONVERSATION_ID,
            currentDeviceId = DONOR_DEVICE_ID,
            currentUserId = CURRENT_USER_ID,
            expectedMemberUserIds = setOf(CURRENT_USER_ID, PEER_USER_ID),
        )
        val donor = rosterWire.devices.orEmpty().filterNotNull()
            .single { it.deviceId == DONOR_DEVICE_ID }
        val target = rosterWire.devices.orEmpty().filterNotNull()
            .single { it.deviceId == TARGET_DEVICE_ID }
        val candidate = EncryptedMessageDto(
            id = MESSAGE_ID,
            conversationId = CONVERSATION_ID,
            clientMessageId = ORIGINAL_CLIENT_ID,
            sender = EncryptedMessageSenderDto(CURRENT_USER_ID, "Current User"),
            senderDeviceId = DONOR_DEVICE_ID,
            senderEnrollmentEpoch = null,
            senderSignalDeviceId = donor.signalDeviceId,
            senderRegistrationId = donor.registrationId,
            senderProtocolVersion = "v2",
            senderBundleVersion = donor.bundleVersion,
            senderIdentityKeySha256 = donor.identityKeySha256,
            rosterRevision = ORIGINAL_ROSTER,
            kind = ENCRYPTED_MESSAGE_KIND,
            envelope = null,
            attachments = emptyList(),
            reactions = emptyList(),
            sentAt = CANDIDATE_TIMESTAMP,
            revokedAt = null,
        )

        fun validate(senderEpoch: Long?) =
            SecureMessagingTransportValidator.validateHistoryBackfillCandidates(
                response = MessagingHistoryBackfillCandidatesDto(
                    conversationId = CONVERSATION_ID,
                    rosterRevision = roster.rosterRevision,
                    targetCryptoBundle = MessagingHistoryTargetCryptoBundleDto(
                        deviceId = TARGET_DEVICE_ID,
                        userId = CURRENT_USER_ID,
                        enrollmentEpoch = 7,
                        signalDeviceId = target.signalDeviceId,
                        registrationId = target.registrationId,
                        protocolVersion = "v2",
                        bundleVersion = target.bundleVersion,
                        identityKeySha256 = target.identityKeySha256,
                    ),
                    messages = listOf(candidate.copy(senderEnrollmentEpoch = senderEpoch)),
                    page = CursorPageDto(nextCursor = null, hasMore = false, limit = 1),
                ),
                authoritativeRoster = roster,
                expectedConversationId = CONVERSATION_ID,
                expectedCurrentUserId = CURRENT_USER_ID,
                expectedCurrentDeviceId = DONOR_DEVICE_ID,
                expectedCurrentEnrollmentEpoch = 1,
                expectedTargetDeviceId = TARGET_DEVICE_ID,
                expectedTargetEnrollmentEpoch = 7,
                requestedAfter = null,
                requestedLimit = 1,
            )

        assertEquals(1L, validate(null).messages.single().senderEnrollmentEpoch)
        listOf(0L, -1L).forEach { malformed ->
            assertTrue(runCatching { validate(malformed) }.isFailure)
        }
    }

    @Test
    fun `history wire authenticates the donor separately and binds the target enrollment`() {
        val validated = SecureMessagingWireValidator.validateIncomingEncryptedMessage(
            message = historyMessage(),
            expectedConversationId = CONVERSATION_ID,
            currentDeviceId = TARGET_DEVICE_ID,
            currentUserId = CURRENT_USER_ID,
            currentEnrollmentEpoch = 7,
        )

        assertEquals(PEER_USER_ID, validated.senderUserId)
        assertEquals(PEER_DEVICE_ID, validated.senderDeviceId)
        assertEquals(3L, validated.senderEnrollmentEpoch)
        assertEquals(CURRENT_USER_ID, validated.cryptoSenderUserId)
        assertEquals(DONOR_DEVICE_ID, validated.cryptoSenderDeviceId)
        assertEquals(5L, validated.cryptoSenderEnrollmentEpoch)
        assertEquals(7L, validated.recipientEnrollmentEpoch)
        assertTrue(validated.isHistoryBackfill)

        val wrongAccount = historyMessage().let { message ->
            message.copy(
                envelope = message.envelope?.copy(
                    cryptoSender = message.envelope?.cryptoSender?.copy(userId = PEER_USER_ID),
                ),
            )
        }
        assertTrue(
            runCatching {
                SecureMessagingWireValidator.validateIncomingEncryptedMessage(
                    wrongAccount,
                    CONVERSATION_ID,
                    TARGET_DEVICE_ID,
                    CURRENT_USER_ID,
                    7,
                )
            }.isFailure,
        )

        val staleEnrollment = historyMessage().let { message ->
            message.copy(
                envelope = message.envelope?.copy(recipientEnrollmentEpoch = 6),
            )
        }
        assertTrue(
            runCatching {
                SecureMessagingWireValidator.validateIncomingEncryptedMessage(
                    staleEnrollment,
                    CONVERSATION_ID,
                    TARGET_DEVICE_ID,
                    CURRENT_USER_ID,
                    7,
                )
            }.isFailure,
        )
    }

    @Test
    fun `transfer identity is replay stable and changes for every enrollment or roster`() {
        fun transfer(
            targetEpoch: Long = 7,
            donorEpoch: Long = 5,
            rosterRevision: String = TRANSFER_ROSTER,
        ) = SecureMessagingHistoryBackfillCodec.deterministicTransferId(
            messageId = MESSAGE_ID,
            targetDeviceId = TARGET_DEVICE_ID,
            targetEnrollmentEpoch = targetEpoch,
            donorDeviceId = DONOR_DEVICE_ID,
            donorEnrollmentEpoch = donorEpoch,
            transferRosterRevision = rosterRevision,
        )

        assertEquals(transfer(), transfer())
        assertNotEquals(transfer(), transfer(targetEpoch = 8))
        assertNotEquals(transfer(), transfer(donorEpoch = 6))
        assertNotEquals(transfer(), transfer(rosterRevision = OTHER_TRANSFER_ROSTER))
    }

    @Test
    fun `durable pagination is idempotent resumable and completes without rescanning messages`() =
        runTest {
            val state = TestSecureMessagingStateStore()
            val projections = SecureMessagingProjectionStore(
                state,
                LibSignalCompanionStateReader(state),
            )

            projections.enqueueHistoryBackfill(CONVERSATION_ID, TARGET_DEVICE_ID, 7)
            projections.enqueueHistoryBackfill(CONVERSATION_ID, TARGET_DEVICE_ID, 7)
            val initial = projections.pendingHistoryBackfills(limit = 4).single()
            assertEquals(null, initial.nextCursor)

            val cursor = "eyJzZW50X2F0IjoiMjAyNi0wNy0yMiAxMDowMDowMC4wMDAwMDEifQ.signature"
            val advanced = projections.updateHistoryBackfill(
                initial,
                nextCursor = cursor,
                completed = false,
            )
            assertEquals(
                cursor,
                projections.pendingHistoryBackfills(limit = 4).single().nextCursor,
            )

            projections.updateHistoryBackfill(advanced, nextCursor = null, completed = true)
            assertTrue(projections.pendingHistoryBackfills(limit = 4).isEmpty())
        }

    @Test
    fun `completed history task reopens from its first page only when reconciliation requests it`() =
        runTest {
            val state = TestSecureMessagingStateStore()
            val projections = SecureMessagingProjectionStore(
                state,
                LibSignalCompanionStateReader(state),
            )

            projections.enqueueHistoryBackfill(CONVERSATION_ID, TARGET_DEVICE_ID, 7)
            val initial = projections.pendingHistoryBackfills(limit = 1).single()
            val advanced = projections.updateHistoryBackfill(
                initial,
                nextCursor = "old_cursor",
                completed = false,
            )
            projections.updateHistoryBackfill(advanced, nextCursor = null, completed = true)
            assertTrue(projections.pendingHistoryBackfills(limit = 1).isEmpty())

            projections.enqueueHistoryBackfill(CONVERSATION_ID, TARGET_DEVICE_ID, 7)
            assertTrue(projections.pendingHistoryBackfills(limit = 1).isEmpty())

            projections.enqueueHistoryBackfill(
                CONVERSATION_ID,
                TARGET_DEVICE_ID,
                7,
                reopenCompleted = true,
            )
            val reopened = projections.pendingHistoryBackfills(limit = 1).single()
            assertEquals(null, reopened.nextCursor)
            assertFalse(reopened.completed)
        }

    @Test
    fun `history drain gives more than four tasks work in the same run`() = runTest {
        val state = TestSecureMessagingStateStore()
        val projections = SecureMessagingProjectionStore(
            state,
            LibSignalCompanionStateReader(state),
        )
        (1..5).forEach { index ->
            projections.enqueueHistoryBackfill(
                CONVERSATION_ID,
                historyTargetDevice(index),
                index.toLong(),
            )
        }
        val attempted = mutableListOf<String>()

        val result = drainSecureMessagingHistoryWork(
            maxWorkUnits = 5,
            batchSize = 4,
            loadPending = projections::pendingHistoryBackfills,
            attempt = { task ->
                attempted += task.recordKey
                projections.updateHistoryBackfill(task, nextCursor = null, completed = true)
                true
            },
        )

        assertEquals(5, attempted.distinct().size)
        assertEquals(5, result.workUnits)
        assertTrue(result.madeProgress)
        assertFalse(result.hadFailure)
        assertFalse(result.pending)
    }

    @Test
    fun `history drain completes five fifty item pages beyond the former two hundred candidate cap`() =
        runTest {
            val state = TestSecureMessagingStateStore()
            val projections = SecureMessagingProjectionStore(
                state,
                LibSignalCompanionStateReader(state),
            )
            projections.enqueueHistoryBackfill(CONVERSATION_ID, TARGET_DEVICE_ID, 7)
            var pages = 0

            val result = drainSecureMessagingHistoryWork(
                maxWorkUnits = 5,
                batchSize = 4,
                loadPending = projections::pendingHistoryBackfills,
                attempt = { task ->
                    pages++
                    projections.updateHistoryBackfill(
                        task,
                        nextCursor = if (pages < 5) "cursor_$pages" else null,
                        completed = pages == 5,
                    )
                    true
                },
            )

            assertEquals(5, pages)
            assertEquals(5, result.workUnits)
            assertFalse(result.pending)
            assertTrue(projections.pendingHistoryBackfills(limit = 1).isEmpty())
        }

    @Test
    fun `four failing history tasks do not starve the fifth healthy task`() = runTest {
        val state = TestSecureMessagingStateStore()
        val projections = SecureMessagingProjectionStore(
            state,
            LibSignalCompanionStateReader(state),
        )
        (1..5).forEach { index ->
            projections.enqueueHistoryBackfill(
                CONVERSATION_ID,
                historyTargetDevice(index),
                index.toLong(),
            )
        }
        val ordered = projections.pendingHistoryBackfills(limit = 5)
        val failing = ordered.take(4).mapTo(mutableSetOf()) { it.recordKey }
        val healthy = ordered.last().recordKey
        val attempted = mutableListOf<String>()

        val result = drainSecureMessagingHistoryWork(
            maxWorkUnits = 5,
            batchSize = 4,
            loadPending = projections::pendingHistoryBackfills,
            attempt = { task ->
                attempted += task.recordKey
                if (task.recordKey in failing) {
                    false
                } else {
                    projections.updateHistoryBackfill(task, nextCursor = null, completed = true)
                    true
                }
            },
        )

        assertEquals(healthy, attempted.last())
        assertTrue(result.hadFailure)
        assertTrue(result.madeProgress)
        assertTrue(result.pending)
        assertEquals(failing, projections.pendingHistoryBackfills(limit = 5).mapTo(mutableSetOf()) { it.recordKey })
    }

    @Test
    fun `history drain reports pending continuation when its work budget is exhausted`() = runTest {
        val state = TestSecureMessagingStateStore()
        val projections = SecureMessagingProjectionStore(
            state,
            LibSignalCompanionStateReader(state),
        )
        projections.enqueueHistoryBackfill(CONVERSATION_ID, TARGET_DEVICE_ID, 7)
        var pages = 0

        val result = drainSecureMessagingHistoryWork(
            maxWorkUnits = 16,
            batchSize = 4,
            loadPending = projections::pendingHistoryBackfills,
            attempt = { task ->
                pages++
                projections.updateHistoryBackfill(
                    task,
                    nextCursor = "continuation_$pages",
                    completed = false,
                )
                true
            },
        )

        assertEquals(16, pages)
        assertEquals(16, result.workUnits)
        assertTrue(result.madeProgress)
        assertTrue(result.pending)
    }

    @Test
    fun `history upload is ciphertext only and flat replay result is validated`() {
        val request = StoreMessagingHistoryEnvelopeRequest(
            targetDeviceId = TARGET_DEVICE_ID,
            targetEnrollmentEpoch = 7,
            transferClientMessageId = TRANSFER_ID,
            rosterRevision = TRANSFER_ROSTER,
            envelopeType = "signal-message-v2",
            ciphertext = Base64.getEncoder().encodeToString("opaque".toByteArray()),
        )
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val json = moshi.adapter(StoreMessagingHistoryEnvelopeRequest::class.java).toJson(request)
        assertTrue(json.contains("\"ciphertext\""))
        assertFalse(json.contains("plaintext"))
        assertFalse(json.contains("\"text\""))
        assertFalse(json.contains("message_body"))

        val result = SecureMessagingTransportValidator.validateHistoryEnvelopeResult(
            response = MessagingHistoryEnvelopeResultDto(
                messageId = MESSAGE_ID,
                targetDeviceId = TARGET_DEVICE_ID,
                targetEnrollmentEpoch = 7,
                transferClientMessageId = TRANSFER_ID,
                created = false,
            ),
            expectedMessageId = MESSAGE_ID,
            expectedTargetDeviceId = TARGET_DEVICE_ID,
            expectedTargetEnrollmentEpoch = 7,
            expectedTransferClientMessageId = TRANSFER_ID,
        )
        assertFalse(result.created)
    }

    private fun historyMessage(): EncryptedMessageDto {
        val ciphertext = "opaque history ciphertext".toByteArray()
        return EncryptedMessageDto(
            id = MESSAGE_ID,
            conversationId = CONVERSATION_ID,
            clientMessageId = ORIGINAL_CLIENT_ID,
            sender = EncryptedMessageSenderDto(PEER_USER_ID, "Peer"),
            senderDeviceId = PEER_DEVICE_ID,
            senderEnrollmentEpoch = 3,
            senderSignalDeviceId = 2,
            senderRegistrationId = 43,
            senderProtocolVersion = "v2",
            senderBundleVersion = 4,
            senderIdentityKeySha256 = "a".repeat(64),
            rosterRevision = ORIGINAL_ROSTER,
            kind = ENCRYPTED_MESSAGE_KIND,
            replyToMessageId = null,
            envelope = EncryptedMessageEnvelopeDto(
                recipientDeviceId = TARGET_DEVICE_ID,
                recipientEnrollmentEpoch = 7,
                envelopeType = "signal-message-v2",
                ciphertext = Base64.getEncoder().encodeToString(ciphertext),
                ciphertextSha256 = sha256(ciphertext),
                isHistoryBackfill = true,
                transferClientMessageId = TRANSFER_ID,
                transferRosterRevision = TRANSFER_ROSTER,
                cryptoSender = EncryptedMessageCryptoSenderDto(
                    userId = CURRENT_USER_ID,
                    deviceId = DONOR_DEVICE_ID,
                    enrollmentEpoch = 5,
                    signalDeviceId = 3,
                    registrationId = 44,
                    protocolVersion = "v2",
                    bundleVersion = 6,
                    identityKeySha256 = "b".repeat(64),
                ),
            ),
            attachments = emptyList(),
            reactions = emptyList(),
            sentAt = "2026-07-22T10:00:00Z",
            revokedAt = null,
        )
    }

    private fun historyCandidateRoster(): MessagingDeviceRosterDto {
        val devices = listOf(
            rosterDevice(
                TARGET_DEVICE_ID,
                CURRENT_USER_ID,
                1,
                45,
                7,
                0x41,
                enrollmentEpoch = 7,
            ),
            rosterDevice(
                DONOR_DEVICE_ID,
                CURRENT_USER_ID,
                3,
                44,
                6,
                0x31,
                enrollmentEpoch = 1,
            ),
            rosterDevice(
                PEER_DEVICE_ID,
                PEER_USER_ID,
                2,
                43,
                4,
                0x21,
                enrollmentEpoch = 3,
            ),
        )
        val hash = sha256(canonicalRosterBytes(devices))
        return MessagingDeviceRosterDto(
            conversationId = CONVERSATION_ID,
            rosterRevision = "v1:sha256:$hash",
            rosterHash = hash,
            hashAlgorithm = "sha256",
            devices = devices,
        )
    }

    private fun rosterDevice(
        deviceId: String,
        userId: String,
        signalDeviceId: Int,
        registrationId: Int,
        bundleVersion: Int,
        seed: Int,
        enrollmentEpoch: Long? = null,
    ): MessagingDeviceRosterEntryDto {
        val identityKey = signalValue(5, seed, 33)
        val signedKey = signalValue(5, seed + 1, 33)
        return MessagingDeviceRosterEntryDto(
            deviceId = deviceId,
            enrollmentEpoch = enrollmentEpoch,
            signalDeviceId = signalDeviceId,
            userId = userId,
            registrationId = registrationId,
            protocolVersion = "v2",
            bundleVersion = bundleVersion,
            identityKey = identityKey,
            identityKeySha256 = digestBase64(identityKey),
            signedPrekey = MessagingSignedPrekeyDto(
                prekeyId = 1_000 + seed,
                publicKey = signedKey,
                publicKeySha256 = digestBase64(signedKey),
                signature = Base64.getEncoder()
                    .encodeToString(ByteArray(64) { (seed + 2).toByte() }),
            ),
            publishedAt = CANDIDATE_TIMESTAMP,
            rotatedAt = null,
            identityKeyChangedAt = CANDIDATE_TIMESTAMP,
            bundleVersionChangedAt = CANDIDATE_TIMESTAMP,
        )
    }

    private fun canonicalRosterBytes(devices: List<MessagingDeviceRosterEntryDto>): ByteArray =
        buildString {
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

    private fun historyTargetDevice(index: Int): String =
        "40000000-0000-4000-8000-${index.toString().padStart(12, '0')}"

    private fun signalValue(type: Int, fill: Int, size: Int): String = Base64.getEncoder()
        .encodeToString(ByteArray(size) { fill.toByte() }.also { it[0] = type.toByte() })

    private fun digestBase64(value: String): String = sha256(Base64.getDecoder().decode(value))

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        const val CONVERSATION_ID = "10000000-0000-4000-8000-000000000001"
        const val CURRENT_USER_ID = "20000000-0000-4000-8000-000000000002"
        const val PEER_USER_ID = "30000000-0000-4000-8000-000000000003"
        const val TARGET_DEVICE_ID = "40000000-0000-4000-8000-000000000004"
        const val DONOR_DEVICE_ID = "50000000-0000-4000-8000-000000000005"
        const val PEER_DEVICE_ID = "60000000-0000-4000-8000-000000000006"
        const val MESSAGE_ID = "70000000-0000-4000-8000-000000000007"
        const val ORIGINAL_CLIENT_ID = "80000000-0000-4000-8000-000000000008"
        const val TRANSFER_ID = "90000000-0000-4000-8000-000000000009"
        val ORIGINAL_ROSTER = "v1:sha256:${"c".repeat(64)}"
        val TRANSFER_ROSTER = "v1:sha256:${"d".repeat(64)}"
        val OTHER_TRANSFER_ROSTER = "v1:sha256:${"e".repeat(64)}"
        const val CANDIDATE_TIMESTAMP = "2026-07-22T10:00:00Z"
    }
}
