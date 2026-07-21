package com.kit.wallet

import com.kit.wallet.data.local.SecureMessagingRecordEntity
import com.kit.wallet.data.messaging.messagingRecordAad
import com.kit.wallet.data.messaging.SecureMessagingSessionLifecycle
import com.kit.wallet.data.messaging.SecureMessagingLifecycleGate
import com.kit.wallet.data.messaging.SecureMessagingLifecycleGuard
import com.kit.wallet.data.messaging.SecureMessagingIncomingNotification
import com.kit.wallet.data.messaging.SecureMessagingIncomingNotificationSink
import com.kit.wallet.data.messaging.SecureMessagingStateEraser
import com.kit.wallet.data.messaging.SecureMessagingStateWrite
import com.kit.wallet.data.messaging.eraseMessagingKeyAndRecords
import com.kit.wallet.data.session.eraseMessagingThenClearSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureMessagingStorageContractTest {
    @Test
    fun `state write consumes and wipes its retained plaintext exactly once`() {
        val callerBytes = byteArrayOf(11, 22, 33, 44)
        val write = SecureMessagingStateWrite(
            namespace = "session:one",
            recordKey = "record:one",
            expectedVersion = null,
            bytes = callerBytes,
        )
        callerBytes.fill(0)

        assertEquals(listOf<Byte>(11, 22, 33, 44), write.copyBytes().toList())
        val consumed = write.consumeBytes()
        assertEquals(listOf<Byte>(11, 22, 33, 44), consumed.toList())
        assertTrue(runCatching { write.copyBytes() }.exceptionOrNull() is IllegalStateException)
        assertTrue(runCatching { write.consumeBytes() }.exceptionOrNull() is IllegalStateException)
        consumed.fill(0)
    }

    @Test
    fun `namespace enumeration is denied outside an active lifecycle gate`() = runTest {
        val gate = SecureMessagingLifecycleGate()
        var encryptedRowsQueried = false

        val denied = runCatching {
            gate.withOperation { encryptedRowsQueried = true }
        }.exceptionOrNull()

        assertTrue(denied is IllegalStateException)
        assertFalse(encryptedRowsQueried)
        gate.open()
        gate.withOperation { encryptedRowsQueried = true }
        assertTrue(encryptedRowsQueried)
    }

    @Test
    fun `room record exposes only encrypted bytes and authenticated routing metadata`() {
        val fields = SecureMessagingRecordEntity::class.java.declaredFields.map { it.name }.toSet()
        assertTrue(fields.containsAll(setOf("namespace", "recordKey", "version", "iv", "ciphertext")))
        assertFalse(fields.any { it in setOf("plaintext", "text", "body", "privateKey", "sessionState") })
    }

    @Test
    fun `aad binds ciphertext to namespace key and monotonic version`() {
        val original = messagingRecordAad("user:one", "session:2", 7)
        assertNotEquals(original.toList(), messagingRecordAad("user:two", "session:2", 7).toList())
        assertNotEquals(original.toList(), messagingRecordAad("user:one", "session:3", 7).toList())
        assertNotEquals(original.toList(), messagingRecordAad("user:one", "session:2", 8).toList())
    }

    @Test
    fun `session lifecycle erases state on logout and before a new local session`() = runTest {
        var erasures = 0
        var activations = 0
        var notificationCancellations = 0
        val lifecycle = SecureMessagingSessionLifecycle(
            object : SecureMessagingStateEraser {
                override suspend fun eraseAll() {
                    erasures++
                }

                override suspend fun allowForActiveSession() {
                    activations++
                }
            },
            SecureMessagingLifecycleGuard(),
            object : SecureMessagingIncomingNotificationSink {
                override fun publish(notification: SecureMessagingIncomingNotification) = Unit

                override fun cancelAll() {
                    notificationCancellations++
                }
            },
        )

        lifecycle.beforeSessionSave(isSameSession = false)
        lifecycle.beforeSessionSave(isSameSession = true)
        lifecycle.afterSessionSave()
        lifecycle.beforeSessionClear()

        assertEquals(2, erasures)
        assertEquals(1, activations)
        assertEquals(2, notificationCancellations)
    }

    @Test
    fun `local session is removed even when messaging erasure fails`() = runTest {
        val erasureFailure = IllegalStateException("unavailable keystore")
        var sessionCleared = false
        var erasureSucceededAtClear: Boolean? = null

        val observed = runCatching {
            eraseMessagingThenClearSession(
                eraseMessaging = { throw erasureFailure },
                clearSession = { succeeded ->
                    sessionCleared = true
                    erasureSucceededAtClear = succeeded
                },
            )
        }.exceptionOrNull()

        assertTrue(sessionCleared)
        assertEquals(false, erasureSucceededAtClear)
        assertSame(erasureFailure, observed)
    }

    @Test
    fun `session cleanup completes after messaging erasure attempt`() = runTest {
        val events = mutableListOf<String>()

        eraseMessagingThenClearSession(
            eraseMessaging = { events += "erase" },
            clearSession = { succeeded -> events += "clear:$succeeded" },
        )

        assertEquals(listOf("erase", "clear:true"), events)
    }

    @Test
    fun `erase serializes with writes and rejects a queued post logout write`() = runTest {
        val gate = SecureMessagingLifecycleGate()
        val writeEntered = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        var recoverableStateExists = false

        val initiallyRejected = runCatching {
            gate.withOperation { recoverableStateExists = true }
        }.exceptionOrNull()
        assertTrue(initiallyRejected is IllegalStateException)
        gate.open()

        val inFlightWrite = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withOperation {
                writeEntered.complete(Unit)
                releaseWrite.await()
                recoverableStateExists = true
            }
        }
        writeEntered.await()
        val erasure = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.erase { recoverableStateExists = false }
        }
        val lateWrite = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching {
                gate.withOperation { recoverableStateExists = true }
            }.exceptionOrNull()
        }

        releaseWrite.complete(Unit)
        inFlightWrite.join()
        erasure.join()

        assertFalse(recoverableStateExists)
        assertTrue(lateWrite.await() is IllegalStateException)

        gate.open()
        gate.withOperation { recoverableStateExists = true }
        assertTrue(recoverableStateExists)
    }

    @Test
    fun `record cleanup is attempted when keystore erasure fails`() = runTest {
        val keyFailure = IllegalStateException("Keystore unavailable")
        var recordsDeleted = false

        val observed = runCatching {
            eraseMessagingKeyAndRecords(
                eraseKey = { throw keyFailure },
                eraseRecords = { recordsDeleted = true },
            )
        }.exceptionOrNull()

        assertTrue(recordsDeleted)
        assertSame(keyFailure, observed)
    }
}
