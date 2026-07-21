package com.kit.wallet

import com.kit.wallet.data.auth.AuthRepository
import com.kit.wallet.data.repository.ProfileEmailChallenge
import com.kit.wallet.data.repository.UserRepository
import com.kit.wallet.feature.settings.SettingsViewModel
import com.kit.wallet.feature.settings.isProfileEmailResendAllowed
import com.kit.wallet.feature.settings.profileEmailResendDeadline
import com.kit.wallet.ui.model.UserProfile
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsEmailChallengeViewModelTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `resend cooldown is enforced and a failed resend retains the usable challenge`() = runTest {
        val clock = MutableClock(Instant.parse("2026-07-20T12:00:00Z"))
        val repository = FakeUserRepository()
        val challenge = ProfileEmailChallenge(
            id = "email-challenge-1",
            destination = "a***@example.test",
            expiresAt = "2026-07-20T12:05:00Z",
            resendAfterSeconds = 60L,
        )
        repository.requests += Result.success(challenge)
        val viewModel = SettingsViewModel(repository, unusedAuthRepository(), clock)
        viewModel.beginEmailFlow(null)

        viewModel.requestEmailAttachment(" Amina@Example.Test ")

        val issued = viewModel.emailState.value
        assertSame(challenge, issued.challenge)
        assertEquals(clock.millis() + 60_000L, issued.resendNotBeforeEpochMillis)
        assertEquals(1, repository.requestCount)

        viewModel.requestEmailAttachment("amina@example.test")

        assertEquals(1, repository.requestCount)
        assertSame(challenge, viewModel.emailState.value.challenge)
        assertNotNull(viewModel.emailState.value.error)

        clock.advanceSeconds(61L)
        repository.requests += Result.failure<ProfileEmailChallenge>(
            IllegalStateException("network unavailable"),
        )
        viewModel.requestEmailAttachment("amina@example.test")

        val failedResend = viewModel.emailState.value
        assertEquals(2, repository.requestCount)
        assertSame(challenge, failedResend.challenge)
        assertFalse(failedResend.requesting)
        assertEquals("network unavailable", failedResend.error)
        assertEquals(clock.millis() + 60_000L, failedResend.resendNotBeforeEpochMillis)

        var verified = false
        viewModel.verifyEmailAttachment("123456") { verified = true }

        assertTrue(verified)
        assertEquals("email-challenge-1", repository.verifiedChallengeId)
    }

    @Test
    fun `missing email resend metadata also fails closed to a local cooldown`() {
        val now = 10_000L

        assertEquals(70_000L, profileEmailResendDeadline(now, resendAfterSeconds = null))
        assertFalse(isProfileEmailResendAllowed(null, now))
        assertFalse(isProfileEmailResendAllowed(70_000L, 69_999L))
        assertTrue(isProfileEmailResendAllowed(70_000L, 70_000L))
    }

    private class FakeUserRepository : UserRepository {
        override val profile: StateFlow<UserProfile> = MutableStateFlow(
            UserProfile(
                name = "Amina",
                phone = "+256700000001",
                tag = "amina",
                kycLabel = "Not verified",
            ),
        )
        val requests = mutableListOf<Result<ProfileEmailChallenge>>()
        var requestCount = 0
            private set
        var verifiedChallengeId: String? = null
            private set

        override suspend fun refreshProfile() = Unit

        override suspend fun updateProfile(name: String, tag: String) = Unit

        override suspend fun requestEmailAttachment(email: String): ProfileEmailChallenge {
            requestCount += 1
            return requests.removeAt(0).getOrThrow()
        }

        override suspend fun verifyEmailAttachment(challengeId: String, code: String) {
            verifiedChallengeId = challengeId
        }
    }

    private class MutableClock(
        private var current: Instant,
        private val zone: ZoneId = ZoneOffset.UTC,
    ) : Clock() {
        override fun instant(): Instant = current

        override fun getZone(): ZoneId = zone

        override fun withZone(zone: ZoneId): Clock = MutableClock(current, zone)

        fun advanceSeconds(seconds: Long) {
            current = current.plusSeconds(seconds)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun unusedAuthRepository(): AuthRepository = Proxy.newProxyInstance(
        AuthRepository::class.java.classLoader,
        arrayOf(AuthRepository::class.java),
    ) { instance, method, arguments ->
        when (method.name) {
            "toString" -> "UnusedAuthRepository"
            "hashCode" -> System.identityHashCode(instance)
            "equals" -> instance === arguments?.firstOrNull()
            else -> error("Unexpected AuthRepository call: ${method.name}")
        }
    } as AuthRepository
}
