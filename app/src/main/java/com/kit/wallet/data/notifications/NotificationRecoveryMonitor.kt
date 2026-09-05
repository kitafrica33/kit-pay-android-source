package com.kit.wallet.data.notifications

import com.kit.wallet.data.realtime.KitForegroundSource
import com.kit.wallet.data.realtime.KitNetworkSource
import com.kit.wallet.data.session.SessionFence
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.di.ApplicationScope
import com.kit.wallet.worker.NotificationRecoveryScheduler
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Singleton
internal class NotificationRecoveryMonitor @Inject constructor(
    private val sessions: SessionStore,
    private val foreground: KitForegroundSource,
    private val network: KitNetworkSource,
    private val scheduler: NotificationRecoveryScheduler,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private var started = false

    fun start() {
        if (started) return
        started = true
        foreground.start()
        network.start()
        scope.launch {
            var previous: NotificationRecoveryConditions? = null
            combine(
                sessions.session.map { it?.fence() }.distinctUntilChanged(),
                foreground.foregrounded,
                network.online,
            ) { owner, onScreen, online -> NotificationRecoveryConditions(owner, onScreen, online) }
                .collect { current ->
                    if (current.shouldRecoverAfter(previous)) scheduler.schedule()
                    previous = current
                }
        }
    }
}

internal data class NotificationRecoveryConditions(
    val owner: SessionFence?,
    val foreground: Boolean,
    val online: Boolean,
) {
    fun shouldRecoverAfter(previous: NotificationRecoveryConditions?): Boolean = owner != null && (
        owner != previous?.owner || (foreground && previous?.foreground != true) ||
            (online && previous?.online != true)
        )
}
