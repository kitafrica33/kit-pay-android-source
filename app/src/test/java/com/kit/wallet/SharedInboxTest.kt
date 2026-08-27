package com.kit.wallet

import com.kit.wallet.data.messaging.KitMediaMessage
import com.kit.wallet.data.messaging.MAX_IMAGE_PLAINTEXT_BYTES
import com.kit.wallet.feature.chat.SharedInboxPolicy
import com.kit.wallet.feature.chat.SharedInboxItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What crosses the boundary between the system share sheet and Kit Pay.
 *
 * Rule for rule the iOS `SharedInboxTests`, minus the container round trip: staging touches a real
 * `Context`, which a JVM unit test has no way to produce. What is pinned here is the contract — a
 * share this app accepts has to be a share its own wire will carry.
 */
class SharedInboxTest {

    // The two ends of the hand-off agree

    @Test
    fun `share limits match the limits the app enforces`() {
        assertEquals(
            "A share the sheet accepted must be a share the wire can carry",
            MAX_IMAGE_PLAINTEXT_BYTES,
            SharedInboxPolicy.MAXIMUM_BYTES,
        )
    }

    @Test
    fun `share allowlist matches the wire allowlist`() {
        assertEquals(
            KitMediaMessage.SUPPORTED_MEDIA_TYPES,
            SharedInboxPolicy.ALLOWED_MEDIA_TYPES,
        )
        assertTrue(SharedInboxPolicy.FALLBACK_MEDIA_TYPE in SharedInboxPolicy.ALLOWED_MEDIA_TYPES)
    }

    /** Whatever normalization decides, the send path has to accept it. */
    @Test
    fun `every normalized type is sendable`() {
        val candidates = SharedInboxPolicy.ALLOWED_MEDIA_TYPES +
            setOf("image/heic", "image/heif", "application/x-made-up", "", "   ")
        for (candidate in candidates) {
            val normalized = SharedInboxPolicy.normalizedMediaType(candidate)
            assertTrue(
                "$candidate normalized to an unsendable $normalized",
                KitMediaMessage.normalizeMediaType(normalized) != null,
            )
        }
    }

    // What a shared file travels as

    @Test
    fun `known media types are kept exactly`() {
        assertEquals("application/pdf", SharedInboxPolicy.normalizedMediaType("application/pdf"))
        assertEquals("video/mp4", SharedInboxPolicy.normalizedMediaType("VIDEO/MP4"))
        assertEquals(
            "text/plain",
            SharedInboxPolicy.normalizedMediaType("text/plain; charset=utf-8"),
        )
    }

    /**
     * A photo straight off the camera is HEIC, which the wire does not carry. Staging re-encodes
     * it, so its assigned wire type must be JPEG rather than an unsendable HEIC descriptor.
     */
    @Test
    fun `camera native images are prepared as jpeg`() {
        assertTrue(SharedInboxPolicy.requiresImageTranscode("image/heic"))
        assertTrue(SharedInboxPolicy.requiresImageTranscode("IMAGE/HEIF; profile=main"))
        assertTrue(SharedInboxPolicy.requiresImageTranscode("image/jpeg"))
        assertTrue(SharedInboxPolicy.requiresImageTranscode("image/png"))
        assertEquals("image/jpeg", SharedInboxPolicy.normalizedMediaType("image/heic"))
    }

    @Test
    fun `anything else travels as a document`() {
        assertEquals(
            "application/octet-stream",
            SharedInboxPolicy.normalizedMediaType("application/x-made-up"),
        )
        assertEquals("application/octet-stream", SharedInboxPolicy.normalizedMediaType(null))
        assertEquals("application/octet-stream", SharedInboxPolicy.normalizedMediaType("   "))
    }

    // Names from another app are not trusted

    @Test
    fun `stored name is ours and keeps only the extension`() {
        val id = SharedInboxPolicy.newId()
        assertEquals("$id.pdf", SharedInboxPolicy.storageFileName(id, "Quarterly Report.PDF"))
        assertEquals(id, SharedInboxPolicy.storageFileName(id, "../../../etc/passwd"))
        assertEquals(id, SharedInboxPolicy.storageFileName(id, null))
        assertEquals(id, SharedInboxPolicy.storageFileName(id, "no-extension-at-all"))
    }

    @Test
    fun `a file name that could leave the batch is refused`() {
        assertTrue(SharedInboxPolicy.isSafeFileName("photo.jpg"))
        assertFalse(SharedInboxPolicy.isSafeFileName(""))
        assertFalse(SharedInboxPolicy.isSafeFileName("."))
        assertFalse(SharedInboxPolicy.isSafeFileName(".."))
        assertFalse(SharedInboxPolicy.isSafeFileName("nested/photo.jpg"))
        assertFalse(SharedInboxPolicy.isSafeFileName("..\\photo.jpg"))
        assertFalse(SharedInboxPolicy.isSafeFileName("photo\u0000.jpg"))
        assertFalse(SharedInboxPolicy.isSafeFileName("a".repeat(256)))
    }

    /** Every name this app writes has to be a name it will agree to read back. */
    @Test
    fun `a stored name is always a safe name`() {
        val id = SharedInboxPolicy.newId()
        val hostile = listOf(
            "../../etc/passwd",
            "photo.jpg\u0000.exe",
            "a".repeat(400) + ".pdf",
            "..",
            "",
        )
        for (name in hostile) {
            assertTrue(
                "$name produced an unsafe stored name",
                SharedInboxPolicy.isSafeFileName(SharedInboxPolicy.storageFileName(id, name)),
            )
        }
    }

    @Test
    fun `display name falls back to what the file is`() {
        assertEquals(
            "Report.pdf",
            SharedInboxPolicy.displayName("Report.pdf", "application/pdf"),
        )
        assertEquals("Photo", SharedInboxPolicy.displayName(null, "image/jpeg"))
        assertEquals("Video", SharedInboxPolicy.displayName("  ", "video/mp4"))
        assertEquals("Audio", SharedInboxPolicy.displayName(null, "audio/mp4"))
        assertEquals("Document", SharedInboxPolicy.displayName(null, "application/zip"))
        assertEquals(
            "A separator in a name must never read as a path",
            "a-b-c",
            SharedInboxPolicy.displayName("a/b:c", "application/pdf"),
        )
        assertEquals(120, SharedInboxPolicy.displayName("n".repeat(400), "application/pdf").length)
    }

    // Text

    @Test
    fun `shared text is trimmed and bounded`() {
        assertNull(SharedInboxPolicy.normalizedText("   \n "))
        assertNull(SharedInboxPolicy.normalizedText(null))
        assertEquals("hello", SharedInboxPolicy.normalizedText("  hello  "))
        val long = "a".repeat(SharedInboxPolicy.MAXIMUM_TEXT_CHARACTERS + 500)
        assertEquals(
            SharedInboxPolicy.MAXIMUM_TEXT_CHARACTERS,
            SharedInboxPolicy.normalizedText(long)?.length,
        )
    }

    @Test
    fun `structured wire prefixes cannot enter chat as shared user text`() {
        assertFalse(SharedInboxPolicy.allowsUserAuthoredText("KITPAY1:fake"))
        assertFalse(SharedInboxPolicy.allowsUserAuthoredText("KITGRP1:fake"))
        assertFalse(SharedInboxPolicy.allowsUserAuthoredText("KITMEDIA1:fake"))
        assertFalse(SharedInboxPolicy.allowsUserAuthoredText("KITRXN1:fake"))
        assertFalse(SharedInboxPolicy.allowsUserAuthoredText("KITEDIT1:fake"))
        assertTrue(SharedInboxPolicy.allowsUserAuthoredText("Please review KITMEDIA1: later"))
    }

    // What the picker says

    @Test
    fun `summary counts what is actually there`() {
        assertEquals("Nothing to send", SharedInboxPolicy.summary(itemCount = 0, hasText = false))
        assertEquals("Text ready to send", SharedInboxPolicy.summary(itemCount = 0, hasText = true))
        assertEquals(
            "1 item ready to send",
            SharedInboxPolicy.summary(itemCount = 1, hasText = false),
        )
        assertEquals(
            "1 item and text ready to send",
            SharedInboxPolicy.summary(itemCount = 1, hasText = true),
        )
        assertEquals(
            "3 items ready to send",
            SharedInboxPolicy.summary(itemCount = 3, hasText = false),
        )
        assertEquals(
            "3 items and text ready to send",
            SharedInboxPolicy.summary(itemCount = 3, hasText = true),
        )
    }

    // Nothing forgotten is kept

    @Test
    fun `a shared file nobody delivered is retired`() {
        val now = 1_700_000_000_000L
        assertFalse(SharedInboxPolicy.isExpired(now, now))
        assertFalse(
            SharedInboxPolicy.isExpired(now - SharedInboxPolicy.RETENTION_MILLIS + 60_000, now),
        )
        assertTrue(SharedInboxPolicy.isExpired(now - SharedInboxPolicy.RETENTION_MILLIS, now))
    }

    /** A device whose clock moved backwards must not turn an old share into a fresh one. */
    @Test
    fun `a share stamped far in the future is also retired`() {
        val now = 1_700_000_000_000L
        assertTrue(SharedInboxPolicy.isExpired(now + SharedInboxPolicy.RETENTION_MILLIS * 2, now))
    }

    // The size cap

    @Test
    fun `the size cap is the same on both sides of the hand off`() {
        assertFalse(SharedInboxPolicy.fits(0))
        assertFalse(SharedInboxPolicy.fits(-1))
        assertTrue(SharedInboxPolicy.fits(1))
        assertTrue(SharedInboxPolicy.fits(SharedInboxPolicy.MAXIMUM_BYTES.toLong()))
        assertFalse(SharedInboxPolicy.fits(SharedInboxPolicy.MAXIMUM_BYTES + 1L))
    }

    @Test
    fun `the aggregate batch cap prevents individually valid files exceeding 200 MiB`() {
        fun item(id: String, bytes: Int) = SharedInboxItem(
            id = id,
            fileName = id,
            mediaType = "application/octet-stream",
            displayName = "Document",
            byteCount = bytes,
        )
        val first = item("40000000-0000-4000-8000-000000000001", 120 * 1_024 * 1_024)
        val within = item("40000000-0000-4000-8000-000000000002", 80 * 1_024 * 1_024)
        val over = item("40000000-0000-4000-8000-000000000003", 80 * 1_024 * 1_024 + 1)

        assertEquals(SharedInboxPolicy.MAXIMUM_BYTES, SharedInboxPolicy.MAXIMUM_BATCH_BYTES)
        assertTrue(SharedInboxPolicy.batchFits(listOf(first, within)))
        assertFalse(SharedInboxPolicy.batchFits(listOf(first, over)))
    }

    @Test
    fun `a batch id is unique per share`() {
        assertNotNull(SharedInboxPolicy.newId())
        assertTrue(SharedInboxPolicy.newId() != SharedInboxPolicy.newId())
    }

    @Test
    fun `delivery ids are stable within one pinned conversation and differ across chats`() {
        val batch = "20000000-0000-4000-8000-000000000001"
        val item = "40000000-0000-4000-8000-000000000001"
        val firstChat = "30000000-0000-4000-8000-000000000001"
        val secondChat = "30000000-0000-4000-8000-000000000002"

        assertEquals(
            SharedInboxPolicy.deliveryMessageId(batch, firstChat, item),
            SharedInboxPolicy.deliveryMessageId(batch, firstChat, item),
        )
        assertTrue(
            SharedInboxPolicy.deliveryMessageId(batch, firstChat, item) !=
                SharedInboxPolicy.deliveryMessageId(batch, secondChat, item),
        )
    }
}
