package com.kit.wallet.data.media

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * Every image format the platform can decode on any supported API level, matching what customers
 * can already send from other messengers. A wildcard image request accepts more than
 * [BitmapFactory] alone handles, so [decodeUploadImage] deliberately prefers [ImageDecoder]
 * (HEIF, AVIF, animated WebP/GIF) and only falls back to the legacy decoder.
 */
internal object UploadImageFormats {
    /**
     * Explicit MIME list for document-style pickers, which — unlike the photo picker — filter by
     * exact type and would otherwise hide HEIC and AVIF libraries.
     */
    val PICKER_MIME_TYPES: Array<String> = arrayOf(
        "image/jpeg",
        "image/png",
        "image/webp",
        "image/gif",
        "image/bmp",
        "image/heic",
        "image/heif",
        "image/avif",
        "image/x-ms-bmp",
        "image/vnd.wap.wbmp",
        "image/*",
    )
}

/**
 * Decodes [uri] into a software ARGB bitmap whose longest side is at most [maxDimension], with
 * EXIF orientation already applied. Returns null only when no decoder on this device can read
 * the source at all.
 *
 * Callers own the returned bitmap. It is never a `HARDWARE` bitmap, so it is safe to crop,
 * scale, colour-filter and compress.
 */
internal fun decodeUploadImage(
    resolver: ContentResolver,
    uri: Uri,
    maxDimension: Int,
): Bitmap? {
    require(maxDimension > 0) { "maxDimension must be positive" }
    val decoded = decodeWithImageDecoder(resolver, uri, maxDimension)
        ?: decodeWithBitmapFactory(resolver, uri, maxDimension)
        ?: return null
    return decoded.fitWithin(maxDimension)
}

/** File-backed equivalent used after a picked original is durable in Sent Media. */
internal fun decodeUploadImage(file: File, maxDimension: Int): Bitmap? {
    require(maxDimension > 0) { "maxDimension must be positive" }
    if (!file.isFile || file.length() <= 0L) return null
    val decoded = decodeFileWithImageDecoder(file, maxDimension)
        ?: decodeFileWithBitmapFactory(file, maxDimension)
        ?: return null
    return decoded.fitWithin(maxDimension)
}

/**
 * Compresses [source] to JPEG, stepping quality down until the bytes fit [maxBytes]. Alpha is
 * flattened onto white first, because JPEG has no alpha channel and would otherwise render
 * transparent PNG and WebP regions as black.
 */
internal fun compressUploadJpeg(source: Bitmap, maxBytes: Int, qualities: IntArray): ByteArray? {
    val opaque = source.flattenedForJpeg()
    try {
        for (quality in qualities) {
            val output = ByteArrayOutputStream()
            if (!opaque.compress(Bitmap.CompressFormat.JPEG, quality, output)) return null
            val bytes = output.toByteArray()
            if (bytes.size <= maxBytes) return bytes
        }
    } finally {
        if (opaque !== source) opaque.recycle()
    }
    return null
}

/** Encodes into an unpublished file so background preparation never needs a JPEG-sized array. */
internal fun compressUploadJpegToFile(
    source: Bitmap,
    destination: File,
    maxBytes: Int,
    qualities: IntArray,
): Long? {
    require(maxBytes > 0) { "maxBytes must be positive" }
    val opaque = source.flattenedForJpeg()
    try {
        for (quality in qualities) {
            val encoded = runCatching {
                FileOutputStream(destination, false).use { output ->
                    if (!opaque.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                        return@use false
                    }
                    output.flush()
                    output.fd.sync()
                    true
                }
            }.getOrDefault(false)
            val byteCount = destination.length()
            if (encoded && byteCount in 1..maxBytes.toLong()) return byteCount
        }
    } finally {
        if (opaque !== source) opaque.recycle()
    }
    destination.delete()
    return null
}

/** Power-of-two sample factor bringing [longestSide] to at least [maxDimension]. */
internal fun uploadSampleSize(longestSide: Int, maxDimension: Int): Int {
    var sample = 1
    while (maxDimension > 0 && longestSide / (sample * 2) >= maxDimension) sample *= 2
    return sample
}

private fun decodeWithImageDecoder(
    resolver: ContentResolver,
    uri: Uri,
    maxDimension: Int,
): Bitmap? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
    return runCatching {
        val source = ImageDecoder.createSource(resolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            // A HARDWARE bitmap has no pixel access, so cropping and JPEG encoding would fail.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            decoder.setTargetSampleSize(
                uploadSampleSize(max(info.size.width, info.size.height), maxDimension),
            )
        }
    }.getOrNull()
}

private fun decodeFileWithImageDecoder(file: File, maxDimension: Int): Bitmap? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
    return runCatching {
        val source = ImageDecoder.createSource(file)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            decoder.setTargetSampleSize(
                uploadSampleSize(max(info.size.width, info.size.height), maxDimension),
            )
        }
    }.getOrNull()
}

private fun decodeWithBitmapFactory(
    resolver: ContentResolver,
    uri: Uri,
    maxDimension: Int,
): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    // decodeStream always returns null in bounds mode, so the measurement result lives in the
    // options, not in the return value. Reading the return value instead is what previously made
    // every selection fail.
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

    val options = BitmapFactory.Options().apply {
        inSampleSize = uploadSampleSize(max(bounds.outWidth, bounds.outHeight), maxDimension)
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val bitmap = resolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, options)
    } ?: return@runCatching null

    val rotation = exifRotationDegrees(resolver, uri)
    if (rotation == 0) bitmap else bitmap.rotated(rotation)
}.getOrNull()

private fun decodeFileWithBitmapFactory(file: File, maxDimension: Int): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    val options = BitmapFactory.Options().apply {
        inSampleSize = uploadSampleSize(max(bounds.outWidth, bounds.outHeight), maxDimension)
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return@runCatching null
    val orientation = runCatching {
        ExifInterface(file.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val rotation = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }
    if (rotation == 0) bitmap else bitmap.rotated(rotation)
}.getOrNull()

/** [ImageDecoder] applies orientation itself; only the legacy path needs this. */
private fun exifRotationDegrees(resolver: ContentResolver, uri: Uri): Int = runCatching {
    val orientation = resolver.openInputStream(uri)?.use {
        ExifInterface(it).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    } ?: ExifInterface.ORIENTATION_NORMAL
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }
}.getOrDefault(0)

private fun Bitmap.rotated(degrees: Int): Bitmap {
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    val rotated = Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    if (rotated !== this) recycle()
    return rotated
}

private fun Bitmap.fitWithin(maxDimension: Int): Bitmap {
    val longest = max(width, height)
    if (longest <= maxDimension || longest <= 0) return this
    val scale = maxDimension.toFloat() / longest
    val scaled = Bitmap.createScaledBitmap(
        this,
        max(1, (width * scale).toInt()),
        max(1, (height * scale).toInt()),
        true,
    )
    if (scaled !== this) recycle()
    return scaled
}

private fun Bitmap.flattenedForJpeg(): Bitmap {
    if (!hasAlpha() && config != Bitmap.Config.HARDWARE) return this
    val opaque = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    Canvas(opaque).apply {
        drawColor(Color.WHITE)
        drawBitmap(this@flattenedForJpeg, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
    }
    return opaque
}
