package com.kit.wallet.feature.chat

import android.content.ContentResolver
import android.net.Uri
import com.kit.wallet.data.media.compressUploadJpeg
import com.kit.wallet.data.media.decodeUploadImage
import com.kit.wallet.data.messaging.KitChatMediaLimits

internal const val CHAT_IMAGE_MAX_DIMENSION = 2_048

private val CHAT_IMAGE_QUALITIES = intArrayOf(90, 80, 70, 60, 50)

/**
 * Prepares a picked or captured photo for the encrypted attachment pipeline exactly like iOS
 * `AttachmentImageDecoder.secureJPEG`: downscale so the longest side is at most 2,048 px
 * (which also strips EXIF by re-encode), then walk JPEG quality 90 → 50 until the bytes fit
 * the shared transfer cap. Every still format the device can decode is accepted — JPEG, PNG,
 * WebP, GIF, BMP, HEIC/HEIF and AVIF — with EXIF orientation applied and transparency
 * flattened. Returns null when the source cannot be decoded or fitted.
 */
internal fun transcodeChatImage(resolver: ContentResolver, uri: Uri): ByteArray? {
    val decoded = decodeUploadImage(resolver, uri, CHAT_IMAGE_MAX_DIMENSION) ?: return null
    return try {
        compressUploadJpeg(
            decoded,
            KitChatMediaLimits.MAX_TRANSFER_BYTES,
            CHAT_IMAGE_QUALITIES,
        )
    } finally {
        decoded.recycle()
    }
}
