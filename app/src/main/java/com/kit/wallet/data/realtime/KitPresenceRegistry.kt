package com.kit.wallet.data.realtime

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Who is presently watching each conversation, as the server reports it.
 *
 * "Online" is defined as membership of `presence-kit.conv.{id}` and nothing else —
 * not "has a socket", not "app is open", not a `last_seen_at` column. The set is
 * keyed by `public_id`, so a peer on three devices is one entry and a peer on none
 * is absent: multi-device resolution is a union, which is exactly the boolean the
 * UI renders. A peer therefore never learns how many devices you have, and we
 * deliberately do not accept a `user.device` composite that would leak it.
 *
 * **RAM only, never Room.** Presence is not a fact about the account, it is a fact
 * about right now; persisting it would create a durable liveness record the
 * product never agreed to keep, and would let a stale dot survive a process death.
 */
@Singleton
internal class KitPresenceRegistry @Inject constructor() {
    private val rosters = MutableStateFlow<Map<String, Set<String>>>(emptyMap())

    /** conversation id → the `public_id`s currently subscribed, including our own. */
    val presence: StateFlow<Map<String, Set<String>>> = rosters.asStateFlow()

    /**
     * Our own `public_id`. Held here rather than in each consumer because both
     * "is a peer online" and "is a peer typing" have to exclude ourselves, and two
     * copies of that answer is one more than can be kept in agreement.
     */
    @Volatile
    var selfPublicId: String? = null

    /** Whether anybody who is not us is presently watching [conversationId]. */
    fun peersOnlineIn(conversationId: String): Flow<Boolean> = rosters
        .map { current -> current[conversationId].orEmpty().any { it != selfPublicId } }
        .distinctUntilChanged()

    /**
     * True once a hold-over is running: the rosters below are the last ones the
     * server confirmed, kept visible across our own deliberate reconnect.
     */
    private var holdingOver: Boolean = false

    /** `pusher_internal:subscription_succeeded` — the authoritative seed. */
    fun onRoster(conversationId: String, members: Set<String>) {
        holdingOver = false
        rosters.update { current -> current + (conversationId to members) }
    }

    /** Fires on a user's **first** connection, so it is an add, not an increment. */
    fun onMemberAdded(conversationId: String, user: String) {
        rosters.update { current ->
            val existing = current[conversationId] ?: return@update current
            current + (conversationId to (existing + user))
        }
    }

    /** Fires on a user's **last** connection, so it is a remove, not a decrement. */
    fun onMemberRemoved(conversationId: String, user: String) {
        rosters.update { current ->
            val existing = current[conversationId] ?: return@update current
            current + (conversationId to (existing - user))
        }
    }

    /** Left the conversation, or gave up its channel. Its dot must go out at once. */
    fun forget(conversationId: String) {
        rosters.update { current -> current - conversationId }
    }

    fun membersOf(conversationId: String): Set<String> = rosters.value[conversationId].orEmpty()

    /**
     * Our own clean reconnect — the 30-minute lifetime, or a foreground bounce.
     *
     * The rosters stay put for [HOLD_OVER_MILLIS] so a reconnect nobody asked for
     * does not blink every peer offline and back. It is superseded the moment a
     * fresh roster arrives, and [expireHoldOver] is what ends it if one does not.
     */
    fun beginHoldOver() {
        if (rosters.value.isNotEmpty()) holdingOver = true
    }

    /**
     * The hold-over elapsed without a new roster. Whatever we were showing is now
     * older than the server's own staleness window, so it stops being shown.
     */
    fun expireHoldOver() {
        if (!holdingOver) return
        holdingOver = false
        rosters.value = emptyMap()
    }

    /**
     * A drop we did not choose — killed socket, lost tunnel, failed ping.
     *
     * Cleared immediately and unconditionally: an unclean drop is precisely the
     * case where the server's `member_removed` can lag by up to a minute, so a
     * frozen dot would be wrong for exactly as long as it was most visible.
     */
    fun onHardDrop() {
        holdingOver = false
        rosters.value = emptyMap()
    }

    companion object {
        /** Long enough to cover a deliberate reconnect, short enough to stay true. */
        const val HOLD_OVER_MILLIS: Long = 5_000L
    }
}
