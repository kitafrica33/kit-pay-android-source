package com.kit.wallet

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneCallLogIntegrationContractTest {
    @Test
    fun `self managed Telecom integration is declared without restricted call log permissions`() {
        val root = repositoryRoot()
        val manifest = File(root, "app/src/main/AndroidManifest.xml").readText()
        val bridge = File(
            root,
            "app/src/main/java/com/kit/wallet/feature/calls/KitTelecomBridge.kt",
        ).readText()

        assertTrue(manifest.contains("android.permission.MANAGE_OWN_CALLS"))
        assertTrue(manifest.contains("android.permission.BIND_TELECOM_CONNECTION_SERVICE"))
        assertTrue(manifest.contains("android.telecom.ConnectionService"))
        assertFalse(manifest.contains("android.permission.READ_CALL_LOG"))
        assertFalse(manifest.contains("android.permission.WRITE_CALL_LOG"))
        assertTrue(bridge.contains("Build.VERSION.SDK_INT >= Build.VERSION_CODES.P"))
        assertTrue(bridge.contains("PhoneAccount.EXTRA_LOG_SELF_MANAGED_CALLS"))
        assertTrue(bridge.contains("PhoneAccount.CAPABILITY_SELF_MANAGED"))
        assertTrue(bridge.contains("Manifest.permission.MANAGE_OWN_CALLS"))
        assertTrue(bridge.contains("catch (_: SecurityException)"))
        assertTrue(bridge.contains("[1-8]"))
        assertTrue(bridge.contains("Laravel's HasUuids emits UUIDv7"))
    }

    private fun repositoryRoot(): File {
        val workingDirectory = File(System.getProperty("user.dir")).canonicalFile
        return generateSequence(workingDirectory) { it.parentFile }
            .firstOrNull { File(it, "app/build.gradle.kts").isFile }
            ?: error("Could not locate the Android repository root from $workingDirectory")
    }
}
