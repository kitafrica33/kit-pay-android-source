package com.kit.wallet.data.messaging

import android.util.Log
import com.kit.wallet.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton

internal enum class MediaPipelineMilestone {
    LOCAL_PROJECTION_READY,
    LOCAL_PLAYABLE,
    ENCRYPTED,
    UPLOADED,
}

internal data class MediaPipelineMeasurement(
    val milestone: MediaPipelineMilestone,
    val elapsedMillis: Long,
    val declaredByteCount: Long? = null,
    val durationMillis: Long? = null,
)

/**
 * PII-free timing probes from the capture/selection boundary carried by [SecureMediaSource] to
 * the durable local projection, local playback readiness, and later background stages.
 *
 * IDs are used only as in-memory correlation keys and are never emitted. The bounded diagnostic
 * journal stores a coarse media kind, milestone, elapsed latency and bucketed input dimensions;
 * debug logs contain only the kind, milestone and elapsed latency. This keeps the device matrix
 * measurable without logging a conversation, account, filename or remote object reference.
 */
@Singleton
internal class MediaPipelineProfiler internal constructor(
    private val nanoTime: () -> Long,
    private val emit: (String, MediaPipelineMeasurement) -> Unit,
    private val diagnosticJournal: MediaPipelineDiagnosticJournal? = null,
) {
    @Inject
    constructor(diagnosticJournal: MediaPipelineDiagnosticJournal) : this(
        nanoTime = System::nanoTime,
        emit = { mediaKind, measurement ->
            if (BuildConfig.DEBUG) {
                Log.d(
                    TAG,
                    "kind=$mediaKind milestone=${measurement.milestone.name.lowercase()} " +
                        "elapsed_ms=${measurement.elapsedMillis}",
                )
            }
        },
        diagnosticJournal = diagnosticJournal,
    )

    private data class Started(
        val mediaKind: String,
        val atNanos: Long,
        val declaredByteCount: Long?,
        val durationMillis: Long?,
        val observedMilestones: MutableSet<MediaPipelineMilestone> = mutableSetOf(),
    )

    /** A client media ID is unique only inside the exact authenticated account generation. */
    private data class MediaKey(
        val ownerScopeId: String,
        val mediaId: String,
    )

    private data class Observation(
        val mediaKind: String,
        val ownerScopeId: String,
        val measurement: MediaPipelineMeasurement,
    )

    private val started = linkedMapOf<MediaKey, Started>()
    private val lock = Any()

    fun begin(
        mediaId: String,
        mediaType: String,
        originatedAtNanos: Long = nanoTime(),
        declaredByteCount: Long? = null,
        durationMillis: Long? = null,
        ownerScopeId: String,
    ) {
        val key = diagnosticKey(mediaId, ownerScopeId) ?: return
        val now = nanoTime()
        // A forged/future timestamp must not produce negative or unbounded diagnostics.
        val origin = originatedAtNanos.takeIf { it in 1L..now } ?: now
        synchronized(lock) {
            if (started.containsKey(key)) return
            if (started.size >= MAX_TRACKED_MEDIA) {
                started.entries.firstOrNull()?.key?.let(started::remove)
            }
            started[key] = Started(
                mediaKind = privacySafeMediaKind(mediaType),
                atNanos = origin,
                declaredByteCount = declaredByteCount,
                durationMillis = durationMillis,
            )
        }
    }

    fun mark(
        mediaId: String,
        milestone: MediaPipelineMilestone,
        ownerScopeId: String,
    ) {
        val key = diagnosticKey(mediaId, ownerScopeId) ?: return
        val observation = synchronized(lock) {
            val origin = started[key] ?: return
            // UI recomposition, reopening local media, or a repeated worker callback must not turn
            // one send into several latency samples. The first observation is the user-facing fact.
            if (!origin.observedMilestones.add(milestone)) return
            if (milestone == MediaPipelineMilestone.UPLOADED) started.remove(key)
            Observation(
                mediaKind = origin.mediaKind,
                ownerScopeId = key.ownerScopeId,
                measurement = MediaPipelineMeasurement(
                    milestone,
                    ((nanoTime() - origin.atNanos).coerceAtLeast(0L)) / 1_000_000L,
                    origin.declaredByteCount,
                    origin.durationMillis,
                ),
            )
        }
        // Persistence is deliberately best-effort. A diagnostic backend must never turn an
        // already accepted/encrypted/uploaded attachment into a failed or duplicated send.
        runCatching {
            diagnosticJournal?.record(
                mediaKind = observation.mediaKind,
                measurement = observation.measurement,
                ownerScopeId = observation.ownerScopeId,
            )
        }
        runCatching { emit(observation.mediaKind, observation.measurement) }
    }

    fun forget(mediaId: String, ownerScopeId: String) {
        val key = diagnosticKey(mediaId, ownerScopeId) ?: return
        synchronized(lock) { started.remove(key) }
    }

    private fun diagnosticKey(mediaId: String, ownerScopeId: String): MediaKey? {
        if (mediaId.isBlank() || ownerScopeId.isBlank()) return null
        return MediaKey(ownerScopeId, mediaId)
    }

    private companion object {
        const val TAG = "KitMediaPipeline"
        const val MAX_TRACKED_MEDIA = 256
    }
}
