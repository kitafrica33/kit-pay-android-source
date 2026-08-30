package com.kit.wallet

import com.kit.wallet.data.session.SessionFence
import com.kit.wallet.worker.observeForegroundWalletRefreshes
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundWalletRefreshCoordinatorTest {
    @Test
    fun `only a genuine foreground transition refreshes and successful refreshes are throttled`() =
        runTest {
            val foregrounded = MutableStateFlow(false)
            val session = MutableStateFlow<SessionFence?>(SESSION_A)
            var now = 1_000L
            var refreshes = 0
            backgroundScope.launch {
                observeForegroundWalletRefreshes(
                    foregrounded = foregrounded,
                    sessionFences = session,
                    currentSession = { session.value },
                    nowMillis = { now },
                    minimumRefreshIntervalMillis = 10_000L,
                    refresh = { refreshes++ },
                    waitBeforeRetry = { delay(it) },
                )
            }
            runCurrent()

            foregrounded.value = true
            runCurrent()
            assertEquals(1, refreshes)

            // StateFlow does not create a second lifecycle edge for repeated resume noise.
            foregrounded.value = true
            runCurrent()
            assertEquals(1, refreshes)

            foregrounded.value = false
            runCurrent()
            now += 5_000L
            foregrounded.value = true
            runCurrent()
            assertEquals("a quick foreground bounce is throttled", 1, refreshes)

            foregrounded.value = false
            runCurrent()
            now += 5_000L
            foregrounded.value = true
            runCurrent()
            assertEquals(2, refreshes)

            // A replacement login is never hidden behind the previous account's throttle.
            session.value = SESSION_B
            runCurrent()
            assertEquals(3, refreshes)
        }

    @Test
    fun `a transient foreground failure retries while the exact session remains current`() =
        runTest {
            val foregrounded = MutableStateFlow(false)
            val session = MutableStateFlow<SessionFence?>(SESSION_A)
            var attempts = 0
            backgroundScope.launch {
                observeForegroundWalletRefreshes(
                    foregrounded = foregrounded,
                    sessionFences = session,
                    currentSession = { session.value },
                    nowMillis = { testScheduler.currentTime },
                    refresh = {
                        attempts++
                        if (attempts == 1) throw IOException("radio changed networks")
                    },
                    waitBeforeRetry = { delay(it) },
                )
            }
            runCurrent()

            foregrounded.value = true
            runCurrent()
            assertEquals(1, attempts)

            advanceTimeBy(1_000L)
            runCurrent()
            assertEquals(2, attempts)
        }

    @Test
    fun `session replacement cancels the obsolete refresh before its successor starts`() = runTest {
        val foregrounded = MutableStateFlow(false)
        val session = MutableStateFlow<SessionFence?>(SESSION_A)
        val firstEntered = CompletableDeferred<Unit>()
        val refreshedSessions = mutableListOf<SessionFence>()
        var inFlight = 0
        var maximumInFlight = 0
        backgroundScope.launch {
            observeForegroundWalletRefreshes(
                foregrounded = foregrounded,
                sessionFences = session,
                currentSession = { session.value },
                nowMillis = { testScheduler.currentTime },
                refresh = {
                    val owner = checkNotNull(session.value)
                    refreshedSessions += owner
                    inFlight++
                    maximumInFlight = maxOf(maximumInFlight, inFlight)
                    try {
                        if (owner == SESSION_A) {
                            firstEntered.complete(Unit)
                            awaitCancellation()
                        }
                    } finally {
                        inFlight--
                    }
                },
                waitBeforeRetry = { delay(it) },
            )
        }
        runCurrent()

        foregrounded.value = true
        firstEntered.await()
        session.value = SESSION_B
        runCurrent()

        assertEquals(listOf(SESSION_A, SESSION_B), refreshedSessions)
        assertEquals(1, maximumInFlight)
        assertEquals(0, inFlight)
    }

    private companion object {
        val SESSION_A = SessionFence("session-a", "scope-a", "user-a")
        val SESSION_B = SessionFence("session-b", "scope-b", "user-b")
    }
}
