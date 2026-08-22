package com.kit.wallet.feature.calls

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallPictureInPictureTest {
    @Test fun `only a live video call enters picture in picture`() {
        assertTrue(shouldEnterCallPictureInPicture(video = true, CallPhase.CONNECTED))
        assertTrue(shouldEnterCallPictureInPicture(video = true, CallPhase.RECONNECTING))
        assertFalse(shouldEnterCallPictureInPicture(video = false, CallPhase.CONNECTED))
        assertFalse(shouldEnterCallPictureInPicture(video = true, CallPhase.RINGING))
        assertFalse(shouldEnterCallPictureInPicture(video = true, CallPhase.INCOMING))
        assertFalse(shouldEnterCallPictureInPicture(video = true, CallPhase.ENDED))
    }
}
