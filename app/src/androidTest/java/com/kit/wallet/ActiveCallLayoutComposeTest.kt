package com.kit.wallet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kit.wallet.data.notifications.ActiveCallPresence
import com.kit.wallet.feature.calls.ActiveCallContent
import com.kit.wallet.feature.calls.ActiveCallMiniBar
import com.kit.wallet.feature.calls.ActiveCallUiState
import com.kit.wallet.feature.calls.CallPhase
import com.kit.wallet.feature.calls.RemoteCallParticipant
import com.kit.wallet.navigation.OngoingSessionLayout
import com.kit.wallet.ui.theme.KitWalletTheme
import io.livekit.android.LiveKit
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.track.LocalVideoTrack
import java.io.File
import kotlin.math.abs
import livekit.org.webrtc.CapturerObserver
import livekit.org.webrtc.JavaI420Buffer
import livekit.org.webrtc.SurfaceTextureHelper
import livekit.org.webrtc.VideoCapturer
import livekit.org.webrtc.VideoFrame
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Native layout/rendering coverage with generated frames; no camera, microphone or room connection. */
class ActiveCallLayoutComposeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var room: Room? = null
    private var track: LocalVideoTrack? = null
    private var visible by mutableStateOf(true)
    private var viewportHeight by mutableStateOf(620.dp)
    private var compact by mutableStateOf(false)
    private var rootView: View? = null
    private val capturer = CornerFrameCapturer()
    private var muteClicks = 0

    @Before
    fun useTheSameWindowLayoutAsTheCallActivity() {
        compose.runOnUiThread {
            // The generic test activity inherits a platform action bar; the real call activity
            // draws edge to edge without it. Avoid testing a renderer hidden by that fixture bar.
            compose.activity.actionBar?.hide()
            compose.activity.enableEdgeToEdge()
        }
    }

    @After
    fun releaseNativeFixture() {
        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        compose.runOnIdle {
            track?.stopCapture()
            track?.dispose()
            room?.release()
        }
    }

    @Test
    fun global_call_bar_consumes_safe_insets_once_and_returns_to_the_exact_call() {
        var showBar by mutableStateOf(true)
        val insets = WindowInsets(left = 12.dp, top = 24.dp, right = 18.dp, bottom = 0.dp)
        val call = ActiveCallPresence("fixture-call", "Fixture peer", emptyList(), null, false, null)
        var returnedCall: String? = null
        compose.setContent {
            KitWalletTheme {
                if (visible) Box(Modifier.fillMaxSize().testTag("fixture-root")) {
                    OngoingSessionLayout(
                        hasBars = showBar,
                        safeInsets = insets,
                        bars = { ActiveCallMiniBar(call) { returnedCall = call.callId } },
                    ) {
                        Column(Modifier.windowInsetsPadding(insets)) {
                            Box(Modifier.fillMaxWidth().height(48.dp).testTag("screen-header"))
                        }
                    }
                }
            }
        }
        val root = compose.onNodeWithTag("fixture-root").fetchSemanticsNode().boundsInRoot
        val bar = compose.onNodeWithText("Fixture peer").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val header = compose.onNodeWithTag("screen-header").fetchSemanticsNode().boundsInRoot
        with(compose.density) {
            assertEquals(root.top + 24.dp.toPx(), bar.top, 1f)
            assertEquals(root.left + 12.dp.toPx(), bar.left, 1f)
            assertEquals(root.right - 18.dp.toPx(), bar.right, 1f)
            assertTrue(bar.height >= 48.dp.toPx())
        }
        assertEquals("The screen header must directly follow the bar", bar.bottom, header.top, 1f)
        saveScreenshot("call-banner-insets")
        compose.onNodeWithText("Fixture peer").performClick()
        compose.runOnIdle { assertEquals(call.callId, returnedCall); showBar = false }
        val restored = compose.onNodeWithTag("screen-header").fetchSemanticsNode().boundsInRoot
        assertEquals("The child resumes owning the top inset when the bar closes", bar.top, restored.top, 1f)
    }

    @Test
    fun full_shared_frame_stays_above_accessible_controls_after_viewport_resize() {
        showSharedScreen()
        assertCompleteSharedFrame()
        assertShareAboveControls()
        saveScreenshot("shared-portrait")
        compose.onNodeWithText("Mute").performClick()
        compose.runOnIdle { assertEquals(1, muteClicks); viewportHeight = 450.dp }
        assertCompleteSharedFrame()
        assertShareAboveControls()
        saveScreenshot("shared-short")
        compose.onNodeWithContentDescription("Switch camera").assertDoesNotExist()
    }

    @Test
    fun compact_shared_frame_keeps_all_corners_without_controls_or_self_preview() {
        compact = true
        viewportHeight = 260.dp
        showSharedScreen()
        assertCompleteSharedFrame()
        compose.onNodeWithText("Mute").assertDoesNotExist()
        compose.onNodeWithText("End").assertDoesNotExist()
        compose.onNodeWithContentDescription("Switch camera").assertDoesNotExist()
        saveScreenshot("shared-compact")
    }

    private fun showSharedScreen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.runOnIdle {
            room = LiveKit.create(context)
            track = room!!.localParticipant.createVideoTrack("generated-screen", capturer)
            track!!.startCapture()
        }
        compose.setContent {
            KitWalletTheme {
                rootView = LocalView.current.rootView
                if (visible) Box(Modifier.size(width = 360.dp, height = viewportHeight).testTag("call-viewport")) {
                    ActiveCallContent(
                        state = ActiveCallUiState(
                            name = "Fixture peer", phase = CallPhase.CONNECTED, video = true,
                            cameraEnabled = true, localVideoTrack = track,
                            remoteParticipants = listOf(RemoteCallParticipant(
                                "peer", "Fixture peer", videoTrack = track, screenSharing = true,
                            )),
                        ),
                        room = room!!, compact = compact,
                        onMute = { muteClicks++ }, onSpeaker = {}, onCamera = {}, onFlip = {},
                        onSwitchToVideo = {}, onDeclineWaiting = {}, onMergeWaiting = {},
                        onAddParticipant = {}, onOpenChat = {}, canOpenChat = true,
                        openingChat = false, onToggleScreenShare = {}, onAccept = {},
                        onDecline = {}, onRetry = {}, onEnd = {},
                    )
                }
            }
        }
    }

    private fun assertShareAboveControls() {
        val shared = compose.onNodeWithContentDescription("Fixture peer's shared screen")
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val mute = compose.onNodeWithText("Mute").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val share = compose.onNodeWithText("Share").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("The complete shared frame must remain above both rows of controls", shared.bottom <= share.top)
        with(compose.density) {
            assertTrue(mute.width >= 48.dp.toPx() && mute.height >= 48.dp.toPx())
            assertTrue("A short viewport must still leave useful height for the share", shared.height >= 48.dp.toPx())
        }
    }

    private fun assertCompleteSharedFrame() {
        compose.waitUntil(10_000) {
            capturer.emit()
            var frameBounds: Rect? = null
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val renderers = rootView?.let(::renderers).orEmpty()
                val renderer = renderers.singleOrNull()
                if (renderer != null && renderer.height > 0 &&
                    abs(renderer.width.toFloat() / renderer.height - 16f / 9f) < 0.025f
                ) {
                    val visibleBounds = Rect()
                    val location = IntArray(2)
                    renderer.getLocationOnScreen(location)
                    val bounds = Rect(location[0], location[1],
                        location[0] + renderer.width, location[1] + renderer.height)
                    if (renderer.getGlobalVisibleRect(visibleBounds) && visibleBounds == bounds) frameBounds = bounds
                }
            }
            frameBounds?.let { bounds ->
                val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                hasAllCorners(screenshot, bounds)
            } == true
        }
        compose.runOnIdle {
            val renderer = renderers(requireNotNull(rootView)).single()
            assertEquals("The native renderer must fit the 16:9 frame", 16f / 9f,
                renderer.width.toFloat() / renderer.height, 0.025f)
        }
    }

    private fun renderers(view: View): List<TextureViewRenderer> = when (view) {
        is TextureViewRenderer -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { renderers(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun hasAllCorners(bitmap: Bitmap, bounds: Rect): Boolean {
        if (bounds.left < 0 || bounds.top < 0 || bounds.right > bitmap.width || bounds.bottom > bitmap.height) {
            bitmap.recycle()
            return false
        }
        val points = listOf(0.04f to 0.04f, 0.96f to 0.04f, 0.04f to 0.96f, 0.96f to 0.96f)
        val levels = points.map { (x, y) -> Color.red(bitmap.getPixel(
            bounds.left + (x * (bounds.width() - 1)).toInt(),
            bounds.top + (y * (bounds.height() - 1)).toInt(),
        )) }
        bitmap.recycle()
        return levels[0] < 45 && levels[1] in 70..125 && levels[2] in 160..210 && levels[3] > 225
    }

    private fun saveScreenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = requireNotNull(instrumentation.targetContext.getExternalFilesDir("call-layout-evidence"))
        val screenshot = compose.onRoot().captureToImage().asAndroidBitmap()
        File(directory, "$name.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
    }

    private class CornerFrameCapturer : VideoCapturer {
        private var observer: CapturerObserver? = null
        override fun initialize(helper: SurfaceTextureHelper?, context: Context?, observer: CapturerObserver?) {
            this.observer = observer
        }
        override fun startCapture(width: Int, height: Int, framerate: Int) { observer?.onCapturerStarted(true) }
        override fun stopCapture() { observer?.onCapturerStopped() }
        override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) = Unit
        override fun dispose() { observer = null }
        override fun isScreencast() = true

        fun emit() {
            val target = observer ?: return
            val buffer = JavaI420Buffer.allocate(640, 360)
            for (y in 0 until 360) for (x in 0 until 640) {
                val corner = x < 64 || x >= 576
                val luminance = when {
                    corner && y < 36 -> if (x < 64) 35 else 100
                    corner && y >= 324 -> if (x < 64) 180 else 235
                    else -> 128
                }
                buffer.dataY.put(y * buffer.strideY + x, luminance.toByte())
            }
            for (y in 0 until 180) for (x in 0 until 320) {
                buffer.dataU.put(y * buffer.strideU + x, 128.toByte())
                buffer.dataV.put(y * buffer.strideV + x, 128.toByte())
            }
            val frame = VideoFrame(buffer, 0, System.nanoTime())
            target.onFrameCaptured(frame)
            frame.release()
        }
    }
}
