package com.kit.wallet.data.messaging

import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Best-effort encrypted per-conversation composer drafts.
 *
 * Drafts live in the same hardware-encrypted messaging state store as the libsignal records, so
 * they share its at-rest protection and are cryptographically erased with the rest of the local
 * messaging state when the session is removed. A draft is a convenience projection of the
 * composer, never part of the authenticated message history: absence and failures are silent,
 * and no send path may ever depend on one.
 */
@Singleton
internal class SecureMessagingComposerDraftStore @Inject constructor(
    private val stateStore: SecureMessagingStateStore,
) {
    /** Returns the stored draft text, or null when none (or only a tombstone) is stored. */
    suspend fun read(conversationId: String): String? {
        val record = stateStore.read(NAMESPACE, recordKey(conversationId)) ?: return null
        return try {
            decode(record.bytes)
        } finally {
            record.bytes.fill(0)
        }
    }

    /** Blank text clears the draft; concurrent writers resolve last-writer-wins. */
    suspend fun save(conversationId: String, text: String) {
        val bounded = text.take(MAX_DRAFT_CHARS)
        val encoded = if (bounded.isBlank()) {
            byteArrayOf(TOMBSTONE)
        } else {
            byteArrayOf(VERSION) + bounded.toByteArray(StandardCharsets.UTF_8)
        }
        try {
            repeat(WRITE_ATTEMPTS) {
                val existing = stateStore.read(NAMESPACE, recordKey(conversationId))
                val unchanged = existing?.let { record ->
                    try {
                        record.bytes.contentEquals(encoded)
                    } finally {
                        record.bytes.fill(0)
                    }
                } == true
                if (unchanged) return
                // Nothing stored yet and nothing to store: never create a tombstone-only record.
                if (existing == null && bounded.isBlank()) return
                try {
                    stateStore.write(
                        namespace = NAMESPACE,
                        recordKey = recordKey(conversationId),
                        expectedVersion = existing?.version,
                        bytes = encoded,
                    )
                    return
                } catch (_: SecureMessagingStateConflictException) {
                    // Another process advanced the record between read and write; re-resolve.
                }
            }
        } finally {
            encoded.fill(0)
        }
    }

    suspend fun clear(conversationId: String) = save(conversationId, "")

    private fun decode(bytes: ByteArray): String? {
        if (bytes.size < 2 || bytes[0] != VERSION) return null
        return String(bytes, 1, bytes.size - 1, StandardCharsets.UTF_8)
            .takeIf(String::isNotBlank)
    }

    private fun recordKey(conversationId: String): String {
        val canonical = conversationId.trim().lowercase()
        require(DRAFT_CONVERSATION_ID.matches(canonical)) { "Invalid draft conversation ID" }
        return "draft:$canonical"
    }

    private companion object {
        const val NAMESPACE = "composer-draft"
        const val VERSION: Byte = 0x01
        const val TOMBSTONE: Byte = 0x00
        const val MAX_DRAFT_CHARS = 8_192
        const val WRITE_ATTEMPTS = 3
        val DRAFT_CONVERSATION_ID = Regex("^[a-z0-9-]{1,64}$")
    }
}
