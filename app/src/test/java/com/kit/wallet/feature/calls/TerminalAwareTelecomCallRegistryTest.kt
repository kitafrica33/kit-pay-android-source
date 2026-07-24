package com.kit.wallet.feature.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalAwareTelecomCallRegistryTest {
    @Test
    fun `unknown terminal event is a no-op and does not block later tracking`() {
        val registry = registry()

        assertNull(registry.finish("unknown-call", "missed"))
        assertNotNull(registry.track("unknown-call", "Test Caller", TestState.RINGING))

        var connectionCreated = false
        val attached = registry.attachConnection(
            callId = "unknown-call",
            metadata = "Test Caller",
            initialState = TestState.RINGING,
            createConnection = {
                connectionCreated = true
                "telecom-connection"
            },
            prepareLiveConnection = { _, _ -> },
        )
        assertTrue(connectionCreated)
        assertNotNull(attached.liveCall)
        assertNull(attached.terminalDisconnect)
    }

    @Test
    fun `registration failure resolves a racing terminal event without reopening the call`() {
        val registry = registry()
        val callId = "registration-race"
        registry.track(callId, "Flora", TestState.RINGING)

        assertNotNull(registry.finish(callId, "remote"))
        registry.registrationFailed(callId, "platform-error")

        var connectionCreated = false
        val lateAcceptedCallback = registry.attachConnection(
            callId = callId,
            metadata = "Flora",
            initialState = TestState.RINGING,
            createConnection = {
                connectionCreated = true
                "late-connection"
            },
            prepareLiveConnection = { _, _ -> },
        )
        assertFalse(connectionCreated)
        assertEquals("remote", lateAcceptedCallback.terminalDisconnect)
        assertNull(registry.track(callId, "Flora", TestState.RINGING))
    }

    @Test
    fun `registration failure blocks a callback accepted just before Telecom threw`() {
        val registry = registry()
        val callId = "failed-registration"
        registry.track(callId, "Contact", TestState.RINGING)

        registry.registrationFailed(callId, "platform-error")

        var connectionCreated = false
        val lateAcceptedCallback = registry.attachConnection(
            callId = callId,
            metadata = "Contact",
            initialState = TestState.RINGING,
            createConnection = {
                connectionCreated = true
                "late-connection"
            },
            prepareLiveConnection = { _, _ -> },
        )
        assertFalse(connectionCreated)
        assertEquals("platform-error", lateAcceptedCallback.terminalDisconnect)
        assertNull(registry.track(callId, "Contact", TestState.RINGING))

        // A later authoritative terminal event updates the bounded failure marker instead of being
        // treated as an unknown id, covering the opposite ordering of the same race.
        assertNull(registry.finish(callId, "missed"))
        val afterTerminalEvent = registry.attachConnection(
            callId = callId,
            metadata = "Contact",
            initialState = TestState.RINGING,
            createConnection = { "must-not-be-created" },
            prepareLiveConnection = { _, _ -> },
        )
        assertEquals("missed", afterTerminalEvent.terminalDisconnect)
    }

    @Test
    fun `registration failure returns an already attached connection for completion`() {
        val registry = registry()
        val callId = "attached-before-failure"
        registry.track(callId, "Contact", TestState.RINGING)
        registry.attachConnection(
            callId = callId,
            metadata = "Contact",
            initialState = TestState.RINGING,
            createConnection = { "accepted-connection" },
            prepareLiveConnection = { _, _ -> },
        )

        val failed = registry.registrationFailed(callId, "platform-error")

        assertEquals("accepted-connection", failed?.connection)
        assertNull(
            registry.updateState(callId, TestState.ACTIVE) { _, _ ->
                throw AssertionError("A failed connection must no longer receive live state")
            },
        )
        val duplicate = registry.attachConnection(
            callId = callId,
            metadata = "Contact",
            initialState = TestState.RINGING,
            createConnection = { "must-not-be-created" },
            prepareLiveConnection = { _, _ -> },
        )
        assertEquals("platform-error", duplicate.terminalDisconnect)
    }

    @Test
    fun `terminal tombstone survives until delayed connection is resolved`() {
        val registry = registry(maxResolvedTombstones = 1)
        val callId = "delayed-call"

        assertNotNull(registry.track(callId, "Test Caller", TestState.RINGING))
        val finished = registry.finish(callId, "missed")
        assertNotNull(finished)
        assertNull(finished?.connection)

        // Resolved history is bounded, but an unresolved tombstone must never be selected for
        // pruning while Telecom still owes the app its asynchronous createConnection callback.
        repeat(3) { index ->
            val resolvedId = "resolved-$index"
            registry.track(resolvedId, "Contact $index", TestState.RINGING)
            registry.attachConnection(
                callId = resolvedId,
                metadata = "Contact $index",
                initialState = TestState.RINGING,
                createConnection = { "connection-$index" },
                prepareLiveConnection = { _, _ -> },
            )
            registry.finish(resolvedId, "remote")
        }

        var preparedAsLive = false
        val late = registry.attachConnection(
            callId = callId,
            metadata = "Test Caller",
            initialState = TestState.RINGING,
            createConnection = {
                preparedAsLive = true
                "late-connection"
            },
            prepareLiveConnection = { _, _ -> preparedAsLive = true },
        )

        assertFalse(preparedAsLive)
        assertNull(late.liveCall)
        assertEquals("missed", late.terminalDisconnect)
        assertNull(registry.track(callId, "Test Caller", TestState.RINGING))

        // A duplicate platform callback is terminal too; resolving the first callback must not
        // immediately reopen the call id.
        val duplicate = registry.attachConnection(
            callId = callId,
            metadata = "Test Caller",
            initialState = TestState.RINGING,
            createConnection = {
                preparedAsLive = true
                "duplicate-connection"
            },
            prepareLiveConnection = { _, _ -> preparedAsLive = true },
        )
        assertEquals("missed", duplicate.terminalDisconnect)
        assertFalse(preparedAsLive)
    }

    @Test
    fun `finish after connection attachment prevents later state resurrection`() {
        val registry = registry()
        val callId = "connected-call"
        registry.track(callId, "Flora", TestState.RINGING)

        var preparedState: TestState? = null
        val attached = registry.attachConnection(
            callId = callId,
            metadata = "Flora",
            initialState = TestState.RINGING,
            createConnection = { "telecom-connection" },
            prepareLiveConnection = { _, state -> preparedState = state },
        )
        assertNotNull(attached.liveCall)
        assertEquals(TestState.RINGING, preparedState)

        val finished = registry.finish(callId, "remote")
        assertEquals("telecom-connection", finished?.connection)

        var staleStateApplied = false
        val update = registry.updateState(
            callId = callId,
            state = TestState.ACTIVE,
            applyToConnection = { _, _ -> staleStateApplied = true },
        )
        assertNull(update)
        assertFalse(staleStateApplied)

        val duplicate = registry.attachConnection(
            callId = callId,
            metadata = "Flora",
            initialState = TestState.RINGING,
            createConnection = {
                staleStateApplied = true
                "late-duplicate"
            },
            prepareLiveConnection = { _, _ -> staleStateApplied = true },
        )
        assertEquals("remote", duplicate.terminalDisconnect)
        assertFalse(staleStateApplied)
    }

    private fun registry(
        maxResolvedTombstones: Int = 8,
    ) = TerminalAwareTelecomCallRegistry<String, TestState, String, String>(
        maxResolvedTombstones = maxResolvedTombstones,
    )

    private enum class TestState { RINGING, ACTIVE }
}
