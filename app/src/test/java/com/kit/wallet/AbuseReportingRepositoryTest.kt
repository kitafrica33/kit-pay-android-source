package com.kit.wallet

import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.SessionHeaderInterceptor
import com.kit.wallet.data.repository.AbuseReportReason
import com.kit.wallet.data.repository.AbuseReportRequest
import com.kit.wallet.data.repository.AbuseReportSelectedMessage
import com.kit.wallet.data.repository.AbuseReportTargetType
import com.kit.wallet.data.repository.RemoteAbuseReportingRepository
import com.kit.wallet.data.session.SessionInvalidatedException
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class AbuseReportingRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: RemoteAbuseReportingRepository
    private lateinit var sessions: MutableTestSessionStore

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        sessions = MutableTestSessionStore(testSession(CURRENT, sessionId = REPORTER_SESSION))
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(KitWalletApi::class.java)
        repository = RemoteAbuseReportingRepository(api, ApiCallExecutor(moshi), sessions)
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `submission matches compatibility endpoint header consent and plaintext contract`() = runTest {
        server.enqueue(successResponse())
        val request = reportRequest()

        val receipt = repository.submit(request, IDEMPOTENCY_KEY)

        assertEquals(REPORT, receipt.id)
        assertEquals(1, receipt.selectedMessageCount)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/kit-wallet/v1/communications/reports", recorded.path)
        assertEquals(IDEMPOTENCY_KEY, recorded.getHeader("Idempotency-Key"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"target_type\":\"message\""))
        assertTrue(body.contains("\"reported_user_id\":\"$REPORTED\""))
        assertTrue(body.contains("\"message_id\":\"$MESSAGE\""))
        assertTrue(body.contains("\"plaintext\":\"exact & unchanged\""))
        assertTrue(body.contains("\"share_report_with_moderators\":true"))
        assertTrue(body.contains("\"share_selected_message_plaintext\":true"))
    }

    @Test
    fun `metadata-only report sends no selected messages and denies plaintext consent`() = runTest {
        val request = reportRequest().copy(selectedMessages = emptyList())
        server.enqueue(successResponse(selectedCount = 0))

        repository.submit(request, IDEMPOTENCY_KEY)

        val body = server.takeRequest().body.readUtf8()
        assertTrue(!body.contains("\"selected_messages\""))
        assertTrue(body.contains("\"share_selected_message_plaintext\":false"))
    }

    @Test
    fun `mismatched malformed or non-confirming receipt is rejected`() = runTest {
        server.enqueue(successResponse(reason = "spam"))
        try {
            repository.submit(reportRequest(), IDEMPOTENCY_KEY)
            fail("A receipt for another request must fail")
        } catch (error: IllegalStateException) {
            assertTrue(error.message.orEmpty().contains("did not confirm"))
        }

        server.enqueue(successResponse(messageId = "not-a-uuid"))
        try {
            repository.submit(reportRequest(), "android-abuse-report-22222222-2222-4222-8222-222222222222")
            fail("A malformed message ID must fail")
        } catch (error: IllegalStateException) {
            assertTrue(error.message.orEmpty().contains("did not confirm"))
        }
    }

    @Test
    fun `invalid replay key is refused before a network request`() = runTest {
        try {
            repository.submit(reportRequest(), "short")
            fail("An invalid idempotency key must fail")
        } catch (_: IllegalArgumentException) {
            Unit
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `submission cannot cross into a replacement login before interception`() = runTest {
        server.enqueue(successResponse())
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val switchingSessions = MutableTestSessionStore(
            testSession(CURRENT, sessionId = REPORTER_SESSION),
        )
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                runBlocking {
                    switchingSessions.save(testSession(OTHER, sessionId = REPLACEMENT_SESSION))
                }
                chain.proceed(chain.request())
            }
            .addInterceptor(SessionHeaderInterceptor(switchingSessions))
            .build()
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(KitWalletApi::class.java)
        val fenced = RemoteAbuseReportingRepository(
            api,
            ApiCallExecutor(moshi),
            switchingSessions,
        )

        val failure = runCatching {
            fenced.submit(reportRequest(), IDEMPOTENCY_KEY)
        }.exceptionOrNull()

        assertTrue("expected session invalidation, got $failure", failure is SessionInvalidatedException)
        assertEquals(0, server.requestCount)
    }

    private fun reportRequest() = AbuseReportRequest(
        reporterUserId = CURRENT,
        targetType = AbuseReportTargetType.MESSAGE,
        reportedUserId = REPORTED,
        conversationId = CONVERSATION,
        messageId = MESSAGE,
        reason = AbuseReportReason.HARASSMENT_OR_BULLYING,
        reporterNote = "Please review",
        selectedMessages = listOf(
            AbuseReportSelectedMessage(MESSAGE, REPORTED, "exact & unchanged"),
        ),
    )

    private fun successResponse(
        reason: String = "harassment_or_bullying",
        messageId: String = MESSAGE,
        selectedCount: Int = 1,
    ) = MockResponse()
        .setResponseCode(201)
        .setHeader("Content-Type", "application/json")
        .setBody(
            """
            {"ok":true,"data":{"id":"$REPORT","status":"received","target_type":"message","reason_code":"$reason","conversation_id":"$CONVERSATION","message_id":"$messageId","selected_message_count":$selectedCount,"submitted_at":"2026-08-25T12:00:00Z"}}
            """.trimIndent(),
        )

    private companion object {
        const val CURRENT = "11111111-1111-4111-8111-111111111111"
        const val OTHER = "99999999-9999-4999-8999-999999999999"
        const val REPORTED = "22222222-2222-4222-8222-222222222222"
        const val CONVERSATION = "55555555-5555-4555-8555-555555555555"
        const val MESSAGE = "66666666-6666-4666-8666-666666666666"
        const val REPORT = "77777777-7777-4777-8777-777777777777"
        const val IDEMPOTENCY_KEY =
            "android-abuse-report-11111111-1111-4111-8111-111111111111"
        const val REPORTER_SESSION = "reporter-session"
        const val REPLACEMENT_SESSION = "replacement-session"
    }
}
