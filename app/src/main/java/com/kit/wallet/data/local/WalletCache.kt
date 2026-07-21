package com.kit.wallet.data.local

import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

const val AUTHENTICATED_CACHE_OWNER_KEY = "authenticated_cache_owner_scope_v1"

interface WalletCache {
    val ownerScope: Flow<String?>

    suspend fun replaceProfile(ownerScopeId: String, profile: ProfileEntity)
    suspend fun replaceProfileAndWallets(
        ownerScopeId: String,
        profile: ProfileEntity,
        wallets: List<WalletEntity>,
    )
    suspend fun replaceWallets(ownerScopeId: String, wallets: List<WalletEntity>)
    suspend fun selectedWallet(ownerScopeId: String): WalletEntity?
    suspend fun replaceTransactions(
        ownerScopeId: String,
        walletUuid: String,
        transactions: List<WalletTransactionEntity>,
        nextCursor: String?,
    )
    /** Clears all projections, optionally only while [ownerScopeId] still owns them. */
    suspend fun clearUserData(ownerScopeId: String? = null): Boolean
}

@Singleton
class RoomWalletCache @Inject constructor(
    private val database: KitWalletDatabase,
    private val profileDao: ProfileDao,
    private val walletDao: WalletDao,
    private val transactionDao: WalletTransactionDao,
    private val syncStateDao: SyncStateDao,
) : WalletCache {
    override val ownerScope: Flow<String?> = syncStateDao.observe(AUTHENTICATED_CACHE_OWNER_KEY)

    override suspend fun replaceProfile(ownerScopeId: String, profile: ProfileEntity) {
        require(ownerScopeId.isNotBlank()) { "Cache owner scope must not be blank" }
        database.withTransaction {
            claimOwner(ownerScopeId)
            profileDao.upsert(profile)
        }
    }

    override suspend fun replaceProfileAndWallets(
        ownerScopeId: String,
        profile: ProfileEntity,
        wallets: List<WalletEntity>,
    ) {
        require(ownerScopeId.isNotBlank()) { "Cache owner scope must not be blank" }
        database.withTransaction {
            claimOwner(ownerScopeId)
            profileDao.upsert(profile)
            walletDao.replaceAll(wallets)
        }
    }

    override suspend fun replaceWallets(ownerScopeId: String, wallets: List<WalletEntity>) {
        database.withTransaction {
            requireOwner(ownerScopeId)
            walletDao.replaceAll(wallets)
        }
    }

    override suspend fun selectedWallet(ownerScopeId: String): WalletEntity? =
        database.withTransaction {
            if (syncStateDao.get(AUTHENTICATED_CACHE_OWNER_KEY) != ownerScopeId) {
                null
            } else {
                walletDao.selected()
            }
        }

    override suspend fun replaceTransactions(
        ownerScopeId: String,
        walletUuid: String,
        transactions: List<WalletTransactionEntity>,
        nextCursor: String?,
    ) {
        database.withTransaction {
            requireOwner(ownerScopeId)
            transactionDao.replaceWallet(walletUuid, transactions)
            syncStateDao.put(
                SyncStateEntity(
                    key = "transactions:$ownerScopeId:$walletUuid:next_cursor",
                    value = nextCursor,
                ),
            )
        }
    }

    override suspend fun clearUserData(ownerScopeId: String?): Boolean =
        database.withTransaction {
            val currentOwner = syncStateDao.get(AUTHENTICATED_CACHE_OWNER_KEY)
            if (ownerScopeId != null && currentOwner != null && currentOwner != ownerScopeId) {
                return@withTransaction false
            }
            clearRows()
            true
        }

    private suspend fun claimOwner(ownerScopeId: String) {
        val currentOwner = syncStateDao.get(AUTHENTICATED_CACHE_OWNER_KEY)
        if (currentOwner == ownerScopeId) return

        clearRows()
        syncStateDao.put(SyncStateEntity(AUTHENTICATED_CACHE_OWNER_KEY, ownerScopeId))
    }

    private suspend fun clearRows() {
        transactionDao.clearAll()
        syncStateDao.clear()
        walletDao.clear()
        profileDao.clear()
    }

    private suspend fun requireOwner(ownerScopeId: String) {
        check(syncStateDao.get(AUTHENTICATED_CACHE_OWNER_KEY) == ownerScopeId) {
            "The wallet cache belongs to an obsolete authenticated session"
        }
    }
}
