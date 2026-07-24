package com.kit.wallet.navigation

import android.net.Uri

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
    const val BILLS = "bills"
    const val BILL_PAY = "bills/pay/{providerId}"
    const val AIRTIME = "bills/airtime"
    const val BANK = "bank"
    const val MOBILE_MONEY = "mobile-money"
    const val CONTACTS = "contacts"
    const val CALL_CONTACTS = "calls/contacts"
    const val CONVERSATION = "chat/{chatId}"
    const val VOICE_CALL = "call/voice/{name}"
    const val VIDEO_CALL = "call/video/{name}"
    const val INCOMING_CALL = "call/incoming/{callId}?accept={accept}"
    const val SECURITY = "settings/security"
    const val PROFILE_EDIT = "settings/profile/edit"

    fun txDetail(id: String) = "wallet/tx/$id"
    fun send(contactId: String? = null) = contactId
        ?.takeIf(String::isNotBlank)
        ?.let { "$SEND?contactId=${Uri.encode(it)}" }
        ?: SEND
    fun billPay(providerId: String) = "bills/pay/$providerId"
    fun conversation(chatId: String) = "chat/${Uri.encode(chatId)}"
    fun voiceCall(name: String) = "call/voice/${Uri.encode(name)}"
    fun videoCall(name: String) = "call/video/${Uri.encode(name)}"
    fun incomingCall(callId: String, accept: Boolean = false) =
        "call/incoming/${Uri.encode(callId)}?accept=${if (accept) "1" else "0"}"
    fun profileSetup(needsPin: Boolean) = "auth/profile/setup?needsPin=$needsPin"
}
