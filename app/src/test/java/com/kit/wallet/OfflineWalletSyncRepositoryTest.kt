package com.kit.wallet

import com.kit.wallet.data.local.ProfileEntity
import com.kit.wallet.data.local.WalletCache
import com.kit.wallet.data.local.WalletEntity
import com.kit.wallet.data.local.WalletTransactionEntity
import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.repository.OfflineWalletSyncRepository
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.data.session.SessionTokens
import com.kit.wallet.data.session.SessionInvalidatedException
import com.squareup.moshi.Moshi
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class OfflineWalletSyncRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var cache: FakeWalletCache
    private lateinit var sessions: FakeSessionStore
    private lateinit var repository: OfflineWalletSyncRepository

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(KitWalletApi::class.java)
        cache = FakeWalletCache()
        sessions = FakeSessionStore(
            SessionTokens(
                "access",
                "refresh",
                "session",
                accountId = "user-uuid",
                cacheScopeId = "scope-1",
            ),
        )
        repository = OfflineWalletSyncRepository(
            api = api,
            apiCalls = ApiCallExecutor(moshi),
            cache = cache,
            sessions = sessions,
            clock = Clock.fixed(Instant.parse("2026-07-16T12:00:00Z"), ZoneOffset.UTC),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `refresh replaces cache from bootstrap wallets and first transaction page`() = runTest {
        server.enqueue(jsonResponse(BOOTSTRAP_JSON))
        server.enqueue(jsonResponse(WALLETS_JSON))
        server.enqueue(jsonResponse(TRANSACTIONS_JSON))

        val result = repository.refresh()

        assertEquals(1, result.walletCount)
        assertEquals(1, result.transactionCount)
        assertTrue(result.hasMoreTransactions)
        assertEquals("Amina Yusuf", cache.profile?.name)
        assertEquals(128_450_000L, cache.wallets.single().availableBalanceMinor)
        assertEquals(-8_500_000L, cache.transactions.single().amountMinor)
        assertEquals("cursor-2", cache.nextCursor)
        assertEquals("/api/kit-wallet/v1/bootstrap", server.takeRequest().path)
        assertEquals("/api/kit-wallet/v1/wallets", server.takeRequest().path)
        assertEquals(
            "/api/kit-wallet/v1/wallets/wallet-uuid/transactions?limit=50",
            server.takeRequest().path,
        )
    }

    @Test
    fun `refresh is a no-op without a device session`() = runTest {
        sessions.clear()

        val result = repository.refresh()

        assertEquals(0, result.walletCount)
        assertFalse(result.hasMoreTransactions)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `account switch rejects in flight wallet response without claiming new owner cache`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse =
                when (request.requestUrl?.encodedPath) {
                    "/api/kit-wallet/v1/bootstrap" -> jsonResponse(BOOTSTRAP_JSON)
                    "/api/kit-wallet/v1/wallets" -> {
                        runBlocking {
                            sessions.save(
                                SessionTokens(
                                    "access-b",
                                    "refresh-b",
                                    "session-b",
                                    accountId = "user-b",
                                    cacheScopeId = "scope-b",
                                ),
                            )
                        }
                        jsonResponse(WALLETS_JSON)
                    }
                    else -> MockResponse().setResponseCode(500)
                }
        }

        val failure = runCatching { repository.refresh() }.exceptionOrNull()

        assertTrue(failure is SessionInvalidatedException)
        assertEquals("scope-b", sessions.current()?.cacheScopeId)
        assertEquals("scope-1", cache.ownerScope.value)
        assertNull(cache.selectedWallet("scope-b"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `null bootstrap wallets cannot erase a previously cached account`() = runTest {
        primeCache()
        val beforeProfile = cache.profile
        val beforeWallets = cache.wallets
        val beforeTransactions = cache.transactions
        server.enqueue(jsonResponse(NULL_BOOTSTRAP_WALLETS_JSON))

        expectMalformedRefresh()

        assertEquals(beforeProfile, cache.profile)
        assertEquals(beforeWallets, cache.wallets)
        assertEquals(beforeTransactions, cache.transactions)
    }

    @Test
    fun `null wallet list cannot be interpreted as an authoritative empty account`() = runTest {
        primeCache()
        val beforeWallets = cache.wallets
        val beforeTransactions = cache.transactions
        server.enqueue(jsonResponse(BOOTSTRAP_JSON))
        server.enqueue(jsonResponse(NULL_WALLETS_JSON))

        expectMalformedRefresh()

        assertEquals(beforeWallets, cache.wallets)
        assertEquals(beforeTransactions, cache.transactions)
    }

    @Test
    fun `null transaction items or page cannot erase cached history`() = runTest {
        primeCache()
        val beforeTransactions = cache.transactions
        val beforeCursor = cache.nextCursor

        server.enqueue(jsonResponse(BOOTSTRAP_JSON))
        server.enqueue(jsonResponse(WALLETS_JSON))
        server.enqueue(jsonResponse(NULL_TRANSACTION_ITEMS_JSON))
        expectMalformedRefresh()
        assertEquals(beforeTransactions, cache.transactions)
        assertEquals(beforeCursor, cache.nextCursor)

        server.enqueue(jsonResponse(BOOTSTRAP_JSON))
        server.enqueue(jsonResponse(WALLETS_JSON))
        server.enqueue(jsonResponse(NULL_TRANSACTION_PAGE_JSON))
        expectMalformedRefresh()
        assertEquals(beforeTransactions, cache.transactions)
        assertEquals(beforeCursor, cache.nextCursor)
    }

    private suspend fun primeCache() {
        server.enqueue(jsonResponse(BOOTSTRAP_JSON))
        server.enqueue(jsonResponse(WALLETS_JSON))
        server.enqueue(jsonResponse(TRANSACTIONS_JSON))
        repository.refresh()
    }

    private suspend fun expectMalformedRefresh() {
        try {
            repository.refresh()
            fail("A null authoritative collection must fail the refresh")
        } catch (_: JsonDataException) {
            // Expected: the cache must remain at its last known-good state.
        }
    }

    private fun jsonResponse(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private class FakeSessionStore(initial: SessionTokens?) : SessionStore {
        private val state = MutableStateFlow(initial)
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
        }
    }

    private class FakeWalletCache : WalletCache {
        private val mutableOwner = MutableStateFlow<String?>(null)
        override val ownerScope: StateFlow<String?> = mutableOwner
        var profile: ProfileEntity? = null
        var wallets: List<WalletEntity> = emptyList()
        var transactions: List<WalletTransactionEntity> = emptyList()
        var nextCursor: String? = null

        override suspend fun replaceProfileAndWallets(
            ownerScopeId: String,
            profile: ProfileEntity,
            wallets: List<WalletEntity>,
        ) {
            if (mutableOwner.value != ownerScopeId) clearUserData(null)
            mutableOwner.value = ownerScopeId
            this.profile = profile
            this.wallets = wallets
        }

        override suspend fun replaceProfile(ownerScopeId: String, profile: ProfileEntity) {
            if (mutableOwner.value != ownerScopeId) clearUserData(null)
            mutableOwner.value = ownerScopeId
            this.profile = profile
        }

        override suspend fun replaceWallets(
            ownerScopeId: String,
            wallets: List<WalletEntity>,
        ) {
            check(mutableOwner.value == ownerScopeId)
            this.wallets = wallets
        }

        override suspend fun selectedWallet(ownerScopeId: String): WalletEntity? =
            if (mutableOwner.value != ownerScopeId) null
            else wallets.firstOrNull { it.isPrimary } ?: wallets.firstOrNull()

        override suspend fun replaceTransactions(
            ownerScopeId: String,
            walletUuid: String,
            transactions: List<WalletTransactionEntity>,
            nextCursor: String?,
        ) {
            check(mutableOwner.value == ownerScopeId)
            this.transactions = transactions
            this.nextCursor = nextCursor
        }

        override suspend fun clearUserData(ownerScopeId: String?): Boolean {
            if (ownerScopeId != null && mutableOwner.value != ownerScopeId) return false
            mutableOwner.value = null
            profile = null
            wallets = emptyList()
            transactions = emptyList()
            nextCursor = null
            return true
        }
    }

    private companion object {
        val BOOTSTRAP_JSON = """
            {"ok":true,"data":{"user":{"id":"user-uuid","name":"Amina Yusuf","email":"amina@example.test","phone":"+256772345678","tag":"@amina","kyc_status":"verified","payment_pin_set":null,"mfa_enabled":null,"email_verified":null,"phone_verified":null,"profile_setup_required":null},"wallets":[{"id":"wallet-uuid","name":"Main wallet","account_number":"KIT-1001","account_type":"personal","currency":{"code":"UGX","scale":"2"},"balances":{"available":"1284500.00","ledger":"1284500.00"},"status":"active","kyc_status":"verified","is_primary":true,"updated_at":"2026-07-16T11:00:00Z"}],"devices":[],"selected_wallet_id":"wallet-uuid"},"meta":{"request_id":"request-001","api_version":"v1","server_time":"2026-07-16T12:00:00Z"}}
        """.trimIndent()

        val WALLETS_JSON = """
            {"ok":true,"data":{"items":[{"id":"wallet-uuid","name":"Main wallet","account_number":"KIT-1001","account_type":"personal","currency":{"code":"UGX","scale":"2"},"balances":{"available":"1284500.00","ledger":"1284500.00"},"status":"active","kyc_status":"verified","is_primary":true,"updated_at":"2026-07-16T11:00:00Z"}]},"meta":{"request_id":"request-002","api_version":"v1","server_time":"2026-07-16T12:00:00Z"}}
        """.trimIndent()

        val TRANSACTIONS_JSON = """
            {"ok":true,"data":{"items":[{"id":"tx-uuid","wallet_id":"wallet-uuid","reference":"KIT-ABC123","amount":"85000.00","currency":{"code":"UGX","scale":"2"},"type":"transfer","direction":"debit","status":"completed","counterparty":{"id":"contact-uuid","name":"Brian Kato","phone":"+256700000001"},"note":"Lunch","occurred_at":"2026-07-16T10:30:00Z"}],"page":{"next_cursor":"cursor-2","has_more":true,"limit":50}},"meta":{"request_id":"request-003","api_version":"v1","server_time":"2026-07-16T12:00:00Z"}}
        """.trimIndent()

        val NULL_BOOTSTRAP_WALLETS_JSON = """
            {"ok":true,"data":{"user":{"id":"user-uuid","name":"Amina Yusuf"},"wallets":null,"devices":[]},"meta":{"request_id":"request-null-bootstrap"}}
        """.trimIndent()

        val NULL_WALLETS_JSON = """
            {"ok":true,"data":{"items":null},"meta":{"request_id":"request-null-wallets"}}
        """.trimIndent()

        val NULL_TRANSACTION_ITEMS_JSON = """
            {"ok":true,"data":{"items":null,"page":{"next_cursor":null,"has_more":false,"limit":50}},"meta":{"request_id":"request-null-transactions"}}
        """.trimIndent()

        val NULL_TRANSACTION_PAGE_JSON = """
            {"ok":true,"data":{"items":[],"page":null},"meta":{"request_id":"request-null-page"}}
        """.trimIndent()
    }
}
