package com.kit.wallet

import com.kit.wallet.data.repository.isTrustedDiditVerificationUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiditVerificationUrlTest {
    @Test
    fun `documented Didit session URLs on the exact production host are accepted`() {
        assertTrue(isTrustedDiditVerificationUrl("https://verify.didit.me/session/example"))
        assertTrue(isTrustedDiditVerificationUrl("https://verify.didit.me:443/session/example/"))
        assertTrue(isTrustedDiditVerificationUrl("https://verify.didit.me/en/session/example_123"))
        assertTrue(isTrustedDiditVerificationUrl("https://verify.didit.me/pt-BR/session/example-123"))
    }

    @Test
    fun `alternate origins credentials and ports are rejected`() {
        assertFalse(isTrustedDiditVerificationUrl("https://eu.verify.didit.me/session/example"))
        assertFalse(isTrustedDiditVerificationUrl("https://verification.didit.me/session/example"))
        assertFalse(isTrustedDiditVerificationUrl("http://verify.didit.me/session/example"))
        assertFalse(isTrustedDiditVerificationUrl("https://verify.didit.me.attacker.example/session"))
        assertFalse(isTrustedDiditVerificationUrl("https://user@verify.didit.me/session/example"))
        assertFalse(isTrustedDiditVerificationUrl("https://verify.didit.me:444/session/example"))
    }

    @Test
    fun `query fragments and undocumented paths are rejected`() {
        assertFalse(isTrustedDiditVerificationUrl("https://verify.didit.me/session/example?next=continue"))
        assertFalse(isTrustedDiditVerificationUrl("https://verify.didit.me/session/example#continue"))
        assertFalse(isTrustedDiditVerificationUrl("https://verify.didit.me/verify/example"))
        assertFalse(isTrustedDiditVerificationUrl("https://verify.didit.me/session/short"))
        assertFalse(isTrustedDiditVerificationUrl("https://verify.didit.me/session/%65xample"))
    }
}
