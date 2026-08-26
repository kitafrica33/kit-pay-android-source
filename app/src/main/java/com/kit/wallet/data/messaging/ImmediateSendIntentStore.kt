package com.kit.wallet.data.messaging

import com.kit.wallet.data.session.SessionFence
import com.kit.wallet.data.session.SessionInvalidatedException
import com.kit.wallet.data.session.SessionStore
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
 * Hardware-encrypted, exact-login-owned queue for sends accepted by the UI immediately.
 *
 * A record is also its local projection. Disk is committed before [items] changes, so every
 * bubble/chip emitted here survives a process restart. Removal is a tombstone because the common
 * secure state store intentionally has no per-record delete.
 */
@Singleton
internal class ImmediateSendIntentStore @Inject constructor(
    private val stateStore: SecureMessagingStateStore,
    private val sessions: SessionStore,
) {
    private val mutex = Mutex()
    private val live = mutableMapOf<String, ImmediateSendIntent>()
    private val versions = mutableMapOf<String, Long>()
    private val mutableSnapshot = MutableStateFlow(ImmediateSendOwnerSnapshot(null, emptyList()))
    private var owner: SessionFence? = null
    private var loaded = false

    val items: StateFlow<List<ImmediateSendIntent>> = CurrentOwnerImmediateSendFlow(
        mutableSnapshot,
        sessions,
    )

    fun currentOwnerFence(): SessionFence? = sessions.current()?.fence()

    suspend fun loadForCurrentOwner(): SessionFence? {
        val expected = sessions.current()?.fence()
        if (expected == null) {
            forget()
            return null
        }
        withOwner(expected) { loadLocked() }
        return expected.takeIf(::isCurrentOwner)
    }

    suspend fun reload() {
        val expected = currentOwnerOrThrow()
        withOwner(expected) {
            loaded = false
            loadLocked()
        }
    }

    suspend fun forget() {
        mutex.withLock {
            live.clear()
            versions.clear()
            owner = null
            loaded = false
            mutableSnapshot.value = ImmediateSendOwnerSnapshot(null, emptyList())
        }
    }

    fun itemsForOwner(expectedOwner: SessionFence): List<ImmediateSendIntent> =
        if (isCurrentOwner(expectedOwner)) mutableSnapshot.value.visibleTo(expectedOwner) else emptyList()

    fun find(id: String): ImmediateSendIntent? = items.value.firstOrNull { it.id == id }

    fun isCurrentOwner(expectedOwner: SessionFence): Boolean =
        sessions.current()?.fence() == expectedOwner && mutableSnapshot.value.owner == expectedOwner

    suspend fun enqueue(intent: ImmediateSendIntent): SessionFence {
        val expected = currentOwnerOrThrow()
        enqueueForOwner(expected, intent)
        return expected
    }

    suspend fun enqueueForOwner(expectedOwner: SessionFence, intent: ImmediateSendIntent) {
        withOwner(expectedOwner) {
            loadLocked()
            check(live[intent.id] == null) { "An immediate send already uses this identity" }
            writeLocked(intent)
            publishLocked()
        }
    }

    suspend fun replaceForOwner(
        expectedOwner: SessionFence,
        expected: ImmediateSendIntent,
        updated: ImmediateSendIntent,
    ): Boolean {
        require(expected.id == updated.id) { "An immediate send cannot change identity" }
        return withOwner(expectedOwner) {
            loadLocked()
            if (live[expected.id] != expected) return@withOwner false
            writeLocked(updated)
            publishLocked()
            true
        }
    }

    suspend fun markRetryRequiredForOwner(
        expectedOwner: SessionFence,
        expected: ImmediateSendIntent,
    ): Boolean = replaceForOwner(
        expectedOwner,
        expected,
        expected.copy(state = ImmediateSendState.RETRY_REQUIRED),
    )

    suspend fun rearmForOwner(expectedOwner: SessionFence, id: String): Boolean =
        withOwner(expectedOwner) {
            loadLocked()
            val existing = live[id] ?: return@withOwner false
            if (existing.state == ImmediateSendState.WAITING) return@withOwner true
            writeLocked(existing.copy(state = ImmediateSendState.WAITING))
            publishLocked()
            true
        }

    suspend fun removeForOwner(expectedOwner: SessionFence, id: String) {
        withOwner(expectedOwner) {
            loadLocked()
            if (live[id] == null) return@withOwner
            tombstoneLocked(id)
            publishLocked()
        }
    }

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
        mutableSnapshot.value = ImmediateSendOwnerSnapshot(expectedOwner, emptyList())
    }

    private suspend fun loadLocked() {
        if (loaded) return
        live.clear()
        versions.clear()
        var cursor: String? = null
        do {
            val page = stateStore.readNamespacePage(NAMESPACE, cursor, PAGE_SIZE)
            page.records().forEach { record ->
                try {
                    val id = idFor(record.recordKey)
                    versions[id] = record.version
                    val decoded = decode(record.bytes)
                    if (decoded != null && recordKey(decoded.id) == record.recordKey) {
                        live[decoded.id] = decoded
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

    private suspend fun writeLocked(intent: ImmediateSendIntent) {
        val encoded = ImmediateSendIntentCodec.encode(intent)
        try {
            val committed = stateStore.write(
                namespace = NAMESPACE,
                recordKey = recordKey(intent.id),
                expectedVersion = versions[intent.id],
                bytes = encoded,
            )
            versions[intent.id] = committed.version
            live[intent.id] = intent
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
        val publishedOwner = checkNotNull(owner)
        val published = live.values.sortedWith(
            compareBy<ImmediateSendIntent>({ it.createdAtEpochMillis }, { it.id }),
        )
        mutableSnapshot.value = ImmediateSendOwnerSnapshot(publishedOwner, published)
        if (published.isEmpty() && versions.isNotEmpty()) {
            runCatching { stateStore.deleteNamespace(NAMESPACE) }
                .onSuccess { versions.clear() }
        }
    }

    private fun decode(bytes: ByteArray): ImmediateSendIntent? =
        if (bytes.size == 1 && bytes[0] == TOMBSTONE) null
        else ImmediateSendIntentCodec.decode(bytes)

    private fun recordKey(id: String): String = "intent:$id"

    private fun idFor(recordKey: String): String = recordKey.removePrefix("intent:")

    private companion object {
        const val NAMESPACE = "immediate-send"
        const val TOMBSTONE: Byte = 0
        const val PAGE_SIZE = 100
    }
}

private data class ImmediateSendOwnerSnapshot(
    val owner: SessionFence?,
    val items: List<ImmediateSendIntent>,
) {
    fun visibleTo(current: SessionFence?): List<ImmediateSendIntent> =
        items.takeIf { owner != null && owner == current }.orEmpty()
}

/** A StateFlow whose replay can never expose the previous account's plaintext intent. */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class, InternalCoroutinesApi::class)
private class CurrentOwnerImmediateSendFlow(
    private val source: StateFlow<ImmediateSendOwnerSnapshot>,
    private val sessions: SessionStore,
) : StateFlow<List<ImmediateSendIntent>> {
    override val value: List<ImmediateSendIntent>
        get() = source.value.visibleTo(sessions.current()?.fence())

    override val replayCache: List<List<ImmediateSendIntent>>
        get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<List<ImmediateSendIntent>>): Nothing {
        combine(source, sessions.session) { snapshot, session ->
            snapshot.visibleTo(session?.fence())
        }.distinctUntilChanged().collect(collector)
        error("An immediate-send StateFlow collection completed unexpectedly")
    }
}
