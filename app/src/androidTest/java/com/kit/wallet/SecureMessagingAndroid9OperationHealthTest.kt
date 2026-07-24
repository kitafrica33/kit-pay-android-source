package com.kit.wallet

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.kit.wallet.data.messaging.AndroidKeystoreMessagingRecordCipher
import com.kit.wallet.data.messaging.EncryptedMessagingRecord
import com.kit.wallet.data.messaging.SecureMessagingRecordKeyTemporarilyUnavailableException
import com.kit.wallet.data.messaging.SecureMessagingStateRetryableException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 28, maxSdkVersion = 28)
class SecureMessagingAndroid9OperationHealthTest {
    @Test
    fun missing_alias_does_not_erase_cipher_mode_failure_evidence() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val keyHealth = context.getSharedPreferences(KEY_HEALTH_PREFERENCES, Context.MODE_PRIVATE)
        val cipher = AndroidKeystoreMessagingRecordCipher(context)
        val firstFailureAt = System.currentTimeMillis() - 20_000L
        val aad = "kit-api28-missing-alias-health".toByteArray()
        val missingRecord = EncryptedMessagingRecord(
            iv = ByteArray(12),
            ciphertext = ByteArray(16),
        )

        try {
            assertTrue(keyHealth.edit().clear().commit())
            cipher.eraseKey()
            assertTrue(
                keyHealth.edit()
                    .putInt(KEY_ENCRYPT_UNRECOVERABLE_COUNT, 2)
                    .putLong(KEY_ENCRYPT_FIRST_UNRECOVERABLE_AT, firstFailureAt)
                    .putInt(KEY_DECRYPT_UNRECOVERABLE_COUNT, 2)
                    .putLong(KEY_DECRYPT_FIRST_UNRECOVERABLE_AT, firstFailureAt)
                    .commit(),
            )

            val failure = runCatching { cipher.decrypt(aad, missingRecord) }.exceptionOrNull()

            assertTrue(failure is SecureMessagingStateRetryableException)
            assertTrue(
                generateSequence(checkNotNull(failure) as Throwable?) { it.cause }
                    .any { it is SecureMessagingRecordKeyTemporarilyUnavailableException },
            )
            assertEquals(2, keyHealth.getInt(KEY_ENCRYPT_UNRECOVERABLE_COUNT, 0))
            assertEquals(
                firstFailureAt,
                keyHealth.getLong(KEY_ENCRYPT_FIRST_UNRECOVERABLE_AT, 0L),
            )
            assertEquals(2, keyHealth.getInt(KEY_DECRYPT_UNRECOVERABLE_COUNT, 0))
            assertEquals(
                firstFailureAt,
                keyHealth.getLong(KEY_DECRYPT_FIRST_UNRECOVERABLE_AT, 0L),
            )
            assertEquals(1, keyHealth.getInt(KEY_MISSING_COUNT, 0))
            assertTrue(keyHealth.getLong(KEY_FIRST_MISSING_AT, 0L) > 0L)
        } finally {
            aad.fill(0)
            missingRecord.iv.fill(0)
            missingRecord.ciphertext.fill(0)
            runCatching { cipher.eraseKey() }
            keyHealth.edit().clear().commit()
        }
    }

    @Test
    fun successful_decrypt_does_not_erase_encrypt_failure_evidence() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val keyHealth = context.getSharedPreferences(KEY_HEALTH_PREFERENCES, Context.MODE_PRIVATE)
        val cipher = AndroidKeystoreMessagingRecordCipher(context)
        val aad = "kit-api28-operation-health".toByteArray()
        val plaintext = "published enrollment awaiting its first durable update".toByteArray()
        var encrypted: EncryptedMessagingRecord? = null
        var rewritten: EncryptedMessagingRecord? = null
        var restored: ByteArray? = null

        try {
            assertTrue(keyHealth.edit().clear().commit())
            cipher.eraseKey()
            encrypted = cipher.encrypt(aad, plaintext, allowKeyCreation = true)

            val firstFailureAt = System.currentTimeMillis() - 20_000L
            assertTrue(
                keyHealth.edit()
                    .putInt(KEY_ENCRYPT_UNRECOVERABLE_COUNT, 3)
                    .putLong(KEY_ENCRYPT_FIRST_UNRECOVERABLE_AT, firstFailureAt)
                    .putInt(KEY_DECRYPT_UNRECOVERABLE_COUNT, 3)
                    .putLong(KEY_DECRYPT_FIRST_UNRECOVERABLE_AT, firstFailureAt)
                    .putInt(KEY_MISSING_COUNT, 3)
                    .putLong(KEY_FIRST_MISSING_AT, firstFailureAt)
                    .commit(),
            )

            restored = cipher.decrypt(aad, checkNotNull(encrypted))

            assertArrayEquals(plaintext, restored)
            assertEquals(3, keyHealth.getInt(KEY_ENCRYPT_UNRECOVERABLE_COUNT, 0))
            assertEquals(
                firstFailureAt,
                keyHealth.getLong(KEY_ENCRYPT_FIRST_UNRECOVERABLE_AT, 0L),
            )
            assertFalse(keyHealth.contains(KEY_DECRYPT_UNRECOVERABLE_COUNT))
            assertFalse(keyHealth.contains(KEY_DECRYPT_FIRST_UNRECOVERABLE_AT))
            assertFalse(keyHealth.contains(KEY_MISSING_COUNT))
            assertFalse(keyHealth.contains(KEY_FIRST_MISSING_AT))

            rewritten = cipher.encrypt(aad, checkNotNull(restored), allowKeyCreation = false)

            assertFalse(keyHealth.contains(KEY_ENCRYPT_UNRECOVERABLE_COUNT))
            assertFalse(keyHealth.contains(KEY_ENCRYPT_FIRST_UNRECOVERABLE_AT))
        } finally {
            aad.fill(0)
            plaintext.fill(0)
            restored?.fill(0)
            encrypted?.iv?.fill(0)
            encrypted?.ciphertext?.fill(0)
            rewritten?.iv?.fill(0)
            rewritten?.ciphertext?.fill(0)
            runCatching { cipher.eraseKey() }
            keyHealth.edit().clear().commit()
        }
    }

    private companion object {
        const val KEY_HEALTH_PREFERENCES = "kit_pay_secure_messaging_key_health_v1"
        const val KEY_MISSING_COUNT = "missing_alias_count"
        const val KEY_FIRST_MISSING_AT = "missing_alias_first_seen_at"
        const val KEY_ENCRYPT_UNRECOVERABLE_COUNT = "encrypt_unrecoverable_alias_count"
        const val KEY_ENCRYPT_FIRST_UNRECOVERABLE_AT =
            "encrypt_unrecoverable_alias_first_seen_at"
        const val KEY_DECRYPT_UNRECOVERABLE_COUNT = "decrypt_unrecoverable_alias_count"
        const val KEY_DECRYPT_FIRST_UNRECOVERABLE_AT =
            "decrypt_unrecoverable_alias_first_seen_at"
    }
}
