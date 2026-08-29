package com.kit.wallet.data.local

import androidx.room.withTransaction
import com.kit.wallet.data.media.ProfileAvatarByteStore
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
    private val conversationPrefsDao: ConversationPrefsDao? = null,
    private val profilePhotoDao: ProfilePhotoDao? = null,
    private val profileAvatarBytes: ProfileAvatarByteStore? = null,
    private val beneficiaryContactDao: BeneficiaryContactDao? = null,
    private val supportOutboxDao: SupportOutboxDao? = null,
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
        conversationPrefsDao?.clear()
        // Signing out, or another account claiming this device, takes the faces with it. Who a
        // person's contacts are is theirs, and a photo left behind would say so to whoever holds
        // the phone next. Both halves go: the rows that address the photos, and the bytes.
        profilePhotoDao?.clear()
        // Which of this account's payout destinations belong to which of its contacts is just as
        // much theirs, and says as much about them, as the faces do.
        beneficiaryContactDao?.clear()
        // Queued support text is the account's own words to Kit staff. It must neither render
        // under nor ever be transmitted by whoever signs in next.
        supportOutboxDao?.clear()
        // A file that will not delete must not roll back the row erasure — leaving the rows behind
        // would be the worse of the two failures by far.
        runCatching { profileAvatarBytes?.clear() }
    }

    private suspend fun requireOwner(ownerScopeId: String) {
        check(syncStateDao.get(AUTHENTICATED_CACHE_OWNER_KEY) == ownerScopeId) {
            "The wallet cache belongs to an obsolete authenticated session"
        }
    }
}
