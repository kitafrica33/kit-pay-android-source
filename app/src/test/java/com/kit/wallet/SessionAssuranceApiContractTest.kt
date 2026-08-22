package com.kit.wallet

import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.LoginBiometricAssertionRequest
import com.kit.wallet.data.remote.LoginUnlockPinRequest
import com.kit.wallet.data.remote.EnrollBiometricKeyRequest
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

class SessionAssuranceApiContractTest {
    private lateinit var server: MockWebServer
    private lateinit var api: KitWalletApi

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        api = Retrofit.Builder().baseUrl(server.url("/"))
            .addConverterFactory(
                MoshiConverterFactory.create(Moshi.Builder().add(KotlinJsonAdapterFactory()).build()),
            ).build().create(KitWalletApi::class.java)
    }

    @After fun tearDown() = server.shutdown()

    @Test fun `session assurance and both unlock methods preserve wire names`() = runTest {
        val assurance = """{"session_assurance":{"device_identity":{"status":"verified","required":true,"epoch":2,"verified_at":"2026-08-22T12:00:00Z"},"login_unlock":{"status":"unlocked","required":true,"methods":["pin","biometric_signature"],"method":"pin","unlocked_at":"2026-08-22T12:00:00Z"},"access":"full"},"method":"pin"}"""
        repeat(2) { server.enqueue(ok(assurance)) }
        server.enqueue(ok("""{"challenge_id":"challenge","nonce":"nonce-value-with-at-least-32-characters","signing_payload":"payload","expires_at":"2026-08-22T13:00:00Z"}"""))
        server.enqueue(ok(assurance.replace("\"pin\"}", "\"biometric_signature\"}")))

        assertEquals("full", api.sessionAssurance().data!!.sessionAssurance.access)
        assertEquals("/api/kit-wallet/v1/auth/session-assurance", server.takeRequest().path)

        api.unlockSessionWithPin(LoginUnlockPinRequest("1234"))
        val pin = server.takeRequest()
        assertEquals("/api/kit-wallet/v1/auth/session-unlock/pin", pin.path)
        assertEquals("{\"pin\":\"1234\"}", pin.body.readUtf8())

        api.createLoginBiometricChallenge()
        assertEquals("/api/kit-wallet/v1/auth/session-unlock/biometric/challenge", server.takeRequest().path)

        api.assertLoginBiometricChallenge(LoginBiometricAssertionRequest("challenge", "nonce", "signature"))
        val assertion = server.takeRequest()
        assertEquals("/api/kit-wallet/v1/auth/session-unlock/biometric/assert", assertion.path)
        assertTrue(assertion.body.readUtf8().contains("\"challenge_id\":\"challenge\""))
    }

    @Test fun `biometric key enrollment and removal use the current device route`() = runTest {
        server.enqueue(ok("""{"device_id":"device","algorithm":"ES256","enrolled_at":"2026-08-22T12:00:00Z"}"""))
        server.enqueue(ok("""{"device_id":"device","removed":true}"""))

        api.enrollBiometricKey(
            EnrollBiometricKeyRequest(
                "-----BEGIN PUBLIC KEY-----\nkey\n-----END PUBLIC KEY-----",
                mapOf("platform" to "android"),
            ),
        )
        val enrollment = server.takeRequest()
        assertEquals("PUT", enrollment.method)
        assertEquals("/api/kit-wallet/v1/devices/current/biometric-key", enrollment.path)
        assertTrue(enrollment.body.readUtf8().contains("\"public_key\""))

        assertTrue(api.removeBiometricKey().data!!.removed == true)
        val removal = server.takeRequest()
        assertEquals("DELETE", removal.method)
        assertEquals("/api/kit-wallet/v1/devices/current/biometric-key", removal.path)
    }

    private fun ok(data: String) = MockResponse().setHeader("Content-Type", "application/json")
        .setBody("{\"ok\":true,\"data\":$data}")
}
