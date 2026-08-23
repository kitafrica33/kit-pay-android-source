package com.kit.wallet.feature.settings

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.kit.wallet.data.remote.ProfileAvatarUploader
import java.io.ByteArrayOutputStream
import kotlin.math.min

internal const val PROFILE_AVATAR_MAX_DIMENSION = 640

/**
 * Decodes the picked image, center-crops it square, downscales it to the avatar dimension, and
 * compresses it as JPEG within the byte budget shared with iOS. Returns null when the source
 * cannot be decoded or cannot fit the budget even at the lowest acceptable quality.
 */
internal fun transcodeProfileAvatar(resolver: ContentResolver, uri: Uri): ByteArray? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        ?: return null
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize(min(bounds.outWidth, bounds.outHeight))
    }
    val decoded = resolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, options)
    } ?: return null

    val side = min(decoded.width, decoded.height)
    if (side <= 0) return null
    val square = Bitmap.createBitmap(
        decoded,
        (decoded.width - side) / 2,
        (decoded.height - side) / 2,
        side,
        side,
    )
    val scaled = if (side > PROFILE_AVATAR_MAX_DIMENSION) {
        Bitmap.createScaledBitmap(
            square,
            PROFILE_AVATAR_MAX_DIMENSION,
            PROFILE_AVATAR_MAX_DIMENSION,
            true,
        )
    } else {
        square
    }

    for (quality in intArrayOf(90, 80, 70, 60, 50, 40)) {
        val output = ByteArrayOutputStream()
        if (!scaled.compress(Bitmap.CompressFormat.JPEG, quality, output)) return null
        val bytes = output.toByteArray()
        if (bytes.size <= ProfileAvatarUploader.MAX_AVATAR_BYTES) return bytes
    }
    return null
}

/** Power-of-two sample factor bringing the shorter image side near the avatar dimension. */
internal fun sampleSize(shortestSide: Int): Int {
    var sample = 1
    while (shortestSide / (sample * 2) >= PROFILE_AVATAR_MAX_DIMENSION) sample *= 2
    return sample
}
