package com.kit.wallet

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SideloadReleaseContractTest {
    @Test
    fun `arm64 property is isolated from the ordinary Play bundle build`() {
        val root = repositoryRoot()
        val buildScript = File(root, "app/build.gradle.kts").readText()
        val workflow = File(root, ".github/workflows/android-ci.yml").readText()

        assertTrue(buildScript.contains("gradleProperty(\"KIT_PAY_SIDELOAD_ABI\").orNull"))
        assertFalse(buildScript.contains("environmentVariable(\"KIT_PAY_SIDELOAD_ABI\")"))
        assertTrue(buildScript.contains("explicitlyRequestedTasks.contains(\"assembleRelease\")"))
        assertTrue(buildScript.contains("task in setOf(\"clean\", \"assembleRelease\")"))
        assertTrue(buildScript.contains("ndk { abiFilters += kitPaySideloadAbi }"))
        assertTrue(buildScript.contains("artifactChannel.set(if (kitPaySideloadAbi == null)"))
        assertTrue(buildScript.contains("nativeAbis.set("))

        val bundlePosition = workflow.indexOf(":app:bundleRelease --stacktrace")
        val sideloadPosition = workflow.indexOf("-PKIT_PAY_SIDELOAD_ABI=arm64-v8a")
        assertTrue(bundlePosition >= 0)
        assertTrue(sideloadPosition > bundlePosition)
    }

    @Test
    fun `release gates bind one arm64 APK to a four ABI Play AAB`() {
        val root = repositoryRoot()
        val verifier = File(root, "fastlane/scripts/verify-release-contents.sh").readText()
        val signer = File(root, "fastlane/scripts/sign-play-artifacts.sh").readText()
        val stager = File(root, "fastlane/scripts/stage-sideload-downloads.sh").readText()
        val validator = File(root, "fastlane/scripts/validate-release-artifacts.py").readText()

        assertTrue(verifier.contains("EXPECTED_SIDELOAD_NATIVE_ENTRIES"))
        assertTrue(verifier.contains("for abi in arm64-v8a armeabi-v7a x86 x86_64"))
        assertTrue(verifier.contains("native-code: 'arm64-v8a'"))
        assertTrue(validator.contains("SIDELOAD_ABIS = [\"arm64-v8a\"]"))
        assertTrue(
            validator.contains(
                "PLAY_ABIS = [\"arm64-v8a\", \"armeabi-v7a\", \"x86\", \"x86_64\"]",
            ),
        )
        assertTrue(signer.contains("-${'$'}{EXPECTED_VERSION_CODE}-arm64.apk"))
        assertTrue(stager.contains("Number of signers: 1"))
        assertTrue(stager.contains("EXPECTED_CERT_SHA256="))
        assertTrue(stager.contains("kit.apk"))
        assertTrue(stager.contains("app.apk"))
        assertTrue(stager.contains("ln \"${'$'}{TEMP_DIR}/kit.apk\" \"${'$'}{TEMP_DIR}/app.apk\""))
    }

    private fun repositoryRoot(): File {
        val workingDirectory = File(System.getProperty("user.dir")).canonicalFile
        return generateSequence(workingDirectory) { it.parentFile }
            .firstOrNull { File(it, "app/build.gradle.kts").isFile }
            ?: error("Could not locate the Android repository root from $workingDirectory")
    }
}
