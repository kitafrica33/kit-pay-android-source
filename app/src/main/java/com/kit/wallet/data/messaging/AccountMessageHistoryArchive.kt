package com.kit.wallet.data.messaging

import com.kit.wallet.data.auth.DeviceIdentityProvider
import com.kit.wallet.data.session.SessionFence
import com.kit.wallet.data.session.SessionStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import com.kit.wallet.di.ApplicationScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Read-only, sanitized message history that is safe to retain after Signal state is erased. */
internal data class AccountArchivedMessage(
    val serverMessageId: String,
    val clientMessageId: String,
    val conversationId: String,
    val sender: SecureMessagingCryptoAddress,
    val rosterRevision: String,
    val replyToMessageId: String?,
    val sentAt: Instant,
    val text: String,
    /** Always an inbound-shaped display state; it never carries resend/fanout authority. */
    val deliveryState: SecureMessagingProjectionDeliveryState,
    /**
         * True only when this device proved the strict-v2 envelope binding for exactly this text —
         * an inbound row whose outer attachment rows matched the authenticated descriptor, or an
         * own-authored row whose descriptor was strictly derived before its send commit. Restoration
         * maps it back to a VALIDATED disposition only when the text still strict-parses as v2,
     * so a flag smuggled onto arbitrary text is inert. Without it, restored reserved-format text
     * arrived as a plain text-kind claim and stays a fail-closed placeholder.
     */
    val mediaValidated: Boolean = false,
) {
    init {
        requireArchiveUuid(serverMessageId, "archived server message ID")
        requireArchiveUuid(clientMessageId, "archived client message ID")
        requireArchiveUuid(conversationId, "archived conversation ID")
        requireArchiveUuid(sender.userId, "archived sender user ID")
        requireArchiveUuid(sender.serverDeviceId, "archived sender device ID")
        require(sender.signalDeviceId > 0) { "Invalid archived sender Signal device ID" }
        require(rosterRevision.isNotBlank() && rosterRevision.length <= MAX_ARCHIVE_IDENTIFIER_CHARS) {
            "Invalid archived roster revision"
        }
        replyToMessageId?.let { requireArchiveUuid(it, "archived reply message ID") }
        require(sentAt.toEpochMilli() > 0L) { "Invalid archived message timestamp" }
        val textBytes = text.toByteArray(Charsets.UTF_8)
        try {
            require(textBytes.size in 1..MAX_ARCHIVED_TEXT_BYTES) {
                "Invalid archived message text size"
            }
        } finally {
            textBytes.fill(0)
        }
        require(deliveryState in ARCHIVABLE_INBOUND_STATES) {
            "Archived history cannot retain pending, suppressed, or resend-capable state"
        }
    }
}

/**
 * Account identity is derived only from the authenticated session. Callers can supply message
 * history but cannot select another archive owner.
 */
@Singleton
internal class AccountMessageHistoryArchive @Inject constructor(
    private val sessions: SessionStore,
    private val deviceIdentity: DeviceIdentityProvider,
    private val records: AccountMessageArchiveStore,
    private val purgeIntents: AccountMessageArchivePurgeIntents =
        NoOpAccountMessageArchivePurgeIntents,
) : AccountMessageHistoryAccess {
    override fun capture(
        expectedOwnerAccountId: String,
        expectedSessionFence: SessionFence?,
    ): CapturedAccountMessageHistory {
        requireArchiveUuid(expectedOwnerAccountId, "expected archive owner account ID")
        val session = requireNotNull(sessions.current()) {
            "Account message archive requires an authenticated session"
        }
        val capturedFence = expectedSessionFence ?: session.fence()
        check(session.fence() == capturedFence) {
            "Account message archive session changed before owner capture"
        }
        check(capturedFence.accountId == expectedOwnerAccountId) {
            "Account message archive target does not match its authenticated session"
        }
        val owner = AccountMessageArchiveOwner(
            ownerAccountId = expectedOwnerAccountId,
            installationId = deviceIdentity.registration().installationId,
        )
        return BoundAccountMessageHistory(owner, capturedFence)
    }

    internal suspend fun eraseAccount(target: SessionFence) {
        val ownerAccountId = target.accountId ?: return
        eraseAccount(ownerAccountId)
    }

    internal suspend fun eraseAccount(ownerAccountId: String) {
        requireArchiveUuid(ownerAccountId, "archive purge account ID")
        records.eraseAccount(ownerAccountId)
    }

    internal suspend fun <T> withCurrentPurgeTarget(
        target: SessionFence,
        block: suspend () -> T,
    ): T = withCurrentHistoryTarget(target, block)

    /** Holds the exact authenticated owner across a messaging-state snapshot or archive purge. */
    internal suspend fun <T> withCurrentHistoryTarget(
        target: SessionFence,
        block: suspend () -> T,
    ): T = sessions.withCurrentSession(target) { current ->
        check(current.accountId == target.accountId && target.accountId != null) {
            "Account message archive target changed before it became durable"
        }
        block()
    }

    /** Purges only after the targeted old session is gone, serialized against any new session. */
    internal suspend fun erasePendingAccount(
        pending: PendingAccountMessageArchivePurge,
    ): Boolean {
        val expected = sessions.snapshot()
        if (pending.matches(expected.fence)) return false
        return sessions.withUnchangedSession(expected) { current ->
            if (pending.matches(current?.fence())) return@withUnchangedSession false
            // A newer same-account archive boundary may have consumed this marker after the retry
            // took its batch snapshot. Recheck while the session is locked before deleting rows.
            if (!purgeIntents.contains(pending)) return@withUnchangedSession false
            records.eraseAccount(pending.ownerAccountId)
            true
        }
    }

    private inner class BoundAccountMessageHistory(
        private val owner: AccountMessageArchiveOwner,
        private val sessionFence: SessionFence,
    ) : CapturedAccountMessageHistory {
        override suspend fun archive(projected: SecureMessagingProjectedMessage) =
            withCapturedOwner(owner, sessionFence) {
                archiveForOwner(owner, projected)
            }

        override suspend fun restore(archived: AccountArchivedMessage) =
            withCapturedOwner(owner, sessionFence) {
                // A backup is bytes the user brought back, not authenticated traffic. It goes
                // through the same immutability rule as live archiving — fill a gap, or advance a
                // delivery state, never rewrite history that is already here.
                validateArchivedMessageOwner(archived, owner.ownerAccountId)
                writeArchivedForOwner(owner, archived)
            }

        override suspend fun readAll(): List<AccountArchivedMessage> =
            withCapturedOwner(owner, sessionFence) {
                readAllForOwner(owner)
            }

        override suspend fun readAllAndMaterialize(
            materialize: suspend (List<AccountArchivedMessage>) -> Unit,
        ) = withCapturedOwner(owner, sessionFence) {
            // Do not call readAll() here: it would try to enter the same SessionStore critical
            // section recursively. Both the owner read and its caller-owned projection writes
            // must complete before logout or a replacement account can acquire that section.
            materialize(readAllForOwner(owner))
        }
    }

    private suspend fun <T> withCapturedOwner(
        owner: AccountMessageArchiveOwner,
        sessionFence: SessionFence,
        block: suspend () -> T,
    ): T = sessions.withCurrentSession(sessionFence) { current ->
        check(current.accountId == owner.ownerAccountId) {
            "Account message archive owner changed during a captured operation"
        }
        check(deviceIdentity.registration().installationId == owner.installationId) {
            "Account message archive installation changed during a captured operation"
        }
        reconcileOlderPurgeGenerations(owner, sessionFence)
        block()
    }

    /**
     * A purge marker can survive a failed S1 logout, but it must never wake after S2 has written
     * new history and erase both generations. Every S2 archive access is already session-locked;
     * erase S1's account archive and retire its exact marker before admitting the first S2 read or
     * write. Marker enqueue is also session-locked, so an obsolete S1 cannot appear after this
     * boundary has admitted S2.
     */
    private suspend fun reconcileOlderPurgeGenerations(
        owner: AccountMessageArchiveOwner,
        currentFence: SessionFence,
    ) {
        val currentGeneration = PendingAccountMessageArchivePurge.from(currentFence)
        val olderGenerations = purgeIntents.pending().filter { pending ->
            pending.ownerAccountId == owner.ownerAccountId && pending != currentGeneration
        }
        if (olderGenerations.isEmpty()) return

        records.eraseAccount(owner.ownerAccountId)
        olderGenerations.forEach { pending ->
            // Another retry may have completed the same exact generation after our snapshot.
            if (purgeIntents.contains(pending)) purgeIntents.complete(pending)
        }
    }

    private suspend fun archiveForOwner(
        owner: AccountMessageArchiveOwner,
        projected: SecureMessagingProjectedMessage,
    ) {
        val archived = projected.toArchived(owner.ownerAccountId) ?: return
        writeArchivedForOwner(owner, archived)
    }

    private suspend fun writeArchivedForOwner(
        owner: AccountMessageArchiveOwner,
        archived: AccountArchivedMessage,
    ) {
        val recordKey = archiveMessageRecordKey(archived.serverMessageId)
        repeat(MAX_ARCHIVE_WRITE_ATTEMPTS) { attempt ->
            val existingRecord = records.read(owner, recordKey)
            val existing = existingRecord?.let { record ->
                try {
                    AccountArchivedMessageCodec.decode(record.bytes)
                } finally {
                    record.bytes.fill(0)
                }
            }
            val selected = selectArchivedMessageUpdate(existing, archived) ?: return
            val encoded = AccountArchivedMessageCodec.encode(selected)
            try {
                try {
                    records.write(
                        owner = owner,
                        recordKey = recordKey,
                        expectedVersion = existingRecord?.version,
                        bytes = encoded,
                    )
                    return
                } catch (conflict: AccountMessageArchiveConflictException) {
                    if (attempt == MAX_ARCHIVE_WRITE_ATTEMPTS - 1) throw conflict
                }
            } finally {
                encoded.fill(0)
            }
        }
        error("Account message archive write retry bound was exhausted")
    }

    private suspend fun readAllForOwner(
        owner: AccountMessageArchiveOwner,
    ): List<AccountArchivedMessage> {
        val messages = ArrayList<AccountArchivedMessage>()
        var after: String? = null
        repeat(MAX_ARCHIVE_READ_PAGES) {
            val page = records.readPage(owner, after, ARCHIVE_PAGE_SIZE)
            val pageRecords = page.records()
            try {
                pageRecords.forEach { record ->
                    try {
                        val decoded = AccountArchivedMessageCodec.decode(record.bytes)
                        check(record.recordKey == archiveMessageRecordKey(decoded.serverMessageId)) {
                            "Account message archive record key does not match its authenticated value"
                        }
                        validateArchivedMessageOwner(decoded, owner.ownerAccountId)
                        messages += decoded
                    } finally {
                        record.bytes.fill(0)
                    }
                }
            } finally {
                // A corrupt record must not leave any later decrypted page payloads in memory.
                pageRecords.forEach { it.bytes.fill(0) }
            }
            after = page.nextAfterRecordKey ?: return messages
        }
        error("Account message archive exceeds the supported restoration bound")
    }
}

internal interface CapturedAccountMessageHistory {
    suspend fun archive(projected: SecureMessagingProjectedMessage)

    /** Merges one message out of a restored backup; existing history always wins a disagreement. */
    suspend fun restore(archived: AccountArchivedMessage)

    suspend fun readAll(): List<AccountArchivedMessage>
    suspend fun readAllAndMaterialize(
        materialize: suspend (List<AccountArchivedMessage>) -> Unit,
    )
}

internal interface AccountMessageHistoryAccess {
    fun capture(
        expectedOwnerAccountId: String,
        expectedSessionFence: SessionFence? = null,
    ): CapturedAccountMessageHistory
}

internal object NoOpAccountMessageHistoryAccess : AccountMessageHistoryAccess {
    override fun capture(
        expectedOwnerAccountId: String,
        expectedSessionFence: SessionFence?,
    ): CapturedAccountMessageHistory = NoOpCapturedAccountMessageHistory
}

private object NoOpCapturedAccountMessageHistory : CapturedAccountMessageHistory {
    override suspend fun archive(projected: SecureMessagingProjectedMessage) = Unit
    override suspend fun restore(archived: AccountArchivedMessage) = Unit
    override suspend fun readAll(): List<AccountArchivedMessage> = emptyList()
    override suspend fun readAllAndMaterialize(
        materialize: suspend (List<AccountArchivedMessage>) -> Unit,
    ) = materialize(emptyList())
}

/** Narrow auth-facing boundary for final snapshotting and explicit all-account local purges. */
interface AccountMessageHistoryRetention {
    suspend fun snapshotActiveHistory(target: SessionFence)
    suspend fun eraseAccount(target: SessionFence)
    suspend fun scheduleAccountErasure(target: SessionFence) = Unit
    suspend fun eraseScheduledAccount(target: SessionFence) = eraseAccount(target)
}

object NoOpAccountMessageHistoryRetention : AccountMessageHistoryRetention {
    override suspend fun snapshotActiveHistory(target: SessionFence) = Unit
    override suspend fun eraseAccount(target: SessionFence) = Unit
}

@Singleton
class AccountMessageHistoryCoordinator @Inject internal constructor(
    private val projections: SecureMessagingProjectionStore,
    private val archive: AccountMessageHistoryArchive,
    private val purgeQueue: AccountMessageArchivePurgeIntents,
    @ApplicationScope private val applicationScope: CoroutineScope,
) : AccountMessageHistoryRetention {
    private val purgeMutex = Mutex()

    init {
        schedulePendingPurgeRetry()
    }

    override suspend fun snapshotActiveHistory(target: SessionFence) {
        // Legacy/restored sessions without an account ID never had a safely addressable archive.
        val ownerAccountId = target.accountId ?: return
        // SessionStore is always acquired before the generic state lease. Archive writes re-enter
        // the already-owned session section, so neither replacement nor state erase/reopen can
        // interleave with this exact owner's scan.
        archive.withCurrentHistoryTarget(target) {
            val captured = projections.captureHistoryArchive(ownerAccountId, target)
            projections.withStateLease {
                archiveAcceptedHistory(captured)
            }
        }
    }

    override suspend fun eraseAccount(target: SessionFence) {
        scheduleAccountErasure(target)
        eraseScheduledAccount(target)
    }

    override suspend fun scheduleAccountErasure(target: SessionFence) {
        if (target.accountId == null) return
        archive.withCurrentPurgeTarget(target) {
            purgeMutex.withLock {
                try {
                    purgeQueue.enqueue(target)
                } catch (error: Exception) {
                    throw AccountMessageArchivePurgeNotDurableException(error)
                }
            }
        }
    }

    override suspend fun eraseScheduledAccount(target: SessionFence) {
        val ownerAccountId = target.accountId ?: return
        val pending = PendingAccountMessageArchivePurge.from(target)
        try {
            archive.withCurrentPurgeTarget(target) {
                purgeMutex.withLock {
                    val markerPresent = try {
                        purgeQueue.contains(pending)
                    } catch (error: Exception) {
                        throw AccountMessageArchivePurgeNotDurableException(error)
                    }
                    if (!markerPresent) {
                        try {
                            purgeQueue.enqueue(target)
                        } catch (error: Exception) {
                            throw AccountMessageArchivePurgeNotDurableException(error)
                        }
                    }
                    archive.eraseAccount(ownerAccountId)
                    purgeQueue.complete(pending)
                }
            }
        } catch (cancelled: CancellationException) {
            schedulePendingPurgeRetry()
            throw cancelled
        } catch (error: Exception) {
            schedulePendingPurgeRetry()
            throw error
        }
    }

    private fun schedulePendingPurgeRetry() {
        applicationScope.launch {
            repeat(PURGE_RETRY_ATTEMPTS) { attempt ->
                val pendingBatch = purgeMutex.withLock { purgeQueue.pending().toList() }
                pendingBatch.forEach { pending ->
                    try {
                        if (archive.erasePendingAccount(pending)) {
                            purgeMutex.withLock {
                                // Exact-generation conditional completion prevents an older retry
                                // from removing another session's newly enqueued purge intent.
                                if (purgeQueue.contains(pending)) {
                                    purgeQueue.complete(pending)
                                }
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // The exact generation marker remains for a later attempt/app start.
                    }
                }
                if (purgeQueue.pending().isEmpty()) return@launch
                if (attempt < PURGE_RETRY_ATTEMPTS - 1) delay(PURGE_RETRY_DELAY_MILLIS)
            }
        }
    }

    private companion object {
        const val PURGE_RETRY_ATTEMPTS = 4
        const val PURGE_RETRY_DELAY_MILLIS = 5_000L
    }
}

internal class AccountMessageArchivePurgeNotDurableException(cause: Throwable) :
    IllegalStateException("Account message archive purge could not be made durable", cause)

internal fun AccountArchivedMessage.toAuthenticatedHistory(): SecureMessagingAuthenticatedHistory =
    AuthenticatedHistoryValue(
        messageId = serverMessageId,
        clientMessageId = clientMessageId,
        conversationId = conversationId,
        sender = sender,
        // This field authenticates server transfer wrappers. A local archive does not create a
        // wrapper and the canonical companion record deliberately excludes enrollment authority.
        senderEnrollmentEpoch = 1L,
        rosterRevision = rosterRevision,
        replyToMessageId = replyToMessageId,
        sentAt = sentAt,
        text = text,
    )

private fun validateArchivedMessageOwner(
    message: AccountArchivedMessage,
    ownerAccountId: String,
) {
    val fromCurrentUser = message.sender.userId == ownerAccountId
    when (message.deliveryState) {
        SecureMessagingProjectionDeliveryState.INBOUND_READ -> check(!fromCurrentUser) {
            "Self-authored archived history used peer-read state"
        }
        SecureMessagingProjectionDeliveryState.INBOUND_SELF_DELIVERED,
        SecureMessagingProjectionDeliveryState.INBOUND_SELF_READ,
        -> check(fromCurrentUser) {
            "Peer-authored archived history used sender receipt state"
        }
        SecureMessagingProjectionDeliveryState.INBOUND_RECEIVED -> Unit
        else -> error("Unarchivable message delivery state")
    }
}

/** Returns null for pending/failed/suppressed state, which must never survive as send authority. */
private fun SecureMessagingProjectedMessage.toArchived(
    ownerAccountId: String,
): AccountArchivedMessage? {
    requireArchiveUuid(ownerAccountId, "archive owner account ID")
    val serverId = serverMessageId ?: return null
    // Callers already skip rejected media, but the archive layer protects itself too: a pinned or
    // derived unsupported row is a placeholder whose durable descriptor bytes never leave the
    // live projection, so it can never be captured into retained history.
    if (unsupportedMedia) return null
    val durable = requireDurableLibSignalCompanionRecord(durableRecord)
    val fromCurrentUser = durable.sender.userId == ownerAccountId
    val archivedState = when (deliveryState) {
        SecureMessagingProjectionDeliveryState.OUTBOUND_SENT -> {
            check(fromCurrentUser) { "Another account authored an outbound archive projection" }
            SecureMessagingProjectionDeliveryState.INBOUND_RECEIVED
        }
        SecureMessagingProjectionDeliveryState.OUTBOUND_DELIVERED -> {
            check(fromCurrentUser) { "Another account authored an outbound archive projection" }
            SecureMessagingProjectionDeliveryState.INBOUND_SELF_DELIVERED
        }
        SecureMessagingProjectionDeliveryState.OUTBOUND_READ -> {
            check(fromCurrentUser) { "Another account authored an outbound archive projection" }
            SecureMessagingProjectionDeliveryState.INBOUND_SELF_READ
        }
        SecureMessagingProjectionDeliveryState.INBOUND_RECEIVED ->
            SecureMessagingProjectionDeliveryState.INBOUND_RECEIVED
        SecureMessagingProjectionDeliveryState.INBOUND_READ -> {
            check(!fromCurrentUser) { "Self-authored archive history used peer-read state" }
            SecureMessagingProjectionDeliveryState.INBOUND_READ
        }
        SecureMessagingProjectionDeliveryState.INBOUND_SELF_DELIVERED -> {
            check(fromCurrentUser) { "Peer-authored archive history used sender delivery state" }
            SecureMessagingProjectionDeliveryState.INBOUND_SELF_DELIVERED
        }
        SecureMessagingProjectionDeliveryState.INBOUND_SELF_READ -> {
            check(fromCurrentUser) { "Peer-authored archive history used sender read state" }
            SecureMessagingProjectionDeliveryState.INBOUND_SELF_READ
        }
        SecureMessagingProjectionDeliveryState.OUTBOUND_PENDING,
        SecureMessagingProjectionDeliveryState.OUTBOUND_RETRY_REQUIRED,
        SecureMessagingProjectionDeliveryState.OUTBOUND_PERMANENT_FAILURE,
        SecureMessagingProjectionDeliveryState.INBOUND_SUPPRESSED,
        -> return null
    }
    return AccountArchivedMessage(
        serverMessageId = serverId,
        clientMessageId = durable.clientMessageId,
        conversationId = durable.conversationId,
        sender = durable.sender,
        rosterRevision = durable.rosterRevision,
        replyToMessageId = durable.replyToMessageId,
        sentAt = sentAt,
        text = durable.authenticatedText,
        deliveryState = archivedState,
        // The explicit pin is required in both directions. This prevents a legacy outbound row
        // containing user-authored KITMEDIA2-shaped text from gaining authority merely because it
        // was sent by this account; new genuine v2 sends receive the pin before projection commit.
        mediaValidated = mediaDisposition == SecureMessagingMediaDisposition.VALIDATED &&
            KitMediaMessageV2.parse(durable.authenticatedText) != null,
    )
}

/** Live authenticated state may advance an archive, but can never rewrite immutable history. */
internal fun selectArchivedMessageUpdate(
    existing: AccountArchivedMessage?,
    incoming: AccountArchivedMessage,
): AccountArchivedMessage? {
    if (existing == null) return incoming
    check(
        existing.copy(
            deliveryState = incoming.deliveryState,
            mediaValidated = incoming.mediaValidated,
        ) == incoming,
    ) {
        "Account message archive immutable history changed"
    }
    // Validation provenance is monotone over the same immutable text: once either copy proved
    // the envelope binding, a copy written before the verdict existed (an older backup, or a
    // build that predates the flag) must not silently erase it.
    val mediaValidated = existing.mediaValidated || incoming.mediaValidated
    val existingRank = archivedDeliveryRank(existing)
    val incomingRank = archivedDeliveryRank(incoming)
    return when {
        incomingRank > existingRank -> incoming.copy(mediaValidated = mediaValidated)
        mediaValidated != existing.mediaValidated -> existing.copy(mediaValidated = mediaValidated)
        else -> null
    }
}

private fun archivedDeliveryRank(message: AccountArchivedMessage): Int {
    val selfAuthored = message.deliveryState in setOf(
        SecureMessagingProjectionDeliveryState.INBOUND_SELF_DELIVERED,
        SecureMessagingProjectionDeliveryState.INBOUND_SELF_READ,
    )
    return when (message.deliveryState) {
        SecureMessagingProjectionDeliveryState.INBOUND_RECEIVED -> 1
        SecureMessagingProjectionDeliveryState.INBOUND_READ -> {
            check(!selfAuthored)
            2
        }
        SecureMessagingProjectionDeliveryState.INBOUND_SELF_DELIVERED -> 2
        SecureMessagingProjectionDeliveryState.INBOUND_SELF_READ -> 3
        else -> error("Unarchivable message delivery state")
    }
}

internal fun archiveMessageRecordKey(serverMessageId: String): String {
    requireArchiveUuid(serverMessageId, "archived server message ID")
    return "message:$serverMessageId"
}

private object AccountArchivedMessageCodec {
    fun encode(message: AccountArchivedMessage): ByteArray {
        val output = WipingArchiveOutputStream()
        return try {
            DataOutputStream(output).use { data ->
                data.write(ARCHIVE_MESSAGE_MAGIC)
                // Any v2/future-family-bearing record uses the fenced schema, even without a
                // positive verdict. Otherwise an older build could decode its raw descriptor as
                // ordinary text. Canonical v1 and plain text remain byte-identical to schema 1.
                val fenced = requiresModernMediaSchemaFence(message.text) || message.mediaValidated
                data.writeInt(
                    if (fenced) {
                        ARCHIVE_MESSAGE_SCHEMA_MEDIA_VALIDATED
                    } else {
                        ARCHIVE_MESSAGE_SCHEMA
                    },
                )
                data.writeBounded(message.serverMessageId, MAX_ARCHIVE_IDENTIFIER_BYTES)
                data.writeBounded(message.clientMessageId, MAX_ARCHIVE_IDENTIFIER_BYTES)
                data.writeBounded(message.conversationId, MAX_ARCHIVE_IDENTIFIER_BYTES)
                data.writeBounded(message.sender.userId, MAX_ARCHIVE_IDENTIFIER_BYTES)
                data.writeBounded(message.sender.serverDeviceId, MAX_ARCHIVE_IDENTIFIER_BYTES)
                data.writeInt(message.sender.signalDeviceId)
                data.writeBounded(message.rosterRevision, MAX_ARCHIVE_IDENTIFIER_BYTES)
                data.writeBoolean(message.replyToMessageId != null)
                message.replyToMessageId?.let {
                    data.writeBounded(it, MAX_ARCHIVE_IDENTIFIER_BYTES)
                }
                data.writeLong(message.sentAt.toEpochMilli())
                data.writeByte(archiveDeliveryCode(message.deliveryState))
                data.writeBounded(message.text, MAX_ARCHIVED_TEXT_BYTES)
                if (fenced) data.writeBoolean(message.mediaValidated)
            }
            output.toOwnedByteArray().also {
                require(it.size in 1..MAX_ARCHIVE_MESSAGE_RECORD_BYTES) {
                    "Archived message record is too large"
                }
            }
        } finally {
            output.wipe()
        }
    }

    fun decode(bytes: ByteArray): AccountArchivedMessage {
        require(bytes.size in 1..MAX_ARCHIVE_MESSAGE_RECORD_BYTES) {
            "Invalid archived message record size"
        }
        val owned = bytes.copyOf()
        return try {
            val input = ByteArrayInputStream(owned)
            DataInputStream(input).use { data ->
                val magic = ByteArray(ARCHIVE_MESSAGE_MAGIC.size).also(data::readFully)
                require(magic.contentEquals(ARCHIVE_MESSAGE_MAGIC)) {
                    "Invalid archived message record header"
                }
                val schema = data.readInt()
                require(
                    schema == ARCHIVE_MESSAGE_SCHEMA ||
                        schema == ARCHIVE_MESSAGE_SCHEMA_MEDIA_VALIDATED,
                ) {
                    "Unsupported archived message record schema"
                }
                val message = AccountArchivedMessage(
                    serverMessageId = data.readBounded(MAX_ARCHIVE_IDENTIFIER_BYTES),
                    clientMessageId = data.readBounded(MAX_ARCHIVE_IDENTIFIER_BYTES),
                    conversationId = data.readBounded(MAX_ARCHIVE_IDENTIFIER_BYTES),
                    sender = SecureMessagingCryptoAddress(
                        userId = data.readBounded(MAX_ARCHIVE_IDENTIFIER_BYTES),
                        serverDeviceId = data.readBounded(MAX_ARCHIVE_IDENTIFIER_BYTES),
                        signalDeviceId = data.readInt(),
                    ),
                    rosterRevision = data.readBounded(MAX_ARCHIVE_IDENTIFIER_BYTES),
                    replyToMessageId = when (data.readUnsignedByte()) {
                        0 -> null
                        1 -> data.readBounded(MAX_ARCHIVE_IDENTIFIER_BYTES)
                        else -> error("Invalid archived reply-presence marker")
                    },
                    sentAt = Instant.ofEpochMilli(data.readLong()),
                    deliveryState = archiveDeliveryState(data.readUnsignedByte()),
                    text = data.readBounded(MAX_ARCHIVED_TEXT_BYTES),
                    // The fenced schema carries an explicit positive/negative verdict. Negative is
                    // essential for unvalidated v2/future-family text: old readers reject the
                    // record, while capable readers restore it fail-closed.
                    mediaValidated = when (schema) {
                        ARCHIVE_MESSAGE_SCHEMA_MEDIA_VALIDATED -> {
                            when (data.readUnsignedByte()) {
                                0 -> false
                                1 -> true
                                else -> error("Invalid archived media-validation marker")
                            }
                        }
                        else -> false
                    },
                )
                require(input.available() == 0) { "Archived message record contains trailing data" }
                message
            }
        } finally {
            owned.fill(0)
        }
    }
}

private class WipingArchiveOutputStream : ByteArrayOutputStream() {
    fun toOwnedByteArray(): ByteArray = toByteArray()

    fun wipe() {
        buf.fill(0)
        reset()
    }
}

private fun DataOutputStream.writeBounded(value: String, maximumBytes: Int) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    try {
        require(bytes.size in 1..maximumBytes) { "Invalid archived string size" }
        writeInt(bytes.size)
        write(bytes)
    } finally {
        bytes.fill(0)
    }
}

private fun DataInputStream.readBounded(maximumBytes: Int): String {
    val size = readInt()
    require(size in 1..maximumBytes && size <= available()) { "Invalid archived string size" }
    val bytes = ByteArray(size)
    return try {
        readFully(bytes)
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } finally {
        bytes.fill(0)
    }
}

private fun archiveDeliveryCode(state: SecureMessagingProjectionDeliveryState): Int = when (state) {
    SecureMessagingProjectionDeliveryState.INBOUND_RECEIVED -> 1
    SecureMessagingProjectionDeliveryState.INBOUND_READ -> 2
    SecureMessagingProjectionDeliveryState.INBOUND_SELF_DELIVERED -> 3
    SecureMessagingProjectionDeliveryState.INBOUND_SELF_READ -> 4
    else -> error("Unarchivable message delivery state")
}

private fun archiveDeliveryState(code: Int): SecureMessagingProjectionDeliveryState = when (code) {
    1 -> SecureMessagingProjectionDeliveryState.INBOUND_RECEIVED
    2 -> SecureMessagingProjectionDeliveryState.INBOUND_READ
    3 -> SecureMessagingProjectionDeliveryState.INBOUND_SELF_DELIVERED
    4 -> SecureMessagingProjectionDeliveryState.INBOUND_SELF_READ
    else -> error("Unknown archived message delivery state")
}

private fun requireArchiveUuid(value: String, field: String) {
    require(runCatching { UUID.fromString(value).toString() }.getOrNull() == value) {
        "$field must be a canonical lowercase UUID"
    }
}

private val ARCHIVABLE_INBOUND_STATES = setOf(
    SecureMessagingProjectionDeliveryState.INBOUND_RECEIVED,
    SecureMessagingProjectionDeliveryState.INBOUND_READ,
    SecureMessagingProjectionDeliveryState.INBOUND_SELF_DELIVERED,
    SecureMessagingProjectionDeliveryState.INBOUND_SELF_READ,
)

private val ARCHIVE_MESSAGE_MAGIC = "kit.account-message-history".toByteArray(Charsets.US_ASCII)
private const val ARCHIVE_MESSAGE_SCHEMA = 1

/** Schema for records carrying a positive media-validation verdict; older builds refuse it. */
private const val ARCHIVE_MESSAGE_SCHEMA_MEDIA_VALIDATED = 2
private const val MAX_ARCHIVE_IDENTIFIER_CHARS = 160
private const val MAX_ARCHIVE_IDENTIFIER_BYTES = 640
private const val MAX_ARCHIVED_TEXT_BYTES = 64 * 1024
private const val MAX_ARCHIVE_MESSAGE_RECORD_BYTES = 128 * 1024
private const val ARCHIVE_PAGE_SIZE = 100
private const val MAX_ARCHIVE_READ_PAGES = 100
private const val MAX_ARCHIVE_WRITE_ATTEMPTS = 3
