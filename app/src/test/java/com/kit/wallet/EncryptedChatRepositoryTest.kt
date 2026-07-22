package com.kit.wallet

import com.kit.wallet.data.messaging.LibSignalCompanionDirection
import com.kit.wallet.data.repository.AuthenticatedDirectConversation
import com.kit.wallet.data.repository.AuthenticatedProjectedText
import com.kit.wallet.data.repository.AuthenticatedProjectionPage
import com.kit.wallet.data.repository.AuthenticatedTextDeliveryState
import com.kit.wallet.data.repository.EncryptedChatRepository
import com.kit.wallet.data.repository.SecureMessagingChatRuntime
import com.kit.wallet.data.repository.projectionIsFromCurrentUser
import com.kit.wallet.ui.model.Contact
import com.kit.wallet.ui.model.DeliveryState
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EncryptedChatRepositoryTest {
    @Test
    fun `inbound envelope from another device on this account retains outgoing authorship`() {
        assertTrue(
            projectionIsFromCurrentUser(
                LibSignalCompanionDirection.INBOUND,
                senderUserId = USER_TWO,
                currentUserId = USER_TWO,
            ),
        )
        assertFalse(
            projectionIsFromCurrentUser(
                LibSignalCompanionDirection.INBOUND,
                senderUserId = USER_ONE,
                currentUserId = USER_TWO,
            ),
        )
    }

    @Test
    fun `readiness and plaintext projections clear when the active session ends`() = runTest {
        val runtime = FakeRuntime(epoch = null).apply {
            conversations += conversation(CONVERSATION_ONE, "Grace")
            projected += message("out:one", CONVERSATION_ONE, "hello", fromMe = true)
        }
        val repository = repository(runtime)

        runtime.sessionEpoch.value = "session-one"
        runCurrent()

        assertTrue(repository.readiness.value)
        assertEquals(listOf(CONVERSATION_ONE), repository.chats.value.map { it.id })
        assertEquals("hello", repository.conversation(CONVERSATION_ONE).value.single().text)

        runtime.sessionEpoch.value = null
        runCurrent()

        assertFalse(repository.readiness.value)
        assertTrue(repository.chats.value.isEmpty())
        assertTrue(repository.conversation(CONVERSATION_ONE).value.isEmpty())
    }

    @Test
    fun `all authenticated projection pages map in time order and preserve direction`() = runTest {
        val runtime = FakeRuntime().apply {
            conversations += conversation(CONVERSATION_ONE, "Grace")
            repeat(101) { index ->
                projected += message(
                    recordKey = "out:${index.toString().padStart(3, '0')}",
                    conversationId = CONVERSATION_ONE,
                    text = "message-$index",
                    fromMe = index % 2 == 0,
                    sentAt = Instant.ofEpochSecond(1_700_000_000L + index),
                )
            }
        }
        val repository = repository(runtime)

        runCurrent()

        val messages = repository.conversation(CONVERSATION_ONE).value
        assertEquals(101, messages.size)
        assertEquals("message-0", messages.first().text)
        assertTrue(messages.first().fromMe)
        assertFalse(messages[1].fromMe)
        assertTrue(runtime.pageRequests.any { it != null })
    }

    @Test
    fun `equal-time mixed directions use server IDs with a pending client fallback`() = runTest {
        val runtime = FakeRuntime().apply {
            conversations += conversation(CONVERSATION_ONE, "Grace")
            projected += message(
                recordKey = "out:z-storage",
                conversationId = CONVERSATION_ONE,
                text = "server-low outgoing",
                fromMe = true,
                serverMessageId = LOW_SERVER_MESSAGE_ID,
            )
            projected += message(
                recordKey = "out:a-storage",
                conversationId = CONVERSATION_ONE,
                text = "pending fallback",
                fromMe = true,
                state = AuthenticatedTextDeliveryState.PENDING,
                serverMessageId = null,
                clientMessageId = PENDING_CLIENT_MESSAGE_ID,
            )
            projected += message(
                recordKey = "in:a-storage",
                conversationId = CONVERSATION_ONE,
                text = "server-high inbound",
                fromMe = false,
                serverMessageId = HIGH_SERVER_MESSAGE_ID,
            )
        }
        val repository = repository(runtime)

        runCurrent()

        assertEquals(
            listOf("server-low outgoing", "pending fallback", "server-high inbound"),
            repository.conversation(CONVERSATION_ONE).value.map { it.text },
        )
        assertEquals("server-high inbound", repository.chats.value.single().lastMessage)
        assertFalse(repository.chats.value.single().lastFromMe)
    }

    @Test
    fun `authenticated inbound messages remain unread until the conversation is opened`() = runTest {
        val runtime = FakeRuntime().apply {
            conversations += conversation(CONVERSATION_ONE, "Grace")
            projected += message("in:one", CONVERSATION_ONE, "one", fromMe = false)
            projected += message(
                "in:two",
                CONVERSATION_ONE,
                "two",
                fromMe = false,
                sentAt = Instant.parse("2026-07-20T12:00:01Z"),
            )
        }
        val repository = repository(runtime)
        runCurrent()

        assertEquals(2, repository.chats.value.single().unread)
        assertEquals(DeliveryState.DELIVERED, repository.conversation(CONVERSATION_ONE).value.first().state)

        repository.markConversationRead(CONVERSATION_ONE)
        runCurrent()

        assertEquals(0, repository.chats.value.single().unread)
        assertEquals(DeliveryState.READ, repository.conversation(CONVERSATION_ONE).value.first().state)
    }

    @Test
    fun `projection authored by a user outside the direct peer binding fails closed`() = runTest {
        val runtime = FakeRuntime().apply {
            conversations += conversation(CONVERSATION_ONE, "Grace")
            projected += message(
                recordKey = "in:forged-peer",
                conversationId = CONVERSATION_ONE,
                text = "must stay hidden",
                fromMe = false,
            ).copy(senderUserId = USER_TWO)
        }
        val repository = repository(runtime)

        runCurrent()

        assertTrue(runtime.pageRequests.isNotEmpty())
        assertTrue(repository.chats.value.isEmpty())
        assertTrue(repository.conversation(CONVERSATION_ONE).value.isEmpty())
    }

    @Test
    fun `only an on-Kit contact can open a validated direct conversation`() = runTest {
        val runtime = FakeRuntime()
        val repository = repository(runtime)
        val onKit = Contact(USER_ONE, "Grace", "+256700000001", isKitUser = true)

        val conversationId = repository.openDirectConversation(onKit)

        assertEquals("conversation:$USER_ONE", conversationId)
        assertEquals(listOf(USER_ONE), runtime.createdPeers)
        assertEquals(conversationId, repository.chats.value.single().id)
        assertEquals(USER_ONE, repository.chats.value.single().peerUserId)

        val rejected = runCatching {
            repository.openDirectConversation(
                Contact(USER_TWO, "Invitee", "+256700000002", isKitUser = false),
            )
        }.exceptionOrNull()
        assertTrue(rejected is IllegalArgumentException)
        assertEquals(listOf(USER_ONE), runtime.createdPeers)
    }

    @Test
    fun `explicit lost response retry reuses one durable pending projection and normalized text`() = runTest {
        val runtime = FakeRuntime(sendScenario = SendScenario.LOST_RESPONSE).apply {
            conversations += conversation(CONVERSATION_ONE, "Grace")
        }
        val repository = repository(runtime)

        val firstFailure = runCatching {
            repository.sendMessage(CONVERSATION_ONE, "  hello securely  ")
        }.exceptionOrNull()
        assertTrue(firstFailure is IOException)
        assertEquals(DeliveryState.SENDING, repository.conversation(CONVERSATION_ONE).value.single().state)

        val pendingId = repository.conversation(CONVERSATION_ONE).value.single().id
        repository.retryMessage(CONVERSATION_ONE, pendingId, " hello securely ")

        assertEquals(
            listOf("hello securely", "hello securely"),
            runtime.sendAttempts.map { it.second },
        )
        assertEquals(listOf(null, pendingId), runtime.sendAttempts.map { it.third })
        val messages = repository.conversation(CONVERSATION_ONE).value
        assertEquals(1, messages.size)
        assertEquals(DeliveryState.SENT, messages.single().state)
    }

    @Test
    fun `changed roster retires stale ciphertext and exposes retry-required before fresh send`() = runTest {
        val runtime = FakeRuntime(sendScenario = SendScenario.CHANGED_ROSTER).apply {
            conversations += conversation(CONVERSATION_ONE, "Grace")
        }
        val repository = repository(runtime)

        assertTrue(
            runCatching { repository.sendMessage(CONVERSATION_ONE, "same text") }
                .exceptionOrNull() is IOException,
        )
        val pendingId = repository.conversation(CONVERSATION_ONE).value.single().id
        repository.retryMessage(CONVERSATION_ONE, pendingId, "same text")

        val messages = repository.conversation(CONVERSATION_ONE).value
        assertEquals(2, messages.size)
        assertEquals(DeliveryState.RETRY_REQUIRED, messages.first().state)
        assertEquals(DeliveryState.SENT, messages.last().state)
        assertEquals(2, runtime.sendAttempts.size)
    }

    @Test
    fun `identical consecutive text without an explicit retry creates distinct sends`() = runTest {
        val runtime = FakeRuntime().apply {
            conversations += conversation(CONVERSATION_ONE, "Grace")
        }
        val repository = repository(runtime)

        repository.sendMessage(CONVERSATION_ONE, "identical")
        repository.sendMessage(CONVERSATION_ONE, "identical")

        assertEquals(listOf(null, null), runtime.sendAttempts.map { it.third })
        val messages = repository.conversation(CONVERSATION_ONE).value
        assertEquals(2, messages.size)
        assertEquals(2, messages.map { it.id }.distinct().size)
    }

    private fun kotlinx.coroutines.test.TestScope.repository(runtime: FakeRuntime) =
        EncryptedChatRepository(
            runtime = runtime,
            scope = backgroundScope,
            clock = Clock.fixed(Instant.parse("2026-07-20T12:00:00Z"), ZoneOffset.UTC),
        )

    private enum class SendScenario { NORMAL, LOST_RESPONSE, CHANGED_ROSTER }

    private class FakeRuntime(
        epoch: String? = "session-one",
        private val sendScenario: SendScenario = SendScenario.NORMAL,
    ) : SecureMessagingChatRuntime {
        override val sessionEpoch = MutableStateFlow(epoch)
        override val projectionChanges = MutableStateFlow(0L)
        val conversations = mutableListOf<AuthenticatedDirectConversation>()
        val projected = mutableListOf<AuthenticatedProjectedText>()
        val createdPeers = mutableListOf<String>()
        val sendAttempts = mutableListOf<Triple<String, String, String?>>()
        val pageRequests = mutableListOf<String?>()

        override suspend fun directConversations(
            forceRefresh: Boolean,
        ): List<AuthenticatedDirectConversation> = conversations.toList()

        override suspend fun createDirectConversation(
            peerUserId: String,
        ): AuthenticatedDirectConversation {
            createdPeers += peerUserId
            return AuthenticatedDirectConversation(
                id = "conversation:$peerUserId",
                peerUserId = peerUserId,
                peerName = "Grace",
            ).also(conversations::add)
        }

        override suspend fun projectionPage(
            afterRecordKey: String?,
            limit: Int,
        ): AuthenticatedProjectionPage {
            pageRequests += afterRecordKey
            val remaining = projected.sortedBy { it.recordKey }
                .filter { afterRecordKey == null || it.recordKey > afterRecordKey }
            val page = remaining.take(limit)
            return AuthenticatedProjectionPage(
                messages = page,
                nextAfterRecordKey = page.lastOrNull()?.recordKey
                    ?.takeIf { page.size < remaining.size },
            )
        }

        override suspend fun sendText(
            conversationId: String,
            text: String,
            retryClientMessageId: String?,
        ) {
            sendAttempts += Triple(conversationId, text, retryClientMessageId)
            when (sendScenario) {
                SendScenario.NORMAL -> recordNormalSend(conversationId, text)
                SendScenario.LOST_RESPONSE -> retryScenario(
                    conversationId,
                    text,
                    retryClientMessageId,
                    changedRoster = false,
                )
                SendScenario.CHANGED_ROSTER -> retryScenario(
                    conversationId,
                    text,
                    retryClientMessageId,
                    changedRoster = true,
                )
            }
        }

        override suspend fun markConversationRead(conversationId: String) {
            var changed = false
            projected.indices.forEach { index ->
                val message = projected[index]
                if (message.conversationId == conversationId &&
                    !message.fromCurrentUser &&
                    message.deliveryState == AuthenticatedTextDeliveryState.RECEIVED
                ) {
                    projected[index] = message.copy(
                        deliveryState = AuthenticatedTextDeliveryState.RECEIVED_READ,
                    )
                    changed = true
                }
            }
            if (changed) projectionChanges.value++
        }

        override suspend fun synchronizeConversation(conversationId: String) = Unit

        private fun retryScenario(
            conversationId: String,
            text: String,
            retryClientMessageId: String?,
            changedRoster: Boolean,
        ) {
            if (sendAttempts.size == 1) {
                projected += message(
                    recordKey = "out:durable-one",
                    conversationId = conversationId,
                    text = text,
                    fromMe = true,
                    state = AuthenticatedTextDeliveryState.PENDING,
                )
                projectionChanges.value++
                throw IOException("response lost")
            }
            val original = projected.single()
            check(retryClientMessageId == original.clientMessageId) {
                "The test retry did not identify its durable send"
            }
            projected[0] = original.copy(
                deliveryState = if (changedRoster) {
                    AuthenticatedTextDeliveryState.RETRY_REQUIRED
                } else {
                    AuthenticatedTextDeliveryState.SENT
                },
            )
            if (changedRoster) {
                projected += message(
                    recordKey = "out:durable-two",
                    conversationId = conversationId,
                    text = text,
                    fromMe = true,
                    state = AuthenticatedTextDeliveryState.SENT,
                    sentAt = original.sentAt.plusSeconds(1),
                )
            }
            projectionChanges.value++
        }

        private fun recordNormalSend(conversationId: String, text: String) {
            val attempt = sendAttempts.size
            projected += message(
                recordKey = "out:normal-$attempt",
                conversationId = conversationId,
                text = text,
                fromMe = true,
                state = AuthenticatedTextDeliveryState.SENT,
                sentAt = Instant.parse("2026-07-20T12:00:00Z").plusSeconds(attempt.toLong()),
            )
            projectionChanges.value++
        }
    }

    private companion object {
        const val CONVERSATION_ONE = "11111111-1111-4111-8111-111111111111"
        const val USER_ONE = "22222222-2222-4222-8222-222222222222"
        const val USER_TWO = "33333333-3333-4333-8333-333333333333"

        // Equal-timestamp ordering keys chosen so the pending message's client-ID fallback sorts
        // between the two server IDs: LOW_SERVER < PENDING_CLIENT < HIGH_SERVER.
        const val LOW_SERVER_MESSAGE_ID = "server-msg-0001"
        const val PENDING_CLIENT_MESSAGE_ID = "server-msg-0500"
        const val HIGH_SERVER_MESSAGE_ID = "server-msg-0999"

        fun conversation(id: String, name: String) = AuthenticatedDirectConversation(
            id = id,
            peerUserId = USER_ONE,
            peerName = name,
        )

        fun message(
            recordKey: String,
            conversationId: String,
            text: String,
            fromMe: Boolean,
            state: AuthenticatedTextDeliveryState = if (fromMe) {
                AuthenticatedTextDeliveryState.SENT
            } else {
                AuthenticatedTextDeliveryState.RECEIVED
            },
            sentAt: Instant = Instant.parse("2026-07-20T12:00:00Z"),
            serverMessageId: String? = recordKey,
            clientMessageId: String = recordKey,
        ) = AuthenticatedProjectedText(
            recordKey = recordKey,
            messageId = recordKey,
            serverMessageId = serverMessageId,
            clientMessageId = clientMessageId,
            conversationId = conversationId,
            senderUserId = if (fromMe) USER_TWO else USER_ONE,
            fromCurrentUser = fromMe,
            text = text,
            sentAt = sentAt,
            deliveryState = state,
        )
    }
}
