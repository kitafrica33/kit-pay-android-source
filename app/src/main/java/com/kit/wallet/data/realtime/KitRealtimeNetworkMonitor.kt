package com.kit.wallet.data.realtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

private val ALWAYS_ONLINE = MutableStateFlow(true).asStateFlow()

/** What the default network just did. */
enum class KitNetworkEvent {
    /** A usable default network appeared — including the far side of a handover. */
    Available,

    /** The default network went away. */
    Lost,
}

/**
 * Connectivity changes, behind an interface so the coordinator's two connectivity
 * rules — reset-and-redial on [KitNetworkEvent.Available], back off without
 * spending an attempt on [KitNetworkEvent.Lost] — are pinned by a JVM test rather
 * than only by walking into a tunnel with a device.
 */
interface KitNetworkSource {
    val events: SharedFlow<KitNetworkEvent>

    /** Current validated default-network availability for work that must pause while offline. */
    val online: StateFlow<Boolean>
        get() = ALWAYS_ONLINE

    fun start()
}

/**
 * Watches the default network so a reconnect happens when it can succeed.
 *
 * Without this, a Wi-Fi to mobile handover or a walk out of a tunnel leaves the
 * app sitting out a backoff delay that has nothing to do with the reason it
 * failed — the worst version being a 60-second wait after connectivity has already
 * returned. Two rules follow from that, and both are enforced by the coordinator:
 *
 * - [KitNetworkEvent.Available] resets the ladder and redials immediately, because
 *   a new network is new information that the previous failures no longer predict.
 * - [KitNetworkEvent.Lost] backs off **without spending an attempt**. There was no
 *   server to fail against and nobody to be polite to.
 *
 * `registerDefaultNetworkCallback` rather than a request with capabilities: we want
 * whatever the system considers default, which is exactly what OkHttp will dial.
 */
@Singleton
internal class KitRealtimeNetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) : KitNetworkSource {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val changes = MutableSharedFlow<KitNetworkEvent>(
        replay = 0,
        extraBufferCapacity = 8,
        // A backlog of stale connectivity changes is worth less than the newest
        // one, and the emitter is a system callback that must never be suspended.
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val events: SharedFlow<KitNetworkEvent> = changes.asSharedFlow()
    private val mutableOnline = MutableStateFlow(hasUsableDefaultNetwork())
    override val online: StateFlow<Boolean> = mutableOnline.asStateFlow()

    private var registered: Boolean = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            updateOnlineFromSystem()
            changes.tryEmit(KitNetworkEvent.Available)
        }

        override fun onLost(network: Network) {
            updateOnlineFromSystem()
            changes.tryEmit(KitNetworkEvent.Lost)
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            updateOnlineFromSystem()
        }
    }

    @Synchronized
    override fun start() {
        if (registered) return
        val manager = manager ?: return
        updateOnlineFromSystem()
        // Never fatal: a device that refuses the registration keeps the socket on
        // its own ladder and the fallback poller behind it. Connectivity awareness
        // is an optimisation, not a correctness requirement.
        runCatching { manager.registerDefaultNetworkCallback(callback) }
            .onSuccess { registered = true }
            // If observation itself is unavailable, retain retry correctness rather than
            // freezing behind an offline snapshot that can never change.
            .onFailure { mutableOnline.value = true }
    }

    private fun updateOnlineFromSystem() {
        mutableOnline.value = hasUsableDefaultNetwork()
    }

    private fun hasUsableDefaultNetwork(): Boolean = runCatching {
        val manager = manager ?: return@runCatching true
        val active = manager.activeNetwork ?: return@runCatching false
        val capabilities = manager.getNetworkCapabilities(active) ?: return@runCatching false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(true)
}
