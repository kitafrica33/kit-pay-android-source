package com.kit.wallet.data.messaging

import com.kit.wallet.data.repository.AuthenticatedConversation
import com.kit.wallet.data.repository.AuthenticatedConversationMember
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * One conversation exactly as the server last authenticated it, kept on this device.
 *
 * The name is the point: this is a *roster*, not an authority. It is written only from a
 * conversation list the transport has already validated for this activation, and it is read back
 * only to draw the screen — the chat list, a title, who is in a group, whose bubble is whose. No
 * send, no key agreement and no membership decision may be taken on it, because a record on disk
 * cannot prove the server still agrees. Those all continue to run against the live authenticated
 * handles held by [DefaultSecureMessagingChatRuntime], and a stale entry is replaced wholesale the
 * first time the real list loads.
 *
 * Without it, opening the app offline showed nothing at all: the roster lived only in process
 * memory, so a cold start had to reach the server before it could name a single chat, even though
 * every message in them was already decrypted and sitting in the local store.
 *
 * It rides the same hardware-encrypted state store as the libsignal records, under the same
 * activation lease, and is cryptographically erased with them on logout.
 */
@Singleton
internal class ConversationRosterStore @Inject constructor(
    private val stateStore: SecureMessagingStateStore,
) {
    /**
     * Every conversation this device has authenticated for [activation], newest write order
     * irrelevant — the caller sorts by what the transcript says.
     *
     * Never throws for a record it cannot decode: a roster entry lost to a partial write is one
     * chat missing from the list until the next successful load, not a failure to open Messages.
     */
    suspend fun read(
        activation: SecureMessagingActivationCapability,
    ): List<AuthenticatedConversation> = stateStore.withActivationLease(activation) {
        val conversations = mutableListOf<AuthenticatedConversation>()
        var after: String? = null
        repeat(MAX_PAGES) {
            val page = stateStore.readNamespacePage(NAMESPACE, after, PAGE_SIZE)
            val records = page.records()
            records.forEach { record ->
                try {
                    decode(record.bytes)?.let(conversations::add)
                } finally {
                    record.bytes.fill(0)
                }
            }
            after = page.nextAfterRecordKey ?: return@withActivationLease conversations
        }
        conversations
    }

    /**
     * Replaces the stored roster with [conversations], which must be a complete authenticated
     * list rather than a delta.
     *
     * A conversation the account has left is therefore removed here too, so it cannot reappear
     * offline after the server has already stopped returning it. Best-effort by construction: a
     * write failure leaves the previous roster in place and the next successful load retries.
     */
    suspend fun replace(
        activation: SecureMessagingActivationCapability,
        conversations: List<AuthenticatedConversation>,
    ) {
        val encoded = conversations.mapNotNull { conversation ->
            canonicalIdOrNull(conversation.id)?.let { id -> id to encode(conversation) }
        }
        stateStore.withActivationLease(activation) {
            val stale = mutableSetOf<String>()
            var after: String? = null
            repeat(MAX_PAGES) {
                val page = stateStore.readNamespacePage(NAMESPACE, after, PAGE_SIZE)
                page.records().forEach { record ->
                    record.bytes.fill(0)
                    stale += record.recordKey
                }
                after = page.nextAfterRecordKey ?: return@repeat
            }
            encoded.forEach { (id, bytes) ->
                val key = recordKey(id)
                stale -= key
                try {
                    writeRecord(key, bytes)
                } finally {
                    bytes.fill(0)
                }
            }
            stale.forEach { key ->
                runCatching { writeRecord(key, ByteArray(0)) }
            }
        }
    }

    private suspend fun writeRecord(recordKey: String, bytes: ByteArray) {
        repeat(WRITE_ATTEMPTS) {
            val existing = stateStore.read(NAMESPACE, recordKey)
            existing?.bytes?.fill(0)
            try {
                stateStore.write(
                    namespace = NAMESPACE,
                    recordKey = recordKey,
                    expectedVersion = existing?.version,
                    bytes = bytes,
                )
                return
            } catch (_: SecureMessagingStateConflictException) {
                // Another writer advanced the record between the read and the write. Re-read and
                // retry: this is a whole-list replacement, so last writer wins is correct here.
            }
        }
    }

    private fun encode(conversation: AuthenticatedConversation): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            data.writeByte(VERSION.toInt())
            data.writeUTF(conversation.id)
            data.writeUTF(conversation.type)
            data.writeUTF(conversation.title.orEmpty())
            data.writeUTF(conversation.viewerUserId)
            data.writeUTF(conversation.currentUserRole)
            data.writeUTF(conversation.description.orEmpty())
            data.writeUTF(conversation.photoUrl.orEmpty())
            data.writeInt(conversation.members.size)
            conversation.members.forEach { member ->
                data.writeUTF(member.userId)
                data.writeUTF(member.name.orEmpty())
                data.writeUTF(member.role)
            }
        }
        return out.toByteArray()
    }

    private fun decode(bytes: ByteArray): AuthenticatedConversation? = try {
        if (bytes.isEmpty()) {
            null
        } else {
            DataInputStream(ByteArrayInputStream(bytes)).use { data ->
                // A record written before group identity existed still names its chat offline;
                // it simply has no description or photo until the next authenticated load.
                val version = data.readByte()
                if (version != VERSION && version != LEGACY_VERSION) {
                    null
                } else {
                    val id = data.readUTF()
                    val type = data.readUTF()
                    val title = data.readUTF().takeIf(String::isNotEmpty)
                    val viewerUserId = data.readUTF()
                    val currentUserRole = data.readUTF()
                    val description = if (version == VERSION) {
                        data.readUTF().takeIf(String::isNotEmpty)
                    } else {
                        null
                    }
                    val photoUrl = if (version == VERSION) {
                        data.readUTF().takeIf(String::isNotEmpty)
                    } else {
                        null
                    }
                    val count = data.readInt()
                    if (count !in 0..MAX_MEMBERS) {
                        null
                    } else {
                        AuthenticatedConversation(
                            id = id,
                            type = type,
                            title = title,
                            viewerUserId = viewerUserId,
                            currentUserRole = currentUserRole,
                            members = (0 until count).map {
                                AuthenticatedConversationMember(
                                    userId = data.readUTF(),
                                    name = data.readUTF().takeIf(String::isNotEmpty),
                                    role = data.readUTF(),
                                )
                            },
                            description = description,
                            photoUrl = photoUrl,
                        ).takeIf { it.members.any { member -> member.userId == viewerUserId } }
                    }
                }
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // An unreadable roster entry is one row missing from the list until the next load.
        null
    }

    private fun canonicalIdOrNull(conversationId: String): String? =
        conversationId.trim().lowercase().takeIf(CONVERSATION_ID::matches)

    private fun recordKey(conversationId: String) = "roster:$conversationId"

    private companion object {
        const val NAMESPACE = "conversation-roster-v1"
        const val VERSION: Byte = 0x02
        const val LEGACY_VERSION: Byte = 0x01

        /** Matches the chat list's own display bound, so the cache can never outgrow the screen. */
        const val PAGE_SIZE = 100
        const val MAX_PAGES = 20
        const val MAX_MEMBERS = 64
        const val WRITE_ATTEMPTS = 3
        val CONVERSATION_ID = Regex("^[a-z0-9-]{1,64}$")
    }
}
