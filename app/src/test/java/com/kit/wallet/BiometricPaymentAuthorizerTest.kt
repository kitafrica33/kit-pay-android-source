package com.kit.wallet

import com.kit.wallet.data.auth.BiometricPaymentApprover
import com.kit.wallet.data.auth.serverAcceptsBiometrics
import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.repository.PaymentAuthorizer
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.data.session.SessionTokens
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class BiometricPaymentAuthorizerTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() = server.shutdown()

    @Test fun `available biometric key signs the server payload and never sends the PIN`() = runTest {
        server.enqueue(ok("""{"id":"challenge","purpose":"wallet_transfer","intent_hash":"hash","nonce":"nonce","signing_payload":"signed payload","methods":["pin","biometric_signature"],"expires_at":"2099-01-01T00:00:00Z"}"""))
        server.enqueue(ok("""{"step_up_token":"token","expires_at":"2099-01-01T00:01:00Z","method":"biometric_signature"}"""))
        val api = Retrofit.Builder().baseUrl(server.url("/"))
            .addConverterFactory(MoshiConverterFactory.create(Moshi.Builder().add(KotlinJsonAdapterFactory()).build()))
            .build().create(KitWalletApi::class.java)
        val approver = RecordingApprover()
        val authorizer = PaymentAuthorizer(
            api, ApiCallExecutor(Moshi.Builder().add(KotlinJsonAdapterFactory()).build()),
            sessionStore(), approver,
        )

        assertEquals("token", authorizer.authorize("wallet_transfer", mapOf("amount" to "100"), ""))
        server.takeRequest()
        val verification = server.takeRequest().body.readUtf8()
        assertEquals("signed payload", approver.payload)
        assertTrue(verification.contains("\"signature\":\"signature\""))
        assertFalse(verification.contains("pin"))
    }

    @Test fun `missing biometric key falls back to PIN when the server permits it`() = runTest {
        server.enqueue(ok("""{"id":"challenge","purpose":"wallet_transfer","intent_hash":"hash","nonce":"nonce","signing_payload":"signed payload","methods":["pin","biometric_signature"],"expires_at":"2099-01-01T00:00:00Z"}"""))
        server.enqueue(ok("""{"step_up_token":"token","expires_at":"2099-01-01T00:01:00Z","method":"pin"}"""))
        val api = Retrofit.Builder().baseUrl(server.url("/"))
            .addConverterFactory(MoshiConverterFactory.create(Moshi.Builder().add(KotlinJsonAdapterFactory()).build()))
            .build().create(KitWalletApi::class.java)
        val authorizer = PaymentAuthorizer(
            api, ApiCallExecutor(Moshi.Builder().add(KotlinJsonAdapterFactory()).build()),
            sessionStore(), object : BiometricPaymentApprover {
                override fun availableFor(accountId: String) = false
                override suspend fun sign(accountId: String, payload: String, reason: String) =
                    error("Biometric signing must not be attempted")
            },
        )

        assertEquals("token", authorizer.authorize("wallet_transfer", mapOf("amount" to "100"), "1234"))
        server.takeRequest()
        val verification = server.takeRequest().body.readUtf8()
        assertTrue(verification.contains("\"pin\":\"1234\""))
        assertFalse(verification.contains("signature"))
    }

    @Test fun `an explicit PIN is honored even when a biometric key is enrolled`() = runTest {
        server.enqueue(ok("""{"id":"challenge","purpose":"wallet_transfer_reverse","intent_hash":"hash","nonce":"nonce","signing_payload":"signed payload","methods":["pin","biometric_signature"],"expires_at":"2099-01-01T00:00:00Z"}"""))
        server.enqueue(ok("""{"step_up_token":"token","expires_at":"2099-01-01T00:01:00Z","method":"pin"}"""))
        val api = Retrofit.Builder().baseUrl(server.url("/"))
            .addConverterFactory(MoshiConverterFactory.create(Moshi.Builder().add(KotlinJsonAdapterFactory()).build()))
            .build().create(KitWalletApi::class.java)
        val authorizer = PaymentAuthorizer(
            api, ApiCallExecutor(Moshi.Builder().add(KotlinJsonAdapterFactory()).build()),
            sessionStore(), object : BiometricPaymentApprover {
                override fun availableFor(accountId: String) = true
                override suspend fun sign(accountId: String, payload: String, reason: String) =
                    error("An explicit PIN must not open biometric approval")
            },
        )

        assertEquals(
            "token",
            authorizer.authorize(
                "wallet_transfer_reverse",
                mapOf("action" to "reverse", "claim_id" to "claim", "reason" to null),
                "2580",
            ),
        )
        val challenge = server.takeRequest()
        assertTrue(challenge.body.readUtf8().contains("\"reason\":null"))
        val verification = server.takeRequest().body.readUtf8()
        assertTrue(verification.contains("\"pin\":\"2580\""))
        assertFalse(verification.contains("signature"))
    }

    @Test fun `unknown cached server methods never advertise biometric approval`() {
        assertFalse(serverAcceptsBiometrics(emptyList()))
        assertFalse(serverAcceptsBiometrics(listOf("pin")))
        assertTrue(serverAcceptsBiometrics(listOf("PIN", "BIOMETRIC_SIGNATURE")))
    }

    @Test fun `exact payment challenge can withdraw biometric approval before prompting`() = runTest {
        server.enqueue(ok("""{"id":"challenge","purpose":"wallet_transfer","intent_hash":"hash","nonce":"nonce","signing_payload":"signed payload","methods":["pin"],"expires_at":"2099-01-01T00:00:00Z"}"""))
        val api = Retrofit.Builder().baseUrl(server.url("/"))
            .addConverterFactory(MoshiConverterFactory.create(Moshi.Builder().add(KotlinJsonAdapterFactory()).build()))
            .build().create(KitWalletApi::class.java)
        val approver = RecordingApprover()
        val authorizer = PaymentAuthorizer(
            api, ApiCallExecutor(Moshi.Builder().add(KotlinJsonAdapterFactory()).build()),
            sessionStore(), approver,
        )

        val failure = runCatching {
            authorizer.authorize("wallet_transfer", mapOf("amount" to "100"), "")
        }.exceptionOrNull()

        assertEquals(
            "Biometric approval is not available for this payment. Use your wallet PIN.",
            failure?.message,
        )
        assertEquals(1, server.requestCount)
        assertEquals(null, approver.payload)
    }

    private class RecordingApprover : BiometricPaymentApprover {
        var payload: String? = null
        override fun availableFor(accountId: String) = accountId == "account"
        override suspend fun sign(accountId: String, payload: String, reason: String): String {
            this.payload = payload
            return "signature"
        }
    }

    private fun sessionStore(): SessionStore {
        val tokens = SessionTokens("access", "refresh", "session", accountId = "account")
        return Proxy.newProxyInstance(SessionStore::class.java.classLoader, arrayOf(SessionStore::class.java)) {
                instance, method, arguments ->
            when (method.name) {
                "current" -> tokens
                "snapshot" -> com.kit.wallet.data.session.SessionSnapshot(0, tokens.fence())
                "toString" -> "BiometricSessionStore"
                "hashCode" -> System.identityHashCode(instance)
                "equals" -> instance === arguments?.firstOrNull()
                else -> error("Unexpected session call: ${method.name}")
            }
        } as SessionStore
    }

    private fun ok(data: String) = MockResponse().setHeader("Content-Type", "application/json")
        .setBody("{\"ok\":true,\"data\":$data}")
}
