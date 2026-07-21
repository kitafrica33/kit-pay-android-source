package com.kit.wallet.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "profile")
data class ProfileEntity(
    @PrimaryKey val singletonId: Int = SINGLETON_ID,
    val userId: String,
    val name: String,
    val phone: String,
    val tag: String,
    val kycLabel: String,
    val email: String?,
    val emailVerified: Boolean,
    val profileSetupRequired: Boolean,
    val updatedAtEpochMillis: Long,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}

@Entity(tableName = "wallets")
data class WalletEntity(
    @PrimaryKey val uuid: String,
    val name: String,
    val accountNumber: String?,
    val currencyCode: String,
    val currencyScale: Int,
    val availableBalanceMinor: Long,
    val ledgerBalanceMinor: Long,
    val status: String,
    val kycStatus: String,
    val isPrimary: Boolean,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "wallet_transactions",
    indices = [
        Index(value = ["walletUuid", "occurredAtEpochMillis"]),
        Index(value = ["reference"]),
    ],
)
data class WalletTransactionEntity(
    @PrimaryKey val id: String,
    val walletUuid: String,
    val reference: String,
    val amountMinor: Long,
    val currencyCode: String,
    val type: String,
    val direction: String,
    val status: String,
    val counterpartyName: String,
    val note: String?,
    val occurredAtEpochMillis: Long,
)

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val key: String,
    val value: String?,
)

/**
 * Opaque E2EE state only. Identity/prekey/session bytes and decrypted message projections must be
 * encrypted before they reach Room; namespace/key/version are authenticated as AES-GCM AAD.
 */
@Entity(
    tableName = "secure_messaging_records",
    primaryKeys = ["namespace", "recordKey"],
    indices = [Index(value = ["namespace"])],
)
data class SecureMessagingRecordEntity(
    val namespace: String,
    val recordKey: String,
    val version: Long,
    val iv: ByteArray,
    val ciphertext: ByteArray,
    val updatedAtEpochMillis: Long,
)
