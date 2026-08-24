package com.kit.wallet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kit.wallet.data.media.compressUploadJpeg
import com.kit.wallet.data.media.decodeUploadImage
import com.kit.wallet.feature.chat.transcodeChatImage
import com.kit.wallet.feature.settings.transcodeProfileAvatar
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guards the picked-photo decode path end to end. The earlier implementation read the return
 * value of a bounds-only `BitmapFactory.decodeStream`, which is always null, so every profile
 * photo and every chat photo attachment failed with "could not be prepared". These cases fail
 * loudly if that class of bug returns.
 */
@RunWith(AndroidJUnit4::class)
class UploadImageDecoderTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun everySupportedStillFormatDecodes() {
        val formats = listOf(
            Bitmap.CompressFormat.JPEG to "jpg",
            Bitmap.CompressFormat.PNG to "png",
            Bitmap.CompressFormat.WEBP to "webp",
        )
        for ((format, extension) in formats) {
            val uri = writeImage(format, extension, width = 1_200, height = 800)
            try {
                val decoded = decodeUploadImage(context.contentResolver, uri, 2_048)
                assertNotNull("$extension failed to decode", decoded)
                checkNotNull(decoded).recycle()
            } finally {
                uri.path?.let { File(it).delete() }
            }
        }
    }

    @Test
    fun profileAvatarTranscodeProducesASquareJpegWithinTheBudget() {
        val uri = writeImage(Bitmap.CompressFormat.PNG, "png", width = 1_600, height = 900)
        try {
            val jpeg = transcodeProfileAvatar(context.contentResolver, uri)
            assertNotNull("avatar transcode returned null", jpeg)
            val bytes = checkNotNull(jpeg)
            assertTrue(bytes.size <= 384 * 1_024)
            val decoded = checkNotNull(
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size),
            )
            assertEquals(decoded.width, decoded.height)
            assertTrue(decoded.width <= 640)
            decoded.recycle()
        } finally {
            uri.path?.let { File(it).delete() }
        }
    }

    @Test
    fun chatImageTranscodeBoundsTheLongestSide() {
        val uri = writeImage(Bitmap.CompressFormat.JPEG, "jpg", width = 4_000, height = 2_000)
        try {
            val jpeg = checkNotNull(transcodeChatImage(context.contentResolver, uri))
            val decoded = checkNotNull(
                android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size),
            )
            assertTrue(maxOf(decoded.width, decoded.height) <= 2_048)
            decoded.recycle()
        } finally {
            uri.path?.let { File(it).delete() }
        }
    }

    @Test
    fun transparentSourcesFlattenInsteadOfEncodingBlack() {
        val transparent = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        try {
            val bytes = checkNotNull(
                compressUploadJpeg(transparent, 256 * 1_024, intArrayOf(90)),
            )
            val decoded = checkNotNull(
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size),
            )
            // A fully transparent source must land on white, not the JPEG default of black.
            assertTrue(Color.red(decoded.getPixel(32, 32)) > 200)
            decoded.recycle()
        } finally {
            transparent.recycle()
        }
    }

    private fun writeImage(
        format: Bitmap.CompressFormat,
        extension: String,
        width: Int,
        height: Int,
    ): Uri {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.rgb(20, 140, 110))
        val file = File(context.cacheDir, "upload-decode-${UUID.randomUUID()}.$extension")
        file.outputStream().use { bitmap.compress(format, 92, it) }
        bitmap.recycle()
        return Uri.fromFile(file)
    }
}
