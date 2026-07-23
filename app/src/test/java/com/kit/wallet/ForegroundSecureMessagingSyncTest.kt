package com.kit.wallet

import com.kit.wallet.data.messaging.SecureMessagingCryptographicFailureException
import com.kit.wallet.data.messaging.SecureMessagingQuarantineReason
import com.kit.wallet.data.remote.KitWalletApiException
import com.kit.wallet.data.session.SessionFence
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundSecureMessagingSyncTest {
    @Test
    fun `session saved while activity remains foreground starts immediate synchronization`() =
        runTest {
            val sessionFences = MutableStateFlow<SessionFence?>(null)
            var currentSession: SessionFence? = null
            var scheduleCalls = 0
            val synchronizedSessions = mutableListOf<SessionFence?>()
            backgroundScope.launch {
                observeForegroundSecureMessagingSessions(
                    sessionFences = sessionFences,
                    serializationMutex = Mutex(),
                    currentSession = { currentSession },
                    engineReady = true,
                    schedule = { scheduleCalls++ },
                    synchronize = { synchronizedSessions += currentSession },
                    waitBeforeNextAttempt = { delay(it) },
                )
            }
            runCurrent()

            currentSession = SESSION_A
            sessionFences.value = SESSION_A
            runCurrent()

            assertEquals(1, scheduleCalls)
            assertEquals(listOf(SESSION_A), synchronizedSessions)
        }

    @Test
    fun `credential refresh with the same session fence does not duplicate foreground sync`() =
        runTest {
            val sessionFences = MutableSharedFlow<SessionFence?>(extraBufferCapacity = 2)
            var currentSession: SessionFence? = SESSION_A
            var synchronizeCalls = 0
            backgroundScope.launch {
                observeForegroundSecureMessagingSessions(
                    sessionFences = sessionFences,
                    serializationMutex = Mutex(),
                    currentSession = { currentSession },
                    engineReady = true,
                    schedule = {},
                    synchronize = { synchronizeCalls++ },
                    waitBeforeNextAttempt = { delay(it) },
                )
            }
            runCurrent()

            sessionFences.emit(SESSION_A)
            runCurrent()
            currentSession = SESSION_A.copy()
            sessionFences.emit(SESSION_A.copy())
            runCurrent()

            assertEquals(1, synchronizeCalls)
        }

    @Test
    fun `session flow replacement cancels obsolete sync before starting the successor`() =
        runTest {
            val sessionFences = MutableSharedFlow<SessionFence?>(extraBufferCapacity = 2)
            var currentSession: SessionFence? = SESSION_A
            val firstEntered = CompletableDeferred<Unit>()
            val firstCancelled = CompletableDeferred<Unit>()
            val successorCompleted = CompletableDeferred<Unit>()
            val synchronizedSessions = mutableListOf<SessionFence>()
            backgroundScope.launch {
                observeForegroundSecureMessagingSessions(
                    sessionFences = sessionFences,
                    serializationMutex = Mutex(),
                    currentSession = { currentSession },
                    engineReady = true,
                    schedule = {},
                    synchronize = {
                        when (val owner = checkNotNull(currentSession)) {
                            SESSION_A -> {
                                synchronizedSessions += owner
                                firstEntered.complete(Unit)
                                try {
                                    awaitCancellation()
                                } finally {
                                    firstCancelled.complete(Unit)
                                }
                            }
                            SESSION_B -> {
                                synchronizedSessions += owner
                                successorCompleted.complete(Unit)
                            }
                            else -> error("Unexpected foreground session")
                        }
                    },
                    waitBeforeNextAttempt = { delay(it) },
                )
            }
            runCurrent()

            sessionFences.emit(SESSION_A)
            firstEntered.await()
            currentSession = SESSION_B
            sessionFences.emit(SESSION_B)
            firstCancelled.await()
            successorCompleted.await()

            assertEquals(listOf(SESSION_A, SESSION_B), synchronizedSessions)
        }

    @Test
    fun `scheduler failure cannot prevent immediate foreground synchronization`() = runTest {
        var synchronizeCalls = 0

        val synchronized = scheduleAndSynchronizeForegroundSecureMessaging(
            expectedSession = SESSION_A,
            currentSession = { SESSION_A },
            engineReady = true,
            schedule = { throw IllegalStateException("WorkManager database unavailable") },
            synchronize = { synchronizeCalls++ },
            waitBeforeNextAttempt = { delay(it) },
        )

        assertTrue(synchronized)
        assertEquals(1, synchronizeCalls)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun `foreground sync succeeds on its fourth immediate-session attempt`() = runTest {
        var synchronizeCalls = 0

        val synchronized = synchronizeForegroundSecureMessagingWithRetries(
            expectedSession = SESSION_A,
            currentSession = { SESSION_A },
            engineReady = true,
            synchronize = {
                synchronizeCalls++
                if (synchronizeCalls < 4) throw IOException("Android Keystore is still opening")
            },
            waitBeforeNextAttempt = { delay(it) },
        )

        assertTrue(synchronized)
        assertEquals(4, synchronizeCalls)
        assertEquals(15_000L, testScheduler.currentTime)
    }

    @Test
    fun `foreground Android 9 recovery continues beyond the first retry cycle`() = runTest {
        var synchronizeCalls = 0

        val synchronized = synchronizeForegroundSecureMessagingWithRetries(
            expectedSession = SESSION_A,
            currentSession = { SESSION_A },
            engineReady = true,
            synchronize = {
                synchronizeCalls++
                if (synchronizeCalls < 5) {
                    throw IOException("Android 9 provider remains temporarily unavailable")
                }
            },
            waitBeforeNextAttempt = { delay(it) },
        )

        assertTrue(synchronized)
        assertEquals(5, synchronizeCalls)
        assertEquals(20_000L, testScheduler.currentTime)
    }

    @Test
    fun `permanent foreground failure stops after the bounded first cycle`() = runTest {
        var synchronizeCalls = 0

        val synchronized = synchronizeForegroundSecureMessagingWithRetries(
            expectedSession = SESSION_A,
            currentSession = { SESSION_A },
            engineReady = true,
            synchronize = {
                synchronizeCalls++
                throw IllegalStateException("permanent protocol invariant")
            },
            waitBeforeNextAttempt = { delay(it) },
        )

        assertFalse(synchronized)
        assertEquals(4, synchronizeCalls)
        assertEquals(15_000L, testScheduler.currentTime)
    }

    @Test
    fun `retryable foreground failures back off exponentially after the first cycle`() = runTest {
        var synchronizeCalls = 0

        val synchronized = synchronizeForegroundSecureMessagingWithRetries(
            expectedSession = SESSION_A,
            currentSession = { SESSION_A },
            engineReady = true,
            synchronize = {
                synchronizeCalls++
                if (synchronizeCalls < 7) throw IOException("temporary provider outage")
            },
            waitBeforeNextAttempt = { delay(it) },
        )

        assertTrue(synchronized)
        assertEquals(7, synchronizeCalls)
        assertEquals(50_000L, testScheduler.currentTime)
    }

    @Test
    fun `foreground retry classification excludes permanent API and invariant failures`() {
        assertTrue(isRetryableForegroundSecureMessagingFailure(IOException("offline")))
        assertTrue(
            isRetryableForegroundSecureMessagingFailure(
                KitWalletApiException("BUSY", "busy", statusCode = 429),
            ),
        )
        assertTrue(
            isRetryableForegroundSecureMessagingFailure(
                KitWalletApiException("UPSTREAM", "upstream", statusCode = 503),
            ),
        )
        assertFalse(
            isRetryableForegroundSecureMessagingFailure(
                KitWalletApiException("INVALID", "invalid", statusCode = 400),
            ),
        )
        assertFalse(
            isRetryableForegroundSecureMessagingFailure(
                IllegalArgumentException("malformed state"),
            ),
        )
        assertFalse(
            isRetryableForegroundSecureMessagingFailure(
                SecureMessagingCryptographicFailureException(
                    quarantineReason = SecureMessagingQuarantineReason.STATE_UNAVAILABLE,
                    message = "authenticated state failed",
                    cause = IOException("nested transport-looking cause"),
                ),
            ),
        )
    }

    @Test
    fun `session replacement cancels retries for the captured foreground owner`() = runTest {
        var currentSession: SessionFence? = SESSION_A
        var synchronizeCalls = 0

        val synchronized = synchronizeForegroundSecureMessagingWithRetries(
            expectedSession = SESSION_A,
            currentSession = { currentSession },
            engineReady = true,
            synchronize = {
                synchronizeCalls++
                currentSession = SESSION_B
                throw IOException("obsolete session attempt")
            },
            waitBeforeNextAttempt = { delay(it) },
        )

        assertFalse(synchronized)
        assertEquals(1, synchronizeCalls)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun `foreground sync is a no-op without an authenticated session`() = runTest {
        var synchronizeCalls = 0

        val synchronized = synchronizeForegroundSecureMessagingWithRetries(
            expectedSession = null,
            currentSession = { null },
            engineReady = true,
            synchronize = { synchronizeCalls++ },
            waitBeforeNextAttempt = { delay(it) },
        )

        assertFalse(synchronized)
        assertEquals(0, synchronizeCalls)
        assertEquals(0L, testScheduler.currentTime)
    }

    private companion object {
        val SESSION_A = SessionFence(
            sessionId = "session-a",
            cacheScopeId = "scope-a",
            accountId = "11111111-1111-4111-8111-111111111111",
        )
        val SESSION_B = SessionFence(
            sessionId = "session-b",
            cacheScopeId = "scope-b",
            accountId = "22222222-2222-4222-8222-222222222222",
        )
    }
}
