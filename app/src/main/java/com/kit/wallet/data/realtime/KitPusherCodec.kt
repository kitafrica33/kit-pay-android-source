package com.kit.wallet.data.realtime

import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import okio.Buffer

/**
 * The Pusher protocol-7 wire format, both directions.
 *
 * Pure Kotlin with no Android and no Moshi-reflection dependency, so every rule
 * below is pinned by an ordinary JVM unit test rather than by an instrumented one
 * that CI never compiles.
 *
 * Two properties are load-bearing rather than incidental:
 *
 * 1. **`data` is asymmetric.** Protocol frames (`pusher:*`, `pusher_internal:*`)
 *    carry `data` as a JSON-encoded *string*; application frames carry an object.
 *    Reverb relays whatever the signed HTTP events API was given, and the Pusher
 *    PHP client encodes payloads to a string on the way in, so a `kit.*` frame can
 *    legitimately arrive either way. [decode] accepts both shapes everywhere and
 *    the tests pin both.
 *
 * 2. **No `client-*` frame is ever parsed or ever emitted.** Reverb applies no
 *    attribution and no authorization to client events, so anyone holding the
 *    public app key can inject one into any channel whose name they know. The
 *    event name is therefore resolved first, on its own, and a `client-` prefix
 *    aborts before the `data` member is looked at. On the outbound side there is
 *    simply no function that takes an event name, so no code path can emit one.
 */
internal object KitPusherCodec {
    /**
     * Frames larger than this are dropped unread. Our own largest frame is a
     * presence roster, which is ~45 bytes per member; nothing legitimate on these
     * channels approaches the cap, and it bounds the work a hostile server can
     * make the read thread do.
     */
    const val MAX_FRAME_BYTES: Int = 64 * 1024

    private const val CLIENT_EVENT_PREFIX = "client-"

    private const val EVENT_ESTABLISHED = "pusher:connection_established"
    private const val EVENT_PING = "pusher:ping"
    private const val EVENT_PONG = "pusher:pong"
    private const val EVENT_ERROR = "pusher:error"
    private const val EVENT_SUBSCRIPTION_ERROR = "pusher:subscription_error"
    private const val EVENT_SUBSCRIPTION_SUCCEEDED = "pusher_internal:subscription_succeeded"
    private const val EVENT_MEMBER_ADDED = "pusher_internal:member_added"
    private const val EVENT_MEMBER_REMOVED = "pusher_internal:member_removed"

    const val EVENT_SYNC_NUDGE: String = "kit.sync.nudge"
    const val EVENT_TYPING: String = "kit.typing"
    const val EVENT_TYPING_STOP: String = "kit.typing.stop"
    const val EVENT_CALL_ANSWERED: String = "kit.call.answered"

    /** The frame schema version this build speaks. Anything else is dropped. */
    private const val FRAME_VERSION = 1

    /** The only call state `kit.call.answered` may announce. */
    private const val CALL_STATE_ACTIVE = "active"

    private val UNDERSTOOD = setOf(
        EVENT_ESTABLISHED,
        EVENT_PING,
        EVENT_PONG,
        EVENT_ERROR,
        EVENT_SUBSCRIPTION_ERROR,
        EVENT_SUBSCRIPTION_SUCCEEDED,
        EVENT_MEMBER_ADDED,
        EVENT_MEMBER_REMOVED,
        EVENT_SYNC_NUDGE,
        EVENT_TYPING,
        EVENT_TYPING_STOP,
        EVENT_CALL_ANSWERED,
    )

    /**
     * Maps one inbound text frame onto [KitRealtimeFrame], or `null` if it is to be
     * dropped. Dropping is always silent and always without side effect — there is
     * no error channel here on purpose, because every reason to drop is a reason to
     * carry on with the connection unchanged.
     */
    fun decode(text: String): KitRealtimeFrame? {
        if (text.length > MAX_FRAME_BYTES) return null

        // Resolved on its own pass so that a `client-*` frame — which Reverb will
        // relay from any unauthenticated connection — is discarded before its body
        // is read at all.
        val event = readEventName(text) ?: return null
        if (event.startsWith(CLIENT_EVENT_PREFIX)) return null
        if (event !in UNDERSTOOD) return null

        val envelope = readEnvelope(text) ?: return null
        val data = envelope.data
        val channel = envelope.channel

        return when (event) {
            EVENT_ESTABLISHED -> {
                val socketId = data.string("socket_id")?.takeIf { it.isNotBlank() } ?: return null
                KitRealtimeFrame.Established(
                    socketId = socketId,
                    activityTimeoutSeconds = data.int("activity_timeout")
                        ?.coerceIn(MIN_ACTIVITY_TIMEOUT_SECONDS, MAX_ACTIVITY_TIMEOUT_SECONDS)
                        ?: DEFAULT_ACTIVITY_TIMEOUT_SECONDS,
                )
            }

            EVENT_PING -> KitRealtimeFrame.Ping

            EVENT_PONG -> KitRealtimeFrame.Pong

            EVENT_ERROR -> KitRealtimeFrame.Failure(
                code = data.int("code"),
                message = data.string("message"),
            )

            EVENT_SUBSCRIPTION_ERROR -> KitRealtimeFrame.SubscriptionFailed(
                channel = channel ?: return null,
                status = data.int("status"),
            )

            EVENT_SUBSCRIPTION_SUCCEEDED -> KitRealtimeFrame.SubscriptionSucceeded(
                channel = channel ?: return null,
                members = presenceMembers(data),
            )

            EVENT_MEMBER_ADDED -> KitRealtimeFrame.MemberAdded(
                channel = channel ?: return null,
                user = data.string("user_id")?.takeIf { it.isNotBlank() } ?: return null,
            )

            EVENT_MEMBER_REMOVED -> KitRealtimeFrame.MemberRemoved(
                channel = channel ?: return null,
                user = data.string("user_id")?.takeIf { it.isNotBlank() } ?: return null,
            )

            // The nudge is deliberately content-free. Requiring the exact `{v:1}` shape keeps
            // conversation identifiers, sender metadata and future advisory fields off this
            // account-scoped channel instead of teaching clients to tolerate their disclosure.
            EVENT_SYNC_NUDGE -> if (
                data.keys == setOf("v") && data.int("v") == FRAME_VERSION
            ) {
                KitRealtimeFrame.SyncNudge
            } else {
                null
            }

            EVENT_TYPING, EVENT_TYPING_STOP -> {
                if (data.int("v") != FRAME_VERSION) return null
                KitRealtimeFrame.Typing(
                    channel = channel ?: return null,
                    user = data.string("user")?.takeIf { it.isNotBlank() } ?: return null,
                    active = event == EVENT_TYPING,
                )
            }

            // Every member is required and every member is checked. This frame moves a
            // call's state and sets the anchor its timer counts from, so a partial or
            // mislabelled one is dropped rather than half-applied: the `call.answered`
            // push carries the same answer and will arrive regardless.
            EVENT_CALL_ANSWERED -> {
                if (data.int("v") != FRAME_VERSION) return null
                if (data.string("state") != CALL_STATE_ACTIVE) return null
                KitRealtimeFrame.CallAnswered(
                    channel = channel ?: return null,
                    callId = data.string("call_id")?.takeIf { it.isNotBlank() } ?: return null,
                    answeredAt = data.string("answered_at")?.takeIf { it.isNotBlank() } ?: return null,
                    answeredBy = data.string("answered_by")?.takeIf { it.isNotBlank() } ?: return null,
                    serverTime = data.string("server_time")?.takeIf { it.isNotBlank() } ?: return null,
                )
            }

            else -> null
        }
    }

    /** `{"event":"pusher:pong","data":{}}` */
    fun encodePong(): String = protocolFrame(EVENT_PONG)

    /** `{"event":"pusher:ping","data":{}}` — our own keepalive, not a reply. */
    fun encodePing(): String = protocolFrame(EVENT_PING)

    /**
     * `pusher:subscribe`. [channelData] is present for presence channels only and,
     * like every signed opaque blob, is passed through untouched.
     */
    fun encodeSubscribe(channel: String, auth: String, channelData: String? = null): String =
        frame("pusher:subscribe") { writer ->
            writer.name("auth").value(auth)
            writer.name("channel").value(channel)
            if (channelData != null) writer.name("channel_data").value(channelData)
        }

    /**
     * `pusher:unsubscribe`. Sent before leaving a conversation so the peer's dot
     * clears in under a second instead of waiting out the server's activity timeout.
     */
    fun encodeUnsubscribe(channel: String): String = frame("pusher:unsubscribe") { writer ->
        writer.name("channel").value(channel)
    }

    private const val DEFAULT_ACTIVITY_TIMEOUT_SECONDS = 30
    private const val MIN_ACTIVITY_TIMEOUT_SECONDS = 10
    private const val MAX_ACTIVITY_TIMEOUT_SECONDS = 300

    private class Envelope(val channel: String?, val data: Map<String, Any?>)

    /** Reads `event` and nothing else, skipping every other member unparsed. */
    private fun readEventName(text: String): String? = runCatching {
        JsonReader.of(Buffer().writeUtf8(text)).use { reader ->
            if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) return@use null
            reader.beginObject()
            var event: String? = null
            while (reader.hasNext()) {
                if (reader.nextName() == "event" && reader.peek() == JsonReader.Token.STRING) {
                    event = reader.nextString()
                } else {
                    reader.skipValue()
                }
            }
            reader.endObject()
            event
        }
    }.getOrNull()

    private fun readEnvelope(text: String): Envelope? = runCatching {
        JsonReader.of(Buffer().writeUtf8(text)).use { reader ->
            if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) return@use null
            reader.beginObject()
            var channel: String? = null
            var data: Map<String, Any?> = emptyMap()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "channel" -> channel = reader.nextStringOrNull()
                    "data" -> data = reader.readData()
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            Envelope(channel?.takeIf { it.isNotBlank() }, data)
        }
    }.getOrNull()

    /**
     * The asymmetry from the class docblock, in one place: a protocol frame's
     * `data` is a JSON-encoded string that has to be parsed a second time, while an
     * application frame's is already an object.
     */
    private fun JsonReader.readData(): Map<String, Any?> = when (peek()) {
        JsonReader.Token.STRING -> {
            val encoded = nextString()
            runCatching {
                JsonReader.of(Buffer().writeUtf8(encoded)).use { nested ->
                    if (nested.peek() == JsonReader.Token.BEGIN_OBJECT) nested.readObject() else null
                }
            }.getOrNull() ?: emptyMap()
        }

        JsonReader.Token.BEGIN_OBJECT -> readObject()

        else -> {
            skipValue()
            emptyMap()
        }
    }

    private fun JsonReader.readObject(): Map<String, Any?> {
        val values = mutableMapOf<String, Any?>()
        beginObject()
        while (hasNext()) {
            values[nextName()] = readValue()
        }
        endObject()
        return values
    }

    private fun JsonReader.readValue(): Any? = when (peek()) {
        JsonReader.Token.BEGIN_OBJECT -> readObject()
        JsonReader.Token.BEGIN_ARRAY -> {
            val items = mutableListOf<Any?>()
            beginArray()
            while (hasNext()) items += readValue()
            endArray()
            items
        }

        JsonReader.Token.STRING -> nextString()
        JsonReader.Token.NUMBER -> nextDouble()
        JsonReader.Token.BOOLEAN -> nextBoolean()
        JsonReader.Token.NULL -> nextNull<Any?>()
        else -> {
            skipValue()
            null
        }
    }

    private fun JsonReader.nextStringOrNull(): String? {
        if (peek() == JsonReader.Token.STRING) return nextString()
        skipValue()
        return null
    }

    /**
     * Members come from `presence.ids`, with `presence.hash`'s keys as the fallback
     * for a server that sends only the hash. `count` is ignored: it is derivable and
     * a mismatch would only ever be a reason to distrust the roster we can see.
     */
    private fun presenceMembers(data: Map<String, Any?>): Set<String> {
        @Suppress("UNCHECKED_CAST")
        val presence = data["presence"] as? Map<String, Any?> ?: return emptySet()

        val ids = (presence["ids"] as? List<*>)
            ?.mapNotNull { (it as? String)?.takeIf(String::isNotBlank) }
            ?.toSet()
            .orEmpty()
        if (ids.isNotEmpty()) return ids

        @Suppress("UNCHECKED_CAST")
        val hash = presence["hash"] as? Map<String, Any?> ?: return emptySet()
        return hash.keys.filter(String::isNotBlank).toSet()
    }

    private fun Map<String, Any?>.string(name: String): String? = this[name] as? String

    private fun Map<String, Any?>.int(name: String): Int? = when (val value = this[name]) {
        is Double -> value.toInt()
        is Int -> value
        is Long -> value.toInt()
        // Reverb has been seen to send `activity_timeout` as a string on some paths.
        is String -> value.toIntOrNull()
        else -> null
    }

    private fun protocolFrame(event: String): String = frame(event) { }

    private fun frame(event: String, body: (JsonWriter) -> Unit): String {
        val buffer = Buffer()
        JsonWriter.of(buffer).use { writer ->
            writer.beginObject()
            writer.name("event").value(event)
            writer.name("data").beginObject()
            body(writer)
            writer.endObject()
            writer.endObject()
        }
        return buffer.readUtf8()
    }
}
