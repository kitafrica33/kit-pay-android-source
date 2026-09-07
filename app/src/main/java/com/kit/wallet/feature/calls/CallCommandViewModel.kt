package com.kit.wallet.feature.calls

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class CallCommandViewModel @Inject constructor(private val commands: CallSystemCommands) : ViewModel() {
    fun answerWaiting(callId: String) = commands.publish(CallSystemCommand.Answer(callId))
}
