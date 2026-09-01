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

    /** Canonical `KITGREQ1` request announcement or outcome. Appended for codec stability. */
    GROUP_PAYMENT_REQUEST_EVENT,
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
    /** Cross-platform MIME of the optimized plaintext that is encrypted for delivery. */
    val mediaType: String,
    val plaintextBytes: Int,
    val ciphertextBytes: Long,
    val keyBase64: String,
    val ciphertextSha256Hex: String,
    val storageKey: String? = null,
    /** MIME and size of the independent device-local original retained for sender playback. */
    val originalMediaType: String? = null,
    val originalPlaintextBytes: Int = 0,
    /** Persisted so a process restart can reproduce the same upload representation. */
    val processingPlan: SecureMediaProcessingPlan = SecureMediaProcessingPlan.PASSTHROUGH,
    /** Known sender-local audio/video duration; presentation metadata, not wire authority. */
    val durationMillis: Long? = null,
) {
    /** True while this item names only the retained device-local plaintext copy. */
    val isPreparing: Boolean
        get() = ciphertextBytes == 0L && keyBase64.isEmpty() &&
            ciphertextSha256Hex.isEmpty() && storageKey == null

    val localMediaType: String get() = originalMediaType ?: mediaType
    val localPlaintextBytes: Int
        get() = originalPlaintextBytes.takeIf { it > 0 } ?: plaintextBytes

    init {
        require(ImmediateSendIntent.CANONICAL_UUID.matches(attachmentId)) {
            "Invalid queued album attachment ID"
        }
        require(KitMediaMessage.normalizeMediaType(mediaType) == mediaType) {
            "Invalid queued album media type"
        }
        require(normalizeLocalMediaType(localMediaType) == localMediaType) {
            "Invalid queued album original media type"
        }
        require(originalPlaintextBytes in 0..MAX_LOCAL_MEDIA_ORIGINAL_BYTES) {
            "Invalid queued album original size"
        }
        require(processingPlan != SecureMediaProcessingPlan.CHAT_VIDEO_MP4) {
            "Queued albums do not yet carry per-video edit plans"
        }
        require(
            processingPlan != SecureMediaProcessingPlan.CHAT_IMAGE_JPEG ||
                mediaType == "image/jpeg" && localMediaType.startsWith("image/"),
        ) { "Invalid queued album image processing plan" }
        require(
            durationMillis == null ||
                durationMillis in 1..MAX_LOCAL_MEDIA_DURATION_MILLIS &&
                (localMediaType.startsWith("audio/") || localMediaType.startsWith("video/")),
        ) { "Invalid queued album media duration" }
        require(plaintextBytes in 0..MAX_IMAGE_PLAINTEXT_BYTES) {
            "Invalid queued album item size"
        }
        require(plaintextBytes > 0 || isPreparing) {
            "Only an unfinished local import can have an unknown album item size"
        }
        if (!isPreparing) {
            // Cipher layout is IV(16) + CBC/PKCS7 + HMAC(32); a pair that disagrees describes a
            // blob that cannot exist, exactly as the descriptor codec reads it.
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

    /**
     * The complete plaintext copy is durable on this device, but ciphertext has not yet been
     * committed to the media spool. Appended to preserve every previously persisted ordinal.
     */
    PREPARING,

    /**
     * The durable bubble exists and the selected original is being atomically adopted into Sent
     * Media. Appended for persisted-ordinal compatibility. A restart either finds the complete
     * cache entry and advances it, or leaves a visible terminal failure; it never loses the row.
     */
    IMPORTING,
}

/** A retained plaintext preparation vanished before the background cipher could adopt it. */
internal class ImmediateMediaPreparationUnavailableException(message: String) :
    IllegalStateException(message)

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
    /** Device-local original facts, separate from the optimized wire plaintext facts above. */
    val mediaOriginalType: String? = null,
    val mediaOriginalPlaintextBytes: Int = 0,
    val mediaProcessingPlan: SecureMediaProcessingPlan = SecureMediaProcessingPlan.PASSTHROUGH,
    /** Restart-safe trim/mute recipe for an asynchronously prepared video upload. */
    val mediaVideoEditPlan: SecureMediaVideoEditPlan? = null,
    /** Known sender-local audio/video duration, independent of the remote descriptor. */
    val mediaDurationMillis: Long? = null,
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
            ImmediateSendKind.GROUP_PAYMENT_REQUEST_EVENT -> {
                require(com.kit.wallet.data.remote.KitGroupPaymentRequestMessage.parse(text) != null) {
                    "Invalid queued group payment request event"
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
                require(normalizeLocalMediaType(localMediaType) == localMediaType) {
                    "Invalid queued original media type"
                }
                require(mediaOriginalPlaintextBytes in 0..MAX_LOCAL_MEDIA_ORIGINAL_BYTES) {
                    "Invalid queued original media size"
                }
                require(
                    mediaProcessingPlan != SecureMediaProcessingPlan.CHAT_IMAGE_JPEG ||
                        mediaType == "image/jpeg" && localMediaType.startsWith("image/"),
                ) { "Invalid queued image processing plan" }
                require(
                    (mediaProcessingPlan == SecureMediaProcessingPlan.CHAT_VIDEO_MP4) ==
                        (mediaVideoEditPlan != null),
                ) { "A queued video processing plan needs exact edit parameters" }
                require(
                    mediaProcessingPlan != SecureMediaProcessingPlan.CHAT_VIDEO_MP4 ||
                        mediaType == "video/mp4" && localMediaType.startsWith("video/"),
                ) { "Invalid queued video processing plan" }
                require(
                    mediaDurationMillis == null ||
                        mediaDurationMillis in 1..MAX_LOCAL_MEDIA_DURATION_MILLIS &&
                        (
                            localMediaType.startsWith("audio/") ||
                                localMediaType.startsWith("video/")
                            ),
                ) { "Invalid queued media duration" }
                require(mediaPlaintextBytes in 0..MAX_IMAGE_PLAINTEXT_BYTES)
                require(
                    mediaPlaintextBytes > 0 ||
                        state == ImmediateSendState.IMPORTING ||
                        state == ImmediateSendState.PREPARING &&
                        mediaProcessingPlan != SecureMediaProcessingPlan.PASSTHROUGH ||
                        state == ImmediateSendState.FAILED,
                ) {
                    "Only an unfinished or failed local import can have an unknown media size"
                }
                require(
                    caption == null ||
                        caption.toByteArray(StandardCharsets.UTF_8).size <= MAX_CAPTION_UTF8_BYTES,
                ) { "Queued media caption is too large" }
                val unencrypted = mediaCiphertextBytes == 0 && mediaKeyBase64 == null &&
                    mediaSha256Base64 == null && preparedMediaDescriptor == null
                if (unencrypted) {
                    require(
                        state == ImmediateSendState.IMPORTING ||
                            state == ImmediateSendState.PREPARING ||
                            state == ImmediateSendState.FAILED,
                    ) {
                        "Unencrypted queued media must still be importing, preparing or have failed"
                    }
                } else {
                    require(
                        state != ImmediateSendState.IMPORTING &&
                            state != ImmediateSendState.PREPARING,
                    ) {
                        "Preparing queued media cannot carry ciphertext metadata"
                    }
                    require(mediaCiphertextBytes > 0)
                    require(hasDecodedSize(mediaKeyBase64, MediaAttachmentCipher.KEY_MATERIAL_BYTES)) {
                        "Invalid queued media key"
                    }
                    require(hasDecodedSize(mediaSha256Base64, SHA256_BYTES)) {
                        "Invalid queued media digest"
                    }
                    preparedMediaDescriptor?.let {
                        require(KitMediaMessage.parse(it) != null) {
                            "Invalid prepared queued-media descriptor"
                        }
                        requireStandardSecureMessagingText(it)
                    }
                }
            }
            ImmediateSendKind.MEDIA_V2 -> {
                require(text.isEmpty())
                require(
                    mediaType == null && mediaPlaintextBytes == 0 && mediaCiphertextBytes == 0 &&
                        mediaKeyBase64 == null && mediaSha256Base64 == null,
                ) { "A queued media album carries per-item fields only" }
                require(mediaVideoEditPlan == null) {
                    "A queued media album cannot carry a single-item video edit plan"
                }
                require(mediaDurationMillis == null) {
                    "A queued media album carries per-item duration only"
                }
                require(
                    mediaItems.size in
                        KitMediaMessageV2.MIN_ATTACHMENTS..KitMediaMessageV2.MAX_ATTACHMENTS,
                ) { "A queued media album carries two to eight attachments" }
                require(
                    mediaItems.mapTo(mutableSetOf(), ImmediateSendMediaItem::attachmentId).size ==
                        mediaItems.size,
                ) { "Queued album attachment ids must be unique" }
                val unencrypted = mediaItems.all(ImmediateSendMediaItem::isPreparing)
                require(unencrypted || mediaItems.none(ImmediateSendMediaItem::isPreparing)) {
                    "Queued album preparation cannot be partial"
                }
                if (unencrypted) {
                    require(
                        state == ImmediateSendState.IMPORTING ||
                            state == ImmediateSendState.PREPARING ||
                            state == ImmediateSendState.FAILED,
                    ) {
                        "Unencrypted queued album must still be importing, preparing or have failed"
                    }
                    require(preparedMediaDescriptor == null)
                    require(
                        state != ImmediateSendState.PREPARING ||
                            mediaItems.all {
                                it.plaintextBytes > 0 ||
                                    it.processingPlan != SecureMediaProcessingPlan.PASSTHROUGH &&
                                    it.localPlaintextBytes > 0
                            },
                    ) { "A prepared local album needs exact non-empty item sizes" }
                    require(
                        mediaItems.sumOf {
                            if (it.plaintextBytes == 0) {
                                0L
                            } else {
                                it.plaintextBytes.toLong() + 64L -
                                    (it.plaintextBytes.toLong() % 16L)
                            }
                        } <= KitMediaMessageV2.MAX_AGGREGATE_CIPHERTEXT_BYTES,
                    ) { "Queued media album is too large" }
                } else {
                    require(
                        state != ImmediateSendState.IMPORTING &&
                            state != ImmediateSendState.PREPARING,
                    ) {
                        "Preparing queued album cannot carry ciphertext metadata"
                    }
                    val storageKeys = mediaItems.mapNotNull(ImmediateSendMediaItem::storageKey)
                    require(storageKeys.size == storageKeys.toSet().size) {
                        "Queued album storage keys must be unique"
                    }
                    require(
                        mediaItems.sumOf(ImmediateSendMediaItem::ciphertextBytes) <=
                            KitMediaMessageV2.MAX_AGGREGATE_CIPHERTEXT_BYTES,
                    ) { "Queued media album is too large" }
                }
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
        require(
            state !in setOf(ImmediateSendState.IMPORTING, ImmediateSendState.PREPARING) ||
                kind in MEDIA_KINDS,
        ) {
            "Only queued media can be importing or preparing"
        }
    }

    val authenticatedText: String?
        get() = when (kind) {
            ImmediateSendKind.TEXT,
            ImmediateSendKind.PAYMENT_EVENT,
            ImmediateSendKind.REACTION,
            ImmediateSendKind.GROUP_PAYMENT_EVENT,
            ImmediateSendKind.GROUP_PAYMENT_REQUEST_EVENT,
            ImmediateSendKind.EDIT,
            -> text
            ImmediateSendKind.MEDIA,
            ImmediateSendKind.MEDIA_V2,
            -> preparedMediaDescriptor
        }

    val localMediaType: String get() = mediaOriginalType ?: checkNotNull(mediaType)
    val localPlaintextBytes: Int
        get() = mediaOriginalPlaintextBytes.takeIf { it > 0 } ?: mediaPlaintextBytes

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
                mediaOriginalType == null && mediaOriginalPlaintextBytes == 0 &&
                mediaProcessingPlan == SecureMediaProcessingPlan.PASSTHROUGH &&
                mediaVideoEditPlan == null && mediaDurationMillis == null && mediaItems.isEmpty(),
        ) { "A queued text event cannot carry media fields" }
    }

    companion object {
        const val MAX_CAPTION_UTF8_BYTES = 2_048
        private const val SHA256_BYTES = 32
        internal val CANONICAL_UUID = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        )
        private val CONVERSATION_ID = Regex("^[A-Za-z0-9._:@-]{1,64}$")
        private val MEDIA_KINDS = setOf(ImmediateSendKind.MEDIA, ImmediateSendKind.MEDIA_V2)

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
    private const val VERSION = 6

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
                data.writeNullableString(item.originalMediaType)
                data.writeInt(item.originalPlaintextBytes)
                data.writeByte(item.processingPlan.persistenceCode)
                data.writeNullableLong(item.durationMillis)
            }
            data.writeNullableString(intent.mediaOriginalType)
            data.writeInt(intent.mediaOriginalPlaintextBytes)
            data.writeByte(intent.mediaProcessingPlan.persistenceCode)
            data.writeNullableVideoEditPlan(intent.mediaVideoEditPlan)
            data.writeNullableLong(intent.mediaDurationMillis)
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
                val id = data.readString()
                val conversationId = data.readString()
                val createdAtEpochMillis = data.readLong()
                val text = data.readString()
                val mediaType = data.readNullableString()
                val caption = data.readNullableString()
                val mediaPlaintextBytes = data.readInt()
                val mediaCiphertextBytes = data.readInt()
                val mediaKeyBase64 = data.readNullableString()
                val mediaSha256Base64 = data.readNullableString()
                val preparedMediaDescriptor = data.readNullableString()
                val replyToMessageId = if (version >= 2) data.readNullableString() else null
                val mediaItems = if (version >= 3) data.readMediaItems(version) else emptyList()
                val decoded = ImmediateSendIntent(
                    id = id,
                    conversationId = conversationId,
                    kind = kind,
                    createdAtEpochMillis = createdAtEpochMillis,
                    state = state,
                    text = text,
                    mediaType = mediaType,
                    caption = caption,
                    mediaPlaintextBytes = mediaPlaintextBytes,
                    mediaCiphertextBytes = mediaCiphertextBytes,
                    mediaKeyBase64 = mediaKeyBase64,
                    mediaSha256Base64 = mediaSha256Base64,
                    preparedMediaDescriptor = preparedMediaDescriptor,
                    replyToMessageId = replyToMessageId,
                    mediaItems = mediaItems,
                    mediaOriginalType = if (version >= 4) data.readNullableString() else null,
                    mediaOriginalPlaintextBytes = if (version >= 4) {
                        data.readInt()
                    } else {
                        mediaPlaintextBytes
                    },
                    mediaProcessingPlan = if (version >= 4) {
                        SecureMediaProcessingPlan.fromPersistenceCode(data.readUnsignedByte())
                            ?: return null
                    } else {
                        SecureMediaProcessingPlan.PASSTHROUGH
                    },
                    mediaVideoEditPlan = if (version >= 5) {
                        data.readNullableVideoEditPlan()
                    } else {
                        null
                    },
                    mediaDurationMillis = if (version >= 6) data.readNullableLong() else null,
                )
                if (data.available() != 0) return null
                decoded
            }
        }.getOrNull()
    }

    private fun DataInputStream.readMediaItems(version: Int): List<ImmediateSendMediaItem> {
        val count = readInt()
        require(count in 0..KitMediaMessageV2.MAX_ATTACHMENTS) {
            "Immediate-send album item count is out of range"
        }
        return List(count) {
            val attachmentId = readString()
            val mediaType = readString()
            val plaintextBytes = readInt()
            val ciphertextBytes = readLong()
            val keyBase64 = readString()
            val ciphertextSha256Hex = readString()
            val storageKey = readNullableString()
            ImmediateSendMediaItem(
                attachmentId = attachmentId,
                mediaType = mediaType,
                plaintextBytes = plaintextBytes,
                ciphertextBytes = ciphertextBytes,
                keyBase64 = keyBase64,
                ciphertextSha256Hex = ciphertextSha256Hex,
                storageKey = storageKey,
                originalMediaType = if (version >= 4) readNullableString() else null,
                originalPlaintextBytes = if (version >= 4) readInt() else plaintextBytes,
                processingPlan = if (version >= 4) {
                    SecureMediaProcessingPlan.fromPersistenceCode(readUnsignedByte())
                        ?: error("Invalid queued media processing plan")
                } else {
                    SecureMediaProcessingPlan.PASSTHROUGH
                },
                durationMillis = if (version >= 6) readNullableLong() else null,
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

    private fun DataOutputStream.writeNullableVideoEditPlan(value: SecureMediaVideoEditPlan?) {
        writeBoolean(value != null)
        if (value != null) {
            writeLong(value.startMicros)
            writeLong(value.endMicros)
            writeBoolean(value.keepAudio)
        }
    }

    private fun DataOutputStream.writeNullableLong(value: Long?) {
        writeBoolean(value != null)
        if (value != null) writeLong(value)
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

    private fun DataInputStream.readNullableVideoEditPlan(): SecureMediaVideoEditPlan? =
        if (readBoolean()) {
            SecureMediaVideoEditPlan(
                startMicros = readLong(),
                endMicros = readLong(),
                keepAudio = readBoolean(),
            )
        } else {
            null
        }

    private fun DataInputStream.readNullableLong(): Long? =
        if (readBoolean()) readLong() else null
}
