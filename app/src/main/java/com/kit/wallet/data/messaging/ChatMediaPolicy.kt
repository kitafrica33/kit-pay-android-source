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

/**
 * Placeholder label for reserved media-family text this build cannot strictly parse — an unknown
 * future `KITMEDIA` generation or a malformed descriptor. Such text can embed attachment key
 * material, so every surface shows this label and never the text itself.
 */
internal const val UNSUPPORTED_ATTACHMENT_LABEL = "Attachment"

/**
 * Plural preview label for a media-v2 album, shared by caption-less bubbles, chat rows,
 * notifications and accessibility copy: "3 Photos" when every item is one kind, mixed kinds
 * "3 Attachments" — identical copy to iOS (media-v2 §8).
 */
internal fun mediaAlbumPreviewLabel(mediaTypes: List<String>): String {
    val kinds = mediaTypes.mapTo(mutableSetOf(), KitChatMediaKind::fromMediaType)
    val label = kinds.singleOrNull()?.previewLabel ?: UNSUPPORTED_ATTACHMENT_LABEL
    return "${mediaTypes.size} ${label}s"
}

/** TalkBack copy for one album bubble: plural kind first, then the exact validated caption. */
internal fun mediaAlbumAccessibilityLabel(mediaTypes: List<String>, caption: String?): String =
    if (caption == null) {
        mediaAlbumPreviewLabel(mediaTypes)
    } else {
        "${mediaAlbumPreviewLabel(mediaTypes)} · $caption"
    }

/**
 * Reply-quote label for a media-v2 album: the first item's kind label, "+N" for the rest, then
 * the caption in the shared " · " style — never descriptor text (media-v2 §8). Caption presence
 * is null/non-null only: a validated caption is byte-exact and may consist entirely of
 * codepoints a platform trim would call blank, and it is still the caption.
 */
internal fun mediaAlbumQuoteLabel(mediaTypes: List<String>, caption: String?): String {
    val firstType = mediaTypes.firstOrNull() ?: return UNSUPPORTED_ATTACHMENT_LABEL
    val first = KitChatMediaKind.fromMediaType(firstType).previewLabel
    val label = if (mediaTypes.size > 1) "$first +${mediaTypes.size - 1}" else first
    return if (caption == null) label else "$label · $caption"
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
