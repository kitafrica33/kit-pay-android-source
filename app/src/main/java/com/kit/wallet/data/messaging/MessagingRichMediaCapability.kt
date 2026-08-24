package com.kit.wallet.data.messaging

import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.RichMediaProtocolDto
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Send-side gate for chat media, mirroring the iOS `MessagingRichMediaCapabilityPolicy`.
 *
 * Images at or under the 10 MB legacy baseline always send — every service that ever spoke
 * `KITMEDIA1` accepts them — so today's offline photo sends keep working with no network call.
 * Everything larger, plus voice notes, videos and documents, is gated on the server's
 * `kit-media-v1` advertisement: the app accepts any *coherent* advertisement and clamps the
 * effective send cap to min(compiled cap, advertised cap), falling back to the 10 MB legacy
 * baseline when nothing usable is advertised. The check is refreshed at send time (like iOS
 * `queueMediaMessage`) so a service rollout raises the limit without an app update.
 */
@Singleton
class MessagingRichMediaCapability @Inject constructor(
    private val api: KitWalletApi,
    private val apiCalls: ApiCallExecutor,
) {
    private val refreshMutex = Mutex()

    @Volatile
    private var lastAdvertisement: RichMediaProtocolDto? = null

    @Volatile
    private var lastLoadedAtMillis: Long = 0

    /**
     * Throws with customer-facing copy when [mediaType] at [byteCount] plaintext bytes cannot be
     * sent through this service.
     */
    suspend fun requireSendable(mediaType: String, byteCount: Long) {
        val isImage = mediaType.startsWith("image/")
        // Legacy-baseline images send unconditionally: every KITMEDIA1 service accepts them, so
        // no capability round trip may delay (or, offline, block) a plain photo send.
        if (isImage && byteCount <= LEGACY_MAX_PLAINTEXT_BYTES) return
        if (!permitsWithCachedAdvertisement(mediaType, isImage, byteCount)) {
            refreshMutex.withLock {
                if (System.currentTimeMillis() - lastLoadedAtMillis >= REFRESH_INTERVAL_MILLIS) {
                    lastAdvertisement = runCatching {
                        apiCalls.execute { api.capabilities() }.protocols?.messaging?.richMedia
                    }.getOrNull() ?: lastAdvertisement
                    lastLoadedAtMillis = System.currentTimeMillis()
                }
            }
        }
        if (!isImage) {
            check(supports(lastAdvertisement, mediaType)) {
                "Voice notes, videos, and documents are not available on this Kit Pay service yet."
            }
        }
        val limit = maximumSendableBytes()
        check(byteCount <= limit) {
            "This Kit Pay service accepts files up to ${limit / (1024 * 1024)} MB right now"
        }
    }

    /**
     * The effective plaintext send cap right now: the compiled-in ceiling clamped to whatever a
     * usable advertisement offers, or the 10 MB legacy baseline when none is cached.
     */
    fun maximumSendableBytes(): Long {
        val advertisement = lastAdvertisement
        val advertised = advertisement?.maximumPlaintextBytes
            ?.takeIf { isUsable(advertisement) }
            ?: LEGACY_MAX_PLAINTEXT_BYTES
        return minOf(MAX_IMAGE_PLAINTEXT_BYTES.toLong(), advertised)
    }

    private fun permitsWithCachedAdvertisement(
        mediaType: String,
        isImage: Boolean,
        byteCount: Long,
    ): Boolean {
        if (!isImage && !supports(lastAdvertisement, mediaType)) return false
        return byteCount <= maximumSendableBytes()
    }

    private fun isUsable(advertisement: RichMediaProtocolDto?): Boolean {
        advertisement ?: return false
        val maximumPlaintext = advertisement.maximumPlaintextBytes
        // The advertisement only has to be a coherent kit-media-v1 wire (the +64 overhead of one
        // authenticated envelope), NOT equal to our compiled bounds: the old strict-equality check
        // failed closed whenever the server and the app raised their caps at different times. The
        // effective cap is clamped to min(compiled, advertised) in [maximumSendableBytes].
        return advertisement.ready == true &&
            advertisement.profile == RICH_MEDIA_PROFILE &&
            advertisement.minimumCiphertextBytes == 64L &&
            maximumPlaintext != null && maximumPlaintext > 0 &&
            advertisement.maximumCiphertextBytes == maximumPlaintext + 64L
    }

    private fun supports(advertisement: RichMediaProtocolDto?, mediaType: String): Boolean =
        isUsable(advertisement) && advertisement?.mediaTypes.orEmpty().contains(mediaType)

    companion object {
        /** Baseline every KITMEDIA1 service accepts; also the cap when nothing is advertised. */
        internal const val LEGACY_MAX_PLAINTEXT_BYTES = 10L * 1024L * 1024L

        private const val RICH_MEDIA_PROFILE = "kit-media-v1"
        private const val REFRESH_INTERVAL_MILLIS = 60_000L
    }
}
