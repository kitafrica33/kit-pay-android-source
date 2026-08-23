package com.kit.wallet.data.messaging

import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.RichMediaProtocolDto
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Send-side gate for non-image chat media, mirroring the iOS
 * `MessagingRichMediaCapabilityPolicy`: photos always send, while voice notes, videos and
 * documents require the server to advertise the shared `kit-media-v1` profile. The check is
 * refreshed at send time (like iOS `queueMediaMessage`) so a service rollout lights the
 * feature up without an app update.
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

    /** Throws with customer-facing copy when [mediaType] cannot be sent through this service. */
    suspend fun requireAvailable(mediaType: String) {
        if (mediaType.startsWith("image/")) return
        if (supports(lastAdvertisement, mediaType)) return
        refreshMutex.withLock {
            if (System.currentTimeMillis() - lastLoadedAtMillis >= REFRESH_INTERVAL_MILLIS) {
                lastAdvertisement = runCatching {
                    apiCalls.execute { api.capabilities() }.protocols?.messaging?.richMedia
                }.getOrNull() ?: lastAdvertisement
                lastLoadedAtMillis = System.currentTimeMillis()
            }
        }
        check(supports(lastAdvertisement, mediaType)) {
            "Voice notes, videos, and documents are not available on this Kit Pay service yet."
        }
    }

    private fun supports(advertisement: RichMediaProtocolDto?, mediaType: String): Boolean {
        advertisement ?: return false
        // Bounds must equal the compiled-in kit-media-v1 contract exactly; a different profile
        // means a wire we have not reviewed, so the gate fails closed (same rule as iOS).
        return advertisement.ready == true &&
            advertisement.profile == RICH_MEDIA_PROFILE &&
            advertisement.minimumCiphertextBytes == 64L &&
            advertisement.maximumPlaintextBytes == MAX_IMAGE_PLAINTEXT_BYTES.toLong() &&
            advertisement.maximumCiphertextBytes == MAX_IMAGE_CIPHERTEXT_BYTES &&
            advertisement.mediaTypes.orEmpty().contains(mediaType)
    }

    private companion object {
        const val RICH_MEDIA_PROFILE = "kit-media-v1"
        const val REFRESH_INTERVAL_MILLIS = 60_000L
    }
}
