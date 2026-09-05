package com.kit.wallet.data.notifications

import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.ApiCallResult
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.session.SessionFence
import com.kit.wallet.data.session.SessionStore
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@JsonClass(generateAdapter = false)
data class NotificationInboxPage(val items: List<NotificationInboxItem>)

@JsonClass(generateAdapter = false)
data class NotificationInboxItem(
    val id: String,
    val type: String,
    val title: String? = null,
    val body: String? = null,
    val data: Map<String, Any?> = emptyMap(),
    val silent: Boolean = true,
    @Json(name = "read_at") val readAt: String? = null,
    @Json(name = "created_at") val createdAt: String? = null,
) {
    fun alertEnvelope(): PushEnvelope? {
        if (silent || readAt != null || PaymentClaimAlert.canonicalUuid(id) == null) return null
        // Encrypted messages are recovered by the authenticated pull/decrypt worker. Neither
        // notification inbox text nor a call lifecycle row may recreate an incoming-call route.
        if (type.startsWith("messaging.") || type.startsWith("message.") ||
            type == "message_available" || data["scope"] == "messaging" ||
            (type.startsWith("call.") && type != "call.missed")
        ) return null
        val values = data.mapNotNull { (key, value) ->
            when (value) {
                is String -> key to value
                is Boolean -> key to value.toString()
                else -> null
            }
        }.toMap() + mapOf("notification_id" to id, "type" to type)
        return PushEnvelope(
            values, PushNotificationContent(title, body),
            occurredAtEpochMillis = createdAt?.let {
                runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull()
            },
        )
    }
}

internal interface NotificationInboxAlertSink {
    /** False omits a receipt when Android notifications are currently disabled. */
    suspend fun recoverAlert(owner: SessionFence, envelope: PushEnvelope): Boolean
}

internal interface NotificationRecoveryStore {
    fun cursor(accountId: String): String?
    fun saveCursor(accountId: String, cursor: String?)
    fun delivered(accountId: String, identity: String): Boolean
    fun recordDelivery(accountId: String, identity: String)
}

/**
 * The server cursor goes toward OLDER unread rows, never toward newly delivered events. Each
 * bounded pass checks the head, then resumes its persisted older continuation. A completed scan
 * restarts at the head on the next wake, including scheduled notifications created in the past.
 * Receipts are durable and account scoped, so a restart or a push/inbox race cannot re-alert.
 */
@Singleton
internal class NotificationInboxRecovery @Inject constructor(
    private val api: KitWalletApi,
    private val apiCalls: ApiCallExecutor,
    private val sessions: SessionStore,
    private val store: NotificationRecoveryStore,
    private val sink: NotificationInboxAlertSink,
) {
    private val mutex = Mutex()

    /** True requests another bounded pass; an offline/protocol failure preserves the cursor. */
    suspend fun recover(): Boolean = mutex.withLock {
        val owner = sessions.current()?.fence() ?: return@withLock false
        val account = PaymentClaimAlert.canonicalUuid(owner.accountId) ?: return@withLock false
        var cursor = store.cursor(account)
        if (cursor?.let(::validCursor) == false) {
            // A corrupt local checkpoint is safe to discard: scans always restart from the
            // authenticated head and receipts prevent re-alerting already processed events.
            sessions.withCurrentSession(owner) { store.saveCursor(account, null) }
            cursor = null
        }
        val visited = mutableSetOf<String>()
        val head = fetch(owner, null)
        publish(owner, head.data.items)
        if (cursor == null) {
            cursor = nextCursor(head)
            sessions.withCurrentSession(owner) { store.saveCursor(account, cursor) }
        }
        repeat(MAX_OLDER_PAGES_PER_PASS) {
            val current = cursor ?: return@withLock false
            check(visited.add(current)) { "Notification inbox repeated its continuation" }
            val page = fetch(owner, current)
            publish(owner, page.data.items)
            cursor = nextCursor(page)
            check(cursor == null || cursor !in visited) { "Notification inbox repeated its continuation" }
            sessions.withCurrentSession(owner) { store.saveCursor(account, cursor) }
        }
        cursor != null
    }

    private suspend fun fetch(owner: SessionFence, cursor: String?): ApiCallResult<NotificationInboxPage> {
        check(cursor == null || validCursor(cursor)) { "Invalid notification inbox continuation" }
        val page = apiCalls.executeWithMeta {
            api.notificationInbox(cursor = cursor, limit = 100, unreadOnly = true, expectedOwner = owner)
        }
        // Validate the whole page BEFORE alert publication or checkpoint changes. Missing or
        // contradictory pagination metadata is not proof that an older scan is complete.
        check(page.data.items.size <= 100) { "Notification inbox exceeded its requested page limit" }
        val meta = checkNotNull(page.meta) { "Notification inbox omitted pagination metadata" }
        val hasMore = checkNotNull(meta.hasMore) { "Notification inbox omitted its completion state" }
        check(if (hasMore) meta.nextCursor?.let(::validCursor) == true else meta.nextCursor == null) {
            "Notification inbox returned inconsistent pagination metadata"
        }
        return page
    }

    private suspend fun publish(owner: SessionFence, items: List<NotificationInboxItem>) {
        for (item in items) {
            // Check even skipped rows, so logout cannot advance the old owner's continuation.
            sessions.withCurrentSession(owner) { }
            val envelope = item.alertEnvelope() ?: continue
            // A muted payment channel must not starve older missed calls. No read state is
            // changed: undisplayed items get another chance in the next complete scan.
            sink.recoverAlert(owner, envelope)
        }
    }

    private fun nextCursor(page: ApiCallResult<NotificationInboxPage>): String? =
        checkNotNull(page.meta).nextCursor

    private fun validCursor(cursor: String): Boolean = cursor.isNotBlank() && cursor.length <= 2048

    private companion object {
        const val MAX_OLDER_PAGES_PER_PASS = 3
    }
}
