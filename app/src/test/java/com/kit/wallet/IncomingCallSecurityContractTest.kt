package com.kit.wallet

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingCallSecurityContractTest {
    @Test
    fun `incoming call capability crosses a non exported relay and never a public uri`() {
        val root = repositoryRoot()
        val manifest = source(root, "app/src/main/AndroidManifest.xml")
        val relayDeclaration = manifest
            .substringAfter("android:name=\".IncomingCallRelayActivity\"")
            .substringBefore("/>")
        val receiver = source(
            root,
            "app/src/main/java/com/kit/wallet/data/notifications/DefaultPushEnvelopeReceiver.kt",
        )
        val main = source(root, "app/src/main/java/com/kit/wallet/MainActivity.kt")
        val publicRouter = main
            .substringAfter("internal fun Intent.takeKitDeepLink()")
            .substringBefore("private val CALL_PAYLOAD_KEYS")

        assertTrue(relayDeclaration.contains("android:exported=\"false\""))
        assertTrue(receiver.contains("IncomingCallRelayActivity.intent("))
        assertTrue(receiver.contains("IncomingCallLaunchPurpose.ANSWER"))
        assertTrue(publicRouter.contains("isUntrustedCallRoute"))
        assertFalse(publicRouter.contains("IncomingCallPayload.fromDeepLink"))
        assertFalse(publicRouter.contains("IncomingCallPayload.fromData"))
    }

    @Test
    fun `keyguard visibility waits for an opaque frame and supports api 26`() {
        val root = repositoryRoot()
        val main = source(root, "app/src/main/java/com/kit/wallet/MainActivity.kt")
        val navigation = source(root, "app/src/main/java/com/kit/wallet/navigation/KitApp.kt")
        val install = main
            .substringAfter("private fun installAuthorizedIncomingCall(")
            .substringBefore("private fun clearAuthorizedIncomingCall")
        val cover = main
            .substringAfter("private fun IncomingCallPrivacyCover(")
            .substringBefore("private fun SessionRestorationGate")

        assertFalse(install.contains("setIncomingCallKeyguardVisibility(true)"))
        assertTrue(cover.split("withFrameNanos").size - 1 >= 2)
        assertTrue(main.contains("Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1"))
        assertTrue(main.contains("FLAG_SHOW_WHEN_LOCKED"))
        assertTrue(main.contains("FLAG_TURN_SCREEN_ON"))
        val incomingRoute = navigation
            .substringAfter("route = Dest.INCOMING_CALL")
            .substringBefore("// --- Settings ---")
        assertTrue(incomingRoute.split("withFrameNanos").size - 1 >= 2)
        assertTrue(incomingRoute.contains("onAuthorizedIncomingCallSurfaceChanged(callId, false)"))
        assertTrue(main.contains("setIncomingCallKeyguardVisibility(false)"))
    }

    @Test
    fun `slow authoritative validation leaves the ringing surfaces alive`() {
        val source = source(
            repositoryRoot(),
            "app/src/main/java/com/kit/wallet/feature/calls/ActiveCallViewModel.kt",
        )
        val validation = source
            .substringAfter("private fun validateIncomingCall()")
            .substringBefore("private fun applyContactPresentation")

        assertFalse(validation.contains("NotificationManager"))
        assertFalse(validation.contains("ringDeadlines.cancel"))
        assertFalse(validation.contains("telecom.finish"))
    }

    @Test
    fun `successful in app answer durably retires the ringing identity`() {
        val source = source(
            repositoryRoot(),
            "app/src/main/java/com/kit/wallet/feature/calls/ActiveCallViewModel.kt",
        )
        val acceptedResponse = source
            .substringAfter("A successful answer response ends the incoming ringing window")
            .substringBefore("mutableState.value = mutableState.value.copy")

        assertTrue(acceptedResponse.contains("closeRingWindow(session.callId)"))

        val termination = source
            .substringAfter("private fun terminate(reason: String)")
            .substringBefore("private fun markConnected")
        assertTrue(termination.contains("closeRingWindow(telecomCallId)"))

        val ringWindow = source
            .substringAfter("private fun closeRingWindow(callId: String?)")
            .substringBefore("\n}\n\nprivate const val OUTGOING_CALL_LAUNCH_CLAIMED")
        assertTrue(ringWindow.contains("ringDeadlines.retire(canonicalIncomingId)"))
    }

    @Test
    fun `active duplicate is suppressed before the durable replay admission`() {
        val receiver = source(
            repositoryRoot(),
            "app/src/main/java/com/kit/wallet/data/notifications/DefaultPushEnvelopeReceiver.kt",
        )
        val incoming = receiver
            .substringAfter("val activeCallId = activeCallState.activeCallId.value")
            .substringBefore("val deliveryPlan")

        assertTrue(incoming.contains("if (activeCallId == call.callId) return"))
        assertTrue(
            incoming.indexOf("activeCallId == call.callId") <
                incoming.indexOf("replayLedger.admitRing"),
        )
    }

    private fun source(root: File, path: String): String = File(root, path).readText()

    private fun repositoryRoot(): File {
        val workingDirectory = File(checkNotNull(System.getProperty("user.dir"))).canonicalFile
        return generateSequence(workingDirectory) { it.parentFile }
            .firstOrNull { File(it, "app/build.gradle.kts").isFile }
            ?: error("Could not locate the Android repository root from $workingDirectory")
    }
}
