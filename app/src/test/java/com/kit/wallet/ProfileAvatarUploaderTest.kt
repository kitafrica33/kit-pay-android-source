package com.kit.wallet

import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.ProfileAvatarUploader
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
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

class ProfileAvatarUploaderTest {
    private lateinit var server: MockWebServer
    private lateinit var uploader: ProfileAvatarUploader

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(KitWalletApi::class.java)
        uploader = ProfileAvatarUploader(api, ApiCallExecutor(moshi), OkHttpClient())
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `uploads finalizes waits for the clean scan and attaches the exact asset`() = runTest {
        val bytes = ByteArray(1_024) { it.toByte() }
        server.enqueue(jsonResponse(intentJson(uploadUrl = server.url("/direct-upload").toString())))
        server.enqueue(MockResponse().setResponseCode(202).setBody("{}"))
        server.enqueue(jsonResponse(assetJson(status = "processing", scan = "pending")))
        server.enqueue(jsonResponse(assetJson(status = "ready", scan = "clean")))
        server.enqueue(jsonResponse(userJson()))

        // The first poll reports processing; the retry delay is skipped by the test scheduler.
        val user = uploader.upload(bytes)

        assertEquals("https://cdn.example/avatar.jpg", user.avatarUrl)
        val intent = server.takeRequest()
        assertEquals("/api/kit-wallet/v1/media/upload-intents", intent.path)
        val intentBody = intent.body.readUtf8()
        assertTrue(intentBody.contains("\"purpose\":\"avatar\""))
        assertTrue(intentBody.contains("\"byte_size\":1024"))
        val direct = server.takeRequest()
        assertEquals("PUT", direct.method)
        assertEquals("/direct-upload", direct.path)
        assertEquals("upload-token", direct.getHeader("X-Kit-Media-Upload-Token"))
        assertEquals(1_024L, direct.bodySize)
        val finalize = server.takeRequest()
        assertEquals("/api/kit-wallet/v1/media/$ASSET_ID/finalize", finalize.path)
        val poll = server.takeRequest()
        assertEquals("/api/kit-wallet/v1/media/$ASSET_ID", poll.path)
        val attach = server.takeRequest()
        assertEquals("/api/kit-wallet/v1/profile/avatar", attach.path)
        assertTrue(attach.body.readUtf8().contains(ASSET_ID))
    }

    @Test
    fun `rejected scans fail closed without attaching`() = runTest {
        server.enqueue(jsonResponse(intentJson(uploadUrl = server.url("/direct-upload").toString())))
        server.enqueue(MockResponse().setResponseCode(202).setBody("{}"))
        server.enqueue(jsonResponse(assetJson(status = "processing", scan = "pending")))
        server.enqueue(jsonResponse(assetJson(status = "ready", scan = "infected")))

        val rejection = assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { uploader.upload(ByteArray(16)) }
        }

        assertTrue(rejection.message.orEmpty().contains("rejected"))
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `insecure upload targets are refused before any bytes leave the device`() = runTest {
        server.enqueue(jsonResponse(intentJson(uploadUrl = "http://cdn.example/direct-upload")))

        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { uploader.upload(ByteArray(16)) }
        }

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `oversized photos are refused locally`() = runTest {
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                uploader.upload(ByteArray(ProfileAvatarUploader.MAX_AVATAR_BYTES + 1))
            }
        }
        assertEquals(0, server.requestCount)
    }

    private fun jsonResponse(data: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody("""{"ok":true,"data":$data,"meta":{"request_id":"request-1"}}""")

    private fun intentJson(uploadUrl: String) = """
        {"asset":${assetJson(status = "pending_upload", scan = "pending")},
        "upload":{"method":"PUT","url":"$uploadUrl",
        "headers":{"X-Kit-Media-Upload-Token":"upload-token"}}}
    """.trimIndent()

    private fun assetJson(status: String, scan: String) = """
        {"id":"$ASSET_ID","status":"$status","scan":{"status":"$scan"}}
    """.trimIndent()

    private fun userJson() = """
        {"id":"account-1","name":"Grace","avatar_url":"https://cdn.example/avatar.jpg"}
    """.trimIndent()

    private companion object {
        const val ASSET_ID = "0199aaaa-bbbb-4ccc-8ddd-eeeeffff0009"
    }
}
