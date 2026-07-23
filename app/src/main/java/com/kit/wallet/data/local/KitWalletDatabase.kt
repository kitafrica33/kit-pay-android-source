package com.kit.wallet.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ProfileEntity::class,
        WalletEntity::class,
        WalletTransactionEntity::class,
        SyncStateEntity::class,
        SecureMessagingRecordEntity::class,
        SecureMessagingMetadataEntity::class,
        AccountMessageArchiveEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
abstract class KitWalletDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun walletDao(): WalletDao
    abstract fun walletTransactionDao(): WalletTransactionDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun secureMessagingRecordDao(): SecureMessagingRecordDao
    abstract fun secureMessagingMetadataDao(): SecureMessagingMetadataDao
    abstract fun accountMessageArchiveDao(): AccountMessageArchiveDao

    companion object {
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profile ADD COLUMN email TEXT")
                db.execSQL(
                    "ALTER TABLE profile ADD COLUMN emailVerified INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE profile ADD COLUMN " +
                        "profileSetupRequired INTEGER NOT NULL DEFAULT 0",
                )
                // Version 2 did not persist the server flag. Preserve the known legacy signup
                // placeholders offline until the next authoritative profile refresh arrives.
                db.execSQL(
                    "UPDATE profile SET profileSetupRequired = 1 " +
                        "WHERE TRIM(name) = '' " +
                        "OR LOWER(TRIM(name)) IN ('kit pay user', 'kit wallet user') " +
                        "OR TRIM(tag) = ''",
                )
            }
        }

        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS secure_messaging_records (" +
                        "namespace TEXT NOT NULL, " +
                        "recordKey TEXT NOT NULL, " +
                        "version INTEGER NOT NULL, " +
                        "iv BLOB NOT NULL, " +
                        "ciphertext BLOB NOT NULL, " +
                        "updatedAtEpochMillis INTEGER NOT NULL, " +
                        "PRIMARY KEY(namespace, recordKey))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_secure_messaging_records_namespace " +
                        "ON secure_messaging_records(namespace)",
                )
            }
        }

        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS account_message_archive (" +
                        "ownerAccountId TEXT NOT NULL, " +
                        "installationId TEXT NOT NULL, " +
                        "recordKey TEXT NOT NULL, " +
                        "version INTEGER NOT NULL, " +
                        "iv BLOB NOT NULL, " +
                        "ciphertext BLOB NOT NULL, " +
                        "updatedAtEpochMillis INTEGER NOT NULL, " +
                        "PRIMARY KEY(ownerAccountId, installationId, recordKey))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "index_account_message_archive_ownerAccountId_installationId " +
                        "ON account_message_archive(ownerAccountId, installationId)",
                )
            }
        }

        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS secure_messaging_metadata (" +
                        "`key` TEXT NOT NULL, " +
                        "value TEXT NOT NULL, " +
                        "PRIMARY KEY(`key`))",
                )
                db.execSQL(
                    "INSERT INTO secure_messaging_metadata (`key`, value) " +
                        "SELECT '$SECURE_MESSAGING_LEGACY_KEY_CONTINUITY_KEY', " +
                        "'$SECURE_MESSAGING_LEGACY_KEY_CONTINUITY_VALUE' " +
                        "WHERE EXISTS (SELECT 1 FROM secure_messaging_records LIMIT 1)",
                )
            }
        }
    }
}
