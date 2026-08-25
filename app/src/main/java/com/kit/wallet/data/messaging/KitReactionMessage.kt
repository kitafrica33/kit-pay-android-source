package com.kit.wallet.data.messaging

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.text.Normalizer

/** Whether a reaction descriptor puts a reaction on a message or takes one off. */
internal enum class KitReactionAction(val wire: String) {
    ADD("add"),
    REMOVE("remove"),
    ;

    companion object {
        fun fromWire(value: String): KitReactionAction? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * End-to-end encrypted reaction carried as the authenticated message text.
 *
 * A reaction is an ordinary secure message whose Signal-encrypted text is `KITRXN1:` followed
 * by URL-encoded key=value pairs in a fixed order. Riding the existing message pipeline is what
 * makes reactions durable: they inherit its ordering, deduplication, sync cursor, offline outbox,
 * retry and per-device fanout unchanged. The outer envelope identifies this as a reaction and
 * exposes its target message ID, while the encrypted descriptor keeps the emoji and add/remove
 * action hidden from the server.
 *
 * The reactor is deliberately *not* a field. Identity comes from the authenticated Signal sender
 * of the carrying message, so a peer cannot attribute a reaction to anybody but itself.
 */
internal data class KitReactionMessage(
    /** The message this reaction is attached to. */
    val targetMessageId: String,
    val emoji: String,
    val action: KitReactionAction,
) {
    /** Fixed field order keeps encoding deterministic, so retry text equality holds. */
    fun encode(): String = buildString {
        append(PREFIX)
        append("v=1")
        append("&a=").append(action.wire)
        append("&t=").append(targetMessageId.canonicalPercentEncode())
        append("&e=").append(emoji.nfc().canonicalPercentEncode())
    }

    companion object {
        const val PREFIX = "KITRXN1:"

        // Wire bounds shared with iOS, so both clients accept and reject exactly the same
        // authenticated content. The four-scalar ceiling admits common modifier sequences while
        // deliberately rejecting longer ZWJ families.
        private const val MAX_DESCRIPTOR_LENGTH = 256
        internal const val MAX_EMOJI_CODE_POINTS = 4
        internal const val MAX_EMOJI_UTF8_BYTES = 32
        private val CANONICAL_UUID = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        )

        fun isReactionText(text: String): Boolean = text.startsWith(PREFIX)

        /**
         * User-authored text cannot enter the reaction wire namespace. A typed descriptor would
         * otherwise be folded into a message's reaction set and vanish from the peer's transcript,
         * so the boundary also catches one hidden behind whitespace before a composer trims it.
         */
        fun beginsWithReservedPrefix(text: String): Boolean =
            text.dropWhile(Char::isWhitespace).startsWith(PREFIX)

        /** Strict parse; returns null for anything that is not a well-formed v1 reaction. */
        fun parse(text: String): KitReactionMessage? {
            if (!text.startsWith(PREFIX) || text.length > MAX_DESCRIPTOR_LENGTH) return null
            val fields = mutableMapOf<String, String>()
            for (pair in text.substring(PREFIX.length).split('&')) {
                val separator = pair.indexOf('=')
                if (separator <= 0) return null
                val key = pair.substring(0, separator)
                val value = pair.substring(separator + 1).urlDecode() ?: return null
                if (fields.put(key, value) != null) return null
            }
            if (fields["v"] != "1") return null
            if (fields.keys != setOf("v", "a", "t", "e")) return null
            val targetMessageId = fields["t"] ?: return null
            val emoji = fields["e"] ?: return null
            val canonicalEmoji = emoji.nfc()
            val action = fields["a"]?.let(KitReactionAction::fromWire) ?: return null
            if (!CANONICAL_UUID.matches(targetMessageId)) return null
            if (!isAcceptableReaction(canonicalEmoji)) return null
            val parsed = KitReactionMessage(
                targetMessageId = targetMessageId,
                emoji = canonicalEmoji,
                action = action,
            )
            // The authenticated descriptor has one canonical representation. Reject unknown or
            // reordered fields, alternate escaping and noncanonical values so a future parser
            // cannot assign a second meaning to already-authenticated content.
            return parsed.takeIf { it.encode() == text }
        }

        /**
         * Whether [emoji] is a reaction rather than smuggled text.
         *
         * There is no stable cross-platform Unicode emoji predicate, so the shared rule is only
         * structural: one to four Unicode scalars, at most 32 UTF-8 bytes, with no whitespace.
         */
        fun isAcceptableReaction(emoji: String): Boolean {
            val canonicalEmoji = emoji.nfc()
            if (canonicalEmoji.isEmpty()) return false
            if (canonicalEmoji.toByteArray(StandardCharsets.UTF_8).size > MAX_EMOJI_UTF8_BYTES) {
                return false
            }
            val codePoints = canonicalEmoji.codePoints().toArray()
            if (codePoints.isEmpty() || codePoints.size > MAX_EMOJI_CODE_POINTS) return false
            return codePoints.none { codePoint ->
                Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) ||
                    codePoint == 0x0085
            }
        }

        /** Byte-exact shared iOS/Android encoding, with form spaces canonicalized to `%20`. */
        private fun String.canonicalPercentEncode(): String = buildString {
            val hex = "0123456789ABCDEF"
            this@canonicalPercentEncode.toByteArray(StandardCharsets.UTF_8).forEach { signedByte ->
                val byte = signedByte.toInt() and 0xff
                if (
                    byte in 'a'.code..'z'.code || byte in 'A'.code..'Z'.code ||
                    byte in '0'.code..'9'.code || byte == '-'.code || byte == '.'.code ||
                    byte == '_'.code || byte == '*'.code
                ) {
                    append(byte.toChar())
                } else {
                    append('%')
                    append(hex[byte ushr 4])
                    append(hex[byte and 0x0f])
                }
            }
        }

        private fun String.urlDecode(): String? =
            runCatching { URLDecoder.decode(this, StandardCharsets.UTF_8.name()) }.getOrNull()

        private fun String.nfc(): String = Normalizer.normalize(this, Normalizer.Form.NFC)
    }
}
