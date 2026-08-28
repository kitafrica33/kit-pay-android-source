package com.kit.wallet

import com.kit.wallet.data.messaging.MessagingMediaMessageV2Capability
import com.kit.wallet.data.remote.CapabilitiesDto
import com.kit.wallet.data.remote.MediaMessageProtocolDto
import com.kit.wallet.data.remote.MediaMessageProtocolDtoAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Frozen-contract coverage for the `protocols.messaging.media_message` capability block
 * (KITMEDIA2 §5a, conformance test 11): the decode must be tolerant and isolated — a malformed
 * block turns exactly one feature off, never the whole capabilities document — and the coherence
 * rules must fail closed on structure while clamping capacity instead of comparing it.
 */
class MessagingMediaMessageV2CapabilityTest {
    // Assembled the way `StorageModule` assembles it, custom adapter first: the reflective
    // factory would otherwise throw on the first wrong-typed member and take the whole
    // capabilities response with it, which is exactly what the contract forbids.
    private val moshi = Moshi.Builder()
        .add(MediaMessageProtocolDtoAdapter())
        .add(KotlinJsonAdapterFactory())
        .build()

    /** The §5a block verbatim: the advertisement the server emits once its predicate holds. */
    private val contractAdvertisement = MediaMessageProtocolDto(
        ready = true,
        profile = "kit-media-v2",
        maxAttachments = 8,
        maxDescriptorBytes = 7_680,
        maxCaptionUtf8Bytes = 2_048,
        minAttachmentCiphertextBytes = 64,
        maxAttachmentCiphertextBytes = 209_715_264,
        maxAggregateCiphertextBytes = 268_435_456,
    )

    @Test
    fun `the exact contract advertisement is usable at its own limits`() {
        val limits = MessagingMediaMessageV2Capability.limitsFor(contractAdvertisement)

        assertNotNull(limits)
        assertEquals(8, limits!!.maxAttachments)
        assertEquals(7_680, limits.maxDescriptorUtf8Bytes)
        assertEquals(2_048, limits.maxCaptionUtf8Bytes)
        assertEquals(209_715_264L, limits.maxAttachmentCiphertextBytes)
        assertEquals(268_435_456L, limits.maxAggregateCiphertextBytes)
        assertTrue(MessagingMediaMessageV2Capability.isUsable(contractAdvertisement))
    }

    @Test
    fun `absent not-ready and wrong-profile advertisements are off`() {
        assertFalse(MessagingMediaMessageV2Capability.isUsable(null))
        assertFalse(
            MessagingMediaMessageV2Capability.isUsable(contractAdvertisement.copy(ready = false)),
        )
        assertFalse(
            MessagingMediaMessageV2Capability.isUsable(contractAdvertisement.copy(ready = null)),
        )
        assertFalse(
            MessagingMediaMessageV2Capability.isUsable(
                contractAdvertisement.copy(profile = "kit-media-v1"),
            ),
        )
        assertFalse(
            MessagingMediaMessageV2Capability.isUsable(contractAdvertisement.copy(profile = null)),
        )
    }

    @Test
    fun `the envelope floor is exact and every capacity member is required`() {
        listOf(
            contractAdvertisement.copy(minAttachmentCiphertextBytes = 63),
            contractAdvertisement.copy(minAttachmentCiphertextBytes = 65),
            contractAdvertisement.copy(minAttachmentCiphertextBytes = null),
            contractAdvertisement.copy(maxAttachments = null),
            contractAdvertisement.copy(maxDescriptorBytes = null),
            contractAdvertisement.copy(maxCaptionUtf8Bytes = null),
            contractAdvertisement.copy(maxAttachmentCiphertextBytes = null),
            contractAdvertisement.copy(maxAggregateCiphertextBytes = null),
            // A cap that cannot carry even one minimal two-item album is incoherent, not small.
            contractAdvertisement.copy(maxAttachments = 1),
            contractAdvertisement.copy(maxDescriptorBytes = 0),
            contractAdvertisement.copy(maxCaptionUtf8Bytes = 0),
            contractAdvertisement.copy(maxAttachmentCiphertextBytes = 64),
            contractAdvertisement.copy(maxAggregateCiphertextBytes = 127),
        ).forEach { advertisement ->
            assertFalse(
                "expected off: $advertisement",
                MessagingMediaMessageV2Capability.isUsable(advertisement),
            )
        }
    }

    @Test
    fun `higher advertisements clamp to the compiled ceilings instead of failing closed`() {
        val limits = MessagingMediaMessageV2Capability.limitsFor(
            contractAdvertisement.copy(
                maxAttachments = 12,
                maxDescriptorBytes = 1_000_000,
                maxCaptionUtf8Bytes = 1_000_000,
                maxAttachmentCiphertextBytes = Long.MAX_VALUE,
                maxAggregateCiphertextBytes = Long.MAX_VALUE,
            ),
        )

        assertNotNull(limits)
        assertEquals(8, limits!!.maxAttachments)
        assertEquals(7_680, limits.maxDescriptorUtf8Bytes)
        assertEquals(2_048, limits.maxCaptionUtf8Bytes)
        assertEquals(209_715_264L, limits.maxAttachmentCiphertextBytes)
        assertEquals(268_435_456L, limits.maxAggregateCiphertextBytes)
    }

    @Test
    fun `lower advertisements are obeyed verbatim`() {
        val limits = MessagingMediaMessageV2Capability.limitsFor(
            contractAdvertisement.copy(
                maxAttachments = 4,
                maxDescriptorBytes = 4_096,
                maxCaptionUtf8Bytes = 1_024,
                maxAttachmentCiphertextBytes = 10L * 1024L * 1024L,
                maxAggregateCiphertextBytes = 20L * 1024L * 1024L,
            ),
        )

        assertNotNull(limits)
        assertEquals(4, limits!!.maxAttachments)
        assertEquals(4_096, limits.maxDescriptorUtf8Bytes)
        assertEquals(1_024, limits.maxCaptionUtf8Bytes)
        assertEquals(10L * 1024L * 1024L, limits.maxAttachmentCiphertextBytes)
        assertEquals(20L * 1024L * 1024L, limits.maxAggregateCiphertextBytes)
    }

    @Test
    fun `the contract capabilities document decodes to a usable advertisement`() {
        val capabilities = decodeCapabilities(
            mediaMessageBlock = """
                {"profile":"kit-media-v2","ready":true,"max_attachments":8,
                "max_descriptor_bytes":7680,"max_caption_utf8_bytes":2048,
                "min_attachment_ciphertext_bytes":64,"max_attachment_ciphertext_bytes":209715264,
                "max_aggregate_ciphertext_bytes":268435456,"future_unknown_key":"ignored"}
            """.trimIndent(),
        )

        val advertisement = capabilities.protocols?.messaging?.mediaMessage
        assertEquals(contractAdvertisement, advertisement)
        assertTrue(MessagingMediaMessageV2Capability.isUsable(advertisement))
    }

    @Test
    fun `a malformed block turns one feature off and never the document`() {
        listOf(
            "\"bogus\"",
            "[1,2,3]",
            "17",
            "null",
        ).forEach { block ->
            val capabilities = decodeCapabilities(mediaMessageBlock = block)

            assertNull(
                "block $block must decode to null",
                capabilities.protocols?.messaging?.mediaMessage,
            )
            assertFalse(
                MessagingMediaMessageV2Capability.isUsable(
                    capabilities.protocols?.messaging?.mediaMessage,
                ),
            )
            // Isolation: everything around the malformed block stays usable.
            assertEquals(true, capabilities.features?.get("messaging"))
            assertEquals("kit-media-v1", capabilities.protocols?.messaging?.richMedia?.profile)
            assertEquals(true, capabilities.protocols?.messaging?.ready)
        }
    }

    @Test
    fun `wrong-typed members inside the block leave the feature off without throwing`() {
        val capabilities = decodeCapabilities(
            mediaMessageBlock = """
                {"profile":"kit-media-v2","ready":"yes","max_attachments":"eight",
                "max_descriptor_bytes":7680.5,"max_caption_utf8_bytes":2048,
                "min_attachment_ciphertext_bytes":64,"max_attachment_ciphertext_bytes":209715264,
                "max_aggregate_ciphertext_bytes":268435456}
            """.trimIndent(),
        )

        val advertisement = capabilities.protocols?.messaging?.mediaMessage
        assertNotNull(advertisement)
        assertNull(advertisement!!.ready)
        assertNull(advertisement.maxAttachments)
        assertNull(advertisement.maxDescriptorBytes)
        assertFalse(MessagingMediaMessageV2Capability.isUsable(advertisement))
        assertEquals("kit-media-v1", capabilities.protocols?.messaging?.richMedia?.profile)
    }

    private fun decodeCapabilities(mediaMessageBlock: String): CapabilitiesDto {
        val json = """
            {"api_version":"v1","currency":{"code":"UGX","scale":"2"},
            "features":{"messaging":true,"messaging_media_message_v2":true},
            "authentication":{},
            "protocols":{"messaging":{"ready":true,"version":"v2","suite":"s","post_quantum":true,
            "rich_media":{"ready":true,"profile":"kit-media-v1","minimum_ciphertext_bytes":64,
            "maximum_plaintext_bytes":209715200,"maximum_ciphertext_bytes":209715264,
            "media_types":["image/jpeg"]},
            "media_message":$mediaMessageBlock}}}
        """.trimIndent()
        return checkNotNull(moshi.adapter(CapabilitiesDto::class.java).fromJson(json))
    }
}
