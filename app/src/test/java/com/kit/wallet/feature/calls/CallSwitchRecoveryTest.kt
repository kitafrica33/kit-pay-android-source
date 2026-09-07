package com.kit.wallet.feature.calls

import com.kit.wallet.data.repository.CallConnection
import com.kit.wallet.data.repository.CallRepository
import com.kit.wallet.data.repository.CallStatus
import com.kit.wallet.ui.model.CallEntry
import com.kit.wallet.data.session.SessionFence
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CallSwitchRecoveryTest {
    @Test fun `lost answer response tombstones waiting call before restoring original`() = runTest {
        val server = Server()
        val restored = CallSwitchRecovery.cancelUncertainAnswer(server, "original", "waiting", owner)
        assertEquals(listOf("end:waiting", "status:original", "resume:original:7:null:null"), server.requests)
        assertEquals("fresh-generation", restored.token)
        assertFalse(server.lateAcceptCanJoin())
        assertTrue(server.owners.all { it == owner })
    }

    @Test fun `failed cancellation never restores media with a delayed accept still possible`() = runTest {
        val server = Server().apply { endFails = true }
        assertTrue(runCatching { CallSwitchRecovery.cancelUncertainAnswer(server, "original", "waiting", owner) }
            .exceptionOrNull() is IOException)
        assertEquals(listOf("end:waiting"), server.requests)
    }

    @Test fun `swap recovery binds both current revisions and preserves both logical calls`() = runTest {
        val server = Server()
        CallSwitchRecovery.restoreUncertainSwap(server, "original", "waiting", owner)
        assertEquals(listOf("status:original", "status:waiting", "resume:original:7:waiting:12"), server.requests)
        assertTrue(server.lateAcceptCanJoin()) // Neither existing participant was ended.
        assertEquals(8L, server.originalRevision)
        assertFalse(server.delayedSwapCanHoldOriginal(expectedRevision = 7))
    }

    @Test fun `ended secondary is omitted from restored call transaction`() = runTest {
        val server = Server().apply { waitingEnded = true }
        CallSwitchRecovery.restoreUncertainSwap(server, "original", "waiting", owner)
        assertEquals("resume:original:7:null:null", server.requests.last())
    }

    @Test fun `missing authoritative revision fails closed before resume`() = runTest {
        val server = Server().apply { omitRevision = true }
        assertTrue(runCatching { CallSwitchRecovery.restoreUncertainSwap(server, "original", "waiting", owner) }.isFailure)
        assertEquals(listOf("status:original", "status:waiting"), server.requests)
    }

    @Test fun `successful waiting tombstone survives a later restoration failure`() = runTest {
        val server = Server().apply { omitRevision = true }
        var cancelled = false
        assertTrue(runCatching {
            CallSwitchRecovery.cancelUncertainAnswer(server, "original", "waiting", owner) { cancelled = true }
        }.isFailure)
        assertTrue(cancelled)
        assertFalse(server.lateAcceptCanJoin())
        assertEquals(listOf("end:waiting", "status:original"), server.requests)
    }

    @Test fun `system audio return resumes only interruption holds and never during a switch`() {
        assertTrue(CallHoldPolicy.mayAutoResume(CallHoldReason.INTERRUPTION, true, false))
        for (reason in listOf(null, CallHoldReason.MANUAL, CallHoldReason.WAITING)) {
            assertFalse(CallHoldPolicy.mayAutoResume(reason, true, false))
        }
        assertFalse(CallHoldPolicy.mayAutoResume(CallHoldReason.INTERRUPTION, false, false))
        assertFalse(CallHoldPolicy.mayAutoResume(CallHoldReason.INTERRUPTION, true, true))
    }

    @Test fun `camera requires original consent permission and a foreground surface`() {
        assertFalse(CallHoldPolicy.restoreCamera(true, true, false))
        assertFalse(CallHoldPolicy.restoreCamera(true, false, true))
        assertFalse(CallHoldPolicy.restoreCamera(false, true, true))
        assertTrue(CallHoldPolicy.restoreCamera(true, true, true))
    }

    @Test fun `explicit system unhold resumes manual hold only after audio returns`() {
        assertTrue(CallHoldPolicy.mayResume(CallHoldReason.MANUAL, true, false, true))
        assertFalse(CallHoldPolicy.mayResume(CallHoldReason.MANUAL, false, false, true))
        assertFalse(CallHoldPolicy.mayResume(CallHoldReason.MANUAL, true, true, true))
        assertFalse(CallHoldPolicy.mayResume(CallHoldReason.MANUAL, true, false, false))
    }

    @Test fun `explicit unhold cannot resume an absent hold`() {
        assertFalse(CallHoldPolicy.mayResume(null, true, false, true))
    }

    private val owner = SessionFence("session", "scope", null)

    private class Server : CallRepository {
        override val calls = MutableStateFlow(emptyList<CallEntry>())
        override suspend fun refresh() = Unit
        val requests = mutableListOf<String>()
        val owners = mutableListOf<SessionFence?>()
        var endFails = false
        var waitingEnded = false
        var omitRevision = false
        var originalRevision = 7L
        override suspend fun end(callId: String, reason: String) {
            requests += "end:$callId"
            if (endFails) throw IOException("offline")
            waitingEnded = true
        }
        override suspend fun end(callId: String, reason: String, expectedOwner: SessionFence) {
            owners += expectedOwner
            end(callId, reason)
        }
        override suspend fun status(callId: String, expectedOwner: SessionFence?): CallStatus {
            owners += expectedOwner
            requests += "status:$callId"
            return CallStatus(callId, if (callId == "waiting" && waitingEnded) "ended" else "active",
                "joined", true, if (omitRevision) null else if (callId == "original") originalRevision else 12,
                true, emptyList())
        }
        override suspend fun resume(callId: String, revision: Long?, holdCallId: String?, holdCallRevision: Long?, expectedOwner: SessionFence?): CallConnection {
            owners += expectedOwner
            requests += "resume:$callId:$revision:$holdCallId:$holdCallRevision"
            check(revision == originalRevision)
            originalRevision++
            return CallConnection(callId, "Contact", video = false, provider = "livekit",
                url = "wss://rtc.test", token = "fresh-generation", room = "room")
        }
        fun lateAcceptCanJoin() = !waitingEnded
        fun delayedSwapCanHoldOriginal(expectedRevision: Long) = expectedRevision == originalRevision
    }
}
