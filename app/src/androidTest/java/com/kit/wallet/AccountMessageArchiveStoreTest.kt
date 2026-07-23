package com.kit.wallet

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kit.wallet.data.local.KitWalletDatabase
import com.kit.wallet.data.messaging.AccountMessageArchiveCipher
import com.kit.wallet.data.messaging.AccountMessageArchiveKeyPermanentlyUnrecoverableException
import com.kit.wallet.data.messaging.AccountMessageArchiveOwner
import com.kit.wallet.data.messaging.AccountMessageArchiveUnavailableException
import com.kit.wallet.data.messaging.EncryptedAccountMessageArchiveRecord
import com.kit.wallet.data.messaging.RoomAccountMessageArchiveStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountMessageArchiveStoreTest {
    @Test
    fun permanentlyUnusableOrphanAliasIsErasedAndWriteBootstrapsFreshKey() = runTest {
        val database = database()
        val cipher = PermanentlyUnavailableThenHealthyCipher()
        val store = RoomAccountMessageArchiveStore(database, cipher)
        try {
            val version = store.write(
                OWNER_A,
                "message:one",
                expectedVersion = null,
                bytes = byteArrayOf(7),
            )

            assertEquals(1L, version)
            assertEquals(2, cipher.encryptAttempts)
            assertEquals(listOf(OWNER_A), cipher.eraseAttempts)
            assertEquals(1, database.accountMessageArchiveDao().countForOwner(ACCOUNT, INSTALLATION_A))
        } finally {
            database.close()
        }
    }

    @Test
    fun failedOwnerKeyEraseRetainsRowsAsRetryAddress() = runTest {
        val database = database()
        val failure = IllegalStateException("archive alias still busy")
        val cipher = TestArchiveCipher(failNextEraseFor = OWNER_A, eraseFailure = failure)
        val store = RoomAccountMessageArchiveStore(database, cipher)
        try {
            store.write(OWNER_A, "message:one", expectedVersion = null, bytes = byteArrayOf(7))

            val observed = runCatching { store.eraseOwner(OWNER_A) }.exceptionOrNull()

            assertSame(failure, observed)
            assertEquals(1, database.accountMessageArchiveDao().countForOwner(ACCOUNT, INSTALLATION_A))

            store.eraseOwner(OWNER_A)

            assertEquals(0, database.accountMessageArchiveDao().countForOwner(ACCOUNT, INSTALLATION_A))
            assertEquals(listOf(OWNER_A, OWNER_A), cipher.eraseAttempts)
        } finally {
            database.close()
        }
    }

    @Test
    fun failedAccountKeyEraseRetainsEveryInstallationUntilRetry() = runTest {
        val database = database()
        val failure = IllegalStateException("second archive alias still busy")
        val cipher = TestArchiveCipher(failNextEraseFor = OWNER_B, eraseFailure = failure)
        val store = RoomAccountMessageArchiveStore(database, cipher)
        try {
            store.write(OWNER_A, "message:one", expectedVersion = null, bytes = byteArrayOf(1))
            store.write(OWNER_B, "message:two", expectedVersion = null, bytes = byteArrayOf(2))

            val observed = runCatching { store.eraseAccount(ACCOUNT) }.exceptionOrNull()

            assertSame(failure, observed)
            assertEquals(1, database.accountMessageArchiveDao().countForOwner(ACCOUNT, INSTALLATION_A))
            assertEquals(1, database.accountMessageArchiveDao().countForOwner(ACCOUNT, INSTALLATION_B))

            store.eraseAccount(ACCOUNT)

            assertEquals(0, database.accountMessageArchiveDao().countForOwner(ACCOUNT, INSTALLATION_A))
            assertEquals(0, database.accountMessageArchiveDao().countForOwner(ACCOUNT, INSTALLATION_B))
        } finally {
            database.close()
        }
    }

    private fun database(): KitWalletDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        KitWalletDatabase::class.java,
    ).allowMainThreadQueries().build()

    private class TestArchiveCipher(
        private var failNextEraseFor: AccountMessageArchiveOwner?,
        private val eraseFailure: Exception,
    ) : AccountMessageArchiveCipher {
        val eraseAttempts = mutableListOf<AccountMessageArchiveOwner>()

        override fun encrypt(
            owner: AccountMessageArchiveOwner,
            aad: ByteArray,
            plaintext: ByteArray,
            allowKeyCreation: Boolean,
        ) = EncryptedAccountMessageArchiveRecord(
            iv = ByteArray(12) { 1 },
            ciphertext = plaintext.copyOf(),
        )

        override fun decrypt(
            owner: AccountMessageArchiveOwner,
            aad: ByteArray,
            record: EncryptedAccountMessageArchiveRecord,
        ): ByteArray = record.ciphertext.copyOf()

        override fun eraseKey(owner: AccountMessageArchiveOwner) {
            eraseAttempts += owner
            if (failNextEraseFor == owner) {
                failNextEraseFor = null
                throw eraseFailure
            }
        }
    }

    private class PermanentlyUnavailableThenHealthyCipher : AccountMessageArchiveCipher {
        var encryptAttempts = 0
        val eraseAttempts = mutableListOf<AccountMessageArchiveOwner>()

        override fun encrypt(
            owner: AccountMessageArchiveOwner,
            aad: ByteArray,
            plaintext: ByteArray,
            allowKeyCreation: Boolean,
        ): EncryptedAccountMessageArchiveRecord {
            encryptAttempts++
            if (encryptAttempts == 1) {
                throw AccountMessageArchiveUnavailableException(
                    "The orphan archive alias is unusable",
                    AccountMessageArchiveKeyPermanentlyUnrecoverableException(),
                )
            }
            return EncryptedAccountMessageArchiveRecord(
                iv = ByteArray(12) { 1 },
                ciphertext = plaintext.copyOf(),
            )
        }

        override fun decrypt(
            owner: AccountMessageArchiveOwner,
            aad: ByteArray,
            record: EncryptedAccountMessageArchiveRecord,
        ): ByteArray = record.ciphertext.copyOf()

        override fun eraseKey(owner: AccountMessageArchiveOwner) {
            eraseAttempts += owner
        }
    }

    private companion object {
        const val ACCOUNT = "11111111-1111-4111-8111-111111111111"
        const val INSTALLATION_A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val INSTALLATION_B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val OWNER_A = AccountMessageArchiveOwner(ACCOUNT, INSTALLATION_A)
        val OWNER_B = AccountMessageArchiveOwner(ACCOUNT, INSTALLATION_B)
    }
}
