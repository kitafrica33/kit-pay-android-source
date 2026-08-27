package com.kit.wallet.data.remote

/** Feature keys returned by `GET /api/kit-wallet/v1/capabilities`. */
object KitFeature {
    const val WALLETS = "wallets"
    const val INTERNAL_TRANSFERS = "internal_transfers"
    const val CLAIMABLE_TRANSFERS = "claimable_transfers"
    const val GROUP_PAYMENTS = "group_payments"
    const val PAYMENT_REQUESTS = "payment_requests"
    const val MERCHANT_PAYMENTS = "merchant_payments"
    const val QR_PAYMENTS = "qr_payments"
    const val MOBILE_MONEY = "mobile_money"
    const val BANK_TRANSFERS = "bank_transfers"
    const val BANK_DEPOSITS = "bank_deposits"
    const val AIRTIME = "airtime"
    const val BILLS = "bills"
    const val MESSAGING = "messaging"
    const val CALLS = "calls"
    const val NOTIFICATIONS = "notifications"
    const val ABUSE_REPORTING = "abuse_reporting"
    const val KYC = "kyc"
    const val EMAIL_REGISTRATION = "email_registration"
    const val EMAIL_RECOVERY = "email_recovery"
    const val ACCOUNT_DELETION = "account_deletion"
}

/** Held-transfer actions require their wallet and send dependencies as well as the rollout flag. */
internal fun CapabilitiesDto.claimableTransfersAvailable(): Boolean {
    val advertised = features.orEmpty()
    return advertised[KitFeature.WALLETS] == true &&
        advertised[KitFeature.INTERNAL_TRANSFERS] == true &&
        advertised[KitFeature.CLAIMABLE_TRANSFERS] == true
}

/**
 * Group payments exist only where held transfers and group chat already do — the server refuses
 * otherwise, and the composer must not offer what the server is going to decline.
 */
internal fun CapabilitiesDto.groupPaymentsAvailable(): Boolean =
    claimableTransfersAvailable() && features.orEmpty()[KitFeature.GROUP_PAYMENTS] == true
