package com.kit.wallet

import com.kit.wallet.data.messaging.SecureMessagingUnavailableSyncEngine
import com.kit.wallet.data.messaging.SecureMessagingProtocolUnavailableException
import com.kit.wallet.data.messaging.SecureMessagingStateConflictException
import com.kit.wallet.data.notifications.MessagingWakePayload
import com.kit.wallet.data.notifications.PushEnvelope
import com.kit.wallet.data.notifications.isVerifiedMessagingWake
import com.kit.wallet.worker.SECURE_MESSAGING_WORK_POLICY
import com.kit.wallet.worker.SecureMessagingSyncFailureDisposition
import com.kit.wallet.worker.SecureMessagingWakeCoalescer
import com.kit.wallet.worker.secureMessagingSyncFailureDisposition
import com.kit.wallet.worker.scheduleAuthenticatedMessagingCatchUp
import androidx.work.ExistingWorkPolicy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagingWakePayloadTest {
    @Test
    fun `only the exact opaque messaging wake-up is recognized`() {
        val validPayload = mapOf(
            "notification_id" to "opaque-id",
            "type" to "messaging.sync",
            "scope" to "messaging",
        )
        assertTrue(MessagingWakePayload.matches(validPayload))
        val minimalValidPayload = mapOf(
            "type" to "messaging.sync",
            "scope" to "messaging",
        )
        assertTrue(MessagingWakePayload.matches(minimalValidPayload))
        val missingScope = mapOf("type" to "messaging.sync")
        assertFalse(MessagingWakePayload.matches(missingScope))
        val plaintextScope = mapOf(
            "type" to "messaging.sync",
            "scope" to "conversation-plaintext",
        )
        assertFalse(MessagingWakePayload.matches(plaintextScope))
        val plaintextBody = mapOf(
            "type" to "messaging.sync",
            "scope" to "messaging",
            "body" to "server supplied plaintext must never be rendered",
        )
        assertFalse(MessagingWakePayload.matches(plaintextBody))
    }

    @Test
    fun `opaque wake requires provider envelope verification`() {
        val validPayload = mapOf(
            "notification_id" to "opaque-id",
            "type" to "messaging.sync",
            "scope" to "messaging",
        )

        assertTrue(
            PushEnvelope(data = validPayload, opaqueWakeVerified = true)
                .isVerifiedMessagingWake(),
        )
        assertFalse(PushEnvelope(data = validPayload).isVerifiedMessagingWake())
    }

    @Test
    fun `unavailable sync engine remains fail closed`() = runTest {
        val engine = SecureMessagingUnavailableSyncEngine()
        assertFalse(engine.isReady)
        val failure = runCatching { engine.synchronize() }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
    }

    @Test
    fun `protocol not ready is a clean fail closed worker outcome`() {
        assertEquals(
            SecureMessagingSyncFailureDisposition.SUCCESS,
            secureMessagingSyncFailureDisposition(
                SecureMessagingProtocolUnavailableException("production gate is disabled"),
            ),
        )
    }

    @Test
    fun `atomic messaging state conflicts request a worker retry`() {
        assertEquals(
            SecureMessagingSyncFailureDisposition.RETRY,
            secureMessagingSyncFailureDisposition(
                SecureMessagingStateConflictException("concurrent ratchet commit"),
            ),
        )
    }

    @Test
    fun `wake during active sync queues exactly one sequential follow up`() {
        val coalescer = SecureMessagingWakeCoalescer()
        var enqueued = 0

        coalescer.enqueueOnce { enqueued++ }
        coalescer.enqueueOnce { enqueued++ }
        assertEquals(1, enqueued)

        coalescer.workerStarted()
        coalescer.enqueueOnce { enqueued++ }
        coalescer.enqueueOnce { enqueued++ }

        assertEquals(2, enqueued)
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, SECURE_MESSAGING_WORK_POLICY)
    }

    @Test
    fun `app foreground catch up runs only for an authenticated session`() {
        var schedules = 0

        scheduleAuthenticatedMessagingCatchUp(hasSession = false) { schedules++ }
        scheduleAuthenticatedMessagingCatchUp(hasSession = true) { schedules++ }

        assertEquals(1, schedules)
    }
}
