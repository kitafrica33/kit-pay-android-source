package com.kit.wallet.feature.calls

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import androidx.core.content.ContextCompat
import com.kit.wallet.MainActivity
import com.kit.wallet.data.notifications.CallActionReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registers Kit Pay as a self-managed calling account. On Android 9+ Telecom records completed
 * self-managed calls in the system call log using [PhoneAccount.EXTRA_LOG_SELF_MANAGED_CALLS].
 * Older supported Android releases still receive Telecom audio arbitration and coexistence.
 */
@Singleton
class KitTelecomBridge @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val telecom = context.getSystemService(TelecomManager::class.java)
    private val accountHandle = PhoneAccountHandle(
        ComponentName(context, KitCallConnectionService::class.java),
        ACCOUNT_ID,
    )
    private val calls = TerminalAwareTelecomCallRegistry<
        TelecomCallMetadata,
        TelecomCallState,
        KitTelecomConnection,
        KitTelecomDisconnect,
    >()

    fun registerPhoneAccount(): Boolean {
        if (!supportsTelecomRegistration(context.packageManager::hasSystemFeature)) return false
        return runCatching {
            val extras = Bundle().apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    putBoolean(PhoneAccount.EXTRA_LOG_SELF_MANAGED_CALLS, true)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    putBoolean(PhoneAccount.EXTRA_ADD_SELF_MANAGED_CALLS_TO_INCALLSERVICE, true)
                }
            }
            telecom.registerPhoneAccount(
                PhoneAccount.builder(accountHandle, "Kit Pay")
                    .setCapabilities(
                        PhoneAccount.CAPABILITY_SELF_MANAGED or
                            PhoneAccount.CAPABILITY_SUPPORTS_VIDEO_CALLING,
                    )
                    .setShortDescription("Kit Pay voice and video calls")
                    .setSupportedUriSchemes(listOf(PhoneAccount.SCHEME_TEL, PhoneAccount.SCHEME_SIP))
                    .setExtras(extras)
                    .build(),
            )
            true
        }.getOrDefault(false)
    }

    fun trackOutgoing(callId: String, name: String, phone: String?, video: Boolean) {
        val permitted = registerPhoneAccount() &&
            hasSelfManagedCallingPermission() &&
            runCatching { telecom.isOutgoingCallPermitted(accountHandle) }.getOrDefault(false)
        if (!permitted) return
        val metadata = TelecomCallMetadata(callId, name, phone, video, incoming = false)
        val tracked = calls.track(callId, metadata, TelecomCallState.DIALING) ?: return
        if (tracked.alreadyTracked) {
            tracked.call.connection?.applyPresentation(metadata)
            return
        }
        val customExtras = metadata.toBundle()
        val extras = Bundle().apply {
            putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, accountHandle)
            putInt(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE, metadata.videoState)
            putBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS, customExtras)
        }
        if (!placeSelfManagedCall(metadata.address, extras)) {
            registrationFailed(callId)
        }
    }

    private fun hasSelfManagedCallingPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.MANAGE_OWN_CALLS,
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Telecom's SDK annotation only names CALL_PHONE even though a self-managed ConnectionService
     * is authorized by MANAGE_OWN_CALLS. Keep the platform call behind the explicit install-time
     * permission check above and still handle revocation/security-policy changes at the boundary.
     */
    @SuppressLint("MissingPermission")
    private fun placeSelfManagedCall(address: Uri, extras: Bundle): Boolean = try {
        telecom.placeCall(address, extras)
        true
    } catch (_: SecurityException) {
        false
    } catch (_: RuntimeException) {
        false
    }

    fun trackIncoming(callId: String, name: String, phone: String?, video: Boolean) {
        val permitted = registerPhoneAccount() &&
            runCatching { telecom.isIncomingCallPermitted(accountHandle) }.getOrDefault(false)
        if (!permitted) return
        val metadata = TelecomCallMetadata(callId, name, phone, video, incoming = true)
        val tracked = calls.track(callId, metadata, TelecomCallState.RINGING) ?: return
        if (tracked.alreadyTracked) {
            tracked.call.connection?.applyPresentation(metadata)
            return
        }
        val extras = metadata.toBundle().apply {
            putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, metadata.address)
            putInt(TelecomManager.EXTRA_INCOMING_VIDEO_STATE, metadata.videoState)
        }
        runCatching { telecom.addNewIncomingCall(accountHandle, extras) }
            .onFailure { registrationFailed(callId) }
    }

    fun updatePresentation(callId: String, name: String, phone: String?, video: Boolean) {
        calls.updateMetadata(
            callId = callId,
            transform = { metadata ->
                metadata.copy(name = name, phone = phone, video = video)
            },
            applyToConnection = { connection, metadata ->
                connection.applyPresentation(metadata)
            },
        )
    }

    fun markConnecting(callId: String) {
        updateState(callId, TelecomCallState.DIALING)
    }

    fun markActive(callId: String) {
        updateState(callId, TelecomCallState.ACTIVE)
    }

    fun finish(callId: String, disconnect: KitTelecomDisconnect) {
        val tracked = calls.finish(callId, disconnect)
        tracked?.connection?.complete(disconnect.cause)
    }

    private fun registrationFailed(callId: String) {
        val tracked = calls.registrationFailed(callId, KitTelecomDisconnect.ERROR)
        tracked?.connection?.complete(KitTelecomDisconnect.ERROR.cause)
    }

    internal fun createConnection(request: ConnectionRequest, incoming: Boolean): Connection {
        val metadata = TelecomCallMetadata.fromRequest(request, incoming)
            ?: return Connection.createFailedConnection(DisconnectCause(DisconnectCause.ERROR))
        val resolution = calls.attachConnection(
            callId = metadata.callId,
            metadata = metadata,
            initialState = if (incoming) TelecomCallState.RINGING else TelecomCallState.DIALING,
            createConnection = { KitTelecomConnection(metadata, this) },
            prepareLiveConnection = { liveConnection, state ->
                liveConnection.applyState(state)
            },
        )
        resolution.terminalDisconnect?.let { disconnect ->
            return Connection.createFailedConnection(disconnect.cause)
        }
        return checkNotNull(resolution.liveCall?.connection)
    }

    internal fun systemAnswered(callId: String) {
        val intent = Intent(context, MainActivity::class.java)
            .setData(Uri.parse("kitwallet://call/incoming?call_id=$callId&accept=1"))
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        runCatching { context.startActivity(intent) }
    }

    internal fun systemDeclined(callId: String) {
        calls.finish(callId, KitTelecomDisconnect.REJECTED)
        runCatching { context.sendBroadcast(CallActionReceiver.declineIntent(context, callId)) }
    }

    internal fun systemDisconnected(callId: String) {
        val tracked = calls.finish(callId, KitTelecomDisconnect.LOCAL) ?: return
        val intent = if (tracked.metadata.incoming && tracked.state == TelecomCallState.RINGING) {
            CallActionReceiver.declineIntent(context, callId)
        } else {
            CallActionReceiver.endIntent(context, callId, "cancelled")
        }
        runCatching { context.sendBroadcast(intent) }
    }

    private fun updateState(callId: String, state: TelecomCallState) {
        calls.updateState(
            callId = callId,
            state = state,
            applyToConnection = { connection, updatedState ->
                connection.applyState(updatedState)
            },
        )
    }

    private companion object {
        const val ACCOUNT_ID = "kit-pay-self-managed-v1"
    }
}

@Suppress("DEPRECATION")
internal fun supportsTelecomRegistration(hasSystemFeature: (String) -> Boolean): Boolean =
    hasSystemFeature(PackageManager.FEATURE_TELECOM) ||
        hasSystemFeature(PackageManager.FEATURE_CONNECTION_SERVICE)

enum class KitTelecomDisconnect(internal val cause: DisconnectCause) {
    LOCAL(DisconnectCause(DisconnectCause.LOCAL)),
    REJECTED(DisconnectCause(DisconnectCause.REJECTED)),
    REMOTE(DisconnectCause(DisconnectCause.REMOTE)),
    MISSED(DisconnectCause(DisconnectCause.MISSED)),
    ERROR(DisconnectCause(DisconnectCause.ERROR)),
}

private enum class TelecomCallState { RINGING, DIALING, ACTIVE }

internal data class TelecomCallMetadata(
    val callId: String,
    val name: String,
    val phone: String?,
    val video: Boolean,
    val incoming: Boolean,
) {
    val address: Uri get() = telecomAddress(phone)
    val videoState: Int get() = if (video) VideoProfile.STATE_BIDIRECTIONAL else VideoProfile.STATE_AUDIO_ONLY

    fun toBundle() = Bundle().apply {
        putString(EXTRA_CALL_ID, callId)
        putString(EXTRA_DISPLAY_NAME, name)
        putString(EXTRA_PHONE, phone)
        putBoolean(EXTRA_VIDEO, video)
        putBoolean(EXTRA_INCOMING, incoming)
    }

    companion object {
        private const val EXTRA_CALL_ID = "com.kit.wallet.telecom.CALL_ID"
        private const val EXTRA_DISPLAY_NAME = "com.kit.wallet.telecom.DISPLAY_NAME"
        private const val EXTRA_PHONE = "com.kit.wallet.telecom.PHONE"
        private const val EXTRA_VIDEO = "com.kit.wallet.telecom.VIDEO"
        private const val EXTRA_INCOMING = "com.kit.wallet.telecom.INCOMING"

        fun fromRequest(request: ConnectionRequest, incoming: Boolean): TelecomCallMetadata? {
            val direct = request.extras ?: Bundle.EMPTY
            val custom = direct.getBundle(
                if (incoming) TelecomManager.EXTRA_INCOMING_CALL_EXTRAS
                else TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS,
            ) ?: direct
            val callId = custom.getString(EXTRA_CALL_ID)?.takeIf(CANONICAL_UUID::matches) ?: return null
            val name = custom.getString(EXTRA_DISPLAY_NAME)
                ?.filterNot(Char::isISOControl)
                ?.trim()
                ?.take(160)
                ?.takeIf(String::isNotBlank)
                ?: "Kit Pay contact"
            return TelecomCallMetadata(
                callId = callId,
                name = name,
                phone = custom.getString(EXTRA_PHONE)?.trim()?.takeIf(String::isNotEmpty),
                video = custom.getBoolean(EXTRA_VIDEO, request.videoState != VideoProfile.STATE_AUDIO_ONLY),
                incoming = custom.getBoolean(EXTRA_INCOMING, incoming),
            )
        }
    }
}

private class KitTelecomConnection(
    metadata: TelecomCallMetadata,
    private val bridge: KitTelecomBridge,
) : Connection() {
    private val callId = metadata.callId

    init {
        setConnectionProperties(PROPERTY_SELF_MANAGED)
        setConnectionCapabilities(CAPABILITY_MUTE or CAPABILITY_SUPPORT_HOLD)
        setAudioModeIsVoip(true)
        applyPresentation(metadata)
    }

    fun applyPresentation(metadata: TelecomCallMetadata) {
        setAddress(metadata.address, TelecomManager.PRESENTATION_ALLOWED)
        setCallerDisplayName(metadata.name, TelecomManager.PRESENTATION_ALLOWED)
        setVideoState(metadata.videoState)
    }

    fun applyState(state: TelecomCallState) = when (state) {
        TelecomCallState.RINGING -> setRinging()
        TelecomCallState.DIALING -> setDialing()
        TelecomCallState.ACTIVE -> setActive()
    }

    fun complete(cause: DisconnectCause) {
        setDisconnected(cause)
        destroy()
    }

    override fun onAnswer() = onAnswer(VideoProfile.STATE_AUDIO_ONLY)

    override fun onAnswer(videoState: Int) {
        setVideoState(videoState)
        setActive()
        bridge.systemAnswered(callId)
    }

    override fun onReject() {
        complete(DisconnectCause(DisconnectCause.REJECTED))
        bridge.systemDeclined(callId)
    }

    override fun onDisconnect() {
        complete(DisconnectCause(DisconnectCause.LOCAL))
        bridge.systemDisconnected(callId)
    }

    override fun onAbort() = onDisconnect()
}

internal fun telecomAddress(phone: String?): Uri {
    val trimmed = phone?.trim().orEmpty()
    val normalized = buildString {
        if (trimmed.startsWith('+')) append('+')
        append(trimmed.filter(Char::isDigit))
    }
    return if (normalized.count(Char::isDigit) >= 7) {
        Uri.fromParts(PhoneAccount.SCHEME_TEL, normalized, null)
    } else {
        // A stable non-personal SIP address keeps an unresolved UUID out of the system call UI/log.
        Uri.fromParts(PhoneAccount.SCHEME_SIP, "contact@pay.kit.africa", null)
    }
}

private val CANONICAL_UUID = Regex(
    // Laravel's HasUuids emits UUIDv7; RFC 9562 reserves versions through 8.
    "^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
    RegexOption.IGNORE_CASE,
)
