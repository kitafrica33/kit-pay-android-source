package com.kit.wallet

import com.kit.wallet.data.messaging.KitMediaFamily
import com.kit.wallet.data.messaging.KitMediaMessage
import com.kit.wallet.data.messaging.KitMediaMessageV2
import com.kit.wallet.data.messaging.KitMediaMessageV2Item
import com.kit.wallet.data.messaging.kitMediaAttachmentsFor
import com.kit.wallet.data.messaging.mediaAlbumAccessibilityLabel
import com.kit.wallet.data.messaging.requiresModernMediaSchemaFence
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Frozen-contract vectors for the KITMEDIA2 descriptor (contract §9): the two valid vectors are
 * asserted byte-for-byte, and every malformed class must strictly fail to parse.
 */
class KitMediaMessageV2Test {
    // K0 = base64(64 x 0x00), K1 = base64(64 x 0xFF) — fixed key-material vectors.
    private val k0 = "A".repeat(86) + "=="
    private val k1 = "/".repeat(85) + "w=="
    private val k0Encoded = "A".repeat(86) + "%3D%3D"
    private val k1Encoded = "%2F".repeat(85) + "w%3D%3D"

    private val vector1Item0 = KitMediaMessageV2Item(
        attachmentId = "11111111-1111-4111-8111-111111111111",
        storageKey = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
        mediaType = "image/jpeg",
        ciphertextByteSize = 1_088,
        ciphertextSha256 = "1".repeat(64),
        keyMaterialBase64 = k0,
        plaintextByteSize = 1_024,
    )
    private val vector1Item1 = KitMediaMessageV2Item(
        attachmentId = "22222222-2222-4222-8222-222222222222",
        storageKey = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
        mediaType = "video/mp4",
        ciphertextByteSize = 5_242_944,
        ciphertextSha256 = "2".repeat(64),
        keyMaterialBase64 = k1,
        plaintextByteSize = 5_242_880,
    )
    private val vector1 = KitMediaMessageV2(
        items = listOf(vector1Item0, vector1Item1),
        caption = "Family photos",
    )
    private val vector1Text = "KITMEDIA2:v=2&n=2" +
        "&id0=11111111-1111-4111-8111-111111111111" +
        "&sk0=aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa" +
        "&mt0=image%2Fjpeg" +
        "&bs0=1088" +
        "&sha0=" + "1".repeat(64) +
        "&key0=" + k0Encoded +
        "&ps0=1024" +
        "&id1=22222222-2222-4222-8222-222222222222" +
        "&sk1=bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb" +
        "&mt1=video%2Fmp4" +
        "&bs1=5242944" +
        "&sha1=" + "2".repeat(64) +
        "&key1=" + k1Encoded +
        "&ps1=5242880" +
        "&cap=Family%20photos"

    private val vector2 = KitMediaMessageV2(
        items = listOf(
            KitMediaMessageV2Item(
                attachmentId = "33333333-3333-4333-8333-333333333333",
                storageKey = "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
                mediaType = "application/pdf",
                ciphertextByteSize = 64,
                ciphertextSha256 = "3".repeat(64),
                keyMaterialBase64 = k0,
                plaintextByteSize = 5,
            ),
            KitMediaMessageV2Item(
                attachmentId = "44444444-4444-4444-8444-444444444444",
                storageKey = "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
                mediaType = "audio/ogg",
                ciphertextByteSize = 80,
                ciphertextSha256 = "4".repeat(64),
                keyMaterialBase64 = k1,
                plaintextByteSize = 16,
            ),
            KitMediaMessageV2Item(
                attachmentId = "55555555-5555-4555-8555-555555555555",
                storageKey = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
                mediaType = "image/png",
                ciphertextByteSize = 1_048_640,
                ciphertextSha256 = "5".repeat(64),
                keyMaterialBase64 = k0,
                plaintextByteSize = 1_048_576,
            ),
        ),
        caption = null,
    )
    private val vector2Text = "KITMEDIA2:v=2&n=3" +
        "&id0=33333333-3333-4333-8333-333333333333" +
        "&sk0=cccccccc-cccc-4ccc-8ccc-cccccccccccc" +
        "&mt0=application%2Fpdf" +
        "&bs0=64" +
        "&sha0=" + "3".repeat(64) +
        "&key0=" + k0Encoded +
        "&ps0=5" +
        "&id1=44444444-4444-4444-8444-444444444444" +
        "&sk1=dddddddd-dddd-4ddd-8ddd-dddddddddddd" +
        "&mt1=audio%2Fogg" +
        "&bs1=80" +
        "&sha1=" + "4".repeat(64) +
        "&key1=" + k1Encoded +
        "&ps1=16" +
        "&id2=55555555-5555-4555-8555-555555555555" +
        "&sk2=eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee" +
        "&mt2=image%2Fpng" +
        "&bs2=1048640" +
        "&sha2=" + "5".repeat(64) +
        "&key2=" + k0Encoded +
        "&ps2=1048576"

    /** Raw builder for malformed shapes the typed encoder refuses to produce. */
    private fun rawItemGroup(
        index: Int,
        id: String = "%08d-0000-4000-8000-00000000000%d".format(index, index),
        sk: String = "%08d-0000-4000-8000-10000000000%d".format(index, index),
        mt: String = "audio%2Fogg",
        bs: Long = 80,
        sha: String = index.toString().takeLast(1).repeat(64),
        key: String = k0Encoded,
        ps: Int = 16,
    ): String = "&id$index=$id&sk$index=$sk&mt$index=$mt&bs$index=$bs" +
        "&sha$index=$sha&key$index=$key&ps$index=$ps"

    @Test
    fun contractVector1RoundTripsByteExactly() {
        assertEquals(vector1Text, vector1.encode())
        assertEquals(vector1, KitMediaMessageV2.parse(vector1Text))
        assertEquals(vector1Text, KitMediaMessageV2.parse(vector1Text)?.encode())
        assertTrue(KitMediaMessageV2.isMediaText(vector1Text))
        assertTrue(KitMediaFamily.isFamilyText(vector1Text))
    }

    @Test
    fun contractVector2RoundTripsByteExactly() {
        assertEquals(vector2Text, vector2.encode())
        assertEquals(vector2, KitMediaMessageV2.parse(vector2Text))
        assertEquals(vector2Text, KitMediaMessageV2.parse(vector2Text)?.encode())
    }

    @Test
    fun attachmentRowsAreCanonicalAscendingIdOrderNeverDisplayOrder() {
        // Display order deliberately lists the lexicographically larger id first.
        val reversedDisplay = KitMediaMessageV2(
            items = listOf(vector1Item1, vector1Item0),
            caption = null,
        )
        val roundTripped = KitMediaMessageV2.parse(reversedDisplay.encode())
        // The descriptor preserves display order…
        assertEquals(
            listOf(vector1Item1.attachmentId, vector1Item0.attachmentId),
            roundTripped?.items?.map(KitMediaMessageV2Item::attachmentId),
        )
        // …while the server-visible rows are always ascending by id.
        val rows = KitMediaMessageV2.attachmentsFor(reversedDisplay.encode())
        assertEquals(
            listOf(vector1Item0.attachmentId, vector1Item1.attachmentId),
            rows.map { it.id },
        )
        assertEquals(
            listOf(vector1Item0.storageKey, vector1Item1.storageKey),
            rows.map { it.storageKey },
        )
        rows.forEach { assertNull(it.encryptionMetadataCiphertext) }
        // No row field carries key material.
        assertFalse(rows.toString().contains(k0))
        assertFalse(rows.toString().contains(k1))
    }

    @Test
    fun familyAttachmentDerivationCoversBothGenerations() {
        val v1 = KitMediaMessage(
            attachmentId = vector1Item0.attachmentId,
            storageKey = vector1Item0.storageKey,
            mediaType = "image/jpeg",
            ciphertextByteSize = 1_088,
            ciphertextSha256 = "1".repeat(64),
            keyMaterialBase64 = k0,
            plaintextByteSize = 1_024,
            caption = null,
        ).encode()
        assertEquals(1, kitMediaAttachmentsFor(v1).size)
        assertEquals(2, kitMediaAttachmentsFor(vector1Text).size)
        assertTrue(kitMediaAttachmentsFor("hello securely").isEmpty())
        // The generations never parse as one another.
        assertNull(KitMediaMessageV2.parse(v1))
        assertNull(KitMediaMessage.parse(vector1Text))
        assertTrue(KitMediaMessageV2.attachmentsFor(v1).isEmpty())
        assertFalse(requiresModernMediaSchemaFence(v1))
        assertTrue(requiresModernMediaSchemaFence(vector1Text))
        assertTrue(requiresModernMediaSchemaFence("KITMEDIA2:v=2&n=2&malformed"))
        assertTrue(requiresModernMediaSchemaFence("KITMEDIA9:future"))
        assertFalse(requiresModernMediaSchemaFence("hello securely"))
    }

    @Test
    fun albumAccessibilityUsesPluralKindAndExactNullableCaption() {
        assertEquals(
            "2 Photos · Family photos",
            mediaAlbumAccessibilityLabel(
                mediaTypes = listOf("image/jpeg", "image/png"),
                caption = "Family photos",
            ),
        )
        assertEquals(
            "2 Attachments · " + Char(0x0E) + "caption",
            mediaAlbumAccessibilityLabel(
                mediaTypes = listOf("image/jpeg", "video/mp4"),
                caption = Char(0x0E) + "caption",
            ),
        )
        assertEquals(
            "2 Voice notes",
            mediaAlbumAccessibilityLabel(
                mediaTypes = listOf("audio/ogg", "audio/mpeg"),
                caption = null,
            ),
        )
    }

    @Test
    fun malformed1SingleItemStaysKitmedia1() {
        assertNull(KitMediaMessageV2.parse("KITMEDIA2:v=2&n=1" + rawItemGroup(0)))
    }

    @Test
    fun malformed2CountAboveCeilingAndOversizedDescriptor() {
        val nine = "KITMEDIA2:v=2&n=9" + (0 until 9).joinToString("", transform = ::rawItemGroup)
        assertNull(KitMediaMessageV2.parse(nine))
        // Eight items parse; the same eight with a caption whose encoded form blows the shared
        // 7,680-byte budget do not — the caption ceiling is an absolute bound, not an allowance.
        val eightItems = (0 until 8).map { index ->
            KitMediaMessageV2Item(
                attachmentId = "%08d-0000-4000-8000-00000000000%d".format(index, index),
                storageKey = "%08d-0000-4000-8000-10000000000%d".format(index, index),
                mediaType = "audio/ogg",
                ciphertextByteSize = 80,
                ciphertextSha256 = index.toString().repeat(64),
                keyMaterialBase64 = k1,
                plaintextByteSize = 16,
            )
        }
        assertEquals(
            KitMediaMessageV2(eightItems, null),
            KitMediaMessageV2.parse(KitMediaMessageV2(eightItems, null).encode()),
        )
        // 1,024 two-byte codepoints: 2,048 caption bytes (legal alone) but 6,144 encoded bytes.
        val eAcute = Char(0xE9).toString()
        val oversized = KitMediaMessageV2(eightItems, eAcute.repeat(1_024))
        assertTrue(oversized.encode().toByteArray(StandardCharsets.UTF_8).size > 7_680)
        assertNull(KitMediaMessageV2.parse(oversized.encode()))
    }

    @Test
    fun malformed3FieldCountMismatchesCount() {
        assertNull(KitMediaMessageV2.parse("KITMEDIA2:v=2&n=2" + rawItemGroup(0)))
        assertNull(
            KitMediaMessageV2.parse(
                "KITMEDIA2:v=2&n=2" + rawItemGroup(0) + rawItemGroup(1) + rawItemGroup(2),
            ),
        )
    }

    @Test
    fun malformed4DuplicateIdentifiersAcrossItems() {
        assertNull(
            KitMediaMessageV2.parse(
                vector1Text.replace(vector1Item1.attachmentId, vector1Item0.attachmentId),
            ),
        )
        assertNull(
            KitMediaMessageV2.parse(
                vector1Text.replace(vector1Item1.storageKey, vector1Item0.storageKey),
            ),
        )
    }

    @Test
    fun malformed5StructuralKeyViolations() {
        // Duplicate cap.
        assertNull(KitMediaMessageV2.parse(vector1Text + "&cap=b"))
        // Unknown key.
        assertNull(KitMediaMessageV2.parse(vector2Text + "&zz=1"))
        // cap not last.
        assertNull(
            KitMediaMessageV2.parse(
                vector1Text.replace("&ps1=5242880&cap=Family%20photos", "&cap=Family%20photos&ps1=5242880"),
            ),
        )
        // Group order violated: sk0 before id0.
        assertNull(
            KitMediaMessageV2.parse(
                vector1Text.replace(
                    "&id0=11111111-1111-4111-8111-111111111111&sk0=aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                    "&sk0=aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa&id0=11111111-1111-4111-8111-111111111111",
                ),
            ),
        )
        // Index gap: groups labelled 0 and 2.
        assertNull(KitMediaMessageV2.parse("KITMEDIA2:v=2&n=2" + rawItemGroup(0) + rawItemGroup(2)))
        // A second '=' inside a token.
        assertNull(KitMediaMessageV2.parse(vector1Text.replace("cap=Family", "cap=Fam=ily")))
    }

    @Test
    fun malformed6CaptionViolations() {
        val base = vector1Text.removeSuffix("Family%20photos")
        // Empty caption key.
        assertNull(KitMediaMessageV2.parse(base))
        // Only whitespace codepoints.
        assertNull(KitMediaMessageV2.parse(base + "%20%09"))
        // Leading or trailing codepoint from the six-codepoint set.
        assertNull(KitMediaMessageV2.parse(base + "%20Family"))
        assertNull(KitMediaMessageV2.parse(base + "Family%0A"))
        // Embedded U+0000.
        assertNull(KitMediaMessageV2.parse(base + "Fam%00ily"))
        // 2,049 UTF-8 bytes.
        assertNull(KitMediaMessageV2.parse(base + "a".repeat(2_049)))
        // 2,048 bytes is the last accepted size; interior newlines are content, not edges.
        assertEquals(
            "a".repeat(2_048),
            KitMediaMessageV2.parse(base + "a".repeat(2_048))?.caption,
        )
        val interiorNewline = "Family" + Char(0x0A) + "photos"
        assertEquals(interiorNewline, KitMediaMessageV2.parse(base + "Family%0Aphotos")?.caption)
    }

    @Test
    fun malformed7CaseAndBase64Canonicality() {
        // Uppercase hex in sha, uppercase UUID.
        assertNull(
            KitMediaMessageV2.parse(vector1Text.replace("1".repeat(64), "1".repeat(63) + "A")),
        )
        // The digit-only vector ids are caseless, so the storage key is the UUID with letters.
        assertNull(
            KitMediaMessageV2.parse(
                vector1Text.replace(vector1Item0.storageKey, vector1Item0.storageKey.uppercase()),
            ),
        )
        // Missing base64 padding.
        assertNull(
            KitMediaMessageV2.parse(vector1Text.replace("&key0=$k0Encoded", "&key0=" + "A".repeat(86))),
        )
        // Canonical base64 of the wrong key sizes: 63 and 65 bytes.
        val k63 = "A".repeat(84)
        val k65 = "A".repeat(87) + "%3D"
        assertNull(KitMediaMessageV2.parse(vector1Text.replace("&key0=$k0Encoded", "&key0=$k63")))
        assertNull(KitMediaMessageV2.parse(vector1Text.replace("&key0=$k0Encoded", "&key0=$k65")))
    }

    @Test
    fun malformed8SizeArithmetic() {
        assertNull(KitMediaMessageV2.parse(vector1Text.replace("&bs0=1088", "&bs0=1089")))
        assertNull(KitMediaMessageV2.parse(vector1Text.replace("&bs0=1088", "&bs0=63")))
        assertNull(
            KitMediaMessageV2.parse(
                vector1Text.replace("&bs0=1088", "&bs0=64").replace("&ps0=1024", "&ps0=0"),
            ),
        )
        assertNull(
            KitMediaMessageV2.parse(
                vector1Text
                    .replace("&bs0=1088", "&bs0=209715264")
                    .replace("&ps0=1024", "&ps0=209715201"),
            ),
        )
        // Noncanonical integers re-encode differently and fail.
        assertNull(KitMediaMessageV2.parse(vector1Text.replace("&bs0=1088", "&bs0=01088")))
    }

    @Test
    fun malformed9AggregateCiphertextCeiling() {
        fun twoItems(ps0: Int, ps1: Int): String {
            fun bsOf(ps: Int): Long = ps.toLong() + 64L - (ps.toLong() % 16L)
            return "KITMEDIA2:v=2&n=2" +
                rawItemGroup(0, bs = bsOf(ps0), ps = ps0) +
                rawItemGroup(1, bs = bsOf(ps1), ps = ps1)
        }
        // 209,715,264 + 58,720,192 = 268,435,456 — exactly the 256 MiB ceiling: accepted.
        assertEquals(
            listOf(209_715_264L, 58_720_192L),
            KitMediaMessageV2.parse(twoItems(209_715_200, 58_720_128))
                ?.items?.map(KitMediaMessageV2Item::ciphertextByteSize),
        )
        // Two maximum items overshoot the aggregate: rejected even though each is legal alone.
        assertNull(KitMediaMessageV2.parse(twoItems(209_715_200, 209_715_200)))
    }

    @Test
    fun malformed10EscapingViolations() {
        // Literal '+' for space.
        assertNull(KitMediaMessageV2.parse(vector1Text.replace("Family%20photos", "Family+photos")))
        // Lowercase hex escape.
        assertNull(KitMediaMessageV2.parse(vector1Text.replace("image%2Fjpeg", "image%2fjpeg")))
        // Raw '/' in a media type.
        assertNull(KitMediaMessageV2.parse(vector1Text.replace("image%2Fjpeg", "image/jpeg")))
        // Truncated and non-hex escapes.
        assertNull(KitMediaMessageV2.parse(vector1Text.replace("Family%20photos", "Family%2")))
        assertNull(KitMediaMessageV2.parse(vector1Text.replace("Family%20photos", "Family%ZZphotos")))
    }

    @Test
    fun malformed11UnsupportedMediaType() {
        assertNull(KitMediaMessageV2.parse(vector1Text.replace("image%2Fjpeg", "image%2Ftiff")))
    }

    @Test
    fun malformed13PrefixAndVersionViolations() {
        assertNull(KitMediaMessageV2.parse(vector1Text + "&"))
        assertNull(KitMediaMessageV2.parse("KITMEDIA2:"))
        assertNull(KitMediaMessageV2.parse(vector1Text.replace("v=2&", "v=1&")))
        assertNull(KitMediaMessageV2.parse(vector1Text.replace("v=2&", "v=3&")))
        assertNull(KitMediaMessageV2.parse("plain text"))
        // Leading whitespace never parses — but it is still family text, so the receive
        // pipeline shows the generic placeholder rather than the raw bytes.
        assertNull(KitMediaMessageV2.parse(" $vector1Text"))
        assertTrue(KitMediaFamily.isFamilyText(" $vector1Text"))
    }

    @Test
    fun familyDetectionCoversEveryGenerationAfterExactEdgeStrip() {
        assertTrue(KitMediaFamily.isFamilyText("KITMEDIA1:v=1&id=x"))
        assertTrue(KitMediaFamily.isFamilyText("KITMEDIA2:"))
        assertTrue(KitMediaFamily.isFamilyText("KITMEDIA9:future"))
        assertTrue(KitMediaFamily.isFamilyText("KITMEDIA10:future"))
        assertTrue(KitMediaFamily.isFamilyText(" " + Char(0x09) + "KITMEDIA3:x "))
        assertFalse(KitMediaFamily.isFamilyText("KITMEDIA:"))
        assertFalse(KitMediaFamily.isFamilyText("xKITMEDIA2:"))
        // U+000E is outside the six-codepoint strip set, so this is ordinary (odd) user text.
        assertFalse(KitMediaFamily.isFamilyText(Char(0x0E) + "KITMEDIA2:x"))
        assertFalse(KitMediaFamily.isFamilyText("kitmedia2:x"))
        assertFalse(KitMediaFamily.isFamilyText("hello securely"))
    }

    @Test
    fun captionNormalizationAndValidation() {
        assertEquals("Family photos", KitMediaMessageV2.normalizeCaption(" Family photos " + Char(0x0A)))
        assertNull(KitMediaMessageV2.normalizeCaption("  " + Char(0x09)))
        assertNull(KitMediaMessageV2.normalizeCaption(null))
        // Normalization strips only the contract set: U+000E edges survive…
        assertEquals(
            Char(0x0E) + "caption",
            KitMediaMessageV2.normalizeCaption(" " + Char(0x0E) + "caption "),
        )
        // …and such a caption is then legal for receivers too.
        assertTrue(KitMediaMessageV2.isValidCaption(Char(0x0E) + "caption"))
        assertTrue(KitMediaMessageV2.isValidCaption("a"))
        assertFalse(KitMediaMessageV2.isValidCaption(""))
        assertFalse(KitMediaMessageV2.isValidCaption(" caption"))
        assertFalse(KitMediaMessageV2.isValidCaption("caption "))
        assertFalse(KitMediaMessageV2.isValidCaption("cap" + Char(0x00) + "tion"))
        assertFalse(KitMediaMessageV2.isValidCaption("a".repeat(2_049)))
    }

    @Test
    fun captionBudgetIsExactAtTheDescriptorCeiling() {
        val items = (0 until 8).map { index ->
            KitMediaMessageV2Item(
                attachmentId = "%08d-0000-4000-8000-00000000000%d".format(index, index),
                storageKey = "%08d-0000-4000-8000-10000000000%d".format(index, index),
                mediaType = "video/mp4",
                ciphertextByteSize = 80,
                ciphertextSha256 = index.toString().repeat(64),
                keyMaterialBase64 = k1,
                plaintextByteSize = 16,
            )
        }
        val remaining = KitMediaMessageV2.remainingEncodedCaptionBudgetBytes(items)
        assertTrue(remaining > 0)
        // Build a caption whose encoded footprint is exactly the remaining budget: two-byte
        // codepoints encode to six bytes, ASCII to one.
        val eAcute = Char(0xE9).toString()
        val multiByteCount = maxOf(0, (remaining - 2_048 + 3) / 4)
        val asciiCount = remaining - 6 * multiByteCount
        val caption = eAcute.repeat(multiByteCount) + "a".repeat(asciiCount)
        assertTrue(caption.toByteArray(StandardCharsets.UTF_8).size <= 2_048)
        assertEquals(remaining, KitMediaMessageV2.encodedCaptionBytes(caption))

        val atCeiling = KitMediaMessageV2(items, caption)
        assertEquals(
            KitMediaMessageV2.MAX_DESCRIPTOR_UTF8_BYTES,
            atCeiling.encode().toByteArray(StandardCharsets.UTF_8).size,
        )
        assertEquals(atCeiling, KitMediaMessageV2.parse(atCeiling.encode()))
        // One more encoded byte crosses the ceiling and must fail to parse.
        val overCeiling = KitMediaMessageV2(items, caption + "a")
        assertNull(KitMediaMessageV2.parse(overCeiling.encode()))
    }
}
