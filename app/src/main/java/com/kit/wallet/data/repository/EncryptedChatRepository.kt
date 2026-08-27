package com.kit.wallet.data.repository

import android.util.Log
import com.kit.wallet.BuildConfig
import com.kit.wallet.data.local.ConversationPrefEntity
import com.kit.wallet.data.local.ConversationPrefsDao
import com.kit.wallet.data.messaging.ConversationRosterStore
import com.kit.wallet.data.messaging.ConversationSystemEvent
import com.kit.wallet.data.messaging.ConversationSystemEventStore
import com.kit.wallet.data.messaging.ImmediateMediaSpool
import com.kit.wallet.data.messaging.ImmediateSendIntent
import com.kit.wallet.data.messaging.ImmediateSendIntentStore
import com.kit.wallet.data.messaging.ImmediateSendKind
import com.kit.wallet.data.messaging.ImmediateSendState
import com.kit.wallet.data.messaging.KitChatMediaKind
import com.kit.wallet.data.messaging.KitEditMessage
import com.kit.wallet.data.messaging.KitGroupPaymentAction
import com.kit.wallet.data.messaging.KitGroupPaymentMessage
import com.kit.wallet.data.messaging.KitMediaMessage
import com.kit.wallet.data.messaging.KitPaymentAction
import com.kit.wallet.data.messaging.KitPaymentMessage
import com.kit.wallet.data.messaging.KitReactionAction
import com.kit.wallet.data.messaging.KitReactionMessage
import com.kit.wallet.data.messaging.KitUserAuthoredTextPolicy
import com.kit.wallet.data.messaging.LibSignalCompanionDirection
import com.kit.wallet.data.messaging.LibSignalCompanionRecord
import com.kit.wallet.data.messaging.MAX_IMAGE_PLAINTEXT_BYTES
import com.kit.wallet.data.messaging.MEMBERSHIP_ADDED_EVENT
import com.kit.wallet.data.messaging.MEMBERSHIP_REMOVED_EVENT
import com.kit.wallet.data.messaging.MEMBERSHIP_ROLE_CHANGED_EVENT
import com.kit.wallet.data.messaging.MediaAttachmentCipher
import com.kit.wallet.data.messaging.MediaAttachmentStreamCipher
import com.kit.wallet.data.messaging.MessageReplyQuotes
import com.kit.wallet.data.messaging.RemoteSecureMessagingTransport
import com.kit.wallet.data.messaging.ScheduledSendStore
import com.kit.wallet.data.messaging.SecureMediaCache
import com.kit.wallet.data.messaging.SecureMediaFile
import com.kit.wallet.data.messaging.SecureMediaSource
import com.kit.wallet.data.messaging.SecureMessagingActivationCapability
import com.kit.wallet.data.messaging.SecureMessagingActiveSession
import com.kit.wallet.data.messaging.SecureMessagingActiveSessionRegistry
import com.kit.wallet.data.messaging.SecureMessagingAuthenticationEpochChangedException
import com.kit.wallet.data.messaging.SecureMessagingCommittedResult
import com.kit.wallet.data.messaging.SecureMessagingComposerDraftStore
import com.kit.wallet.data.messaging.SecureMessagingConversationCapabilityUnavailableException
import com.kit.wallet.data.messaging.SecureMessagingCryptoEngine
import com.kit.wallet.data.messaging.SecureMessagingCryptoTransaction
import com.kit.wallet.data.messaging.SecureMessagingCryptoWireMapper
import com.kit.wallet.data.messaging.SecureMessagingEncryptedSend
import com.kit.wallet.data.messaging.SecureMessagingEncryptionPlan
import com.kit.wallet.data.messaging.SecureMessagingEncryptionRequest
import com.kit.wallet.data.messaging.SecureMessagingLifecycleGuard
import com.kit.wallet.data.messaging.SecureMessagingMissingSessionSet
import com.kit.wallet.data.messaging.SecureMessagingProjectionDeliveryState
import com.kit.wallet.data.messaging.SecureMessagingProjectionPage
import com.kit.wallet.data.messaging.SecureMessagingProjectionStore
import com.kit.wallet.data.messaging.SecureMessagingStateConflictException
import com.kit.wallet.data.messaging.SecureMessagingSyncCompletionSignal
import com.kit.wallet.data.messaging.SecureMessagingSyncEngine
import com.kit.wallet.data.messaging.authenticatedOutboundMessageKind
import com.kit.wallet.data.messaging.isRecoverableSecureMessagingStateLoss
import com.kit.wallet.data.messaging.isRetryableSecureMessagingStateFailure
import com.kit.wallet.data.messaging.requireDurablyCommittedSessions
import com.kit.wallet.data.realtime.KitPresenceRegistry
import com.kit.wallet.data.realtime.KitTypingRegistry
import com.kit.wallet.data.remote.ADMIN_CONVERSATION_ROLE
import com.kit.wallet.data.remote.GROUP_CONVERSATION_TYPE
import com.kit.wallet.data.remote.KitWalletApiException
import com.kit.wallet.data.remote.MEMBER_CONVERSATION_ROLE
import com.kit.wallet.data.remote.OWNER_CONVERSATION_ROLE
import com.kit.wallet.data.remote.ValidatedMessageDeliveryInfo
import com.kit.wallet.data.remote.ProfileAvatarUploader
import com.kit.wallet.data.remote.isValidMessagingGroupDescription
import com.kit.wallet.data.remote.isValidMessagingGroupTitle
import com.kit.wallet.data.remote.normalizeMessagingGroupDescription
import com.kit.wallet.data.remote.normalizeMessagingGroupTitle
import com.kit.wallet.data.session.SessionFence
import com.kit.wallet.data.session.SessionInvalidatedException
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.di.ApplicationScope
import com.kit.wallet.ui.model.ChatMember
import com.kit.wallet.ui.model.ChatMemberRole
import com.kit.wallet.ui.model.ChatPreview
import com.kit.wallet.ui.model.Contact
import com.kit.wallet.ui.model.DeliveryState
import com.kit.wallet.ui.model.GroupPaymentEventKind
import com.kit.wallet.ui.model.Message
import com.kit.wallet.ui.model.MessageDeliveryInfo
import com.kit.wallet.ui.model.MessageDeliveryPerson
import com.kit.wallet.ui.model.MessageKind
import com.kit.wallet.ui.model.MessageReaction
import com.kit.wallet.ui.model.PaymentEventKind
import com.kit.wallet.ui.model.acceptsEdits
import com.kit.wallet.ui.model.acceptsDeliveryInfo
import com.kit.wallet.ui.model.acceptsReactions
import com.kit.wallet.ui.model.acceptsReplies
import com.kit.wallet.worker.SecureMessagingSyncScheduler
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class AuthenticatedConversationMember(
    val userId: String,
    val name: String?,
    val role: String,
)

/**
 * A conversation this account is a proven, server-validated member of.
 *
 * Direct chats and groups share the type because everything downstream of here — projection
 * routing, unread counts, drafts, reactions — is keyed by conversation, not by peer. The
 * differences are narrow and explicit: a group has a [title] and more than one other member,
 * a direct chat has [peerUserId] and cannot be renamed or left.
 */
internal data class AuthenticatedConversation(
    val id: String,
    val type: String,
    val title: String?,
    val viewerUserId: String,
    val currentUserRole: String,
    val members: List<AuthenticatedConversationMember>,
    /** Server-visible group description; null for direct chats and undescribed groups. */
    val description: String? = null,
    /** The group photo's public address; render-time origin pinning decides whether to fetch. */
    val photoUrl: String? = null,
) {
    val isGroup: Boolean get() = type == GROUP_CONVERSATION_TYPE

    val others: List<AuthenticatedConversationMember>
        get() = members.filterNot { it.userId == viewerUserId }

    val peerUserId: String? get() = if (isGroup) null else others.singleOrNull()?.userId

    val peerName: String? get() = if (isGroup) null else others.singleOrNull()?.name

    fun memberNamed(userId: String): AuthenticatedConversationMember? =
        members.firstOrNull { it.userId.equals(userId, ignoreCase = true) }

    /** True when [userId] may post here — i.e. is still an active member. */
    fun contains(userId: String): Boolean = memberNamed(userId) != null

    val canManageMembers: Boolean
        get() = isGroup && currentUserRole in MANAGING_CONVERSATION_ROLES
}

private val MANAGING_CONVERSATION_ROLES =
    setOf(OWNER_CONVERSATION_ROLE, ADMIN_CONVERSATION_ROLE)

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
    /** The authenticated reply binding, when this message answers another. */
    val replyToMessageId: String? = null,
)

internal data class AuthenticatedProjectionPage(
    val messages: List<AuthenticatedProjectedText>,
    val nextAfterRecordKey: String?,
)

/** One process-local message-ready activation; a reset may replace it within the same login. */
internal class SecureMessagingChatSession internal constructor(
    val sessionEpoch: String,
    internal val identity: Any,
    /**
     * Whether the server advertised message corrections for this authenticated account.
     *
     * Carried on the session rather than read back through the opaque transport handle so the
     * repository can publish it at the same instant it publishes the projection, and so it dies
     * with the activation it came from. Fail closed: a session that never said so cannot edit.
     */
    internal val messageEditsEnabled: Boolean = false,
)

/** A newer local intent must wait until this conversation's older ciphertext is reconciled. */
internal class SecureMessagingPendingPredecessorException : IOException(
    "An earlier encrypted message is still waiting to send",
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

/** Display name used when an authenticated conversation carries no usable peer name. */
internal const val DEFAULT_PEER_NAME = "Kit Pay contact"

/** Display name used when a group's server-visible title is missing or unusable. */
internal const val DEFAULT_GROUP_NAME = "Kit Pay group"

/** How this account is named in its own reaction list. */
internal const val SELF_REACTOR_NAME = "You"

/** How this account is named in a group's participant list. */
internal const val SELF_MEMBER_NAME = SELF_REACTOR_NAME

/**
 * Maps a server role onto what the UI may offer.
 *
 * Anything unrecognised reads as a plain member: a role this build has never heard of must not
 * be mistaken for elevated rights just because the server invented it after we shipped.
 */
internal fun String.toChatMemberRole(): ChatMemberRole = when (this) {
    OWNER_CONVERSATION_ROLE -> ChatMemberRole.OWNER
    ADMIN_CONVERSATION_ROLE -> ChatMemberRole.ADMIN
    else -> ChatMemberRole.MEMBER
}

internal fun ChatMemberRole.toConversationRole(): String = when (this) {
    ChatMemberRole.OWNER -> OWNER_CONVERSATION_ROLE
    ChatMemberRole.ADMIN -> ADMIN_CONVERSATION_ROLE
    ChatMemberRole.MEMBER -> MEMBER_CONVERSATION_ROLE
}

/**
 * Collapses one conversation's reaction descriptors onto the messages they point at.
 *
 * [ordered] must already be in [authenticatedProjectionOrder], because that order is what decides
 * a reaction's fate: the last applicable descriptor a reactor authored for a message wins, so an
 * `add` replaces that user's previous emoji and a `remove` clears only the emoji it names. That
 * makes the fold idempotent and convergent — replaying the same log, in whole or in part, on any
 * device yields the same result, so duplicates and out-of-order delivery need no separate handling.
 *
 * A reaction is attributed only to the authenticated Signal sender of its carrying message; the
 * descriptor itself names no reactor, so a peer cannot react on somebody else's behalf. [nameOf]
 * turns that authenticated sender ID into a display name, which is what makes a group's who-reacted
 * list name the right person rather than collapsing everyone onto a single peer.
 */
internal fun foldAuthenticatedReactions(
    ordered: List<AuthenticatedProjectedText>,
    nameOf: (senderUserId: String) -> String,
): Map<String, List<MessageReaction>> {
    val targets = ordered.mapTo(mutableSetOf(), AuthenticatedProjectedText::messageId)
    data class StandingReaction(val emoji: String, val order: Int)
    // target -> reactor -> the user's one current reaction on that message.
    val state = linkedMapOf<String, LinkedHashMap<String, StandingReaction>>()
    var nextOrder = 0
    ordered.forEach { projected ->
        val reaction = KitReactionMessage.parse(projected.text) ?: return@forEach
        // A permanent local failure never reached the server or another device. Suppressing its
        // event bubble while still folding it would show a reaction only on this installation
        // and make the next tap send a nonsensical REMOVE for an ADD nobody received.
        if (projected.deliveryState == AuthenticatedTextDeliveryState.PERMANENT_FAILURE) {
            return@forEach
        }
        // A reaction whose target never authenticated on this device has nothing to annotate.
        if (reaction.targetMessageId !in targets) return@forEach
        val reactor = if (projected.fromCurrentUser) {
            SELF_REACTOR_NAME
        } else {
            projected.senderUserId
        }
        val reactors = state.getOrPut(reaction.targetMessageId) { linkedMapOf() }
        when (reaction.action) {
            KitReactionAction.ADD -> {
                if (reactors[reactor]?.emoji != reaction.emoji) {
                    reactors[reactor] = StandingReaction(reaction.emoji, nextOrder++)
                }
            }
            KitReactionAction.REMOVE -> {
                if (reactors[reactor]?.emoji == reaction.emoji) reactors.remove(reactor)
            }
        }
    }
    val byTarget = linkedMapOf<String, MutableList<MessageReaction>>()
    state.forEach { (targetMessageId, reactors) ->
        reactors.entries.groupBy { it.value.emoji }.forEach { (emoji, entries) ->
            val standing = entries.sortedBy { it.value.order }.map { it.key }
            val fromMe = SELF_REACTOR_NAME in standing
            byTarget.getOrPut(targetMessageId) { mutableListOf() } += MessageReaction(
                emoji = emoji,
                reactorNames = standing.sortedBy { if (it == SELF_REACTOR_NAME) 0 else 1 }
                    .map { if (it == SELF_REACTOR_NAME) SELF_REACTOR_NAME else nameOf(it) },
                fromMe = fromMe,
            )
        }
    }
    // Most-reacted first; ties keep the order the emoji first appeared in the conversation, which
    // [state] preserves, so the chip row does not reshuffle as counts change.
    return byTarget.mapValues { (_, reactions) ->
        reactions.sortedWith(compareByDescending(MessageReaction::count).thenBy(MessageReaction::emoji))
    }
}

/** The wording a message ended up with, and the moment its author replaced the original. */
internal data class AuthenticatedMessageEdit(
    val text: String,
    val editedAtEpochMillis: Long,
)

/**
 * Folds every authenticated edit descriptor onto the message whose wording it replaces.
 *
 * [ordered] must already be in [authenticatedProjectionOrder], because that order is what decides
 * which correction stands: the last one its author wrote wins, so replaying the same log — in
 * whole or in part, on any device — converges on the same wording and duplicates need no separate
 * handling.
 *
 * Only the authenticated Signal sender of the original may replace it. The descriptor names no
 * author, so the attribution cannot be forged, and an edit from anybody else is simply dropped
 * rather than rendered: a peer does not get to put words in someone else's bubble.
 *
 * The fifteen-minute window is deliberately *not* re-checked here. The server is what authorises a
 * correction, and it is the only party with an untampered clock for both messages; re-deciding it
 * on the reader's side could keep showing words the sender has already taken back, which is the
 * one outcome editing exists to prevent.
 */
internal fun foldAuthenticatedEdits(
    ordered: List<AuthenticatedProjectedText>,
): Map<String, AuthenticatedMessageEdit> {
    val originals = ordered.associateBy(AuthenticatedProjectedText::messageId)
    val applied = linkedMapOf<String, AuthenticatedMessageEdit>()
    ordered.forEach { projected ->
        val edit = KitEditMessage.parse(projected.text) ?: return@forEach
        // A permanent local failure never reached the server or another device. Applying it would
        // rewrite the message on this installation alone and leave the two sides disagreeing about
        // what was said.
        if (projected.deliveryState == AuthenticatedTextDeliveryState.PERMANENT_FAILURE) {
            return@forEach
        }
        val target = originals[edit.targetMessageId] ?: return@forEach
        if (!target.senderUserId.equals(projected.senderUserId, ignoreCase = true)) return@forEach
        // Corrections apply to what someone said, never to an annotation on it or to another
        // correction: chains of those would let one descriptor rewrite the meaning of a second.
        if (KitReactionMessage.isReactionText(target.text)) return@forEach
        if (KitEditMessage.isEditText(target.text)) return@forEach
        // Nor to a photo, a voice note or a document. Its descriptor is what points recipients at
        // the ciphertext they have already downloaded; replacing it with a sentence would strand
        // that media with nothing left to name it.
        if (KitMediaMessage.isMediaText(target.text)) return@forEach
        applied[edit.targetMessageId] = AuthenticatedMessageEdit(
            text = edit.body,
            editedAtEpochMillis = projected.sentAt.toEpochMilli(),
        )
    }
    return applied
}

/** Testable repository-facing surface; the production implementation retains opaque handles. */
internal interface SecureMessagingChatRuntime {
    val activeSession: StateFlow<SecureMessagingChatSession?>
    val projectionChanges: StateFlow<Long>
    val baselineRetrySessions: Flow<SecureMessagingChatSession>
        get() = emptyFlow()

    /**
     * The authority to read this device's own encrypted history, published from the first moment
     * an activation exists rather than when message exchange becomes possible.
     *
     * [activeSession] is the exchange authority and is deliberately withheld until the transport,
     * key and roster steps have all completed over the network. This is the display authority for
     * the same activation, and it is what lets the chat list, transcripts and previews be drawn
     * from the local encrypted store while those steps are still running — or failing.
     */
    val localHistoryActivations: StateFlow<SecureMessagingActivationCapability?>
        get() = MutableStateFlow(null)

    fun isCurrent(session: SecureMessagingChatSession): Boolean = activeSession.value === session

    /** Atomically publishes local UI state only for the exact current opaque activation. */
    fun publishIfCurrent(
        session: SecureMessagingChatSession?,
        publication: () -> Unit,
    ): Boolean

    /** Routes only proved key loss or migration-fenced unreadable state through local recovery. */
    suspend fun recoverPermanentlyUnavailableState(error: Throwable): Boolean = false

    /**
     * Forgets that the current session's archive has already been projected.
     *
     * Restoring a backup writes into the encrypted archive behind the projection's back, so unless
     * the projection is told to read it again the restored history stays invisible until the next
     * cold start — which reads to the user as a restore that did nothing.
     */
    suspend fun invalidateArchivedHistoryProjection() = Unit

    suspend fun conversations(
        session: SecureMessagingChatSession,
        forceRefresh: Boolean = false,
    ): List<AuthenticatedConversation>

    /**
     * The conversations this device has already authenticated, read from local encrypted state.
     *
     * Display only. Every send, key agreement and membership change continues to run against the
     * live authenticated handles, so a cached entry can name a chat but can never authorize one.
     */
    suspend fun cachedConversations(
        activation: SecureMessagingActivationCapability,
    ): List<AuthenticatedConversation> = emptyList()

    /** One page of local transcript, readable before the activation is ready to exchange. */
    suspend fun localProjectionPage(
        activation: SecureMessagingActivationCapability,
        afterRecordKey: String?,
        limit: Int,
    ): AuthenticatedProjectionPage = AuthenticatedProjectionPage(emptyList(), null)

    suspend fun createDirectConversation(
        session: SecureMessagingChatSession,
        peerUserId: String,
    ): AuthenticatedConversation

    suspend fun createGroupConversation(
        session: SecureMessagingChatSession,
        title: String,
        memberUserIds: List<String>,
    ): AuthenticatedConversation = error("This runtime does not support group conversations")

    suspend fun addGroupMember(
        session: SecureMessagingChatSession,
        conversationId: String,
        userId: String,
        role: String = MEMBER_CONVERSATION_ROLE,
    ): AuthenticatedConversation = error("This runtime does not support group conversations")

    suspend fun setGroupMemberRole(
        session: SecureMessagingChatSession,
        conversationId: String,
        userId: String,
        role: String,
    ): AuthenticatedConversation = error("This runtime does not support group conversations")

    suspend fun removeGroupMember(
        session: SecureMessagingChatSession,
        conversationId: String,
        userId: String,
    ): AuthenticatedConversation = error("This runtime does not support group conversations")

    suspend fun leaveGroupConversation(
        session: SecureMessagingChatSession,
        conversationId: String,
    ): Unit = error("This runtime does not support group conversations")

    suspend fun updateGroupDescription(
        session: SecureMessagingChatSession,
        conversationId: String,
        description: String?,
    ): AuthenticatedConversation = error("This runtime does not support group conversations")

    suspend fun attachGroupPhoto(
        session: SecureMessagingChatSession,
        conversationId: String,
        assetId: String,
    ): AuthenticatedConversation = error("This runtime does not support group conversations")

    suspend fun removeGroupPhoto(
        session: SecureMessagingChatSession,
        conversationId: String,
    ): AuthenticatedConversation = error("This runtime does not support group conversations")

    suspend fun projectionPage(
        session: SecureMessagingChatSession,
        afterRecordKey: String?,
        limit: Int,
    ): AuthenticatedProjectionPage

    suspend fun markConversationRead(
        session: SecureMessagingChatSession,
        conversationId: String,
    )

    suspend fun messageDeliveryInfo(
        session: SecureMessagingChatSession,
        conversationId: String,
        messageId: String,
    ): ValidatedMessageDeliveryInfo = error("This runtime cannot report what became of a message")

    suspend fun synchronizeConversation(
        session: SecureMessagingChatSession,
        conversationId: String,
    )

    suspend fun sendText(
        session: SecureMessagingChatSession,
        conversationId: String,
        text: String,
        retryClientMessageId: String? = null,
        replyToMessageId: String? = null,
        onDurablyCommitted: (clientMessageId: String) -> Unit = {},
        expectedOwner: SessionFence? = null,
        idempotentClientMessageId: String? = null,
    )

    /** Uploads already-encrypted local-outbox media and returns its canonical E2E descriptor. */
    suspend fun prepareMediaDescriptor(
        session: SecureMessagingChatSession,
        conversationId: String,
        attachmentId: String,
        ciphertext: File,
        mediaType: String,
        keyMaterialBase64: String,
        plaintextBytes: Int,
        caption: String?,
    ): String = error("This secure messaging runtime does not support queued media")

    /** Encrypts, uploads and sends one attachment as an end-to-end encrypted media message. */
    suspend fun sendImage(
        session: SecureMessagingChatSession,
        conversationId: String,
        source: SecureMediaSource,
        mediaType: String,
        caption: String?,
    ): Unit = error("This secure messaging runtime does not support media messages")

    /**
     * Downloads and decrypts the blob an authenticated media descriptor references, writing the
     * plaintext to [destination] and returning its size. Nothing unauthenticated is ever written.
     */
    suspend fun openMediaToFile(
        session: SecureMessagingChatSession,
        conversationId: String,
        descriptorText: String,
        destination: File,
    ): Int =
        error("This secure messaging runtime does not support media messages")
}

@Singleton
internal class DefaultSecureMessagingChatRuntime @Inject constructor(
    private val sessions: SecureMessagingActiveSessionRegistry,
    private val authenticationSessions: SessionStore,
    private val engine: SecureMessagingCryptoEngine,
    private val projections: SecureMessagingProjectionStore,
    private val syncEngine: SecureMessagingSyncEngine,
    @param:ApplicationScope private val scope: CoroutineScope,
    private val clock: Clock,
    private val lifecycle: SecureMessagingLifecycleGuard,
    private val roster: ConversationRosterStore,
    private val syncScheduler: SecureMessagingSyncScheduler? = null,
    private val syncCompletions: SecureMessagingSyncCompletionSignal =
        SecureMessagingSyncCompletionSignal(),
) : SecureMessagingChatRuntime {
    override val localHistoryActivations: StateFlow<SecureMessagingActivationCapability?> =
        lifecycle.localReadActivation

    override val activeSession: StateFlow<SecureMessagingChatSession?> = sessions.activeSession
        .map { active ->
            active?.let {
                SecureMessagingChatSession(
                    sessionEpoch = it.binding.sessionEpoch,
                    identity = it,
                    messageEditsEnabled = it.transport.messageEditsEnabled,
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
    private val recoveryRetryLock = Any()
    private var recoveryRetryJob: Job? = null
    private var recoveryRetryOwner: SessionFence? = null
    private var conversationOwner: SecureMessagingActiveSession? = null
    private var conversationsLoaded = false
    private var archiveRestoredOwner: SecureMessagingActiveSession? = null
    private var conversationHandles =
        emptyMap<String, RemoteSecureMessagingTransport.Session.SecureConversation>()

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
        // Capture only the non-secret authentication fence before recovery can quarantine and
        // remove the active transport handle. Tokens are never retained by the retry job.
        val expectedAuthentication = authenticationSessions.current()?.fence()
            ?.takeIf { it.sessionId == previous.binding.sessionEpoch }
        // Quarantine temporarily removes the active handle, which cancels collectLatest. Finish
        // the already-authorized fenced recovery so cancellation cannot strand the login in
        // QUARANTINED before the fresh activation is published.
        try {
            withContext(NonCancellable) {
                syncEngine.recoverPermanentlyUnavailableState(previous.fence)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // A network/provider failure before or during reset must not turn this one foreground
            // attempt into another permanent stall. WorkManager remains the durable fallback.
            runCatching { syncScheduler?.schedule() }
                .exceptionOrNull()
                ?.let(error::addSuppressed)
            if (sessions.currentOrNull() == null) {
                // Quarantine cancels the repository collector and removes its opaque activation.
                // Continue the already-proved recovery under the same authenticated-session fence
                // so Android 9 does not have to wait for an OEM WorkManager wake.
                expectedAuthentication?.let(::startProcessLocalRecoveryRetry)
            }
            throw error
        }
        return sessions.currentOrNull()?.let { it !== previous } == true
    }

    private fun startProcessLocalRecoveryRetry(expectedSession: SessionFence) {
        val candidate = scope.launch(start = CoroutineStart.LAZY) {
            continueRecoveryWhileAuthenticated(expectedSession)
        }
        synchronized(recoveryRetryLock) {
            val existing = recoveryRetryJob
            if (existing != null && !existing.isCompleted && recoveryRetryOwner == expectedSession) {
                candidate.cancel()
                return
            }
            existing?.cancel()
            recoveryRetryJob = candidate
            recoveryRetryOwner = expectedSession
            candidate.invokeOnCompletion {
                synchronized(recoveryRetryLock) {
                    if (recoveryRetryJob === candidate) {
                        recoveryRetryJob = null
                        recoveryRetryOwner = null
                    }
                }
            }
            candidate.start()
        }
    }

    private suspend fun continueRecoveryWhileAuthenticated(expectedSession: SessionFence) =
        coroutineScope {
            val retry = launch {
                retrySecureMessagingOperation(
                    isCurrent = { authenticationSessions.current()?.fence() == expectedSession },
                    operation = {
                        syncEngine.synchronize(expectedSession)
                        true
                    },
                )
            }
            val ownership = launch(start = CoroutineStart.UNDISPATCHED) {
                authenticationSessions.session
                    .map { it?.fence() }
                    .first { it != expectedSession }
                retry.cancel()
            }
            try {
                retry.join()
            } finally {
                ownership.cancelAndJoin()
            }
        }

    override suspend fun invalidateArchivedHistoryProjection() {
        archiveRestoreMutex.withLock { archiveRestoredOwner = null }
    }

    override suspend fun conversations(
        session: SecureMessagingChatSession,
        forceRefresh: Boolean,
    ): List<AuthenticatedConversation> {
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
        val authenticated = loaded.values.map { it.toAuthenticated(active.binding.userId) }
        // Write through so the next cold start can name these chats before it can reach the
        // server. Best-effort: a failed cache write must never fail a successful roster load.
        try {
            roster.replace(active.activation, authenticated)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The list is already correct in memory for this activation; the next load retries.
        }
        return authenticated
    }

    override suspend fun cachedConversations(
        activation: SecureMessagingActivationCapability,
    ): List<AuthenticatedConversation> = roster.read(activation)

    override suspend fun localProjectionPage(
        activation: SecureMessagingActivationCapability,
        afterRecordKey: String?,
        limit: Int,
    ): AuthenticatedProjectionPage = projections.readLocalPage(
        activation = activation,
        afterRecordKey = afterRecordKey,
        limit = limit,
    ).toAuthenticated(activation.binding.userId)

    override suspend fun createDirectConversation(
        session: SecureMessagingChatSession,
        peerUserId: String,
    ): AuthenticatedConversation {
        val active = requireCurrent(session)
        loadConversations(active, forceRefresh = false).values
            .singleOrNull { !it.isGroup && it.peerUserId == peerUserId }
            ?.let { return it.toAuthenticated(active.binding.userId) }

        val created = active.transport.createDirectConversation(peerUserId)
        check(sessions.currentOrNull() === active) {
            "Secure messaging session changed while creating a conversation"
        }
        return adoptConversation(active, created, expectNew = true)
    }

    override suspend fun createGroupConversation(
        session: SecureMessagingChatSession,
        title: String,
        memberUserIds: List<String>,
    ): AuthenticatedConversation {
        val active = requireCurrent(session)
        // Deliberately not deduplicated against an existing group: unlike a direct chat, the
        // same people may hold any number of groups, and silently reopening an old one would
        // put a new message in front of the wrong audience.
        val created = active.transport.createGroupConversation(title, memberUserIds)
        check(sessions.currentOrNull() === active) {
            "Secure messaging session changed while creating a group"
        }
        return adoptConversation(active, created, expectNew = true)
    }

    override suspend fun addGroupMember(
        session: SecureMessagingChatSession,
        conversationId: String,
        userId: String,
        role: String,
    ): AuthenticatedConversation = mutateGroup(session, conversationId) { active, conversation ->
        active.transport.addGroupMember(conversation, userId, role)
    }

    override suspend fun setGroupMemberRole(
        session: SecureMessagingChatSession,
        conversationId: String,
        userId: String,
        role: String,
    ): AuthenticatedConversation = mutateGroup(session, conversationId) { active, conversation ->
        active.transport.setGroupMemberRole(conversation, userId, role)
    }

    override suspend fun removeGroupMember(
        session: SecureMessagingChatSession,
        conversationId: String,
        userId: String,
    ): AuthenticatedConversation = mutateGroup(session, conversationId) { active, conversation ->
        active.transport.removeGroupMember(conversation, userId)
    }

    override suspend fun updateGroupDescription(
        session: SecureMessagingChatSession,
        conversationId: String,
        description: String?,
    ): AuthenticatedConversation = mutateGroup(session, conversationId) { active, conversation ->
        active.transport.updateGroupDescription(conversation, description)
    }

    override suspend fun attachGroupPhoto(
        session: SecureMessagingChatSession,
        conversationId: String,
        assetId: String,
    ): AuthenticatedConversation = mutateGroup(session, conversationId) { active, conversation ->
        active.transport.attachGroupPhoto(conversation, assetId)
    }

    override suspend fun removeGroupPhoto(
        session: SecureMessagingChatSession,
        conversationId: String,
    ): AuthenticatedConversation = mutateGroup(session, conversationId) { active, conversation ->
        active.transport.removeGroupPhoto(conversation)
    }

    override suspend fun leaveGroupConversation(
        session: SecureMessagingChatSession,
        conversationId: String,
    ) {
        val active = requireCurrent(session)
        val conversation = requireConversation(active, conversationId)
        active.transport.leaveGroup(conversation)
        check(sessions.currentOrNull() === active) {
            "Secure messaging session changed while leaving a group"
        }
        conversationMutex.withLock {
            prepareOwner(active)
            conversationHandles = conversationHandles - conversationId
        }
    }

    private suspend fun mutateGroup(
        session: SecureMessagingChatSession,
        conversationId: String,
        mutate: suspend (
            SecureMessagingActiveSession,
            RemoteSecureMessagingTransport.Session.SecureConversation,
        ) -> RemoteSecureMessagingTransport.Session.SecureConversation,
    ): AuthenticatedConversation {
        val active = requireCurrent(session)
        val conversation = requireConversation(active, conversationId)
        val updated = mutate(active, conversation)
        check(sessions.currentOrNull() === active) {
            "Secure messaging session changed while changing group membership"
        }
        return adoptConversation(active, updated, expectNew = false)
    }

    /** Replaces the cached handle for a conversation the server just returned. */
    private suspend fun adoptConversation(
        active: SecureMessagingActiveSession,
        conversation: RemoteSecureMessagingTransport.Session.SecureConversation,
        expectNew: Boolean,
    ): AuthenticatedConversation {
        conversationMutex.withLock {
            prepareOwner(active)
            check(!expectNew || conversationHandles[conversation.conversationId] == null) {
                "The server created a duplicate conversation identifier"
            }
            conversationHandles =
                conversationHandles + (conversation.conversationId to conversation)
            conversationsLoaded = true
        }
        return conversation.toAuthenticated(active.binding.userId)
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
        return page.toAuthenticated(active.binding.userId)
    }

    private fun SecureMessagingProjectionPage.toAuthenticated(
        currentUserId: String,
    ): AuthenticatedProjectionPage = AuthenticatedProjectionPage(
        messages = messages().map { projected ->
            val durable = projected.durableRecord
            val fromCurrentUser = projectionIsFromCurrentUser(
                direction = durable.direction,
                senderUserId = durable.sender.userId,
                currentUserId = currentUserId,
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
                replyToMessageId = durable.replyToMessageId,
            )
        },
        nextAfterRecordKey = nextAfterRecordKey,
    )

    override suspend fun sendText(
        session: SecureMessagingChatSession,
        conversationId: String,
        text: String,
        retryClientMessageId: String?,
        replyToMessageId: String?,
        onDurablyCommitted: (clientMessageId: String) -> Unit,
        expectedOwner: SessionFence?,
        idempotentClientMessageId: String?,
    ) = sendMutex.withLock {
        val active = requireCurrent(session)
        expectedOwner?.let { requireAuthenticationOwner(active, it) }
        val conversation = requireConversation(active, conversationId, expectedOwner)
        require(retryClientMessageId == null || idempotentClientMessageId == null) {
            "A secure send cannot be both an explicit retry and an outbox promotion"
        }
        retryClientMessageId?.let {
            require(CANONICAL_UUID.matches(it)) { "Invalid secure-message retry ID" }
        }
        idempotentClientMessageId?.let {
            require(CANONICAL_UUID.matches(it)) { "Invalid local-outbox message ID" }
            val projected = findRetryCandidate(active, conversationId, text, it)
            if (projected != null) {
                // The Signal companion already owns this stable intent. Removing the plaintext
                // intent is now safe regardless of whether its network send has completed; the
                // existing encrypted outbox performs every later retry.
                onDurablyCommitted(it)
                return@withLock
            }
            val companion = projections.withActivationLease(
                active.activation,
                readyRequired = true,
            ) { readOutbound(it) }
            if (companion != null) {
                check(
                    companion.conversationId == conversationId &&
                        companion.sender.userId == active.binding.userId &&
                        companion.authenticatedText == text
                ) { "A local-outbox message ID belongs to different content" }
                projections.withActivationLease(active.activation, readyRequired = true) {
                    recordOutboundPending(companion, Instant.ofEpochMilli(companion.updatedAtEpochMillis))
                }
                onDurablyCommitted(it)
                return@withLock
            }
        }
        if (
            idempotentClientMessageId != null &&
            hasPendingOutboundPredecessor(
                active = active,
                conversationId = conversationId,
                excludingClientMessageId = idempotentClientMessageId,
            )
        ) {
            // The recovery engine must settle or explicitly retire the existing ciphertext first.
            // Without this runtime-level gate, a dispatcher retry could encrypt and deliver a
            // newer message while the predecessor still waited in the UUID-keyed durable outbox.
            throw SecureMessagingPendingPredecessorException()
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

        val roster = active.transport.roster(conversation, expectedOwner)
        if (KitReactionMessage.parse(text) != null) {
            active.transport.requireReactionCapability(conversation, roster)
        }
        if (KitEditMessage.parse(text) != null) {
            active.transport.requireMessageEditCapability(conversation, roster)
        }
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
                expectedOwner?.let { requireAuthenticationOwner(active, it) }
                reissuePending(active, conversation, pending, plan, expectedOwner)
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
                expectedOwner = expectedOwner,
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
        expectedOwner?.let { requireAuthenticationOwner(active, it) }
        val clientMessageId = idempotentClientMessageId ?: UUID.randomUUID().toString()
        val encrypted = commitEncryption(
            transaction = encryptionTransaction,
            plan = plan,
            clientMessageId = clientMessageId,
            text = text,
            replyToMessageId = replyToMessageId,
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
        // This operation now owns a durable encrypted outbox record. Notify its exact caller before
        // the transport can suspend or fail; no text/projection matching is needed at the UI edge.
        onDurablyCommitted(durable.clientMessageId)
        try {
            // Server-visible attachment metadata is derived from the descriptor text on every send
            // and retry, so the end-to-end content and the metadata rows can never disagree.
            val receipt = active.transport.send(
                conversation,
                encrypted,
                KitMediaMessage.attachmentsFor(text),
                expectedOwner,
            )
            projections.withActivationLease(active.activation, readyRequired = true) {
                markOutboundSent(durable, receipt)
            }
        } catch (error: Throwable) {
            // The exact ciphertext is already committed. Enqueue the network-constrained sync
            // worker so returning connectivity replays this outbox record on its own, without
            // waiting for a later login, push, foreground, or visible-conversation event.
            runCatching { syncScheduler?.schedule() }
                .exceptionOrNull()
                ?.takeIf { it !== error }
                ?.let(error::addSuppressed)
            throw error
        }
    }

    override suspend fun prepareMediaDescriptor(
        session: SecureMessagingChatSession,
        conversationId: String,
        attachmentId: String,
        ciphertext: File,
        mediaType: String,
        keyMaterialBase64: String,
        plaintextBytes: Int,
        caption: String?,
    ): String {
        require(CANONICAL_UUID.matches(attachmentId)) { "Invalid queued attachment ID" }
        require(ciphertext.isFile && ciphertext.length() > 0) { "Attachment ciphertext is empty" }
        val normalizedMediaType = requireNotNull(KitMediaMessage.normalizeMediaType(mediaType)) {
            "Choose a supported photo, voice note, video or document"
        }
        val active = requireCurrent(session)
        requireConversation(active, conversationId)
        val uploaded = active.transport.uploadAttachment(normalizedMediaType, ciphertext)
        check(sessions.currentOrNull() === active) {
            "Secure messaging session changed while uploading encrypted media"
        }
        val descriptor = KitMediaMessage(
            attachmentId = attachmentId,
            storageKey = uploaded.storageKey,
            mediaType = normalizedMediaType,
            ciphertextByteSize = uploaded.byteSize,
            ciphertextSha256 = uploaded.ciphertextSha256,
            keyMaterialBase64 = keyMaterialBase64,
            plaintextByteSize = plaintextBytes,
            caption = caption?.trim()?.takeIf(String::isNotEmpty),
        ).encode()
        check(KitMediaMessage.parse(descriptor)?.encode() == descriptor) {
            "The queued attachment metadata cannot be authenticated"
        }
        return descriptor
    }

    private fun requireAuthenticationOwner(
        active: SecureMessagingActiveSession,
        expected: SessionFence,
    ) {
        if (
            authenticationSessions.current()?.fence() != expected ||
            active.binding.sessionEpoch != expected.sessionId ||
            active.binding.userId != expected.accountId
        ) throw SessionInvalidatedException()
    }

    override suspend fun sendImage(
        session: SecureMessagingChatSession,
        conversationId: String,
        source: SecureMediaSource,
        mediaType: String,
        caption: String?,
    ) {
        val normalizedMediaType = requireNotNull(KitMediaMessage.normalizeMediaType(mediaType)) {
            "Choose a supported photo, voice note, video or document"
        }
        val active = requireCurrent(session)
        val conversation = requireConversation(active, conversationId)
        var streamed: MediaAttachmentStreamCipher.StreamedAttachment? = null
        // The ciphertext is spooled to app-private disk rather than held in heap, which is what
        // lets an attachment be far larger than the process could ever allocate.
        // Android points java.io.tmpdir at this app's own cache directory, so a null parent here
        // is still app-private storage and no Context has to reach this layer to say so.
        val spooled = File.createTempFile("kit-media-", ".ciphertext", null)
        try {
            // Assign inside the non-cancellable worker before dispatching back. If cancellation
            // wins that return handoff, finally still owns and erases every produced array.
            withContext(Dispatchers.IO + NonCancellable) {
                streamed = spooled.outputStream().buffered().use { output ->
                    source.open().use { input ->
                        MediaAttachmentStreamCipher.encrypt(
                            source = input.buffered(),
                            destination = output,
                            maximumPlaintextBytes = MAX_IMAGE_PLAINTEXT_BYTES,
                        )
                    }
                }
            }
            coroutineContext.ensureActive()
            val owned = checkNotNull(streamed)
            val uploaded = active.transport.uploadAttachment(normalizedMediaType, spooled)
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
                plaintextByteSize = owned.plaintextByteSize,
                caption = caption?.trim()?.takeIf(String::isNotEmpty),
            )
            val authenticatedText = descriptor.encode()
            check(KitMediaMessage.parse(authenticatedText) == descriptor) {
                "The attachment store returned media metadata that cannot be authenticated"
            }
            sendText(session, conversation.conversationId, authenticatedText)
        } finally {
            streamed?.keyMaterial?.fill(0)
            streamed?.sha256?.fill(0)
            spooled.delete()
        }
    }

    override suspend fun openMediaToFile(
        session: SecureMessagingChatSession,
        conversationId: String,
        descriptorText: String,
        destination: File,
    ): Int {
        val media = requireNotNull(KitMediaMessage.parse(descriptorText)) {
            "This message does not reference readable secure media"
        }
        val active = requireCurrent(session)
        requireConversation(active, conversationId)
        var keyMaterial: ByteArray? = null
        var expectedSha256: ByteArray? = null
        // Ciphertext lands on app-private disk instead of in heap; nothing is decrypted out of it
        // until its authenticated digest and HMAC have both been checked over every byte.
        val ciphertext = File.createTempFile("kit-media-", ".ciphertext", null)
        try {
            val downloadedBytes = withContext(Dispatchers.IO + NonCancellable) {
                active.transport.downloadAttachmentToFile(
                    storageKey = media.storageKey,
                    maximumBytes = media.ciphertextByteSize,
                    destination = ciphertext,
                )
            }
            coroutineContext.ensureActive()
            keyMaterial = media.keyMaterial()
            expectedSha256 = media.ciphertextSha256Bytes()
            val key = checkNotNull(keyMaterial)
            val digest = checkNotNull(expectedSha256)
            check(sessions.currentOrNull() === active) {
                "Secure messaging session changed while downloading encrypted media"
            }
            check(downloadedBytes == media.ciphertextByteSize) {
                "The encrypted media blob does not match its authenticated size"
            }
            val plaintextBytes = withContext(Dispatchers.IO + NonCancellable) {
                destination.outputStream().buffered().use { output ->
                    MediaAttachmentStreamCipher.decrypt(
                        ciphertext = ciphertext,
                        keyMaterial = key,
                        expectedSha256 = digest,
                        destination = output,
                    )
                }
            }
            coroutineContext.ensureActive()
            check(plaintextBytes == media.plaintextByteSize) {
                "The decrypted media does not match its authenticated size"
            }
            check(sessions.publishIfCurrent(active) {}) {
                "Secure messaging session changed while decrypting encrypted media"
            }
            return plaintextBytes
        } finally {
            keyMaterial?.fill(0)
            expectedSha256?.fill(0)
            ciphertext.delete()
        }
    }

    override suspend fun messageDeliveryInfo(
        session: SecureMessagingChatSession,
        conversationId: String,
        messageId: String,
    ): ValidatedMessageDeliveryInfo {
        val active = requireCurrent(session)
        val conversation = requireConversation(active, conversationId)
        return active.transport.messageInfo(conversation, messageId)
    }

    override suspend fun markConversationRead(
        session: SecureMessagingChatSession,
        conversationId: String,
    ) = readMutex.withLock {
        val active = requireCurrent(session)
        val conversation = requireConversation(active, conversationId)
        // Every other member, so a group marks read against whoever actually spoke. For a direct
        // chat this is the single peer, which is exactly the previous behaviour.
        val senderUserIds = conversation.otherMemberUserIds(active.binding.userId)
        val newestUnreadMessageId = projections.withActivationLease(
            active.activation,
            readyRequired = true,
        ) {
            newestUnreadInboundMessageId(
                conversationId = conversationId,
                senderUserIds = senderUserIds,
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
                senderUserIds = senderUserIds,
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
        expectedOwner: SessionFence? = null,
    ): Map<String, RemoteSecureMessagingTransport.Session.SecureConversation> =
        conversationMutex.withLock {
            prepareOwner(active)
            if (forceRefresh || !conversationsLoaded) {
                val loaded = active.transport.conversations(expectedOwner)
                check(sessions.currentOrNull() === active) {
                    "Secure messaging session changed while loading conversations"
                }
                val byId = loaded.associateBy { it.conversationId }
                check(byId.size == loaded.size) {
                    "The server returned duplicate conversation identifiers"
                }
                // Only direct chats are unique per peer. Groups deliberately are not: the same
                // people may hold several, and each is a separate conversation.
                val directPeers = loaded.filterNot { it.isGroup }.map { it.peerUserId }
                check(directPeers.distinct().size == directPeers.size) {
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
        expectedOwner: SessionFence? = null,
    ): RemoteSecureMessagingTransport.Session.SecureConversation =
        loadConversations(active, forceRefresh = false, expectedOwner)[conversationId]
            ?: loadConversations(active, forceRefresh = true, expectedOwner)[conversationId]
            ?: error("The secure conversation is no longer available")

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

    private suspend fun hasPendingOutboundPredecessor(
        active: SecureMessagingActiveSession,
        conversationId: String,
        excludingClientMessageId: String,
    ): Boolean = projections.withActivationLease(
        active.activation,
        readyRequired = true,
    ) {
        var after: String? = null
        repeat(MAX_PROJECTION_PAGES) {
            val page = readPage(after, PROJECTION_PAGE_SIZE)
            if (page.messages().any { projected ->
                    projected.deliveryState ==
                        SecureMessagingProjectionDeliveryState.OUTBOUND_PENDING &&
                        projected.durableRecord.direction ==
                        LibSignalCompanionDirection.OUTBOUND &&
                        projected.durableRecord.conversationId == conversationId &&
                        projected.durableRecord.clientMessageId != excludingClientMessageId
                }
            ) {
                return@withActivationLease true
            }
            val next = page.nextAfterRecordKey ?: return@withActivationLease false
            check(after == null || next > after!!) {
                "Encrypted projection pagination did not advance"
            }
            after = next
        }
        error("Encrypted projection history exceeds the supported recovery bound")
    }

    private suspend fun reissuePending(
        active: SecureMessagingActiveSession,
        conversation: RemoteSecureMessagingTransport.Session.SecureConversation,
        durable: LibSignalCompanionRecord,
        plan: SecureMessagingEncryptionPlan,
        expectedOwner: SessionFence?,
    ) {
        val encrypted = SecureMessagingCryptoWireMapper.retryEncryption(durable, plan)
        val receipt = active.transport.send(
            conversation,
            encrypted,
            KitMediaMessage.attachmentsFor(durable.authenticatedText),
            expectedOwner,
        )
        projections.withActivationLease(active.activation, readyRequired = true) {
            markOutboundSent(durable, receipt)
        }
    }

    private suspend fun commitMissingSessions(
        active: SecureMessagingActiveSession,
        conversation: RemoteSecureMessagingTransport.Session.SecureConversation,
        roster: RemoteSecureMessagingTransport.Session.AuthoritativeRoster,
        plan: SecureMessagingEncryptionPlan,
        transaction: SecureMessagingCryptoTransaction,
        missingDeviceIds: Set<String>,
        expectedOwner: SessionFence?,
    ) {
        var committed = false
        try {
            val request = active.transport.consumeKeyBundles(
                conversation = conversation,
                roster = roster,
                plan = plan,
                deviceIds = missingDeviceIds,
                expectedOwner = expectedOwner,
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
        replyToMessageId: String?,
    ): SecureMessagingEncryptedSend {
        var committed = false
        val request = SecureMessagingEncryptionRequest(
            plan = plan,
            clientMessageId = clientMessageId,
            text = text,
            replyToMessageId = replyToMessageId,
        )
        return try {
            transaction.stageEncryption(request, projections.outboundIntent(clientMessageId))
            val result = transaction.commit()
            check(result is SecureMessagingCommittedResult.Encrypted) {
                "Secure messaging encryption returned the wrong committed operation"
            }
            committed = true
            SecureMessagingCryptoWireMapper.encryption(
                result,
                messageKind = authenticatedOutboundMessageKind(text),
            )
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

    private fun RemoteSecureMessagingTransport.Session.SecureConversation.toAuthenticated(
        viewerUserId: String,
    ) = AuthenticatedConversation(
        id = conversationId,
        type = type,
        title = title,
        viewerUserId = viewerUserId,
        currentUserRole = currentUserRole,
        members = members.map {
            AuthenticatedConversationMember(userId = it.userId, name = it.name, role = it.role)
        },
        description = description,
        photoUrl = photoUrl,
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

/**
 * Retries one session-owned recovery operation without busy-looping or surviving owner loss.
 * A false result is terminal; exceptions retry only while they are transient.
 */
private suspend fun retrySecureMessagingOperation(
    isCurrent: () -> Boolean,
    operation: suspend () -> Boolean,
): Boolean {
    var failedAttempts = 0
    var cooldownMillis = RECOVERY_RETRY_COOLDOWN_MILLIS
    while (isCurrent()) {
        try {
            return operation() && isCurrent()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (!isCurrent() || !isRetryableSecureMessagingRecoveryFailure(error)) return false
            failedAttempts++
            val retryDelay = if (failedAttempts < RECOVERY_RETRY_ATTEMPTS) {
                RECOVERY_RETRY_DELAY_MILLIS
            } else {
                failedAttempts = 0
                cooldownMillis.also {
                    cooldownMillis = (cooldownMillis * 2)
                        .coerceAtMost(MAX_RECOVERY_RETRY_COOLDOWN_MILLIS)
                }
            }
            delay(retryDelay)
        }
    }
    return false
}

private fun isRetryableSecureMessagingRecoveryFailure(error: Throwable): Boolean {
    if (error is SecureMessagingAuthenticationEpochChangedException ||
        isRecoverableSecureMessagingStateLoss(error)
    ) {
        return false
    }
    if (isRetryableSecureMessagingStateFailure(error)) return true
    return when (error) {
        is IOException,
        is SecureMessagingStateConflictException,
        -> true
        is KitWalletApiException -> error.statusCode?.let { status ->
            status == 408 || status == 425 || status == 429 || status >= 500
        } ?: error.connectivity
        else -> false
    }
}

private const val RECOVERY_RETRY_ATTEMPTS = 4
private const val RECOVERY_RETRY_DELAY_MILLIS = 5_000L
private const val RECOVERY_RETRY_COOLDOWN_MILLIS = 30_000L
private const val MAX_RECOVERY_RETRY_COOLDOWN_MILLIS = 5 * 60_000L
private const val MAX_CHAT_CONTACT_NAME_LENGTH = 160
private val CHAT_CONTACT_UUID = Regex(
    "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-" +
        "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
)

private fun String?.safeChatContactName(): String? = this
    ?.filterNot(Char::isISOControl)
    ?.trim()
    ?.take(MAX_CHAT_CONTACT_NAME_LENGTH)
    ?.takeIf(String::isNotBlank)
    ?.takeUnless(CHAT_CONTACT_UUID::matches)

@Singleton
class EncryptedChatRepository @Inject internal constructor(
    private val runtime: SecureMessagingChatRuntime,
    private val contacts: ContactRepository,
    @ApplicationScope scope: CoroutineScope,
    private val clock: Clock,
    private val composerDrafts: SecureMessagingComposerDraftStore? = null,
    private val conversationPrefs: ConversationPrefsDao? = null,
    private val systemEvents: ConversationSystemEventStore? = null,
    private val presence: KitPresenceRegistry? = null,
    private val typists: KitTypingRegistry? = null,
    private val profilePhotos: ProfilePhotoDirectory? = null,
    private val authenticationSessions: SessionStore? = null,
    private val scheduledSends: ScheduledSendStore? = null,
    private val immediateSends: ImmediateSendIntentStore? = null,
    private val immediateMediaSpool: ImmediateMediaSpool? = null,
    private val immediateSendScheduler: SecureMessagingSyncScheduler? = null,
    private val secureMediaCache: SecureMediaCache? = null,
    private val avatarUploader: ProfileAvatarUploader? = null,
) : ChatRepository {
    /** Serializes caller-owned media IDs across lookup, spool write and durable intent commit. */
    private val idempotentMediaSendMutex = Mutex()

    // Drafts are a best-effort convenience riding the encrypted messaging state store; they are
    // erased with that state on logout and must never fail or gate any messaging operation.
    override suspend fun composerDraft(chatId: String): String? =
        runCatching { composerDrafts?.read(chatId) }.getOrNull()

    override suspend fun saveComposerDraft(chatId: String, text: String) {
        runCatching { composerDrafts?.save(chatId, text) }
    }

    override suspend fun clearComposerDraft(chatId: String) {
        runCatching { composerDrafts?.clear(chatId) }
    }

    // A message-ready transport is necessary but not sufficient for UI readiness. Keep this gate
    // closed until the new epoch's restored/current projection baseline has been published, so an
    // open conversation cannot mistake restored history for newly arrived messages or payments.
    //
    // This is the *exchange* gate and nothing else: it says a message may be sent, not that there
    // is something to show. What to show is [localHistoryReady], which the local encrypted store
    // can answer on its own. Conflating the two is what used to blank the entire Messages screen
    // behind "Secure messaging is not ready" for as long as the network took to answer.
    private val mutableReadiness = MutableStateFlow(false)
    override val readiness: StateFlow<Boolean> = mutableReadiness.asStateFlow()

    private val mutableLocalHistoryReady = MutableStateFlow(false)
    override val localHistoryReady: StateFlow<Boolean> = mutableLocalHistoryReady.asStateFlow()

    // Corrections need a live authenticated session to be gated at all: the capability is a fact
    // about the account, and the local store cannot know it. So this is published only alongside
    // the message-ready publication and withdrawn with it, which leaves an offline or still
    // starting app with no edit affordance rather than one that would fail on use.
    private val mutableMessageEditsAvailable = MutableStateFlow(false)
    override val messageEditsAvailable: StateFlow<Boolean> =
        mutableMessageEditsAvailable.asStateFlow()

    // Viewer-local pin/mute decoration happens synchronously at the publication edge so the
    // authenticated projection flow stays byte-derived from the encrypted store while readers
    // (including openDirectConversation's post-publication check) never observe a stale list.
    private val rawChats = MutableStateFlow<List<ChatPreview>>(emptyList())
    private val prefsSnapshot = MutableStateFlow<List<ConversationPrefEntity>>(emptyList())
    private val mutableChats = MutableStateFlow<List<ChatPreview>>(emptyList())
    override val chats: StateFlow<List<ChatPreview>> = mutableChats.asStateFlow()

    // Presence and typing are the one part of a preview that is not derived from the encrypted
    // store: they are facts about right now, held in RAM by the realtime registries and folded in
    // at the same publication edge as the viewer-local pin/mute decoration. Both are empty unless a
    // conversation is currently subscribed, which is only ever the one on screen.
    private val onlineConversations = MutableStateFlow<Set<String>>(emptySet())
    private val typingConversations = MutableStateFlow<Set<String>>(emptySet())

    // The whole roster map, not just which conversations have somebody in them: a participant
    // list needs to know *who* is watching, one dot per row, and a group needs to name whoever is
    // typing rather than say that somebody, somewhere in it, is.
    private val presenceRosters = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    private val typingRosters = MutableStateFlow<Map<String, Set<String>>>(emptyMap())

    private fun publishChats(previews: List<ChatPreview>) {
        rawChats.value = previews
        republishChats()
    }

    private fun republishChats() {
        synchronized(publicationLock) {
            mutableChats.value = applyRealtimeSignals(
                applyConversationPrefs(rawChats.value, prefsSnapshot.value),
                online = onlineConversations.value,
                typing = typingConversations.value,
                typingNames = typingNames(),
            )
        }
    }

    /**
     * Names for the people typing in each group, ordered the way its participant list reads.
     *
     * Only groups appear here — a direct chat's typist is the person already named at the top of
     * the screen. A typist this device cannot name is left out rather than guessed at: the bubble
     * still shows, it simply says "typing…" the way it always has.
     */
    private fun typingNames(): Map<String, List<String>> {
        val rosters = typingRosters.value
        if (rosters.isEmpty()) return emptyMap()
        val members = rawMembers.value
        return rosters.mapValues { (conversationId, typists) ->
            members[conversationId].orEmpty()
                .filter { member ->
                    !member.isSelf && typists.any { it.equals(member.userId, ignoreCase = true) }
                }
                .map(ChatMember::name)
        }.filterValues(List<String>::isNotEmpty)
    }

    /**
     * Re-publishes every participant list with the current presence roster folded in.
     *
     * The roster is only populated for a conversation this device is subscribed to — the one on
     * screen — so everybody else's dot is simply off rather than guessed at. Our own row never
     * lights up: a dot means "somebody else is here", the same rule the chat list follows.
     */
    private fun republishMembers() {
        synchronized(conversationLock) {
            memberFlows.forEach { (conversationId, flow) ->
                flow.value = decoratedMembers(conversationId)
            }
        }
    }

    private fun decoratedMembers(conversationId: String): List<ChatMember> {
        val roster = presenceRosters.value[conversationId].orEmpty()
        return rawMembers.value[conversationId].orEmpty().map { member ->
            member.copy(
                online = !member.isSelf && roster.any { it.equals(member.userId, true) },
            )
        }
    }

    override suspend fun setChatPinned(chatId: String, pinned: Boolean) {
        val dao = conversationPrefs ?: return
        runCatching {
            dao.put((dao.get(chatId) ?: ConversationPrefEntity(chatId)).copy(pinned = pinned))
        }
    }

    override suspend fun setChatMuted(chatId: String, muted: Boolean) {
        val dao = conversationPrefs ?: return
        runCatching {
            dao.put((dao.get(chatId) ?: ConversationPrefEntity(chatId)).copy(muted = muted))
        }
    }
    private val publicationLock = Any()
    private val conversationLock = Any()
    private val conversationFlows = mutableMapOf<String, MutableStateFlow<List<Message>>>()
    private val memberFlows = mutableMapOf<String, MutableStateFlow<List<ChatMember>>>()
    private val rawMembers = MutableStateFlow<Map<String, List<ChatMember>>>(emptyMap())
    private val refreshMutex = Mutex()
    private var publishedSession: SecureMessagingChatSession? = null

    /**
     * The activation whose local history is currently on screen, when no ready session's
     * publication has replaced it yet. Never a substitute for [publishedSession]: it names what is
     * displayed, and confers no authority to send.
     */
    private var publishedLocalActivation: SecureMessagingActivationCapability? = null
    private val localHistoryMutex = Mutex()

    /** A memory-only bubble shown while the selected media is encrypted into the durable spool. */
    private data class StagingMedia(
        val id: String,
        val owner: SessionFence,
        val conversationId: String,
        val createdAtEpochMillis: Long,
        val mediaType: String,
        val caption: String?,
        val plaintextBytes: Int,
        val replyToMessageId: String? = null,
    )

    private val stagingMedia = MutableStateFlow<List<StagingMedia>>(emptyList())

    private data class ProjectionPublication(
        val chats: List<ChatPreview>,
        val messagesByConversation: Map<String, List<Message>>,
        /** Groups only: a direct chat's "participants" are the two people already on screen. */
        val membersByConversation: Map<String, List<ChatMember>> = emptyMap(),
        /** Trusted URLs learned while building this exact authenticated projection. */
        val learnedProfilePhotos: Map<String, String> = emptyMap(),
    )

    init {
        conversationPrefs?.let { dao ->
            scope.launch {
                dao.observeAll().collect { prefs ->
                    prefsSnapshot.value = prefs
                    republishChats()
                }
            }
        }
        presence?.let { registry ->
            scope.launch {
                registry.presence.collect { rosters ->
                    // Our own membership is not presence: the dot means "somebody else is here".
                    val self = registry.selfPublicId
                    presenceRosters.value = rosters
                    onlineConversations.value =
                        rosters.filterValues { members -> members.any { it != self } }.keys
                    republishChats()
                    republishMembers()
                }
            }
        }
        typists?.let { registry ->
            scope.launch {
                registry.typing.collect { byConversation ->
                    val active = byConversation.filterValues(Set<String>::isNotEmpty)
                    typingRosters.value = active
                    typingConversations.value = active.keys
                    republishChats()
                }
            }
        }
        scheduledSends?.let { queue ->
            scope.launch {
                // Send-later items live in the same encrypted state as the transcripts, so they are
                // readable exactly when an activation is, and they belong to that activation alone.
                // Re-reading on every change rather than once is what stops one account's scheduled
                // messages being shown to — or sent by — the next one to sign in on this device.
                runtime.localHistoryActivations
                    .distinctUntilChanged { previous, next -> previous === next }
                    .collectLatest { activation ->
                        try {
                            if (activation == null) queue.forget() else queue.reload()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            // An unreadable queue is not something the user can act on here. The
                            // next activation change re-reads it; nothing else depends on this.
                        }
                    }
            }
        }
        immediateSends?.let { queue ->
            scope.launch {
                runtime.localHistoryActivations
                    .distinctUntilChanged { previous, next -> previous === next }
                    .collectLatest { activation ->
                        try {
                            if (activation == null) {
                                queue.forget()
                            } else {
                                queue.reload()
                                immediateMediaSpool?.prune(
                                    queue.items.value
                                        .filter { it.kind == ImmediateSendKind.MEDIA }
                                        .mapTo(mutableSetOf(), ImmediateSendIntent::id),
                                )
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            // A later activation/projection signal retries the encrypted queue.
                        }
                    }
            }
            scope.launch {
                combine(runtime.activeSession, queue.items) { session, pending ->
                    session != null && pending.any { it.state == ImmediateSendState.WAITING }
                }.distinctUntilChanged().collect { shouldWake ->
                    if (shouldWake) runCatching { immediateSendScheduler?.schedule() }
                }
            }
        }
        scope.launch {
            // Draws the app from the encrypted store the moment there is an activation to read
            // it with, which is before the transport, key and roster round trips have run — and
            // regardless of whether they ever succeed. This is what makes Messages usable on a
            // cold start, offline, and while secure setup is retrying in the background.
            //
            // It yields the screen to the exchange path below as soon as that path publishes:
            // the ready baseline is authoritative, this is the interim view of the same data.
            combine(
                runtime.localHistoryActivations,
                mutableReadiness,
                runtime.projectionChanges,
                contacts.contacts,
                combine(
                    immediateSends?.items ?: MutableStateFlow(emptyList()),
                    stagingMedia,
                ) { pending, staging -> pending to staging },
            ) { activation, ready, _, contactList, _ ->
                Triple(activation, ready, contactList)
            }.conflate().collect { (activation, ready, contactList) ->
                localHistoryMutex.withLock {
                    // Compared by identity rather than waiting for an intervening null: StateFlow
                    // conflation could otherwise carry one activation's chats into the next.
                    if (publishedLocalActivation !== activation) discardLocalHistoryPublication()
                    if (activation == null || ready) return@withLock
                    try {
                        publishLocalHistory(activation, contactList)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Unreadable local state is not an error the user can act on. Leave the
                        // screen as it is; the next projection change or activation retries.
                    }
                }
            }
        }
        scope.launch {
            // Only a REAL activation change (a different identity, or none at all) may cancel
            // in-flight projection work. Signals for the same activation are conflated and
            // served sequentially below, so a long rebuild can never be starved by the
            // foreground sync ticks or draft writes — the livelock that used to
            // blank every chat exactly while the user was typing.
            runtime.activeSession
                .distinctUntilChanged { previous, next ->
                    previous?.identity === next?.identity
                }
                .collectLatest { session ->
                    if (session == null) {
                        // Lifecycle blips (key revalidation, roster resync) retain the registry
                        // for a moment; keep the last published chats visible instead of
                        // blanking the app. A real sign-out never returns, so the retained
                        // plaintext is erased after a short grace that a returning session
                        // cancels via collectLatest.
                        synchronized(publicationLock) {
                            mutableReadiness.value = false
                            // The chats stay on screen through a lifecycle blip, but the edit
                            // affordance does not: no session means no capability to prove.
                            mutableMessageEditsAvailable.value = false
                        }
                        delay(SIGNED_OUT_CLEAR_GRACE_MILLIS)
                        clearPublishedStateIfCurrent(null)
                        return@collectLatest
                    }
                    val signals = merge(
                        combine(
                            runtime.projectionChanges,
                            contacts.contacts,
                        ) { _, contactList -> contactList },
                        runtime.baselineRetrySessions
                            .filter { it.identity === session.identity }
                            .map { contacts.contacts.value },
                        immediateSends?.items
                            ?.map { contacts.contacts.value }
                            ?: emptyFlow(),
                        stagingMedia.drop(1).map { contacts.contacts.value },
                    )
                    signals.conflate().collect { contactList ->
                        if (!runtime.isCurrent(session)) return@collect
                        try {
                            if (needsProjectionBaseline(session)) {
                                establishProjectionBaseline(session, contactList)
                            } else {
                                refresh(session, contactList)
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            // Keep the last good publication visible; the next conflated
                            // signal (sync tick, projection change) retries the rebuild.
                        }
                    }
                }
        }
    }

    /**
     * Publishes the chat list and transcripts from this device's own encrypted store.
     *
     * Everything here is local: the roster comes from [ConversationRosterStore], the messages from
     * the projection store, both under an activation lease that has never required stage READY.
     * No network call is made and none is waited for, so this succeeds in flight mode with the
     * same content the user last had.
     */
    private suspend fun publishLocalHistory(
        activation: SecureMessagingActivationCapability,
        localContacts: List<Contact>,
    ) {
        val conversations = runtime.cachedConversations(activation)
        val projections = readAllProjectionPages { after, limit ->
            runtime.localProjectionPage(activation, after, limit)
        }
        val pending = immediateSends?.items?.value.orEmpty()
        val staging = currentStagingMedia()
        if (
            conversations.isEmpty() && projections.isEmpty() && pending.isEmpty() &&
            staging.isEmpty()
        ) {
            // Nothing has ever synced on this device. Say so by publishing an empty-but-ready
            // list rather than leaving the screen in a permanent loading state.
            synchronized(publicationLock) {
                if (mutableReadiness.value || publishedSession != null) return
                publishedLocalActivation = activation
                mutableLocalHistoryReady.value = true
            }
            return
        }
        val membershipHistory = try {
            systemEvents?.load(conversations.map(AuthenticatedConversation::id))
            systemEvents?.events?.value.orEmpty()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyMap()
        }
        val publication = buildPublication(
            conversations = conversations,
            projections = projections,
            localContacts = localContacts,
            membershipHistory = membershipHistory,
            strictSenderAuthentication = false,
            pendingIntents = pending,
            stagingMedia = staging,
        )
        val committed = synchronized(publicationLock) {
            // The exchange path may have published while this was being built. Its baseline is
            // authoritative and must never be replaced by the interim local view.
            if (mutableReadiness.value || publishedSession != null) {
                false
            } else {
                commitPublicationBodyLocked(publication)
                publishedLocalActivation = activation
                mutableLocalHistoryReady.value = true
                true
            }
        }
        if (committed) rememberPublicationPhotos(activation.binding.sessionEpoch, publication)
    }

    /** Takes the local view off screen, but never a ready session's publication. */
    private fun discardLocalHistoryPublication() = synchronized(publicationLock) {
        if (publishedSession != null) {
            // A ready session already replaced the interim view. Nothing to take down, and
            // withdrawing readiness here would be a lie about what is on screen.
            publishedLocalActivation = null
            return@synchronized
        }
        val owned = publishedLocalActivation != null
        publishedLocalActivation = null
        mutableLocalHistoryReady.value = false
        if (owned) clearPublishedStateLocked(owner = null)
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
        // A previous activation's projections must never remain visible while this identity's
        // baseline touches the network, so an identity change erases them first. A readiness
        // blip on the SAME activation deliberately keeps the last publication on screen: the
        // rebuild replaces it atomically on commit, and a cancelled or failed rebuild leaves
        // the user's chats intact instead of blanking the app.
        val previousIdentity = synchronized(publicationLock) { publishedSession?.identity }
        if (previousIdentity != null && previousIdentity !== session.identity) {
            if (!clearPublishedStateIfCurrent(session)) return
        } else if (!runtime.isCurrent(session)) {
            return
        }
        var attempt = 0
        var permanentRecoveryAttempted = false
        var retryCooldownMillis = BASELINE_REFRESH_COOLDOWN_MILLIS
        while (runtime.isCurrent(session) && !isReadyFor(session)) {
            if (!runtime.isCurrent(session) || isReadyFor(session)) return
            try {
                refresh(session, localContacts, establishReadiness = true)
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                debugProjectionBaselineFailure(error)
                if (!runtime.isCurrent(session)) {
                    return
                }
                if (
                    !permanentRecoveryAttempted &&
                    isRecoverableSecureMessagingStateLoss(error)
                ) {
                    permanentRecoveryAttempted = true
                    val recovered = retrySecureMessagingOperation(
                        isCurrent = { runtime.isCurrent(session) },
                        operation = {
                            runtime.recoverPermanentlyUnavailableState(error)
                        },
                    )
                    if (!runtime.isCurrent(session)) {
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
                    awaitBaselineRetryWindow(session, BASELINE_REFRESH_RETRY_DELAY_MILLIS)
                } else {
                    // A healthy activation must not depend on another flow emission to recover
                    // from a transient provider/network outage; the identity-change collector
                    // cancels this wait on logout or replacement, and a successful sync
                    // completion for this activation cuts it short immediately.
                    attempt = 0
                    awaitBaselineRetryWindow(session, retryCooldownMillis)
                    retryCooldownMillis = (retryCooldownMillis * 2)
                        .coerceAtMost(MAX_BASELINE_REFRESH_COOLDOWN_MILLIS)
                }
            }
        }
    }

    /** Waits [millis], or less when this activation reports a successful sync completion. */
    private suspend fun awaitBaselineRetryWindow(
        session: SecureMessagingChatSession,
        millis: Long,
    ) {
        merge(
            flow {
                delay(millis)
                emit(Unit)
            },
            runtime.baselineRetrySessions
                .filter { it.identity === session.identity }
                .map { },
        ).first()
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

    // Session wrappers are re-minted per activation emission; the underlying identity is the
    // activation. Comparing identities keeps a same-activation re-emission from forcing a
    // destructive full re-baseline after every lifecycle blip.
    private fun needsProjectionBaseline(session: SecureMessagingChatSession): Boolean =
        synchronized(publicationLock) {
            publishedSession?.identity !== session.identity || !mutableReadiness.value
        }

    private fun isPublishedSession(session: SecureMessagingChatSession): Boolean =
        synchronized(publicationLock) { publishedSession?.identity === session.identity }

    private fun isReadyFor(session: SecureMessagingChatSession): Boolean =
        synchronized(publicationLock) {
            publishedSession?.identity === session.identity && mutableReadiness.value
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

    override fun searchMessages(query: String, limit: Int): List<MessageSearchHit> {
        val needle = query.trim()
        if (needle.length < 2) return emptyList()
        val previews = chats.value.associateBy(ChatPreview::id)
        val snapshot = synchronized(conversationLock) {
            conversationFlows.mapValues { (_, flow) -> flow.value }
        }
        val hits = mutableListOf<MessageSearchHit>()
        for ((conversationId, messages) in snapshot) {
            val preview = previews[conversationId] ?: continue
            for (message in messages.asReversed()) {
                // Only ordinary decrypted text is searchable; media captions ride inside
                // authenticated descriptors and payment cards are excluded like on iOS.
                if (message.kind != MessageKind.TEXT) continue
                if (!message.text.contains(needle, ignoreCase = true)) continue
                hits += MessageSearchHit(preview, message)
                if (hits.size >= limit) return hits
            }
        }
        return hits
    }

    override fun conversation(chatId: String): StateFlow<List<Message>> =
        synchronized(conversationLock) {
            conversationFlows.getOrPut(chatId) { MutableStateFlow(emptyList()) }.asStateFlow()
        }

    override suspend fun openDirectConversation(contact: Contact): String {
        require(contact.isKitUser) {
            "Only contacts who are on Kit Pay can receive secure messages"
        }
        chats.value.singleOrNull { preview ->
            !preview.isGroup && preview.peerUserId.equals(contact.id, ignoreCase = true)
        }?.let { return it.id }
        val session = requireReadySession()
        val created = runtime.createDirectConversation(session, contact.id)
        refresh(session, contacts.contacts.value)
        check(chat(created.id) != null) { "The secure conversation was not added to the projection" }
        return created.id
    }

    override suspend fun createGroupConversation(title: String, contacts: List<Contact>): String {
        val session = requireReadySession()
        val normalizedTitle = normalizeMessagingGroupTitle(title)
        require(normalizedTitle.isNotEmpty()) { "A group needs a name" }
        require(isValidMessagingGroupTitle(normalizedTitle)) { "That group name is too long" }
        require(contacts.isNotEmpty()) { "A group needs at least one other person" }
        require(contacts.all { it.isKitUser }) {
            "Only contacts who are on Kit Pay can join a group"
        }
        val memberUserIds = contacts.map(Contact::id).distinct()
        require(memberUserIds.size == contacts.size) { "That group lists somebody twice" }
        val created = runtime.createGroupConversation(session, normalizedTitle, memberUserIds)
        refresh(session, this.contacts.contacts.value)
        check(chat(created.id) != null) { "The secure conversation was not added to the projection" }
        return created.id
    }

    override fun groupMembers(chatId: String): StateFlow<List<ChatMember>> =
        synchronized(conversationLock) {
            memberFlows.getOrPut(chatId) { MutableStateFlow(decoratedMembers(chatId)) }
                .asStateFlow()
        }

    override suspend fun addGroupMember(chatId: String, contact: Contact) {
        require(contact.isKitUser) { "Only contacts who are on Kit Pay can join a group" }
        mutateGroupMembership(chatId) { session ->
            runtime.addGroupMember(session, chatId, contact.id)
        }
    }

    override suspend fun setGroupMemberRole(chatId: String, userId: String, role: ChatMemberRole) {
        mutateGroupMembership(chatId) { session ->
            runtime.setGroupMemberRole(session, chatId, userId, role.toConversationRole())
        }
    }

    override suspend fun removeGroupMember(chatId: String, userId: String) {
        mutateGroupMembership(chatId) { session ->
            runtime.removeGroupMember(session, chatId, userId)
        }
    }

    override suspend fun updateGroupDescription(chatId: String, description: String?) {
        val canonical = description
            ?.let(::normalizeMessagingGroupDescription)
            ?.takeIf(String::isNotEmpty)
        canonical?.let {
            require(isValidMessagingGroupDescription(it)) { "That description is too long" }
        }
        mutateGroupMembership(chatId) { session ->
            runtime.updateGroupDescription(session, chatId, canonical)
        }
    }

    override suspend fun updateGroupPhoto(chatId: String, jpegBytes: ByteArray) {
        val uploader = checkNotNull(avatarUploader) {
            "Group photos are unavailable in this configuration"
        }
        // The upload rides the same moderated pipeline as a profile photo — intent, direct
        // upload, scan, sanitize — and only the resulting clean asset id is offered to the
        // group. The server then re-checks ownership, management rights and the sanitizer's
        // proof before anything changes.
        val assetId = uploader.uploadReadyAvatarAsset(jpegBytes)
        mutateGroupMembership(chatId) { session ->
            runtime.attachGroupPhoto(session, chatId, assetId)
        }
    }

    override suspend fun removeGroupPhoto(chatId: String) {
        mutateGroupMembership(chatId) { session ->
            runtime.removeGroupPhoto(session, chatId)
        }
    }

    override suspend fun leaveGroupConversation(chatId: String) {
        val session = requireReadySession()
        // A direct chat cannot be left, only deleted, and there is no delete here: leaving one
        // would take a peer's whole history off this device with no way to ask for it back.
        check(chat(chatId)?.isGroup == true) { "That conversation is not a group" }
        runtime.leaveGroupConversation(session, chatId)
        refresh(session, contacts.contacts.value)
        // The projection is rebuilt from what the server still says we are in, so a group we have
        // left simply stops being there. Saying so out loud keeps a stale chat from lingering on
        // a list that is otherwise only ever appended to.
        check(chat(chatId) == null) { "The secure conversation was not removed from the projection" }
    }

    /**
     * Applies one membership change and re-publishes from the server's answer.
     *
     * The projection is never edited in place: the server decides who is in a group, and a local
     * guess about the outcome would show a member who was actually refused, or hide one who was
     * not. The refresh is what makes the participant list true.
     */
    private suspend fun mutateGroupMembership(
        chatId: String,
        mutate: suspend (SecureMessagingChatSession) -> Unit,
    ) {
        val session = requireReadySession()
        check(chat(chatId)?.isGroup == true) { "That conversation is not a group" }
        mutate(session)
        refresh(session, contacts.contacts.value)
    }

    override suspend fun sendMessage(
        chatId: String,
        text: String,
        replyToMessageId: String?,
        onDurablyCommitted: (clientMessageId: String) -> Unit,
    ) = sendValidatedText(
        chatId = chatId,
        text = text,
        retryClientMessageId = null,
        authorship = AuthoredContent.USER_TEXT,
        onDurablyCommitted = onDurablyCommitted,
        replyToMessageId = replyToMessageId,
    )

    override suspend fun sendMessageForOwner(
        owner: SessionFence,
        chatId: String,
        text: String,
        onDurablyCommitted: (clientMessageId: String) -> Unit,
    ) = sendValidatedText(
        chatId = chatId,
        text = text,
        retryClientMessageId = null,
        authorship = AuthoredContent.USER_TEXT,
        onDurablyCommitted = onDurablyCommitted,
        expectedOwner = owner,
    )

    override suspend fun sendIdempotentMessageForOwner(
        owner: SessionFence,
        chatId: String,
        text: String,
        clientMessageId: String,
    ) = sendValidatedText(
        chatId = chatId,
        text = text,
        retryClientMessageId = null,
        authorship = AuthoredContent.USER_TEXT,
        expectedOwner = owner,
        idempotentClientMessageId = clientMessageId,
    )

    override suspend fun sendPaymentEvent(
        chatId: String,
        descriptor: String,
        onDurablyCommitted: (clientMessageId: String) -> Unit,
    ) = sendValidatedText(
        chatId = chatId,
        text = descriptor,
        retryClientMessageId = null,
        authorship = AuthoredContent.PAYMENT_EVENT,
        onDurablyCommitted = onDurablyCommitted,
    )

    override suspend fun sendGroupPaymentEvent(
        chatId: String,
        descriptor: String,
        onDurablyCommitted: (clientMessageId: String) -> Unit,
    ) = sendValidatedText(
        chatId = chatId,
        text = descriptor,
        retryClientMessageId = null,
        authorship = AuthoredContent.GROUP_PAYMENT_EVENT,
        onDurablyCommitted = onDurablyCommitted,
    )

    override suspend fun sendPaymentEventForOwner(
        owner: SessionFence,
        chatId: String,
        descriptor: String,
        onDurablyCommitted: (clientMessageId: String) -> Unit,
    ) = sendValidatedText(
        chatId = chatId,
        text = descriptor,
        retryClientMessageId = null,
        authorship = AuthoredContent.PAYMENT_EVENT,
        onDurablyCommitted = onDurablyCommitted,
        expectedOwner = owner,
    )

    override suspend fun retryMessage(chatId: String, clientMessageId: String, text: String) =
        retryPendingOrSendEncrypted(
            chatId = chatId,
            text = text,
            clientMessageId = clientMessageId,
            authorship = AuthoredContent.USER_TEXT,
        )

    override suspend fun retryPaymentEvent(
        chatId: String,
        clientMessageId: String,
        descriptor: String,
    ) = retryPendingOrSendEncrypted(
        chatId = chatId,
        text = descriptor,
        clientMessageId = clientMessageId,
        authorship = AuthoredContent.PAYMENT_EVENT,
    )

    private suspend fun retryPendingOrSendEncrypted(
        chatId: String,
        text: String,
        clientMessageId: String,
        authorship: AuthoredContent,
    ) {
        val queue = immediateSends
        val owner = authenticationSessions?.current()?.fence()
        val pending = queue?.find(clientMessageId)
        if (queue != null && owner != null && pending != null) {
            check(pending.conversationId == chatId) { "Queued message belongs to another chat" }
            queue.rearmForOwner(owner, pending.id)
            immediateSendScheduler?.schedule()
            return
        }
        sendValidatedText(
            chatId = chatId,
            text = text,
            retryClientMessageId = clientMessageId,
            authorship = authorship,
        )
    }

    override suspend fun toggleReaction(chatId: String, messageId: String, emoji: String) {
        val current = conversation(chatId).value.firstOrNull { it.id == messageId }
        // Reacting to a message this device has not authenticated locally would send a descriptor
        // pointing at nothing the peer can resolve back to a bubble.
        requireNotNull(current) { "That message is no longer in this conversation" }
        require(current.acceptsReactions) { "That message cannot be reacted to yet" }
        require(KitReactionMessage.isAcceptableReaction(emoji)) { "That is not a usable reaction" }
        val alreadyMine = current.reactions.any { it.emoji == emoji && it.fromMe }
        val descriptor = KitReactionMessage(
            targetMessageId = messageId,
            emoji = emoji,
            action = if (alreadyMine) KitReactionAction.REMOVE else KitReactionAction.ADD,
        ).encode()
        sendValidatedText(
            chatId = chatId,
            text = descriptor,
            retryClientMessageId = null,
            authorship = AuthoredContent.REACTION,
        )
    }

    override suspend fun editMessage(chatId: String, messageId: String, text: String) {
        // Refuse before anything is parked in the offline queue. The send path checks the roster
        // too, but by then the correction is already durable, and a queued edit the account was
        // never entitled to send would only be retired later with nothing to show for it.
        if (!mutableMessageEditsAvailable.value) {
            throw SecureMessagingConversationCapabilityUnavailableException(
                "Encrypted message edits are not enabled",
            )
        }
        val current = conversation(chatId).value.firstOrNull { it.id == messageId }
        // Correcting a message this device has not authenticated locally would send a descriptor
        // pointing at nothing the peer can resolve back to a bubble.
        requireNotNull(current) { "That message is no longer in this conversation" }
        require(current.fromMe) { "Only your own messages can be edited" }
        val normalized = text.trim()
        require(current.acceptsEdits(clock.instant().toEpochMilli())) {
            "This message is too old to edit"
        }
        require(normalized != current.text) { "That is the same wording" }
        require(KitEditMessage.isAcceptableBody(normalized)) {
            "Enter the wording you meant to send"
        }
        sendValidatedText(
            chatId = chatId,
            text = KitEditMessage(targetMessageId = messageId, body = normalized).encode(),
            retryClientMessageId = null,
            authorship = AuthoredContent.EDIT,
        )
    }

    /**
     * Checks a chosen reply target against this device's own view of the thread.
     *
     * A reply pins an ID the peer has to be able to resolve back to a bubble. Sending one that
     * names a message this device never authenticated — or one still waiting in the outbox under a
     * client ID the server has not replaced yet — would arrive as an answer to nothing.
     */
    private fun resolvedReplyTarget(chatId: String, replyToMessageId: String?): String? {
        val target = replyToMessageId?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val current = conversation(chatId).value.firstOrNull { it.id == target }
        requireNotNull(current) { "That message is no longer in this conversation" }
        require(current.acceptsReplies) { "That message cannot be replied to yet" }
        return target
    }

    /** Which caller produced the authenticated text, and therefore which rules validate it. */
    private enum class AuthoredContent {
        USER_TEXT,
        PAYMENT_EVENT,
        GROUP_PAYMENT_EVENT,
        REACTION,
        EDIT,
    }

    private suspend fun sendValidatedText(
        chatId: String,
        text: String,
        retryClientMessageId: String?,
        authorship: AuthoredContent,
        onDurablyCommitted: (clientMessageId: String) -> Unit = {},
        expectedOwner: SessionFence? = null,
        replyToMessageId: String? = null,
        idempotentClientMessageId: String? = null,
    ) {
        val normalized = text.trim()
        require(normalized.isNotEmpty()) { "Enter a message to send securely" }
        when (authorship) {
            AuthoredContent.PAYMENT_EVENT -> require(KitPaymentMessage.parse(normalized) != null) {
                "Kit Pay could not validate this payment event"
            }
            AuthoredContent.GROUP_PAYMENT_EVENT ->
                require(KitGroupPaymentMessage.parse(normalized) != null) {
                    "Kit Pay could not validate this group payment event"
                }
            AuthoredContent.REACTION -> require(KitReactionMessage.parse(normalized) != null) {
                "Kit Pay could not validate this reaction"
            }
            AuthoredContent.EDIT -> require(KitEditMessage.parse(normalized) != null) {
                "Kit Pay could not validate this edit"
            }
            AuthoredContent.USER_TEXT -> require(KitUserAuthoredTextPolicy.allows(normalized)) {
                "Messages cannot start with one of Kit Pay's reserved prefixes"
            }
        }
        val replyTarget = resolvedReplyTarget(chatId, replyToMessageId)
        val queue = immediateSends
        val owner = expectedOwner ?: authenticationSessions?.current()?.fence()
        require(retryClientMessageId == null || idempotentClientMessageId == null) {
            "A secure send cannot be both an explicit retry and an idempotent enqueue"
        }
        idempotentClientMessageId?.let {
            require(ImmediateSendIntent.CANONICAL_UUID.matches(it)) {
                "Invalid idempotent secure-message ID"
            }
        }
        if (retryClientMessageId == null && queue != null && owner != null) {
            if (authenticationSessions?.current()?.fence() != owner) {
                throw SessionInvalidatedException()
            }
            check(chat(chatId) != null) { "The secure conversation is no longer available" }
            val intent = ImmediateSendIntent(
                id = idempotentClientMessageId ?: UUID.randomUUID().toString(),
                conversationId = chatId,
                kind = when (authorship) {
                    AuthoredContent.USER_TEXT -> ImmediateSendKind.TEXT
                    AuthoredContent.PAYMENT_EVENT -> ImmediateSendKind.PAYMENT_EVENT
                    AuthoredContent.GROUP_PAYMENT_EVENT ->
                        ImmediateSendKind.GROUP_PAYMENT_EVENT
                    AuthoredContent.REACTION -> ImmediateSendKind.REACTION
                    AuthoredContent.EDIT -> ImmediateSendKind.EDIT
                },
                createdAtEpochMillis = clock.instant().toEpochMilli(),
                text = normalized,
                replyToMessageId = replyTarget,
            )
            if (idempotentClientMessageId == null) {
                queue.enqueueForOwner(owner, intent)
            } else {
                queue.enqueueIdempotentForOwner(owner, intent)
            }
            onDurablyCommitted(intent.id)
            immediateSendScheduler?.schedule()
            return
        }
        val session = requireReadySession()
        expectedOwner?.let { expected ->
            if (
                authenticationSessions?.current()?.fence() != expected ||
                session.sessionEpoch != expected.sessionId
            ) throw SessionInvalidatedException()
        }
        try {
            runtime.sendText(
                session = session,
                conversationId = chatId,
                text = normalized,
                retryClientMessageId = retryClientMessageId,
                // A reaction and an edit each carry their own target inside the descriptor the
                // peer authenticates them against; the envelope must name that same message.
                replyToMessageId = when (authorship) {
                    AuthoredContent.REACTION ->
                        checkNotNull(KitReactionMessage.parse(normalized)).targetMessageId
                    AuthoredContent.EDIT ->
                        checkNotNull(KitEditMessage.parse(normalized)).targetMessageId
                    else -> replyTarget
                },
                onDurablyCommitted = onDurablyCommitted,
                expectedOwner = expectedOwner,
                idempotentClientMessageId = idempotentClientMessageId,
            )
        } finally {
            refresh(session, contacts.contacts.value)
        }
    }

    override suspend fun sendMediaMessage(
        chatId: String,
        source: SecureMediaSource,
        mediaType: String,
        caption: String?,
        replyToMessageId: String?,
    ) = sendMediaMessageInternal(
        chatId = chatId,
        source = source,
        mediaType = mediaType,
        caption = caption,
        replyToMessageId = replyToMessageId,
        expectedOwner = null,
        idempotentClientMessageId = null,
    )

    override suspend fun sendIdempotentMediaMessageForOwner(
        owner: SessionFence,
        chatId: String,
        source: SecureMediaSource,
        mediaType: String,
        clientMessageId: String,
        caption: String?,
    ) = idempotentMediaSendMutex.withLock {
        sendMediaMessageInternal(
            chatId = chatId,
            source = source,
            mediaType = mediaType,
            caption = caption,
            replyToMessageId = null,
            expectedOwner = owner,
            idempotentClientMessageId = clientMessageId,
        )
    }

    private suspend fun sendMediaMessageInternal(
        chatId: String,
        source: SecureMediaSource,
        mediaType: String,
        caption: String?,
        replyToMessageId: String?,
        expectedOwner: SessionFence?,
        idempotentClientMessageId: String?,
    ) {
        val queue = immediateSends
        val spool = immediateMediaSpool
        val owner = expectedOwner ?: authenticationSessions?.current()?.fence()
        val replyTarget = resolvedReplyTarget(chatId, replyToMessageId)
        if (queue != null && spool != null && owner != null) {
            if (authenticationSessions?.current()?.fence() != owner) {
                throw SessionInvalidatedException()
            }
            check(chat(chatId) != null) { "The secure conversation is no longer available" }
            val normalizedMediaType = requireNotNull(KitMediaMessage.normalizeMediaType(mediaType)) {
                "Choose a supported photo, voice note, video or document"
            }
            val normalizedCaption = caption?.trim()?.takeIf(String::isNotBlank)
            require(
                normalizedCaption == null ||
                    normalizedCaption.toByteArray(Charsets.UTF_8).size <=
                    ImmediateSendIntent.MAX_CAPTION_UTF8_BYTES,
            ) { "Queued media caption is too large" }
            idempotentClientMessageId?.let {
                require(ImmediateSendIntent.CANONICAL_UUID.matches(it)) {
                    "Invalid idempotent secure-media ID"
                }
            }
            val id = idempotentClientMessageId ?: UUID.randomUUID().toString()
            if (idempotentClientMessageId != null) {
                queue.findForOwner(owner, id)?.let { existing ->
                    check(
                        existing.kind == ImmediateSendKind.MEDIA &&
                            existing.conversationId == chatId &&
                            existing.mediaType == normalizedMediaType &&
                            existing.caption == normalizedCaption &&
                            existing.replyToMessageId == replyTarget &&
                            existing.mediaPlaintextBytes.toLong() == source.declaredByteCount,
                    ) { "An immediate-send identity belongs to different media" }
                    if (existing.state == ImmediateSendState.RETRY_REQUIRED) {
                        queue.rearmForOwner(owner, existing.id)
                    }
                    immediateSendScheduler?.schedule()
                    return
                }
            }
            val createdAt = clock.instant().toEpochMilli()
            stagingMedia.update { current ->
                current + StagingMedia(
                    id = id,
                    owner = owner,
                    conversationId = chatId,
                    createdAtEpochMillis = createdAt,
                    mediaType = normalizedMediaType,
                    caption = normalizedCaption,
                    plaintextBytes = source.declaredByteCount
                        .coerceIn(0L, MAX_IMAGE_PLAINTEXT_BYTES.toLong()).toInt(),
                    replyToMessageId = replyTarget,
                )
            }
            try {
                val material = spool.stage(id, source)
                val intent = ImmediateSendIntent(
                    id = id,
                    conversationId = chatId,
                    kind = ImmediateSendKind.MEDIA,
                    createdAtEpochMillis = createdAt,
                    mediaType = normalizedMediaType,
                    caption = normalizedCaption,
                    mediaPlaintextBytes = material.plaintextBytes,
                    mediaCiphertextBytes = material.ciphertextBytes,
                    mediaKeyBase64 = material.keyBase64,
                    mediaSha256Base64 = material.sha256Base64,
                    replyToMessageId = replyTarget,
                )
                if (idempotentClientMessageId == null) {
                    queue.enqueueForOwner(owner, intent)
                } else {
                    queue.enqueueIdempotentForOwner(owner, intent)
                }
            } catch (error: Throwable) {
                spool.discard(id)
                throw error
            } finally {
                stagingMedia.update { current -> current.filterNot { it.id == id } }
            }
            immediateSendScheduler?.schedule()
            return
        }
        check(idempotentClientMessageId == null && expectedOwner == null) {
            "The durable secure-media outbox is unavailable"
        }
        val session = requireReadySession()
        try {
            runtime.sendImage(session, chatId, source, mediaType, caption)
        } finally {
            refresh(session, contacts.contacts.value)
        }
    }

    override suspend fun openImageMessage(
        chatId: String,
        mediaDescriptor: String,
    ): SecureMediaFile {
        if (mediaDescriptor.startsWith(LOCAL_MEDIA_DESCRIPTOR_PREFIX)) {
            val id = mediaDescriptor.removePrefix(LOCAL_MEDIA_DESCRIPTOR_PREFIX)
            val intent = immediateSends?.find(id)
            if (
                intent == null && currentStagingMedia().any {
                    it.id == id && it.conversationId == chatId
                }
            ) {
                error("This secure attachment is still being prepared")
            }
            check(intent != null && intent.conversationId == chatId && intent.kind == ImmediateSendKind.MEDIA) {
                "The queued secure attachment is no longer available"
            }
            val mediaType = checkNotNull(intent.mediaType)
            val cache = requireSecureMediaCache()
            cache.cached(mediaDescriptor, mediaType)?.let { return it }
            val ciphertext = checkNotNull(immediateMediaSpool).ciphertextFile(intent)
            return cache.store(mediaDescriptor, mediaType) { destination ->
                val key = intent.mediaKeyMaterial()
                val digest = intent.mediaSha256()
                try {
                    destination.outputStream().buffered().use { output ->
                        MediaAttachmentStreamCipher.decrypt(ciphertext, key, digest, output)
                    }
                } finally {
                    key.fill(0)
                    digest.fill(0)
                }
            }
        }
        val mediaType = requireNotNull(KitMediaMessage.parse(mediaDescriptor)?.mediaType) {
            "This message does not reference readable secure media"
        }
        val cache = requireSecureMediaCache()
        cache.cached(mediaDescriptor, mediaType)?.let { return it }
        val session = requireReadySession()
        return cache.store(mediaDescriptor, mediaType) { destination ->
            runtime.openMediaToFile(session, chatId, mediaDescriptor, destination)
        }
    }

    // Asked for only once the descriptor has been judged openable at all. A missing cache is a
    // wiring fault, and reporting it ahead of "still being prepared" would hide the real answer
    // behind an internal one.
    private fun requireSecureMediaCache(): SecureMediaCache = checkNotNull(secureMediaCache) {
        "This chat repository does not support secure media messages"
    }

    /** Worker-only upload step; money/media selection never passes through this boundary. */
    internal suspend fun prepareImmediateMediaDescriptor(
        owner: SessionFence,
        intent: ImmediateSendIntent,
        ciphertext: File,
    ): String {
        require(intent.kind == ImmediateSendKind.MEDIA && intent.preparedMediaDescriptor == null)
        val session = requireReadySession()
        if (
            authenticationSessions?.current()?.fence() != owner ||
            session.sessionEpoch != owner.sessionId
        ) throw SessionInvalidatedException()
        return runtime.prepareMediaDescriptor(
            session = session,
            conversationId = intent.conversationId,
            attachmentId = intent.id,
            ciphertext = ciphertext,
            mediaType = checkNotNull(intent.mediaType),
            keyMaterialBase64 = checkNotNull(intent.mediaKeyBase64),
            plaintextBytes = intent.mediaPlaintextBytes,
            caption = intent.caption,
        )
    }

    /** Worker-only promotion into the Signal companion outbox under the intent's stable ID. */
    internal suspend fun promoteImmediateSend(
        owner: SessionFence,
        intent: ImmediateSendIntent,
        onDurablyCommitted: () -> Unit,
    ) {
        val session = requireReadySession()
        if (
            authenticationSessions?.current()?.fence() != owner ||
            session.sessionEpoch != owner.sessionId
        ) throw SessionInvalidatedException()
        val text = checkNotNull(intent.authenticatedText) {
            "Queued media must be uploaded before encrypted promotion"
        }
        try {
            runtime.sendText(
                session = session,
                conversationId = intent.conversationId,
                text = text,
                replyToMessageId = when (intent.kind) {
                    ImmediateSendKind.REACTION ->
                        checkNotNull(KitReactionMessage.parse(text)).targetMessageId
                    ImmediateSendKind.EDIT ->
                        checkNotNull(KitEditMessage.parse(text)).targetMessageId
                    else -> intent.replyToMessageId
                },
                onDurablyCommitted = { onDurablyCommitted() },
                expectedOwner = owner,
                idempotentClientMessageId = intent.id,
            )
        } finally {
            refresh(session, contacts.contacts.value)
        }
    }

    override suspend fun markConversationRead(chatId: String) {
        val session = requireReadySession()
        runtime.markConversationRead(session, chatId)
        refresh(session, contacts.contacts.value)
    }

    override suspend fun messageDeliveryInfo(
        chatId: String,
        messageId: String,
    ): MessageDeliveryInfo {
        val session = requireReadySession()
        // Refused here as well as by the server, so a screen that should never have offered the
        // question gets a local no rather than a 403 it would have to translate.
        val sentFromHere = conversation(chatId).value.any {
            it.id == messageId && it.acceptsDeliveryInfo
        }
        check(sentFromHere) { "Only the person who sent a message can see what became of it" }
        val validated = runtime.messageDeliveryInfo(session, chatId, messageId)
        val members = synchronized(conversationLock) { rawMembers.value[chatId].orEmpty() }
        val addressBook = contacts.contacts.value
        return MessageDeliveryInfo(
            messageId = validated.messageId,
            sentAtEpochMillis = validated.sentAt.toEpochMilli(),
            recipients = validated.recipients.map { recipient ->
                val member = members.firstOrNull { it.userId.equals(recipient.userId, true) }
                val contact = addressBook.firstOrNull { it.id.equals(recipient.userId, true) }
                MessageDeliveryPerson(
                    userId = recipient.userId,
                    // This phone's own name for somebody first: a group can contain people who
                    // were never saved here, and only then is the server's name worth showing.
                    name = contact?.name?.takeIf(String::isNotBlank)
                        ?: member?.name?.takeIf(String::isNotBlank)
                        ?: recipient.name,
                    avatarUrl = member?.avatarUrl ?: contact?.avatarUrl,
                    deliveredAtEpochMillis = recipient.deliveredAt?.toEpochMilli() ?: 0,
                    readAtEpochMillis = recipient.readAt?.toEpochMilli() ?: 0,
                )
            },
        )
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

        var conversations = runtime.conversations(session, forceRefresh = false)
        val projections = readAllProjectionPages(session)
        if (projections.any { projected -> conversations.none { it.id == projected.conversationId } }) {
            conversations = runtime.conversations(session, forceRefresh = true)
        }

        // Timeline annotations for whatever this refresh is about to publish. Best-effort by
        // construction: a group whose system-message record cannot be read shows a transcript
        // without those lines rather than no transcript at all.
        val membershipHistory = try {
            systemEvents?.load(conversations.map(AuthenticatedConversation::id))
            systemEvents?.events?.value.orEmpty()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyMap()
        }

        // Authentication and UI mapping may be non-trivial. Build without holding the runtime's
        // active-session lock, then make only the in-memory commit inside its atomic fence.
        val publication = buildPublication(
            conversations = conversations,
            projections = projections,
            localContacts = localContacts,
            membershipHistory = membershipHistory,
            pendingIntents = immediateSends?.items?.value.orEmpty(),
            stagingMedia = currentStagingMedia(),
        )
        var committed = false
        val current = runtime.publishIfCurrent(session) {
            synchronized(publicationLock) {
                if (
                    establishReadiness ||
                    (publishedSession?.identity === session.identity && mutableReadiness.value)
                ) {
                    commitPublicationLocked(session, publication)
                    committed = true
                }
            }
        }
        // An obsolete refresh simply does not commit: the successor activation's own baseline
        // replaces (or, on identity change, erases) the publication. Eagerly clearing here used
        // to blank the UI whenever the registry retained the session mid-refresh.
        (current && committed).also { published ->
            if (published) rememberPublicationPhotos(session.sessionEpoch, publication)
        }
    }

    private suspend fun readAllProjectionPages(
        session: SecureMessagingChatSession,
    ): List<AuthenticatedProjectedText> = readAllProjectionPages { after, limit ->
        runtime.projectionPage(session, after, limit)
    }

    private suspend fun readAllProjectionPages(
        readPage: suspend (afterRecordKey: String?, limit: Int) -> AuthenticatedProjectionPage,
    ): List<AuthenticatedProjectedText> {
        val projected = mutableListOf<AuthenticatedProjectedText>()
        var after: String? = null
        repeat(MAX_PROJECTION_PAGES) {
            val page = readPage(after, PROJECTION_PAGE_SIZE)
            projected += page.messages
            val next = page.nextAfterRecordKey ?: return projected
            check(after == null || next > after!!) { "Encrypted projection pagination did not advance" }
            after = next
        }
        error("Encrypted projection history exceeds the supported display bound")
    }

    private fun buildPublication(
        conversations: List<AuthenticatedConversation>,
        projections: List<AuthenticatedProjectedText>,
        localContacts: List<Contact>,
        membershipHistory: Map<String, List<ConversationSystemEvent>> = emptyMap(),
        strictSenderAuthentication: Boolean = true,
        pendingIntents: List<ImmediateSendIntent> = emptyList(),
        stagingMedia: List<StagingMedia> = emptyList(),
    ): ProjectionPublication {
        val conversationIds = conversations.mapTo(mutableSetOf()) { it.id }
        val conversationsById = conversations.associateBy(AuthenticatedConversation::id)
        fun senderIsMember(projected: AuthenticatedProjectedText): Boolean =
            projected.fromCurrentUser ||
                conversationsById[projected.conversationId]?.others?.any {
                    it.userId.equals(projected.senderUserId, ignoreCase = true)
                } == true
        val projectedClientIds = projections.mapTo(mutableSetOf(), AuthenticatedProjectedText::clientMessageId)
        val visiblePending = pendingIntents.filter {
            it.conversationId in conversationIds && it.id !in projectedClientIds
        }
        val pendingIds = visiblePending.mapTo(mutableSetOf(), ImmediateSendIntent::id)
        val visibleStaging = stagingMedia.filter {
            it.conversationId in conversationIds &&
                it.id !in projectedClientIds &&
                it.id !in pendingIds
        }
        val pendingText = visiblePending.mapNotNull { intent ->
            intent.authenticatedText?.let { text ->
                AuthenticatedProjectedText(
                    recordKey = "intent:${intent.id}",
                    messageId = intent.id,
                    serverMessageId = null,
                    clientMessageId = intent.id,
                    conversationId = intent.conversationId,
                    senderUserId = conversationsById[intent.conversationId]?.viewerUserId
                        ?: return@mapNotNull null,
                    fromCurrentUser = true,
                    text = text,
                    sentAt = Instant.ofEpochMilli(intent.createdAtEpochMillis),
                    deliveryState = when (intent.state) {
                        ImmediateSendState.WAITING -> AuthenticatedTextDeliveryState.PENDING
                        ImmediateSendState.RETRY_REQUIRED ->
                            AuthenticatedTextDeliveryState.RETRY_REQUIRED
                    },
                    replyToMessageId = intent.replyToMessageId,
                )
            }
        }
        val authenticated = (projections + pendingText)
            .filter { it.conversationId in conversationIds }
            // Membership minus the viewer, not peer identity: a group has many legitimate
            // senders, but a bubble attributed to somebody else can never carry this account's
            // own ID. For a direct chat [others] is exactly the peer, so this is the same
            // fail-closed decision it has always been — a bubble is shown only to a proven member.
            //
            // How that decision is enforced differs by source. A freshly loaded server roster and
            // the projections it accompanies must agree exactly, so disagreement is an integrity
            // failure and throws. A roster read back from local cache may legitimately predate a
            // membership change this device has not learned yet, so an unattributable bubble is
            // dropped instead — the same message is never displayed, but one stale row does not
            // cost the user every other conversation on the screen.
            .filter { projected -> strictSenderAuthentication || senderIsMember(projected) }
        if (strictSenderAuthentication) {
            authenticated.forEach { projected ->
                check(senderIsMember(projected)) {
                    "An authenticated message projection names a sender outside this conversation"
                }
            }
        }
        val savedNames = localContacts.asSequence()
            .filter { it.isKitUser && it.savedInDevice }
            .associate { it.id.lowercase() to it.name.safeChatContactName() }
        // The address book wins over the server's name for anyone this device has saved, which is
        // what makes a group's bubbles and reaction lists read the way the rest of the app does.
        fun AuthenticatedConversation.displayNameOf(userId: String): String =
            savedNames[userId.lowercase()]
                ?: memberNamed(userId)?.name.safeChatContactName()
                ?: DEFAULT_PEER_NAME
        // A reaction is carried by an ordinary encrypted message, so it arrives here as a
        // projection like any other. Fold each conversation's reaction descriptors onto the
        // messages they point at and drop them from the transcript: they are an annotation on a
        // bubble, never a bubble, a chat preview or an unread count of their own.
        val orderedByConversation = authenticated.groupBy(AuthenticatedProjectedText::conversationId)
            .mapValues { (_, values) -> values.sortedWith(authenticatedProjectionOrder) }
        // A group that has been joined but not yet spoken in still has a timeline: its membership
        // lines are the whole of it, so the transcript is keyed by more than what has ciphertext.
        val timelineConversationIds = orderedByConversation.keys +
            membershipHistory.keys.filter { conversationsById[it]?.isGroup == true } +
            visiblePending.map(ImmediateSendIntent::conversationId) +
            visibleStaging.map(StagingMedia::conversationId)
        val messageLists = timelineConversationIds.associateWith { conversationId ->
            val ordered = orderedByConversation[conversationId].orEmpty()
            val conversation = conversationsById[conversationId]
            val reactions = foldAuthenticatedReactions(
                ordered = ordered,
                nameOf = { senderUserId ->
                    conversation?.displayNameOf(senderUserId) ?: DEFAULT_PEER_NAME
                },
            )
            val edits = foldAuthenticatedEdits(ordered)
            val bubbles = ordered
                .filterNot {
                    KitReactionMessage.isReactionText(it.text) ||
                        KitEditMessage.isEditText(it.text)
                }
                .map { projected ->
                    toUiMessage(
                        projected = projected,
                        reactions = reactions[projected.messageId].orEmpty(),
                        edit = edits[projected.messageId],
                        // Only a group needs an author label on the bubble; a direct chat's
                        // sender is already the person named at the top of the screen.
                        senderName = conversation
                            ?.takeIf { it.isGroup && !projected.fromCurrentUser }
                            ?.displayNameOf(projected.senderUserId),
                    )
                }
            val localMedia = visiblePending.asSequence()
                .filter {
                    it.conversationId == conversationId &&
                        it.kind == ImmediateSendKind.MEDIA &&
                        it.preparedMediaDescriptor == null
                }
                .map(::toPendingMediaMessage)
                .toList()
            val preparingMedia = visibleStaging.asSequence()
                .filter { it.conversationId == conversationId }
                .map(::toStagingMediaMessage)
                .toList()
            // Membership lines belong to a group's timeline, not to a direct chat, whose
            // membership cannot change at all.
            val notices = conversation?.takeIf(AuthenticatedConversation::isGroup)?.let { group ->
                membershipHistory[conversationId].orEmpty().mapNotNull { event ->
                    toSystemMessage(
                        event = event,
                        isViewer = event.userId.equals(group.viewerUserId, ignoreCase = true),
                        // A removed member has already left the roster by the time this reads
                        // it, so the address book is what usually names them. Nothing is
                        // invented when neither knows: the line names no one instead.
                        name = savedNames[event.userId.lowercase()]
                            ?: group.memberNamed(event.userId)?.name.safeChatContactName(),
                    )
                }
            }.orEmpty()
            // sortedBy is stable, so the authenticated bubbles keep their order exactly and a
            // notice slots in after anything sent in the same millisecond.
            MessageReplyQuotes.resolve(
                (bubbles + localMedia + preparingMedia + notices).sortedBy { it.sortEpochMillis },
            )
        }
        // Neither a reaction nor a correction is a bubble, a chat preview or an unread of its
        // own: both are annotations on something already in the thread.
        val projectedByConversation = orderedByConversation.mapValues { (_, ordered) ->
            ordered.filterNot {
                KitReactionMessage.isReactionText(it.text) ||
                    KitEditMessage.isEditText(it.text)
            }
        }
        val latestByConversation = messageLists.mapValues { (_, messages) ->
            messages.lastOrNull { it.kind != MessageKind.SYSTEM }
        }
        val contactAvatars = localContacts.asSequence()
            .filter { it.isKitUser && it.id.isNotBlank() }
            .mapNotNull { contact ->
                contact.avatarUrl?.trim()?.takeIf(String::isNotEmpty)?.let {
                    contact.id.lowercase() to it
                }
            }
            .toMap()
        // The remembered photos come first and the freshly loaded contacts overwrite them, so a
        // chat list drawn before — or entirely without — a contacts fetch still shows faces, and a
        // photo that has since changed is corrected the moment the network says so.
        val avatarsByUser = profilePhotos?.currentPhotos().orEmpty() + contactAvatars
        // A group is named by its server-visible title; a direct chat by whoever is on the other
        // end, preferring the local address book.
        fun AuthenticatedConversation.displayName(): String = if (isGroup) {
            title.safeChatContactName() ?: DEFAULT_GROUP_NAME
        } else {
            peerUserId?.let { displayNameOf(it) } ?: DEFAULT_PEER_NAME
        }
        val chats = conversations.sortedWith(
            compareByDescending<AuthenticatedConversation> { conversation ->
                latestByConversation[conversation.id]?.sortEpochMillis ?: Long.MIN_VALUE
            }.thenBy { it.displayName().lowercase() }.thenBy { it.id },
        ).map { conversation ->
            val last = latestByConversation[conversation.id]
            ChatPreview(
                id = conversation.id,
                name = conversation.displayName(),
                lastMessage = last?.previewLabel().orEmpty(),
                time = last?.time.orEmpty(),
                peerUserId = conversation.peerUserId,
                unread = projectedByConversation[conversation.id].orEmpty().count { projected ->
                    !projected.fromCurrentUser &&
                        projected.deliveryState == AuthenticatedTextDeliveryState.RECEIVED
                },
                isGroup = conversation.isGroup,
                lastFromMe = last?.fromMe == true,
                lastState = last?.state ?: DeliveryState.READ,
                // A group shows its own photo, never a member's: borrowing an avatar would
                // misname the chat. A direct chat shows the peer, address book first.
                avatarUrl = if (conversation.isGroup) {
                    conversation.photoUrl
                } else {
                    conversation.peerUserId?.let { avatarsByUser[it.lowercase()] }
                },
                description = conversation.description,
            )
        }
        // Participant lists are ordered the way the group reads them: whoever can act on the
        // group first, then everybody else alphabetically, with this account marked rather than
        // moved so its own role stays visible where the eye already is.
        val membersByConversation = conversations.filter(AuthenticatedConversation::isGroup)
            .associate { conversation ->
                conversation.id to conversation.members.map { member ->
                    ChatMember(
                        userId = member.userId,
                        name = if (member.userId == conversation.viewerUserId) {
                            SELF_MEMBER_NAME
                        } else {
                            conversation.displayNameOf(member.userId)
                        },
                        role = member.role.toChatMemberRole(),
                        isSelf = member.userId == conversation.viewerUserId,
                        avatarUrl = avatarsByUser[member.userId.lowercase()],
                        savedInDevice = savedNames.containsKey(member.userId.lowercase()),
                    )
                }.sortedWith(
                    compareBy<ChatMember> { it.role }
                        .thenBy { it.name.lowercase() }
                        .thenBy { it.userId },
                )
            }
        return ProjectionPublication(
            chats = chats,
            messagesByConversation = messageLists,
            membersByConversation = membersByConversation,
            learnedProfilePhotos = contactAvatars,
        )
    }

    /** Writes only after the runtime committed this projection and the login epoch still agrees. */
    private fun rememberPublicationPhotos(
        sessionEpoch: String,
        publication: ProjectionPublication,
    ) {
        if (publication.learnedProfilePhotos.isEmpty()) return
        val current = authenticationSessions?.current() ?: return
        if (current.sessionId != sessionEpoch) return
        profilePhotos?.learn(
            current.fence(),
            publication.learnedProfilePhotos,
            complete = false,
        )
    }

    /** Called only while [publicationLock] and the runtime's exact-session fence are held. */
    private fun commitPublicationLocked(
        session: SecureMessagingChatSession,
        publication: ProjectionPublication,
    ) {
        commitPublicationBodyLocked(publication)
        // The exchange path owns the screen from here; the interim local view is superseded
        // rather than cleared, so nothing blanks between the two.
        publishedLocalActivation = null
        publishedSession = session
        mutableMessageEditsAvailable.value = session.messageEditsEnabled
        // Publish readiness last: observing true implies this activation's chats/messages were
        // already committed under the same repository publication lock.
        mutableReadiness.value = true
        mutableLocalHistoryReady.value = true
    }

    /** The observable half of a commit, shared by the local and message-ready publications. */
    private fun commitPublicationBodyLocked(publication: ProjectionPublication) {
        synchronized(conversationLock) {
            // Replace each conversation atomically: a collector never observes an empty list
            // between commits. Only conversations absent from the new publication are erased
            // (a departed conversation, or a different account's leftovers after re-login).
            conversationFlows.forEach { (conversationId, flow) ->
                if (conversationId !in publication.messagesByConversation) {
                    flow.value = emptyList()
                }
            }
            publication.messagesByConversation.forEach { (conversationId, messages) ->
                conversationFlows
                    .getOrPut(conversationId) { MutableStateFlow(emptyList()) }
                    .value = messages
            }
        }
        rawMembers.value = publication.membersByConversation
        republishMembers()
        publishChats(publication.chats)
    }

    /** One membership change as a centred timeline line, or null when there is nothing to say. */
    private fun toSystemMessage(
        event: ConversationSystemEvent,
        isViewer: Boolean,
        name: String?,
    ): Message? {
        val text = conversationSystemMessageText(
            type = event.type,
            role = event.role,
            name = name,
            isViewer = isViewer,
        ) ?: return null
        return Message(
            id = "system:${event.eventId}",
            text = text,
            time = formatChatTime(event.occurredAt),
            fromMe = false,
            kind = MessageKind.SYSTEM,
            sortEpochMillis = event.occurredAt.toEpochMilli(),
        )
    }

    private fun toUiMessage(
        projected: AuthenticatedProjectedText,
        reactions: List<MessageReaction>,
        senderName: String? = null,
        edit: AuthenticatedMessageEdit? = null,
    ): Message {
        val media = KitMediaMessage.parse(projected.text)
        val mediaKind = media?.let { KitChatMediaKind.fromMediaType(it.mediaType) }
        val payment = if (media == null) KitPaymentMessage.parse(projected.text) else null
        val groupPayment = if (media == null && payment == null) {
            KitGroupPaymentMessage.parse(projected.text)
        } else {
            null
        }
        return Message(
            id = projected.messageId,
            text = when {
                // A correction replaces the visible wording and nothing else: a photo stays the
                // photo it was, and only the caption under it reads differently.
                edit != null && payment == null && groupPayment == null -> edit.text
                media != null -> media.caption ?: mediaKind!!.previewLabel
                payment != null -> payment.note.orEmpty()
                // The announcement's own line is built in the chat, where the members' names are
                // known; the note is all of it that survives this layer.
                groupPayment != null -> groupPayment.note.orEmpty()
                else -> projected.text
            },
            time = formatChatTime(projected.sentAt),
            fromMe = projected.fromCurrentUser,
            senderUserId = projected.senderUserId,
            senderName = senderName,
            state = projected.deliveryState.toUiDeliveryState(),
            kind = when {
                mediaKind == KitChatMediaKind.VOICE -> MessageKind.VOICE_NOTE
                mediaKind == KitChatMediaKind.VIDEO -> MessageKind.VIDEO
                mediaKind == KitChatMediaKind.DOCUMENT -> MessageKind.DOCUMENT
                mediaKind == KitChatMediaKind.IMAGE -> MessageKind.IMAGE
                groupPayment != null -> groupPayment.action.toMessageKind()
                payment == null -> MessageKind.TEXT
                else -> payment.action.toMessageKind()
            },
            // The opaque authenticated descriptor; the UI passes it back for follow-up actions.
            mediaDescriptor = if (media != null || payment != null || groupPayment != null) {
                projected.text
            } else {
                null
            },
            mediaType = media?.mediaType,
            mediaPlaintextBytes = media?.plaintextByteSize ?: 0,
            amountMinor = when {
                payment == null -> 0
                payment.moneyLeavesCurrentUser(projected.fromCurrentUser) -> -payment.amountMinor
                else -> payment.amountMinor
            },
            paymentReferenceId = payment?.referenceId,
            paymentEvent = payment?.action?.toPaymentEventKind(),
            paymentNote = payment?.note,
            paymentReason = payment?.reason,
            paymentCurrencyCode = payment?.currencyCode ?: "UGX",
            paymentCurrencyScale = payment?.currencyScale ?: com.kit.wallet.ui.model.Money.SCALE,
            groupPaymentId = groupPayment?.groupPaymentId,
            groupPaymentEvent = groupPayment?.action?.toGroupPaymentEventKind(),
            sortEpochMillis = projected.sentAt.toEpochMilli(),
            reactions = reactions,
            replyToMessageId = projected.replyToMessageId,
            editedAtEpochMillis = edit?.editedAtEpochMillis ?: 0L,
        )
    }

    private fun toPendingMediaMessage(intent: ImmediateSendIntent): Message {
        val mediaType = checkNotNull(intent.mediaType)
        val mediaKind = KitChatMediaKind.fromMediaType(mediaType)
        return Message(
            id = intent.id,
            text = intent.caption ?: mediaKind.previewLabel,
            time = formatChatTime(Instant.ofEpochMilli(intent.createdAtEpochMillis)),
            fromMe = true,
            state = when (intent.state) {
                ImmediateSendState.WAITING -> DeliveryState.SENDING
                ImmediateSendState.RETRY_REQUIRED -> DeliveryState.RETRY_REQUIRED
            },
            kind = when (mediaKind) {
                KitChatMediaKind.VOICE -> MessageKind.VOICE_NOTE
                KitChatMediaKind.VIDEO -> MessageKind.VIDEO
                KitChatMediaKind.DOCUMENT -> MessageKind.DOCUMENT
                KitChatMediaKind.IMAGE -> MessageKind.IMAGE
            },
            mediaDescriptor = LOCAL_MEDIA_DESCRIPTOR_PREFIX + intent.id,
            mediaType = mediaType,
            mediaPlaintextBytes = intent.mediaPlaintextBytes,
            sortEpochMillis = intent.createdAtEpochMillis,
            replyToMessageId = intent.replyToMessageId,
        )
    }

    private fun currentStagingMedia(): List<StagingMedia> {
        val owner = authenticationSessions?.current()?.fence() ?: return emptyList()
        return stagingMedia.value.filter { it.owner == owner }
    }

    private fun toStagingMediaMessage(staging: StagingMedia): Message {
        val mediaKind = KitChatMediaKind.fromMediaType(staging.mediaType)
        return Message(
            id = staging.id,
            text = staging.caption ?: mediaKind.previewLabel,
            time = formatChatTime(Instant.ofEpochMilli(staging.createdAtEpochMillis)),
            fromMe = true,
            state = DeliveryState.SENDING,
            kind = when (mediaKind) {
                KitChatMediaKind.VOICE -> MessageKind.VOICE_NOTE
                KitChatMediaKind.VIDEO -> MessageKind.VIDEO
                KitChatMediaKind.DOCUMENT -> MessageKind.DOCUMENT
                KitChatMediaKind.IMAGE -> MessageKind.IMAGE
            },
            mediaDescriptor = LOCAL_MEDIA_DESCRIPTOR_PREFIX + staging.id,
            mediaType = staging.mediaType,
            mediaPlaintextBytes = staging.plaintextBytes,
            sortEpochMillis = staging.createdAtEpochMillis,
            replyToMessageId = staging.replyToMessageId,
        )
    }

    private fun Message.previewLabel(): String = when (kind) {
        MessageKind.IMAGE -> text.takeIf { it != "Photo" }?.let { "Photo · $it" } ?: "Photo"
        MessageKind.VOICE_NOTE -> text.takeIf { it != "Voice note" }?.let { "Voice note · $it" }
            ?: "Voice note"
        MessageKind.VIDEO -> text.takeIf { it != "Video" }?.let { "Video · $it" } ?: "Video"
        MessageKind.DOCUMENT -> text.takeIf { it != "Document" }?.let { "Document · $it" }
            ?: "Document"
        MessageKind.PAYMENT,
        MessageKind.PAYMENT_REQUEST,
        MessageKind.PAYMENT_TRANSFER,
        MessageKind.PAYMENT_EVENT,
        -> mediaDescriptor?.let(KitPaymentMessage::parse)?.previewLabel() ?: "💸 Payment"
        // A group payment's own words are the announcement, which needs names this layer has not
        // resolved. The chat list says what happened without pretending to know who to.
        MessageKind.GROUP_PAYMENT -> "💛 Group payment"
        MessageKind.GROUP_PAYMENT_EVENT -> "💛 Group payment update"
        else -> text
    }

    /**
     * Whether this descriptor reads as money leaving the person looking at it. A completed payment
     * or an outgoing transfer is a debit for its sender; a return puts the money back, so it is a
     * credit for the original sender and never a debit for anyone.
     */
    private fun KitPaymentMessage.moneyLeavesCurrentUser(fromCurrentUser: Boolean): Boolean =
        when (action) {
            KitPaymentAction.PAID, KitPaymentAction.TRANSFER, KitPaymentAction.SENT -> fromCurrentUser
            KitPaymentAction.REQUEST,
            KitPaymentAction.DECLINED,
            KitPaymentAction.CANCELLED,
            KitPaymentAction.ACCEPTED,
            KitPaymentAction.REJECTED,
            KitPaymentAction.REVERSED,
            KitPaymentAction.EXPIRED,
            -> false
        }

    private fun KitPaymentAction.toMessageKind(): MessageKind = when (this) {
        KitPaymentAction.REQUEST -> MessageKind.PAYMENT_REQUEST
        KitPaymentAction.PAID, KitPaymentAction.SENT -> MessageKind.PAYMENT
        KitPaymentAction.TRANSFER -> MessageKind.PAYMENT_TRANSFER
        KitPaymentAction.DECLINED,
        KitPaymentAction.CANCELLED,
        KitPaymentAction.ACCEPTED,
        KitPaymentAction.REJECTED,
        KitPaymentAction.REVERSED,
        KitPaymentAction.EXPIRED,
        -> MessageKind.PAYMENT_EVENT
    }

    private fun KitGroupPaymentAction.toMessageKind(): MessageKind = when (this) {
        KitGroupPaymentAction.SENT -> MessageKind.GROUP_PAYMENT
        KitGroupPaymentAction.ACCEPTED,
        KitGroupPaymentAction.REJECTED,
        KitGroupPaymentAction.RETURNED,
        -> MessageKind.GROUP_PAYMENT_EVENT
    }

    private fun KitGroupPaymentAction.toGroupPaymentEventKind(): GroupPaymentEventKind =
        when (this) {
            KitGroupPaymentAction.SENT -> GroupPaymentEventKind.ANNOUNCED
            KitGroupPaymentAction.ACCEPTED -> GroupPaymentEventKind.ACCEPTED
            KitGroupPaymentAction.REJECTED -> GroupPaymentEventKind.REJECTED
            KitGroupPaymentAction.RETURNED -> GroupPaymentEventKind.RETURNED
        }

    private fun KitPaymentAction.toPaymentEventKind(): PaymentEventKind = when (this) {
        KitPaymentAction.REQUEST -> PaymentEventKind.REQUESTED
        KitPaymentAction.PAID -> PaymentEventKind.PAID
        KitPaymentAction.DECLINED -> PaymentEventKind.DECLINED
        KitPaymentAction.CANCELLED -> PaymentEventKind.CANCELLED
        KitPaymentAction.TRANSFER -> PaymentEventKind.TRANSFER
        KitPaymentAction.SENT -> PaymentEventKind.SENT
        KitPaymentAction.ACCEPTED -> PaymentEventKind.ACCEPTED
        KitPaymentAction.REJECTED -> PaymentEventKind.REJECTED
        KitPaymentAction.REVERSED -> PaymentEventKind.REVERSED
        KitPaymentAction.EXPIRED -> PaymentEventKind.EXPIRED
    }

    private fun KitPaymentMessage.previewLabel(): String = when (action) {
        KitPaymentAction.REQUEST -> "💰 Payment request"
        KitPaymentAction.PAID, KitPaymentAction.SENT -> "💸 Payment"
        KitPaymentAction.TRANSFER -> "💸 Payment awaiting acceptance"
        KitPaymentAction.ACCEPTED -> "✅ Payment accepted"
        KitPaymentAction.DECLINED -> "↩️ Payment request declined"
        KitPaymentAction.CANCELLED -> "↩️ Payment request cancelled"
        KitPaymentAction.REJECTED -> "↩️ Payment declined and returned"
        KitPaymentAction.REVERSED -> "↩️ Payment reversed"
        KitPaymentAction.EXPIRED -> "↩️ Payment returned"
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
        if (publishedSession?.identity !== session.identity) return@synchronized false
        clearPublishedStateLocked(owner = null)
        true
    }

    /** Called only while [publicationLock] is held. */
    private fun clearPublishedStateLocked(owner: SecureMessagingChatSession?) {
        mutableReadiness.value = false
        mutableLocalHistoryReady.value = false
        mutableMessageEditsAvailable.value = false
        publishedSession = owner
        publishedLocalActivation = null
        publishChats(emptyList())
        rawMembers.value = emptyMap()
        // Timeline annotations are read back per session like everything else here: a warm
        // process must never carry one account's group history into another's publication.
        systemEvents?.forget()
        synchronized(conversationLock) {
            conversationFlows.values.forEach { it.value = emptyList() }
            memberFlows.values.forEach { it.value = emptyList() }
        }
    }

    private companion object {
        const val PROJECTION_PAGE_SIZE = 100
        const val MAX_PROJECTION_PAGES = 100
        const val BASELINE_REFRESH_ATTEMPTS = 4
        const val BASELINE_REFRESH_RETRY_DELAY_MILLIS = 5_000L
        const val BASELINE_REFRESH_COOLDOWN_MILLIS = 30_000L
        const val MAX_BASELINE_REFRESH_COOLDOWN_MILLIS = 5 * 60_000L
        const val SIGNED_OUT_CLEAR_GRACE_MILLIS = 15_000L
        const val LOCAL_MEDIA_DESCRIPTOR_PREFIX = "KITLOCALMEDIA1:"
    }
}

/** Debug builds report only exception class names; no account, message, or key data is logged. */
private fun debugProjectionBaselineFailure(error: Throwable) {
    if (!BuildConfig.DEBUG) return
    val classes = generateSequence(error) { current ->
        current.cause?.takeUnless { it === current }
    }
        .take(MAX_PROJECTION_DIAGNOSTIC_CAUSES)
        .joinToString(" <- ") { it::class.java.simpleName }
    Log.w(PROJECTION_DIAGNOSTIC_TAG, "Projection baseline failure: $classes")
}

private const val PROJECTION_DIAGNOSTIC_TAG = "KitMessagingBaseline"
private const val MAX_PROJECTION_DIAGNOSTIC_CAUSES = 8
private val CHAT_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** Stored/order time stays UTC; only this final presentation step enters the device zone. */
internal fun formatChatTime(
    instant: Instant,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String = CHAT_TIME_FORMATTER.withZone(zoneId).format(instant)

/**
 * Decorates published previews with viewer-local pin/mute flags and floats pinned chats to the
 * top, preserving the recency order the publication already established within each group.
 */
internal fun applyConversationPrefs(
    previews: List<ChatPreview>,
    prefs: List<ConversationPrefEntity>,
): List<ChatPreview> {
    if (prefs.isEmpty()) return previews
    val byId = prefs.associateBy(ConversationPrefEntity::conversationId)
    val decorated = previews.map { preview ->
        val pref = byId[preview.id] ?: return@map preview
        if (pref.pinned == preview.pinned && pref.muted == preview.muted) {
            preview
        } else {
            preview.copy(pinned = pref.pinned, muted = pref.muted)
        }
    }
    return decorated.sortedByDescending(ChatPreview::pinned)
}

/**
 * What a group's timeline says about one membership change.
 *
 * Deliberately actor-free. The sync event names the person the change was *about* and nobody
 * else, so the copy can only ever name them: "Aisha joined this group", never "Brian added
 * Aisha". Leaving and being removed are the same event on the wire, so they get the same neutral
 * line rather than a guess that would be a lie half the time. A change this device cannot put a
 * name to still gets a line — the group did change — but names nobody instead of inventing one.
 *
 * Returns null when there is nothing worth saying, which is what keeps an unrecognised event type
 * or a role change with no role out of the transcript entirely.
 */
internal fun conversationSystemMessageText(
    type: String,
    role: String?,
    name: String?,
    isViewer: Boolean,
): String? {
    val subject = when {
        isViewer -> "You"
        !name.isNullOrBlank() -> name
        else -> null
    }
    fun line(viewer: String, third: String, unnamed: String): String = when {
        isViewer -> viewer
        subject != null -> "$subject $third"
        else -> unnamed
    }
    return when (type) {
        MEMBERSHIP_ADDED_EVENT -> line(
            viewer = "You joined this group",
            third = "joined this group",
            unnamed = "Someone joined this group",
        )
        // "Left" and "was removed" are indistinguishable here, so the line commits to neither.
        MEMBERSHIP_REMOVED_EVENT -> line(
            viewer = "You are no longer in this group",
            third = "is no longer in this group",
            unnamed = "Someone is no longer in this group",
        )
        MEMBERSHIP_ROLE_CHANGED_EVENT -> when (role) {
            OWNER_CONVERSATION_ROLE -> line(
                viewer = "You are now an owner of this group",
                third = "is now an owner of this group",
                unnamed = "This group has another owner",
            )
            ADMIN_CONVERSATION_ROLE -> line(
                viewer = "You are now an admin",
                third = "is now an admin",
                unnamed = "This group has another admin",
            )
            MEMBER_CONVERSATION_ROLE -> line(
                viewer = "You are no longer an admin",
                third = "is no longer an admin",
                unnamed = "This group has one fewer admin",
            )
            else -> null
        }
        else -> null
    }
}

/**
 * Folds the realtime registries' view of "right now" onto an already-ordered preview list.
 *
 * Deliberately does not reorder: presence is the most volatile input the list has, and letting it
 * move rows would make a conversation jump under a finger the moment a peer opened it. It only ever
 * sets the two boolean decorations, and it clears them when the signal is gone — a conversation
 * that stops being subscribed goes dark rather than freezing on its last known state.
 */
internal fun applyRealtimeSignals(
    previews: List<ChatPreview>,
    online: Set<String>,
    typing: Set<String>,
    typingNames: Map<String, List<String>> = emptyMap(),
): List<ChatPreview> {
    if (online.isEmpty() && typing.isEmpty() &&
        previews.none { it.online || it.typing || it.typingNames.isNotEmpty() }
    ) {
        return previews
    }
    return previews.map { preview ->
        val isOnline = preview.id in online
        val isTyping = preview.id in typing
        val names = typingNames[preview.id].orEmpty().takeIf { isTyping }.orEmpty()
        if (
            isOnline == preview.online &&
            isTyping == preview.typing &&
            names == preview.typingNames
        ) {
            preview
        } else {
            preview.copy(online = isOnline, typing = isTyping, typingNames = names)
        }
    }
}
