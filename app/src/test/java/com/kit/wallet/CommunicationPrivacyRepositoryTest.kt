package com.kit.wallet

import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.repository.CommunicationPreferenceChanges
import com.kit.wallet.data.repository.RemoteCommunicationPrivacyRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class CommunicationPrivacyRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: RemoteCommunicationPrivacyRepository

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(KitWalletApi::class.java)
        repository = RemoteCommunicationPrivacyRepository(api, ApiCallExecutor(moshi))
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `nullable preference flags remain off and version is required`() = runTest {
        server.enqueue(jsonResponse(PREFERENCES_NULL_FLAGS))

        val preferences = repository.preferences()

        assertEquals(1L, preferences.version)
        assertFalse(preferences.phoneDiscoverable)
        assertFalse(preferences.directMessageRequestsEnabled)
        assertFalse(preferences.incomingCallsEnabled)
        assertEquals("/api/kit-wallet/v1/communications/preferences", server.takeRequest().path)

        server.enqueue(jsonResponse(PREFERENCES_MISSING_VERSION))
        try {
            repository.preferences()
            fail("A versionless preference response must fail closed")
        } catch (error: IllegalStateException) {
            assertTrue(error.message.orEmpty().contains("valid version"))
        }
    }

    @Test
    fun `patch sends the last read version and only the changed preference`() = runTest {
        server.enqueue(jsonResponse(PREFERENCES_PHONE_ENABLED))

        val updated = repository.updatePreferences(
            expectedVersion = 4,
            changes = CommunicationPreferenceChanges(phoneDiscoverable = true),
        )

        assertEquals(5L, updated.version)
        assertTrue(updated.phoneDiscoverable)
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/api/kit-wallet/v1/communications/preferences", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"version\":4"))
        assertTrue(body.contains("\"phone_discoverable\":true"))
        assertFalse(body.contains("direct_message_requests_enabled"))
        assertFalse(body.contains("incoming_calls_enabled"))
    }

    @Test
    fun `block listing is bounded paginated and tolerant of nullable legacy rows`() = runTest {
        server.enqueue(jsonResponse(BLOCKS_PAGE_ONE))
        server.enqueue(jsonResponse(BLOCKS_PAGE_TWO))

        val blocks = repository.blockedUsers()

        assertEquals(listOf(USER_ONE, USER_TWO), blocks.map { it.userId })
        assertEquals("2026-07-19T10:00:00Z", blocks.first().blockedAt)
        assertEquals(
            "/api/kit-wallet/v1/communications/blocks?limit=100",
            server.takeRequest().path,
        )
        assertEquals(
            "/api/kit-wallet/v1/communications/blocks?cursor=next-page&limit=100",
            server.takeRequest().path,
        )
    }

    @Test
    fun `block and unblock require matching server confirmation`() = runTest {
        server.enqueue(jsonResponse(BLOCK_RESPONSE))
        server.enqueue(jsonResponse(UNBLOCK_RESPONSE))

        val blocked = repository.block("  ${USER_ONE.uppercase()}  ")
        repository.unblock(USER_ONE)

        assertEquals(USER_ONE, blocked.userId)
        assertEquals("PUT", server.takeRequest().method)
        assertEquals(
            "/api/kit-wallet/v1/communications/blocks/$USER_ONE",
            server.takeRequest().let { request ->
                assertEquals("DELETE", request.method)
                request.path
            },
        )
    }

    private fun jsonResponse(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private companion object {
        const val USER_ONE = "11111111-1111-4111-8111-111111111111"
        const val USER_TWO = "22222222-2222-4222-8222-222222222222"

        val PREFERENCES_NULL_FLAGS = """
            {"ok":true,"data":{"version":1,"phone_discoverable":null,"direct_message_requests_enabled":null,"incoming_calls_enabled":null,"updated_at":null}}
        """.trimIndent()
        val PREFERENCES_MISSING_VERSION = """
            {"ok":true,"data":{"version":null,"phone_discoverable":true,"direct_message_requests_enabled":true,"incoming_calls_enabled":true}}
        """.trimIndent()
        val PREFERENCES_PHONE_ENABLED = """
            {"ok":true,"data":{"version":5,"phone_discoverable":true,"direct_message_requests_enabled":false,"incoming_calls_enabled":false,"updated_at":"2026-07-19T09:00:00Z"}}
        """.trimIndent()
        val BLOCKS_PAGE_ONE = """
            {"ok":true,"data":{"items":[
              {"user_id":"$USER_ONE","blocked":true,"blocked_at":"2026-07-19T10:00:00Z","unblocked_at":null},
              {"user_id":null,"blocked":true,"blocked_at":null,"unblocked_at":null},
              {"user_id":"$USER_TWO","blocked":null,"blocked_at":null,"unblocked_at":null}
            ],"page":{"next_cursor":"next-page","has_more":true,"limit":100}}}
        """.trimIndent()
        val BLOCKS_PAGE_TWO = """
            {"ok":true,"data":{"items":[
              {"user_id":"33333333-3333-4333-8333-333333333333","blocked":false,"blocked_at":null,"unblocked_at":"2026-07-19T11:00:00Z"}
            ],"page":{"next_cursor":null,"has_more":false,"limit":100}}}
        """.trimIndent()
        val BLOCK_RESPONSE = """
            {"ok":true,"data":{"user_id":"$USER_ONE","blocked":true,"blocked_at":"2026-07-19T10:00:00Z","unblocked_at":null}}
        """.trimIndent()
        val UNBLOCK_RESPONSE = """
            {"ok":true,"data":{"user_id":"$USER_ONE","blocked":false,"blocked_at":"2026-07-19T10:00:00Z","unblocked_at":"2026-07-19T11:00:00Z"}}
        """.trimIndent()
    }
}
