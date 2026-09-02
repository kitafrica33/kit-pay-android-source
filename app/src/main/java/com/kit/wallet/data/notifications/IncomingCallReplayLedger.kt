package com.kit.wallet.data.notifications

import android.content.Context
import com.kit.wallet.data.time.BootSessionIdProvider
import com.kit.wallet.data.time.ElapsedRealtimeClock
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Crash-durable replay fence and monotonic lease store for incoming call pushes.
 *
 * Only a SHA-256 digest of the opaque call id is persisted. A ring lease is tied to Android's
 * current boot count, so elapsed realtime can be trusted after process death but never after a
 * reboot. Retirement markers intentionally have no wall-clock expiry: call IDs are unique, and a
 * bounded set of recent tombstones is safer than resurrecting one because the device clock moved.
 */
@Singleton
class IncomingCallReplayLedger @Inject constructor(
    @ApplicationContext context: Context,
    private val elapsedRealtimeClock: ElapsedRealtimeClock,
    private val bootSessionIdProvider: BootSessionIdProvider,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val lock = Any()

    /** Returns the admitted lease, including for an exact idempotent duplicate. */
    fun admitRing(
        callId: String,
        ringExpiresAt: String?,
        serverTime: String?,
    ): CallRingLease? {
        val nowElapsed = elapsedRealtimeClock.millis()
        val bootId = bootSessionIdProvider.currentBootId()
        val candidate = callRingLease(
            ringExpiresAt = ringExpiresAt,
            serverTime = serverTime,
            receivedElapsedRealtimeMillis = nowElapsed,
            bootSessionId = bootId,
        ) ?: return null
        val digest = digest(callId)
        return synchronized(lock) {
            val rawRecorded = preferences.all[ringKey(digest)]
            // The v1 ledger stored a wall-clock Long. It cannot be translated safely onto this
            // boot's monotonic clock, so its presence blocks a replacement instead of reviving it.
            if (rawRecorded != null && decodeRing(rawRecorded) == null) return@synchronized null
            val selected = selectIncomingRingLease(
                candidate = candidate,
                recorded = decodeRing(rawRecorded),
                retired = preferences.contains(retiredKey(digest)),
                nowElapsedRealtimeMillis = nowElapsed,
                currentBootSessionId = bootId,
            ) ?: return@synchronized null
            val committed = preferences.edit()
                .putString(ringKey(digest), encodeRing(selected))
                .commit()
            if (committed) trimLocked()
            selected.takeIf { committed }
        }
    }

    internal fun retire(
        callId: String,
        disposition: IncomingCallRetirementDisposition,
    ) {
        claimRetirement(
            callId,
            IncomingCallPublicationAuthorization.Retired(disposition),
        )
    }

    /**
     * Claims a natural ring expiry only if no answer, decline or terminal push retired it first.
     * The distinct durable marker lets a late publication preserve MISSED timeout semantics while
     * a terminal marker still wins even when reconciliation runs after the ring deadline.
     */
    fun claimExpiry(callId: String): Boolean {
        return claimRetirement(callId, IncomingCallPublicationAuthorization.Expired)
    }

    /** Returns the exact still-live lease named by an immutable notification action. */
    fun authorizedLease(callId: String, ringExpiresAt: String): CallRingLease? {
        val nowElapsed = elapsedRealtimeClock.millis()
        val bootId = bootSessionIdProvider.currentBootId()
        val digest = digest(callId)
        return synchronized(lock) {
            val recorded = decodeRing(preferences.all[ringKey(digest)])
            recorded?.takeIf {
                !preferences.contains(retiredKey(digest)) &&
                    it.sourceRingExpiresAt == ringExpiresAt &&
                    it.remainingMillis(nowElapsed, bootId) != null
            }
        }
    }

    /** Final publication status that distinguishes a terminal tombstone from natural expiry. */
    internal fun publicationAuthorization(
        callId: String,
        ringExpiresAt: String,
    ): IncomingCallPublicationAuthorization {
        val nowElapsed = elapsedRealtimeClock.millis()
        val bootId = bootSessionIdProvider.currentBootId()
        val digest = digest(callId)
        return synchronized(lock) {
            selectIncomingCallPublicationAuthorization(
                ringExpiresAt = ringExpiresAt,
                recorded = decodeRing(preferences.all[ringKey(digest)]),
                retirement = if (preferences.contains(retiredKey(digest))) {
                    decodeRetirement(preferences.all[retiredKey(digest)])
                } else {
                    null
                },
                nowElapsedRealtimeMillis = nowElapsed,
                currentBootSessionId = bootId,
            )
        }
    }

    private fun claimRetirement(
        callId: String,
        proposed: IncomingCallPublicationAuthorization,
    ): Boolean {
        require(proposed != IncomingCallPublicationAuthorization.Authorized)
        val digest = digest(callId)
        val bootId = bootSessionIdProvider.currentBootId() ?: UNKNOWN_BOOT_ID
        val elapsed = elapsedRealtimeClock.millis().coerceAtLeast(0L)
        return synchronized(lock) {
            val key = retiredKey(digest)
            val existing = if (preferences.contains(key)) {
                decodeRetirement(preferences.all[key])
            } else {
                null
            }
            val selected = selectIncomingCallRetirement(existing, proposed)
            // The first terminal decision owns durable call-log semantics. Later UI teardown,
            // duplicate pushes, and deadline callbacks may clear surfaces but cannot rewrite it.
            if (existing != null) return@synchronized false
            preferences.edit()
                .remove(ringKey(digest))
                .putString(key, encodeRetirement(selected, bootId, elapsed))
                .commit()
            trimLocked()
            true
        }
    }

    private fun trimLocked() {
        val excess = preferences.all.size - MAX_ENTRIES
        if (excess <= 0) return
        val editor = preferences.edit()
        preferences.all.entries
            .sortedWith(
                compareBy<Map.Entry<String, *>> { (_, value) -> recordBootId(value) }
                    .thenBy { (_, value) -> recordElapsedMillis(value) }
                    .thenBy { (key, _) -> key },
            )
            .take(excess)
            .forEach { (key, _) -> editor.remove(key) }
        editor.commit()
    }

    private fun digest(callId: String): String = MessageDigest.getInstance("SHA-256")
        .digest(callId.toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun ringKey(digest: String) = "ring:$digest"
    private fun retiredKey(digest: String) = "retired:$digest"

    private companion object {
        // Retain the original name so an upgrade sees old terminal/ring markers and fails closed.
        const val PREFERENCES = "kit_incoming_call_replay_v1"
        const val MAX_ENTRIES = 256
    }
}

/**
 * Exact duplicates keep the first (earliest) monotonic deadline. A retry may repair delivery of a
 * missing surface, but can never extend or resurrect a ring whose original local lease elapsed.
 */
internal fun selectIncomingRingLease(
    candidate: CallRingLease,
    recorded: CallRingLease?,
    retired: Boolean,
    nowElapsedRealtimeMillis: Long,
    currentBootSessionId: Long?,
): CallRingLease? {
    if (retired || candidate.remainingMillis(nowElapsedRealtimeMillis, currentBootSessionId) == null) {
        return null
    }
    if (recorded == null) return candidate
    if (
        recorded.sourceRingExpiresAt != candidate.sourceRingExpiresAt ||
        recorded.remainingMillis(nowElapsedRealtimeMillis, currentBootSessionId) == null
    ) {
        return null
    }
    return if (recorded.deadlineElapsedRealtimeMillis <= candidate.deadlineElapsedRealtimeMillis) {
        recorded
    } else {
        candidate
    }
}

internal sealed interface IncomingCallPublicationAuthorization {
    data object Authorized : IncomingCallPublicationAuthorization
    data object Expired : IncomingCallPublicationAuthorization
    data class Retired(
        val disposition: IncomingCallRetirementDisposition,
    ) : IncomingCallPublicationAuthorization
}

internal enum class IncomingCallRetirementDisposition(val storageValue: String) {
    ANSWERED_ELSEWHERE("answered_elsewhere"),
    REJECTED("rejected"),
    REMOTE("remote"),
    MISSED("missed"),
    LOCAL("local"),
    ERROR("error"),
    UNKNOWN("unknown"),
}

internal fun selectIncomingCallRetirement(
    existing: IncomingCallPublicationAuthorization?,
    proposed: IncomingCallPublicationAuthorization,
): IncomingCallPublicationAuthorization {
    require(proposed != IncomingCallPublicationAuthorization.Authorized)
    return existing ?: proposed
}

internal fun selectIncomingCallPublicationAuthorization(
    ringExpiresAt: String,
    recorded: CallRingLease?,
    retirement: IncomingCallPublicationAuthorization?,
    nowElapsedRealtimeMillis: Long,
    currentBootSessionId: Long?,
): IncomingCallPublicationAuthorization {
    when (retirement) {
        IncomingCallPublicationAuthorization.Authorized ->
            return IncomingCallPublicationAuthorization.Authorized
        IncomingCallPublicationAuthorization.Expired ->
            return IncomingCallPublicationAuthorization.Expired
        is IncomingCallPublicationAuthorization.Retired -> return retirement
        null -> Unit
    }
    if (recorded == null || recorded.sourceRingExpiresAt != ringExpiresAt) {
        return IncomingCallPublicationAuthorization.Retired(
            IncomingCallRetirementDisposition.UNKNOWN,
        )
    }
    return if (recorded.remainingMillis(nowElapsedRealtimeMillis, currentBootSessionId) != null) {
        IncomingCallPublicationAuthorization.Authorized
    } else {
        IncomingCallPublicationAuthorization.Expired
    }
}

private fun encodeRing(lease: CallRingLease): String = listOf(
    RING_RECORD_VERSION,
    lease.bootSessionId.toString(),
    lease.receivedElapsedRealtimeMillis.toString(),
    lease.deadlineElapsedRealtimeMillis.toString(),
    lease.sourceRingExpiresAt,
).joinToString(RECORD_SEPARATOR)

private fun encodeRetirement(
    retirement: IncomingCallPublicationAuthorization,
    bootId: Long,
    elapsedRealtimeMillis: Long,
): String = when (retirement) {
    IncomingCallPublicationAuthorization.Authorized -> error("A live ring is not a retirement")
    IncomingCallPublicationAuthorization.Expired ->
        "$EXPIRY_RECORD_VERSION|$bootId|$elapsedRealtimeMillis"
    is IncomingCallPublicationAuthorization.Retired ->
        "$TERMINAL_RECORD_VERSION|$bootId|$elapsedRealtimeMillis|" +
            retirement.disposition.storageValue
}

private fun decodeRing(value: Any?): CallRingLease? {
    val parts = (value as? String)?.split(RECORD_SEPARATOR, limit = 5) ?: return null
    if (parts.size != 5 || parts[0] != RING_RECORD_VERSION) return null
    val bootId = parts[1].toLongOrNull()?.takeIf { it >= 0L } ?: return null
    val received = parts[2].toLongOrNull()?.takeIf { it >= 0L } ?: return null
    val deadline = parts[3].toLongOrNull()?.takeIf { it > received } ?: return null
    val expiry = parts[4].takeIf(String::isNotBlank) ?: return null
    return CallRingLease(expiry, bootId, received, deadline).takeIf {
        it.isStructurallyValid()
    }
}

private fun recordBootId(value: Any?): Long = when (value) {
    is String -> value.split(RECORD_SEPARATOR).getOrNull(1)?.toLongOrNull()
        ?: Long.MIN_VALUE
    else -> Long.MIN_VALUE
}

private fun recordElapsedMillis(value: Any?): Long = when (value) {
    is String -> value.split('|').getOrNull(2)?.toLongOrNull() ?: Long.MIN_VALUE
    else -> Long.MIN_VALUE
}

private fun decodeRetirement(value: Any?): IncomingCallPublicationAuthorization {
    val parts = (value as? String)?.split(RECORD_SEPARATOR)
    if (
        parts != null &&
        parts.size == 3 &&
        parts[0] == EXPIRY_RECORD_VERSION &&
        parts[1].toLongOrNull()?.let { it >= UNKNOWN_BOOT_ID } == true &&
        parts[2].toLongOrNull()?.let { it >= 0L } == true
    ) {
        return IncomingCallPublicationAuthorization.Expired
    }
    if (
        parts != null &&
        parts.size == 4 &&
        parts[0] == TERMINAL_RECORD_VERSION &&
        parts[1].toLongOrNull()?.let { it >= UNKNOWN_BOOT_ID } == true &&
        parts[2].toLongOrNull()?.let { it >= 0L } == true
    ) {
        val disposition = IncomingCallRetirementDisposition.entries.firstOrNull {
            it.storageValue == parts[3]
        }
        if (disposition != null) {
            return IncomingCallPublicationAuthorization.Retired(disposition)
        }
    }
    // Legacy and malformed tombstones fail closed with the historical cleanup disposition.
    return IncomingCallPublicationAuthorization.Retired(
        IncomingCallRetirementDisposition.UNKNOWN,
    )
}

private const val RING_RECORD_VERSION = "r1"
private const val TERMINAL_RECORD_VERSION = "t2"
private const val EXPIRY_RECORD_VERSION = "e1"
private const val RECORD_SEPARATOR = "|"
private const val UNKNOWN_BOOT_ID = -1L
