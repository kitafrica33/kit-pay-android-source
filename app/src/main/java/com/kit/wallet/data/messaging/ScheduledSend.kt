package com.kit.wallet.data.messaging

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** What a scheduled item will do when its time arrives. */
internal enum class ScheduledSendKind(val wire: String) {
    /** A composed message, sent through the ordinary encrypted send path. */
    TEXT("text"),

    /** A payment request: created against the payments API first, then shared into the chat. */
    PAYMENT_REQUEST("request"),
    ;

    companion object {
        fun fromWire(value: String): ScheduledSendKind? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * Where a scheduled item is in its short life.
 *
 * There is deliberately no "sent" state. The instant a send is durably committed to the encrypted
 * outbox, the outbox owns delivery, retry and de-duplication, and the scheduled record is deleted.
 * Anything still in this namespace has definitively not been handed over yet — with the single
 * exception of [UNCONFIRMED], which exists precisely because the device stopped between those two
 * facts and cannot honestly claim either.
 */
internal enum class ScheduledSendState(val wire: String) {
    /** Waiting for its time, or for the next dispatch after an attempt that never committed. */
    WAITING("waiting"),

    /** Claimed by a dispatch that is sending it right now. At most one claim can exist. */
    SENDING("sending"),

    /**
     * A dispatch was interrupted after it claimed this item and before it could confirm the
     * outcome. Resending automatically could duplicate the message, so it waits for a person.
     */
    UNCONFIRMED("unconfirmed"),
    ;

    companion object {
        fun fromWire(value: String): ScheduledSendState? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * One item the user asked Kit Pay to send later, as it is stored at rest.
 *
 * Scheduled items live in the same hardware-encrypted messaging state store as the libsignal
 * records, so a message that has not been sent yet is protected exactly like one that has, and is
 * cryptographically erased with the rest of the local messaging state on sign-out.
 *
 * Nothing here is encrypted for a recipient yet, and that is the point: encryption binds a message
 * to a roster, and a roster can change between scheduling and sending. A scheduled item is an
 * intent. It becomes ciphertext once, at the moment it is actually sent, through the same path a
 * message typed at that moment would take.
 */
internal data class ScheduledSend(
    /** Stable identity of the intent; also the payment-request idempotency key for [PAYMENT_REQUEST]. */
    val id: String,
    val conversationId: String,
    val kind: ScheduledSendKind,
    val scheduledAtEpochMillis: Long,
    val createdAtEpochMillis: Long,
    val state: ScheduledSendState = ScheduledSendState.WAITING,
    /** Message text for [ScheduledSendKind.TEXT]; empty for a payment request. */
    val text: String = "",
    /** Minor units for [ScheduledSendKind.PAYMENT_REQUEST]; zero for a text message. */
    val amountMinor: Long = 0,
    /** Optional note carried on a scheduled payment request. */
    val note: String? = null,
    /** How many dispatches have tried and returned without durably committing anything. */
    val attempts: Int = 0,
    /** When the current [ScheduledSendState.SENDING] claim was taken; zero when unclaimed. */
    val claimedAtEpochMillis: Long = 0,
    /** When the last fruitless attempt was made; zero before the first one. */
    val lastAttemptAtEpochMillis: Long = 0,
) {
    init {
        require(CANONICAL_UUID.matches(id)) { "Invalid scheduled send ID" }
        require(CONVERSATION_ID.matches(conversationId)) { "Invalid scheduled send conversation" }
        require(scheduledAtEpochMillis > 0) { "A scheduled send needs a send time" }
        require(createdAtEpochMillis > 0) { "A scheduled send needs a creation time" }
        require(attempts >= 0) { "A scheduled send cannot have negative attempts" }
        require(claimedAtEpochMillis >= 0) { "Invalid scheduled send claim time" }
        require(lastAttemptAtEpochMillis >= 0) { "Invalid scheduled send attempt time" }
        when (kind) {
            ScheduledSendKind.TEXT -> {
                require(text.isNotBlank() && text.length <= MAX_TEXT_LENGTH) {
                    "A scheduled message needs text of up to $MAX_TEXT_LENGTH characters"
                }
                require(amountMinor == 0L && note == null) {
                    "A scheduled message cannot carry payment fields"
                }
            }
            ScheduledSendKind.PAYMENT_REQUEST -> {
                require(text.isEmpty()) { "A scheduled payment request cannot carry message text" }
                require(amountMinor in 1..MAX_AMOUNT_MINOR) {
                    "A scheduled payment request needs a positive amount"
                }
                require(note == null || (note.isNotBlank() && note.length <= MAX_NOTE_LENGTH)) {
                    "A scheduled payment request note is too long"
                }
            }
        }
    }

    /** True once this item's time has come and it is the dispatcher's to pick up. */
    fun isDue(nowEpochMillis: Long): Boolean =
        state == ScheduledSendState.WAITING && scheduledAtEpochMillis <= nowEpochMillis

    /**
     * True when a [ScheduledSendState.SENDING] claim is old enough that no live dispatch holds it.
     *
     * A claim this old means the process that took it died mid-send. It is never re-sent on that
     * basis alone — see [ScheduledSendState.UNCONFIRMED].
     */
    fun claimIsStale(nowEpochMillis: Long): Boolean =
        state == ScheduledSendState.SENDING &&
            nowEpochMillis - claimedAtEpochMillis >= STALE_CLAIM_MILLIS

    /**
     * The earliest a dispatch should try this item again after [attempts] fruitless tries.
     *
     * A failed attempt committed nothing, so re-trying is always safe; backing off just keeps a
     * conversation whose peer is unreachable from spinning the radio every time anything wakes.
     * The backoff runs from the last attempt rather than from the send time, so it keeps moving
     * forward instead of settling on a ceiling that is already in the past.
     */
    fun nextEligibleAtEpochMillis(): Long = when (state) {
        ScheduledSendState.WAITING ->
            maxOf(scheduledAtEpochMillis, lastAttemptAtEpochMillis + backoffMillis(attempts))
        ScheduledSendState.SENDING -> claimedAtEpochMillis + STALE_CLAIM_MILLIS
        ScheduledSendState.UNCONFIRMED -> Long.MAX_VALUE
    }

    /**
     * Returns this item back in the queue after an attempt that committed nothing.
     *
     * One shape for every fruitless outcome — a send that threw, and a dispatch that could not
     * start because secure messaging was not ready — so both back off the same way.
     */
    fun attempted(nowEpochMillis: Long): ScheduledSend = copy(
        state = ScheduledSendState.WAITING,
        attempts = attempts + 1,
        claimedAtEpochMillis = 0,
        lastAttemptAtEpochMillis = nowEpochMillis,
    )

    /** Fixed field order keeps encoding canonical, so a round trip is a real integrity check. */
    fun encode(): String = buildString {
        append(PREFIX)
        append("v=1")
        append("&id=").append(id.urlEncode())
        append("&cid=").append(conversationId.urlEncode())
        append("&k=").append(kind.wire)
        append("&st=").append(state.wire)
        append("&at=").append(scheduledAtEpochMillis)
        append("&cr=").append(createdAtEpochMillis)
        append("&n=").append(attempts)
        append("&clm=").append(claimedAtEpochMillis)
        append("&la=").append(lastAttemptAtEpochMillis)
        if (kind == ScheduledSendKind.TEXT) {
            append("&tx=").append(text.urlEncode())
        } else {
            append("&amt=").append(amountMinor)
            note?.let { append("&note=").append(it.urlEncode()) }
        }
    }

    companion object {
        const val PREFIX = "KITSCHED1:"

        /** A scheduled send is at least this far out, so "later" is never indistinguishable from now. */
        const val MIN_LEAD_MILLIS = 60_000L

        /** And no further than a year, which is well past any use a chat composer has for it. */
        const val MAX_HORIZON_MILLIS = 365L * 24 * 60 * 60 * 1_000

        /** How long a claim may sit before its owner is presumed dead. */
        const val STALE_CLAIM_MILLIS = 2L * 60 * 1_000

        const val MAX_TEXT_LENGTH = 4_096
        const val MAX_NOTE_LENGTH = KitPaymentMessage.MAX_NOTE_LENGTH
        private const val MAX_AMOUNT_MINOR = 1_000_000_000_000L
        private const val MAX_RECORD_LENGTH = 8_192
        private val CANONICAL_UUID = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        )
        private val CONVERSATION_ID = Regex("^[A-Za-z0-9._:@-]{1,64}$")

        /** Roughly a minute, then four, then a quarter of an hour, then hourly. */
        fun backoffMillis(attempts: Int): Long = when {
            attempts <= 0 -> 0L
            attempts == 1 -> 60_000L
            attempts == 2 -> 4L * 60_000L
            attempts == 3 -> 15L * 60_000L
            else -> 60L * 60_000L
        }

        /**
         * Whether [scheduledAtEpochMillis] is a time this app will accept, or the reason it is not.
         *
         * Returns null when the time is fine. The copy is the user-facing sentence, so the picker
         * and the dispatcher cannot disagree about what "later" means.
         */
        fun schedulingError(scheduledAtEpochMillis: Long, nowEpochMillis: Long): String? = when {
            scheduledAtEpochMillis - nowEpochMillis < MIN_LEAD_MILLIS ->
                "Pick a time at least a minute from now."
            scheduledAtEpochMillis - nowEpochMillis > MAX_HORIZON_MILLIS ->
                "Pick a time within the next year."
            else -> null
        }

        /** Strict parse; returns null for anything that is not a canonical v1 scheduled send. */
        fun parse(text: String): ScheduledSend? {
            if (!text.startsWith(PREFIX) || text.length > MAX_RECORD_LENGTH) return null
            val fields = mutableMapOf<String, String>()
            for (pair in text.substring(PREFIX.length).split('&')) {
                val separator = pair.indexOf('=')
                if (separator <= 0) return null
                val key = pair.substring(0, separator)
                val value = pair.substring(separator + 1).urlDecode() ?: return null
                if (fields.put(key, value) != null) return null
            }
            if (fields["v"] != "1") return null
            val parsed = runCatching {
                ScheduledSend(
                    id = fields["id"] ?: return null,
                    conversationId = fields["cid"] ?: return null,
                    kind = fields["k"]?.let(ScheduledSendKind::fromWire) ?: return null,
                    state = fields["st"]?.let(ScheduledSendState::fromWire) ?: return null,
                    scheduledAtEpochMillis = fields["at"]?.toLongOrNull() ?: return null,
                    createdAtEpochMillis = fields["cr"]?.toLongOrNull() ?: return null,
                    attempts = fields["n"]?.toIntOrNull() ?: return null,
                    claimedAtEpochMillis = fields["clm"]?.toLongOrNull() ?: return null,
                    lastAttemptAtEpochMillis = fields["la"]?.toLongOrNull() ?: return null,
                    text = fields["tx"].orEmpty(),
                    amountMinor = fields["amt"]?.toLongOrNull() ?: 0L,
                    note = fields["note"],
                )
            }.getOrNull() ?: return null
            // One canonical representation only. Unknown, reordered or alternately escaped fields
            // are rejected rather than silently dropped, so a record written by a future build is
            // never half-understood by this one.
            return parsed.takeIf { it.encode() == text }
        }

        private fun String.urlEncode(): String =
            URLEncoder.encode(this, StandardCharsets.UTF_8.name()).replace("+", "%20")

        private fun String.urlDecode(): String? =
            runCatching { URLDecoder.decode(this, StandardCharsets.UTF_8.name()) }.getOrNull()
    }
}
