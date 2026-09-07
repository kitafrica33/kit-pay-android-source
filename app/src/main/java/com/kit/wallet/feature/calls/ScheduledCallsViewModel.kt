package com.kit.wallet.feature.calls

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kit.wallet.data.notifications.ActiveCallStateHolder
import com.kit.wallet.data.remote.CallInvitePreviewDto
import com.kit.wallet.data.remote.CreateScheduledCallRequest
import com.kit.wallet.data.remote.KitWalletApiException
import com.kit.wallet.data.remote.ScheduledCallDto
import com.kit.wallet.data.repository.ContactRepository
import com.kit.wallet.data.repository.ScheduledCallFeatures
import com.kit.wallet.data.repository.ScheduledCallRepository
import com.kit.wallet.data.session.SessionFence
import com.kit.wallet.data.session.SessionInvalidatedException
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.ui.model.Contact
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ScheduledCallsState(
    val features: ScheduledCallFeatures = ScheduledCallFeatures(),
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val calls: List<ScheduledCallDto> = emptyList(),
    val contacts: List<Contact> = emptyList(),
    val nextCursor: String? = null,
    val preview: CallInvitePreviewDto? = null,
    val error: String? = null,
    val notice: String? = null,
    val editorOpen: Boolean = false,
    val editing: ScheduledCallDto? = null,
    val pendingCreate: Boolean = false,
    val share: ScheduledCallShare? = null,
)

class ScheduledCallShare(val id: String, val url: String, internal val owner: SessionFence) {
    override fun toString() = "ScheduledCallShare([redacted])"
}

class CallInviteJoin(val token: String, val video: Boolean) {
    override fun toString() = "CallInviteJoin([redacted])"
}

@HiltViewModel
class ScheduledCallsViewModel @Inject constructor(
    private val repository: ScheduledCallRepository,
    private val contacts: ContactRepository,
    private val sessions: SessionStore,
    activeCalls: ActiveCallStateHolder,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ScheduledCallsState())
    val state = mutableState.asStateFlow()
    val activeCallId = activeCalls.activeCallId
    private val inviteToken: String? = savedStateHandle.get<String>("inviteToken")
    private var owner: SessionFence? = null
    private var work: Job? = null
    private var pendingCreate: CreateScheduledCallRequest? = null
    private val cursors = ScheduledCallPageCursor()
    private var serverAnchor: Instant? = null
    private var anchorNanos: Long = 0L

    init {
        viewModelScope.launch {
            sessions.session.map { it?.fence() }.distinctUntilChanged().collect { next ->
                work?.cancel()
                owner = next
                pendingCreate = null
                serverAnchor = null
                cursors.reset()
                mutableState.value = ScheduledCallsState()
                if (next != null) refresh()
            }
        }
    }

    fun currentServerTime(): Instant = serverAnchor?.plusNanos((System.nanoTime() - anchorNanos).coerceAtLeast(0)) ?: Instant.now()

    fun isOrganizer(schedule: ScheduledCallDto): Boolean = owner?.accountId?.let {
        schedule.organizerUserId.equals(it, ignoreCase = true)
    } == true

    fun refresh() = action { expected ->
        val features = repository.features(expected)
        publish(expected) { it.copy(features = features, share = null, error = null) }
        if (inviteToken != null) {
            if (!features.inviteLinks) {
                publish(expected) { it.copy(loaded = true, preview = null) }
                return@action
            }
            val preview = repository.preview(inviteToken, expected)
            anchor(preview.serverTime)
            publish(expected) { it.copy(loaded = true, preview = preview) }
        } else if (features.scheduling) {
            val page = repository.list(expected)
            cursors.reset()
            val next = cursors.accept(page.page.nextCursor, page.page.hasMore == true)
            page.items.firstOrNull()?.serverTime?.let(::anchor)
            // If an earlier response was lost, list reconciliation retires the same stable key.
            val reconciled = pendingCreate?.let { request -> page.items.any { it.clientScheduleId == request.clientScheduleId } } == true
            if (reconciled) pendingCreate = null
            publish(expected) {
                it.copy(loaded = true, calls = page.items.distinctBy(ScheduledCallDto::id), nextCursor = next,
                    pendingCreate = pendingCreate != null, editorOpen = if (reconciled) false else it.editorOpen,
                    notice = if (reconciled) "Your scheduled call was saved." else it.notice)
            }
            try {
                contacts.refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Existing, authenticated Kit contacts are sufficient; no device-address-book sync.
            }
            publish(expected) { it.copy(contacts = selectableCallContacts(contacts.contacts.value, expected.accountId)) }
        } else {
            publish(expected) { it.copy(loaded = true, calls = emptyList(), contacts = emptyList(), nextCursor = null) }
        }
    }

    fun loadMore() {
        val cursor = state.value.nextCursor ?: return
        action { expected ->
            val page = repository.list(expected, cursor)
            val next = cursors.accept(page.page.nextCursor, page.page.hasMore == true)
            publish(expected) { it.copy(calls = (it.calls + page.items).distinctBy(ScheduledCallDto::id), nextCursor = next) }
        }
    }

    fun openEditor(schedule: ScheduledCallDto? = null) {
        if (!sameOwner() || state.value.busy || !state.value.features.scheduling) return
        if (pendingCreate != null) return
        if (schedule != null && (!isOrganizer(schedule) || schedule.status != "scheduled")) return
        mutableState.update { it.copy(editorOpen = true, editing = schedule, error = null) }
    }

    fun closeEditor() {
        if (!state.value.busy) mutableState.update { it.copy(editorOpen = false, editing = null) }
    }

    fun save(title: String, recipients: List<String>, video: Boolean, localTime: LocalDateTime, zone: ZoneId) {
        val expected = owner ?: return
        if (!sameOwner() || state.value.busy) return
        val editing = state.value.editing
        val validated = try {
            val start = ScheduledCallPolicy.startsAt(localTime, zone, currentServerTime())
            val cleanTitle = ScheduledCallPolicy.title(title)
            if (editing == null) {
                CreateScheduledCallRequest(
                    clientScheduleId = pendingCreate?.clientScheduleId ?: UUID.randomUUID().toString(),
                    recipientUserIds = ScheduledCallPolicy.recipients(recipients, expected.accountId),
                    type = if (video) "video" else "voice", startsAt = start, title = cleanTitle,
                )
            } else {
                CreateScheduledCallRequest(editing.clientScheduleId, emptyList(), editing.type, start, cleanTitle)
            }
        } catch (error: IllegalArgumentException) {
            mutableState.update { it.copy(error = error.message ?: "Check the scheduled call details.") }
            return
        }
        if (editing == null && pendingCreate != null && pendingCreate != validated) {
            mutableState.update { it.copy(error = "Retry the pending call before creating a different one.") }
            return
        }
        action { fence ->
            val result = if (editing == null) {
                // Kept only in this exact session's memory. A retry must reuse the identical body.
                pendingCreate = validated
                publish(fence) { it.copy(pendingCreate = true) }
                submitCreate(validated, fence, retry = true)
            } else {
                repository.update(editing, validated.startsAt, validated.title, fence)
            }
            pendingCreate = null
            anchor(result.serverTime)
            publish(fence) {
                it.copy(calls = replaceSchedule(it.calls, result), editorOpen = false, editing = null,
                    pendingCreate = false, notice = "Scheduled call saved.")
            }
        }
    }

    /** An uncertain create can be retried even if its editor was closed or the time became due. */
    fun retryPendingCreate() {
        val request = pendingCreate ?: return
        action { expected ->
            val result = submitCreate(request, expected, retry = true)
            pendingCreate = null
            publish(expected) { it.copy(calls = replaceSchedule(it.calls, result), pendingCreate = false,
                editorOpen = false, editing = null, notice = "Scheduled call saved.") }
        }
    }

    fun cancel(schedule: ScheduledCallDto) = action { expected ->
        val result = repository.cancel(schedule, expected)
        publish(expected) { it.copy(calls = replaceSchedule(it.calls, result), notice = "Scheduled call cancelled.") }
    }

    fun respond(schedule: ScheduledCallDto, response: String) = action { expected ->
        val result = repository.respond(schedule, response, expected)
        publish(expected) {
            it.copy(calls = replaceSchedule(it.calls, result), preview = it.preview?.copy(scheduledCall = result),
                notice = if (response == "accepted") "Invitation accepted. Kit Pay will ring you when the call starts." else "Invitation declined.")
        }
    }

    fun share(schedule: ScheduledCallDto) = action { expected ->
        val link = repository.shareSchedule(schedule, expected)
        publish(expected) { it.copy(share = ScheduledCallShare(UUID.randomUUID().toString(), link.shareUrl, expected)) }
    }

    fun takeShare(): String? {
        val share = state.value.share ?: return null
        mutableState.update { it.copy(share = null) }
        return share.url.takeIf { share.owner == sessions.current()?.fence() }
    }

    fun prepareJoin(): CallInviteJoin? {
        if (!sameOwner() || state.value.busy || !state.value.features.inviteLinks) return null
        if (activeCallId.value != null) {
            mutableState.update { it.copy(error = "Finish your current call before joining this one.") }
            return null
        }
        val preview = state.value.preview ?: return null
        if (runCatching { Instant.parse(preview.expiresAt).isAfter(currentServerTime()) }.getOrDefault(false).not()) {
            mutableState.update { it.copy(error = "This invitation has expired. Refresh to check its status.") }
            return null
        }
        val type = when (preview.kind) {
            "call" -> preview.call?.type
            "scheduled_call" -> preview.scheduledCall?.takeIf { it.status == "started" && it.callId != null }?.type
            else -> null
        } ?: return null
        return inviteToken?.let { CallInviteJoin(it, type == "video") }
    }

    private fun action(block: suspend (SessionFence) -> Unit) {
        val expected = owner ?: return
        if (!sameOwner() || work?.isActive == true) return
        mutableState.update { it.copy(busy = true, error = null) }
        work = viewModelScope.launch {
            try {
                block(expected)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SessionInvalidatedException) {
                // The session observer clears the entire surface; no old result or error survives.
            } catch (error: Exception) {
                if (sessions.current()?.fence() == expected) {
                    // Re-read a revision conflict, then require a new explicit edit of that truth.
                    if ((error as? KitWalletApiException)?.statusCode == 409 && inviteToken == null) {
                        try {
                            val page = repository.list(expected)
                            cursors.reset()
                            val next = cursors.accept(page.page.nextCursor, page.page.hasMore == true)
                            publish(expected) { it.copy(calls = page.items.distinctBy(ScheduledCallDto::id), nextCursor = next,
                                editorOpen = false, editing = null) }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            // Keep the conflict visible when the authoritative reread is offline.
                        }
                    }
                    publish(expected) { it.copy(error = scheduledCallError(error), loaded = true, share = null) }
                }
            } finally {
                if (sessions.current()?.fence() == expected) {
                    publish(expected) { it.copy(busy = false) }
                }
            }
        }
    }

    private fun sameOwner(): Boolean = owner != null && owner == sessions.current()?.fence()

    private suspend fun publish(expected: SessionFence, transform: (ScheduledCallsState) -> ScheduledCallsState) {
        try {
            sessions.withCurrentSession(expected) { mutableState.update(transform) }
        } catch (_: SessionInvalidatedException) {
            // A session switch can race even the final busy/error update. It is never a crash.
        }
    }

    private suspend fun submitCreate(request: CreateScheduledCallRequest, expected: SessionFence, retry: Boolean): ScheduledCallDto {
        try {
            return repository.create(request, expected, retry)
        } catch (error: KitWalletApiException) {
            // These statuses explicitly reject a create before commitment. Transport errors,
            // throttling, conflicts and server failures keep the exact retry identity intact.
            if (error.statusCode in setOf(400, 403, 404, 422) && sessions.current()?.fence() == expected) {
                pendingCreate = null
                publish(expected) { it.copy(pendingCreate = false) }
            }
            throw error
        }
    }

    private fun anchor(raw: String) {
        serverAnchor = Instant.parse(raw)
        anchorNanos = System.nanoTime()
    }
}

internal fun selectableCallContacts(contacts: List<Contact>, ownId: String?): List<Contact> = contacts
    .filter { it.isKitUser && ScheduledCallPolicy.canonicalId(it.id) != null && !it.id.equals(ownId, ignoreCase = true) }
    .distinctBy { it.id.lowercase() }
    .sortedBy { it.name.lowercase() }

private fun replaceSchedule(calls: List<ScheduledCallDto>, updated: ScheduledCallDto) =
    (calls.filterNot { it.id == updated.id } + updated).sortedBy(ScheduledCallDto::startsAt)

internal fun scheduledCallError(error: Exception): String = when ((error as? KitWalletApiException)?.statusCode) {
    404 -> "This invitation or scheduled call is no longer available."
    409 -> "This call changed on another device. Refresh before trying again."
    403 -> "This account cannot perform that call action."
    503 -> "This call feature is not available yet."
    else -> if (error is KitWalletApiException && error.connectivity) {
        "Could not reach Kit Pay. Check your connection, then retry or refresh."
    } else {
        "Could not complete that call action. Refresh and try again."
    }
}
