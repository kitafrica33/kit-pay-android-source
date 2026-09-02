package com.kit.wallet.data.notifications

import java.time.Duration
import java.time.Instant

/**
 * A server-authenticated ring window translated onto Android's monotonic clock at receipt.
 * Device wall time never participates, so manual clock changes cannot drop or extend a call.
 */
data class CallRingLease(
    val sourceRingExpiresAt: String,
    val bootSessionId: Long,
    val receivedElapsedRealtimeMillis: Long,
    val deadlineElapsedRealtimeMillis: Long,
) {
    internal fun isStructurallyValid(): Boolean {
        if (
            sourceRingExpiresAt.isBlank() ||
            runCatching { Instant.parse(sourceRingExpiresAt) }.isFailure ||
            bootSessionId < 0L ||
            receivedElapsedRealtimeMillis < 0L ||
            deadlineElapsedRealtimeMillis <= receivedElapsedRealtimeMillis
        ) {
            return false
        }
        val lifetime = runCatching {
            Math.subtractExact(deadlineElapsedRealtimeMillis, receivedElapsedRealtimeMillis)
        }.getOrNull() ?: return false
        return lifetime in 1L..MAX_CALL_RING_MILLIS
    }

    fun remainingMillis(
        nowElapsedRealtimeMillis: Long,
        currentBootSessionId: Long?,
    ): Long? {
        if (
            !isStructurallyValid() ||
            currentBootSessionId != bootSessionId ||
            nowElapsedRealtimeMillis < receivedElapsedRealtimeMillis ||
            nowElapsedRealtimeMillis >= deadlineElapsedRealtimeMillis
        ) {
            return null
        }
        return deadlineElapsedRealtimeMillis - nowElapsedRealtimeMillis
    }
}

/**
 * Builds a bounded lease only from two timestamps produced by the same backend clock.
 *
 * A legacy payload without `server_time` fails closed: a device wall clock cannot prove that its
 * absolute expiry is still live, and using it would make an already-expired call answerable on a
 * phone whose clock is behind.
 */
internal fun callRingLease(
    ringExpiresAt: String?,
    serverTime: String?,
    receivedElapsedRealtimeMillis: Long,
    bootSessionId: Long?,
    maxRingMillis: Long = MAX_CALL_RING_MILLIS,
): CallRingLease? {
    if (receivedElapsedRealtimeMillis < 0L || bootSessionId == null || bootSessionId < 0L) return null
    val sourceExpiry = ringExpiresAt?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val remaining = callRingWindowMillis(sourceExpiry, serverTime, maxRingMillis) ?: return null
    val deadline = runCatching {
        Math.addExact(receivedElapsedRealtimeMillis, remaining)
    }.getOrNull() ?: return null
    return CallRingLease(
        sourceRingExpiresAt = sourceExpiry,
        bootSessionId = bootSessionId,
        receivedElapsedRealtimeMillis = receivedElapsedRealtimeMillis,
        deadlineElapsedRealtimeMillis = deadline,
    )
}

/** Validates a backend ring window without consulting the device wall clock. */
internal fun callRingWindowMillis(
    ringExpiresAt: String?,
    serverTime: String?,
    maxRingMillis: Long = MAX_CALL_RING_MILLIS,
): Long? {
    if (maxRingMillis <= 0L) return null
    val expiry = ringExpiresAt
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let { runCatching { Instant.parse(it) }.getOrNull() }
        ?: return null
    val serverNow = serverTime
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let { runCatching { Instant.parse(it) }.getOrNull() }
        ?: return null
    return runCatching { Duration.between(serverNow, expiry).toMillis() }
        .getOrNull()
        ?.takeIf { it > 0L }
        ?.coerceAtMost(maxRingMillis)
}

internal const val MAX_CALL_RING_MILLIS = 60_000L
