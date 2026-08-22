package com.kit.wallet.data.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

interface BiometricKeyLifecycle {
    fun remove(accountId: String)
}

@Singleton
class BiometricSigningKey @Inject constructor(
    @ApplicationContext private val context: Context,
) : BiometricKeyLifecycle {
    private val keyStore: KeyStore get() = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    fun hasKey(accountId: String): Boolean = keyStore.containsAlias(alias(accountId))

    fun isAvailable(): Boolean = BiometricManager.from(context).canAuthenticate(
        BiometricManager.Authenticators.BIOMETRIC_STRONG,
    ) == BiometricManager.BIOMETRIC_SUCCESS

    fun publicKeyPem(accountId: String): String {
        val pair = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE).run {
            initialize(
                KeyGenParameterSpec.Builder(alias(accountId), KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(true)
                    .setUserAuthenticationValidityDurationSeconds(-1)
                    .setInvalidatedByBiometricEnrollment(true)
                    .build(),
            )
            if (hasKey(accountId)) null else generateKeyPair()
        }
        val publicKey = pair?.public ?: requireNotNull(keyStore.getCertificate(alias(accountId))).publicKey
        val encoded = Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)
        return buildString {
            appendLine("-----BEGIN PUBLIC KEY-----")
            encoded.chunked(64).forEach(::appendLine)
            append("-----END PUBLIC KEY-----")
        }
    }

    fun signature(accountId: String): Signature = Signature.getInstance("SHA256withECDSA").apply {
        val key = requireNotNull(keyStore.getKey(alias(accountId), null)) {
            "Biometric signing key is unavailable"
        }
        initSign(key as java.security.PrivateKey)
    }

    fun sign(authenticatedSignature: Signature, payload: String): String {
        authenticatedSignature.update(payload.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(
            authenticatedSignature.sign(),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
    }

    override fun remove(accountId: String) {
        keyStore.deleteEntry(alias(accountId))
    }

    private fun alias(accountId: String): String = "kit-pay-biometric-${accountId.filter {
        it.isLetterOrDigit() || it == '-'
    }}"

    private companion object { const val KEYSTORE = "AndroidKeyStore" }
}
