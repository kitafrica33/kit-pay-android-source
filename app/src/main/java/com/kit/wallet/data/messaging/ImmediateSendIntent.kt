package com.kit.wallet.data.messaging

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64

/** A user-visible send that is durable locally but has not necessarily reached libsignal yet. */
internal enum class ImmediateSendKind {
    TEXT,
    PAYMENT_EVENT,
    REACTION,
    MEDIA,
}

internal enum class ImmediateSendState {
    WAITING,
    RETRY_REQUIRED,
}

/**
 * One hardware-encrypted local-first send intent.
 *
 * [id] is also the eventual Signal client-message id. Keeping that identity across the local
 * intent -> encrypted companion transition makes process death at the boundary idempotent.
 * Media ciphertext lives in the app-private spool; only its key and authenticated metadata live
 * here, protected by the messaging-state hardware key.
 */
internal data class ImmediateSendIntent(
    val id: String,
    val conversationId: String,
    val kind: ImmediateSendKind,
    val createdAtEpochMillis: Long,
    val state: ImmediateSendState = ImmediateSendState.WAITING,
    val text: String = "",
    val mediaType: String? = null,
    val caption: String? = null,
    val mediaPlaintextBytes: Int = 0,
    val mediaCiphertextBytes: Int = 0,
    val mediaKeyBase64: String? = null,
    val mediaSha256Base64: String? = null,
    /** Canonical KITMEDIA1 descriptor after upload; persisted before Signal encryption. */
    val preparedMediaDescriptor: String? = null,
) {
    init {
        require(CANONICAL_UUID.matches(id)) { "Invalid immediate-send ID" }
        require(CONVERSATION_ID.matches(conversationId)) { "Invalid immediate-send conversation" }
        require(createdAtEpochMillis > 0L) { "An immediate send needs a creation time" }
        when (kind) {
            ImmediateSendKind.TEXT -> {
                require(text.isNotBlank()) { "An immediate send needs text" }
                requireStandardSecureMessagingText(text)
                requireMediaFieldsAbsent()
            }
            ImmediateSendKind.PAYMENT_EVENT -> {
                require(KitPaymentMessage.parse(text) != null) { "Invalid queued payment event" }
                requireStandardSecureMessagingText(text)
                requireMediaFieldsAbsent()
            }
            ImmediateSendKind.REACTION -> {
                require(KitReactionMessage.parse(text) != null) { "Invalid queued reaction" }
                requireStandardSecureMessagingText(text)
                requireMediaFieldsAbsent()
            }
            ImmediateSendKind.MEDIA -> {
                require(text.isEmpty())
                requireNotNull(KitMediaMessage.normalizeMediaType(mediaType.orEmpty())) {
                    "Invalid queued media type"
                }
                require(mediaPlaintextBytes in 1..MAX_IMAGE_PLAINTEXT_BYTES)
                require(mediaCiphertextBytes > 0)
                require(hasDecodedSize(mediaKeyBase64, MediaAttachmentCipher.KEY_MATERIAL_BYTES)) {
                    "Invalid queued media key"
                }
                require(hasDecodedSize(mediaSha256Base64, SHA256_BYTES)) {
                    "Invalid queued media digest"
                }
                require(
                    caption == null ||
                        caption.toByteArray(StandardCharsets.UTF_8).size <= MAX_CAPTION_UTF8_BYTES,
                ) { "Queued media caption is too large" }
                preparedMediaDescriptor?.let {
                    require(KitMediaMessage.parse(it) != null) {
                        "Invalid prepared queued-media descriptor"
                    }
                    requireStandardSecureMessagingText(it)
                }
            }
        }
    }

    val authenticatedText: String?
        get() = when (kind) {
            ImmediateSendKind.TEXT,
            ImmediateSendKind.PAYMENT_EVENT,
            ImmediateSendKind.REACTION,
            -> text
            ImmediateSendKind.MEDIA -> preparedMediaDescriptor
        }

    fun mediaKeyMaterial(): ByteArray = checkNotNull(decodeBase64(mediaKeyBase64))

    fun mediaSha256(): ByteArray = checkNotNull(decodeBase64(mediaSha256Base64))

    private fun requireMediaFieldsAbsent() {
        require(
            mediaType == null && caption == null && mediaPlaintextBytes == 0 &&
                mediaCiphertextBytes == 0 && mediaKeyBase64 == null &&
                mediaSha256Base64 == null && preparedMediaDescriptor == null,
        ) { "A queued text event cannot carry media fields" }
    }

    companion object {
        const val MAX_CAPTION_UTF8_BYTES = 2_048
        private const val SHA256_BYTES = 32
        internal val CANONICAL_UUID = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        )
        private val CONVERSATION_ID = Regex("^[A-Za-z0-9._:@-]{1,64}$")

        private fun decodeBase64(value: String?): ByteArray? = value?.let {
            runCatching { Base64.getDecoder().decode(it) }.getOrNull()
        }

        private fun hasDecodedSize(value: String?, expectedBytes: Int): Boolean {
            val decoded = decodeBase64(value) ?: return false
            return try {
                decoded.size == expectedBytes
            } finally {
                decoded.fill(0)
            }
        }
    }
}

/** Strict, bounded binary codec; future versions fail closed rather than being half-understood. */
internal object ImmediateSendIntentCodec {
    private const val VERSION = 1
    private const val MAX_RECORD_BYTES = 64 * 1024
    private const val MAX_STRING_BYTES = 32 * 1024

    fun encode(intent: ImmediateSendIntent): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeByte(VERSION)
            data.writeByte(intent.kind.ordinal)
            data.writeByte(intent.state.ordinal)
            data.writeString(intent.id)
            data.writeString(intent.conversationId)
            data.writeLong(intent.createdAtEpochMillis)
            data.writeString(intent.text)
            data.writeNullableString(intent.mediaType)
            data.writeNullableString(intent.caption)
            data.writeInt(intent.mediaPlaintextBytes)
            data.writeInt(intent.mediaCiphertextBytes)
            data.writeNullableString(intent.mediaKeyBase64)
            data.writeNullableString(intent.mediaSha256Base64)
            data.writeNullableString(intent.preparedMediaDescriptor)
        }
        return output.toByteArray().also {
            require(it.size <= MAX_RECORD_BYTES) { "Immediate-send record is too large" }
        }
    }

    fun decode(bytes: ByteArray): ImmediateSendIntent? {
        if (bytes.isEmpty() || bytes.size > MAX_RECORD_BYTES) return null
        return runCatching {
            DataInputStream(ByteArrayInputStream(bytes)).use { data ->
                if (data.readUnsignedByte() != VERSION) return null
                val kind = ImmediateSendKind.entries.getOrNull(data.readUnsignedByte()) ?: return null
                val state = ImmediateSendState.entries.getOrNull(data.readUnsignedByte()) ?: return null
                val decoded = ImmediateSendIntent(
                    id = data.readString(),
                    conversationId = data.readString(),
                    kind = kind,
                    createdAtEpochMillis = data.readLong(),
                    state = state,
                    text = data.readString(),
                    mediaType = data.readNullableString(),
                    caption = data.readNullableString(),
                    mediaPlaintextBytes = data.readInt(),
                    mediaCiphertextBytes = data.readInt(),
                    mediaKeyBase64 = data.readNullableString(),
                    mediaSha256Base64 = data.readNullableString(),
                    preparedMediaDescriptor = data.readNullableString(),
                )
                if (data.available() != 0) return null
                decoded
            }
        }.getOrNull()
    }

    private fun DataOutputStream.writeString(value: String) {
        val encoded = value.toByteArray(StandardCharsets.UTF_8)
        require(encoded.size <= MAX_STRING_BYTES) { "Immediate-send field is too large" }
        writeInt(encoded.size)
        write(encoded)
        encoded.fill(0)
    }

    private fun DataOutputStream.writeNullableString(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeString(value)
    }

    private fun DataInputStream.readString(): String {
        val length = readInt()
        require(length in 0..MAX_STRING_BYTES && length <= available())
        val encoded = ByteArray(length)
        readFully(encoded)
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(encoded))
                .toString()
        } finally {
            encoded.fill(0)
        }
    }

    private fun DataInputStream.readNullableString(): String? =
        if (readBoolean()) readString() else null
}
