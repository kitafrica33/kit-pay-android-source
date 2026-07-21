package com.kit.wallet

import com.kit.wallet.data.repository.EncryptedMessagingUnavailableRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EncryptedMessagingUnavailableRepositoryTest {
    @Test
    fun `production fallback never exposes or accepts plaintext messages`() = runTest {
        val repository = EncryptedMessagingUnavailableRepository()

        assertFalse(repository.readiness.value)
        assertTrue(repository.chats.value.isEmpty())
        assertTrue(repository.conversation("unknown").value.isEmpty())
        val failure = runCatching { repository.sendMessage("unknown", "plaintext") }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
    }
}
