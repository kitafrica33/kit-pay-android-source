package com.kit.wallet.data.media

import java.util.Locale

/** Track indexes that can be copied into Kit Pay's canonical cross-platform MP4. */
internal data class CanonicalMp4TrackSelection(
    val videoTrack: Int,
    val audioTrack: Int?,
)

/**
 * Selects only the compressed formats that Kit Pay can remux without lying about the output.
 *
 * MIME supplied by a picker, filename extension, and container name are deliberately absent from
 * this decision. These values come from MediaExtractor after it has inspected the retained bytes.
 * H.264 video and, when retained, at most one AAC track can be copied losslessly into a real MP4.
 * Anything that would require transcoding fails closed until a transcoder is available.
 */
internal fun canonicalMp4TrackSelection(
    trackMediaTypes: List<String?>,
    keepAudio: Boolean,
): CanonicalMp4TrackSelection? {
    val tracks = trackMediaTypes.mapIndexedNotNull { index, value ->
        value?.trim()?.lowercase(Locale.US)?.takeIf(String::isNotEmpty)?.let { index to it }
    }
    val videos = tracks.filter { (_, mediaType) -> mediaType.startsWith("video/") }
    if (videos.size != 1 || videos.single().second != H264_MEDIA_TYPE) return null

    val audioTrack = if (keepAudio) {
        val audio = tracks.filter { (_, mediaType) -> mediaType.startsWith("audio/") }
        if (audio.size > 1 || audio.any { (_, mediaType) -> mediaType != AAC_MEDIA_TYPE }) {
            return null
        }
        audio.singleOrNull()?.first
    } else {
        null
    }
    return CanonicalMp4TrackSelection(videos.single().first, audioTrack)
}

private const val H264_MEDIA_TYPE = "video/avc"
private const val AAC_MEDIA_TYPE = "audio/mp4a-latm"
