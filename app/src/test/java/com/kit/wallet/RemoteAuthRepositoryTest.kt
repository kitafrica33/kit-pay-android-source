package com.kit.wallet

import com.kit.wallet.data.auth.AuthOutcome
import com.kit.wallet.data.auth.AuthChallengeKind
import com.kit.wallet.data.auth.DeviceIdentityProvider
import com.kit.wallet.data.auth.PendingAuthChallenge
import com.kit.wallet.data.auth.RemoteAuthRepository
import com.kit.wallet.data.auth.RemoteRevocationState
import com.kit.wallet.data.auth.SecureMessagingEnrollmentResetTarget
import com.kit.wallet.data.messaging.AccountMessageHistoryRetention
import com.kit.wallet.data.messaging.AccountMessageArchivePurgeNotDurableException
import com.kit.wallet.data.messaging.NoOpAccountMessageHistoryRetention
import com.kit.wallet.data.messaging.SecureMessagingSessionBinding
import com.kit.wallet.data.messaging.SecureMessagingSessionFence
import com.kit.wallet.data.messaging.SecureMessagingRecordKeyPermanentlyMissingException
import com.kit.wallet.data.notifications.PushMessagingTransport
import com.kit.wallet.data.notifications.PushTokenCoordinator
import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.AuthTokenRefresher
import com.kit.wallet.data.remote.DeviceRegistrationDto
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.KitWalletApiException
import com.kit.wallet.data.remote.SessionRefreshApi
import com.kit.wallet.data.remote.SecureMessagingEnrollmentRecoveryApi
import com.kit.wallet.data.repository.WalletRefreshTrigger
import com.kit.wallet.data.repository.WalletSyncRepository
import com.kit.wallet.data.repository.WalletSyncResult
import com.kit.wallet.data.repository.PaymentAuthorizer
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.data.session.SessionTokens
import com.kit.wallet.data.session.SessionFence
import com.kit.wallet.data.session.ProfileSetupState
import com.kit.wallet.data.session.SessionInvalidatedException
import com.kit.wallet.data.session.SecureMessagingResetProofFence
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class RemoteAuthRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var api: KitWalletApi
    private lateinit var messagingEnrollmentRecovery: SecureMessagingEnrollmentRecoveryApi
    private lateinit var sessionRefreshApi: SessionRefreshApi
    private lateinit var apiCalls: ApiCallExecutor

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        apiCalls = ApiCallExecutor(moshi)
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
        api = retrofit.create(KitWalletApi::class.java)
        messagingEnrollmentRecovery =
            retrofit.create(SecureMessagingEnrollmentRecoveryApi::class.java)
        sessionRefreshApi = retrofit.create(SessionRefreshApi::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `authenticated login persists tokens and schedules wallet refresh`() = runTest {
        server.enqueue(jsonResponse(AUTHENTICATED_JSON))
        val sessions = FakeSessionStore()
        val refresh = FakeRefreshTrigger()
        val repository = repository(sessions, refresh)

        val outcome = repository.loginWithEmail("amina@example.test", "secret")

        assertTrue(outcome is AuthOutcome.Authenticated)
        assertEquals("access-token", sessions.current()?.accessToken)
        assertEquals("session-uuid", sessions.current()?.sessionId)
        assertEquals("7", sessions.current()?.accountId)
        assertEquals(ProfileSetupState.REQUIRED, sessions.current()?.profileSetupState)
        assertEquals(1, refresh.calls)
        val request = server.takeRequest()
        assertEquals("/api/kit-wallet/v1/auth/email/login", request.path)
        assertTrue(request.body.readUtf8().contains("\"installation_id\":\"installation-uuid\""))
    }

    @Test
    fun `authenticated login snapshots old history before saving a replacement session`() =
        runTest {
            server.enqueue(jsonResponse(AUTHENTICATED_JSON))
            val events = mutableListOf<String>()
            val oldSession = TEST_SESSION.copy(
                sessionId = "old-session",
                accountId = "11111111-1111-4111-8111-111111111111",
            )
            val sessions = FakeSessionStore(replacementEvents = events).apply { save(oldSession) }
            val history = FakeMessageHistoryRetention(snapshotEvents = events)
            val repository = repository(
                sessions = sessions,
                refresh = FakeRefreshTrigger(),
                messageHistory = history,
            )

            repository.loginWithEmail("amina@example.test", "secret")

            assertEquals(listOf("snapshot", "save-replacement"), events)
            assertEquals(listOf(oldSession.fence()), history.snapshotTargets)
            assertEquals("session-uuid", sessions.current()?.sessionId)
        }

    @Test
    fun `failed replacement snapshot retains the old session and skips wallet refresh`() =
        runTest {
            server.enqueue(jsonResponse(AUTHENTICATED_JSON))
            val failure = IllegalStateException("message archive unavailable")
            val events = mutableListOf<String>()
            val oldSession = TEST_SESSION.copy(
                sessionId = "old-session",
                accountId = "11111111-1111-4111-8111-111111111111",
            )
            val sessions = FakeSessionStore(replacementEvents = events).apply { save(oldSession) }
            val history = FakeMessageHistoryRetention(
                snapshotFailure = failure,
                snapshotEvents = events,
            )
            val refresh = FakeRefreshTrigger()
            val repository = repository(
                sessions = sessions,
                refresh = refresh,
                messageHistory = history,
            )

            val observed = runCatching {
                repository.loginWithEmail("amina@example.test", "secret")
            }.exceptionOrNull()

            assertSame(failure, observed)
            assertEquals(oldSession, sessions.current())
            assertEquals(listOf("snapshot"), events)
            assertEquals(0, refresh.calls)
        }

    @Test
    fun `fresh authenticated session drops an older messaging reset fence`() = runTest {
        server.enqueue(jsonResponse(AUTHENTICATED_JSON))
        val sessions = FakeSessionStore().apply {
            save(
                TEST_SESSION.copy(
                    sessionId = "older-session",
                    messagingResetProof = RESET_PENDING,
                ),
            )
        }

        repository(sessions, FakeRefreshTrigger())
            .loginWithEmail("amina@example.test", "secret")

        assertEquals("session-uuid", sessions.current()?.sessionId)
        assertNull(sessions.current()?.messagingResetProof)
    }

    @Test
    fun `email registration sends the normalized user chosen tag`() = runTest {
        server.enqueue(jsonResponse(REGISTRATION_JSON))
        val repository = repository(FakeSessionStore(), FakeRefreshTrigger())

        val result = repository.registerWithEmail(
            name = "  Amina   Yusuf  ",
            tag = " @Amina_01 ",
            email = "amina@example.test",
            password = "Strong-password-123",
            passwordConfirmation = "Strong-password-123",
        )

        assertEquals("amina@example.test", result.email)
        val request = server.takeRequest()
        assertEquals("/api/kit-wallet/v1/auth/email/register", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"name\":\"Amina Yusuf\""))
        assertTrue(body.contains("\"tag\":\"amina_01\""))
    }

    @Test
    fun `null or blank recovery messages use safe user feedback`() = runTest {
        server.enqueue(jsonResponse(NULL_MESSAGE_JSON))
        server.enqueue(jsonResponse(BLANK_MESSAGE_JSON))
        val repository = repository(FakeSessionStore(), FakeRefreshTrigger())

        assertEquals(
            "If the account exists, a new verification email has been sent.",
            repository.resendEmailVerification("amina@example.test"),
        )
        assertEquals(
            "If the account exists, password reset instructions have been sent.",
            repository.forgotPassword("amina@example.test"),
        )
    }

    @Test
    fun `legacy null phone profile cannot bypass required profile setup with false flag`() = runTest {
        server.enqueue(jsonResponse(PLACEHOLDER_PROFILE_JSON))
        val sessions = FakeSessionStore()
        val repository = repository(sessions, FakeRefreshTrigger())

        val outcome = repository.loginWithEmail("amina@example.test", "secret")
            as AuthOutcome.Authenticated

        assertEquals("Kit Pay User", outcome.user.name)
        assertTrue(outcome.user.profileSetupRequired)
        assertEquals(ProfileSetupState.REQUIRED, sessions.current()?.profileSetupState)
    }

    @Test
    fun `late authentication response cannot resurrect a cleared local epoch`() = runTest {
        val sessions = FakeSessionStore()
        val refresh = FakeRefreshTrigger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                runBlocking { sessions.clear() }
                return jsonResponse(AUTHENTICATED_JSON)
            }
        }
        val repository = repository(sessions, refresh)

        val failure = runCatching {
            repository.loginWithEmail("amina@example.test", "secret")
        }.exceptionOrNull()

        assertTrue(failure is SessionInvalidatedException)
        assertNull(sessions.current())
        assertEquals(0, refresh.calls)
    }

    @Test
    fun `explicit refreshes share one rotation coordinator and advance credential generations`() =
        runTest {
            server.enqueue(
                jsonResponse(
                    AUTHENTICATED_JSON
                        .replace("access-token", "refreshed-access-token")
                        .replace("refresh-token", "refreshed-refresh-token"),
                ),
            )
            server.enqueue(
                jsonResponse(
                    AUTHENTICATED_JSON
                        .replace("access-token", "twice-refreshed-access-token")
                        .replace("refresh-token", "twice-refreshed-refresh-token"),
                ),
            )
            val sessions = FakeSessionStore().apply { save(TEST_SESSION) }
            val refresh = FakeRefreshTrigger()
            val repository = repository(sessions, refresh)

            val first = async { repository.refreshSession() }
            val second = async { repository.refreshSession() }
            first.await()
            second.await()

            val firstRequest = server.takeRequest()
            val secondRequest = server.takeRequest()
            assertEquals("/api/kit-wallet/v1/auth/refresh", firstRequest.path)
            assertEquals(TEST_SESSION.sessionId, firstRequest.getHeader("X-Kit-Wallet-Session-ID"))
            assertTrue(firstRequest.body.readUtf8().contains("\"refresh_token\":\"refresh-token\""))
            assertTrue(
                secondRequest.body.readUtf8()
                    .contains("\"refresh_token\":\"refreshed-refresh-token\""),
            )
            assertEquals("twice-refreshed-access-token", sessions.current()?.accessToken)
            assertEquals("twice-refreshed-refresh-token", sessions.current()?.refreshToken)
            assertTrue(sessions.current()?.refreshReplayNonce != TEST_SESSION.refreshReplayNonce)
            assertEquals(2, refresh.calls)
        }

    @Test
    fun `late refresh rejection cannot clear a newer same-session credential`() = runTest {
        val sessions = FakeSessionStore().apply { save(TEST_SESSION) }
        val replacement = TEST_SESSION.copy(
            accessToken = "replacement-access-token",
            refreshToken = "replacement-refresh-token",
            refreshReplayNonce = "11111111-1111-4111-8111-111111111111",
        )
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                runBlocking { sessions.save(replacement) }
                return MockResponse()
                    .setResponseCode(401)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """{"ok":false,"error":{"code":"SESSION_REVOKED","message":"Revoked"}}""",
                    )
            }
        }
        val walletSync = FakeWalletSync()
        val repository = repository(
            sessions = sessions,
            refresh = FakeRefreshTrigger(),
            walletSync = walletSync,
        )

        val failure = runCatching { repository.refreshSession() }.exceptionOrNull()

        assertTrue(failure is KitWalletApiException)
        assertEquals("SESSION_REVOKED", (failure as KitWalletApiException).code)
        assertEquals(replacement, sessions.current())
        assertEquals(0, walletSync.clearCalls)
    }

    @Test
    fun `terminal refresh rejection clears only its exact local credential`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"ok":false,"error":{"code":"REFRESH_TOKEN_REUSED","message":"Reused"}}""",
                ),
        )
        val sessions = FakeSessionStore().apply { save(TEST_SESSION) }
        val walletSync = FakeWalletSync()
        val repository = repository(
            sessions = sessions,
            refresh = FakeRefreshTrigger(),
            walletSync = walletSync,
        )

        val failure = runCatching { repository.refreshSession() }.exceptionOrNull()

        assertTrue(failure is KitWalletApiException)
        assertEquals("REFRESH_TOKEN_REUSED", (failure as KitWalletApiException).code)
        assertNull(sessions.current())
        assertEquals(1, walletSync.clearCalls)
    }

    @Test
    fun `terminal refresh rejection retains session when final history snapshot fails`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"ok":false,"error":{"code":"REFRESH_TOKEN_REUSED","message":"Reused"}}""",
                ),
        )
        val failure = IllegalStateException("final refresh-rejection snapshot failed")
        val history = FakeMessageHistoryRetention(snapshotFailure = failure)
        val targetSession = TEST_SESSION.copy(
            accountId = "11111111-1111-4111-8111-111111111111",
        )
        val sessions = FakeSessionStore().apply { save(targetSession) }
        val walletSync = FakeWalletSync()
        val repository = repository(
            sessions = sessions,
            refresh = FakeRefreshTrigger(),
            walletSync = walletSync,
            messageHistory = history,
        )

        val observed = runCatching { repository.refreshSession() }.exceptionOrNull()

        assertEquals(failure, observed)
        assertEquals(targetSession, sessions.current())
        assertEquals(listOf(targetSession.fence()), history.snapshotTargets)
        assertEquals(0, walletSync.clearCalls)
    }

    @Test
    fun `phone OTP challenge retains destination for navigation state`() = runTest {
        server.enqueue(jsonResponse(CHALLENGE_JSON))
        val repository = repository(FakeSessionStore(), FakeRefreshTrigger())

        val challenge = repository.requestPhoneOtp("+256772345678")

        assertEquals("challenge-uuid", challenge.id)
        assertEquals("+256772345678", challenge.destination)
        assertEquals(60L, challenge.resendAfterSeconds)
    }

    @Test
    fun `email login preserves the authenticator challenge method`() = runTest {
        server.enqueue(jsonResponse(TOTP_CHALLENGE_JSON))
        val repository = repository(FakeSessionStore(), FakeRefreshTrigger())

        val outcome = repository.loginWithEmail("amina@example.test", "secret")
        val challenge = (outcome as AuthOutcome.ChallengeRequired).challenge

        assertEquals(AuthChallengeKind.TWO_FACTOR, challenge.kind)
        assertEquals("totp", challenge.method)
        assertEquals("Authenticator app", challenge.destination)
    }

    @Test
    fun `phone OTP and two factor authentication both snapshot a replaced session`() = runTest {
        server.enqueue(jsonResponse(AUTHENTICATED_JSON))
        server.enqueue(jsonResponse(AUTHENTICATED_JSON))
        val oldSession = TEST_SESSION.copy(
            sessionId = "old-session",
            accountId = "11111111-1111-4111-8111-111111111111",
        )

        suspend fun authenticate(kind: AuthChallengeKind): List<SessionFence> {
            val sessions = FakeSessionStore().apply { save(oldSession) }
            val history = FakeMessageHistoryRetention()
            val repository = repository(
                sessions = sessions,
                refresh = FakeRefreshTrigger(),
                messageHistory = history,
            )
            val challenge = PendingAuthChallenge(
                id = "challenge-uuid",
                kind = kind,
                expectedSession = sessions.snapshot(),
            )
            when (kind) {
                AuthChallengeKind.PHONE_OTP -> repository.verifyPhoneOtp(
                    challenge = challenge,
                    phone = "+256772345678",
                    code = "123456",
                )
                AuthChallengeKind.TWO_FACTOR -> repository.verifyTwoFactor(
                    challenge = challenge,
                    code = "123456",
                )
            }
            return history.snapshotTargets
        }

        assertEquals(listOf(oldSession.fence()), authenticate(AuthChallengeKind.PHONE_OTP))
        assertEquals(listOf(oldSession.fence()), authenticate(AuthChallengeKind.TWO_FACTOR))
        assertEquals("/api/kit-wallet/v1/auth/otp/verify", server.takeRequest().path)
        assertEquals("/api/kit-wallet/v1/auth/2fa/verify", server.takeRequest().path)
    }

    @Test
    fun `phone auth uses only the local SMS capability`() = runTest {
        server.enqueue(jsonResponse(CAPABILITIES_JSON))
        val repository = repository(FakeSessionStore(), FakeRefreshTrigger())

        val capabilities = repository.phoneAuthCapabilities()

        assertEquals(false, capabilities.serverPhoneOtp)
        assertEquals("/api/kit-wallet/v1/capabilities", server.takeRequest().path)
    }

    @Test
    fun `logout attempts push unregister before revocation and clears after server success`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(503).setHeader("Content-Type", "application/json")
                .setBody(API_UNAVAILABLE_JSON),
        )
        server.enqueue(jsonResponse(LOGOUT_JSON))
        val sessions = FakeSessionStore().apply { save(TEST_SESSION) }
        val repository = repository(sessions, FakeRefreshTrigger())

        repository.logout(allDevices = true)

        assertEquals("/api/kit-wallet/v1/devices/current/push-token", server.takeRequest().path)
        val logout = server.takeRequest()
        assertEquals("/api/kit-wallet/v1/auth/logout", logout.path)
        assertTrue(logout.body.readUtf8().contains("\"all_devices\":true"))
        assertNull(sessions.current())
    }

    @Test
    fun `all device logout retains session when archive purge cannot be made durable`() = runTest {
        val failure = AccountMessageArchivePurgeNotDurableException(
            IllegalStateException("purge marker unavailable"),
        )
        val history = FakeMessageHistoryRetention(eraseFailure = failure)
        val targetSession = TEST_SESSION.copy(
            accountId = "11111111-1111-4111-8111-111111111111",
        )
        val sessions = FakeSessionStore().apply { save(targetSession) }
        val repository = repository(
            sessions = sessions,
            refresh = FakeRefreshTrigger(),
            messageHistory = history,
        )

        val observed = runCatching { repository.logout(allDevices = true) }.exceptionOrNull()

        assertEquals(failure, observed)
        assertEquals(targetSession, sessions.current())
        assertEquals(listOf(targetSession.fence()), history.scheduleTargets)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `all device logout clears session and retains retry marker on purge failure`() = runTest {
        server.enqueue(jsonResponse(PUSH_UNREGISTER_JSON))
        server.enqueue(jsonResponse(LOGOUT_JSON))
        val history = FakeMessageHistoryRetention(
            eraseFailure = IllegalStateException("archive key temporarily unavailable"),
        )
        val targetSession = TEST_SESSION.copy(
            accountId = "11111111-1111-4111-8111-111111111111",
        )
        val sessions = FakeSessionStore().apply { save(targetSession) }
        val repository = repository(
            sessions = sessions,
            refresh = FakeRefreshTrigger(),
            messageHistory = history,
        )

        val result = repository.logout(allDevices = true)

        assertNull(sessions.current())
        assertTrue(result.retryRecommended)
        assertTrue(result.warning.orEmpty().contains("message-history cleanup"))
        assertEquals(listOf(targetSession.fence()), history.scheduleTargets)
        assertEquals(listOf(targetSession.fence()), history.eraseTargets)
    }

    @Test
    fun `logout erases local state and reports retry when server revocation is unconfirmed`() = runTest {
        server.enqueue(jsonResponse(PUSH_UNREGISTER_JSON))
        server.enqueue(
            MockResponse().setResponseCode(503).setHeader("Content-Type", "application/json")
                .setBody(API_UNAVAILABLE_JSON),
        )
        val sessions = FakeSessionStore().apply { save(TEST_SESSION) }
        val repository = repository(sessions, FakeRefreshTrigger())

        val result = repository.logout(allDevices = false)

        assertNull(sessions.current())
        assertEquals(RemoteRevocationState.UNCONFIRMED, result.remoteRevocation)
        assertTrue(result.localSessionCleared)
        assertTrue(result.retryRecommended)
        assertNotNull(result.warning)
    }

    @Test
    fun `normal logout aborts before revocation when archive preflight snapshot fails`() =
        runTest {
            val failure = IllegalStateException("final archive snapshot failed")
            val history = FakeMessageHistoryRetention(snapshotFailure = failure)
            val targetSession = TEST_SESSION.copy(
                accountId = "11111111-1111-4111-8111-111111111111",
            )
            val sessions = FakeSessionStore().apply { save(targetSession) }
            val repository = repository(
                sessions = sessions,
                refresh = FakeRefreshTrigger(),
                messageHistory = history,
            )

            val observed = runCatching { repository.logout(allDevices = false) }.exceptionOrNull()

            assertEquals(failure, observed)
            assertEquals(targetSession, sessions.current())
            assertEquals(targetSession.fence(), history.snapshotTargets.single())
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `normal logout retains session when state exclusive final snapshot fails`() = runTest {
        server.enqueue(jsonResponse(PUSH_UNREGISTER_JSON))
        server.enqueue(jsonResponse(LOGOUT_JSON))
        val failure = IllegalStateException("exclusive final snapshot failed")
        val history = FakeMessageHistoryRetention(
            snapshotFailure = failure,
            failSnapshotCall = 2,
        )
        val targetSession = TEST_SESSION.copy(
            accountId = "11111111-1111-4111-8111-111111111111",
        )
        val sessions = FakeSessionStore().apply { save(targetSession) }
        val walletSync = FakeWalletSync()
        val repository = repository(
            sessions = sessions,
            refresh = FakeRefreshTrigger(),
            walletSync = walletSync,
            messageHistory = history,
        )

        val observed = runCatching { repository.logout(allDevices = false) }.exceptionOrNull()

        assertEquals(failure, observed)
        assertEquals(targetSession, sessions.current())
        assertEquals(listOf(targetSession.fence(), targetSession.fence()), history.snapshotTargets)
        assertEquals(0, walletSync.clearCalls)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `logout clears local session when server confirms it is already unauthorized`() = runTest {
        server.enqueue(jsonResponse(PUSH_UNREGISTER_JSON))
        server.enqueue(
            MockResponse().setResponseCode(401).setHeader("Content-Type", "application/json")
                .setBody(UNAUTHORIZED_JSON),
        )
        val sessions = FakeSessionStore().apply { save(TEST_SESSION) }
        val repository = repository(sessions, FakeRefreshTrigger())

        repository.logout(allDevices = false)

        assertNull(sessions.current())
    }

    @Test
    fun `messaging recovery resets exact enrollment and retains authenticated session`() = runTest {
        server.enqueue(jsonResponse(RESET_APPLIED_JSON))
        val sessions = FakeSessionStore().apply { save(TEST_SESSION) }
        val repository = repository(sessions, FakeRefreshTrigger())

        repository.recoverMissingSecureMessagingEnrollment(
            TEST_SESSION.fence(),
            unusedActivationFence(),
            RESET_TARGET,
        )

        val reset = server.takeRequest()
        assertEquals("/api/kit-wallet/v1/messaging/enrollment/reset", reset.path)
        assertEquals("Bearer ${TEST_SESSION.accessToken}", reset.getHeader("Authorization"))
        assertEquals(TEST_SESSION.sessionId, reset.getHeader("X-Kit-Wallet-Session-ID"))
        val body = reset.body.readUtf8()
        assertTrue(body.contains("\"expected_enrollment_epoch\":7"))
        assertTrue(body.contains("\"expected_registration_id\":42"))
        assertTrue(body.contains("\"expected_identity_key_sha256\":\"${"1".repeat(64)}\""))
        assertTrue(body.contains("\"expected_bundle_version\":3"))
        assertEquals(TEST_SESSION.sessionId, sessions.current()?.sessionId)
        assertEquals(8L, sessions.current()?.messagingResetProof?.resultingEnrollmentEpoch)
    }

    @Test
    fun `fresh authentication recovery snapshots history before clearing exact session`() = runTest {
        val targetSession = TEST_SESSION.copy(
            accountId = "11111111-1111-4111-8111-111111111111",
        )
        val sessions = FakeSessionStore().apply { save(targetSession) }
        val history = FakeMessageHistoryRetention()
        val repository = repository(
            sessions = sessions,
            refresh = FakeRefreshTrigger(),
            messageHistory = history,
        )

        repository.requireFreshAuthenticationForSecureMessagingRecovery(
            expectedSession = targetSession.fence(),
            activationFence = unusedActivationFence(),
        )

        assertEquals(listOf(targetSession.fence()), history.snapshotTargets)
        assertNull(sessions.current())
    }

    @Test
    fun `fresh authentication recovery bypasses only proved unreadable history`() = runTest {
        val targetSession = TEST_SESSION.copy(
            accountId = "11111111-1111-4111-8111-111111111111",
        )
        val sessions = FakeSessionStore().apply { save(targetSession) }
        val history = FakeMessageHistoryRetention(
            snapshotFailure = SecureMessagingRecordKeyPermanentlyMissingException(),
        )
        val repository = repository(
            sessions = sessions,
            refresh = FakeRefreshTrigger(),
            messageHistory = history,
        )

        repository.requireFreshAuthenticationForSecureMessagingRecovery(
            expectedSession = targetSession.fence(),
            activationFence = unusedActivationFence(),
        )

        assertEquals(listOf(targetSession.fence()), history.snapshotTargets)
        assertNull(sessions.current())
    }

    @Test
    fun `fresh authentication recovery retains session on ordinary snapshot failure`() = runTest {
        val targetSession = TEST_SESSION.copy(
            accountId = "11111111-1111-4111-8111-111111111111",
        )
        val sessions = FakeSessionStore().apply { save(targetSession) }
        val failure = IllegalStateException("archive temporarily unavailable")
        val history = FakeMessageHistoryRetention(snapshotFailure = failure)
        val repository = repository(
            sessions = sessions,
            refresh = FakeRefreshTrigger(),
            messageHistory = history,
        )

        val observed = runCatching {
            repository.requireFreshAuthenticationForSecureMessagingRecovery(
                expectedSession = targetSession.fence(),
                activationFence = unusedActivationFence(),
            )
        }.exceptionOrNull()

        assertSame(failure, observed)
        assertEquals(targetSession, sessions.current())
    }

    @Test
    fun `messaging recovery retains session when reset is unconfirmed`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(503).setHeader("Content-Type", "application/json")
                .setBody(API_UNAVAILABLE_JSON),
        )
        val sessions = FakeSessionStore().apply { save(TEST_SESSION) }
        val repository = repository(sessions, FakeRefreshTrigger())

        val failure = runCatching {
            repository.recoverMissingSecureMessagingEnrollment(
                TEST_SESSION.fence(),
                unusedActivationFence(),
                RESET_TARGET,
            )
        }.exceptionOrNull()

        assertTrue(failure is KitWalletApiException)
        assertEquals(TEST_SESSION.sessionId, sessions.current()?.sessionId)
    }

    @Test
    fun `messaging recovery persists its exact pending target before first HTTP`() = runTest {
        val sessions = FakeSessionStore().apply { save(TEST_SESSION) }
        var pendingObservedByServer: SecureMessagingResetProofFence? = null
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                pendingObservedByServer = sessions.current()?.messagingResetProof
                return MockResponse()
                    .setResponseCode(503)
                    .setHeader("Content-Type", "application/json")
                    .setBody(API_UNAVAILABLE_JSON)
            }
        }

        val failure = runCatching {
            repository(sessions, FakeRefreshTrigger())
                .recoverMissingSecureMessagingEnrollment(
                    TEST_SESSION.fence(),
                    unusedActivationFence(),
                    RESET_TARGET,
                )
        }.exceptionOrNull()

        assertTrue(failure is KitWalletApiException)
        assertEquals(RESET_PENDING, pendingObservedByServer)
        assertEquals(RESET_PENDING, sessions.current()?.messagingResetProof)
    }

    @Test
    fun `restarted messaging recovery retries only its durable exact target`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(503).setHeader("Content-Type", "application/json")
                .setBody(API_UNAVAILABLE_JSON),
        )
        server.enqueue(jsonResponse(RESET_REPLAY_JSON))
        val sessions = FakeSessionStore().apply { save(TEST_SESSION) }

        val firstFailure = runCatching {
            repository(sessions, FakeRefreshTrigger())
                .recoverMissingSecureMessagingEnrollment(
                    TEST_SESSION.fence(),
                    unusedActivationFence(),
                    RESET_TARGET,
                )
        }.exceptionOrNull()
        assertTrue(firstFailure is KitWalletApiException)
        assertEquals(RESET_PENDING, sessions.current()?.messagingResetProof)

        repository(sessions, FakeRefreshTrigger())
            .recoverMissingSecureMessagingEnrollment(
                TEST_SESSION.fence(),
                unusedActivationFence(),
                RESET_TARGET,
            )

        val first = server.takeRequest()
        val retriedAfterRestart = server.takeRequest()
        assertEquals(first.body.readUtf8(), retriedAfterRestart.body.readUtf8())
        assertEquals(first.getHeader("Authorization"), retriedAfterRestart.getHeader("Authorization"))
        assertEquals(TEST_SESSION.sessionId, retriedAfterRestart.getHeader("X-Kit-Wallet-Session-ID"))
        assertEquals(8L, sessions.current()?.messagingResetProof?.resultingEnrollmentEpoch)
    }

    @Test
    fun `messaging recovery accepts idempotent lost-response proof`() = runTest {
        server.enqueue(jsonResponse(RESET_REPLAY_JSON))
        val sessions = FakeSessionStore().apply { save(TEST_SESSION) }
        val repository = repository(sessions, FakeRefreshTrigger())

        repository.recoverMissingSecureMessagingEnrollment(
            TEST_SESSION.fence(),
            unusedActivationFence(),
            RESET_TARGET,
        )

        assertEquals(TEST_SESSION.sessionId, sessions.current()?.sessionId)
    }

    @Test
    fun `messaging recovery refreshes the exact captured session after access expiry`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(401).setHeader("Content-Type", "application/json")
                .setBody(
                    """{"ok":false,"error":{"code":"ACCESS_TOKEN_EXPIRED","message":"Expired"}}""",
                ),
        )
        server.enqueue(
            jsonResponse(
                AUTHENTICATED_JSON
                    .replace("access-token", "refreshed-access-token")
                    .replace("refresh-token", "refreshed-refresh-token"),
            ),
        )
        server.enqueue(jsonResponse(RESET_APPLIED_JSON))
        val sessions = FakeSessionStore().apply { save(TEST_SESSION) }
        val repository = repository(sessions, FakeRefreshTrigger())

        repository.recoverMissingSecureMessagingEnrollment(
            TEST_SESSION.fence(),
            unusedActivationFence(),
            RESET_TARGET,
        )

        val expiredReset = server.takeRequest()
        assertEquals("Bearer ${TEST_SESSION.accessToken}", expiredReset.getHeader("Authorization"))
        val refresh = server.takeRequest()
        assertEquals("/api/kit-wallet/v1/auth/refresh", refresh.path)
        assertEquals(TEST_SESSION.sessionId, refresh.getHeader("X-Kit-Wallet-Session-ID"))
        val confirmedReset = server.takeRequest()
        assertEquals(
            "Bearer refreshed-access-token",
            confirmedReset.getHeader("Authorization"),
        )
        assertEquals("refreshed-access-token", sessions.current()?.accessToken)
    }

    @Test
    fun `messaging recovery preserves refreshed session after an ambiguous second 401`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(401).setHeader("Content-Type", "application/json")
                .setBody(
                    """{"ok":false,"error":{"code":"ACCESS_TOKEN_EXPIRED","message":"Expired"}}""",
                ),
        )
        server.enqueue(
            jsonResponse(
                AUTHENTICATED_JSON
                    .replace("access-token", "refreshed-access-token")
                    .replace("refresh-token", "refreshed-refresh-token"),
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(401).setHeader("Content-Type", "application/json")
                .setBody("""{"ok":false}"""),
        )
        val sessions = FakeSessionStore().apply { save(TEST_SESSION) }
        val repository = repository(sessions, FakeRefreshTrigger())

        val failure = runCatching {
            repository.recoverMissingSecureMessagingEnrollment(
                TEST_SESSION.fence(),
                unusedActivationFence(),
                RESET_TARGET,
            )
        }.exceptionOrNull()

        assertTrue(failure is KitWalletApiException)
        assertEquals("HTTP_401", (failure as KitWalletApiException).code)
        assertEquals("refreshed-access-token", sessions.current()?.accessToken)
        assertEquals(RESET_PENDING, sessions.current()?.messagingResetProof)
    }

    @Test
    fun `messaging recovery never revokes or clears another local epoch`() = runTest {
        val sessions = FakeSessionStore().apply { save(TEST_SESSION) }
        val repository = repository(sessions, FakeRefreshTrigger())

        val failure = runCatching {
            repository.recoverMissingSecureMessagingEnrollment(
                TEST_SESSION.fence().copy(sessionId = "obsolete-session"),
                unusedActivationFence(),
                RESET_TARGET,
            )
        }.exceptionOrNull()

        assertTrue(failure is SessionInvalidatedException)
        assertEquals(TEST_SESSION.sessionId, sessions.current()?.sessionId)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `stale reset response clears only S1 and preserves a concurrently adopted S2`() = runTest {
        val sessions = FakeSessionStore().apply { save(TEST_SESSION) }
        var observedAuthorization: String? = null
        var observedSessionId: String? = null
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse =
                when (request.path) {
                    "/api/kit-wallet/v1/messaging/enrollment/reset" -> {
                        observedAuthorization = request.getHeader("Authorization")
                        observedSessionId = request.getHeader("X-Kit-Wallet-Session-ID")
                        runBlocking {
                            sessions.save(
                                TEST_SESSION.copy(
                                    sessionId = "replacement-session",
                                    accessToken = "replacement-access",
                                    refreshToken = "replacement-refresh",
                                ),
                            )
                        }
                        MockResponse().setResponseCode(409)
                            .setHeader("Content-Type", "application/json")
                            .setBody(
                                """{"ok":false,"error":{"code":"MESSAGING_ENROLLMENT_RESET_STALE","message":"Stale"}}""",
                            )
                    }
                    else -> MockResponse().setResponseCode(404)
                }
        }
        val repository = repository(sessions, FakeRefreshTrigger())

        val failure = runCatching {
            repository.recoverMissingSecureMessagingEnrollment(
                TEST_SESSION.fence(),
                unusedActivationFence(),
                RESET_TARGET,
            )
        }.exceptionOrNull()

        assertTrue(failure is SessionInvalidatedException)
        assertEquals("Bearer ${TEST_SESSION.accessToken}", observedAuthorization)
        assertEquals(TEST_SESSION.sessionId, observedSessionId)
        assertEquals("replacement-session", sessions.current()?.sessionId)
    }

    @Test
    fun `logout clears cached projections when secure local erasure reports a failure`() = runTest {
        server.enqueue(jsonResponse(PUSH_UNREGISTER_JSON))
        server.enqueue(jsonResponse(LOGOUT_JSON))
        val erasureFailure = IllegalStateException("Secure messaging erasure failed")
        val sessions = FakeSessionStore(clearFailure = erasureFailure).apply { save(TEST_SESSION) }
        val walletSync = FakeWalletSync()
        val repository = repository(
            sessions = sessions,
            refresh = FakeRefreshTrigger(),
            walletSync = walletSync,
        )

        val observed = runCatching { repository.logout(allDevices = false) }.exceptionOrNull()

        assertEquals(erasureFailure, observed)
        assertNull(sessions.current())
        assertEquals(1, walletSync.clearCalls)
    }

    @Test
    fun `account deletion uses exact preflight step up and clears only after acceptance`() = runTest {
        server.enqueue(jsonResponse(ACCOUNT_DELETION_PREFLIGHT_JSON))
        server.enqueue(jsonResponse(ACCOUNT_DELETION_CHALLENGE_JSON))
        server.enqueue(jsonResponse(ACCOUNT_DELETION_VERIFICATION_JSON))
        server.enqueue(jsonResponse(ACCOUNT_DELETION_ACCEPTED_JSON))
        val sessions = FakeSessionStore().apply { save(TEST_SESSION) }
        val repository = repository(sessions, FakeRefreshTrigger())

        val preflight = repository.accountDeletionPreflight()
        repository.requestAccountDeletion(preflight, confirmation = "DELETE", paymentPin = "2580")

        assertEquals("/api/kit-wallet/v1/account/deletion", server.takeRequest().path)
        val challenge = server.takeRequest()
        assertEquals("/api/kit-wallet/v1/auth/step-up/challenges", challenge.path)
        val challengeBody = challenge.body.readUtf8()
        assertTrue(challengeBody.contains("\"purpose\":\"account_deletion\""))
        assertTrue(challengeBody.contains("\"account_id\":\"11111111-1111-4111-8111-111111111111\""))
        assertTrue(challengeBody.contains("\"action\":\"delete_account\""))
        val verification = server.takeRequest()
        assertEquals(
            "/api/kit-wallet/v1/auth/step-up/challenges/deletion-challenge/verify",
            verification.path,
        )
        assertTrue(verification.body.readUtf8().contains("\"pin\":\"2580\""))
        val deletion = server.takeRequest()
        assertEquals("/api/kit-wallet/v1/account/deletion-requests", deletion.path)
        assertEquals("deletion-step-up-token", deletion.getHeader("X-Kit-Wallet-Step-Up"))
        assertTrue(deletion.body.readUtf8().contains("\"confirmation\":\"DELETE\""))
        assertNull(sessions.current())
    }

    @Test
    fun `accepted account deletion retains cleanup authority when purge is not durable`() = runTest {
        server.enqueue(jsonResponse(ACCOUNT_DELETION_PREFLIGHT_JSON))
        server.enqueue(jsonResponse(ACCOUNT_DELETION_CHALLENGE_JSON))
        server.enqueue(jsonResponse(ACCOUNT_DELETION_VERIFICATION_JSON))
        server.enqueue(jsonResponse(ACCOUNT_DELETION_ACCEPTED_JSON))
        val failure = AccountMessageArchivePurgeNotDurableException(
            IllegalStateException("purge marker unavailable"),
        )
        val history = FakeMessageHistoryRetention(eraseFailure = failure)
        val targetSession = TEST_SESSION.copy(
            accountId = "11111111-1111-4111-8111-111111111111",
        )
        val sessions = FakeSessionStore().apply { save(targetSession) }
        val repository = repository(
            sessions = sessions,
            refresh = FakeRefreshTrigger(),
            messageHistory = history,
        )

        val preflight = repository.accountDeletionPreflight()
        val observed = runCatching {
            repository.requestAccountDeletion(preflight, "DELETE", "2580")
        }.exceptionOrNull()

        assertEquals(failure, observed)
        assertEquals(targetSession, sessions.current())
        assertEquals(listOf(targetSession.fence()), history.scheduleTargets)
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `account deletion keeps the local session when acceptance is not confirmed`() = runTest {
        server.enqueue(jsonResponse(ACCOUNT_DELETION_PREFLIGHT_JSON))
        server.enqueue(jsonResponse(ACCOUNT_DELETION_CHALLENGE_JSON))
        server.enqueue(jsonResponse(ACCOUNT_DELETION_VERIFICATION_JSON))
        server.enqueue(
            MockResponse().setResponseCode(503).setHeader("Content-Type", "application/json")
                .setBody(API_UNAVAILABLE_JSON),
        )
        val sessions = FakeSessionStore().apply { save(TEST_SESSION) }
        val repository = repository(sessions, FakeRefreshTrigger())
        val preflight = repository.accountDeletionPreflight()

        try {
            repository.requestAccountDeletion(preflight, confirmation = "DELETE", paymentPin = "2580")
            fail("Deletion must not clear the phone before the backend confirms acceptance")
        } catch (error: KitWalletApiException) {
            assertEquals(503, error.statusCode)
        }

        assertEquals("session-uuid", sessions.current()?.sessionId)
    }

    private fun kotlinx.coroutines.test.TestScope.repository(
        sessions: FakeSessionStore,
        refresh: FakeRefreshTrigger,
        walletSync: WalletSyncRepository = FakeWalletSync(),
        messageHistory: AccountMessageHistoryRetention = NoOpAccountMessageHistoryRetention,
    ) = RemoteAuthRepository(
        api = api,
        apiCalls = apiCalls,
        messagingEnrollmentRecovery = messagingEnrollmentRecovery,
        tokenRefresher = AuthTokenRefresher(dagger.Lazy { sessionRefreshApi }, apiCalls),
        sessions = sessions,
        deviceIdentity = FakeDeviceIdentity,
        walletSync = walletSync,
        walletRefreshTrigger = refresh,
        pushTokens = PushTokenCoordinator(
            api,
            apiCalls,
            sessions,
            FakePushMessagingTransport,
            backgroundScope,
        ),
        paymentAuthorizer = PaymentAuthorizer(api, apiCalls),
        messageHistory = messageHistory,
        applicationScope = backgroundScope,
    )

    private fun jsonResponse(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private class FakeSessionStore(
        private val clearFailure: Exception? = null,
        private val replacementEvents: MutableList<String>? = null,
    ) : SessionStore {
        private val state = MutableStateFlow<SessionTokens?>(null)
        private var revision = 0L
        override val session: StateFlow<SessionTokens?> = state
        override fun current(): SessionTokens? = state.value
        override fun snapshot() = com.kit.wallet.data.session.SessionSnapshot(
            revision,
            state.value?.fence(),
        )
        override suspend fun save(tokens: SessionTokens) {
            state.value = tokens
            revision++
        }
        override suspend fun saveIfUnchanged(
            expected: com.kit.wallet.data.session.SessionSnapshot,
            tokens: SessionTokens,
        ): Boolean {
            if (snapshot() != expected) return false
            save(tokens)
            return true
        }
        override suspend fun replaceIfUnchangedAfterFinalMessagingSnapshot(
            expected: com.kit.wallet.data.session.SessionSnapshot,
            tokens: SessionTokens,
            finalMessagingSnapshot: suspend (com.kit.wallet.data.session.SessionFence) -> Unit,
        ): Boolean {
            if (snapshot() != expected) return false
            val previous = current()?.fence()
            if (previous != null && previous != tokens.fence()) {
                finalMessagingSnapshot(previous)
            }
            replacementEvents?.add("save-replacement")
            save(tokens)
            return true
        }
        override suspend fun updateProfileSetupState(
            expected: com.kit.wallet.data.session.SessionFence,
            setupState: com.kit.wallet.data.session.ProfileSetupState,
        ): Boolean {
            val current = state.value ?: return false
            if (current.fence() != expected) return false
            save(current.copy(profileSetupState = setupState))
            return true
        }
        override suspend fun <T> withCurrentSession(
            expected: com.kit.wallet.data.session.SessionFence,
            block: suspend (SessionTokens) -> T,
        ): T {
            val current = state.value ?: throw com.kit.wallet.data.session.SessionInvalidatedException()
            if (current.fence() != expected) {
                throw com.kit.wallet.data.session.SessionInvalidatedException()
            }
            return block(current)
        }
        override suspend fun clearIfCurrent(
            expected: com.kit.wallet.data.session.SessionFence,
        ): Boolean {
            if (state.value?.fence() != expected) return false
            clear()
            return true
        }
        override suspend fun recordMessagingResetProofIfCurrent(
            expected: com.kit.wallet.data.session.SessionFence,
            proof: com.kit.wallet.data.session.SecureMessagingResetProofFence,
        ): Boolean {
            val current = state.value ?: return false
            if (current.fence() != expected) return false
            save(current.copy(messagingResetProof = proof))
            return true
        }
        override suspend fun recordMessagingResetPendingIfCurrent(
            expected: com.kit.wallet.data.session.SessionFence,
            pending: com.kit.wallet.data.session.SecureMessagingResetProofFence,
        ): Boolean {
            val current = state.value ?: return false
            if (current.fence() != expected) return false
            val existing = current.messagingResetProof
            if (existing != null) {
                return existing.copy(resultingEnrollmentEpoch = null) == pending
            }
            save(current.copy(messagingResetProof = pending))
            return true
        }
        override suspend fun clearMessagingResetProofIfCurrent(
            expected: com.kit.wallet.data.session.SessionFence,
            proof: com.kit.wallet.data.session.SecureMessagingResetProofFence,
        ): Boolean {
            val current = state.value ?: return false
            if (current.fence() != expected || current.messagingResetProof != proof) return false
            save(current.copy(messagingResetProof = null))
            return true
        }
        override suspend fun clear() {
            state.value = null
            revision++
            clearFailure?.let { throw it }
        }
    }

    private class FakeRefreshTrigger : WalletRefreshTrigger {
        var calls = 0
        override fun refreshNow() { calls++ }
    }

    private object FakeDeviceIdentity : DeviceIdentityProvider {
        override fun registration() = DeviceRegistrationDto(
            installationId = "installation-uuid",
            name = "Test phone",
            appVersion = "0.1.0",
            osVersion = "15",
            model = "Kit Test Phone",
        )
    }

    private object FakePushMessagingTransport : PushMessagingTransport {
        override val provider = "test-push"
        override val configured = true
        override fun initialize() = Unit
        override suspend fun currentToken() = "test-push-token"
    }

    private class FakeWalletSync : WalletSyncRepository {
        var clearCalls = 0
        override suspend fun refresh() = WalletSyncResult(0, 0, false)
        override suspend fun clearCachedUserData(ownerScopeId: String?) {
            clearCalls++
        }
    }

    private class FakeMessageHistoryRetention(
        private val snapshotFailure: Exception? = null,
        private val failSnapshotCall: Int = 1,
        private val eraseFailure: Exception? = null,
        private val snapshotEvents: MutableList<String>? = null,
    ) : AccountMessageHistoryRetention {
        val snapshotTargets = mutableListOf<com.kit.wallet.data.session.SessionFence>()
        val eraseTargets = mutableListOf<com.kit.wallet.data.session.SessionFence>()
        val scheduleTargets = mutableListOf<com.kit.wallet.data.session.SessionFence>()

        override suspend fun snapshotActiveHistory(
            target: com.kit.wallet.data.session.SessionFence,
        ) {
            snapshotTargets += target
            snapshotEvents?.add("snapshot")
            if (snapshotTargets.size == failSnapshotCall) snapshotFailure?.let { throw it }
        }

        override suspend fun eraseAccount(target: com.kit.wallet.data.session.SessionFence) {
            eraseTargets += target
            eraseFailure?.let { throw it }
        }

        override suspend fun scheduleAccountErasure(
            target: com.kit.wallet.data.session.SessionFence,
        ) {
            scheduleTargets += target
            if (eraseFailure is AccountMessageArchivePurgeNotDurableException) throw eraseFailure
        }
    }

    private fun unusedActivationFence(): SecureMessagingSessionFence =
        SecureMessagingSessionFence(
            binding = SecureMessagingSessionBinding(
                sessionEpoch = TEST_SESSION.sessionId,
                userId = "11111111-1111-4111-8111-111111111111",
                serverDeviceId = RESET_TARGET.serverDeviceId,
                installationId = "33333333-3333-4333-8333-333333333333",
            ),
            activationIdentity = Any(),
        )

    private companion object {
        val TEST_SESSION = SessionTokens(
            accessToken = "access-token",
            refreshToken = "refresh-token",
            sessionId = "session-uuid",
        )

        val RESET_TARGET = SecureMessagingEnrollmentResetTarget(
            serverDeviceId = "22222222-2222-4222-8222-222222222222",
            enrollmentEpoch = 7,
            registrationId = 42,
            identityKeySha256 = "1".repeat(64),
            bundleVersion = 3,
        )

        val RESET_PENDING = SecureMessagingResetProofFence(
            serverDeviceId = RESET_TARGET.serverDeviceId,
            previousEnrollmentEpoch = RESET_TARGET.enrollmentEpoch,
            previousRegistrationId = RESET_TARGET.registrationId,
            previousIdentityKeySha256 = RESET_TARGET.identityKeySha256,
            previousBundleVersion = RESET_TARGET.bundleVersion,
        )

        val RESET_APPLIED_JSON = """
            {"ok":true,"data":{"device_id":"22222222-2222-4222-8222-222222222222","previous_enrollment_epoch":7,"enrollment_epoch":8,"enrolled":false,"reset_applied":true}}
        """.trimIndent()

        val RESET_REPLAY_JSON = RESET_APPLIED_JSON.replace(
            "\"reset_applied\":true",
            "\"reset_applied\":false",
        )

        val API_UNAVAILABLE_JSON = """
            {"ok":false,"error":{"code":"TEMPORARILY_UNAVAILABLE","message":"Try again"},"meta":{"request_id":"request-failed"}}
        """.trimIndent()

        val LOGOUT_JSON = """
            {"ok":true,"data":{"revoked":true},"meta":{"request_id":"request-logout","api_version":"v1","server_time":"2026-07-17T12:00:00Z"}}
        """.trimIndent()

        val PUSH_UNREGISTER_JSON = """
            {"ok":true,"data":{"registered":false,"provider":"fcm"},"meta":{"request_id":"request-push-unregister"}}
        """.trimIndent()

        val UNAUTHORIZED_JSON = """
            {"ok":false,"error":{"code":"SESSION_REVOKED","message":"This session is no longer active."},"meta":{"request_id":"request-unauthorized"}}
        """.trimIndent()

        val ACCOUNT_DELETION_PREFLIGHT_JSON = """
            {"ok":true,"data":{"state":"available","can_request":true,"requires_support":false,"closure_requirements":[],"step_up":{"purpose":"account_deletion","intent":{"account_id":"11111111-1111-4111-8111-111111111111","action":"delete_account"}},"confirmation_text":"DELETE","notice":{"version":"kit-account-deletion-2026-07","public_url":"https://pay.kit.africa/account-deletion","deleted_categories":["Profile data"],"retained_categories":["Ledger records"]}},"meta":{"request_id":"request-deletion-preflight"}}
        """.trimIndent()

        val ACCOUNT_DELETION_CHALLENGE_JSON = """
            {"ok":true,"data":{"id":"deletion-challenge","purpose":"account_deletion","intent_hash":"intent-hash","nonce":"nonce","signing_payload":"payload","methods":["pin"],"expires_at":"2026-07-18T13:05:00Z"},"meta":{"request_id":"request-deletion-challenge"}}
        """.trimIndent()

        val ACCOUNT_DELETION_VERIFICATION_JSON = """
            {"ok":true,"data":{"step_up_token":"deletion-step-up-token","expires_at":"2026-07-18T13:05:00Z","method":"pin"},"meta":{"request_id":"request-deletion-verification"}}
        """.trimIndent()

        val ACCOUNT_DELETION_ACCEPTED_JSON = """
            {"ok":true,"data":{"receipt_id":"22222222-2222-4222-8222-222222222222","state":"accepted","account_status":"deletion_pending","requested_at":"2026-07-18T13:00:00Z","requires_support":false,"closure_requirements":[],"notice":{"version":"kit-account-deletion-2026-07","public_url":"https://pay.kit.africa/account-deletion","deleted_categories":["Profile data"],"retained_categories":["Ledger records"]}},"meta":{"request_id":"request-deletion-accepted"}}
        """.trimIndent()

        val AUTHENTICATED_JSON = """
            {"ok":true,"data":{"state":"authenticated","challenge":null,"session":{"access_token":"access-token","refresh_token":"refresh-token","token_type":"Bearer","access_expires_at":"2026-07-16T13:00:00Z","refresh_expires_at":"2026-08-15T12:00:00Z","session_id":"session-uuid"},"user":{"id":"7","name":"Amina Yusuf","email":"amina@example.test","phone":"+256772345678","tag":"KIT-1001","kyc_status":"verified"}},"meta":{"request_id":"request-auth","api_version":"v1","server_time":"2026-07-16T12:00:00Z"}}
        """.trimIndent()

        val PLACEHOLDER_PROFILE_JSON = """
            {"ok":true,"data":{"state":"authenticated","session":{"access_token":"access-token","refresh_token":"refresh-token","token_type":"Bearer","session_id":"session-uuid"},"user":{"id":"7","name":null,"phone":"+256772345678","tag":null,"payment_pin_set":null,"mfa_enabled":null,"profile_setup_required":false}},"meta":{"request_id":"request-placeholder"}}
        """.trimIndent()

        val REGISTRATION_JSON = """
            {"ok":true,"data":{"state":"verification_required","challenge":{"type":"email_verification","method":"email","destination":"a***@example.test","expires_at":"2026-07-16T12:05:00Z"},"user":{"id":"7","name":"Amina Yusuf","email":"amina@example.test","tag":"amina_01"}},"meta":{"request_id":"request-register"}}
        """.trimIndent()

        val NULL_MESSAGE_JSON = """
            {"ok":true,"data":{"message":null},"meta":{"request_id":"request-null-message"}}
        """.trimIndent()

        val BLANK_MESSAGE_JSON = """
            {"ok":true,"data":{"message":"   "},"meta":{"request_id":"request-blank-message"}}
        """.trimIndent()

        val CHALLENGE_JSON = """
            {"ok":true,"data":{"state":"challenge_required","challenge":{"id":"challenge-uuid","type":"phone_otp","expires_at":"2026-07-16T12:05:00Z","resend_after_seconds":59.021593},"session":null,"user":null},"meta":{"request_id":"request-otp","api_version":"v1","server_time":"2026-07-16T12:00:00Z"}}
        """.trimIndent()

        val TOTP_CHALLENGE_JSON = """
            {"ok":true,"data":{"state":"challenge_required","challenge":{"id":"mfa-challenge-uuid","type":"two_factor","method":"totp","destination":"Authenticator app","expires_at":"2026-07-16T12:05:00Z","resend_after_seconds":60},"session":null,"user":null},"meta":{"request_id":"request-mfa","api_version":"v1","server_time":"2026-07-16T12:00:00Z"}}
        """.trimIndent()

        val CAPABILITIES_JSON = """
            {"ok":true,"data":{"api_version":"v1","currency":{"code":"UGX","scale":"2"},"features":{},"authentication":{"phone_otp":false,"firebase_phone":true}},"meta":{"request_id":"request-capabilities","api_version":"v1","server_time":"2026-07-16T12:00:00Z"}}
        """.trimIndent()
    }
}
