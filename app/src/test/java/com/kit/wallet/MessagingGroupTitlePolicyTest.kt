package com.kit.wallet

import com.kit.wallet.data.remote.isValidMessagingGroupTitle
import com.kit.wallet.data.remote.normalizeMessagingGroupTitle
import com.kit.wallet.feature.chat.isMessagingGroupTitleInputError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagingGroupTitlePolicyTest {
    @Test
    fun `NEXT LINE is trimmed from both title edges`() {
        assertEquals("Title", normalizeMessagingGroupTitle("\u0085Title\u0085"))
        assertFalse(isValidMessagingGroupTitle("\u0085\u0085"))
    }

    @Test
    fun `Unicode separators share the backend edge whitespace policy`() {
        assertEquals(
            "Title",
            normalizeMessagingGroupTitle("\u00a0\u3000Title\u3000\u00a0"),
        )
        assertEquals(
            "Weekend\u0085trip",
            normalizeMessagingGroupTitle("\u0085Weekend\u0085trip\u0085"),
        )
        assertFalse(isValidMessagingGroupTitle("\u00a0\u3000"))
    }

    @Test
    fun `scalar and UTF-8 bounds are measured after normalization`() {
        assertTrue(isValidMessagingGroupTitle("\u0085${"a".repeat(64)}\u3000"))
        assertFalse(isValidMessagingGroupTitle("\u0085${"a".repeat(65)}\u3000"))
        assertTrue(isValidMessagingGroupTitle("\u00a0${"é".repeat(60)}\u0085"))
        assertFalse(isValidMessagingGroupTitle("\u00a0${"é".repeat(61)}\u0085"))
    }

    @Test
    fun `new group screen validates the shared normalized title`() {
        assertFalse(isMessagingGroupTitleInputError("\u0085\u3000"))
        assertFalse(isMessagingGroupTitleInputError("\u0085Title\u3000"))
        assertTrue(isMessagingGroupTitleInputError("\u0085${"a".repeat(65)}\u3000"))
    }
}
