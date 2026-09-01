package com.kit.wallet

import com.kit.wallet.data.notifications.MessageReplyPolicy
import com.kit.wallet.data.notifications.deliverMessageReply
import com.kit.wallet.data.session.SessionFence
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MessageReplyDeliveryTest {
    @Test
    fun `cold start waits for encrypted state before durably capturing`() = runTest {
        val sessions = MutableStateFlow<SessionFence?>(null)
        val stateAvailable = MutableStateFlow(false)
        val sends = mutableListOf<Triple<SessionFence, String, String>>()
        val result = async {
            deliverMessageReply(
                request = REQUEST,
                sessionFences = sessions,
                stateAvailable = stateAvailable,
                currentSession = { sessions.value },
                timeoutMillis = 5_000L,
            ) { owner, request ->
                sends += Triple(owner, request.conversationId, request.text)
            }
        }

        runCurrent()
        assertTrue(sends.isEmpty())
        assertFalse(result.isCompleted)

        sessions.value = OWNER
        runCurrent()
        assertTrue(sends.isEmpty())
        assertFalse(result.isCompleted)

        stateAvailable.value = true
        runCurrent()

        assertTrue(result.await())
        assertEquals(listOf(Triple(OWNER, CONVERSATION_ID, "Reply securely")), sends)
    }

    @Test
    fun `durable success authorizes cancellation and retry keeps one outbox identity`() = runTest {
        val sessions = MutableStateFlow<SessionFence?>(OWNER)
        val stateAvailable = MutableStateFlow(true)
        val accepted = linkedMapOf<String, Pair<String, String>>()

        repeat(2) {
            val delivered = deliverMessageReply(
                request = REQUEST,
                sessionFences = sessions,
                stateAvailable = stateAvailable,
                currentSession = { sessions.value },
            ) { _, request ->
                accepted.putIfAbsent(
                    request.clientMessageId,
                    request.conversationId to request.text,
                )
            }
            assertTrue(delivered)
        }

        assertEquals(
            mapOf(CLIENT_MESSAGE_ID to (CONVERSATION_ID to "Reply securely")),
            accepted,
        )
    }

    @Test
    fun `timeout and durable-send failure do not authorize cancellation`() = runTest {
        val sessions = MutableStateFlow<SessionFence?>(OWNER)
        val stateAvailable = MutableStateFlow(false)
        var sends = 0
        val timedOut = async {
            deliverMessageReply(
                request = REQUEST,
                sessionFences = sessions,
                stateAvailable = stateAvailable,
                currentSession = { sessions.value },
                timeoutMillis = 1_000L,
            ) { _, _ -> sends++ }
        }

        runCurrent()
        advanceTimeBy(1_000L)
        runCurrent()
        assertFalse(timedOut.await())
        assertEquals(0, sends)

        stateAvailable.value = true
        assertFalse(
            deliverMessageReply(
                request = REQUEST,
                sessionFences = sessions,
                stateAvailable = stateAvailable,
                currentSession = { sessions.value },
            ) { _, _ -> throw IOException("encrypted store unavailable") },
        )
    }

    @Test
    fun `session replacement rejects the stale notification reply`() = runTest {
        val sessions = MutableStateFlow<SessionFence?>(OTHER_OWNER)
        var sends = 0

        val delivered = deliverMessageReply(
            request = REQUEST,
            sessionFences = sessions,
            stateAvailable = MutableStateFlow(true),
            currentSession = { sessions.value },
        ) { _, _ -> sends++ }

        assertFalse(delivered)
        assertEquals(0, sends)
    }

    @Test
    fun `blank or malformed reply requests are ignored`() {
        assertNull(
            MessageReplyPolicy.request(
                CONVERSATION_ID,
                OWNER.sessionId,
                CLIENT_MESSAGE_ID,
                "  \n  ",
            ),
        )
        assertNull(
            MessageReplyPolicy.request(
                "not-a-conversation",
                OWNER.sessionId,
                CLIENT_MESSAGE_ID,
                "hello",
            ),
        )
        assertNull(
            MessageReplyPolicy.request(
                CONVERSATION_ID,
                OWNER.sessionId,
                "not-a-message",
                "hello",
            ),
        )
    }

    @Test
    fun `notification retries derive one stable distinct outgoing identity`() {
        val first = MessageReplyPolicy.deliveryMessageId(
            CONVERSATION_ID,
            SOURCE_MESSAGE_ID,
            OWNER.sessionId,
        )
        assertEquals(
            first,
            MessageReplyPolicy.deliveryMessageId(
                CONVERSATION_ID,
                SOURCE_MESSAGE_ID,
                OWNER.sessionId,
            ),
        )
        assertNotEquals(
            first,
            MessageReplyPolicy.deliveryMessageId(
                CONVERSATION_ID,
                OTHER_SOURCE_MESSAGE_ID,
                OWNER.sessionId,
            ),
        )
        assertEquals(first, UUID.fromString(first).toString())
        assertNotEquals(SOURCE_MESSAGE_ID, first)
    }

    private companion object {
        const val CONVERSATION_ID = "10000000-0000-4000-8000-000000000001"
        const val SOURCE_MESSAGE_ID = "20000000-0000-4000-8000-000000000001"
        const val OTHER_SOURCE_MESSAGE_ID = "20000000-0000-4000-8000-000000000002"
        const val CLIENT_MESSAGE_ID = "30000000-0000-4000-8000-000000000001"
        val OWNER = SessionFence("session-a", "scope-a", "account-a")
        val OTHER_OWNER = SessionFence("session-b", "scope-b", "account-b")
        val REQUEST = checkNotNull(
            MessageReplyPolicy.request(
                conversationId = CONVERSATION_ID,
                expectedSessionEpoch = OWNER.sessionId,
                clientMessageId = CLIENT_MESSAGE_ID,
                text = "  Reply securely  ",
            ),
        )
    }
}
