package com.kit.wallet.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Query(
        "SELECT profile.* FROM profile WHERE singletonId = 1 AND EXISTS (" +
            "SELECT 1 FROM sync_state AS cache_owner " +
            "WHERE cache_owner.`key` = :ownerKey " +
            "AND cache_owner.value = :ownerScopeId)",
    )
    fun observeForOwner(ownerScopeId: String, ownerKey: String): Flow<ProfileEntity?>

    @Upsert
    suspend fun upsert(profile: ProfileEntity)

    @Query("DELETE FROM profile")
    suspend fun clear()
}

@Dao
interface WalletDao {
    @Query(
        "SELECT wallets.* FROM wallets WHERE EXISTS (" +
            "SELECT 1 FROM sync_state AS cache_owner " +
            "WHERE cache_owner.`key` = :ownerKey " +
            "AND cache_owner.value = :ownerScopeId) " +
            "ORDER BY isPrimary DESC, updatedAtEpochMillis DESC LIMIT 1",
    )
    fun observeSelectedForOwner(ownerScopeId: String, ownerKey: String): Flow<WalletEntity?>

    @Query("SELECT * FROM wallets ORDER BY isPrimary DESC, updatedAtEpochMillis DESC LIMIT 1")
    suspend fun selected(): WalletEntity?

    @Upsert
    suspend fun upsertAll(wallets: List<WalletEntity>)

    @Query("DELETE FROM wallets")
    suspend fun clear()

    @Transaction
    suspend fun replaceAll(wallets: List<WalletEntity>) {
        clear()
        upsertAll(wallets)
    }
}

@Dao
interface WalletTransactionDao {
    @Query(
        "SELECT wallet_transactions.* FROM wallet_transactions " +
            "WHERE walletUuid = :walletUuid AND EXISTS (" +
            "SELECT 1 FROM sync_state AS cache_owner " +
            "WHERE cache_owner.`key` = :ownerKey " +
            "AND cache_owner.value = :ownerScopeId) " +
            "ORDER BY occurredAtEpochMillis DESC, id DESC",
    )
    fun observeForOwnerWallet(
        ownerScopeId: String,
        ownerKey: String,
        walletUuid: String,
    ): Flow<List<WalletTransactionEntity>>

    @Upsert
    suspend fun upsertAll(transactions: List<WalletTransactionEntity>)

    @Query("DELETE FROM wallet_transactions WHERE walletUuid = :walletUuid")
    suspend fun clearWallet(walletUuid: String)

    @Query("DELETE FROM wallet_transactions")
    suspend fun clearAll()

    @Transaction
    suspend fun replaceWallet(walletUuid: String, transactions: List<WalletTransactionEntity>) {
        clearWallet(walletUuid)
        upsertAll(transactions)
    }
}

@Dao
interface SyncStateDao {
    @Query("SELECT value FROM sync_state WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Query("SELECT value FROM sync_state WHERE `key` = :key")
    fun observe(key: String): Flow<String?>

    @Upsert
    suspend fun put(state: SyncStateEntity)

    @Query("DELETE FROM sync_state WHERE `key` = :key")
    suspend fun remove(key: String)

    @Query("DELETE FROM sync_state")
    suspend fun clear()
}

@Dao
interface SecureMessagingRecordDao {
    @Query("SELECT COUNT(*) FROM secure_messaging_records")
    suspend fun count(): Int

    @Query(
        "SELECT * FROM secure_messaging_records " +
            "WHERE namespace = :namespace AND recordKey = :recordKey",
    )
    suspend fun get(namespace: String, recordKey: String): SecureMessagingRecordEntity?

    @Query(
        "SELECT * FROM secure_messaging_records " +
            "WHERE namespace = :namespace " +
            "AND (:afterRecordKey IS NULL OR " +
            "recordKey COLLATE BINARY > :afterRecordKey COLLATE BINARY) " +
            "ORDER BY recordKey COLLATE BINARY ASC LIMIT :limit",
    )
    suspend fun page(
        namespace: String,
        afterRecordKey: String?,
        limit: Int,
    ): List<SecureMessagingRecordEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(record: SecureMessagingRecordEntity)

    @Query(
        "UPDATE secure_messaging_records SET version = :newVersion, iv = :iv, " +
            "ciphertext = :ciphertext, updatedAtEpochMillis = :updatedAtEpochMillis " +
            "WHERE namespace = :namespace AND recordKey = :recordKey " +
            "AND version = :expectedVersion",
    )
    suspend fun compareAndSet(
        namespace: String,
        recordKey: String,
        expectedVersion: Long,
        newVersion: Long,
        iv: ByteArray,
        ciphertext: ByteArray,
        updatedAtEpochMillis: Long,
    ): Int

    @Query("DELETE FROM secure_messaging_records WHERE namespace = :namespace")
    suspend fun deleteNamespace(namespace: String)

    @Query("DELETE FROM secure_messaging_records")
    suspend fun deleteAll()
}

@Dao
interface AccountMessageArchiveDao {
    @Query(
        "SELECT COUNT(*) FROM account_message_archive " +
            "WHERE ownerAccountId = :ownerAccountId AND installationId = :installationId",
    )
    suspend fun countForOwner(ownerAccountId: String, installationId: String): Int

    @Query(
        "SELECT * FROM account_message_archive " +
            "WHERE ownerAccountId = :ownerAccountId AND installationId = :installationId " +
            "AND recordKey = :recordKey",
    )
    suspend fun get(
        ownerAccountId: String,
        installationId: String,
        recordKey: String,
    ): AccountMessageArchiveEntity?

    @Query(
        "SELECT * FROM account_message_archive " +
            "WHERE ownerAccountId = :ownerAccountId AND installationId = :installationId " +
            "AND (:afterRecordKey IS NULL OR " +
            "recordKey COLLATE BINARY > :afterRecordKey COLLATE BINARY) " +
            "ORDER BY recordKey COLLATE BINARY ASC LIMIT :limit",
    )
    suspend fun page(
        ownerAccountId: String,
        installationId: String,
        afterRecordKey: String?,
        limit: Int,
    ): List<AccountMessageArchiveEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(record: AccountMessageArchiveEntity)

    @Query(
        "UPDATE account_message_archive SET version = :newVersion, iv = :iv, " +
            "ciphertext = :ciphertext, updatedAtEpochMillis = :updatedAtEpochMillis " +
            "WHERE ownerAccountId = :ownerAccountId AND installationId = :installationId " +
            "AND recordKey = :recordKey AND version = :expectedVersion",
    )
    suspend fun compareAndSet(
        ownerAccountId: String,
        installationId: String,
        recordKey: String,
        expectedVersion: Long,
        newVersion: Long,
        iv: ByteArray,
        ciphertext: ByteArray,
        updatedAtEpochMillis: Long,
    ): Int

    @Query(
        "DELETE FROM account_message_archive " +
            "WHERE ownerAccountId = :ownerAccountId AND installationId = :installationId",
    )
    suspend fun deleteOwner(ownerAccountId: String, installationId: String): Int

    @Query(
        "SELECT DISTINCT installationId FROM account_message_archive " +
            "WHERE ownerAccountId = :ownerAccountId ORDER BY installationId COLLATE BINARY ASC",
    )
    suspend fun installationIdsForAccount(ownerAccountId: String): List<String>

    @Query("DELETE FROM account_message_archive WHERE ownerAccountId = :ownerAccountId")
    suspend fun deleteAccount(ownerAccountId: String): Int
}
