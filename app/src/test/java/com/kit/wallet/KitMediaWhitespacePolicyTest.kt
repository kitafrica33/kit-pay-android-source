package com.kit.wallet

import com.kit.wallet.data.messaging.KitMediaWhitespacePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract test 9: the media-v2 six-codepoint edge set {U+0009, U+000A, U+000B, U+000C, U+000D,
 * U+0020} is implemented exactly, and no platform-default trim may be substituted for it.
 */
class KitMediaWhitespacePolicyTest {
    private val boundaryCodes = setOf(0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x20)
    private val boundaryEdges = boundaryCodes.sorted().map(::Char).joinToString("")

    @Test
    fun boundarySetIsExactlyTheSixContractCodepoints() {
        for (code in 0x0000..0x30FF) {
            assertEquals(
                "U+%04X".format(code),
                code in boundaryCodes,
                KitMediaWhitespacePolicy.isBoundary(Char(code)),
            )
        }
    }

    @Test
    fun stripRemovesOnlyEdgeCodepointsFromTheSet() {
        assertEquals("caption", KitMediaWhitespacePolicy.strip(boundaryEdges + "caption" + boundaryEdges))
        val interiorNewline = "family" + Char(0x0A) + "photos"
        assertEquals(interiorNewline, KitMediaWhitespacePolicy.strip(" " + interiorNewline + " "))
        assertEquals("", KitMediaWhitespacePolicy.strip(boundaryEdges))
        assertEquals("", KitMediaWhitespacePolicy.strip(""))
        assertEquals("caption", KitMediaWhitespacePolicy.strip("caption"))
    }

    @Test
    fun kotlinTrimIsNotTheContractSet() {
        // U+001C (file separator) and U+2028 (line separator) are Kotlin-whitespace but are NOT
        // in the contract set: trim() would strip them and silently accept captions iOS rejects.
        val fileSeparatorEdged = Char(0x1C) + "caption" + Char(0x1C)
        assertEquals(fileSeparatorEdged, KitMediaWhitespacePolicy.strip(fileSeparatorEdged))
        assertNotEquals(fileSeparatorEdged.trim(), KitMediaWhitespacePolicy.strip(fileSeparatorEdged))
        val lineSeparatorEdged = Char(0x2028) + "caption"
        assertEquals(lineSeparatorEdged, KitMediaWhitespacePolicy.strip(lineSeparatorEdged))
        assertNotEquals(lineSeparatorEdged.trim(), KitMediaWhitespacePolicy.strip(lineSeparatorEdged))
    }

    @Test
    fun asciiControlTrimIsNotTheContractSet() {
        // Java's `String.trim` idiom strips every char up to U+0020; the contract set keeps
        // U+0000 and U+000E (they are invalid captions, and receivers must REJECT, not re-trim).
        val nulEdged = Char(0x00) + "caption"
        val shiftOutEdged = Char(0x0E) + "caption" + Char(0x0E)
        assertEquals(nulEdged, KitMediaWhitespacePolicy.strip(nulEdged))
        assertEquals(shiftOutEdged, KitMediaWhitespacePolicy.strip(shiftOutEdged))
        assertNotEquals(nulEdged.trim { it <= ' ' }, KitMediaWhitespacePolicy.strip(nulEdged))
        assertNotEquals(
            shiftOutEdged.trim { it <= ' ' },
            KitMediaWhitespacePolicy.strip(shiftOutEdged),
        )
    }

    @Test
    fun boundaryEdgeAndContentPredicates() {
        assertTrue(KitMediaWhitespacePolicy.beginsOrEndsWithBoundary(" caption"))
        assertTrue(KitMediaWhitespacePolicy.beginsOrEndsWithBoundary("caption" + Char(0x0D)))
        assertFalse(KitMediaWhitespacePolicy.beginsOrEndsWithBoundary("caption"))
        assertFalse(KitMediaWhitespacePolicy.beginsOrEndsWithBoundary(""))
        // A non-breaking space is outside the set: a caption edged with it is legal content.
        assertFalse(KitMediaWhitespacePolicy.beginsOrEndsWithBoundary(Char(0xA0) + "caption"))

        assertTrue(KitMediaWhitespacePolicy.hasContentOutsideBoundarySet("a"))
        assertTrue(KitMediaWhitespacePolicy.hasContentOutsideBoundarySet(Char(0xA0).toString()))
        assertFalse(KitMediaWhitespacePolicy.hasContentOutsideBoundarySet(boundaryEdges))
        assertFalse(KitMediaWhitespacePolicy.hasContentOutsideBoundarySet(""))
    }
}
