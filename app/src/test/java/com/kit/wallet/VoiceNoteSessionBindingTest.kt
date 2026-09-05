package com.kit.wallet

import com.kit.wallet.data.session.SessionTokens
import com.kit.wallet.feature.chat.VoiceNoteSessionBinding
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceNoteSessionBindingTest {
    @Test
    fun `token refresh preserves playback but logout immediately invalidates it`() = runTest {
        val login = login("first")
        val sessions = MutableStateFlow<SessionTokens?>(login)
        val binding = VoiceNoteSessionBinding(sessions)
        var stops = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { binding.watch { stops++ } }
        assertTrue(binding.claim(login.fence()))
        sessions.value = login.copy(accessToken = "rotated", refreshToken = "rotated-refresh")
        assertTrue(binding.ownsCurrentSession())
        assertEquals(0, stops)
        sessions.value = null
        assertFalse(binding.ownsCurrentSession())
        assertEquals(1, stops)
        assertFalse(binding.claim(login.fence()))
    }

    @Test
    fun `replacement login cannot inherit or restart the old conversations note`() = runTest {
        val first = login("first")
        val second = login("second")
        val sessions = MutableStateFlow<SessionTokens?>(first)
        val binding = VoiceNoteSessionBinding(sessions)
        var stops = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { binding.watch { stops++ } }
        assertTrue(binding.claim(first.fence()))
        sessions.value = second
        assertEquals(1, stops)
        assertFalse(binding.ownsCurrentSession())
        assertFalse(binding.claim(first.fence()))
        assertFalse(binding.claim(null))
        assertTrue(binding.claim(second.fence()))
        binding.clear()
        assertFalse(binding.ownsCurrentSession())
    }

    private fun login(id: String) = SessionTokens("access-$id", "refresh-$id", id, accountId = "same-account")
}
