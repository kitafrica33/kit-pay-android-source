package com.kit.wallet

import com.kit.wallet.data.realtime.ChannelAuthDto
import com.kit.wallet.data.realtime.ChannelAuthRequest
import com.kit.wallet.data.realtime.KitPusherCodec
import com.kit.wallet.data.realtime.KitRealtimeAuthApi
import com.kit.wallet.data.realtime.KitRealtimeClient
import com.kit.wallet.data.realtime.KitRealtimeFrame
import com.kit.wallet.data.realtime.KitRealtimeTransport
import com.squareup.moshi.Moshi
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
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

/** Exercises the two real network boundaries used between upgrade and subscription. */
class KitRealtimeTransportIntegrationTest {
    private lateinit var server: MockWebServer
    private var transport: KitRealtimeClient? = null

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        transport?.cancel()
        server.shutdown()
    }

    @Test
    fun `generated auth adapters POST the advertised path and decode the signature`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"auth":"app-key:signature","channel_data":"{\"user_id\":\"user-1\"}"}""",
                ),
        )
        // Deliberately no KotlinJsonAdapterFactory: this test fails if these wire
        // types ever fall back to the reflection path that R8 broke in code 39.
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient())
            .addConverterFactory(MoshiConverterFactory.create(Moshi.Builder().build()))
            .build()
            .create(KitRealtimeAuthApi::class.java)

        val response = api.authorizeChannel(
            AUTH_PATH,
            ChannelAuthRequest(SOCKET_ID, USER_CHANNEL),
        )

        assertTrue(response.isSuccessful)
        assertEquals(
            ChannelAuthDto("app-key:signature", """{"user_id":"user-1"}"""),
            response.body(),
        )
        val request = server.takeRequest(5, TimeUnit.SECONDS)
        checkNotNull(request)
        assertEquals("POST", request.method)
        assertEquals(AUTH_PATH, request.path)
        assertEquals("application/json; charset=UTF-8", request.getHeader("Content-Type"))
        assertEquals(
            """{"socket_id":"$SOCKET_ID","channel_name":"$USER_CHANNEL"}""",
            request.body.readUtf8(),
        )
    }

    @Test
    fun `real OkHttp transport delivers the production-size handshake and sends subscribe`() {
        val serverReceived = AtomicReference<String?>()
        val serverReceivedLatch = CountDownLatch(1)
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(ESTABLISHED_FRAME)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        serverReceived.set(text)
                        serverReceivedLatch.countDown()
                    }
                },
            ),
        )

        val opened = CountDownLatch(1)
        val frameReceived = CountDownLatch(1)
        val decoded = AtomicReference<KitRealtimeFrame?>()
        val failure = AtomicReference<Throwable?>()
        val client = KitRealtimeClient(OkHttpClient()).also { transport = it }
        client.open(
            server.url("/app/app-key?protocol=7&client=java&version=4.12.0").toString(),
            object : KitRealtimeTransport.Listener {
                override fun onOpen() {
                    opened.countDown()
                }

                override fun onFrame(text: String) {
                    decoded.set(KitPusherCodec.decode(text))
                    frameReceived.countDown()
                }

                override fun onClosed(code: Int, reason: String) = Unit

                override fun onFailure(error: Throwable) {
                    failure.set(error)
                    opened.countDown()
                    frameReceived.countDown()
                    serverReceivedLatch.countDown()
                }
            },
        )

        assertTrue("WebSocket did not open", opened.await(5, TimeUnit.SECONDS))
        assertTrue("Handshake frame was not delivered", frameReceived.await(5, TimeUnit.SECONDS))
        assertNull(failure.get())
        assertEquals(
            KitRealtimeFrame.Established(SOCKET_ID, activityTimeoutSeconds = 30),
            decoded.get(),
        )

        val subscribe = KitPusherCodec.encodeSubscribe(USER_CHANNEL, "app-key:signature")
        assertTrue(client.send(subscribe))
        assertTrue("Subscribe frame did not reach the server", serverReceivedLatch.await(5, TimeUnit.SECONDS))
        assertNull(failure.get())
        assertEquals(subscribe, serverReceived.get())
        assertEquals(
            "/app/app-key?protocol=7&client=java&version=4.12.0",
            server.takeRequest(5, TimeUnit.SECONDS)?.path,
        )
    }

    @Test
    fun `release shrinker explicitly keeps the realtime Retrofit proxy`() {
        val rules = File(repositoryRoot(), "app/proguard-rules.pro").readLines().map(String::trim)
        assertEquals(
            1,
            rules.count {
                it == "-keep interface com.kit.wallet.data.realtime.KitRealtimeAuthApi { *; }"
            },
        )
    }

    private fun repositoryRoot(): File {
        val workingDirectory = File(checkNotNull(System.getProperty("user.dir"))).canonicalFile
        return generateSequence(workingDirectory) { it.parentFile }
            .firstOrNull { File(it, "app/build.gradle.kts").isFile }
            ?: error("Could not locate the Android repository root from $workingDirectory")
    }

    private companion object {
        const val AUTH_PATH = "/api/kit-wallet/v1/messaging/realtime/auth"
        const val SOCKET_ID = "123456789.123456789"
        const val USER_CHANNEL = "private-kit.user.user-1"
        const val ESTABLISHED_FRAME =
            "{\"event\":\"pusher:connection_established\",\"data\":\"{\\\"socket_id\\\":\\\"$SOCKET_ID\\\",\\\"activity_timeout\\\":30}\"}"
    }
}
