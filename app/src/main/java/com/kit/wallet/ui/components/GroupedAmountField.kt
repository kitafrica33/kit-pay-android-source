package com.kit.wallet.ui.components

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.kit.wallet.ui.model.Money

/**
 * Shows an amount entry grouped in thousands while leaving the stored value untouched.
 *
 * The field's state stays exactly the characters that were typed, so [Money.parseMinor] and the
 * validation built on top of it keep reading an unpunctuated string; only the glyphs gain
 * separators. Rewriting the state instead is what produces the familiar grouped-input defects — a
 * caret that jumps to the end on every keystroke, or a backspace that eats a comma the user never
 * typed — and none of that can happen when the transformation is purely presentational.
 */
object GroupedAmountTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val grouped = Money.groupTypedAmount(raw)
        // Anything that is not a plain amount comes back unchanged, and so does an entry too short
        // to need a separator; both are already their own display form.
        if (grouped == raw) return TransformedText(text, OffsetMapping.Identity)

        val mapping = GroupedOffsetMapping(raw, grouped)
            ?: return TransformedText(text, OffsetMapping.Identity)
        return TransformedText(AnnotatedString(grouped), mapping)
    }
}

/**
 * Maps caret positions across an edit that only ever *inserts* characters.
 *
 * Both directions are derived by walking the two strings together rather than by recomputing where
 * separators fall, so the mapping cannot disagree with what [Money.groupTypedAmount] actually
 * produced. Returns null from [invoke] if the walk does not consume the whole original, which
 * would mean the two strings are not related by insertion and no honest mapping exists.
 */
private class GroupedOffsetMapping private constructor(
    private val originalToTransformed: IntArray,
    private val transformedToOriginal: IntArray,
) : OffsetMapping {
    override fun originalToTransformed(offset: Int): Int =
        originalToTransformed[offset.coerceIn(0, originalToTransformed.lastIndex)]

    override fun transformedToOriginal(offset: Int): Int =
        transformedToOriginal[offset.coerceIn(0, transformedToOriginal.lastIndex)]

    companion object {
        operator fun invoke(raw: String, grouped: String): GroupedOffsetMapping? {
            val forward = IntArray(raw.length + 1)
            val backward = IntArray(grouped.length + 1)
            var consumed = 0
            grouped.forEachIndexed { index, character ->
                backward[index] = consumed
                if (consumed < raw.length && character == raw[consumed]) {
                    forward[consumed] = index
                    consumed++
                }
            }
            if (consumed != raw.length) return null
            forward[raw.length] = grouped.length
            backward[grouped.length] = raw.length
            return GroupedOffsetMapping(forward, backward)
        }
    }
}
