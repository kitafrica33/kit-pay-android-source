package com.kit.wallet.feature.calls

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kit.wallet.data.notifications.CallActionReceiver
import com.kit.wallet.data.notifications.CallLifecycleEvent
import com.kit.wallet.data.notifications.CallLifecycleEventBus
import com.kit.wallet.data.notifications.CallLifecycleKind
import com.kit.wallet.data.remote.KitWalletApiException
import com.kit.wallet.data.repository.CallConnection
import com.kit.wallet.data.repository.CallRepository
import com.kit.wallet.data.repository.ContactRepository
import com.kit.wallet.di.ApplicationScope
import com.twilio.audioswitch.AudioDevice
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.livekit.android.LiveKit
import io.livekit.android.RoomOptions
import io.livekit.android.audio.AudioSwitchHandler
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class CallPhase {
    IDLE,
    VALIDATING,
    INCOMING,
    CONNECTING,
    RINGING,
    CONNECTED,
    RECONNECTING,
    ENDING,
    ENDED,
    ERROR,
}

data class ActiveCallUiState(
    val name: String = "Kit Pay contact",
    val video: Boolean = false,
    val incoming: Boolean = false,
    val incomingVerified: Boolean = false,
    val phase: CallPhase = CallPhase.IDLE,
    val muted: Boolean = false,
    val cameraEnabled: Boolean = false,
    val speakerEnabled: Boolean = false,
    val durationSeconds: Long = 0,
    val remoteVideoTrack: VideoTrack? = null,
    val localVideoTrack: VideoTrack? = null,
    val error: String? = null,
)

@HiltViewModel
class ActiveCallViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val calls: CallRepository,
    private val contacts: ContactRepository,
    private val callEvents: CallLifecycleEventBus,
    @ApplicationScope private val applicationScope: CoroutineScope,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val target: String? = savedStateHandle["name"]
    private val incomingCallId: String? = savedStateHandle["callId"]
    private val mutableState = MutableStateFlow(
        ActiveCallUiState(
            name = if (incomingCallId != null) {
                "Incoming Kit Pay call"
            } else {
                target?.takeIf { it.isNotBlank() } ?: "Kit Pay contact"
            },
            incoming = incomingCallId != null,
            phase = if (incomingCallId != null) CallPhase.VALIDATING else CallPhase.IDLE,
        ),
    )
    val state = mutableState.asStateFlow()

    val room: Room = LiveKit.create(
        appContext = context,
        options = RoomOptions(adaptiveStream = true, dynacast = true),
    )

    private var connection: CallConnection? = null
    private var validationJob: Job? = null
    private var startJob: Job? = null
    private var cleanupJob: Job? = null
    private var terminationJob: Job? = null
    private var timerJob: Job? = null
    private val pendingTerminations = CallTerminationQueue()
    private var terminated = false

    init {
        viewModelScope.launch {
            room.events.collect { event ->
                when (event) {
                    is RoomEvent.ParticipantConnected -> if (!terminated) markConnected()
                    is RoomEvent.ParticipantDisconnected -> if (room.remoteParticipants.isEmpty()) {
                        end("network_error")
                    }
                    is RoomEvent.TrackSubscribed -> if (!terminated && event.track is VideoTrack) {
                        // A peer turning their camera on upgrades a voice call to the video layout.
                        mutableState.value = mutableState.value.copy(
                            remoteVideoTrack = event.track as VideoTrack,
                            video = true,
                        )
                    }
                    is RoomEvent.TrackUnsubscribed -> if (event.track == mutableState.value.remoteVideoTrack) {
                        // With both cameras off the call settles back into the voice layout.
                        mutableState.value = mutableState.value.copy(
                            remoteVideoTrack = null,
                            video = mutableState.value.cameraEnabled,
                        )
                    }
                    is RoomEvent.Reconnecting -> if (!terminated) {
                        mutableState.value = mutableState.value.copy(phase = CallPhase.RECONNECTING)
                    }
                    is RoomEvent.Reconnected -> if (!terminated) {
                        mutableState.value = mutableState.value.copy(phase = CallPhase.CONNECTED)
                    }
                    is RoomEvent.FailedToConnect -> fail(event.error)
                    is RoomEvent.Disconnected -> if (!terminated) {
                        fail(event.error ?: IOException("The call connection ended"))
                    }
                    else -> Unit
                }
            }
        }
        viewModelScope.launch {
            callEvents.events.collect(::handleLifecycleEvent)
        }
        if (incomingCallId != null) validateIncomingCall()
    }

    fun start(requestedVideo: Boolean) {
        if (incomingCallId != null) return
        connect(requestedVideo)
    }

    fun accept(requestedVideo: Boolean) {
        if (incomingCallId == null || !mutableState.value.incomingVerified) return
        connect(requestedVideo)
    }

    private fun connect(requestedVideo: Boolean) {
        if (!pendingTerminations.isEmpty || startJob?.isActive == true || cleanupJob?.isActive == true ||
            terminationJob?.isActive == true || mutableState.value.phase !in setOf(
                CallPhase.IDLE,
                CallPhase.INCOMING,
                CallPhase.ERROR,
            )
        ) {
            return
        }
        terminated = false
        startJob = viewModelScope.launch {
            mutableState.value = mutableState.value.copy(
                video = requestedVideo,
                cameraEnabled = requestedVideo,
                speakerEnabled = requestedVideo,
                phase = CallPhase.CONNECTING,
                error = null,
            )
            try {
                val session = incomingCallId?.let { calls.accept(it) }
                    ?: calls.start(resolveRecipient(), requestedVideo)
                connection = session
                if (terminated) return@launch
                mutableState.value = mutableState.value.copy(
                    name = session.name,
                    video = session.video,
                    cameraEnabled = session.video,
                )
                CallForegroundService.start(context, session.name, session.video)
                room.connect(url = session.url, token = session.token)
                if (terminated) {
                    room.disconnect()
                    return@launch
                }
                room.localParticipant.setMicrophoneEnabled(true)
                if (session.video) room.localParticipant.setCameraEnabled(true)
                selectSpeaker(session.video)
                val localTrack = room.localParticipant
                    .getTrackPublication(Track.Source.CAMERA)?.track as? LocalVideoTrack
                val remoteTrack = room.remoteParticipants.values
                    .asSequence()
                    .flatMap { it.videoTrackPublications.asSequence() }
                    .mapNotNull { (_, track) -> track as? VideoTrack }
                    .firstOrNull()
                mutableState.value = mutableState.value.copy(
                    phase = when {
                        room.remoteParticipants.isNotEmpty() -> CallPhase.CONNECTED
                        incomingCallId != null -> CallPhase.CONNECTING
                        else -> CallPhase.RINGING
                    },
                    localVideoTrack = localTrack,
                    remoteVideoTrack = remoteTrack,
                )
                if (mutableState.value.phase == CallPhase.CONNECTED) startTimer()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (!terminated) fail(error)
            }
        }
    }

    fun retry() {
        if (cleanupJob?.isActive == true || terminationJob?.isActive == true) return
        if (!pendingTerminations.isEmpty) {
            retryPendingTerminations()
            return
        }
        if (incomingCallId != null && !mutableState.value.incomingVerified) {
            validateIncomingCall()
            return
        }
        startJob = null
        if (incomingCallId != null) accept(mutableState.value.video)
        else start(mutableState.value.video)
    }

    fun decline() {
        if (incomingCallId != null) terminate("cancelled")
    }

    fun toggleMute() {
        val enabled = mutableState.value.muted
        viewModelScope.launch {
            runCatching { room.localParticipant.setMicrophoneEnabled(enabled) }
                .onSuccess { mutableState.value = mutableState.value.copy(muted = !enabled) }
                .onFailure(::fail)
        }
    }

    fun toggleCamera() {
        val enabled = !mutableState.value.cameraEnabled
        viewModelScope.launch {
            runCatching { room.localParticipant.setCameraEnabled(enabled) }
                .onSuccess {
                    val track = room.localParticipant
                        .getTrackPublication(Track.Source.CAMERA)?.track as? LocalVideoTrack
                    mutableState.value = mutableState.value.copy(
                        cameraEnabled = enabled,
                        localVideoTrack = track,
                    )
                }
                .onFailure(::fail)
        }
    }

    /**
     * Upgrades the current voice call to video: publishes the camera and routes audio to the
     * speaker. The peer's screen upgrades automatically when the video track arrives.
     */
    fun switchToVideo() {
        if (mutableState.value.phase !in setOf(CallPhase.CONNECTED, CallPhase.RECONNECTING)) return
        viewModelScope.launch {
            runCatching { room.localParticipant.setCameraEnabled(true) }
                .onSuccess {
                    val track = room.localParticipant
                        .getTrackPublication(Track.Source.CAMERA)?.track as? LocalVideoTrack
                    mutableState.value = mutableState.value.copy(
                        video = true,
                        cameraEnabled = true,
                        localVideoTrack = track,
                    )
                    selectSpeaker(true)
                }
                .onFailure(::fail)
        }
    }

    /**
     * Drops this side of the call back to voice: unpublishes the camera and returns audio to the
     * earpiece. The remote video keeps rendering if the peer still has their camera on.
     */
    fun switchToAudio() {
        viewModelScope.launch {
            runCatching { room.localParticipant.setCameraEnabled(false) }
                .onSuccess {
                    mutableState.value = mutableState.value.copy(
                        video = mutableState.value.remoteVideoTrack != null,
                        cameraEnabled = false,
                        localVideoTrack = null,
                    )
                    if (mutableState.value.remoteVideoTrack == null) selectSpeaker(false)
                }
                .onFailure(::fail)
        }
    }

    fun flipCamera() {
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
        selectSpeaker(!mutableState.value.speakerEnabled)
    }

    fun end(reason: String = "completed") {
        terminate(reason)
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
        // The ringing status-bar banner is owned by this screen once the user is looking at it.
        context.getSystemService(android.app.NotificationManager::class.java)?.cancel(
            CallActionReceiver.notificationTag(callId),
            CallActionReceiver.NOTIFICATION_ID,
        )
        mutableState.value = mutableState.value.copy(
            name = "Incoming Kit Pay call",
            video = false,
            incomingVerified = false,
            phase = CallPhase.VALIDATING,
            error = null,
        )
        validationJob = viewModelScope.launch {
            try {
                val incoming = calls.incoming(callId)
                if (terminated) return@launch
                mutableState.value = mutableState.value.copy(
                    name = incoming.name,
                    video = incoming.video,
                    incomingVerified = true,
                    phase = CallPhase.INCOMING,
                    error = null,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (!terminated) {
                    mutableState.value = mutableState.value.copy(
                        name = "Incoming Kit Pay call",
                        video = false,
                        incomingVerified = false,
                        phase = CallPhase.ERROR,
                        error = "This incoming call could not be verified. It may have expired.",
                    )
                }
            }
        }
    }

    private suspend fun resolveRecipient(): String {
        val raw = requireNotNull(target) { "Choose a contact before starting a call" }
        val uuid = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
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

    private fun selectSpeaker(speaker: Boolean) {
        val handler = room.audioHandler as? AudioSwitchHandler ?: return
        val selected = runCatching {
            val device = if (speaker) {
                handler.availableAudioDevices.firstOrNull { it is AudioDevice.Speakerphone }
            } else {
                handler.availableAudioDevices.firstOrNull { it is AudioDevice.Earpiece }
            }
            handler.selectDevice(device)
            speaker
        }.getOrDefault(false)
        mutableState.value = mutableState.value.copy(speakerEnabled = selected)
    }

    private fun terminate(reason: String) {
        if (terminationJob?.isActive == true ||
            mutableState.value.phase in setOf(CallPhase.ENDING, CallPhase.ENDED)
        ) {
            return
        }
        val safeReason = reason.takeIf { it in setOf("completed", "cancelled", "network_error") }
            ?: "cancelled"
        terminated = true
        validationJob?.cancel()
        timerJob?.cancel()
        timerJob = null
        room.disconnect()
        CallForegroundService.stop(context)
        mutableState.value = mutableState.value.copy(
            phase = CallPhase.ENDING,
            remoteVideoTrack = null,
            localVideoTrack = null,
            error = null,
        )
        val connecting = startJob
        terminationJob = viewModelScope.launch {
            connecting?.join()
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
            mutableState.value = mutableState.value.copy(phase = CallPhase.ENDED)
        }
    }

    private fun markConnected() {
        mutableState.value = mutableState.value.copy(phase = CallPhase.CONNECTED)
        startTimer()
    }

    private fun handleLifecycleEvent(event: CallLifecycleEvent) {
        val activeCallId = connection?.callId ?: incomingCallId
        if (event.callId != activeCallId) return

        when (event.kind) {
            CallLifecycleKind.ANSWERED -> if (mutableState.value.phase == CallPhase.RINGING) {
                mutableState.value = mutableState.value.copy(phase = CallPhase.CONNECTING)
            }
            CallLifecycleKind.DECLINED -> if (event.terminal) finishFromRemote(event.callId)
            CallLifecycleKind.ENDED, CallLifecycleKind.MISSED -> finishFromRemote(event.callId)
        }
    }

    private fun finishFromRemote(callId: String) {
        if (terminationJob?.isActive == true ||
            mutableState.value.phase in setOf(CallPhase.ENDING, CallPhase.ENDED)
        ) {
            return
        }
        terminated = true
        validationJob?.cancel()
        timerJob?.cancel()
        timerJob = null
        room.disconnect()
        CallForegroundService.stop(context)
        mutableState.value = mutableState.value.copy(
            phase = CallPhase.ENDING,
            remoteVideoTrack = null,
            localVideoTrack = null,
        )
        val connecting = startJob
        terminationJob = viewModelScope.launch {
            connecting?.join()
            pendingTerminations.completed(callId)
            connection = null
            startJob = null
            mutableState.value = mutableState.value.copy(phase = CallPhase.ENDED)
        }
    }

    private fun startTimer() {
        if (timerJob?.isActive == true) return
        timerJob = viewModelScope.launch {
            while (true) {
                delay(1_000)
                mutableState.value = mutableState.value.copy(
                    durationSeconds = mutableState.value.durationSeconds + 1,
                )
            }
        }
    }

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

    private suspend fun drainPendingTerminations(): Boolean {
        pendingTerminations.snapshot().forEach { action ->
            if (performBackendTermination(action)) {
                pendingTerminations.completed(action.callId)
            }
        }
        return pendingTerminations.isEmpty
    }

    private suspend fun performBackendTermination(action: PendingCallTermination): Boolean =
        withTimeoutOrNull(3_000) {
            runCatching {
                when (action.kind) {
                    BackendCallTerminationKind.END -> calls.end(action.callId, action.reason)
                    BackendCallTerminationKind.DECLINE -> calls.decline(action.callId)
                }
            }.isSuccess
        } ?: false

    private fun fail(error: Throwable) {
        if (cleanupJob?.isActive == true || terminationJob?.isActive == true ||
            mutableState.value.phase in setOf(CallPhase.ENDING, CallPhase.ENDED)
        ) {
            return
        }
        terminated = true
        timerJob?.cancel()
        timerJob = null
        room.disconnect()
        CallForegroundService.stop(context)
        mutableState.value = mutableState.value.copy(
            phase = CallPhase.ENDING,
            error = error.userMessage(),
            remoteVideoTrack = null,
            localVideoTrack = null,
        )
        val connecting = startJob
        cleanupJob = viewModelScope.launch {
            connecting?.join()
            val failedCallId = connection?.callId
            connection = null
            if (failedCallId != null) {
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

    private fun Throwable.userMessage(): String = when (this) {
        is KitWalletApiException -> message
        is IOException -> "The secure call connection could not be established. Check your internet and try again."
        else -> message?.takeIf(String::isNotBlank) ?: "The call could not be started."
    }

    override fun onCleared() {
        validationJob?.cancel()
        timerJob?.cancel()
        timerJob = null
        terminated = true
        room.disconnect()
        room.release()
        CallForegroundService.stop(context)
        connection?.callId?.let { activeCallId ->
            pendingTerminations.enqueue(
                PendingCallTermination(activeCallId, BackendCallTerminationKind.END, "cancelled"),
            )
        }
        if (connection == null && incomingCallId != null && mutableState.value.incomingVerified &&
            mutableState.value.phase !in setOf(CallPhase.ENDING, CallPhase.ENDED)
        ) {
            pendingTerminations.enqueue(
                PendingCallTermination(incomingCallId, BackendCallTerminationKind.DECLINE),
            )
        }
        val pending = pendingTerminations.snapshot()
        if (pending.isNotEmpty()) {
            applicationScope.launch {
                pending.forEach { action -> performBackendTermination(action) }
            }
        }
        super.onCleared()
    }
}
