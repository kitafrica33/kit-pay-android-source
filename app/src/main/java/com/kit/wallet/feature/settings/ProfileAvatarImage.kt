package com.kit.wallet.feature.settings

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import com.kit.wallet.data.media.compressUploadJpeg
import com.kit.wallet.data.media.decodeUploadImage
import com.kit.wallet.data.remote.ProfileAvatarUploader
import kotlin.math.min

/**
 * The window the server accepts, mirroring `media.avatar.min_dimension` / `max_dimension` and the
 * iOS `ProfileAvatarUploadPolicy`. The server re-encodes the avatar at whatever size it arrives
 * and rejects anything outside this window as an invalid image, so an avatar that leaves the
 * device larger than [PROFILE_AVATAR_MAX_DIMENSION] comes back as a scan rejection.
 */
internal const val PROFILE_AVATAR_MAX_DIMENSION = 512
internal const val PROFILE_AVATAR_MIN_DIMENSION = 64

/**
 * Upper bound for the intermediate decode. Cropping to a square before downscaling means a very
 * wide or very tall source must still carry [PROFILE_AVATAR_MAX_DIMENSION] pixels on its shorter
 * side, so the decode budget is measured on the longer one.
 */
private const val PROFILE_AVATAR_SOURCE_MAX_DIMENSION = 1_920

private val PROFILE_AVATAR_QUALITIES = intArrayOf(90, 80, 70, 60, 50, 40)

/**
 * Decodes the picked image, center-crops it square, downscales it to the avatar dimension, and
 * compresses it as JPEG within the byte budget shared with iOS. Accepts every still format the
 * device can decode — JPEG, PNG, WebP, GIF, BMP, HEIC/HEIF and AVIF — with EXIF orientation
 * applied and transparency flattened. Returns null when the source cannot be decoded at all or
 * cannot fit the budget even at the lowest acceptable quality.
 */
internal fun transcodeProfileAvatar(resolver: ContentResolver, uri: Uri): ByteArray? {
    val decoded = decodeUploadImage(resolver, uri, PROFILE_AVATAR_SOURCE_MAX_DIMENSION)
        ?: return null
    val square = decoded.centerSquare() ?: return null
    val scaled = square.scaledToAvatar()
    return try {
        compressUploadJpeg(scaled, ProfileAvatarUploader.MAX_AVATAR_BYTES, PROFILE_AVATAR_QUALITIES)
    } finally {
        scaled.recycle()
    }
}

private fun Bitmap.centerSquare(): Bitmap? {
    val side = min(width, height)
    if (side <= 0) {
        recycle()
        return null
    }
    val square = Bitmap.createBitmap(this, (width - side) / 2, (height - side) / 2, side, side)
    if (square !== this) recycle()
    return square
}

/**
 * Clamps the square into the window the server accepts. A thumbnail smaller than
 * [PROFILE_AVATAR_MIN_DIMENSION] is enlarged rather than refused: the alternative is telling
 * someone their perfectly ordinary picture was rejected.
 */
private fun Bitmap.scaledToAvatar(): Bitmap {
    val side = width.coerceIn(PROFILE_AVATAR_MIN_DIMENSION, PROFILE_AVATAR_MAX_DIMENSION)
    if (side == width) return this
    val scaled = Bitmap.createScaledBitmap(this, side, side, true)
    if (scaled !== this) recycle()
    return scaled
}
