package com.kit.wallet.data.notifications

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Small crash-durable replay fence for call pushes. Only a SHA-256 digest of the opaque call id is
 * persisted. Terminal facts are committed synchronously before notification/Telecom cleanup, so a
 * delayed ring cannot resurrect a call after process death.
 */
@Singleton
class IncomingCallReplayLedger @Inject constructor(
    @ApplicationContext context: Context,
    private val clock: Clock,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val lock = Any()

    fun admitRing(callId: String, ringExpiresAt: String?): Boolean {
        val now = clock.instant()
        val serverExpiry = ringExpiresAt
            ?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?.takeIf { it.isAfter(now) }
            ?: return false
        val digest = digest(callId)
        return synchronized(lock) {
            pruneLocked(now)
            if (!shouldAdmitIncomingRing(
                    now = now,
                    serverExpiry = serverExpiry,
                    recordedRingUntilEpochMillis = preferences.getLongOrNull(ringKey(digest)),
                    retiredUntilEpochMillis = preferences.getLongOrNull(retiredKey(digest)),
                )
            ) {
                return@synchronized false
            }
            // An exact duplicate is deliberately admitted again. The first delivery can be
            // interrupted after this durable write but before Telecom/NotificationManager owns
            // a visible surface; their stable call id and notification tag make replay safe.
            val committed = preferences.edit()
                .putLong(ringKey(digest), serverExpiry.plus(RING_RETENTION).toEpochMilli())
                .commit()
            if (committed) trimLocked()
            committed
        }
    }

    fun retire(callId: String) {
        val now = clock.instant()
        val digest = digest(callId)
        synchronized(lock) {
            pruneLocked(now)
            preferences.edit()
                .remove(ringKey(digest))
                .putLong(retiredKey(digest), now.plus(TERMINAL_RETENTION).toEpochMilli())
                .commit()
            trimLocked()
        }
    }

    /** True only while this exact server ring remains the durable, non-terminal local fact. */
    fun authorizesLaunch(callId: String, ringExpiresAt: Instant): Boolean {
        val now = clock.instant()
        if (!ringExpiresAt.isAfter(now)) return false
        val digest = digest(callId)
        return synchronized(lock) {
            pruneLocked(now)
            shouldAuthorizeIncomingCallLaunch(
                now = now,
                ringExpiresAt = ringExpiresAt,
                recordedRingUntilEpochMillis = preferences.getLongOrNull(ringKey(digest)),
                retiredUntilEpochMillis = preferences.getLongOrNull(retiredKey(digest)),
            )
        }
    }

    private fun pruneLocked(now: Instant) {
        val editor = preferences.edit()
        var changed = false
        preferences.all.forEach { (key, value) ->
            val expiry = value as? Long
            if (expiry == null || expiry <= now.toEpochMilli()) {
                editor.remove(key)
                changed = true
            }
        }
        if (changed) editor.commit()
    }

    private fun trimLocked() {
        val excess = preferences.all.size - MAX_ENTRIES
        if (excess <= 0) return
        val editor = preferences.edit()
        preferences.all.entries
            .sortedBy { (_, value) -> value as? Long ?: Long.MIN_VALUE }
            .take(excess)
            .forEach { (key, _) -> editor.remove(key) }
        editor.commit()
    }

    private fun digest(callId: String): String = MessageDigest.getInstance("SHA-256")
        .digest(callId.toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun ringKey(digest: String) = "ring:$digest"
    private fun retiredKey(digest: String) = "retired:$digest"

    private fun android.content.SharedPreferences.getLongOrNull(key: String): Long? =
        if (contains(key)) getLong(key, Long.MIN_VALUE) else null

    private companion object {
        const val PREFERENCES = "kit_incoming_call_replay_v1"
        const val MAX_ENTRIES = 256
        val RING_RETENTION: Duration = Duration.ofMinutes(10)
        val TERMINAL_RETENTION: Duration = Duration.ofDays(2)
    }
}

internal fun shouldAdmitIncomingRing(
    now: Instant,
    serverExpiry: Instant,
    recordedRingUntilEpochMillis: Long?,
    retiredUntilEpochMillis: Long?,
): Boolean {
    val nowMillis = now.toEpochMilli()
    val expectedRingRetention = serverExpiry.plus(Duration.ofMinutes(10)).toEpochMilli()
    return serverExpiry.isAfter(now) &&
        (retiredUntilEpochMillis == null || retiredUntilEpochMillis <= nowMillis) &&
        (
            recordedRingUntilEpochMillis == null ||
                recordedRingUntilEpochMillis <= nowMillis ||
                recordedRingUntilEpochMillis == expectedRingRetention
            )
}

internal fun shouldAuthorizeIncomingCallLaunch(
    now: Instant,
    ringExpiresAt: Instant,
    recordedRingUntilEpochMillis: Long?,
    retiredUntilEpochMillis: Long?,
): Boolean = ringExpiresAt.isAfter(now) &&
    recordedRingUntilEpochMillis == ringExpiresAt.plus(Duration.ofMinutes(10)).toEpochMilli() &&
    (retiredUntilEpochMillis == null || retiredUntilEpochMillis <= now.toEpochMilli())
