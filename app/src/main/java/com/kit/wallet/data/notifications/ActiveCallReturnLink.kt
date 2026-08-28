package com.kit.wallet.data.notifications

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * The ongoing-call notification's way back into the call it names.
 *
 * Carries nothing but the call id. Tapping the notification must *return* to the live call —
 * never answer, never redial, never tear anything down — so the link deliberately has no verb:
 * the app matches the id against the call it already knows it is in and reopens that screen, or
 * does nothing at all when they disagree (a stale notification outliving its call, a tap landing
 * after a new call replaced the old one).
 */
data class ActiveCallReturnLink(val callId: String) {

    fun deepLinkUri(): String = "kitwallet://call/active?call_id=" + callId.urlEncode()

    companion object {
        /**
         * Builds a link for [callId], or null when the id is not a server-issued UUID. The id is
         * canonicalized the same way incoming-call ids are, so the tap-time comparison is between
         * two ids normalized by the same rule.
         */
        fun forCallId(callId: String?): ActiveCallReturnLink? =
            normalizedCallId(callId)?.let(::ActiveCallReturnLink)

        /** Parses [raw], accepting only this link's exact scheme, host, path and a valid id. */
        fun fromDeepLink(raw: String): ActiveCallReturnLink? {
            val uri = runCatching { URI(raw) }.getOrNull() ?: return null
            if (uri.scheme != "kitwallet" || uri.host != "call" || uri.path != "/active") {
                return null
            }
            val query = uri.rawQuery.orEmpty()
                .split('&')
                .mapNotNull { part ->
                    val separator = part.indexOf('=')
                    if (separator <= 0) return@mapNotNull null
                    val key = part.substring(0, separator).urlDecode() ?: return@mapNotNull null
                    val value = part.substring(separator + 1).urlDecode() ?: return@mapNotNull null
                    key to value
                }
                .toMap()
            return forCallId(query["call_id"])
        }

        private fun normalizedCallId(raw: String?): String? {
            val trimmed = raw?.trim()?.takeIf(String::isNotEmpty) ?: return null
            return runCatching { UUID.fromString(trimmed).toString() }.getOrNull()
        }
    }
}

/** What a tap on the ongoing-call notification may do. Nothing here starts or ends a call. */
enum class CallReopenAction {
    /** The call screen is already in front; bringing the app forward was the whole job. */
    ALREADY_OPEN,

    /** Return to the call screen that is still on the back stack, without disturbing it. */
    POP_BACK_TO_CALL,

    /** The link does not name the call this device is in — do nothing. */
    IGNORE,
}

/**
 * Decides what a validated return link may do, given the call the app knows it is in.
 *
 * The link's id must name the current call exactly (ignoring case, like every call-id comparison
 * in the call stack); anything else — no live call, a different call, a blank id — is IGNORE.
 * Failing closed here is what keeps a stale notification from opening the wrong call or
 * restarting a finished one.
 */
fun callReopenDecision(
    requestedCallId: String?,
    activeCallId: String?,
    onCallRoute: Boolean,
): CallReopenAction {
    if (requestedCallId.isNullOrBlank()) return CallReopenAction.IGNORE
    if (activeCallId.isNullOrBlank()) return CallReopenAction.IGNORE
    if (!requestedCallId.equals(activeCallId, ignoreCase = true)) return CallReopenAction.IGNORE
    return if (onCallRoute) CallReopenAction.ALREADY_OPEN else CallReopenAction.POP_BACK_TO_CALL
}

private fun String.urlEncode(): String =
    URLEncoder.encode(this, StandardCharsets.UTF_8.name()).replace("+", "%20")

private fun String.urlDecode(): String? =
    runCatching { URLDecoder.decode(this, StandardCharsets.UTF_8.name()) }.getOrNull()
