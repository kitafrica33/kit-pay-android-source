package com.kit.wallet

import com.kit.wallet.data.session.ProfileSetupState
import com.kit.wallet.data.session.PendingRestorationDiscardTarget
import com.kit.wallet.data.session.SessionFence
import com.kit.wallet.data.session.SessionKeyTemporarilyUnavailableException
import com.kit.wallet.data.session.SessionTokens
import com.kit.wallet.data.session.activateMessagingForPublishedSession
import com.kit.wallet.data.session.fenceThenEraseMessagingAndClearSession
import com.kit.wallet.data.session.pendingRestorationDiscardTarget
import com.kit.wallet.data.session.resolveSessionKey
import com.kit.wallet.data.session.resetMessagingStateForPublishedSession
import com.kit.wallet.data.session.restoreRetainingEncryptedSession
import com.kit.wallet.data.session.retryPublishedMessagingActivationWithRetries
import com.kit.wallet.data.session.retryRetainedEncryptedSession
import com.kit.wallet.data.session.retryRetainedEncryptedSessionAfterMessagingReset
import com.kit.wallet.data.session.sessionKeyCreationAllowed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class KeystoreSessionRestorationTest {
    @Test
    fun `retry restore finishes messaging activation before propagating cancellation`() = runTest {
        val activationStarted = CompletableDeferred<Unit>()
        val finishActivation = CompletableDeferred<Unit>()
        val observed = CompletableDeferred<Throwable>()
        var stateAvailable = false
        val cancellation = CancellationException("retry restore was cancelled")

        val restoration = launch {
            try {
                activateMessagingForPublishedSession(
                    expected = SESSION.fence(),
                    currentSession = { SESSION.fence() },
                    activate = {
                        activationStarted.complete(Unit)
                        finishActivation.await()
                        stateAvailable = true
                    },
                )
            } catch (error: Throwable) {
                observed.complete(error)
            }
        }

        activationStarted.await()
        restoration.cancel(cancellation)
        finishActivation.complete(Unit)

        assertSame(cancellation, observed.await())
        restoration.join()
        assertTrue(stateAvailable)
    }

    @Test
    fun `regular persist finishes messaging activation before propagating cancellation`() = runTest {
        val activationStarted = CompletableDeferred<Unit>()
        val finishActivation = CompletableDeferred<Unit>()
        val observed = CompletableDeferred<Throwable>()
        val events = mutableListOf<String>()
        val cancellation = CancellationException("session persist was cancelled")

        val persistence = launch {
            try {
                activateMessagingForPublishedSession(
                    expected = SESSION.fence(),
                    currentSession = { SESSION.fence() },
                    activate = {
                        events += "activation-started"
                        activationStarted.complete(Unit)
                        finishActivation.await()
                        events += "state-available"
                    },
                )
            } catch (error: Throwable) {
                events += "cancelled"
                observed.complete(error)
            }
        }

        activationStarted.await()
        persistence.cancel(cancellation)
        finishActivation.complete(Unit)

        assertSame(cancellation, observed.await())
        persistence.join()
        assertEquals(listOf("activation-started", "state-available", "cancelled"), events)
    }

    @Test
    fun `reset reopening survives cancellation without abandoning the authenticated session`() =
        runTest {
            val activationStarted = CompletableDeferred<Unit>()
            val finishActivation = CompletableDeferred<Unit>()
            val observed = CompletableDeferred<Throwable>()
            val cancellation = CancellationException("foreground recovery stopped")
            var sessionPublished = true
            var stateAvailable = false

            val recovery = launch {
                try {
                    activateMessagingForPublishedSession(
                        expected = SESSION.fence(),
                        currentSession = { SESSION.fence().takeIf { sessionPublished } },
                        activate = {
                            activationStarted.complete(Unit)
                            finishActivation.await()
                            stateAvailable = true
                        },
                    )
                } catch (error: Throwable) {
                    observed.complete(error)
                }
            }

            activationStarted.await()
            recovery.cancel(cancellation)
            finishActivation.complete(Unit)

            assertSame(cancellation, observed.await())
            recovery.join()
            assertTrue(sessionPublished)
            assertTrue(stateAvailable)
        }

    @Test
    fun `replacement or logout cannot reopen messaging for the obsolete session`() = runTest {
        val expected = SESSION.fence()
        var current: SessionFence? = null
        var activations = 0

        assertFalse(
            activateMessagingForPublishedSession(
                expected = expected,
                currentSession = { current },
                activate = { activations++ },
            ),
        )

        current = expected.copy(sessionId = "replacement-session")
        assertFalse(
            activateMessagingForPublishedSession(
                expected = expected,
                currentSession = { current },
                activate = { activations++ },
            ),
        )

        assertEquals(0, activations)
    }

    @Test
    fun `caller cancellation wins and retains an activation failure as suppressed`() = runTest {
        val activationStarted = CompletableDeferred<Unit>()
        val finishActivation = CompletableDeferred<Unit>()
        val observed = CompletableDeferred<Throwable>()
        val activationFailure = IllegalStateException("gate activation failed")
        val cancellation = CancellationException("caller stopped")

        val persistence = launch {
            try {
                activateMessagingForPublishedSession(
                    expected = SESSION.fence(),
                    currentSession = { SESSION.fence() },
                    activate = {
                        activationStarted.complete(Unit)
                        finishActivation.await()
                        throw activationFailure
                    },
                )
            } catch (error: Throwable) {
                observed.complete(error)
            }
        }

        activationStarted.await()
        persistence.cancel(cancellation)
        finishActivation.complete(Unit)

        val thrown = observed.await()
        assertSame(cancellation, thrown)
        assertEquals(listOf(activationFailure), thrown.suppressed.toList())
        persistence.join()
    }

    @Test
    fun `activation failure marks the published session retryable and a retry opens it`() = runTest {
        val activationFailure = IllegalStateException("lifecycle gate temporarily failed")
        var retryPending = false
        var attempts = 0

        val observed = runCatching {
            activateMessagingForPublishedSession(
                expected = SESSION.fence(),
                currentSession = { SESSION.fence() },
                activate = {
                    attempts++
                    throw activationFailure
                },
                onActivationFailure = { retryPending = true },
            )
        }.exceptionOrNull()

        assertSame(activationFailure, observed)
        assertTrue(retryPending)

        val activated = activateMessagingForPublishedSession(
            expected = SESSION.fence(),
            currentSession = { SESSION.fence() },
            activate = {
                attempts++
                retryPending = false
            },
            onActivationFailure = { retryPending = true },
        )

        assertTrue(activated)
        assertFalse(retryPending)
        assertEquals(2, attempts)
    }

    @Test
    fun `reset reopen failure retains the authenticated session for retry`() = runTest {
        val activationFailure = IllegalStateException("reset gate failed to reopen")
        var retryPending = false
        val sessionPublished = true

        val observed = runCatching {
            activateMessagingForPublishedSession(
                expected = SESSION.fence(),
                currentSession = { SESSION.fence().takeIf { sessionPublished } },
                activate = { throw activationFailure },
                onActivationFailure = { retryPending = true },
            )
        }.exceptionOrNull()

        assertSame(activationFailure, observed)
        assertTrue(sessionPublished)
        assertTrue(retryPending)
    }

    @Test
    fun `reset reopen retry eventually opens the exact authenticated session`() = runTest {
        val expected = SESSION.fence()
        var current: SessionFence? = expected
        var retryPending = true
        var attempts = 0
        val waits = mutableListOf<Long>()

        val reopened = retryPublishedMessagingActivationWithRetries(
            attempts = 3,
            retryDelayMillis = 250L,
            attemptActivation = {
                if (current != expected) return@retryPublishedMessagingActivationWithRetries null
                attempts++
                if (attempts < 3) {
                    false
                } else {
                    retryPending = false
                    true
                }
            },
            waitBeforeNextAttempt = { waits += it },
        )

        assertTrue(reopened)
        assertFalse(retryPending)
        assertEquals(3, attempts)
        assertEquals(listOf(250L, 500L), waits)

        current = expected.copy(sessionId = "replacement-session")
        val obsoleteReopened = retryPublishedMessagingActivationWithRetries(
            attempts = 3,
            retryDelayMillis = 250L,
            attemptActivation = {
                if (current != expected) null else error("Obsolete activation was retried")
            },
            waitBeforeNextAttempt = { error("Obsolete activation entered backoff") },
        )

        assertFalse(obsoleteReopened)
    }

    @Test
    fun `post-fence messaging erase failure retains session and keeps partial state closed`() = runTest {
        val eraseFailure = IllegalStateException("Android 9 alias deletion failed")
        val events = mutableListOf<String>()
        var resetPending = false
        var current: SessionFence? = SESSION.fence()

        val observed = runCatching {
            resetMessagingStateForPublishedSession(
                expected = SESSION.fence(),
                currentSession = { current },
                persistResetFence = {
                    events += "fence"
                    resetPending = true
                },
                clearResetFence = {
                    events += "clear"
                    resetPending = false
                },
                reset = { onErasureStarted ->
                    events += "reset"
                    onErasureStarted()
                    events += "erase"
                    throw eraseFailure
                },
                reopen = {
                    events += "reopen"
                    true
                },
            )
        }.exceptionOrNull()

        assertSame(eraseFailure, observed)
        assertEquals(SESSION.fence(), current)
        assertTrue(resetPending)
        assertEquals(listOf("reset", "fence", "erase"), events)
    }

    @Test
    fun `successful messaging reset reopens exact session without credential replacement`() =
        runTest {
            val events = mutableListOf<String>()
            val expected = SESSION.fence()
            var resetPending = false
            var current: SessionFence? = expected

            val reset = resetMessagingStateForPublishedSession(
                expected = expected,
                currentSession = { current },
                persistResetFence = {
                    events += "fence"
                    resetPending = true
                },
                clearResetFence = {
                    events += "clear"
                    resetPending = false
                },
                reset = { onErasureStarted ->
                    events += "snapshot"
                    onErasureStarted()
                    events += "erase"
                },
                reopen = {
                    events += "reopen"
                    true
                },
            )

            assertTrue(reset)
            assertEquals(expected, current)
            assertFalse(resetPending)
            assertEquals(listOf("snapshot", "fence", "erase", "clear", "reopen"), events)
        }

    @Test
    fun `failed local reset fence aborts before key or record erasure`() = runTest {
        val markerFailure = IllegalStateException("preferences commit failed")
        val events = mutableListOf<String>()

        val observed = runCatching {
            resetMessagingStateForPublishedSession(
                expected = SESSION.fence(),
                currentSession = { SESSION.fence() },
                persistResetFence = {
                    events += "fence"
                    throw markerFailure
                },
                clearResetFence = { events += "clear" },
                reset = { persistResetFence ->
                    events += "snapshot"
                    persistResetFence()
                    events += "erase"
                },
                reopen = { events += "reopen"; true },
            )
        }.exceptionOrNull()

        assertSame(markerFailure, observed)
        assertEquals(listOf("snapshot", "fence"), events)
    }

    @Test
    fun `process death window after erase remains fenced until durable marker clear`() = runTest {
        val clearFailure = IllegalStateException("marker clear interrupted")
        val events = mutableListOf<String>()
        var resetPending = false

        val observed = runCatching {
            resetMessagingStateForPublishedSession(
                expected = SESSION.fence(),
                currentSession = { SESSION.fence() },
                persistResetFence = {
                    events += "fence"
                    resetPending = true
                },
                clearResetFence = {
                    events += "clear"
                    throw clearFailure
                },
                reset = { persistResetFence ->
                    persistResetFence()
                    events += "erase-key"
                    events += "erase-records"
                },
                reopen = { events += "reopen"; true },
            )
        }.exceptionOrNull()

        assertSame(clearFailure, observed)
        assertTrue(resetPending)
        assertEquals(listOf("fence", "erase-key", "erase-records", "clear"), events)
    }

    @Test
    fun `session replacement during reset is never cleared or reopened as the old owner`() = runTest {
        val expected = SESSION.fence()
        var current: SessionFence? = expected
        var resetPending = false
        val events = mutableListOf<String>()

        val reset = resetMessagingStateForPublishedSession(
            expected = expected,
            currentSession = { current },
            persistResetFence = { resetPending = true },
            clearResetFence = {
                resetPending = false
                events += "clear"
            },
            reset = { persistResetFence ->
                persistResetFence()
                current = expected.copy(sessionId = "replacement-session")
            },
            reopen = { events += "reopen"; true },
        )

        assertFalse(reset)
        assertTrue(resetPending)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `published session with a failed messaging gate can be explicitly discarded`() {
        assertEquals(
            PendingRestorationDiscardTarget.PUBLISHED_CREDENTIAL,
            pendingRestorationDiscardTarget(
                restorationPending = true,
                sessionPublished = true,
            ),
        )
        assertEquals(
            PendingRestorationDiscardTarget.RETAINED_CREDENTIAL,
            pendingRestorationDiscardTarget(
                restorationPending = true,
                sessionPublished = false,
            ),
        )
        assertEquals(
            PendingRestorationDiscardTarget.NONE,
            pendingRestorationDiscardTarget(
                restorationPending = false,
                sessionPublished = true,
            ),
        )
    }

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
    fun `process restart never decodes reset credential before partial state cleanup succeeds`() =
        runTest {
            val events = mutableListOf<String>()

            val retry = retryRetainedEncryptedSessionAfterMessagingReset(
                encryptedSession = "retained-session",
                messagingResetPending = true,
                finishPendingMessagingReset = {
                    events += "erase-key"
                    events += "erase-records-failed"
                    false
                },
                decode = {
                    events += "decode"
                    SESSION
                },
            )

            assertNull(retry.tokens)
            assertTrue(retry.retryRequired)
            assertEquals(listOf("erase-key", "erase-records-failed"), events)
        }

    @Test
    fun `process restart finishes reset then restores the exact retained credential`() = runTest {
        val events = mutableListOf<String>()

        val retry = retryRetainedEncryptedSessionAfterMessagingReset(
            encryptedSession = "retained-session",
            messagingResetPending = true,
            finishPendingMessagingReset = {
                events += "erase-key"
                events += "erase-records"
                events += "clear-marker"
                true
            },
            decode = {
                events += "decode:$it"
                SESSION
            },
        )

        assertEquals(SESSION, retry.tokens)
        assertFalse(retry.retryRequired)
        assertEquals(
            listOf("erase-key", "erase-records", "clear-marker", "decode:retained-session"),
            events,
        )
    }

    @Test
    fun `process restart reset cleanup cancellation preserves fenced credential`() = runTest {
        val cancellation = CancellationException("process recovery cancelled")
        var decodeCalled = false

        val observed = runCatching {
            retryRetainedEncryptedSessionAfterMessagingReset(
                encryptedSession = "retained-session",
                messagingResetPending = true,
                finishPendingMessagingReset = { throw cancellation },
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
