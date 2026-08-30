package com.kit.wallet.data.messaging

import java.net.URLEncoder
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant

internal enum class KitScheduledPaymentAction(val wire: String) {
    COMPLETED("completed"), FAILED("failed"), CANCELLED("cancelled");

    companion object {
        fun fromEventType(type: String): KitScheduledPaymentAction? = entries.firstOrNull {
            type == "scheduled_payment.${it.wire}"
        }
    }
}

/** Local-only projection of an authenticated, exact-hydrated scheduled-payment sync event. */
internal data class KitScheduledPaymentMessage(
    val action: KitScheduledPaymentAction,
    val scheduledPaymentId: String,
    val amountMinor: Long,
    val currencyCode: String,
    val currencyScale: Int,
    val scheduledAtEpochSeconds: Long,
    val walletTransactionId: String?,
    val note: String?,
    val reason: String?,
) {
    fun encode(): String = buildString {
        append(PREFIX).append("v=1&a=").append(action.wire)
        append("&id=").append(scheduledPaymentId)
        append("&amt=").append(amountMinor)
        append("&cur=").append(currencyCode)
        append("&sc=").append(currencyScale)
        append("&at=").append(scheduledAtEpochSeconds)
        walletTransactionId?.let { append("&tx=").append(it) }
        note?.let { append("&note=").append(it.urlEncode()) }
        reason?.let { append("&rsn=").append(it.urlEncode()) }
    }

    companion object {
        const val PREFIX = "KITSCHPAY1:"
        private val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

        fun create(
            action: KitScheduledPaymentAction,
            scheduledPaymentId: String,
            amountMinor: Long,
            currencyCode: String,
            currencyScale: Int,
            scheduledAt: Instant,
            walletTransactionId: String?,
            note: String?,
            reason: String?,
        ): KitScheduledPaymentMessage? {
            val id = scheduledPaymentId.lowercase()
            val tx = walletTransactionId?.lowercase()
            val cleanNote = note?.trim()?.takeIf(String::isNotEmpty)
            val cleanReason = reason?.trim()?.takeIf(String::isNotEmpty)
            if (!UUID.matches(id) || amountMinor !in 1..1_000_000_000_000L ||
                !currencyCode.matches(Regex("^[A-Z]{3}$")) || currencyScale !in 0..6 ||
                scheduledAt.epochSecond <= 0 || (cleanNote?.length ?: 0) > 280 ||
                (cleanReason?.length ?: 0) > 280 || cleanNote?.any(Char::isISOControl) == true ||
                cleanReason?.any(Char::isISOControl) == true || tx?.let { !UUID.matches(it) } == true ||
                (action == KitScheduledPaymentAction.COMPLETED) != (tx != null) ||
                (action == KitScheduledPaymentAction.FAILED && cleanReason == null)
            ) return null
            return KitScheduledPaymentMessage(
                action, id, amountMinor, currencyCode, currencyScale, scheduledAt.epochSecond,
                tx, cleanNote, cleanReason,
            ).takeIf { it.encode().length <= 2_048 }
        }

        fun parse(text: String): KitScheduledPaymentMessage? {
            if (!text.startsWith(PREFIX) || text.length > 2_048) return null
            val fields = text.substring(PREFIX.length).parseFields() ?: return null
            if (fields["v"] != "1") return null
            val action = KitScheduledPaymentAction.entries.firstOrNull { it.wire == fields["a"] }
                ?: return null
            val value = create(
                action = action,
                scheduledPaymentId = fields["id"] ?: return null,
                amountMinor = fields["amt"]?.toLongOrNull() ?: return null,
                currencyCode = fields["cur"] ?: return null,
                currencyScale = fields["sc"]?.toIntOrNull() ?: return null,
                scheduledAt = fields["at"]?.toLongOrNull()?.let(Instant::ofEpochSecond) ?: return null,
                walletTransactionId = fields["tx"],
                note = fields["note"],
                reason = fields["rsn"],
            ) ?: return null
            return value.takeIf { it.encode() == text }
        }
    }

    fun deterministicMessageId(): String = deterministicUuid(
        "kit-scheduled-payment-event:$scheduledPaymentId:${action.wire}",
    )
}

internal enum class KitScheduledGroupPaymentOutcomeAction(val wire: String) {
    FAILED("failed"), CANCELLED("cancelled");
}

/** Content-minimal creator-only outcome for a schedule that produced no group payment. */
internal data class KitScheduledGroupPaymentOutcomeMessage(
    val action: KitScheduledGroupPaymentOutcomeAction,
    val scheduledGroupPaymentId: String,
    val scheduledAtEpochSeconds: Long,
) {
    fun encode(): String = "${PREFIX}v=1&a=${action.wire}&id=$scheduledGroupPaymentId&at=$scheduledAtEpochSeconds"

    companion object {
        const val PREFIX = "KITSGRP1:"
        private val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

        fun create(
            action: KitScheduledGroupPaymentOutcomeAction,
            scheduledGroupPaymentId: String,
            scheduledAt: Instant,
        ): KitScheduledGroupPaymentOutcomeMessage? {
            val id = scheduledGroupPaymentId.lowercase()
            if (!UUID.matches(id) || scheduledAt.epochSecond < 0) return null
            return KitScheduledGroupPaymentOutcomeMessage(action, id, scheduledAt.epochSecond)
        }

        fun parse(text: String): KitScheduledGroupPaymentOutcomeMessage? {
            if (!text.startsWith(PREFIX) || text.length > 180) return null
            val fields = text.substring(PREFIX.length).parseFields(decode = false) ?: return null
            if (fields["v"] != "1") return null
            val action = KitScheduledGroupPaymentOutcomeAction.entries.firstOrNull {
                it.wire == fields["a"]
            } ?: return null
            val value = create(
                action = action,
                scheduledGroupPaymentId = fields["id"] ?: return null,
                scheduledAt = fields["at"]?.toLongOrNull()?.let(Instant::ofEpochSecond) ?: return null,
            ) ?: return null
            return value.takeIf { it.encode() == text }
        }
    }

    fun deterministicMessageId(): String = deterministicUuid(
        "scheduled-group-payment-outcome|$scheduledGroupPaymentId|${action.wire}",
    )
}

private fun String.urlEncode(): String =
    URLEncoder.encode(this, StandardCharsets.UTF_8.name()).replace("+", "%20")

private fun String.parseFields(decode: Boolean = true): Map<String, String>? {
    val fields = linkedMapOf<String, String>()
    for (pair in split('&')) {
        val separator = pair.indexOf('=')
        if (separator <= 0) return null
        val key = pair.substring(0, separator)
        val encoded = pair.substring(separator + 1)
        val value = if (decode) runCatching {
            URLDecoder.decode(encoded.replace("+", "%2B"), StandardCharsets.UTF_8.name())
        }.getOrNull() ?: return null else encoded
        if (fields.put(key, value) != null) return null
    }
    return fields
}

internal fun deterministicUuid(namespace: String): String {
    val bytes = MessageDigest.getInstance("SHA-256")
        .digest(namespace.toByteArray(StandardCharsets.UTF_8))
        .take(16)
        .toMutableList()
    bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
    val hex = bytes.joinToString("") { "%02x".format(it) }
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
        "${hex.substring(16, 20)}-${hex.substring(20, 32)}"
}
