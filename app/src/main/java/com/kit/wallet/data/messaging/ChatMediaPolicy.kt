package com.kit.wallet.data.messaging

/**
 * Shared presentation and limit policy for kit-media-v1 chat attachments, mirroring the iOS
 * `ChatMediaPolicy`. The v1 `KITMEDIA1` descriptor deliberately carries no dedicated kind
 * field, so the MIME type in `mt` is the single cross-platform source of truth.
 */
internal enum class KitChatMediaKind {
    IMAGE,
    VOICE,
    VIDEO,
    DOCUMENT,
    ;

    /** Chat-list and notification preview label, identical copy to iOS. */
    val previewLabel: String
        get() = when (this) {
            IMAGE -> "Photo"
            VOICE -> "Voice note"
            VIDEO -> "Video"
            DOCUMENT -> "Document"
        }

    companion object {
        fun fromMediaType(mediaType: String): KitChatMediaKind = when {
            mediaType.startsWith("image/") -> IMAGE
            mediaType.startsWith("audio/") -> VOICE
            mediaType.startsWith("video/") -> VIDEO
            else -> DOCUMENT
        }
    }
}

internal object KitChatMediaLimits {
    /** One shared transfer cap for every kind; the wire enforces the same bound on both ends. */
    const val MAX_TRANSFER_BYTES = MAX_IMAGE_PLAINTEXT_BYTES

    /** Derived, never restated, so the number a person is shown cannot drift from the real cap. */
    val MAX_TRANSFER_LABEL: String = "${MAX_TRANSFER_BYTES / (1_024 * 1_024)} MB"

    /** Voice-note recording bounds shared with iOS (`VoiceNoteRecorder`). */
    const val VOICE_NOTE_MIN_DURATION_MILLIS = 1_000L
    const val VOICE_NOTE_MAX_DURATION_MILLIS = 30L * 60L * 1_000L

    /** Camera video notes stop at three minutes, matching iOS `videoNoteMaximumDuration`. */
    const val VIDEO_NOTE_MAX_DURATION_SECONDS = 3 * 60

    fun fits(byteCount: Long): Boolean = byteCount in 1..MAX_TRANSFER_BYTES.toLong()
}

/** File extension for share/open targets, mirroring iOS `ChatMediaTempFiles.fileExtension`. */
internal fun chatMediaFileExtension(mediaType: String): String = when (mediaType) {
    "image/jpeg" -> "jpg"
    "image/png" -> "png"
    "image/webp" -> "webp"
    "image/gif" -> "gif"
    "audio/mp4" -> "m4a"
    "audio/aac" -> "aac"
    "audio/mpeg" -> "mp3"
    "audio/ogg" -> "ogg"
    "video/mp4" -> "mp4"
    "video/quicktime" -> "mov"
    "video/webm" -> "webm"
    "application/pdf" -> "pdf"
    "application/zip" -> "zip"
    "application/msword" -> "doc"
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
    "application/vnd.ms-excel" -> "xls"
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx"
    "application/vnd.ms-powerpoint" -> "ppt"
    "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "pptx"
    "text/plain" -> "txt"
    "text/csv" -> "csv"
    else -> "bin"
}
