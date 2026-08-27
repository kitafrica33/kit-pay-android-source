package com.kit.wallet.data.realtime

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Url

/**
 * The socket's entire HTTP surface: one channel-signing call and one typing call.
 *
 * Deliberately a separate interface from the secure-messaging wire API — nothing
 * here carries ciphertext, a cursor or an envelope, and the isolation test that
 * pins which files may name that interface is a gate worth keeping narrow. Naming
 * it, even in a comment, is what widens it: the gate matches on the text of the
 * file, so this paragraph spells it out rather than writing the symbol.
 *
 * Provided from the *authenticated* OkHttp client, so `SessionHeaderInterceptor`
 * and `SessionAuthenticator`'s refresh apply unchanged. There is no token handling
 * in this package at all, which is the point.
 *
 * The signing endpoint is addressed by [Url] because its path arrives in the
 * `/capabilities` advertisement rather than being compiled in: the server owns
 * where it signs, and a version-prefix change must not need an app release.
 */
internal interface KitRealtimeAuthApi {
    /**
     * Signs one channel subscription. The response is the socket protocol's own
     * shape, not an API envelope — it is fed straight back into `pusher:subscribe`.
     */
    @POST
    suspend fun authorizeChannel(
        @Url path: String,
        @Body body: ChannelAuthRequest,
    ): Response<ChannelAuthDto>

    /**
     * Announces that we started or stopped typing.
     *
     * Fire-and-forget by contract: a non-2xx is dropped silently and never retried.
     * The peer's bubble expires on its own in six seconds, so a failed signal costs
     * nothing, while a retry would put a queue on the path of a keystroke.
     *
     * `X-Socket-Id` is what lets the server exclude our own connection, so a device
     * with two sockets does not watch itself type.
     */
    @POST("api/kit-wallet/v1/messaging/conversations/{conversation}/typing")
    suspend fun typing(
        @Path("conversation") conversationId: String,
        @Header("X-Socket-Id") socketId: String?,
        @Body body: TypingRequest,
    ): Response<Unit>
}

@JsonClass(generateAdapter = true)
internal data class ChannelAuthRequest(
    @Json(name = "socket_id") val socketId: String,
    @Json(name = "channel_name") val channelName: String,
)

@JsonClass(generateAdapter = true)
internal data class TypingRequest(
    val state: String,
) {
    companion object {
        val Start = TypingRequest("start")
        val Stop = TypingRequest("stop")
    }
}

/**
 * `channel_data` is the server's signed JSON *string* and is forwarded to
 * `pusher:subscribe` byte for byte: it is covered by [auth], so re-encoding it
 * would invalidate the signature.
 */
@JsonClass(generateAdapter = true)
internal data class ChannelAuthDto(
    val auth: String? = null,
    @Json(name = "channel_data") val channelData: String? = null,
)
