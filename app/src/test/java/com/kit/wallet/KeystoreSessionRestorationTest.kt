package com.kit.wallet

import com.kit.wallet.data.session.ProfileSetupState
import com.kit.wallet.data.session.SessionKeyTemporarilyUnavailableException
import com.kit.wallet.data.session.SessionTokens
import com.kit.wallet.data.session.fenceThenEraseMessagingAndClearSession
import com.kit.wallet.data.session.resolveSessionKey
import com.kit.wallet.data.session.restoreRetainingEncryptedSession
import com.kit.wallet.data.session.retryRetainedEncryptedSession
import com.kit.wallet.data.session.sessionKeyCreationAllowed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class KeystoreSessionRestorationTest {
    @Test
    fun `foreground restoration retries transient failures with a bounded backoff`() = runTest {
        var pending = true
        var attempts = 0
        val waits = mutableListOf<Long>()

        val restored = restoreRetainedSessionWithRetries(
            pending = { pending },
            retryable = { true },
            retry = {
                attempts++
                if (attempts == 3) pending = false
                !pending
            },
            waitBeforeNextAttempt = { waits += it },
        )

        assertTrue(restored)
        assertEquals(3, attempts)
        assertEquals(listOf(250L, 500L), waits)
    }

    @Test
    fun `foreground restoration leaves permanent failure for explicit recovery`() = runTest {
        var attempts = 0

        val restored = restoreRetainedSessionWithRetries(
            pending = { true },
            retryable = { false },
            retry = {
                attempts++
                false
            },
            waitBeforeNextAttempt = {},
        )

        assertFalse(restored)
        assertEquals(0, attempts)
    }

    @Test
    fun `transient keystore read failure retains the credential for foreground retry`() = runTest {
        val encryptedCredential = "iv.ciphertext"
        val decodeInputs = mutableListOf<String>()
        var keystoreAvailable = false
        val decode: (String) -> SessionTokens = { encrypted ->
            decodeInputs += encrypted
            if (!keystoreAvailable) throw SessionKeyTemporarilyUnavailableException()
            SESSION
        }

        val initial = restoreRetainingEncryptedSession(
            encryptedSession = encryptedCredential,
            messagingErasurePending = false,
            decode = decode,
        )

        assertNull(initial.tokens)
        assertTrue(initial.retryRequired)

        keystoreAvailable = true
        val retry = retryRetainedEncryptedSession(
            encryptedSession = encryptedCredential,
            messagingErasurePending = false,
            finishPendingMessagingErasure = { error("Cleanup is not pending") },
            decode = decode,
        )

        assertEquals(SESSION, retry.tokens)
        assertFalse(retry.retryRequired)
        assertEquals(listOf(encryptedCredential, encryptedCredential), decodeInputs)
    }

    @Test
    fun `corrupt encrypted payload requires explicit recovery instead of endless retry`() {
        val restore = restoreRetainingEncryptedSession(
            encryptedSession = "malformed-ciphertext",
            messagingErasurePending = false,
            decode = { throw IllegalArgumentException("Invalid encrypted session") },
        )

        assertNull(restore.tokens)
        assertFalse(restore.retryRequired)
        assertTrue(restore.recoveryRequired)
        assertTrue(restore.pending)
    }

    @Test
    fun `fatal restore errors are never hidden as retryable state`() {
        val fatal = AssertionError("fatal")

        val observed = runCatching {
            restoreRetainingEncryptedSession(
                encryptedSession = "ciphertext",
                messagingErasurePending = false,
                decode = { throw fatal },
            )
        }.exceptionOrNull()

        assertSame(fatal, observed)
    }

    @Test
    fun `failed durable erasure fence aborts before messaging cleanup suspension`() = runTest {
        val events = mutableListOf<String>()
        val markerFailure = IllegalStateException("preferences commit failed")

        val observed = runCatching {
            fenceThenEraseMessagingAndClearSession(
                persistErasureFence = {
                    events += "fence"
                    throw markerFailure
                },
                eraseMessaging = { events += "erase" },
                clearSession = { events += "clear" },
            )
        }.exceptionOrNull()

        assertSame(markerFailure, observed)
        assertEquals(listOf("fence"), events)
    }

    @Test
    fun `durable erasure fence precedes messaging cleanup and credential clear`() = runTest {
        val events = mutableListOf<String>()

        fenceThenEraseMessagingAndClearSession(
            persistErasureFence = { events += "fence" },
            eraseMessaging = { events += "erase" },
            clearSession = { succeeded -> events += "clear:$succeeded" },
        )

        assertEquals(listOf("fence", "erase", "clear:true"), events)
    }

    @Test
    fun `failed pending messaging cleanup cannot delete or decode the retained credential`() = runTest {
        var decodeCalled = false

        val retry = retryRetainedEncryptedSession(
            encryptedSession = "retained-session",
            messagingErasurePending = true,
            finishPendingMessagingErasure = { false },
            decode = {
                decodeCalled = true
                SESSION
            },
        )

        assertNull(retry.tokens)
        assertTrue(retry.retryRequired)
        assertFalse(decodeCalled)
    }

    @Test
    fun `pending cleanup cancellation propagates without decoding the fenced credential`() = runTest {
        val cancellation = CancellationException("activity stopped")
        var decodeCalled = false

        val observed = runCatching {
            retryRetainedEncryptedSession(
                encryptedSession = "fenced-session",
                messagingErasurePending = true,
                finishPendingMessagingErasure = { throw cancellation },
                decode = {
                    decodeCalled = true
                    SESSION
                },
            )
        }.exceptionOrNull()

        assertSame(cancellation, observed)
        assertFalse(decodeCalled)
    }

    @Test
    fun `successful pending cleanup cannot resurrect the fenced Kit session`() = runTest {
        val events = mutableListOf<String>()

        val retry = retryRetainedEncryptedSession(
            encryptedSession = "retained-session",
            messagingErasurePending = true,
            finishPendingMessagingErasure = {
                events += "cleanup"
                true
            },
            decode = {
                events += "decode:$it"
                SESSION
            },
        )

        assertNull(retry.tokens)
        assertFalse(retry.retryRequired)
        assertEquals(listOf("cleanup"), events)
    }

    @Test
    fun `pending erasure without a credential still completes messaging cleanup`() = runTest {
        var cleanupCalled = false
        var decodeCalled = false

        val retry = retryRetainedEncryptedSession(
            encryptedSession = null,
            messagingErasurePending = true,
            finishPendingMessagingErasure = {
                cleanupCalled = true
                true
            },
            decode = {
                decodeCalled = true
                SESSION
            },
        )

        assertNull(retry.tokens)
        assertFalse(retry.retryRequired)
        assertTrue(cleanupCalled)
        assertFalse(decodeCalled)
    }

    @Test
    fun `failed decrypt key lookup never generates a replacement alias`() {
        var creations = 0

        val failure = runCatching {
            resolveSessionKey(
                allowCreation = false,
                loadExisting = { null },
                createNew = {
                    creations++
                    "replacement-key"
                },
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(0, creations)
    }

    @Test
    fun `same session refresh never replaces a temporarily hidden Android 9 key`() {
        var creations = 0

        val failure = runCatching {
            resolveSessionKey(
                allowCreation = sessionKeyCreationAllowed(
                    hasEncryptedSession = true,
                    hasCurrentSession = true,
                ),
                loadExisting = { null },
                createNew = {
                    creations++
                    "replacement-key"
                },
            )
        }.exceptionOrNull()

        assertTrue(failure is SessionKeyTemporarilyUnavailableException)
        assertEquals(0, creations)
    }

    @Test
    fun `retained ciphertext without a restored session never permits key replacement`() {
        assertFalse(
            sessionKeyCreationAllowed(
                hasEncryptedSession = true,
                hasCurrentSession = false,
            ),
        )
    }

    @Test
    fun `genuinely new login may create a session key`() {
        var creations = 0

        val key = resolveSessionKey(
            allowCreation = sessionKeyCreationAllowed(
                hasEncryptedSession = false,
                hasCurrentSession = false,
            ),
            loadExisting = { null },
            createNew = {
                creations++
                "new-key"
            },
        )

        assertEquals("new-key", key)
        assertEquals(1, creations)
    }

    private companion object {
        val SESSION = SessionTokens(
            accessToken = "access-token",
            refreshToken = "refresh-token",
            sessionId = "session-id",
            accountId = "account-id",
            cacheScopeId = "cache-scope",
            profileSetupState = ProfileSetupState.COMPLETED,
        )
    }
}
