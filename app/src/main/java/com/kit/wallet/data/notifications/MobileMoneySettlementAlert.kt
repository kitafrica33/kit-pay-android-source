package com.kit.wallet.data.notifications

import java.net.URI

/**
 * Strictly validated mobile-money terminal push.
 *
 * The status is only a wake-up hint: callers must fetch [operationId] from the authenticated
 * operation endpoint before changing wallet or operation state.
 */
internal data class MobileMoneySettlementAlert(
    val type: String,
    val operationId: String,
    val notificationId: String,
    val notificationTag: String,
    val status: String,
    val mobileMoneyType: String,
    val title: String?,
    val body: String?,
) {
    fun link(): MobileMoneySettlementLink = MobileMoneySettlementLink(operationId)

    companion object {
        private const val NOTIFICATION_TAG_PREFIX = "mobile-money-operation:"
        private val CONTRACTS = mapOf(
            "mobile_money.collection.succeeded" to ("collection" to "succeeded"),
            "mobile_money.collection.failed" to ("collection" to "failed"),
            "mobile_money.collection.reversed" to ("collection" to "reversed"),
            "mobile_money.payout.succeeded" to ("payout" to "succeeded"),
            "mobile_money.payout.failed" to ("payout" to "failed"),
            "mobile_money.payout.reversed" to ("payout" to "reversed"),
        )

        fun isCandidate(data: Map<String, String>): Boolean = data["type"] in CONTRACTS

        fun fromData(data: Map<String, String>): MobileMoneySettlementAlert? {
            val type = data["type"] ?: return null
            val contract = CONTRACTS[type] ?: return null
            val operationId = PaymentClaimAlert.canonicalUuid(data["operation_id"]) ?: return null
            val notificationId = PaymentClaimAlert.canonicalUuid(data["notification_id"]) ?: return null
            val notificationTag = data["notification_tag"] ?: return null
            if (notificationTag != NOTIFICATION_TAG_PREFIX + operationId) return null
            if (data["mobile_money_type"] != contract.first) return null
            if (data["status"] != contract.second) return null
            return MobileMoneySettlementAlert(
                type = type,
                operationId = operationId,
                notificationId = notificationId,
                notificationTag = notificationTag,
                status = contract.second,
                mobileMoneyType = contract.first,
                title = data["notification_title"].sanitizedAlertText(MAX_TITLE_LENGTH),
                body = data["notification_body"].sanitizedAlertText(MAX_BODY_LENGTH),
            )
        }

        private fun String?.sanitizedAlertText(maxLength: Int): String? = this
            ?.filterNot(Char::isISOControl)
            ?.trim()
            ?.take(maxLength)
            ?.takeIf(String::isNotEmpty)

        private const val MAX_TITLE_LENGTH = 120
        private const val MAX_BODY_LENGTH = 300
    }
}

/** Locally reconstructed, non-mutating route to the authoritative mobile-money screen. */
internal data class MobileMoneySettlementLink(val operationId: String) {
    fun deepLinkUri(): String = "kitwallet://mobile-money/operation?operation_id=$operationId"

    companion object {
        fun fromDeepLink(raw: String): MobileMoneySettlementLink? {
            val uri = runCatching { URI(raw) }.getOrNull() ?: return null
            if (uri.scheme != "kitwallet" || uri.host != "mobile-money" || uri.path != "/operation") {
                return null
            }
            if (uri.userInfo != null || uri.port != -1 || uri.fragment != null) return null
            val query = uri.rawQuery ?: return null
            val separator = query.indexOf('=')
            if (separator <= 0 || query.indexOf('=', separator + 1) != -1) return null
            if (query.substring(0, separator) != "operation_id") return null
            val operationId = PaymentClaimAlert.canonicalUuid(query.substring(separator + 1))
                ?: return null
            return MobileMoneySettlementLink(operationId)
        }
    }
}
