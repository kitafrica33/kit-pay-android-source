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
import com.kit.wallet.data.messaging.SecureMessagingStateConflictException
import com.kit.wallet.data.messaging.SecureMessagingSyncCompletionSignal
import com.kit.wallet.data.messaging.SecureMessagingSyncEngine
import com.kit.wallet.data.messaging.isRecoverableSecureMessagingStateLoss
import com.kit.wallet.data.messaging.requireDurablyCommittedSessions
import com.kit.wallet.data.remote.KitWalletApiException
import com.kit.wallet.di.ApplicationScope
import com.kit.wallet.ui.model.ChatPreview
import com.kit.wallet.ui.model.Contact
import com.kit.wallet.ui.model.DeliveryState
import com.kit.wallet.ui.model.Message
import com.kit.wallet.ui.model.MessageKind
import com.kit.wallet.worker.SecureMessagingSyncScheduler
import java.io.IOException
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
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

/** One process-local message-ready activation; a reset may replace it within the same login. */
internal class SecureMessagingChatSession internal constructor(
    val sessionEpoch: String,
    internal val identity: Any,
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
    val activeSession: StateFlow<SecureMessagingChatSession?>
    val projectionChanges: StateFlow<Long>
    val baselineRetrySessions: Flow<SecureMessagingChatSession>
        get() = emptyFlow()

    fun isCurrent(session: SecureMessagingChatSession): Boolean = activeSession.value === session

    /** Atomically publishes local UI state only for the exact current opaque activation. */
    fun publishIfCurrent(
        session: SecureMessagingChatSession?,
        publication: () -> Unit,
    ): Boolean

    /** Routes only proved key loss or migration-fenced unreadable state through local recovery. */
    suspend fun recoverPermanentlyUnavailableState(error: Throwable): Boolean = false

    suspend fun directConversations(
        session: SecureMessagingChatSession,
        forceRefresh: Boolean = false,
    ): List<AuthenticatedDirectConversation>

    suspend fun createDirectConversation(
        session: SecureMessagingChatSession,
        peerUserId: String,
    ): AuthenticatedDirectConversation

    suspend fun projectionPage(
        session: SecureMessagingChatSession,
        afterRecordKey: String?,
        limit: Int,
    ): AuthenticatedProjectionPage

    suspend fun markConversationRead(
        session: SecureMessagingChatSession,
        conversationId: String,
    )

    suspend fun synchronizeConversation(
        session: SecureMessagingChatSession,
        conversationId: String,
    )

    suspend fun sendText(
        session: SecureMessagingChatSession,
        conversationId: String,
        text: String,
        retryClientMessageId: String? = null,
    )

    /** Encrypts, uploads and sends one image as an end-to-end encrypted media message. */
    suspend fun sendImage(
        session: SecureMessagingChatSession,
        conversationId: String,
        bytes: ByteArray,
        mediaType: String,
        caption: String?,
    ): Unit = error("This secure messaging runtime does not support media messages")

    /** Downloads and decrypts the media blob referenced by an authenticated media descriptor. */
    suspend fun openMedia(
        session: SecureMessagingChatSession,
        conversationId: String,
        descriptorText: String,
    ): ByteArray =
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
    private val syncScheduler: SecureMessagingSyncScheduler? = null,
    private val syncCompletions: SecureMessagingSyncCompletionSignal =
        SecureMessagingSyncCompletionSignal(),
) : SecureMessagingChatRuntime {
    override val activeSession: StateFlow<SecureMessagingChatSession?> = sessions.activeSession
        .map { active ->
            active?.let {
                SecureMessagingChatSession(
                    sessionEpoch = it.binding.sessionEpoch,
                    identity = it,
                )
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, null)
    override val projectionChanges: StateFlow<Long> = projections.changes
    override val baselineRetrySessions: Flow<SecureMessagingChatSession> =
        combine(syncCompletions.completions, activeSession) { completed, exposed ->
            exposed?.takeIf { it.identity === completed }
        }.filterNotNull()

    private val conversationMutex = Mutex()
    private val archiveRestoreMutex = Mutex()
    private val sendMutex = Mutex()
    private val readMutex = Mutex()
    private var conversationOwner: SecureMessagingActiveSession? = null
    private var conversationsLoaded = false
    private var archiveRestoredOwner: SecureMessagingActiveSession? = null
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
                    archiveRestoreMutex.withLock { archiveRestoredOwner = null }
                }
            }
        }
    }

    override fun isCurrent(session: SecureMessagingChatSession): Boolean =
        sessions.currentOrNull() === session.identity

    override fun publishIfCurrent(
        session: SecureMessagingChatSession?,
        publication: () -> Unit,
    ): Boolean {
        val expected = session?.identity?.let { identity ->
            identity as? SecureMessagingActiveSession ?: return false
        }
        return sessions.publishIfCurrent(expected, publication)
    }

    private fun requireCurrent(
        session: SecureMessagingChatSession,
    ): SecureMessagingActiveSession {
        val expected = session.identity as? SecureMessagingActiveSession
            ?: error("Secure messaging session was not issued by this runtime")
        val active = sessions.requireCurrent()
        check(active === expected) {
            "Secure messaging session changed before the requested operation"
        }
        return active
    }

    override suspend fun recoverPermanentlyUnavailableState(error: Throwable): Boolean {
        if (!isRecoverableSecureMessagingStateLoss(error) || !syncEngine.isReady) {
            return false
        }
        val previous = sessions.currentOrNull() ?: return false
        // Quarantine temporarily removes the active handle, which cancels collectLatest. Finish
        // the already-authorized fenced recovery so cancellation cannot strand the login in
        // QUARANTINED before the fresh activation is published.
        try {
            withContext(NonCancellable) {
                syncEngine.recoverPermanentlyUnavailableState(previous.fence)
            }
        } catch (error: Exception) {
            // A network/provider failure before or during reset must not turn this one foreground
            // attempt into another permanent stall. WorkManager durably retries; a successful
            // replacement emits a new opaque active-session identity and restarts the baseline.
            runCatching { syncScheduler?.schedule() }
                .exceptionOrNull()
                ?.let(error::addSuppressed)
            throw error
        }
        return sessions.currentOrNull()?.let { it !== previous } == true
    }

    override suspend fun directConversations(
        session: SecureMessagingChatSession,
        forceRefresh: Boolean,
    ): List<AuthenticatedDirectConversation> {
        val active = requireCurrent(session)
        val loaded = loadConversations(active, forceRefresh)
        archiveRestoreMutex.withLock {
            if (archiveRestoredOwner !== active) {
                // A missing/corrupt archive must not disable a valid new Signal epoch. Leave the
                // owner unset so a later projection refresh can retry after transient Keystore IO.
                try {
                    projections.restoreArchivedHistory(
                        activation = active.activation,
                        currentUserId = active.binding.userId,
                        allowedConversationIds = loaded.keys,
                    )
                    check(sessions.currentOrNull() === active) {
                        "Secure messaging session changed while restoring archived history"
                    }
                    archiveRestoredOwner = active
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // A missing/corrupt display archive never disables the new Signal epoch. The
                    // unset owner makes the next conversation refresh retry a recoverable failure.
                }
            }
        }
        return loaded.values.map { it.toAuthenticated() }
    }

    override suspend fun createDirectConversation(
        session: SecureMessagingChatSession,
        peerUserId: String,
    ): AuthenticatedDirectConversation {
        val active = requireCurrent(session)
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
        session: SecureMessagingChatSession,
        afterRecordKey: String?,
        limit: Int,
    ): AuthenticatedProjectionPage {
        val active = requireCurrent(session)
        val page = projections.readPageAndArchive(
            activation = active.activation,
            expectedOwnerAccountId = active.binding.userId,
            afterRecordKey = afterRecordKey,
            limit = limit,
        )
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
        session: SecureMessagingChatSession,
        conversationId: String,
        text: String,
        retryClientMessageId: String?,
    ) = sendMutex.withLock {
        val active = requireCurrent(session)
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
            syncEngine.synchronize(active.fence)
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
                projections.withActivationLease(
                    active.activation,
                    readyRequired = true,
                ) {
                    markOutboundRetryRequired(pending)
                }
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
        val durable = projections.withActivationLease(
            active.activation,
            readyRequired = true,
        ) {
            checkNotNull(readOutbound(clientMessageId)) {
                "Committed ciphertext is missing its durable outbox projection"
            }.also { committed ->
                recordOutboundPending(committed, clock.instant())
            }
        }
        // Server-visible attachment metadata is derived from the descriptor text on every send
        // and retry, so the end-to-end content and the metadata rows can never disagree.
        val receipt = active.transport.send(
            conversation,
            encrypted,
            KitMediaMessage.attachmentsFor(text),
        )
        projections.withActivationLease(active.activation, readyRequired = true) {
            markOutboundSent(durable, receipt)
        }
    }

    override suspend fun sendImage(
        session: SecureMessagingChatSession,
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
        val active = requireCurrent(session)
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
            sendText(session, conversation.conversationId, authenticatedText)
        } finally {
            encrypted?.ciphertext?.fill(0)
            encrypted?.keyMaterial?.fill(0)
            encrypted?.sha256?.fill(0)
        }
    }

    override suspend fun openMedia(
        session: SecureMessagingChatSession,
        conversationId: String,
        descriptorText: String,
    ): ByteArray {
        val media = requireNotNull(KitMediaMessage.parse(descriptorText)) {
            "This message does not reference readable secure media"
        }
        val active = requireCurrent(session)
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
            check(
                sessions.publishIfCurrent(active) {
                    returned = true
                },
            ) {
                "Secure messaging session changed while decrypting encrypted media"
            }
            return decrypted
        } finally {
            if (!returned) plaintext?.fill(0)
            ciphertext?.fill(0)
            keyMaterial?.fill(0)
            expectedSha256?.fill(0)
        }
    }

    override suspend fun markConversationRead(
        session: SecureMessagingChatSession,
        conversationId: String,
    ) = readMutex.withLock {
        val active = requireCurrent(session)
        val conversation = requireConversation(active, conversationId)
        val newestUnreadMessageId = projections.withActivationLease(
            active.activation,
            readyRequired = true,
        ) {
            newestUnreadInboundMessageId(
                conversationId = conversationId,
                peerUserId = conversation.peerUserId,
            )
        } ?: return@withLock
        // Persist the server-visible receipt first. If it fails, the durable unread projection
        // remains retryable and the UI must not falsely claim that the receipt was published.
        val receipt = active.transport.markConversationRead(conversation, newestUnreadMessageId)
        check(sessions.currentOrNull() === active) {
            "Secure messaging session changed while publishing a read receipt"
        }
        projections.withActivationLease(active.activation, readyRequired = true) {
            markInboundReadThrough(
                conversationId = conversationId,
                peerUserId = conversation.peerUserId,
                requestedLastReadMessageId = newestUnreadMessageId,
                canonicalLastReadMessageId = receipt.lastReadMessageId,
                canonicalReadAt = receipt.readAt,
            )
        }
        check(sessions.currentOrNull() === active) {
            "Secure messaging session changed while saving local read state"
        }
    }

    override suspend fun synchronizeConversation(
        session: SecureMessagingChatSession,
        conversationId: String,
    ) {
        val active = requireCurrent(session)
        requireConversation(active, conversationId)
        check(syncEngine.isReady) { "Secure messaging sync is unavailable" }
        syncEngine.synchronize(active.fence)
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
    ): RetryCandidate? = projections.withActivationLease(
        active.activation,
        readyRequired = true,
    ) {
        var after: String? = null
        repeat(MAX_PROJECTION_PAGES) {
            val page = readPage(after, PROJECTION_PAGE_SIZE)
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
                return@withActivationLease RetryCandidate(durable, projected.deliveryState)
            }
            val next = page.nextAfterRecordKey ?: return@withActivationLease null
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
        projections.withActivationLease(active.activation, readyRequired = true) {
            markOutboundSent(durable, receipt)
        }
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
    // A message-ready transport is necessary but not sufficient for UI readiness. Keep this gate
    // closed until the new epoch's restored/current projection baseline has been published, so an
    // open conversation cannot mistake restored history for newly arrived messages or payments.
    private val mutableReadiness = MutableStateFlow(false)
    override val readiness: StateFlow<Boolean> = mutableReadiness.asStateFlow()

    private val mutableChats = MutableStateFlow<List<ChatPreview>>(emptyList())
    override val chats: StateFlow<List<ChatPreview>> = mutableChats.asStateFlow()
    private val publicationLock = Any()
    private val conversationLock = Any()
    private val conversationFlows = mutableMapOf<String, MutableStateFlow<List<Message>>>()
    private val refreshMutex = Mutex()
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(clock.zone)
    private var publishedSession: SecureMessagingChatSession? = null

    private data class ProjectionRefreshRequest(
        val session: SecureMessagingChatSession?,
        val contacts: List<Contact>,
        val baselineRetryOnly: Boolean,
    )

    private data class ProjectionPublication(
        val chats: List<ChatPreview>,
        val messagesByConversation: Map<String, List<Message>>,
    )

    init {
        scope.launch {
            val stateRequests = combine(
                runtime.activeSession,
                runtime.projectionChanges,
                contacts.contacts,
            ) { session, _, contactList ->
                ProjectionRefreshRequest(
                    session = session,
                    contacts = contactList,
                    baselineRetryOnly = false,
                )
            }
            val successfulSyncRetries = runtime.baselineRetrySessions
                // Filtering before merge prevents an obsolete completion from cancelling the
                // current identity's collectLatest baseline before the handler can reject it.
                .filter(runtime::isCurrent)
                .map { session ->
                    ProjectionRefreshRequest(
                        session = session,
                        contacts = contacts.contacts.value,
                        baselineRetryOnly = true,
                    )
                }
            merge(stateRequests, successfulSyncRetries)
                .collectLatest { request ->
                    val session = request.session
                    val contactList = request.contacts
                    if (session == null) {
                        clearPublishedStateIfCurrent(null)
                    } else if (request.baselineRetryOnly) {
                        if (
                            runtime.isCurrent(session) &&
                            needsProjectionBaseline(session)
                        ) {
                            establishProjectionBaseline(session, contactList)
                        }
                    } else if (needsProjectionBaseline(session)) {
                        establishProjectionBaseline(session, contactList)
                    } else {
                        try {
                            refresh(session, contactList)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            if (!runtime.isCurrent(session) || !isPublishedSession(session)) {
                                clearPublishedStateIfOwnedBy(session)
                            }
                        }
                    }
                }
        }
    }

    /**
     * A ready transport can precede a readable restored/current projection baseline on Android 9.
     * Retry that initial baseline locally, but never keep an obsolete epoch alive or turn a
     * cancelled collectLatest child into background work.
     */
    private suspend fun establishProjectionBaseline(
        session: SecureMessagingChatSession,
        localContacts: List<Contact>,
    ) {
        // Revoke every projection from the previous activation before the replacement baseline
        // performs network, archive, or Keystore work. The exact-session publication primitive
        // prevents an obsolete collector from clearing a newer activation.
        if (!clearPublishedStateIfCurrent(session)) return
        var attempt = 0
        var permanentRecoveryAttempted = false
        var retryCooldownMillis = BASELINE_REFRESH_COOLDOWN_MILLIS
        while (runtime.isCurrent(session) && !isReadyFor(session)) {
            if (!runtime.isCurrent(session) || isReadyFor(session)) return
            try {
                if (!refresh(session, localContacts, establishReadiness = true)) {
                    clearPublishedStateIfOwnedBy(session)
                }
                return
            } catch (cancelled: CancellationException) {
                clearPublishedStateIfOwnedBy(session)
                throw cancelled
            } catch (error: Exception) {
                if (!runtime.isCurrent(session)) {
                    clearPublishedStateIfOwnedBy(session)
                    return
                }
                if (
                    !permanentRecoveryAttempted &&
                    isRecoverableSecureMessagingStateLoss(error)
                ) {
                    permanentRecoveryAttempted = true
                    val recovered = try {
                        runtime.recoverPermanentlyUnavailableState(error)
                    } catch (cancelled: CancellationException) {
                        clearPublishedStateIfOwnedBy(session)
                        throw cancelled
                    } catch (_: Exception) {
                        false
                    }
                    if (!runtime.isCurrent(session)) {
                        clearPublishedStateIfOwnedBy(session)
                        return
                    }
                    if (recovered) {
                        // The runtime can complete recovery without replacing its exposed handle in
                        // tests or alternate implementations. Give that recovered state one fresh,
                        // independently bounded baseline cycle.
                        attempt = 0
                        continue
                    }
                }
                if (!isRetryableProjectionBaselineFailure(error)) return
                attempt++
                if (isReadyFor(session) || !runtime.isCurrent(session)) return
                if (attempt < BASELINE_REFRESH_ATTEMPTS) {
                    delay(BASELINE_REFRESH_RETRY_DELAY_MILLIS)
                } else {
                    // A healthy activation must not depend on another flow emission to recover
                    // from a transient provider/network outage. The collectLatest owner cancels
                    // this delay immediately on logout, replacement, or another refresh signal.
                    attempt = 0
                    delay(retryCooldownMillis)
                    retryCooldownMillis = (retryCooldownMillis * 2)
                        .coerceAtMost(MAX_BASELINE_REFRESH_COOLDOWN_MILLIS)
                }
            }
        }
    }

    private fun isRetryableProjectionBaselineFailure(error: Throwable): Boolean {
        if (isRecoverableSecureMessagingStateLoss(error)) return false
        return when (error) {
            is IOException,
            is SecureMessagingStateConflictException,
            -> true
            is KitWalletApiException ->
                error.statusCode == null || error.statusCode == 408 ||
                    error.statusCode == 425 || error.statusCode == 429 ||
                    error.statusCode >= 500
            else -> false
        }
    }

    private fun needsProjectionBaseline(session: SecureMessagingChatSession): Boolean =
        synchronized(publicationLock) {
            publishedSession !== session || !mutableReadiness.value
        }

    private fun isPublishedSession(session: SecureMessagingChatSession): Boolean =
        synchronized(publicationLock) { publishedSession === session }

    private fun isReadyFor(session: SecureMessagingChatSession): Boolean =
        synchronized(publicationLock) {
            publishedSession === session && mutableReadiness.value
        }

    /**
     * Captures the exact activation that owns the visible projection. The runtime performs the
     * authority check while holding its session-publication fence; a later replacement can only
     * make the exact-session operation fail, never redirect it to the replacement activation.
     */
    private fun requireReadySession(): SecureMessagingChatSession {
        val candidate = synchronized(publicationLock) {
            checkNotNull(publishedSession) { "Secure messaging is not ready" }
        }
        var projectionIsReady = false
        val isCurrent = runtime.publishIfCurrent(candidate) {
            synchronized(publicationLock) {
                projectionIsReady =
                    publishedSession === candidate && mutableReadiness.value
            }
        }
        check(isCurrent && projectionIsReady) {
            "Secure messaging projection is not ready for the active session"
        }
        return candidate
    }

    override fun chat(chatId: String): ChatPreview? = chats.value.singleOrNull { it.id == chatId }

    override fun conversation(chatId: String): StateFlow<List<Message>> =
        synchronized(conversationLock) {
            conversationFlows.getOrPut(chatId) { MutableStateFlow(emptyList()) }.asStateFlow()
        }

    override suspend fun openDirectConversation(contact: Contact): String {
        val session = requireReadySession()
        require(contact.isKitUser) {
            "Only contacts who are on Kit Pay can receive secure messages"
        }
        val created = runtime.createDirectConversation(session, contact.id)
        refresh(session, contacts.contacts.value)
        check(chat(created.id) != null) { "The secure conversation was not added to the projection" }
        return created.id
    }

    override suspend fun sendMessage(chatId: String, text: String) {
        val session = requireReadySession()
        val normalized = text.trim()
        require(normalized.isNotEmpty()) { "Enter a message to send securely" }
        try {
            runtime.sendText(session, chatId, normalized, retryClientMessageId = null)
        } finally {
            refresh(session, contacts.contacts.value)
        }
    }

    override suspend fun retryMessage(chatId: String, clientMessageId: String, text: String) {
        val session = requireReadySession()
        val normalized = text.trim()
        require(normalized.isNotEmpty()) { "Enter a message to retry securely" }
        try {
            runtime.sendText(session, chatId, normalized, retryClientMessageId = clientMessageId)
        } finally {
            refresh(session, contacts.contacts.value)
        }
    }

    override suspend fun sendImageMessage(
        chatId: String,
        bytes: ByteArray,
        mediaType: String,
        caption: String?,
    ) {
        val session = requireReadySession()
        try {
            runtime.sendImage(session, chatId, bytes, mediaType, caption)
        } finally {
            refresh(session, contacts.contacts.value)
        }
    }

    override suspend fun openImageMessage(chatId: String, mediaDescriptor: String): ByteArray {
        val session = requireReadySession()
        return runtime.openMedia(session, chatId, mediaDescriptor)
    }

    override suspend fun markConversationRead(chatId: String) {
        val session = requireReadySession()
        runtime.markConversationRead(session, chatId)
        refresh(session, contacts.contacts.value)
    }

    override suspend fun synchronizeConversation(chatId: String) {
        val session = requireReadySession()
        runtime.synchronizeConversation(session, chatId)
    }

    private suspend fun refresh(
        session: SecureMessagingChatSession,
        localContacts: List<Contact>,
        establishReadiness: Boolean = false,
    ): Boolean = refreshMutex.withLock {
        if (!runtime.isCurrent(session)) return@withLock false
        if (!establishReadiness && !isReadyFor(session)) return@withLock false

        var conversations = runtime.directConversations(session, forceRefresh = false)
        val projections = readAllProjectionPages(session)
        if (projections.any { projected -> conversations.none { it.id == projected.conversationId } }) {
            conversations = runtime.directConversations(session, forceRefresh = true)
        }

        // Authentication and UI mapping may be non-trivial. Build without holding the runtime's
        // active-session lock, then make only the in-memory commit inside its atomic fence.
        val publication = buildPublication(conversations, projections, localContacts)
        var committed = false
        val current = runtime.publishIfCurrent(session) {
            synchronized(publicationLock) {
                if (
                    establishReadiness ||
                    (publishedSession === session && mutableReadiness.value)
                ) {
                    commitPublicationLocked(session, publication)
                    committed = true
                }
            }
        }
        if (!current) clearPublishedStateIfOwnedBy(session)
        current && committed
    }

    private suspend fun readAllProjectionPages(
        session: SecureMessagingChatSession,
    ): List<AuthenticatedProjectedText> {
        val projected = mutableListOf<AuthenticatedProjectedText>()
        var after: String? = null
        repeat(MAX_PROJECTION_PAGES) {
            val page = runtime.projectionPage(session, after, PROJECTION_PAGE_SIZE)
            projected += page.messages
            val next = page.nextAfterRecordKey ?: return projected
            check(after == null || next > after!!) { "Encrypted projection pagination did not advance" }
            after = next
        }
        error("Encrypted projection history exceeds the supported display bound")
    }

    private fun buildPublication(
        conversations: List<AuthenticatedDirectConversation>,
        projections: List<AuthenticatedProjectedText>,
        localContacts: List<Contact>,
    ): ProjectionPublication {
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
        val projectedByConversation = authenticated.groupBy(AuthenticatedProjectedText::conversationId)
        val latestByConversation = projectedByConversation.mapValues { (_, messages) ->
            messages.maxWithOrNull(authenticatedProjectionOrder)
        }
        val savedNames = localContacts.asSequence()
            .filter { it.isKitUser && it.savedInDevice }
            .associate { it.id.lowercase() to it.name.trim() }
        val chats = conversations.sortedWith(
            compareByDescending<AuthenticatedDirectConversation> { conversation ->
                latestByConversation[conversation.id]?.sentAt?.toEpochMilli() ?: Long.MIN_VALUE
            }.thenBy { it.peerName?.lowercase().orEmpty() }.thenBy { it.id },
        ).map { conversation ->
            val last = latestByConversation[conversation.id]
            ChatPreview(
                id = conversation.id,
                name = savedNames[conversation.peerUserId.lowercase()]?.takeIf(String::isNotEmpty)
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
        return ProjectionPublication(chats, messageLists)
    }

    /** Called only while [publicationLock] and the runtime's exact-session fence are held. */
    private fun commitPublicationLocked(
        session: SecureMessagingChatSession,
        publication: ProjectionPublication,
    ) {
        synchronized(conversationLock) {
            conversationFlows.values.forEach { it.value = emptyList() }
            publication.messagesByConversation.forEach { (conversationId, messages) ->
                conversationFlows
                    .getOrPut(conversationId) { MutableStateFlow(emptyList()) }
                    .value = messages
            }
        }
        mutableChats.value = publication.chats
        publishedSession = session
        // Publish readiness last: observing true implies this activation's chats/messages were
        // already committed under the same repository publication lock.
        mutableReadiness.value = true
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

    private fun clearPublishedStateIfCurrent(
        session: SecureMessagingChatSession?,
    ): Boolean = runtime.publishIfCurrent(session) {
        synchronized(publicationLock) { clearPublishedStateLocked(session) }
    }

    private fun clearPublishedStateIfOwnedBy(
        session: SecureMessagingChatSession,
    ): Boolean = synchronized(publicationLock) {
        if (publishedSession !== session) return@synchronized false
        clearPublishedStateLocked(owner = null)
        true
    }

    /** Called only while [publicationLock] is held. */
    private fun clearPublishedStateLocked(owner: SecureMessagingChatSession?) {
        mutableReadiness.value = false
        publishedSession = owner
        mutableChats.value = emptyList()
        synchronized(conversationLock) {
            conversationFlows.values.forEach { it.value = emptyList() }
        }
    }

    private companion object {
        const val PROJECTION_PAGE_SIZE = 100
        const val MAX_PROJECTION_PAGES = 100
        const val BASELINE_REFRESH_ATTEMPTS = 4
        const val BASELINE_REFRESH_RETRY_DELAY_MILLIS = 5_000L
        const val BASELINE_REFRESH_COOLDOWN_MILLIS = 30_000L
        const val MAX_BASELINE_REFRESH_COOLDOWN_MILLIS = 5 * 60_000L
    }
}
