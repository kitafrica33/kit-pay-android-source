package com.kit.wallet.data.messaging

import com.kit.wallet.data.remote.MediaMessageProtocolDto

/**
 * Coherence judgment for the server's `protocols.messaging.media_message` advertisement
 * (KITMEDIA2 frozen contract, §5a).
 *
 * Mirrors [MessagingRichMediaCapability]'s reading of `kit-media-v1`: the advertisement must be
 * structurally exact where the wire format is at stake — profile string, `ready`, and the exact
 * 64-byte authenticated-envelope floor — and merely coherent where capacity is at stake. Advertised
 * caps are clamped to the compiled ceilings rather than compared for equality, so the server and
 * the app can raise limits at different times without either side failing closed (the exact
 * strict-equality failure mode the rich-media gate abandoned).
 *
 * Nothing user-reachable consumes this yet: outbound v2 composition is deferred until the server's
 * §5a readiness predicate is live and version floors are pinned above this build. The object exists
 * so capability negotiation is already decided and tested when that switch is thrown, and so the
 * send gate in `RemoteSecureMessagingTransport.Session.requireMediaMessageV2Capability` has one
 * place to grow an advertisement-driven affordance check.
 */
object MessagingMediaMessageV2Capability {

    /** Effective send limits after clamping the advertisement to the compiled ceilings. */
    data class Limits(
        val maxAttachments: Int,
        val maxDescriptorUtf8Bytes: Int,
        val maxCaptionUtf8Bytes: Int,
        val maxAttachmentCiphertextBytes: Long,
        val maxAggregateCiphertextBytes: Long,
    )

    fun isUsable(advertisement: MediaMessageProtocolDto?): Boolean =
        limitsFor(advertisement) != null

    /**
     * The clamped limits a usable advertisement grants, or null when the feature is off.
     *
     * "Off" is every §5a failure mode at once: an absent block, `ready != true`, a profile other
     * than `kit-media-v2`, an envelope floor other than exactly 64 bytes, or any capacity member
     * missing or too small to carry even one minimal two-item album. False, missing, and null all
     * mean off — never an exception.
     */
    fun limitsFor(advertisement: MediaMessageProtocolDto?): Limits? {
        advertisement ?: return null
        if (advertisement.ready != true) return null
        if (advertisement.profile != MEDIA_MESSAGE_V2_PROFILE) return null
        if (advertisement.minAttachmentCiphertextBytes !=
            KitMediaMessageV2.MIN_ATTACHMENT_CIPHERTEXT_BYTES
        ) {
            return null
        }
        val maxAttachments = advertisement.maxAttachments ?: return null
        val maxDescriptorBytes = advertisement.maxDescriptorBytes ?: return null
        val maxCaptionBytes = advertisement.maxCaptionUtf8Bytes ?: return null
        val maxAttachmentBytes = advertisement.maxAttachmentCiphertextBytes ?: return null
        val maxAggregateBytes = advertisement.maxAggregateCiphertextBytes ?: return null
        // An advertisement that cannot carry one minimal album is incoherent, not merely small: a
        // v2 message is at least two attachments of at least one 64-byte envelope each.
        if (maxAttachments < KitMediaMessageV2.MIN_ATTACHMENTS) return null
        if (maxDescriptorBytes <= 0 || maxCaptionBytes <= 0) return null
        if (maxAttachmentBytes <= KitMediaMessageV2.MIN_ATTACHMENT_CIPHERTEXT_BYTES) return null
        if (maxAggregateBytes <
            KitMediaMessageV2.MIN_ATTACHMENTS * KitMediaMessageV2.MIN_ATTACHMENT_CIPHERTEXT_BYTES
        ) {
            return null
        }
        return Limits(
            maxAttachments = minOf(
                maxAttachments,
                KitMediaMessageV2.MAX_ATTACHMENTS.toLong(),
            ).toInt(),
            maxDescriptorUtf8Bytes = minOf(
                maxDescriptorBytes,
                KitMediaMessageV2.MAX_DESCRIPTOR_UTF8_BYTES.toLong(),
            ).toInt(),
            maxCaptionUtf8Bytes = minOf(
                maxCaptionBytes,
                KitMediaMessageV2.MAX_CAPTION_UTF8_BYTES.toLong(),
            ).toInt(),
            maxAttachmentCiphertextBytes = minOf(
                maxAttachmentBytes,
                MAX_ATTACHMENT_CIPHERTEXT_BYTES,
            ),
            maxAggregateCiphertextBytes = minOf(
                maxAggregateBytes,
                KitMediaMessageV2.MAX_AGGREGATE_CIPHERTEXT_BYTES,
            ),
        )
    }

    private const val MEDIA_MESSAGE_V2_PROFILE = "kit-media-v2"

    /**
     * Compiled per-attachment ceiling: 200 MiB of plaintext plus the 64-byte authenticated
     * envelope, the §5a `max_attachment_ciphertext_bytes` value. The codec has no per-attachment
     * constant of its own because descriptor validation bounds the aggregate; the per-attachment
     * bound only matters here, where an advertisement is clamped.
     */
    internal const val MAX_ATTACHMENT_CIPHERTEXT_BYTES = 200L * 1024L * 1024L + 64L
}
