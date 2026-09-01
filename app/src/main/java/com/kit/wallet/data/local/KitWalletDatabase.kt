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
        ConversationPrefEntity::class,
        ProfilePhotoEntity::class,
        BeneficiaryContactEntity::class,
        SupportOutboxEntity::class,
    ],
    version = 16,
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
    abstract fun conversationPrefsDao(): ConversationPrefsDao
    abstract fun profilePhotoDao(): ProfilePhotoDao
    abstract fun beneficiaryContactDao(): BeneficiaryContactDao
    abstract fun supportOutboxDao(): SupportOutboxDao

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

        val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE wallet_transactions ADD COLUMN " +
                        "currencyScale INTEGER NOT NULL DEFAULT 2",
                )
            }
        }

        val MIGRATION_7_8: Migration = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profile ADD COLUMN avatarUrl TEXT")
            }
        }

        val MIGRATION_8_9: Migration = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS conversation_prefs (" +
                        "conversationId TEXT NOT NULL, " +
                        "pinned INTEGER NOT NULL DEFAULT 0, " +
                        "muted INTEGER NOT NULL DEFAULT 0, " +
                        "PRIMARY KEY(conversationId))",
                )
            }
        }

        val MIGRATION_9_10: Migration = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS profile_photos (" +
                        "userId TEXT NOT NULL, " +
                        "avatarUrl TEXT NOT NULL, " +
                        "updatedAtEpochMillis INTEGER NOT NULL, " +
                        "PRIMARY KEY(userId))",
                )
            }
        }

        val MIGRATION_10_11: Migration = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS beneficiary_contacts (" +
                        "beneficiaryId TEXT NOT NULL, " +
                        "phoneKey TEXT NOT NULL, " +
                        "updatedAtEpochMillis INTEGER NOT NULL, " +
                        "PRIMARY KEY(beneficiaryId))",
                )
            }
        }

        val MIGRATION_11_12: Migration = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profile ADD COLUMN legalName TEXT")
                // Required until the server says otherwise, which is the safe way round: an
                // account wrongly told its username is optional would be offered a Save the API
                // rejects. The next profile refresh replaces both values with the truth.
                db.execSQL(
                    "ALTER TABLE profile ADD COLUMN usernameRequired INTEGER NOT NULL DEFAULT 1",
                )

                // A profile-photo URL can be retained only when the old unified cache has an
                // authenticated owner. Copy it into the composite owner/user key before replacing
                // the table. Rows with no owner are deliberately discarded.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS profile_photos_v12 (" +
                        "ownerScopeId TEXT NOT NULL, " +
                        "userId TEXT NOT NULL, " +
                        "avatarUrl TEXT NOT NULL, " +
                        "updatedAtEpochMillis INTEGER NOT NULL, " +
                        "PRIMARY KEY(ownerScopeId, userId))",
                )
                db.execSQL(
                    "INSERT OR REPLACE INTO profile_photos_v12 " +
                        "(ownerScopeId, userId, avatarUrl, updatedAtEpochMillis) " +
                        "SELECT owner.value, LOWER(TRIM(photos.userId)), photos.avatarUrl, " +
                        "photos.updatedAtEpochMillis FROM profile_photos AS photos " +
                        "JOIN sync_state AS owner ON owner.`key` = " +
                        "'$AUTHENTICATED_CACHE_OWNER_KEY' " +
                        "WHERE owner.value IS NOT NULL AND TRIM(owner.value) != '' " +
                        "AND TRIM(photos.userId) != ''",
                )
                db.execSQL("DROP TABLE profile_photos")
                db.execSQL("ALTER TABLE profile_photos_v12 RENAME TO profile_photos")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_profile_photos_ownerScopeId " +
                        "ON profile_photos(ownerScopeId)",
                )

                // Version 11 retained only the final nine digits. It cannot distinguish equal
                // subscriber numbers in two countries and cannot be upgraded into a keyed digest
                // because the canonical number no longer exists. Dropping those display-only links
                // is safer than assigning a potentially wrong face to a payment destination.
                db.execSQL("DROP TABLE beneficiary_contacts")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS beneficiary_contacts (" +
                        "ownerScopeId TEXT NOT NULL, " +
                        "beneficiaryId TEXT NOT NULL, " +
                        "phoneIdentity TEXT NOT NULL, " +
                        "updatedAtEpochMillis INTEGER NOT NULL, " +
                        "PRIMARY KEY(ownerScopeId, beneficiaryId))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_beneficiary_contacts_ownerScopeId " +
                        "ON beneficiary_contacts(ownerScopeId)",
                )
            }
        }

        val MIGRATION_12_13: Migration = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS support_outbox (" +
                        "ownerScopeId TEXT NOT NULL, " +
                        "clientMessageId TEXT NOT NULL, " +
                        "kind TEXT NOT NULL, " +
                        "ticketId TEXT, " +
                        "categoryKey TEXT, " +
                        "subject TEXT, " +
                        "body TEXT NOT NULL, " +
                        "status TEXT NOT NULL, " +
                        "failureCode TEXT, " +
                        "createdAtEpochMillis INTEGER NOT NULL, " +
                        "lastAttemptAtEpochMillis INTEGER, " +
                        "PRIMARY KEY(ownerScopeId, clientMessageId))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_support_outbox_ownerScopeId_ticketId " +
                        "ON support_outbox(ownerScopeId, ticketId)",
                )
            }
        }

        val MIGRATION_13_14: Migration = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Both columns are nullable so existing profiles remain unbadged until the next
                // authenticated profile refresh. No KYC/name field is consulted during migration.
                db.execSQL("ALTER TABLE profile ADD COLUMN verificationDesignation TEXT")
                db.execSQL("ALTER TABLE profile ADD COLUMN verificationSince TEXT")
            }
        }

        val MIGRATION_14_15: Migration = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // A public account id is the only safe join key for counterparty presentation.
                // Existing rows stay unbadged until a wallet refresh supplies that identity.
                db.execSQL("ALTER TABLE wallet_transactions ADD COLUMN counterpartyUserId TEXT")
                db.execSQL("ALTER TABLE wallet_transactions ADD COLUMN counterpartyAvatarUrl TEXT")
                db.execSQL(
                    "ALTER TABLE wallet_transactions ADD COLUMN " +
                        "counterpartyVerificationDesignation TEXT",
                )
                db.execSQL(
                    "ALTER TABLE wallet_transactions ADD COLUMN counterpartyVerificationSince TEXT",
                )
            }
        }

        val MIGRATION_15_16: Migration = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Version 15 cached only a display amount, so it cannot prove whether a bank fee
                // companion was already included. Keep those rows untrusted until the next
                // authoritative history refresh supplies and validates explicit customer totals.
                db.execSQL(
                    "ALTER TABLE wallet_transactions ADD COLUMN " +
                        "customerProjectionVerified INTEGER NOT NULL DEFAULT 0",
                )
            }
        }
    }
}
