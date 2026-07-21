package com.kit.wallet.data.notifications

data object MessagingWakePayload {
    fun matches(data: Map<String, String>): Boolean =
        isCandidate(data) &&
            data["scope"] == SCOPE &&
            data.keys.all(ALLOWED_KEYS::contains)

    fun isCandidate(data: Map<String, String>): Boolean = data["type"] == TYPE

    private const val TYPE = "messaging.sync"
    private const val SCOPE = "messaging"
    private val ALLOWED_KEYS = setOf("type", "scope", "notification_id")
}
