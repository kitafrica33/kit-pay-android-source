package com.kit.wallet.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException

@JsonClass(generateAdapter = false)
data class ApiEnvelope<T>(
    // Treat an explicitly-null status as a failed/malformed response instead of letting Moshi
    // abort deserialization with "Expected a boolean but was NULL".
    val ok: Boolean? = null,
    val data: T? = null,
    val error: ApiErrorDto? = null,
    val meta: ApiMetaDto? = null,
) {
    fun requireData(): T {
        if (ok != true) {
            throw KitWalletApiException(
                code = error?.code ?: "UNKNOWN_API_ERROR",
                message = error?.message ?: "Kit Pay request failed",
                details = error?.details.orEmpty(),
            )
        }
        return requireNotNull(data) { "Successful Kit Pay response omitted data" }
    }
}

@JsonClass(generateAdapter = false)
data class ApiErrorDto(
    // A malformed/legacy error body must still reach the common fallback instead of making
    // Retrofit fail while it is trying to explain the original request failure.
    val code: String? = null,
    val message: String? = null,
    val details: Map<String, Any?>? = null,
)

@JsonClass(generateAdapter = false)
data class ApiMetaDto(
    @Json(name = "request_id") val requestId: String? = null,
    @Json(name = "api_version") val apiVersion: String? = null,
    @Json(name = "server_time") val serverTime: String? = null,
)

data class ApiCallResult<T>(
    val data: T,
    val meta: ApiMetaDto?,
)

class KitWalletApiException(
    val code: String,
    override val message: String,
    val details: Map<String, Any?> = emptyMap(),
    val statusCode: Int? = null,
    cause: Throwable? = null,
    /**
     * True when the request never reached the server (no connectivity, DNS/TLS/timeout). These are
     * transient: Kit Pay keeps showing cached content and retries automatically, so the UI must not
     * present a fatal error, and must never echo the underlying host or IP address.
     */
    val connectivity: Boolean = false,
) : RuntimeException(message, cause)

/** Stable code and address-free copy used for every transport failure, WhatsApp-style. */
const val KIT_NETWORK_UNAVAILABLE_CODE = "NETWORK_UNAVAILABLE"
const val KIT_NETWORK_UNAVAILABLE_MESSAGE =
    "No internet connection. Kit Pay will keep trying and update automatically."

/**
 * Whether a failure is a connectivity/transport problem rather than a server-reported error.
 * Screens use this to stay silent and keep showing cached data instead of surfacing an error.
 * Only [ApiCallExecutor], the actual network boundary, may issue that classification: secure-state
 * and Android-Keystore availability exceptions are also IOExceptions.
 */
fun Throwable?.isKitConnectivityError(): Boolean {
    var current = this
    while (current != null) {
        // Respect the first classified API boundary and never reinterpret its nested cause.
        if (current is KitWalletApiException) return current.connectivity
        // A local secure-state/Keystore IOException owns its failure semantics even when a deeper
        // cause came from a network attempt during recovery.
        if (current is IOException) return false
        current = current.cause
    }
    return false
}

@JsonClass(generateAdapter = false)
internal data class ApiFailureEnvelope(
    val ok: Boolean? = null,
    val error: ApiErrorDto? = null,
    val meta: ApiMetaDto? = null,
)

@Singleton
class ApiCallExecutor @Inject constructor(moshi: Moshi) {
    private val failureAdapter = moshi.adapter(ApiFailureEnvelope::class.java)

    suspend fun <T> execute(call: suspend () -> ApiEnvelope<T>): T =
        executeWithMeta(call).data

    suspend fun <T> executeWithMeta(
        call: suspend () -> ApiEnvelope<T>,
    ): ApiCallResult<T> = try {
        val envelope = call()
        ApiCallResult(
            data = envelope.requireData(),
            meta = envelope.meta,
        )
    } catch (error: HttpException) {
        val failure = runCatching {
            error.response()?.errorBody()?.string()?.let(failureAdapter::fromJson)
        }.getOrNull()
        throw KitWalletApiException(
            code = failure?.error?.code ?: "HTTP_${error.code()}",
            message = failure?.error?.message ?: error.message(),
            details = failure?.error?.details.orEmpty(),
            statusCode = error.code(),
            cause = error,
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (transport: IOException) {
        // The request never reached the server (offline, DNS, TLS, timeout). Replace the raw cause
        // — which typically embeds the host name or IP address — with one clean, address-free
        // message so no screen can ever display connection internals to the user.
        throw KitWalletApiException(
            code = KIT_NETWORK_UNAVAILABLE_CODE,
            message = KIT_NETWORK_UNAVAILABLE_MESSAGE,
            statusCode = null,
            cause = transport,
            connectivity = true,
        )
    }
}
