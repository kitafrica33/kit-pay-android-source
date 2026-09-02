package com.kit.wallet.data.messaging

import android.content.Context
import android.os.Build
import com.kit.wallet.BuildConfig
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One deliberately privacy-safe performance observation retained for tester export. */
internal data class MediaPipelineDiagnosticEntry(
    val mediaKind: String,
    val milestone: MediaPipelineMilestone,
    val elapsedMillis: Long,
    val declaredByteBucket: String,
    val durationBucket: String,
)

/** One queued observation and the non-exported session generation that produced it. */
private data class PendingMediaPipelineDiagnostic(
    val ownerScopeId: String,
    val generation: Long,
    val entry: MediaPipelineDiagnosticEntry,
)

/**
 * A small device-local journal for media acceptance measurements.
 *
 * The persisted owner is a one-way digest of the high-entropy cache/session scope and is never
 * exported. Every read and write requires the digest to match the currently authenticated scope,
 * so a failed preference deletion cannot make one account's measurements visible to another.
 * Records contain only allowlisted kinds, coarse size/duration buckets and bounded timings. The
 * fixed record bound also keeps storage and Android's text-share payload comfortably small.
 *
 * Hot-path calls only append to a bounded in-memory queue. A single scheduled drain coalesces
 * pending observations and performs preference parsing/serialization on the application IO scope;
 * persistence is diagnostic-only and cannot fail a media operation.
 */
@Singleton
class MediaPipelineDiagnosticJournal internal constructor(
    private val readPersisted: () -> String?,
    private val writePersisted: (String) -> Boolean,
    private val clearPersisted: () -> Boolean,
    private val currentOwnerScopeId: () -> String? = { TEST_OWNER_SCOPE_ID },
    private val schedulePersist: ((() -> Unit) -> Unit) = { operation -> operation() },
    private val maxEntries: Int = MAX_ENTRIES,
) {
    @Inject
    internal constructor(
        @ApplicationContext context: Context,
        sessions: SessionStore,
        @ApplicationScope applicationScope: CoroutineScope,
    ) : this(
        readPersisted = {
            preferences(context).getString(ENTRIES_KEY, null)
        },
        writePersisted = { encoded ->
            preferences(context).edit().putString(ENTRIES_KEY, encoded).commit()
        },
        // Clearing is an explicit tester/account-boundary action. Unlike hot-path samples, wait
        // for durable completion so the caller can report the result truthfully.
        clearPersisted = { preferences(context).edit().remove(ENTRIES_KEY).commit() },
        currentOwnerScopeId = { sessions.current()?.cacheScopeId },
        schedulePersist = { operation ->
            applicationScope.launch {
                delay(PERSIST_COALESCE_MILLIS)
                operation()
            }
            Unit
        },
    )

    init {
        require(maxEntries in 1..MAX_ENTRIES)
    }

    private val queueLock = Any()
    private val pending = mutableListOf<PendingMediaPipelineDiagnostic>()
    private var queueGeneration = 0L
    private var persistScheduled = false

    /**
     * Enqueues one sample for its exact originating login generation.
     *
     * No storage call is made here, and all diagnostic failures are swallowed. A caller that has
     * already accepted, encrypted or uploaded media must never fail because evidence collection did.
     */
    internal fun record(
        mediaKind: String,
        measurement: MediaPipelineMeasurement,
        ownerScopeId: String? = currentOwnerScopeId(),
    ) {
        if (ownerScopeId.isNullOrBlank()) return
        try {
            val sample = PendingMediaPipelineDiagnostic(
                ownerScopeId = ownerScopeId,
                generation = synchronized(queueLock) { queueGeneration },
                entry = MediaPipelineDiagnosticEntry(
                    mediaKind = privacySafeMediaKind(mediaKind),
                    milestone = measurement.milestone,
                    elapsedMillis = measurement.elapsedMillis.coerceIn(
                        0L,
                        MAX_ELAPSED_MILLIS,
                    ),
                    declaredByteBucket = privacySafeByteBucket(measurement.declaredByteCount),
                    durationBucket = privacySafeDurationBucket(measurement.durationMillis),
                ),
            )
            val shouldSchedule = synchronized(queueLock) {
                // A concurrent clear may have advanced the generation between construction and
                // enqueue. Such a sample belongs before that boundary and must be discarded.
                if (sample.generation != queueGeneration) return@synchronized false
                pending += sample
                if (pending.size > MAX_PENDING_ENTRIES) {
                    pending.subList(0, pending.size - MAX_PENDING_ENTRIES).clear()
                }
                if (persistScheduled) {
                    false
                } else {
                    persistScheduled = true
                    true
                }
            }
            if (shouldSchedule) scheduleDrainSafely()
        } catch (_: Exception) {
            // Diagnostics are never allowed to affect media acceptance or delivery.
        }
    }

    /**
     * Invalidates queued samples before attempting the durable preference deletion.
     *
     * A `false` result is safe across accounts: persisted rows remain tagged with the retired
     * owner's digest and every subsequent read filters them against the live session scope.
     */
    fun clear(): Boolean = synchronized(processLock) {
        synchronized(queueLock) {
            queueGeneration++
            pending.clear()
        }
        runCatching(clearPersisted).getOrDefault(false)
    }

    fun exportReport(
        versionName: String = BuildConfig.VERSION_NAME,
        versionCode: Int = BuildConfig.VERSION_CODE,
        androidApi: Int = Build.VERSION.SDK_INT,
    ): String {
        val entries = currentEntries()
        return buildString {
            appendLine("Kit Pay media performance diagnostics v2")
            appendLine(
                "build=${privacySafeBuildVersion(versionName)} " +
                    "(${versionCode.coerceAtLeast(0)})",
            )
            appendLine("platform=Android API ${androidApi.coerceAtLeast(0)}")
            appendLine("retained_measurements=${entries.size}/$maxEntries")
            appendLine(
                "fields=media_kind,milestone,elapsed_ms,declared_bytes_bucket," +
                    "duration_bucket",
            )
            appendLine(
                "privacy=owner-scoped; no account, contact, conversation, message, media, file, " +
                    "URL, path, MIME value, exact byte count, exact duration, or device-model " +
                    "identifier",
            )
            entries.forEach { entry ->
                append(entry.mediaKind)
                append(',')
                append(entry.milestone.name.lowercase())
                append(',')
                append(entry.elapsedMillis)
                append(',')
                append(entry.declaredByteBucket)
                append(',')
                appendLine(entry.durationBucket)
            }
        }.trimEnd()
    }

    internal fun snapshot(): List<MediaPipelineDiagnosticEntry> = currentEntries()

    private fun scheduleDrainSafely() {
        try {
            schedulePersist(::drainPending)
        } catch (_: Exception) {
            synchronized(queueLock) { persistScheduled = false }
        }
    }

    private fun drainPending() {
        var writeSucceeded = true
        synchronized(processLock) {
            val batch = synchronized(queueLock) {
                if (pending.isEmpty()) {
                    persistScheduled = false
                    return
                }
                pending.toList().also { pending.clear() }
            }
            val currentOwner = runCatching(currentOwnerScopeId).getOrNull()
            val generation = synchronized(queueLock) { queueGeneration }
            val eligible = batch.filter {
                it.generation == generation && it.ownerScopeId == currentOwner
            }
            if (eligible.isNotEmpty() && currentOwner != null) {
                var intendedEncoded: String? = null
                writeSucceeded = runCatching {
                    val updated = decodeForOwner(readPersisted(), currentOwner)
                        .plus(eligible.map(PendingMediaPipelineDiagnostic::entry))
                        .takeLast(maxEntries)
                    encode(currentOwner, updated).also { intendedEncoded = it }
                        .let(writePersisted)
                }.getOrDefault(false)
                if (!writeSucceeded) {
                    // SharedPreferences may make an edited value visible in memory even when
                    // commit() reports that its durable disk write failed. Re-read and compare
                    // the complete intended snapshot before requeuing: an exact match means this
                    // batch was applied and adding it again would duplicate the measurements.
                    writeSucceeded = intendedEncoded?.let { intended ->
                        runCatching { readPersisted() == intended }.getOrDefault(false)
                    } ?: false
                }
            }
            if (!writeSucceeded) {
                synchronized(queueLock) {
                    if (queueGeneration == generation) {
                        pending.addAll(0, eligible)
                        if (pending.size > MAX_PENDING_ENTRIES) {
                            pending.subList(0, pending.size - MAX_PENDING_ENTRIES).clear()
                        }
                    }
                }
            }
        }

        val scheduleAgain = synchronized(queueLock) {
            persistScheduled = false
            if (writeSucceeded && pending.isNotEmpty()) {
                persistScheduled = true
                true
            } else {
                false
            }
        }
        if (scheduleAgain) scheduleDrainSafely()
    }

    private fun currentEntries(): List<MediaPipelineDiagnosticEntry> = synchronized(processLock) {
        val currentOwner = runCatching(currentOwnerScopeId).getOrNull()
            ?.takeIf(String::isNotBlank)
            ?: return@synchronized emptyList()
        val generation = synchronized(queueLock) { queueGeneration }
        val queued = synchronized(queueLock) {
            pending.filter {
                it.generation == generation && it.ownerScopeId == currentOwner
            }.map(PendingMediaPipelineDiagnostic::entry)
        }
        val stored = runCatching { decodeForOwner(readPersisted(), currentOwner) }
            .getOrDefault(emptyList())
        (stored + queued).takeLast(maxEntries)
    }

    private fun decodeForOwner(
        encoded: String?,
        ownerScopeId: String,
    ): List<MediaPipelineDiagnosticEntry> {
        val lines = encoded.orEmpty().take(MAX_PERSISTED_CHARACTERS).lineSequence().iterator()
        if (!lines.hasNext()) return emptyList()
        val expectedHeader = OWNER_HEADER_PREFIX + privacySafeOwnerToken(ownerScopeId)
        if (lines.next() != expectedHeader) return emptyList()
        return lines.asSequence().mapNotNull(::decodeEntry).toList().takeLast(maxEntries)
    }

    private fun decodeEntry(line: String): MediaPipelineDiagnosticEntry? {
        val fields = line.split('|')
        if (fields.size != FIELD_COUNT) return null
        val mediaKind = fields[0].takeIf(ALLOWED_MEDIA_KINDS::contains) ?: return null
        val milestone = runCatching { MediaPipelineMilestone.valueOf(fields[1]) }.getOrNull()
            ?: return null
        val elapsed = fields[2].toLongOrNull()
            ?.takeIf { it in 0L..MAX_ELAPSED_MILLIS }
            ?: return null
        val byteBucket = fields[3].takeIf(ALLOWED_BYTE_BUCKETS::contains) ?: return null
        val durationBucket = fields[4].takeIf(ALLOWED_DURATION_BUCKETS::contains) ?: return null
        return MediaPipelineDiagnosticEntry(
            mediaKind = mediaKind,
            milestone = milestone,
            elapsedMillis = elapsed,
            declaredByteBucket = byteBucket,
            durationBucket = durationBucket,
        )
    }

    private fun encode(
        ownerScopeId: String,
        entries: List<MediaPipelineDiagnosticEntry>,
    ): String = buildString {
        append(OWNER_HEADER_PREFIX)
        appendLine(privacySafeOwnerToken(ownerScopeId))
        entries.forEach { entry ->
            append(entry.mediaKind)
            append('|')
            append(entry.milestone.name)
            append('|')
            append(entry.elapsedMillis)
            append('|')
            append(entry.declaredByteBucket)
            append('|')
            appendLine(entry.durationBucket)
        }
    }.trimEnd()

    private companion object {
        const val PREFERENCES_NAME = "kit_media_performance_diagnostics_v1"
        const val ENTRIES_KEY = "privacy_safe_measurements"
        const val OWNER_HEADER_PREFIX = "owner_sha256="
        const val TEST_OWNER_SCOPE_ID = "test-owner-scope"
        const val MAX_ENTRIES = 128
        const val MAX_PENDING_ENTRIES = MAX_ENTRIES
        const val PERSIST_COALESCE_MILLIS = 250L
        const val MAX_PERSISTED_CHARACTERS = 32 * 1_024
        const val FIELD_COUNT = 5
        const val MAX_ELAPSED_MILLIS = 30L * 24L * 60L * 60L * 1_000L
        val ALLOWED_MEDIA_KINDS = setOf("image", "video", "audio", "document", "other")
        val ALLOWED_BYTE_BUCKETS = setOf(
            "unknown",
            "0-1mib",
            "1-10mib",
            "10-100mib",
            "100mib-1gib",
        )
        val ALLOWED_DURATION_BUCKETS = setOf(
            "unknown",
            "0-15s",
            "15s-3m",
            "3m-15m",
            "15m-24h",
        )
        val processLock = Any()

        fun preferences(context: Context) = context.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        )
    }
}

internal fun privacySafeMediaKind(mediaType: String): String = when (
    mediaType.substringBefore('/').trim().lowercase()
) {
    "image" -> "image"
    "video" -> "video"
    "audio" -> "audio"
    "application", "text" -> "document"
    else -> "other"
}

private fun privacySafeByteBucket(value: Long?): String = when (value) {
    null -> "unknown"
    in 0L..MEBIBYTE -> "0-1mib"
    in (MEBIBYTE + 1L)..(10L * MEBIBYTE) -> "1-10mib"
    in (10L * MEBIBYTE + 1L)..(100L * MEBIBYTE) -> "10-100mib"
    in (100L * MEBIBYTE + 1L)..MAX_DECLARED_BYTES -> "100mib-1gib"
    else -> "unknown"
}

private fun privacySafeDurationBucket(value: Long?): String = when (value) {
    null -> "unknown"
    in 1L..15_000L -> "0-15s"
    in 15_001L..180_000L -> "15s-3m"
    in 180_001L..900_000L -> "3m-15m"
    in 900_001L..MAX_DURATION_MILLIS -> "15m-24h"
    else -> "unknown"
}

private fun privacySafeOwnerToken(ownerScopeId: String): String = MessageDigest
    .getInstance("SHA-256")
    .digest((OWNER_TOKEN_DOMAIN + ownerScopeId).toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }

private fun privacySafeBuildVersion(value: String): String = value
    .filter { character -> character.isLetterOrDigit() || character in ".-_" }
    .take(MAX_BUILD_VERSION_LENGTH)
    .ifBlank { "unknown" }

private const val OWNER_TOKEN_DOMAIN = "kit-media-diagnostics-v2:"
private const val MEBIBYTE = 1_048_576L
private const val MAX_DECLARED_BYTES = 1_073_741_824L
private const val MAX_DURATION_MILLIS = MAX_LOCAL_MEDIA_DURATION_MILLIS
private const val MAX_BUILD_VERSION_LENGTH = 32
