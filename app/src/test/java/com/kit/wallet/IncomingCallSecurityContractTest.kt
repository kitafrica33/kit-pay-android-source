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
        val relay = source(root, "app/src/main/java/com/kit/wallet/IncomingCallRelayActivity.kt")
        val themes = source(root, "app/src/main/res/values/themes.xml")
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
        val relayTheme = themes
            .substringAfter("name=\"Theme.KitWallet.CallRelay\"")
            .substringBefore("</style>")
        assertFalse(relayTheme.contains("Theme.NoDisplay"))
        assertTrue(relayTheme.contains("@color/kit_navy"))
        assertTrue(relayTheme.contains("android:windowIsTranslucent\">false"))
        val relayCreate = relay
            .substringAfter("override fun onCreate")
            .substringBefore("companion object")
        assertTrue(relayCreate.indexOf("if (token == null)") >= 0)
        assertTrue(
            relayCreate.indexOf("if (token == null)") <
                relayCreate.indexOf("showAuthorizedCallOverKeyguard()"),
        )
        assertTrue(
            relayCreate.indexOf("showAuthorizedCallOverKeyguard()") <
                relayCreate.indexOf("startActivity("),
        )
        assertTrue(relay.contains("setShowWhenLocked(true)"))
        assertTrue(relay.contains("setTurnScreenOn(true)"))
        assertTrue(relay.contains("FLAG_SHOW_WHEN_LOCKED"))
        assertTrue(relay.contains("FLAG_TURN_SCREEN_ON"))
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

        assertTrue(acceptedResponse.contains("closeRingWindow("))
        assertTrue(acceptedResponse.contains("session.callId"))
        assertTrue(acceptedResponse.contains("IncomingCallRetirementDisposition.ANSWERED_ELSEWHERE"))

        val termination = source
            .substringAfter("private fun terminate(reason: String)")
            .substringBefore("private fun markConnected")
        assertTrue(
            termination.contains(
                "closeRingWindow(telecomCallId, disconnect.ringRetirementDisposition())",
            ),
        )

        val ringWindow = source
            .substringAfter("private fun closeRingWindow(")
            .substringBefore("\n}\n\nprivate fun KitTelecomDisconnect")
        assertTrue(ringWindow.contains("ringDeadlines.retire(canonicalIncomingId, disposition)"))
    }

    @Test
    fun `answer attempt claims Telecom and retires notification before network acceptance`() {
        val source = source(
            repositoryRoot(),
            "app/src/main/java/com/kit/wallet/feature/calls/ActiveCallViewModel.kt",
        )
        val accept = source
            .substringAfter("fun accept(requestedVideo: Boolean)")
            .substringBefore("private fun connect(requestedVideo: Boolean)")

        assertTrue(accept.contains("telecom.markAnswering(incomingCallId)"))
        assertTrue(accept.contains("closeRingWindow("))
        assertTrue(accept.contains("incomingCallId"))
        assertTrue(
            accept.indexOf("telecom.markAnswering(incomingCallId)") <
                accept.indexOf("closeRingWindow("),
        )
        assertTrue(
            accept.indexOf("closeRingWindow(") <
                accept.indexOf("connect(requestedVideo)"),
        )

        val main = source(
            root = repositoryRoot(),
            path = "app/src/main/java/com/kit/wallet/MainActivity.kt",
        )
        val trustedLaunch = main
            .substringAfter("if (launch.acceptRequested)")
            .substringBefore("installAuthorizedIncomingCall")
        assertTrue(trustedLaunch.contains("claimAuthorizedIncomingCallAnswer"))
    }

    @Test
    fun `incoming ring lifetime never consults the device wall clock`() {
        val root = repositoryRoot()
        val sources = listOf(
            "app/src/main/java/com/kit/wallet/MainActivity.kt",
            "app/src/main/java/com/kit/wallet/IncomingCallRelayActivity.kt",
            "app/src/main/java/com/kit/wallet/data/notifications/CallRingDeadlineCoordinator.kt",
            "app/src/main/java/com/kit/wallet/data/notifications/DefaultPushEnvelopeReceiver.kt",
            "app/src/main/java/com/kit/wallet/data/notifications/IncomingCallLaunchAuthorization.kt",
            "app/src/main/java/com/kit/wallet/data/notifications/IncomingCallReplayLedger.kt",
        ).joinToString("\n") { source(root, it) }

        assertFalse(sources.contains("Instant.now"))
        assertFalse(sources.contains("java.time.Clock"))
        assertTrue(sources.contains("ElapsedRealtimeClock"))
        assertTrue(sources.contains("BootSessionIdProvider"))
    }

    @Test
    fun `telecom answer stays pre active until authenticated media connects`() {
        val root = repositoryRoot()
        val telecom = source(
            root,
            "app/src/main/java/com/kit/wallet/feature/calls/KitTelecomBridge.kt",
        )
        val onAnswer = telecom
            .substringAfter("override fun onAnswer(videoState: Int)")
            .substringBefore("override fun onReject()")
        val systemAnswered = telecom
            .substringAfter("internal fun systemAnswered")
            .substringBefore("internal fun systemDeclined")
        val markAnswering = telecom
            .substringAfter("fun markAnswering")
            .substringBefore("/** A sibling answer")
        val activeCall = source(
            root,
            "app/src/main/java/com/kit/wallet/feature/calls/ActiveCallViewModel.kt",
        )
        val connected = activeCall
            .substringAfter("private fun markConnected()")
            .substringBefore("private fun publishPresence()")

        assertFalse(onAnswer.contains("setActive()"))
        assertTrue(onAnswer.contains("bridge.systemAnswered"))
        assertTrue(systemAnswered.contains("markAnswering(callId)"))
        assertTrue(markAnswering.contains("TelecomCallState.ANSWERING"))
        assertTrue(telecom.contains("TelecomCallState.ANSWERING -> setInitializing()"))
        assertTrue(connected.contains("telecom::markActive"))
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

    @Test
    fun `every published ring is reconciled against retirement after its deadline is armed`() {
        val receiver = source(
            repositoryRoot(),
            "app/src/main/java/com/kit/wallet/data/notifications/DefaultPushEnvelopeReceiver.kt",
        )
        val publication = receiver
            .substringAfter("private fun showIncomingCall(")
            .substringBefore("private fun reconcileIncomingCallPublication")
        val callWaiting = publication
            .substringAfter(
                "if (deliveryPlan.notificationSurface == " +
                    "IncomingCallNotificationSurface.CALL_WAITING)",
            )
            .substringBefore("val ringtoneUri")
        val ordinary = publication.substringAfter("val published = runCatching")

        listOf(callWaiting, ordinary).forEach { branch ->
            assertTrue(branch.contains("ringDeadlines.schedule(call.callId, ringLease)"))
            assertTrue(branch.contains("reconcileIncomingCallPublication(call.callId, expiresAt)"))
            assertTrue(
                branch.indexOf("ringDeadlines.schedule(call.callId, ringLease)") <
                    branch.indexOf("reconcileIncomingCallPublication(call.callId, expiresAt)"),
            )
        }

        val reconciliation = receiver
            .substringAfter("private fun reconcileIncomingCallPublication")
            .substringBefore("private fun callAlertSettingsIntent")
        assertTrue(reconciliation.contains("replayLedger.publicationAuthorization"))
        assertTrue(reconciliation.contains("ringDeadlines::retire"))
        assertTrue(reconciliation.contains("telecom.finishRinging"))
    }

    private fun source(root: File, path: String): String = File(root, path).readText()

    private fun repositoryRoot(): File {
        val workingDirectory = File(checkNotNull(System.getProperty("user.dir"))).canonicalFile
        return generateSequence(workingDirectory) { it.parentFile }
            .firstOrNull { File(it, "app/build.gradle.kts").isFile }
            ?: error("Could not locate the Android repository root from $workingDirectory")
    }
}
