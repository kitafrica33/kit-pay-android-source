package com.kit.wallet

import com.kit.wallet.data.local.ProfileDao
import com.kit.wallet.data.local.ProfileEntity
import com.kit.wallet.data.local.WalletCache
import com.kit.wallet.data.local.WalletEntity
import com.kit.wallet.data.local.WalletTransactionEntity
import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.repository.OfflineUserRepository
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.data.session.SessionTokens
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class OfflineUserRepositoryTest {
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
    fun tearDown() = server.shutdown()

    @Test
    fun `email attachment parses fractional cooldown and sends normalized address`() = runTest {
        server.enqueue(jsonResponse(EMAIL_CHALLENGE_JSON))
        val repository = repository(FakeProfileDao())

        val challenge = repository.requestEmailAttachment(" Amina@Example.Test ")

        assertEquals("email-challenge", challenge.id)
        assertEquals("a***@example.test", challenge.destination)
        assertEquals(60L, challenge.resendAfterSeconds)
        val request = server.takeRequest()
        assertEquals("/api/kit-wallet/v1/profile/email", request.path)
        assertTrue(request.body.readUtf8().contains("\"email\":\"amina@example.test\""))
    }

    @Test
    fun `verified email response is persisted with null flags normalized`() = runTest {
        server.enqueue(jsonResponse(VERIFIED_EMAIL_JSON))
        val profiles = FakeProfileDao()
        val repository = repository(profiles)

        repository.verifyEmailAttachment("email-challenge", "123456")

        assertEquals("amina@example.test", profiles.value.value?.email)
        assertEquals(true, profiles.value.value?.emailVerified)
        val request = server.takeRequest()
        assertEquals("/api/kit-wallet/v1/profile/email/verify", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"challenge_id\":\"email-challenge\""))
        assertTrue(body.contains("\"code\":\"123456\""))
    }

    @Test
    fun `profile update returns after completed identity is observable`() = runTest {
        server.enqueue(jsonResponse(COMPLETED_PROFILE_JSON))
        val profiles = FakeProfileDao()
        val repository = repository(profiles)

        repository.updateProfile("Amina Yusuf", "amina")

        assertEquals("Amina Yusuf", repository.profile.value.name)
        assertEquals("amina", repository.profile.value.tag)
        assertEquals(false, repository.profile.value.profileSetupRequired)
        val request = server.takeRequest()
        assertEquals("/api/kit-wallet/v1/profile", request.path)
        assertEquals("PATCH", request.method)
    }

    private fun kotlinx.coroutines.test.TestScope.repository(profiles: FakeProfileDao) =
        OfflineUserRepository(
            profileDao = profiles,
            cache = FakeWalletCache(profiles),
            sessions = FakeSessionStore(),
            api = api,
            apiCalls = apiCalls,
            clock = Clock.fixed(Instant.parse("2026-07-18T12:00:00Z"), ZoneOffset.UTC),
            scope = backgroundScope,
        )

    private fun jsonResponse(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private class FakeProfileDao : ProfileDao {
        val value = MutableStateFlow<ProfileEntity?>(null)
        override fun observeForOwner(ownerScopeId: String, ownerKey: String): Flow<ProfileEntity?> =
            value
        override suspend fun upsert(profile: ProfileEntity) { value.value = profile }
        override suspend fun clear() { value.value = null }
    }

    private class FakeSessionStore : SessionStore {
        private val value = MutableStateFlow<SessionTokens?>(
            SessionTokens(
                "access",
                "refresh",
                "session",
                accountId = "user-1",
                cacheScopeId = "scope-1",
            ),
        )
        private var revision = 0L
        override val session: StateFlow<SessionTokens?> = value
        override fun current(): SessionTokens? = value.value
        override fun snapshot() = com.kit.wallet.data.session.SessionSnapshot(
            revision,
            value.value?.fence(),
        )
        override suspend fun save(tokens: SessionTokens) {
            value.value = tokens
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
            state: com.kit.wallet.data.session.ProfileSetupState,
        ): Boolean {
            val current = value.value ?: return false
            if (current.fence() != expected) return false
            save(current.copy(profileSetupState = state))
            return true
        }
        override suspend fun <T> withCurrentSession(
            expected: com.kit.wallet.data.session.SessionFence,
            block: suspend (SessionTokens) -> T,
        ): T {
            val current = requireNotNull(value.value)
            check(current.fence() == expected)
            return block(current)
        }
        override suspend fun clearIfCurrent(
            expected: com.kit.wallet.data.session.SessionFence,
        ): Boolean {
            if (value.value?.fence() != expected) return false
            clear()
            return true
        }
        override suspend fun clear() {
            value.value = null
            revision++
        }
    }

    private class FakeWalletCache(
        private val profiles: FakeProfileDao,
    ) : WalletCache {
        private val owner = MutableStateFlow<String?>("scope-1")
        override val ownerScope: Flow<String?> = owner

        override suspend fun replaceProfile(ownerScopeId: String, profile: ProfileEntity) {
            owner.value = ownerScopeId
            profiles.upsert(profile)
        }

        override suspend fun replaceProfileAndWallets(
            ownerScopeId: String,
            profile: ProfileEntity,
            wallets: List<WalletEntity>,
        ) = replaceProfile(ownerScopeId, profile)

        override suspend fun replaceWallets(
            ownerScopeId: String,
            wallets: List<WalletEntity>,
        ) = Unit

        override suspend fun selectedWallet(ownerScopeId: String): WalletEntity? = null

        override suspend fun replaceTransactions(
            ownerScopeId: String,
            walletUuid: String,
            transactions: List<WalletTransactionEntity>,
            nextCursor: String?,
        ) = Unit

        override suspend fun clearUserData(ownerScopeId: String?): Boolean {
            if (ownerScopeId != null && owner.value != ownerScopeId) return false
            owner.value = null
            profiles.clear()
            return true
        }
    }

    private companion object {
        val EMAIL_CHALLENGE_JSON = """
            {"ok":true,"data":{"state":"challenge_required","challenge":{"id":"email-challenge","type":"email_attachment","method":"email","destination":"a***@example.test","expires_at":"2026-07-18T12:05:00Z","resend_after_seconds":59.021593}},"meta":{"request_id":"request-email"}}
        """.trimIndent()

        val VERIFIED_EMAIL_JSON = """
            {"ok":true,"data":{"id":"user-1","name":"Amina Yusuf","email":"amina@example.test","phone":"+256700000200","tag":"amina","kyc_status":"not_started","email_verified":true,"phone_verified":null,"mfa_enabled":null,"payment_pin_set":null,"profile_setup_required":false},"meta":{"request_id":"request-email-verified"}}
        """.trimIndent()

        val COMPLETED_PROFILE_JSON = """
            {"ok":true,"data":{"id":"user-1","name":"Amina Yusuf","phone":"+256700000200","tag":"amina","kyc_status":"not_started","email_verified":null,"phone_verified":null,"mfa_enabled":null,"payment_pin_set":null,"profile_setup_required":false},"meta":{"request_id":"request-profile"}}
        """.trimIndent()
    }
}
