package com.kit.wallet

import com.kit.wallet.data.messaging.KitReactionAction
import com.kit.wallet.data.messaging.KitReactionMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KitReactionMessageTest {
    private val descriptor = KitReactionMessage(
        targetMessageId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
        emoji = "👍",
        action = KitReactionAction.ADD,
    )

    @Test
    fun roundTripsThroughDeterministicEncoding() {
        val encoded = descriptor.encode()
        assertTrue(KitReactionMessage.isReactionText(encoded))
        assertEquals(descriptor, KitReactionMessage.parse(encoded))
        // Deterministic bytes keep retry text equality intact.
        assertEquals(encoded, KitReactionMessage.parse(encoded)?.encode())

        val removal = descriptor.copy(action = KitReactionAction.REMOVE)
        assertEquals(removal, KitReactionMessage.parse(removal.encode()))
    }

    @Test
    fun carriesNoReactorIdentityOnTheWire() {
        // Attribution comes from the authenticated Signal sender, so the descriptor names nobody.
        val encoded = descriptor.encode()
        assertEquals(
            "KITRXN1:v=1&a=add&t=aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa&e=%F0%9F%91%8D",
            encoded,
        )
    }

    @Test
    fun plainTextIsNotMistakenForAReaction() {
        assertNull(KitReactionMessage.parse("hello securely"))
        assertFalse(KitReactionMessage.isReactionText("hello securely"))
        assertFalse(KitReactionMessage.isReactionText("👍"))
    }

    @Test
    fun rejectsMalformedDescriptors() {
        val encoded = descriptor.encode()
        assertNull(KitReactionMessage.parse(encoded.replace("v=1", "v=2")))
        assertNull(KitReactionMessage.parse(KitReactionMessage.PREFIX + "not-fields"))
        assertNull(KitReactionMessage.parse("$encoded&future=value"))
        assertNull(KitReactionMessage.parse(encoded.replace("&a=add", "")))
        assertNull(KitReactionMessage.parse(encoded.replace("a=add", "a=toggle")))
        // Reordered fields have no canonical encoding, so they are not a second way to say this.
        assertNull(
            KitReactionMessage.parse(
                KitReactionMessage.PREFIX + encoded.removePrefix(KitReactionMessage.PREFIX)
                    .split('&')
                    .reversed()
                    .joinToString("&"),
            ),
        )
        // Duplicate keys cannot be used to shadow an earlier value.
        assertNull(KitReactionMessage.parse("$encoded&a=remove"))
        // Alternate escaping of an accepted emoji is still a second representation.
        assertNull(KitReactionMessage.parse(encoded.replace("%F0%9F%91%8D", "%f0%9f%91%8d")))
        // Non-canonical target identifiers fail closed.
        assertNull(
            KitReactionMessage.parse(
                descriptor.copy(targetMessageId = "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA").encode(),
            ),
        )
        assertNull(KitReactionMessage.parse(descriptor.copy(targetMessageId = "not-a-uuid").encode()))
    }

    @Test
    fun `accepts pictographic sequences and rejects smuggled text`() {
        // ZWJ sequences, skin-tone modifiers, variation selectors and keycaps are all reactions.
        listOf("👍", "❤️", "🙏🏽", "1️⃣", "#️⃣", "🇰🇪")
            .forEach { assertTrue(it, KitReactionMessage.isAcceptableReaction(it)) }
        assertFalse(KitReactionMessage.isAcceptableReaction("👨‍👩‍👧‍👦"))

        // The shared contract deliberately does not maintain an evolving emoji allowlist.
        listOf("ok", "!", "~", "*", "👍\u0000", "👍\u202e")
            .forEach { assertTrue(it, KitReactionMessage.isAcceptableReaction(it)) }

        // Empty, whitespace-bearing and over-four-scalar tokens are rejected.
        listOf(
            "",
            "https://kit.africa",
            "👍 👍",
            "👍\n",
            "👍\u0085",
            "abcde",
        ).forEach { assertFalse(it, KitReactionMessage.isAcceptableReaction(it)) }
    }

    @Test
    fun `emoji bounds are shared with iOS`() {
        val atCodePointLimit = "👍".repeat(KitReactionMessage.MAX_EMOJI_CODE_POINTS)
        assertTrue(KitReactionMessage.isAcceptableReaction(atCodePointLimit))
        assertFalse(KitReactionMessage.isAcceptableReaction(atCodePointLimit + "👍"))

        // The byte bound is the outer of the two and no code point costs more than four bytes, so
        // the code-point bound is what actually bites. Both are asserted because iOS applies the
        // same pair, and loosening either one alone must not silently widen what is accepted.
        assertTrue(
            atCodePointLimit.toByteArray(Charsets.UTF_8).size <=
                KitReactionMessage.MAX_EMOJI_UTF8_BYTES,
        )

        assertNull(KitReactionMessage.parse(descriptor.copy(emoji = "not an emoji").encode()))
        assertNull(KitReactionMessage.parse(descriptor.copy(emoji = "").encode()))
        assertNull(KitReactionMessage.parse(descriptor.copy(emoji = atCodePointLimit + "👍").encode()))
    }

    @Test
    fun `user authored text cannot enter the reaction namespace`() {
        assertTrue(KitReactionMessage.beginsWithReservedPrefix(descriptor.encode()))
        assertTrue(KitReactionMessage.beginsWithReservedPrefix(KitReactionMessage.PREFIX))
        // Leading whitespace must not smuggle a descriptor past a composer that trims later.
        assertTrue(KitReactionMessage.beginsWithReservedPrefix("   ${descriptor.encode()}"))
        assertTrue(KitReactionMessage.beginsWithReservedPrefix("\n\t${KitReactionMessage.PREFIX}x"))

        assertFalse(KitReactionMessage.beginsWithReservedPrefix("Sounds good"))
        assertFalse(KitReactionMessage.beginsWithReservedPrefix("see KITREACT1: for details"))
    }

    @Test
    fun `percent encoding matches the cross-platform golden safe set`() {
        assertEquals(
            "KITRXN1:v=1&a=add&t=aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa&e=*%EF%B8%8F",
            descriptor.copy(emoji = "*️").encode(),
        )
        assertEquals(
            "KITRXN1:v=1&a=add&t=aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa&e=%7E%F0%9F%91%8D",
            descriptor.copy(emoji = "~👍").encode(),
        )
    }

    @Test
    fun `reaction tokens use one NFC wire representation`() {
        val decomposed = "e\u0301"
        val composed = "é"
        assertEquals(descriptor.copy(emoji = composed).encode(), descriptor.copy(emoji = decomposed).encode())
        assertEquals(composed, KitReactionMessage.parse(descriptor.copy(emoji = decomposed).encode())?.emoji)

        val nonCanonicalWire =
            "KITRXN1:v=1&a=add&t=aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa&e=e%CC%81"
        assertNull(KitReactionMessage.parse(nonCanonicalWire))
    }
}
