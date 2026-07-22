package com.kit.wallet.data.repository

import com.kit.wallet.data.messaging.KitMediaMessage
import com.kit.wallet.data.messaging.KitPaymentMessage
import com.kit.wallet.data.messaging.LibSignalCompanionDirection
import com.kit.wallet.data.messaging.LibSignalCompanionRecord
import com.kit.wallet.data.messaging.MediaAttachmentCipher
import com.kit.wallet.data.messaging.MAX_IMAGE_PLAINTEXT_BYTES
import com.kit.wallet.data.messaging.RemoteSecureMessagingTransport
import com.kit.wallet.data.messaging.SecureMessagingActiveSession
import com.kit.wallet.data.messaging.SecureMessagingActiveSessionRegistry
import com.kit.wallet.data.messaging.SecureMessagingCommittedResult
import com.kit.wallet.data.messaging.SecureMessagingCryptoEngine
import com.kit.wallet.data.messaging.SecureMessagingCryptoTransaction
import com.kit.wallet.data.messaging.SecureMessagingCryptoWireMapper
import com.kit.wallet.data.messaging.SecureMessagingEncryptionPlan
import com.kit.wallet.data.messaging.SecureMessagingEncryptionRequest
import com.kit.wallet.data.messaging.SecureMessagingEncryptedSend
import com.kit.wallet.data.messaging.SecureMessagingMissingSessionSet
import com.kit.wallet.data.messaging.SecureMessagingProjectionDeliveryState
import com.kit.wallet.data.messaging.SecureMessagingProjectionStore
import com.kit.wallet.data.messaging.SecureMessagingSyncEngine
import com.kit.wallet.data.messaging.requireDurablyCommittedSessions
import com.kit.wallet.di.ApplicationScope
import com.kit.wallet.ui.model.ChatPreview
import com.kit.wallet.ui.model.Contact
import com.kit.wallet.ui.model.DeliveryState
import com.kit.wallet.ui.model.Message
import com.kit.wallet.ui.model.MessageKind
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class AuthenticatedDirectConversation(
    val id: String,
    val peerUserId: String,
    val peerName: String?,
)

internal enum class AuthenticatedTextDeliveryState {
    RECEIVED,
    PENDING,
    SENT,
    DELIVERED,
    READ,
    RETRY_REQUIRED,
    RECEIVED_READ,
    PERMANENT_FAILURE,
}

/** Text can enter this type only after the encrypted companion record was durably authenticated. */
internal data class AuthenticatedProjectedText(
    val recordKey: String,
    val messageId: String,
    val serverMessageId: String?,
    val clientMessageId: String,
    val conversationId: String,
    val senderUserId: String,
    val fromCurrentUser: Boolean,
    val text: String,
    val sentAt: Instant,
    val deliveryState: AuthenticatedTextDeliveryState,
)

internal data class AuthenticatedProjectionPage(
    val messages: List<AuthenticatedProjectedText>,
    val nextAfterRecordKey: String?,
)

internal fun projectionIsFromCurrentUser(
    direction: LibSignalCompanionDirection,
    senderUserId: String,
    currentUserId: String,
): Boolean = when (direction) {
    LibSignalCompanionDirection.OUTBOUND -> {
        check(senderUserId == currentUserId) {
            "An outgoing encrypted projection belongs to another user"
        }
        true
    }
    // Another device on the same account produces an inbound envelope locally while retaining
    // current-user authorship in the authenticated sender address.
    LibSignalCompanionDirection.INBOUND -> senderUserId == currentUserId
}

/** Server order once assigned; a pending local send deliberately falls back to its client UUID. */
internal val authenticatedProjectionOrder = Comparator<AuthenticatedProjectedText> { left, right ->
    val timeOrder = left.sentAt.compareTo(right.sentAt)
    if (timeOrder != 0) {
        timeOrder
    } else {
        val idOrder = (left.serverMessageId ?: left.clientMessageId)
            .compareTo(right.serverMessageId ?: right.clientMessageId)
        if (idOrder != 0) idOrder else left.recordKey.compareTo(right.recordKey)
    }
}

/** Testable repository-facing surface; the production implementation retains opaque handles. */
internal interface SecureMessagingChatRuntime {
    val sessionEpoch: StateFlow<String?>
    val projectionChanges: StateFlow<Long>

    suspend fun directConversations(forceRefresh: Boolean = false): List<AuthenticatedDirectConversation>

    suspend fun createDirectConversation(peerUserId: String): AuthenticatedDirectConversation

    suspend fun projectionPage(
        afterRecordKey: String?,
        limit: Int,
    ): AuthenticatedProjectionPage

    suspend fun markConversationRead(conversationId: String)

    suspend fun synchronizeConversation(conversationId: String)

    suspend fun sendText(
        conversationId: String,
        text: String,
        retryClientMessageId: String? = null,
    )

    /** Encrypts, uploads and sends one image as an end-to-end encrypted media message. */
    suspend fun sendImage(
        conversationId: String,
        bytes: ByteArray,
        mediaType: String,
        caption: String?,
    ): Unit = error("This secure messaging runtime does not support media messages")

    /** Downloads and decrypts the media blob referenced by an authenticated media descriptor. */
    suspend fun openMedia(conversationId: String, descriptorText: String): ByteArray =
        error("This secure messaging runtime does not support media messages")
}

@Singleton
internal class DefaultSecureMessagingChatRuntime @Inject constructor(
    private val sessions: SecureMessagingActiveSessionRegistry,
    private val engine: SecureMessagingCryptoEngine,
    private val projections: SecureMessagingProjectionStore,
    private val syncEngine: SecureMessagingSyncEngine,
    @ApplicationScope scope: CoroutineScope,
    private val clock: Clock,
) : SecureMessagingChatRuntime {
    override val sessionEpoch: StateFlow<String?> = sessions.activeSession
        .map { it?.binding?.sessionEpoch }
        .stateIn(scope, SharingStarted.Eagerly, null)
    override val projectionChanges: StateFlow<Long> = projections.changes

    private val conversationMutex = Mutex()
    private val sendMutex = Mutex()
    private val readMutex = Mutex()
    private var conversationOwner: SecureMessagingActiveSession? = null
    private var conversationsLoaded = false
    private var conversationHandles =
        emptyMap<String, RemoteSecureMessagingTransport.Session.DirectConversation>()

    private data class RetryCandidate(
        val durable: LibSignalCompanionRecord,
        val deliveryState: SecureMessagingProjectionDeliveryState,
    )

    init {
        scope.launch {
            sessions.activeSession.collectLatest { active ->
                if (active == null) {
                    conversationMutex.withLock {
                        conversationOwner = null
                        conversationsLoaded = false
                        conversationHandles = emptyMap()
                    }
                }
            }
        }
    }

    override suspend fun directConversations(
        forceRefresh: Boolean,
    ): List<AuthenticatedDirectConversation> {
        val active = sessions.requireCurrent()
        return loadConversations(active, forceRefresh).values.map { it.toAuthenticated() }
    }

    override suspend fun createDirectConversation(
        peerUserId: String,
    ): AuthenticatedDirectConversation {
        val active = sessions.requireCurrent()
        loadConversations(active, forceRefresh = false).values
            .singleOrNull { it.peerUserId == peerUserId }
            ?.let { return it.toAuthenticated() }

        val created = active.transport.createDirectConversation(peerUserId)
        check(sessions.currentOrNull() === active) {
            "Secure messaging session changed while creating a conversation"
        }
        conversationMutex.withLock {
            prepareOwner(active)
            check(conversationHandles[created.conversationId] == null) {
                "The server created a duplicate direct conversation identifier"
            }
            conversationHandles = conversationHandles + (created.conversationId to created)
            conversationsLoaded = true
        }
        return created.toAuthenticated()
    }

    override suspend fun projectionPage(
        afterRecordKey: String?,
        limit: Int,
    ): AuthenticatedProjectionPage {
        val active = sessions.requireCurrent()
        val page = projections.readPage(afterRecordKey, limit)
        check(sessions.currentOrNull() === active) {
            "Secure messaging session changed while reading encrypted projections"
        }
        return AuthenticatedProjectionPage(
            messages = page.messages().map { projected ->
                val durable = projected.durableRecord
                val fromCurrentUser = projectionIsFromCurrentUser(
                    direction = durable.direction,
                    senderUserId = durable.sender.userId,
                    currentUserId = active.binding.userId,
                )
                AuthenticatedProjectedText(
                    recordKey = durable.recordKey,
                    messageId = projected.serverMessageId ?: durable.messageId,
                    serverMessageId = projected.serverMessageId,
                    clientMessageId = durable.clientMessageId,
                    conversationId = durable.conversationId,
                    senderUserId = durable.sender.userId,
                    fromCurrentUser = fromCurrentUser,
                    text = durable.authenticatedText,
                    sentAt = projected.sentAt,
                    deliveryState = projected.deliveryState.toAuthenticated(fromCurrentUser),
                )
            },
            nextAfterRecordKey = page.nextAfterRecordKey,
        )
    }

    override suspend fun sendText(
        conversationId: String,
        text: String,
        retryClientMessageId: String?,
    ) = sendMutex.withLock {
        val active = sessions.requireCurrent()
        val conversation = requireConversation(active, conversationId)
        retryClientMessageId?.let {
            require(CANONICAL_UUID.matches(it)) { "Invalid secure-message retry ID" }
        }
        var retry = retryClientMessageId?.let { clientMessageId ->
            requireNotNull(
                findRetryCandidate(active, conversationId, text, clientMessageId),
            ) { "The secure-message retry target is no longer available" }
        }
        // Enforce retry eligibility at the runtime boundary, not only in Compose. In particular,
        // a permanently failed media descriptor names a dead/single-use blob handle; encrypting
        // it under a fresh client ID would create another doomed pending fanout.
        if (retry != null && retry.deliveryState !in RETRYABLE_DELIVERY_STATES) {
            return@withLock
        }
        if (retry?.deliveryState == SecureMessagingProjectionDeliveryState.OUTBOUND_PENDING) {
            check(syncEngine.isReady) {
                "Secure messaging sync is unavailable for pending-ciphertext reconciliation"
            }
            syncEngine.synchronize()
            check(sessions.currentOrNull() === active) {
                "Secure messaging session changed while reconciling the encrypted outbox"
            }
            retry = findRetryCandidate(
                active = active,
                conversationId = conversationId,
                text = text,
                clientMessageId = checkNotNull(retryClientMessageId),
            )
            // A successful sync echo changed the durable item from pending to sent. The explicit
            // retry is complete and must not create a duplicate message.
            if (retry == null || retry.deliveryState !in RETRYABLE_DELIVERY_STATES) {
                return@withLock
            }
        }

        val roster = active.transport.roster(conversation)
        val plan = active.transport.encryptionPlan(conversation, roster)
        val pending = retry?.takeIf {
            it.deliveryState == SecureMessagingProjectionDeliveryState.OUTBOUND_PENDING
        }?.durable
        if (pending != null) {
            val planSnapshot = SecureMessagingCryptoWireMapper.requireEncryptionPlan(plan)
            if (
                pending.conversationId != planSnapshot.conversationId ||
                pending.rosterRevision != planSnapshot.rosterRevision ||
                pending.sender != planSnapshot.sender
            ) {
                // Never send committed fanout against a different roster. Keep the old bubble as
                // an explicit retry-required item, then create a fresh encrypted message below.
                projections.markOutboundRetryRequired(pending)
            } else {
                reissuePending(active, conversation, pending, plan)
                return@withLock
            }
        }
        val firstTransaction = active.transport.openCryptoTransaction(engine)
        val missing = missingSessionsOrAbort(firstTransaction, plan)
        val encryptionTransaction = if (missing.isEmpty) {
            firstTransaction
        } else {
            commitMissingSessions(
                active = active,
                conversation = conversation,
                roster = roster,
                plan = plan,
                transaction = firstTransaction,
                missingDeviceIds = missing.addresses().mapTo(mutableSetOf()) {
                    it.serverDeviceId
                },
            )
            active.transport.openCryptoTransaction(engine).also { transaction ->
                val unresolved = missingSessionsOrAbort(transaction, plan)
                if (!unresolved.isEmpty) {
                    transaction.abort()
                    error("Secure messaging sessions remain unavailable after key establishment")
                }
            }
        }

        // A normal send always receives a fresh ID, even when its text is byte-for-byte identical
        // to a pending message. Only the explicit retry path above may reuse committed fanout.
        val clientMessageId = UUID.randomUUID().toString()
        val encrypted = commitEncryption(
            transaction = encryptionTransaction,
            plan = plan,
            clientMessageId = clientMessageId,
            text = text,
        )
        val durable = checkNotNull(projections.readOutbound(clientMessageId)) {
            "Committed ciphertext is missing its durable outbox projection"
        }
        projections.recordOutboundPending(durable, clock.instant())
        // Server-visible attachment metadata is derived from the descriptor text on every send
        // and retry, so the end-to-end content and the metadata rows can never disagree.
        val receipt = active.transport.send(
            conversation,
            encrypted,
            KitMediaMessage.attachmentsFor(text),
        )
        projections.markOutboundSent(durable, receipt)
    }

    override suspend fun sendImage(
        conversationId: String,
        bytes: ByteArray,
        mediaType: String,
        caption: String?,
    ) {
        require(bytes.isNotEmpty()) { "Choose an image to send securely" }
        require(bytes.size <= MAX_IMAGE_PLAINTEXT_BYTES) {
            "Images up to ${MAX_IMAGE_PLAINTEXT_BYTES / (1024 * 1024)} MB are supported"
        }
        val normalizedMediaType = requireNotNull(KitMediaMessage.normalizeImageMediaType(mediaType)) {
            "Choose a JPEG, PNG, WebP or GIF image"
        }
        val active = sessions.requireCurrent()
        val conversation = requireConversation(active, conversationId)
        var encrypted: MediaAttachmentCipher.EncryptedAttachment? = null
        try {
            // Assign inside the non-cancellable worker before dispatching back. If cancellation
            // wins that return handoff, finally still owns and erases every produced array.
            withContext(Dispatchers.Default + NonCancellable) {
                encrypted = MediaAttachmentCipher.encrypt(bytes)
            }
            coroutineContext.ensureActive()
            val owned = checkNotNull(encrypted)
            val uploaded = active.transport.uploadAttachment(normalizedMediaType, owned.ciphertext)
            check(sessions.currentOrNull() === active) {
                "Secure messaging session changed while uploading encrypted media"
            }
            val descriptor = KitMediaMessage(
                attachmentId = UUID.randomUUID().toString(),
                storageKey = uploaded.storageKey,
                mediaType = normalizedMediaType,
                ciphertextByteSize = uploaded.byteSize,
                ciphertextSha256 = uploaded.ciphertextSha256,
                keyMaterialBase64 = Base64.getEncoder().encodeToString(owned.keyMaterial),
                plaintextByteSize = owned.plaintextSize,
                caption = caption?.trim()?.takeIf(String::isNotEmpty),
            )
            val authenticatedText = descriptor.encode()
            check(KitMediaMessage.parse(authenticatedText) == descriptor) {
                "The attachment store returned media metadata that cannot be authenticated"
            }
            sendText(conversation.conversationId, authenticatedText)
        } finally {
            encrypted?.ciphertext?.fill(0)
            encrypted?.keyMaterial?.fill(0)
            encrypted?.sha256?.fill(0)
        }
    }

    override suspend fun openMedia(conversationId: String, descriptorText: String): ByteArray {
        val media = requireNotNull(KitMediaMessage.parse(descriptorText)) {
            "This message does not reference readable secure media"
        }
        val active = sessions.requireCurrent()
        requireConversation(active, conversationId)
        var ciphertext: ByteArray? = null
        var keyMaterial: ByteArray? = null
        var expectedSha256: ByteArray? = null
        var plaintext: ByteArray? = null
        var returned = false
        try {
            // Retrofit returns a streaming ResponseBody. Assign the bounded result inside the
            // worker so prompt cancellation cannot discard the only reference before cleanup.
            withContext(Dispatchers.IO + NonCancellable) {
                ciphertext = active.transport.downloadAttachment(
                    storageKey = media.storageKey,
                    maximumBytes = media.ciphertextByteSize,
                )
            }
            coroutineContext.ensureActive()
            val downloaded = checkNotNull(ciphertext)
            keyMaterial = media.keyMaterial()
            expectedSha256 = media.ciphertextSha256Bytes()
            val key = checkNotNull(keyMaterial)
            val digest = checkNotNull(expectedSha256)
            check(sessions.currentOrNull() === active) {
                "Secure messaging session changed while downloading encrypted media"
            }
            check(downloaded.size.toLong() == media.ciphertextByteSize) {
                "The encrypted media blob does not match its authenticated size"
            }
            // decrypt() enforces the authenticated SHA-256 digest and the HMAC before returning.
            withContext(Dispatchers.Default + NonCancellable) {
                plaintext = MediaAttachmentCipher.decrypt(
                    ciphertext = downloaded,
                    keyMaterial = key,
                    expectedSha256 = digest,
                )
            }
            coroutineContext.ensureActive()
            val decrypted = checkNotNull(plaintext)
            plaintext = decrypted
            check(decrypted.size == media.plaintextByteSize) {
                "The decrypted media does not match its authenticated size"
            }
            if (sessions.currentOrNull() !== active) {
                decrypted.fill(0)
                error("Secure messaging session changed while decrypting encrypted media")
            }
            returned = true
            return decrypted
        } finally {
            if (!returned) plaintext?.fill(0)
            ciphertext?.fill(0)
            keyMaterial?.fill(0)
            expectedSha256?.fill(0)
        }
    }

    override suspend fun markConversationRead(conversationId: String) = readMutex.withLock {
        val active = sessions.requireCurrent()
        val conversation = requireConversation(active, conversationId)
        val newestUnreadMessageId = projections.newestUnreadInboundMessageId(
            conversationId = conversationId,
            peerUserId = conversation.peerUserId,
        ) ?: return@withLock
        // Persist the server-visible receipt first. If it fails, the durable unread projection
        // remains retryable and the UI must not falsely claim that the receipt was published.
        val receipt = active.transport.markConversationRead(conversation, newestUnreadMessageId)
        check(sessions.currentOrNull() === active) {
            "Secure messaging session changed while publishing a read receipt"
        }
        projections.markInboundReadThrough(
            conversationId = conversationId,
            peerUserId = conversation.peerUserId,
            requestedLastReadMessageId = newestUnreadMessageId,
            canonicalLastReadMessageId = receipt.lastReadMessageId,
            canonicalReadAt = receipt.readAt,
        )
        check(sessions.currentOrNull() === active) {
            "Secure messaging session changed while saving local read state"
        }
    }

    override suspend fun synchronizeConversation(conversationId: String) {
        val active = sessions.requireCurrent()
        requireConversation(active, conversationId)
        check(syncEngine.isReady) { "Secure messaging sync is unavailable" }
        syncEngine.synchronize()
        check(sessions.currentOrNull() === active) {
            "Secure messaging session changed during foreground sync"
        }
    }

    private suspend fun loadConversations(
        active: SecureMessagingActiveSession,
        forceRefresh: Boolean,
    ): Map<String, RemoteSecureMessagingTransport.Session.DirectConversation> =
        conversationMutex.withLock {
            prepareOwner(active)
            if (forceRefresh || !conversationsLoaded) {
                val loaded = active.transport.directConversations()
                check(sessions.currentOrNull() === active) {
                    "Secure messaging session changed while loading conversations"
                }
                val byId = loaded.associateBy { it.conversationId }
                check(byId.size == loaded.size) {
                    "The server returned duplicate direct conversation identifiers"
                }
                check(loaded.map { it.peerUserId }.distinct().size == loaded.size) {
                    "The server returned duplicate direct conversation peers"
                }
                conversationHandles = byId
                conversationsLoaded = true
            }
            conversationHandles.toMap()
        }

    private suspend fun requireConversation(
        active: SecureMessagingActiveSession,
        conversationId: String,
    ): RemoteSecureMessagingTransport.Session.DirectConversation =
        loadConversations(active, forceRefresh = false)[conversationId]
            ?: loadConversations(active, forceRefresh = true)[conversationId]
            ?: error("The secure direct conversation is no longer available")

    private fun prepareOwner(active: SecureMessagingActiveSession) {
        if (conversationOwner !== active) {
            conversationOwner = active
            conversationsLoaded = false
            conversationHandles = emptyMap()
        }
    }

    private suspend fun findRetryCandidate(
        active: SecureMessagingActiveSession,
        conversationId: String,
        text: String,
        clientMessageId: String,
    ): RetryCandidate? {
        var after: String? = null
        repeat(MAX_PROJECTION_PAGES) {
            val page = projections.readPage(after, PROJECTION_PAGE_SIZE)
            check(sessions.currentOrNull() === active) {
                "Secure messaging session changed while recovering the encrypted outbox"
            }
            page.messages().singleOrNull { projected ->
                projected.durableRecord.clientMessageId == clientMessageId
            }?.let { projected ->
                val durable = projected.durableRecord
                check(durable.direction == LibSignalCompanionDirection.OUTBOUND) {
                    "A secure-message retry target is not outbound"
                }
                check(
                    durable.conversationId == conversationId &&
                        durable.sender.userId == active.binding.userId &&
                        durable.authenticatedText == text
                ) { "A secure-message retry target does not match the requested message" }
                return RetryCandidate(durable, projected.deliveryState)
            }
            val next = page.nextAfterRecordKey ?: return null
            check(after == null || next > after!!) {
                "Encrypted projection pagination did not advance"
            }
            after = next
        }
        error("Encrypted projection history exceeds the supported recovery bound")
    }

    private suspend fun reissuePending(
        active: SecureMessagingActiveSession,
        conversation: RemoteSecureMessagingTransport.Session.DirectConversation,
        durable: LibSignalCompanionRecord,
        plan: SecureMessagingEncryptionPlan,
    ) {
        val encrypted = SecureMessagingCryptoWireMapper.retryEncryption(durable, plan)
        val receipt = active.transport.send(
            conversation,
            encrypted,
            KitMediaMessage.attachmentsFor(durable.authenticatedText),
        )
        projections.markOutboundSent(durable, receipt)
    }

    private suspend fun commitMissingSessions(
        active: SecureMessagingActiveSession,
        conversation: RemoteSecureMessagingTransport.Session.DirectConversation,
        roster: RemoteSecureMessagingTransport.Session.AuthoritativeRoster,
        plan: SecureMessagingEncryptionPlan,
        transaction: SecureMessagingCryptoTransaction,
        missingDeviceIds: Set<String>,
    ) {
        var committed = false
        try {
            val request = active.transport.consumeKeyBundles(
                conversation = conversation,
                roster = roster,
                plan = plan,
                deviceIds = missingDeviceIds,
            )
            transaction.stageSessionEstablishment(request)
            val result = transaction.commit()
            check(result is SecureMessagingCommittedResult.SessionsEstablished) {
                "Secure messaging key establishment returned the wrong committed operation"
            }
            requireDurablyCommittedSessions(result)
            committed = true
        } finally {
            if (!committed) transaction.abort()
        }
    }

    private suspend fun commitEncryption(
        transaction: SecureMessagingCryptoTransaction,
        plan: SecureMessagingEncryptionPlan,
        clientMessageId: String,
        text: String,
    ): SecureMessagingEncryptedSend {
        var committed = false
        val request = SecureMessagingEncryptionRequest(
            plan = plan,
            clientMessageId = clientMessageId,
            text = text,
        )
        return try {
            transaction.stageEncryption(request, projections.outboundIntent(clientMessageId))
            val result = transaction.commit()
            check(result is SecureMessagingCommittedResult.Encrypted) {
                "Secure messaging encryption returned the wrong committed operation"
            }
            committed = true
            SecureMessagingCryptoWireMapper.encryption(result)
        } finally {
            request.close()
            if (!committed) transaction.abort()
        }
    }

    private suspend fun missingSessionsOrAbort(
        transaction: SecureMessagingCryptoTransaction,
        plan: SecureMessagingEncryptionPlan,
    ): SecureMessagingMissingSessionSet = try {
        transaction.missingSessions(plan)
    } catch (error: Throwable) {
        try {
            withContext(NonCancellable) { transaction.abort() }
        } catch (abortError: Throwable) {
            error.addSuppressed(abortError)
        }
        throw error
    }

    private fun RemoteSecureMessagingTransport.Session.DirectConversation.toAuthenticated() =
        AuthenticatedDirectConversation(
            id = conversationId,
            peerUserId = peerUserId,
            peerName = peerName,
        )

    private fun SecureMessagingProjectionDeliveryState.toAuthenticated(
        fromCurrentUser: Boolean,
    ) = when (this) {
        SecureMessagingProjectionDeliveryState.INBOUND_RECEIVED -> if (fromCurrentUser) {
            AuthenticatedTextDeliveryState.SENT
        } else {
            AuthenticatedTextDeliveryState.RECEIVED
        }
        SecureMessagingProjectionDeliveryState.INBOUND_READ -> {
            check(!fromCurrentUser) { "A self-authored inbound message used local peer-read state" }
            AuthenticatedTextDeliveryState.RECEIVED_READ
        }
        SecureMessagingProjectionDeliveryState.INBOUND_SELF_DELIVERED -> {
            check(fromCurrentUser) { "A peer-authored inbound message used sender delivery state" }
            AuthenticatedTextDeliveryState.DELIVERED
        }
        SecureMessagingProjectionDeliveryState.INBOUND_SELF_READ -> {
            check(fromCurrentUser) { "A peer-authored inbound message used sender read state" }
            AuthenticatedTextDeliveryState.READ
        }
        SecureMessagingProjectionDeliveryState.OUTBOUND_PENDING ->
            AuthenticatedTextDeliveryState.PENDING
        SecureMessagingProjectionDeliveryState.OUTBOUND_SENT ->
            AuthenticatedTextDeliveryState.SENT
        SecureMessagingProjectionDeliveryState.OUTBOUND_DELIVERED ->
            AuthenticatedTextDeliveryState.DELIVERED
        SecureMessagingProjectionDeliveryState.OUTBOUND_READ ->
            AuthenticatedTextDeliveryState.READ
        SecureMessagingProjectionDeliveryState.OUTBOUND_RETRY_REQUIRED ->
            AuthenticatedTextDeliveryState.RETRY_REQUIRED
        SecureMessagingProjectionDeliveryState.OUTBOUND_PERMANENT_FAILURE ->
            AuthenticatedTextDeliveryState.PERMANENT_FAILURE
        SecureMessagingProjectionDeliveryState.INBOUND_SUPPRESSED ->
            error("Suppressed inbound records must not enter authenticated projection pages")
    }

    private companion object {
        const val PROJECTION_PAGE_SIZE = 100
        const val MAX_PROJECTION_PAGES = 100
        val CANONICAL_UUID = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        )
        val RETRYABLE_DELIVERY_STATES = setOf(
            SecureMessagingProjectionDeliveryState.OUTBOUND_PENDING,
            SecureMessagingProjectionDeliveryState.OUTBOUND_RETRY_REQUIRED,
        )
    }
}

@Singleton
class EncryptedChatRepository @Inject internal constructor(
    private val runtime: SecureMessagingChatRuntime,
    private val contacts: ContactRepository,
    @ApplicationScope scope: CoroutineScope,
    clock: Clock,
) : ChatRepository {
    override val readiness: StateFlow<Boolean> = runtime.sessionEpoch
        .map { it != null }
        .stateIn(scope, SharingStarted.Eagerly, runtime.sessionEpoch.value != null)

    private val mutableChats = MutableStateFlow<List<ChatPreview>>(emptyList())
    override val chats: StateFlow<List<ChatPreview>> = mutableChats.asStateFlow()
    private val conversationLock = Any()
    private val conversationFlows = mutableMapOf<String, MutableStateFlow<List<Message>>>()
    private val refreshMutex = Mutex()
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(clock.zone)
    private var publishedSessionEpoch: String? = null

    init {
        scope.launch {
            combine(
                runtime.sessionEpoch,
                runtime.projectionChanges,
                contacts.contacts,
            ) { epoch, _, contactList -> epoch to contactList }
                .collectLatest { (epoch, contactList) ->
                    if (epoch == null) {
                        clearPublishedState()
                    } else {
                        try {
                            refresh(epoch, contactList)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            if (runtime.sessionEpoch.value != epoch || publishedSessionEpoch != epoch) {
                                clearPublishedState()
                            }
                        }
                    }
                }
        }
    }

    override fun chat(chatId: String): ChatPreview? = chats.value.singleOrNull { it.id == chatId }

    override fun conversation(chatId: String): StateFlow<List<Message>> =
        synchronized(conversationLock) {
            conversationFlows.getOrPut(chatId) { MutableStateFlow(emptyList()) }.asStateFlow()
        }

    override suspend fun openDirectConversation(contact: Contact): String {
        check(readiness.value) { "Secure messaging has no active message-ready session" }
        require(contact.isKitUser) {
            "Only contacts who are on Kit Pay can receive secure messages"
        }
        val created = runtime.createDirectConversation(contact.id)
        refresh(checkNotNull(runtime.sessionEpoch.value), contacts.contacts.value)
        check(chat(created.id) != null) { "The secure conversation was not added to the projection" }
        return created.id
    }

    override suspend fun sendMessage(chatId: String, text: String) {
        check(readiness.value) { "Secure messaging has no active message-ready session" }
        val normalized = text.trim()
        require(normalized.isNotEmpty()) { "Enter a message to send securely" }
        try {
            runtime.sendText(chatId, normalized, retryClientMessageId = null)
        } finally {
            runtime.sessionEpoch.value?.let { refresh(it, contacts.contacts.value) }
        }
    }

    override suspend fun retryMessage(chatId: String, clientMessageId: String, text: String) {
        check(readiness.value) { "Secure messaging has no active message-ready session" }
        val normalized = text.trim()
        require(normalized.isNotEmpty()) { "Enter a message to retry securely" }
        try {
            runtime.sendText(chatId, normalized, retryClientMessageId = clientMessageId)
        } finally {
            runtime.sessionEpoch.value?.let { refresh(it, contacts.contacts.value) }
        }
    }

    override suspend fun sendImageMessage(
        chatId: String,
        bytes: ByteArray,
        mediaType: String,
        caption: String?,
    ) {
        check(readiness.value) { "Secure messaging has no active message-ready session" }
        try {
            runtime.sendImage(chatId, bytes, mediaType, caption)
        } finally {
            runtime.sessionEpoch.value?.let { refresh(it, contacts.contacts.value) }
        }
    }

    override suspend fun openImageMessage(chatId: String, mediaDescriptor: String): ByteArray {
        check(readiness.value) { "Secure messaging has no active message-ready session" }
        return runtime.openMedia(chatId, mediaDescriptor)
    }

    override suspend fun markConversationRead(chatId: String) {
        check(readiness.value) { "Secure messaging has no active message-ready session" }
        runtime.markConversationRead(chatId)
        runtime.sessionEpoch.value?.let { refresh(it, contacts.contacts.value) }
    }

    override suspend fun synchronizeConversation(chatId: String) {
        check(readiness.value) { "Secure messaging has no active message-ready session" }
        runtime.synchronizeConversation(chatId)
    }

    private suspend fun refresh(
        epoch: String,
        localContacts: List<Contact>,
    ) = refreshMutex.withLock {
        if (runtime.sessionEpoch.value != epoch) return@withLock
        var conversations = runtime.directConversations(forceRefresh = false)
        val projections = readAllProjectionPages()
        if (projections.any { projected -> conversations.none { it.id == projected.conversationId } }) {
            conversations = runtime.directConversations(forceRefresh = true)
        }
        check(runtime.sessionEpoch.value == epoch) {
            "Secure messaging session changed while publishing chat projections"
        }
        publish(epoch, conversations, projections, localContacts)
    }

    private suspend fun readAllProjectionPages(): List<AuthenticatedProjectedText> {
        val projected = mutableListOf<AuthenticatedProjectedText>()
        var after: String? = null
        repeat(MAX_PROJECTION_PAGES) {
            val page = runtime.projectionPage(after, PROJECTION_PAGE_SIZE)
            projected += page.messages
            val next = page.nextAfterRecordKey ?: return projected
            check(after == null || next > after!!) { "Encrypted projection pagination did not advance" }
            after = next
        }
        error("Encrypted projection history exceeds the supported display bound")
    }

    private fun publish(
        epoch: String,
        conversations: List<AuthenticatedDirectConversation>,
        projections: List<AuthenticatedProjectedText>,
        localContacts: List<Contact>,
    ) {
        val conversationIds = conversations.mapTo(mutableSetOf()) { it.id }
        val authenticated = projections.filter { it.conversationId in conversationIds }
        val conversationsById = conversations.associateBy(AuthenticatedDirectConversation::id)
        authenticated.forEach { projected ->
            if (!projected.fromCurrentUser) {
                check(
                    conversationsById[projected.conversationId]?.peerUserId ==
                        projected.senderUserId,
                ) { "An authenticated direct-message projection belongs to another peer" }
            }
        }
        val messageLists = authenticated.groupBy { it.conversationId }.mapValues { (_, values) ->
            values.sortedWith(authenticatedProjectionOrder)
                .map(::toUiMessage)
        }
        synchronized(conversationLock) {
            conversationFlows.values.forEach { it.value = emptyList() }
            messageLists.forEach { (conversationId, messages) ->
                conversationFlows.getOrPut(conversationId) { MutableStateFlow(emptyList()) }.value = messages
            }
        }
        val projectedByConversation = authenticated.groupBy(AuthenticatedProjectedText::conversationId)
        val latestByConversation = projectedByConversation.mapValues { (_, messages) ->
            messages.maxWithOrNull(authenticatedProjectionOrder)
        }
        val savedNames = localContacts.asSequence()
            .filter { it.isKitUser && it.savedInDevice }
            .associate { it.id to it.name.trim() }
        mutableChats.value = conversations.sortedWith(
            compareByDescending<AuthenticatedDirectConversation> { conversation ->
                latestByConversation[conversation.id]?.sentAt?.toEpochMilli() ?: Long.MIN_VALUE
            }.thenBy { it.peerName?.lowercase().orEmpty() }.thenBy { it.id },
        ).map { conversation ->
            val last = latestByConversation[conversation.id]
            ChatPreview(
                id = conversation.id,
                name = savedNames[conversation.peerUserId]?.takeIf(String::isNotEmpty)
                    ?: conversation.peerName?.trim()?.takeIf(String::isNotEmpty)
                    ?: "Kit Pay contact",
                lastMessage = last?.text?.let { text ->
                    KitMediaMessage.parse(text)?.let { media -> media.caption ?: "📷 Photo" }
                        ?: KitPaymentMessage.parse(text)?.let { payment ->
                            if (payment.isRequest) "💰 Payment request" else "💸 Payment"
                        }
                        ?: text
                }.orEmpty(),
                time = last?.sentAt?.let(timeFormatter::format).orEmpty(),
                peerUserId = conversation.peerUserId,
                unread = projectedByConversation[conversation.id].orEmpty().count { projected ->
                    !projected.fromCurrentUser &&
                        projected.deliveryState == AuthenticatedTextDeliveryState.RECEIVED
                },
                isGroup = false,
                lastFromMe = last?.fromCurrentUser == true,
                lastState = last?.deliveryState.toUiDeliveryState(),
            )
        }
        publishedSessionEpoch = epoch
    }

    private fun toUiMessage(projected: AuthenticatedProjectedText): Message {
        val media = KitMediaMessage.parse(projected.text)
        val payment = if (media == null) KitPaymentMessage.parse(projected.text) else null
        return Message(
            id = projected.messageId,
            text = when {
                media != null -> media.caption ?: "📷 Photo"
                payment != null -> payment.note.orEmpty()
                else -> projected.text
            },
            time = timeFormatter.format(projected.sentAt),
            fromMe = projected.fromCurrentUser,
            state = projected.deliveryState.toUiDeliveryState(),
            kind = when {
                media != null -> MessageKind.IMAGE
                payment == null -> MessageKind.TEXT
                payment.isRequest -> MessageKind.PAYMENT_REQUEST
                else -> MessageKind.PAYMENT
            },
            // The opaque authenticated descriptor; the UI passes it back for follow-up actions.
            mediaDescriptor = if (media != null || payment != null) projected.text else null,
            amountMinor = when {
                payment == null -> 0
                // A completed payment reads "sent" for the payer and "received" for the requester.
                !payment.isRequest && projected.fromCurrentUser -> -payment.amountMinor
                else -> payment.amountMinor
            },
            paymentRequestId = payment?.paymentRequestId,
            paymentNote = payment?.note,
            sortEpochMillis = projected.sentAt.toEpochMilli(),
        )
    }

    private fun AuthenticatedTextDeliveryState?.toUiDeliveryState(): DeliveryState = when (this) {
        AuthenticatedTextDeliveryState.PENDING -> DeliveryState.SENDING
        AuthenticatedTextDeliveryState.SENT -> DeliveryState.SENT
        AuthenticatedTextDeliveryState.DELIVERED -> DeliveryState.DELIVERED
        AuthenticatedTextDeliveryState.RECEIVED -> DeliveryState.DELIVERED
        AuthenticatedTextDeliveryState.READ,
        AuthenticatedTextDeliveryState.RECEIVED_READ,
        null,
        -> DeliveryState.READ
        AuthenticatedTextDeliveryState.RETRY_REQUIRED -> DeliveryState.RETRY_REQUIRED
        AuthenticatedTextDeliveryState.PERMANENT_FAILURE -> DeliveryState.FAILED
    }

    private fun clearPublishedState() {
        publishedSessionEpoch = null
        mutableChats.value = emptyList()
        synchronized(conversationLock) {
            conversationFlows.values.forEach { it.value = emptyList() }
        }
    }

    private companion object {
        const val PROJECTION_PAGE_SIZE = 100
        const val MAX_PROJECTION_PAGES = 100
    }
}
