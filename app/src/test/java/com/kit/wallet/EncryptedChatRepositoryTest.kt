package com.kit.wallet

import com.kit.wallet.data.messaging.ConversationSystemEvent
import com.kit.wallet.data.messaging.ConversationSystemEventStore
import com.kit.wallet.data.messaging.LibSignalCompanionDirection
import com.kit.wallet.data.messaging.KitPaymentAction
import com.kit.wallet.data.messaging.KitPaymentMessage
import com.kit.wallet.data.messaging.KitReactionAction
import com.kit.wallet.data.messaging.KitReactionMessage
import com.kit.wallet.data.messaging.MEMBERSHIP_ADDED_EVENT
import com.kit.wallet.data.messaging.MEMBERSHIP_REMOVED_EVENT
import com.kit.wallet.data.messaging.MEMBERSHIP_ROLE_CHANGED_EVENT
import com.kit.wallet.data.messaging.SecureMessagingActivationCapability
import com.kit.wallet.data.messaging.SecureMessagingLifecycleGuard
import com.kit.wallet.data.messaging.SecureMessagingRecordKeyPermanentlyMissingException
import com.kit.wallet.data.messaging.SecureMessagingSessionBinding
import com.kit.wallet.data.realtime.KitPresenceRegistry
import com.kit.wallet.data.remote.DIRECT_CONVERSATION_TYPE
import com.kit.wallet.data.remote.GROUP_CONVERSATION_TYPE
import com.kit.wallet.data.remote.MEMBER_CONVERSATION_ROLE
import com.kit.wallet.data.remote.OWNER_CONVERSATION_ROLE
import com.kit.wallet.data.repository.AuthenticatedConversation
import com.kit.wallet.data.repository.AuthenticatedConversationMember
import com.kit.wallet.data.repository.AuthenticatedProjectedText
import com.kit.wallet.data.repository.AuthenticatedProjectionPage
import com.kit.wallet.data.repository.AuthenticatedTextDeliveryState
import com.kit.wallet.data.repository.ContactRepository
import com.kit.wallet.data.repository.EncryptedChatRepository
import com.kit.wallet.data.repository.SecureMessagingChatSession
import com.kit.wallet.data.repository.SecureMessagingChatRuntime
import com.kit.wallet.data.repository.projectionIsFromCurrentUser
import com.kit.wallet.data.remote.KitWalletApiException
import com.kit.wallet.data.session.SessionFence
import com.kit.wallet.data.session.SessionInvalidatedException
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.ui.model.ChatMemberRole
import com.kit.wallet.ui.model.ChatPreview
import com.kit.wallet.ui.model.Contact
import com.kit.wallet.ui.model.DeliveryState
import com.kit.wallet.ui.model.MessageKind
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
import org.junit.Assert.assertNull
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

        // A lifecycle blip keeps the last publication visible (readiness only drops), so the
        // open chat never blanks; a real sign-out erases the plaintext after the short grace.
        assertFalse(repository.readiness.value)
        assertEquals(listOf(CONVERSATION_ONE), repository.chats.value.map { it.id })
        assertEquals("hello", repository.conversation(CONVERSATION_ONE).value.single().text)

        advanceTimeBy(16_000L)
        runCurrent()

        assertFalse(repository.readiness.value)
        assertTrue(repository.chats.value.isEmpty())
        assertTrue(repository.conversation(CONVERSATION_ONE).value.isEmpty())
    }

    @Test
    fun `local encrypted history is on screen before any session can exchange`() = runTest {
        val runtime = FakeRuntime(epoch = null).apply {
            cachedConversations += conversation(CONVERSATION_ONE, "Grace")
            localProjected += message("in:one", CONVERSATION_ONE, "from disk", fromMe = false)
        }
        val repository = repository(runtime)

        // Exactly the cold-start ordering: an activation exists — the local store can be opened —
        // long before the transport, key and roster round trips have run, or if they never do.
        runtime.localHistoryActivations.value = localActivation()
        runCurrent()

        assertTrue(repository.localHistoryReady.value)
        assertFalse("nothing may be sendable yet", repository.readiness.value)
        assertEquals(listOf(CONVERSATION_ONE), repository.chats.value.map { it.id })
        assertEquals(
            "from disk",
            repository.conversation(CONVERSATION_ONE).value.single().text,
        )

        // And sending still cannot happen behind that publication: no ready session was published,
        // so the exchange gate refuses rather than falling back to anything.
        val failure = runCatching {
            repository.sendMessage(CONVERSATION_ONE, "nope") {}
        }.exceptionOrNull()
        assertTrue("expected a refusal, got $failure", failure is IllegalStateException)
    }

    @Test
    fun `a ready session supersedes the local view without blanking the screen`() = runTest {
        val runtime = FakeRuntime(epoch = null).apply {
            cachedConversations += conversation(CONVERSATION_ONE, "Grace")
            localProjected += message("in:one", CONVERSATION_ONE, "from disk", fromMe = false)
            conversations += conversation(CONVERSATION_ONE, "Grace")
            projected += message("in:one", CONVERSATION_ONE, "from disk", fromMe = false)
            projected += message("out:two", CONVERSATION_ONE, "synced", fromMe = true)
        }
        val repository = repository(runtime)
        runtime.localHistoryActivations.value = localActivation()
        runCurrent()
        assertTrue(repository.localHistoryReady.value)

        val chatEmissions = mutableListOf<Int>()
        val messageEmissions = mutableListOf<Int>()
        backgroundScope.launch { repository.chats.collect { chatEmissions += it.size } }
        backgroundScope.launch {
            repository.conversation(CONVERSATION_ONE).collect { messageEmissions += it.size }
        }
        runCurrent()

        runtime.activate("session-one")
        runCurrent()

        assertTrue(repository.readiness.value)
        assertTrue(repository.localHistoryReady.value)
        assertEquals(listOf(CONVERSATION_ONE), repository.chats.value.map { it.id })
        assertEquals(
            listOf("from disk", "synced"),
            repository.conversation(CONVERSATION_ONE).value.map { it.text },
        )
        assertTrue("chats blanked: $chatEmissions", chatEmissions.none { it == 0 })
        assertTrue("messages blanked: $messageEmissions", messageEmissions.none { it == 0 })
    }

    @Test
    fun `a device that has never synced settles empty instead of loading forever`() = runTest {
        val runtime = FakeRuntime(epoch = null)
        val repository = repository(runtime)

        runtime.localHistoryActivations.value = localActivation()
        runCurrent()

        assertTrue(repository.localHistoryReady.value)
        assertFalse(repository.readiness.value)
        assertTrue(repository.chats.value.isEmpty())
    }

    @Test
    fun `withdrawing the activation takes the local view down with it`() = runTest {
        val runtime = FakeRuntime(epoch = null).apply {
            cachedConversations += conversation(CONVERSATION_ONE, "Grace")
            localProjected += message("in:one", CONVERSATION_ONE, "from disk", fromMe = false)
        }
        val repository = repository(runtime)
        runtime.localHistoryActivations.value = localActivation()
        runCurrent()
        assertEquals(listOf(CONVERSATION_ONE), repository.chats.value.map { it.id })

        // Quarantine or erasure withdraws the read authority; the plaintext must not outlive it.
        runtime.localHistoryActivations.value = null
        runCurrent()

        assertFalse(repository.localHistoryReady.value)
        assertTrue(repository.chats.value.isEmpty())
        assertTrue(repository.conversation(CONVERSATION_ONE).value.isEmpty())
    }

    @Test
    fun `published chats survive a same-identity lifecycle blip without an empty emission`() =
        runTest {
            val runtime = FakeRuntime(epoch = null).apply {
                conversations += conversation(CONVERSATION_ONE, "Grace")
                projected += message("out:one", CONVERSATION_ONE, "hello", fromMe = true)
            }
            val repository = repository(runtime)
            runtime.activate("session-blip")
            runCurrent()
            assertTrue(repository.readiness.value)

            val chatEmissions = mutableListOf<Int>()
            val messageEmissions = mutableListOf<Int>()
            backgroundScope.launch { repository.chats.collect { chatEmissions += it.size } }
            backgroundScope.launch {
                repository.conversation(CONVERSATION_ONE).collect { messageEmissions += it.size }
            }
            runCurrent()

            // A key-revalidation/roster-resync blip retains the registry momentarily and then
            // republishes the SAME activation under a fresh wrapper. The visible chats and the
            // open conversation must never blank, and no destructive re-baseline may run.
            runtime.blipSameIdentity()
            runCurrent()

            assertTrue(repository.readiness.value)
            assertEquals(listOf(CONVERSATION_ONE), repository.chats.value.map { it.id })
            assertEquals("hello", repository.conversation(CONVERSATION_ONE).value.single().text)
            assertTrue("chats blanked: $chatEmissions", chatEmissions.none { it == 0 })
            assertTrue("messages blanked: $messageEmissions", messageEmissions.none { it == 0 })
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
        // The completion cuts the cooldown short (attempt 5 succeeds) and the same signal then
        // performs one ordinary republication of the now-ready projection (attempt 6).
        assertEquals(6, runtime.baselineAttempts)
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
                    phone = "+256700000001",
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
                phone = "+256700000001",
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
                    phone = "+256700000001",
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

    @Test
    fun `owner pinned send cannot cross into a replacement login`() = runTest {
        val runtime = FakeRuntime(epoch = "session-one").apply {
            conversations += conversation(CONVERSATION_ONE, "Grace")
        }
        val authentication = MutableTestSessionStore(
            testSession(USER_TWO, sessionId = "session-one"),
        )
        val repository = repository(runtime, authenticationSessions = authentication)
        runCurrent()
        val owner = checkNotNull(authentication.current()).fence()

        repository.sendMessageForOwner(owner, CONVERSATION_ONE, "first owner")
        assertEquals(listOf(owner), runtime.expectedOwners)

        authentication.save(testSession(USER_ONE, sessionId = "session-two"))
        runtime.activate("session-two")
        runCurrent()

        val failure = runCatching {
            repository.sendMessageForOwner(owner, CONVERSATION_ONE, "must not cross")
        }.exceptionOrNull()

        assertTrue(failure is SessionInvalidatedException)
        assertEquals(listOf("first owner"), runtime.sendAttempts.map { it.second })
        assertEquals(listOf(owner), runtime.expectedOwners)
    }

    @Test
    fun `user text cannot impersonate the reserved payment wire`() = runTest {
        val runtime = FakeRuntime().apply {
            conversations += conversation(CONVERSATION_ONE, "Grace")
        }
        val repository = repository(runtime)
        runCurrent()
        val descriptor = KitPaymentMessage(
            action = KitPaymentAction.ACCEPTED,
            referenceId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            amountMinor = 500,
            currencyCode = "UGX",
            currencyScale = 0,
            note = null,
        ).encode()

        assertTrue(
            runCatching { repository.sendMessage(CONVERSATION_ONE, descriptor) }
                .exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            runCatching {
                repository.sendMessage(CONVERSATION_ONE, " \n KITPAY1:not-a-descriptor")
            }.exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(runtime.sendAttempts.isEmpty())

        repository.sendPaymentEvent(CONVERSATION_ONE, descriptor)
        assertEquals(listOf(descriptor), runtime.sendAttempts.map { it.second })
        assertTrue(
            runCatching {
                repository.sendPaymentEvent(CONVERSATION_ONE, "KITPAY1:not-a-descriptor")
            }.exceptionOrNull() is IllegalArgumentException,
        )
        assertEquals(1, runtime.sendAttempts.size)
    }

    @Test
    fun `reacting rides the durable send path and annotates instead of adding a bubble`() = runTest {
        val runtime = FakeRuntime().apply {
            conversations += conversation(CONVERSATION_ONE, "Grace")
            projected += message(
                recordKey = TARGET_MESSAGE_ID,
                conversationId = CONVERSATION_ONE,
                text = "Sent you the deposit",
                fromMe = false,
            )
        }
        val repository = repository(runtime)
        runCurrent()
        assertTrue(repository.conversation(CONVERSATION_ONE).value.single().reactions.isEmpty())

        repository.toggleReaction(CONVERSATION_ONE, TARGET_MESSAGE_ID, "👍")

        // The reaction is an ordinary encrypted send, so it inherits the outbox, retry and sync.
        assertEquals(
            listOf(
                KitReactionMessage(TARGET_MESSAGE_ID, "👍", KitReactionAction.ADD).encode(),
            ),
            runtime.sendAttempts.map { it.second },
        )
        assertEquals(listOf(TARGET_MESSAGE_ID), runtime.replyTargets)
        // It annotates the bubble it points at rather than becoming one of its own, and the
        // projection republishes on durable commit, so the chip is on screen before the round trip.
        val reacted = repository.conversation(CONVERSATION_ONE).value.single()
        assertEquals(TARGET_MESSAGE_ID, reacted.id)
        assertEquals(listOf("👍"), reacted.reactions.map { it.emoji })
        assertTrue(reacted.reactions.single().fromMe)
        assertEquals(listOf("You"), reacted.reactions.single().reactorNames)
        // Nor does it become the conversation's last word.
        assertEquals("Sent you the deposit", repository.chats.value.single().lastMessage)

        repository.toggleReaction(CONVERSATION_ONE, TARGET_MESSAGE_ID, "👍")

        assertEquals(
            KitReactionMessage(TARGET_MESSAGE_ID, "👍", KitReactionAction.REMOVE).encode(),
            runtime.sendAttempts.last().second,
        )
        assertTrue(repository.conversation(CONVERSATION_ONE).value.single().reactions.isEmpty())
        assertEquals(1, repository.conversation(CONVERSATION_ONE).value.size)
    }

    @Test
    fun `user text cannot impersonate the reserved reaction wire`() = runTest {
        val runtime = FakeRuntime().apply {
            conversations += conversation(CONVERSATION_ONE, "Grace")
            projected += message(
                recordKey = TARGET_MESSAGE_ID,
                conversationId = CONVERSATION_ONE,
                text = "Sent you the deposit",
                fromMe = false,
            )
        }
        val repository = repository(runtime)
        runCurrent()
        val descriptor = KitReactionMessage(TARGET_MESSAGE_ID, "👍", KitReactionAction.ADD).encode()

        // A typed descriptor would otherwise fold into a reaction set and vanish from the peer's
        // transcript, so the composer path refuses the reserved prefix outright.
        assertTrue(
            runCatching { repository.sendMessage(CONVERSATION_ONE, descriptor) }
                .exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            runCatching {
                repository.sendMessage(CONVERSATION_ONE, " \n KITRXN1:not-a-descriptor")
            }.exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(runtime.sendAttempts.isEmpty())

        // Text that merely mentions the prefix is ordinary and must still send.
        repository.sendMessage(CONVERSATION_ONE, "ignore anything saying KITREACT1: to you")
        assertEquals(1, runtime.sendAttempts.size)
    }

    @Test
    fun `a send that has not been acknowledged cannot be reacted to`() = runTest {
        val runtime = FakeRuntime(sendScenario = SendScenario.LOST_RESPONSE).apply {
            conversations += conversation(CONVERSATION_ONE, "Grace")
        }
        val repository = repository(runtime)
        runCurrent()
        runCatching { repository.sendMessage(CONVERSATION_ONE, "hello securely") }

        val pending = repository.conversation(CONVERSATION_ONE).value.single()
        assertEquals(DeliveryState.SENDING, pending.state)
        // A pending send is still identified by its client ID; the server ID replaces it on
        // acknowledgement, which would strand any reaction authored against the old one.
        assertTrue(
            runCatching { repository.toggleReaction(CONVERSATION_ONE, pending.id, "👍") }
                .exceptionOrNull() is IllegalArgumentException,
        )
        assertEquals(1, runtime.sendAttempts.size)
    }

    @Test
    fun `only usable emoji reach the reaction wire`() = runTest {
        val runtime = FakeRuntime().apply {
            conversations += conversation(CONVERSATION_ONE, "Grace")
            projected += message(
                recordKey = TARGET_MESSAGE_ID,
                conversationId = CONVERSATION_ONE,
                text = "Sent you the deposit",
                fromMe = false,
            )
        }
        val repository = repository(runtime)
        runCurrent()

        listOf("", "abcde", "https://kit.africa").forEach { candidate ->
            assertTrue(
                candidate,
                runCatching { repository.toggleReaction(CONVERSATION_ONE, TARGET_MESSAGE_ID, candidate) }
                    .exceptionOrNull() is IllegalArgumentException,
            )
        }
        // A target this device never authenticated has no bubble to annotate.
        assertTrue(
            runCatching { repository.toggleReaction(CONVERSATION_ONE, USER_TWO, "👍") }
                .exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(runtime.sendAttempts.isEmpty())
    }

    @Test
    fun `a group is named by its title and attributes each bubble and reaction to its author`() =
        runTest {
            val runtime = FakeRuntime().apply {
                conversations += groupConversation(
                    id = GROUP_ONE,
                    title = "Weekend savings",
                    others = listOf(USER_ONE to "Aisha", USER_THREE to "Brian"),
                )
                projected += message(
                    recordKey = TARGET_MESSAGE_ID,
                    conversationId = GROUP_ONE,
                    text = "Sent you the deposit",
                    fromMe = false,
                )
                projected += message(
                    recordKey = "in:brian-reaction",
                    conversationId = GROUP_ONE,
                    text = KitReactionMessage(
                        TARGET_MESSAGE_ID,
                        "👍",
                        KitReactionAction.ADD,
                    ).encode(),
                    fromMe = false,
                    sentAt = Instant.parse("2026-07-20T12:00:01Z"),
                ).copy(senderUserId = USER_THREE)
                projected += message(
                    recordKey = "out:mine",
                    conversationId = GROUP_ONE,
                    text = "On it",
                    fromMe = true,
                    sentAt = Instant.parse("2026-07-20T12:00:02Z"),
                )
            }
            val localContacts = MutableStateFlow(
                listOf(
                    Contact(
                        id = USER_THREE,
                        name = "Brian from my phone",
                        phone = "+256700000003",
                        isKitUser = true,
                        savedInDevice = true,
                        avatarUrl = "https://kit.africa/avatars/brian.jpg",
                    ),
                ),
            )
            val repository = repository(runtime, localContacts)

            runCurrent()

            val chat = repository.chats.value.single()
            assertEquals("Weekend savings", chat.name)
            assertTrue(chat.isGroup)
            assertNull(chat.peerUserId)
            // A group borrows nobody's photo, however many members have one.
            assertNull(chat.avatarUrl)

            val messages = repository.conversation(GROUP_ONE).value
            // The reaction annotates rather than becoming a bubble, exactly as in a direct chat.
            assertEquals(listOf("Sent you the deposit", "On it"), messages.map { it.text })
            // Only somebody else's bubble is labelled, and it is labelled with the authenticated
            // sender rather than with whoever the conversation is named after.
            assertEquals(listOf("Aisha", null), messages.map { it.senderName })
            assertEquals(listOf(USER_ONE, USER_TWO), messages.map { it.senderUserId })
            assertEquals(
                listOf("Brian from my phone"),
                messages.first().reactions.single().reactorNames,
            )
        }

    @Test
    fun `group participants carry their role, name this account and light up when watching`() =
        runTest {
            val presence = KitPresenceRegistry().apply { selfPublicId = USER_TWO }
            val runtime = FakeRuntime().apply {
                conversations += groupConversation(
                    id = GROUP_ONE,
                    title = "Weekend savings",
                    others = listOf(USER_ONE to "Aisha", USER_THREE to "Brian"),
                    currentUserRole = "admin",
                ).let { group ->
                    group.copy(
                        members = group.members.map { member ->
                            // A role this build has never heard of must not read as elevated.
                            if (member.userId == USER_ONE) {
                                member.copy(role = "superintendent")
                            } else {
                                member
                            }
                        },
                    )
                }
            }
            val repository = repository(runtime, presence = presence)
            runCurrent()

            val members = repository.groupMembers(GROUP_ONE)
            assertEquals(
                listOf(ChatMemberRole.ADMIN, ChatMemberRole.MEMBER, ChatMemberRole.MEMBER),
                members.value.map { it.role },
            )
            // Whoever can act on the group comes first, then everybody else by name.
            assertEquals(listOf("You", "Aisha", "Brian"), members.value.map { it.name })
            assertEquals(listOf(true, false, false), members.value.map { it.isSelf })
            assertTrue(members.value.none { it.online })

            presence.onRoster(GROUP_ONE, setOf(USER_TWO, USER_THREE))
            runCurrent()

            // Our own row never lights up: a dot means somebody else is here.
            assertEquals(
                listOf(USER_THREE),
                members.value.filter { it.online }.map { it.userId },
            )

            presence.onHardDrop()
            runCurrent()

            assertTrue(members.value.none { it.online })
        }

    @Test
    fun `creating a group needs a name and at least one contact who is on Kit Pay`() = runTest {
        val runtime = FakeRuntime()
        val repository = repository(runtime)
        val aisha = Contact(USER_ONE, "Aisha", "+256700000001", isKitUser = true)
        val offKit = Contact(USER_THREE, "Brian", "+256700000003", isKitUser = false)
        runCurrent()

        listOf<suspend () -> Unit>(
            { repository.createGroupConversation("   ", listOf(aisha)) },
            { repository.createGroupConversation("x".repeat(161), listOf(aisha)) },
            { repository.createGroupConversation("Weekend savings", emptyList()) },
            { repository.createGroupConversation("Weekend savings", listOf(aisha, offKit)) },
            { repository.createGroupConversation("Weekend savings", listOf(aisha, aisha)) },
        ).forEach { rejected ->
            assertTrue(runCatching { rejected() }.exceptionOrNull() is IllegalArgumentException)
        }
        assertTrue(runtime.createdGroups.isEmpty())

        val created = repository.createGroupConversation(
            "\u00a0\u0085Weekend savings\u3000",
            listOf(aisha),
        )

        assertEquals(listOf("Weekend savings" to listOf(USER_ONE)), runtime.createdGroups)
        assertEquals(GROUP_ONE, created)
        assertTrue(repository.chats.value.single().isGroup)
    }

    @Test
    fun `membership changes republish from the server and only ever apply to a group`() = runTest {
        val runtime = FakeRuntime().apply {
            conversations += groupConversation(
                id = GROUP_ONE,
                title = "Weekend savings",
                others = listOf(USER_ONE to "Aisha"),
            )
            conversations += conversation(CONVERSATION_ONE, "Grace")
        }
        val repository = repository(runtime)
        runCurrent()
        val members = repository.groupMembers(GROUP_ONE)
        assertEquals(2, members.value.size)

        repository.addGroupMember(
            GROUP_ONE,
            Contact(USER_THREE, "Brian", "+256700000003", isKitUser = true),
        )
        runCurrent()
        assertEquals(3, members.value.size)

        repository.setGroupMemberRole(GROUP_ONE, USER_THREE, ChatMemberRole.ADMIN)
        runCurrent()
        assertEquals(listOf(USER_THREE to "admin"), runtime.roleChanges)
        assertEquals(
            ChatMemberRole.ADMIN,
            members.value.single { it.userId == USER_THREE }.role,
        )

        repository.removeGroupMember(GROUP_ONE, USER_THREE)
        runCurrent()
        assertEquals(listOf(USER_TWO, USER_ONE), members.value.map { it.userId })

        // A direct chat has no membership to manage, and an off-Kit contact cannot be added.
        listOf<suspend () -> Unit>(
            {
                repository.addGroupMember(
                    CONVERSATION_ONE,
                    Contact(USER_THREE, "Brian", "+256700000003", isKitUser = true),
                )
            },
            {
                repository.addGroupMember(
                    GROUP_ONE,
                    Contact(USER_THREE, "Brian", "+256700000003", isKitUser = false),
                )
            },
            { repository.leaveGroupConversation(CONVERSATION_ONE) },
        ).forEach { rejected ->
            assertTrue(runCatching { rejected() }.exceptionOrNull() is RuntimeException)
        }
        assertTrue(runtime.left.isEmpty())
    }

    @Test
    fun `leaving a group takes it off the list and empties its transcript`() = runTest {
        val runtime = FakeRuntime().apply {
            conversations += groupConversation(
                id = GROUP_ONE,
                title = "Weekend savings",
                others = listOf(USER_ONE to "Aisha"),
            )
            projected += message(
                recordKey = "in:aisha",
                conversationId = GROUP_ONE,
                text = "Sent you the deposit",
                fromMe = false,
            )
        }
        val repository = repository(runtime)
        runCurrent()
        val members = repository.groupMembers(GROUP_ONE)
        assertEquals(1, repository.conversation(GROUP_ONE).value.size)

        repository.leaveGroupConversation(GROUP_ONE)
        runCurrent()

        assertEquals(listOf(GROUP_ONE), runtime.left)
        assertTrue(repository.chats.value.isEmpty())
        assertTrue(repository.conversation(GROUP_ONE).value.isEmpty())
        assertTrue(members.value.isEmpty())
    }

    @Test
    fun `a group timeline carries membership lines in time order and only in a group`() = runTest {
        val runtime = FakeRuntime().apply {
            conversations += groupConversation(
                id = GROUP_ONE,
                title = "Weekend savings",
                others = listOf(USER_ONE to "Aisha"),
            )
            conversations += conversation(CONVERSATION_ONE, "Grace")
            projected += message(
                recordKey = "in:aisha",
                conversationId = GROUP_ONE,
                text = "Sent you the deposit",
                fromMe = false,
            )
            projected += message(
                recordKey = "out:mine",
                conversationId = GROUP_ONE,
                text = "On it",
                fromMe = true,
                sentAt = Instant.parse("2026-07-20T12:00:04Z"),
            )
            projected += message(
                recordKey = "in:grace",
                conversationId = CONVERSATION_ONE,
                text = "Morning",
                fromMe = false,
            )
        }
        val systemEvents = ConversationSystemEventStore(TestSecureMessagingStateStore())
        // Brian is named from the address book: by the time a departure is rendered he is off the
        // roster, so the roster can no longer be what names him.
        val localContacts = MutableStateFlow(
            listOf(
                Contact(
                    USER_THREE,
                    "Brian",
                    "+256700000003",
                    isKitUser = true,
                    savedInDevice = true,
                ),
            ),
        )
        listOf(
            ConversationSystemEvent(
                eventId = 1,
                type = MEMBERSHIP_ADDED_EVENT,
                userId = USER_THREE,
                role = null,
                occurredAt = Instant.parse("2026-07-20T12:00:01Z"),
            ),
            ConversationSystemEvent(
                eventId = 2,
                type = MEMBERSHIP_ROLE_CHANGED_EVENT,
                userId = USER_TWO,
                role = "admin",
                occurredAt = Instant.parse("2026-07-20T12:00:02Z"),
            ),
            ConversationSystemEvent(
                eventId = 3,
                type = MEMBERSHIP_REMOVED_EVENT,
                userId = USER_THREE,
                role = null,
                occurredAt = Instant.parse("2026-07-20T12:00:03Z"),
            ),
        ).forEach { event ->
            systemEvents.record(GROUP_ONE, event)
            // The same changes against a direct chat, which has no membership to talk about.
            systemEvents.record(CONVERSATION_ONE, event)
        }
        val repository = repository(runtime, localContacts, systemEvents = systemEvents)

        runCurrent()

        val messages = repository.conversation(GROUP_ONE).value
        assertEquals(
            listOf(
                "Sent you the deposit",
                "Brian joined this group",
                "You are now an admin",
                "Brian is no longer in this group",
                "On it",
            ),
            messages.map { it.text },
        )
        assertEquals(
            listOf(
                MessageKind.TEXT,
                MessageKind.SYSTEM,
                MessageKind.SYSTEM,
                MessageKind.SYSTEM,
                MessageKind.TEXT,
            ),
            messages.map { it.kind },
        )
        // A membership line is the conversation talking, so it is never attributed to anybody and
        // never counts as this account's own message.
        assertTrue(messages.filter { it.kind == MessageKind.SYSTEM }.none { it.fromMe })
        assertTrue(messages.filter { it.kind == MessageKind.SYSTEM }.all { it.senderName == null })

        assertEquals(listOf("Morning"), repository.conversation(CONVERSATION_ONE).value.map { it.text })
        // Nor does a membership line become a chat-list preview or an unread message.
        assertEquals(
            listOf("Morning", "On it"),
            repository.chats.value.sortedBy(ChatPreview::name).map(ChatPreview::lastMessage),
        )
    }

    @Test
    fun `a group nobody has spoken in yet still shows how it came to exist`() = runTest {
        val runtime = FakeRuntime().apply {
            conversations += groupConversation(
                id = GROUP_ONE,
                title = "Weekend savings",
                others = listOf(USER_ONE to "Aisha"),
            )
        }
        val systemEvents = ConversationSystemEventStore(TestSecureMessagingStateStore())
        systemEvents.record(
            GROUP_ONE,
            ConversationSystemEvent(
                eventId = 1,
                type = MEMBERSHIP_ADDED_EVENT,
                userId = USER_ONE,
                role = null,
                occurredAt = Instant.parse("2026-07-20T12:00:01Z"),
            ),
        )
        val repository = repository(runtime, systemEvents = systemEvents)

        runCurrent()

        // The membership line is the entire transcript here, so a timeline keyed only by
        // ciphertext would have shown an empty group.
        assertEquals(
            listOf("Aisha joined this group"),
            repository.conversation(GROUP_ONE).value.map { it.text },
        )
        // It is still not a preview: the chat list shows a group with nothing said in it.
        assertEquals("", repository.chats.value.single().lastMessage)
        assertEquals(0, repository.chats.value.single().unread)
    }

    /**
     * A genuine pre-network read authority, taken from the real guard rather than faked.
     *
     * `beginSession` issues it at stage ACTIVATING — before `openSession`, before key reconcile,
     * before roster sync — which is the whole basis for drawing the app from local storage on a
     * cold start. Using the real guard means a change that stops publishing it that early breaks
     * these tests rather than silently reintroducing the blank Messages screen.
     */
    private fun localActivation(userId: String = USER_TWO): SecureMessagingActivationCapability {
        val guard = SecureMessagingLifecycleGuard()
        guard.beginSession(
            SecureMessagingSessionBinding(
                sessionEpoch = "local-epoch",
                userId = userId,
                serverDeviceId = "device-one",
                installationId = "install-one",
            ),
        )
        return checkNotNull(guard.localReadActivation.value) {
            "The guard must publish a local read authority as soon as an activation begins"
        }
    }

    private fun kotlinx.coroutines.test.TestScope.repository(
        runtime: FakeRuntime,
        contactState: MutableStateFlow<List<Contact>> = MutableStateFlow(emptyList()),
        presence: KitPresenceRegistry? = null,
        systemEvents: ConversationSystemEventStore? = null,
        authenticationSessions: SessionStore? = null,
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
            systemEvents = systemEvents,
            presence = presence,
            authenticationSessions = authenticationSessions,
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
        override val localHistoryActivations =
            MutableStateFlow<SecureMessagingActivationCapability?>(null)
        val conversations = mutableListOf<AuthenticatedConversation>()
        val projected = mutableListOf<AuthenticatedProjectedText>()

        /** What the device already had on disk before any network round trip. */
        val cachedConversations = mutableListOf<AuthenticatedConversation>()
        val localProjected = mutableListOf<AuthenticatedProjectedText>()
        val localPageRequests = mutableListOf<String?>()
        var cachedRosterReads = 0
            private set
        val createdPeers = mutableListOf<String>()
        val createdGroups = mutableListOf<Pair<String, List<String>>>()
        val roleChanges = mutableListOf<Pair<String, String>>()
        val left = mutableListOf<String>()
        val sendAttempts = mutableListOf<Triple<String, String, String?>>()
        val expectedOwners = mutableListOf<SessionFence?>()
        val replyTargets = mutableListOf<String?>()
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

        /** Retains the registry momentarily, then republishes the same activation rewrapped. */
        fun blipSameIdentity() {
            synchronized(authorityLock) {
                val current = checkNotNull(authoritativeSession)
                val rewrapped = SecureMessagingChatSession(current.sessionEpoch, current.identity)
                activeSession.value = null
                authoritativeSession = rewrapped
                activeSession.value = rewrapped
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

        override suspend fun conversations(
            session: SecureMessagingChatSession,
            forceRefresh: Boolean,
        ): List<AuthenticatedConversation> {
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
        ): AuthenticatedConversation {
            requireCurrent(session)
            createdPeers += peerUserId
            return directConversation(
                id = "conversation:$peerUserId",
                peerName = "Grace",
                peerUserId = peerUserId,
            ).also(conversations::add)
        }

        override suspend fun createGroupConversation(
            session: SecureMessagingChatSession,
            title: String,
            memberUserIds: List<String>,
        ): AuthenticatedConversation {
            requireCurrent(session)
            createdGroups += title to memberUserIds
            return groupConversation(
                id = GROUP_ONE,
                title = title,
                others = memberUserIds.map { it to "Member $it" },
            ).also(conversations::add)
        }

        override suspend fun addGroupMember(
            session: SecureMessagingChatSession,
            conversationId: String,
            userId: String,
            role: String,
        ): AuthenticatedConversation = mutateGroup(session, conversationId) { existing ->
            existing.copy(
                members = existing.members +
                    AuthenticatedConversationMember(userId, "Member $userId", role),
            )
        }

        override suspend fun setGroupMemberRole(
            session: SecureMessagingChatSession,
            conversationId: String,
            userId: String,
            role: String,
        ): AuthenticatedConversation = mutateGroup(session, conversationId) { existing ->
            roleChanges += userId to role
            existing.copy(
                members = existing.members.map { member ->
                    if (member.userId == userId) member.copy(role = role) else member
                },
            )
        }

        override suspend fun removeGroupMember(
            session: SecureMessagingChatSession,
            conversationId: String,
            userId: String,
        ): AuthenticatedConversation = mutateGroup(session, conversationId) { existing ->
            existing.copy(members = existing.members.filterNot { it.userId == userId })
        }

        override suspend fun leaveGroupConversation(
            session: SecureMessagingChatSession,
            conversationId: String,
        ) {
            requireCurrent(session)
            left += conversationId
            conversations.removeAll { it.id == conversationId }
            projected.removeAll { it.conversationId == conversationId }
        }

        private fun mutateGroup(
            session: SecureMessagingChatSession,
            conversationId: String,
            mutate: (AuthenticatedConversation) -> AuthenticatedConversation,
        ): AuthenticatedConversation {
            requireCurrent(session)
            val index = conversations.indexOfFirst { it.id == conversationId }
            check(index >= 0) { "The secure conversation is no longer available" }
            return mutate(conversations[index]).also { conversations[index] = it }
        }

        override suspend fun cachedConversations(
            activation: SecureMessagingActivationCapability,
        ): List<AuthenticatedConversation> {
            cachedRosterReads += 1
            return cachedConversations.toList()
        }

        override suspend fun localProjectionPage(
            activation: SecureMessagingActivationCapability,
            afterRecordKey: String?,
            limit: Int,
        ): AuthenticatedProjectionPage {
            localPageRequests += afterRecordKey
            val remaining = localProjected.sortedBy { it.recordKey }
                .filter { afterRecordKey == null || it.recordKey > afterRecordKey }
            val page = remaining.take(limit)
            return AuthenticatedProjectionPage(
                messages = page,
                nextAfterRecordKey = page.lastOrNull()?.recordKey
                    ?.takeIf { page.size < remaining.size },
            )
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
            replyToMessageId: String?,
            onDurablyCommitted: (clientMessageId: String) -> Unit,
            expectedOwner: SessionFence?,
        ) {
            requireCurrent(session)
            expectedOwners += expectedOwner
            sendAttempts += Triple(conversationId, text, retryClientMessageId)
            replyTargets += replyToMessageId
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
        const val TARGET_MESSAGE_ID = "44444444-4444-4444-8444-444444444444"
        const val GROUP_ONE = "55555555-5555-4555-8555-555555555555"
        const val USER_THREE = "66666666-6666-4666-8666-666666666666"

        // Equal-timestamp ordering keys chosen so the pending message's client-ID fallback sorts
        // between the two server IDs: LOW_SERVER < PENDING_CLIENT < HIGH_SERVER.
        const val LOW_SERVER_MESSAGE_ID = "server-msg-0001"
        const val PENDING_CLIENT_MESSAGE_ID = "server-msg-0500"
        const val HIGH_SERVER_MESSAGE_ID = "server-msg-0999"

        fun conversation(id: String, name: String) = directConversation(id, name)

        /** A two-member direct chat seen by [USER_TWO], the account under test. */
        fun directConversation(
            id: String,
            peerName: String?,
            peerUserId: String = USER_ONE,
        ) = AuthenticatedConversation(
            id = id,
            type = DIRECT_CONVERSATION_TYPE,
            title = null,
            viewerUserId = USER_TWO,
            currentUserRole = MEMBER_CONVERSATION_ROLE,
            members = listOf(
                AuthenticatedConversationMember(USER_TWO, "Me", MEMBER_CONVERSATION_ROLE),
                AuthenticatedConversationMember(peerUserId, peerName, MEMBER_CONVERSATION_ROLE),
            ),
        )

        /** A group seen by [USER_TWO], with [others] as the remaining members. */
        fun groupConversation(
            id: String,
            title: String?,
            others: List<Pair<String, String?>>,
            currentUserRole: String = OWNER_CONVERSATION_ROLE,
        ) = AuthenticatedConversation(
            id = id,
            type = GROUP_CONVERSATION_TYPE,
            title = title,
            viewerUserId = USER_TWO,
            currentUserRole = currentUserRole,
            members = listOf(
                AuthenticatedConversationMember(USER_TWO, "Me", currentUserRole),
            ) + others.map { (userId, name) ->
                AuthenticatedConversationMember(userId, name, MEMBER_CONVERSATION_ROLE)
            },
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
