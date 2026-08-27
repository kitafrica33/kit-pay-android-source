package com.kit.wallet

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareTargetContractTest {
    @Test
    fun `share relay is visible while asynchronous files are staged`() {
        val root = repositoryRoot()
        val themes = File(root, "app/src/main/res/values/themes.xml").readText()
        val relay = File(
            root,
            "app/src/main/java/com/kit/wallet/ShareRelayActivity.kt",
        ).readText()
        val relayTheme = themes
            .substringAfter("<style name=\"Theme.KitWallet.ShareRelay\"")
            .substringBefore("</style>")

        assertFalse(relayTheme.contains("Theme.NoDisplay"))
        assertTrue(relay.contains("class ShareRelayActivity : ComponentActivity()"))
        assertTrue(relay.contains("withContext(Dispatchers.IO)"))
        assertTrue(relay.contains("Preparing your share"))
    }

    @Test
    fun `system share target accepts single and multiple supported content`() {
        val manifest = File(repositoryRoot(), "app/src/main/AndroidManifest.xml").readText()
        val relayDeclaration = manifest
            .substringAfter("android:name=\".ShareRelayActivity\"")
            .substringBefore("</activity>")

        assertTrue(relayDeclaration.contains("android.intent.action.SEND\""))
        assertTrue(relayDeclaration.contains("android.intent.action.SEND_MULTIPLE\""))
        assertTrue(relayDeclaration.contains("android:mimeType=\"image/*\""))
        assertTrue(relayDeclaration.contains("android:mimeType=\"video/*\""))
        assertTrue(relayDeclaration.contains("android:mimeType=\"audio/*\""))
        assertTrue(relayDeclaration.contains("android:mimeType=\"application/pdf\""))
        assertTrue(relayDeclaration.contains("android:mimeType=\"*/*\""))

        val textFilter = relayDeclaration
            .substringBefore("</intent-filter>")
        assertTrue(textFilter.contains("android.intent.action.SEND_MULTIPLE\""))
        assertTrue(textFilter.contains("android:mimeType=\"text/plain\""))
    }

    @Test
    fun `staging is private durable and cancellation owned`() {
        val root = repositoryRoot()
        val staging = File(
            root,
            "app/src/main/java/com/kit/wallet/feature/chat/IncomingTextShare.kt",
        ).readText()
        val relay = File(
            root,
            "app/src/main/java/com/kit/wallet/ShareRelayActivity.kt",
        ).readText()

        assertTrue(staging.contains("context.noBackupFilesDir"))
        assertTrue(staging.contains("currentCoroutineContext().ensureActive()"))
        assertTrue(relay.contains("val batchId"))
        assertTrue(relay.contains("withContext(NonCancellable + Dispatchers.IO)"))
    }

    @Test
    fun `share staging requires a session and retained partial sends resurface on reopen`() {
        val root = repositoryRoot()
        val relay = File(
            root,
            "app/src/main/java/com/kit/wallet/ShareRelayActivity.kt",
        ).readText()
        val main = File(root, "app/src/main/java/com/kit/wallet/MainActivity.kt").readText()
        val onNewIntent = main
            .substringAfter("override fun onNewIntent(intent: Intent)")
            .substringBefore("override fun onSaveInstanceState")

        assertTrue(relay.contains("if (owner == null)"))
        assertTrue(relay.contains("owner = owner"))
        assertTrue(onNewIntent.contains("restoreRetainedTextShares()"))
        assertTrue(
            onNewIntent.indexOf("restoreRetainedTextShares()") <
                onNewIntent.indexOf("handleIntent(intent)"),
        )
    }

    private fun repositoryRoot(): File {
        val workingDirectory = File(checkNotNull(System.getProperty("user.dir"))).canonicalFile
        return generateSequence(workingDirectory) { it.parentFile }
            .firstOrNull { File(it, "app/build.gradle.kts").isFile }
            ?: error("Could not locate the Android repository root from $workingDirectory")
    }
}
