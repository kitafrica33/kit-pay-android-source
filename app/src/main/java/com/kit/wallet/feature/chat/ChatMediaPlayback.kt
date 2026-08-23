package com.kit.wallet.feature.chat

import android.content.Context
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.graphics.Bitmap
import com.kit.wallet.data.messaging.chatMediaFileExtension
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/**
 * One-at-a-time in-memory voice-note player, mirroring the iOS `VoiceNotePlayer`: plays
 * decrypted bytes directly (never from a world-readable path), reports progress for the
 * waveform, and stops itself when another note starts.
 */
internal object VoiceNotePlayer {
    private var player: MediaPlayer? = null
    private var playingMessageId: String? = null

    /** Starts [bytes] for [messageId]; stops any other note first. */
    @Synchronized
    fun play(messageId: String, bytes: ByteArray, onCompleted: () -> Unit) {
        stop()
        val source = InMemoryMediaDataSource(bytes.copyOf())
        val created = MediaPlayer()
        try {
            created.setDataSource(source)
            created.prepare()
            created.setOnCompletionListener {
                stop()
                onCompleted()
            }
            created.start()
        } catch (error: Exception) {
            runCatching { created.release() }
            source.close()
            throw IllegalStateException("This voice note could not be played", error)
        }
        player = created
        playingMessageId = messageId
    }

    @Synchronized
    fun pause(messageId: String) {
        if (playingMessageId == messageId) runCatching { player?.pause() }
    }

    @Synchronized
    fun resume(messageId: String) {
        if (playingMessageId == messageId) runCatching { player?.start() }
    }

    @Synchronized
    fun stop() {
        player?.let { active ->
            runCatching { active.stop() }
            runCatching { active.release() }
        }
        player = null
        playingMessageId = null
    }

    @Synchronized
    fun isPlaying(messageId: String): Boolean =
        playingMessageId == messageId && runCatching { player?.isPlaying == true }.getOrDefault(false)

    /** Played fraction 0..1 for [messageId]; 0 when not the active note. */
    @Synchronized
    fun progress(messageId: String): Float {
        if (playingMessageId != messageId) return 0f
        val active = player ?: return 0f
        return runCatching {
            val duration = active.duration
            if (duration <= 0) 0f else min(1f, active.currentPosition.toFloat() / duration)
        }.getOrDefault(0f)
    }

    private class InMemoryMediaDataSource(private var data: ByteArray?) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            val bytes = data ?: return -1
            if (position >= bytes.size) return -1
            val count = min(size.toLong(), bytes.size - position).toInt()
            bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
            return count
        }

        override fun getSize(): Long = data?.size?.toLong() ?: 0

        override fun close() {
            data?.fill(0)
            data = null
        }
    }
}

/**
 * Deterministic 26-bar waveform seeded from the message UUID, byte-compatible with the iOS
 * `VoiceNoteWaveform` shape (`height = 6 + byte % 16` over a 22-point track).
 */
internal fun voiceNoteWaveformFractions(messageId: String): List<Float> {
    val seed = messageId.filter(Char::isLetterOrDigit).ifEmpty { "kitpay" }
    return List(26) { index ->
        val byte = seed[index % seed.length].code
        (6 + (byte % 16)) / 22f
    }
}

/** Extracts a poster frame near the start of a decrypted video, like the iOS 0.1 s poster. */
internal fun videoPosterFrame(context: Context, plaintext: ByteArray, mediaType: String): Bitmap? {
    val temp = File(context.cacheDir, "kit-poster-${UUID.randomUUID()}.${chatMediaFileExtension(mediaType)}")
    val retriever = MediaMetadataRetriever()
    return try {
        temp.writeBytes(plaintext)
        retriever.setDataSource(temp.absolutePath)
        retriever.getFrameAtTime(100_000)
    } catch (_: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
        temp.delete()
    }
}

/**
 * Writes decrypted media into this app's private cache for a short-lived open/play handoff.
 * The caller deletes the file as soon as the viewer closes, mirroring iOS temp-file hygiene.
 */
internal fun writeChatMediaTempFile(
    context: Context,
    plaintext: ByteArray,
    mediaType: String,
    displayName: String?,
): File {
    val directory = File(context.cacheDir, "chat-media/${UUID.randomUUID()}")
    check(directory.mkdirs()) { "The media viewer could not prepare storage" }
    val sanitized = displayName
        ?.replace(Regex("[/\\\\:]"), "-")
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.take(120)
        ?: "Kit-media.${chatMediaFileExtension(mediaType)}"
    val named = if (sanitized.contains('.')) {
        sanitized
    } else {
        "$sanitized.${chatMediaFileExtension(mediaType)}"
    }
    val file = File(directory, named)
    file.writeBytes(plaintext)
    return file
}

internal fun deleteChatMediaTempFile(file: File) {
    runCatching {
        file.delete()
        file.parentFile?.takeIf { it.name.length == 36 }?.delete()
    }
}

internal fun formatVoiceNoteTime(fractionOrMillis: Long): String {
    val totalSeconds = max(0L, fractionOrMillis / 1_000)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
