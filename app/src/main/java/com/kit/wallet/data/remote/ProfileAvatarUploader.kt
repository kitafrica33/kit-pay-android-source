package com.kit.wallet.data.remote

import com.kit.wallet.di.RefreshHttpClient
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Uploads a moderated profile photo through the media pipeline shared with iOS: create an upload
 * intent, PUT the exact JPEG bytes to the returned direct-upload URL, finalize, wait for the
 * managed scan to report the asset ready and clean, then attach it to the profile. Every step
 * validates the server's response so a substituted asset or unsafe upload target fails closed.
 */
@Singleton
class ProfileAvatarUploader @Inject constructor(
    private val api: KitWalletApi,
    private val apiCalls: ApiCallExecutor,
    @param:RefreshHttpClient private val http: OkHttpClient,
) {
    suspend fun upload(jpegBytes: ByteArray): UserDto {
        require(jpegBytes.isNotEmpty()) { "Choose a profile photo first" }
        require(jpegBytes.size <= MAX_AVATAR_BYTES) {
            "Profile photos up to ${MAX_AVATAR_BYTES / 1024} KB are supported"
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(jpegBytes)
            .joinToString("") { "%02x".format(it) }
        val intent = apiCalls.execute {
            api.createMediaUploadIntent(
                idempotencyKey = UUID.randomUUID().toString().lowercase(Locale.ROOT),
                request = CreateMediaUploadIntentRequest(
                    byteSize = jpegBytes.size,
                    sha256 = digest,
                ),
            )
        }
        val assetId = intent.asset.id
        check(runCatching { UUID.fromString(assetId) }.isSuccess) {
            "The photo service returned an invalid upload"
        }
        putBytes(intent.upload, jpegBytes)
        val finalized = apiCalls.execute { api.finalizeMediaAsset(assetId) }
        check(finalized.id.equals(assetId, ignoreCase = true)) {
            "The photo service finalized a different upload"
        }
        awaitReadyAndClean(assetId)
        return apiCalls.execute { api.attachProfileAvatar(AttachProfileAvatarRequest(assetId)) }
    }

    private suspend fun putBytes(upload: MediaUploadInstructionsDto, bytes: ByteArray) {
        val headers = upload.headers.orEmpty()
        val target = upload.url.toHttpUrlOrNull()
        // Only HTTPS may carry the photo off the device; plaintext is tolerated solely for
        // loopback test servers, which cannot route beyond this machine.
        val loopback = target?.host == "127.0.0.1" || target?.host == "localhost"
        check(
            upload.method.equals("PUT", ignoreCase = true) &&
                target != null && (target.isHttps || loopback) &&
                headers.size <= MAX_UPLOAD_HEADERS &&
                headers.all { (name, value) -> isSafeUploadHeader(name, value) },
        ) { "The photo service returned an unsafe upload target" }
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(target)
                .put(bytes.toRequestBody("application/octet-stream".toMediaType()))
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .build()
            http.newCall(request).execute().use { response ->
                check(response.isSuccessful) {
                    "The profile photo could not be uploaded. Try again."
                }
            }
        }
    }

    private suspend fun awaitReadyAndClean(assetId: String) {
        repeat(MAX_SCAN_POLLS) { attempt ->
            val asset = apiCalls.execute { api.mediaAsset(assetId) }
            check(asset.id.equals(assetId, ignoreCase = true)) {
                "The photo service returned a different upload"
            }
            val status = asset.status.lowercase(Locale.ROOT)
            val scan = asset.scan?.status?.lowercase(Locale.ROOT).orEmpty()
            if (status == "ready" && scan == "clean") return
            // Only a malware verdict is a judgement about the picture. Anything else — a size or
            // format the server would not take, a storage failure — is a processing failure, and
            // calling it a safety rejection sends people hunting for a "safer" photo.
            check(scan != "infected") {
                "This photo was blocked by the safety scan. Choose a different photo."
            }
            check(status !in setOf("failed", "rejected", "deleted") && scan != "failed") {
                "This photo could not be processed. Try another photo or take a new one."
            }
            if (attempt + 1 < MAX_SCAN_POLLS) delay(SCAN_POLL_MILLIS)
        }
        error("The photo is still being reviewed. Try again in a moment.")
    }

    private fun isSafeUploadHeader(name: String, value: String): Boolean =
        name.isNotBlank() && name.length <= 128 && value.length <= 1_024 &&
            name.none { it == '\r' || it == '\n' } && value.none { it == '\r' || it == '\n' } &&
            !name.equals("Authorization", ignoreCase = true) &&
            !name.equals("Cookie", ignoreCase = true)

    companion object {
        /** Shared with iOS ProfileAvatarUploadPolicy: the transcoded JPEG cap. */
        const val MAX_AVATAR_BYTES = 384 * 1_024
        private const val MAX_UPLOAD_HEADERS = 32
        private const val SCAN_POLL_MILLIS = 3_000L
        private const val MAX_SCAN_POLLS = 31
    }
}
