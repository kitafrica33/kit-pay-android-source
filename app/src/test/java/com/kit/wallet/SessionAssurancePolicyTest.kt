package com.kit.wallet

import com.kit.wallet.data.remote.DeviceIdentityAssuranceDto
import com.kit.wallet.data.remote.LoginUnlockAssuranceDto
import com.kit.wallet.data.remote.SessionAssuranceDto
import com.kit.wallet.data.session.CachedSessionAssurance
import com.kit.wallet.feature.auth.grantsFullAccess
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionAssurancePolicyTest {
    @Test fun `full access requires every server assurance dimension`() {
        val full = assurance("verified", "unlocked", "full")
        assertTrue(full.grantsFullAccess())
        assertFalse(assurance("pending", "unlocked", "full").grantsFullAccess())
        assertFalse(assurance("verified", "locked", "full").grantsFullAccess())
        assertFalse(assurance("verified", "unlocked", "restricted").grantsFullAccess())
    }

    @Test fun `dimensions explicitly marked optional do not block full access`() {
        val value = SessionAssuranceDto(
            DeviceIdentityAssuranceDto("pending", false, 1),
            LoginUnlockAssuranceDto("locked", false, emptyList()),
            "full",
        )
        assertTrue(value.grantsFullAccess())
    }

    @Test fun `cached assurance preserves restricted and full access decisions`() {
        val restricted = CachedSessionAssurance(
            "restricted", "verified", true, "locked", true, listOf("pin"),
        )
        assertFalse(restricted.grantsFullAccess())
        assertTrue(
            restricted.copy(access = "full", loginUnlockStatus = "unlocked")
                .grantsFullAccess(),
        )
    }

    private fun assurance(identity: String, unlock: String, access: String) = SessionAssuranceDto(
        DeviceIdentityAssuranceDto(identity, true, 1),
        LoginUnlockAssuranceDto(unlock, true, listOf("pin")),
        access,
    )
}
