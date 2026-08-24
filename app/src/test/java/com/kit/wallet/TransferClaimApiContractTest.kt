package com.kit.wallet

import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.TransferClaimResolutionRequest
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

class TransferClaimApiContractTest {
    private lateinit var server: MockWebServer
    private lateinit var api: KitWalletApi
    private lateinit var calls: ApiCallExecutor

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(KitWalletApi::class.java)
        calls = ApiCallExecutor(moshi)
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `single claim read uses the authoritative item endpoint`() = runTest {
        server.enqueue(ok(claimJson("pending")))

        calls.execute { api.transferClaim(CLAIM_ID) }

        assertEquals("/api/kit-wallet/v1/transfer-claims/$CLAIM_ID", server.takeRequest().path)
    }

    @Test
    fun `reverse sends the claim-bound step-up token and canonical reason`() = runTest {
        server.enqueue(ok(claimJson("reversed")))

        calls.execute {
            api.reverseTransferClaim(
                CLAIM_ID,
                "step-up-token",
                TransferClaimResolutionRequest("Wrong person"),
            )
        }

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/kit-wallet/v1/transfer-claims/$CLAIM_ID/reverse", request.path)
        assertEquals("step-up-token", request.getHeader("X-Kit-Wallet-Step-Up"))
        assertTrue(request.body.readUtf8().contains("\"reason\":\"Wrong person\""))
    }

    private fun claimJson(status: String) = """
        {
          "id":"$CLAIM_ID",
          "transaction_id":"22222222-2222-4222-8222-222222222222",
          "status":"$status",
          "amount":"2500.00",
          "currency":{"code":"UGX","scale":"2"},
          "sender":{"id":"33333333-3333-4333-8333-333333333333"},
          "recipient":{"id":"44444444-4444-4444-8444-444444444444"}
        }
    """.trimIndent()

    private fun ok(data: String) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("{\"ok\":true,\"data\":$data}")

    private companion object {
        const val CLAIM_ID = "11111111-1111-4111-8111-111111111111"
    }
}
