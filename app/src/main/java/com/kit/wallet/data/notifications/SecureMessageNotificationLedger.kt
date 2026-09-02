package com.kit.wallet.data.notifications

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Crash-durable identity of the message currently owning each conversation notification row.
 *
 * Android's active-notification query is normally authoritative, but some OEM services throw
 * while a direct reply is being dispatched. This tiny ledger gives that recovery path enough
 * information to replace the spinner without ever overwriting a newer message preview.
 */
@Singleton
internal class SecureMessageNotificationLedger @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun currentDigest(notificationTag: String): String? = runCatching {
        preferences.getString(notificationTag, null)
    }.getOrNull()?.takeIf(SECURE_MESSAGE_DIGEST_PATTERN::matches)

    fun record(notificationTag: String, messageDigest: String): Boolean {
        require(notificationTag.startsWith(SECURE_MESSAGE_CONVERSATION_TAG_PREFIX))
        require(SECURE_MESSAGE_DIGEST_PATTERN.matches(messageDigest))
        return preferences.edit().putString(notificationTag, messageDigest).commit()
    }

    fun restore(notificationTag: String, previousDigest: String?) {
        val editor = preferences.edit()
        if (previousDigest == null) {
            editor.remove(notificationTag)
        } else {
            editor.putString(notificationTag, previousDigest)
        }
        editor.commit()
    }

    fun remove(notificationTag: String) {
        preferences.edit().remove(notificationTag).commit()
    }

    fun clear() {
        preferences.edit().clear().commit()
    }

    private companion object {
        const val PREFERENCES = "kit_secure_message_notification_v1"
    }
}
