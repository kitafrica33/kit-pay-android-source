package com.kit.wallet

import androidx.lifecycle.SavedStateHandle
import com.kit.wallet.data.auth.AuthChallengeKind
import com.kit.wallet.data.auth.AuthOutcome
import com.kit.wallet.data.auth.AuthRepository
import com.kit.wallet.data.auth.AuthenticatedUser
import com.kit.wallet.data.auth.PendingAuthChallenge
import com.kit.wallet.data.auth.PhoneAuthCapabilities
import com.kit.wallet.data.remote.KitWalletApiException
import com.kit.wallet.data.session.ProfileSetupState
import com.kit.wallet.data.session.SessionFence
import com.kit.wallet.data.session.SessionSnapshot
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.data.time.BootSessionIdProvider
import com.kit.wallet.data.time.ElapsedRealtimeClock
import com.kit.wallet.feature.auth.AuthViewModel
import java.io.IOException
import java.lang.reflect.Proxy
import java.time.Instant
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelOtpTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `rapid OTP taps reserve one request before the coroutine starts`() {
        val repository = PhoneOtpRepository()
        val clock = MutableElapsedRealtimeClock(1_000_000L)
        val viewModel = viewModel(repository, clock)
        var navigations = 0

        viewModel.requestPhoneOtp("0700000002") { navigations += 1 }
        viewModel.requestPhoneOtp("0700000002") { navigations += 1 }

        assertTrue(viewModel.uiState.value.loading)
        assertEquals(0, repository.requestCount)

        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, repository.requestCount)
        assertEquals(1, navigations)
        assertFalse(viewModel.uiState.value.loading)
        assertEquals(CHALLENGE_ID, viewModel.uiState.value.pendingChallenge?.id)
    }

    @Test
    fun `resend countdown blocks early and repeated taps while preserving challenge identity`() {
        val repository = PhoneOtpRepository()
        val clock = MutableElapsedRealtimeClock(1_000_000L)
        val savedState = SavedStateHandle()
        val viewModel = viewModel(repository, clock, savedState)

        viewModel.requestPhoneOtp("0700000002") {}
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.resendPhoneOtp()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, repository.requestCount)

        clock.advanceSeconds(60)
        viewModel.resendPhoneOtp()
        viewModel.resendPhoneOtp()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, repository.requestCount)
        assertEquals(CHALLENGE_ID, viewModel.uiState.value.pendingChallenge?.id)
        assertTrue(viewModel.uiState.value.notice.orEmpty().contains("same code"))
        assertEquals(null, savedState.get<String>(STATE_RESEND_IN_FLIGHT_CHALLENGE_ID))
    }

    @Test
    fun `resend rejects a replacement challenge and prevents stale route verification`() {
        val repository = PhoneOtpRepository(
            challengeIds = mutableListOf(CHALLENGE_ID, REPLACEMENT_CHALLENGE_ID),
        )
        val clock = MutableElapsedRealtimeClock(1_000_000L)
        val savedState = SavedStateHandle()
        val viewModel = viewModel(repository, clock, savedState)

        viewModel.requestPhoneOtp("0700000002") {}
        dispatcher.scheduler.advanceUntilIdle()
        clock.advanceSeconds(60)
        viewModel.resendPhoneOtp()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(null, viewModel.uiState.value.pendingChallenge)
        assertTrue(viewModel.uiState.value.error.orEmpty().contains("Start again"))
        assertTrue(viewModel.uiState.value.error.orEmpty().contains("latest message"))
        assertTrue(savedState.keys().none { it.startsWith("auth.phone_otp.") })

        viewModel.verifyCode("123456") { _, _ -> }
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(null, repository.verifiedCode)
        assertEquals(2, repository.requestCount)
    }

    @Test
    fun `restoration fails closed while a resend response is unresolved`() {
        val repository = PhoneOtpRepository()
        val clock = MutableElapsedRealtimeClock(1_000_000L)
        val savedState = SavedStateHandle()
        val original = viewModel(repository, clock, savedState)
        original.requestPhoneOtp("0700000002") {}
        dispatcher.scheduler.advanceUntilIdle()

        clock.advanceSeconds(60)
        repository.delayNextPhoneRequest = true
        original.resendPhoneOtp()
        dispatcher.scheduler.runCurrent()

        assertTrue(repository.phoneRequestPending)
        assertEquals(
            CHALLENGE_ID,
            savedState.get<String>(STATE_RESEND_IN_FLIGHT_CHALLENGE_ID),
        )
        val restoredSavedState = SavedStateHandle(
            savedState.keys().associateWith { key -> savedState.get<Any?>(key) },
        )
        val restored = viewModel(repository, clock, restoredSavedState)

        assertEquals(null, restored.uiState.value.pendingChallenge)
        assertTrue(restored.uiState.value.error.orEmpty().contains("Start again"))
        assertTrue(restored.uiState.value.error.orEmpty().contains("latest message"))
        assertTrue(restoredSavedState.keys().none { it.startsWith("auth.phone_otp.") })

        repository.completePhoneRequest()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(CHALLENGE_ID, original.uiState.value.pendingChallenge?.id)
    }

    @Test
    fun `ambiguous resend failure clears the challenge and requires a restart`() {
        val repository = PhoneOtpRepository()
        val clock = MutableElapsedRealtimeClock(1_000_000L)
        val savedState = SavedStateHandle()
        val viewModel = viewModel(repository, clock, savedState)
        viewModel.requestPhoneOtp("0700000002") {}
        dispatcher.scheduler.advanceUntilIdle()

        clock.advanceSeconds(60)
        repository.nextPhoneRequestFailure = IOException("response was not received")
        viewModel.resendPhoneOtp()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(null, viewModel.uiState.value.pendingChallenge)
        assertTrue(viewModel.uiState.value.error.orEmpty().contains("Start again"))
        assertTrue(viewModel.uiState.value.error.orEmpty().contains("latest message"))
        assertFalse(viewModel.uiState.value.loading)
        assertTrue(savedState.keys().none { it.startsWith("auth.phone_otp.") })
    }

    @Test
    fun `cancelled resend clears the ambiguous challenge and requires a restart`() {
        val repository = PhoneOtpRepository()
        val clock = MutableElapsedRealtimeClock(1_000_000L)
        val savedState = SavedStateHandle()
        val viewModel = viewModel(repository, clock, savedState)
        viewModel.requestPhoneOtp("0700000002") {}
        dispatcher.scheduler.advanceUntilIdle()

        clock.advanceSeconds(60)
        repository.nextPhoneRequestFailure = CancellationException("request cancelled")
        viewModel.resendPhoneOtp()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(null, viewModel.uiState.value.pendingChallenge)
        assertTrue(viewModel.uiState.value.error.orEmpty().contains("Start again"))
        assertTrue(viewModel.uiState.value.error.orEmpty().contains("latest message"))
        assertFalse(viewModel.uiState.value.loading)
        assertTrue(savedState.keys().none { it.startsWith("auth.phone_otp.") })
    }

    @Test
    fun `server relative lifetime ignores a badly skewed device wall clock`() {
        val repository = PhoneOtpRepository(
            expiresAtEpochSeconds = 1L,
            challengeLifetimesMillis = mutableListOf(300_000L),
        )
        val clock = MutableElapsedRealtimeClock(42_000L)
        val viewModel = viewModel(repository, clock)

        viewModel.requestPhoneOtp("0700000002") {}
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            342_000L,
            viewModel.uiState.value.challengeExpiresAtElapsedRealtimeMillis,
        )
        assertEquals(1L, viewModel.uiState.value.pendingChallenge?.expiresAtEpochSeconds)
    }

    @Test
    fun `active phone challenge restores with canonical phone and current session revision`() {
        val fence = SessionFence("session-id", "cache-scope", "account-id")
        val repository = PhoneOtpRepository(
            currentSnapshot = SessionSnapshot(revision = 7L, fence = fence),
        )
        val clock = MutableElapsedRealtimeClock(1_000_000L)
        val savedState = SavedStateHandle()
        val original = viewModel(repository, clock, savedState)

        original.requestPhoneOtp("0700000002") {}
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(savedState.keys().isNotEmpty())
        assertTrue(savedState.keys().all { it.startsWith("auth.phone_otp.") })

        // Session revisions are process-local and restart at zero. The stable, non-secret fence
        // still has to match before the challenge may be rebound to the new process revision.
        repository.currentSnapshot = SessionSnapshot(revision = 0L, fence = fence)
        val restored = viewModel(repository, clock, savedState)

        assertEquals(CHALLENGE_ID, restored.uiState.value.pendingChallenge?.id)
        assertEquals("+256700000002", restored.uiState.value.pendingPhone)
        assertEquals(
            repository.currentSnapshot,
            restored.uiState.value.pendingChallenge?.expectedSession,
        )
        assertEquals(
            clock.millis() + 60_000L,
            restored.uiState.value.resendNotBeforeElapsedRealtimeMillis,
        )
        assertTrue(
            restored.uiState.value.challengeExpiresAtElapsedRealtimeMillis!! > clock.millis(),
        )
    }

    @Test
    fun `restored challenge remains verifiable without saving the entered OTP`() {
        val repository = PhoneOtpRepository()
        val clock = MutableElapsedRealtimeClock(1_000_000L)
        val savedState = SavedStateHandle()
        val original = viewModel(repository, clock, savedState)
        original.requestPhoneOtp("0700000002") {}
        dispatcher.scheduler.advanceUntilIdle()
        val restored = viewModel(repository, clock, savedState)
        var authenticated = false

        restored.verifyCode("123456") { _, _ -> authenticated = true }

        assertTrue(savedState.keys().none { it.contains("code", ignoreCase = true) })
        assertTrue(
            savedState.keys().none { key -> savedState.get<Any?>(key) == "123456" },
        )
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(authenticated)
        assertEquals("123456", repository.verifiedCode)
        assertEquals("+256700000002", repository.verifiedPhone)
        assertTrue(savedState.keys().none { it.startsWith("auth.phone_otp.") })
        assertEquals(null, restored.uiState.value.pendingChallenge)
    }

    @Test
    fun `expired saved challenge is discarded instead of restored`() {
        val repository = PhoneOtpRepository()
        val clock = MutableElapsedRealtimeClock(1_000_000L)
        val savedState = SavedStateHandle()
        val original = viewModel(repository, clock, savedState)
        original.requestPhoneOtp("0700000002") {}
        dispatcher.scheduler.advanceUntilIdle()

        clock.advanceSeconds(301)
        val restored = viewModel(repository, clock, savedState)

        assertEquals(null, restored.uiState.value.pendingChallenge)
        assertTrue(savedState.keys().none { it.startsWith("auth.phone_otp.") })
    }

    @Test
    fun `later uptime after reboot still rejects a saved monotonic deadline`() {
        val repository = PhoneOtpRepository()
        val originalClock = MutableElapsedRealtimeClock(60_000L)
        val boot = MutableBootSessionIdProvider(bootId = 41L)
        val savedState = SavedStateHandle()
        val original = viewModel(repository, originalClock, savedState, boot)
        original.requestPhoneOtp("0700000002") {}
        dispatcher.scheduler.advanceUntilIdle()

        // A short reboot can produce a later uptime that would pass the old monotonic-only check.
        boot.bootId = 42L
        val restored = viewModel(
            repository = repository,
            clock = MutableElapsedRealtimeClock(120_000L),
            savedStateHandle = savedState,
            bootSessionIdProvider = boot,
        )

        assertEquals(null, restored.uiState.value.pendingChallenge)
        assertTrue(savedState.keys().none { it.startsWith("auth.phone_otp.") })
    }

    @Test
    fun `saved challenge fails closed when boot identity becomes unavailable`() {
        val repository = PhoneOtpRepository()
        val clock = MutableElapsedRealtimeClock(60_000L)
        val boot = MutableBootSessionIdProvider(bootId = 7L)
        val savedState = SavedStateHandle()
        val original = viewModel(repository, clock, savedState, boot)
        original.requestPhoneOtp("0700000002") {}
        dispatcher.scheduler.advanceUntilIdle()

        boot.bootId = null
        val restored = viewModel(repository, clock, savedState, boot)

        assertEquals(null, restored.uiState.value.pendingChallenge)
        assertTrue(savedState.keys().none { it.startsWith("auth.phone_otp.") })
    }

    @Test
    fun `saved challenge is discarded when its session fence no longer matches`() {
        val repository = PhoneOtpRepository(
            currentSnapshot = SessionSnapshot(
                revision = 3L,
                fence = SessionFence("old-session", "old-cache", "account-id"),
            ),
        )
        val clock = MutableElapsedRealtimeClock(1_000_000L)
        val savedState = SavedStateHandle()
        val original = viewModel(repository, clock, savedState)
        original.requestPhoneOtp("0700000002") {}
        dispatcher.scheduler.advanceUntilIdle()

        repository.currentSnapshot = SessionSnapshot(
            revision = 0L,
            fence = SessionFence("new-session", "new-cache", "account-id"),
        )
        val restored = viewModel(repository, clock, savedState)

        assertEquals(null, restored.uiState.value.pendingChallenge)
        assertTrue(savedState.keys().none { it.startsWith("auth.phone_otp.") })
    }

    @Test
    fun `unusable restored route is rejected without clearing a newer active challenge`() {
        val repository = PhoneOtpRepository()
        val clock = MutableElapsedRealtimeClock(1_000_000L)
        val emptyRoute = viewModel(repository, clock)

        assertTrue(emptyRoute.clearUnavailableChallenge(routeChallengeId = null))
        assertEquals(
            "This verification request expired. Start again.",
            emptyRoute.uiState.value.error,
        )

        val activeRoute = viewModel(repository, clock)
        activeRoute.requestPhoneOtp("0700000002") {}
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(activeRoute.clearUnavailableChallenge(routeChallengeId = "older-challenge"))
        assertEquals(CHALLENGE_ID, activeRoute.uiState.value.pendingChallenge?.id)
        assertFalse(activeRoute.clearUnavailableChallenge(routeChallengeId = CHALLENGE_ID))
        assertEquals(CHALLENGE_ID, activeRoute.uiState.value.pendingChallenge?.id)
    }

    @Test
    fun `verification completing after expiry cannot erase a replacement challenge`() {
        val repository = PhoneOtpRepository(
            challengeIds = mutableListOf(CHALLENGE_ID, REPLACEMENT_CHALLENGE_ID),
        ).apply {
            delayVerification = true
        }
        val clock = MutableElapsedRealtimeClock(1_000_000L)
        val viewModel = viewModel(repository, clock)
        var authenticated = false

        viewModel.requestPhoneOtp("0700000002") {}
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.verifyCode("123456") { _, _ -> authenticated = true }
        dispatcher.scheduler.runCurrent()
        assertTrue(repository.verificationPending)

        clock.advanceSeconds(301)
        viewModel.clearPendingChallenge()
        assertEquals(null, viewModel.uiState.value.pendingChallenge)
        viewModel.requestPhoneOtp("0700000002") {}
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(REPLACEMENT_CHALLENGE_ID, viewModel.uiState.value.pendingChallenge?.id)

        repository.completeVerification()
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(authenticated)
        assertEquals(REPLACEMENT_CHALLENGE_ID, viewModel.uiState.value.pendingChallenge?.id)
        assertFalse(viewModel.uiState.value.loading)
    }

    @Test
    fun `expiry waits for an admitted verification and blocks replacement login`() {
        val repository = PhoneOtpRepository().apply {
            delayVerification = true
        }
        val clock = MutableElapsedRealtimeClock(1_000_000L)
        val viewModel = viewModel(repository, clock)
        var authenticated = false
        viewModel.requestPhoneOtp("0700000002") {}
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.verifyCode("123456") { _, _ -> authenticated = true }
        dispatcher.scheduler.runCurrent()

        clock.advanceSeconds(301)
        assertFalse(viewModel.clearUnavailableChallenge(CHALLENGE_ID))
        viewModel.requestPhoneOtp("0700000002") {}
        assertEquals(1, repository.requestCount)

        repository.completeVerification()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(authenticated)
        assertEquals(null, viewModel.uiState.value.pendingChallenge)
    }

    @Test
    fun `nonterminal verification failure after expiry clears the unusable route`() {
        val repository = PhoneOtpRepository().apply {
            delayVerification = true
        }
        val clock = MutableElapsedRealtimeClock(1_000_000L)
        val viewModel = viewModel(repository, clock)
        viewModel.requestPhoneOtp("0700000002") {}
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.verifyCode("123456") { _, _ -> }
        dispatcher.scheduler.runCurrent()

        clock.advanceSeconds(301)
        assertFalse(viewModel.clearUnavailableChallenge(CHALLENGE_ID))
        repository.failVerification(IOException("temporary verification failure"))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(null, viewModel.uiState.value.pendingChallenge)
        assertEquals(
            "Kit Pay could not connect. Check your internet and try again.",
            viewModel.uiState.value.error,
        )
        assertFalse(viewModel.uiState.value.loading)
    }

    @Test
    fun `eligible resend crossing old expiry publishes its renewed challenge`() {
        val repository = PhoneOtpRepository(
            challengeIds = mutableListOf(CHALLENGE_ID, CHALLENGE_ID),
            challengeLifetimesMillis = mutableListOf(61_000L, 300_000L),
        )
        val clock = MutableElapsedRealtimeClock(1_000_000L)
        val viewModel = viewModel(repository, clock)
        viewModel.requestPhoneOtp("0700000002") {}
        dispatcher.scheduler.advanceUntilIdle()

        clock.advanceSeconds(60)
        repository.delayNextPhoneRequest = true
        viewModel.resendPhoneOtp()
        dispatcher.scheduler.runCurrent()
        assertTrue(repository.phoneRequestPending)

        clock.advanceSeconds(2)
        assertFalse(viewModel.clearUnavailableChallenge(CHALLENGE_ID))
        assertTrue(viewModel.uiState.value.loading)

        repository.completePhoneRequest()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(CHALLENGE_ID, viewModel.uiState.value.pendingChallenge?.id)
        assertEquals(
            clock.millis() + 300_000L,
            viewModel.uiState.value.challengeExpiresAtElapsedRealtimeMillis,
        )
        assertTrue(viewModel.uiState.value.notice.orEmpty().contains("same code"))
        assertFalse(viewModel.uiState.value.loading)
    }

    @Test
    fun `terminal expired used and locked failures clear saved and in-memory challenge`() {
        listOf("CHALLENGE_EXPIRED", "CHALLENGE_ALREADY_USED", "CHALLENGE_LOCKED")
            .forEach { errorCode ->
                val repository = PhoneOtpRepository().apply {
                    verificationFailure = KitWalletApiException(
                        code = errorCode,
                        message = "Terminal challenge failure: $errorCode",
                    )
                }
                val clock = MutableElapsedRealtimeClock(1_000_000L)
                val savedState = SavedStateHandle()
                val viewModel = viewModel(repository, clock, savedState)
                viewModel.requestPhoneOtp("0700000002") {}
                dispatcher.scheduler.advanceUntilIdle()

                viewModel.verifyCode("123456") { _, _ -> }
                dispatcher.scheduler.advanceUntilIdle()

                assertEquals(errorCode, null, viewModel.uiState.value.pendingChallenge)
                assertEquals(
                    errorCode,
                    "Terminal challenge failure: $errorCode",
                    viewModel.uiState.value.error,
                )
                assertTrue(
                    errorCode,
                    savedState.keys().none { it.startsWith("auth.phone_otp.") },
                )
            }
    }

    private fun viewModel(
        repository: PhoneOtpRepository,
        clock: MutableElapsedRealtimeClock,
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
        bootSessionIdProvider: MutableBootSessionIdProvider =
            MutableBootSessionIdProvider(bootId = 1L),
    ) = AuthViewModel(
        authRepository = repository.proxy,
        elapsedRealtimeClock = clock,
        bootSessionIdProvider = bootSessionIdProvider,
        savedStateHandle = savedStateHandle,
        sessionStore = repository.sessionStore,
    )

    private class PhoneOtpRepository(
        private val challengeIds: MutableList<String> = mutableListOf(CHALLENGE_ID),
        var currentSnapshot: SessionSnapshot = SessionSnapshot(revision = 0L, fence = null),
        private val expiresAtEpochSeconds: Long = CHALLENGE_EXPIRES_AT.epochSecond,
        private val challengeLifetimesMillis: MutableList<Long> = mutableListOf(300_000L),
    ) {
        var requestCount = 0
            private set
        var verifiedCode: String? = null
            private set
        var verifiedPhone: String? = null
            private set
        var verificationFailure: KitWalletApiException? = null
        var delayVerification: Boolean = false
        var verificationPending: Boolean = false
            private set
        private var verificationContinuation: Continuation<AuthOutcome>? = null
        var nextPhoneRequestFailure: Throwable? = null
        var delayNextPhoneRequest: Boolean = false
        var phoneRequestPending: Boolean = false
            private set
        private var phoneRequestContinuation: Continuation<PendingAuthChallenge>? = null
        private var delayedPhoneChallenge: PendingAuthChallenge? = null
        private val signedIn = MutableStateFlow(false)
        private val profileSetup = MutableStateFlow(ProfileSetupState.UNKNOWN)

        val sessionStore: SessionStore = Proxy.newProxyInstance(
            SessionStore::class.java.classLoader,
            arrayOf(SessionStore::class.java),
        ) { instance, method, arguments ->
            when (method.name) {
                "snapshot" -> currentSnapshot
                "toString" -> "PhoneOtpSessionStore"
                "hashCode" -> System.identityHashCode(instance)
                "equals" -> instance === arguments?.firstOrNull()
                else -> error("Unexpected SessionStore call: ${method.name}")
            }
        } as SessionStore

        @Suppress("UNCHECKED_CAST")
        val proxy: AuthRepository = Proxy.newProxyInstance(
            AuthRepository::class.java.classLoader,
            arrayOf(AuthRepository::class.java),
        ) { instance, method, arguments ->
            when (method.name) {
                "getSignedIn" -> signedIn
                "getProfileSetupState" -> profileSetup
                "phoneAuthCapabilities" -> PhoneAuthCapabilities(serverPhoneOtp = true)
                "requestPhoneOtp" -> {
                    val requestIndex = requestCount
                    val challengeId = challengeIds.getOrElse(requestIndex) {
                        challengeIds.last()
                    }
                    requestCount += 1
                    nextPhoneRequestFailure?.let { failure ->
                        nextPhoneRequestFailure = null
                        throw failure
                    }
                    val challenge = PendingAuthChallenge(
                        id = challengeId,
                        kind = AuthChallengeKind.PHONE_OTP,
                        method = "sms",
                        destination = "+256700000002",
                        expiresAtEpochSeconds = expiresAtEpochSeconds,
                        expiresAfterMillis = challengeLifetimesMillis.getOrElse(requestIndex) {
                            challengeLifetimesMillis.last()
                        },
                        resendAfterSeconds = 60,
                        expectedSession = currentSnapshot,
                    )
                    if (delayNextPhoneRequest) {
                        delayNextPhoneRequest = false
                        phoneRequestPending = true
                        delayedPhoneChallenge = challenge
                        phoneRequestContinuation =
                            arguments?.last() as Continuation<PendingAuthChallenge>
                        COROUTINE_SUSPENDED
                    } else {
                        challenge
                    }
                }
                "verifyPhoneOtp" -> {
                    verifiedPhone = arguments?.get(1) as String
                    verifiedCode = arguments[2] as String
                    verificationFailure?.let { throw it }
                    if (delayVerification) {
                        verificationPending = true
                        verificationContinuation =
                            arguments.last() as Continuation<AuthOutcome>
                        COROUTINE_SUSPENDED
                    } else {
                        authenticatedOutcome()
                    }
                }
                "toString" -> "PhoneOtpRepository"
                "hashCode" -> System.identityHashCode(instance)
                "equals" -> instance === arguments?.firstOrNull()
                else -> error("Unexpected AuthRepository call: ${method.name}")
            }
        } as AuthRepository

        fun completeVerification() {
            val continuation = checkNotNull(verificationContinuation) {
                "No delayed verification is pending"
            }
            verificationContinuation = null
            verificationPending = false
            continuation.resumeWith(Result.success(authenticatedOutcome()))
        }

        fun failVerification(error: Throwable) {
            val continuation = checkNotNull(verificationContinuation) {
                "No delayed verification is pending"
            }
            verificationContinuation = null
            verificationPending = false
            continuation.resumeWith(Result.failure(error))
        }

        fun completePhoneRequest() {
            val continuation = checkNotNull(phoneRequestContinuation) {
                "No delayed phone request is pending"
            }
            val challenge = checkNotNull(delayedPhoneChallenge)
            phoneRequestContinuation = null
            delayedPhoneChallenge = null
            phoneRequestPending = false
            continuation.resumeWith(Result.success(challenge))
        }

        private fun authenticatedOutcome(): AuthOutcome = AuthOutcome.Authenticated(
            AuthenticatedUser(
                id = "account-id",
                name = "Test User",
                email = null,
                phone = verifiedPhone,
                tag = "test-user",
                paymentPinSet = true,
                profileSetupRequired = false,
            ),
        )
    }

    private class MutableElapsedRealtimeClock(
        private var currentMillis: Long,
    ) : ElapsedRealtimeClock {
        override fun millis(): Long = currentMillis

        fun advanceSeconds(seconds: Long) {
            currentMillis += seconds * 1_000L
        }
    }

    private class MutableBootSessionIdProvider(
        var bootId: Long?,
    ) : BootSessionIdProvider {
        override fun currentBootId(): Long? = bootId
    }

    private companion object {
        const val CHALLENGE_ID = "otp-challenge-id"
        const val REPLACEMENT_CHALLENGE_ID = "replacement-otp-challenge-id"
        const val STATE_RESEND_IN_FLIGHT_CHALLENGE_ID =
            "auth.phone_otp.resend_in_flight_challenge_id"
        val CHALLENGE_EXPIRES_AT: Instant = Instant.parse("2026-07-24T15:23:00Z")
    }
}
