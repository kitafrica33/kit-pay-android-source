package com.kit.wallet.data.messaging

import android.content.Context
import com.kit.wallet.data.media.MediaVideoRemuxPlan
import com.kit.wallet.data.media.MediaVideoRemuxer
import com.kit.wallet.data.media.compressUploadJpegToFile
import com.kit.wallet.data.media.decodeUploadImage
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One upload plaintext representation and the lifecycle of any unpublished scratch it owns. */
internal data class PreparedSecureMedia(
    val file: File,
    val deleteAfterUse: Boolean,
) {
    fun release() {
        if (deleteAfterUse) file.delete()
    }
}

/** Converts a retained original into its wire representation without changing the original. */
internal interface SecureMediaUploadProcessor {
    suspend fun prepare(
        original: SecureMediaFile,
        plan: SecureMediaProcessingPlan,
        videoEditPlan: SecureMediaVideoEditPlan? = null,
        maximumPlaintextBytes: Int = MAX_IMAGE_PLAINTEXT_BYTES,
    ): PreparedSecureMedia
}

@Singleton
internal class AndroidSecureMediaUploadProcessor @Inject constructor(
    @ApplicationContext context: Context,
) : SecureMediaUploadProcessor {
    private val directory = File(context.noBackupFilesDir, DIRECTORY_NAME)

    override suspend fun prepare(
        original: SecureMediaFile,
        plan: SecureMediaProcessingPlan,
        videoEditPlan: SecureMediaVideoEditPlan?,
        maximumPlaintextBytes: Int,
    ): PreparedSecureMedia = withContext(Dispatchers.IO) {
        check(original.file.isFile && original.byteCount > 0L) {
            "The retained attachment is no longer available"
        }
        when (plan) {
            SecureMediaProcessingPlan.PASSTHROUGH -> {
                require(videoEditPlan == null) { "Passthrough media cannot carry video edits" }
                require(original.byteCount <= maximumPlaintextBytes.toLong()) {
                    "This attachment is too large to send"
                }
                PreparedSecureMedia(original.file, deleteAfterUse = false)
            }
            SecureMediaProcessingPlan.CHAT_IMAGE_JPEG -> {
                require(videoEditPlan == null) { "A photo cannot carry video edits" }
                check(original.mediaType.startsWith("image/")) {
                    "The retained photo has an invalid media type"
                }
                check(directory.isDirectory || directory.mkdirs()) {
                    "Secure media processing storage is unavailable"
                }
                directory.deleteStaleScratch()
                val output = File(directory, ".${UUID.randomUUID()}.jpg.partial")
                val decoded = decodeUploadImage(original.file, CHAT_IMAGE_MAX_DIMENSION)
                    ?: error("The selected photo could not be prepared")
                try {
                    val byteCount = compressUploadJpegToFile(
                        source = decoded,
                        destination = output,
                        maxBytes = maximumPlaintextBytes,
                        qualities = CHAT_IMAGE_QUALITIES,
                    ) ?: error("The selected photo could not be prepared")
                    check(byteCount == output.length() && byteCount > 0L) {
                        "The prepared photo is incomplete"
                    }
                    output.restrictToOwner()
                    PreparedSecureMedia(output, deleteAfterUse = true)
                } catch (error: Throwable) {
                    output.delete()
                    throw error
                } finally {
                    decoded.recycle()
                }
            }
            SecureMediaProcessingPlan.CHAT_VIDEO_MP4 -> {
                check(original.mediaType.startsWith("video/")) {
                    "The retained video has an invalid media type"
                }
                val edit = requireNotNull(videoEditPlan) {
                    "The retained video has no edit plan"
                }
                check(directory.isDirectory || directory.mkdirs()) {
                    "Secure media processing storage is unavailable"
                }
                directory.deleteStaleScratch()
                val output = File(directory, ".${UUID.randomUUID()}.mp4.partial")
                try {
                    check(
                        MediaVideoRemuxer.remux(
                            source = original.file,
                            destination = output,
                            plan = MediaVideoRemuxPlan(
                                edit.startMicros,
                                edit.endMicros,
                                edit.keepAudio,
                            ),
                        ),
                    ) { "The selected video could not be prepared" }
                    check(output.length() in 1..maximumPlaintextBytes.toLong()) {
                        "This attachment is too large to send"
                    }
                    output.restrictToOwner()
                    PreparedSecureMedia(output, deleteAfterUse = true)
                } catch (error: Throwable) {
                    output.delete()
                    throw error
                }
            }
        }
    }

    private fun File.deleteStaleScratch(nowMillis: Long = System.currentTimeMillis()) {
        listFiles().orEmpty()
            .filter {
                it.isFile && it.name.endsWith(".partial") &&
                    nowMillis - it.lastModified() > STALE_MILLIS
            }
            .forEach(File::delete)
    }

    private fun File.restrictToOwner() {
        setReadable(false, false)
        setWritable(false, false)
        setReadable(true, true)
        setWritable(true, true)
    }

    private companion object {
        const val DIRECTORY_NAME = "secure-media-processing"
        const val STALE_MILLIS = 24L * 60L * 60L * 1_000L
        const val CHAT_IMAGE_MAX_DIMENSION = 2_048
        val CHAT_IMAGE_QUALITIES = intArrayOf(90, 80, 70, 60, 50)
    }
}
