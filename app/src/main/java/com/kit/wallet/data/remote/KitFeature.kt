package com.kit.wallet.data.remote

/** Feature keys returned by `GET /api/kit-wallet/v1/capabilities`. */
object KitFeature {
    const val WALLETS = "wallets"
    const val INTERNAL_TRANSFERS = "internal_transfers"
    const val CLAIMABLE_TRANSFERS = "claimable_transfers"
    const val GROUP_PAYMENTS = "group_payments"
    const val GROUP_PAYMENT_REQUESTS_V1 = "group_payment_requests_v1"
    const val SCHEDULED_PAYMENTS = "scheduled_payments"
    const val SCHEDULED_CHAT_PAYMENTS_V1 = "scheduled_chat_payments_v1"
    const val SCHEDULED_GROUP_PAYMENTS_V1 = "scheduled_group_payments_v1"
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
    const val EMAIL_RECOVERY = "email_recovery"
    const val ACCOUNT_DELETION = "account_deletion"

    /**
     * Server-owned starter checklist (onboarding milestones). Until the backend advertises
     * this key as exactly `true`, the client never calls the onboarding route and starter
     * milestones rest on device-local evidence alone.
     */
    const val STARTER_CHECKLIST = "starter_checklist"

    /**
     * Authenticated in-app support. The feature flag alone is not sufficient: the surface
     * additionally requires the exact `protocols.support` handshake
     * (see `com.kit.wallet.data.support.SupportContract`), and every deviation fails closed.
     */
    const val SUPPORT = "support"

    /** Customer-initiated, company-beneficiary payments inside a support ticket. */
    const val SUPPORT_PAYMENTS = "support_payments"

    /** Presentation gate for the support AI assistant; per-ticket state stays authoritative. */
    const val SUPPORT_AI = "support_ai"

    /**
     * Referral program surface. Dark unless this key is exactly `true` on a successfully
     * loaded snapshot; the client renders only server-provided policy and reward states.
     */
    const val REFERRALS = "referrals"
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

internal fun CapabilitiesDto.groupPaymentRequestsAvailable(): Boolean =
    features.orEmpty()[KitFeature.WALLETS] == true &&
        features.orEmpty()[KitFeature.INTERNAL_TRANSFERS] == true &&
        features.orEmpty()[KitFeature.GROUP_PAYMENT_REQUESTS_V1] == true &&
        protocols?.payments?.groupPaymentRequests?.supportsAndroidV1 == true

internal fun CapabilitiesDto.scheduledChatPaymentsAvailable(): Boolean =
    features.orEmpty()[KitFeature.WALLETS] == true &&
        features.orEmpty()[KitFeature.INTERNAL_TRANSFERS] == true &&
        features.orEmpty()[KitFeature.SCHEDULED_PAYMENTS] == true &&
        features.orEmpty()[KitFeature.SCHEDULED_CHAT_PAYMENTS_V1] == true &&
        protocols?.payments?.scheduledChatPayments?.supportsAndroidV1 == true

internal fun CapabilitiesDto.scheduledGroupPaymentsAvailable(): Boolean =
    groupPaymentsAvailable() &&
        features.orEmpty()[KitFeature.SCHEDULED_PAYMENTS] == true &&
        features.orEmpty()[KitFeature.SCHEDULED_GROUP_PAYMENTS_V1] == true &&
        protocols?.payments?.scheduledGroupPayments?.supportsAndroidV1 == true
