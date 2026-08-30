package com.kit.wallet.navigation

import android.net.Uri
import com.kit.wallet.feature.wallet.FinancialBlockReason

/**
 * Where a landing on the retired email-registration route continues. Phone OTP is the only
 * way an account is created; a back stack restored from an older release must resume on a
 * real screen — the phone screen when signed out, home when signed in — never sit blank.
 */
internal fun retiredRegisterRedirect(signedIn: Boolean): String =
    if (signedIn) Dest.HOME else Dest.PHONE_LOGIN

/** Route constants. Kept as plain strings until type-safe navigation lands. */
object Dest {
    const val ONBOARDING = "onboarding"
    const val PHONE_LOGIN = "auth/phone"
    const val OTP = "auth/otp"
    const val REGISTER = "auth/register"
    const val VERIFY_EMAIL = "auth/email/verify"
    const val FORGOT_PASSWORD = "auth/password/forgot"
    const val RESET_PASSWORD = "auth/password/reset"
    const val PIN_SETUP = "auth/pin"
    const val PROFILE_SETUP = "auth/profile/setup?needsPin={needsPin}"
    const val PIN_CHANGE = "settings/security/pin"
    const val MFA = "settings/security/mfa"
    const val KYC = "settings/identity-verification"

    const val HOME = "home"
    const val CHATS = "chats"
    const val CALLS = "calls"
    const val SETTINGS = "settings"

    const val SEND = "wallet/send"
    const val SEND_ROUTE = "$SEND?contactId={contactId}"
    const val RECEIVE = "wallet/receive"
    const val SCAN = "wallet/scan"
    const val REQUEST = "wallet/request"
    const val TRANSACTIONS = "wallet/transactions"
    const val TX_DETAIL = "wallet/tx/{txId}"
    const val FINANCIAL_ACCESS = "wallet/access"
    const val BILLS = "bills"
    const val BILL_PAY = "bills/pay/{providerId}"
    const val AIRTIME = "bills/airtime"
    const val BANK = "bank"
    const val MOBILE_MONEY = "mobile-money"
    const val CONTACTS = "contacts"
    const val CALL_CONTACTS = "calls/contacts"
    const val CONVERSATION = "chat/{chatId}"
    const val NEW_GROUP = "groups/new"
    const val GROUP_PROFILE = "chat/{chatId}/group"
    const val GROUP_ADD = "chat/{chatId}/group/add"
    const val GROUP_DESCRIPTION = "chat/{chatId}/group/description"
    const val GROUP_PHOTO = "chat/{chatId}/group/photo"
    const val VOICE_CALL = "call/voice/{name}"
    const val VIDEO_CALL = "call/video/{name}"
    const val INCOMING_CALL = "call/incoming/{callId}?accept={accept}"
    const val SECURITY = "settings/security"
    const val PROFILE_EDIT = "settings/profile/edit"
    const val CHAT_BACKUP = "settings/chats/backup"
    // Support is reachable from Settings only; nothing else links into these routes.
    const val SUPPORT = "settings/support"
    const val SUPPORT_NEW_TICKET = "settings/support/new"
    const val SUPPORT_TICKET = "settings/support/ticket/{ticketId}"
    const val REFERRALS = "settings/referrals"

    fun txDetail(id: String) = "wallet/tx/$id"
    fun send(contactId: String? = null) = contactId
        ?.takeIf(String::isNotBlank)
        ?.let { "$SEND?contactId=${Uri.encode(it)}" }
        ?: SEND
    fun billPay(providerId: String) = "bills/pay/$providerId"
    fun conversation(chatId: String) = "chat/${Uri.encode(chatId)}"
    fun groupProfile(chatId: String) = "chat/${Uri.encode(chatId)}/group"
    fun groupAdd(chatId: String) = "chat/${Uri.encode(chatId)}/group/add"
    fun groupDescription(chatId: String) = "chat/${Uri.encode(chatId)}/group/description"
    fun groupPhoto(chatId: String) = "chat/${Uri.encode(chatId)}/group/photo"
    fun voiceCall(name: String) = "call/voice/${Uri.encode(name)}"
    fun videoCall(name: String) = "call/video/${Uri.encode(name)}"
    fun incomingCall(callId: String, accept: Boolean = false) =
        "call/incoming/${Uri.encode(callId)}?accept=${if (accept) "1" else "0"}"
    fun profileSetup(needsPin: Boolean) = "auth/profile/setup?needsPin=$needsPin"
    fun supportTicket(ticketId: String) = "settings/support/ticket/${Uri.encode(ticketId)}"
}

/**
 * Routes that expose wallet balances, payment instruments, or money instructions.
 *
 * This is intentionally separate from capability routing: a feature can be deployed and still be
 * unavailable to an account that has not completed identity verification. Dynamic route values
 * are matched explicitly so restored back stacks and deep links cannot bypass the same boundary
 * used by dashboard taps.
 */
internal fun isFinancialRoute(route: String?): Boolean {
    if (route == null) return false
    if (route == Dest.SEND || route == Dest.SEND_ROUTE || route.startsWith("${Dest.SEND}?")) {
        return true
    }
    if (route == Dest.TX_DETAIL || route.startsWith("wallet/tx/")) return true
    if (route == Dest.BILL_PAY || route.startsWith("bills/pay/")) return true
    return route in setOf(
        Dest.RECEIVE,
        Dest.SCAN,
        Dest.REQUEST,
        Dest.TRANSACTIONS,
        Dest.BILLS,
        Dest.AIRTIME,
        Dest.BANK,
        Dest.MOBILE_MONEY,
    )
}

/** App Review's synthetic account may inspect history, but can never initiate money movement. */
internal fun isReadOnlyFinancialRoute(route: String?): Boolean =
    route == Dest.TRANSACTIONS || route == Dest.TX_DETAIL || route?.startsWith("wallet/tx/") == true

internal fun financialRouteAccessAllowed(
    route: String?,
    moneyAccessAllowed: Boolean,
    moneyReadOnly: Boolean,
): Boolean = when {
    !isFinancialRoute(route) -> true
    !moneyAccessAllowed -> false
    !moneyReadOnly -> true
    else -> isReadOnlyFinancialRoute(route)
}

internal fun financialRouteRedirect(
    route: String?,
    moneyAccessAllowed: Boolean,
    moneyReadOnly: Boolean = false,
): String? = Dest.HOME.takeIf {
    !financialRouteAccessAllowed(route, moneyAccessAllowed, moneyReadOnly)
}

/** Full-screen destination for a blocked financial entry point; session assurance owns its gate. */
internal fun financialAccessDestination(
    blockReason: FinancialBlockReason?,
    verificationAvailable: Boolean,
): String? = when (blockReason) {
    FinancialBlockReason.VERIFY_IDENTITY ->
        if (verificationAvailable) Dest.KYC else Dest.FINANCIAL_ACCESS
    FinancialBlockReason.READ_ONLY, null -> Dest.FINANCIAL_ACCESS
    FinancialBlockReason.SESSION_ASSURANCE -> null
}
