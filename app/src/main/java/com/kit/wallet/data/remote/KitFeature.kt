package com.kit.wallet.data.remote

/** Feature keys returned by `GET /api/kit-wallet/v1/capabilities`. */
object KitFeature {
    const val WALLETS = "wallets"
    const val INTERNAL_TRANSFERS = "internal_transfers"
    const val PAYMENT_REQUESTS = "payment_requests"
    const val MERCHANT_PAYMENTS = "merchant_payments"
    const val QR_PAYMENTS = "qr_payments"
    const val MOBILE_MONEY = "mobile_money"
    const val BANK_TRANSFERS = "bank_transfers"
    const val AIRTIME = "airtime"
    const val BILLS = "bills"
    const val MESSAGING = "messaging"
    const val CALLS = "calls"
    const val NOTIFICATIONS = "notifications"
    const val KYC = "kyc"
    const val EMAIL_REGISTRATION = "email_registration"
    const val EMAIL_RECOVERY = "email_recovery"
    const val ACCOUNT_DELETION = "account_deletion"
}
