package com.kit.wallet.data.realtime

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What replaces the two-second foreground poll, and what happens when the socket
 * cannot carry the conversation.
 *
 * The ladder is strictly ordered, and each rung answers a different failure:
 *
 * 1. **`Live` ⇒ no periodic messaging sync at all.** Delivery is nudge-driven. This
 *    is the whole saving: roughly four requests a minute at conversational rates,
 *    against the 120-180 the old loop spent whether or not anything happened.
 * 2. **Not `Live`, conversation on screen ⇒ 15 s**, relaxing to 60 s once the socket
 *    has been unavailable for five continuous minutes. A socket that is down for a
 *    minute is probably coming back; one that has been down for five is an outage,
 *    and paying 4 requests a minute for it indefinitely is not worth the latency.
 * 3. **Backgrounded ⇒ unchanged.** The data-only FCM wake into WorkManager. This
 *    class has no opinion there; no socket exists in the background by design.
 * 4. **No advertisement at all ⇒ 10 s.** The server-side kill switch. Turning
 *    `protocols.realtime` off returns every client to polling with no app release
 *    and no store review, at a cadence five times gentler than the one it replaces.
 *
 * The interval is milliseconds, or `null` for "do not poll".
 */
@Singleton
internal class KitRealtimeFallbackPoller @Inject constructor(
    private val clock: KitRealtimeClock,
) {
    private val interval = MutableStateFlow<Long?>(WITHOUT_REALTIME_MILLIS)

    /**
     * How often a visible conversation should synchronise, or `null` while the
     * socket is carrying it. Consumers poll only while a conversation is on screen.
     */
    val intervalMillis: StateFlow<Long?> = interval.asStateFlow()

    private var notLiveSinceMillis: Long? = clock.elapsedMillis()

    /** Called on every state transition and on every capability change. */
    fun update(state: KitRealtimeState, realtimeAdvertised: Boolean) {
        if (!realtimeAdvertised) {
            notLiveSinceMillis = notLiveSinceMillis ?: clock.elapsedMillis()
            interval.value = WITHOUT_REALTIME_MILLIS
            return
        }

        if (state is KitRealtimeState.Live) {
            notLiveSinceMillis = null
            interval.value = null
            return
        }

        val since = notLiveSinceMillis ?: clock.elapsedMillis().also { notLiveSinceMillis = it }
        interval.value = if (clock.elapsedMillis() - since >= DEGRADE_AFTER_MILLIS) {
            DEGRADED_MILLIS
        } else {
            DEGRADED_FROM_MILLIS
        }
    }

    companion object {
        /** No `protocols.realtime` block: today's behaviour, five times gentler. */
        const val WITHOUT_REALTIME_MILLIS: Long = 10_000L

        /** Socket down, conversation on screen. */
        const val DEGRADED_FROM_MILLIS: Long = 15_000L

        /** Socket down for [DEGRADE_AFTER_MILLIS]; treat it as an outage. */
        const val DEGRADED_MILLIS: Long = 60_000L

        const val DEGRADE_AFTER_MILLIS: Long = 5 * 60_000L
    }
}
