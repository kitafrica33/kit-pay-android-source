package com.kit.wallet.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import javax.inject.Inject
import javax.inject.Singleton
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

class KitWalletApiException(
    val code: String,
    override val message: String,
    val details: Map<String, Any?> = emptyMap(),
    val statusCode: Int? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

@JsonClass(generateAdapter = false)
internal data class ApiFailureEnvelope(
    val ok: Boolean? = null,
    val error: ApiErrorDto? = null,
    val meta: ApiMetaDto? = null,
)

@Singleton
class ApiCallExecutor @Inject constructor(moshi: Moshi) {
    private val failureAdapter = moshi.adapter(ApiFailureEnvelope::class.java)

    suspend fun <T> execute(call: suspend () -> ApiEnvelope<T>): T = try {
        call().requireData()
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
    }
}
