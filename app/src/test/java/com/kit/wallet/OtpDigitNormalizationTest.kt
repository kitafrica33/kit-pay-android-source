package com.kit.wallet

import com.kit.wallet.feature.auth.normalizedOtpDigits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whatever an SMS autofill, a paste, a hardware keyboard, or the keypad delivers, the
 * stored one-time code must be the ASCII digits the server issued. A localized digit that
 * merely *renders* like the code would fill all six boxes and then fail verification.
 */
class OtpDigitNormalizationTest {

    @Test
    fun `localized decimal digits normalize to their ascii values`() {
        assertEquals("427193", normalizedOtpDigits("٤٢٧١٩٣", 6)) // Arabic-Indic
        assertEquals("012345", normalizedOtpDigits("०१२३४५", 6)) // Devanagari
        assertEquals("987654", normalizedOtpDigits("９８７６５４", 6)) // fullwidth
        assertEquals("407193", normalizedOtpDigits("4０7١9３", 6)) // mixed in one paste
    }

    @Test
    fun `everything that is not a decimal digit is dropped`() {
        assertEquals("427193", normalizedOtpDigits("Your Kit Pay code is 427-193.", 6))
        // Numeric-looking characters outside Unicode Nd carry no decimal value: vulgar
        // fractions, superscripts, and Roman numerals must vanish, not smuggle a value.
        assertEquals("", normalizedOtpDigits("½ ² Ⅷ abc —", 6))
        assertEquals("", normalizedOtpDigits("", 6))
    }

    @Test
    fun `the code is capped at the requested length`() {
        assertEquals("123456", normalizedOtpDigits("123456789", 6))
        assertEquals("42", normalizedOtpDigits("٤٢", 6))
    }

    @Test
    fun `the stored code is always plain ascii digits`() {
        val stored = normalizedOtpDigits("٤２７1９3٠٠", 6)
        assertEquals("427193", stored)
        assertTrue(stored.all { it in '0'..'9' })
    }
}
