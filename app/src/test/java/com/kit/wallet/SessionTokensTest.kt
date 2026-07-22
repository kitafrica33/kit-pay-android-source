package com.kit.wallet

import com.kit.wallet.data.session.ProfileSetupState
import com.kit.wallet.data.session.SessionDiskPayload
import com.kit.wallet.data.session.SessionTokens
import com.kit.wallet.data.session.SecureMessagingResetProofFence
import com.kit.wallet.data.session.decodeSessionPersistingLegacyNonce
import com.kit.wallet.data.session.requireValidCredentials
import com.kit.wallet.data.session.restoreRetainingEncryptedSession
import com.kit.wallet.data.session.toDiskPayload
import com.kit.wallet.data.session.toSessionTokens
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SessionTokensTest {
    private val diskAdapter = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
        .adapter(SessionDiskPayload::class.java)
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
        assertEquals(tokens.refreshReplayNonce, restored.refreshReplayNonce)
        assertTrue(restored.profileSetupState.requiresSetup)
    }

    @Test
    fun `pending and proved messaging reset fences survive persisted JSON round trip`() {
        val pending = SecureMessagingResetProofFence(
            serverDeviceId = "device-1",
            previousEnrollmentEpoch = 7,
            previousRegistrationId = 42,
            previousIdentityKeySha256 = "1".repeat(64),
            previousBundleVersion = 3,
        )

        listOf(
            pending,
            pending.copy(resultingEnrollmentEpoch = 8),
        ).forEach { resetFence ->
            val encoded = diskAdapter.toJson(
                tokens.copy(messagingResetProof = resetFence).toDiskPayload(),
            )
            val restored = checkNotNull(diskAdapter.fromJson(encoded))
                .toSessionTokens()

            assertEquals(resetFence, restored.messagingResetProof)
            assertEquals(resetFence.proved, restored.messagingResetProof?.proved)
        }
    }

    @Test
    fun `release shrinker keeps the complete reflective session payload graph`() {
        val rules = File(repositoryRoot(), "app/proguard-rules.pro").readLines()
            .map(String::trim)
        listOf(
            "-keep class com.kit.wallet.data.session.SessionDiskPayload { *; }",
            "-keep class com.kit.wallet.data.session.SecureMessagingResetProofFence { *; }",
        ).forEach { requiredRule ->
            assertEquals(1, rules.count { it == requiredRule })
        }
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
        assertTrue(restored.refreshReplayNonce.isNotBlank())
    }

    @Test
    fun `legacy replay nonce is durable and identical across process starts`() {
        var durableSession = diskAdapter.toJson(
            SessionDiskPayload(
                accessToken = "legacy-access",
                refreshToken = "legacy-refresh",
                sessionId = "legacy-session",
                accessTokenExpiresAtEpochSeconds = null,
            ),
        )
        var migrations = 0

        fun restoreAfterProcessStart(): SessionTokens = decodeSessionPersistingLegacyNonce(
            encryptedSession = durableSession,
            decodePayload = { checkNotNull(diskAdapter.fromJson(it)) },
            encodePayload = diskAdapter::toJson,
            persistEncryptedSession = {
                migrations++
                durableSession = it
                true
            },
        )

        val firstProcess = restoreAfterProcessStart()
        val secondProcess = restoreAfterProcessStart()

        assertEquals(firstProcess.refreshReplayNonce, secondProcess.refreshReplayNonce)
        assertEquals(1, migrations)
        assertEquals(
            firstProcess.refreshReplayNonce,
            checkNotNull(diskAdapter.fromJson(durableSession)).refreshReplayNonce,
        )
    }

    @Test
    fun `failed legacy nonce persistence keeps restoration pending without credentials`() {
        val retainedSession = diskAdapter.toJson(
            SessionDiskPayload(
                accessToken = "legacy-access",
                refreshToken = "legacy-refresh",
                sessionId = "legacy-session",
                accessTokenExpiresAtEpochSeconds = null,
            ),
        )

        val restore = restoreRetainingEncryptedSession(
            encryptedSession = retainedSession,
            messagingErasurePending = false,
            decode = { encrypted ->
                decodeSessionPersistingLegacyNonce(
                    encryptedSession = encrypted,
                    decodePayload = { checkNotNull(diskAdapter.fromJson(it)) },
                    encodePayload = diskAdapter::toJson,
                    persistEncryptedSession = { false },
                )
            },
        )

        assertEquals(null, restore.tokens)
        assertTrue(restore.pending)
        assertTrue(restore.recoveryRequired)
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

    private fun repositoryRoot(): File {
        val workingDirectory = File(checkNotNull(System.getProperty("user.dir"))).canonicalFile
        return generateSequence(workingDirectory) { it.parentFile }
            .firstOrNull { File(it, "app/build.gradle.kts").isFile }
            ?: error("Could not locate the Android repository root from $workingDirectory")
    }
}
