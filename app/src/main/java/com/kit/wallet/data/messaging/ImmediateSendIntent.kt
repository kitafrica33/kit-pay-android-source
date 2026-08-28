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

    /**
     * A member's answer to a group payment. Appended after the original four on purpose: the
     * durable codec writes this enum by ordinal, so an existing queued send must keep the number
     * it was written with, and every later kind can only be added at the end.
     */
    GROUP_PAYMENT_EVENT,

    /** Replacement wording for one of this account's own earlier messages. */
    EDIT,

    /**
     * A `KITMEDIA2` media album: two to eight ordered attachments and an optional caption that
     * travel as one message under one idempotency identity. Appended after [EDIT] for the same
     * ordinal-stability reason as [GROUP_PAYMENT_EVENT].
     */
    MEDIA_V2,
}

/**
 * One attachment of a queued media album, in display order.
 *
 * [attachmentId] is the item's wire row id, its spool file name, and its retry identity all at
 * once: it is minted fresh when the album is enqueued and never changes across retries, so a
 * resumed upload can neither duplicate a finished item nor orphan its ciphertext. [storageKey]
 * starts null and is persisted the moment the server confirms that item's upload, which is what
 * lets a process death mid-album resume from the last finished item instead of re-uploading.
 */
internal data class ImmediateSendMediaItem(
    val attachmentId: String,
    val mediaType: String,
    val plaintextBytes: Int,
    val ciphertextBytes: Long,
    val keyBase64: String,
    val ciphertextSha256Hex: String,
    val storageKey: String? = null,
) {
    init {
        require(ImmediateSendIntent.CANONICAL_UUID.matches(attachmentId)) {
            "Invalid queued album attachment ID"
        }
        require(KitMediaMessage.normalizeMediaType(mediaType) == mediaType) {
            "Invalid queued album media type"
        }
        require(plaintextBytes in 1..MAX_IMAGE_PLAINTEXT_BYTES) {
            "Invalid queued album item size"
        }
        // Cipher layout is IV(16) + CBC/PKCS7 + HMAC(32); a pair that disagrees describes a blob
        // that cannot exist, exactly as the descriptor codec reads it.
        require(
            ciphertextBytes == plaintextBytes.toLong() + 64L - (plaintextBytes.toLong() % 16L),
        ) { "Queued album item sizes disagree" }
        require(
            hasCanonicalBase64Size(keyBase64, MediaAttachmentCipher.KEY_MATERIAL_BYTES),
        ) { "Invalid queued album key" }
        require(SHA256_HEX.matches(ciphertextSha256Hex)) { "Invalid queued album digest" }
        storageKey?.let {
            require(ImmediateSendIntent.CANONICAL_UUID.matches(it)) {
                "Invalid queued album storage key"
            }
        }
    }

    fun keyMaterial(): ByteArray = Base64.getDecoder().decode(keyBase64)

    fun ciphertextSha256(): ByteArray = ByteArray(ciphertextSha256Hex.length / 2) { index ->
        ciphertextSha256Hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    private companion object {
        val SHA256_HEX = Regex("^[0-9a-f]{64}$")
    }
}

internal enum class ImmediateSendState {
    WAITING,
    RETRY_REQUIRED,

    /**
     * Terminal and user-visible: the local ciphertext was lost before the send, so no retry can
     * ever succeed. The record itself stays as the durable failed bubble — a `KITMEDIA2` album
     * must fail visibly rather than vanish — and dispatch skips it, so it never holds up the
     * conversation behind it. Appended last: persisted records store the ordinal.
     */
    FAILED,
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
    /**
     * Canonical media descriptor after upload — KITMEDIA1 for [ImmediateSendKind.MEDIA],
     * KITMEDIA2 for [ImmediateSendKind.MEDIA_V2] — persisted before Signal encryption.
     */
    val preparedMediaDescriptor: String? = null,
    /** Ordered album attachments; non-empty exactly when [kind] is [ImmediateSendKind.MEDIA_V2]. */
    val mediaItems: List<ImmediateSendMediaItem> = emptyList(),
    /**
     * The message this send is an answer to, when the sender picked one.
     *
     * Carried on the intent rather than derived at promotion time because the answer is part of
     * what was written: a queued reply that lost its target would go out as an unrelated remark.
     * A reaction leaves this null — its target lives inside its own descriptor, which is what the
     * peer authenticates it against.
     */
    val replyToMessageId: String? = null,
) {
    init {
        require(CANONICAL_UUID.matches(id)) { "Invalid immediate-send ID" }
        require(CONVERSATION_ID.matches(conversationId)) { "Invalid immediate-send conversation" }
        require(createdAtEpochMillis > 0L) { "An immediate send needs a creation time" }
        replyToMessageId?.let {
            require(CANONICAL_UUID.matches(it)) { "Invalid immediate-send reply target" }
            require(it != id) { "A message cannot be a reply to itself" }
            // A reaction's target is authenticated from its own descriptor. Writing it here too
            // would create a second copy the peer never checks, and the two could disagree.
            require(
                kind != ImmediateSendKind.REACTION && kind != ImmediateSendKind.EDIT,
            ) { "A queued reaction or edit carries its target in its descriptor" }
        }
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
            ImmediateSendKind.EDIT -> {
                require(KitEditMessage.parse(text) != null) { "Invalid queued edit" }
                requireStandardSecureMessagingText(text)
                requireMediaFieldsAbsent()
            }
            ImmediateSendKind.GROUP_PAYMENT_EVENT -> {
                require(KitGroupPaymentMessage.parse(text) != null) {
                    "Invalid queued group payment event"
                }
                requireStandardSecureMessagingText(text)
                requireMediaFieldsAbsent()
            }
            ImmediateSendKind.MEDIA -> {
                require(text.isEmpty())
                require(mediaItems.isEmpty()) { "A single queued media send carries no item list" }
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
            ImmediateSendKind.MEDIA_V2 -> {
                require(text.isEmpty())
                require(
                    mediaType == null && mediaPlaintextBytes == 0 && mediaCiphertextBytes == 0 &&
                        mediaKeyBase64 == null && mediaSha256Base64 == null,
                ) { "A queued media album carries per-item fields only" }
                require(
                    mediaItems.size in
                        KitMediaMessageV2.MIN_ATTACHMENTS..KitMediaMessageV2.MAX_ATTACHMENTS,
                ) { "A queued media album carries two to eight attachments" }
                require(
                    mediaItems.mapTo(mutableSetOf(), ImmediateSendMediaItem::attachmentId).size ==
                        mediaItems.size,
                ) { "Queued album attachment ids must be unique" }
                val storageKeys = mediaItems.mapNotNull(ImmediateSendMediaItem::storageKey)
                require(storageKeys.size == storageKeys.toSet().size) {
                    "Queued album storage keys must be unique"
                }
                require(
                    mediaItems.sumOf(ImmediateSendMediaItem::ciphertextBytes) <=
                        KitMediaMessageV2.MAX_AGGREGATE_CIPHERTEXT_BYTES,
                ) { "Queued media album is too large" }
                // Already in exact wire form: enqueue normalizes with the contract's
                // six-codepoint strip, so a caption that decodes differently than it will be
                // sent cannot sit in the queue.
                require(caption == null || KitMediaMessageV2.isValidCaption(caption)) {
                    "Queued album caption is not in canonical form"
                }
                preparedMediaDescriptor?.let { descriptor ->
                    require(KitMediaMessageV2.parse(descriptor) != null) {
                        "Invalid prepared queued-album descriptor"
                    }
                    requireStandardSecureMessagingText(descriptor)
                    require(mediaItems.all { it.storageKey != null }) {
                        "A prepared album descriptor requires every item to be uploaded"
                    }
                    // The descriptor must BE the canonical encoding of every persisted item
                    // field plus the caption — id order alone is not enough. Anything less lets
                    // a corrupt or mismatched checkpoint send different keys or metadata than
                    // the items this record says it carries.
                    require(descriptor == buildAlbumDescriptor()) {
                        "Prepared album descriptor does not match its queued items"
                    }
                }
            }
        }
    }

    val authenticatedText: String?
        get() = when (kind) {
            ImmediateSendKind.TEXT,
            ImmediateSendKind.PAYMENT_EVENT,
            ImmediateSendKind.REACTION,
            ImmediateSendKind.GROUP_PAYMENT_EVENT,
            ImmediateSendKind.EDIT,
            -> text
            ImmediateSendKind.MEDIA,
            ImmediateSendKind.MEDIA_V2,
            -> preparedMediaDescriptor
        }

    fun mediaKeyMaterial(): ByteArray = checkNotNull(decodeBase64(mediaKeyBase64))

    fun mediaSha256(): ByteArray = checkNotNull(decodeBase64(mediaSha256Base64))

    /**
     * The canonical `KITMEDIA2` encoding of every persisted item field plus the caption; every
     * item's upload must already be recorded. The seal step and the persisted-record invariant
     * share this one construction, so what was validated is exactly what will be sent.
     */
    fun buildAlbumDescriptor(): String {
        check(kind == ImmediateSendKind.MEDIA_V2) { "Only albums build KITMEDIA2 descriptors" }
        return KitMediaMessageV2(
            items = mediaItems.map { item ->
                KitMediaMessageV2Item(
                    attachmentId = item.attachmentId,
                    storageKey = checkNotNull(item.storageKey) {
                        "A queued album descriptor requires every item to be uploaded"
                    },
                    mediaType = item.mediaType,
                    ciphertextByteSize = item.ciphertextBytes,
                    ciphertextSha256 = item.ciphertextSha256Hex,
                    keyMaterialBase64 = item.keyBase64,
                    plaintextByteSize = item.plaintextBytes,
                )
            },
            caption = caption,
        ).encode()
    }

    /**
     * This album with one item's confirmed upload recorded. Everything else — the id the item
     * will be sent under, its key, its digests — is deliberately immutable across retries.
     */
    fun withAlbumItemStorageKey(attachmentId: String, storageKey: String): ImmediateSendIntent {
        check(kind == ImmediateSendKind.MEDIA_V2) { "Only albums record per-item storage keys" }
        check(mediaItems.any { it.attachmentId == attachmentId }) {
            "The uploaded attachment does not belong to this album"
        }
        return copy(
            mediaItems = mediaItems.map { item ->
                if (item.attachmentId == attachmentId) item.copy(storageKey = storageKey) else item
            },
        )
    }

    private fun requireMediaFieldsAbsent() {
        require(
            mediaType == null && caption == null && mediaPlaintextBytes == 0 &&
                mediaCiphertextBytes == 0 && mediaKeyBase64 == null &&
                mediaSha256Base64 == null && preparedMediaDescriptor == null &&
                mediaItems.isEmpty(),
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

/**
 * Whether [value] is the canonical base64 of exactly [expectedBytes] bytes. Album keys must be
 * canonical, not merely decodable: the descriptor codec proves canonicality by re-encoding, so a
 * noncanonical spelling here would build a descriptor that fails its own parse.
 */
private fun hasCanonicalBase64Size(value: String, expectedBytes: Int): Boolean {
    val decoded = runCatching { Base64.getDecoder().decode(value) }.getOrNull() ?: return false
    return try {
        decoded.size == expectedBytes && Base64.getEncoder().encodeToString(decoded) == value
    } finally {
        decoded.fill(0)
    }
}

/** Strict, bounded binary codec; future versions fail closed rather than being half-understood. */
internal object ImmediateSendIntentCodec {
    private const val VERSION = 3

    /**
     * Version 1 is still read, and only read.
     *
     * A send already sitting in the queue when the app updated was written without a reply target,
     * and it is a message someone meant to send. Refusing it would silently drop their words at
     * exactly the moment they trusted the queue to hold them; the field it lacks is simply absent.
     * Version 2 likewise: it predates media albums, so its item list is simply empty.
     */
    private const val OLDEST_READABLE_VERSION = 1
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
            data.writeNullableString(intent.replyToMessageId)
            data.writeInt(intent.mediaItems.size)
            intent.mediaItems.forEach { item ->
                data.writeString(item.attachmentId)
                data.writeString(item.mediaType)
                data.writeInt(item.plaintextBytes)
                data.writeLong(item.ciphertextBytes)
                data.writeString(item.keyBase64)
                data.writeString(item.ciphertextSha256Hex)
                data.writeNullableString(item.storageKey)
            }
        }
        return output.toByteArray().also {
            require(it.size <= MAX_RECORD_BYTES) { "Immediate-send record is too large" }
        }
    }

    fun decode(bytes: ByteArray): ImmediateSendIntent? {
        if (bytes.isEmpty() || bytes.size > MAX_RECORD_BYTES) return null
        return runCatching {
            DataInputStream(ByteArrayInputStream(bytes)).use { data ->
                val version = data.readUnsignedByte()
                if (version !in OLDEST_READABLE_VERSION..VERSION) return null
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
                    replyToMessageId = if (version >= 2) data.readNullableString() else null,
                    mediaItems = if (version >= 3) data.readMediaItems() else emptyList(),
                )
                if (data.available() != 0) return null
                decoded
            }
        }.getOrNull()
    }

    private fun DataInputStream.readMediaItems(): List<ImmediateSendMediaItem> {
        val count = readInt()
        require(count in 0..KitMediaMessageV2.MAX_ATTACHMENTS) {
            "Immediate-send album item count is out of range"
        }
        return List(count) {
            ImmediateSendMediaItem(
                attachmentId = readString(),
                mediaType = readString(),
                plaintextBytes = readInt(),
                ciphertextBytes = readLong(),
                keyBase64 = readString(),
                ciphertextSha256Hex = readString(),
                storageKey = readNullableString(),
            )
        }
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
