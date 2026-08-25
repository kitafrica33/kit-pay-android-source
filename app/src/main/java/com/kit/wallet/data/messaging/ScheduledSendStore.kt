package com.kit.wallet.data.messaging

import com.kit.wallet.data.session.SessionFence
import com.kit.wallet.data.session.SessionInvalidatedException
import com.kit.wallet.data.session.SessionStore
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Rearms whatever wakes the device for the next scheduled send.
 *
 * A fun interface so the store stays a pure state holder: the WorkManager binding is supplied from
 * the DI graph, and tests drive time themselves without a scheduler at all. `null` means nothing is
 * waiting and any pending wake can be dropped.
 */
internal fun interface ScheduledSendAlarm {
    fun rearm(nextDueAtEpochMillis: Long?)
}

/**
 * Durable, hardware-encrypted storage for items the user asked Kit Pay to send later.
 *
 * One record per item in its own namespace of [SecureMessagingStateStore], which gives an unsent
 * message the same at-rest protection as a sent one and the same cryptographic erasure on sign-out.
 * The in-memory map is the read path — a conversation renders its scheduled items from RAM — and
 * every mutation is written to disk before the mirror is published, so what the user sees is never
 * ahead of what survives a restart.
 *
 * The underlying store has no per-record delete, so removal writes a one-byte tombstone. When the
 * last live item goes the whole namespace is dropped, which is what stops tombstones accumulating.
 */
@Singleton
internal class ScheduledSendStore @Inject constructor(
    private val stateStore: SecureMessagingStateStore,
    private val sessions: SessionStore,
    private val alarm: ScheduledSendAlarm? = null,
) {
    private val mutex = Mutex()
    private val mutableSnapshot = MutableStateFlow(ScheduledSendOwnerSnapshot(null, emptyList()))

    /**
     * Every item still waiting to be sent, soonest first, but only while its exact authenticated
     * owner remains current. The synchronous [StateFlow.value] check closes the small window before
     * the session-flow collector handles an account switch; collection also emits an empty list as
     * soon as that switch happens.
     */
    val items: StateFlow<List<ScheduledSend>> = CurrentOwnerScheduledSendFlow(
        source = mutableSnapshot,
        sessions = sessions,
    )

    private val live = mutableMapOf<String, ScheduledSend>()

    /** Record versions for the optimistic writes; a missing entry means "create". */
    private val versions = mutableMapOf<String, Long>()
    private var owner: SessionFence? = null
    private var loaded = false

    /** Reads the namespace from disk. Cheap and idempotent after the first successful call. */
    suspend fun load() {
        loadForCurrentOwner()
    }

    /** Re-reads the namespace even if it has been read before, e.g. after an account change. */
    suspend fun reload() {
        val expected = currentOwnerOrThrow()
        withOwner(expected) {
            loaded = false
            loadLocked()
        }
    }

    /** Items for one conversation, soonest first. Reads the published mirror, never disk. */
    fun forConversation(conversationId: String): List<ScheduledSend> =
        items.value.filter { it.conversationId == conversationId }

    fun find(id: String): ScheduledSend? = items.value.firstOrNull { it.id == id }

    /** Creates or replaces one item. The caller owns validation of the send time. */
    suspend fun put(send: ScheduledSend) {
        putForOwner(currentOwnerOrThrow(), send)
    }

    /** Writes only for the owner captured when the user initiated the action. */
    internal suspend fun putForOwner(expectedOwner: SessionFence, send: ScheduledSend) {
        withOwner(expectedOwner) {
            loadLocked()
            writeLocked(send)
            publishLocked()
        }
    }

    /**
     * Replaces [expected] with [updated] only if nothing else has changed it meanwhile.
     *
     * Returns false when the item is gone or has moved on, which is exactly how a second dispatch
     * discovers that the first one already owns this send.
     */
    suspend fun compareAndSet(expected: ScheduledSend, updated: ScheduledSend): Boolean {
        val owner = currentOwnerOrThrow()
        return compareAndSetForOwner(owner, expected, updated)
    }

    /** The dispatcher's owner-pinned form; an obsolete worker can never write into a successor. */
    internal suspend fun compareAndSetForOwner(
        expectedOwner: SessionFence,
        expected: ScheduledSend,
        updated: ScheduledSend,
    ): Boolean {
        require(expected.id == updated.id) { "A scheduled send cannot change identity" }
        return withOwner(expectedOwner) {
            loadLocked()
            if (live[expected.id] != expected) return@withOwner false
            writeLocked(updated)
            publishLocked()
            true
        }
    }

    /** Removes one item. Removing something already gone is not an error. */
    suspend fun remove(id: String) {
        removeForOwner(currentOwnerOrThrow(), id)
    }

    /**
     * Removes only the exact item the caller observed. A dispatch that claimed it meanwhile wins,
     * so cancelling can never delete an item after its gateway handoff has begun.
     */
    internal suspend fun removeIfUnchangedForOwner(
        expectedOwner: SessionFence,
        expected: ScheduledSend,
    ): Boolean = withOwner(expectedOwner) {
        loadLocked()
        if (live[expected.id] != expected) return@withOwner false
        tombstoneLocked(expected.id)
        publishLocked()
        true
    }

    /** The dispatcher's owner-pinned form; a late completion cannot delete a successor's item. */
    internal suspend fun removeForOwner(expectedOwner: SessionFence, id: String) {
        withOwner(expectedOwner) {
            loadLocked()
            if (!live.containsKey(id)) return@withOwner
            tombstoneLocked(id)
            publishLocked()
        }
    }

    /**
     * Drops the in-memory mirror without touching disk.
     *
     * For an account change or a sign-out, where the records belong to the outgoing identity and the
     * incoming one must not see them; sign-out erases the namespace itself, cryptographically. The
     * armed wake is deliberately left alone: a wake that finds nothing costs one no-op run, whereas
     * cancelling it for what turns out to be a lifecycle blip would silently drop a send the user is
     * still expecting.
     */
    suspend fun forget() {
        mutex.withLock {
            live.clear()
            versions.clear()
            owner = null
            loaded = false
            mutableSnapshot.value = ScheduledSendOwnerSnapshot(null, emptyList())
        }
    }

    /** Drops every item without touching the rest of the messaging state. */
    suspend fun clear() {
        val expected = sessions.current()?.fence()
        if (expected == null) {
            forget()
            return
        }
        withOwner(expected) {
            runCatching { stateStore.deleteNamespace(NAMESPACE) }
            live.clear()
            versions.clear()
            loaded = true
            publishLocked()
        }
    }

    /** Loads and returns the exact owner a dispatcher must carry for this run. */
    internal suspend fun loadForCurrentOwner(): SessionFence? {
        val expected = sessions.current()?.fence()
        if (expected == null) {
            forget()
            return null
        }
        withOwner(expected) { loadLocked() }
        return expected.takeIf(::isCurrentOwner)
    }

    /** Loads only while [expectedOwner] is still authenticated. */
    internal suspend fun loadForOwner(expectedOwner: SessionFence) {
        withOwner(expectedOwner) { loadLocked() }
    }

    /** A synchronous, fail-closed view for a worker that already captured [expectedOwner]. */
    internal fun itemsForOwner(expectedOwner: SessionFence): List<ScheduledSend> {
        if (!isCurrentOwner(expectedOwner)) return emptyList()
        return mutableSnapshot.value.visibleTo(expectedOwner)
    }

    internal fun isCurrentOwner(expectedOwner: SessionFence): Boolean =
        sessions.current()?.fence() == expectedOwner && mutableSnapshot.value.owner == expectedOwner

    /** Captures the session fence before an asynchronous UI mutation can be delayed. */
    internal fun currentOwnerFence(): SessionFence? = sessions.current()?.fence()

    private fun currentOwnerOrThrow(): SessionFence =
        sessions.current()?.fence() ?: throw SessionInvalidatedException()

    /** Session lock -> messaging-state lease -> queue lock is the only mutation lock order. */
    private suspend fun <T> withOwner(
        expectedOwner: SessionFence,
        operation: suspend () -> T,
    ): T = sessions.withCurrentSession(expectedOwner) { current ->
        check(current.fence() == expectedOwner)
        stateStore.withStateLease {
            mutex.withLock {
                bindOwnerLocked(expectedOwner)
                operation()
            }
        }
    }

    private fun bindOwnerLocked(expectedOwner: SessionFence) {
        if (owner == expectedOwner) return
        live.clear()
        versions.clear()
        loaded = false
        owner = expectedOwner
        mutableSnapshot.value = ScheduledSendOwnerSnapshot(expectedOwner, emptyList())
    }

    private suspend fun loadLocked() {
        if (loaded) return
        live.clear()
        versions.clear()
        var cursor: String? = null
        do {
            val page = stateStore.readNamespacePage(
                namespace = NAMESPACE,
                afterRecordKey = cursor,
                limit = PAGE_SIZE,
            )
            page.records().forEach { record ->
                try {
                    // Every record's version is remembered, tombstones and unreadable rows
                    // included: it is what a later write needs in order to replace them.
                    versions[idFor(record.recordKey)] = record.version
                    val send = decode(record.bytes)
                    // A record whose key disagrees with the identity inside it is not this store's
                    // to trust; ignoring it stops a corrupted namespace resurrecting a send under
                    // somebody else's identity.
                    if (send != null && recordKey(send.id) == record.recordKey) {
                        live[send.id] = send
                    }
                } finally {
                    record.bytes.fill(0)
                }
            }
            cursor = page.nextAfterRecordKey
        } while (cursor != null)
        loaded = true
        publishLocked()
    }

    private suspend fun writeLocked(send: ScheduledSend) {
        val encoded = byteArrayOf(VERSION) + send.encode().toByteArray(StandardCharsets.UTF_8)
        try {
            val committed = stateStore.write(
                namespace = NAMESPACE,
                recordKey = recordKey(send.id),
                expectedVersion = versions[send.id],
                bytes = encoded,
            )
            versions[send.id] = committed.version
            live[send.id] = send
        } finally {
            encoded.fill(0)
        }
    }

    private suspend fun tombstoneLocked(id: String) {
        val committed = stateStore.write(
            namespace = NAMESPACE,
            recordKey = recordKey(id),
            expectedVersion = versions[id],
            bytes = byteArrayOf(TOMBSTONE),
        )
        versions[id] = committed.version
        live.remove(id)
    }

    private suspend fun publishLocked() {
        val publishedOwner = checkNotNull(owner) { "A scheduled queue cannot publish without an owner" }
        val published = live.values.sortedWith(
            compareBy({ it.scheduledAtEpochMillis }, { it.id }),
        )
        mutableSnapshot.value = ScheduledSendOwnerSnapshot(publishedOwner, published)
        // Nothing live at all: drop the namespace so its tombstones go with it. A failure here is
        // cosmetic — those records read back as absent either way — so it must not fail the
        // mutation that got us here.
        if (published.isEmpty() && versions.isNotEmpty()) {
            runCatching { stateStore.deleteNamespace(NAMESPACE) }
                .onSuccess { versions.clear() }
        }
        alarm?.rearm(nextWakeAtEpochMillis(published))
    }

    private fun decode(bytes: ByteArray): ScheduledSend? {
        if (bytes.size < 2 || bytes[0] != VERSION) return null
        return ScheduledSend.parse(String(bytes, 1, bytes.size - 1, StandardCharsets.UTF_8))
    }

    private fun recordKey(id: String): String = "send:$id"

    private fun idFor(recordKey: String): String = recordKey.removePrefix("send:")

    private companion object {
        const val NAMESPACE = "scheduled-send"
        const val VERSION: Byte = 0x01
        const val TOMBSTONE: Byte = 0x00
        const val PAGE_SIZE = 100
    }
}

private data class ScheduledSendOwnerSnapshot(
    val owner: SessionFence?,
    val items: List<ScheduledSend>,
) {
    fun visibleTo(current: SessionFence?): List<ScheduledSend> =
        items.takeIf { owner != null && owner == current }.orEmpty()
}

/** A StateFlow whose replay can never expose the previous account's plaintext queue. */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class, InternalCoroutinesApi::class)
private class CurrentOwnerScheduledSendFlow(
    private val source: StateFlow<ScheduledSendOwnerSnapshot>,
    private val sessions: SessionStore,
) : StateFlow<List<ScheduledSend>> {
    override val value: List<ScheduledSend>
        get() = source.value.visibleTo(sessions.current()?.fence())

    override val replayCache: List<List<ScheduledSend>>
        get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<List<ScheduledSend>>): Nothing {
        combine(source, sessions.session) { snapshot, session ->
            snapshot.visibleTo(session?.fence())
        }.distinctUntilChanged().collect(collector)
        error("A StateFlow collection completed unexpectedly")
    }
}

/**
 * When the device next needs waking for [items], or null when nothing is waiting on a clock.
 *
 * An item awaiting a person's decision has no wake time at all — arming a timer for it would burn
 * a wakelock to discover, every hour, that there is still nothing to do.
 */
internal fun nextWakeAtEpochMillis(items: List<ScheduledSend>): Long? = items
    .filter { it.state != ScheduledSendState.UNCONFIRMED }
    .minOfOrNull { it.nextEligibleAtEpochMillis() }
