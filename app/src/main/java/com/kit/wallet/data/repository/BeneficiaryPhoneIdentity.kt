package com.kit.wallet.data.repository

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_PHONE_INPUT_UTF8_BYTES = 64
private const val MIN_E164_DIGITS = 8
private const val MAX_E164_DIGITS = 15
private const val BENEFICIARY_IDENTITY_DOMAIN = "kit-pay-beneficiary-phone-v1\u0000"
private const val BENEFICIARY_PHONE_KEY_ALIAS = "kit_pay_beneficiary_phone_hmac_v1"
private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val HMAC_SHA_256 = "HmacSHA256"
internal const val BENEFICIARY_PHONE_IDENTITY_HEX_LENGTH = 64

/** Deterministic, non-reversible identity for one canonical international phone number. */
interface BeneficiaryPhoneIdentity {
    fun digest(phoneNumber: String?): String?
}

/**
 * Canonical Uganda-local/E.164 identity.
 *
 * Explicit international numbers retain every country-code digit. Local `0...` and nine-digit
 * subscriber forms are interpreted as Uganda because Uganda is the app's current local market.
 * Nothing is ever compared by suffix, so equal subscriber digits in two countries stay distinct.
 */
internal fun canonicalContactPhone(rawPhone: String?): String? {
    val raw = rawPhone?.trim().orEmpty()
    if (raw.isEmpty() || raw.toByteArray(Charsets.UTF_8).size > MAX_PHONE_INPUT_UTF8_BYTES) {
        return null
    }
    val compact = StringBuilder(raw.length)
    for ((index, character) in raw.withIndex()) {
        when {
            character in '0'..'9' -> compact.append(character)
            character == '+' && index == 0 -> Unit
            character.isSupportedPhoneSeparator() -> Unit
            else -> return null
        }
    }
    val digits = compact.toString()
    val international = when {
        raw.startsWith('+') -> digits
        digits.startsWith("00") -> digits.drop(2)
        digits.startsWith('0') -> "256${digits.drop(1)}"
        digits.length == 9 -> "256$digits"
        // A number with ten or more digits and no trunk prefix is treated as country-code first.
        // This covers the no-plus form accepted by the mobile-money entry field without guessing
        // a country from its final subscriber digits.
        digits.length in 10..MAX_E164_DIGITS -> digits
        else -> return null
    }
    if (
        international.length !in MIN_E164_DIGITS..MAX_E164_DIGITS ||
        international.firstOrNull() !in '1'..'9'
    ) {
        return null
    }
    return "+$international"
}

internal fun Char.isSupportedPhoneSeparator(): Boolean = when (this) {
    ' ', '\t', '\u00a0', '\u202f',
    '-', '\u2010', '\u2011', '\u2012', '\u2013', '\u2212',
    '(', ')', '.',
    -> true
    else -> false
}

/** Pure HMAC implementation; production supplies a non-exportable AndroidKeyStore key. */
internal class KeyedBeneficiaryPhoneIdentity(
    private val key: () -> SecretKey,
) : BeneficiaryPhoneIdentity {
    override fun digest(phoneNumber: String?): String? {
        val canonical = canonicalContactPhone(phoneNumber) ?: return null
        return runCatching {
            val bytes = Mac.getInstance(HMAC_SHA_256).run {
                init(key())
                doFinal((BENEFICIARY_IDENTITY_DOMAIN + canonical).toByteArray(Charsets.UTF_8))
            }
            bytes.joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
        }.getOrNull()
    }
}

/**
 * Device-held keyed digest. The phone number is never persisted and the HMAC key cannot be
 * exported from AndroidKeyStore. If the key is temporarily unavailable, matching fails closed to
 * initials; an existing-but-unreadable alias is never replaced behind stored digests.
 */
@Singleton
class AndroidKeystoreBeneficiaryPhoneIdentity @Inject constructor() : BeneficiaryPhoneIdentity {
    private val delegate = KeyedBeneficiaryPhoneIdentity(::key)
    private val cached = object : LinkedHashMap<String, String>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > MAX_IN_MEMORY_IDENTITIES
    }

    override fun digest(phoneNumber: String?): String? {
        val canonical = canonicalContactPhone(phoneNumber) ?: return null
        synchronized(cached) { cached[canonical] }?.let { return it }
        val identity = delegate.digest(canonical) ?: return null
        synchronized(cached) { cached[canonical] = identity }
        return identity
    }

    @Synchronized
    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(BENEFICIARY_PHONE_KEY_ALIAS, null) as? SecretKey)?.let { return it }
        check(!keyStore.containsAlias(BENEFICIARY_PHONE_KEY_ALIAS)) {
            "The beneficiary identity key is temporarily unavailable"
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE)
            .run {
                init(
                    KeyGenParameterSpec.Builder(
                        BENEFICIARY_PHONE_KEY_ALIAS,
                        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                    ).setDigests(KeyProperties.DIGEST_SHA256).build(),
                )
                generateKey()
            }
    }

    private companion object {
        // Bounded process memory only. No canonical phone number is written to disk.
        const val MAX_IN_MEMORY_IDENTITIES = 4_096
    }
}

internal fun isCanonicalBeneficiaryPhoneIdentity(value: String?): Boolean =
    value?.length == BENEFICIARY_PHONE_IDENTITY_HEX_LENGTH &&
        value.all { it in '0'..'9' || it in 'a'..'f' }

/** Opaque server identifier accepted for a local display-only association. */
internal fun canonicalBeneficiaryId(value: String?): String? {
    val id = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
    if (id.length > 128 || id.any(Char::isISOControl)) return null
    return id.takeIf { candidate ->
        candidate.first().isLetterOrDigit() &&
            candidate.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == ':' || it == '.' }
    }
}
