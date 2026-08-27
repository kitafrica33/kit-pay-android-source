package com.kit.wallet.data.remote

import com.kit.wallet.di.RefreshHttpClient
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Uploads an unencrypted bank receipt through the private, malware-scanned media pipeline. */
@Singleton
class BankDepositProofUploader @Inject constructor(
    private val api: KitWalletApi,
    private val apiCalls: ApiCallExecutor,
    @param:RefreshHttpClient private val http: OkHttpClient,
) {
    suspend fun upload(bytes: ByteArray, filename: String, mimeType: String): String {
        val safeName = filename.trim()
        val normalizedMime = mimeType.lowercase(Locale.ROOT)
        require(bytes.isNotEmpty() && bytes.size <= MAX_PROOF_BYTES) {
            "Choose a receipt no larger than 10 MB"
        }
        require(safeName.isNotEmpty() && safeName.length <= 255 && safeName.none(Char::isISOControl)) {
            "Choose a receipt with a valid filename"
        }
        require(normalizedMime in ACCEPTED_MIME_TYPES) {
            "Choose a JPEG, PNG, WebP, or PDF receipt"
        }

        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val intent = apiCalls.execute {
            api.createMediaUploadIntent(
                idempotencyKey = UUID.randomUUID().toString().lowercase(Locale.ROOT),
                request = CreateMediaUploadIntentRequest(
                    kind = if (normalizedMime == "application/pdf") "document" else "image",
                    purpose = "bank_deposit_proof",
                    filename = safeName,
                    mimeType = normalizedMime,
                    byteSize = bytes.size,
                    sha256 = digest,
                    clientEncrypted = false,
                ),
            )
        }
        val assetId = intent.asset.id
        check(runCatching { UUID.fromString(assetId) }.isSuccess) {
            "The receipt service returned an invalid upload"
        }
        putBytes(intent.upload, bytes, normalizedMime)
        val finalized = apiCalls.execute { api.finalizeMediaAsset(assetId) }
        check(finalized.id.equals(assetId, ignoreCase = true)) {
            "The receipt service finalized a different upload"
        }
        check(finalized.status.lowercase(Locale.ROOT) !in setOf("failed", "rejected", "deleted")) {
            "That receipt could not be processed. Choose another file."
        }
        check(finalized.scan?.status?.lowercase(Locale.ROOT) !in setOf("failed", "infected")) {
            "That receipt was blocked by the safety scan. Choose another file."
        }
        return assetId
    }

    private suspend fun putBytes(
        upload: MediaUploadInstructionsDto,
        bytes: ByteArray,
        mimeType: String,
    ) {
        val headers = upload.headers.orEmpty()
        val target = upload.url.toHttpUrlOrNull()
        val loopback = target?.host == "127.0.0.1" || target?.host == "localhost"
        check(
            upload.method.equals("PUT", ignoreCase = true) &&
                target != null && (target.isHttps || loopback) &&
                headers.size <= MAX_UPLOAD_HEADERS &&
                headers.all { (name, value) -> isSafeUploadHeader(name, value) },
        ) { "The receipt service returned an unsafe upload target" }
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(requireNotNull(target))
                .put(bytes.toRequestBody(mimeType.toMediaType()))
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .build()
            http.newCall(request).execute().use { response ->
                check(response.isSuccessful) {
                    "The payment proof could not be uploaded. Try again."
                }
            }
        }
    }

    private fun isSafeUploadHeader(name: String, value: String): Boolean =
        name.isNotBlank() && name.length <= 128 && value.length <= 1_024 &&
            name.none { it == '\r' || it == '\n' } && value.none { it == '\r' || it == '\n' } &&
            !name.equals("Authorization", ignoreCase = true) &&
            !name.equals("Cookie", ignoreCase = true)

    companion object {
        const val MAX_PROOF_BYTES = 10 * 1_024 * 1_024
        val ACCEPTED_MIME_TYPES = setOf(
            "image/jpeg",
            "image/png",
            "image/webp",
            "application/pdf",
        )
        private const val MAX_UPLOAD_HEADERS = 32
    }
}
