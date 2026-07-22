package com.kit.wallet.data.notifications

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

/** Validated navigation data from the backend's `call.ringing` push contract. */
data class IncomingCallPayload(
    val callId: String,
    val callerName: String,
    val video: Boolean,
    val ringExpiresAt: String? = null,
    /** True when the notification's Answer action asked the app to accept immediately. */
    val acceptRequested: Boolean = false,
) {
    fun deepLinkUri(accept: Boolean = false): String = buildString {
        append("kitwallet://call/incoming?call_id=")
        append(callId.urlEncode())
        if (accept) append("&accept=1")
    }

    companion object {
        private const val RINGING_TYPE = "call.ringing"
        private const val DEFAULT_CALLER = "Kit Pay contact"
        private const val MAX_CALLER_NAME_LENGTH = 100

        fun fromData(data: Map<String, String>): IncomingCallPayload? {
            if (data["type"] != RINGING_TYPE) return null

            return create(
                callId = data["call_id"],
                callerName = data["initiator_name"],
                video = data["call_type"].equals("video", ignoreCase = true) ||
                    data["video"]?.toBooleanStrictOrNull() == true,
                ringExpiresAt = data["ring_expires_at"],
            )
        }

        fun fromDeepLink(raw: String): IncomingCallPayload? {
            val uri = runCatching { URI(raw) }.getOrNull() ?: return null
            if (uri.scheme != "kitwallet" || uri.host != "call" || uri.path != "/incoming") {
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

            return create(
                callId = query["call_id"],
                // Explicit activity intents are untrusted. The call endpoint supplies display data.
                callerName = null,
                video = false,
            )?.copy(acceptRequested = query["accept"] == "1")
        }

        fun callId(data: Map<String, String>): String? = normalizedCallId(data["call_id"])

        private fun create(
            callId: String?,
            callerName: String?,
            video: Boolean,
            ringExpiresAt: String? = null,
        ): IncomingCallPayload? {
            val normalizedCallId = normalizedCallId(callId) ?: return null
            val safeCaller = callerName.orEmpty()
                .filterNot(Char::isISOControl)
                .trim()
                .take(MAX_CALLER_NAME_LENGTH)
                .ifBlank { DEFAULT_CALLER }
            return IncomingCallPayload(
                callId = normalizedCallId,
                callerName = safeCaller,
                video = video,
                ringExpiresAt = ringExpiresAt?.trim()?.takeIf(String::isNotEmpty),
            )
        }

        private fun normalizedCallId(raw: String?): String? {
            val trimmed = raw?.trim()?.takeIf(String::isNotEmpty) ?: return null
            return runCatching { UUID.fromString(trimmed).toString() }.getOrNull()
        }
    }
}

private fun String.urlEncode(): String =
    URLEncoder.encode(this, StandardCharsets.UTF_8.name()).replace("+", "%20")

private fun String.urlDecode(): String? =
    runCatching { URLDecoder.decode(this, StandardCharsets.UTF_8.name()) }.getOrNull()
