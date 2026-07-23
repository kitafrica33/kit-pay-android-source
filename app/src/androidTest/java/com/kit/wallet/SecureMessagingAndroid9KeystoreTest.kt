package com.kit.wallet

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.kit.wallet.data.messaging.AndroidKeystoreMessagingRecordCipher
import com.kit.wallet.data.messaging.EncryptedMessagingRecord
import com.kit.wallet.data.messaging.SecureMessagingStateRetryableException
import com.kit.wallet.data.messaging.SecureMessagingStateUnavailableException
import com.kit.wallet.data.messaging.isPermanentlyMissingSecureMessagingRecordKey
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 28, maxSdkVersion = 28)
class SecureMessagingAndroid9KeystoreTest {
    @Test
    fun deleted_alias_recovers_from_retryable_to_permanent_without_replacing_key() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cipher = AndroidKeystoreMessagingRecordCipher(context)
        val aad = "kit-api28-missing-alias".toByteArray()
        val plaintext = "retained secure state".toByteArray()
        var encrypted: EncryptedMessagingRecord? = null

        try {
            cipher.eraseKey()
            encrypted = cipher.encrypt(aad, plaintext, allowKeyCreation = true)
            cipher.eraseKey()

            repeat(4) { attempt ->
                if (attempt > 0) SystemClock.sleep(5_200L)
                val failure = runCatching {
                    cipher.decrypt(aad, checkNotNull(encrypted))
                }.exceptionOrNull()
                assertNotNull(failure)
                if (attempt < 3) {
                    assertTrue(failure is SecureMessagingStateRetryableException)
                } else {
                    assertTrue(failure is SecureMessagingStateUnavailableException)
                    assertTrue(isPermanentlyMissingSecureMessagingRecordKey(checkNotNull(failure)))
                }
            }
        } finally {
            aad.fill(0)
            plaintext.fill(0)
            encrypted?.iv?.fill(0)
            encrypted?.ciphertext?.fill(0)
            runCatching { cipher.eraseKey() }
        }
    }
}
