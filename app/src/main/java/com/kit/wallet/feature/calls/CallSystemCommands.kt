package com.kit.wallet.feature.calls

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed interface CallSystemCommand {
    val callId: String
    data class Hold(override val callId: String) : CallSystemCommand
    data class Resume(override val callId: String) : CallSystemCommand
    data class Answer(override val callId: String) : CallSystemCommand
    data class ForegroundUnavailable(override val callId: String) : CallSystemCommand
}

/** Process-only commands from Telecom, the call service, or an authorized incoming activity. */
@Singleton
class CallSystemCommands @Inject constructor() {
    private val mutableEvents = MutableSharedFlow<CallSystemCommand>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events = mutableEvents.asSharedFlow()
    fun publish(command: CallSystemCommand) = mutableEvents.tryEmit(command)
}
