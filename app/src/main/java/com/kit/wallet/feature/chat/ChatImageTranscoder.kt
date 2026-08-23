package com.kit.wallet.feature.chat

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.kit.wallet.data.messaging.KitChatMediaLimits
import java.io.ByteArrayOutputStream
import kotlin.math.max

internal const val CHAT_IMAGE_MAX_DIMENSION = 2_048

/**
 * Prepares a picked or captured photo for the encrypted attachment pipeline exactly like iOS
 * `AttachmentImageDecoder.secureJPEG`: downscale so the longest side is at most 2,048 px
 * (which also strips EXIF by re-encode), then walk JPEG quality 90 → 50 until the bytes fit
 * the shared 10 MB transfer cap. Returns null when the source cannot be decoded or fitted.
 */
internal fun transcodeChatImage(resolver: ContentResolver, uri: Uri): ByteArray? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        ?: return null
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val options = BitmapFactory.Options().apply {
        inSampleSize = chatImageSampleSize(max(bounds.outWidth, bounds.outHeight))
    }
    val decoded = resolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, options)
    } ?: return null

    val longest = max(decoded.width, decoded.height)
    val scaled = if (longest > CHAT_IMAGE_MAX_DIMENSION) {
        val scale = CHAT_IMAGE_MAX_DIMENSION.toFloat() / longest
        Bitmap.createScaledBitmap(
            decoded,
            max(1, (decoded.width * scale).toInt()),
            max(1, (decoded.height * scale).toInt()),
            true,
        )
    } else {
        decoded
    }

    for (quality in intArrayOf(90, 80, 70, 60, 50)) {
        val output = ByteArrayOutputStream()
        if (!scaled.compress(Bitmap.CompressFormat.JPEG, quality, output)) return null
        val bytes = output.toByteArray()
        if (KitChatMediaLimits.fits(bytes.size.toLong())) return bytes
    }
    return null
}

/** Power-of-two sample factor bringing the longest image side near the chat dimension. */
internal fun chatImageSampleSize(longestSide: Int): Int {
    var sample = 1
    while (longestSide / (sample * 2) >= CHAT_IMAGE_MAX_DIMENSION) sample *= 2
    return sample
}
