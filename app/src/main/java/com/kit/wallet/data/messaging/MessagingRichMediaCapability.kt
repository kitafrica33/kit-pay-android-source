package com.kit.wallet.data.messaging

import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.RichMediaProtocolDto
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The selected media is valid locally, but the authenticated service has not yet advertised a
 * compatible transport contract. This is retryable: the durable pending bubble and local original
 * stay in place while WorkManager waits for a later coherent capability document.
 */
internal class MessagingRichMediaCapabilityTemporarilyUnavailableException(message: String) :
    IOException(message)

/**
 * Local-admission and network-dispatch policy for chat media.
 *
 * Selecting or capturing media is a device-local action: [requireLocallyQueueable] validates only
 * facts compiled into this client and must never fetch service capabilities. That lets the durable
 * outbox publish a playable pending bubble on a fresh install with no network. [requireSendable]
 * remains the authoritative dispatch-time check against the service advertisement; a missing or
 * incompatible advertisement keeps the already-local message pending rather than weakening E2EE.
 */
@Singleton
class MessagingRichMediaCapability @Inject constructor(
    private val api: KitWalletApi,
    private val apiCalls: ApiCallExecutor,
) {
    private val refreshMutex = Mutex()

    private data class CachedAdvertisement(
        val ownerScopeId: String,
        val loadedAtMillis: Long,
        val advertisement: RichMediaProtocolDto?,
    )

    @Volatile
    private var cachedAdvertisement: CachedAdvertisement? = null

    private var currentTimeMillis: () -> Long = System::currentTimeMillis

    /** Test-only clock injection without adding a function dependency to the Hilt constructor. */
    internal constructor(
        api: KitWalletApi,
        apiCalls: ApiCallExecutor,
        currentTimeMillis: () -> Long,
    ) : this(api, apiCalls) {
        this.currentTimeMillis = currentTimeMillis
    }

    /**
     * Validates whether this build can safely retain and eventually transmit the selected bytes.
     *
     * This method is deliberately synchronous and cache-independent. In particular, it must not
     * turn a fresh-install offline capture into a network request before the durable local media
     * record and pending message exist.
     */
    fun requireLocallyQueueable(mediaType: String, byteCount: Long) {
        check(KitMediaMessage.normalizeMediaType(mediaType) != null) {
            "Choose a supported photo, voice note, video or document"
        }
        check(byteCount in 1..maximumLocallyQueueableBytes()) {
            "Files up to ${maximumLocallyQueueableBytes() / (1024 * 1024)} MB are supported"
        }
    }

    /** The file-backed plaintext ceiling this build can accept without consulting the network. */
    fun maximumLocallyQueueableBytes(): Long = MAX_IMAGE_PLAINTEXT_BYTES.toLong()

    /**
     * Throws with customer-facing copy when [mediaType] at [byteCount] plaintext bytes cannot be
     * sent through this service.
     */
    suspend fun requireSendable(
        mediaType: String,
        byteCount: Long,
        ownerScopeId: String,
    ) {
        requireLocallyQueueable(mediaType, byteCount)
        require(ownerScopeId.isNotBlank()) { "The media queue owner is unavailable" }
        val normalizedMediaType = mediaType.trim().lowercase()
        val isImage = normalizedMediaType.startsWith("image/")
        // Legacy-baseline images send unconditionally: every KITMEDIA1 service accepts them, so
        // no capability round trip may delay (or, offline, block) a plain photo send.
        if (isImage && byteCount <= LEGACY_MAX_PLAINTEXT_BYTES) return
        val advertisement = refreshMutex.withLock {
            val now = currentTimeMillis()
            val cached = cachedAdvertisement
            val age = cached?.let { now - it.loadedAtMillis }
            val isFreshForOwner = cached?.ownerScopeId == ownerScopeId &&
                age != null && age in 0 until REFRESH_INTERVAL_MILLIS
            if (!isFreshForOwner) {
                // A transport failure intentionally leaves the previous cache untouched and is
                // propagated: the outbox classifies it as a background retry. The next run will
                // try again rather than treating an unreachable service as a feature denial.
                val fetched = apiCalls.execute {
                    api.capabilities()
                }.protocols?.messaging?.richMedia
                cachedAdvertisement = CachedAdvertisement(
                    ownerScopeId = ownerScopeId,
                    loadedAtMillis = currentTimeMillis(),
                    advertisement = fetched,
                )
            }
            cachedAdvertisement?.advertisement
        }
        val limit = maximumSendableBytes(advertisement)
        if (byteCount > limit) {
            throw MessagingRichMediaCapabilityTemporarilyUnavailableException(
                "This Kit Pay service accepts files up to ${limit / (1024 * 1024)} MB right now",
            )
        }
        val requiresAdvertisedType = !isImage || byteCount > LEGACY_MAX_PLAINTEXT_BYTES
        if (requiresAdvertisedType && !supports(advertisement, normalizedMediaType)) {
            throw MessagingRichMediaCapabilityTemporarilyUnavailableException(
                "This attachment type is not available on this Kit Pay service yet.",
            )
        }
    }

    /**
     * The effective plaintext send cap right now: the compiled-in ceiling clamped to whatever a
     * usable advertisement offers, or the 10 MB legacy baseline when none is cached.
     */
    fun maximumSendableBytes(): Long {
        return maximumSendableBytes(cachedAdvertisement?.advertisement)
    }

    private fun maximumSendableBytes(advertisement: RichMediaProtocolDto?): Long {
        val advertised = advertisement?.maximumPlaintextBytes
            ?.takeIf { isUsable(advertisement) }
            ?: LEGACY_MAX_PLAINTEXT_BYTES
        return minOf(MAX_IMAGE_PLAINTEXT_BYTES.toLong(), advertised)
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
