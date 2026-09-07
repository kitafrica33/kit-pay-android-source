package com.kit.wallet.feature.calls

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.kit.wallet.data.session.SessionStore
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kit.wallet.data.notifications.ActiveCallPresence
import com.kit.wallet.data.notifications.ActiveCallStateHolder
import com.kit.wallet.data.notifications.CallLifecycleEvent
import com.kit.wallet.data.notifications.CallLifecycleEventBus
import com.kit.wallet.data.notifications.CallLifecycleKind
import com.kit.wallet.data.notifications.CallRingDeadlineCoordinator
import com.kit.wallet.data.notifications.IncomingCallRelay
import com.kit.wallet.data.notifications.IncomingCallRelayEvent
import com.kit.wallet.data.notifications.IncomingCallRetirementDisposition
import com.kit.wallet.data.notifications.callRingLease
import com.kit.wallet.data.remote.KitWalletApiException
import com.kit.wallet.data.remote.isKitConnectivityError
import com.kit.wallet.data.repository.CallConnection
import com.kit.wallet.data.repository.CallParticipantIdentity
import com.kit.wallet.data.repository.CallRepository
import com.kit.wallet.data.repository.ChatRepository
import com.kit.wallet.data.repository.ContactRepository
import com.kit.wallet.data.repository.IncomingCallDetails
import com.kit.wallet.data.repository.canonicalCallUserId
import com.kit.wallet.data.repository.initialCallPresentation
import com.kit.wallet.data.repository.resolveCallPresentation
import com.kit.wallet.data.repository.resolveRoomParticipant
import com.kit.wallet.data.time.BootSessionIdProvider
import com.kit.wallet.data.time.ElapsedRealtimeClock
import com.kit.wallet.di.ApplicationScope
import com.kit.wallet.ui.model.Contact
import com.kit.wallet.ui.model.AccountVerification
import com.twilio.audioswitch.AudioDevice
import com.twilio.audioswitch.AudioDeviceChangeListener
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.livekit.android.ConnectOptions
import io.livekit.android.AudioOptions
import io.livekit.android.LiveKitOverrides
import io.livekit.android.LiveKit
import io.livekit.android.RoomOptions
import io.livekit.android.audio.AudioSwitchHandler
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.LocalAudioTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import io.livekit.android.room.track.screencapture.ScreenCaptureParams
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private class CallForegroundUnavailableException : IllegalStateException()

enum class CallPhase {
    IDLE,
    VALIDATING,
    INCOMING,
    CONNECTING,
    RINGING,
    CONNECTED,
    RECONNECTING,
    HELD,
    ENDING,
    ENDED,
    ERROR,
}

private data class ForegroundCallPresentation(
    val callId: String,
    val name: String,
    val video: Boolean,
    val camera: Boolean,
    val held: Boolean,
)

private data class HeldCallSnapshot(
    val session: CallConnection,
    val presentation: ActiveCallUiState,
    val anchor: CallDurationAnchor?,
    val reason: CallHoldReason,
    val localOnly: Boolean = false,
)

/** One other person on the call: their name, current video (camera or screen) and speaking state. */
data class RemoteCallParticipant(
    val id: String,
    val name: String,
    val videoTrack: VideoTrack? = null,
    val screenSharing: Boolean = false,
    val speaking: Boolean = false,
    /** Stable backend/LiveKit fallback used if a saved contact is renamed or removed. */
    val serverName: String? = name,
    /** This participant's profile photo, when they are someone the viewer has saved. */
    val avatarUrl: String? = null,
    val accountVerification: AccountVerification? = null,
)

/** A second call ringing in while this call is connected (call-waiting). */
data class WaitingCall(
    val callId: String,
    val name: String,
    val video: Boolean,
    val callerUserId: String?,
    /** Stable fallback; [name] may change whenever the address book changes. */
    val serverName: String = name,
)

data class ActiveCallUiState(
    val name: String = "Kit Pay contact",
    /** The single matched peer's profile photo URL; null for groups or unsaved callers. */
    val avatarUrl: String? = null,
    val accountVerification: AccountVerification? = null,
    val video: Boolean = false,
    val incoming: Boolean = false,
    val incomingVerified: Boolean = false,
    val phase: CallPhase = CallPhase.IDLE,
    val muted: Boolean = false,
    val cameraEnabled: Boolean = false,
    val mediaChanging: Boolean = false,
    val speakerEnabled: Boolean = false,
    val audioDevices: List<AudioDevice> = emptyList(),
    val selectedAudioDevice: AudioDevice? = null,
    val screenSharing: Boolean = false,
    val durationSeconds: Long = 0,
    val remoteParticipants: List<RemoteCallParticipant> = emptyList(),
    val localVideoTrack: VideoTrack? = null,
    val waitingCall: WaitingCall? = null,
    val mergingWaitingCall: Boolean = false,
    val switchingCall: Boolean = false,
    val canHold: Boolean = false,
    val holdReason: CallHoldReason? = null,
    val heldCallName: String? = null,
    val addingParticipants: Boolean = false,
    val participantUserIds: List<String> = emptyList(),
    val canShareInvitation: Boolean = false,
    val sharingInvitation: Boolean = false,
    val error: String? = null,
) {
    /** The primary remote video, used by the one-to-one layout. */
    val remoteVideoTrack: VideoTrack? get() = remoteParticipants.firstOrNull { it.videoTrack != null }?.videoTrack

    val remoteScreenShare: RemoteCallParticipant?
        get() = remoteParticipants.firstOrNull { it.screenSharing && it.videoTrack != null }

    /** True once more than one other participant is on the call. */
    val isGroup: Boolean get() = remoteParticipants.size > 1
}

/** Applies the single ordered ring/retirement stream that owns the call-waiting banner. */
internal fun applyIncomingCallRelayEvent(
    state: ActiveCallUiState,
    activeCallId: String?,
    terminated: Boolean,
    event: IncomingCallRelayEvent,
): ActiveCallUiState = when (event) {
    is IncomingCallRelayEvent.Ringing -> {
        val incoming = event.call
        if (
            terminated ||
            activeCallId == null ||
            incoming.callId == activeCallId ||
            state.waitingCall?.callId == incoming.callId
        ) {
            state
        } else {
            state.copy(
                waitingCall = WaitingCall(
                    callId = incoming.callId,
                    name = incoming.callerName,
                    video = incoming.video,
                    callerUserId = incoming.callerUserId,
                ),
            )
        }
    }
    is IncomingCallRelayEvent.Retired -> if (
        event.callId.equals(state.waitingCall?.callId, ignoreCase = true)
    ) {
        state.copy(waitingCall = null, mergingWaitingCall = false)
    } else {
        state
    }
}

internal fun offlineCallRetryDelayMillis(attempt: Int): Long =
    (2_000L shl attempt.coerceIn(0, 4)).coerceAtMost(30_000L)

internal data class ActiveCallContactPresentationSource(
    val callId: String?,
    val serverName: String?,
    val participantUserIds: List<String>,
    val participants: List<CallParticipantIdentity> = emptyList(),
    val fallbackPhone: String? = null,
)

internal data class TelecomPresentationUpdate(
    val callId: String,
    val name: String,
    val phone: String?,
    val video: Boolean,
)

internal data class ActiveCallContactPresentationRefresh(
    val state: ActiveCallUiState,
    val activeTelecom: TelecomPresentationUpdate?,
    val waitingTelecom: TelecomPresentationUpdate?,
)

internal fun directCallChatContact(
    source: ActiveCallContactPresentationSource?,
    contacts: List<Contact>,
): Contact? {
    val participantIds = source?.participantUserIds
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        ?.distinctBy(String::lowercase)
        .orEmpty()
    val matches = contacts.filter { contact ->
        contact.isKitUser && participantIds.any { it.equals(contact.id, ignoreCase = true) }
    }.distinctBy { it.id.lowercase() }
    return matches.singleOrNull()
}

/**
 * Re-resolves viewer-specific call labels from an already-loaded contact snapshot. This function
 * is presentation-only: it cannot refresh/upload contacts or alter any call lifecycle state.
 */
internal fun refreshActiveCallContactPresentation(
    state: ActiveCallUiState,
    activeSource: ActiveCallContactPresentationSource?,
    contacts: List<Contact>,
): ActiveCallContactPresentationRefresh {
    val activePresentation = activeSource?.let { source ->
        resolveCallPresentation(
            serverName = source.serverName,
            participantUserIds = source.participantUserIds,
            contacts = contacts,
            participants = source.participants,
        )
    }
    val activePhone = activePresentation?.phone
        ?: activeSource?.fallbackPhone?.trim()?.takeIf(String::isNotEmpty)

    val waiting = state.waitingCall
    val waitingPresentation = waiting?.let {
        resolveCallPresentation(
            serverName = it.serverName,
            participantUserIds = listOfNotNull(it.callerUserId),
            contacts = contacts,
        )
    }
    val refreshedWaiting = if (waiting != null && waitingPresentation != null) {
        waiting.copy(name = waitingPresentation.name)
    } else {
        waiting
    }
    val refreshedParticipants = state.remoteParticipants.map { participant ->
        val presentation = resolveRoomParticipant(
            identity = participant.id,
            serverName = participant.serverName,
            contacts = contacts,
            participants = activeSource?.participants.orEmpty(),
        )
        participant.copy(
            name = presentation.name,
            avatarUrl = presentation.avatarUrl ?: participant.avatarUrl,
            accountVerification = presentation.accountVerification
                ?: participant.accountVerification,
        )
    }
    val activeParticipantCount = activeSource
        ?.let { it.participantUserIds + it.participants.map(CallParticipantIdentity::userId) }
        ?.mapNotNull(::canonicalCallUserId)
        ?.distinctBy(String::lowercase)
        ?.size
    val refreshedState = state.copy(
        name = activePresentation?.name ?: state.name,
        avatarUrl = when {
            activePresentation == null -> state.avatarUrl
            activeParticipantCount != 1 -> null
            else -> activePresentation.avatarUrl ?: state.avatarUrl
        },
        accountVerification = when {
            activePresentation == null -> state.accountVerification
            activeParticipantCount != 1 -> null
            else -> activePresentation.accountVerification ?: state.accountVerification
        },
        waitingCall = refreshedWaiting,
        remoteParticipants = refreshedParticipants,
    )

    return ActiveCallContactPresentationRefresh(
        state = refreshedState,
        activeTelecom = activeSource?.callId?.let { callId ->
            activePresentation?.let { presentation ->
                TelecomPresentationUpdate(
                    callId = callId,
                    name = presentation.name,
                    phone = activePhone,
                    video = state.video,
                )
            }
        },
        waitingTelecom = if (waiting != null && waitingPresentation != null) {
            TelecomPresentationUpdate(
                callId = waiting.callId,
                name = waitingPresentation.name,
                phone = waitingPresentation.phone,
                video = waiting.video,
            )
        } else {
            null
        },
    )
}

@HiltViewModel
class ActiveCallViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val calls: CallRepository,
    private val contacts: ContactRepository,
    private val chats: ChatRepository,
    private val callEvents: CallLifecycleEventBus,
    private val activeCallState: ActiveCallStateHolder,
    private val incomingCalls: IncomingCallRelay,
    private val telecom: KitTelecomBridge,
    private val systemCommands: CallSystemCommands,
    private val sessions: SessionStore,
    private val scheduledCalls: com.kit.wallet.data.repository.ScheduledCallRepository,
    private val ringDeadlines: CallRingDeadlineCoordinator,
    private val elapsedRealtimeClock: ElapsedRealtimeClock,
    private val bootSessionIdProvider: BootSessionIdProvider,
    @ApplicationScope private val applicationScope: CoroutineScope,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val target: String? = savedStateHandle["name"]
    private val incomingCallId: String? = savedStateHandle["callId"]
    private val inviteToken: String? = savedStateHandle["inviteToken"]

    private val outgoingCallLaunchGate = if (incomingCallId == null) {
        OutgoingCallLaunchGate(savedStateHandle)
    } else {
        null
    }
    // Process-only by design: configuration changes retain this ViewModel, while process death
    // creates a new attempt that the stale-route gate refuses to submit or ring.
    private val outgoingClientCallId = if (incomingCallId == null) UUID.randomUUID().toString() else null

    private val initialPresentation = initialCallPresentation(
        if (target?.split(',')?.all { canonicalCallUserId(it) != null } == true && target.contains(','))
            "Group call" else target, contacts.contacts.value)
    private val mutableState = MutableStateFlow(
        ActiveCallUiState(
            name = if (incomingCallId != null) {
                "Incoming Kit Pay call"
            } else {
                initialPresentation.name
            },
            avatarUrl = if (incomingCallId != null) null else initialPresentation.avatarUrl,
            accountVerification = if (incomingCallId != null) {
                null
            } else {
                initialPresentation.accountVerification
            },
            incoming = incomingCallId != null,
            phase = if (incomingCallId != null) CallPhase.VALIDATING else CallPhase.IDLE,
        ),
    )
    val state = mutableState.asStateFlow()
    private val mutableOpeningChat = MutableStateFlow(false)
    val openingChat = mutableOpeningChat.asStateFlow()
    val canOpenChat: StateFlow<Boolean> = combine(
        state,
        contacts.contacts,
        chats.readiness,
    ) { callState, availableContacts, messagingReady ->
        messagingReady && callState.phase in setOf(CallPhase.CONNECTED, CallPhase.RECONNECTING) &&
            directCallChatContact(activeContactPresentationSource(), availableContacts) != null
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    internal fun consumeOutgoingCallLaunch(): OutgoingCallLaunchAction =
        outgoingCallLaunchGate?.consume() ?: OutgoingCallLaunchAction.KEEP_CURRENT_ROUTE

    var room: Room = createCallRoom()
        private set

    private fun createCallRoom(): Room = LiveKit.create(
        context, RoomOptions(adaptiveStream = true, dynacast = true),
        LiveKitOverrides(audioOptions = AudioOptions(disableAudioPrewarming = true,
            disableCommunicationModeWorkaround = true)),
    )

    private val sessionOwner = sessions.current()?.fence()
    private val ownedCallIds = mutableSetOf<String>()
    private var roomEventsJob: Job? = null
    private var switchJob: Job? = null
    private var primaryHold: HeldCallSnapshot? = null
    private var heldSession: HeldCallSnapshot? = null
    private var screenForeground = false
    private var acceptedWaitingCallId: String? = null
    private var acceptInFlightCallId: String? = null
    private var pendingAnswerCancellationId: String? = null
    private var requestedSystemResumeCallId: String? = null
    private var resumeAfterInterruption = false
    private val terminalCallIds = mutableSetOf<String>()
    private val callAudio = CallAudioOwnership(context,
        interruptImmediately = {
            hardMuteRoom()
            viewModelScope.launch { holdCurrent(CallHoldReason.INTERRUPTION) }
        },
        onAvailable = ::resumeAfterAudioReturn,
    )

    private var connection: CallConnection? = null
    private var verifiedIncomingCall: IncomingCallDetails? = null
    private var validationJob: Job? = null
    private var startJob: Job? = null
    private val mediaOperations = CallMediaOperations()
    private var foregroundCall: ForegroundCallPresentation? = null
    private var offlineStartRetryJob: Job? = null
    private var offlineStartRetryAttempt = 0
    private var outgoingAttemptSubmitted = false
    private var outgoingAttemptResolved = false
    private var cleanupJob: Job? = null
    private var terminationJob: Job? = null
    private var timerJob: Job? = null
    private var durationAnchor: CallDurationAnchor? = null
    /**
     * The call this screen has seen a validated answer for, whichever route carried it.
     * Only ever compared against the call id an authenticated response just handed this
     * attempt — a server-issued UUID unique across accounts and attempts — so an answer
     * held over from another session can never satisfy the comparison. Cleared before
     * each attempt and on teardown so not even the id itself outlives the call it names.
     */
    private var answeredCallId: String? = null
    private val pendingAnswers = PendingCallAnswers()
    private val pendingTerminations = CallTerminationQueue()
    private var localTelecomTermination = DeferredCallTermination(
        finish = telecom::finish,
        initialCallId = incomingCallId,
    )
    private var terminated = false
    private val audioDeviceListener: AudioDeviceChangeListener = { devices, selected ->
        // AudioSwitch dispatches from its audio thread, while call state belongs to Main.
        viewModelScope.launch {
            if (!terminated) {
                mutableState.value = mutableState.value.copy(
                    audioDevices = devices.toList(),
                    selectedAudioDevice = selected,
                    speakerEnabled = selected is AudioDevice.Speakerphone,
                )
            }
        }
    }

    /** Kit Pay contacts that can be added to the call, for the in-call "Add people" picker. */
    val callableContacts: StateFlow<List<Contact>> = combine(contacts.contacts, state) { list, current ->
        list.filter { contact -> contact.isKitUser &&
            !contact.id.equals(sessionOwner?.accountId, ignoreCase = true) &&
            current.participantUserIds.none { it.equals(contact.id, ignoreCase = true) }
        }.sortedBy { it.name }
    }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch {
            sessionOwner?.let { owner ->
                runCatching { scheduledCalls.features(owner) }.getOrNull()?.let { features ->
                    if (ownsSession()) mutableState.value = mutableState.value.copy(canShareInvitation = features.inviteLinks)
                }
            }
        }
        (room.audioHandler as? AudioSwitchHandler)
            ?.registerAudioDeviceChangeListener(audioDeviceListener)
        viewModelScope.launch {
            contacts.contacts.collect(::applyContactPresentation)
        }
        (room.audioHandler as? AudioSwitchHandler)?.manageAudioFocus = false
        observeRoom(room)
        viewModelScope.launch {
            systemCommands.events.collect { command ->
                when (command) {
                    is CallSystemCommand.Answer -> answerWaitingCall(command.callId)
                    is CallSystemCommand.Hold -> if (command.callId == connection?.callId) callAudio.systemHeld()
                    is CallSystemCommand.Resume -> if (command.callId == connection?.callId) {
                        // A deliberate system/Bluetooth unhold also resumes a manual hold. Keep
                        // that intent if Android cannot return focus until the other call ends.
                        requestedSystemResumeCallId = command.callId
                        callAudio.systemResumed()
                        resumeAfterAudioReturn()
                    } else if (command.callId == heldSession?.session?.callId) resumeHeldCall()
                    is CallSystemCommand.ForegroundUnavailable -> foregroundCallUnavailable(command.callId)
                }
            }
        }
        viewModelScope.launch {
            sessions.session.collect { owner ->
                if (owner?.fence() != sessionOwner && !terminated) terminate("cancelled")
            }
        }
        viewModelScope.launch {
            callEvents.events.collect(::handleLifecycleEvent)
        }
        // A second call ringing in while this one is connected becomes a call-waiting banner.
        viewModelScope.launch {
            incomingCalls.events.collect { event ->
                val previous = mutableState.value
                val updated = applyIncomingCallRelayEvent(
                    state = previous,
                    activeCallId = connection?.callId,
                    terminated = terminated,
                    event = event,
                )
                if (updated != previous && !(mutableState.value.switchingCall &&
                        event is IncomingCallRelayEvent.Retired && event.callId == acceptedWaitingCallId)) {
                    mutableState.value = updated
                    if (event is IncomingCallRelayEvent.Ringing) {
                        applyContactPresentation(contacts.contacts.value)
                    }
                }
            }
        }
        if (incomingCallId != null) validateIncomingCall()
    }

    private fun observeRoom(observedRoom: Room) {
        roomEventsJob?.cancel()
        roomEventsJob = viewModelScope.launch {
            observedRoom.events.collect { event ->
                if (room !== observedRoom || terminated || primaryHold != null || mutableState.value.switchingCall) return@collect
                when (event) {
                    is RoomEvent.ParticipantConnected -> if (!terminated) {
                        markConnected()
                        syncRemoteParticipants()
                        reconcileCallStatus()
                    }
                    is RoomEvent.ParticipantDisconnected -> if (!terminated) {
                        syncRemoteParticipants()
                        // A held peer intentionally retires its RTC identity. Only the backend
                        // can decide whether a call/participant has actually ended.
                        reconcileCallStatus()
                    }
                    // Any camera/screen track appearing or disappearing rebuilds the participant grid
                    // and re-derives whether the call is showing video.
                    is RoomEvent.TrackSubscribed -> if (!terminated) syncRemoteParticipants()
                    is RoomEvent.TrackUnsubscribed -> if (!terminated) syncRemoteParticipants()
                    is RoomEvent.TrackMuted,
                    is RoomEvent.TrackUnmuted,
                    is RoomEvent.TrackPublished,
                    is RoomEvent.TrackUnpublished,
                    -> if (!terminated) {
                        syncRemoteParticipants()
                        syncLocalMediaState()
                    }
                    is RoomEvent.ActiveSpeakersChanged -> if (!terminated) syncRemoteParticipants()
                    is RoomEvent.Reconnecting -> if (!terminated) {
                        mutableState.value = mutableState.value.copy(phase = CallPhase.RECONNECTING)
                    }
                    is RoomEvent.Reconnected -> if (!terminated) {
                        mutableState.value = mutableState.value.copy(
                            phase = CallAnswerRouting.phaseAfterConnect(
                                hasRemoteParticipants = room.remoteParticipants.isNotEmpty(),
                                incoming = incomingCallId != null,
                                alreadyAnswered = answeredCallId.equals(connection?.callId, ignoreCase = true),
                            ),
                        )
                        syncRemoteParticipants()
                        syncLocalMediaState()
                    }
                    is RoomEvent.FailedToConnect -> fail(event.error)
                    is RoomEvent.Disconnected -> if (!terminated) {
                        fail(event.error ?: IOException("The call connection ended"))
                    }
                    else -> Unit
                }
            }
        }
    }

    /** Declines the second, waiting call without disturbing the current call. */
    fun declineWaitingCall() {
        if (mutableState.value.switchingCall) return
        val waiting = mutableState.value.waitingCall ?: return
        mutableState.value = mutableState.value.copy(waitingCall = null, mergingWaitingCall = false)
        ringDeadlines.retire(waiting.callId, IncomingCallRetirementDisposition.REJECTED)
        telecom.finish(waiting.callId, KitTelecomDisconnect.REJECTED)
        applicationScope.launch { runCatching { calls.decline(waiting.callId) } }
    }

    /**
     * Merges the waiting call into this one: the waiting caller is added to the current call as a
     * group call, and their separate incoming call is dismissed. Both parties end up together.
     */
    fun mergeWaitingCall() {
        if (mutableState.value.switchingCall || primaryHold != null) return
        val waiting = mutableState.value.waitingCall ?: return
        val currentCallId = connection?.callId ?: return
        val callerUserId = waiting.callerUserId
        if (callerUserId == null) {
            mutableState.value = mutableState.value.copy(
                waitingCall = null,
                error = "This call can't be merged. Ask them to call back after this call.",
            )
            ringDeadlines.retire(waiting.callId, IncomingCallRetirementDisposition.REJECTED)
            telecom.finish(waiting.callId, KitTelecomDisconnect.REJECTED)
            applicationScope.launch { runCatching { calls.decline(waiting.callId) } }
            return
        }
        mutableState.value = mutableState.value.copy(mergingWaitingCall = true)
        viewModelScope.launch {
            try {
                calls.invite(currentCallId, listOf(callerUserId))
                runCatching { calls.decline(waiting.callId) }
                ringDeadlines.retire(waiting.callId, IncomingCallRetirementDisposition.REJECTED)
                telecom.finish(waiting.callId, KitTelecomDisconnect.REJECTED)
                mutableState.value = mutableState.value.copy(
                    waitingCall = null,
                    mergingWaitingCall = false,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (!terminated) {
                    mutableState.value = mutableState.value.copy(
                        mergingWaitingCall = false,
                        error = error.userMessage(),
                    )
                }
            }
        }
    }

    fun openChat(onOpened: (String) -> Unit) {
        if (mutableOpeningChat.value || !canOpenChat.value) return
        val contact = directCallChatContact(
            activeContactPresentationSource(),
            contacts.contacts.value,
        ) ?: return
        viewModelScope.launch {
            mutableOpeningChat.value = true
            try {
                onOpened(chats.openDirectConversation(contact))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (!terminated) {
                    mutableState.value = mutableState.value.copy(error = error.userMessage())
                }
            } finally {
                mutableOpeningChat.value = false
            }
        }
    }

    private fun clearWaitingCall() {
        if (mutableState.value.waitingCall != null || mutableState.value.mergingWaitingCall) {
            mutableState.value = mutableState.value.copy(
                waitingCall = null,
                mergingWaitingCall = false,
            )
        }
    }

    fun start(requestedVideo: Boolean) {
        if (incomingCallId != null) return
        connect(requestedVideo)
    }

    fun accept(requestedVideo: Boolean) {
        if (incomingCallId == null || !mutableState.value.incomingVerified) return
        // Claim the local Telecom offer synchronously, before POST /accept can emit its
        // call.answered push back to this same device. The echo dismisses sibling rings only;
        // ANSWERING keeps this device's Connection alive until the authenticated response.
        telecom.markAnswering(incomingCallId)
        // Answer is a terminal local decision for the ringing surface. Retire it before the
        // network call so a failed or interrupted accept can never leave a zombie notification.
        closeRingWindow(
            incomingCallId,
            IncomingCallRetirementDisposition.ANSWERED_ELSEWHERE,
        )
        connect(requestedVideo)
    }

    private fun connect(requestedVideo: Boolean) {
        if (sessions.current()?.fence() != sessionOwner) return
        if (activeCallState.activeCallId.value != null &&
            activeCallState.activeCallId.value !in ownedCallIds) {
            mutableState.value = mutableState.value.copy(phase = CallPhase.ERROR,
                error = "Return to your active call before starting another.")
            return
        }
        if (!pendingTerminations.isEmpty || startJob?.isActive == true || cleanupJob?.isActive == true ||
            terminationJob?.isActive == true || mutableState.value.phase !in setOf(
                CallPhase.IDLE,
                CallPhase.INCOMING,
                CallPhase.ERROR,
            )
        ) {
            return
        }
        if (incomingCallId == null && connection == null) {
            // A successful retry creates a new backend/Telecom call id and needs a fresh one-shot
            // termination guard; an incoming retry always retains its original call id.
            localTelecomTermination = DeferredCallTermination(finish = telecom::finish)
        }
        mediaOperations.open()
        foregroundCall = null
        terminated = false
        // A retry places a new call, so nothing the previous attempt counted applies to it.
        durationAnchor = null
        answeredCallId = null
        // Cleared before the request goes out, so only an answer to the call this attempt
        // is about to place can ever be claimed by it.
        pendingAnswers.clear()
        startJob = viewModelScope.launch {
            mutableState.value = mutableState.value.copy(
                video = requestedVideo,
                cameraEnabled = requestedVideo,
                phase = CallPhase.CONNECTING,
                durationSeconds = 0,
                muted = false,
                screenSharing = false,
                mediaChanging = false,
                error = null,
            )
            try {
                check(activeCallState.activeCallId.value == null ||
                    activeCallState.activeCallId.value == connection?.callId) { "Return to your active call first" }
                val session = inviteToken?.let { scheduledCalls.redeem(it, requireNotNull(sessionOwner)) }
                    ?: incomingCallId?.let { calls.accept(it) } ?: run {
                    outgoingAttemptSubmitted = true
                    calls.startGroup(
                        recipientUserIds = resolveRecipients(),
                        video = requestedVideo,
                        clientCallId = requireNotNull(outgoingClientCallId),
                    ).also { outgoingAttemptResolved = true }
                }
                connection = session
                ownedCallIds.add(session.callId)
                // The accept response already carries the authoritative answer, so an
                // answerer never waits for a socket frame or a push to know where its
                // timer starts. For a caller this is null until somebody picks up.
                applyAnswerAnchor(session.callId, session.answeredAt, session.serverTime)
                // And this is the answer that arrived while the request above was still in
                // flight, if there was one. Claimed by exact call id, so a signal about any
                // other call is never applied to this one.
                pendingAnswers.claim(session.callId)?.let(::applyLifecycleEvent)
                offlineStartRetryJob?.cancel()
                offlineStartRetryJob = null
                offlineStartRetryAttempt = 0
                if (incomingCallId == null) {
                    telecom.trackOutgoing(session.callId, session.name, session.phone, session.video)
                } else {
                    telecom.updatePresentation(session.callId, session.name, session.phone, session.video)
                    telecom.markConnecting(session.callId)
                }
                // Resolve only after Telecom tracking. If End was tapped while POST /calls was in
                // flight, this atomically turns the just-tracked call into a terminal tombstone.
                localTelecomTermination.resolveCallId(session.callId)
                if (terminated) return@launch
                if (CallAnswerRouting.armsRingDeadline(
                        incoming = incomingCallId != null,
                        alreadyAnswered = answeredCallId.equals(session.callId, ignoreCase = true),
                    )
                ) {
                    callRingLease(
                        ringExpiresAt = session.ringExpiresAt,
                        serverTime = session.ringServerTime,
                        receivedElapsedRealtimeMillis = elapsedRealtimeClock.millis(),
                        bootSessionId = bootSessionIdProvider.currentBootId(),
                    )?.let { ringDeadlines.schedule(session.callId, it) }
                } else {
                    // A successful answer response ends the incoming ringing window even if media
                    // connection takes longer than the original deadline — and so does an answer
                    // that overtook this response on the way here.
                    // Persist the answered tombstone before dropping an incoming ring deadline so
                    // an old immutable notification action cannot reopen this accepted call.
                    closeRingWindow(
                        session.callId,
                        IncomingCallRetirementDisposition.ANSWERED_ELSEWHERE,
                    )
                }
                mutableState.value = mutableState.value.copy(
                    name = session.name,
                    avatarUrl = session.avatarUrl,
                    accountVerification = session.accountVerification,
                    video = session.video,
                    cameraEnabled = session.video,
                    canHold = session.canHold,
                    participantUserIds = session.participantUserIds,
                )
                applyContactPresentation(contacts.contacts.value)
                if (!updateForegroundCall()) return@launch
                check(callAudio.acquire()) { "Another call is using audio" }
                callAudio.mediaActive(true)
                configureAudioRouting(session.video)
                (room.audioHandler as? AudioSwitchHandler)?.selectDevice(null)
                val initialRoom = room
                room.connect(
                    url = session.url,
                    token = session.token,
                    // Publishing the microphone as part of the connect handshake instead of
                    // as a separate round trip after it. Enabling it afterwards costs another
                    // negotiation before the first audio packet can flow, which is exactly
                    // the gap an answerer hears as silence right after they pick up.
                    options = ConnectOptions(audio = false, video = false),
                )
                if (terminated || room !== initialRoom || connection?.callId != session.callId ||
                    primaryHold != null || mutableState.value.switchingCall) {
                    if (terminated) initialRoom.disconnect()
                    return@launch
                }
                // Idempotent: the handshake above normally published these already. Kept so
                // a server or SDK path that declined to publish during connect still ends up
                // with two-way media rather than a silent call.
                if (!callAudio.available) {
                    holdCurrent(CallHoldReason.INTERRUPTION)
                    return@launch
                }
                val microphoneEnabled = initialRoom.localParticipant.setMicrophoneEnabled(true)
                if (terminated || room !== initialRoom || connection?.callId != session.callId ||
                    primaryHold != null || mutableState.value.switchingCall) {
                    if (terminated) initialRoom.disconnect()
                    return@launch
                }
                check(microphoneEnabled) { "The microphone could not start" }
                val cameraEnabled = session.video && callAudio.available &&
                    initialRoom.localParticipant.setCameraEnabled(true)
                if (terminated || room !== initialRoom || connection?.callId != session.callId ||
                    primaryHold != null || mutableState.value.switchingCall) {
                    if (terminated) initialRoom.disconnect()
                    return@launch
                }
                val localTrack = initialRoom.localParticipant
                    .getTrackPublication(Track.Source.CAMERA)?.track as? LocalVideoTrack
                mutableState.value = mutableState.value.copy(
                    phase = CallAnswerRouting.phaseAfterConnect(
                        hasRemoteParticipants = room.remoteParticipants.isNotEmpty(),
                        incoming = incomingCallId != null,
                        alreadyAnswered = answeredCallId.equals(session.callId, ignoreCase = true),
                    ),
                    cameraEnabled = cameraEnabled,
                    localVideoTrack = localTrack,
                    error = if (session.video && !cameraEnabled) {
                        "The camera could not start. You can continue with audio or try again."
                    } else null,
                )
                syncRemoteParticipants()
                if (!updateForegroundCall()) return@launch
                if (mutableState.value.phase == CallPhase.CONNECTED) {
                    markConnected()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (primaryHold != null || mutableState.value.switchingCall) return@launch
                if (!terminated && incomingCallId == null && connection == null &&
                    error.isKitConnectivityError()
                ) {
                    scheduleOfflineStartRetry(requestedVideo, error)
                } else if (!terminated) {
                    fail(error)
                }
            }
        }
    }

    private fun scheduleOfflineStartRetry(requestedVideo: Boolean, error: Throwable) {
        mutableState.value = mutableState.value.copy(
            phase = CallPhase.ERROR,
            error = error.userMessage(),
        )
        if (offlineStartRetryJob?.isActive == true) return
        val retryDelayMillis = offlineCallRetryDelayMillis(offlineStartRetryAttempt)
        offlineStartRetryAttempt++
        offlineStartRetryJob = viewModelScope.launch {
            delay(retryDelayMillis)
            offlineStartRetryJob = null
            startJob = null
            if (!terminated && connection == null && mutableState.value.phase == CallPhase.ERROR) {
                connect(requestedVideo)
            }
        }
    }

    fun retry() {
        if (primaryHold != null) { resumeHeldCall(); return }
        if (cleanupJob?.isActive == true || terminationJob?.isActive == true) return
        if (!pendingTerminations.isEmpty) {
            retryPendingTerminations()
            return
        }
        if (incomingCallId != null && !mutableState.value.incomingVerified) {
            validateIncomingCall()
            return
        }
        offlineStartRetryJob?.cancel()
        offlineStartRetryJob = null
        offlineStartRetryAttempt = 0
        startJob = null
        if (incomingCallId != null) accept(mutableState.value.video)
        else start(mutableState.value.video)
    }

    fun decline() {
        if (incomingCallId != null) terminate("cancelled")
    }

    fun toggleMute() {
        val enable = mutableState.value.muted
        changeMedia("The microphone could not be changed. Please try again.") {
            room.localParticipant.setMicrophoneEnabled(enable)
        }
    }

    fun toggleCamera() {
        if (mutableState.value.cameraEnabled) switchToAudio() else switchToVideo()
    }

    /** Publish the camera without disconnecting working audio if capture cannot start. */
    fun switchToVideo() {
        changeMedia("The camera could not start. Check camera access and try again.") {
            if (!updateForegroundCall(camera = true)) return@changeMedia false
            room.localParticipant.setCameraEnabled(true)
        }
    }

    fun switchToAudio() {
        changeMedia("The camera could not be turned off. Please try again.") {
            room.localParticipant.setCameraEnabled(false)
        }
    }

    fun startScreenShare(mediaProjectionData: Intent) {
        changeMedia("Screen sharing could not start. Please try again.") {
            room.localParticipant.setScreenShareEnabled(
                true,
                ScreenCaptureParams(mediaProjectionData, onStop = {
                    // Read the current publication: a delayed stop from an old capture must not
                    // clear a replacement share that has already started.
                    viewModelScope.launch { syncLocalMediaState() }
                }),
            )
        }
    }

    fun stopScreenShare() {
        changeMedia("Screen sharing could not stop. Please try again.") {
            room.localParticipant.setScreenShareEnabled(false)
        }
    }

    private fun changeMedia(failureMessage: String, change: suspend () -> Boolean) {
        if (terminated || !callAudio.available || mutableState.value.switchingCall || mediaOperations.isActive ||
            mutableState.value.phase !in setOf(CallPhase.CONNECTED, CallPhase.RECONNECTING)
        ) return
        val callId = connection?.callId ?: return
        mutableState.value = mutableState.value.copy(mediaChanging = true, error = null)
        mediaOperations.launch(viewModelScope) { isCurrent ->
            try {
                val changed = change()
                if (isCurrent() && !terminated && connection?.callId == callId) {
                    if (!changed) mutableState.value = mutableState.value.copy(error = failureMessage)
                    syncLocalMediaState()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (isCurrent() && !terminated && connection?.callId == callId) {
                    mutableState.value = mutableState.value.copy(error = failureMessage)
                    syncLocalMediaState()
                }
            } finally {
                if (isCurrent() && !terminated && connection?.callId == callId) {
                    mutableState.value = mutableState.value.copy(mediaChanging = false)
                }
            }
        }
    }

    /** Invites another Kit Pay user into this call, turning a one-to-one call into a group call. */
    fun addParticipant(userId: String) = addParticipants(listOf(userId))

    fun shareInvitation(onReady: (String) -> Unit) {
        val callId = connection?.callId ?: return
        val owner = sessionOwner ?: return
        if (!ownsSession() || !mutableState.value.canShareInvitation || mutableState.value.sharingInvitation) return
        mutableState.value = mutableState.value.copy(sharingInvitation = true)
        viewModelScope.launch {
            try {
                val link = scheduledCalls.shareCall(callId, owner)
                if (ownsSession() && connection?.callId == callId) onReady(link.shareUrl)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (ownsSession()) mutableState.value = mutableState.value.copy(error = "Could not create an invitation. Try again.")
            } finally {
                if (ownsSession()) mutableState.value = mutableState.value.copy(sharingInvitation = false)
            }
        }
    }

    fun addParticipants(userIds: List<String>) {
        val callId = connection?.callId ?: return
        val allowed = callableContacts.value.map { it.id.lowercase() }.toSet()
        val ids = userIds.mapNotNull(::canonicalCallUserId).distinct()
        if (!ownsSession() || mutableState.value.addingParticipants || mutableState.value.switchingCall ||
            primaryHold != null || ids.isEmpty() || ids.any { it !in allowed } ||
            ids.size + mutableState.value.participantUserIds.size > 20) return
        mutableState.value = mutableState.value.copy(addingParticipants = true, error = null)
        viewModelScope.launch {
            runCatching { calls.invite(callId, ids) }
                .onSuccess {
                    if (ownsSession() && connection?.callId == callId) {
                        mutableState.value = mutableState.value.copy(
                            participantUserIds = (mutableState.value.participantUserIds + ids).distinct())
                        reconcileCallStatus()
                    }
                }
                .onFailure { error ->
                    if (ownsSession() && connection?.callId == callId) {
                        mutableState.value = mutableState.value.copy(error = error.userMessage())
                    }
                }
            if (ownsSession()) mutableState.value = mutableState.value.copy(addingParticipants = false)
        }
    }

    /** Rebuilds the remote-participant grid from the room and re-derives the video/voice layout. */
    private fun syncRemoteParticipants() {
        if (terminated || primaryHold != null) return
        val participants = room.remoteParticipants.values.map { participant ->
            val identity = participant.identity?.value
            val video = selectRemoteCallVideo(
                participant.trackPublications.values
                    .filter { it.kind == Track.Kind.VIDEO }
                    .map { publication ->
                        CallVideoPublication(
                            track = publication.track as? VideoTrack,
                            source = when (publication.source) {
                                Track.Source.SCREEN_SHARE -> CallVideoSource.SCREEN_SHARE
                                Track.Source.CAMERA -> CallVideoSource.CAMERA
                                else -> CallVideoSource.OTHER
                            },
                            muted = publication.muted,
                        )
                    },
            )
            val presentation = resolveRoomParticipant(
                identity = identity,
                serverName = participant.name,
                contacts = contacts.contacts.value,
                participants = activeContactPresentationSource()?.participants.orEmpty(),
            )
            RemoteCallParticipant(
                id = identity ?: participant.hashCode().toString(),
                name = presentation.name,
                videoTrack = video?.track,
                screenSharing = video?.source == CallVideoSource.SCREEN_SHARE,
                speaking = participant.isSpeaking,
                serverName = participant.name,
                avatarUrl = presentation.avatarUrl,
                accountVerification = presentation.accountVerification,
            )
        }
        val showsVideo = mutableState.value.cameraEnabled ||
            mutableState.value.screenSharing ||
            participants.any { it.videoTrack != null }
        if (showsVideo != mutableState.value.video) configureAudioRouting(showsVideo)
        mutableState.value = mutableState.value.copy(
            remoteParticipants = participants,
            video = showsVideo,
        )
        applyContactPresentation(contacts.contacts.value)
        updateForegroundCall()
    }

    private fun syncLocalMediaState() {
        if (terminated || mutableState.value.phase !in setOf(CallPhase.CONNECTED, CallPhase.RECONNECTING)) {
            return
        }
        val camera = room.localParticipant.getTrackPublication(Track.Source.CAMERA)
        val screen = room.localParticipant.getTrackPublication(Track.Source.SCREEN_SHARE)
        val microphone = room.localParticipant.getTrackPublication(Track.Source.MICROPHONE)
        mutableState.value = mutableState.value.copy(
            cameraEnabled = camera?.track != null && camera.muted == false,
            localVideoTrack = camera?.track as? VideoTrack,
            screenSharing = screen?.track != null && screen.muted == false,
            muted = microphone?.muted ?: mutableState.value.muted,
        )
        syncRemoteParticipants()
        updateForegroundCall()
        publishPresence()
    }

    fun flipCamera() {
        if (terminated || !callAudio.available || mutableState.value.switchingCall || mediaOperations.isActive ||
            mutableState.value.phase !in setOf(CallPhase.CONNECTED, CallPhase.RECONNECTING)
        ) return
        val track = room.localParticipant.getTrackPublication(Track.Source.CAMERA)
            ?.track as? LocalVideoTrack ?: return
        val next = when (track.options.position) {
            CameraPosition.FRONT -> CameraPosition.BACK
            CameraPosition.BACK -> CameraPosition.FRONT
            else -> null
        }
        track.switchCamera(position = next)
    }

    fun toggleSpeaker() {
        val desired = if (mutableState.value.speakerEnabled) {
            mutableState.value.audioDevices.firstOrNull { it is AudioDevice.Earpiece }
        } else {
            mutableState.value.audioDevices.firstOrNull { it is AudioDevice.Speakerphone }
        }
        selectAudioDevice(desired)
    }

    fun end(reason: String = "completed") {
        if (heldSession != null && !terminated) {
            endCurrentAndResume(reason)
            return
        }
        terminate(reason)
    }

    fun setScreenForeground(foreground: Boolean) { screenForeground = foreground }

    /** Silences current and future tracks immediately, before any suspend/network operation. */
    private fun hardMuteRoom() {
        room.setMicrophoneMute(true)
        room.setSpeakerMute(true)
        room.localParticipant.trackPublications.values.forEach { it.track?.enabled = false }
    }

    private fun snapshot(reason: CallHoldReason): HeldCallSnapshot? = connection?.let {
        HeldCallSnapshot(it.copy(token = ""), mutableState.value.copy(
            remoteParticipants = emptyList(), localVideoTrack = null, screenSharing = false,
            waitingCall = null, heldCallName = null, switchingCall = false,
        ), durationAnchor, reason)
    }

    private fun ownsSession(): Boolean = !terminated && sessions.current()?.fence() == sessionOwner

    private suspend fun pauseMedia() {
        hardMuteRoom()
        mediaOperations.retire()?.join()
        // Never retain/replay MediaProjection consent across a hold.
        var stopped = true
        try {
            if (runCatching { room.localParticipant.setScreenShareEnabled(false) }.isFailure) stopped = false
            if (runCatching { room.localParticipant.setCameraEnabled(false) }.isFailure) stopped = false
            if (runCatching { room.localParticipant.setMicrophoneEnabled(false) }.isFailure) stopped = false
            if (runCatching {
                (room.localParticipant.getTrackPublication(Track.Source.MICROPHONE)?.track as? LocalAudioTrack)
                    ?.let { room.localParticipant.unpublishTrack(it) }
            }.isFailure) stopped = false
        } finally {
            (room.audioHandler as? AudioSwitchHandler)?.stop()
            callAudio.mediaActive(false)
            // A capture failure must release the devices even on an old-peer local hold.
            if (!stopped) replaceRoom()
        }
    }

    private fun replaceRoom() {
        roomEventsJob?.cancel()
        (room.audioHandler as? AudioSwitchHandler)?.unregisterAudioDeviceChangeListener(audioDeviceListener)
        room.disconnect()
        room.release()
        if (terminated) return
        room = createCallRoom()
        (room.audioHandler as? AudioSwitchHandler)?.apply {
            manageAudioFocus = false
            registerAudioDeviceChangeListener(audioDeviceListener)
        }
        hardMuteRoom()
        observeRoom(room)
    }

    fun holdCurrent(reason: CallHoldReason = CallHoldReason.MANUAL) {
        if (!ownsSession() || primaryHold != null || switchJob?.isActive == true) return
        if (mutableState.value.phase !in setOf(CallPhase.CONNECTED, CallPhase.RECONNECTING,
                CallPhase.CONNECTING, CallPhase.RINGING)) return
        val saved = snapshot(reason) ?: return
        if (reason != CallHoldReason.INTERRUPTION && !saved.session.canHold) return
        primaryHold = saved.copy(localOnly = true)
        hardMuteRoom()
        mutableState.value = mutableState.value.copy(phase = CallPhase.HELD, holdReason = reason,
            switchingCall = true, cameraEnabled = false, screenSharing = false, localVideoTrack = null)
        telecom.markHeld(saved.session.callId)
        publishPresence()
        switchJob = viewModelScope.launch {
            try {
                pauseMedia()
                if (saved.session.canHold) {
                    val truth = calls.hold(saved.session.callId, saved.session.holdRevision,
                        interruption = reason == CallHoldReason.INTERRUPTION, expectedOwner = sessionOwner)
                    if (!ownsSession()) return@launch
                    check(truth.isHeld) { "The call could not be held" }
                    primaryHold = saved.copy(session = saved.session.copy(holdRevision = truth.holdRevision))
                    connection = primaryHold?.session
                    replaceRoom()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // An old peer or unavailable server cannot authorize fresh RTC credentials. Keep
                // the existing identity silent until authenticated reconciliation says otherwise.
                runCatching { calls.status(saved.session.callId, sessionOwner) }.getOrNull()?.let { truth ->
                    if (truth.isHeld && ownsSession()) {
                        primaryHold = saved.copy(session = saved.session.copy(holdRevision = truth.holdRevision))
                        connection = primaryHold?.session
                        replaceRoom()
                    }
                }
                if (ownsSession()) mutableState.value = mutableState.value.copy(
                    error = "Call paused. Tap Resume when you're ready.")
            } finally {
                if (ownsSession()) {
                    mutableState.value = mutableState.value.copy(switchingCall = false)
                    updateForegroundCall(camera = false)
                    switchJob = null
                    resumeAfterAudioReturn()
                }
            }
        }
    }

    /** Accepts only an authenticated waiting identity, retaining the original logical call. */
    fun answerWaitingCall(callId: String? = mutableState.value.waitingCall?.callId) {
        val current = connection ?: return
        val waitingId = callId ?: return
        if (!ownsSession() || waitingId == current.callId || !CallHoldPolicy.maySwitch(
                current.canHold, heldSession != null, switchJob?.isActive == true)) return
        if (!callAudio.available) {
            mutableState.value = mutableState.value.copy(error = "Finish the other call before answering.")
            return
        }
        val saved = primaryHold ?: snapshot(CallHoldReason.WAITING) ?: return
        acceptedWaitingCallId = waitingId
        acceptInFlightCallId = waitingId
        hardMuteRoom()
        mutableState.value = mutableState.value.copy(switchingCall = true, error = null)
        switchJob = viewModelScope.launch {
            var waiting: HeldCallSnapshot? = null
            try {
                val incoming = calls.incoming(waitingId)
                if (!ownsSession()) return@launch
                waiting = HeldCallSnapshot(CallConnection(
                    callId = incoming.callId, name = incoming.name, phone = incoming.phone,
                    participantUserIds = incoming.participantUserIds, participants = incoming.participants,
                    avatarUrl = incoming.avatarUrl, accountVerification = incoming.accountVerification,
                    video = incoming.video, provider = "livekit", url = "", token = "", room = "",
                ), ActiveCallUiState(name = incoming.name, incoming = true, incomingVerified = true,
                    video = incoming.video, cameraEnabled = incoming.video), null, CallHoldReason.WAITING)
                pauseMedia()
                telecom.markAnswering(waitingId)
                val sourceTruth = calls.status(current.callId, sessionOwner)
                val next = calls.accept(waitingId, current.callId, requireNotNull(sourceTruth.holdRevision), sessionOwner)
                if (!ownsSession()) return@launch
                ringDeadlines.retire(waitingId, IncomingCallRetirementDisposition.ANSWERED_ELSEWHERE)
                heldSession = confirmedHeld(saved, next.heldCall)
                primaryHold = null
                telecom.markHeld(current.callId)
                telecom.updatePresentation(next.callId, next.name, next.phone, next.video)
                connectResumed(next, requireNotNull(waiting), replace = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                restoreAfterSwitchFailure(saved, waiting, waitingId, uncertainAnswer = true)
            } finally {
                acceptInFlightCallId = null
                finishSwitch()
            }
        }
    }

    /** Resumes the primary held call, or atomically swaps the live and secondary held calls. */
    fun resumeHeldCall() {
        if (!ownsSession() || switchJob?.isActive == true) return
        val target = primaryHold ?: heldSession ?: return
        if (!callAudio.acquire()) {
            mutableState.value = mutableState.value.copy(error = "Waiting for the other call to release audio.")
            return
        }
        val current = if (primaryHold == null) snapshot(CallHoldReason.WAITING) else null
        hardMuteRoom()
        mutableState.value = mutableState.value.copy(switchingCall = true, error = null)
        switchJob = viewModelScope.launch {
            var resumeRemaining = false
            try {
                pauseMedia()
                pendingAnswerCancellationId?.let { pendingId ->
                    calls.end(pendingId, "cancelled", requireNotNull(sessionOwner))
                    if (!ownsSession()) return@launch
                    pendingAnswerCancellationId = null
                    if (heldSession?.session?.callId == pendingId) heldSession = null
                    telecom.finish(pendingId, KitTelecomDisconnect.LOCAL)
                    ringDeadlines.retire(pendingId, IncomingCallRetirementDisposition.LOCAL)
                }
                val truth = calls.status(target.session.callId, sessionOwner)
                if (!ownsSession()) return@launch
                if (truth.terminal) {
                    if (current != null) heldSession = null else primaryHold = null
                    telecom.finish(target.session.callId, KitTelecomDisconnect.REMOTE)
                    if (current == null && heldSession != null) {
                        val remaining = requireNotNull(heldSession)
                        replaceRoom()
                        connection = remaining.session
                        primaryHold = remaining
                        heldSession = null
                        mutableState.value = remaining.presentation.copy(phase = CallPhase.HELD,
                            holdReason = remaining.reason, heldCallName = null)
                        resumeRemaining = true
                    } else if (current == null) {
                        finishFromRemote(target.session.callId, KitTelecomDisconnect.REMOTE)
                    } else {
                        // The other call ended while held. Restore this active call safely.
                        val currentTruth = calls.status(current.session.callId, sessionOwner)
                        val restored = calls.resume(current.session.callId, requireNotNull(currentTruth.holdRevision), expectedOwner = sessionOwner)
                        connectResumed(restored, current, replace = true)
                    }
                    return@launch
                }
                val localResume = target.localOnly && !truth.isHeld && current == null &&
                    room.state == Room.State.CONNECTED
                val other = current ?: heldSession
                val otherTruth = other?.let { calls.status(it.session.callId, sessionOwner) }?.takeUnless { it.terminal }
                val resumed = if (localResume) target.session.copy(canHold = truth.canHold,
                    holdRevision = truth.holdRevision, participants = truth.participants,
                    participantUserIds = truth.participants.map { it.userId }) else calls.resume(
                    target.session.callId, requireNotNull(truth.holdRevision), otherTruth?.callId,
                    otherTruth?.holdRevision, expectedOwner = sessionOwner)
                if (!ownsSession()) return@launch
                if (current != null) {
                    heldSession = confirmedHeld(current, resumed.heldCall)
                    telecom.markHeld(current.session.callId)
                }
                primaryHold = null
                connectResumed(resumed, target, replace = !localResume)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (current != null) restoreAfterSwitchFailure(current, target, target.session.callId)
                else if (ownsSession()) {
                    primaryHold = target.copy(session = connection?.takeIf { it.callId == target.session.callId }
                        ?.copy(token = "") ?: target.session, localOnly = false)
                    hardMuteRoom()
                    pauseMedia()
                    replaceRoom()
                    mutableState.value = mutableState.value.copy(phase = CallPhase.HELD,
                        holdReason = target.reason, localVideoTrack = null, cameraEnabled = false, screenSharing = false,
                        error = "Could not resume. Check your connection and try again.")
                }
            } finally {
                finishSwitch()
                if (resumeRemaining && callAudio.available) resumeHeldCall()
            }
        }
    }

    private suspend fun connectResumed(next: CallConnection, saved: HeldCallSnapshot, replace: Boolean) {
        check(ownsSession())
        if (replace) replaceRoom()
        connection = next
        ownedCallIds.add(next.callId)
        durationAnchor = saved.anchor
        applyAnswerAnchor(next.callId, next.answeredAt, next.serverTime)
        answeredCallId = next.callId
        val enableCamera = CallHoldPolicy.restoreCamera(saved.presentation.cameraEnabled,
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
            screenForeground)
        mutableState.value = saved.presentation.copy(name = next.name, avatarUrl = next.avatarUrl,
            accountVerification = next.accountVerification, phase = CallPhase.CONNECTING, canHold = next.canHold,
            holdReason = null, heldCallName = heldSession?.presentation?.name, switchingCall = true,
            participantUserIds = next.participantUserIds,
            canShareInvitation = mutableState.value.canShareInvitation,
            cameraEnabled = false, screenSharing = false, localVideoTrack = null, remoteParticipants = emptyList())
        if (!updateForegroundCall(camera = enableCamera)) return
        callAudio.mediaActive(true)
        configureAudioRouting(saved.presentation.video)
        if (replace) room.connect(next.url, next.token, ConnectOptions(audio = false, video = false))
        else (room.audioHandler as? AudioSwitchHandler)?.start()
        if (!ownsSession()) return
        if (!callAudio.available) {
            primaryHold = saved.copy(session = next.copy(token = ""), reason = CallHoldReason.INTERRUPTION, localOnly = true)
            mutableState.value = mutableState.value.copy(phase = CallPhase.HELD, holdReason = CallHoldReason.INTERRUPTION)
            pauseMedia()
            telecom.markHeld(next.callId)
            return
        }
        mediaOperations.open()
        room.localParticipant.setMicrophoneEnabled(!saved.presentation.muted)
        if (!ownsSession()) { hardMuteRoom(); return }
        if (!callAudio.available) { pauseDuringTransition(next, saved); return }
        if (enableCamera) room.localParticipant.setCameraEnabled(true)
        if (!ownsSession()) { hardMuteRoom(); return }
        if (!callAudio.available) { pauseDuringTransition(next, saved); return }
        room.setMicrophoneMute(false)
        room.setSpeakerMute(false)
        (room.audioHandler as? AudioSwitchHandler)?.let { handler ->
            saved.presentation.selectedAudioDevice?.let { selected ->
                handler.availableAudioDevices.firstOrNull { it == selected }?.let(handler::selectDevice)
            }
        }
        mutableState.value = mutableState.value.copy(phase = CallPhase.CONNECTED, switchingCall = false)
        syncLocalMediaState()
        syncRemoteParticipants()
        markConnected()
    }

    private suspend fun pauseDuringTransition(next: CallConnection, saved: HeldCallSnapshot) {
        primaryHold = saved.copy(session = next.copy(token = ""), reason = CallHoldReason.INTERRUPTION,
            localOnly = true)
        mutableState.value = mutableState.value.copy(phase = CallPhase.HELD,
            holdReason = CallHoldReason.INTERRUPTION, cameraEnabled = false, screenSharing = false)
        hardMuteRoom()
        pauseMedia()
        telecom.markHeld(next.callId)
    }

    private fun clearActivePresence() {
        if (activeCallState.activeCallId.value in ownedCallIds) activeCallState.setActiveCall(null)
    }

    private fun confirmedHeld(saved: HeldCallSnapshot, truth: com.kit.wallet.data.repository.CallStatus?): HeldCallSnapshot =
        saved.copy(session = saved.session.copy(holdRevision = truth?.takeIf { it.callId == saved.session.callId }
            ?.holdRevision ?: saved.session.holdRevision), localOnly = false)

    private suspend fun restoreAfterSwitchFailure(saved: HeldCallSnapshot, other: HeldCallSnapshot?, otherId: String,
        uncertainAnswer: Boolean = false) {
        if (!ownsSession()) return
        primaryHold = saved.copy(localOnly = false)
        connection = primaryHold?.session
        replaceRoom()
        var cancelledAnswer = false
        try {
            val restored = if (uncertainAnswer) {
                pendingAnswerCancellationId = otherId
                CallSwitchRecovery.cancelUncertainAnswer(calls, saved.session.callId, otherId, requireNotNull(sessionOwner)) {
                    cancelledAnswer = true
                    pendingAnswerCancellationId = null
                }
            } else CallSwitchRecovery.restoreUncertainSwap(calls, saved.session.callId, otherId, requireNotNull(sessionOwner))
            if (!ownsSession()) return
            heldSession = if (uncertainAnswer) null else other?.let { confirmedHeld(it, restored.heldCall) }
            if (uncertainAnswer) {
                telecom.finish(otherId, KitTelecomDisconnect.LOCAL)
                ringDeadlines.retire(otherId, IncomingCallRetirementDisposition.LOCAL)
            }
            primaryHold = null
            connectResumed(restored, saved, replace = true)
            mutableState.value = mutableState.value.copy(error = "Could not switch. Your previous call is restored.")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            if (ownsSession()) {
                primaryHold = saved.copy(session = connection?.takeIf { it.callId == saved.session.callId }
                    ?.copy(token = "") ?: saved.session, localOnly = false)
                hardMuteRoom()
                pauseMedia()
                replaceRoom()
                heldSession = other.takeUnless { cancelledAnswer }
                mutableState.value = saved.presentation.copy(phase = CallPhase.HELD,
                    holdReason = saved.reason, heldCallName = heldSession?.presentation?.name,
                    error = "Both calls are paused. Check your connection and tap Resume.")
            }
        }
    }

    private fun finishSwitch() {
        switchJob = null
        if (!ownsSession()) return
        mutableState.value = mutableState.value.copy(switchingCall = false,
            heldCallName = heldSession?.presentation?.name, holdReason = primaryHold?.reason)
        if (primaryHold == null && mutableState.value.phase == CallPhase.CONNECTED) markConnected()
        else publishPresence()
        val deferredResume = resumeAfterInterruption
        resumeAfterInterruption = false
        heldSession?.session?.callId?.let { heldId ->
            if (terminalCallIds.remove(heldId)) {
                telecom.finish(heldId, KitTelecomDisconnect.REMOTE)
                heldSession = null
                mutableState.value = mutableState.value.copy(heldCallName = null)
            }
        }
        if (terminalCallIds.remove(connection?.callId)) {
            connection?.callId?.let { finishFromRemote(it, KitTelecomDisconnect.REMOTE) }
        }
        if (deferredResume || requestedSystemResumeCallId != null) resumeAfterAudioReturn()
    }

    private fun resumeAfterAudioReturn() {
        if (!ownsSession()) return
        val held = primaryHold ?: run {
            requestedSystemResumeCallId = null
            return
        }
        val explicitlyRequested = requestedSystemResumeCallId == held.session.callId
        val switching = switchJob?.isActive == true
        if (CallHoldPolicy.mayResume(held.reason, callAudio.available, switching, explicitlyRequested)) {
            requestedSystemResumeCallId = null
            resumeHeldCall()
        } else if (switching && callAudio.available &&
            (explicitlyRequested || held.reason == CallHoldReason.INTERRUPTION)) {
            resumeAfterInterruption = true
        }
    }

    private fun endCurrentAndResume(reason: String, remoteCompletion: Boolean = false) {
        if (switchJob?.isActive == true) return
        val ending = connection ?: return
        val remaining = heldSession ?: return
        hardMuteRoom()
        mutableState.value = mutableState.value.copy(switchingCall = true)
        switchJob = viewModelScope.launch {
            try {
                pauseMedia()
                if (!remoteCompletion) calls.end(ending.callId, reason, requireNotNull(sessionOwner))
                if (!ownsSession()) return@launch
                telecom.finish(ending.callId, KitTelecomDisconnect.LOCAL)
                replaceRoom()
                connection = remaining.session
                primaryHold = remaining
                heldSession = null
                mutableState.value = remaining.presentation.copy(phase = CallPhase.HELD,
                    holdReason = remaining.reason)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                primaryHold = snapshot(CallHoldReason.MANUAL)?.copy(localOnly = true)
                mutableState.value = mutableState.value.copy(phase = CallPhase.HELD,
                    error = "Could not end the call. Check your connection and try again.")
            } finally {
                finishSwitch()
            }
            if (connection?.callId == remaining.session.callId && callAudio.available) resumeHeldCall()
        }
    }

    private var statusJob: Job? = null
    private fun reconcileCallStatus() {
        val callId = connection?.callId ?: return
        if (statusJob?.isActive == true || mutableState.value.switchingCall) return
        statusJob = viewModelScope.launch {
            runCatching { calls.status(callId, sessionOwner) }.getOrNull()?.let { truth ->
                if (!ownsSession() || connection?.callId != callId || mutableState.value.switchingCall) return@let
                connection = connection?.copy(canHold = truth.canHold, holdRevision = truth.holdRevision,
                    participants = truth.participants, participantUserIds = truth.participants.map { it.userId })
                mutableState.value = mutableState.value.copy(canHold = truth.canHold,
                    participantUserIds = truth.participants.map { it.userId })
                telecom.setHoldSupported(callId, truth.canHold)
                if (truth.terminal) finishFromRemote(callId, KitTelecomDisconnect.REMOTE)
                else if (truth.isHeld && primaryHold == null) {
                    hardMuteRoom()
                    primaryHold = snapshot(CallHoldReason.MANUAL)
                    mediaOperations.retire()?.join()
                    replaceRoom()
                    mutableState.value = mutableState.value.copy(phase = CallPhase.HELD, holdReason = CallHoldReason.MANUAL,
                        localVideoTrack = null, cameraEnabled = false, screenSharing = false)
                    telecom.markHeld(callId)
                }
            }
        }
    }

    private fun enqueueHeldTermination() {
        pendingAnswerCancellationId?.let { callId ->
            pendingTerminations.enqueue(PendingCallTermination(callId, BackendCallTerminationKind.END, "cancelled"))
        }
        acceptInFlightCallId?.takeIf { it != connection?.callId && it != heldSession?.session?.callId }?.let { callId ->
            pendingTerminations.enqueue(PendingCallTermination(callId, BackendCallTerminationKind.END, "cancelled"))
            telecom.finish(callId, KitTelecomDisconnect.LOCAL)
        }
        heldSession?.session?.callId?.let { callId ->
            telecom.finish(callId, KitTelecomDisconnect.LOCAL)
            pendingTerminations.enqueue(PendingCallTermination(callId, BackendCallTerminationKind.END, "cancelled"))
        }
        heldSession = null
        primaryHold = null
    }

    fun permissionDenied() {
        mutableState.value = mutableState.value.copy(
            phase = CallPhase.ERROR,
            error = "Microphone access is required for calls. Camera access is also required for video calls.",
        )
    }

    private fun validateIncomingCall() {
        val callId = incomingCallId ?: return
        if (validationJob?.isActive == true || terminated) return
        verifiedIncomingCall = null
        // Keep the ringing notification and its deadline alive while the authoritative lookup is
        // slow or temporarily offline. Only an answer/decline/terminal event may retire it.
        mutableState.value = mutableState.value.copy(
            name = "Incoming Kit Pay call",
            avatarUrl = null,
            accountVerification = null,
            video = false,
            incomingVerified = false,
            phase = CallPhase.VALIDATING,
            error = null,
        )
        validationJob = viewModelScope.launch {
            try {
                val incoming = calls.incoming(callId)
                if (terminated) return@launch
                val ringLease = callRingLease(
                    ringExpiresAt = incoming.ringExpiresAt,
                    serverTime = incoming.serverTime,
                    receivedElapsedRealtimeMillis = elapsedRealtimeClock.millis(),
                    bootSessionId = bootSessionIdProvider.currentBootId(),
                ) ?: error("This incoming call has expired")
                verifiedIncomingCall = incoming
                mutableState.value = mutableState.value.copy(
                    name = incoming.name,
                    avatarUrl = incoming.avatarUrl,
                    accountVerification = incoming.accountVerification,
                    video = incoming.video,
                    incomingVerified = true,
                    phase = CallPhase.INCOMING,
                    error = null,
                )
                telecom.trackIncoming(
                    callId,
                    incoming.name,
                    incoming.phone,
                    incoming.video,
                    incoming.ringExpiresAt,
                )
                localTelecomTermination.resolveCallId(callId)
                ringDeadlines.schedule(callId, ringLease)
                applyContactPresentation(contacts.contacts.value)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (!terminated) {
                    mutableState.value = mutableState.value.copy(
                        name = "Incoming Kit Pay call",
                        avatarUrl = null,
                        accountVerification = null,
                        video = false,
                        incomingVerified = false,
                        phase = CallPhase.ERROR,
                        error = "This incoming call could not be verified. It may have expired.",
                    )
                }
            }
        }
    }

    private fun applyContactPresentation(availableContacts: List<Contact>) {
        if (terminated || mutableState.value.phase in setOf(CallPhase.ENDING, CallPhase.ENDED)) return
        val refresh = refreshActiveCallContactPresentation(
            state = mutableState.value,
            activeSource = activeContactPresentationSource(),
            contacts = availableContacts,
        )
        if (refresh.state != mutableState.value) mutableState.value = refresh.state
        refresh.activeTelecom?.apply {
            telecom.updatePresentation(callId, name, phone, video)
        }
        refresh.waitingTelecom?.apply {
            telecom.updatePresentation(callId, name, phone, video)
        }
        publishPresence()
    }

    private fun activeContactPresentationSource(): ActiveCallContactPresentationSource? {
        val liveParticipantIds = mutableState.value.remoteParticipants
            .map { it.id.substringBefore(':').trim() }
            .filter(String::isNotEmpty)
        connection?.let { active ->
            return ActiveCallContactPresentationSource(
                callId = active.callId,
                serverName = active.name,
                participantUserIds = (
                    active.participantUserIds + liveParticipantIds + listOfNotNull(target)
                ).distinctBy(String::lowercase),
                participants = active.participants,
                fallbackPhone = active.phone,
            )
        }
        verifiedIncomingCall?.let { incoming ->
            return ActiveCallContactPresentationSource(
                callId = incoming.callId,
                serverName = incoming.name,
                participantUserIds = (incoming.participantUserIds + liveParticipantIds)
                    .distinctBy(String::lowercase),
                participants = incoming.participants,
                fallbackPhone = incoming.phone,
            )
        }
        if (incomingCallId != null || target == null) return null
        return ActiveCallContactPresentationSource(
            callId = null,
            serverName = target,
            participantUserIds = listOf(target),
            fallbackPhone = initialPresentation.phone,
        )
    }

    private suspend fun resolveRecipients(): List<String> {
        val multiple = target?.split(',').orEmpty()
        if (multiple.size > 1) {
            require(multiple.size <= 20) { "A call supports up to 20 invited contacts" }
            return multiple.map { requireNotNull(canonicalCallUserId(it)) }.distinct()
        }
        return listOf(resolveRecipient())
    }

    private suspend fun resolveRecipient(): String {
        val raw = requireNotNull(target) { "Choose a contact before starting a call" }
        // Laravel emits UUIDv7 identifiers; accept every RFC 9562 version supported by the API.
        val uuid = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
        if (raw.matches(uuid)) return raw

        var contact = contacts.contacts.value.firstOrNull { it.name.equals(raw, ignoreCase = true) }
        if (contact == null) {
            runCatching { contacts.refresh() }
            contact = contacts.contacts.value.firstOrNull { it.name.equals(raw, ignoreCase = true) }
        }
        return requireNotNull(contact?.takeIf { it.isKitUser }?.id) {
            "This conversation is not linked to a callable Kit Pay contact"
        }
    }

    private fun configureAudioRouting(video: Boolean) {
        (room.audioHandler as? AudioSwitchHandler)?.preferredDeviceList =
            callAudioDevicePreference(video)
    }

    private fun updateForegroundCall(camera: Boolean = mutableState.value.cameraEnabled): Boolean {
        val session = connection ?: return false
        if (terminated) return false
        val presentation = ForegroundCallPresentation(
            session.callId, mutableState.value.name, mutableState.value.video, camera, primaryHold != null,
        )
        if (presentation == foregroundCall) return true
        try {
            CallForegroundService.start(
                context, presentation.name, presentation.video,
                callId = presentation.callId, camera = presentation.camera, held = presentation.held,
            )
        } catch (_: RuntimeException) {
            foregroundCallUnavailable(session.callId)
            return false
        }
        foregroundCall = presentation
        return true
    }

    private fun foregroundCallUnavailable(callId: String) {
        if (!ownsSession() || callId != connection?.callId) return
        foregroundCall = null
        hardMuteRoom()
        fail(CallForegroundUnavailableException())
    }

    fun selectAudioDevice(device: AudioDevice?) {
        if (terminated || device != null && device !in mutableState.value.audioDevices) return
        val handler = room.audioHandler as? AudioSwitchHandler ?: return
        // A deliberate selection is sticky in AudioSwitch; automatic mode follows device changes.
        // The listener, rather than this request, acknowledges the route shown to the user.
        handler.selectDevice(device)
    }

    private fun terminate(reason: String) {
        if (terminationJob?.isActive == true ||
            mutableState.value.phase in setOf(CallPhase.ENDING, CallPhase.ENDED)
        ) {
            return
        }
        val safeReason = reason.takeIf { it in setOf("completed", "cancelled", "network_error") }
            ?: "cancelled"
        val telecomCallId = connection?.callId ?: incomingCallId
        val disconnect = when {
            safeReason == "network_error" -> KitTelecomDisconnect.ERROR
            connection == null && incomingCallId != null -> KitTelecomDisconnect.REJECTED
            else -> KitTelecomDisconnect.LOCAL
        }
        closeRingWindow(telecomCallId, disconnect.ringRetirementDisposition())
        if (telecomCallId != null) {
            telecom.finish(telecomCallId, disconnect)
        } else {
            // Outgoing POST /calls is still in flight. The deferred transition is delivered once
            // its response has been tracked with Telecom.
            localTelecomTermination.terminate(disconnect)
            outgoingClientCallId?.takeIf { outgoingAttemptSubmitted && !outgoingAttemptResolved }
                ?.let { attemptId ->
                applicationScope.launch { runCatching { calls.cancelAttempt(attemptId) } }
            }
        }
        terminated = true
        switchJob?.cancel()
        callAudio.abandon()
        enqueueHeldTermination()
        val retiringMedia = mediaOperations.retire()
        offlineStartRetryJob?.cancel()
        offlineStartRetryJob = null
        validationJob?.cancel()
        timerJob?.cancel()
        timerJob = null
        room.disconnect()
        if (ownedCallIds.isNotEmpty()) CallForegroundService.stop(context)
        clearActivePresence()
        clearWaitingCall()
        mutableState.value = mutableState.value.copy(
            phase = CallPhase.ENDING,
            remoteParticipants = emptyList(),
            localVideoTrack = null,
            error = null,
        )
        val connecting = startJob
        terminationJob = viewModelScope.launch {
            connecting?.join()
            retiringMedia?.join()
            room.disconnect()
            val activeCallId = connection?.callId
            if (activeCallId != null) {
                pendingTerminations.enqueue(
                    PendingCallTermination(
                        callId = activeCallId,
                        kind = BackendCallTerminationKind.END,
                        reason = safeReason,
                    ),
                )
            } else if (incomingCallId != null && mutableState.value.incomingVerified) {
                pendingTerminations.enqueue(
                    PendingCallTermination(
                        callId = incomingCallId,
                        kind = BackendCallTerminationKind.DECLINE,
                    ),
                )
            }
            drainPendingTerminations()
            connection = null
            startJob = null
            answeredCallId = null
            mutableState.value = mutableState.value.copy(phase = CallPhase.ENDED)
        }
    }

    private fun markConnected() {
        if (primaryHold != null || mutableState.value.switchingCall || terminated) return
        closeRingWindow(
            connection?.callId ?: incomingCallId,
            IncomingCallRetirementDisposition.ANSWERED_ELSEWHERE,
        )
        mutableState.value = mutableState.value.copy(phase = CallPhase.CONNECTED)
        // Media is up, so the call is running whether or not anything authoritative has
        // reached us yet. Anchoring here is never earlier than the real answer, so a
        // signal that arrives later can still correct the timer forward.
        (connection?.callId ?: incomingCallId)?.let { callId ->
            if (!durationAnchor?.callId.equals(callId, ignoreCase = true)) {
                durationAnchor = CallDurationAnchorPolicy.anchorOnConnect(callId, elapsedRealtime())
            }
        }
        (connection?.callId ?: incomingCallId)?.let { callId ->
            telecom.markActive(callId)
            telecom.setHoldSupported(callId, connection?.canHold == true)
        }
        // Mark this device busy so a second incoming call is surfaced as call-waiting, not a
        // full-screen ring over the active call.
        activeCallState.setActiveCall(connection?.callId)
        publishPresence()
        startTimer()
    }

    /**
     * Republishes the connected call to the surfaces outside this screen that show or return to
     * it: the ongoing-call notification's reopen link, the owning chat's live banner, and the
     * recent-chats row. Everything published comes from the authenticated session and this
     * screen's own resolved state — and only while genuinely connected, so a ringing, failed or
     * torn-down attempt never appears anywhere as a live call.
     */
    private fun publishPresence() {
        val session = connection ?: return
        if (terminated) return
        if (mutableState.value.phase !in setOf(CallPhase.CONNECTED, CallPhase.RECONNECTING, CallPhase.HELD)) return
        activeCallState.publishPresence(
            ActiveCallPresence(
                callId = session.callId,
                name = mutableState.value.name,
                participantUserIds = session.participantUserIds,
                conversationId = session.conversationId,
                video = mutableState.value.video,
                anchor = durationAnchor,
                ownerRouteCallId = incomingCallId,
                held = primaryHold != null,
            ),
        )
    }

    private fun handleLifecycleEvent(event: CallLifecycleEvent) {
        if (event.kind == CallLifecycleKind.PARTICIPANT_CHANGED) {
            if (event.callId == connection?.callId) reconcileCallStatus()
            return
        }
        if (event.callId.equals(heldSession?.session?.callId, ignoreCase = true)) {
            if (event.terminal) {
                telecom.finish(event.callId, KitTelecomDisconnect.REMOTE)
                heldSession = null
                mutableState.value = mutableState.value.copy(heldCallName = null)
            }
            return
        }
        if (mutableState.value.switchingCall && event.terminal) {
            terminalCallIds.add(event.callId)
            return
        }

        // A waiting call that ends, is missed or is declined elsewhere dismisses its banner.
        // Ignoring case throughout: a validated event carries the canonical lowercase id,
        // while ids taken verbatim from REST responses keep whatever case the server used,
        // and the same call must never fail to match itself over that difference.
        if (event.callId.equals(mutableState.value.waitingCall?.callId, ignoreCase = true)) {
            if (event.kind == CallLifecycleKind.ANSWERED || event.terminal) clearWaitingCall()
            return
        }

        val activeCallId = connection?.callId ?: incomingCallId
        if (!event.callId.equals(activeCallId, ignoreCase = true)) {
            // An outgoing call has no id until `POST /calls` answers, and on a slow uplink
            // the person called can pick up before it does. Holding the answer lets that
            // response claim it a moment later instead of dropping it for not matching a
            // call this screen could not yet name.
            if (activeCallId == null) pendingAnswers.remember(event)
            return
        }

        applyLifecycleEvent(event)
    }

    private fun applyLifecycleEvent(event: CallLifecycleEvent) {
        event.pendingLocalTermination()?.let { action ->
            pendingTerminations.enqueue(action)
            terminate(action.reason)
            return
        }
        when (event.kind) {
            CallLifecycleKind.PARTICIPANT_CHANGED -> reconcileCallStatus()
            CallLifecycleKind.ANSWERED -> {
                // Recorded before the action is chosen, so a start response that is still
                // in flight finds it when it decides whether the ring window is armed.
                // Every event reaching here already matched this attempt's call id, either
                // directly or by being claimed from the buffer with the id the response
                // named — that exact-id match is the session fence at this layer.
                answeredCallId = event.callId
                // Whatever else the answer means for this screen, it ends the ring window.
                // An armed deadline left ticking over an answered call — the answer can
                // land while `room.connect` is still in flight, after the deadline was
                // armed — would expire mid-call, finish Telecom as MISSED, and tear down a
                // call both sides are on. Cancelled under this screen's own id, the exact
                // string the deadline was scheduled with.
                closeRingWindow(
                    connection?.callId ?: incomingCallId,
                    IncomingCallRetirementDisposition.ANSWERED_ELSEWHERE,
                )
                // Applied on every answer signal, not only the one that moves the phase.
                // The socket frame, the push and the accept response all carry the same
                // instant, and taking each of them is what lets the earliest — and so the
                // least delayed — one set where the caller's timer counts from.
                applyAnswerAnchor(event.callId, event.answeredAt, event.serverTime)
                when (answerAction()) {
                    // The server sends the answer to the answering account as well as to
                    // the caller, precisely so this account's *other* devices stop ringing.
                    // Without this they keep ringing until the invite expires, and the user
                    // is left declining a call they are already on.
                    CallAnswerAction.SUPERSEDE_LOCAL_RING ->
                        finishFromRemote(event.callId, KitTelecomDisconnect.ANSWERED_ELSEWHERE)

                    CallAnswerAction.ADVANCE_TO_CONNECTING ->
                        mutableState.value = mutableState.value.copy(phase = CallPhase.CONNECTING)

                    CallAnswerAction.ANCHOR_ONLY -> Unit
                }
            }
            CallLifecycleKind.DECLINED -> if (event.terminal) {
                finishFromRemote(event.callId, KitTelecomDisconnect.REJECTED)
            }
            CallLifecycleKind.ENDED -> finishFromRemote(event.callId, KitTelecomDisconnect.REMOTE)
            CallLifecycleKind.MISSED -> finishFromRemote(event.callId, KitTelecomDisconnect.MISSED)
        }
    }

    /** Reads this screen's live state into the pure rule that decides what an answer does. */
    private fun answerAction(): CallAnswerAction = CallAnswerRouting.actionFor(
        phase = mutableState.value.phase,
        hasConnection = connection != null,
        starting = startJob?.isActive == true,
    )

    private fun finishFromRemote(callId: String, disconnect: KitTelecomDisconnect) {
        if (heldSession != null && switchJob?.isActive != true) {
            endCurrentAndResume("completed", remoteCompletion = true)
            return
        }

        if (terminationJob?.isActive == true ||
            mutableState.value.phase in setOf(CallPhase.ENDING, CallPhase.ENDED)
        ) {
            return
        }
        terminated = true
        switchJob?.cancel()
        callAudio.abandon()
        enqueueHeldTermination()
        val retiringMedia = mediaOperations.retire()
        ringDeadlines.retire(callId, disconnect.ringRetirementDisposition())
        telecom.finish(callId, disconnect)
        validationJob?.cancel()
        timerJob?.cancel()
        timerJob = null
        room.disconnect()
        if (ownedCallIds.isNotEmpty()) CallForegroundService.stop(context)
        clearActivePresence()
        clearWaitingCall()
        mutableState.value = mutableState.value.copy(
            phase = CallPhase.ENDING,
            remoteParticipants = emptyList(),
            localVideoTrack = null,
        )
        val connecting = startJob
        terminationJob = viewModelScope.launch {
            connecting?.join()
            retiringMedia?.join()
            room.disconnect()
            pendingTerminations.completed(callId)
            connection = null
            startJob = null
            answeredCallId = null
            mutableState.value = mutableState.value.copy(phase = CallPhase.ENDED)
        }
    }

    /**
     * Records where the call's timer counts from, from whatever answer signal just arrived.
     *
     * Called from every route the answer can take. The policy keeps the earliest anchor it
     * has been offered for the call, so repeated signals converge on the least-delayed one
     * and the displayed duration only ever moves forward.
     */
    private fun applyAnswerAnchor(callId: String, answeredAt: String?, serverTime: String?) {
        durationAnchor = CallDurationAnchorPolicy.anchor(
            callId = callId,
            answeredAt = answeredAt,
            serverTime = serverTime,
            elapsedRealtimeMillis = elapsedRealtime(),
            previous = durationAnchor,
        )
        if (timerJob?.isActive == true) publishDuration()
        publishPresence()
    }

    private fun startTimer() {
        if (timerJob?.isActive == true) return
        timerJob = viewModelScope.launch {
            while (true) {
                publishDuration()
                delay(1_000)
            }
        }
    }

    /**
     * Derived from the anchor rather than accumulated. A counter that adds one per tick
     * drifts by every scheduling delay the call survives — a doze window, a busy main
     * thread — and after a few minutes shows visibly less time than has passed.
     */
    private fun publishDuration() {
        val seconds = CallDurationAnchorPolicy.seconds(durationAnchor, elapsedRealtime())
        if (seconds != mutableState.value.durationSeconds) {
            mutableState.value = mutableState.value.copy(durationSeconds = seconds)
        }
    }

    private fun elapsedRealtime(): Long = SystemClock.elapsedRealtime()

    private fun retryPendingTerminations() {
        if (cleanupJob?.isActive == true) return
        val retryVideo = mutableState.value.video
        terminated = true
        mutableState.value = mutableState.value.copy(
            phase = CallPhase.ENDING,
            error = null,
        )
        cleanupJob = viewModelScope.launch {
            val cleared = drainPendingTerminations()
            terminated = false
            cleanupJob = null
            if (!cleared) {
                mutableState.value = mutableState.value.copy(
                    phase = CallPhase.ERROR,
                    error = "The previous call is still ending. Check your connection and try again.",
                )
            } else if (incomingCallId != null) {
                mutableState.value = mutableState.value.copy(phase = CallPhase.ENDED)
            } else {
                startJob = null
                start(retryVideo)
            }
        }
    }

    private suspend fun drainPendingTerminations(): Boolean =
        pendingTerminations.drain(::performBackendTermination)

    private suspend fun performBackendTermination(action: PendingCallTermination): Boolean =
        withTimeoutOrNull(3_000) {
            if (sessions.current()?.fence() != sessionOwner) return@withTimeoutOrNull false
            runCatching {
                when (action.kind) {
                    BackendCallTerminationKind.END -> calls.end(action.callId, action.reason, requireNotNull(sessionOwner))
                    BackendCallTerminationKind.DECLINE -> calls.decline(action.callId)
                }
            }.isSuccess
        } ?: false

    private fun fail(error: Throwable) {
        if (error !is CallForegroundUnavailableException &&
            heldSession != null && ownsSession() && switchJob?.isActive != true) {
            hardMuteRoom()
            primaryHold = snapshot(CallHoldReason.MANUAL)
            mutableState.value = mutableState.value.copy(phase = CallPhase.HELD,
                holdReason = CallHoldReason.MANUAL, error = "Call connection interrupted. Tap Resume to reconnect.")
            switchJob = viewModelScope.launch {
                mediaOperations.retire()?.join()
                replaceRoom()
                finishSwitch()
            }
            return
        }

        if (cleanupJob?.isActive == true || terminationJob?.isActive == true ||
            mutableState.value.phase in setOf(CallPhase.ENDING, CallPhase.ENDED)
        ) {
            return
        }
        terminated = true
        switchJob?.cancel()
        callAudio.abandon()
        enqueueHeldTermination()
        val retiringMedia = mediaOperations.retire()
        closeRingWindow(
            connection?.callId ?: incomingCallId,
            IncomingCallRetirementDisposition.ERROR,
        )
        timerJob?.cancel()
        timerJob = null
        room.disconnect()
        if (ownedCallIds.isNotEmpty()) CallForegroundService.stop(context)
        clearActivePresence()
        clearWaitingCall()
        mutableState.value = mutableState.value.copy(
            phase = CallPhase.ENDING,
            error = error.userMessage(),
            remoteParticipants = emptyList(),
            localVideoTrack = null,
        )
        val connecting = startJob
        cleanupJob = viewModelScope.launch {
            connecting?.join()
            retiringMedia?.join()
            room.disconnect()
            val failedCallId = connection?.callId
            connection = null
            if (failedCallId != null) {
                telecom.finish(failedCallId, KitTelecomDisconnect.ERROR)
                pendingTerminations.enqueue(
                    PendingCallTermination(
                        callId = failedCallId,
                        kind = BackendCallTerminationKind.END,
                        reason = "network_error",
                    ),
                )
            }
            drainPendingTerminations()
            startJob = null
            terminated = false
            mutableState.value = mutableState.value.copy(phase = CallPhase.ERROR)
        }
    }

    private fun Throwable.userMessage(): String = when {
        this is CallForegroundUnavailableException ->
            "Android could not keep this call active. Open Kit Pay and try again."
        // Offline/transport failures are transient and must never echo the call server's host or
        // IP address; keep the wording calm and reconnection-oriented like WhatsApp.
        isKitConnectivityError() ->
            "No internet connection. Kit Pay will reconnect the call automatically when you're back online."
        // A server-reported error already carries a clean, address-free message.
        this is KitWalletApiException -> message
        // Any other failure (e.g. a media-server error) stays generic so no connection internals leak.
        else -> "The secure call connection could not be established. Check your internet and try again."
    }

    override fun onCleared() {
        validationJob?.cancel()
        offlineStartRetryJob?.cancel()
        timerJob?.cancel()
        timerJob = null
        terminated = true
        switchJob?.cancel()
        callAudio.close()
        enqueueHeldTermination()
        (room.audioHandler as? AudioSwitchHandler)
            ?.unregisterAudioDeviceChangeListener(audioDeviceListener)
        val retiringMedia = mediaOperations.retire()
        val connecting = startJob
        room.disconnect()
        applicationScope.launch(Dispatchers.Main.immediate) {
            retiringMedia?.join()
            connecting?.join()
            room.disconnect()
            room.release()
        }
        if (ownedCallIds.isNotEmpty()) CallForegroundService.stop(context)
        clearActivePresence()
        val closingDisposition = if (
            connection == null &&
            incomingCallId != null &&
            mutableState.value.incomingVerified &&
            mutableState.value.phase !in setOf(CallPhase.ENDING, CallPhase.ENDED)
        ) {
            IncomingCallRetirementDisposition.REJECTED
        } else {
            IncomingCallRetirementDisposition.LOCAL
        }
        closeRingWindow(connection?.callId ?: incomingCallId, closingDisposition)
        connection?.callId?.let { activeCallId ->
            telecom.finish(activeCallId, KitTelecomDisconnect.LOCAL)
            pendingTerminations.enqueue(
                PendingCallTermination(activeCallId, BackendCallTerminationKind.END, "cancelled"),
            )
        }
        if (connection == null && incomingCallId != null && mutableState.value.incomingVerified &&
            mutableState.value.phase !in setOf(CallPhase.ENDING, CallPhase.ENDED)
        ) {
            telecom.finish(incomingCallId, KitTelecomDisconnect.REJECTED)
            pendingTerminations.enqueue(
                PendingCallTermination(incomingCallId, BackendCallTerminationKind.DECLINE),
            )
        }
        if (connection == null && incomingCallId == null &&
            outgoingAttemptSubmitted && !outgoingAttemptResolved
        ) {
            outgoingClientCallId?.let { attemptId ->
                applicationScope.launch { runCatching { calls.cancelAttempt(attemptId) } }
            }
        }
        val pending = pendingTerminations.snapshot()
        if (pending.isNotEmpty()) {
            applicationScope.launch {
                pending.forEach { action -> performBackendTermination(action) }
            }
        }
        super.onCleared()
    }

    /** Retires an incoming identity; outgoing deadlines need only process-local cancellation. */
    private fun closeRingWindow(
        callId: String?,
        disposition: IncomingCallRetirementDisposition,
    ) {
        val canonicalIncomingId = acceptedWaitingCallId?.takeIf { it == callId } ?: incomingCallId
        if (callId == null) return
        if (canonicalIncomingId != null && callId.equals(canonicalIncomingId, ignoreCase = true)) {
            ringDeadlines.retire(canonicalIncomingId, disposition)
        } else {
            ringDeadlines.cancel(callId)
        }
    }
}

private fun KitTelecomDisconnect.ringRetirementDisposition():
    IncomingCallRetirementDisposition =
    when (this) {
        KitTelecomDisconnect.ANSWERED_ELSEWHERE ->
            IncomingCallRetirementDisposition.ANSWERED_ELSEWHERE
        KitTelecomDisconnect.REJECTED -> IncomingCallRetirementDisposition.REJECTED
        KitTelecomDisconnect.REMOTE -> IncomingCallRetirementDisposition.REMOTE
        KitTelecomDisconnect.MISSED -> IncomingCallRetirementDisposition.MISSED
        KitTelecomDisconnect.LOCAL -> IncomingCallRetirementDisposition.LOCAL
        KitTelecomDisconnect.ERROR -> IncomingCallRetirementDisposition.ERROR
    }

private const val OUTGOING_CALL_LAUNCH_CLAIMED = "kit.outgoing_call_launch_claimed"

internal enum class OutgoingCallLaunchAction {
    START,
    KEEP_CURRENT_ROUTE,
    EXIT_STALE_ROUTE,
}

/**
 * Treats an outgoing-call destination as a one-shot command across both kinds of restoration.
 *
 * The saved marker makes a newly constructed ViewModel reject a process-restored back-stack entry.
 * The in-memory marker prevents a retained ViewModel from issuing the command again when its
 * Compose content leaves and re-enters after a configuration or capability change.
 */
internal class OutgoingCallLaunchGate(savedStateHandle: SavedStateHandle) {
    private val freshRoute = savedStateHandle.get<Boolean>(OUTGOING_CALL_LAUNCH_CLAIMED) != true
    private var consumed = false

    init {
        savedStateHandle[OUTGOING_CALL_LAUNCH_CLAIMED] = true
    }

    fun consume(): OutgoingCallLaunchAction = when {
        !freshRoute -> OutgoingCallLaunchAction.EXIT_STALE_ROUTE
        consumed -> OutgoingCallLaunchAction.KEEP_CURRENT_ROUTE
        else -> {
            consumed = true
            OutgoingCallLaunchAction.START
        }
    }
}
