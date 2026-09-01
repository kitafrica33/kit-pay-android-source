package com.kit.wallet.data.messaging

import com.kit.wallet.data.remote.ResumableAttachmentProtocolDto

/** Exact, fail-closed negotiation for resumable opaque-ciphertext attachment uploads. */
internal object MessagingResumableAttachmentCapability {
    const val PROFILE = "kit-attachment-upload-v1"
    const val MAX_CHUNK_BYTES = 5L * 1024L * 1024L

    fun isUsable(advertisement: ResumableAttachmentProtocolDto?): Boolean =
        advertisement?.ready == true &&
            advertisement.profile == PROFILE &&
            advertisement.maxChunkBytes == MAX_CHUNK_BYTES &&
            advertisement.offsetUnit == "ciphertext_byte" &&
            advertisement.chunkDigest == "sha256" &&
            advertisement.fullDigest == "sha256"
}
