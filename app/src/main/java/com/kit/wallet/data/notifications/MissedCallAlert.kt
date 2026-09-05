package com.kit.wallet.data.notifications

import com.kit.wallet.data.session.SessionFence
import javax.inject.Inject
import javax.inject.Singleton
import com.kit.wallet.data.session.SessionStore

/** Only a server-designated callee receives a missed-call alert; caller lifecycle sync is quiet. */
internal data class MissedCallAlert(val accountId: String, val callId: String) {
    val identity: String get() = "call.missed:$callId"
    val tag: String get() = "kit-missed-call:${notificationAccountDigest(accountId)}:$callId"

    companion object {
        fun fromData(data: Map<String, String>, owner: SessionFence): MissedCallAlert? {
            if (data["type"] != "call.missed" || data["state"] != "missed" ||
                data["missed_call_alert"] != "true"
            ) return null
            val account = PaymentClaimAlert.canonicalUuid(owner.accountId) ?: return null
            if (PaymentClaimAlert.canonicalUuid(data["recipient_user_id"]) != account) return null
            val call = PaymentClaimAlert.canonicalUuid(data["call_id"]) ?: return null
            return MissedCallAlert(account, call)
        }
    }
}

internal const val CALL_HISTORY_NOTIFICATION_LINK = "kitwallet://calls/history"

/** Legacy lifecycle envelopes omit a recipient; an explicit recipient must match exactly. */
internal fun explicitPushRecipientMatches(data: Map<String, String>, owner: SessionFence?): Boolean {
    val recipient = data["recipient_user_id"] ?: return true
    val account = PaymentClaimAlert.canonicalUuid(owner?.accountId) ?: return false
    return PaymentClaimAlert.canonicalUuid(recipient) == account
}

/** Publication and its receipt share the same session lock used by logout and account changes. */
@Singleton
internal class NotificationAlertDelivery @Inject constructor(
    private val sessions: SessionStore,
    private val store: NotificationRecoveryStore,
) {
    suspend fun deliver(
        owner: SessionFence,
        identity: String,
        canDisplay: () -> Boolean,
        alreadyDisplayed: () -> Boolean,
        onAuthenticatedHint: () -> Unit = {},
        display: () -> Unit,
    ): Boolean = sessions.withCurrentSession(owner) {
        val account = PaymentClaimAlert.canonicalUuid(owner.accountId)
            ?: return@withCurrentSession true
        // A settlement hint wakes its authoritative GET even if its visual alert is muted,
        // already receipted, coalesced with a newer state, or blocked by notification quota.
        onAuthenticatedHint()
        if (store.delivered(account, identity)) return@withCurrentSession true
        if (alreadyDisplayed()) {
            store.recordDelivery(account, identity)
            return@withCurrentSession true
        }
        if (!canDisplay()) return@withCurrentSession false
        // Keep notify before its receipt. A crash here can replace the same active tag once;
        // recording first would permanently lose an alert if the process died before notify.
        display()
        store.recordDelivery(account, identity)
        true
    }
}
