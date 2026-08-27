package com.kit.wallet

import com.kit.wallet.data.messaging.MessagingRichMediaCapability
import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.KitWalletApi
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class MessagingRichMediaCapabilityTest {
    private lateinit var server: MockWebServer

    // A fresh instance per case: the 60s refresh throttle keys off System.currentTimeMillis(),
    // so a shared instance would leak one test's cached advertisement into the next.
    private lateinit var capability: MessagingRichMediaCapability

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(KitWalletApi::class.java)
        capability = MessagingRichMediaCapability(api, ApiCallExecutor(moshi))
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `legacy-baseline images send with no capability round trip`() = runTest {
        capability.requireSendable("image/jpeg", 10L * MB)

        assertEquals(0, server.requestCount)
    }

    @Test
    fun `video sends when the live backend advertises kit-media-v1 at 10 MB`() = runTest {
        server.enqueue(capabilitiesResponse(richMediaJson(maximumPlaintextBytes = 10L * MB)))

        capability.requireSendable("video/mp4", 10L * MB)

        assertEquals(1, server.requestCount)
        assertEquals("/api/kit-wallet/v1/capabilities", server.takeRequest().path)
    }

    @Test
    fun `video over the advertised 10 MB cap is refused with the advertised limit`() = runTest {
        server.enqueue(capabilitiesResponse(richMediaJson(maximumPlaintextBytes = 10L * MB)))

        val rejection = assertThrows(IllegalStateException::class.java) {
            runBlocking { capability.requireSendable("video/mp4", 10L * MB + 1L) }
        }

        assertTrue(rejection.message.orEmpty().contains("10 MB"))
        assertEquals(10L * MB, capability.maximumSendableBytes())
    }

    // The send and receive paths stream through a file now, so the compiled cap matches what the
    // service advertises and min(compiled, advertised) stops being the clamp it once was.
    @Test
    fun `a 200 MB advertisement is honoured in full`() = runTest {
        server.enqueue(capabilitiesResponse(richMediaJson(maximumPlaintextBytes = 200L * MB)))

        val rejection = assertThrows(IllegalStateException::class.java) {
            runBlocking { capability.requireSendable("video/mp4", 200L * MB + 1L) }
        }

        assertTrue(rejection.message.orEmpty().contains("200 MB"))
        assertEquals(200L * MB, capability.maximumSendableBytes())
    }

    @Test
    fun `a 150 MB video passes under a 200 MB advertisement`() = runTest {
        server.enqueue(capabilitiesResponse(richMediaJson(maximumPlaintextBytes = 200L * MB)))

        capability.requireSendable("video/mp4", 150L * MB)

        assertEquals(200L * MB, capability.maximumSendableBytes())
    }

    // An advertisement above what this build can actually handle is still clamped: the compiled
    // cap remains the binding side whenever it is the smaller of the two.
    @Test
    fun `an advertisement above the compiled cap is clamped to it`() = runTest {
        server.enqueue(capabilitiesResponse(richMediaJson(maximumPlaintextBytes = 512L * MB)))

        val rejection = assertThrows(IllegalStateException::class.java) {
            runBlocking { capability.requireSendable("video/mp4", 300L * MB) }
        }

        assertTrue(rejection.message.orEmpty().contains("200 MB"))
        assertEquals(200L * MB, capability.maximumSendableBytes())
    }

    @Test
    fun `an incoherent advertisement fails closed for non-images`() = runTest {
        server.enqueue(
            capabilitiesResponse(
                richMediaJson(
                    maximumPlaintextBytes = 10L * MB,
                    maximumCiphertextBytes = 10L * MB + 63L,
                ),
            ),
        )

        val rejection = assertThrows(IllegalStateException::class.java) {
            runBlocking { capability.requireSendable("video/mp4", 1L * MB) }
        }

        assertTrue(rejection.message.orEmpty().contains("not available"))
        assertEquals(10L * MB, capability.maximumSendableBytes())
    }

    @Test
    fun `images above the baseline need an advertisement`() = runTest {
        server.enqueue(capabilitiesResponse(richMediaJson = null))

        val rejection = assertThrows(IllegalStateException::class.java) {
            runBlocking { capability.requireSendable("image/jpeg", 10L * MB + 1L) }
        }

        assertTrue(rejection.message.orEmpty().contains("accepts files up to 10 MB"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `media types outside the advertised list are refused`() = runTest {
        server.enqueue(
            capabilitiesResponse(
                richMediaJson(
                    maximumPlaintextBytes = 10L * MB,
                    mediaTypes = """["image/jpeg","audio/mp4"]""",
                ),
            ),
        )

        val rejection = assertThrows(IllegalStateException::class.java) {
            runBlocking { capability.requireSendable("video/mp4", 1L * MB) }
        }

        assertTrue(rejection.message.orEmpty().contains("not available"))
    }

    private fun capabilitiesResponse(richMediaJson: String?) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(
            """{"ok":true,"data":{"currency":{"code":"UGX","scale":"2"},
            "protocols":{"messaging":{"ready":true,"version":"1",
            "rich_media":${richMediaJson ?: "null"}}}},
            "meta":{"request_id":"request-1"}}""",
        )

    private fun richMediaJson(
        maximumPlaintextBytes: Long,
        maximumCiphertextBytes: Long = maximumPlaintextBytes + 64L,
        mediaTypes: String =
            """["image/jpeg","image/png","audio/mp4","video/mp4","application/pdf"]""",
    ) = """
        {"ready":true,"profile":"kit-media-v1",
        "supported_platforms":["ios","android"],
        "minimum_ciphertext_bytes":64,
        "maximum_plaintext_bytes":$maximumPlaintextBytes,
        "maximum_ciphertext_bytes":$maximumCiphertextBytes,
        "media_types":$mediaTypes}
    """.trimIndent()

    private companion object {
        const val MB = 1024L * 1024L
    }
}
