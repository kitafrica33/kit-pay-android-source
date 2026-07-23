package com.kit.wallet

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kit.wallet.data.local.KitWalletDatabase
import com.kit.wallet.data.local.SECURE_MESSAGING_LEGACY_KEY_CONTINUITY_KEY
import com.kit.wallet.data.local.SECURE_MESSAGING_LEGACY_KEY_CONTINUITY_VALUE
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecureMessagingDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        KitWalletDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migration3To4CreatesOnlyOpaqueMessagingStorage() {
        helper.createDatabase(DATABASE_NAME, 3).close()

        val database = helper.runMigrationsAndValidate(
            DATABASE_NAME,
            4,
            true,
            KitWalletDatabase.MIGRATION_3_4,
        )
        val columns = database.query("PRAGMA table_info(secure_messaging_records)").use { cursor ->
            buildSet {
                val nameColumn = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) add(cursor.getString(nameColumn))
            }
        }

        assertEquals(
            setOf("namespace", "recordKey", "version", "iv", "ciphertext", "updatedAtEpochMillis"),
            columns,
        )
        database.close()
    }

    @Test
    fun migrations4To6MarkTheCode15To17UpgradePathBeforeActivation() {
        val originalIv = byteArrayOf(9, 8, 7, 6)
        val originalCiphertext = byteArrayOf(5, 4, 3, 2)
        helper.createDatabase(MIGRATION_4_6_WITH_STATE_DATABASE, 4).apply {
            execSQL(
                "INSERT INTO secure_messaging_records " +
                    "(namespace, recordKey, version, iv, ciphertext, updatedAtEpochMillis) " +
                    "VALUES (?, ?, ?, ?, ?, ?)",
                arrayOf(
                    "libsignal-v2",
                    "code15-active-state",
                    4L,
                    originalIv,
                    originalCiphertext,
                    2345L,
                ),
            )
            close()
        }

        val database = helper.runMigrationsAndValidate(
            MIGRATION_4_6_WITH_STATE_DATABASE,
            6,
            true,
            KitWalletDatabase.MIGRATION_4_5,
            KitWalletDatabase.MIGRATION_5_6,
        )

        assertMetadataSchema(database)
        assertLegacyContinuityPending(database)
        database.query(
            "SELECT namespace, recordKey, version, iv, ciphertext, updatedAtEpochMillis " +
                "FROM secure_messaging_records",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("libsignal-v2", cursor.getString(0))
            assertEquals("code15-active-state", cursor.getString(1))
            assertEquals(4L, cursor.getLong(2))
            assertArrayEquals(originalIv, cursor.getBlob(3))
            assertArrayEquals(originalCiphertext, cursor.getBlob(4))
            assertEquals(2345L, cursor.getLong(5))
            assertFalse(cursor.moveToNext())
        }
        database.close()
    }

    @Test
    fun migration5To6MarksExistingEncryptedStateForLegacyKeyContinuity() {
        val originalIv = byteArrayOf(1, 2, 3, 4)
        val originalCiphertext = byteArrayOf(5, 6, 7, 8)
        helper.createDatabase(MIGRATION_5_6_WITH_STATE_DATABASE, 5).apply {
            execSQL(
                "INSERT INTO secure_messaging_records " +
                    "(namespace, recordKey, version, iv, ciphertext, updatedAtEpochMillis) " +
                    "VALUES (?, ?, ?, ?, ?, ?)",
                arrayOf(
                    "libsignal-v2",
                    "active-protocol-state",
                    3L,
                    originalIv,
                    originalCiphertext,
                    1234L,
                ),
            )
            close()
        }

        val database = helper.runMigrationsAndValidate(
            MIGRATION_5_6_WITH_STATE_DATABASE,
            6,
            true,
            KitWalletDatabase.MIGRATION_5_6,
        )

        assertMetadataSchema(database)
        assertLegacyContinuityPending(database)
        database.query(
            "SELECT namespace, recordKey, version, iv, ciphertext, updatedAtEpochMillis " +
                "FROM secure_messaging_records",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("libsignal-v2", cursor.getString(0))
            assertEquals("active-protocol-state", cursor.getString(1))
            assertEquals(3L, cursor.getLong(2))
            assertArrayEquals(originalIv, cursor.getBlob(3))
            assertArrayEquals(originalCiphertext, cursor.getBlob(4))
            assertEquals(1234L, cursor.getLong(5))
            assertFalse(cursor.moveToNext())
        }
        database.close()
    }

    private fun assertLegacyContinuityPending(database: SupportSQLiteDatabase) {
        database.query(
            "SELECT `key`, value FROM secure_messaging_metadata",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(SECURE_MESSAGING_LEGACY_KEY_CONTINUITY_KEY, cursor.getString(0))
            assertEquals(SECURE_MESSAGING_LEGACY_KEY_CONTINUITY_VALUE, cursor.getString(1))
            assertFalse(cursor.moveToNext())
        }
    }

    @Test
    fun migration5To6LeavesFreshMessagingStateUnmarked() {
        helper.createDatabase(MIGRATION_5_6_EMPTY_DATABASE, 5).close()

        val database = helper.runMigrationsAndValidate(
            MIGRATION_5_6_EMPTY_DATABASE,
            6,
            true,
            KitWalletDatabase.MIGRATION_5_6,
        )

        assertMetadataSchema(database)
        database.query("SELECT COUNT(*) FROM secure_messaging_metadata").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0L, cursor.getLong(0))
        }
        database.close()
    }

    private fun assertMetadataSchema(database: SupportSQLiteDatabase) {
        val columns = database.query("PRAGMA table_info(secure_messaging_metadata)").use { cursor ->
            buildMap {
                val name = cursor.getColumnIndexOrThrow("name")
                val type = cursor.getColumnIndexOrThrow("type")
                val notNull = cursor.getColumnIndexOrThrow("notnull")
                val primaryKey = cursor.getColumnIndexOrThrow("pk")
                while (cursor.moveToNext()) {
                    put(
                        cursor.getString(name),
                        Column(
                            type = cursor.getString(type),
                            notNull = cursor.getInt(notNull),
                            primaryKeyPosition = cursor.getInt(primaryKey),
                        ),
                    )
                }
            }
        }
        assertEquals(
            mapOf(
                "key" to Column("TEXT", 1, 1),
                "value" to Column("TEXT", 1, 0),
            ),
            columns,
        )
    }

    private data class Column(
        val type: String,
        val notNull: Int,
        val primaryKeyPosition: Int,
    )

    private companion object {
        const val DATABASE_NAME = "secure-messaging-migration-test"
        const val MIGRATION_4_6_WITH_STATE_DATABASE =
            "secure-messaging-metadata-migration-4-6-with-state"
        const val MIGRATION_5_6_WITH_STATE_DATABASE =
            "secure-messaging-metadata-migration-5-6-with-state"
        const val MIGRATION_5_6_EMPTY_DATABASE =
            "secure-messaging-metadata-migration-5-6-empty"
    }
}
