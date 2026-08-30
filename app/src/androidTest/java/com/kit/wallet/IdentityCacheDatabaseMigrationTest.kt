package com.kit.wallet

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kit.wallet.data.local.AUTHENTICATED_CACHE_OWNER_KEY
import com.kit.wallet.data.local.KitWalletDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IdentityCacheDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        KitWalletDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migration11To12ScopesSafePhotosAndDropsUnrecoverablePhoneSuffixes() {
        helper.createDatabase(DATABASE, 11).apply {
            execSQL(
                "INSERT INTO sync_state (`key`, value) VALUES (?, ?)",
                arrayOf(AUTHENTICATED_CACHE_OWNER_KEY, OWNER_A),
            )
            execSQL(
                "INSERT INTO profile_photos (userId, avatarUrl, updatedAtEpochMillis) " +
                    "VALUES (?, ?, ?)",
                arrayOf<Any?>("user-a", "https://pay.kit.africa/avatar/a", 10L),
            )
            execSQL(
                "INSERT INTO beneficiary_contacts " +
                    "(beneficiaryId, phoneKey, updatedAtEpochMillis) VALUES (?, ?, ?)",
                arrayOf<Any?>("beneficiary-a", "700000001", 11L),
            )
            close()
        }

        val database = helper.runMigrationsAndValidate(
            DATABASE,
            12,
            true,
            KitWalletDatabase.MIGRATION_11_12,
        )

        database.query(
            "SELECT ownerScopeId, userId, avatarUrl FROM profile_photos",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(OWNER_A, cursor.getString(0))
            assertEquals("user-a", cursor.getString(1))
            assertEquals("https://pay.kit.africa/avatar/a", cursor.getString(2))
            assertTrue(!cursor.moveToNext())
        }
        assertEquals(0L, database.longQuery("SELECT COUNT(*) FROM beneficiary_contacts"))
        assertTrue("legalName" in database.columns("profile"))
        assertTrue("usernameRequired" in database.columns("profile"))
        assertEquals(
            listOf("ownerScopeId", "beneficiaryId", "phoneIdentity", "updatedAtEpochMillis"),
            database.columns("beneficiary_contacts"),
        )
        assertTrue("index_profile_photos_ownerScopeId" in database.indices("profile_photos"))
        assertTrue(
            "index_beneficiary_contacts_ownerScopeId" in database.indices("beneficiary_contacts"),
        )
        database.close()
    }

    @Test
    fun migration13To14AddsServerOwnedAccountVerificationWithoutInferringIt() {
        helper.createDatabase(DATABASE_13_14, 13).close()

        val database = helper.runMigrationsAndValidate(
            DATABASE_13_14,
            14,
            true,
            KitWalletDatabase.MIGRATION_13_14,
        )

        assertTrue("verificationDesignation" in database.columns("profile"))
        assertTrue("verificationSince" in database.columns("profile"))
        database.close()
    }

    @Test
    fun migration14To15AddsServerOwnedCounterpartyVerificationWithoutGuessing() {
        helper.createDatabase(DATABASE_14_15, 14).close()

        val database = helper.runMigrationsAndValidate(
            DATABASE_14_15,
            15,
            true,
            KitWalletDatabase.MIGRATION_14_15,
        )

        assertTrue("counterpartyUserId" in database.columns("wallet_transactions"))
        assertTrue("counterpartyAvatarUrl" in database.columns("wallet_transactions"))
        assertTrue(
            "counterpartyVerificationDesignation" in database.columns("wallet_transactions"),
        )
        assertTrue("counterpartyVerificationSince" in database.columns("wallet_transactions"))
        database.close()
    }

    private fun SupportSQLiteDatabase.columns(table: String): List<String> =
        query("PRAGMA table_info($table)").use { cursor ->
            val name = cursor.getColumnIndexOrThrow("name")
            buildList { while (cursor.moveToNext()) add(cursor.getString(name)) }
        }

    private fun SupportSQLiteDatabase.indices(table: String): Set<String> =
        query("PRAGMA index_list($table)").use { cursor ->
            val name = cursor.getColumnIndexOrThrow("name")
            buildSet { while (cursor.moveToNext()) add(cursor.getString(name)) }
        }

    private fun SupportSQLiteDatabase.longQuery(sql: String): Long = query(sql).use { cursor ->
        assertTrue(cursor.moveToFirst())
        cursor.getLong(0)
    }

    private companion object {
        const val DATABASE = "identity-cache-migration-11-12"
        const val DATABASE_13_14 = "identity-cache-migration-13-14"
        const val DATABASE_14_15 = "identity-cache-migration-14-15"
        const val OWNER_A = "scope-account-a"
    }
}
