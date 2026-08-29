package com.kit.wallet.data.messaging

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One membership change, as the durable sync log described it.
 *
 * The server sends the *subject* of the change and nothing else — never who made it — so this is
 * everything a system message can honestly say. [role] is present only on a role change.
 */
internal data class ConversationSystemEvent(
    val eventId: Long,
    val type: String,
    val userId: String?,
    val role: String?,
    val occurredAt: Instant,
    val paymentId: String? = null,
    val contributionId: String? = null,
    val contributorUserId: String? = null,
    val contributionAmountMinor: String? = null,
) {
    init {
        require(eventId > 0) { "A conversation system event needs its sync event ID" }
        if (type in MEMBERSHIP_SYSTEM_EVENT_TYPES) {
            require(!userId.isNullOrBlank()) { "A membership event needs its subject" }
        } else if (type in GROUP_PAYMENT_REQUEST_SYSTEM_EVENT_TYPES) {
            require(!paymentId.isNullOrBlank()) {
                "A financial event needs its exact payment request"
            }
        }
    }
}

internal const val MEMBERSHIP_ADDED_EVENT = "membership.added"
internal const val MEMBERSHIP_ROLE_CHANGED_EVENT = "membership.role_changed"
internal const val MEMBERSHIP_REMOVED_EVENT = "membership.removed"

internal val MEMBERSHIP_SYSTEM_EVENT_TYPES = setOf(
    MEMBERSHIP_ADDED_EVENT,
    MEMBERSHIP_ROLE_CHANGED_EVENT,
    MEMBERSHIP_REMOVED_EVENT,
)

internal val GROUP_PAYMENT_REQUEST_SYSTEM_EVENT_TYPES = setOf(
    "group_payment_request.created",
    "group_payment_request.contributed",
    "group_payment_request.completed",
    "group_payment_request.cancelled",
    "group_payment_request.expired",
)

/**
 * The durable half of a group's system messages.
 *
 * A membership change arrives as a metadata sync event: it carries no ciphertext, so it produces
 * no projection, and replaying the sync log to rebuild one is not possible once the cursor has
 * moved past it. Each change is therefore kept here, in the same hardware-encrypted state store
 * as the libsignal records and the composer drafts — so who joined which group is protected at
 * rest exactly like the messages are, and is cryptographically erased with them on logout.
 *
 * This is a presentation record and nothing else. It is bounded, best-effort and read-only to the
 * rest of the app: no send, no sync and no authorization decision may ever depend on it, and a
 * decode failure returns an empty history rather than propagating.
 */
@Singleton
internal class ConversationSystemEventStore @Inject constructor(
    private val stateStore: SecureMessagingStateStore,
) {
    /** Serialises the read-modify-write of a record. Never held across a memory read. */
    private val writeLock = Mutex()
    private val memoryLock = Any()
    private val loaded = mutableSetOf<String>()
    private val mutableEvents =
        MutableStateFlow<Map<String, List<ConversationSystemEvent>>>(emptyMap())

    /** What has been loaded so far, newest last within each conversation. */
    val events: StateFlow<Map<String, List<ConversationSystemEvent>>> = mutableEvents.asStateFlow()

    /**
     * Reads any conversation not read since the last [forget], leaving the rest alone.
     *
     * Called on the publication path, so it is deliberately cheap after the first pass and never
     * throws: a conversation whose record cannot be read simply shows no system messages.
     */
    suspend fun load(conversationIds: Collection<String>) {
        val wanted = conversationIds.mapNotNull(::canonicalIdOrNull).toSet()
        val missing = synchronized(memoryLock) { wanted.filterNot { it in loaded } }
        if (missing.isEmpty()) return
        val read = missing.associateWith { conversationId ->
            try {
                readStored(conversationId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
        }
        synchronized(memoryLock) {
            loaded += read.keys
            // A record written while this read was in flight wins: it is the newer history.
            mutableEvents.value = read.filterValues { it.isNotEmpty() } + mutableEvents.value
        }
    }

    /**
     * Records one membership change, ignoring a replay of one already stored.
     *
     * The sync log is append-only and a fresh login starts without a cursor, so the same event can
     * arrive more than once; [ConversationSystemEvent.eventId] is what makes that idempotent.
     */
    suspend fun record(conversationId: String, event: ConversationSystemEvent) {
        if (event.type !in MEMBERSHIP_SYSTEM_EVENT_TYPES &&
            event.type !in GROUP_PAYMENT_REQUEST_SYSTEM_EVENT_TYPES
        ) return
        val canonical = canonicalIdOrNull(conversationId) ?: return
        writeLock.withLock {
            repeat(WRITE_ATTEMPTS) {
                val stored = stateStore.read(NAMESPACE, recordKey(canonical))
                val existing = stored?.let { record ->
                    try {
                        decode(record.bytes)
                    } finally {
                        record.bytes.fill(0)
                    }
                }.orEmpty()
                if (existing.any { it.eventId == event.eventId }) {
                    publish(canonical, existing)
                    return
                }
                val merged = bound(existing + event)
                val encoded = encode(merged)
                try {
                    stateStore.write(
                        namespace = NAMESPACE,
                        recordKey = recordKey(canonical),
                        expectedVersion = stored?.version,
                        bytes = encoded,
                    )
                    publish(canonical, merged)
                    return
                } catch (_: SecureMessagingStateConflictException) {
                    // Another writer advanced the record between the read and the write. Re-read
                    // rather than overwrite: losing somebody else's join line would be silent.
                } finally {
                    encoded.fill(0)
                }
            }
        }
    }

    /**
     * Forgets everything held in memory, for a session that is no longer the published one.
     *
     * The records themselves belong to the state store's lifecycle and are erased with the rest
     * of the messaging state; this only makes sure one account never reads another's timeline
     * out of a warm process.
     */
    fun forget() {
        synchronized(memoryLock) {
            loaded.clear()
            mutableEvents.value = emptyMap()
        }
    }

    private fun publish(conversationId: String, history: List<ConversationSystemEvent>) {
        synchronized(memoryLock) {
            loaded += conversationId
            mutableEvents.value = mutableEvents.value + (conversationId to history)
        }
    }

    private suspend fun readStored(conversationId: String): List<ConversationSystemEvent> {
        val record = stateStore.read(NAMESPACE, recordKey(conversationId)) ?: return emptyList()
        return try {
            decode(record.bytes)
        } finally {
            record.bytes.fill(0)
        }
    }

    /** Newest last, deduplicated by event ID, and never more than [MAX_EVENTS] of them. */
    private fun bound(events: List<ConversationSystemEvent>): List<ConversationSystemEvent> =
        events.distinctBy(ConversationSystemEvent::eventId)
            .sortedWith(compareBy({ it.occurredAt }, { it.eventId }))
            .takeLast(MAX_EVENTS)

    private fun encode(events: List<ConversationSystemEvent>): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            data.writeByte(VERSION.toInt())
            data.writeInt(events.size)
            events.forEach { event ->
                data.writeLong(event.eventId)
                data.writeUTF(event.type)
                data.writeUTF(event.userId.orEmpty())
                data.writeUTF(event.role.orEmpty())
                data.writeLong(event.occurredAt.toEpochMilli())
                data.writeUTF(event.paymentId.orEmpty())
                data.writeUTF(event.contributionId.orEmpty())
                data.writeUTF(event.contributorUserId.orEmpty())
                data.writeUTF(event.contributionAmountMinor.orEmpty())
            }
        }
        return out.toByteArray()
    }

    private fun decode(bytes: ByteArray): List<ConversationSystemEvent> = try {
        DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            val version = data.readByte()
            if (version != VERSION && version != LEGACY_VERSION) {
                emptyList()
            } else {
                val count = data.readInt()
                if (count !in 0..MAX_EVENTS) {
                    emptyList()
                } else {
                    bound(
                        (0 until count).map {
                            ConversationSystemEvent(
                                eventId = data.readLong(),
                                type = data.readUTF(),
                                userId = data.readUTF().takeIf(String::isNotEmpty),
                                role = data.readUTF().takeIf(String::isNotEmpty),
                                occurredAt = Instant.ofEpochMilli(data.readLong()),
                                paymentId = if (version == VERSION) {
                                    data.readUTF().takeIf(String::isNotEmpty)
                                } else null,
                                contributionId = if (version == VERSION) {
                                    data.readUTF().takeIf(String::isNotEmpty)
                                } else null,
                                contributorUserId = if (version == VERSION) {
                                    data.readUTF().takeIf(String::isNotEmpty)
                                } else null,
                                contributionAmountMinor = if (version == VERSION) {
                                    data.readUTF().takeIf(String::isNotEmpty)
                                } else null,
                            )
                        },
                    )
                }
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // A record this device can no longer read is a lost timeline annotation, not a lost
        // message. Show none rather than refuse to open the conversation.
        emptyList()
    }

    private fun canonicalIdOrNull(conversationId: String): String? =
        conversationId.trim().lowercase().takeIf(CONVERSATION_ID::matches)

    private fun recordKey(conversationId: String) = "events:$conversationId"

    private companion object {
        const val NAMESPACE = "conversation-system-events"
        const val LEGACY_VERSION: Byte = 0x01
        const val VERSION: Byte = 0x02

        /**
         * Membership changes are rare next to messages, and a group is capped at 32 people, so
         * this holds a long history for a busy group while keeping one bounded record per chat.
         */
        const val MAX_EVENTS = 200
        const val WRITE_ATTEMPTS = 3
        val CONVERSATION_ID = Regex("^[a-z0-9-]{1,64}$")
    }
}
