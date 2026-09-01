package com.kit.wallet.data.messaging

import android.util.Log
import com.kit.wallet.BuildConfig
import java.util.concurrent.ConcurrentHashMap
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
)

/**
 * PII-free timing probes from the capture/selection boundary carried by [SecureMediaSource] to
 * the durable local projection, local playback readiness, and later background stages.
 *
 * IDs are used only as in-memory correlation keys and are never emitted. Debug logs contain the
 * media kind, milestone and duration, which makes a 10-second/2-minute/10-minute device matrix
 * measurable without logging a conversation, account, filename or remote object reference.
 */
@Singleton
internal class MediaPipelineProfiler internal constructor(
    private val nanoTime: () -> Long,
    private val emit: (String, MediaPipelineMeasurement) -> Unit,
) {
    @Inject
    constructor() : this(
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
    )

    private data class Started(val mediaKind: String, val atNanos: Long)

    private val started = ConcurrentHashMap<String, Started>()

    fun begin(
        mediaId: String,
        mediaType: String,
        originatedAtNanos: Long = nanoTime(),
    ) {
        val now = nanoTime()
        // A forged/future timestamp must not produce negative or unbounded diagnostics.
        val origin = originatedAtNanos.takeIf { it in 1L..now } ?: now
        started.putIfAbsent(mediaId, Started(mediaType.substringBefore('/'), origin))
    }

    fun mark(mediaId: String, milestone: MediaPipelineMilestone) {
        val origin = started[mediaId] ?: return
        emit(
            origin.mediaKind,
            MediaPipelineMeasurement(
                milestone = milestone,
                elapsedMillis = ((nanoTime() - origin.atNanos).coerceAtLeast(0L)) / 1_000_000L,
            ),
        )
        if (milestone == MediaPipelineMilestone.UPLOADED) started.remove(mediaId, origin)
    }

    fun forget(mediaId: String) {
        started.remove(mediaId)
    }

    private companion object {
        const val TAG = "KitMediaPipeline"
    }
}
