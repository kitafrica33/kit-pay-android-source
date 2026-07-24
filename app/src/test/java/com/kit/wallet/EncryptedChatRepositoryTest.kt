package com.kit.wallet

import com.kit.wallet.data.messaging.LibSignalCompanionDirection
import com.kit.wallet.data.messaging.SecureMessagingRecordKeyPermanentlyMissingException
import com.kit.wallet.data.repository.AuthenticatedDirectConversation
import com.kit.wallet.data.repository.AuthenticatedProjectedText
import com.kit.wallet.data.repository.AuthenticatedProjectionPage
import com.kit.wallet.data.repository.AuthenticatedTextDeliveryState
import com.kit.wallet.data.repository.ContactRepository
import com.kit.wallet.data.repository.EncryptedChatRepository
import com.kit.wallet.data.repository.SecureMessagingChatSession
import com.kit.wallet.data.repository.SecureMessagingChatRuntime
import com.kit.wallet.data.repository.projectionIsFromCurrentUser
import com.kit.wallet.data.remote.KitWalletApiException
import com.kit.wallet.ui.model.Contact
import com.kit.wallet.ui.model.DeliveryState
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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

        runtime.activate("session-one")
        runCurrent()

        assertTrue(repository.readiness.value)
        assertEquals(listOf(CONVERSATION_ONE), repository.chats.value.map { it.id })
        assertEquals("hello", repository.conversation(CONVERSATION_ONE).value.single().text)

        runtime.activate(null)
        runCurrent()

        assertFalse(repository.readiness.value)
        assertTrue(repository.chats.value.isEmpty())
        assertTrue(repository.conversation(CONVERSATION_ONE).value.isEmpty())
    }

    @Test
    fun `new epoch readiness waits until its projection baseline is published`() = runTest {
        val baselineGate = CompletableDeferred<Unit>()
        val runtime = FakeRuntime(epoch = null, baselineGate = baselineGate).apply {
            conversations += conversation(CONVERSATION_ONE, "Grace")
            projected += message("in:restored", CONVERSATION_ONE, "restored", fromMe = false)
        }
        val repository = repository(runtime)

        runtime.activate("session-restored")
        runCurrent()
        assertFalse(repository.readiness.value)
        assertTrue(repository.conversation(CONVERSATION_ONE).value.isEmpty())

        baselineGate.complete(Unit)
        runCurrent()
        assertTrue(repository.readiness.value)
        assertEquals("restored", repository.conversation(CONVERSATION_ONE).value.single().text)
    }

    @Test
    fun `new epoch baseline retries three times at five second intervals before readiness`() =
        runTest {
            val runtime = FakeRuntime(epoch = null, baselineFailures = 3).apply {
                conversations += conversation(CONVERSATION_ONE, "Grace")
                projected += message("in:restored", CONVERSATION_ONE, "restored", fromMe = false)
            }
            val repository = repository(runtime)

            runtime.activate("session-retry")
            runCurrent()
            assertEquals(1, runtime.baselineAttempts)
            assertFalse(repository.readiness.value)

            advanceTimeBy(4_999L)
            runCurrent()
            assertEquals(1, runtime.baselineAttempts)
            assertFalse(repository.readiness.value)

            advanceTimeBy(1L)
            runCurrent()
            assertEquals(2, runtime.baselineAttempts)
            assertFalse(repository.readiness.value)

            repeat(2) {
                advanceTimeBy(5_000L)
                runCurrent()
            }
            assertEquals(4, runtime.baselineAttempts)
            assertTrue(repository.readiness.value)
            assertEquals("restored", repository.conversation(CONVERSATION_ONE).value.single().text)
        }

    @Test
    fun `exhausted retryable baseline recovers after cooldown without another emission`() = runTest {
        val runtime = FakeRuntime(epoch = null, baselineFailures = Int.MAX_VALUE).apply {
            conversations += conversation(CONVERSATION_ONE, "Grace")
        }
        val repository = repository(runtime)

        runtime.activate("session-failing")
        runCurrent()
        advanceTimeBy(15_000L)
        runCurrent()

        assertEquals(4, runtime.baselineAttempts)
        assertFalse(repository.readiness.value)
        runtime.clearBaselineFailures()

        advanceTimeBy(29_999L)
        runCurrent()
        assertEquals(4, runtime.baselineAttempts)
        advanceTimeBy(1L)
        runCurrent()

        assertEquals(5, runtime.baselineAttempts)
        assertTrue(repository.readiness.value)
    }

    @Test
    fun `successful sync retries an exhausted baseline for the same active identity`() = runTest {
        val runtime = FakeRuntime(epoch = null, baselineFailures = 4).apply {
            conversations += conversation(CONVERSATION_ONE, "Grace")
        }
        val repository = repository(runtime)

        runtime.activate("session-recovered-provider")
        runCurrent()
        advanceTimeBy(15_000L)
        runCurrent()

        val active = checkNotNull(runtime.activeSession.value)
        assertEquals(4, runtime.baselineAttempts)
        assertFalse(repository.readiness.value)

        runtime.completeSuccessfulSync(active)
        runCurrent()

        assertTrue(runtime.activeSession.value === active)
        assertEquals(5, runtime.baselineAttempts)
        assertTrue(repository.readiness.value)
    }

    @Test
    fun `proved missing record key is recovered once before a fresh baseline cycle`() = runTest {
        val runtime = FakeRuntime(
            epoch = null,
            baselineFailures = 4,
            permanentBaselineFailureAttempt = 4,
            recoverPermanentBaselineFailure = true,
        ).apply {
            conversations += conversation(CONVERSATION_ONE, "Grace")
            projected += message("in:restored", CONVERSATION_ONE, "restored", fromMe = false)
        }
        val repository = repository(runtime)

        runtime.activate("session-recover")
        runCurrent()
        advanceTimeBy(15_000L)
        runCurrent()

        assertEquals(1, runtime.permanentRecoveryAttempts)
        assertEquals(5, runtime.baselineAttempts)
        assertTrue(repository.readiness.value)
        assertEquals("restored", repository.conversation(CONVERSATION_ONE).value.single().text)

        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals(1, runtime.permanentRecoveryAttempts)
        assertEquals(5, runtime.baselineAttempts)
    }

    @Test
    fun `same login reactivation identity restarts an exhausted baseline`() = runTest {
        val runtime = FakeRuntime(epoch = null, baselineFailures = 4).apply {
            conversations += conversation(CONVERSATION_ONE, "Grace")
        }
        val repository = repository(runtime)

        runtime.activate("session-same-login")
        runCurrent()
        advanceTimeBy(15_000L)
        runCurrent()
        assertEquals(4, runtime.baselineAttempts)
        assertFalse(repository.readiness.value)

        runtime.reactivate()
        runCurrent()

        assertEquals(5, runtime.baselineAttempts)
        assertEquals(List(5) { "session-same-login" }, runtime.baselineAttemptEpochs)
        assertTrue(repository.readiness.value)
    }

    @Test
    fun `failed proved loss recovery uses bounded cooldown in the same active identity`() =
        runTest {
        val runtime = FakeRuntime(
            epoch = null,
            baselineFailures = 4,
            permanentBaselineFailureAttempt = 4,
            recoverPermanentBaselineFailure = true,
            permanentRecoveryError = IOException("recovery network unavailable"),
            permanentRecoveryFailures = 4,
        ).apply {
            conversations += conversation(CONVERSATION_ONE, "Grace")
        }
        val repository = repository(runtime)

        runtime.activate("session-foreground-retry")
        runCurrent()
        advanceTimeBy(15_000L)
        runCurrent()

        assertEquals(1, runtime.permanentRecoveryAttempts)
        assertEquals(4, runtime.baselineAttempts)
        assertFalse(repository.readiness.value)

        advanceTimeBy(14_999L)
        runCurrent()
        assertEquals(3, runtime.permanentRecoveryAttempts)
        assertFalse(repository.readiness.value)

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(4, runtime.permanentRecoveryAttempts)
        assertFalse(repository.readiness.value)

        advanceTimeBy(29_999L)
        runCurrent()
        assertEquals(4, runtime.permanentRecoveryAttempts)
        assertFalse(repository.readiness.value)

        advanceTimeBy(1L)
        runCurrent()

        assertEquals(5, runtime.permanentRecoveryAttempts)
        assertEquals(5, runtime.baselineAttempts)
        assertTrue(repository.readiness.value)
    }

    @Test
    fun `session replacement cancels a failed proved loss recovery retry`() = runTest {
        val runtime = FakeRuntime(
            epoch = null,
            baselineFailures = 4,
            permanentBaselineFailureAttempt = 4,
            recoverPermanentBaselineFailure = true,
            permanentRecoveryError = IOException("recovery network unavailable"),
            permanentRecoveryFailures = Int.MAX_VALUE,
        ).apply {
            conversations += conversation(CONVERSATION_ONE, "Grace")
        }
        val repository = repository(runtime)

        runtime.activate("session-obsolete-recovery")
        runCurrent()
        advanceTimeBy(15_000L)
        runCurrent()
        assertEquals(1, runtime.permanentRecoveryAttempts)
        assertFalse(repository.readiness.value)

        runtime.activate("session-current-recovery")
        runCurrent()
        assertTrue(repository.readiness.value)

        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals(1, runtime.permanentRecoveryAttempts)
        assertEquals("session-current-recovery", runtime.baselineAttemptEpochs.last())
        assertTrue(repository.readiness.value)
    }

    @Test
    fun `null status business recovery failure is terminal`() = runTest {
        val runtime = FakeRuntime(
            epoch = null,
            baselineFailures = 1,
            permanentBaselineFailureAttempt = 1,
            recoverPermanentBaselineFailure = true,
            permanentRecoveryError = KitWalletApiException(
                code = "RECOVERY_NOT_ALLOWED",
                message = "Recovery is not allowed for this account",
                statusCode = null,
                connectivity = false,
            ),
            permanentRecoveryFailures = Int.MAX_VALUE,
        )
        val repository = repository(runtime)

        runtime.activate("session-business-error")
        runCurrent()

        assertEquals(1, runtime.permanentRecoveryAttempts)
        assertEquals(1, runtime.baselineAttempts)
        assertFalse(repository.readiness.value)

        advanceTimeBy(10 * 60_000L)
        runCurrent()

        assertEquals(1, runtime.permanentRecoveryAttempts)
        assertEquals(1, runtime.baselineAttempts)
        assertFalse(repository.readiness.value)
    }

    @Test
    fun `epoch replacement cancels an obsolete baseline retry delay`() = runTest {
        val runtime = FakeRuntime(epoch = null, baselineFailures = 1).apply {
            conversations += conversation(CONVERSATION_ONE, "Grace")
        }
        val repository = repository(runtime)

        runtime.activate("session-obsolete")
        runCurrent()
        assertEquals(1, runtime.baselineAttempts)
        assertFalse(repository.readiness.value)

        runtime.activate("session-current")
        runCurrent()
        assertEquals(
            listOf("session-obsolete", "session-current"),
            runtime.baselineAttemptEpochs,
        )
        assertTrue(repository.readiness.value)

        advanceTimeBy(10_000L)
        runCurrent()
        assertEquals(2, runtime.baselineAttempts)
        assertEquals("session-current", runtime.baselineAttemptEpochs.last())
        assertTrue(repository.readiness.value)
    }

    @Test
    fun `replacement during exhausted cooldown never retries or publishes obsolete session`() =
        runTest {
            val runtime = FakeRuntime(epoch = null, baselineFailures = Int.MAX_VALUE).apply {
                conversations += conversation(CONVERSATION_ONE, "Grace")
            }
            val repository = repository(runtime)

            runtime.activate("session-obsolete")
            runCurrent()
            advanceTimeBy(15_000L)
            runCurrent()
            assertEquals(4, runtime.baselineAttempts)
            assertFalse(repository.readiness.value)

            runtime.clearBaselineFailures()
            val replacementGate = CompletableDeferred<Unit>()
            runtime.activate("session-current", replacementGate)
            runCurrent()
            assertEquals(5, runtime.baselineAttempts)
            assertEquals("session-current", runtime.baselineAttemptEpochs.last())
            assertFalse(repository.readiness.value)

            advanceTimeBy(60_000L)
            runCurrent()
            assertEquals(5, runtime.baselineAttempts)
            assertFalse(repository.readiness.value)

            replacementGate.complete(Unit)
            runCurrent()
            assertEquals(5, runtime.baselineAttempts)
            assertTrue(repository.readiness.value)
        }

    @Test
    fun `obsolete projection cannot publish across an activation replacement`() = runTest {
        val runtime = FakeRuntime().apply {
            conversations += conversation(CONVERSATION_ONE, "Grace")
            projected += message("in:old", CONVERSATION_ONE, "old A", fromMe = false)
        }
        val repository = repository(runtime)
        val observedTexts = mutableListOf<List<String>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.conversation(CONVERSATION_ONE).collect { messages ->
                observedTexts += messages.map { it.text }
            }
        }
        runCurrent()
        assertTrue(repository.readiness.value)
        assertEquals(listOf("old A"), repository.conversation(CONVERSATION_ONE).value.map { it.text })

        val replacementBaseline = CompletableDeferred<Unit>()
        runtime.replaceAtNextPublicationBoundary("session-two", replacementBaseline)
        runtime.projected.clear()
        runtime.projected += message("in:stale", CONVERSATION_ONE, "stale A", fromMe = false)
        runtime.projectionChanges.value++
        runCurrent()

        assertEquals("session-two", runtime.activeSession.value?.sessionEpoch)
        assertFalse(repository.readiness.value)
        assertTrue(repository.chats.value.isEmpty())
        assertTrue(repository.conversation(CONVERSATION_ONE).value.isEmpty())
        assertFalse(observedTexts.any { "stale A" in it })
    }

    @Test
    fun `stale ready projection cannot redirect an action to a replacement session`() = runTest {
        val runtime = FakeRuntime().apply {
            conversations += conversation(CONVERSATION_ONE, "Grace")
        }
        val repository = repository(runtime)
        runCurrent()
        assertTrue(repository.readiness.value)

        runtime.replaceAuthorityWithoutExposure("session-two")
        val failure = runCatching {
            repository.openDirectConversation(
                Contact(USER_TWO, "New peer", "+256700000002", isKitUser = true),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(repository.readiness.value)
        assertEquals("session-one", runtime.activeSession.value?.sessionEpoch)
        assertEquals("session-two", runtime.authoritativeEpoch())
        assertTrue(runtime.createdPeers.isEmpty())
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
        runCurrent()

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
    fun `saved address book name overrides the registered conversation name`() = runTest {
        val runtime = FakeRuntime().apply {
            conversations += conversation(CONVERSATION_ONE, "Registered Flora")
        }
        val localContacts = MutableStateFlow(
            listOf(
                Contact(
                    id = USER_ONE.uppercase(),
                    name = "Flora from my phone",
                    phone = "+256761146015",
                    isKitUser = true,
                    registeredName = "Registered Flora",
                    savedInDevice = true,
                ),
            ),
        )
        val repository = repository(runtime, localContacts)

        runCurrent()

        assertEquals("Flora from my phone", repository.chats.value.single().name)
    }

    @Test
    fun `viewer scoped peer alias stays visible until a saved contact name arrives`() = runTest {
        val runtime = FakeRuntime().apply {
            conversations += conversation(CONVERSATION_ONE, "My sister")
        }
        val localContacts = MutableStateFlow<List<Contact>>(emptyList())
        val repository = repository(runtime, localContacts)
        val observedNames = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.chats.collect { chats ->
                chats.singleOrNull()?.name?.let(observedNames::add)
            }
        }

        runCurrent()
        assertEquals(listOf("My sister"), observedNames)

        localContacts.value = listOf(
            Contact(
                id = USER_ONE,
                name = "Flora from my phone",
                phone = "+256761146015",
                isKitUser = true,
                registeredName = USER_ONE,
                savedInDevice = true,
            ),
        )
        runCurrent()

        assertEquals(listOf("My sister", "Flora from my phone"), observedNames)
        assertFalse(observedNames.any { it == USER_ONE })
    }

    @Test
    fun `invalid saved and server contact names use the generic fallback`() = runTest {
        val runtime = FakeRuntime().apply {
            conversations += conversation(CONVERSATION_ONE, USER_ONE.uppercase())
        }
        val localContacts = MutableStateFlow(
            listOf(
                Contact(
                    id = USER_ONE,
                    name = "\u0000\u0007\t",
                    phone = "+256761146015",
                    isKitUser = true,
                    registeredName = USER_ONE,
                    savedInDevice = true,
                ),
            ),
        )
        val repository = repository(runtime, localContacts)

        runCurrent()

        assertEquals("Kit Pay contact", repository.chats.value.single().name)
    }

    @Test
    fun `explicit lost response retry reuses one durable pending projection and normalized text`() = runTest {
        val runtime = FakeRuntime(sendScenario = SendScenario.LOST_RESPONSE).apply {
            conversations += conversation(CONVERSATION_ONE, "Grace")
        }
        val repository = repository(runtime)
        runCurrent()
        val durableClientIds = mutableListOf<String>()

        val firstFailure = runCatching {
            repository.sendMessage(CONVERSATION_ONE, "  hello securely  ") {
                durableClientIds += it
            }
        }.exceptionOrNull()
        assertTrue(firstFailure is IOException)
        assertEquals(DeliveryState.SENDING, repository.conversation(CONVERSATION_ONE).value.single().state)

        val pendingId = repository.conversation(CONVERSATION_ONE).value.single().id
        assertEquals(listOf(pendingId), durableClientIds)
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
    fun `pre-commit runtime failure never reports a durable client message ID`() = runTest {
        val runtime = FakeRuntime(sendScenario = SendScenario.PRE_DURABLE_FAILURE).apply {
            conversations += conversation(CONVERSATION_ONE, "Grace")
        }
        val repository = repository(runtime)
        runCurrent()
        val durableClientIds = mutableListOf<String>()

        val failure = runCatching {
            repository.sendMessage(CONVERSATION_ONE, "not committed") {
                durableClientIds += it
            }
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertTrue(durableClientIds.isEmpty())
        assertTrue(repository.conversation(CONVERSATION_ONE).value.isEmpty())
    }

    @Test
    fun `changed roster retires stale ciphertext and exposes retry-required before fresh send`() = runTest {
        val runtime = FakeRuntime(sendScenario = SendScenario.CHANGED_ROSTER).apply {
            conversations += conversation(CONVERSATION_ONE, "Grace")
        }
        val repository = repository(runtime)
        runCurrent()

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
        runCurrent()

        repository.sendMessage(CONVERSATION_ONE, "identical")
        repository.sendMessage(CONVERSATION_ONE, "identical")

        assertEquals(listOf(null, null), runtime.sendAttempts.map { it.third })
        val messages = repository.conversation(CONVERSATION_ONE).value
        assertEquals(2, messages.size)
        assertEquals(2, messages.map { it.id }.distinct().size)
    }

    private fun kotlinx.coroutines.test.TestScope.repository(
        runtime: FakeRuntime,
        contactState: MutableStateFlow<List<Contact>> = MutableStateFlow(emptyList()),
    ) =
        EncryptedChatRepository(
            runtime = runtime,
            contacts = object : ContactRepository {
                override val contacts: StateFlow<List<Contact>> = contactState
                override suspend fun refresh() = Unit
                override suspend fun syncDeviceContacts() = Unit
            },
            scope = backgroundScope,
            clock = Clock.fixed(Instant.parse("2026-07-20T12:00:00Z"), ZoneOffset.UTC),
        )

    private enum class SendScenario {
        NORMAL,
        LOST_RESPONSE,
        CHANGED_ROSTER,
        PRE_DURABLE_FAILURE,
    }

    private class FakeRuntime(
        epoch: String? = "session-one",
        private val sendScenario: SendScenario = SendScenario.NORMAL,
        private val baselineGate: CompletableDeferred<Unit>? = null,
        baselineFailures: Int = 0,
        private val permanentBaselineFailureAttempt: Int? = null,
        private val recoverPermanentBaselineFailure: Boolean = false,
        private val permanentRecoveryError: Exception? = null,
        permanentRecoveryFailures: Int = if (permanentRecoveryError == null) 0 else Int.MAX_VALUE,
    ) : SecureMessagingChatRuntime {
        private val authorityLock = Any()
        private val initialSession = epoch?.let(::newSession)
        private var authoritativeSession = initialSession
        private var boundaryReplacement: SecureMessagingChatSession? = null
        private var boundaryReplacementArmed = false
        private val sessionBaselineGates =
            mutableMapOf<SecureMessagingChatSession, CompletableDeferred<Unit>>()
        private var remainingBaselineFailures = baselineFailures
        private var remainingPermanentRecoveryFailures = permanentRecoveryFailures
        override val activeSession = MutableStateFlow(initialSession)
        override val projectionChanges = MutableStateFlow(0L)
        private val mutableBaselineRetrySessions =
            MutableSharedFlow<SecureMessagingChatSession>(extraBufferCapacity = 1)
        override val baselineRetrySessions = mutableBaselineRetrySessions
        val conversations = mutableListOf<AuthenticatedDirectConversation>()
        val projected = mutableListOf<AuthenticatedProjectedText>()
        val createdPeers = mutableListOf<String>()
        val sendAttempts = mutableListOf<Triple<String, String, String?>>()
        val pageRequests = mutableListOf<String?>()
        var baselineAttempts = 0
            private set
        val baselineAttemptEpochs = mutableListOf<String?>()
        var permanentRecoveryAttempts = 0
            private set

        fun activate(
            epoch: String?,
            sessionBaselineGate: CompletableDeferred<Unit>? = null,
        ) {
            val activated = epoch?.let(::newSession)
            synchronized(authorityLock) {
                if (activated != null && sessionBaselineGate != null) {
                    sessionBaselineGates[activated] = sessionBaselineGate
                }
                authoritativeSession = activated
                activeSession.value = activated
            }
        }

        fun clearBaselineFailures() {
            remainingBaselineFailures = 0
        }

        fun replaceAtNextPublicationBoundary(
            epoch: String,
            baselineGate: CompletableDeferred<Unit>,
        ) {
            synchronized(authorityLock) {
                check(boundaryReplacement == null)
                newSession(epoch).also { replacement ->
                    boundaryReplacement = replacement
                    sessionBaselineGates[replacement] = baselineGate
                }
            }
        }

        fun replaceAuthorityWithoutExposure(epoch: String) {
            synchronized(authorityLock) {
                authoritativeSession = newSession(epoch)
            }
        }

        fun authoritativeEpoch(): String? =
            synchronized(authorityLock) { authoritativeSession?.sessionEpoch }

        override fun isCurrent(session: SecureMessagingChatSession): Boolean =
            synchronized(authorityLock) {
                val wasCurrent = authoritativeSession === session
                if (wasCurrent && boundaryReplacementArmed) {
                    applyBoundaryReplacementLocked()
                }
                wasCurrent
            }

        override fun publishIfCurrent(
            session: SecureMessagingChatSession?,
            publication: () -> Unit,
        ): Boolean = synchronized(authorityLock) {
            if (boundaryReplacementArmed) applyBoundaryReplacementLocked()
            if (authoritativeSession !== session) return@synchronized false
            publication()
            authoritativeSession === session
        }

        fun completeSuccessfulSync(session: SecureMessagingChatSession) {
            check(mutableBaselineRetrySessions.tryEmit(session))
        }

        fun reactivate() {
            activate(checkNotNull(activeSession.value).sessionEpoch)
        }

        override suspend fun recoverPermanentlyUnavailableState(error: Throwable): Boolean {
            if (!recoverPermanentBaselineFailure) return false
            permanentRecoveryAttempts++
            if (remainingPermanentRecoveryFailures > 0) {
                remainingPermanentRecoveryFailures--
                throw checkNotNull(permanentRecoveryError)
            }
            remainingBaselineFailures = 0
            return true
        }

        override suspend fun directConversations(
            session: SecureMessagingChatSession,
            forceRefresh: Boolean,
        ): List<AuthenticatedDirectConversation> {
            requireCurrent(session)
            baselineAttempts++
            baselineAttemptEpochs += session.sessionEpoch
            if (remainingBaselineFailures > 0) {
                remainingBaselineFailures--
                if (baselineAttempts == permanentBaselineFailureAttempt) {
                    throw IOException(
                        "projection baseline record key is permanently unavailable",
                        SecureMessagingRecordKeyPermanentlyMissingException(),
                    )
                }
                throw IOException("projection baseline temporarily unavailable")
            }
            synchronized(authorityLock) { sessionBaselineGates[session] }?.await()
            baselineGate?.await()
            requireCurrent(session)
            return conversations.toList()
        }

        override suspend fun createDirectConversation(
            session: SecureMessagingChatSession,
            peerUserId: String,
        ): AuthenticatedDirectConversation {
            requireCurrent(session)
            createdPeers += peerUserId
            return AuthenticatedDirectConversation(
                id = "conversation:$peerUserId",
                peerUserId = peerUserId,
                peerName = "Grace",
            ).also(conversations::add)
        }

        override suspend fun projectionPage(
            session: SecureMessagingChatSession,
            afterRecordKey: String?,
            limit: Int,
        ): AuthenticatedProjectionPage {
            requireCurrent(session)
            pageRequests += afterRecordKey
            val remaining = projected.sortedBy { it.recordKey }
                .filter { afterRecordKey == null || it.recordKey > afterRecordKey }
            val page = remaining.take(limit)
            return AuthenticatedProjectionPage(
                messages = page,
                nextAfterRecordKey = page.lastOrNull()?.recordKey
                    ?.takeIf { page.size < remaining.size },
            ).also { result ->
                if (result.nextAfterRecordKey == null) {
                    synchronized(authorityLock) {
                        if (boundaryReplacement != null) boundaryReplacementArmed = true
                    }
                }
            }
        }

        override suspend fun sendText(
            session: SecureMessagingChatSession,
            conversationId: String,
            text: String,
            retryClientMessageId: String?,
            onDurablyCommitted: (clientMessageId: String) -> Unit,
        ) {
            requireCurrent(session)
            sendAttempts += Triple(conversationId, text, retryClientMessageId)
            when (sendScenario) {
                SendScenario.NORMAL -> {
                    val committed = recordNormalSend(conversationId, text)
                    onDurablyCommitted(committed.clientMessageId)
                }
                SendScenario.LOST_RESPONSE -> retryScenario(
                    conversationId,
                    text,
                    retryClientMessageId,
                    changedRoster = false,
                    onDurablyCommitted = onDurablyCommitted,
                )
                SendScenario.CHANGED_ROSTER -> retryScenario(
                    conversationId,
                    text,
                    retryClientMessageId,
                    changedRoster = true,
                    onDurablyCommitted = onDurablyCommitted,
                )
                SendScenario.PRE_DURABLE_FAILURE -> throw IOException(
                    "injected failure before durable encryption commit",
                )
            }
        }

        override suspend fun markConversationRead(
            session: SecureMessagingChatSession,
            conversationId: String,
        ) {
            requireCurrent(session)
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

        override suspend fun synchronizeConversation(
            session: SecureMessagingChatSession,
            conversationId: String,
        ) {
            requireCurrent(session)
        }

        private fun requireCurrent(session: SecureMessagingChatSession) {
            check(synchronized(authorityLock) { authoritativeSession === session }) {
                "The requested secure messaging activation is no longer current"
            }
        }

        private fun applyBoundaryReplacementLocked() {
            val replacement = checkNotNull(boundaryReplacement)
            boundaryReplacement = null
            boundaryReplacementArmed = false
            authoritativeSession = replacement
            activeSession.value = replacement
        }

        private fun retryScenario(
            conversationId: String,
            text: String,
            retryClientMessageId: String?,
            changedRoster: Boolean,
            onDurablyCommitted: (clientMessageId: String) -> Unit,
        ) {
            if (sendAttempts.size == 1) {
                val committed = message(
                    recordKey = "out:durable-one",
                    conversationId = conversationId,
                    text = text,
                    fromMe = true,
                    state = AuthenticatedTextDeliveryState.PENDING,
                )
                projected += committed
                projectionChanges.value++
                onDurablyCommitted(committed.clientMessageId)
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

        private fun recordNormalSend(
            conversationId: String,
            text: String,
        ): AuthenticatedProjectedText {
            val attempt = sendAttempts.size
            val committed = message(
                recordKey = "out:normal-$attempt",
                conversationId = conversationId,
                text = text,
                fromMe = true,
                state = AuthenticatedTextDeliveryState.SENT,
                sentAt = Instant.parse("2026-07-20T12:00:00Z").plusSeconds(attempt.toLong()),
            )
            projected += committed
            projectionChanges.value++
            return committed
        }

        private fun newSession(epoch: String) = SecureMessagingChatSession(epoch, Any())
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
