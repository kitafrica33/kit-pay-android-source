package com.kit.wallet

import com.kit.wallet.data.messaging.MessagingRichMediaCapability
import com.kit.wallet.data.messaging.MessagingRichMediaCapabilityTemporarilyUnavailableException
import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.KitWalletApiException
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
    private var nowMillis = 1_000_000L

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(KitWalletApi::class.java)
        capability = MessagingRichMediaCapability(api, ApiCallExecutor(moshi)) { nowMillis }
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `legacy-baseline images send with no capability round trip`() = runTest {
        capability.requireSendable("image/jpeg", 10L * MB, ACCOUNT_A)

        assertEquals(0, server.requestCount)
    }

    @Test
    fun `fresh install locally accepts every media kind without a capability round trip`() {
        listOf(
            "image/jpeg",
            "video/mp4",
            "audio/mp4",
            "application/pdf",
        ).forEach { mediaType ->
            capability.requireLocallyQueueable(mediaType, 1L * MB)
        }

        assertEquals(200L * MB, capability.maximumLocallyQueueableBytes())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `local admission enforces compiled type and size without consulting the service`() {
        val unsupported = assertThrows(IllegalStateException::class.java) {
            capability.requireLocallyQueueable("application/x-unsafe", 1L * MB)
        }
        val oversized = assertThrows(IllegalStateException::class.java) {
            capability.requireLocallyQueueable("video/mp4", 200L * MB + 1L)
        }

        assertTrue(unsupported.message.orEmpty().contains("supported"))
        assertTrue(oversized.message.orEmpty().contains("200 MB"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `video sends when the live backend advertises kit-media-v1 at 10 MB`() = runTest {
        server.enqueue(capabilitiesResponse(richMediaJson(maximumPlaintextBytes = 10L * MB)))

        capability.requireSendable("video/mp4", 10L * MB, ACCOUNT_A)

        assertEquals(1, server.requestCount)
        assertEquals("/api/kit-wallet/v1/capabilities", server.takeRequest().path)
    }

    @Test
    fun `offline dispatch remains retryable and a later capability refresh succeeds`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(503)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"ok":false,"error":{"code":"TEMPORARY","message":"Retry later"}}""",
                ),
        )

        assertThrows(KitWalletApiException::class.java) {
            runBlocking { capability.requireSendable("video/mp4", 1L * MB, ACCOUNT_A) }
        }

        server.enqueue(capabilitiesResponse(richMediaJson(maximumPlaintextBytes = 200L * MB)))
        capability.requireSendable("video/mp4", 1L * MB, ACCOUNT_A)

        assertEquals(2, server.requestCount)
    }

    @Test
    fun `video over the advertised 10 MB cap is refused with the advertised limit`() = runTest {
        server.enqueue(capabilitiesResponse(richMediaJson(maximumPlaintextBytes = 10L * MB)))

        val rejection = assertThrows(
            MessagingRichMediaCapabilityTemporarilyUnavailableException::class.java,
        ) {
            runBlocking {
                capability.requireSendable("video/mp4", 10L * MB + 1L, ACCOUNT_A)
            }
        }

        assertTrue(rejection.message.orEmpty().contains("10 MB"))
        assertEquals(10L * MB, capability.maximumSendableBytes())
    }

    // The send and receive paths stream through a file now, so the compiled cap matches what the
    // service advertises and min(compiled, advertised) stops being the clamp it once was.
    @Test
    fun `a 200 MB advertisement is honoured in full`() = runTest {
        server.enqueue(capabilitiesResponse(richMediaJson(maximumPlaintextBytes = 200L * MB)))

        capability.requireSendable("video/mp4", 200L * MB, ACCOUNT_A)

        val rejection = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                capability.requireSendable("video/mp4", 200L * MB + 1L, ACCOUNT_A)
            }
        }

        assertTrue(rejection.message.orEmpty().contains("200 MB"))
        assertEquals(200L * MB, capability.maximumSendableBytes())
    }

    @Test
    fun `a 150 MB video passes under a 200 MB advertisement`() = runTest {
        server.enqueue(capabilitiesResponse(richMediaJson(maximumPlaintextBytes = 200L * MB)))

        capability.requireSendable("video/mp4", 150L * MB, ACCOUNT_A)

        assertEquals(200L * MB, capability.maximumSendableBytes())
    }

    // An advertisement above what this build can actually handle is still clamped: the compiled
    // cap remains the binding side whenever it is the smaller of the two.
    @Test
    fun `an advertisement above the compiled cap is clamped to it`() = runTest {
        server.enqueue(capabilitiesResponse(richMediaJson(maximumPlaintextBytes = 512L * MB)))

        capability.requireSendable("video/mp4", 200L * MB, ACCOUNT_A)

        val rejection = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                capability.requireSendable("video/mp4", 300L * MB, ACCOUNT_A)
            }
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

        val rejection = assertThrows(
            MessagingRichMediaCapabilityTemporarilyUnavailableException::class.java,
        ) {
            runBlocking { capability.requireSendable("video/mp4", 1L * MB, ACCOUNT_A) }
        }

        assertTrue(rejection.message.orEmpty().contains("not available"))
        assertEquals(10L * MB, capability.maximumSendableBytes())
    }

    @Test
    fun `images above the baseline need an advertisement`() = runTest {
        server.enqueue(capabilitiesResponse(richMediaJson = null))

        val rejection = assertThrows(
            MessagingRichMediaCapabilityTemporarilyUnavailableException::class.java,
        ) {
            runBlocking {
                capability.requireSendable("image/jpeg", 10L * MB + 1L, ACCOUNT_A)
            }
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

        val rejection = assertThrows(
            MessagingRichMediaCapabilityTemporarilyUnavailableException::class.java,
        ) {
            runBlocking { capability.requireSendable("video/mp4", 1L * MB, ACCOUNT_A) }
        }

        assertTrue(rejection.message.orEmpty().contains("not available"))
    }

    @Test
    fun `an allowing grant is refreshed after ttl and revocation becomes retryable`() = runTest {
        server.enqueue(capabilitiesResponse(richMediaJson(maximumPlaintextBytes = 200L * MB)))
        capability.requireSendable("video/mp4", 1L * MB, ACCOUNT_A)

        nowMillis += 60_001L
        server.enqueue(capabilitiesResponse(richMediaJson = null))
        assertThrows(MessagingRichMediaCapabilityTemporarilyUnavailableException::class.java) {
            runBlocking { capability.requireSendable("video/mp4", 1L * MB, ACCOUNT_A) }
        }

        assertEquals(2, server.requestCount)
    }

    @Test
    fun `capability grants never cross account scopes`() = runTest {
        server.enqueue(capabilitiesResponse(richMediaJson(maximumPlaintextBytes = 200L * MB)))
        capability.requireSendable("video/mp4", 1L * MB, ACCOUNT_A)

        server.enqueue(capabilitiesResponse(richMediaJson = null))
        assertThrows(MessagingRichMediaCapabilityTemporarilyUnavailableException::class.java) {
            runBlocking { capability.requireSendable("video/mp4", 1L * MB, ACCOUNT_B) }
        }

        assertEquals(2, server.requestCount)
    }

    @Test
    fun `large images must be included in the advertised media type list`() = runTest {
        server.enqueue(
            capabilitiesResponse(
                richMediaJson(
                    maximumPlaintextBytes = 200L * MB,
                    mediaTypes = """["video/mp4","application/pdf"]""",
                ),
            ),
        )

        assertThrows(MessagingRichMediaCapabilityTemporarilyUnavailableException::class.java) {
            runBlocking {
                capability.requireSendable("image/webp", 10L * MB + 1L, ACCOUNT_A)
            }
        }
        assertEquals(1, server.requestCount)
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
        const val ACCOUNT_A = "account-a"
        const val ACCOUNT_B = "account-b"
    }
}
