package com.kit.wallet

import com.kit.wallet.data.media.VideoFrameSize
import com.kit.wallet.data.media.videoFrameDecodeSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoFrameDecoderTest {
    @Test
    fun `4K and portrait posters bound the decode while preserving aspect ratio`() {
        assertEquals(VideoFrameSize(720, 405), videoFrameDecodeSize(3840, 2160, 720))
        assertEquals(VideoFrameSize(405, 720), videoFrameDecodeSize(2160, 3840, 720))
        assertEquals(VideoFrameSize(160, 90), videoFrameDecodeSize(3840, 2160, 160))
        assertEquals(VideoFrameSize(100, 50), videoFrameDecodeSize(100, 50, 720))
    }

    @Test
    fun `untrusted dimensions cannot overflow or bypass the maximum poster allocation`() {
        assertEquals(VideoFrameSize(720, 720), videoFrameDecodeSize(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE))
        assertEquals(VideoFrameSize(720, 1), videoFrameDecodeSize(Int.MAX_VALUE, 1, 720))
        assertNull(videoFrameDecodeSize(0, 2160, 720))
        assertNull(videoFrameDecodeSize(3840, -1, 720))
        assertNull(videoFrameDecodeSize(3840, 2160, 0))
    }
}
