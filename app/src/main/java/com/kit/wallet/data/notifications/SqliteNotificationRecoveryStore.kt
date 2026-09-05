package com.kit.wallet.data.notifications

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Stores only opaque pagination cursors and digests, never alert text, phone numbers or tokens. */
@Singleton
internal class SqliteNotificationRecoveryStore @Inject constructor(
    @ApplicationContext context: Context,
) : SQLiteOpenHelper(context, "notification-recovery.db", null, 1), NotificationRecoveryStore {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE receipt (account TEXT NOT NULL, identity TEXT NOT NULL, PRIMARY KEY(account, identity))")
        db.execSQL("CREATE TABLE continuation (account TEXT PRIMARY KEY NOT NULL, cursor TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    override fun cursor(accountId: String): String? = readableDatabase.query(
        "continuation", arrayOf("cursor"), "account = ?", arrayOf(notificationAccountDigest(accountId)),
        null, null, null,
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    override fun saveCursor(accountId: String, cursor: String?) {
        val account = notificationAccountDigest(accountId)
        if (cursor == null) {
            writableDatabase.delete("continuation", "account = ?", arrayOf(account))
        } else {
            writableDatabase.insertWithOnConflict(
                "continuation", null, ContentValues().apply {
                    put("account", account)
                    put("cursor", cursor)
                }, SQLiteDatabase.CONFLICT_REPLACE,
            ).also { check(it != -1L) { "Could not checkpoint notification recovery" } }
        }
    }

    override fun delivered(accountId: String, identity: String): Boolean = readableDatabase.query(
        "receipt", arrayOf("identity"), "account = ? AND identity = ?",
        arrayOf(notificationAccountDigest(accountId), notificationAccountDigest(identity)),
        null, null, null,
    ).use { it.moveToFirst() }

    override fun recordDelivery(accountId: String, identity: String) {
        writableDatabase.insertWithOnConflict(
            "receipt", null, ContentValues().apply {
                put("account", notificationAccountDigest(accountId))
                put("identity", notificationAccountDigest(identity))
            }, SQLiteDatabase.CONFLICT_REPLACE,
        ).also { check(it != -1L) { "Could not checkpoint notification delivery" } }
    }
}

internal fun notificationAccountDigest(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
