package com.kit.wallet.data.messaging

import com.kit.wallet.data.remote.EncryptedAttachmentRequest
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * End-to-end encrypted media descriptor carried as the authenticated message text.
 *
 * A media message's Signal-encrypted text is `KITMEDIA1:` followed by URL-encoded key=value
 * pairs in a fixed order. The per-attachment key material therefore rides inside the per-device
 * Signal envelopes exactly like any text and never reaches the server. The server-visible
 * `attachments` metadata (storage key, media type, sizes, ciphertext digest — never the key) is
 * re-derived from this descriptor on every send and retry so the two can never disagree.
 */
internal data class KitMediaMessage(
    val attachmentId: String,
    val storageKey: String,
    val mediaType: String,
    val ciphertextByteSize: Long,
    val ciphertextSha256: String,
    val keyMaterialBase64: String,
    val plaintextByteSize: Int,
    val caption: String?,
) {
    /** Fixed field order keeps encoding deterministic, so retry text equality holds. */
    fun encode(): String = buildString {
        append(PREFIX)
        append("v=1")
        append("&id=").append(attachmentId.urlEncode())
        append("&sk=").append(storageKey.urlEncode())
        append("&mt=").append(mediaType.urlEncode())
        append("&bs=").append(ciphertextByteSize)
        append("&sha=").append(ciphertextSha256.urlEncode())
        append("&key=").append(keyMaterialBase64.urlEncode())
        append("&ps=").append(plaintextByteSize)
        caption?.takeIf(String::isNotBlank)?.let { append("&cap=").append(it.urlEncode()) }
    }

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

    companion object {
        const val PREFIX = "KITMEDIA1:"
        private const val MAX_DESCRIPTOR_LENGTH = 4_096
        private val SHA256_HEX = Regex("^[0-9a-f]{64}$")
        private val CANONICAL_UUID = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        )

        fun isMediaText(text: String): Boolean = text.startsWith(PREFIX)

        /** Strict parse; returns null for anything that is not a well-formed v1 media descriptor. */
        fun parse(text: String): KitMediaMessage? {
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
            val attachmentId = fields["id"]?.lowercase() ?: return null
            val storageKey = fields["sk"]?.lowercase() ?: return null
            val mediaType = fields["mt"]?.lowercase() ?: return null
            val byteSize = fields["bs"]?.toLongOrNull() ?: return null
            val sha256 = fields["sha"]?.lowercase() ?: return null
            val key = fields["key"] ?: return null
            val plaintextSize = fields["ps"]?.toIntOrNull() ?: return null
            val caption = fields["cap"]
            if (!CANONICAL_UUID.matches(attachmentId)) return null
            if (!CANONICAL_UUID.matches(storageKey)) return null
            if (mediaType !in SUPPORTED_IMAGE_MEDIA_TYPES) return null
            if (byteSize !in MIN_IMAGE_CIPHERTEXT_BYTES..MAX_IMAGE_CIPHERTEXT_BYTES) return null
            if (!SHA256_HEX.matches(sha256)) return null
            if (plaintextSize !in 1..MAX_IMAGE_PLAINTEXT_BYTES) return null
            val keyBytes = runCatching { Base64.getDecoder().decode(key) }.getOrNull() ?: return null
            val canonicalKey = try {
                keyBytes.size == MediaAttachmentCipher.KEY_MATERIAL_BYTES &&
                    Base64.getEncoder().encodeToString(keyBytes) == key
            } finally {
                keyBytes.fill(0)
            }
            if (!canonicalKey) return null
            val parsed = KitMediaMessage(
                attachmentId = attachmentId,
                storageKey = storageKey,
                mediaType = mediaType,
                ciphertextByteSize = byteSize,
                ciphertextSha256 = sha256,
                keyMaterialBase64 = key,
                plaintextByteSize = plaintextSize,
                caption = caption,
            )
            // The authenticated descriptor has one canonical representation. Reject unknown or
            // reordered fields, alternate escaping, case variants and noncanonical numbers so a
            // future parser cannot assign a second meaning to already-authenticated content.
            return parsed.takeIf { it.encode() == text }
        }

        /**
         * Server-visible attachment metadata for outbound text, derived deterministically from the
         * descriptor so first sends and retries always publish identical rows.
         */
        fun attachmentsFor(text: String): List<EncryptedAttachmentRequest> =
            parse(text)?.let { listOf(it.toAttachmentRequest()) } ?: emptyList()

        fun normalizeImageMediaType(value: String): String? = value.trim().lowercase()
            .takeIf { it in SUPPORTED_IMAGE_MEDIA_TYPES }

        private fun String.urlEncode(): String =
            URLEncoder.encode(this, StandardCharsets.UTF_8.name()).replace("+", "%20")

        private fun String.urlDecode(): String? =
            runCatching { URLDecoder.decode(this, StandardCharsets.UTF_8.name()) }.getOrNull()

        private val SUPPORTED_IMAGE_MEDIA_TYPES = setOf(
            "image/jpeg",
            "image/png",
            "image/webp",
            "image/gif",
        )
        private const val MIN_IMAGE_CIPHERTEXT_BYTES = 64L
    }
}
