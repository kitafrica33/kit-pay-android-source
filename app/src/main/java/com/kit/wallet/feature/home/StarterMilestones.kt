package com.kit.wallet.feature.home

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** The starter facts this device remembers per account, each monotonic once observed. */
enum class StarterMilestone(internal val keyword: String) {
    FIRST_MESSAGE("first_message"),
    FIRST_TRANSACTION("first_transaction"),
}

/**
 * Durable, account-bound starter milestones — this device's memory, not server authority.
 *
 * The local caches are not durable answers to "has this account ever messaged or moved
 * money?": both are session-scoped, cleared on logout, and the wallet cache is replaced a
 * page at a time — so an old first payment falls out of them. This store records the fact
 * the moment genuine evidence is observed and never unsets it through sync, eviction, or
 * logout. It claims nothing across devices: a reinstall or another phone starts blank and
 * fails closed, showing the checklist again until evidence is next observed.
 *
 * Keys never contain the account id, nor anything derivable from it alone: each is an
 * HMAC-SHA256 of the milestone and canonical account id under a random per-install secret,
 * so the preference file names no account and the same account's fingerprint differs on
 * every install. The keyed binding is also what keeps accounts apart and sign-outs cheap: a
 * replacement sign-in derives different keys and inherits nothing, while the same owner
 * signing back in re-derives the same keys — the secret survives logout, and only account
 * deletion removes an account's markers. If the secret cannot be persisted, nothing is
 * recorded at all: an entry that could never be re-derived after restart would be junk, and
 * completion then rests on live evidence alone.
 *
 * All disk access hops to [ioDispatcher]; callers may collect on the main thread.
 */
@Singleton
class StarterMilestones internal constructor(
    private val prefsProvider: () -> SharedPreferences,
    private val ioDispatcher: CoroutineDispatcher,
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        prefsProvider = {
            context.getSharedPreferences("kit_starter_milestones", Context.MODE_PRIVATE)
        },
        ioDispatcher = Dispatchers.IO,
    )

    private val prefs by lazy(prefsProvider)

    private val secretLock = Any()

    @Volatile
    private var cachedSecret: ByteArray? = null

    /** Bumped on every committed write so flows re-read what the getters answer. */
    private val mutableRevision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = mutableRevision.asStateFlow()

    suspend fun recorded(milestone: StarterMilestone, accountId: String?): Boolean =
        withContext(ioDispatcher) {
            val key = key(milestone, accountId) ?: return@withContext false
            prefs.getBoolean(key, false)
        }

    /**
     * Records the milestone, monotonically. Committed synchronously on the IO dispatcher
     * and only announced on success: a failed write must leave completion resting on live
     * evidence rather than publishing a durable fact this store does not actually hold.
     * Message and transaction live under distinct keys and each commit carries only its
     * own key, so recording one can never overwrite or lose the other.
     */
    suspend fun record(milestone: StarterMilestone, accountId: String?) {
        withContext(ioDispatcher) {
            val key = key(milestone, accountId) ?: return@withContext
            if (prefs.getBoolean(key, false)) return@withContext
            if (prefs.edit().putBoolean(key, true).commit()) {
                mutableRevision.update { it + 1 }
            }
        }
    }

    /** Deleting the account deletes exactly its markers and nobody else's. */
    suspend fun clearForAccount(accountId: String?) {
        withContext(ioDispatcher) {
            val keys = StarterMilestone.entries.mapNotNull { key(it, accountId) }
            if (keys.isEmpty()) return@withContext
            val editor = prefs.edit()
            keys.forEach(editor::remove)
            if (editor.commit()) {
                mutableRevision.update { it + 1 }
            }
        }
    }

    /**
     * Null for a blank or absent account id — an unowned milestone is no milestone — and
     * null when no install secret can be established, which fails closed to not recording.
     */
    private fun key(milestone: StarterMilestone, accountId: String?): String? {
        val canonical = accountId?.trim()?.lowercase()?.takeIf(String::isNotEmpty) ?: return null
        val secret = installSecret() ?: return null
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(secret, "HmacSHA256"))
        }
        val fingerprint = mac
            .doFinal("kit-starter-milestone:${milestone.keyword}:$canonical".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "${milestone.keyword}:$fingerprint"
    }

    /**
     * The random per-install MAC secret, generated once and kept beside the markers it
     * keys. Generation is serialized so two first writes cannot race two secrets into
     * being with one silently orphaned; a secret that will not commit is not used at all.
     */
    private fun installSecret(): ByteArray? {
        cachedSecret?.let { return it }
        synchronized(secretLock) {
            cachedSecret?.let { return it }
            val stored = prefs.getString(SECRET_KEY, null)?.let(::decodeHexOrNull)
            if (stored != null && stored.size == SECRET_BYTES) {
                cachedSecret = stored
                return stored
            }
            val fresh = ByteArray(SECRET_BYTES).also(SecureRandom()::nextBytes)
            val hex = fresh.joinToString("") { "%02x".format(it) }
            if (!prefs.edit().putString(SECRET_KEY, hex).commit()) return null
            cachedSecret = fresh
            return fresh
        }
    }

    private fun decodeHexOrNull(value: String): ByteArray? {
        if (value.length % 2 != 0) return null
        return runCatching {
            ByteArray(value.length / 2) { index ->
                value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        }.getOrNull()
    }

    private companion object {
        const val SECRET_KEY = "install_secret_v1"
        const val SECRET_BYTES = 32
    }
}
