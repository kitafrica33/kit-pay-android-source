package com.kit.wallet

import androidx.compose.ui.text.AnnotatedString
import com.kit.wallet.ui.components.GroupedAmountTransformation
import org.junit.Assert.assertEquals
import org.junit.Test

class GroupedAmountTransformationTest {
    private fun transform(raw: String) = GroupedAmountTransformation.filter(AnnotatedString(raw))

    @Test
    fun `an amount reads grouped while the stored entry stays plain`() {
        assertEquals("25,000", transform("25000").text.text)
        assertEquals("1,284,500", transform("1284500").text.text)
        assertEquals("999", transform("999").text.text)
        assertEquals("", transform("").text.text)
        // The fraction is the user's business; only whole units are punctuated.
        assertEquals("25,000.5", transform("25000.5").text.text)
        assertEquals("25,000.", transform("25000.").text.text)
    }

    @Test
    fun `the caret lands where the digit it was next to went`() {
        val mapping = transform("1284500").offsetMapping
        // Start, end, and each boundary a separator was inserted at.
        assertEquals(0, mapping.originalToTransformed(0))
        assertEquals(2, mapping.originalToTransformed(1))
        assertEquals(6, mapping.originalToTransformed(4))
        assertEquals(9, mapping.originalToTransformed(7))
    }

    @Test
    fun `every caret position survives a round trip in both directions`() {
        listOf("", "5", "999", "1000", "25000", "1284500", "1000.05", "25000.").forEach { raw ->
            val transformed = transform(raw)
            val mapping = transformed.offsetMapping
            (0..raw.length).forEach { offset ->
                assertEquals(
                    "original offset $offset in \"$raw\"",
                    offset,
                    mapping.transformedToOriginal(mapping.originalToTransformed(offset)),
                )
            }
            // And nothing maps outside the string it is meant to index, which is what would
            // crash a text field rather than merely misplace a caret.
            (0..transformed.text.length).forEach { offset ->
                val original = mapping.transformedToOriginal(offset)
                assert(original in 0..raw.length) { "transformed $offset in \"$raw\" -> $original" }
            }
        }
    }

    @Test
    fun `an entry that is not a plain amount is left exactly as typed`() {
        listOf("1e5", "-100", "abc").forEach { raw ->
            val transformed = transform(raw)
            assertEquals(raw, transformed.text.text)
            assertEquals(raw.length, transformed.offsetMapping.originalToTransformed(raw.length))
        }
    }
}
