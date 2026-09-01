package com.kit.wallet.data.messaging

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.FileNotFoundException

/**
 * Treats something the user picked as a stream to encrypt, never as bytes to hold.
 *
 * A gallery video or a shared document already exists in the provider that owns it. Opening it at
 * the moment of local adoption is what keeps a 200 MB attachment off the heap. The stream is
 * copied atomically into app-owned Sent Media first, while encryption and upload consume that
 * durable copy later in background work.
 *
 * The read permission that came with the pick lasts as long as the activity, and staging happens
 * immediately, so the stream is opened while the grant is certainly still good.
 */
internal fun ContentResolver.secureMediaSource(
    uri: Uri,
    originatedAtNanos: Long = System.nanoTime(),
    originalMediaType: String? = null,
    processingPlan: SecureMediaProcessingPlan = SecureMediaProcessingPlan.PASSTHROUGH,
): SecureMediaSource =
    SecureMediaSource(
        declaredByteCount = declaredByteCount(uri),
        originatedAtNanos = originatedAtNanos,
        originalMediaType = originalMediaType,
        processingPlan = processingPlan,
    ) {
        openInputStream(uri) ?: throw FileNotFoundException(
            "The selected file could not be opened",
        )
    }

/**
 * What the provider says the pick weighs, or zero when it will not say.
 *
 * This only decides whether the "too large" refusal can be shown before any work happens; the
 * cipher enforces the real cap as it streams, so an absent or dishonest size costs a wasted
 * encryption pass at worst and can never widen what reaches the wire.
 */
internal fun ContentResolver.declaredByteCount(uri: Uri): Long = runCatching {
    query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) return@use 0L
        val column = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (column < 0 || cursor.isNull(column)) 0L else cursor.getLong(column).coerceAtLeast(0L)
    } ?: 0L
}.getOrDefault(0L)

/** The name to carry as the caption, since the wire descriptor has no filename field. */
internal fun ContentResolver.displayName(uri: Uri): String? = runCatching {
    query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}.getOrNull()?.takeIf { it.isNotBlank() }?.take(120)
