package com.kit.wallet.feature.calls

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CallMediaOperationsTest {
    @Test
    fun `hangup fences late native capture and waits before final disconnect`() = runTest {
        val operations = CallMediaOperations()
        val nativeCapture = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        var cameraAcknowledged = false
        operations.open()
        assertTrue(operations.launch(this) { isCurrent ->
            // A native capture callback can complete even after coroutine cancellation.
            withContext(NonCancellable) {
                nativeCapture.await()
                events += "capture published"
                if (isCurrent()) cameraAcknowledged = true
            }
        })
        runCurrent()

        val retired = operations.retire()
        events += "immediate disconnect"
        val cleanup = launch {
            retired?.join()
            events += "final disconnect"
        }
        runCurrent()
        assertFalse(cleanup.isCompleted)
        assertFalse(operations.launch(this) { events += "unexpected capture" })
        assertEquals(listOf("immediate disconnect"), events)

        nativeCapture.complete(Unit)
        runCurrent()
        assertTrue(cleanup.isCompleted)
        assertFalse(cameraAcknowledged)
        assertEquals(listOf("immediate disconnect", "capture published", "final disconnect"), events)
    }

    @Test
    fun `a replacement call cannot open while retired native capture is still finishing`() = runTest {
        val operations = CallMediaOperations()
        val nativeCapture = CompletableDeferred<Unit>()
        operations.open()
        operations.launch(this) { withContext(NonCancellable) { nativeCapture.await() } }
        runCurrent()
        val retired = operations.retire()
        assertThrows(IllegalStateException::class.java) { operations.open() }
        nativeCapture.complete(Unit)
        retired?.join()

        operations.open()
        var current = false
        assertTrue(operations.launch(this) { isCurrent -> current = isCurrent() })
        runCurrent()
        assertTrue(current)
    }

    @Test
    fun `repeated control taps are rejected until the acknowledged change completes`() = runTest {
        val operations = CallMediaOperations()
        val permissionResult = CompletableDeferred<Unit>()
        var changes = 0
        operations.open()
        assertTrue(operations.launch(this) {
            permissionResult.await()
            changes++
        })
        assertFalse(operations.launch(this) { changes++ })
        runCurrent()
        permissionResult.complete(Unit)
        runCurrent()
        assertTrue(operations.launch(this) { changes++ })
        runCurrent()
        assertEquals(2, changes)
    }
}
