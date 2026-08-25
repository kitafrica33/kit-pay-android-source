package com.kit.wallet

import com.kit.wallet.data.backup.GoogleAuthorizationException
import com.kit.wallet.data.backup.GoogleDriveClient
import com.kit.wallet.data.backup.GoogleDriveEndpoints
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.ByteArrayOutputStream
import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlinx.coroutines.runBlocking

/**
 * Kit Pay talks to Drive over hand-written REST rather than Google's SDK, so nothing here is
 * covered by somebody else's tests. What matters most is the failure shape: a phone on a patchy
 * connection must resume rather than restart, and a revoked grant must be told apart from a bad
 * afternoon on the network, because the two lead to completely different things being shown to
 * the user.
 */
class GoogleDriveClientTest {
    @get:Rule val temporary = TemporaryFolder()

    private val server = MockWebServer()
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    private val client by lazy {
        GoogleDriveClient(
            client = OkHttpClient.Builder().build(),
            endpoints = GoogleDriveEndpoints(
                revocation = server.url("/revoke"),
                drive = server.url("/drive/v3/"),
                upload = server.url("/upload/drive/v3/"),
            ),
            moshi = moshi,
        )
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun json(code: Int, body: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Test fun `disconnecting hands the whole grant back to Google`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))
        assertTrue(client.revoke("at-1"))

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("token=at-1", recorded.body.readUtf8())
    }

    /**
     * Play Services mints the access tokens, so Drive answering 401 means the user took the grant
     * away from their Google account page rather than that a token aged out. Retrying is pointless;
     * the only way forward is asking again.
     */
    @Test fun `a revoked grant asks for consent rather than a retry`() {
        server.enqueue(json(401, """{"error":"invalid_credentials"}"""))
        val failure = assertThrows(GoogleAuthorizationException::class.java) {
            runBlocking { client.findFile("at-dead", "b.kitbak") }
        }
        assertTrue(failure.requiresSignIn)
    }

    @Test fun `a Google outage is retryable, not a consent problem`() {
        server.enqueue(json(503, """{"error":"backendError"}"""))
        val failure = assertThrows(IOException::class.java) {
            runBlocking { client.findFile("at-1", "b.kitbak") }
        }
        assertFalse(failure is GoogleAuthorizationException)
        assertTrue(failure.message!!.contains("busy"))
    }

    @Test fun `a lookup searches only the private app folder`() = runBlocking {
        server.enqueue(
            json(
                200,
                """{"files":[{"id":"file-1","name":"kit-messages-aa.kitbak","size":"2048",""" +
                    """"modifiedTime":"2026-08-20T10:15:30Z"}]}""",
            ),
        )
        val found = client.findFile("at-1", "kit-messages-aa.kitbak")
        assertEquals("file-1", found?.id)
        assertEquals(2048L, found?.sizeBytes)

        val recorded = server.takeRequest()
        assertEquals("Bearer at-1", recorded.getHeader("Authorization"))
        val url = recorded.requestUrl!!
        assertEquals("appDataFolder", url.queryParameter("spaces"))
        assertEquals(
            "name = 'kit-messages-aa.kitbak' and trashed = false",
            url.queryParameter("q"),
        )
    }

    /** A name with a quote in it must not be able to rewrite Drive's query. */
    @Test fun `a quote in the file name is escaped for the query grammar`() = runBlocking {
        server.enqueue(json(200, """{"files":[]}"""))
        assertNull(client.findFile("at-1", "od' or name != '"))
        val q = server.takeRequest().requestUrl!!.queryParameter("q")
        assertEquals("name = 'od\\' or name != \\'' and trashed = false", q)
    }

    @Test fun `no backup in the account is not an error`() = runBlocking {
        server.enqueue(json(200, """{"files":[]}"""))
        assertNull(client.findFile("at-1", "kit-messages-aa.kitbak"))
    }

    @Test fun `a first upload creates the file inside the app folder`() = runBlocking {
        val source = temporary.newFile("backup.kitbak").apply { writeBytes(ByteArray(64) { 7 }) }
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Location", server.url("/upload/session/1").toString()),
        )
        server.enqueue(json(200, """{"id":"file-9","name":"b.kitbak","size":"64"}"""))

        val uploaded = client.upload("at-1", fileId = null, name = "b.kitbak", source = source)
        assertEquals("file-9", uploaded.id)

        val begin = server.takeRequest()
        assertEquals("POST", begin.method)
        assertEquals("resumable", begin.requestUrl!!.queryParameter("uploadType"))
        assertEquals("64", begin.getHeader("X-Upload-Content-Length"))
        assertTrue(begin.body.readUtf8().contains("appDataFolder"))

        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("bytes 0-63/64", put.getHeader("Content-Range"))
        assertEquals(64L, put.bodySize)
    }

    @Test fun `replacing an existing backup patches it in place`() = runBlocking {
        val source = temporary.newFile("backup.kitbak").apply { writeBytes(ByteArray(16)) }
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Location", server.url("/upload/session/2").toString()),
        )
        server.enqueue(json(200, """{"id":"file-9","name":"b.kitbak","size":"16"}"""))

        client.upload("at-1", fileId = "file-9", name = "b.kitbak", source = source)

        val begin = server.takeRequest()
        // PATCH rather than POST, so there is never a moment with two backups or none.
        assertEquals("PATCH", begin.method)
        assertTrue(begin.path!!.contains("/files/file-9"))
        assertFalse(begin.body.readUtf8().contains("parents"))
    }

    @Test fun `an interrupted upload resumes from where Drive got to`() = runBlocking {
        val source = temporary.newFile("backup.kitbak").apply { writeBytes(ByteArray(100) { 3 }) }
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Location", server.url("/upload/session/3").toString()),
        )
        server.enqueue(MockResponse().setResponseCode(308).setHeader("Range", "bytes=0-39"))
        server.enqueue(json(200, """{"id":"file-9","name":"b.kitbak","size":"100"}"""))

        val uploaded = client.upload("at-1", null, "b.kitbak", source)
        assertEquals("file-9", uploaded.id)

        server.takeRequest()
        assertEquals("bytes 0-99/100", server.takeRequest().getHeader("Content-Range"))
        val resumed = server.takeRequest()
        assertEquals("bytes 40-99/100", resumed.getHeader("Content-Range"))
        assertEquals(60L, resumed.bodySize)
    }

    /** A server that keeps reporting the same offset must end the attempt, not loop forever. */
    @Test fun `an upload that stops making progress gives up`() {
        val source = temporary.newFile("backup.kitbak").apply { writeBytes(ByteArray(100)) }
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Location", server.url("/upload/session/4").toString()),
        )
        repeat(8) {
            server.enqueue(MockResponse().setResponseCode(308).setHeader("Range", "bytes=0-9"))
        }
        assertThrows(IOException::class.java) {
            runBlocking { client.upload("at-1", null, "b.kitbak", source) }
        }
    }

    @Test fun `an empty backup is refused before it reaches the network`() {
        val source = temporary.newFile("empty.kitbak")
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { client.upload("at-1", null, "b.kitbak", source) }
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun `a full Google account says so instead of blaming the network`() {
        val source = temporary.newFile("backup.kitbak").apply { writeBytes(ByteArray(8)) }
        server.enqueue(json(403, """{"error":{"errors":[{"reason":"storageQuotaExceeded"}]}}"""))
        val failure = assertThrows(IOException::class.java) {
            runBlocking { client.upload("at-1", null, "b.kitbak", source) }
        }
        assertFalse(failure is GoogleAuthorizationException)
        assertTrue(failure.message!!.contains("no room"))
    }

    @Test fun `a download streams the archive out`() = runBlocking {
        val bytes = ByteArray(512) { it.toByte() }
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(bytes)))
        val sink = ByteArrayOutputStream()
        val copied = client.download("at-1", "file-9", sink)
        assertEquals(512L, copied)
        assertEquals(bytes.toList(), sink.toByteArray().toList())
        assertEquals("media", server.takeRequest().requestUrl!!.queryParameter("alt"))
    }

    @Test fun `deleting a backup that is already gone is a success`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        client.delete("at-1", "file-9")
        assertEquals("DELETE", server.takeRequest().method)
    }

    @Test fun `revoking is best effort so disconnecting offline still works`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertFalse(client.revoke("rt-1"))
    }
}
