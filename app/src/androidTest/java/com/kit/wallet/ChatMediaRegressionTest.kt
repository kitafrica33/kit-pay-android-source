package com.kit.wallet

import android.os.Build
import android.media.AudioManager
import android.media.MediaPlayer
import android.app.Notification
import android.app.NotificationManager
import android.provider.MediaStore
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.kit.wallet.data.media.decodeVideoFrame
import com.kit.wallet.data.messaging.SecureMediaCache
import com.kit.wallet.data.messaging.SecureMediaFile
import com.kit.wallet.data.messaging.SecureMediaLease
import com.kit.wallet.feature.chat.ChatVideoPlayer
import com.kit.wallet.feature.chat.VoiceNotePlayer
import com.kit.wallet.feature.chat.VoiceNotePlaybackContext
import com.kit.wallet.data.session.SessionTokens
import com.kit.wallet.feature.chat.saveGalleryMedia
import com.kit.wallet.feature.chat.camera.ChatVideoTranscoder
import com.kit.wallet.ui.model.Message
import com.kit.wallet.ui.theme.KitWalletTheme
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertNotEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class ChatMediaRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun retained_notification_controls_cannot_control_a_replacement_sessions_playback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(
                    "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS",
                ),
            ).use { it.readBytes() }
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        val application = context.applicationContext as KitApplication
        val first = SessionTokens("a", "a", "notification-a", accountId = "account-a")
        val second = SessionTokens("b", "b", "notification-b", accountId = "account-b")
        val sessions = MutableStateFlow<SessionTokens?>(first)
        val source = audioFixture()
        fun notification(speaker: String): Notification? = manager.activeNotifications
            .map { it.notification }
            .firstOrNull { it.extras.getString(Notification.EXTRA_TITLE) == speaker && it.actions?.size == 3 }
        compose.setContent { Text("Notification ownership regression") }
        try {
            compose.runOnIdle {
                VoiceNotePlayer.bindToSession(sessions)
                VoiceNotePlayer.toggle(context, "shared-note", source,
                    VoiceNotePlaybackContext(conversationId = "shared-group", speaker = "Account A", sessionOwner = first.fence()))
            }
            compose.waitUntil(5_000) { notification("Account A") != null }
            val oldNotification = requireNotNull(notification("Account A"))
            val oldToggle = oldNotification.actions[1].actionIntent
            val oldStop = oldNotification.deleteIntent
            compose.runOnIdle {
                sessions.value = second
                VoiceNotePlayer.toggle(context, "shared-note", source,
                    VoiceNotePlaybackContext(conversationId = "shared-group", speaker = "Account B", sessionOwner = second.fence()))
            }
            compose.waitUntil(5_000) { notification("Account B") != null }
            val newNotification = requireNotNull(notification("Account B"))
            val newToggle = newNotification.actions[1].actionIntent
            val newStop = newNotification.deleteIntent
            assertNotEquals(oldToggle, newToggle)
            assertNotEquals(oldStop, newStop)
            oldToggle.send()
            oldStop.send()
            // Delivery of B's following command also proves the older service commands have
            // been processed. B must pause, not disappear or resume after a stale A toggle.
            newToggle.send()
            compose.waitUntil(5_000) { VoiceNotePlayer.state.value.isPaused }
            compose.runOnIdle {
                assertEquals(second.fence(), VoiceNotePlayer.state.value.playing?.context?.sessionOwner)
            }
            newStop.send()
            compose.waitUntil(5_000) { VoiceNotePlayer.state.value.playing == null }
        } finally {
            compose.runOnIdle { VoiceNotePlayer.bindToSession(application.sessions.get().session) }
            source.delete()
        }
    }

    @Test
    fun replaced_session_callbacks_cannot_stop_or_hide_the_new_owners_native_voice_player() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val application = context.applicationContext as KitApplication
        val first = SessionTokens("a", "a", "session-a", accountId = "account-a")
        val second = SessionTokens("b", "b", "session-b", accountId = "account-b")
        val sessions = MutableStateFlow<SessionTokens?>(first)
        val source = audioFixture()
        compose.setContent { Text("Voice player ownership regression") }
        try {
            compose.runOnIdle {
                VoiceNotePlayer.bindToSession(sessions)
                val oldContext = VoiceNotePlaybackContext(conversationId = "shared-group", sessionOwner = first.fence())
                VoiceNotePlayer.toggle(context, "shared-note", source, oldContext)
                val playerField = VoiceNotePlayer::class.java.getDeclaredField("player").apply { isAccessible = true }
                val oldPlayer = playerField.get(VoiceNotePlayer) as MediaPlayer
                assertTrue(oldPlayer.isPlaying)
                // Retain callbacks just as the platform may retain an already queued event.
                val completion = MediaPlayer::class.java.getDeclaredField("mOnCompletionListener")
                    .apply { isAccessible = true }.get(oldPlayer) as MediaPlayer.OnCompletionListener
                val error = MediaPlayer::class.java.getDeclaredField("mOnErrorListener")
                    .apply { isAccessible = true }.get(oldPlayer) as MediaPlayer.OnErrorListener
                val focus = VoiceNotePlayer::class.java.getDeclaredField("focusListener")
                    .apply { isAccessible = true }.get(VoiceNotePlayer) as AudioManager.OnAudioFocusChangeListener

                sessions.value = second
                val newContext = oldContext.copy(sessionOwner = second.fence())
                VoiceNotePlayer.toggle(context, "shared-note", source, newContext)
                val newPlayer = playerField.get(VoiceNotePlayer) as MediaPlayer
                assertTrue(newPlayer.isPlaying)
                assertTrue(newPlayer !== oldPlayer)
                VoiceNotePlayer.toggle(context, "shared-note", source, oldContext)
                completion.onCompletion(oldPlayer)
                error.onError(oldPlayer, MediaPlayer.MEDIA_ERROR_UNKNOWN, 0)
                focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
                VoiceNotePlayer.noteSourceVisibility(false, "shared-note", first.fence())
                assertSame(newPlayer, playerField.get(VoiceNotePlayer))
                assertTrue(newPlayer.isPlaying)
                assertEquals(second.fence(), VoiceNotePlayer.state.value.playing?.context?.sessionOwner)
                assertTrue(VoiceNotePlayer.state.value.isSourceOnScreen)
                VoiceNotePlayer.noteSourceVisibility(false, "shared-note", second.fence())
                assertFalse(VoiceNotePlayer.state.value.isSourceOnScreen)
                sessions.value = second.copy(accessToken = "refreshed-b")
                assertTrue(newPlayer.isPlaying)
            }
            compose.runOnIdle { sessions.value = null }
            compose.waitUntil(5_000) { VoiceNotePlayer.state.value.playing == null }
            compose.runOnIdle {
                VoiceNotePlayer.toggle(context, "shared-note", source,
                    VoiceNotePlaybackContext(conversationId = "shared-group", sessionOwner = second.fence()))
                assertNull(VoiceNotePlayer.state.value.playing)
            }
        } finally {
            compose.runOnIdle { VoiceNotePlayer.bindToSession(application.sessions.get().session) }
            source.delete()
        }
    }

    @Test
    fun four_k_video_poster_and_trim_filmstrip_decode_to_bounded_bitmaps() {
        val source = fixture()
        try {
            val bitmap = decodeVideoFrame(source, 100_000, 160)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
                assertNull(bitmap)
                return
            }
            assertNotNull(bitmap)
            bitmap!!
            assertTrue(bitmap.width <= 160 && bitmap.height <= 160)
            assertTrue(bitmap.allocationByteCount <= 160 * 160 * 4)
            bitmap.recycle()
            assertNotNull(ChatVideoTranscoder.posterFrame(source, 100, 160))
        } finally {
            source.delete()
        }
    }

    @Test
    fun corrupt_video_uses_the_retry_callback_without_a_platform_error_dialog() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File.createTempFile("bad-video-", ".mp4", context.cacheDir)
        source.writeText("not a video")
        var failed by mutableStateOf(false)
        try {
            assertNull(decodeVideoFrame(source, 0))
            compose.setContent {
                KitWalletTheme {
                    if (failed) Text("Video could not be played") else ChatVideoPlayer(
                        source, onCompleted = {}, onError = { failed = true },
                    )
                }
            }
            compose.waitUntil(5_000) { failed }
        } finally {
            source.delete()
        }
    }

    @Test
    fun playback_lease_keeps_video_readable_when_cache_eviction_is_attempted() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val root = SecureMediaCache.privateDirectoryFor(context.filesDir).apply { mkdirs() }
        val source = File.createTempFile("lease-test-", ".mp4", root)
        instrumentation.context.assets.open("media/playback.mp4").use { input ->
            source.outputStream().use { output -> input.copyTo(output) }
        }
        val lease = SecureMediaLease.forPlayback(context, SecureMediaFile(source, "video/mp4", source.length()))
        var completed by mutableStateOf(false)
        var failed by mutableStateOf(false)
        try {
            // Some Android SELinux policies reject even app-private hard links. Exercise the
            // cache's real deletion path so its marker fallback can keep that source pinned.
            val deleted = SecureMediaLease.deleteIfNotPinned(source) { source.delete() }
            assertEquals(lease.file != source, deleted)
            assertTrue(lease.file.exists())
            compose.setContent {
                KitWalletTheme {
                    if (!completed && !failed) ChatVideoPlayer(
                        lease.file, onCompleted = { completed = true }, onError = { failed = true },
                    )
                }
            }
            compose.waitUntil(10_000) { completed || failed }
            assertTrue("The leased video must finish playback", completed && !failed)
        } finally {
            compose.runOnIdle { completed = true }
            compose.waitForIdle()
            lease.close()
            source.delete()
        }
    }

    @Test
    fun failed_gallery_export_removes_its_pending_public_entry() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val source = File(context.cacheDir, "missing-$id")
        val message = Message(id = id, text = "", time = "", fromMe = false)
        val result = runCatching { saveGalleryMedia(context, message, source) }
        assertTrue(result.isFailure)
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf("KitPay-${id.take(8)}.jpg"),
            null,
        )!!.use { cursor -> assertEquals(0, cursor.count) }
    }

    private fun fixture(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        return File.createTempFile("poster-", ".mp4", instrumentation.targetContext.cacheDir).also { target ->
            instrumentation.context.assets.open("media/poster-4k.mp4").use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }

    private fun audioFixture(): File {
        val dataSize = 8_000 * 2 * 6
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(8_000).putInt(16_000)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(dataSize).array()
        return File.createTempFile("voice-owner-", ".wav", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
            .also { file -> file.outputStream().use { it.write(header); it.write(ByteArray(dataSize)) } }
    }
}
