package com.kit.wallet.data.messaging

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

/**
 * Where an attachment's plaintext comes from, opened only at the moment it is encrypted.
 *
 * A 200 MB video already exists somewhere the user put it — in the gallery, in another app's
 * share, in a capture file. Handing the send path a way to *open* it, rather than a `ByteArray`
 * of it, is what lets the outbox keep its promise that plaintext is never copied onto our own
 * disk: the bytes go straight from their original home through the cipher into the ciphertext
 * spool, and a heap-sized attachment stops being a heap-sized problem.
 *
 * A source may be opened more than once, so an implementation must return a fresh stream each
 * time rather than handing back one it has already drained.
 */
class SecureMediaSource(
    /**
     * What the source claims it is about to produce, used only to draw the placeholder bubble
     * while encryption runs. The cipher counts the real bytes and the descriptor records those,
     * so an optimistic or stale claim here can never reach the wire.
     */
    val declaredByteCount: Long,
    private val opener: () -> InputStream,
) {
    fun open(): InputStream = opener()

    companion object {
        /** For plaintext that is genuinely already in heap, such as a re-encoded photo. */
        fun ofBytes(bytes: ByteArray): SecureMediaSource =
            SecureMediaSource(bytes.size.toLong()) { ByteArrayInputStream(bytes) }

        /** For a capture or recording this app wrote itself. */
        fun ofFile(file: File): SecureMediaSource =
            SecureMediaSource(file.length()) { file.inputStream() }
    }
}
