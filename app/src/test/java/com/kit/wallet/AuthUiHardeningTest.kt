package com.kit.wallet

import com.kit.wallet.data.remote.DeviceDto
import com.kit.wallet.feature.auth.isResendCooldownElapsed
import com.kit.wallet.feature.auth.phoneResendDeadline
import com.kit.wallet.feature.settings.canRevokeDevice
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthUiHardeningTest {
    @Test
    fun `missing phone resend metadata gets a conservative cooldown`() {
        val now = 1_000L
        val viewModel = source(
            "app/src/main/java/com/kit/wallet/feature/auth/AuthViewModel.kt",
        )

        assertEquals(
            61_000L,
            phoneResendDeadline(nowElapsedRealtimeMillis = now, resendAfterSeconds = null),
        )
        assertEquals(
            now,
            phoneResendDeadline(nowElapsedRealtimeMillis = now, resendAfterSeconds = 0L),
        )
        assertFalse(isResendCooldownElapsed(null, now))
        assertFalse(isResendCooldownElapsed(61_000L, 60_999L))
        assertTrue(isResendCooldownElapsed(61_000L, 61_000L))
        assertTrue(viewModel.contains("AuthChallengeKind.PHONE_OTP -> phoneResendDeadline"))
    }

    @Test
    fun `only an explicitly non-current device can be revoked`() {
        val unknown = device(isCurrent = null)
        val screen = source(
            "app/src/main/java/com/kit/wallet/feature/settings/SecurityScreen.kt",
        )

        assertFalse(canRevokeDevice(unknown))
        assertFalse(canRevokeDevice(unknown.copy(isCurrent = true)))
        assertTrue(canRevokeDevice(unknown.copy(isCurrent = false)))
        assertTrue(screen.contains("canRevokeDevice(device) -> Text"))
    }

    @Test
    fun `OTP entry state is bound to both challenge id and kind`() {
        val otp = source("app/src/main/java/com/kit/wallet/feature/auth/OtpScreen.kt")
        val navigation = source("app/src/main/java/com/kit/wallet/navigation/KitApp.kt")

        listOf("code", "recoveryCode", "submittedCode", "useRecoveryCode").forEach { field ->
            assertTrue(
                "$field must reset when the challenge identity changes",
                otp.contains("var $field by remember(challengeId, challengeKind)"),
            )
        }
        assertTrue(navigation.contains("challengeId = challenge?.id"))
        assertTrue(navigation.contains("challengeKind = challenge?.kind"))
        assertTrue(otp.contains("Verification expires in"))
        assertTrue(otp.contains("onChallengeUnavailable(challengeId)"))
        assertTrue(otp.contains("BackHandler"))
        assertTrue(otp.contains("IconButton(onClick = onBack, enabled = !loading)"))
        assertTrue(navigation.contains("challengeExpiresAtElapsedRealtimeMillis ="))
        assertTrue(navigation.contains("clearUnavailableChallenge(routeChallengeId)"))
    }

    @Test
    fun `passwords and email OTP are never saveable compose state`() {
        val accountAccess = source(
            "app/src/main/java/com/kit/wallet/feature/auth/AccountAccessScreens.kt",
        )
        val phoneLogin = source(
            "app/src/main/java/com/kit/wallet/feature/auth/PhoneLoginScreen.kt",
        )
        val settings = source(
            "app/src/main/java/com/kit/wallet/feature/settings/SettingsScreen.kt",
        )
        val saveablePassword = Regex(
            """var\s+(?:password|confirmation)\s+by\s+rememberSaveable""",
        )

        assertFalse(saveablePassword.containsMatchIn(accountAccess))
        assertFalse(saveablePassword.containsMatchIn(phoneLogin))
        assertFalse(
            Regex("""var\s+code\s+by\s+rememberSaveable""").containsMatchIn(settings),
        )
    }

    @Test
    fun `Didit request transports explicit consent without a hardcoded grant`() {
        val screen = source("app/src/main/java/com/kit/wallet/feature/settings/KycScreen.kt")
        val viewModel = source(
            "app/src/main/java/com/kit/wallet/feature/settings/KycViewModel.kt",
        )
        val repository = source(
            "app/src/main/java/com/kit/wallet/data/repository/RemoteKycRepository.kt",
        )

        assertTrue(screen.contains("viewModel.startVerification(consented)"))
        assertTrue(viewModel.contains("kyc.startVerification(consent)"))
        assertTrue(repository.contains("require(consent)"))
        assertTrue(repository.contains("consent = consent"))
        assertFalse(repository.contains("consent = true"))
    }

    private fun device(isCurrent: Boolean?) = DeviceDto(
        id = "device-id",
        name = "Phone",
        platform = "android",
        isCurrent = isCurrent,
    )

    private fun source(relativePath: String): String = File(repositoryRoot(), relativePath).readText()

    private fun repositoryRoot(): File {
        val workingDirectory = File(
            requireNotNull(System.getProperty("user.dir")),
        ).canonicalFile
        return generateSequence(workingDirectory) { it.parentFile }
            .firstOrNull { File(it, "app/build.gradle.kts").isFile }
            ?: error("Could not locate the Android repository root from $workingDirectory")
    }
}
