package com.kit.wallet

import com.kit.wallet.data.auth.isTrustedTotpProvisioningUri
import com.kit.wallet.data.auth.normalizeMfaFactorCode
import com.kit.wallet.data.auth.normalizeSixDigitCode
import com.kit.wallet.feature.settings.requireReturnedRecoveryCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class MfaSecurityTest {
    @Test
    fun `six digit verification accepts only the exact numeric contract`() {
        assertEquals("123456", normalizeSixDigitCode(" 123456 "))
        assertNull(normalizeSixDigitCode("12345678"))
        assertNull(normalizeSixDigitCode("12345a"))
    }

    @Test
    fun `MFA factor accepts and normalizes one time recovery codes`() {
        assertEquals("123456", normalizeMfaFactorCode("123456"))
        assertEquals(
            "A1B2C3D4E5F60718293A",
            normalizeMfaFactorCode("a1b2-c3d4-e5f6-0718-293a"),
        )
        assertNull(normalizeMfaFactorCode("12345678"))
        assertNull(normalizeMfaFactorCode("ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ"))
        assertNull(normalizeMfaFactorCode("A1B2-C3D4"))
    }

    @Test
    fun `authenticator deep link is bound to TOTP parameters and returned secret`() {
        val secret = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP"
        val valid = "otpauth://totp/Kit%20Pay%3Aamina%40example.test" +
            "?secret=$secret&issuer=Kit%20Pay&algorithm=SHA1&digits=6&period=30"

        assertTrue(isTrustedTotpProvisioningUri(valid, secret))
        assertFalse(isTrustedTotpProvisioningUri(valid.replace("otpauth", "https"), secret))
        assertFalse(isTrustedTotpProvisioningUri(valid.replace("totp", "hotp"), secret))
        assertFalse(isTrustedTotpProvisioningUri(valid.replace(secret, "WRONGSECRET"), secret))
        assertFalse(isTrustedTotpProvisioningUri("$valid&secret=$secret", secret))
        assertFalse(isTrustedTotpProvisioningUri("$valid#unexpected", secret))
    }

    @Test
    fun `MFA recovery code responses must contain at least one usable code`() {
        assertEquals(
            listOf("CODE-ONE", "CODE-TWO"),
            requireReturnedRecoveryCodes(listOf(" CODE-ONE ", "", "  ", "CODE-TWO")),
        )
        assertThrows(IllegalStateException::class.java) { requireReturnedRecoveryCodes(null) }
        assertThrows(IllegalStateException::class.java) {
            requireReturnedRecoveryCodes(listOf("", "  "))
        }
    }
}
