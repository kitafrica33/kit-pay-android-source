package com.kit.wallet.data.media

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import coil.imageLoader
import coil.request.CachePolicy
import com.kit.wallet.BuildConfig
import java.io.File
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Where profile photos live on the device, and the only loader they are read through.
 *
 * Coil's default store is an LRU cache inside `cacheDir`, which the system is free to delete
 * whenever storage runs short. That is the right home for something disposable; a profile photo is
 * not. It is small, it is looked at constantly — every chat row, every call, every contact — and a
 * download the user already paid for should not have to be paid for again because the OS reclaimed
 * a few megabytes. So the store is a directory in `filesDir`, which nothing outside this app
 * removes, and it is sized so an ordinary address book fits several times over.
 *
 * Coil is used for profile photos and nothing else in this app — chat media is end-to-end encrypted
 * and decoded through its own pipeline, never handed to an image loader that would write a
 * plaintext copy to disk — so this store belongs to avatars alone and nothing can evict them.
 */
object ProfileAvatarImages {
    /**
     * A cap, not an allocation. Avatars leave the device at 512px inside a 384 KB ceiling and come
     * back re-encoded well under that, so this holds an address book of hundreds with room spare.
     */
    private const val MAX_STORE_BYTES = 48L * 1_024 * 1_024
    private const val DIRECTORY = "profile-avatars"

    /**
     * The loader for the whole app, installed by [com.kit.wallet.KitApplication].
     *
     * `respectCacheHeaders(false)` looks aggressive and is in fact the conservative choice here: an
     * avatar URL names one immutable asset — `/{publicId}/{assetId}` — so a stored response can
     * never be stale for the URL that produced it, and changing the photo produces a different URL
     * rather than different bytes at the same one. Pinning it this way means a stored photo is
     * shown without a network round trip at all, which is what makes it appear offline and appear
     * instantly, and it holds even if the CDN in front of the API one day starts sending headers of
     * its own.
     */
    fun newImageLoader(context: Context): ImageLoader = ImageLoader.Builder(context)
        .diskCache {
            DiskCache.Builder()
                .directory(File(context.applicationContext.filesDir, DIRECTORY))
                .maxSizeBytes(MAX_STORE_BYTES)
                .build()
        }
        .diskCachePolicy(CachePolicy.ENABLED)
        .memoryCachePolicy(CachePolicy.ENABLED)
        .respectCacheHeaders(false)
        // The photo fades up over the initials that were already standing in for it, so the first
        // download reads as the picture arriving rather than as the row changing shape.
        .crossfade(CROSSFADE_MILLIS)
        .build()

    /** The loader in force, for the rare caller that needs it outside a composable. */
    fun loader(context: Context): ImageLoader = context.applicationContext.imageLoader

    /**
     * How much of the device a user's photos are currently occupying, for the storage screen and
     * for the delete-my-data path. Zero before anything has been downloaded.
     */
    fun storedBytes(context: Context): Long =
        File(context.applicationContext.filesDir, DIRECTORY)
            .walkTopDown()
            .filter(File::isFile)
            .sumOf(File::length)

    /**
     * Forgets every downloaded photo. Initials take over until each is fetched again.
     *
     * Cleared through the loader rather than by deleting the directory: the store keeps a journal,
     * and pulling the files out from under a live loader would leave it describing entries that are
     * no longer there.
     */
    fun clear(context: Context) {
        val loader = loader(context)
        loader.memoryCache?.clear()
        loader.diskCache?.clear()
    }

    internal const val CROSSFADE_MILLIS = 180
}

/**
 * Erasure of the stored photo bytes, as a seam the wallet cache can hold.
 *
 * The bytes and the URLs that address them are erased together on sign-out. They live in different
 * places — a Room table and a directory — and only this half needs a `Context`, so it is the half
 * that gets passed in rather than dragging Android into the cache.
 */
fun interface ProfileAvatarByteStore {
    fun clear()
}

/**
 * Whether an avatar URL is one this app is willing to fetch.
 *
 * The URL arrives in an API response, and a URL in a response is an instruction to make a request.
 * Restricting that instruction to the API's own origin means a tampered or compromised response
 * cannot turn every installed client into a beacon for somebody else's host — the same reasoning
 * that already gates the identity provider's verification link. Photos are served by the API itself
 * (`route('profile.avatar.content')`), so nothing legitimate is excluded.
 */
fun isTrustedProfileAvatarUrl(url: String?): Boolean {
    val candidate = url?.trim()?.takeIf(String::isNotEmpty)?.toHttpUrlOrNull() ?: return false
    val api = BuildConfig.KIT_WALLET_BASE_URL.toHttpUrlOrNull() ?: return false
    return candidate.isHttps && candidate.hostMatches(api)
}

/**
 * Host equality, plus subdomains of the API host.
 *
 * A deployment that serves photos from `media.<api host>` is the same operator by any reasonable
 * reading; a deployment that serves them from `<api host>.example.net` is not, which is why the
 * suffix test insists on the dot.
 */
private fun HttpUrl.hostMatches(api: HttpUrl): Boolean {
    val host = host.lowercase()
    val apiHost = api.host.lowercase()
    return host == apiHost || host.endsWith(".$apiHost")
}
