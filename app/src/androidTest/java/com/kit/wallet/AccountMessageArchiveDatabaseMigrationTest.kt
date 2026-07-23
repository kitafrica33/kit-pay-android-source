package com.kit.wallet

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kit.wallet.data.local.KitWalletDatabase
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountMessageArchiveDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        KitWalletDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migration4To5CreatesIsolatedOpaqueArchiveAndPreservesActiveState() {
        val originalIv = byteArrayOf(1, 2, 3, 4)
        val originalCiphertext = byteArrayOf(5, 6, 7, 8)
        helper.createDatabase(MIGRATION_4_5_DATABASE, 4).apply {
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
            MIGRATION_4_5_DATABASE,
            5,
            true,
            KitWalletDatabase.MIGRATION_4_5,
        )

        assertArchiveSchema(database)
        assertEquals(0L, database.longQuery("SELECT COUNT(*) FROM account_message_archive"))
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
            assertTrue(!cursor.moveToNext())
        }

        insertArchive(database, ACCOUNT_A, INSTALLATION_A, "message:one")
        insertArchive(database, ACCOUNT_B, INSTALLATION_A, "message:one")
        insertArchive(database, ACCOUNT_A, INSTALLATION_B, "message:one")
        assertEquals(3L, database.longQuery("SELECT COUNT(*) FROM account_message_archive"))
        assertTrue(
            runCatching {
                insertArchive(database, ACCOUNT_A, INSTALLATION_A, "message:one")
            }.isFailure,
        )
        database.close()
    }

    @Test
    fun migrations3To5CreateBothIndependentEncryptedStores() {
        helper.createDatabase(MIGRATION_3_5_DATABASE, 3).close()

        val database = helper.runMigrationsAndValidate(
            MIGRATION_3_5_DATABASE,
            5,
            true,
            KitWalletDatabase.MIGRATION_3_4,
            KitWalletDatabase.MIGRATION_4_5,
        )

        assertEquals(
            1L,
            database.longQuery(
                "SELECT COUNT(*) FROM sqlite_master " +
                    "WHERE type = 'table' AND name = 'secure_messaging_records'",
            ),
        )
        assertArchiveSchema(database)
        database.close()
    }

    private fun assertArchiveSchema(database: SupportSQLiteDatabase) {
        val columns = database.query("PRAGMA table_info(account_message_archive)").use { cursor ->
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
                "ownerAccountId" to Column("TEXT", 1, 1),
                "installationId" to Column("TEXT", 1, 2),
                "recordKey" to Column("TEXT", 1, 3),
                "version" to Column("INTEGER", 1, 0),
                "iv" to Column("BLOB", 1, 0),
                "ciphertext" to Column("BLOB", 1, 0),
                "updatedAtEpochMillis" to Column("INTEGER", 1, 0),
            ),
            columns,
        )

        val indexName = "index_account_message_archive_ownerAccountId_installationId"
        val indices = database.query("PRAGMA index_list(account_message_archive)").use { cursor ->
            buildSet {
                val name = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) add(cursor.getString(name))
            }
        }
        assertTrue(indexName in indices)
        val indexColumns = database.query("PRAGMA index_info($indexName)").use { cursor ->
            buildList {
                val name = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) add(cursor.getString(name))
            }
        }
        assertEquals(listOf("ownerAccountId", "installationId"), indexColumns)
    }

    private fun insertArchive(
        database: SupportSQLiteDatabase,
        ownerAccountId: String,
        installationId: String,
        recordKey: String,
    ) {
        database.execSQL(
            "INSERT INTO account_message_archive " +
                "(ownerAccountId, installationId, recordKey, version, iv, ciphertext, " +
                "updatedAtEpochMillis) VALUES (?, ?, ?, ?, ?, ?, ?)",
            arrayOf(
                ownerAccountId,
                installationId,
                recordKey,
                1L,
                byteArrayOf(1, 2, 3),
                byteArrayOf(4, 5, 6),
                1234L,
            ),
        )
    }

    private fun SupportSQLiteDatabase.longQuery(sql: String): Long = query(sql).use { cursor ->
        assertTrue(cursor.moveToFirst())
        cursor.getLong(0)
    }

    private data class Column(
        val type: String,
        val notNull: Int,
        val primaryKeyPosition: Int,
    )

    private companion object {
        const val MIGRATION_4_5_DATABASE = "account-message-archive-migration-4-5"
        const val MIGRATION_3_5_DATABASE = "account-message-archive-migration-3-5"
        const val ACCOUNT_A = "11111111-1111-4111-8111-111111111111"
        const val ACCOUNT_B = "22222222-2222-4222-8222-222222222222"
        const val INSTALLATION_A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val INSTALLATION_B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    }
}
