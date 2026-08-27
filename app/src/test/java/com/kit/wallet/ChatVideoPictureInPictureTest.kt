package com.kit.wallet

import com.kit.wallet.feature.chat.shouldEnterChatVideoPictureInPicture
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatVideoPictureInPictureTest {
    @Test
    fun `a playing video follows the user out of the app`() {
        assertTrue(
            shouldEnterChatVideoPictureInPicture(isPlaying = true, callInProgress = false),
        )
    }

    /** A poster frame is not playback; there is nothing to keep alive. */
    @Test
    fun `a video that is not playing is left where it is`() {
        assertFalse(
            shouldEnterChatVideoPictureInPicture(isPlaying = false, callInProgress = false),
        )
    }

    /** A call owns the audio route, and its own window. A chat video takes neither. */
    @Test
    fun `a call keeps the window`() {
        assertFalse(
            shouldEnterChatVideoPictureInPicture(isPlaying = true, callInProgress = true),
        )
    }

    @Test
    fun `a device without the feature is never asked for it`() {
        assertFalse(
            shouldEnterChatVideoPictureInPicture(
                isPlaying = true,
                callInProgress = false,
                supported = false,
            ),
        )
    }
}
