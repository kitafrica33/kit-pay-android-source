package com.kit.wallet

import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.ApiEnvelope
import com.kit.wallet.data.remote.KitWalletApiException
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class ApiCallExecutorTest {
    private val executor = ApiCallExecutor(
        Moshi.Builder().add(KotlinJsonAdapterFactory()).build(),
    )

    @Test
    fun `parses canonical error envelope from non-2xx response`() = runTest {
        val body = """
            {
              "ok": false,
              "error": {
                "code": "OTP_INVALID",
                "message": "The verification code is invalid.",
                "details": {"remaining_attempts": 2}
              },
              "meta": {"request_id": "request-123", "api_version": "v1"}
            }
        """.trimIndent().toResponseBody("application/json".toMediaType())
        val httpError = HttpException(Response.error<ApiEnvelope<String>>(422, body))

        try {
            executor.execute<String> { throw httpError }
            fail("Expected KitWalletApiException")
        } catch (error: KitWalletApiException) {
            assertEquals("OTP_INVALID", error.code)
            assertEquals("The verification code is invalid.", error.message)
            assertEquals(422, error.statusCode)
            assertEquals(2.0, error.details["remaining_attempts"])
        }
    }
}
