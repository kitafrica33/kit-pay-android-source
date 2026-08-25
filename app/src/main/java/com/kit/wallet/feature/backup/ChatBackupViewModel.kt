package com.kit.wallet.feature.backup

import android.app.PendingIntent
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kit.wallet.data.backup.DriveBackupState
import com.kit.wallet.data.backup.DriveConnectStep
import com.kit.wallet.data.backup.MessageBackupDescription
import com.kit.wallet.data.backup.MessageBackupException
import com.kit.wallet.data.backup.MessageBackupFrequency
import com.kit.wallet.data.backup.MessageBackupService
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the backup screen is doing right now. Only one of these can be true at a time. */
enum class ChatBackupTask { NONE, CONNECTING, BACKING_UP, RESTORING, DELETING }

data class ChatBackupUiState(
    val configured: Boolean = true,
    val connected: Boolean = false,
    val frequency: MessageBackupFrequency = MessageBackupFrequency.OFF,
    val requiresUnmeteredNetwork: Boolean = true,
    val recoveryCodeConfirmed: Boolean = false,
    val lastBackupAt: Instant? = null,
    val lastBackupBytes: Long? = null,
    val lastBackupMessageCount: Int? = null,
    val available: MessageBackupDescription? = null,
    val task: ChatBackupTask = ChatBackupTask.NONE,
    val recoveryCode: String? = null,
    /** Google's own consent screen, waiting for the UI to launch it. */
    val consent: PendingIntent? = null,
    val message: String? = null,
    val error: String? = null,
) {
    val busy: Boolean get() = task != ChatBackupTask.NONE
    val everBackedUp: Boolean get() = lastBackupAt != null
}

@HiltViewModel
class ChatBackupViewModel @Inject constructor(
    private val backups: MessageBackupService,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ChatBackupUiState(configured = backups.supported))
    val state = mutableState.asStateFlow()

    init {
        publish(backups.snapshot())
        refresh()
    }

    /** Re-reads Drive so the screen is honest about what is actually stored there. */
    fun refresh() {
        publish(backups.snapshot())
        if (!mutableState.value.connected) return
        viewModelScope.launch {
            runCatching { backups.findBackup() }
                .onSuccess { found -> mutableState.update { it.copy(available = found) } }
                .onFailure(::report)
        }
    }

    /**
     * Asks Google for the app-folder scope. A user who has approved before is connected without
     * seeing anything; otherwise [ChatBackupUiState.consent] carries the screen to show them.
     */
    fun connect() {
        if (mutableState.value.busy) return
        mutableState.update { it.copy(task = ChatBackupTask.CONNECTING, error = null) }
        viewModelScope.launch {
            runCatching { backups.connect() }
                .onSuccess { step ->
                    when (step) {
                        is DriveConnectStep.Connected -> connected(step.state)
                        is DriveConnectStep.NeedsConsent ->
                            mutableState.update { it.copy(consent = step.consent) }
                    }
                }
                .onFailure { failure ->
                    mutableState.update { it.copy(task = ChatBackupTask.NONE) }
                    report(failure)
                }
        }
    }

    /** The consent screen has been launched, so it must not be launched again on recomposition. */
    fun consentLaunched() {
        mutableState.update { it.copy(consent = null) }
    }

    /** Google's consent screen returned. [data] is null when the user backed out of it. */
    fun consentReturned(data: Intent?) {
        if (data == null) {
            mutableState.update { it.copy(task = ChatBackupTask.NONE, consent = null) }
            return
        }
        viewModelScope.launch {
            runCatching { backups.completeConnect(data) }
                .onSuccess(::connected)
                .onFailure { failure ->
                    mutableState.update { it.copy(task = ChatBackupTask.NONE) }
                    report(failure)
                }
        }
    }

    private fun connected(state: DriveBackupState) {
        publish(state)
        mutableState.update {
            it.copy(task = ChatBackupTask.NONE, consent = null, message = "Google Drive connected")
        }
        refresh()
    }

    fun setFrequency(frequency: MessageBackupFrequency) {
        viewModelScope.launch {
            runCatching { backups.setFrequency(frequency) }
                .onSuccess(::publish)
                .onFailure(::report)
        }
    }

    fun setRequiresUnmeteredNetwork(required: Boolean) {
        viewModelScope.launch {
            runCatching { backups.setRequiresUnmeteredNetwork(required) }
                .onSuccess(::publish)
                .onFailure(::report)
        }
    }

    fun backUpNow() {
        if (mutableState.value.busy) return
        mutableState.update { it.copy(task = ChatBackupTask.BACKING_UP, error = null, message = null) }
        viewModelScope.launch {
            runCatching { backups.backUpNow(System.currentTimeMillis()) }
                .onSuccess { summary ->
                    publish(backups.snapshot())
                    mutableState.update {
                        it.copy(
                            task = ChatBackupTask.NONE,
                            message = "Backed up ${summary.messageCount} messages",
                        )
                    }
                    refresh()
                }
                .onFailure { failure ->
                    mutableState.update { it.copy(task = ChatBackupTask.NONE) }
                    report(failure)
                }
        }
    }

    fun restore(recoveryCode: String? = null) {
        if (mutableState.value.busy) return
        mutableState.update { it.copy(task = ChatBackupTask.RESTORING, error = null, message = null) }
        viewModelScope.launch {
            runCatching { backups.restore(recoveryCode?.takeIf(String::isNotBlank)) }
                .onSuccess { summary ->
                    publish(backups.snapshot())
                    mutableState.update {
                        it.copy(
                            task = ChatBackupTask.NONE,
                            message = when {
                                summary.mergedCount == 0 ->
                                    "Everything in that backup is already on this phone"

                                else -> "Restored ${summary.mergedCount} messages"
                            },
                        )
                    }
                }
                .onFailure { failure ->
                    mutableState.update { it.copy(task = ChatBackupTask.NONE) }
                    report(failure)
                }
        }
    }

    fun revealRecoveryCode() {
        viewModelScope.launch {
            runCatching { backups.recoveryCode() }
                .onSuccess { code -> mutableState.update { it.copy(recoveryCode = code) } }
                .onFailure(::report)
        }
    }

    fun hideRecoveryCode() {
        mutableState.update { it.copy(recoveryCode = null) }
    }

    fun confirmRecoveryCodeSaved() {
        viewModelScope.launch {
            runCatching { backups.markRecoveryCodeConfirmed() }
                .onSuccess { publish(backups.snapshot()) }
                .onFailure(::report)
            mutableState.update { it.copy(recoveryCode = null) }
        }
    }

    fun deleteBackup() {
        if (mutableState.value.busy) return
        mutableState.update { it.copy(task = ChatBackupTask.DELETING, error = null, message = null) }
        viewModelScope.launch {
            runCatching { backups.deleteBackup() }
                .onSuccess {
                    publish(backups.snapshot())
                    mutableState.update {
                        it.copy(
                            task = ChatBackupTask.NONE,
                            available = null,
                            message = "Backup deleted from Google Drive",
                        )
                    }
                }
                .onFailure { failure ->
                    mutableState.update { it.copy(task = ChatBackupTask.NONE) }
                    report(failure)
                }
        }
    }

    fun disconnect() {
        if (mutableState.value.busy) return
        viewModelScope.launch {
            runCatching { backups.disconnect() }
                .onSuccess {
                    publish(backups.snapshot())
                    mutableState.update { it.copy(available = null) }
                }
                .onFailure(::report)
        }
    }

    fun dismissMessage() {
        mutableState.update { it.copy(message = null, error = null) }
    }

    private fun publish(state: DriveBackupState) {
        mutableState.update {
            it.copy(
                connected = state.connected,
                frequency = state.frequency,
                requiresUnmeteredNetwork = state.requiresUnmeteredNetwork,
                recoveryCodeConfirmed = state.recoveryCodeConfirmed,
                lastBackupAt = state.lastBackupAtEpochMillis?.let(Instant::ofEpochMilli),
                lastBackupBytes = state.lastBackupBytes,
                lastBackupMessageCount = state.lastBackupMessageCount,
            )
        }
    }

    private fun report(failure: Throwable) {
        if (failure is CancellationException) throw failure
        val message = when (failure) {
            is MessageBackupException -> failure.message
            is IOException -> "Kit Pay could not reach Google Drive"
            else -> failure.message
        }
        // A failure can have revoked the grant, so re-read rather than leave the screen offering
        // a "Back up now" button that cannot work.
        publish(backups.snapshot())
        mutableState.update {
            it.copy(error = message?.takeIf(String::isNotBlank) ?: "Something went wrong")
        }
    }
}
