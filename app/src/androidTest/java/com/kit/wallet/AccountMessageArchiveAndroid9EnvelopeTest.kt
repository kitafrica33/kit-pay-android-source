package com.kit.wallet

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.kit.wallet.data.local.KitWalletDatabase
import com.kit.wallet.data.messaging.AccountMessageArchiveOwner
import com.kit.wallet.data.messaging.AccountMessageArchiveStorageFormat
import com.kit.wallet.data.messaging.AndroidKeystoreAccountMessageArchiveCipher
import com.kit.wallet.data.messaging.EncryptedAccountMessageArchiveRecord
import com.kit.wallet.data.messaging.RoomAccountMessageArchiveStore
import com.kit.wallet.data.messaging.accountMessageArchiveStorageFormat
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 28, maxSdkVersion = 28)
class AccountMessageArchiveAndroid9EnvelopeTest {
    @Test
    fun maximum_archive_record_survives_an_independent_keystore_alias_reload() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, KitWalletDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val writeCipher = AndroidKeystoreAccountMessageArchiveCipher(context)
        var cleanupCipher: AndroidKeystoreAccountMessageArchiveCipher = writeCipher
        val plaintext = ByteArray(128 * 1024) { index ->
            ((index * 37 + 0x51) and 0xff).toByte()
        }

        try {
            writeCipher.eraseKey(OWNER)
            val writeStore = RoomAccountMessageArchiveStore(database, writeCipher)
            assertEquals(
                1L,
                writeStore.write(
                    owner = OWNER,
                    recordKey = RECORD_KEY,
                    expectedVersion = null,
                    bytes = plaintext,
                ),
            )

            val stored = checkNotNull(
                database.accountMessageArchiveDao().get(
                    OWNER.ownerAccountId,
                    OWNER.installationId,
                    RECORD_KEY,
                ),
            )
            try {
                assertEquals(
                    AccountMessageArchiveStorageFormat.DEK_ENVELOPE_V1,
                    accountMessageArchiveStorageFormat(
                        EncryptedAccountMessageArchiveRecord(stored.iv, stored.ciphertext),
                    ),
                )
            } finally {
                stored.iv.fill(0)
                stored.ciphertext.fill(0)
            }

            // This instance has no generated handle. Reading must resolve and authenticate the
            // owner alias through a fresh Android Keystore view before software decrypts 128 KiB.
            val freshCipher = AndroidKeystoreAccountMessageArchiveCipher(context)
            cleanupCipher = freshCipher
            val readStore = RoomAccountMessageArchiveStore(database, freshCipher)
            val restored = checkNotNull(readStore.read(OWNER, RECORD_KEY))
            try {
                assertEquals(1L, restored.version)
                assertArrayEquals(plaintext, restored.bytes)
            } finally {
                restored.bytes.fill(0)
            }
        } finally {
            plaintext.fill(0)
            database.close()
            runCatching { cleanupCipher.eraseKey(OWNER) }
        }
    }

    private companion object {
        val OWNER = AccountMessageArchiveOwner(
            ownerAccountId = "33333333-3333-4333-8333-333333333333",
            installationId = "44444444-4444-4444-8444-444444444444",
        )
        const val RECORD_KEY = "message:api28-envelope"
    }
}
