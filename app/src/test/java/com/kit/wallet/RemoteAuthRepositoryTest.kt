package com.kit.wallet

import com.kit.wallet.data.auth.AuthOutcome
import com.kit.wallet.data.auth.AuthChallengeKind
import com.kit.wallet.data.auth.DeviceIdentityProvider
import com.kit.wallet.data.auth.RemoteAuthRepository
import com.kit.wallet.data.auth.RemoteRevocationState
import com.kit.wallet.data.notifications.PushMessagingTransport
import com.kit.wallet.data.notifications.PushTokenCoordinator
import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.DeviceRegistrationDto
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.KitWalletApiException
import com.kit.wallet.data.repository.WalletRefreshTrigger
import com.kit.wallet.data.repository.WalletSyncRepository
import com.kit.wallet.data.repository.WalletSyncResult
import com.kit.wallet.data.repository.PaymentAuthorizer
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.data.session.SessionTokens
import com.kit.wallet.data.session.ProfileSetupState
import com.kit.wallet.data.session.SessionInvalidatedException
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
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
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class RemoteAuthRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var api: KitWalletApi
    private lateinit var apiCalls: ApiCallExecutor

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        apiCalls = ApiCallExecutor(moshi)
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(KitWalletApi::class.java)
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
    ) = RemoteAuthRepository(
        api = api,
        apiCalls = apiCalls,
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
        applicationScope = backgroundScope,
    )

    private fun jsonResponse(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private class FakeSessionStore(
        private val clearFailure: Exception? = null,
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

    private companion object {
        val TEST_SESSION = SessionTokens(
            accessToken = "access-token",
            refreshToken = "refresh-token",
            sessionId = "session-uuid",
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
