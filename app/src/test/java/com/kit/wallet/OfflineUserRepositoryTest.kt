package com.kit.wallet

import com.kit.wallet.data.local.ProfileDao
import com.kit.wallet.data.local.ProfileEntity
import com.kit.wallet.data.local.ProfilePhotoDao
import com.kit.wallet.data.local.ProfilePhotoEntity
import com.kit.wallet.data.local.WalletCache
import com.kit.wallet.data.local.WalletEntity
import com.kit.wallet.data.local.WalletTransactionEntity
import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.UpdateProfileRequestAdapter
import com.kit.wallet.data.repository.OfflineUserRepository
import com.kit.wallet.data.repository.ProfilePhotoDirectory
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.data.session.SessionTokens
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

@OptIn(ExperimentalCoroutinesApi::class)
class OfflineUserRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var api: KitWalletApi
    private lateinit var apiCalls: ApiCallExecutor

    /** On the API's own host, because anything else is refused by `isTrustedProfileAvatarUrl`. */
    private val avatarUrl =
        "https://${BuildConfig.KIT_WALLET_BASE_URL.toHttpUrl().host}/profile/user-1/avatar/one"

    private val profileWithAvatarJson = """
        {"ok":true,"data":{"id":"user-1","name":"Amina Yusuf","phone":"+256700000200","tag":"amina","kyc_status":"not_started","email_verified":null,"phone_verified":null,"mfa_enabled":null,"payment_pin_set":null,"profile_setup_required":false,"avatar_url":"$avatarUrl"},"meta":{"request_id":"request-profile-avatar"}}
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        // Assembled the way `StorageModule` assembles it, custom adapter first: the reflective
        // factory would otherwise claim `UpdateProfileRequest` and drop the null that clears a
        // username, and the test would be exercising a serializer the app does not use.
        val moshi = Moshi.Builder()
            .add(UpdateProfileRequestAdapter())
            .add(KotlinJsonAdapterFactory())
            .build()
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

    @Test
    fun `a verified account can drop its username and setup stays complete`() = runTest {
        server.enqueue(jsonResponse(VERIFIED_WITHOUT_USERNAME_JSON))
        val profiles = FakeProfileDao()
        profiles.value.value = verifiedProfileEntity(tag = "amina")
        val repository = repository(profiles)
        runCurrent()

        repository.updateProfile("", "")

        // An explicit null, not an omitted field: omission means "leave my username alone" and
        // would leave the user staring at a switch that did nothing.
        assertEquals("""{"tag":null}""", server.takeRequest().body.readUtf8())
        assertEquals("", repository.profile.value.tag)
        assertEquals("Amina Yusuf", repository.profile.value.legalName)
        assertEquals("Amina Yusuf", repository.profile.value.displayIdentityName)
        // The wait for the cache to catch up has to settle on an empty tag rather than hang on it.
        assertEquals(false, repository.profile.value.profileSetupRequired)
    }

    @Test
    fun `an account that still needs a username never asks the server to drop it`() = runTest {
        server.enqueue(jsonResponse(COMPLETED_PROFILE_JSON))
        val profiles = FakeProfileDao()
        val repository = repository(profiles)
        runCurrent()

        repository.updateProfile("Amina Yusuf", "amina")

        assertEquals(
            """{"name":"Amina Yusuf","tag":"amina"}""",
            server.takeRequest().body.readUtf8(),
        )
    }

    @Test
    fun `the signed-in account's own photo is indexed like everyone else's`() = runTest {
        // Screens that draw a face by user id — a group's participant list, an @tag search result —
        // read one shared directory. Their own row was the one nobody was writing, which is why the
        // person the app knows best appeared to themselves as initials.
        server.enqueue(jsonResponse(profileWithAvatarJson))
        val profiles = FakeProfileDao()
        val photos = ProfilePhotoDirectory(FakeOwnPhotoDao(), FakeSessionStore(), backgroundScope)
        val repository = repository(profiles, photos)

        repository.refreshProfile()
        runCurrent()

        assertEquals(avatarUrl, photos.photoFor("user-1"))
    }

    @Test
    fun `a photo stored by an earlier build is indexed without waiting for the network`() = runTest {
        // Read from the cached row rather than from the response, so an account whose profile was
        // saved before this indexing existed is picked up on the next cold start — offline too.
        val profiles = FakeProfileDao()
        profiles.value.value = ProfileEntity(
            userId = "user-1",
            name = "Amina Yusuf",
            phone = "+256700000200",
            tag = "amina",
            kycLabel = "Not started",
            email = null,
            emailVerified = false,
            profileSetupRequired = false,
            avatarUrl = avatarUrl,
            updatedAtEpochMillis = 1L,
        )
        val photos = ProfilePhotoDirectory(FakeOwnPhotoDao(), FakeSessionStore(), backgroundScope)

        repository(profiles, photos)
        runCurrent()

        assertEquals(avatarUrl, photos.photoFor("user-1"))
    }

    @Test
    fun `taking your own photo down takes the indexed row with it`() = runTest {
        // A profile is the whole story about its own photo, so a profile that comes back without
        // one is a removal — not a gap to keep the old face in.
        server.enqueue(jsonResponse(profileWithAvatarJson))
        server.enqueue(jsonResponse(COMPLETED_PROFILE_JSON))
        val profiles = FakeProfileDao()
        val photos = ProfilePhotoDirectory(FakeOwnPhotoDao(), FakeSessionStore(), backgroundScope)
        val repository = repository(profiles, photos)

        repository.refreshProfile()
        runCurrent()
        assertEquals(avatarUrl, photos.photoFor("user-1"))

        repository.refreshProfile()
        runCurrent()

        assertNull(photos.photoFor("user-1"))
    }

    private fun kotlinx.coroutines.test.TestScope.repository(
        profiles: FakeProfileDao,
        photos: ProfilePhotoDirectory? = null,
    ) =
        OfflineUserRepository(
            profileDao = profiles,
            cache = FakeWalletCache(profiles),
            sessions = FakeSessionStore(),
            api = api,
            apiCalls = apiCalls,
            clock = Clock.fixed(Instant.parse("2026-07-18T12:00:00Z"), ZoneOffset.UTC),
            scope = backgroundScope,
            profilePhotos = photos,
        )

    /** A verified account whose legal name came from its identity document, not from a form. */
    private fun verifiedProfileEntity(tag: String) = ProfileEntity(
        userId = "user-1",
        name = "Amina Yusuf",
        phone = "+256700000200",
        tag = tag,
        kycLabel = "KYC verified",
        email = null,
        emailVerified = false,
        profileSetupRequired = false,
        legalName = "Amina Yusuf",
        usernameRequired = false,
        updatedAtEpochMillis = 1L,
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

    /** Local because `ProfilePhotoDirectoryTest`'s equivalent is private to that file. */
    private class FakeOwnPhotoDao : ProfilePhotoDao {
        private val rows = MutableStateFlow(emptyMap<Pair<String, String>, ProfilePhotoEntity>())

        override fun observeForOwner(ownerScopeId: String): Flow<List<ProfilePhotoEntity>> =
            rows.map { stored -> stored.values.filter { it.ownerScopeId == ownerScopeId } }

        override suspend fun put(photos: List<ProfilePhotoEntity>) {
            rows.value = rows.value + photos.associateBy { it.ownerScopeId to it.userId }
        }

        override suspend fun forget(ownerScopeId: String, userIds: List<String>) {
            rows.value = rows.value - userIds.mapTo(mutableSetOf()) { ownerScopeId to it }
        }

        override suspend fun clear() {
            rows.value = emptyMap()
        }
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

        /** As the API answers once the username is gone: a null tag, and `name` falling back. */
        val VERIFIED_WITHOUT_USERNAME_JSON = """
            {"ok":true,"data":{"id":"user-1","name":"Amina Yusuf","legal_name":"Amina Yusuf","legal_name_verified_at":"2026-08-25T09:00:00Z","username_required":false,"phone":"+256700000200","tag":null,"kyc_status":"approved","email_verified":null,"phone_verified":null,"mfa_enabled":null,"payment_pin_set":null,"profile_setup_required":false},"meta":{"request_id":"request-profile-no-username"}}
        """.trimIndent()
    }
}
