package com.kit.wallet

import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.ApiEnvelope
import com.kit.wallet.data.remote.KitWalletApiException
import com.kit.wallet.data.remote.KIT_NETWORK_UNAVAILABLE_CODE
import com.kit.wallet.data.remote.isKitConnectivityError
import com.kit.wallet.data.messaging.SecureMessagingStateNotReadyException
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.KeyStoreException
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun `wraps genuine network IO as address-free connectivity failure`() = runTest {
        try {
            executor.execute<String> { throw SocketTimeoutException("private-host.test timed out") }
            fail("Expected KitWalletApiException")
        } catch (error: KitWalletApiException) {
            assertEquals(KIT_NETWORK_UNAVAILABLE_CODE, error.code)
            assertTrue(error.connectivity)
            assertTrue(error.isKitConnectivityError())
            assertFalse(error.message.contains("private-host.test"))
        }
    }

    @Test
    fun `raw secure state IOException is not network connectivity`() {
        val secureState = SecureMessagingStateNotReadyException("Secure messaging state is opening")

        assertFalse(secureState.isKitConnectivityError())
    }

    @Test
    fun `does not classify Keystore IOException subtype as connectivity`() {
        val keystoreFailure = TestKeystoreIOException(KeyStoreException("alias unavailable"))
        val wrappedConnectivity = TestKeystoreIOException(
            KitWalletApiException(
                code = KIT_NETWORK_UNAVAILABLE_CODE,
                message = "network boundary nested under local recovery",
                connectivity = true,
            ),
        )

        assertFalse(keystoreFailure.isKitConnectivityError())
        assertFalse(wrappedConnectivity.isKitConnectivityError())
        assertFalse(SocketTimeoutException("raw transport without an API boundary").isKitConnectivityError())
    }

    private class TestKeystoreIOException(cause: Throwable) : IOException(
        "Android Keystore is temporarily unavailable",
        cause,
    )
}
