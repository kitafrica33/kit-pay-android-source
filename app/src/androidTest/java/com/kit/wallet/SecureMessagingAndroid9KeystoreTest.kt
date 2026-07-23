package com.kit.wallet

import android.os.SystemClock
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.kit.wallet.data.local.KitWalletDatabase
import com.kit.wallet.data.local.SECURE_MESSAGING_LEGACY_KEY_CONTINUITY_KEY
import com.kit.wallet.data.local.SECURE_MESSAGING_LEGACY_KEY_CONTINUITY_VALUE
import com.kit.wallet.data.local.SecureMessagingMetadataEntity
import com.kit.wallet.data.local.SecureMessagingRecordEntity
import com.kit.wallet.data.messaging.AndroidKeystoreMessagingRecordCipher
import com.kit.wallet.data.messaging.EncryptedMessagingRecord
import com.kit.wallet.data.messaging.RoomSecureMessagingStateStore
import com.kit.wallet.data.messaging.SecureMessagingLegacyStateUnreadableException
import com.kit.wallet.data.messaging.SecureMessagingStateRetryableException
import com.kit.wallet.data.messaging.SecureMessagingStateUnavailableException
import com.kit.wallet.data.messaging.isPermanentlyMissingSecureMessagingRecordKey
import com.kit.wallet.data.messaging.isRecoverableSecureMessagingStateLoss
import com.kit.wallet.data.messaging.messagingRecordAad
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @Test
    fun legacy_single_key_state_is_validated_without_erasure() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, KitWalletDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val cipher = AndroidKeystoreMessagingRecordCipher(context)
        val store = RoomSecureMessagingStateStore(database, cipher)
        val firstAad = messagingRecordAad("a-legacy", "state", 1L)
        val secondAad = messagingRecordAad("b-legacy", "state", 1L)
        val firstPlaintext = "legacy state under the retained key".toByteArray()
        val secondPlaintext = "more state under the retained key".toByteArray()
        var firstEncrypted: EncryptedMessagingRecord? = null
        var secondEncrypted: EncryptedMessagingRecord? = null

        try {
            cipher.eraseKey()
            firstEncrypted = cipher.encrypt(firstAad, firstPlaintext, allowKeyCreation = true)
            secondEncrypted = cipher.encrypt(secondAad, secondPlaintext, allowKeyCreation = false)
            database.secureMessagingRecordDao().insert(
                SecureMessagingRecordEntity(
                    namespace = "a-legacy",
                    recordKey = "state",
                    version = 1L,
                    iv = checkNotNull(firstEncrypted).iv,
                    ciphertext = checkNotNull(firstEncrypted).ciphertext,
                    updatedAtEpochMillis = 1L,
                ),
            )
            database.secureMessagingRecordDao().insert(
                SecureMessagingRecordEntity(
                    namespace = "b-legacy",
                    recordKey = "state",
                    version = 1L,
                    iv = checkNotNull(secondEncrypted).iv,
                    ciphertext = checkNotNull(secondEncrypted).ciphertext,
                    updatedAtEpochMillis = 2L,
                ),
            )
            database.secureMessagingMetadataDao().put(
                SecureMessagingMetadataEntity(
                    SECURE_MESSAGING_LEGACY_KEY_CONTINUITY_KEY,
                    SECURE_MESSAGING_LEGACY_KEY_CONTINUITY_VALUE,
                ),
            )
            store.allowForActiveSession()

            store.validateAndRetireLegacyKeyContinuity()

            assertEquals(2, database.secureMessagingRecordDao().count())
            assertNull(
                database.secureMessagingMetadataDao()
                    .get(SECURE_MESSAGING_LEGACY_KEY_CONTINUITY_KEY),
            )
            val firstRestored = checkNotNull(store.read("a-legacy", "state"))
            val secondRestored = checkNotNull(store.read("b-legacy", "state"))
            try {
                assertArrayEquals(firstPlaintext, firstRestored.bytes)
                assertArrayEquals(secondPlaintext, secondRestored.bytes)
            } finally {
                firstRestored.bytes.fill(0)
                secondRestored.bytes.fill(0)
            }
        } finally {
            firstAad.fill(0)
            secondAad.fill(0)
            firstPlaintext.fill(0)
            secondPlaintext.fill(0)
            firstEncrypted?.iv?.fill(0)
            firstEncrypted?.ciphertext?.fill(0)
            secondEncrypted?.iv?.fill(0)
            secondEncrypted?.ciphertext?.fill(0)
            database.close()
            runCatching { cipher.eraseKey() }
        }
    }

    @Test
    fun legacy_mixed_keys_are_classified_unreadable_erased_and_reopened() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, KitWalletDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val cipher = AndroidKeystoreMessagingRecordCipher(context)
        val store = RoomSecureMessagingStateStore(database, cipher)
        val oldAad = messagingRecordAad("a-legacy", "state", 1L)
        val newAad = messagingRecordAad("b-legacy", "state", 1L)
        val oldPlaintext = "encrypted with legacy key A".toByteArray()
        val newPlaintext = "encrypted with replacement key B".toByteArray()
        var oldEncrypted: EncryptedMessagingRecord? = null
        var newEncrypted: EncryptedMessagingRecord? = null

        try {
            cipher.eraseKey()
            oldEncrypted = cipher.encrypt(oldAad, oldPlaintext, allowKeyCreation = true)
            // Reproduce the code-15/16 failure: Android 9 hid A and getOrCreateKey replaced it.
            cipher.eraseKey()
            newEncrypted = cipher.encrypt(newAad, newPlaintext, allowKeyCreation = true)
            database.secureMessagingRecordDao().insert(
                SecureMessagingRecordEntity(
                    namespace = "a-legacy",
                    recordKey = "state",
                    version = 1L,
                    iv = checkNotNull(oldEncrypted).iv,
                    ciphertext = checkNotNull(oldEncrypted).ciphertext,
                    updatedAtEpochMillis = 1L,
                ),
            )
            database.secureMessagingRecordDao().insert(
                SecureMessagingRecordEntity(
                    namespace = "b-legacy",
                    recordKey = "state",
                    version = 1L,
                    iv = checkNotNull(newEncrypted).iv,
                    ciphertext = checkNotNull(newEncrypted).ciphertext,
                    updatedAtEpochMillis = 2L,
                ),
            )
            database.secureMessagingMetadataDao().put(
                SecureMessagingMetadataEntity(
                    SECURE_MESSAGING_LEGACY_KEY_CONTINUITY_KEY,
                    SECURE_MESSAGING_LEGACY_KEY_CONTINUITY_VALUE,
                ),
            )
            store.allowForActiveSession()

            val failure = runCatching {
                store.validateAndRetireLegacyKeyContinuity()
            }.exceptionOrNull()

            assertTrue(failure is SecureMessagingLegacyStateUnreadableException)
            assertTrue(isRecoverableSecureMessagingStateLoss(checkNotNull(failure)))
            assertEquals(2, database.secureMessagingRecordDao().count())
            assertEquals(
                SECURE_MESSAGING_LEGACY_KEY_CONTINUITY_VALUE,
                database.secureMessagingMetadataDao()
                    .get(SECURE_MESSAGING_LEGACY_KEY_CONTINUITY_KEY),
            )

            store.eraseAll()

            assertEquals(0, database.secureMessagingRecordDao().count())
            assertNull(
                database.secureMessagingMetadataDao()
                    .get(SECURE_MESSAGING_LEGACY_KEY_CONTINUITY_KEY),
            )
            store.allowForActiveSession()
            val fresh = "fresh post-recovery state".toByteArray()
            try {
                store.write("fresh", "state", expectedVersion = null, bytes = fresh)
                val restored = checkNotNull(store.read("fresh", "state"))
                try {
                    assertArrayEquals(fresh, restored.bytes)
                } finally {
                    restored.bytes.fill(0)
                }
            } finally {
                fresh.fill(0)
            }
        } finally {
            oldAad.fill(0)
            newAad.fill(0)
            oldPlaintext.fill(0)
            newPlaintext.fill(0)
            oldEncrypted?.iv?.fill(0)
            oldEncrypted?.ciphertext?.fill(0)
            newEncrypted?.iv?.fill(0)
            newEncrypted?.ciphertext?.fill(0)
            database.close()
            runCatching { cipher.eraseKey() }
        }
    }
}
