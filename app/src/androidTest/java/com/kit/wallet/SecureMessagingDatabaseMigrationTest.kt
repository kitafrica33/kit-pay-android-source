package com.kit.wallet

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kit.wallet.data.local.KitWalletDatabase
import org.junit.Assert.assertEquals
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

    private companion object {
        const val DATABASE_NAME = "secure-messaging-migration-test"
    }
}
