package com.kit.wallet.data.messaging

/**
 * End-to-end encrypted correction carried as the authenticated message text.
 *
 * An edit is an ordinary secure message whose Signal-encrypted text is `KITEDIT1:v=1&t=<target>`
 * followed by `&b=` and the replacement wording verbatim. Riding the existing message pipeline is
 * what makes corrections durable: they inherit its ordering, deduplication, sync cursor, offline
 * outbox, retry and per-device fanout unchanged. The outer envelope identifies this as an edit and
 * exposes the message it supersedes, while the encrypted descriptor keeps the new wording hidden
 * from the server exactly as the original was.
 *
 * The author is deliberately *not* a field. Identity comes from the authenticated Signal sender of
 * the carrying message, so a peer cannot pass off a correction as somebody else's second thought;
 * a projection that sees an edit from anyone but the original's author must discard it.
 *
 * The body is the last field and is carried unencoded. Everything after `&b=` is the replacement,
 * so an ampersand or an equals sign someone typed stays exactly what they typed, and the wording
 * costs the wire no more than it did the first time.
 */
internal data class KitEditMessage(
    /** The message whose wording this replaces. */
    val targetMessageId: String,
    /** The replacement wording, already trimmed to what the composer would have sent. */
    val body: String,
) {
    /** Fixed field order keeps encoding deterministic, so retry text equality holds. */
    fun encode(): String = PREFIX + "v=1&t=" + targetMessageId + "&b=" + body

    companion object {
        const val PREFIX = "KITEDIT1:"
        private const val HEADER = PREFIX + "v=1&t="
        private const val BODY_SEPARATOR = "&b="
        private const val UUID_LENGTH = 36

        /**
         * The same ceiling the ordinary text profile enforces, so a correction can be as long as
         * the message it replaces was allowed to be, and no descriptor can outgrow the wire it
         * has to fit in.
         */
        internal const val MAX_DESCRIPTOR_LENGTH = 8_000

        /**
         * How long after sending its author may still replace the wording. The same figure the
         * server enforces, so "fifteen minutes to edit" means one thing on the screen and another
         * nowhere.
         */
        const val EDIT_WINDOW_MILLIS = 15L * 60L * 1_000L

        private val CANONICAL_UUID = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        )

        fun isEditText(text: String): Boolean = text.startsWith(PREFIX)

        /**
         * User-authored text cannot enter the edit wire namespace. A typed descriptor would
         * otherwise rewrite a message in the peer's transcript, so the boundary also catches one
         * hidden behind whitespace before a composer trims it.
         */
        fun beginsWithReservedPrefix(text: String): Boolean =
            text.dropWhile(Char::isWhitespace).startsWith(PREFIX)

        /**
         * Whether [body] is wording a correction may carry.
         *
         * It has to be something the composer could have sent in the first place: present, already
         * trimmed, within the text profile, and not itself a descriptor in one of Kit Pay's
         * reserved namespaces — otherwise an edit would become a way to author content the
         * composer refuses.
         */
        fun isAcceptableBody(body: String): Boolean =
            body.isNotEmpty() &&
                body == body.trim() &&
                HEADER.length + UUID_LENGTH + BODY_SEPARATOR.length + body.length <=
                MAX_DESCRIPTOR_LENGTH &&
                KitPaymentMessage.allowsUserAuthoredText(body) &&
                !KitGroupPaymentMessage.beginsWithReservedPrefix(body) &&
                // No generation of the media namespace may arrive as a correction: a body of
                // descriptor text would let an edit put attachment key material into a bubble
                // the composer could never have produced. Checked with the contract's exact
                // six-codepoint edge set, matching how receivers classify reserved text.
                !KitMediaFamily.isFamilyText(body) &&
                !KitReactionMessage.beginsWithReservedPrefix(body) &&
                !beginsWithReservedPrefix(body)

        /** Strict parse; returns null for anything that is not a well-formed v1 edit. */
        fun parse(text: String): KitEditMessage? {
            if (!text.startsWith(HEADER) || text.length > MAX_DESCRIPTOR_LENGTH) return null
            val afterHeader = text.substring(HEADER.length)
            if (afterHeader.length < UUID_LENGTH + BODY_SEPARATOR.length) return null
            val targetMessageId = afterHeader.substring(0, UUID_LENGTH)
            if (!CANONICAL_UUID.matches(targetMessageId)) return null
            if (!afterHeader.startsWith(BODY_SEPARATOR, UUID_LENGTH)) return null
            val body = afterHeader.substring(UUID_LENGTH + BODY_SEPARATOR.length)
            if (!isAcceptableBody(body)) return null
            val parsed = KitEditMessage(targetMessageId = targetMessageId, body = body)
            // The authenticated descriptor has one canonical representation, so a future parser
            // cannot assign a second meaning to already-authenticated content.
            return parsed.takeIf { it.encode() == text }
        }
    }
}
