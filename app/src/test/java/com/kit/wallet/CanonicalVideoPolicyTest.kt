package com.kit.wallet

import com.kit.wallet.data.media.canonicalMp4TrackSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CanonicalVideoPolicyTest {
    @Test
    fun `h264 and optional aac are selected from inspected tracks`() {
        assertEquals(
            1,
            canonicalMp4TrackSelection(
                listOf("application/x-timed-metadata", "video/avc", "audio/mp4a-latm"),
                keepAudio = true,
            )?.videoTrack,
        )
        assertEquals(
            2,
            canonicalMp4TrackSelection(
                listOf("application/x-timed-metadata", "video/avc", "audio/mp4a-latm"),
                keepAudio = true,
            )?.audioTrack,
        )
        assertNull(
            canonicalMp4TrackSelection(listOf("video/avc"), keepAudio = true)?.audioTrack,
        )
    }

    @Test
    fun `unsupported compressed tracks fail closed when they would reach the wire`() {
        assertNull(canonicalMp4TrackSelection(listOf("video/hevc"), keepAudio = true))
        assertNull(
            canonicalMp4TrackSelection(
                listOf("video/avc", "audio/opus"),
                keepAudio = true,
            ),
        )
        assertNull(
            canonicalMp4TrackSelection(
                listOf("video/avc", "video/avc"),
                keepAudio = false,
            ),
        )
    }

    @Test
    fun `a muted edit may safely discard an unsupported audio track`() {
        val selection = canonicalMp4TrackSelection(
            listOf("video/avc", "audio/opus"),
            keepAudio = false,
        )

        assertEquals(0, selection?.videoTrack)
        assertNull(selection?.audioTrack)
    }
}
