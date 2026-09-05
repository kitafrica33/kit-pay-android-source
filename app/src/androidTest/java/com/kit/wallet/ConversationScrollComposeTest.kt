package com.kit.wallet

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kit.wallet.feature.chat.ConversationContent
import com.kit.wallet.feature.chat.VoiceNoteDrafts
import com.kit.wallet.data.session.SessionTokens
import com.kit.wallet.ui.model.ChatPreview
import com.kit.wallet.ui.model.Message
import com.kit.wallet.ui.theme.KitWalletTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow

class ConversationScrollComposeTest {
    @get:Rule val compose = createComposeRule()
    private var messages by mutableStateOf<List<Message>>(emptyList())
    private var height by mutableStateOf(620.dp)
    private var mediaEnabled by mutableStateOf(false)
    private var editTarget by mutableStateOf<Message?>(null)
    private val originalSession = SessionTokens(
        "fixture-access-a", "fixture-refresh-a", "session-a",
        accountId = "00000000-0000-0000-0000-000000000001",
    )
    private val sessions = MutableStateFlow<SessionTokens?>(originalSession)
    private var owner by mutableStateOf(originalSession.fence())
    private var conversationShown by mutableStateOf(true)
    private var groupChat by mutableStateOf(false)
    private var cameraOpens = 0

    @Before
    fun bindFixtureSession() {
        compose.runOnIdle { VoiceNoteDrafts.bindToSession(sessions) }
    }

    @After
    fun restoreApplicationSession() {
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as KitApplication
        compose.runOnIdle { VoiceNoteDrafts.bindToSession(application.sessions.get().session) }
    }

    @Test
    fun editing_a_message_disables_the_camera_pull_like_other_attachment_controls() {
        messages = history(0, 2)
        mediaEnabled = true
        editTarget = messages.first()
        showConversation()
        compose.onNodeWithTag("conversation-messages").performTouchInput { swipeUp() }
        compose.runOnIdle { assertEquals(0, cameraOpens) }
        compose.onNodeWithText("Release to open camera").assertDoesNotExist()
        compose.onNodeWithText("Pull further to open camera").assertDoesNotExist()
        compose.runOnIdle { height = 480.dp }
        compose.runOnIdle { height = 620.dp }
        compose.onNodeWithTag("conversation-messages").performTouchInput { swipeUp() }
        compose.runOnIdle { assertEquals(0, cameraOpens) }
    }

    @Test
    fun recording_a_voice_draft_disables_the_camera_pull_until_discarded() {
        grantMicrophone()
        messages = history(0, 2)
        mediaEnabled = true
        showConversation()
        compose.onNodeWithContentDescription("Record a voice note").performClick()
        try {
            compose.onNodeWithContentDescription("Pause recording").assertIsDisplayed()
            compose.onNodeWithTag("conversation-messages").performTouchInput { swipeUp() }
            compose.runOnIdle { assertEquals(0, cameraOpens) }
            compose.onNodeWithText("Release to open camera").assertDoesNotExist()
        } finally {
            compose.onNodeWithContentDescription("Discard recording").performClick()
        }
        compose.onNodeWithTag("conversation-messages").performTouchInput { swipeUp() }
        compose.waitUntil(5_000) { cameraOpens == 1 }
    }

    @Test
    fun a_shared_group_restores_only_the_current_sessions_voice_draft() {
        grantMicrophone()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        messages = history(0, 2)
        mediaEnabled = true
        groupChat = true
        showConversation()
        val oldRecorder = compose.runOnIdle { VoiceNoteDrafts.recorder("chat", owner, context) }
        compose.onNodeWithContentDescription("Record a voice note").performClick()
        compose.waitUntil(5_000) { oldRecorder.elapsedMillis() >= 1_200 }
        compose.onNodeWithContentDescription("Pause recording").performClick()
        val oldFiles = compose.runOnIdle { oldRecorder.previewFiles() }
        assertTrue(oldFiles.isNotEmpty() && oldFiles.all { it.isFile })

        compose.runOnIdle { conversationShown = false }
        compose.runOnIdle {
            sessions.value = originalSession.copy(accessToken = "refreshed-access-a")
            conversationShown = true
        }
        compose.onNodeWithText("Paused").assertIsDisplayed()

        // A different login opens the exact same group ID. It must see a fresh composer.
        val replacement = SessionTokens(
            "fixture-access-b", "fixture-refresh-b", "session-b",
            accountId = "00000000-0000-0000-0000-000000000002",
        )
        compose.runOnIdle {
            sessions.value = replacement
            owner = replacement.fence()
        }
        compose.onNodeWithText("Paused").assertDoesNotExist()
        compose.onNodeWithContentDescription("Record a voice note").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(oldFiles.none { it.exists() })
            assertTrue(runCatching { oldRecorder.start() }.isFailure)
            assertTrue(runCatching { oldRecorder.resume() }.isFailure)
            assertFalse(oldRecorder.hasDraft)
        }

        val newRecorder = compose.runOnIdle { VoiceNoteDrafts.recorder("chat", owner, context) }
        compose.onNodeWithContentDescription("Record a voice note").performClick()
        compose.waitUntil(5_000) { newRecorder.elapsedMillis() >= 1_200 }
        compose.onNodeWithContentDescription("Pause recording").performClick()
        // A late disposal/result from A must not delete B's new draft or its restoration entry.
        compose.runOnIdle { VoiceNoteDrafts.release("chat", originalSession.fence(), oldRecorder) }
        compose.runOnIdle { conversationShown = false }
        compose.runOnIdle { conversationShown = true }
        compose.onNodeWithText("Paused").assertIsDisplayed()
        compose.onNodeWithContentDescription("Listen to the draft").performClick()
        compose.onNodeWithContentDescription("Stop listening").assertIsDisplayed()
        val newFiles = compose.runOnIdle { newRecorder.previewFiles() }
        // Keep the old UI owner mounted: application-level retirement must still stop preview
        // and delete the files without depending on navigation or a screen's disposal callback.
        compose.runOnIdle { sessions.value = null }
        compose.onNodeWithContentDescription("Stop listening").assertDoesNotExist()
        compose.onNodeWithText("Paused").assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(newFiles.isNotEmpty() && newFiles.none { it.exists() })
            assertTrue(runCatching { newRecorder.start() }.isFailure)
        }
    }

    @Test
    fun short_chat_camera_pull_shows_both_hints_and_opens_once_on_release() {
        messages = history(0, 2)
        mediaEnabled = true
        showConversation()
        val pixelsPerDp = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        compose.onNodeWithTag("conversation-messages").performTouchInput {
            down(center)
            moveBy(Offset(0f, -80f * pixelsPerDp))
        }
        compose.onNodeWithText("Pull further to open camera").assertIsDisplayed()
        retainScreenshot("camera-pull-further.png")
        compose.onNodeWithTag("conversation-messages").performTouchInput {
            moveBy(Offset(0f, -80f * pixelsPerDp))
        }
        compose.onNodeWithText("Release to open camera").assertIsDisplayed()
        retainScreenshot("camera-pull-release.png")
        compose.runOnIdle { assertEquals(0, cameraOpens) }
        compose.onNodeWithTag("conversation-messages").performTouchInput { up() }
        compose.waitUntil(5_000) { cameraOpens == 1 }
        compose.runOnIdle { messages = history(0, 4) }
        compose.runOnIdle { assertEquals(1, cameraOpens) }
    }

    @Test
    fun cancelling_an_armed_camera_pull_does_not_launch() {
        messages = history(0, 2)
        mediaEnabled = true
        showConversation()
        val pixelsPerDp = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        compose.onNodeWithTag("conversation-messages").performTouchInput {
            down(center)
            moveBy(Offset(0f, -160f * pixelsPerDp))
        }
        compose.onNodeWithText("Release to open camera").assertIsDisplayed()
        compose.onNodeWithTag("conversation-messages").performTouchInput { cancel() }
        compose.runOnIdle { assertEquals(0, cameraOpens) }
        compose.onNodeWithText("Release to open camera").assertDoesNotExist()
    }

    @Test
    fun viewport_change_cancels_an_armed_pull_but_allows_a_new_deliberate_gesture() {
        messages = history(0, 2)
        mediaEnabled = true
        showConversation()
        val pixelsPerDp = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        compose.onNodeWithTag("conversation-messages").performTouchInput {
            down(center)
            moveBy(Offset(0f, -160f * pixelsPerDp))
        }
        compose.onNodeWithText("Release to open camera").assertIsDisplayed()
        // Models a keyboard/inset transition while the finger is still down.
        compose.runOnIdle { height = 480.dp }
        compose.onNodeWithText("Release to open camera").assertDoesNotExist()
        compose.onNodeWithTag("conversation-messages").performTouchInput { up() }
        compose.runOnIdle { assertEquals(0, cameraOpens) }
        // Stable keyboard visibility is not itself a prohibition on a fresh pull.
        compose.onNodeWithTag("conversation-tail").assertIsDisplayed()
        compose.onNodeWithTag("conversation-messages").performTouchInput { swipeUp() }
        compose.waitUntil(5_000) { cameraOpens == 1 }
    }

    @Test
    fun retracting_below_the_camera_threshold_before_release_does_not_launch() {
        messages = history(0, 2)
        mediaEnabled = true
        showConversation()
        val pixelsPerDp = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        compose.onNodeWithTag("conversation-messages").performTouchInput {
            down(center)
            moveBy(Offset(0f, -160f * pixelsPerDp))
        }
        compose.onNodeWithText("Release to open camera").assertIsDisplayed()
        compose.onNodeWithTag("conversation-messages").performTouchInput {
            moveBy(Offset(0f, 100f * pixelsPerDp))
        }
        compose.onNodeWithText("Pull further to open camera").assertIsDisplayed()
        compose.onNodeWithTag("conversation-messages").performTouchInput { up() }
        compose.runOnIdle { assertEquals(0, cameraOpens) }
    }

    @Test
    fun ordinary_history_scrolling_and_viewport_changes_never_open_camera() {
        messages = history(0, 100)
        mediaEnabled = true
        showConversation()
        compose.onNodeWithTag("conversation-messages").performTouchInput { swipeDown() }
        compose.onNodeWithTag("conversation-messages").performTouchInput { swipeUp() }
        compose.runOnIdle { height = 380.dp }
        compose.runOnIdle { assertEquals(0, cameraOpens) }
    }

    @Test
    fun preloaded_and_async_history_open_at_the_tail_and_stay_there_when_viewport_shrinks() {
        messages = history(20, 80)
        showConversation()
        compose.onNodeWithTag("conversation-tail").assertIsDisplayed()
        compose.runOnIdle { messages = history(0, 100) }
        compose.onNodeWithTag("conversation-tail").assertIsDisplayed()
        // This is the same LazyColumn viewport change produced by showing the keyboard.
        compose.runOnIdle { height = 380.dp }
        compose.onNodeWithTag("conversation-tail").assertIsDisplayed()
    }

    @Test
    fun scrolling_into_history_is_not_undone_by_incoming_batches() {
        messages = history(0, 100)
        showConversation()
        compose.onNodeWithTag("conversation-tail").assertIsDisplayed()
        compose.onNodeWithTag("conversation-messages").performTouchInput { swipeDown() }
        compose.onNodeWithTag("conversation-tail").assertIsNotDisplayed()
        compose.runOnIdle { messages = history(0, 110) }
        compose.onNodeWithTag("conversation-tail").assertIsNotDisplayed()
        compose.onNodeWithText("10 new messages").assertIsDisplayed()
    }

    @Test
    fun a_quote_jump_before_any_drag_remains_on_its_target_after_layout_hydration() {
        messages = history(0, 100) + Message(
            id = "reply", text = "Latest reply", time = "10:00", fromMe = true,
            replyToMessageId = "message-0", replyToText = "Jump to original", replyToSenderName = "Peer",
        )
        showConversation()
        compose.onNodeWithText("Jump to original").performClick()
        compose.onNodeWithText("Message 0").assertIsDisplayed()
        compose.runOnIdle {
            messages = messages.map { if (it.id == "message-1") it.copy(text = "Hydrated\n".repeat(20)) else it }
        }
        compose.onNodeWithText("Message 0").assertIsDisplayed()
        compose.onNodeWithTag("conversation-tail").assertIsNotDisplayed()
    }

    private fun history(first: Int, end: Int) = (first until end).map { index ->
        Message(id = "message-$index", text = "Message $index", time = "10:00", fromMe = index % 2 == 0)
    }

    private fun retainScreenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        try {
            java.io.File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun grantMicrophone() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(
                "pm grant ${instrumentation.targetContext.packageName} android.permission.RECORD_AUDIO",
            ),
        ).use { it.readBytes() }
    }

    private fun showConversation() {
        compose.setContent {
            KitWalletTheme {
                Box(Modifier.fillMaxWidth().height(height)) {
                    if (conversationShown) ConversationContent(
                        chat = ChatPreview("chat", "Peer", "", "10:00", isGroup = groupChat),
                        mediaPlaybackOwner = owner,
                        messages = messages,
                        onBack = {}, onVoiceCall = {}, onVideoCall = {},
                        sending = false, retryingMessageId = null, error = null,
                        onClearError = {}, onSend = { _, _ -> }, onRetry = { _, _ -> },
                        mediaEnabled = mediaEnabled,
                        sendEnabled = true,
                        editTarget = editTarget,
                        onOpenCamera = { cameraOpens++ },
                    )
                }
            }
        }
    }
}
