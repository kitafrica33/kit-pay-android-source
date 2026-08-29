package com.kit.wallet.data.notifications

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Content-minimal group-request and scheduled-payment push validated before notification. */
internal data class FinancialPaymentAlert(
    val type: String,
    val conversationId: String,
    val resourceId: String,
    val resourceQueryKey: String,
    val notificationTag: String,
) {
    fun deepLink(): String = "kitwallet://conversation/${encode(conversationId)}?" +
        "$resourceQueryKey=${encode(resourceId)}"

    companion object {
        fun fromData(data: Map<String, String>): FinancialPaymentAlert? {
            val type = data["type"] ?: return null
            val family = when (type) {
                in GROUP_REQUEST_TYPES -> Family(
                    "group_payment_request_id", "open_group_payment_request", "group-payment-request:",
                )
                in SCHEDULED_PAYMENT_TYPES -> Family(
                    "scheduled_payment_id", "open_scheduled_payment", "scheduled-payment:",
                )
                in SCHEDULED_GROUP_TYPES -> Family(
                    "scheduled_group_payment_id", "open_scheduled_group_payment", "scheduled-group-payment:",
                )
                else -> return null
            }
            if (data["action"] != family.action) return null
            val conversationId = PaymentClaimAlert.canonicalUuid(data["conversation_id"]) ?: return null
            val resourceId = PaymentClaimAlert.canonicalUuid(data[family.idKey]) ?: return null
            val expectedTag = family.tagPrefix + resourceId
            val suppliedTag = data["notification_tag"]
            if (suppliedTag != null && suppliedTag != expectedTag) return null
            return FinancialPaymentAlert(type, conversationId, resourceId, family.idKey, expectedTag)
        }

        private fun encode(value: String) =
            URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

        private data class Family(val idKey: String, val action: String, val tagPrefix: String)

        private val GROUP_REQUEST_TYPES = setOf(
            "group_payment_request.created", "group_payment_request.contributed",
            "group_payment_request.completed", "group_payment_request.cancelled",
            "group_payment_request.expired",
        )
        private val SCHEDULED_PAYMENT_TYPES = setOf(
            "scheduled_payment.completed", "scheduled_payment.failed", "scheduled_payment.cancelled",
        )
        private val SCHEDULED_GROUP_TYPES = setOf(
            "scheduled_group_payment.completed", "scheduled_group_payment.failed",
            "scheduled_group_payment.cancelled",
        )
    }
}
