package com.kit.wallet.data.messaging

import com.kit.wallet.data.remote.EncryptedAttachmentRequest
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * End-to-end encrypted multi-attachment descriptor (`KITMEDIA2`) — one message, never split.
 *
 * A media-v2 message carries a caption plus 2–8 ordered attachments as ONE authenticated text:
 * `KITMEDIA2:` followed by URL-encoded fields in a fixed order (`v`, `n`, then per item
 * `id/sk/mt/bs/sha/key/ps`, then optionally `cap` last). The per-attachment key material rides
 * inside the per-device Signal envelopes exactly like KITMEDIA1 and never reaches the server.
 *
 * Order semantics are deliberately split: the descriptor's item order is the authoritative
 * display order, while the server-visible `attachments` rows are always serialized in ascending
 * lexicographic order of the lowercase attachment id — never display order — so the wire shape
 * is uncorrelated with how the album reads. A single attachment stays `KITMEDIA1` (`n=1` here is
 * malformed by contract).
 */
internal data class KitMediaMessageV2(
    /** Authoritative display order. */
    val items: List<KitMediaMessageV2Item>,
    val caption: String?,
) {
    init {
        require(items.size in MIN_ATTACHMENTS..MAX_ATTACHMENTS) {
            "A media-v2 message carries $MIN_ATTACHMENTS–$MAX_ATTACHMENTS attachments"
        }
    }

    /** Fixed field order keeps encoding deterministic, so retry text equality holds. */
    fun encode(): String = buildString {
        append(PREFIX)
        append("v=2")
        append("&n=").append(items.size)
        items.forEachIndexed { index, item ->
            append("&id").append(index).append('=').append(item.attachmentId.urlEncode())
            append("&sk").append(index).append('=').append(item.storageKey.urlEncode())
            append("&mt").append(index).append('=').append(item.mediaType.urlEncode())
            append("&bs").append(index).append('=').append(item.ciphertextByteSize)
            append("&sha").append(index).append('=').append(item.ciphertextSha256.urlEncode())
            append("&key").append(index).append('=').append(item.keyMaterialBase64.urlEncode())
            append("&ps").append(index).append('=').append(item.plaintextByteSize)
        }
        caption?.let { append("&cap=").append(it.urlEncode()) }
    }

    /**
     * Server-visible rows in the canonical wire order: ascending lexicographic lowercase id,
     * never display order. Deliberately excludes key material.
     */
    fun toAttachmentRequests(): List<EncryptedAttachmentRequest> = items
        .sortedBy(KitMediaMessageV2Item::attachmentId)
        .map(KitMediaMessageV2Item::toAttachmentRequest)

    companion object {
        const val PREFIX = "KITMEDIA2:"

        const val MIN_ATTACHMENTS = 2
        const val MAX_ATTACHMENTS = 8

        /**
         * Whole-descriptor byte cap shared with iOS and the server. The caption's own 2,048-byte
         * ceiling is an absolute bound, not an allowance: whatever the fixed per-item fields
         * leave of this budget is all a caption may use, and an over-budget caption must fail
         * visibly at the sender — never be truncated or split into a second message.
         */
        const val MAX_DESCRIPTOR_UTF8_BYTES = 7_680
        const val MAX_CAPTION_UTF8_BYTES = 2_048

        internal const val MIN_ATTACHMENT_CIPHERTEXT_BYTES = 64L
        internal const val MAX_AGGREGATE_CIPHERTEXT_BYTES = 256L * 1024L * 1024L
        private const val CAPTION_KEY_OVERHEAD_BYTES = 5 // "&cap="

        private val SHA256_HEX = Regex("^[0-9a-f]{64}$")
        private val CANONICAL_UUID = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        )
        private val ITEM_FIELD_KEYS = listOf("id", "sk", "mt", "bs", "sha", "key", "ps")

        fun isMediaText(text: String): Boolean = text.startsWith(PREFIX)

        /** Strict parse; null for anything that is not a canonical v2 media descriptor. */
        fun parse(text: String): KitMediaMessageV2? {
            if (!text.startsWith(PREFIX)) return null
            if (text.toByteArray(StandardCharsets.UTF_8).size > MAX_DESCRIPTOR_UTF8_BYTES) {
                return null
            }
            // Positional parse: the grammar fixes every key's position, so the field list is
            // checked in order rather than collected into a map — duplicate, unknown, missing,
            // gapped or reordered keys all fail structurally before any value is trusted.
            val fields = mutableListOf<Pair<String, String>>()
            for (pair in text.substring(PREFIX.length).split('&')) {
                val separator = pair.indexOf('=')
                if (separator <= 0) return null
                if (pair.indexOf('=', separator + 1) != -1) return null
                val value = pair.substring(separator + 1).urlDecodeStrict() ?: return null
                fields += pair.substring(0, separator) to value
            }
            if (fields.size < 2) return null
            if (fields[0] != ("v" to "2")) return null
            if (fields[1].first != "n") return null
            val count = fields[1].second.toIntOrNull() ?: return null
            if (count !in MIN_ATTACHMENTS..MAX_ATTACHMENTS) return null
            if (fields[1].second != count.toString()) return null
            val caption = when (fields.size) {
                2 + ITEM_FIELD_KEYS.size * count -> null
                3 + ITEM_FIELD_KEYS.size * count -> {
                    val (key, value) = fields.last()
                    if (key != "cap") return null
                    if (!isValidCaption(value)) return null
                    value
                }
                else -> return null
            }
            val attachmentIds = mutableSetOf<String>()
            val storageKeys = mutableSetOf<String>()
            var aggregateCiphertextBytes = 0L
            val items = ArrayList<KitMediaMessageV2Item>(count)
            for (index in 0 until count) {
                val groupStart = 2 + ITEM_FIELD_KEYS.size * index
                val group = fields.subList(groupStart, groupStart + ITEM_FIELD_KEYS.size)
                ITEM_FIELD_KEYS.forEachIndexed { position, name ->
                    if (group[position].first != "$name$index") return null
                }
                val attachmentId = group[0].second.lowercase()
                val storageKey = group[1].second.lowercase()
                val mediaType = group[2].second.lowercase()
                val byteSize = group[3].second.toLongOrNull() ?: return null
                val sha256 = group[4].second.lowercase()
                val key = group[5].second
                val plaintextSize = group[6].second.toIntOrNull() ?: return null
                if (!CANONICAL_UUID.matches(attachmentId)) return null
                if (!CANONICAL_UUID.matches(storageKey)) return null
                if (!attachmentIds.add(attachmentId)) return null
                if (!storageKeys.add(storageKey)) return null
                if (mediaType !in KitMediaMessage.SUPPORTED_MEDIA_TYPES) return null
                if (byteSize !in MIN_ATTACHMENT_CIPHERTEXT_BYTES..MAX_IMAGE_CIPHERTEXT_BYTES) {
                    return null
                }
                if (!SHA256_HEX.matches(sha256)) return null
                if (plaintextSize !in 1..MAX_IMAGE_PLAINTEXT_BYTES) return null
                // Cipher layout is IV(16) + CBC/PKCS7 + HMAC(32), so the two sizes are locked
                // together; a pair that disagrees describes a blob that cannot exist.
                if (byteSize != plaintextSize.toLong() + 64L - (plaintextSize.toLong() % 16L)) {
                    return null
                }
                aggregateCiphertextBytes += byteSize
                if (aggregateCiphertextBytes > MAX_AGGREGATE_CIPHERTEXT_BYTES) return null
                val keyBytes = runCatching { Base64.getDecoder().decode(key) }.getOrNull()
                    ?: return null
                val canonicalKey = try {
                    keyBytes.size == MediaAttachmentCipher.KEY_MATERIAL_BYTES &&
                        Base64.getEncoder().encodeToString(keyBytes) == key
                } finally {
                    keyBytes.fill(0)
                }
                if (!canonicalKey) return null
                items += KitMediaMessageV2Item(
                    attachmentId = attachmentId,
                    storageKey = storageKey,
                    mediaType = mediaType,
                    ciphertextByteSize = byteSize,
                    ciphertextSha256 = sha256,
                    keyMaterialBase64 = key,
                    plaintextByteSize = plaintextSize,
                )
            }
            val parsed = KitMediaMessageV2(items = items, caption = caption)
            // The authenticated descriptor has one canonical representation. Reject alternate
            // escaping, case variants and noncanonical numbers so a future parser cannot assign
            // a second meaning to already-authenticated content.
            return parsed.takeIf { it.encode() == text }
        }

        /**
         * Server-visible attachment metadata for outbound text, derived deterministically from
         * the descriptor so first sends and retries always publish identical rows.
         */
        fun attachmentsFor(text: String): List<EncryptedAttachmentRequest> =
            parse(text)?.toAttachmentRequests() ?: emptyList()

        /**
         * Sender-side caption normalization: strips exactly the contract's six edge codepoints
         * (never a platform trim) and returns null when nothing remains to say.
         */
        fun normalizeCaption(raw: String?): String? = raw
            ?.let(KitMediaWhitespacePolicy::strip)
            ?.takeIf(String::isNotEmpty)

        /** Receiver-side caption validity; violations are rejected, never re-trimmed. */
        fun isValidCaption(caption: String): Boolean {
            val bytes = caption.toByteArray(StandardCharsets.UTF_8).size
            return bytes in 1..MAX_CAPTION_UTF8_BYTES &&
                caption.none { it.code == 0x00 } &&
                !KitMediaWhitespacePolicy.beginsOrEndsWithBoundary(caption) &&
                KitMediaWhitespacePolicy.hasContentOutsideBoundarySet(caption)
        }

        /**
         * Encoded bytes still available for `&cap=<enc(caption)>` once [items] are fixed. The
         * caption shares the 7,680-byte descriptor budget with the per-item fields, so this can
         * be far below the 2,048-byte caption ceiling — or negative when the items alone leave
         * no room for any caption at all.
         */
        fun remainingEncodedCaptionBudgetBytes(items: List<KitMediaMessageV2Item>): Int {
            val fixed = KitMediaMessageV2(items = items, caption = null)
                .encode()
                .toByteArray(StandardCharsets.UTF_8)
                .size
            return MAX_DESCRIPTOR_UTF8_BYTES - fixed - CAPTION_KEY_OVERHEAD_BYTES
        }

        /** Encoded footprint a caption would occupy against the remaining budget. */
        fun encodedCaptionBytes(caption: String): Int =
            caption.urlEncode().toByteArray(StandardCharsets.UTF_8).size

        private fun String.urlEncode(): String =
            URLEncoder.encode(this, StandardCharsets.UTF_8.name()).replace("+", "%20")

        /**
         * Strict decode: a raw `+` (which URLDecoder would silently read as a space) and any
         * malformed or lowercase `%XX` escape are rejected before decoding, so decoding can
         * never loosen the canon that re-encode equality then proves.
         */
        private fun String.urlDecodeStrict(): String? {
            var index = 0
            while (index < length) {
                when (this[index]) {
                    '+' -> return null
                    '%' -> {
                        if (index + 2 >= length) return null
                        if (!this[index + 1].isUppercaseHex()) return null
                        if (!this[index + 2].isUppercaseHex()) return null
                        index += 3
                    }
                    else -> index++
                }
            }
            return runCatching {
                URLDecoder.decode(this, StandardCharsets.UTF_8.name())
            }.getOrNull()
        }

        private fun Char.isUppercaseHex(): Boolean = this in '0'..'9' || this in 'A'..'F'
    }
}

/** One attachment of a media-v2 message; same shape and constraints as a KITMEDIA1 body. */
internal data class KitMediaMessageV2Item(
    val attachmentId: String,
    val storageKey: String,
    val mediaType: String,
    val ciphertextByteSize: Long,
    val ciphertextSha256: String,
    val keyMaterialBase64: String,
    val plaintextByteSize: Int,
) {
    fun keyMaterial(): ByteArray = Base64.getDecoder().decode(keyMaterialBase64)

    fun ciphertextSha256Bytes(): ByteArray =
        ciphertextSha256.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** The server-visible metadata row for this attachment; deliberately excludes key material. */
    fun toAttachmentRequest(): EncryptedAttachmentRequest = EncryptedAttachmentRequest(
        id = attachmentId,
        storageKey = storageKey,
        mediaType = mediaType,
        byteSize = ciphertextByteSize,
        ciphertextSha256 = ciphertextSha256,
    )
}

/**
 * Reserved-prefix detection for the whole `KITMEDIA` family, any generation, known or future.
 *
 * Receive-side this is the fail-closed trigger: family text that no strict parser accepts is
 * rendered as a generic placeholder and its raw bytes (which may embed attachment keys) never
 * reach a transcript, quote, notification or pasteboard — independent of any feature flag.
 * Input-side the same family test blocks typed, shared, pasted and edit-payload text from
 * impersonating a descriptor.
 */
internal object KitMediaFamily {
    private val FAMILY_PREFIX = Regex("^KITMEDIA[0-9]+:")

    /** True for any `KITMEDIA<digits>:` text once the exact six-codepoint edges are stripped. */
    fun isFamilyText(text: String): Boolean =
        FAMILY_PREFIX.containsMatchIn(KitMediaWhitespacePolicy.strip(text))

    /**
     * The bare `KITMEDIA<generation>:` prefix of [text] and nothing after it — what a projection
     * may safely carry in place of reserved text it refused. The marker keeps the generation
     * visible for diagnostics, still classifies as family text (so every layer renders the
     * generic placeholder), and holds no id, key, digest or storage-handle bytes at all.
     */
    fun sanitizedFamilyMarker(text: String): String =
        FAMILY_PREFIX.find(KitMediaWhitespacePolicy.strip(text))?.value
            ?: KitMediaMessageV2.PREFIX
}

/**
 * Server-visible attachment rows for any authenticated outbound media text: one row for a
 * KITMEDIA1 descriptor, 2–8 canonically ordered rows for KITMEDIA2, none for plain text.
 */
internal fun kitMediaAttachmentsFor(text: String): List<EncryptedAttachmentRequest> =
    KitMediaMessage.attachmentsFor(text).ifEmpty { KitMediaMessageV2.attachmentsFor(text) }

/**
 * Whether persisted text needs the media-v2-era schema fence and an explicit provenance verdict.
 *
 * Canonical KITMEDIA1 is the only reserved-family shape old clients already understand safely.
 * Everything else in the family — valid v2, malformed v1/v2, and future generations — must stay
 * behind the newer archive/backup schema even when it has no positive validation pin.
 */
internal fun requiresModernMediaSchemaFence(text: String): Boolean =
    KitMediaFamily.isFamilyText(text) && KitMediaMessage.parse(text) == null

/**
 * The attachment rows an outbound send or retry must carry for [text], failing closed.
 *
 * Text in the reserved media namespace that no strict parser accepts yields no rows, and sending
 * it as ordinary encrypted text would put an unauthenticated descriptor on the wire — so that
 * case is an error here, at the derivation, where every send/retry caller shares it.
 */
internal fun kitMediaOutboundAttachmentsFor(text: String): List<EncryptedAttachmentRequest> {
    val attachments = kitMediaAttachmentsFor(text)
    check(attachments.isNotEmpty() || !KitMediaFamily.isFamilyText(text)) {
        "Reserved media text failed strict parsing and cannot be sent as ordinary text"
    }
    return attachments
}

/**
 * The text a projection page may carry for an authenticated durable record.
 *
 * Reserved-media text that is pinned unsupported, or that no strict parser accepts, is replaced
 * by the bare sanitized generation marker before it can reach any page consumer — transcript,
 * quote, search, evidence extraction or notification replay. Everything else, including strictly
 * valid v1/v2 descriptors whose key material later layers deliberately confine, passes through
 * byte-exact.
 */
internal fun kitMediaProjectedText(text: String, unsupportedMedia: Boolean): String {
    val reserved = unsupportedMedia ||
        (KitMediaFamily.isFamilyText(text) && kitMediaAttachmentsFor(text).isEmpty())
    return if (reserved) KitMediaFamily.sanitizedFamilyMarker(text) else text
}

/**
 * Whether the server-visible [outer] rows are exactly the rows the authenticated descriptor in
 * [text] commits to.
 *
 * A multi-row (v2) descriptor is compared as an unordered set with the outer digests normalized
 * to lowercase: the wire mandates ascending-id row order and lowercase digests, but receivers
 * tolerate both as defense in depth because neither carries meaning — the descriptor alone owns
 * display order and the digest bytes are case-insensitive hex. The single-row and row-less cases
 * keep exact list equality, preserving v1 and plain-text binding semantics unchanged.
 */
internal fun kitMediaOuterAttachmentsMatch(
    text: String,
    outer: List<EncryptedAttachmentRequest>,
): Boolean {
    val authenticated = kitMediaAttachmentsFor(text)
    if (authenticated.size <= 1) return outer == authenticated
    val normalizedOuter = outer.map { it.copy(ciphertextSha256 = it.ciphertextSha256.lowercase()) }
    return normalizedOuter.size == authenticated.size &&
        normalizedOuter.toSet() == authenticated.toSet()
}
