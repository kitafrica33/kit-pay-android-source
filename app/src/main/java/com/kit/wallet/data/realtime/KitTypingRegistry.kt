package com.kit.wallet.data.realtime

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Who is presently typing in each conversation.
 *
 * Peer X is typing in C iff a `kit.typing` frame carrying X's `public_id` arrived
 * on `presence-kit.conv.{C}` within the last [EXPIRY_MILLIS] and no
 * `kit.typing.stop` for X has arrived since. The expiry is the whole reason a
 * bubble cannot get stuck: a peer whose process is killed mid-word sends no
 * `stop`, and the bubble has to disappear anyway.
 *
 * **RAM only, never Room**, and never a message: a typing signal is not content,
 * has no id, no order and no durability, and must never be replayable into a
 * conversation or resumable from a cursor.
 *
 * Two independent filters run before anything is recorded, and both matter:
 *
 * - the sender must be in that conversation's current presence roster, so a frame
 *   naming somebody who is not even watching the channel is discarded;
 * - the sender must not be us, because our other device typing is not a peer
 *   typing and rendering it would be visibly wrong.
 *
 * Attribution itself is the server's job — `data.user` is written from the
 * authenticated session on the originating request and never from a request
 * body — so these two are a second line rather than the first.
 */
@Singleton
internal class KitTypingRegistry @Inject constructor(
    private val presence: KitPresenceRegistry,
    private val clock: KitRealtimeClock,
) {
    private data class Typist(val expiresAtMillis: Long, val acceptedAtMillis: Long)

    private val typists = mutableMapOf<String, MutableMap<String, Typist>>()

    private val visible = MutableStateFlow<Map<String, Set<String>>>(emptyMap())

    /** conversation id → the `public_id`s whose bubble is currently showing. */
    val typing: StateFlow<Map<String, Set<String>>> = visible.asStateFlow()

    /** The peers typing in [conversationId], never including us. */
    fun peersTypingIn(conversationId: String): Flow<Set<String>> = visible
        .map { it[conversationId].orEmpty() }
        .distinctUntilChanged()

    /**
     * Ingests one server-originated typing frame. Returns whether it was accepted,
     * which the tests assert on directly rather than having to infer from state.
     */
    fun onTypingFrame(frame: KitRealtimeFrame.Typing, conversationId: String): Boolean {
        // Read from the presence registry rather than keeping a second copy: the two
        // filters below have to agree about who "we" are, and one field cannot drift.
        val self = presence.selfPublicId
        if (self != null && frame.user == self) return false
        if (frame.user !in presence.membersOf(conversationId)) return false

        val now = clock.elapsedMillis()

        if (!frame.active) {
            val removed = typists[conversationId]?.remove(frame.user) != null
            if (removed) publish()
            return removed
        }

        val existing = typists[conversationId]?.get(frame.user)
        // Flood guard. A peer that ignores its own throttle — or a compromised one
        // trying to keep a connection busy — gets its extra frames dropped rather
        // than being allowed to drive a recomposition per frame.
        if (existing != null && now - existing.acceptedAtMillis < FLOOD_GUARD_MILLIS) return false

        typists.getOrPut(conversationId) { mutableMapOf() }[frame.user] =
            Typist(expiresAtMillis = now + EXPIRY_MILLIS, acceptedAtMillis = now)
        publish()
        return true
    }

    /**
     * Drops every bubble whose [EXPIRY_MILLIS] has passed. Driven by a one-second
     * tick that runs only while at least one conversation is subscribed, so an idle
     * chat list costs nothing.
     */
    fun prune() {
        val now = clock.elapsedMillis()
        var changed = false

        val conversations = typists.keys.toList()
        for (conversationId in conversations) {
            val entries = typists[conversationId] ?: continue
            val expired = entries.filterValues { it.expiresAtMillis <= now }.keys
            if (expired.isEmpty()) continue
            expired.forEach(entries::remove)
            if (entries.isEmpty()) typists.remove(conversationId)
            changed = true
        }

        if (changed) publish()
    }

    /** Left the conversation, or gave up its channel. */
    fun forget(conversationId: String) {
        if (typists.remove(conversationId) != null) publish()
    }

    /**
     * The socket went away. Every bubble was predicated on a live channel we no
     * longer have, so none of them can be trusted to still be true.
     */
    fun clear() {
        if (typists.isEmpty()) return
        typists.clear()
        publish()
    }

    private fun publish() {
        visible.value = typists
            .mapValues { (_, entries) -> entries.keys.toSet() }
            .filterValues { it.isNotEmpty() }
    }

    companion object {
        /** 1.5× the sender's throttle, so one dropped frame cannot flicker a bubble. */
        const val EXPIRY_MILLIS: Long = 6_000L

        /** At most one accepted frame per peer per this window. */
        const val FLOOD_GUARD_MILLIS: Long = 2_000L

        /** How often [prune] runs while any conversation channel is subscribed. */
        const val PRUNE_INTERVAL_MILLIS: Long = 1_000L
    }
}
