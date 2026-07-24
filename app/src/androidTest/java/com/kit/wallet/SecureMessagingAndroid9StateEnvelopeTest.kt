package com.kit.wallet

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.kit.wallet.data.local.KitWalletDatabase
import com.kit.wallet.data.messaging.AndroidKeystoreMessagingRecordCipher
import com.kit.wallet.data.messaging.EncryptedMessagingRecord
import com.kit.wallet.data.messaging.RoomSecureMessagingStateStore
import com.kit.wallet.data.messaging.SecureMessagingRecordStorageFormat
import com.kit.wallet.data.messaging.secureMessagingRecordStorageFormat
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 28, maxSdkVersion = 28)
class SecureMessagingAndroid9StateEnvelopeTest {
    @Test
    fun large_envelope_records_survive_a_fresh_cipher_alias_reload() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, KitWalletDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val writeCipher = AndroidKeystoreMessagingRecordCipher(context)
        var reloadCipher: AndroidKeystoreMessagingRecordCipher? = null
        val testRecords = listOf(
            TestRecord(
                recordKey = "state-envelope-128-kib",
                plaintext = deterministicPlaintext(128 * 1024, seed = 0x31),
            ),
            TestRecord(
                recordKey = "state-envelope-1-mib",
                plaintext = deterministicPlaintext(1024 * 1024, seed = 0x57),
            ),
            TestRecord(
                recordKey = "state-envelope-1536-kib",
                plaintext = deterministicPlaintext(1536 * 1024, seed = 0x6d),
            ),
        )

        try {
            writeCipher.eraseKey()
            val writeStore = RoomSecureMessagingStateStore(database, writeCipher)
            writeStore.allowForActiveSession()

            testRecords.forEach { record ->
                val written = writeStore.write(
                    namespace = TEST_NAMESPACE,
                    recordKey = record.recordKey,
                    expectedVersion = null,
                    bytes = record.plaintext,
                )
                assertEquals(1L, written.version)
            }

            testRecords.forEach { record ->
                val stored = checkNotNull(
                    database.secureMessagingRecordDao().get(TEST_NAMESPACE, record.recordKey),
                )
                try {
                    assertEquals(
                        SecureMessagingRecordStorageFormat.DEK_ENVELOPE_V1,
                        secureMessagingRecordStorageFormat(
                            EncryptedMessagingRecord(stored.iv, stored.ciphertext),
                        ),
                    )
                } finally {
                    stored.iv.fill(0)
                    stored.ciphertext.fill(0)
                }
            }

            // A new cipher instance has no retained key handle, so its first read must resolve the
            // existing Android Keystore alias again rather than relying on the writer's cache.
            val freshCipher = AndroidKeystoreMessagingRecordCipher(context)
            reloadCipher = freshCipher
            val readStore = RoomSecureMessagingStateStore(database, freshCipher)
            readStore.allowForActiveSession()

            testRecords.forEach { record ->
                val restored = checkNotNull(readStore.read(TEST_NAMESPACE, record.recordKey))
                try {
                    assertEquals(1L, restored.version)
                    assertArrayEquals(record.plaintext, restored.bytes)
                } finally {
                    restored.bytes.fill(0)
                }
            }
        } finally {
            testRecords.forEach { it.plaintext.fill(0) }
            database.close()
            runCatching { (reloadCipher ?: writeCipher).eraseKey() }
        }
    }

    private fun deterministicPlaintext(size: Int, seed: Int): ByteArray =
        ByteArray(size) { index -> ((index * 31 + seed) and 0xff).toByte() }

    private data class TestRecord(
        val recordKey: String,
        val plaintext: ByteArray,
    )

    private companion object {
        const val TEST_NAMESPACE = "api28-state-envelope"
    }
}
