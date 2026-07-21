package com.kit.wallet

import com.kit.wallet.data.session.ProfileSetupState
import com.kit.wallet.data.session.SessionDiskPayload
import com.kit.wallet.data.session.SessionTokens
import com.kit.wallet.data.session.requireValidCredentials
import com.kit.wallet.data.session.toDiskPayload
import com.kit.wallet.data.session.toSessionTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SessionTokensTest {
    private val tokens = SessionTokens(
        accessToken = "access",
        refreshToken = "refresh",
        sessionId = "session",
        accessTokenExpiresAtEpochSeconds = 1_100,
    )

    @Test
    fun `access token respects expiry skew`() {
        assertTrue(tokens.isAccessTokenUsable(nowEpochSeconds = 1_000, clockSkewSeconds = 30))
        assertFalse(tokens.isAccessTokenUsable(nowEpochSeconds = 1_070, clockSkewSeconds = 30))
    }

    @Test
    fun `token without expiry remains usable`() {
        assertTrue(tokens.copy(accessTokenExpiresAtEpochSeconds = null).isAccessTokenUsable(Long.MAX_VALUE))
    }

    @Test
    fun `profile setup state and cache owner survive encrypted payload round trip`() {
        val restored = tokens.copy(
            accountId = "account-1",
            cacheScopeId = "account-1:session",
            profileSetupState = ProfileSetupState.REQUIRED,
        ).toDiskPayload().toSessionTokens()

        assertEquals("account-1", restored.accountId)
        assertEquals("account-1:session", restored.cacheScopeId)
        assertEquals(ProfileSetupState.REQUIRED, restored.profileSetupState)
        assertTrue(restored.profileSetupState.requiresSetup)
    }

    @Test
    fun `legacy payload restores unknown setup state and fails closed`() {
        val restored = SessionDiskPayload(
            accessToken = "access",
            refreshToken = "refresh",
            sessionId = "legacy-session",
            accessTokenExpiresAtEpochSeconds = null,
        ).toSessionTokens()

        assertEquals("legacy-session", restored.cacheScopeId)
        assertEquals(ProfileSetupState.UNKNOWN, restored.profileSetupState)
        assertTrue(restored.profileSetupState.requiresSetup)
    }

    @Test
    fun `credential validation rejects blank access and refresh tokens`() {
        listOf(
            tokens.copy(accessToken = "  "),
            tokens.copy(refreshToken = "\t"),
        ).forEach { invalid ->
            try {
                invalid.requireValidCredentials()
                fail("Expected blank credentials to be rejected")
            } catch (_: IllegalArgumentException) {
                // Expected: save and compare-and-save both use this validation boundary.
            }
        }
    }
}
