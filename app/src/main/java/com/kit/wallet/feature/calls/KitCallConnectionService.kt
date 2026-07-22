package com.kit.wallet.feature.calls

import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.PhoneAccountHandle
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Android Telecom entry point for Kit Pay's self-managed voice and video calls. */
@AndroidEntryPoint
class KitCallConnectionService : ConnectionService() {
    @Inject lateinit var telecomBridge: KitTelecomBridge

    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest,
    ): Connection = telecomBridge.createConnection(request, incoming = false)

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest,
    ): Connection = telecomBridge.createConnection(request, incoming = true)
}
