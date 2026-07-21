package com.kit.wallet

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseVersionSingleSourceTest {
    @Test
    fun `android default config is the sole release version source`() {
        val buildScript = File(repositoryRoot(), "app/build.gradle.kts").readText()

        assertTrue(hasExactlyOneLiteralCandidateVersion(buildScript))
        assertFalse(buildScript.contains("kitPayVersionName"))
        assertFalse(buildScript.contains("kitPayVersionCode"))
        assertTrue(buildScript.contains("versionNameValue.set(configuredVersionName)"))
        assertTrue(buildScript.contains("versionCodeValue.set(configuredVersionCode)"))
    }

    @Test
    fun `duplicate candidate version mutation is rejected`() {
        val buildScript = File(repositoryRoot(), "app/build.gradle.kts").readText()
        val duplicated = buildScript + "\nversionCode = 999\nversionName = \"9.9.9\"\n"

        assertFalse(hasExactlyOneLiteralCandidateVersion(duplicated))
    }

    private fun hasExactlyOneLiteralCandidateVersion(source: String): Boolean {
        val names = Regex("^\\s*versionName = \\\"[^\\\"]+\\\"\\s*$", RegexOption.MULTILINE)
            .findAll(source)
            .count()
        val codes = Regex("^\\s*versionCode = [1-9][0-9]*\\s*$", RegexOption.MULTILINE)
            .findAll(source)
            .count()
        return names == 1 && codes == 1
    }

    private fun repositoryRoot(): File {
        val workingDirectory = File(System.getProperty("user.dir")).canonicalFile
        return generateSequence(workingDirectory) { it.parentFile }
            .firstOrNull { File(it, "app/build.gradle.kts").isFile }
            ?: error("Could not locate the Android repository root from $workingDirectory")
    }
}
