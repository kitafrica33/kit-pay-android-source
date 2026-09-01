package com.kit.wallet

import com.kit.wallet.data.messaging.MessagingResumableAttachmentCapability
import com.kit.wallet.data.remote.CapabilitiesDto
import com.kit.wallet.data.remote.ResumableAttachmentProtocolDto
import com.kit.wallet.data.remote.ResumableAttachmentProtocolDtoAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagingResumableAttachmentCapabilityTest {
    private val moshi = Moshi.Builder()
        .add(ResumableAttachmentProtocolDtoAdapter())
        .add(KotlinJsonAdapterFactory())
        .build()

    private val contract = ResumableAttachmentProtocolDto(
        ready = true,
        profile = "kit-attachment-upload-v1",
        maxChunkBytes = 5_242_880,
        offsetUnit = "ciphertext_byte",
        chunkDigest = "sha256",
        fullDigest = "sha256",
    )

    @Test
    fun `only the exact resumable ciphertext contract is usable`() {
        assertTrue(MessagingResumableAttachmentCapability.isUsable(contract))
        listOf(
            null,
            contract.copy(ready = false),
            contract.copy(profile = "kit-attachment-upload-v2"),
            contract.copy(maxChunkBytes = 5_242_879),
            contract.copy(offsetUnit = "chunk"),
            contract.copy(chunkDigest = "md5"),
            contract.copy(fullDigest = null),
        ).forEach { advertisement ->
            assertFalse(MessagingResumableAttachmentCapability.isUsable(advertisement))
        }
    }

    @Test
    fun `malformed optional block disables only resumable uploads`() {
        listOf("\"enabled\"", "[1,2]", "17", "null").forEach { block ->
            val decoded = decode(block)
            assertNull(decoded.protocols?.messaging?.resumableAttachments)
            assertEquals(true, decoded.protocols?.messaging?.ready)
            assertEquals(true, decoded.features?.get("messaging"))
        }
    }

    @Test
    fun `wrong typed members decode fail closed without losing messaging`() {
        val decoded = decode(
            """{"ready":"yes","profile":"kit-attachment-upload-v1",
                "max_chunk_bytes":5242880.5,"offset_unit":"ciphertext_byte",
                "chunk_digest":"sha256","full_digest":"sha256"}""",
        )

        val advertisement = decoded.protocols?.messaging?.resumableAttachments
        assertEquals("kit-attachment-upload-v1", advertisement?.profile)
        assertNull(advertisement?.ready)
        assertNull(advertisement?.maxChunkBytes)
        assertFalse(MessagingResumableAttachmentCapability.isUsable(advertisement))
        assertEquals(true, decoded.protocols?.messaging?.ready)
    }

    private fun decode(block: String): CapabilitiesDto {
        val json = """
            {"api_version":"v1","currency":{"code":"UGX","scale":"2"},
            "features":{"messaging":true},"authentication":{},
            "protocols":{"messaging":{"ready":true,"version":"v2","suite":"s",
            "post_quantum":true,"resumable_attachments":$block}}}
        """.trimIndent()
        return checkNotNull(moshi.adapter(CapabilitiesDto::class.java).fromJson(json))
    }
}
