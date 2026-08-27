package com.kit.wallet

import com.kit.wallet.data.remote.AttachMessagingConversationPhotoRequest
import com.kit.wallet.data.remote.MAX_GROUP_DESCRIPTION_LENGTH
import com.kit.wallet.data.remote.MAX_GROUP_DESCRIPTION_UTF8_BYTES
import com.kit.wallet.data.remote.UpdateMessagingConversationRequest
import com.kit.wallet.data.remote.UpdateMessagingConversationRequestAdapter
import com.kit.wallet.data.remote.isValidMessagingGroupDescription
import com.kit.wallet.data.remote.normalizeMessagingGroupDescription
import com.kit.wallet.data.remote.truncateMessagingGroupDescription
import com.kit.wallet.feature.chat.boundedMessagingGroupDescriptionInput
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagingGroupIdentityPolicyTest {
    private val moshi = Moshi.Builder()
        .add(UpdateMessagingConversationRequestAdapter())
        .add(KotlinJsonAdapterFactory())
        .build()

    @Test
    fun `description trims the shared edge whitespace set and keeps interior newlines`() {
        assertEquals(
            "What we ship\nand when",
            normalizeMessagingGroupDescription("　What we ship\nand when "),
        )
        // A newline at the edge is padding like any other; one inside is a paragraph break.
        assertEquals("a\nb", normalizeMessagingGroupDescription("\na\nb\n"))
    }

    @Test
    fun `control and bidirectional override characters are stripped wherever they appear`() {
        assertEquals("safetxt.exe", normalizeMessagingGroupDescription("safe‮txt.exe"))
        assertEquals("ab", normalizeMessagingGroupDescription("a‏⁦b"))
        assertEquals("ab", normalizeMessagingGroupDescription("a\u0007b"))
        assertEquals("ab", normalizeMessagingGroupDescription("a\u0000b"))
    }

    @Test
    fun `scalar and UTF-8 bounds are measured after normalization`() {
        assertTrue(
            isValidMessagingGroupDescription(
                "　${"a".repeat(MAX_GROUP_DESCRIPTION_LENGTH)} ",
            ),
        )
        assertFalse(
            isValidMessagingGroupDescription("a".repeat(MAX_GROUP_DESCRIPTION_LENGTH + 1)),
        )
        // 400 three-byte scalars fit the scalar cap and break the byte cap.
        assertTrue(400 * 3 > MAX_GROUP_DESCRIPTION_UTF8_BYTES)
        assertFalse(isValidMessagingGroupDescription("€".repeat(400)))
        assertFalse(isValidMessagingGroupDescription("　 "))
    }

    @Test
    fun `truncation and editor bounding respect scalar boundaries`() {
        val truncated = truncateMessagingGroupDescription("a".repeat(600))
        assertEquals(MAX_GROUP_DESCRIPTION_LENGTH, truncated.length)
        // In-progress trailing whitespace survives while the canonical core still fits.
        assertEquals("Weekly ", boundedMessagingGroupDescriptionInput("Weekly "))
        val bounded = boundedMessagingGroupDescriptionInput("b".repeat(700))
        assertEquals(MAX_GROUP_DESCRIPTION_LENGTH, bounded.length)
    }

    @Test
    fun `the update request insists on canonical bounded descriptions`() {
        UpdateMessagingConversationRequest(description = null)
        UpdateMessagingConversationRequest(description = "Ships weekly")
        assertThrows(IllegalArgumentException::class.java) {
            UpdateMessagingConversationRequest(description = " padded ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            UpdateMessagingConversationRequest(
                description = "a".repeat(MAX_GROUP_DESCRIPTION_LENGTH + 1),
            )
        }
    }

    @Test
    fun `a cleared description travels as an explicit JSON null`() {
        val adapter = moshi.adapter(UpdateMessagingConversationRequest::class.java)
        assertEquals(
            """{"description":null}""",
            adapter.toJson(UpdateMessagingConversationRequest(description = null)),
        )
        assertEquals(
            """{"description":"Ships weekly"}""",
            adapter.toJson(UpdateMessagingConversationRequest(description = "Ships weekly")),
        )
    }

    @Test
    fun `a group photo attaches only by canonical asset id`() {
        AttachMessagingConversationPhotoRequest(
            assetId = "3b47a1f0-90c7-4b7e-8f3c-2f4a5b6c7d8e",
        )
        assertThrows(IllegalArgumentException::class.java) {
            AttachMessagingConversationPhotoRequest(assetId = "not-an-asset")
        }
        assertThrows(IllegalArgumentException::class.java) {
            // Uppercase is how a forged or hand-built id most often arrives.
            AttachMessagingConversationPhotoRequest(
                assetId = "3B47A1F0-90C7-4B7E-8F3C-2F4A5B6C7D8E",
            )
        }
    }
}
