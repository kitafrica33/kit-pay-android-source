package com.kit.wallet

import com.kit.wallet.data.remote.BeginContactSyncRequest
import com.kit.wallet.data.remote.ContactSyncChunkDto
import com.kit.wallet.data.remote.ContactSyncChunkResponseDto
import com.kit.wallet.data.remote.ContactSyncRequest
import com.kit.wallet.data.remote.ContactSyncSessionDto
import com.kit.wallet.data.remote.ContactSyncSessionResponseDto
import com.kit.wallet.data.remote.DeviceContactDto
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.repository.validateContactSync
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
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

class ContactSyncApiContractTest {
    private lateinit var server: MockWebServer
    private lateinit var api: KitWalletApi

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(KitWalletApi::class.java)
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `chunked contact sync uses the iOS-compatible wire contract`() = runTest {
        server.enqueue(success(sessionResponse("open")))
        server.enqueue(success(chunkResponse()))
        server.enqueue(success(sessionResponse("finalized", storedCount = 1)))

        api.startContactSync(BeginContactSyncRequest(CLIENT_ID, 1, "full"))
        val start = server.takeRequest()
        assertEquals("/api/kit-wallet/v1/contacts/sync/sessions", start.path)
        assertTrue(start.body.readUtf8().contains("\"client_sync_id\":\"$CLIENT_ID\""))

        api.uploadContactSyncChunk(
            SESSION_ID,
            0,
            ContactSyncRequest(listOf(DeviceContactDto("+256700000001", "Amina"))),
        )
        assertEquals(
            "/api/kit-wallet/v1/contacts/sync/sessions/$SESSION_ID/chunks/0",
            server.takeRequest().path,
        )

        api.finalizeContactSync(SESSION_ID)
        assertEquals(
            "/api/kit-wallet/v1/contacts/sync/sessions/$SESSION_ID/finalize",
            server.takeRequest().path,
        )
    }

    @Test
    fun `contact sync rejects a mismatched replay identity`() {
        val mismatch = session("open").copy(clientSyncId = OTHER_CLIENT_ID)
        val failure = runCatching {
            validateContactSync(mismatch, CLIENT_ID, 1, "open")
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
    }

    private fun success(data: Any): MockResponse {
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val json = when (data) {
            is ContactSyncSessionResponseDto -> moshi.adapter(ContactSyncSessionResponseDto::class.java).toJson(data)
            is ContactSyncChunkResponseDto -> moshi.adapter(ContactSyncChunkResponseDto::class.java).toJson(data)
            else -> error("Unexpected fixture")
        }
        return MockResponse().setHeader("Content-Type", "application/json")
            .setBody("{\"ok\":true,\"data\":$json}")
    }

    private fun sessionResponse(status: String, storedCount: Int? = null) =
        ContactSyncSessionResponseDto(session(status, storedCount))

    private fun chunkResponse() = ContactSyncChunkResponseDto(
        sync = session("open").copy(
            receivedContactCount = 1,
            receivedChunkCount = 1,
            acceptedContactCount = 1,
            missingChunkIndexes = emptyList(),
        ),
        chunk = ContactSyncChunkDto(0, 1, 1, false),
    )

    private fun session(status: String, storedCount: Int? = null) = ContactSyncSessionDto(
        id = SESSION_ID,
        clientSyncId = CLIENT_ID,
        generation = 1,
        status = status,
        snapshotScope = "full",
        chunkSize = 500,
        totalContactCount = 1,
        totalChunkCount = 1,
        receivedContactCount = if (status == "finalized") 1 else 0,
        receivedChunkCount = if (status == "finalized") 1 else 0,
        acceptedContactCount = if (status == "finalized") 1 else 0,
        storedContactCount = storedCount,
        missingChunkIndexes = if (status == "finalized") emptyList() else listOf(0),
        expiresAt = "2026-08-22T13:00:00Z",
    )

    private companion object {
        const val SESSION_ID = "43ab13b1-860c-488f-9b53-5becc9722890"
        const val CLIENT_ID = "04526aa4-34ee-4bff-bc58-382c1cffde02"
        const val OTHER_CLIENT_ID = "cbd35ad7-cee7-4987-a35d-e677b9513961"
    }
}
