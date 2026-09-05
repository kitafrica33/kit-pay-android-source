package com.kit.wallet.data.notifications

/** Shares the 18 non-message slots with live calls, service notices and other system surfaces. */
internal const val MAX_ACTIVE_RECOVERED_ALERTS = 12

internal data class ActiveRecoveredAlert(val tag: String, val id: Int, val occurredAt: Long)
internal data class RecoveredAlertQuotaPlan(val display: Boolean, val cancel: List<ActiveRecoveredAlert>)

internal fun planRecoveredAlertQuota(
    active: List<ActiveRecoveredAlert>,
    tag: String,
    occurredAt: Long,
): RecoveredAlertQuotaPlan {
    val others = active.filter { it.tag != tag }.sortedBy { it.occurredAt }
    if (others.size < MAX_ACTIVE_RECOVERED_ALERTS) return RecoveredAlertQuotaPlan(true, emptyList())
    // An older continuation must never evict the fresh alerts fetched from the head. Unreceipted
    // rows remain unread on the server and are reconsidered after space becomes available.
    if (occurredAt <= others[others.size - MAX_ACTIVE_RECOVERED_ALERTS].occurredAt) {
        return RecoveredAlertQuotaPlan(false, emptyList())
    }
    return RecoveredAlertQuotaPlan(true, others.take(others.size - MAX_ACTIVE_RECOVERED_ALERTS + 1))
}
