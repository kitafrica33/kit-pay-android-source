package com.kit.wallet.data.realtime

import com.kit.wallet.data.remote.RealtimeProtocolDto

/**
 * A `protocols.realtime` block that has been checked all the way through.
 *
 * The negotiation rule is all-or-nothing on purpose: the client opens a socket
 * **iff** the block is present, `v` is one we speak, and every member parses. A
 * half-usable advertisement — a host with no path, a template with no placeholder —
 * would have the client dial something that cannot work and then retry it on a
 * ladder, which is strictly worse than never dialling at all. Anything that fails
 * to validate here returns `null`, and `null` means today's polling behaviour.
 *
 * Pure Kotlin, so the rule is pinned by a unit test rather than by a device.
 */
internal data class KitRealtimeConfig(
    val socketUrl: String,
    val authPath: String,
    val activityTimeoutSeconds: Int,
    val maxConnectionSeconds: Int,
    val userChannelTemplate: String,
    val conversationChannelTemplate: String,
    val presenceEnabled: Boolean,
    val typingEnabled: Boolean,
    val callAnswerEnabled: Boolean,
) {
    fun userChannel(userPublicId: String): String =
        userChannelTemplate.replace(USER_PLACEHOLDER, userPublicId)

    fun conversationChannel(conversationId: String): String =
        conversationChannelTemplate.replace(CONVERSATION_PLACEHOLDER, conversationId)

    /** The inverse of [conversationChannel], for attributing an inbound frame. */
    fun conversationIdOf(channel: String): String? {
        val prefix = conversationChannelTemplate.substringBefore(CONVERSATION_PLACEHOLDER)
        val suffix = conversationChannelTemplate.substringAfter(CONVERSATION_PLACEHOLDER)
        if (!channel.startsWith(prefix) || !channel.endsWith(suffix)) return null
        return channel.removePrefix(prefix).removeSuffix(suffix).takeIf { it.isNotBlank() }
    }

    companion object {
        /** The only frame and protocol versions this build speaks. */
        const val SUPPORTED_BLOCK_VERSION: Int = 1
        const val SUPPORTED_PUSHER_PROTOCOL: Int = 7

        private const val USER_PLACEHOLDER = "{user}"
        private const val CONVERSATION_PLACEHOLDER = "{conversation}"

        private val SUPPORTED_SCHEMES = setOf("ws", "wss")

        fun from(dto: RealtimeProtocolDto?): KitRealtimeConfig? {
            val block = dto ?: return null
            if (block.v != SUPPORTED_BLOCK_VERSION) return null
            if (block.protocol != SUPPORTED_PUSHER_PROTOCOL) return null

            val scheme = block.scheme?.lowercase()?.takeIf { it in SUPPORTED_SCHEMES } ?: return null
            val host = block.host?.trim()?.takeIf { it.isNotEmpty() && !it.contains('/') } ?: return null
            val port = block.port?.takeIf { it in 1..65_535 } ?: return null

            // Taken verbatim, key segment included. The socket server resolves the
            // application from that segment and offers no alternative, so composing
            // the path here would mean duplicating a server convention that can change.
            val path = block.path?.takeIf { it.startsWith("/") && it.length > 1 } ?: return null

            // Not used to build the URL — it is already inside `path` — but its
            // presence is what proves the block was built against a configured app
            // rather than half-populated from defaults.
            if (block.key.isNullOrBlank()) return null

            val authPath = block.authPath?.takeIf { it.startsWith("/") } ?: return null
            val activityTimeout = block.activityTimeoutSeconds?.takeIf { it >= 10 } ?: return null
            val maxConnectionSeconds = block.maxConnectionSeconds?.takeIf { it >= 60 } ?: return null

            val userTemplate = block.channels?.user
                ?.takeIf { it.contains(USER_PLACEHOLDER) } ?: return null
            val conversationTemplate = block.channels.conversation
                ?.takeIf { it.contains(CONVERSATION_PLACEHOLDER) } ?: return null

            // Typing rides on the presence channel, so it can never be honoured
            // without it however the server advertised the pair.
            val presence = block.presence == true
            val typing = presence && block.typing == true

            // The answer frame rides the user channel, which is always subscribed, so
            // unlike typing it has no companion capability to depend on. It is still
            // gated on the advertisement: a server that has the rollout switched off
            // sends nothing, and a client that ignores an unadvertised frame simply
            // waits for the `call.answered` push that has always carried the answer.
            val callAnswer = block.calls == true

            return KitRealtimeConfig(
                socketUrl = buildString {
                    append(scheme).append("://").append(host)
                    if (port != defaultPortFor(scheme)) append(':').append(port)
                    append(path)
                    append("?protocol=").append(SUPPORTED_PUSHER_PROTOCOL)
                    append("&client=kit-android&version=").append(SUPPORTED_BLOCK_VERSION)
                },
                authPath = authPath,
                activityTimeoutSeconds = activityTimeout,
                maxConnectionSeconds = maxConnectionSeconds,
                userChannelTemplate = userTemplate,
                conversationChannelTemplate = conversationTemplate,
                presenceEnabled = presence,
                typingEnabled = typing,
                callAnswerEnabled = callAnswer,
            )
        }

        private fun defaultPortFor(scheme: String): Int = if (scheme == "wss") 443 else 80
    }
}
