package com.kit.wallet.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.kit.wallet.data.messaging.KitChatMediaLimits
import com.kit.wallet.data.messaging.GroupPaymentAudience
import com.kit.wallet.data.messaging.GroupPaymentSplitMode
import com.kit.wallet.data.messaging.FinancialCreationReceiptCoordinator
import com.kit.wallet.data.messaging.FinancialCreationReceiptPhase
import com.kit.wallet.data.messaging.KitEditMessage
import com.kit.wallet.data.messaging.KitGroupPaymentAction
import com.kit.wallet.data.messaging.KitGroupPaymentMessage
import com.kit.wallet.data.messaging.KitPaymentAction
import com.kit.wallet.data.messaging.KitPaymentMessage
import com.kit.wallet.data.messaging.KitReactionMessage
import com.kit.wallet.data.messaging.KitUserAuthoredTextPolicy
import com.kit.wallet.data.messaging.MessagingRichMediaCapability
import com.kit.wallet.data.messaging.PendingFinancialEventCoordinator
import com.kit.wallet.data.messaging.ScheduledSend
import com.kit.wallet.data.messaging.ScheduledSendDispatcher
import com.kit.wallet.data.messaging.ScheduledSendKind
import com.kit.wallet.data.messaging.ScheduledSendState
import com.kit.wallet.data.messaging.ScheduledSendStore
import com.kit.wallet.data.messaging.SecureMediaAlbumSource
import com.kit.wallet.data.messaging.SecureMediaFile
import com.kit.wallet.data.messaging.SecureMediaProcessingPlan
import com.kit.wallet.data.messaging.SecureMediaSource
import com.kit.wallet.data.notifications.ActiveCallPresence
import com.kit.wallet.data.notifications.ActiveCallStateHolder
import com.kit.wallet.data.realtime.KitConversationSignals
import com.kit.wallet.data.realtime.KitTypingSignals
import com.kit.wallet.data.remote.KIT_NETWORK_UNAVAILABLE_MESSAGE
import com.kit.wallet.data.remote.CreateCollaborativeGroupPaymentRequest
import com.kit.wallet.data.remote.GroupPaymentRequestContributionDto
import com.kit.wallet.data.remote.GroupPaymentRequestDto
import com.kit.wallet.data.remote.GroupPaymentRequestStatus
import com.kit.wallet.data.remote.KitGroupPaymentRequestAction
import com.kit.wallet.data.remote.KitGroupPaymentRequestMessage
import com.kit.wallet.data.remote.ScheduledGroupPaymentDto
import com.kit.wallet.data.remote.ScheduledGroupPaymentPlanDto
import com.kit.wallet.data.remote.ScheduledPaymentDto
import com.kit.wallet.data.remote.ScheduledPaymentStatus
import com.kit.wallet.data.remote.CreateScheduledPaymentRequest
import com.kit.wallet.data.remote.CurrencyDto
import com.kit.wallet.data.remote.PreviewScheduledGroupPaymentRequest
import com.kit.wallet.data.remote.isKitConnectivityError
import com.kit.wallet.data.remote.isKitInsufficientFundsError
import com.kit.wallet.data.repository.CallRepository
import com.kit.wallet.data.repository.ChatPaymentRequest
import com.kit.wallet.data.repository.ChatRepository
import com.kit.wallet.data.repository.ContactRepository
import com.kit.wallet.data.repository.DefinitiveFinancialMutationRejection
import com.kit.wallet.data.repository.GroupPaymentDraftPolicy
import com.kit.wallet.data.repository.GroupPaymentRepository
import com.kit.wallet.data.repository.GroupPaymentRequestContributionResolution
import com.kit.wallet.data.repository.GroupPaymentRequestRepository
import com.kit.wallet.data.repository.SecureMediaStillPreparingException
import com.kit.wallet.data.repository.ServerScheduledPaymentRepository
import com.kit.wallet.data.repository.WalletRepository
import com.kit.wallet.data.repository.WalletSyncRepository
import com.kit.wallet.data.repository.WalletSyncResult
import com.kit.wallet.data.repository.canonicalTransferClaimReason
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.ui.model.CallDirection
import com.kit.wallet.ui.model.CallEntry
import com.kit.wallet.ui.model.ChatPreview
import com.kit.wallet.ui.model.ChatMember
import com.kit.wallet.ui.model.Contact
import com.kit.wallet.ui.model.DeliveryState
import com.kit.wallet.ui.model.GroupPaymentSummary
import com.kit.wallet.ui.model.Message
import com.kit.wallet.ui.model.MessageDeliveryInfo
import com.kit.wallet.ui.model.MessageKind
import com.kit.wallet.ui.model.MessageMediaItem
import com.kit.wallet.ui.model.albumItemMediaKey
import com.kit.wallet.ui.model.PaymentEventKind
import com.kit.wallet.ui.model.TopUp
import com.kit.wallet.ui.model.TopUpRequirement
import com.kit.wallet.ui.model.TransferClaim
import com.kit.wallet.ui.model.TransferClaimStatus
import com.kit.wallet.ui.model.acceptsDeliveryInfo
import com.kit.wallet.ui.model.acceptsEdits
import com.kit.wallet.ui.model.acceptsReplies
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Process-wide receive gate: navigation must not overlap bounded download/decrypt buffer sets. */
private val secureMediaOpenMutex = Mutex()

/**
 * Marks a thread entry as belonging to the send-later queue rather than to the encrypted store.
 *
 * A scheduled entry has no message id — it has never been encrypted for anyone — so the queue's own
 * id is namespaced here instead. The prefix contains a character the projection's ids cannot, so a
 * real message can never be mistaken for a scheduled one.
 */
internal const val SCHEDULED_MESSAGE_ID_PREFIX = "scheduled:"

/** Canonical lowercase UUID, which is the identity form [ScheduledSend] accepts. */
private fun newScheduledSendId(): String = UUID.randomUUID().toString()

internal data class ConversationSoundDecision(
    val playReceived: Boolean = false,
    val playPaymentReceived: Boolean = false,
    val playSent: Boolean = false,
)

internal data class ServerSchedulePreview(
    val amountMinor: Long,
    val note: String?,
    val scheduledAtEpochMillis: Long,
    val currencyCode: String,
    val currencyScale: Int,
    val recipientNames: List<String>,
)

internal sealed interface ServerScheduleCreation {
    data class Direct(val payment: ScheduledPaymentDto) : ServerScheduleCreation
    data class Group(val payment: ScheduledGroupPaymentDto) : ServerScheduleCreation
}

/** Executes one frozen schedule and persists the ambiguity boundary immediately before its POST. */
internal suspend fun executePendingServerSchedule(
    repository: ServerScheduledPaymentRepository,
    store: PendingServerScheduleStore,
    operation: PendingServerSchedule,
    paymentPin: String,
    now: Instant,
    onSubmitted: (PendingServerSchedule) -> Unit = {},
): ServerScheduleCreation = try {
    val expectedOwner = store.requireOwner(operation)
    val created = when (operation) {
        is PendingServerSchedule.Group -> {
            check(operation.phase == PendingServerSchedulePhase.SUBMITTED ||
                operation.plan.isStructurallyValid(now)
            ) { "This scheduled group payment preview expired. Review it again." }
            val key = {
                store.markSubmitted(operation).also(onSubmitted).idempotencyKey
            }
            ServerScheduleCreation.Group(
                if (operation.phase == PendingServerSchedulePhase.SUBMITTED) {
                    repository.replayGroup(operation.plan, key, paymentPin, expectedOwner)
                } else {
                    repository.createGroup(operation.plan, key, paymentPin, expectedOwner)
                },
            )
        }
        is PendingServerSchedule.Direct -> {
            if (operation.phase == PendingServerSchedulePhase.PREPARED) {
                val scheduledAt = Instant.parse(operation.request.scheduledFor).toEpochMilli()
                ScheduledSend.schedulingError(scheduledAt, now.toEpochMilli())?.let(::error)
            }
            ServerScheduleCreation.Direct(
                repository.createDirect(
                    request = operation.request,
                    currencyCode = operation.currencyCode,
                    idempotencyKey = {
                        store.markSubmitted(operation).also(onSubmitted).idempotencyKey
                    },
                    paymentPin = paymentPin,
                    expectedOwner = expectedOwner,
                ),
            )
        }
    }
    store.complete(operation)
    created
} catch (error: DefinitiveFinancialMutationRejection) {
    val legacyExpiredDirectRejection = operation is PendingServerSchedule.Direct &&
        operation.phase == PendingServerSchedulePhase.SUBMITTED &&
        error.rejection.statusCode == 422 &&
        ScheduledSend.schedulingError(
            Instant.parse(operation.request.scheduledFor).toEpochMilli(),
            now.toEpochMilli(),
        ) != null
    // Older servers checked freshness before idempotency replay. During rollout skew, their 422
    // cannot prove whether the original POST committed, so the submitted identity must survive.
    if (legacyExpiredDirectRejection) {
        throw IllegalStateException(
            "This submitted scheduled payment cannot be confirmed yet. Try again later.",
            error.rejection,
        )
    }
    store.complete(operation)
    throw error.rejection
}

/**
 * One durable identity for a group-request creation whose server result may already exist.
 *
 * A request does not have an ID until the server answers, so an ambiguous response cannot be
 * reconciled with a GET. Persist the complete immutable intent and reuse its idempotency key until
 * the resulting chat descriptor is durably queued. A changed intent is a different operation and
 * must wait for the unresolved one instead of silently minting a second request.
 */
internal class GroupPaymentRequestCreationRetryStore(
    private val state: SavedStateHandle,
) {
    private data class Intent(
        val conversationId: String,
        val destinationWalletId: String,
        val amountMinor: Long,
        val currencyScale: Int,
        val note: String?,
    )

    private data class Pending(val intent: Intent, val key: String)

    private var pending: Pending? = state.get<ArrayList<String>>(STATE_KEY)?.let { restored ->
        check(restored.size == ENCODED_FIELD_COUNT) {
            "Invalid saved group request creation retry"
        }
        val amount = restored[2].toLongOrNull()
        val scale = restored[3].toIntOrNull()
        val note = when (restored[4]) {
            "0" -> null
            "1" -> restored[5]
            else -> error("Invalid saved group request creation retry")
        }
        val key = restored[6]
        check(restored[0].isNotBlank() && restored[1].isNotBlank() &&
            amount != null && amount > 0L && scale != null && scale in 0..6 &&
            (note == null || note.isNotBlank() && note.length <= 280) &&
            key.matches(RETRY_KEY)
        ) { "Invalid saved group request creation retry" }
        Pending(
            Intent(restored[0].lowercase(), restored[1].lowercase(), amount, scale, note),
            key,
        )
    }

    fun keyFor(
        conversationId: String,
        destinationWalletId: String,
        amountMinor: Long,
        currencyScale: Int,
        note: String?,
    ): String {
        val intent = intent(conversationId, destinationWalletId, amountMinor, currencyScale, note)
        pending?.let { existing ->
            check(existing.intent == intent) {
                "Resolve the pending group payment request before changing its amount or note"
            }
            return existing.key
        }
        return "group-request:${UUID.randomUUID()}".also { key ->
            pending = Pending(intent, key)
            persist()
        }
    }

    fun complete(
        conversationId: String,
        destinationWalletId: String,
        amountMinor: Long,
        currencyScale: Int,
        note: String?,
    ) {
        val existing = pending ?: return
        check(existing.intent == intent(
            conversationId,
            destinationWalletId,
            amountMinor,
            currencyScale,
            note,
        )) { "A different group payment request is still unresolved" }
        pending = null
        persist()
    }

    internal fun snapshot(): List<String>? = pending?.let { value ->
        listOf(
            value.intent.conversationId,
            value.intent.destinationWalletId,
            value.intent.amountMinor.toString(),
            value.intent.currencyScale.toString(),
            if (value.intent.note == null) "0" else "1",
            value.intent.note.orEmpty(),
            value.key,
        )
    }

    private fun intent(
        conversationId: String,
        destinationWalletId: String,
        amountMinor: Long,
        currencyScale: Int,
        note: String?,
    ): Intent {
        require(conversationId.isNotBlank() && destinationWalletId.isNotBlank())
        require(amountMinor > 0L && currencyScale in 0..6)
        require(note == null || note.isNotBlank() && note.length <= 280)
        return Intent(
            conversationId.lowercase(),
            destinationWalletId.lowercase(),
            amountMinor,
            currencyScale,
            note,
        )
    }

    private fun persist() {
        val encoded = snapshot()
        if (encoded == null) state.remove<ArrayList<String>>(STATE_KEY)
        else state[STATE_KEY] = ArrayList(encoded)
    }

    private companion object {
        const val STATE_KEY = "pendingGroupPaymentRequestCreation"
        const val ENCODED_FIELD_COUNT = 7
        val RETRY_KEY = Regex("^[A-Za-z0-9._:-]{16,128}$")
    }
}

/** Executes the retry-safe server half; the caller retires the key after durable chat sharing. */
internal suspend fun executeGroupPaymentRequestCreation(
    repository: GroupPaymentRequestRepository,
    retryKeys: GroupPaymentRequestCreationRetryStore,
    conversationId: String,
    destinationWalletId: String,
    amountMinor: Long,
    currencyScale: Int,
    note: String?,
    expectedOwner: com.kit.wallet.data.session.SessionFence? = null,
): GroupPaymentRequestDto = try {
    repository.create(
        conversationId = conversationId,
        request = CreateCollaborativeGroupPaymentRequest(
            destinationWalletId = destinationWalletId,
            totalAmount = BigDecimal.valueOf(amountMinor, currencyScale).toPlainString(),
            note = note,
        ),
        idempotencyKey = {
            retryKeys.keyFor(
                conversationId,
                destinationWalletId,
                amountMinor,
                currencyScale,
                note,
            )
        },
        expectedOwner = expectedOwner,
    )
} catch (error: DefinitiveFinancialMutationRejection) {
    retryKeys.complete(
        conversationId,
        destinationWalletId,
        amountMinor,
        currencyScale,
        note,
    )
    throw error.rejection
}

/** Saved retry identities for contribution POSTs whose result may still be ambiguous. */
internal class GroupPaymentContributionRetryStore(
    private val state: SavedStateHandle,
) {
    private data class Intent(
        val requestId: String,
        val sourceWalletId: String,
        val amountMinor: Long,
    )

    private val keys = linkedMapOf<Intent, String>().apply {
        val restored = state.get<ArrayList<String>>(STATE_KEY).orEmpty()
        check(restored.size <= MAX_PENDING) { "Too many saved group contribution retries" }
        restored.forEach { encoded ->
            val fields = encoded.split('|', limit = 4)
            val amount = fields.getOrNull(2)?.toLongOrNull()
            val key = fields.getOrNull(3)
            check(fields.size == 4 && fields[0].isNotBlank() && fields[1].isNotBlank() &&
                amount != null && amount > 0L &&
                key != null && key.matches(RETRY_KEY)
            ) { "Invalid saved group contribution retry" }
            val intent = Intent(fields[0].lowercase(), fields[1].lowercase(), amount)
            check(keys.none { it.requestId == intent.requestId }) {
                "Conflicting saved group contribution retries"
            }
            put(intent, key)
        }
    }

    fun keyFor(requestId: String, sourceWalletId: String, amountMinor: Long): String {
        val intent = intent(requestId, sourceWalletId, amountMinor)
        requireCompatibleIfPending(intent)
        keys.entries.firstOrNull { it.key.requestId == intent.requestId }?.let { pending ->
            return pending.value
        }
        check(keys.size < MAX_PENDING) { "Too many unresolved group contributions" }
        return keys.getOrPut(intent) { "group-contribution:${UUID.randomUUID()}" }
            .also { persist() }
    }

    /** Checks a retry against an ambiguous intent without allocating a new identity. */
    fun requireCompatibleIfPending(
        requestId: String,
        sourceWalletId: String,
        amountMinor: Long,
    ) {
        requireCompatibleIfPending(intent(requestId, sourceWalletId, amountMinor))
    }

    fun complete(requestId: String, sourceWalletId: String, amountMinor: Long) {
        keys.remove(Intent(requestId.lowercase(), sourceWalletId.lowercase(), amountMinor))
        persist()
    }

    fun reconcile(requestId: String) {
        val canonical = requestId.lowercase()
        keys.keys.removeAll { it.requestId == canonical }
        persist()
    }

    internal fun snapshot(): List<String> = keys.map { (intent, key) ->
        "${intent.requestId}|${intent.sourceWalletId}|${intent.amountMinor}|$key"
    }

    private fun intent(requestId: String, sourceWalletId: String, amountMinor: Long): Intent {
        require(requestId.isNotBlank() && sourceWalletId.isNotBlank() && amountMinor > 0L)
        return Intent(requestId.lowercase(), sourceWalletId.lowercase(), amountMinor)
    }

    private fun requireCompatibleIfPending(intent: Intent) {
        keys.keys.firstOrNull { it.requestId == intent.requestId }?.let { pending ->
            check(pending == intent) {
                "Resolve the pending contribution before changing its wallet or amount"
            }
        }
    }

    private fun persist() {
        state[STATE_KEY] = ArrayList(snapshot())
    }

    private companion object {
        const val STATE_KEY = "pendingGroupContributionRetryKeys"
        const val MAX_PENDING = 32
        val RETRY_KEY = Regex("^[A-Za-z0-9._:-]{16,128}$")
    }
}

/** Runs one contribution attempt and retires its identity only after exact resolution. */
internal suspend fun executeGroupPaymentRequestContribution(
    repository: GroupPaymentRequestRepository,
    retryKeys: GroupPaymentContributionRetryStore,
    requestId: String,
    sourceWalletId: String,
    amountMinor: Long,
    amount: String,
    paymentPin: String,
    expectedOwner: com.kit.wallet.data.session.SessionFence? = null,
): GroupPaymentRequestContributionResolution {
    // Exact preflight can reconcile a changed request without ever asking for the key. Prove the
    // user's current intent first so that outcome cannot erase a different ambiguous operation.
    retryKeys.requireCompatibleIfPending(requestId, sourceWalletId, amountMinor)
    val resolution = try {
        repository.contribute(
            requestId = requestId,
            sourceWalletId = sourceWalletId,
            amount = amount,
            idempotencyKey = {
                retryKeys.keyFor(requestId, sourceWalletId, amountMinor)
            },
            paymentPin = paymentPin,
            expectedOwner = expectedOwner,
        )
    } catch (error: DefinitiveFinancialMutationRejection) {
        retryKeys.reconcile(requestId)
        throw error.rejection
    }
    when (resolution) {
        is GroupPaymentRequestContributionResolution.Confirmed ->
            retryKeys.complete(requestId, sourceWalletId, amountMinor)
        is GroupPaymentRequestContributionResolution.Reconciled ->
            retryKeys.reconcile(requestId)
    }
    return resolution
}

/**
 * Tracks the visible-conversation sound baseline across messaging epoch recovery. Projections
 * published while readiness is closed establish a silent baseline; only later deltas may sound.
 */
internal class ConversationSoundBaseline {
    private var knownIncomingIds: Set<String>? = null
    private var previousOutgoingStates: Map<String, DeliveryState>? = null

    fun observe(
        messagingReady: Boolean,
        projected: List<Message>,
    ): ConversationSoundDecision {
        val incoming = projected.filter { !it.fromMe }
        val incomingIds = incoming.mapTo(mutableSetOf()) { it.id }
        val outgoingStates = projected.asSequence()
            .filter { it.fromMe }
            .associate { it.id to it.state }
        val previousIncoming = knownIncomingIds
        val previousOutgoing = previousOutgoingStates

        // Readiness loss clears repository projections before the replacement epoch publishes its
        // baseline. Preserve the last non-empty identity set across that gap; StateFlow may
        // conflate the intermediate restored-while-not-ready emission with the ready transition.
        if (!messagingReady && projected.isEmpty()) return ConversationSoundDecision()

        val arrived = if (messagingReady && previousIncoming != null) {
            incoming.filter { it.id !in previousIncoming }
        } else {
            emptyList()
        }
        val reachedFirstTick = messagingReady && previousOutgoing != null &&
            outgoingStates.any { (id, state) ->
                state == DeliveryState.SENT && previousOutgoing[id] == DeliveryState.SENDING
            }

        knownIncomingIds = incomingIds
        previousOutgoingStates = outgoingStates
        val paymentArrived = arrived.any { it.kind == MessageKind.PAYMENT }
        return ConversationSoundDecision(
            playReceived = arrived.isNotEmpty() && !paymentArrived,
            playPaymentReceived = paymentArrived,
            playSent = reachedFirstTick,
        )
    }
}

@HiltViewModel
class ChatsViewModel @Inject constructor(
    private val chatRepo: ChatRepository,
    contactRepo: ContactRepository,
    activeCallState: ActiveCallStateHolder = ActiveCallStateHolder(),
) : ViewModel() {
    /** Whether a message can be sent right now. Never a reason to withhold the list. */
    val messagingAvailable = chatRepo.readiness

    /** This account's live call, if any: the list marks its owning row and returns to the call. */
    internal val activeCallPresence: StateFlow<ActiveCallPresence?> = activeCallState.presence

    /** Whether what is on screen came from the local encrypted store. */
    val historyAvailable = chatRepo.localHistoryReady
    val chats = chatRepo.chats

    /** Kit contacts for the global-search Contacts section, like the iOS search sheet. */
    val searchableContacts = contactRepo.contacts
        .map { contacts -> contacts.filter { it.isKitUser && it.id.isNotBlank() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Local-only search across decrypted text projections. */
    fun searchMessages(query: String) = chatRepo.searchMessages(query)

    /** Opens (or creates) the direct conversation for a searched contact. */
    fun openDirectConversation(contact: Contact, onOpened: (String) -> Unit) {
        viewModelScope.launch {
            runCatching { chatRepo.openDirectConversation(contact) }
                .onSuccess(onOpened)
        }
    }

    /** Total unread messages across every conversation, for the navigation badge. */
    val totalUnread: StateFlow<Int> = chatRepo.chats
        .map { previews -> previews.sumOf { preview -> preview.unread } }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            chatRepo.chats.value.sumOf { preview -> preview.unread },
        )

    /** Viewer-local pin; applies to every selected conversation. */
    fun setPinned(chatIds: Collection<String>, pinned: Boolean) {
        viewModelScope.launch {
            chatIds.forEach { chatRepo.setChatPinned(it, pinned) }
        }
    }

    fun setMuted(chatIds: Collection<String>, muted: Boolean) {
        viewModelScope.launch {
            chatIds.forEach { chatRepo.setChatMuted(it, muted) }
        }
    }

    /** Publishes read receipts for every selected conversation with unread messages. */
    fun markRead(chatIds: Collection<String>) {
        viewModelScope.launch {
            chatIds.forEach { chatId ->
                runCatching { chatRepo.markConversationRead(chatId) }
            }
        }
    }
}

/**
 * What the delivery-details sheet has to show about one message.
 *
 * Three states rather than a nullable record: the sheet opens before the answer exists, and the
 * difference between "still asking", "nobody has read it yet" and "we could not ask" is the whole
 * point of the screen.
 */
sealed interface MessageInfoState {
    data object Loading : MessageInfoState

    data class Loaded(val info: MessageDeliveryInfo) : MessageInfoState

    data class Failed(val message: String) : MessageInfoState
}

@HiltViewModel
class ConversationViewModel @Inject internal constructor(
    private val chatRepo: ChatRepository,
    private val walletRepo: WalletRepository,
    private val walletSync: WalletSyncRepository,
    private val groupPaymentRepo: GroupPaymentRepository? = null,
    private val groupPaymentRequestRepo: GroupPaymentRequestRepository? = null,
    private val serverScheduledPaymentRepo: ServerScheduledPaymentRepository? = null,
    private val contactRepo: ContactRepository? = null,
    private val callRepo: CallRepository,
    private val messageSounds: MessageSoundPlayer,
    private val realtime: KitConversationSignals,
    private val typingSignaller: KitTypingSignals,
    activeCallState: ActiveCallStateHolder = ActiveCallStateHolder(),
    savedStateHandle: SavedStateHandle,
    internal val richMediaCapability: MessagingRichMediaCapability? = null,
    private val scheduledSends: ScheduledSendStore? = null,
    private val scheduledDispatcher: ScheduledSendDispatcher? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val sessions: SessionStore? = null,
    private val pendingFinancialEvents: PendingFinancialEventCoordinator? = null,
    private val financialCreationReceipts: FinancialCreationReceiptCoordinator? = null,
) : ViewModel() {

    private val groupRequestCreationRetryKeys =
        GroupPaymentRequestCreationRetryStore(savedStateHandle)
    private val groupContributionRetryKeys = GroupPaymentContributionRetryStore(savedStateHandle)

    /** Current authenticated public ID; used only to bind server claims to this direct chat. */
    val currentAccountId: String?
        get() = walletRepo.currentAccountId

    /** This account's live call, if any, for the header actions, call-log rows and live banner. */
    internal val activeCallPresence: StateFlow<ActiveCallPresence?> = activeCallState.presence

    private val chatId: String = savedStateHandle.get<String>("chatId")
        ?.trim()
        .orEmpty()

    private val pendingServerSchedules = PendingServerScheduleStore(savedStateHandle) {
        sessions?.current()?.fence()
    }
    private val mutableServerScheduleApproval = MutableStateFlow(
        pendingServerSchedules.restore(chatId, clock.millis())?.preview(),
    )
    internal val serverScheduleApproval = mutableServerScheduleApproval.asStateFlow()

    /** Exact login that owns every send-later action initiated by this conversation instance. */
    private val scheduledOwner = scheduledSends?.currentOwnerFence()

    private val callTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

    val messagingAvailable = chatRepo.readiness
    val historyAvailable = chatRepo.localHistoryReady

    /**
     * Whether this account's server capability for corrections is on.
     *
     * The long-press Edit item is withdrawn while this is false, so a correction is never written
     * that the send path would have to refuse once it read the conversation roster.
     */
    val messageEditsAvailable = chatRepo.messageEditsAvailable

    /**
     * Whether the library picker may offer multi-select. False withdraws the affordance; the
     * send path still re-proves the whole capability gate before any upload.
     */
    val mediaAlbumsAvailable = chatRepo.mediaAlbumsAvailable
    val chat: StateFlow<ChatPreview?> = chatRepo.chats
        .map { chats -> chats.singleOrNull { it.id == chatId } }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            chatId.takeIf(String::isNotBlank)?.let(chatRepo::chat),
        )

    /** Authenticated roster used to bind group-message reporting to the message's real sender. */
    val groupMembers: StateFlow<List<ChatMember>> = if (chatId.isBlank()) {
        MutableStateFlow<List<ChatMember>>(emptyList()).asStateFlow()
    } else {
        chatRepo.groupMembers(chatId)
    }

    /** Raw encrypted messages for this conversation, before call-log entries are interleaved. */
    private val conversationMessages: StateFlow<List<Message>> = chatId.takeIf(String::isNotBlank)
        ?.let(chatRepo::conversation)
        ?: MutableStateFlow<List<Message>>(emptyList()).asStateFlow()

    /** Whether this build can hold a message back until a time the user picks. */
    val schedulingAvailable: Boolean =
        scheduledSends != null && scheduledDispatcher != null && scheduledOwner != null

    /** This conversation's send-later queue, soonest first. Empty when scheduling is unavailable. */
    private val scheduledForChat: StateFlow<List<ScheduledSend>> =
        if (chatId.isBlank() || scheduledSends == null || scheduledOwner == null) {
            MutableStateFlow<List<ScheduledSend>>(emptyList()).asStateFlow()
        } else {
            scheduledSends.items
                .map { items ->
                    items.takeIf { scheduledSends.isCurrentOwner(scheduledOwner) }
                        ?.filter { it.conversationId == chatId }
                        .orEmpty()
                }
                .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
        }

    /** Messages plus this conversation's call-log entries, ordered together like a WhatsApp thread. */
    val messages: StateFlow<List<Message>> = if (chatId.isBlank()) {
        MutableStateFlow<List<Message>>(emptyList()).asStateFlow()
    } else {
        combine(
            conversationMessages,
            callRepo.calls,
            chat,
            scheduledForChat,
        ) { msgs, calls, currentChat, scheduled ->
            // Scheduled entries always sit at the foot of the thread, whatever hour they are due:
            // they are what is still to come, and putting them in date order among things that have
            // already happened would read as history that somehow has not happened yet.
            mergeCallLog(msgs, calls, currentChat) + scheduled.map(::toScheduledMessage)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, conversationMessages.value)
    }

    /**
     * One queued intent as the thread renders it.
     *
     * A scheduled payment request shows the money it will ask for, but carries no reference id —
     * there is no server-side request yet, and there must not be one until the send actually runs.
     */
    private fun toScheduledMessage(item: ScheduledSend): Message = Message(
        id = SCHEDULED_MESSAGE_ID_PREFIX + item.id,
        text = if (item.kind == ScheduledSendKind.TEXT) item.text else "",
        time = callTimeFormatter.format(Instant.ofEpochMilli(item.scheduledAtEpochMillis)),
        fromMe = true,
        state = when (item.state) {
            ScheduledSendState.UNCONFIRMED -> DeliveryState.UNCONFIRMED
            ScheduledSendState.WAITING, ScheduledSendState.SENDING -> DeliveryState.SCHEDULED
        },
        kind = when (item.kind) {
            ScheduledSendKind.TEXT -> MessageKind.TEXT
            ScheduledSendKind.PAYMENT_REQUEST -> MessageKind.PAYMENT_REQUEST
        },
        amountMinor = item.amountMinor,
        paymentNote = item.note,
        sortEpochMillis = item.scheduledAtEpochMillis,
        scheduledAtEpochMillis = item.scheduledAtEpochMillis,
    )

    private fun mergeCallLog(
        messages: List<Message>,
        calls: List<CallEntry>,
        currentChat: ChatPreview?,
    ): List<Message> {
        val peerUserId = currentChat?.peerUserId
        val relevant = calls.filter { call ->
            call.conversationId == chatId ||
                (peerUserId != null && call.participantUserIds.contains(peerUserId))
        }
        if (relevant.isEmpty()) return messages
        val callMessages = relevant.map { call ->
            Message(
                id = "call:${call.id}",
                text = "",
                time = if (call.startedAtEpochMillis > 0) {
                    callTimeFormatter.format(Instant.ofEpochMilli(call.startedAtEpochMillis))
                } else {
                    call.time
                },
                fromMe = call.direction == CallDirection.OUTGOING,
                kind = MessageKind.CALL,
                sortEpochMillis = call.startedAtEpochMillis,
                callDirection = call.direction,
                callVideo = call.video,
                callDurationSeconds = call.durationSeconds,
            )
        }
        // sortedBy is stable, so messages keep their authenticated relative order and calls slot in
        // by start time.
        return (messages + callMessages).sortedBy { it.sortEpochMillis }
    }

    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()

    private val mutableSending = MutableStateFlow(false)
    val sending = mutableSending.asStateFlow()

    /** An in-chat request the authoritative wallet says cannot currently be covered. */
    private val mutableTopUpRequired = MutableStateFlow<TopUpRequirement?>(null)
    val topUpRequired = mutableTopUpRequired.asStateFlow()

    private val mutableRetryingMessageId = MutableStateFlow<String?>(null)
    val retryingMessageId = mutableRetryingMessageId.asStateFlow()

    /**
     * The message the next send will answer, chosen by swiping a bubble.
     *
     * Held here rather than passed down through every send signature because that is what it is:
     * part of the composer's state, like the draft text, and the same choice applies whether the
     * answer turns out to be typed, photographed or spoken.
     */
    private val mutableReplyTarget = MutableStateFlow<Message?>(null)
    val replyTarget = mutableReplyTarget.asStateFlow()

    /** Answers [message], if it is something the other end can still resolve back to a bubble. */
    fun beginReply(message: Message) {
        if (!message.acceptsReplies) return
        mutableEditTarget.value = null
        mutableReplyTarget.value = message
    }

    fun cancelReply() {
        mutableReplyTarget.value = null
    }

    /**
     * The message whose wording is being corrected, chosen from its long-press menu.
     *
     * Editing is a mode of the composer rather than a dialog of its own: a correction is written
     * with the same keyboard, and has to clear the same length and reserved-prefix rules, as the
     * message it replaces. Only the bar above the composer differs, and it says which message is
     * about to change.
     */
    private val mutableEditTarget = MutableStateFlow<Message?>(null)
    val editTarget = mutableEditTarget.asStateFlow()

    /**
     * What the delivery-details sheet is showing, or null while it is closed.
     *
     * Failure is a state of its own rather than an empty list, because "nobody has read this yet"
     * and "we could not ask" look identical on screen and mean opposite things.
     */
    private val mutableMessageInfo = MutableStateFlow<MessageInfoState?>(null)
    val messageInfo = mutableMessageInfo.asStateFlow()
    private var messageInfoJob: Job? = null

    /** Kept so the sheet's own "Try again" does not need the bubble it was opened from. */
    private var messageInfoTarget: Message? = null

    /** Asks what became of [message], for its author alone. */
    fun openMessageInfo(message: Message) {
        if (!message.acceptsDeliveryInfo) return
        messageInfoJob?.cancel()
        messageInfoTarget = message
        mutableMessageInfo.value = MessageInfoState.Loading
        messageInfoJob = viewModelScope.launch {
            val state = try {
                MessageInfoState.Loaded(chatRepo.messageDeliveryInfo(chatId, message.id))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                MessageInfoState.Failed(
                    if (error.isKitConnectivityError()) {
                        "Kit could not reach the network to check this message."
                    } else {
                        "Kit could not load the details for this message."
                    },
                )
            }
            mutableMessageInfo.value = state
        }
    }

    fun retryMessageInfo() {
        messageInfoTarget?.let(::openMessageInfo)
    }

    fun closeMessageInfo() {
        messageInfoJob?.cancel()
        messageInfoJob = null
        messageInfoTarget = null
        mutableMessageInfo.value = null
    }

    /** Corrects [message], while its fifteen minutes are still running. */
    fun beginEdit(message: Message) {
        if (!messageEditsAvailable.value) return
        if (!message.acceptsEdits(clock.millis())) return
        mutableReplyTarget.value = null
        mutableEditTarget.value = message
    }

    fun cancelEdit() {
        mutableEditTarget.value = null
    }

    /**
     * Takes the pending answer, leaving none behind.
     *
     * Read once per send and cleared in the same step, so a send that fails does not silently
     * re-answer the same message when the next one goes out.
     */
    private fun consumeReplyTarget(): String? {
        val target = mutableReplyTarget.value ?: return null
        mutableReplyTarget.value = null
        return target.id
    }

    /**
     * Attachments this conversation has already opened, as files rather than bytes.
     *
     * The bytes themselves live in [com.kit.wallet.data.messaging.SecureMediaCache], which is
     * bounded on disk and cleared at sign-out. What is held here is only the handle, so a window
     * of open attachments costs a few hundred bytes of heap instead of tens of megabytes, and a
     * 200 MB video is something the player can seek rather than something the heap must survive.
     */
    private val mutableMediaFiles = MutableStateFlow<Map<String, SecureMediaFile>>(emptyMap())
    val mediaFiles = mutableMediaFiles.asStateFlow()
    private val mediaCache = LinkedHashMap<String, SecureMediaFile>()

    private val mutableMediaLoading = MutableStateFlow<Set<String>>(emptySet())
    val mediaLoading = mutableMediaLoading.asStateFlow()

    private val mutableMediaErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    val mediaErrors = mutableMediaErrors.asStateFlow()

    private val mutableConversationVisible = MutableStateFlow(false)
    private var foregroundSyncJob: Job? = null
    private var idleTimerJob: Job? = null

    private sealed interface MediaPreloadTarget {
        val key: String
        val mediaDescriptor: String
        val fromCurrentUser: Boolean

        data class Single(
            override val key: String,
            override val mediaDescriptor: String,
            override val fromCurrentUser: Boolean,
        ) : MediaPreloadTarget

        data class AlbumItem(
            override val key: String,
            override val mediaDescriptor: String,
            val attachmentId: String,
            val messageId: String,
            override val fromCurrentUser: Boolean,
        ) : MediaPreloadTarget
    }

    /**
     * Live state of the held Kit → Kit transfers this account is a party to, keyed by claim id.
     *
     * The chat message records that a transfer happened; this records what has become of it. A
     * card built from here is never stale because a follow-up message was lost.
     */
    private val mutableTransferClaims = MutableStateFlow<Map<String, TransferClaim>>(emptyMap())
    val transferClaims = mutableTransferClaims.asStateFlow()

    /**
     * Live state of the group payments this thread mentions, keyed by lowercased payment id.
     *
     * The announcement in the thread is a label. What a member may do, and what they are owed, is
     * only ever read from here — and a card whose payment has not loaded shows a spinner rather
     * than buttons drawn from a claim nobody verified.
     */
    private val mutableGroupPayments = MutableStateFlow<Map<String, GroupPaymentSummary>>(emptyMap())
    val groupPayments = mutableGroupPayments.asStateFlow()

    /** API authority for collaborative request cards; descriptors never populate this map. */
    private val mutableGroupPaymentRequests =
        MutableStateFlow<Map<String, GroupPaymentRequestDto>>(emptyMap())
    val groupPaymentRequests = mutableGroupPaymentRequests.asStateFlow()

    /** Exact rows named by timeline events, including rows older than the embedded newest 50. */
    private val mutableGroupPaymentRequestContributions =
        MutableStateFlow<Map<String, GroupPaymentRequestContributionDto>>(emptyMap())
    val groupPaymentRequestContributions = mutableGroupPaymentRequestContributions.asStateFlow()

    private var groupPaymentRequestsEnabled = false
    private var scheduledChatPaymentsEnabled = false
    private var scheduledGroupPaymentsEnabled = false

    private val mutableServerScheduledDirect = MutableStateFlow<List<ScheduledPaymentDto>>(emptyList())
    val serverScheduledDirect = mutableServerScheduledDirect.asStateFlow()
    private val mutableServerScheduledGroup =
        MutableStateFlow<List<ScheduledGroupPaymentDto>>(emptyList())
    val serverScheduledGroup = mutableServerScheduledGroup.asStateFlow()
    private val mutableServerSchedulesHaveMore = MutableStateFlow(false)
    val serverSchedulesHaveMore = mutableServerSchedulesHaveMore.asStateFlow()

    /** One logical cancellation keeps one key until the server confirms its terminal row. */
    private val serverScheduleCancellationKeys = mutableMapOf<String, String>()
    private val groupRequestCancellationKeys = mutableMapOf<String, String>()

    fun setServerSchedulingEnabled(direct: Boolean, group: Boolean) {
        val changed = direct != scheduledChatPaymentsEnabled || group != scheduledGroupPaymentsEnabled
        scheduledChatPaymentsEnabled = direct
        scheduledGroupPaymentsEnabled = group
        if (changed && mutableConversationVisible.value) {
            viewModelScope.launch { refreshServerSchedules(replace = true) }
        }
    }

    private data class UnsharedGroupPaymentRequest(
        val chatId: String,
        val destinationWalletId: String,
        val amountMinor: Long,
        val currencyScale: Int,
        val note: String?,
        val request: GroupPaymentRequestDto,
    )

    private var unsharedGroupPaymentRequest: UnsharedGroupPaymentRequest? = null

    fun setGroupPaymentRequestsEnabled(enabled: Boolean) {
        if (groupPaymentRequestsEnabled == enabled) return
        groupPaymentRequestsEnabled = enabled
        if (enabled && mutableConversationVisible.value) {
            viewModelScope.launch { refreshGroupPaymentRequests() }
        }
    }

    /** Expiries this session has already written into the conversation, to avoid a second line. */
    private val announcedExpiries = mutableSetOf<String>()

    // Encrypted composer draft restored once per conversation entry; consumed by the screen.
    private val mutableRestoredDraft = MutableStateFlow<String?>(null)
    val restoredDraft = mutableRestoredDraft.asStateFlow()
    private data class PendingComposerDraftWrite(val revision: Long, val text: String)
    private val pendingComposerDraftWrite =
        MutableStateFlow<PendingComposerDraftWrite?>(null)
    private val composerEditedSinceEntry = AtomicBoolean(false)
    private var composerDraftRevision = 0L

    init {
        sessions?.let { sessionStore ->
            viewModelScope.launch {
                sessionStore.session.collect {
                    mutableServerScheduleApproval.value =
                        pendingServerSchedules.current(chatId)?.preview()
                }
            }
        }
        if (chatId.isNotBlank()) {
            viewModelScope.launch {
                // The encrypted store is deliberately unavailable until this account's local
                // history has been opened. Reading before that point looks exactly like "no
                // draft" and permanently loses the only restoration attempt for this entry.
                historyAvailable.first { it }
                val restored = runCatching { chatRepo.composerDraft(chatId) }.getOrNull()
                // A person can start typing (and can type, then clear) while the store opens.
                // Never let the late disk read resurrect older text over that explicit choice.
                if (!composerEditedSinceEntry.get()) {
                    mutableRestoredDraft.value = restored
                }
            }
            viewModelScope.launch {
                combine(historyAvailable, pendingComposerDraftWrite) { ready, pending ->
                    pending.takeIf { ready }
                }.collect { pending ->
                    pending ?: return@collect
                    var retryDelayMillis = COMPOSER_DRAFT_RETRY_INITIAL_MILLIS
                    while (
                        historyAvailable.value &&
                        pendingComposerDraftWrite.value == pending
                    ) {
                        val saved = try {
                            if (pending.text.isBlank()) {
                                chatRepo.clearComposerDraft(chatId)
                            } else {
                                chatRepo.saveComposerDraft(chatId, pending.text)
                            }
                            true
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            false
                        }
                        if (saved) {
                            pendingComposerDraftWrite.compareAndSet(pending, null)
                            break
                        }
                        // The latest text remains owned by this conversation-scoped ViewModel.
                        // Retry autonomously while its encrypted store stays available, but cap
                        // the interval so a persistent keystore failure neither spins nor grows.
                        delay(retryDelayMillis)
                        retryDelayMillis = (retryDelayMillis * 2)
                            .coerceAtMost(COMPOSER_DRAFT_RETRY_MAX_MILLIS)
                    }
                }
            }
            scheduledSends?.let { queue ->
                // Idempotent: the repository already re-reads the queue on every messaging
                // activation. This is what makes the thread show its scheduled entries even when
                // the conversation is opened before that has happened.
                viewModelScope.launch { runCatching { queue.load() } }
            }
            viewModelScope.launch {
                combine(
                    mutableConversationVisible,
                    historyAvailable,
                    chat,
                    conversationMessages,
                ) { visible, historyReady, selectedChat, projected ->
                    if (visible && historyReady && selectedChat != null) {
                        selectedChat to mediaPreloadTargets(projected)
                    } else {
                        null
                    }
                }.collect { preload ->
                    val (selectedChat, targets) = preload ?: return@collect
                    for (target in targets) {
                        if (
                            !mutableConversationVisible.value ||
                            !historyAvailable.value ||
                            chat.value?.id != selectedChat.id
                        ) break
                        if (!claimMedia(target.key)) continue
                        when (target) {
                            is MediaPreloadTarget.Single -> hydrateMedia(
                                key = target.key,
                                fallbackError = "The secure photo could not be opened",
                                reportFailure = false,
                                shouldStart = {
                                    isCurrentPreloadTarget(selectedChat.id, target)
                                },
                            ) {
                                chatRepo.openImageMessage(
                                    selectedChat.id,
                                    target.mediaDescriptor,
                                    messageId = target.key,
                                    fromCurrentUser = target.fromCurrentUser,
                                )
                            }

                            is MediaPreloadTarget.AlbumItem -> hydrateMedia(
                                key = target.key,
                                fallbackError = "The secure attachment could not be opened",
                                reportFailure = false,
                                shouldStart = {
                                    isCurrentPreloadTarget(selectedChat.id, target)
                                },
                            ) {
                                chatRepo.openAlbumItemMessage(
                                    selectedChat.id,
                                    target.mediaDescriptor,
                                    target.attachmentId,
                                    messageId = target.messageId,
                                    fromCurrentUser = target.fromCurrentUser,
                                )
                            }
                        }
                    }
                }
            }
            viewModelScope.launch {
                combine(
                    mutableConversationVisible,
                    messagingAvailable,
                    conversationMessages,
                ) { visible, ready, projected ->
                    visible && ready && projected.any {
                        !it.fromMe && it.state == DeliveryState.DELIVERED
                    }
                }.collectLatest { shouldMarkRead ->
                    if (shouldMarkRead) attemptMarkConversationRead()
                }
            }
            // In-conversation sounds while the chat is open:
            //  - a new incoming message plays the coin tone for a completed payment, else the
            //    knock tone;
            //  - an outgoing message plays the water-drop tone when it reaches its first tick.
            viewModelScope.launch {
                val soundBaseline = ConversationSoundBaseline()
                combine(messagingAvailable, conversationMessages) { ready, projected ->
                    ready to projected
                }.collect { (ready, projected) ->
                    val visible = mutableConversationVisible.value
                    val decision = soundBaseline.observe(ready, projected)
                    if (visible) {
                        when {
                            decision.playPaymentReceived -> messageSounds.playPaymentReceived()
                            decision.playReceived -> messageSounds.playReceived()
                        }
                        if (decision.playSent) messageSounds.playSent()
                    }
                }
            }
        }
    }

    /**
     * Binds this conversation to the realtime transport while it is on screen, and runs the two
     * cadences that survive the socket.
     *
     * Message delivery is no longer polled. While the socket is `Live` the server nudges and the
     * sync engine pulls, so [foregroundSyncJob] sits idle on a `null` interval; when the socket is
     * down it falls back to the coordinator's ladder. The 45-second [idleTimerJob] is separate and
     * unconditional, because the loop this replaced was the *only* retry path for a read receipt
     * whose POST failed without changing local state, and for held transfers that settle or expire
     * with no push behind either.
     */
    fun setConversationVisible(visible: Boolean) {
        mutableConversationVisible.value = visible
        if (chatId.isBlank()) return
        if (!visible) {
            realtime.stopObservingConversation(chatId)
            typingSignaller.onConversationClosed(chatId)
            foregroundSyncJob?.cancel()
            foregroundSyncJob = null
            idleTimerJob?.cancel()
            idleTimerJob = null
            return
        }
        // Presence and typing for this conversation, for exactly as long as it is looked at.
        realtime.observeConversation(chatId)
        // Refresh the call log so recent calls appear inline in the conversation.
        viewModelScope.launch { runCatching { callRepo.refresh() } }
        viewModelScope.launch { refreshTransferClaims() }
        viewModelScope.launch { refreshGroupPayments() }
        viewModelScope.launch { refreshGroupPaymentRequests() }
        viewModelScope.launch { refreshServerSchedules(replace = true) }
        dispatchScheduledDue()
        if (foregroundSyncJob?.isActive != true) {
            foregroundSyncJob = viewModelScope.launch { runForegroundSync() }
        }
        if (idleTimerJob?.isActive != true) {
            idleTimerJob = viewModelScope.launch { runIdleRefresh() }
        }
    }

    /**
     * One catch-up on entry, then whatever cadence the transport says it cannot cover itself.
     *
     * `null` means the socket is carrying this conversation and there is nothing periodic to do;
     * `collectLatest` cancels the waiting loop the moment that becomes true, and starts a fresh one
     * on the new interval when it stops being true.
     */
    private suspend fun runForegroundSync() {
        synchronizeOnce()
        realtime.foregroundSyncIntervalMillis.collectLatest { intervalMillis ->
            if (intervalMillis == null) return@collectLatest
            while (true) {
                delay(intervalMillis)
                synchronizeOnce()
            }
        }
    }

    private suspend fun synchronizeOnce() {
        if (!messagingAvailable.value) return
        try {
            chatRepo.synchronizeConversation(chatId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // FCM, WorkManager and the next realtime nudge all remain recovery paths.
        }
    }

    private suspend fun runIdleRefresh() {
        while (true) {
            delay(IDLE_REFRESH_INTERVAL_MILLIS)
            // Projection changes attempt the receipt immediately through the collector in `init`.
            // If that POST failed without changing local state, this is what tries it again.
            attemptMarkConversationRead()
            // Held transfers settle from the other side, and expire with nobody acting at all.
            // There is no push for either.
            refreshTransferClaims()
            // Nor for a group payment: other members take their shares without telling this device.
            refreshGroupPayments()
            refreshGroupPaymentRequests()
            refreshServerSchedules(replace = true)
            // A scheduled send whose minute passes while the chat is open should go out then, not
            // whenever the system next feels like running a worker.
            dispatchScheduledDue()
        }
    }

    /** Every composer keystroke. The debounce, throttle and stop-on-switch all live downstream. */
    fun onComposerChanged(text: String) {
        if (chatId.isBlank()) return
        composerEditedSinceEntry.set(true)
        typingSignaller.onComposerChanged(chatId, text)
    }

    fun clearError() {
        mutableError.value = null
    }

    fun clearTopUpRequired() {
        mutableTopUpRequired.value = null
    }

    /**
     * Hands one exact server-owned event to the encrypted immediate outbox.
     *
     * Production always supplies [sessions], so this path is owner-pinned and returns only after
     * the deterministic intent is durable. The fallback keeps isolated repository test doubles
     * source-compatible; it is not used by the injected application graph.
     */
    private suspend fun capturePaymentEvent(
        owner: com.kit.wallet.data.session.SessionFence?,
        conversationId: String,
        event: KitPaymentMessage,
    ): Boolean {
        var durablyCommitted = false
        return try {
            val descriptor = event.encode()
            check(KitPaymentMessage.parse(descriptor) == event) {
                "Kit Pay could not validate this payment event"
            }
            if (owner == null) {
                chatRepo.sendPaymentEvent(conversationId, descriptor) { durablyCommitted = true }
            } else {
                chatRepo.capturePaymentEventForOwner(
                    owner = owner,
                    chatId = conversationId,
                    descriptor = descriptor,
                    clientMessageId = event.deterministicMessageId(),
                )
            }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            durablyCommitted
        }
    }

    private suspend fun captureGroupPaymentEvent(
        owner: com.kit.wallet.data.session.SessionFence?,
        conversationId: String,
        event: KitGroupPaymentMessage,
        clientMessageId: String,
    ): Boolean {
        var durablyCommitted = false
        return try {
            val descriptor = event.encode()
            check(KitGroupPaymentMessage.parse(descriptor) == event) {
                "Kit Pay could not validate this group payment event"
            }
            if (owner == null) {
                chatRepo.sendGroupPaymentEvent(conversationId, descriptor) {
                    durablyCommitted = true
                }
            } else {
                chatRepo.captureGroupPaymentEventForOwner(
                    owner = owner,
                    chatId = conversationId,
                    descriptor = descriptor,
                    clientMessageId = clientMessageId,
                )
            }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            durablyCommitted
        }
    }

    private suspend fun captureGroupPaymentRequestEvent(
        owner: com.kit.wallet.data.session.SessionFence?,
        conversationId: String,
        event: KitGroupPaymentRequestMessage,
    ): Boolean {
        var durablyCommitted = false
        return try {
            val descriptor = event.encode()
            check(KitGroupPaymentRequestMessage.parse(descriptor) == event) {
                "Kit Pay could not validate this group payment request event"
            }
            if (owner == null) {
                chatRepo.sendGroupPaymentRequestEvent(conversationId, descriptor) {
                    durablyCommitted = true
                }
            } else {
                chatRepo.captureGroupPaymentRequestEventForOwner(
                    owner = owner,
                    chatId = conversationId,
                    descriptor = descriptor,
                    clientMessageId = event.deterministicMessageId(),
                )
            }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            durablyCommitted
        }
    }

    private suspend fun stagePaymentEvent(
        owner: com.kit.wallet.data.session.SessionFence?,
        conversationId: String,
        event: KitPaymentMessage,
    ): String? = if (owner != null && pendingFinancialEvents != null) {
        pendingFinancialEvents.stagePaymentEvent(owner, conversationId, event)
    } else {
        null
    }

    private suspend fun stageGroupPaymentEvent(
        owner: com.kit.wallet.data.session.SessionFence?,
        conversationId: String,
        event: KitGroupPaymentMessage,
        clientMessageId: String,
    ): String? = if (owner != null && pendingFinancialEvents != null) {
        pendingFinancialEvents.stageGroupPaymentEvent(
            owner,
            conversationId,
            event,
            clientMessageId,
        )
    } else {
        null
    }

    private suspend fun stageGroupPaymentRequestEvent(
        owner: com.kit.wallet.data.session.SessionFence?,
        conversationId: String,
        event: KitGroupPaymentRequestMessage,
    ): String? = if (owner != null && pendingFinancialEvents != null) {
        pendingFinancialEvents.stageGroupPaymentRequestEvent(owner, conversationId, event)
    } else {
        null
    }

    private suspend fun commitStagedEvent(
        owner: com.kit.wallet.data.session.SessionFence?,
        clientMessageId: String?,
        fallback: suspend () -> Boolean,
    ): Boolean = if (owner != null && clientMessageId != null && pendingFinancialEvents != null) {
        try {
            pendingFinancialEvents.commit(owner, clientMessageId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    } else {
        fallback()
    }

    private suspend fun releaseStagedEvent(
        owner: com.kit.wallet.data.session.SessionFence?,
        clientMessageId: String?,
    ) {
        if (owner != null && clientMessageId != null && pendingFinancialEvents != null) {
            pendingFinancialEvents.releaseForRecovery(owner, clientMessageId)
        }
    }

    /** Uses the authenticated request descriptor and current wallet row for the preflight offer. */
    fun shortfallForPaymentRequest(message: Message): TopUpRequirement? =
        shortfallForPaymentRequest(message, refreshed = null)

    private fun shortfallForPaymentRequest(
        message: Message,
        refreshed: WalletSyncResult?,
    ): TopUpRequirement? {
        if (message.fromMe) return null
        val descriptor = message.mediaDescriptor?.let(KitPaymentMessage::parse)
            ?.takeIf { it.isRequest }
            ?: return null
        val currencyCode = refreshed?.selectedCurrencyCode ?: walletRepo.walletCurrency.value.code
        val currencyScale = refreshed?.selectedCurrencyScale ?: walletRepo.walletCurrency.value.scale
        if (!descriptor.currencyCode.equals(currencyCode, ignoreCase = true) ||
            descriptor.currencyScale != currencyScale
        ) return null
        return TopUp.requirementFor(
            requiredMinor = descriptor.amountMinor,
            balanceMinor = refreshed?.selectedAvailableBalanceMinor ?: walletRepo.balanceMinor.value,
            currencyCode = descriptor.currencyCode,
            currencyScale = descriptor.currencyScale,
        )
    }

    fun reportMediaSelectionError(message: String) {
        mutableError.value = message
    }

    /** The screen seeded its composer from the restored draft (or chose not to). */
    fun consumeRestoredDraft() {
        mutableRestoredDraft.value = null
    }

    /** Best-effort encrypted draft persistence; blank text clears the stored draft. */
    fun persistDraft(text: String) {
        if (chatId.isBlank()) return
        pendingComposerDraftWrite.value = PendingComposerDraftWrite(
            revision = ++composerDraftRevision,
            text = text,
        )
    }

    private suspend fun attemptMarkConversationRead() {
        if (
            !mutableConversationVisible.value ||
            !messagingAvailable.value ||
            conversationMessages.value.none { !it.fromMe && it.state == DeliveryState.DELIVERED }
        ) return
        try {
            chatRepo.markConversationRead(chatId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Durable unread state is the retry signal. A projection/readiness emission or the
            // 45-second idle timer will try the receipt again.
        }
    }

    fun send(text: String, onSent: () -> Unit = {}) {
        val selectedChat = chat.value ?: return
        val normalized = text.trim()
        if (!historyAvailable.value || normalized.isBlank()) return
        if (!KitUserAuthoredTextPolicy.allows(normalized)) {
            mutableError.value = "Messages cannot start with one of Kit Pay's reserved prefixes"
            return
        }
        val replyToMessageId = consumeReplyTarget()
        viewModelScope.launch {
            val composerReleased = AtomicBoolean(false)
            fun releaseComposer() {
                if (composerReleased.compareAndSet(false, true)) {
                    onSent()
                    // Durably committed, and this runs before the network POST: the peer must never
                    // see "typing…" still attached to a message that has already reached them.
                    typingSignaller.onMessageCommitted(selectedChat.id)
                    // The message is durably owned by the outbox; its draft copy is obsolete.
                    persistDraft("")
                }
            }
            val failure = try {
                chatRepo.sendMessage(
                    chatId = selectedChat.id,
                    text = normalized,
                    onDurablyCommitted = { releaseComposer() },
                    replyToMessageId = replyToMessageId,
                )
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                error
            }
            val durablyHandled = failure == null || composerReleased.get()

            // A successful return is a final fallback for implementations whose network round-trip
            // completed before they notified the durable boundary.
            if (durablyHandled) releaseComposer()

            failure?.let { error ->
                val connectivity = error.isKitConnectivityError()
                // Stay silent like WhatsApp only when this exact operation committed an encrypted
                // retry. Otherwise keep the composer and show address-free connectivity copy.
                if (!connectivity || !durablyHandled) {
                    mutableError.value = if (connectivity) {
                        KIT_NETWORK_UNAVAILABLE_MESSAGE
                    } else {
                        error.message ?: "Secure messaging is temporarily unavailable"
                    }
                }
            }
        }
    }

    /**
     * Replaces the wording of the message currently being edited.
     *
     * Retyping the same words is treated as changing one's mind about changing one's mind: the
     * mode simply closes, because a correction that corrects nothing is not worth a second bubble
     * in everybody's transcript. The fifteen-minute window is re-checked by the repository and
     * again by the server, so a composer left open past the deadline cannot slip an edit through.
     */
    fun submitEdit(text: String, onEdited: () -> Unit = {}) {
        val selectedChat = chat.value ?: return
        val target = mutableEditTarget.value ?: return
        val normalized = text.trim()
        if (!historyAvailable.value || normalized.isBlank()) return
        // The gate can close between opening the composer and pressing send — a lifecycle blip,
        // a sign-out, a capability that went away. Close the mode rather than enqueue a
        // correction nobody is entitled to send.
        if (!messageEditsAvailable.value) {
            mutableEditTarget.value = null
            return
        }
        if (normalized == target.text) {
            mutableEditTarget.value = null
            onEdited()
            return
        }
        if (!KitUserAuthoredTextPolicy.allows(normalized)) {
            mutableError.value = "Messages cannot start with one of Kit Pay's reserved prefixes"
            return
        }
        if (!KitEditMessage.isAcceptableBody(normalized)) {
            mutableError.value = "That wording is too long to send"
            return
        }
        viewModelScope.launch {
            try {
                chatRepo.editMessage(selectedChat.id, target.id, normalized)
                mutableEditTarget.value = null
                onEdited()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Unlike a first send, an edit that only failed on connectivity is already durably
                // queued, so the mode closes and the outbox delivers it: keeping the composer open
                // would invite the same correction to be written a second time.
                if (error.isKitConnectivityError()) {
                    mutableEditTarget.value = null
                    onEdited()
                } else {
                    mutableError.value = error.message ?: "That message could not be edited"
                }
            }
        }
    }

    /**
     * Holds [text] back until [atEpochMillis] instead of sending it now.
     *
     * Nothing is encrypted here. A scheduled message is stored as an intent and becomes ciphertext
     * once, at the moment it is actually sent, so it goes out under the roster that is current then
     * rather than the one that happened to exist when it was written.
     */
    fun scheduleSend(text: String, atEpochMillis: Long, onScheduled: () -> Unit = {}) {
        val queue = scheduledSends ?: return
        val owner = scheduledOwner ?: return
        val selectedChat = chat.value ?: return
        val normalized = text.trim()
        if (normalized.isBlank()) return
        if (!KitUserAuthoredTextPolicy.allows(normalized)) {
            mutableError.value = "Messages cannot start with one of Kit Pay's reserved prefixes"
            return
        }
        if (normalized.length > ScheduledSend.MAX_TEXT_LENGTH) {
            mutableError.value = "That message is too long to schedule"
            return
        }
        val now = clock.millis()
        ScheduledSend.schedulingError(atEpochMillis, now)?.let { problem ->
            mutableError.value = problem
            return
        }
        viewModelScope.launch {
            try {
                queue.putForOwner(
                    owner,
                    ScheduledSend(
                        id = newScheduledSendId(),
                        conversationId = selectedChat.id,
                        kind = ScheduledSendKind.TEXT,
                        scheduledAtEpochMillis = atEpochMillis,
                        createdAtEpochMillis = now,
                        text = normalized,
                    ),
                )
                onScheduled()
                // The composer's copy has been taken over by the queue; leaving the draft behind
                // would restore the same text on the next visit, next to the scheduled bubble.
                runCatching { chatRepo.clearComposerDraft(selectedChat.id) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableError.value = error.message ?: "That message could not be scheduled"
            }
        }
    }

    /**
     * Queues a payment request for [atEpochMillis] without creating anything on the server yet.
     *
     * A request that does not exist until it is sent cannot be paid, declined or chased early, and
     * cannot leave a stranded ask behind if the user cancels the schedule.
     */
    fun schedulePaymentRequest(
        amountMinor: Long,
        note: String?,
        atEpochMillis: Long,
        onScheduled: () -> Unit = {},
    ) {
        val queue = scheduledSends ?: return
        val owner = scheduledOwner ?: return
        val selectedChat = chat.value ?: return
        if (amountMinor <= 0) {
            mutableError.value = "Enter an amount to request"
            return
        }
        if (selectedChat.peerUserId == null) {
            mutableError.value = "This conversation is not linked to a Kit Pay account"
            return
        }
        val now = clock.millis()
        ScheduledSend.schedulingError(atEpochMillis, now)?.let { problem ->
            mutableError.value = problem
            return
        }
        viewModelScope.launch {
            try {
                queue.putForOwner(
                    owner,
                    ScheduledSend(
                        id = newScheduledSendId(),
                        conversationId = selectedChat.id,
                        kind = ScheduledSendKind.PAYMENT_REQUEST,
                        scheduledAtEpochMillis = atEpochMillis,
                        createdAtEpochMillis = now,
                        amountMinor = amountMinor,
                        note = note?.trim()?.takeIf(String::isNotBlank),
                    ),
                )
                onScheduled()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableError.value = error.message ?: "That request could not be scheduled"
            }
        }
    }

    /** Sends a scheduled entry immediately, ahead of the time it was given. */
    fun sendScheduledNow(message: Message) {
        val dispatcher = scheduledDispatcher ?: return
        val owner = scheduledOwner ?: return
        val id = scheduledSendIdOf(message) ?: return
        viewModelScope.launch {
            try {
                dispatcher.sendNowForOwner(owner, id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!error.isKitConnectivityError()) {
                    mutableError.value = error.message ?: "That message could not be sent"
                }
            }
        }
    }

    /** Moves a scheduled entry to a new time, keeping its identity and its place in the queue. */
    fun rescheduleSend(message: Message, atEpochMillis: Long) {
        val queue = scheduledSends ?: return
        val owner = scheduledOwner ?: return
        val id = scheduledSendIdOf(message) ?: return
        ScheduledSend.schedulingError(atEpochMillis, clock.millis())?.let { problem ->
            mutableError.value = problem
            return
        }
        viewModelScope.launch {
            val current = queue.itemsForOwner(owner).firstOrNull { it.id == id } ?: return@launch
            // A live claim means a dispatch is sending this right now. Moving its time would not
            // recall it, so the schedule is left alone rather than made to look as if it had.
            if (current.state == ScheduledSendState.SENDING) {
                mutableError.value = "That message is being sent right now"
                return@launch
            }
            try {
                queue.compareAndSetForOwner(
                    owner,
                    current,
                    current.copy(
                        scheduledAtEpochMillis = atEpochMillis,
                        attempts = 0,
                        lastAttemptAtEpochMillis = 0,
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableError.value = error.message ?: "That schedule could not be changed"
            }
        }
    }

    /** Discards a scheduled entry. Nothing has been sent, so nothing is recalled. */
    fun cancelScheduledSend(message: Message) {
        val queue = scheduledSends ?: return
        val owner = scheduledOwner ?: return
        val id = scheduledSendIdOf(message) ?: return
        viewModelScope.launch {
            val current = queue.itemsForOwner(owner).firstOrNull { it.id == id } ?: return@launch
            if (current.state == ScheduledSendState.SENDING) {
                mutableError.value = "That message is being sent right now"
                return@launch
            }
            try {
                if (!queue.removeIfUnchangedForOwner(owner, current)) {
                    mutableError.value = "That message changed before it could be cancelled"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableError.value = error.message ?: "That schedule could not be cancelled"
            }
        }
    }

    /**
     * Sends anything already overdue, right now, while the app is in front of the user.
     *
     * The background wake is what makes scheduling work at all; this is what makes it prompt. A
     * device that was asleep, offline or force-stopped at the chosen minute catches up the moment
     * somebody opens the conversation rather than waiting for the system to run the worker.
     */
    private fun dispatchScheduledDue() {
        val dispatcher = scheduledDispatcher ?: return
        val owner = scheduledOwner ?: return
        viewModelScope.launch {
            runCatching { dispatcher.dispatchDueForOwner(owner) }
        }
    }

    /** The queue id behind a scheduled bubble, or null when this is an ordinary message. */
    private fun scheduledSendIdOf(message: Message): String? = message.id
        .takeIf { it.startsWith(SCHEDULED_MESSAGE_ID_PREFIX) }
        ?.removePrefix(SCHEDULED_MESSAGE_ID_PREFIX)

    /**
     * Adds [emoji] to a message, or takes it back off when this account already reacted with it.
     *
     * The repository publishes the outgoing reaction to the projection before the network round
     * trip, so the chip appears immediately and the durable outbox owns delivery from there. A
     * failure that is only connectivity therefore stays silent: the reaction is already queued.
     */
    fun toggleReaction(messageId: String, emoji: String) {
        val selectedChat = chat.value ?: return
        if (!historyAvailable.value) return
        viewModelScope.launch {
            try {
                chatRepo.toggleReaction(selectedChat.id, messageId, emoji)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!error.isKitConnectivityError()) {
                    mutableError.value = error.message ?: "That reaction could not be sent"
                }
            }
        }
    }

    fun retry(message: Message, onRetried: () -> Unit = {}) {
        val selectedChat = chat.value ?: return
        if (
            !message.fromMe ||
            message.state !in setOf(DeliveryState.SENDING, DeliveryState.RETRY_REQUIRED)
        ) return
        // A media message retries its authenticated descriptor, not its display caption.
        val normalized = (message.mediaDescriptor ?: message.text).trim()
        if (!historyAvailable.value || normalized.isBlank() || mutableSending.value) return
        launchSend(
            selectedChatId = selectedChat.id,
            normalizedText = normalized,
            retryingMessageId = message.id,
            trustedPaymentEvent = KitPaymentMessage.parse(normalized) != null,
            onSuccess = onRetried,
        )
    }

    /** A server-confirmed request whose encrypted card has not been durably shared yet. */
    private data class UnsharedPaymentRequest(
        val chatId: String,
        val peerUserId: String,
        val amountMinor: Long,
        val note: String?,
        val request: ChatPaymentRequest,
    )

    private var unsharedPaymentRequest: UnsharedPaymentRequest? = null

    /**
     * Creates an idempotent, non-debit backend payment request addressed to the chat peer, then
     * shares it into the conversation as an end-to-end encrypted payment-request descriptor.
     */
    fun sendPaymentRequest(amountMinor: Long, note: String?, onSent: () -> Unit = {}) {
        val selectedChat = chat.value ?: return
        val peerUserId = selectedChat.peerUserId
        val operationOwner = sessions?.current()?.fence()
        if (!historyAvailable.value || mutableSending.value) return
        if (amountMinor <= 0) {
            mutableError.value = "Enter an amount to request"
            return
        }
        if (peerUserId == null) {
            mutableError.value = "This conversation is not linked to a Kit Pay account"
            return
        }
        val normalizedNote = note?.trim()?.takeIf(String::isNotBlank)
        viewModelScope.launch {
            mutableSending.value = true
            mutableError.value = null
            var durablyShared = false
            var recoveryReceipt: com.kit.wallet.data.messaging.FinancialCreationReceipt? = null
            try {
                // A request the server already confirmed for these exact details is reused, so a
                // create-success/share-failure retry never mints a second financial request. The
                // request UUID stays the stable identity of the eventual card, matching iOS.
                if (operationOwner != null && financialCreationReceipts != null) {
                    val source = walletRepo.spendingSourceForOwner(operationOwner)
                    val receipt = financialCreationReceipts.preparePaymentRequest(
                        owner = operationOwner,
                        conversationId = selectedChat.id,
                        destinationWalletId = source.walletId,
                        peerUserId = peerUserId,
                        amountMinor = amountMinor,
                        currencyCode = source.currencyCode,
                        currencyScale = source.currencyScale,
                        note = normalizedNote,
                    )
                    recoveryReceipt = receipt
                    check(receipt.phase == FinancialCreationReceiptPhase.PREPARED) {
                        "This payment request is still being recovered"
                    }
                    financialCreationReceipts.markSubmitted(operationOwner, receipt.id)
                    val created = walletRepo.createChatPaymentRequestForOwner(
                        owner = operationOwner,
                        peerUserId = peerUserId,
                        amountMinor = amountMinor,
                        note = normalizedNote,
                        idempotencyKey = receipt.idempotencyKey,
                    )
                    val settled = withContext(NonCancellable) {
                        financialCreationReceipts.bindPaymentRequest(
                            operationOwner,
                            receipt,
                            created,
                        )
                    }
                    withContext(NonCancellable) {
                        financialCreationReceipts.handoff(operationOwner, settled)
                    }
                    durablyShared = true
                } else {
                    val retained = unsharedPaymentRequest?.takeIf {
                        it.chatId == selectedChat.id && it.peerUserId == peerUserId &&
                            it.amountMinor == amountMinor && it.note == normalizedNote
                    }?.request
                    val created = retained
                        ?: walletRepo.createChatPaymentRequest(peerUserId, amountMinor, normalizedNote)
                            .also { confirmed ->
                                unsharedPaymentRequest = UnsharedPaymentRequest(
                                    chatId = selectedChat.id,
                                    peerUserId = peerUserId,
                                    amountMinor = amountMinor,
                                    note = normalizedNote,
                                    request = confirmed,
                                )
                            }
                    val event = KitPaymentMessage(
                        action = KitPaymentAction.REQUEST,
                        referenceId = created.id,
                        amountMinor = created.amountMinor,
                        currencyCode = created.currencyCode,
                        currencyScale = created.currencyScale,
                        note = created.note
                            ?.takeIf(String::isNotBlank)
                            ?.take(KitPaymentMessage.MAX_NOTE_LENGTH),
                    )
                    durablyShared = withContext(NonCancellable) {
                        capturePaymentEvent(operationOwner, selectedChat.id, event)
                    }
                }
                check(durablyShared) { "The payment request could not be saved to this chat" }
                unsharedPaymentRequest = null
                onSent()
            } catch (cancelled: CancellationException) {
                recoveryReceipt?.let { receipt ->
                    withContext(NonCancellable) {
                        financialCreationReceipts?.discardPrepared(operationOwner!!, receipt.id)
                    }
                }
                throw cancelled
            } catch (error: Exception) {
                recoveryReceipt?.let { receipt ->
                    withContext(NonCancellable) {
                        runCatching {
                            financialCreationReceipts?.discardPrepared(operationOwner!!, receipt.id)
                        }
                    }
                }
                if (durablyShared) {
                    // The encrypted card is committed to the outbox, appears in the conversation
                    // as a pending bubble, and owns replay from here. Close the composer like a
                    // WhatsApp offline send; surfacing an error here would invite a duplicate.
                    unsharedPaymentRequest = null
                    onSent()
                } else {
                    mutableError.value = error.message
                        ?: "The payment request could not be sent"
                }
            } finally {
                mutableSending.value = false
            }
        }
    }

    /** Pays a request received in this conversation, then confirms it in-chat once debited. */
    fun payPaymentRequest(message: Message, paymentPin: String, onPaid: () -> Unit = {}) {
        val selectedChat = chat.value ?: return
        val operationOwner = sessions?.current()?.fence()
        val descriptor = message.mediaDescriptor?.let(KitPaymentMessage::parse) ?: return
        if (
            !historyAvailable.value || mutableSending.value ||
            message.fromMe || !descriptor.isRequest
        ) return
        viewModelScope.launch {
            mutableSending.value = true
            mutableError.value = null
            var stagedId: String? = null
            try {
                val event = descriptor.copy(action = KitPaymentAction.PAID)
                stagedId = stagePaymentEvent(operationOwner, selectedChat.id, event)
                if (operationOwner == null) {
                    walletRepo.payChatPaymentRequest(
                        descriptor.referenceId,
                        descriptor.amountMinor,
                        paymentPin,
                    )
                } else {
                    walletRepo.payChatPaymentRequestForOwner(
                        operationOwner,
                        descriptor.referenceId,
                        descriptor.amountMinor,
                        paymentPin,
                    )
                }
                val receiptQueued = withContext(NonCancellable) {
                    commitStagedEvent(operationOwner, stagedId) {
                        capturePaymentEvent(operationOwner, selectedChat.id, event)
                    }
                }
                onPaid()
                if (!receiptQueued) {
                    mutableError.value = PAYMENT_CHAT_RECEIPT_WARNING
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    runCatching { releaseStagedEvent(operationOwner, stagedId) }
                }
                throw cancelled
            } catch (error: Exception) {
                withContext(NonCancellable) {
                    runCatching { releaseStagedEvent(operationOwner, stagedId) }
                }
                if (error.isKitInsufficientFundsError()) {
                    try {
                        // The request payment raced a balance change. Re-read the server-owned
                        // wallet before deciding how much the common top-up flow should collect.
                        val refreshed = walletSync.refresh()
                        val shortfall = shortfallForPaymentRequest(message, refreshed)
                        if (shortfall != null) {
                            mutableTopUpRequired.value = shortfall
                        } else {
                            mutableError.value = error.message
                                ?: "The payment could not be completed"
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (refreshFailure: Exception) {
                        mutableError.value = refreshFailure.message
                            ?: "Your wallet balance could not be refreshed"
                    }
                } else {
                    mutableError.value = error.message
                        ?: "The payment could not be completed"
                }
            } finally {
                mutableSending.value = false
            }
        }
    }

    /**
     * Turns down a payment request received in this conversation.
     *
     * Nothing is called on the server, because a request holds no money and the backend has no
     * payee-side decline — it only lets the requester withdraw. What a decline changes is the
     * conversation, so that is exactly and only what this writes.
     */
    fun declinePaymentRequest(message: Message, onDone: () -> Unit = {}) {
        val selectedChat = chat.value ?: return
        val descriptor = message.mediaDescriptor?.let(KitPaymentMessage::parse) ?: return
        if (
            !historyAvailable.value || mutableSending.value ||
            message.fromMe || !descriptor.isRequest
        ) return
        viewModelScope.launch {
            mutableSending.value = true
            mutableError.value = null
            try {
                chatRepo.sendPaymentEvent(
                    selectedChat.id,
                    descriptor.copy(action = KitPaymentAction.DECLINED).encode(),
                )
                onDone()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableError.value = error.message ?: "The request could not be declined"
            } finally {
                mutableSending.value = false
            }
        }
    }

    /** Withdraws a payment request this account sent, and records the withdrawal in-chat. */
    fun cancelPaymentRequest(message: Message, onDone: () -> Unit = {}) {
        val selectedChat = chat.value ?: return
        val operationOwner = sessions?.current()?.fence()
        val descriptor = message.mediaDescriptor?.let(KitPaymentMessage::parse) ?: return
        if (
            !historyAvailable.value || mutableSending.value ||
            !message.fromMe || !descriptor.isRequest
        ) return
        viewModelScope.launch {
            mutableSending.value = true
            mutableError.value = null
            var stagedId: String? = null
            try {
                val event = descriptor.copy(action = KitPaymentAction.CANCELLED)
                stagedId = stagePaymentEvent(operationOwner, selectedChat.id, event)
                if (operationOwner == null) {
                    walletRepo.cancelChatPaymentRequest(descriptor.referenceId)
                } else {
                    walletRepo.cancelChatPaymentRequestForOwner(
                        operationOwner,
                        descriptor.referenceId,
                    )
                }
                val receiptQueued = withContext(NonCancellable) {
                    commitStagedEvent(operationOwner, stagedId) {
                        capturePaymentEvent(operationOwner, selectedChat.id, event)
                    }
                }
                onDone()
                if (!receiptQueued) {
                    mutableError.value = PAYMENT_CHAT_RECEIPT_WARNING
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    runCatching { releaseStagedEvent(operationOwner, stagedId) }
                }
                throw cancelled
            } catch (error: Exception) {
                withContext(NonCancellable) {
                    runCatching { releaseStagedEvent(operationOwner, stagedId) }
                }
                mutableError.value = error.message ?: "The request could not be cancelled"
            } finally {
                mutableSending.value = false
            }
        }
    }

    /** Takes a held transfer. From here the payment is final and cannot be reversed. */
    fun acceptTransfer(
        message: Message,
        claimableTransfersEnabled: Boolean,
        onDone: () -> Unit = {},
    ) = settleTransfer(
        message = message,
        action = TransferClaimResolutionAction.ACCEPT,
        outcome = KitPaymentAction.ACCEPTED,
        expectedStatus = TransferClaimStatus.ACCEPTED,
        reason = null,
        claimableTransfersEnabled = claimableTransfersEnabled,
        onDone = onDone,
    ) { claimId, owner ->
        if (owner == null) walletRepo.acceptTransferClaim(claimId)
        else walletRepo.acceptTransferClaimForOwner(owner, claimId)
    }

    /** Sends a held transfer back, recording the recipient's reason in the conversation. */
    fun rejectTransfer(
        message: Message,
        reason: String?,
        claimableTransfersEnabled: Boolean,
        onDone: () -> Unit = {},
    ) {
        val canonicalReason = canonicalTransferClaimReason(reason)
        settleTransfer(
            message = message,
            action = TransferClaimResolutionAction.REJECT,
            outcome = KitPaymentAction.REJECTED,
            expectedStatus = TransferClaimStatus.REJECTED,
            reason = canonicalReason,
            claimableTransfersEnabled = claimableTransfersEnabled,
            onDone = onDone,
        ) { claimId, owner ->
            if (owner == null) walletRepo.rejectTransferClaim(claimId, canonicalReason)
            else walletRepo.rejectTransferClaimForOwner(owner, claimId, canonicalReason)
        }
    }

    /** Takes back a transfer only after a claim-bound biometric-or-PIN approval. */
    fun reverseTransfer(
        message: Message,
        reason: String?,
        paymentPin: String,
        claimableTransfersEnabled: Boolean,
        onDone: () -> Unit = {},
    ) {
        val canonicalReason = canonicalTransferClaimReason(reason)
        settleTransfer(
            message = message,
            action = TransferClaimResolutionAction.REVERSE,
            outcome = KitPaymentAction.REVERSED,
            expectedStatus = TransferClaimStatus.REVERSED,
            reason = canonicalReason,
            claimableTransfersEnabled = claimableTransfersEnabled,
            onDone = onDone,
        ) { claimId, owner ->
            if (owner == null) {
                walletRepo.reverseTransferClaim(claimId, canonicalReason, paymentPin)
            } else {
                walletRepo.reverseTransferClaimForOwner(
                    owner,
                    claimId,
                    canonicalReason,
                    paymentPin,
                )
            }
        }
    }

    private fun settleTransfer(
        message: Message,
        action: TransferClaimResolutionAction,
        outcome: KitPaymentAction,
        expectedStatus: TransferClaimStatus,
        reason: String?,
        claimableTransfersEnabled: Boolean,
        onDone: () -> Unit,
        settle: suspend (String, com.kit.wallet.data.session.SessionFence?) -> TransferClaim,
    ) {
        val selectedChat = chat.value ?: return
        val claimId = message.paymentReferenceId?.takeIf(String::isNotBlank) ?: return
        val binding = TransferClaimPartyBinding.create(
            currentUserId = walletRepo.currentAccountId,
            peerUserId = selectedChat.peerUserId,
        )
        if (!claimableTransfersEnabled || binding == null) {
            mutableError.value = "Transfer decisions are not available right now"
            return
        }
        if (mutableSending.value) return
        val operationOwner = sessions?.current()?.fence()
        // Claim the in-flight marker before the first suspension so two fast taps cannot race.
        mutableSending.value = true
        mutableError.value = null
        viewModelScope.launch {
            var settlementAttempted = false
            var stagedId: String? = null
            try {
                check(walletRepo.refreshClaimableTransfersCapability()) {
                    "Transfer decisions are not available right now"
                }
                // A failed or malformed fresh read never falls back to the polled claim map.
                val authoritative = if (operationOwner == null) {
                    walletRepo.transferClaim(claimId)
                } else {
                    walletRepo.transferClaimForOwner(operationOwner, claimId)
                }
                mutableTransferClaims.value += authoritative.id to authoritative
                check(
                    TransferClaimResolutionPolicy.allows(
                        action,
                        message,
                        authoritative,
                        binding,
                    ),
                ) {
                    "This payment is not pending or does not belong to this conversation"
                }
                check(walletRepo.currentAccountId.equals(binding.currentUserId, ignoreCase = true)) {
                    "The signed-in account changed while approving this payment"
                }
                val event = transferEvent(authoritative, outcome, reason)
                stagedId = stagePaymentEvent(operationOwner, selectedChat.id, event)
                settlementAttempted = true
                val settled = settle(authoritative.id, operationOwner)
                check(settled.id.equals(authoritative.id, ignoreCase = true)) {
                    "The server returned a different transfer"
                }
                check(settled.status == expectedStatus) {
                    "The server did not confirm this transfer update"
                }
                check(transferEvent(settled, outcome, reason) == event) {
                    "The server changed this transfer while settling it"
                }
                mutableTransferClaims.value += settled.id to settled
                val receiptQueued = withContext(NonCancellable) {
                    commitStagedEvent(operationOwner, stagedId) {
                        capturePaymentEvent(operationOwner, selectedChat.id, event)
                    }
                }
                onDone()
                if (!receiptQueued) {
                    mutableError.value = PAYMENT_CHAT_RECEIPT_WARNING
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    runCatching { releaseStagedEvent(operationOwner, stagedId) }
                }
                throw cancelled
            } catch (error: Exception) {
                withContext(NonCancellable) {
                    runCatching { releaseStagedEvent(operationOwner, stagedId) }
                }
                mutableError.value = error.message ?: "This transfer could not be updated"
                if (settlementAttempted) {
                    // A race can settle between the preflight GET and POST. Re-read this exact
                    // claim; never replace the fresh result with a cached or broad-list fallback.
                    runCatching {
                        if (operationOwner == null) walletRepo.transferClaim(claimId)
                        else walletRepo.transferClaimForOwner(operationOwner, claimId)
                    }
                        .getOrNull()
                        ?.let { refreshed ->
                            mutableTransferClaims.value += refreshed.id to refreshed
                        }
                }
            } finally {
                mutableSending.value = false
            }
        }
    }

    /**
     * Writes the outcome of a held transfer into the conversation, reason and all.
     *
     * The money has already moved, so this exact event is owner-pinned and durably captured under
     * a deterministic ID before the action closes. Network and E2EE preparation remain background
     * work owned by the immediate outbox; a failure here is reported as a receipt warning, never
     * as a failed settlement.
     */
    private suspend fun postTransferEvent(
        owner: com.kit.wallet.data.session.SessionFence?,
        chatId: String,
        claim: TransferClaim,
        outcome: KitPaymentAction,
        reason: String?,
    ): Boolean {
        return capturePaymentEvent(owner, chatId, transferEvent(claim, outcome, reason))
    }

    private fun transferEvent(
        claim: TransferClaim,
        outcome: KitPaymentAction,
        reason: String?,
    ): KitPaymentMessage = KitPaymentMessage(
            action = outcome,
            referenceId = claim.id,
            amountMinor = claim.amountMinor,
            currencyCode = claim.currencyCode,
            currencyScale = claim.currencyScale,
            // The original note already sits on the transfer card above; repeating it here would
            // only crowd out the one thing this line exists to say.
            note = null,
            reason = reason?.trim()
                ?.takeIf(String::isNotBlank)
                ?.take(KitPaymentMessage.MAX_REASON_LENGTH),
        )

    // MARK: - Group payments

    /**
     * Sends one payment into this group and announces it.
     *
     * The composer's own retry key is what makes a timeout safe: a send that actually succeeded is
     * answered with the same payment rather than paying a second time. The announcement afterwards
     * is best-effort — the money has moved, the card's state comes from the server, and a failure
     * here costs the written record, not the truth of what happened.
     */
    internal fun sendGroupPayment(
        splitMode: GroupPaymentSplitMode,
        audience: GroupPaymentAudience,
        selected: List<GroupPaymentDraftPolicy.Member>,
        totalInput: String,
        customAmounts: Map<String, String>,
        note: String?,
        paymentPin: String,
        idempotencyKey: String,
        groupPaymentsEnabled: Boolean,
        onSent: () -> Unit = {},
    ): Boolean {
        val selectedChat = chat.value ?: return false
        val repo = groupPaymentRepo
        if (!groupPaymentsEnabled || repo == null) {
            mutableError.value = "Group payments are not available right now"
            return false
        }
        if (!selectedChat.isGroup) {
            mutableError.value = "Group payments can only be sent in a group"
            return false
        }
        if (!historyAvailable.value || mutableSending.value) return false
        val operationOwner = sessions?.current()?.fence()
        mutableSending.value = true
        mutableError.value = null
        viewModelScope.launch {
            var recoveryReceipt: com.kit.wallet.data.messaging.FinancialCreationReceipt? = null
            try {
                val source = if (operationOwner == null) {
                    walletRepo.spendingSource()
                } else {
                    walletRepo.spendingSourceForOwner(operationOwner)
                }
                val drafted = GroupPaymentDraftPolicy.draft(
                    sourceWalletId = source.walletId,
                    splitMode = splitMode,
                    audience = audience,
                    selected = selected,
                    totalInput = totalInput,
                    customAmounts = customAmounts,
                    note = note,
                    scale = source.currencyScale,
                    availableBalanceMinor = source.availableBalanceMinor,
                )
                val request = when (drafted) {
                    is GroupPaymentDraftPolicy.Outcome.Ready -> drafted.request
                    is GroupPaymentDraftPolicy.Outcome.Problem -> {
                        mutableError.value = drafted.message
                        return@launch
                    }
                }
                if (operationOwner != null && financialCreationReceipts != null) {
                    recoveryReceipt = financialCreationReceipts.prepareGroupPayment(
                        operationOwner,
                        selectedChat.id,
                        idempotencyKey,
                        request,
                    )
                    check(recoveryReceipt.phase == FinancialCreationReceiptPhase.PREPARED) {
                        "This group payment is still being recovered"
                    }
                    financialCreationReceipts.markSubmitted(operationOwner, recoveryReceipt.id)
                }
                val payment = repo.send(
                    conversationId = selectedChat.id,
                    request = request,
                    idempotencyKey = idempotencyKey,
                    paymentPin = paymentPin,
                    expectedOwner = operationOwner,
                )
                storeGroupPayment(payment)
                // The roster in the descriptor is the server's answer, not the composer's: for
                // "everybody" this device never chose the members in the first place.
                val roster = payment.recipients.mapNotNull { it.userId }
                val event = checkNotNull(KitGroupPaymentMessage.announcing(payment, roster)) {
                    "Kit returned a group payment that cannot be announced"
                }
                val receiptQueued = if (
                    operationOwner != null && recoveryReceipt != null &&
                    financialCreationReceipts != null
                ) {
                    val settled = withContext(NonCancellable) {
                        financialCreationReceipts.bindGroupPayment(
                            operationOwner,
                            recoveryReceipt,
                            payment,
                        )
                    }
                    withContext(NonCancellable) {
                        runCatching {
                            financialCreationReceipts.handoff(operationOwner, settled)
                        }.isSuccess
                    }
                } else withContext(NonCancellable) {
                    captureGroupPaymentEvent(
                        operationOwner,
                        selectedChat.id,
                        event,
                        event.announcementMessageId(),
                    )
                }
                onSent()
                if (!receiptQueued) {
                    mutableError.value = PAYMENT_CHAT_RECEIPT_WARNING
                }
            } catch (cancelled: CancellationException) {
                recoveryReceipt?.let { receipt ->
                    withContext(NonCancellable) {
                        financialCreationReceipts?.discardPrepared(operationOwner!!, receipt.id)
                    }
                }
                throw cancelled
            } catch (error: Exception) {
                recoveryReceipt?.let { receipt ->
                    withContext(NonCancellable) {
                        runCatching {
                            financialCreationReceipts?.discardPrepared(operationOwner!!, receipt.id)
                        }
                    }
                }
                mutableError.value = error.message ?: "This group payment could not be sent"
            } finally {
                mutableSending.value = false
            }
        }
        return true
    }

    /** Takes this account's own share. Never a step-up: the money is already held for them. */
    fun acceptGroupPaymentShare(
        message: Message,
        groupPaymentsEnabled: Boolean,
        onDone: () -> Unit = {},
    ) = settleGroupPaymentShare(
        message = message,
        outcome = KitGroupPaymentAction.ACCEPTED,
        groupPaymentsEnabled = groupPaymentsEnabled,
        onDone = onDone,
    ) { repo, paymentId, owner -> repo.acceptShare(paymentId, owner) }

    /** Turns this account's own share down; it goes back to the sender and nobody else's moves. */
    fun rejectGroupPaymentShare(
        message: Message,
        reason: String?,
        groupPaymentsEnabled: Boolean,
        onDone: () -> Unit = {},
    ) {
        val canonicalReason = canonicalTransferClaimReason(reason)
        settleGroupPaymentShare(
            message = message,
            outcome = KitGroupPaymentAction.REJECTED,
            groupPaymentsEnabled = groupPaymentsEnabled,
            onDone = onDone,
        ) { repo, paymentId, owner -> repo.rejectShare(paymentId, canonicalReason, owner) }
    }

    /** The sender pulls back every share nobody has taken, after approving that one payment. */
    fun reverseUnclaimedGroupPayment(
        message: Message,
        reason: String?,
        paymentPin: String,
        groupPaymentsEnabled: Boolean,
        onDone: () -> Unit = {},
    ) {
        val canonicalReason = canonicalTransferClaimReason(reason)
        settleGroupPaymentShare(
            message = message,
            outcome = KitGroupPaymentAction.RETURNED,
            groupPaymentsEnabled = groupPaymentsEnabled,
            onDone = onDone,
        ) { repo, paymentId, owner ->
            repo.reverseUnclaimed(paymentId, canonicalReason, paymentPin, owner)
        }
    }

    /**
     * The one path every group-payment decision takes: settle on the server, then write what this
     * account did into the thread.
     *
     * The outcome line carries the payment and the act, and no amount at all — a share the group was
     * never told is not republished by the person answering it.
     */
    private fun settleGroupPaymentShare(
        message: Message,
        outcome: KitGroupPaymentAction,
        groupPaymentsEnabled: Boolean,
        onDone: () -> Unit,
        settle: suspend (
            GroupPaymentRepository,
            String,
            com.kit.wallet.data.session.SessionFence?,
        ) -> GroupPaymentSummary,
    ) {
        val selectedChat = chat.value ?: return
        val descriptor = message.groupPaymentDescriptor() ?: return
        val repo = groupPaymentRepo
        if (!groupPaymentsEnabled || repo == null) {
            mutableError.value = "Group payment decisions are not available right now"
            return
        }
        if (!historyAvailable.value || mutableSending.value) return
        val actor = walletRepo.currentAccountId?.takeIf(String::isNotBlank) ?: return
        val operationOwner = sessions?.current()?.fence()
        // Claim the in-flight marker before the first suspension so two fast taps cannot settle
        // the same share twice.
        mutableSending.value = true
        mutableError.value = null
        viewModelScope.launch {
            var stagedId: String? = null
            try {
                val event = checkNotNull(
                    KitGroupPaymentMessage.outcome(outcome, descriptor.groupPaymentId),
                ) { "This group payment outcome cannot be announced" }
                val eventId = KitGroupPaymentMessage.outcomeMessageId(
                    descriptor.groupPaymentId,
                    outcome,
                    actor,
                )
                stagedId = stageGroupPaymentEvent(
                    operationOwner,
                    selectedChat.id,
                    event,
                    eventId,
                )
                val settled = settle(repo, descriptor.groupPaymentId, operationOwner)
                storeGroupPayment(settled)
                check(walletRepo.currentAccountId.equals(actor, ignoreCase = true)) {
                    "The signed-in account changed while answering this payment"
                }
                val receiptQueued = withContext(NonCancellable) {
                    commitStagedEvent(operationOwner, stagedId) {
                        captureGroupPaymentEvent(
                            operationOwner,
                            selectedChat.id,
                            event,
                            eventId,
                        )
                    }
                }
                onDone()
                if (!receiptQueued) {
                    mutableError.value = PAYMENT_CHAT_RECEIPT_WARNING
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    runCatching { releaseStagedEvent(operationOwner, stagedId) }
                }
                throw cancelled
            } catch (error: Exception) {
                withContext(NonCancellable) {
                    runCatching { releaseStagedEvent(operationOwner, stagedId) }
                }
                mutableError.value = error.message ?: "This group payment could not be updated"
                // A race can settle between the preflight read and the POST. Re-read this exact
                // payment so the card stops offering an action over money that has already moved.
                runCatching { repo.groupPayment(descriptor.groupPaymentId, operationOwner) }
                    .getOrNull()
                    ?.let(::storeGroupPayment)
            } finally {
                mutableSending.value = false
            }
        }
    }

    /**
     * Re-reads every group payment this thread mentions.
     *
     * A failure leaves the cards where they were rather than painting the chat red: the
     * announcement is still readable, and a stale permission flag must never be what an accept
     * button is drawn from.
     */
    private suspend fun refreshGroupPayments() {
        val repo = groupPaymentRepo ?: return
        if (chatId.isBlank() || !historyAvailable.value) return
        val ids = conversationMessages.value
            .mapNotNull { it.groupPaymentDescriptor()?.groupPaymentId }
            .distinct()
            .takeLast(MAX_TRACKED_GROUP_PAYMENTS)
        if (ids.isEmpty()) return
        for (paymentId in ids) {
            val payment = try {
                repo.groupPayment(paymentId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                continue
            }
            storeGroupPayment(payment)
        }
    }

    private fun storeGroupPayment(payment: GroupPaymentSummary) {
        mutableGroupPayments.value += payment.id.lowercase() to payment
    }

    /**
     * Hydrates the conversation feed in bulk, then resolves descriptor references that fell
     * outside the newest-100 request feed and contribution references outside newest-50 embeds.
     */
    private suspend fun refreshGroupPaymentRequests() {
        val repo = groupPaymentRequestRepo ?: return
        if (!groupPaymentRequestsEnabled || chatId.isBlank() || !historyAvailable.value) return
        val referenced = conversationMessages.value.mapNotNull(Message::groupPaymentRequestId)
            .distinct().takeLast(MAX_TRACKED_GROUP_PAYMENT_REQUESTS)
        val listed = try {
            repo.list(chatId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
        val hydrated = listed.associateBy { it.id.lowercase() }.toMutableMap()
        for (requestId in referenced.filterNot(hydrated::containsKey)) {
            try {
                hydrated[requestId] = repo.get(requestId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A descriptor is only a hint. No API object means no card actions.
            }
        }
        if (hydrated.isNotEmpty()) mutableGroupPaymentRequests.value = hydrated
        hydrated.values.filter {
            it.knownStatus != GroupPaymentRequestStatus.OPEN || !it.canContribute
        }.forEach { groupContributionRetryKeys.reconcile(it.id) }

        val exact = hydrated.values.flatMap(GroupPaymentRequestDto::contributions)
            .associateBy { it.id.lowercase() }.toMutableMap()
        val eventReferences = conversationMessages.value.mapNotNull { message ->
            val requestId = message.groupPaymentRequestId ?: return@mapNotNull null
            val contributionId = message.groupPaymentRequestContributionId ?: return@mapNotNull null
            requestId to contributionId
        }.distinct().takeLast(MAX_TRACKED_GROUP_PAYMENT_REQUEST_CONTRIBUTIONS)
        for ((requestId, contributionId) in eventReferences) {
            if (exact.containsKey(contributionId)) continue
            try {
                exact[contributionId] = repo.exactContribution(requestId, contributionId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Never substitute another row: unattributed fallback copy is safer than guessing.
            }
        }
        mutableGroupPaymentRequestContributions.value = exact
    }

    private suspend fun refreshServerSchedules(@Suppress("UNUSED_PARAMETER") replace: Boolean) {
        val repo = serverScheduledPaymentRepo ?: return
        val selectedChat = chat.value ?: return
        if (!historyAvailable.value) return
        try {
            if (selectedChat.isGroup) {
                if (!scheduledGroupPaymentsEnabled) return
                mutableServerScheduledGroup.value = repo.recoverGroup(
                    selectedChat.id,
                    mutableServerScheduledGroup.value,
                )
            } else {
                if (!scheduledChatPaymentsEnabled) return
                mutableServerScheduledDirect.value = repo.recoverDirect(
                    selectedChat.id,
                    mutableServerScheduledDirect.value,
                )
            }
            mutableServerSchedulesHaveMore.value = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Existing rows stay visible; authorization and actions remain server-derived.
        }
    }

    fun loadMoreServerSchedules() {
        if (!mutableServerSchedulesHaveMore.value || mutableSending.value) return
        viewModelScope.launch { refreshServerSchedules(replace = false) }
    }

    fun cancelServerSchedule(id: String, group: Boolean) {
        val repo = serverScheduledPaymentRepo ?: return
        if (mutableSending.value || (group && !scheduledGroupPaymentsEnabled) ||
            (!group && !scheduledChatPaymentsEnabled)
        ) return
        viewModelScope.launch {
            mutableSending.value = true
            mutableError.value = null
            try {
                if (group) {
                    val operation = "group:${id.lowercase()}"
                    repo.cancelGroup(
                        id,
                        serverScheduleCancellationKeys.getOrPut(operation) {
                            "scheduled-group-cancel:${UUID.randomUUID()}"
                        },
                    )
                    serverScheduleCancellationKeys.remove(operation)
                    mutableServerScheduledGroup.value =
                        mutableServerScheduledGroup.value.filterNot { it.id == id }
                } else {
                    val operation = "direct:${id.lowercase()}"
                    repo.cancelDirect(
                        id,
                        serverScheduleCancellationKeys.getOrPut(operation) {
                            "scheduled-direct-cancel:${UUID.randomUUID()}"
                        },
                    )
                    serverScheduleCancellationKeys.remove(operation)
                    mutableServerScheduledDirect.value =
                        mutableServerScheduledDirect.value.filterNot { it.id == id }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableError.value = error.message ?: "The scheduled payment could not be cancelled"
            } finally {
                mutableSending.value = false
            }
        }
    }

    /** Resolves and freezes the exact direct intent before approval is shown. */
    internal fun prepareServerSchedule(
        amountMinor: Long,
        note: String?,
        scheduledAtEpochMillis: Long,
        enabled: Boolean,
        onReady: (ServerSchedulePreview) -> Unit,
    ) {
        val selectedChat = chat.value ?: return
        val repo = serverScheduledPaymentRepo
        val expectedOwner = sessions?.current()?.fence()
        val capabilityReady = if (selectedChat.isGroup) scheduledGroupPaymentsEnabled
        else scheduledChatPaymentsEnabled
        if (!enabled || !capabilityReady || repo == null || expectedOwner == null || mutableSending.value ||
            !historyAvailable.value || amountMinor <= 0
        ) return
        ScheduledSend.schedulingError(scheduledAtEpochMillis, clock.millis())?.let {
            mutableError.value = it
            return
        }
        val normalizedNote = note?.trim()?.takeIf(String::isNotBlank)?.take(280)
        viewModelScope.launch {
            mutableSending.value = true
            mutableError.value = null
            try {
                val source = walletRepo.spendingSource()
                val amount = BigDecimal.valueOf(amountMinor, source.currencyScale).toPlainString()
                val scheduledFor = Instant.ofEpochMilli(scheduledAtEpochMillis).toString()
                check(!selectedChat.isGroup) { "Use the group schedule composer for a group" }
                val peerId = selectedChat.peerUserId ?: error("This chat has no payment recipient")
                var contact = contactRepo?.contacts?.value?.firstOrNull { it.id == peerId }
                if (contact?.receivingWalletId == null) {
                    contactRepo?.refresh()
                    contact = contactRepo?.contacts?.value?.firstOrNull { it.id == peerId }
                }
                val destinationWalletId = contact?.receivingWalletId
                    ?: error("This contact cannot receive a scheduled payment")
                val operation = pendingServerSchedules.stage(
                    PendingServerSchedule.Direct(
                        chatId = selectedChat.id,
                        idempotencyKey = "server-schedule:${UUID.randomUUID()}",
                        phase = PendingServerSchedulePhase.PREPARED,
                        request = CreateScheduledPaymentRequest(
                            sourceWalletId = source.walletId,
                            destinationWalletId = destinationWalletId,
                            amount = amount,
                            note = normalizedNote,
                            scheduledFor = scheduledFor,
                            conversationId = selectedChat.id,
                        ),
                        currencyCode = source.currencyCode,
                        currencyScale = source.currencyScale,
                        amountMinor = amountMinor,
                        recipientName = contact.name,
                    ),
                    expectedOwner,
                )
                val preview = operation.preview()
                mutableServerScheduleApproval.value = preview
                onReady(preview)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableError.value = error.message ?: "The scheduled payment could not be previewed"
            } finally {
                mutableSending.value = false
            }
        }
    }

    /** Builds and verifies the complete group draft before its frozen plan is shown for approval. */
    internal fun prepareServerGroupSchedule(
        splitMode: GroupPaymentSplitMode,
        audience: GroupPaymentAudience,
        selected: List<GroupPaymentDraftPolicy.Member>,
        totalInput: String,
        customAmounts: Map<String, String>,
        note: String?,
        scheduledAtEpochMillis: Long,
        idempotencyKey: String,
        enabled: Boolean,
        onReady: (ServerSchedulePreview) -> Unit,
    ): Boolean {
        val selectedChat = chat.value ?: return false
        val repo = serverScheduledPaymentRepo ?: return false
        val expectedOwner = sessions?.current()?.fence() ?: return false
        if (!selectedChat.isGroup || !enabled || !scheduledGroupPaymentsEnabled ||
            mutableSending.value || !historyAvailable.value
        ) return false
        ScheduledSend.schedulingError(scheduledAtEpochMillis, clock.millis())?.let {
            mutableError.value = it
            return false
        }
        mutableSending.value = true
        mutableError.value = null
        viewModelScope.launch {
            try {
                val source = walletRepo.spendingSource()
                val drafted = GroupPaymentDraftPolicy.draft(
                    sourceWalletId = source.walletId,
                    splitMode = splitMode,
                    audience = audience,
                    selected = selected,
                    totalInput = totalInput,
                    customAmounts = customAmounts,
                    note = note,
                    scale = source.currencyScale,
                    availableBalanceMinor = source.availableBalanceMinor,
                )
                val request = when (drafted) {
                    is GroupPaymentDraftPolicy.Outcome.Ready -> drafted.request
                    is GroupPaymentDraftPolicy.Outcome.Problem -> error(drafted.message)
                }
                val scheduledFor = Instant.ofEpochMilli(scheduledAtEpochMillis).toString()
                val preview = PreviewScheduledGroupPaymentRequest(
                    sourceWalletId = request.sourceWalletId,
                    splitMode = request.splitMode,
                    audience = request.audience,
                    totalAmount = request.totalAmount,
                    note = request.note,
                    recipients = request.recipients,
                    scheduledFor = scheduledFor,
                )
                val allowed = groupMembers.value.filterNot(ChatMember::isSelf)
                    .map { it.userId.lowercase() }.toSet()
                val plan = repo.previewGroup(
                    selectedChat.id,
                    preview,
                    CurrencyDto(source.currencyCode, source.currencyScale.toString()),
                    allowed,
                    expectedOwner,
                )
                val scale = plan.currency.scale.toInt()
                val operation = pendingServerSchedules.stage(
                    PendingServerSchedule.Group(
                        chatId = selectedChat.id,
                        idempotencyKey = idempotencyKey,
                        phase = PendingServerSchedulePhase.PREPARED,
                        plan = plan,
                        amountMinor = plan.totalAmount.toBigDecimal().movePointRight(scale)
                            .longValueExact(),
                        recipientNames = plan.recipients.map { row ->
                            groupMembers.value.firstOrNull { it.userId == row.userId }?.name
                                ?: error("Kit returned a recipient outside this group")
                        },
                    ),
                    expectedOwner,
                )
                val approval = operation.preview()
                mutableServerScheduleApproval.value = approval
                onReady(approval)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableError.value = error.message ?: "The scheduled group payment could not be previewed"
            } finally {
                mutableSending.value = false
            }
        }
        return true
    }

    /** Creates a backend-owned money schedule; local send-later storage is never involved. */
    fun createServerSchedule(
        paymentPin: String,
        enabled: Boolean,
        onDone: () -> Unit = {},
    ) {
        val selectedChat = chat.value ?: return
        val repo = serverScheduledPaymentRepo
        val capabilityReady = if (selectedChat.isGroup) {
            scheduledGroupPaymentsEnabled
        } else {
            scheduledChatPaymentsEnabled
        }
        val operation = pendingServerSchedules.current(selectedChat.id)
        if (!enabled || !capabilityReady || repo == null || operation == null ||
            mutableSending.value || !historyAvailable.value
        ) return
        viewModelScope.launch {
            mutableSending.value = true
            mutableError.value = null
            try {
                when (val created = executePendingServerSchedule(
                    repository = repo,
                    store = pendingServerSchedules,
                    operation = operation,
                    paymentPin = paymentPin,
                    now = clock.instant(),
                    onSubmitted = { mutableServerScheduleApproval.value = it.preview() },
                )) {
                    is ServerScheduleCreation.Group -> {
                        mutableServerScheduledGroup.value = listOf(created.payment) +
                            mutableServerScheduledGroup.value.filterNot {
                                it.id == created.payment.id
                            }
                    }
                    is ServerScheduleCreation.Direct -> {
                        mutableServerScheduledDirect.value = listOf(created.payment) +
                            mutableServerScheduledDirect.value.filterNot {
                                it.id == created.payment.id
                            }
                    }
                }
                mutableServerScheduleApproval.value = null
                onDone()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableServerScheduleApproval.value =
                    pendingServerSchedules.current(selectedChat.id)?.preview()
                mutableError.value = error.message ?: "The payment could not be scheduled"
            } finally {
                mutableSending.value = false
            }
        }
    }

    /** Only a reviewed operation is dismissible; an ambiguous POST remains visible for replay. */
    fun dismissServerScheduleApproval() {
        pendingServerSchedules.discardPrepared()
        mutableServerScheduleApproval.value = pendingServerSchedules.current(chatId)?.preview()
    }

    /** Creates the authoritative request before mirroring its canonical encrypted card. */
    fun createGroupPaymentRequest(
        amountMinor: Long,
        note: String?,
        enabled: Boolean,
        onSent: () -> Unit = {},
    ) {
        val selectedChat = chat.value ?: return
        val repo = groupPaymentRequestRepo
        if (!enabled || !groupPaymentRequestsEnabled || repo == null || !selectedChat.isGroup) {
            mutableError.value = "Group payment requests are not available right now"
            return
        }
        if (amountMinor <= 0 || mutableSending.value || !historyAvailable.value) return
        val operationOwner = sessions?.current()?.fence()
        viewModelScope.launch {
            mutableSending.value = true
            mutableError.value = null
            var durablyShared = false
            try {
                val destination = if (operationOwner == null) {
                    walletRepo.spendingSource()
                } else {
                    walletRepo.spendingSourceForOwner(operationOwner)
                }
                val scale = destination.currencyScale
                val normalizedNote = note?.trim()?.takeIf(String::isNotBlank)?.take(280)
                val created = unsharedGroupPaymentRequest?.takeIf {
                    it.chatId == selectedChat.id &&
                        it.destinationWalletId == destination.walletId &&
                        it.amountMinor == amountMinor && it.currencyScale == scale &&
                        it.note == normalizedNote
                }?.request ?: executeGroupPaymentRequestCreation(
                    repository = repo,
                    retryKeys = groupRequestCreationRetryKeys,
                    conversationId = selectedChat.id,
                    destinationWalletId = destination.walletId,
                    amountMinor = amountMinor,
                    currencyScale = scale,
                    note = normalizedNote,
                    expectedOwner = operationOwner,
                ).also { confirmed ->
                    unsharedGroupPaymentRequest = UnsharedGroupPaymentRequest(
                        selectedChat.id,
                        destination.walletId,
                        amountMinor,
                        scale,
                        normalizedNote,
                        confirmed,
                    )
                }
                mutableGroupPaymentRequests.value += created.id to created
                val descriptor = checkNotNull(
                    KitGroupPaymentRequestMessage.create(
                        action = KitGroupPaymentRequestAction.REQUESTED,
                        requestId = created.id,
                        amountMinor = checkNotNull(created.targetMinor),
                        currencyCode = created.currency.code,
                        currencyScale = checkNotNull(created.currencyScale),
                        note = created.note,
                    ),
                )
                durablyShared = withContext(NonCancellable) {
                    captureGroupPaymentRequestEvent(operationOwner, selectedChat.id, descriptor)
                }
                if (durablyShared) {
                    groupRequestCreationRetryKeys.complete(
                        selectedChat.id,
                        destination.walletId,
                        amountMinor,
                        scale,
                        normalizedNote,
                    )
                }
                check(durablyShared) { "The group payment request could not be saved to this chat" }
                unsharedGroupPaymentRequest = null
                onSent()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (durablyShared) {
                    unsharedGroupPaymentRequest = null
                    onSent()
                } else {
                    mutableError.value = error.message
                        ?: "The group payment request could not be created"
                }
            } finally {
                mutableSending.value = false
            }
        }
    }

    /** Contributes a caller-selected partial amount; the server's remaining amount is re-read first. */
    fun contributeToGroupPaymentRequest(
        requestId: String,
        amountMinor: Long,
        paymentPin: String,
        enabled: Boolean,
        onDone: () -> Unit = {},
    ) {
        val selectedChat = chat.value ?: return
        val repo = groupPaymentRequestRepo
        val authority = mutableGroupPaymentRequests.value[requestId.lowercase()]
        if (!enabled || !groupPaymentRequestsEnabled || repo == null || authority == null ||
            authority.knownStatus != GroupPaymentRequestStatus.OPEN || !authority.canContribute
        ) return
        val scale = authority.currencyScale ?: return
        if (amountMinor <= 0 || amountMinor > (authority.remainingMinor ?: 0L) ||
            mutableSending.value || !historyAvailable.value
        ) return
        val operationOwner = sessions?.current()?.fence()
        viewModelScope.launch {
            mutableSending.value = true
            mutableError.value = null
            var recoveryReceipt: com.kit.wallet.data.messaging.FinancialCreationReceipt? = null
            try {
                val source = if (operationOwner == null) {
                    walletRepo.spendingSource()
                } else {
                    walletRepo.spendingSourceForOwner(operationOwner)
                }
                check(source.currencyCode == authority.currency.code && source.currencyScale == scale) {
                    "This request uses another currency"
                }
                val canonicalAmount = BigDecimal.valueOf(amountMinor, scale).toPlainString()
                val retryKey = groupContributionRetryKeys.keyFor(
                    authority.id,
                    source.walletId,
                    amountMinor,
                )
                if (operationOwner != null && financialCreationReceipts != null) {
                    recoveryReceipt = financialCreationReceipts.prepareGroupRequestContribution(
                        operationOwner,
                        selectedChat.id,
                        retryKey,
                        authority.id,
                        source.walletId,
                        amountMinor,
                        canonicalAmount,
                    )
                    check(recoveryReceipt.phase == FinancialCreationReceiptPhase.PREPARED) {
                        "This contribution is still being recovered"
                    }
                    financialCreationReceipts.markSubmitted(operationOwner, recoveryReceipt.id)
                }
                when (val resolution = executeGroupPaymentRequestContribution(
                    repository = repo,
                    retryKeys = groupContributionRetryKeys,
                    requestId = authority.id,
                    sourceWalletId = source.walletId,
                    amountMinor = amountMinor,
                    amount = canonicalAmount,
                    paymentPin = paymentPin,
                    expectedOwner = operationOwner,
                )) {
                    is GroupPaymentRequestContributionResolution.Confirmed -> {
                        val result = resolution.result
                        mutableGroupPaymentRequests.value += result.request.id to result.request
                        mutableGroupPaymentRequestContributions.value +=
                            result.contribution.id to result.contribution
                        val receiptQueued = if (
                            operationOwner != null && recoveryReceipt != null &&
                            financialCreationReceipts != null
                        ) {
                            val settled = withContext(NonCancellable) {
                                financialCreationReceipts.bindGroupRequestContribution(
                                    operationOwner,
                                    recoveryReceipt,
                                    result,
                                )
                            }
                            withContext(NonCancellable) {
                                runCatching {
                                    financialCreationReceipts.handoff(operationOwner, settled)
                                }.isSuccess
                            }
                        } else {
                            // Keep the exact contribution id even when this row closes the request.
                            val event = KitGroupPaymentRequestMessage.create(
                                action = KitGroupPaymentRequestAction.CONTRIBUTED,
                                requestId = result.request.id,
                                contributionId = result.contribution.id,
                                amountMinor = result.contribution.amountMinor.toLong(),
                            )
                            event != null && withContext(NonCancellable) {
                                captureGroupPaymentRequestEvent(
                                    operationOwner,
                                    selectedChat.id,
                                    event,
                                )
                            }
                        }
                        if (!receiptQueued) {
                            mutableError.value = PAYMENT_CHAT_RECEIPT_WARNING
                        }
                    }
                    is GroupPaymentRequestContributionResolution.Reconciled -> {
                        recoveryReceipt?.let { receipt ->
                            withContext(NonCancellable) {
                                financialCreationReceipts?.discardNotSubmitted(
                                    operationOwner!!,
                                    receipt.id,
                                )
                            }
                            recoveryReceipt = null
                        }
                        mutableGroupPaymentRequests.value +=
                            resolution.request.id to resolution.request
                    }
                }
                onDone()
            } catch (cancelled: CancellationException) {
                recoveryReceipt?.let { receipt ->
                    withContext(NonCancellable) {
                        financialCreationReceipts?.discardPrepared(operationOwner!!, receipt.id)
                    }
                }
                throw cancelled
            } catch (error: Exception) {
                recoveryReceipt?.let { receipt ->
                    withContext(NonCancellable) {
                        runCatching {
                            financialCreationReceipts?.discardPrepared(operationOwner!!, receipt.id)
                        }
                    }
                }
                mutableError.value = error.message ?: "The contribution could not be completed"
            } finally {
                mutableSending.value = false
            }
        }
    }

    fun cancelGroupPaymentRequest(
        requestId: String,
        enabled: Boolean,
        onDone: () -> Unit = {},
    ) {
        val selectedChat = chat.value ?: return
        val repo = groupPaymentRequestRepo
        val authority = mutableGroupPaymentRequests.value[requestId.lowercase()]
        if (!enabled || !groupPaymentRequestsEnabled || repo == null || authority?.canCancel != true ||
            mutableSending.value || !historyAvailable.value
        ) return
        val operationOwner = sessions?.current()?.fence()
        viewModelScope.launch {
            mutableSending.value = true
            mutableError.value = null
            var stagedId: String? = null
            try {
                val operation = authority.id.lowercase()
                val event = checkNotNull(
                    KitGroupPaymentRequestMessage.create(
                        KitGroupPaymentRequestAction.CANCELLED,
                        authority.id,
                    ),
                ) { "This group payment request cannot be cancelled" }
                stagedId = stageGroupPaymentRequestEvent(
                    operationOwner,
                    selectedChat.id,
                    event,
                )
                val cancelled = repo.cancel(
                    requestId = authority.id,
                    idempotencyKey = groupRequestCancellationKeys.getOrPut(operation) {
                        "group-request-cancel:${UUID.randomUUID()}"
                    },
                    expectedOwner = operationOwner,
                )
                groupRequestCancellationKeys.remove(operation)
                mutableGroupPaymentRequests.value += cancelled.id to cancelled
                check(cancelled.id == event.requestId) {
                    "Kit returned another group payment request"
                }
                val receiptQueued = withContext(NonCancellable) {
                    commitStagedEvent(operationOwner, stagedId) {
                        captureGroupPaymentRequestEvent(operationOwner, selectedChat.id, event)
                    }
                }
                if (!receiptQueued) {
                    mutableError.value = PAYMENT_CHAT_RECEIPT_WARNING
                }
                onDone()
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    runCatching { releaseStagedEvent(operationOwner, stagedId) }
                }
                throw cancelled
            } catch (error: Exception) {
                withContext(NonCancellable) {
                    runCatching { releaseStagedEvent(operationOwner, stagedId) }
                }
                mutableError.value = error.message ?: "The request could not be cancelled"
            } finally {
                mutableSending.value = false
            }
        }
    }

    private suspend fun refreshTransferClaims() {
        val claims = try {
            walletRepo.transferClaims()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Keep whatever was last known. A card falls back to the conversation's own record
            // rather than losing its state because one poll failed.
            return
        }
        mutableTransferClaims.value = claims.associateBy(TransferClaim::id)
        recordExpiredTransfers(claims)
    }

    /**
     * Writes the closing line for transfers that timed out.
     *
     * The seven-day auto-return happens on the server with nobody watching, so neither party is
     * told. The sender's app posts it, chosen because exactly one side must and the sender's own
     * outgoing card identifies them without any further identity plumbing.
     */
    private suspend fun recordExpiredTransfers(claims: List<TransferClaim>) {
        val expired = claims.filter { it.status == TransferClaimStatus.EXPIRED }
        if (expired.isEmpty() || chatId.isBlank() || !historyAvailable.value) return
        val projected = conversationMessages.value
        val sentFromHere = projected
            .filter { it.fromMe && it.kind == MessageKind.PAYMENT_TRANSFER }
            .mapNotNullTo(mutableSetOf()) { it.paymentReferenceId?.lowercase() }
        val alreadyWritten = projected
            .filter { it.paymentEvent == PaymentEventKind.EXPIRED }
            .mapNotNullTo(mutableSetOf()) { it.paymentReferenceId?.lowercase() }
        for (claim in expired) {
            val reference = claim.id.lowercase()
            if (reference !in sentFromHere || reference in alreadyWritten) continue
            // The projection lags the send by a round trip; this keeps one poll from writing the
            // same line twice while the first is still in flight.
            if (!announcedExpiries.add(reference)) continue
            postTransferEvent(
                owner = sessions?.current()?.fence(),
                chatId = chatId,
                claim = claim,
                outcome = KitPaymentAction.EXPIRED,
                reason = claim.reason,
            )
        }
    }

    fun sendImage(bytes: ByteArray, mediaType: String, onSent: () -> Unit = {}) =
        sendMedia(bytes, mediaType, caption = null, onSent = onSent)

    /**
     * Sends an attachment the platform hands us as a stream: a gallery video, shared document or
     * another large item. The repository first adopts it into the durable local media store, then
     * streams that copy through encryption into the background outbox.
     */
    fun sendMedia(
        source: SecureMediaSource,
        mediaType: String,
        caption: String? = null,
        onSent: () -> Unit = {},
        /**
         * Runs once the source has been read to the end, whatever the outcome. Whoever staged the
         * bytes — the camera's encoder, say — owns them until this fires, so it cannot delete a
         * file the cipher is still streaming.
         */
        onFinished: () -> Unit = {},
    ) {
        val selectedChat = chat.value
        if (selectedChat == null || !historyAvailable.value) {
            mutableError.value = "Secure messaging is temporarily unavailable"
            onFinished()
            return
        }
        val replyToMessageId = consumeReplyTarget()
        val sendJob = viewModelScope.launch {
            mutableError.value = null
            try {
                // A declared size of zero means the provider would not say; the cipher still stops
                // at the compiled cap, so an unknown size can never become an oversized upload.
                if (
                    source.processingPlan == SecureMediaProcessingPlan.PASSTHROUGH &&
                    source.declaredByteCount > 0
                ) {
                    richMediaCapability?.requireLocallyQueueable(
                        mediaType.trim().lowercase(),
                        source.declaredByteCount,
                    )
                }
                chatRepo.sendMediaMessage(
                    chatId = selectedChat.id,
                    source = source,
                    mediaType = mediaType,
                    caption = caption,
                    replyToMessageId = replyToMessageId,
                )
                onSent()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableError.value = error.message
                    ?: "The secure attachment could not be sent"
            }
        }
        // A launch into an already-cleared ViewModel can be cancelled before its body — and so
        // before any finally block — ever starts. Completion is the one boundary that always runs.
        sendJob.invokeOnCompletion { onFinished() }
    }

    /**
     * Sends everything the person multi-selected as ONE end-to-end encrypted message: one bubble,
     * one caption, one send. A single-item list falls through to the classic media message.
     */
    fun sendMediaAlbum(
        attachments: List<SecureMediaAlbumSource>,
        onSent: () -> Unit = {},
        /** Same contract as [sendMedia]'s: whoever staged the bytes owns them until this fires. */
        onFinished: () -> Unit = {},
    ) {
        val selectedChat = chat.value
        if (selectedChat == null || !historyAvailable.value) {
            mutableError.value = "Secure messaging is temporarily unavailable"
            onFinished()
            return
        }
        val replyToMessageId = consumeReplyTarget()
        val sendJob = viewModelScope.launch {
            mutableError.value = null
            try {
                // Per-item policy check first, so an oversized pick fails before any encryption.
                // A declared size of zero means the provider would not say; the cipher still
                // stops at the compiled cap.
                for (attachment in attachments) {
                    if (
                        attachment.source.processingPlan == SecureMediaProcessingPlan.PASSTHROUGH &&
                        attachment.source.declaredByteCount > 0
                    ) {
                        richMediaCapability?.requireLocallyQueueable(
                            attachment.mediaType.trim().lowercase(),
                            attachment.source.declaredByteCount,
                        )
                    }
                }
                chatRepo.sendMediaAlbumMessage(
                    chatId = selectedChat.id,
                    attachments = attachments,
                    caption = null,
                    replyToMessageId = replyToMessageId,
                )
                onSent()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableError.value = error.message
                    ?: "The secure attachments could not be sent"
            }
        }
        sendJob.invokeOnCompletion { onFinished() }
    }

    /**
     * Device-local recording/encoding cap for the in-app camera. Capture must not depend on a
     * cached or live service advertisement; the background dispatcher applies the authoritative
     * network gate after the original and pending message are durable.
     */
    fun captureByteLimit(): Long =
        richMediaCapability?.maximumLocallyQueueableBytes()
            ?: KitChatMediaLimits.MAX_TRANSFER_BYTES.toLong()

    /** Sends any kit-media-v1 attachment (photo, voice note, video or document) end-to-end. */
    fun sendMedia(
        bytes: ByteArray,
        mediaType: String,
        caption: String? = null,
        onSent: () -> Unit = {},
    ) {
        val selectedChat = chat.value
        if (
            selectedChat == null ||
            !historyAvailable.value ||
            bytes.isEmpty()
        ) {
            // Dropping a capture without a word would present a fake success (the camera has
            // already closed); say why the attachment was not queued.
            if (bytes.isNotEmpty() && selectedChat != null) {
                mutableError.value = "Secure messaging is temporarily unavailable"
            }
            bytes.fill(0)
            return
        }
        val replyToMessageId = consumeReplyTarget()
        val sendJob = viewModelScope.launch {
            mutableError.value = null
            try {
                richMediaCapability?.requireLocallyQueueable(
                    mediaType.trim().lowercase(),
                    bytes.size.toLong(),
                )
                chatRepo.sendImageMessage(
                    chatId = selectedChat.id,
                    bytes = bytes,
                    mediaType = mediaType,
                    caption = caption,
                    replyToMessageId = replyToMessageId,
                )
                onSent()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableError.value = error.message
                    ?: "The secure attachment could not be sent"
            } finally {
                bytes.fill(0)
            }
        }
        // A launch into an already-cleared ViewModel can be cancelled before its body (and
        // finally block) starts. Completion is the final ownership boundary for picker plaintext.
        sendJob.invokeOnCompletion { bytes.fill(0) }
    }

    /** Downloads and decrypts a media message once; results and failures are keyed by message. */
    fun openMedia(message: Message) {
        val selectedChat = chat.value ?: return
        val descriptor = message.mediaDescriptor ?: return
        if (!historyAvailable.value) return
        if (!claimMedia(message.id)) return
        viewModelScope.launch {
            hydrateMedia(message.id, "The secure photo could not be opened") {
                chatRepo.openImageMessage(
                    selectedChat.id,
                    descriptor,
                    messageId = message.id,
                    fromCurrentUser = message.fromMe,
                )
            }
        }
    }

    fun retryMedia(message: Message) {
        discardMedia(message.id)
        mutableMediaErrors.value = mutableMediaErrors.value - message.id
        openMedia(message)
    }

    /**
     * [openMedia] for one attachment of an album bubble; results and failures are keyed per item
     * via [albumItemMediaKey], so eight tiles of one message load, fail and retry independently.
     */
    fun openAlbumItem(message: Message, item: MessageMediaItem) {
        val selectedChat = chat.value ?: return
        val descriptor = message.mediaDescriptor ?: return
        if (!historyAvailable.value) return
        // Composer-staging chips carry placeholder ids; there is nothing to open until the queue
        // record with real attachment ids replaces them.
        if (item.attachmentId.startsWith("staging:")) return
        val key = albumItemMediaKey(message.id, item.attachmentId)
        if (!claimMedia(key)) return
        viewModelScope.launch {
            hydrateMedia(key, "The secure attachment could not be opened") {
                chatRepo.openAlbumItemMessage(
                    selectedChat.id,
                    descriptor,
                    item.attachmentId,
                    messageId = message.id,
                    fromCurrentUser = message.fromMe,
                )
            }
        }
    }

    fun retryAlbumItem(message: Message, item: MessageMediaItem) {
        val key = albumItemMediaKey(message.id, item.attachmentId)
        discardMedia(key)
        mutableMediaErrors.value = mutableMediaErrors.value - key
        openAlbumItem(message, item)
    }

    /** Claims [key] before any coroutine is launched, so automatic and tapped opens cannot race. */
    private fun claimMedia(key: String): Boolean {
        // A handle whose file the on-disk cache has since evicted is not an open attachment; it
        // has to be fetched again rather than handed to a player that would find nothing there.
        if (
            mutableMediaFiles.value[key]?.exists == true ||
            key in mutableMediaLoading.value ||
            mutableMediaErrors.value.containsKey(key)
        ) return false
        mutableMediaLoading.value = mutableMediaLoading.value + key
        return true
    }

    /** Runs one claimed receive through the process-wide gate, then publishes its cache handle. */
    private suspend fun hydrateMedia(
        key: String,
        fallbackError: String,
        reportFailure: Boolean = true,
        shouldStart: () -> Boolean = { true },
        open: suspend () -> SecureMediaFile,
    ) {
        try {
            // Download and decrypt still stream through one fixed buffer each, but they do read
            // and write whole attachments; serializing keeps large receives from competing for
            // the same disk and the same cache budget.
            val opened = secureMediaOpenMutex.withLock {
                // An automatic open may have waited behind another conversation for a long time.
                // Re-prove its lifecycle and projection only after it owns the gate, immediately
                // before the non-cancellable receive begins. Tapped opens use the default `true`.
                if (!shouldStart()) return@withLock null
                withContext(NonCancellable) { open() }
            } ?: return
            coroutineContext.ensureActive()
            cacheMedia(key, opened)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (stillPreparing: SecureMediaStillPreparingException) {
            // Not a failure: the send queue simply has no servable bytes yet, and nothing about
            // this attachment needs the person's attention. Remembering it here would paint a
            // dead retry tile over a healthy send and refuse every later claim — including the
            // automatic one that succeeds once the import copy publishes. Released quietly, the
            // next projection pass or tap re-proves it against the queue's current truth.
        } catch (error: Exception) {
            if (!reportFailure) return
            mutableMediaErrors.value = mutableMediaErrors.value +
                (key to (error.message ?: fallbackError))
        } finally {
            mutableMediaLoading.value = mutableMediaLoading.value - key
        }
    }

    private fun isCurrentPreloadTarget(
        selectedChatId: String,
        target: MediaPreloadTarget,
    ): Boolean =
        mutableConversationVisible.value &&
            historyAvailable.value &&
            chat.value?.id == selectedChatId &&
            target in mediaPreloadTargets(conversationMessages.value)

    /** Newest eligible single attachments and album items, capped across both message shapes. */
    private fun mediaPreloadTargets(projected: List<Message>): List<MediaPreloadTarget> {
        val targets = ArrayList<MediaPreloadTarget>(MAX_MEDIA_PRELOAD_ENTRIES)
        for (message in projected.asReversed()) {
            val descriptor = message.mediaDescriptor ?: continue
            when (message.kind) {
                MessageKind.IMAGE,
                MessageKind.VIDEO,
                MessageKind.VOICE_NOTE,
                MessageKind.DOCUMENT,
                -> if (message.mediaPlaintextBytes > 0) {
                    targets += MediaPreloadTarget.Single(
                        key = message.id,
                        mediaDescriptor = descriptor,
                        fromCurrentUser = message.fromMe,
                    )
                }

                MessageKind.MEDIA_ALBUM -> for (item in message.mediaItems) {
                    if (item.plaintextBytes <= 0 || item.attachmentId.startsWith("staging:")) {
                        continue
                    }
                    targets += MediaPreloadTarget.AlbumItem(
                        key = albumItemMediaKey(message.id, item.attachmentId),
                        mediaDescriptor = descriptor,
                        attachmentId = item.attachmentId,
                        messageId = message.id,
                        fromCurrentUser = message.fromMe,
                    )
                    if (targets.size == MAX_MEDIA_PRELOAD_ENTRIES) return targets
                }

                else -> Unit
            }
            if (targets.size == MAX_MEDIA_PRELOAD_ENTRIES) return targets
        }
        return targets
    }

    /**
     * Remembers an opened attachment, oldest-out once the window is full.
     *
     * Forgetting a handle deliberately does not delete its file: the same attachment may still be
     * on screen in the gallery or in another conversation's view model, and the file is owned by
     * the shared on-disk cache, which does its own bounded eviction and is wiped at sign-out.
     */
    private fun cacheMedia(messageId: String, media: SecureMediaFile) {
        mediaCache.remove(messageId)
        while (mediaCache.isNotEmpty() && mediaCache.size >= MAX_OPEN_MEDIA_ENTRIES) {
            mediaCache.remove(mediaCache.entries.first().key)
        }
        mediaCache[messageId] = media
        mutableMediaFiles.value = mediaCache.toMap()
    }

    private fun discardMedia(messageId: String) {
        if (mediaCache.remove(messageId) == null) return
        mutableMediaFiles.value = mediaCache.toMap()
    }

    override fun onCleared() {
        foregroundSyncJob?.cancel()
        mutableMediaFiles.value = emptyMap()
        mediaCache.clear()
        super.onCleared()
    }

    private fun launchSend(
        selectedChatId: String,
        normalizedText: String,
        retryingMessageId: String?,
        trustedPaymentEvent: Boolean,
        onSuccess: () -> Unit,
    ) {
        viewModelScope.launch {
            mutableSending.value = true
            mutableRetryingMessageId.value = retryingMessageId
            mutableError.value = null
            try {
                if (retryingMessageId == null) {
                    chatRepo.sendMessage(selectedChatId, normalizedText)
                } else {
                    if (trustedPaymentEvent) {
                        chatRepo.retryPaymentEvent(
                            chatId = selectedChatId,
                            clientMessageId = retryingMessageId,
                            descriptor = normalizedText,
                        )
                    } else {
                        chatRepo.retryMessage(
                            chatId = selectedChatId,
                            clientMessageId = retryingMessageId,
                            text = normalizedText,
                        )
                    }
                }
                onSuccess()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableError.value = error.message
                    ?: "Secure messaging is temporarily unavailable"
            } finally {
                mutableRetryingMessageId.value = null
                mutableSending.value = false
            }
        }
    }

    private companion object {
        // Read receipts and held-transfer state, neither of which has a push behind it. Deliberately
        // not a message-sync cadence: messages arrive by nudge, or by the coordinator's fallback
        // ladder when the socket cannot carry them.
        const val IDLE_REFRESH_INTERVAL_MILLIS = 45_000L
        // Only handles are held here, so this bounds a scroll window rather than a heap budget;
        // the bytes are bounded on disk by SecureMediaCache.
        const val MAX_OPEN_MEDIA_ENTRIES = 24
        const val MAX_MEDIA_PRELOAD_ENTRIES = 5
        const val COMPOSER_DRAFT_RETRY_INITIAL_MILLIS = 100L
        const val COMPOSER_DRAFT_RETRY_MAX_MILLIS = 5_000L

        // A poll re-reads one payment per request. Long-lived groups accumulate them, and the
        // cards a member can still act on are the recent ones; older announcements stay readable
        // from their descriptor without a live read behind them.
        const val MAX_TRACKED_GROUP_PAYMENTS = 24
        const val MAX_TRACKED_GROUP_PAYMENT_REQUESTS = 100
        const val MAX_TRACKED_GROUP_PAYMENT_REQUEST_CONTRIBUTIONS = 100
        const val PAYMENT_CHAT_RECEIPT_WARNING =
            "The payment succeeded, but its chat receipt is waiting to be recovered."
    }
}
