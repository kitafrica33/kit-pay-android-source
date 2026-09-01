package com.kit.wallet

import com.kit.wallet.data.messaging.SecureMediaAlbumSource
import com.kit.wallet.data.messaging.SecureMediaCache
import com.kit.wallet.data.messaging.SecureMediaFile
import com.kit.wallet.data.messaging.PreparedSecureMedia
import com.kit.wallet.data.messaging.SecureMediaProcessingPlan
import com.kit.wallet.data.messaging.SecureMediaSource
import com.kit.wallet.data.messaging.SecureMediaUploadProcessor
import com.kit.wallet.data.messaging.SecureMediaVideoEditPlan
import com.kit.wallet.data.messaging.ConversationSystemEvent
import com.kit.wallet.data.messaging.ConversationSystemEventStore
import com.kit.wallet.data.messaging.LibSignalCompanionDirection
import com.kit.wallet.data.messaging.KitEditMessage
import com.kit.wallet.data.messaging.KitMediaMessage
import com.kit.wallet.data.messaging.KitMediaMessageV2
import com.kit.wallet.data.messaging.KitMediaMessageV2Item
import com.kit.wallet.data.messaging.MediaAttachmentCipher
import com.kit.wallet.data.messaging.MessagingRichMediaCapability
import com.kit.wallet.data.messaging.KitPaymentAction
import com.kit.wallet.data.messaging.KitPaymentMessage
import com.kit.wallet.data.messaging.KitReactionAction
import com.kit.wallet.data.messaging.KitReactionMessage
import com.kit.wallet.data.messaging.ImmediateMediaSpool
import com.kit.wallet.data.messaging.ImmediateMediaPreparationOutcome
import com.kit.wallet.data.messaging.ImmediateSendDispatcher
import com.kit.wallet.data.messaging.ImmediateSendDispatchOutcome
import com.kit.wallet.data.messaging.ImmediateSendIntent
import com.kit.wallet.data.messaging.ImmediateSendIntentStore
import com.kit.wallet.data.messaging.ImmediateSendKind
import com.kit.wallet.data.messaging.ImmediateSendState
import com.kit.wallet.data.messaging.LocalMediaAvailabilityState
import com.kit.wallet.data.messaging.LocalMediaCollection
import com.kit.wallet.data.messaging.LocalMediaLibrary
import com.kit.wallet.data.messaging.MEMBERSHIP_ADDED_EVENT
import com.kit.wallet.data.messaging.MEMBERSHIP_REMOVED_EVENT
import com.kit.wallet.data.messaging.MEMBERSHIP_ROLE_CHANGED_EVENT
import com.kit.wallet.data.messaging.SecureMessagingActivationCapability
import com.kit.wallet.data.messaging.SecureMessagingLifecycleGuard
import com.kit.wallet.data.messaging.SecureMessagingRecordKeyPermanentlyMissingException
import com.kit.wallet.data.messaging.SecureMessagingConversationCapabilityUnavailableException
import com.kit.wallet.data.messaging.SecureMessagingSessionBinding
import com.kit.wallet.data.messaging.mediaAlbumAccessibilityLabel
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
import com.kit.wallet.data.repository.SecureMessagingPendingPredecessorException
import com.kit.wallet.data.repository.projectionIsFromCurrentUser
import com.kit.wallet.data.remote.KitWalletApiException
import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.KitGroupPaymentRequestAction
import com.kit.wallet.data.remote.KitGroupPaymentRequestMessage
import com.kit.wallet.data.session.SessionFence
import com.kit.wallet.data.session.SessionInvalidatedException
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.ui.model.ChatMemberRole
import com.kit.wallet.ui.model.ChatPreview
import com.kit.wallet.ui.model.Contact
import com.kit.wallet.ui.model.AccountVerification
import com.kit.wallet.ui.model.AccountVerificationDesignation
import com.kit.wallet.ui.model.DeliveryState
import com.kit.wallet.ui.model.MessageKind
import com.kit.wallet.ui.model.copyablePlaintext
import com.kit.wallet.ui.model.replyPreviewLabel
import java.io.File
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
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
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

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
    fun `v2 album maps as one caption-safe message across presentation actions`() = runTest {
        val descriptor = mediaV2Descriptor(caption = "Family photos")
        val runtime = FakeRuntime().apply {
            conversations += conversation(CONVERSATION_ONE, "Grace")
            projected += message(
                recordKey = "in:album",
                conversationId = CONVERSATION_ONE,
                text = descriptor.encode(),
                fromMe = false,
            )
        }
        val repository = repository(runtime)

        runCurrent()

        val album = repository.conversation(CONVERSATION_ONE).value.single()
        assertEquals(MessageKind.MEDIA_ALBUM, album.kind)
        assertEquals("Family photos", album.text)
        assertEquals("Family photos", album.mediaCaption)
        assertEquals(2, album.mediaItems.size)
        assertEquals("Family photos", album.copyablePlaintext())
        assertEquals("Photo +1 · Family photos", album.replyPreviewLabel())
        assertEquals(
            "2 Attachments · Family photos",
            mediaAlbumAccessibilityLabel(
                album.mediaItems.map { it.mediaType },
                album.mediaCaption,
            ),
        )
        assertFalse(album.text.contains(descriptor.items.first().keyMaterialBase64))
        assertEquals("2 Attachments · Family photos", repository.chats.value.single().lastMessage)
    }

    @Test
    fun `unparsed v2 projection never becomes copyable or quotable protocol text`() = runTest {
        val raw = "${KitMediaMessageV2.PREFIX}v=2&n=2&key0=private-key-material"
        val runtime = FakeRuntime().apply {
            conversations += conversation(CONVERSATION_ONE, "Grace")
            projected += message("in:bad-album", CONVERSATION_ONE, raw, fromMe = false)
        }
        val repository = repository(runtime)

        runCurrent()

        val placeholder = repository.conversation(CONVERSATION_ONE).value.single()
        assertEquals(MessageKind.UNSUPPORTED_ATTACHMENT, placeholder.kind)
        assertEquals("Attachment", placeholder.text)
        assertNull(placeholder.copyablePlaintext())
        assertEquals("Attachment", placeholder.replyPreviewLabel())
        assertFalse(repository.chats.value.single().lastMessage.contains("private-key-material"))
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
    fun `contact designation decorates only its exact direct peer and matching group member`() =
        runTest {
            val runtime = FakeRuntime().apply {
                conversations += conversation(CONVERSATION_ONE, "Registered Flora")
                conversations += groupConversation(
                    id = GROUP_ONE,
                    title = "Site team",
                    others = listOf(USER_ONE to "Registered Flora", USER_THREE to "Brian"),
                )
            }
            val official = AccountVerification(
                designation = AccountVerificationDesignation.OFFICIAL,
                since = "2026-08-28T10:00:00Z",
            )
            val localContacts = MutableStateFlow(
                listOf(
                    Contact(
                        id = USER_ONE.uppercase(),
                        name = "Flora from my phone",
                        phone = "+256700000001",
                        isKitUser = true,
                        savedInDevice = true,
                        accountVerification = official,
                    ),
                ),
            )
            val repository = repository(runtime, localContacts)

            runCurrent()

            val direct = repository.chats.value.single { it.id == CONVERSATION_ONE }
            val group = repository.chats.value.single { it.id == GROUP_ONE }
            assertEquals(official, direct.accountVerification)
            assertNull(group.accountVerification)
            assertEquals(
                official,
                repository.groupMembers(GROUP_ONE).value
                    .single { it.userId == USER_ONE }
                    .accountVerification,
            )
            assertTrue(
                repository.groupMembers(GROUP_ONE).value
                    .filterNot { it.userId == USER_ONE }
                    .all { it.accountVerification == null },
            )
        }

    @Test
    fun `authenticated roster metadata covers first sighting direct and group identities`() =
        runTest {
            val official = AccountVerification(
                designation = AccountVerificationDesignation.OFFICIAL_SUPPORT,
                since = "2026-08-29T10:00:00Z",
            )
            val peerAvatar = "https://pay.kit.africa/media/avatars/support"
            val runtime = FakeRuntime().apply {
                conversations += conversation(CONVERSATION_ONE, "Kit Customer Support").let {
                    it.copy(
                        members = it.members.map { member ->
                            if (member.userId == USER_ONE) {
                                member.copy(
                                    avatarUrl = peerAvatar,
                                    accountVerification = official,
                                )
                            } else {
                                member
                            }
                        },
                    )
                }
                conversations += groupConversation(
                    id = GROUP_ONE,
                    title = "Support team",
                    others = listOf(USER_ONE to "Kit Customer Support", USER_THREE to "Brian"),
                ).let {
                    it.copy(
                        members = it.members.map { member ->
                            if (member.userId == USER_ONE) {
                                member.copy(
                                    avatarUrl = peerAvatar,
                                    accountVerification = official,
                                )
                            } else {
                                member
                            }
                        },
                    )
                }
            }
            val repository = repository(runtime, MutableStateFlow(emptyList()))

            runCurrent()

            val direct = repository.chats.value.single { it.id == CONVERSATION_ONE }
            val group = repository.chats.value.single { it.id == GROUP_ONE }
            val member = repository.groupMembers(GROUP_ONE).value
                .single { it.userId == USER_ONE }
            assertEquals(peerAvatar, direct.avatarUrl)
            assertEquals(official, direct.accountVerification)
            assertNull(group.accountVerification)
            assertEquals(peerAvatar, member.avatarUrl)
            assertEquals(official, member.accountVerification)
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
    fun `media bubble appears while encryption is still preparing the durable queue item`() = runTest {
        val encryptionStarted = CompletableDeferred<Unit>()
        val releaseEncryption = CompletableDeferred<Unit>()
        val directory = Files.createTempDirectory("kit-immediate-staging-test").toFile()
        val bytes = "selected photo bytes".toByteArray()
        try {
            val spool = ImmediateMediaSpool(
                directory = directory,
                beforeEncryption = {
                    encryptionStarted.complete(Unit)
                    releaseEncryption.await()
                },
            )
            val runtime = FakeRuntime().apply {
                conversations += conversation(CONVERSATION_ONE, "Grace")
            }
            val authentication = MutableTestSessionStore(
                testSession(USER_TWO, sessionId = "session-one"),
            )
            val queue = ImmediateSendIntentStore(TestSecureMessagingStateStore(), authentication)
            val repository = repository(
                runtime,
                authenticationSessions = authentication,
                immediateSends = queue,
                immediateMediaSpool = spool,
            )
            runCurrent()

            val send = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                repository.sendImageMessage(
                    CONVERSATION_ONE,
                    bytes,
                    "image/jpeg",
                    "Receipt",
                )
            }
            encryptionStarted.await()
            runCurrent()

            val preparing = repository.conversation(CONVERSATION_ONE).value.single()
            assertEquals(MessageKind.IMAGE, preparing.kind)
            assertEquals(DeliveryState.SENDING, preparing.state)
            assertEquals("Receipt", preparing.text)
            assertTrue(queue.items.value.isEmpty())
            val openFailure = runCatching {
                repository.openImageMessage(
                    CONVERSATION_ONE,
                    checkNotNull(preparing.mediaDescriptor),
                )
            }.exceptionOrNull()
            assertEquals("This secure attachment is still being prepared", openFailure?.message)

            releaseEncryption.complete(Unit)
            send.join()
            runCurrent()

            assertEquals(1, queue.items.value.size)
            assertEquals(
                listOf(queue.items.value.single().id),
                repository.conversation(CONVERSATION_ONE).value.map { it.id },
            )
        } finally {
            releaseEncryption.complete(Unit)
            bytes.fill(0)
            directory.deleteRecursively()
        }
    }

    @Test
    fun `selected media is locally readable before encryption and after descriptor promotion`() =
        runTest {
            val encryptionStarted = CompletableDeferred<Unit>()
            val releaseEncryption = CompletableDeferred<Unit>()
            val spoolDirectory = Files.createTempDirectory("kit-local-media-preview-test").toFile()
            val cacheDirectory = Files.createTempDirectory("kit-local-media-preview-cache").toFile()
            val bytes = "selected photo survives promotion".toByteArray()
            try {
                val spool = ImmediateMediaSpool(
                    directory = spoolDirectory,
                    beforeEncryption = {
                        encryptionStarted.complete(Unit)
                        releaseEncryption.await()
                    },
                )
                val runtime = FakeRuntime().apply {
                    conversations += conversation(CONVERSATION_ONE, "Grace")
                }
                val authentication = MutableTestSessionStore(
                    testSession(USER_TWO, sessionId = "session-one"),
                )
                val queue = ImmediateSendIntentStore(
                    TestSecureMessagingStateStore(),
                    authentication,
                )
                val repository = repository(
                    runtime,
                    authenticationSessions = authentication,
                    immediateSends = queue,
                    immediateMediaSpool = spool,
                    secureMediaCache = SecureMediaCache(cacheDirectory),
                )
                runCurrent()

                repository.sendImageMessage(
                    CONVERSATION_ONE,
                    bytes,
                    "image/jpeg",
                    "Receipt",
                )
                runCurrent()

                val preparing = repository.conversation(CONVERSATION_ONE).value.single()
                assertTrue(preparing.mediaPlaintextBytes > 0)
                assertEquals(ImmediateSendState.PREPARING, queue.items.value.single().state)
                val local = repository.openImageMessage(
                    CONVERSATION_ONE,
                    checkNotNull(preparing.mediaDescriptor),
                )
                assertTrue(local.file.readBytes().contentEquals(bytes))

                val dispatch = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) {
                    ImmediateSendDispatcher(queue, spool, repository).dispatch()
                }
                encryptionStarted.await()
                assertEquals(ImmediateSendState.PREPARING, queue.items.value.single().state)
                releaseEncryption.complete(Unit)
                assertEquals(ImmediateSendDispatchOutcome.COMMITTED, dispatch.await())
                runCurrent()
                val promotedDescriptor = runtime.sendAttempts.single().second
                spoolDirectory.listFiles().orEmpty().forEach(File::delete)

                // The wire descriptor changed, but its authenticated attachment id did not. The
                // sender therefore reuses the selected local file without a server round trip.
                val reopened = repository.openImageMessage(CONVERSATION_ONE, promotedDescriptor)
                assertEquals(local.file, reopened.file)
                assertTrue(reopened.file.readBytes().contentEquals(bytes))
            } finally {
                releaseEncryption.complete(Unit)
                bytes.fill(0)
                spoolDirectory.deleteRecursively()
                cacheDirectory.deleteRecursively()
            }
        }

    @Test
    fun `offline capability refresh keeps video pending locally and retry sends only once`() =
        runTest {
            val server = MockWebServer().apply { start() }
            val disk = TestSecureMessagingStateStore()
            val authentication = MutableTestSessionStore(
                testSession(USER_TWO, sessionId = "session-one"),
            )
            val owner = checkNotNull(authentication.current()).fence()
            val queue = ImmediateSendIntentStore(disk, authentication)
            val library = LocalMediaLibrary(disk, authentication)
            val spoolDirectory = Files.createTempDirectory("kit-capability-retry-spool").toFile()
            val cacheDirectory = Files.createTempDirectory("kit-capability-retry-cache").toFile()
            val bytes = "offline video stays playable".toByteArray()
            try {
                val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
                val api = Retrofit.Builder()
                    .baseUrl(server.url("/"))
                    .addConverterFactory(MoshiConverterFactory.create(moshi))
                    .build()
                    .create(KitWalletApi::class.java)
                val capability = MessagingRichMediaCapability(api, ApiCallExecutor(moshi))
                val runtime = FakeRuntime().apply {
                    conversations += conversation(CONVERSATION_ONE, "Grace")
                }
                val spool = ImmediateMediaSpool(spoolDirectory)
                val repository = repository(
                    runtime,
                    authenticationSessions = authentication,
                    immediateSends = queue,
                    immediateMediaSpool = spool,
                    secureMediaCache = SecureMediaCache(cacheDirectory),
                    localMediaLibrary = library,
                    richMediaCapability = capability,
                )
                runCurrent()

                repository.sendMediaMessage(
                    chatId = CONVERSATION_ONE,
                    source = SecureMediaSource.ofBytes(
                        bytes,
                        originalMediaType = "video/mp4",
                    ),
                    mediaType = "video/mp4",
                    caption = "Local before network",
                )
                runCurrent()

                val queued = queue.items.value.single()
                val localDescriptor = checkNotNull(
                    repository.conversation(CONVERSATION_ONE).value.single().mediaDescriptor,
                )
                val localRecord = checkNotNull(library.find(owner, queued.id))
                assertEquals(queued.id, localRecord.messageId)
                assertEquals(queued.id, localRecord.mediaId)
                assertEquals(LocalMediaAvailabilityState.AVAILABLE, localRecord.availabilityState)
                assertTrue(
                    repository.openImageMessage(CONVERSATION_ONE, localDescriptor)
                        .file.readBytes().contentEquals(bytes),
                )

                server.enqueue(
                    MockResponse()
                        .setResponseCode(503)
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                            """{"ok":false,"error":{"code":"TEMPORARY","message":"Retry later"}}""",
                        ),
                )
                val dispatcher = ImmediateSendDispatcher(queue, spool, repository)

                assertEquals(ImmediateSendDispatchOutcome.RETRY, dispatcher.dispatch())
                assertEquals(queued.id, queue.items.value.single().id)
                assertTrue(runtime.sendAttempts.isEmpty())
                assertTrue(
                    repository.openImageMessage(CONVERSATION_ONE, localDescriptor)
                        .file.readBytes().contentEquals(bytes),
                )

                server.enqueue(
                    MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                            """{"ok":true,"data":{"currency":{"code":"UGX","scale":"2"},"protocols":{"messaging":{"ready":true,"version":"1","rich_media":{"ready":true,"profile":"kit-media-v1","supported_platforms":["ios","android"],"minimum_ciphertext_bytes":64,"maximum_plaintext_bytes":209715200,"maximum_ciphertext_bytes":209715264,"media_types":["video/mp4"]}}}},"meta":{"request_id":"request-1"}}""",
                        ),
                )

                assertEquals(ImmediateSendDispatchOutcome.COMMITTED, dispatcher.dispatch())
                assertTrue(queue.items.value.isEmpty())
                assertEquals(listOf(queued.id), runtime.idempotentClientIds)
                assertEquals(1, runtime.projected.count { it.clientMessageId == queued.id })
                assertEquals(ImmediateSendDispatchOutcome.IDLE, dispatcher.dispatch())
                assertEquals(2, server.requestCount)
            } finally {
                bytes.fill(0)
                server.shutdown()
                spoolDirectory.deleteRecursively()
                cacheDirectory.deleteRecursively()
            }
        }

    @Test
    fun `durable importing bubble and captured original are available while copy is still running`() =
        runTest {
            val disk = TestSecureMessagingStateStore()
            val authentication = MutableTestSessionStore(
                testSession(USER_TWO, sessionId = "session-one"),
            )
            val owner = checkNotNull(authentication.current()).fence()
            val queue = ImmediateSendIntentStore(disk, authentication)
            val library = LocalMediaLibrary(disk, authentication)
            val spoolDirectory = Files.createTempDirectory("kit-importing-spool").toFile()
            val cacheDirectory = Files.createTempDirectory("kit-importing-cache").toFile()
            val sourceFile = Files.createTempFile("kit-captured-video", ".mp4").toFile()
            val bytes = ByteArray(8_192) { (it % 251).toByte() }
            sourceFile.writeBytes(bytes)
            val readStarted = CountDownLatch(1)
            val releaseRead = CountDownLatch(1)
            val source = SecureMediaSource(
                declaredByteCount = bytes.size.toLong(),
                localPlaybackFile = sourceFile,
            ) {
                object : ByteArrayInputStream(bytes) {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        readStarted.countDown()
                        check(releaseRead.await(5, TimeUnit.SECONDS))
                        return super.read(buffer, offset, length)
                    }
                }
            }
            try {
                val runtime = FakeRuntime().apply {
                    conversations += conversation(CONVERSATION_ONE, "Grace")
                }
                val repository = repository(
                    runtime,
                    authenticationSessions = authentication,
                    immediateSends = queue,
                    immediateMediaSpool = ImmediateMediaSpool(spoolDirectory),
                    secureMediaCache = SecureMediaCache(cacheDirectory),
                    localMediaLibrary = library,
                )
                runCurrent()

                val send = backgroundScope.async(Dispatchers.IO) {
                    repository.sendMediaMessage(
                        CONVERSATION_ONE,
                        source,
                        "video/mp4",
                        "Local first",
                    )
                }
                assertTrue(readStarted.await(5, TimeUnit.SECONDS))
                runCurrent()

                val importing = queue.items.value.single()
                assertEquals(ImmediateSendState.IMPORTING, importing.state)
                assertEquals(importing.id, repository.conversation(CONVERSATION_ONE).value.single().id)
                val immediatelyPlayable = repository.openImageMessage(
                    CONVERSATION_ONE,
                    checkNotNull(repository.conversation(CONVERSATION_ONE).value.single().mediaDescriptor),
                )
                assertEquals(sourceFile, immediatelyPlayable.file)
                assertTrue(immediatelyPlayable.file.readBytes().contentEquals(bytes))
                assertEquals(
                    LocalMediaAvailabilityState.MISSING,
                    checkNotNull(library.find(owner, importing.id)).availabilityState,
                )

                // The large local copy does not hold this conversation's acceptance lock: text can
                // be durably queued behind the reserved media position while the copy continues.
                repository.sendMessage(CONVERSATION_ONE, "while importing")
                assertEquals(2, queue.items.value.size)
                assertEquals("while importing", queue.items.value.last().text)

                releaseRead.countDown()
                send.await()
                assertEquals(ImmediateSendState.PREPARING, queue.items.value.first().state)
                assertEquals(
                    LocalMediaAvailabilityState.AVAILABLE,
                    checkNotNull(library.find(owner, importing.id)).availabilityState,
                )
            } finally {
                releaseRead.countDown()
                bytes.fill(0)
                sourceFile.delete()
                spoolDirectory.deleteRecursively()
                cacheDirectory.deleteRecursively()
            }
        }

    @Test
    fun `retained preparing media survives process death and completes from its local copy`() =
        runTest {
            val disk = TestSecureMessagingStateStore()
            val authentication = MutableTestSessionStore(
                testSession(USER_TWO, sessionId = "session-one"),
            )
            val firstQueue = ImmediateSendIntentStore(disk, authentication)
            val spoolDirectory = Files.createTempDirectory("kit-preparing-restart-spool").toFile()
            val cacheDirectory = Files.createTempDirectory("kit-preparing-restart-cache").toFile()
            try {
                val runtime = FakeRuntime().apply {
                    conversations += conversation(CONVERSATION_ONE, "Grace")
                }
                val firstRepository = repository(
                    runtime,
                    authenticationSessions = authentication,
                    immediateSends = firstQueue,
                    immediateMediaSpool = ImmediateMediaSpool(spoolDirectory),
                    secureMediaCache = SecureMediaCache(cacheDirectory),
                )
                runCurrent()

                firstRepository.sendImageMessage(
                    CONVERSATION_ONE,
                    "survives before encryption".toByteArray(),
                    "image/jpeg",
                    "Receipt",
                )
                runCurrent()

                val preparing = firstQueue.items.value.single()
                assertEquals(ImmediateSendState.PREPARING, preparing.state)
                assertTrue(spoolDirectory.listFiles().orEmpty().none { it.name.endsWith(".ciphertext") })

                // A fresh queue, repository, cache and spool represent a genuine process restart.
                val restartedQueue = ImmediateSendIntentStore(disk, authentication)
                val restartedSpool = ImmediateMediaSpool(spoolDirectory)
                val restartedRepository = repository(
                    runtime,
                    authenticationSessions = authentication,
                    immediateSends = restartedQueue,
                    immediateMediaSpool = restartedSpool,
                    secureMediaCache = SecureMediaCache(cacheDirectory),
                )
                runCurrent()

                assertEquals(
                    ImmediateSendDispatchOutcome.COMMITTED,
                    ImmediateSendDispatcher(
                        restartedQueue,
                        restartedSpool,
                        restartedRepository,
                    ).dispatch(),
                )
                assertTrue(restartedQueue.items.value.isEmpty())
                assertEquals(listOf(preparing.id), runtime.idempotentClientIds)
            } finally {
                spoolDirectory.deleteRecursively()
                cacheDirectory.deleteRecursively()
            }
        }

    @Test
    fun `photo is visible and keeps its sender original while restarted optimization waits`() =
        runTest {
            val disk = TestSecureMessagingStateStore()
            val authentication = MutableTestSessionStore(
                testSession(USER_TWO, sessionId = "session-one"),
            )
            val queue = ImmediateSendIntentStore(disk, authentication)
            val spoolDirectory = Files.createTempDirectory("kit-photo-local-first-spool").toFile()
            val cacheDirectory = Files.createTempDirectory("kit-photo-local-first-cache").toFile()
            val rawOriginal = "raw heic original selected by the sender".toByteArray()
            val optimizedWireImage = "background jpeg representation".toByteArray()
            val processorStarted = CompletableDeferred<Unit>()
            val releaseProcessor = CompletableDeferred<Unit>()
            var processorCalls = 0
            val processor = object : SecureMediaUploadProcessor {
                override suspend fun prepare(
                    original: SecureMediaFile,
                    plan: SecureMediaProcessingPlan,
                    videoEditPlan: SecureMediaVideoEditPlan?,
                    maximumPlaintextBytes: Int,
                ): PreparedSecureMedia {
                    processorCalls += 1
                    assertEquals(SecureMediaProcessingPlan.CHAT_IMAGE_JPEG, plan)
                    assertEquals(null, videoEditPlan)
                    assertEquals("image/heic", original.mediaType)
                    assertTrue(original.file.readBytes().contentEquals(rawOriginal))
                    processorStarted.complete(Unit)
                    releaseProcessor.await()
                    val output = File.createTempFile("kit-wire-image-", ".jpg")
                    output.writeBytes(optimizedWireImage)
                    return PreparedSecureMedia(output, deleteAfterUse = true)
                }
            }
            try {
                val runtime = FakeRuntime().apply {
                    conversations += conversation(CONVERSATION_ONE, "Grace")
                    localHistoryActivations.value = localActivation()
                }
                val firstRepository = repository(
                    runtime,
                    authenticationSessions = authentication,
                    immediateSends = queue,
                    immediateMediaSpool = ImmediateMediaSpool(spoolDirectory),
                    secureMediaCache = SecureMediaCache(cacheDirectory),
                    localMediaLibrary = LocalMediaLibrary(disk, authentication),
                )
                runCurrent()

                firstRepository.sendMediaMessage(
                    chatId = CONVERSATION_ONE,
                    source = SecureMediaSource.ofBytes(
                        rawOriginal,
                        originalMediaType = "image/heic",
                        processingPlan = SecureMediaProcessingPlan.CHAT_IMAGE_JPEG,
                    ),
                    mediaType = "image/jpeg",
                    caption = "Original stays local",
                )
                runCurrent()

                val preparing = queue.items.value.single()
                assertEquals(ImmediateSendState.PREPARING, preparing.state)
                assertEquals(0, processorCalls)
                val firstBubble = firstRepository.conversation(CONVERSATION_ONE).value.single()
                assertEquals(preparing.id, firstBubble.id)
                assertTrue(
                    firstRepository.openImageMessage(
                        CONVERSATION_ONE,
                        checkNotNull(firstBubble.mediaDescriptor),
                    ).file.readBytes().contentEquals(rawOriginal),
                )

                // A fresh repository/store models process death before image optimization began.
                // The persisted plan must reproduce the wire transformation without replacing the
                // sender's already-visible HEIC original.
                val restartedQueue = ImmediateSendIntentStore(disk, authentication)
                val restartedSpool = ImmediateMediaSpool(spoolDirectory)
                val restartedRepository = repository(
                    runtime,
                    authenticationSessions = authentication,
                    immediateSends = restartedQueue,
                    immediateMediaSpool = restartedSpool,
                    secureMediaCache = SecureMediaCache(cacheDirectory),
                    localMediaLibrary = LocalMediaLibrary(disk, authentication),
                    secureMediaUploadProcessor = processor,
                )
                runCurrent()
                val preparation = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) {
                    ImmediateSendDispatcher(
                        restartedQueue,
                        restartedSpool,
                        restartedRepository,
                    ).prepareLocalMedia()
                }
                processorStarted.await()

                val visibleWhileProcessing =
                    restartedRepository.conversation(CONVERSATION_ONE).value.single()
                assertEquals(preparing.id, visibleWhileProcessing.id)
                assertTrue(
                    restartedRepository.openImageMessage(
                        CONVERSATION_ONE,
                        checkNotNull(visibleWhileProcessing.mediaDescriptor),
                    ).file.readBytes().contentEquals(rawOriginal),
                )

                releaseProcessor.complete(Unit)
                assertEquals(ImmediateMediaPreparationOutcome.PREPARED, preparation.await())
                val waiting = restartedQueue.items.value.single()
                assertEquals(ImmediateSendState.WAITING, waiting.state)
                assertEquals(rawOriginal.size, waiting.mediaOriginalPlaintextBytes)
                assertEquals(optimizedWireImage.size, waiting.mediaPlaintextBytes)

                // The optimized bytes are only the encrypted wire representation. Playback keeps
                // resolving to the retained sender original before and after that checkpoint.
                assertTrue(
                    restartedRepository.openImageMessage(
                        CONVERSATION_ONE,
                        checkNotNull(
                            restartedRepository.conversation(CONVERSATION_ONE)
                                .value.single().mediaDescriptor,
                        ),
                    ).file.readBytes().contentEquals(rawOriginal),
                )
                val ciphertext = restartedSpool.ciphertextFile(waiting).readBytes()
                val wirePlaintext = MediaAttachmentCipher.decrypt(
                    ciphertext,
                    waiting.mediaKeyMaterial(),
                    waiting.mediaSha256(),
                )
                assertTrue(wirePlaintext.contentEquals(optimizedWireImage))
                ciphertext.fill(0)
                wirePlaintext.fill(0)
            } finally {
                releaseProcessor.complete(Unit)
                rawOriginal.fill(0)
                optimizedWireImage.fill(0)
                spoolDirectory.deleteRecursively()
                cacheDirectory.deleteRecursively()
            }
        }

    @Test
    fun `edited video is playable before restart-safe background remux completes`() = runTest {
        val disk = TestSecureMessagingStateStore()
        val authentication = MutableTestSessionStore(
            testSession(USER_TWO, sessionId = "session-one"),
        )
        val owner = checkNotNull(authentication.current()).fence()
        val queue = ImmediateSendIntentStore(disk, authentication)
        val spoolDirectory = Files.createTempDirectory("kit-video-edit-spool").toFile()
        val cacheDirectory = Files.createTempDirectory("kit-video-edit-cache").toFile()
        val sourceFile = Files.createTempFile("kit-video-edit-original", ".mov").toFile()
        val originalBytes = "sender-owned quicktime original".toByteArray()
        val optimized = "background mp4 trim without audio".toByteArray()
        sourceFile.writeBytes(originalBytes)
        val edit = SecureMediaVideoEditPlan(
            startMicros = 1_000_000,
            endMicros = 11_000_000,
            keepAudio = false,
        )
        val processorStarted = CompletableDeferred<Unit>()
        val releaseProcessor = CompletableDeferred<Unit>()
        val processor = object : SecureMediaUploadProcessor {
            override suspend fun prepare(
                original: SecureMediaFile,
                plan: SecureMediaProcessingPlan,
                videoEditPlan: SecureMediaVideoEditPlan?,
                maximumPlaintextBytes: Int,
            ): PreparedSecureMedia {
                assertEquals(SecureMediaProcessingPlan.CHAT_VIDEO_MP4, plan)
                assertEquals(edit, videoEditPlan)
                assertEquals("video/quicktime", original.mediaType)
                assertTrue(original.file.readBytes().contentEquals(originalBytes))
                processorStarted.complete(Unit)
                releaseProcessor.await()
                val output = File.createTempFile("kit-wire-video-", ".mp4")
                output.writeBytes(optimized)
                return PreparedSecureMedia(output, deleteAfterUse = true)
            }
        }
        try {
            val runtime = FakeRuntime().apply {
                conversations += conversation(CONVERSATION_ONE, "Grace")
                localHistoryActivations.value = localActivation()
            }
            val firstRepository = repository(
                runtime,
                authenticationSessions = authentication,
                immediateSends = queue,
                immediateMediaSpool = ImmediateMediaSpool(spoolDirectory),
                secureMediaCache = SecureMediaCache(cacheDirectory),
                localMediaLibrary = LocalMediaLibrary(disk, authentication),
            )
            runCurrent()

            firstRepository.sendMediaMessage(
                chatId = CONVERSATION_ONE,
                source = SecureMediaSource.ofFile(
                    sourceFile,
                    originalMediaType = "video/quicktime",
                    durationMillis = 10_000,
                    processingPlan = SecureMediaProcessingPlan.CHAT_VIDEO_MP4,
                    videoEditPlan = edit,
                ),
                mediaType = "video/mp4",
                caption = "Async edit",
            )
            runCurrent()

            val preparing = queue.items.value.single()
            assertEquals(ImmediateSendState.PREPARING, preparing.state)
            assertEquals(edit, preparing.mediaVideoEditPlan)
            assertEquals(10_000L, preparing.mediaDurationMillis)
            assertEquals(
                10_000L,
                LocalMediaLibrary(disk, authentication).find(owner, preparing.id)?.durationMillis,
            )
            val bubble = firstRepository.conversation(CONVERSATION_ONE).value.single()
            assertEquals(preparing.id, bubble.id)
            assertTrue(
                firstRepository.openImageMessage(
                    CONVERSATION_ONE,
                    checkNotNull(bubble.mediaDescriptor),
                ).file.readBytes().contentEquals(originalBytes),
            )

            val restartedQueue = ImmediateSendIntentStore(disk, authentication)
            val restartedSpool = ImmediateMediaSpool(spoolDirectory)
            val restartedRepository = repository(
                runtime,
                authenticationSessions = authentication,
                immediateSends = restartedQueue,
                immediateMediaSpool = restartedSpool,
                secureMediaCache = SecureMediaCache(cacheDirectory),
                localMediaLibrary = LocalMediaLibrary(disk, authentication),
                secureMediaUploadProcessor = processor,
            )
            runCurrent()
            val preparation = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) {
                ImmediateSendDispatcher(
                    restartedQueue,
                    restartedSpool,
                    restartedRepository,
                ).prepareLocalMedia()
            }
            processorStarted.await()

            val whileProcessing = restartedRepository.conversation(CONVERSATION_ONE).value.single()
            assertTrue(
                restartedRepository.openImageMessage(
                    CONVERSATION_ONE,
                    checkNotNull(whileProcessing.mediaDescriptor),
                ).file.readBytes().contentEquals(originalBytes),
            )
            releaseProcessor.complete(Unit)
            assertEquals(ImmediateMediaPreparationOutcome.PREPARED, preparation.await())
            val waiting = restartedQueue.items.value.single()
            assertEquals(ImmediateSendState.WAITING, waiting.state)
            assertEquals(originalBytes.size, waiting.mediaOriginalPlaintextBytes)
            assertEquals(optimized.size, waiting.mediaPlaintextBytes)
            val ciphertext = restartedSpool.ciphertextFile(waiting).readBytes()
            val wirePlaintext = MediaAttachmentCipher.decrypt(
                ciphertext,
                waiting.mediaKeyMaterial(),
                waiting.mediaSha256(),
            )
            assertTrue(wirePlaintext.contentEquals(optimized))
            ciphertext.fill(0)
            wirePlaintext.fill(0)
        } finally {
            releaseProcessor.complete(Unit)
            originalBytes.fill(0)
            optimized.fill(0)
            sourceFile.delete()
            spoolDirectory.deleteRecursively()
            cacheDirectory.deleteRecursively()
        }
    }

    @Test
    fun `restart reconciles an importing row from the atomic sent media file`() = runTest {
        val disk = TestSecureMessagingStateStore()
        val authentication = MutableTestSessionStore(
            testSession(USER_TWO, sessionId = "session-one"),
        )
        val owner = checkNotNull(authentication.current()).fence()
        val messageId = "6de6bccb-54ab-4cd8-964c-d50c886793ef"
        val importing = ImmediateSendIntent(
            id = messageId,
            conversationId = CONVERSATION_ONE,
            kind = ImmediateSendKind.MEDIA,
            createdAtEpochMillis = Instant.parse("2026-07-20T12:00:00Z").toEpochMilli(),
            state = ImmediateSendState.IMPORTING,
            mediaType = "video/mp4",
            mediaPlaintextBytes = 0,
        )
        val firstQueue = ImmediateSendIntentStore(disk, authentication)
        firstQueue.enqueueForOwner(owner, importing)
        val spoolDirectory = Files.createTempDirectory("kit-import-recovery-spool").toFile()
        val cacheDirectory = Files.createTempDirectory("kit-import-recovery-cache").toFile()
        val cache = SecureMediaCache(cacheDirectory)
        val cacheKey = "kit-media:${owner.cacheScopeId}:$CONVERSATION_ONE:$messageId"
        val bytes = "already atomically published local video".toByteArray()
        try {
            cache.store(
                cacheKey = cacheKey,
                mediaType = "video/mp4",
                retainUntilReleased = true,
                collection = LocalMediaCollection.SENT,
            ) { destination -> destination.writeBytes(bytes) }

            val restartedQueue = ImmediateSendIntentStore(disk, authentication)
            val runtime = FakeRuntime().apply {
                conversations += conversation(CONVERSATION_ONE, "Grace")
            }
            val repository = repository(
                runtime,
                authenticationSessions = authentication,
                immediateSends = restartedQueue,
                immediateMediaSpool = ImmediateMediaSpool(spoolDirectory),
                secureMediaCache = cache,
                localMediaLibrary = LocalMediaLibrary(disk, authentication),
            )
            runCurrent()

            assertEquals(
                ImmediateMediaPreparationOutcome.PREPARED,
                ImmediateSendDispatcher(
                    restartedQueue,
                    ImmediateMediaSpool(spoolDirectory),
                    repository,
                ).prepareLocalMedia(),
            )
            val recovered = restartedQueue.items.value.single()
            assertEquals(ImmediateSendState.WAITING, recovered.state)
            assertEquals(bytes.size, recovered.mediaPlaintextBytes)
            assertEquals(messageId, recovered.id)
            runCurrent()
            assertTrue(
                repository.openImageMessage(
                    CONVERSATION_ONE,
                    checkNotNull(
                        repository.conversation(CONVERSATION_ONE).value.single().mediaDescriptor,
                    ),
                ).file.readBytes().contentEquals(bytes),
            )
        } finally {
            bytes.fill(0)
            spoolDirectory.deleteRecursively()
            cacheDirectory.deleteRecursively()
        }
    }

    @Test
    fun `restart leaves an interrupted import as a visible terminal failure`() = runTest {
        val disk = TestSecureMessagingStateStore()
        val authentication = MutableTestSessionStore(
            testSession(USER_TWO, sessionId = "session-one"),
        )
        val owner = checkNotNull(authentication.current()).fence()
        val queue = ImmediateSendIntentStore(disk, authentication)
        val importing = ImmediateSendIntent(
            id = "99f26899-612d-40e0-8b8b-8a71ef1845ba",
            conversationId = CONVERSATION_ONE,
            kind = ImmediateSendKind.MEDIA,
            createdAtEpochMillis = Instant.parse("2026-07-20T12:00:00Z").toEpochMilli(),
            state = ImmediateSendState.IMPORTING,
            mediaType = "application/pdf",
            mediaPlaintextBytes = 0,
        )
        queue.enqueueForOwner(owner, importing)
        val spoolDirectory = Files.createTempDirectory("kit-import-failure-spool").toFile()
        val cacheDirectory = Files.createTempDirectory("kit-import-failure-cache").toFile()
        try {
            val runtime = FakeRuntime().apply {
                conversations += conversation(CONVERSATION_ONE, "Grace")
            }
            val repository = repository(
                runtime,
                authenticationSessions = authentication,
                immediateSends = queue,
                immediateMediaSpool = ImmediateMediaSpool(spoolDirectory),
                secureMediaCache = SecureMediaCache(cacheDirectory),
                localMediaLibrary = LocalMediaLibrary(disk, authentication),
            )
            runCurrent()

            ImmediateSendDispatcher(
                queue,
                ImmediateMediaSpool(spoolDirectory),
                repository,
            ).prepareLocalMedia()
            runCurrent()

            assertEquals(ImmediateSendState.FAILED, queue.items.value.single().state)
            val visible = repository.conversation(CONVERSATION_ONE).value.single()
            assertEquals(importing.id, visible.id)
            assertEquals(DeliveryState.FAILED, visible.state)
        } finally {
            spoolDirectory.deleteRecursively()
            cacheDirectory.deleteRecursively()
        }
    }

    @Test
    fun `preparing media is a same conversation fifo barrier`() = runTest {
        val encryptionStarted = CompletableDeferred<Unit>()
        val releaseEncryption = CompletableDeferred<Unit>()
        val authentication = MutableTestSessionStore(
            testSession(USER_TWO, sessionId = "session-one"),
        )
        val queue = ImmediateSendIntentStore(TestSecureMessagingStateStore(), authentication)
        val spoolDirectory = Files.createTempDirectory("kit-preparing-fifo-spool").toFile()
        val cacheDirectory = Files.createTempDirectory("kit-preparing-fifo-cache").toFile()
        try {
            val spool = ImmediateMediaSpool(
                directory = spoolDirectory,
                beforeEncryption = {
                    encryptionStarted.complete(Unit)
                    releaseEncryption.await()
                },
            )
            val runtime = FakeRuntime().apply {
                conversations += conversation(CONVERSATION_ONE, "Grace")
            }
            val repository = repository(
                runtime,
                authenticationSessions = authentication,
                immediateSends = queue,
                immediateMediaSpool = spool,
                secureMediaCache = SecureMediaCache(cacheDirectory),
            )
            runCurrent()

            repository.sendImageMessage(
                CONVERSATION_ONE,
                "first media".toByteArray(),
                "image/jpeg",
                null,
            )
            repository.sendMessage(CONVERSATION_ONE, "second text")
            val queued = queue.items.value
            assertEquals(ImmediateSendState.PREPARING, queued.first().state)
            assertEquals("second text", queued.last().text)
            assertTrue(queued.first().createdAtEpochMillis < queued.last().createdAtEpochMillis)

            val dispatch = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) {
                ImmediateSendDispatcher(queue, spool, repository).dispatch()
            }
            encryptionStarted.await()
            runCurrent()
            assertTrue(runtime.sendAttempts.isEmpty())

            releaseEncryption.complete(Unit)
            assertEquals(ImmediateSendDispatchOutcome.COMMITTED, dispatch.await())
            assertTrue(KitMediaMessage.parse(runtime.sendAttempts.first().second) != null)
            assertEquals("second text", runtime.sendAttempts.last().second)
            assertTrue(queue.items.value.isEmpty())
        } finally {
            releaseEncryption.complete(Unit)
            spoolDirectory.deleteRecursively()
            cacheDirectory.deleteRecursively()
        }
    }

    @Test
    fun `blocked media in one conversation does not delay another conversation text`() =
        runTest {
            val encryptionStarted = CompletableDeferred<Unit>()
            val releaseEncryption = CompletableDeferred<Unit>()
            val authentication = MutableTestSessionStore(
                testSession(USER_TWO, sessionId = "session-one"),
            )
            val queue = ImmediateSendIntentStore(TestSecureMessagingStateStore(), authentication)
            val spoolDirectory = Files.createTempDirectory("kit-cross-chat-dispatch-spool").toFile()
            val cacheDirectory = Files.createTempDirectory("kit-cross-chat-dispatch-cache").toFile()
            try {
                val spool = ImmediateMediaSpool(
                    directory = spoolDirectory,
                    beforeEncryption = {
                        encryptionStarted.complete(Unit)
                        releaseEncryption.await()
                    },
                )
                val runtime = FakeRuntime().apply {
                    conversations += conversation(CONVERSATION_ONE, "Grace")
                    conversations += directConversation(CONVERSATION_TWO, "Emma", USER_THREE)
                }
                val repository = repository(
                    runtime,
                    authenticationSessions = authentication,
                    immediateSends = queue,
                    immediateMediaSpool = spool,
                    secureMediaCache = SecureMediaCache(cacheDirectory),
                )
                runCurrent()

                repository.sendImageMessage(
                    CONVERSATION_ONE,
                    "large media".toByteArray(),
                    "image/jpeg",
                    null,
                )
                repository.sendMessage(CONVERSATION_ONE, "same chat waits")
                repository.sendMessage(CONVERSATION_TWO, "other chat is instant")

                val dispatch = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) {
                    ImmediateSendDispatcher(queue, spool, repository).dispatch()
                }
                encryptionStarted.await()
                runCurrent()

                // Chat one's media is still encrypting. Chat two has nevertheless reached the
                // encrypted outbox, while chat one's later text remains behind its media.
                assertEquals(
                    listOf("other chat is instant"),
                    runtime.sendAttempts.map { it.second },
                )

                releaseEncryption.complete(Unit)
                assertEquals(ImmediateSendDispatchOutcome.COMMITTED, dispatch.await())
                val attempts = runtime.sendAttempts.map { it.second }
                val mediaIndex = attempts.indexOfFirst { KitMediaMessage.parse(it) != null }
                val laterTextIndex = attempts.indexOf("same chat waits")
                assertTrue(mediaIndex >= 0)
                assertTrue(laterTextIndex > mediaIndex)
                assertTrue(queue.items.value.isEmpty())
            } finally {
                releaseEncryption.complete(Unit)
                spoolDirectory.deleteRecursively()
                cacheDirectory.deleteRecursively()
            }
        }

    @Test
    fun `unrelated media staging cannot let later same chat text overtake idempotent media`() =
        runTest {
            val firstStageStarted = CompletableDeferred<Unit>()
            val releaseFirstStage = CompletableDeferred<Unit>()
            val secondStageStarted = CompletableDeferred<Unit>()
            val releaseSecondStage = CompletableDeferred<Unit>()
            val authentication = MutableTestSessionStore(
                testSession(USER_TWO, sessionId = "session-one"),
            )
            val owner = checkNotNull(authentication.current()).fence()
            val queue = ImmediateSendIntentStore(TestSecureMessagingStateStore(), authentication)
            val directory = Files.createTempDirectory("kit-idempotent-media-order-test").toFile()
            var stageAttempt = 0
            try {
                val spool = ImmediateMediaSpool(
                    directory = directory,
                    beforeEncryption = {
                        when (++stageAttempt) {
                            1 -> {
                                firstStageStarted.complete(Unit)
                                releaseFirstStage.await()
                            }
                            2 -> {
                                secondStageStarted.complete(Unit)
                                releaseSecondStage.await()
                            }
                        }
                    },
                )
                val runtime = FakeRuntime().apply {
                    conversations += conversation(CONVERSATION_ONE, "Grace")
                    conversations += directConversation(CONVERSATION_TWO, "Emma", USER_THREE)
                }
                val repository = repository(
                    runtime,
                    authenticationSessions = authentication,
                    immediateSends = queue,
                    immediateMediaSpool = spool,
                )
                runCurrent()

                val firstMedia = backgroundScope.launch(
                    UnconfinedTestDispatcher(testScheduler),
                ) {
                    repository.sendIdempotentMediaMessageForOwner(
                        owner = owner,
                        chatId = CONVERSATION_ONE,
                        source = SecureMediaSource.ofBytes("other chat media".toByteArray()),
                        mediaType = "image/jpeg",
                        clientMessageId = OUTBOX_ID_ONE,
                    )
                }
                firstStageStarted.await()

                val secondMedia = backgroundScope.launch(
                    UnconfinedTestDispatcher(testScheduler),
                ) {
                    repository.sendIdempotentMediaMessageForOwner(
                        owner = owner,
                        chatId = CONVERSATION_TWO,
                        source = SecureMediaSource.ofBytes("first in this chat".toByteArray()),
                        mediaType = "image/jpeg",
                        clientMessageId = OUTBOX_ID_TWO,
                    )
                }
                runCurrent()
                val laterText = backgroundScope.launch(
                    UnconfinedTestDispatcher(testScheduler),
                ) {
                    repository.sendMessage(CONVERSATION_TWO, "second in this chat")
                }
                runCurrent()

                // Chat two reached its own staging barrier despite chat one's stalled media. Its
                // later text is consequently waiting on chat two's acceptance lock, not queued
                // ahead of the attachment as it was behind the former process-wide media lock.
                assertTrue(secondStageStarted.isCompleted)
                assertTrue(queue.items.value.none { it.conversationId == CONVERSATION_TWO })

                releaseSecondStage.complete(Unit)
                secondMedia.join()
                laterText.join()
                val chatTwoQueue = queue.items.value.filter {
                    it.conversationId == CONVERSATION_TWO
                }
                assertEquals(
                    listOf(ImmediateSendKind.MEDIA, ImmediateSendKind.TEXT),
                    chatTwoQueue.map(ImmediateSendIntent::kind),
                )
                assertTrue(
                    chatTwoQueue.first().createdAtEpochMillis <
                        chatTwoQueue.last().createdAtEpochMillis,
                )

                releaseFirstStage.complete(Unit)
                firstMedia.join()
            } finally {
                releaseFirstStage.complete(Unit)
                releaseSecondStage.complete(Unit)
                directory.deleteRecursively()
            }
        }

    @Test
    fun `local first send survives promotion crash and is committed only once`() = runTest {
        val disk = TestSecureMessagingStateStore()
        val authentication = MutableTestSessionStore(
            testSession(USER_TWO, sessionId = "session-one"),
        )
        val queue = ImmediateSendIntentStore(disk, authentication)
        val directory = Files.createTempDirectory("kit-immediate-dispatch-test").toFile()
        try {
            val spool = ImmediateMediaSpool(directory)
            val runtime = FakeRuntime().apply {
                conversations += conversation(CONVERSATION_ONE, "Grace")
            }
            val repository = repository(
                runtime,
                authenticationSessions = authentication,
                immediateSends = queue,
                immediateMediaSpool = spool,
            )
            runCurrent()

            repository.sendMessage(CONVERSATION_ONE, "survives the crash")
            runCurrent()
            val intent = queue.items.value.single()
            val owner = checkNotNull(authentication.current()).fence()

            // Model a process dying after libsignal's durable commit but before the local intent
            // can be tombstoned. The restart must discover the same stable client ID.
            repository.promoteImmediateSend(owner, intent) {}
            assertEquals(1, runtime.projected.count { it.clientMessageId == intent.id })

            val restartedQueue = ImmediateSendIntentStore(disk, authentication)
            restartedQueue.loadForCurrentOwner()
            val restartedRepository = repository(
                runtime,
                authenticationSessions = authentication,
                immediateSends = restartedQueue,
                immediateMediaSpool = spool,
            )
            runCurrent()
            val outcome = ImmediateSendDispatcher(
                restartedQueue,
                spool,
                restartedRepository,
            ).dispatch()

            assertEquals(ImmediateSendDispatchOutcome.COMMITTED, outcome)
            assertTrue(restartedQueue.items.value.isEmpty())
            assertEquals(1, runtime.projected.count { it.clientMessageId == intent.id })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `uploaded single attachment is renewed and rebound before immutable retry encryption`() =
        runTest {
            var failFirstSend = true
            val runtime = FakeRuntime(beforeSend = { text ->
                if (KitMediaMessage.isMediaText(text) && failFirstSend) {
                    failFirstSend = false
                    IOException("offline after upload")
                } else {
                    null
                }
            }).apply {
                conversations += conversation(CONVERSATION_ONE, "Grace")
            }
            val authentication = MutableTestSessionStore(
                testSession(USER_TWO, sessionId = "session-one"),
            )
            val queue = ImmediateSendIntentStore(TestSecureMessagingStateStore(), authentication)
            val directory = Files.createTempDirectory("kit-single-renew-before-seal").toFile()
            try {
                val spool = ImmediateMediaSpool(directory)
                val repository = repository(
                    runtime,
                    authenticationSessions = authentication,
                    immediateSends = queue,
                    immediateMediaSpool = spool,
                )
                runCurrent()
                repository.sendImageMessage(
                    CONVERSATION_ONE,
                    "durable photo".toByteArray(),
                    "image/jpeg",
                    "Receipt",
                )
                runCurrent()
                val dispatcher = ImmediateSendDispatcher(queue, spool, repository)

                assertEquals(ImmediateSendDispatchOutcome.RETRY, dispatcher.dispatch())
                val checkpoint = queue.items.value.single()
                val oldDescriptor = checkNotNull(
                    KitMediaMessage.parse(checkNotNull(checkpoint.preparedMediaDescriptor)),
                )
                val replacementKey = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
                runtime.reboundAttachmentKeys[checkpoint.id] = replacementKey

                assertEquals(ImmediateSendDispatchOutcome.COMMITTED, dispatcher.dispatch())

                assertEquals(listOf(checkpoint.id to oldDescriptor.storageKey), runtime.attachmentRenewals)
                val sent = checkNotNull(KitMediaMessage.parse(runtime.sendAttempts.last().second))
                assertEquals(checkpoint.id, sent.attachmentId)
                assertEquals(replacementKey, sent.storageKey)
                assertEquals(oldDescriptor.ciphertextSha256, sent.ciphertextSha256)
                assertEquals(oldDescriptor.keyMaterialBase64, sent.keyMaterialBase64)
                assertEquals(listOf(checkpoint.id, checkpoint.id), runtime.idempotentClientIds)
                assertEquals(
                    1,
                    runtime.projected.count { it.clientMessageId == checkpoint.id },
                )
                assertTrue(queue.items.value.isEmpty())

                // A later worker pass sees the committed tombstone and cannot duplicate either
                // the local bubble or the encrypted message.
                assertEquals(ImmediateSendDispatchOutcome.IDLE, dispatcher.dispatch())
                assertEquals(
                    1,
                    runtime.projected.count { it.clientMessageId == checkpoint.id },
                )
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun `uploaded album is renewed and every swept storage key is rebound before retry sealing`() =
        runTest {
            var failFirstSend = true
            val runtime = FakeRuntime(beforeSend = { text ->
                if (KitMediaMessageV2.isMediaText(text) && failFirstSend) {
                    failFirstSend = false
                    IOException("offline after album upload")
                } else {
                    null
                }
            }).apply {
                conversations += conversation(CONVERSATION_ONE, "Grace")
            }
            val authentication = MutableTestSessionStore(
                testSession(USER_TWO, sessionId = "session-one"),
            )
            val queue = ImmediateSendIntentStore(TestSecureMessagingStateStore(), authentication)
            val directory = Files.createTempDirectory("kit-album-renew-before-seal").toFile()
            try {
                val spool = ImmediateMediaSpool(directory)
                val repository = repository(
                    runtime,
                    authenticationSessions = authentication,
                    immediateSends = queue,
                    immediateMediaSpool = spool,
                )
                runCurrent()
                repository.sendMediaAlbumMessage(
                    CONVERSATION_ONE,
                    albumSources("first photo bytes", "second photo bytes"),
                    caption = "Two for you",
                )
                runCurrent()
                val dispatcher = ImmediateSendDispatcher(queue, spool, repository)

                assertEquals(ImmediateSendDispatchOutcome.RETRY, dispatcher.dispatch())
                val checkpoint = queue.items.value.single()
                val old = checkNotNull(
                    KitMediaMessageV2.parse(checkNotNull(checkpoint.preparedMediaDescriptor)),
                )
                checkpoint.mediaItems.forEachIndexed { index, item ->
                    runtime.reboundAttachmentKeys[item.attachmentId] = if (index == 0) {
                        "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
                    } else {
                        "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
                    }
                }

                assertEquals(ImmediateSendDispatchOutcome.COMMITTED, dispatcher.dispatch())

                assertEquals(
                    checkpoint.mediaItems.map { item ->
                        item.attachmentId to checkNotNull(item.storageKey)
                    },
                    runtime.attachmentRenewals,
                )
                val sent = checkNotNull(KitMediaMessageV2.parse(runtime.sendAttempts.last().second))
                assertEquals(old.items.map { it.attachmentId }, sent.items.map { it.attachmentId })
                assertEquals(
                    checkpoint.mediaItems.map { checkNotNull(runtime.reboundAttachmentKeys[it.attachmentId]) },
                    sent.items.map { it.storageKey },
                )
                assertEquals(old.items.map { it.ciphertextSha256 }, sent.items.map { it.ciphertextSha256 })
                assertEquals(listOf(checkpoint.id, checkpoint.id), runtime.idempotentClientIds)
                assertTrue(queue.items.value.isEmpty())
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun `immediate dispatcher preserves per chat fifo while another chat continues`() = runTest {
        var failFirst = true
        val runtime = FakeRuntime(beforeSend = { text ->
            if (text == "first" && failFirst) {
                failFirst = false
                IOException("offline")
            } else {
                null
            }
        }).apply {
            conversations += conversation(CONVERSATION_ONE, "Grace")
            conversations += directConversation(CONVERSATION_TWO, "Emma", USER_THREE)
        }
        val disk = TestSecureMessagingStateStore()
        val authentication = MutableTestSessionStore(
            testSession(USER_TWO, sessionId = "session-one"),
        )
        val queue = ImmediateSendIntentStore(disk, authentication)
        val directory = Files.createTempDirectory("kit-immediate-fifo-test").toFile()
        try {
            val spool = ImmediateMediaSpool(directory)
            val repository = repository(
                runtime,
                authenticationSessions = authentication,
                immediateSends = queue,
                immediateMediaSpool = spool,
            )
            runCurrent()
            queue.enqueue(textIntent(OUTBOX_ID_ONE, CONVERSATION_ONE, "first", 1L))
            queue.enqueue(textIntent(OUTBOX_ID_TWO, CONVERSATION_ONE, "second", 2L))
            queue.enqueue(textIntent(OUTBOX_ID_THREE, CONVERSATION_TWO, "other chat", 3L))
            val dispatcher = ImmediateSendDispatcher(queue, spool, repository)

            assertEquals(ImmediateSendDispatchOutcome.RETRY, dispatcher.dispatch())
            assertEquals(listOf("first", "other chat"), runtime.sendAttempts.map { it.second })
            assertEquals(
                listOf("first", "second"),
                queue.items.value.map(ImmediateSendIntent::text),
            )

            assertEquals(ImmediateSendDispatchOutcome.COMMITTED, dispatcher.dispatch())
            assertEquals(
                listOf("first", "other chat", "first", "second"),
                runtime.sendAttempts.map { it.second },
            )
            assertTrue(queue.items.value.isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `an incompatible album roster costs zero uploads and zero sends`() = runTest {
        val runtime = FakeRuntime().apply {
            conversations += conversation(CONVERSATION_ONE, "Grace")
            albumAdmissionFailure = SecureMessagingConversationCapabilityUnavailableException(
                "A conversation device does not support messaging_media_message_e2ee_v2",
            )
        }
        val authentication = MutableTestSessionStore(
            testSession(USER_TWO, sessionId = "session-one"),
        )
        val queue = ImmediateSendIntentStore(TestSecureMessagingStateStore(), authentication)
        val directory = Files.createTempDirectory("kit-album-admission-test").toFile()
        try {
            val spool = ImmediateMediaSpool(directory)
            val repository = repository(
                runtime,
                authenticationSessions = authentication,
                immediateSends = queue,
                immediateMediaSpool = spool,
            )
            runCurrent()
            repository.sendMediaAlbumMessage(
                CONVERSATION_ONE,
                albumSources("first photo bytes", "second photo bytes"),
                caption = "Two for you",
            )
            runCurrent()

            val outcome = ImmediateSendDispatcher(queue, spool, repository).dispatch()

            // The single §6 roster admission ran — and nothing after it: not one attachment
            // byte was uploaded and no message of any kind went out.
            assertEquals(ImmediateSendDispatchOutcome.IDLE, outcome)
            assertEquals(listOf(CONVERSATION_ONE), runtime.albumAdmissions)
            assertTrue(runtime.albumUploads.isEmpty())
            assertTrue(runtime.sendAttempts.isEmpty())
            val record = queue.items.value.single()
            assertEquals(ImmediateSendState.RETRY_REQUIRED, record.state)
            assertTrue(record.mediaItems.all { it.storageKey == null })
            assertNull(record.preparedMediaDescriptor)
            // The batch survives the refusal whole: both ciphertexts stay spooled for a retry.
            assertEquals(
                2,
                directory.listFiles().orEmpty().count { it.name.endsWith(".ciphertext") },
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `album aggregate admission includes encryption overhead before reading any source`() =
        runTest {
            val runtime = FakeRuntime().apply {
                conversations += conversation(CONVERSATION_ONE, "Grace")
            }
            val authentication = MutableTestSessionStore(
                testSession(USER_TWO, sessionId = "session-one"),
            )
            val queue = ImmediateSendIntentStore(TestSecureMessagingStateStore(), authentication)
            val directory = Files.createTempDirectory("kit-album-aggregate-admission").toFile()
            var sourceOpens = 0
            try {
                val repository = repository(
                    runtime,
                    authenticationSessions = authentication,
                    immediateSends = queue,
                    immediateMediaSpool = ImmediateMediaSpool(directory),
                )
                runCurrent()
                val sources = listOf(209_715_200L, 58_720_256L).map { declaredBytes ->
                    SecureMediaAlbumSource(
                        SecureMediaSource(declaredBytes) {
                            sourceOpens += 1
                            error("aggregate refusal must happen before a source is opened")
                        },
                        "video/mp4",
                    )
                }

                val failure = runCatching {
                    repository.sendMediaAlbumMessage(CONVERSATION_ONE, sources, caption = null)
                }.exceptionOrNull()

                assertEquals("These attachments are too large to send in one message", failure?.message)
                assertEquals(0, sourceOpens)
                assertTrue(queue.items.value.isEmpty())
                assertTrue(directory.listFiles().orEmpty().isEmpty())
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun `an interrupted album upload resumes after process death without repeating work`() = runTest {
        var failSecondUpload = true
        val runtime = FakeRuntime().apply {
            conversations += conversation(CONVERSATION_ONE, "Grace")
            beforeAlbumUpload = { attempt ->
                if (attempt == 2 && failSecondUpload) {
                    failSecondUpload = false
                    IOException("upload connection lost")
                } else {
                    null
                }
            }
        }
        val disk = TestSecureMessagingStateStore()
        val authentication = MutableTestSessionStore(
            testSession(USER_TWO, sessionId = "session-one"),
        )
        val queue = ImmediateSendIntentStore(disk, authentication)
        val directory = Files.createTempDirectory("kit-album-resume-test").toFile()
        try {
            val spool = ImmediateMediaSpool(directory)
            val repository = repository(
                runtime,
                authenticationSessions = authentication,
                immediateSends = queue,
                immediateMediaSpool = spool,
            )
            runCurrent()
            repository.sendMediaAlbumMessage(
                CONVERSATION_ONE,
                albumSources("first photo bytes", "second photo bytes"),
                caption = "Two for you",
            )
            runCurrent()
            val queued = queue.items.value.single()
            val ascending = queued.mediaItems.sortedBy { it.attachmentId }

            assertEquals(
                ImmediateSendDispatchOutcome.RETRY,
                ImmediateSendDispatcher(queue, spool, repository).dispatch(),
            )

            // The connection died on the second upload: the first item's confirmed storage key
            // is already durable, the second has none, and nothing was sent.
            assertEquals(listOf(ascending[0].ciphertextSha256Hex), runtime.albumUploads)
            assertTrue(runtime.sendAttempts.isEmpty())
            val partial = queue.items.value.single().mediaItems.sortedBy { it.attachmentId }
            assertEquals(
                UUID.nameUUIDFromBytes(
                    "storage:${ascending[0].ciphertextSha256Hex}".toByteArray(),
                ).toString(),
                partial[0].storageKey,
            )
            assertNull(partial[1].storageKey)

            // Process death: a fresh store over the same disk restores the identical record.
            val restartedQueue = ImmediateSendIntentStore(disk, authentication)
            restartedQueue.loadForCurrentOwner()
            val restartedSpool = ImmediateMediaSpool(directory)
            val restartedRepository = repository(
                runtime,
                authenticationSessions = authentication,
                immediateSends = restartedQueue,
                immediateMediaSpool = restartedSpool,
            )
            runCurrent()
            val outcome = ImmediateSendDispatcher(
                restartedQueue,
                restartedSpool,
                restartedRepository,
            ).dispatch()

            assertEquals(ImmediateSendDispatchOutcome.COMMITTED, outcome)
            // Re-admitted once, then only the missing upload ran — ascending id order overall,
            // never a repeat of the item whose storage key the record already carried.
            assertEquals(listOf(CONVERSATION_ONE, CONVERSATION_ONE), runtime.albumAdmissions)
            assertEquals(ascending.map { it.ciphertextSha256Hex }, runtime.albumUploads)
            // Exactly one send, under the intent's stable identity.
            assertEquals(1, runtime.sendAttempts.size)
            assertEquals(listOf(queued.id), runtime.idempotentClientIds)
            assertEquals(1, runtime.projected.count { it.clientMessageId == queued.id })
            val descriptorText = runtime.sendAttempts.single().second
            val descriptor = checkNotNull(KitMediaMessageV2.parse(descriptorText))
            assertEquals("Two for you", descriptor.caption)
            // The sealed descriptor preserves pick order and carries the runtime's storage keys.
            assertEquals(
                queued.mediaItems.map { it.attachmentId },
                descriptor.items.map { it.attachmentId },
            )
            assertEquals(
                queued.mediaItems.map {
                    UUID.nameUUIDFromBytes(
                        "storage:${it.ciphertextSha256Hex}".toByteArray(),
                    ).toString()
                },
                descriptor.items.map { it.storageKey },
            )
            // The server-visible rows derived from that descriptor are canonically ascending,
            // independent of the pick order the descriptor itself preserves.
            assertEquals(
                ascending.map { it.attachmentId },
                KitMediaMessageV2.attachmentsFor(descriptorText).map { it.id },
            )
            assertTrue(restartedQueue.items.value.isEmpty())
            assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".ciphertext") })

            // A dispatch after the commit finds nothing and repeats nothing.
            assertEquals(
                ImmediateSendDispatchOutcome.IDLE,
                ImmediateSendDispatcher(restartedQueue, spool, restartedRepository).dispatch(),
            )
            assertEquals(2, runtime.albumUploads.size)
            assertEquals(1, runtime.sendAttempts.size)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `idempotent album resume accepts unknown declared sizes without reopening sources`() =
        runTest {
            val authentication = MutableTestSessionStore(
                testSession(USER_TWO, sessionId = "session-one"),
            )
            val owner = checkNotNull(authentication.current()).fence()
            val queue = ImmediateSendIntentStore(TestSecureMessagingStateStore(), authentication)
            val directory = Files.createTempDirectory("kit-album-unknown-size-resume-test").toFile()
            try {
                val runtime = FakeRuntime().apply {
                    conversations += conversation(CONVERSATION_ONE, "Grace")
                }
                val repository = repository(
                    runtime,
                    authenticationSessions = authentication,
                    immediateSends = queue,
                    immediateMediaSpool = ImmediateMediaSpool(directory),
                )
                runCurrent()
                repository.sendIdempotentMediaAlbumMessageForOwner(
                    owner = owner,
                    chatId = CONVERSATION_ONE,
                    attachments = albumSources("first photo bytes", "second photo bytes"),
                    clientMessageId = OUTBOX_ID_ONE,
                    caption = "Two for you",
                )
                val original = queue.items.value.single()
                var reopenedSources = 0
                val unknownLengthSources = listOf(
                    SecureMediaAlbumSource(
                        SecureMediaSource(0L) {
                            reopenedSources += 1
                            error("An already durable album must not reopen its source")
                        },
                        "image/jpeg",
                    ),
                    SecureMediaAlbumSource(
                        SecureMediaSource(-1L) {
                            reopenedSources += 1
                            error("An already durable album must not reopen its source")
                        },
                        "image/jpeg",
                    ),
                )

                repository.sendIdempotentMediaAlbumMessageForOwner(
                    owner = owner,
                    chatId = CONVERSATION_ONE,
                    attachments = unknownLengthSources,
                    clientMessageId = OUTBOX_ID_ONE,
                    caption = "Two for you",
                )

                assertEquals(0, reopenedSources)
                assertEquals(listOf(original.id), queue.items.value.map(ImmediateSendIntent::id))
                assertEquals(original.mediaItems, queue.items.value.single().mediaItems)
                assertEquals(
                    2,
                    directory.listFiles().orEmpty().count { it.name.endsWith(".ciphertext") },
                )
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun `a one item album delegates to the legacy media message unchanged`() = runTest {
        val runtime = FakeRuntime().apply {
            conversations += conversation(CONVERSATION_ONE, "Grace")
        }
        val authentication = MutableTestSessionStore(
            testSession(USER_TWO, sessionId = "session-one"),
        )
        val queue = ImmediateSendIntentStore(TestSecureMessagingStateStore(), authentication)
        val directory = Files.createTempDirectory("kit-album-single-test").toFile()
        try {
            val spool = ImmediateMediaSpool(directory)
            val repository = repository(
                runtime,
                authenticationSessions = authentication,
                immediateSends = queue,
                immediateMediaSpool = spool,
            )
            runCurrent()
            repository.sendMediaAlbumMessage(
                CONVERSATION_ONE,
                albumSources("only photo bytes"),
                caption = "Solo",
            )
            runCurrent()

            // One attachment is not an album: it must stay on the KITMEDIA1 path old clients
            // already understand, spooled under the intent's own id with no album items at all.
            val record = queue.items.value.single()
            assertEquals(ImmediateSendKind.MEDIA, record.kind)
            assertTrue(record.mediaItems.isEmpty())
            assertEquals("Solo", record.caption)
            assertEquals("image/jpeg", record.mediaType)
            assertTrue(File(directory, "${record.id}.ciphertext").isFile)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `an album caption over the descriptor budget fails whole with nothing queued`() = runTest {
        val runtime = FakeRuntime().apply {
            conversations += conversation(CONVERSATION_ONE, "Grace")
        }
        val authentication = MutableTestSessionStore(
            testSession(USER_TWO, sessionId = "session-one"),
        )
        val queue = ImmediateSendIntentStore(TestSecureMessagingStateStore(), authentication)
        val directory = Files.createTempDirectory("kit-album-caption-test").toFile()
        try {
            val spool = ImmediateMediaSpool(directory)
            val repository = repository(
                runtime,
                authenticationSessions = authentication,
                immediateSends = queue,
                immediateMediaSpool = spool,
            )
            runCurrent()
            // Valid on its own — 2,046 UTF-8 bytes — but URL-encoding triples it past what
            // eight items leave of the 7,680-byte descriptor budget. The whole send must fail
            // in front of the person: a caption is never truncated and never split off.
            val caption = "€".repeat(682)
            val payloads = (1..8).map { "photo number $it bytes" }

            val failure = runCatching {
                repository.sendMediaAlbumMessage(
                    CONVERSATION_ONE,
                    albumSources(*payloads.toTypedArray()),
                    caption = caption,
                )
            }.exceptionOrNull()
            runCurrent()

            assertEquals(
                "This caption is too long to send with these attachments",
                failure?.message,
            )
            assertTrue(queue.items.value.isEmpty())
            assertTrue(runtime.sendAttempts.isEmpty())
            // Every staged ciphertext was released and the staging bubble withdrawn.
            assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".ciphertext") })
            assertTrue(repository.conversation(CONVERSATION_ONE).value.isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `a lost album spool keeps its failed identity and a fresh send mints new media ids`() = runTest {
        val runtime = FakeRuntime().apply {
            conversations += conversation(CONVERSATION_ONE, "Grace")
        }
        val disk = TestSecureMessagingStateStore()
        val authentication = MutableTestSessionStore(
            testSession(USER_TWO, sessionId = "session-one"),
        )
        val queue = ImmediateSendIntentStore(disk, authentication)
        val directory = Files.createTempDirectory("kit-album-spool-loss-test").toFile()
        try {
            val spool = ImmediateMediaSpool(directory)
            val repository = repository(
                runtime,
                authenticationSessions = authentication,
                immediateSends = queue,
                immediateMediaSpool = spool,
            )
            runCurrent()
            repository.sendMediaAlbumMessage(
                CONVERSATION_ONE,
                albumSources("first photo bytes", "second photo bytes"),
                caption = "Two for you",
            )
            runCurrent()
            val queued = queue.items.value.single()
            val owner = checkNotNull(authentication.current()).fence()
            // The device loses one spooled ciphertext while the album waits.
            val lost = queued.mediaItems.sortedBy { it.attachmentId }.first().attachmentId
            assertTrue(File(directory, "$lost.ciphertext").delete())

            val dispatcher = ImmediateSendDispatcher(queue, spool, repository)
            assertEquals(ImmediateSendDispatchOutcome.IDLE, dispatcher.dispatch())

            // Terminal and visible — never a partial send: zero uploads, zero sends, and the
            // surviving ciphertext is released along with the lost one's record.
            assertTrue(runtime.albumUploads.isEmpty())
            assertTrue(runtime.sendAttempts.isEmpty())
            assertEquals(ImmediateSendState.FAILED, queue.items.value.single().state)
            assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".ciphertext") })

            // The failed bubble is not a head-of-line stop: later messages still deliver.
            repository.sendMessage(CONVERSATION_ONE, "after the album")
            runCurrent()
            assertEquals(ImmediateSendDispatchOutcome.COMMITTED, dispatcher.dispatch())
            assertEquals(listOf("after the album"), runtime.sendAttempts.map { it.second })
            assertEquals(ImmediateSendState.FAILED, queue.items.value.single().state)

            // No silent resurrection: a plain rearm refuses, and an idempotent replay cannot put
            // freshly randomized ciphertext behind a client media ID the server may already have
            // bound to the lost artifact's digest.
            assertFalse(queue.rearmForOwner(owner, queued.id))
            repository.sendIdempotentMediaAlbumMessageForOwner(
                owner = owner,
                chatId = CONVERSATION_ONE,
                attachments = albumSources("first photo bytes", "second photo bytes"),
                clientMessageId = queued.id,
                caption = "Two for you",
            )
            runCurrent()
            assertEquals(ImmediateSendState.FAILED, queue.items.value.single().state)
            assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".ciphertext") })

            // A fresh user action gets a fresh message identity and fresh attachment identities.
            repository.sendMediaAlbumMessage(
                CONVERSATION_ONE,
                albumSources("first photo bytes", "second photo bytes"),
                caption = "Two for you",
            )
            runCurrent()
            val fresh = queue.items.value.single { it.state == ImmediateSendState.WAITING }
            assertTrue(fresh.id != queued.id)
            assertTrue(
                fresh.mediaItems.map { it.attachmentId }.toSet()
                    .intersect(queued.mediaItems.map { it.attachmentId }.toSet())
                    .isEmpty(),
            )

            assertEquals(ImmediateSendDispatchOutcome.COMMITTED, dispatcher.dispatch())
            assertEquals(2, runtime.albumUploads.size)
            assertEquals(0, runtime.projected.count { it.clientMessageId == queued.id })
            assertEquals(1, runtime.projected.count { it.clientMessageId == fresh.id })
            val descriptor = KitMediaMessageV2.parse(runtime.sendAttempts.last().second)
            assertEquals("Two for you", checkNotNull(descriptor).caption)
            assertEquals(ImmediateSendState.FAILED, queue.items.value.single().state)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `queued album items decrypt offline from their own spool files`() = runTest {
        val runtime = FakeRuntime().apply {
            conversations += conversation(CONVERSATION_ONE, "Grace")
        }
        val authentication = MutableTestSessionStore(
            testSession(USER_TWO, sessionId = "session-one"),
        )
        val queue = ImmediateSendIntentStore(TestSecureMessagingStateStore(), authentication)
        val directory = Files.createTempDirectory("kit-album-open-test").toFile()
        val cacheDirectory = Files.createTempDirectory("kit-album-open-cache").toFile()
        try {
            val spool = ImmediateMediaSpool(directory)
            val cache = SecureMediaCache(cacheDirectory)
            val repository = repository(
                runtime,
                authenticationSessions = authentication,
                immediateSends = queue,
                immediateMediaSpool = spool,
                secureMediaCache = cache,
            )
            runCurrent()
            repository.sendMediaAlbumMessage(
                CONVERSATION_ONE,
                albumSources("first photo bytes", "second photo bytes"),
                caption = "Two for you",
            )
            runCurrent()
            val owner = checkNotNull(authentication.current()).fence()
            val preparing = queue.items.value.single()
            val queued = repository.prepareImmediateMediaCiphertext(owner, preparing)
            assertTrue(queue.replaceForOwner(owner, preparing, queued))
            repository.releaseImmediateMediaRetention(owner, queued)
            val descriptor = checkNotNull(
                repository.conversation(CONVERSATION_ONE).value
                    .first { it.kind == MessageKind.MEDIA_ALBUM }
                    .mediaDescriptor,
            )

            // Remove the sender-local copies to exercise the queued-ciphertext fallback itself.
            cache.clear()

            // Before any upload or network round trip, each item opens from its spooled
            // ciphertext — in pick order, each authenticated by its own key and digest.
            val payloads = queued.mediaItems.map { item ->
                val opened = repository.openAlbumItemMessage(
                    CONVERSATION_ONE,
                    descriptor,
                    item.attachmentId,
                )
                assertEquals(item.mediaType, opened.mediaType)
                opened.file.readBytes().decodeToString()
            }
            assertEquals(listOf("first photo bytes", "second photo bytes"), payloads)
            assertTrue(runtime.albumUploads.isEmpty())
            assertTrue(runtime.albumOpens.isEmpty())

            // A second open survives losing the spool: the decrypted copies are cached.
            directory.listFiles().orEmpty()
                .filter { it.name.endsWith(".ciphertext") }
                .forEach { assertTrue(it.delete()) }
            assertEquals(
                "first photo bytes",
                repository.openAlbumItemMessage(
                    CONVERSATION_ONE,
                    descriptor,
                    queued.mediaItems[0].attachmentId,
                ).file.readBytes().decodeToString(),
            )

            // An attachment id outside the album fails closed by name.
            val foreign = runCatching {
                repository.openAlbumItemMessage(
                    CONVERSATION_ONE,
                    descriptor,
                    UUID.nameUUIDFromBytes("foreign".toByteArray()).toString(),
                )
            }.exceptionOrNull()
            assertEquals(
                "The requested attachment does not belong to this album",
                foreign?.message,
            )

            // The album path and the single-media path never serve each other's records.
            repository.sendImageMessage(
                CONVERSATION_ONE,
                "solo bytes".toByteArray(),
                "image/jpeg",
                null,
            )
            runCurrent()
            val soloDescriptor = checkNotNull(
                repository.conversation(CONVERSATION_ONE).value
                    .first { it.kind == MessageKind.IMAGE }
                    .mediaDescriptor,
            )
            val crossKind = runCatching {
                repository.openAlbumItemMessage(
                    CONVERSATION_ONE,
                    soloDescriptor,
                    queued.mediaItems[0].attachmentId,
                )
            }.exceptionOrNull()
            assertEquals(
                "The queued secure attachment is no longer available",
                crossKind?.message,
            )
            val albumViaSingle = runCatching {
                repository.openImageMessage(CONVERSATION_ONE, descriptor)
            }.exceptionOrNull()
            assertEquals(
                "The queued secure attachment is no longer available",
                albumViaSingle?.message,
            )
        } finally {
            directory.deleteRecursively()
            cacheDirectory.deleteRecursively()
        }
    }

    @Test
    fun `an album item refuses to open while encryption is still staging it`() = runTest {
        val encryptionStarted = CompletableDeferred<Unit>()
        val releaseEncryption = CompletableDeferred<Unit>()
        val directory = Files.createTempDirectory("kit-album-staging-test").toFile()
        try {
            val spool = ImmediateMediaSpool(
                directory = directory,
                beforeEncryption = {
                    encryptionStarted.complete(Unit)
                    releaseEncryption.await()
                },
            )
            val runtime = FakeRuntime().apply {
                conversations += conversation(CONVERSATION_ONE, "Grace")
            }
            val authentication = MutableTestSessionStore(
                testSession(USER_TWO, sessionId = "session-one"),
            )
            val queue = ImmediateSendIntentStore(TestSecureMessagingStateStore(), authentication)
            val repository = repository(
                runtime,
                authenticationSessions = authentication,
                immediateSends = queue,
                immediateMediaSpool = spool,
            )
            runCurrent()

            val send = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                repository.sendMediaAlbumMessage(
                    CONVERSATION_ONE,
                    albumSources("first photo bytes", "second photo bytes"),
                    caption = "Two for you",
                )
            }
            encryptionStarted.await()
            runCurrent()

            val preparing = repository.conversation(CONVERSATION_ONE).value.single()
            assertEquals(MessageKind.MEDIA_ALBUM, preparing.kind)
            assertTrue(queue.items.value.isEmpty())
            val openFailure = runCatching {
                repository.openAlbumItemMessage(
                    CONVERSATION_ONE,
                    checkNotNull(preparing.mediaDescriptor),
                    preparing.mediaItems.first().attachmentId,
                )
            }.exceptionOrNull()
            assertEquals("This secure attachment is still being prepared", openFailure?.message)

            releaseEncryption.complete(Unit)
            send.join()
            runCurrent()
            assertEquals(1, queue.items.value.size)
        } finally {
            releaseEncryption.complete(Unit)
            directory.deleteRecursively()
        }
    }

    @Test
    fun `selected album is locally readable before its first item is encrypted`() = runTest {
        val encryptionStarted = CompletableDeferred<Unit>()
        val releaseEncryption = CompletableDeferred<Unit>()
        val spoolDirectory = Files.createTempDirectory("kit-album-local-preview-test").toFile()
        val cacheDirectory = Files.createTempDirectory("kit-album-local-preview-cache").toFile()
        try {
            val spool = ImmediateMediaSpool(
                directory = spoolDirectory,
                beforeEncryption = {
                    encryptionStarted.complete(Unit)
                    releaseEncryption.await()
                },
            )
            val runtime = FakeRuntime().apply {
                conversations += conversation(CONVERSATION_ONE, "Grace")
            }
            val authentication = MutableTestSessionStore(
                testSession(USER_TWO, sessionId = "session-one"),
            )
            val queue = ImmediateSendIntentStore(TestSecureMessagingStateStore(), authentication)
            val repository = repository(
                runtime,
                authenticationSessions = authentication,
                immediateSends = queue,
                immediateMediaSpool = spool,
                secureMediaCache = SecureMediaCache(cacheDirectory),
            )
            runCurrent()

            repository.sendMediaAlbumMessage(
                CONVERSATION_ONE,
                albumSources("first photo bytes", "second photo bytes"),
                caption = "Two for you",
            )
            runCurrent()

            val preparing = repository.conversation(CONVERSATION_ONE).value.single()
            assertEquals(ImmediateSendState.PREPARING, queue.items.value.single().state)
            assertEquals(2, preparing.mediaItems.size)
            assertTrue(preparing.mediaItems.all { it.plaintextBytes > 0 })
            assertTrue(preparing.mediaItems.none { it.attachmentId.startsWith("staging:") })
            val opened = preparing.mediaItems.map { item ->
                repository.openAlbumItemMessage(
                    CONVERSATION_ONE,
                    checkNotNull(preparing.mediaDescriptor),
                    item.attachmentId,
                ).file.readBytes().decodeToString()
            }
            assertEquals(listOf("first photo bytes", "second photo bytes"), opened)

            val dispatch = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) {
                ImmediateSendDispatcher(queue, spool, repository).dispatch()
            }
            encryptionStarted.await()
            releaseEncryption.complete(Unit)
            assertEquals(ImmediateSendDispatchOutcome.COMMITTED, dispatch.await())
            runCurrent()
            assertTrue(queue.items.value.isEmpty())
        } finally {
            releaseEncryption.complete(Unit)
            spoolDirectory.deleteRecursively()
            cacheDirectory.deleteRecursively()
        }
    }

    @Test
    fun `received album items download once each and then reopen from cache`() = runTest {
        val runtime = FakeRuntime().apply {
            conversations += conversation(CONVERSATION_ONE, "Grace")
        }
        val authentication = MutableTestSessionStore(
            testSession(USER_TWO, sessionId = "session-one"),
        )
        val queue = ImmediateSendIntentStore(TestSecureMessagingStateStore(), authentication)
        val directory = Files.createTempDirectory("kit-album-received-test").toFile()
        val cacheDirectory = Files.createTempDirectory("kit-album-received-cache").toFile()
        try {
            val spool = ImmediateMediaSpool(directory)
            val cache = SecureMediaCache(cacheDirectory)
            val repository = repository(
                runtime,
                authenticationSessions = authentication,
                immediateSends = queue,
                immediateMediaSpool = spool,
                secureMediaCache = cache,
            )
            runCurrent()
            // A queued album is the honest way to mint a wire descriptor: seal the exact
            // record a sender would, then open it the way a recipient would.
            repository.sendMediaAlbumMessage(
                CONVERSATION_ONE,
                albumSources("first photo bytes", "second photo bytes"),
                caption = "Two for you",
            )
            runCurrent()
            val owner = checkNotNull(authentication.current()).fence()
            val preparing = queue.items.value.single()
            var sealed = repository.prepareImmediateMediaCiphertext(owner, preparing)
            sealed.mediaItems.forEach { item ->
                sealed = sealed.withAlbumItemStorageKey(
                    item.attachmentId,
                    UUID.nameUUIDFromBytes("blob:${item.attachmentId}".toByteArray()).toString(),
                )
            }
            val descriptorText = sealed.buildAlbumDescriptor()
            val (first, second) = sealed.mediaItems.map { it.attachmentId }
            // This test models another device receiving the descriptor, not the sender retaining
            // its own selected files under these stable attachment ids.
            cache.clear()
            runtime.albumOpenPayloads = mapOf(
                first to "alpha payload".toByteArray(),
                second to "beta payload".toByteArray(),
            )

            val opened = repository.openAlbumItemMessage(CONVERSATION_ONE, descriptorText, first)
            assertEquals("alpha payload", opened.file.readBytes().decodeToString())
            assertEquals("image/jpeg", opened.mediaType)
            assertEquals(
                listOf(Triple(CONVERSATION_ONE, descriptorText, first)),
                runtime.albumOpens,
            )

            // Reopening the same item is a cache hit, not a second authenticated download.
            repository.openAlbumItemMessage(CONVERSATION_ONE, descriptorText, first)
            assertEquals(1, runtime.albumOpens.size)

            // The sibling is its own download under its own id.
            assertEquals(
                "beta payload",
                repository.openAlbumItemMessage(CONVERSATION_ONE, descriptorText, second)
                    .file.readBytes().decodeToString(),
            )
            assertEquals(2, runtime.albumOpens.size)

            // An id the descriptor does not carry fails closed before any network use.
            val foreign = runCatching {
                repository.openAlbumItemMessage(
                    CONVERSATION_ONE,
                    descriptorText,
                    UUID.nameUUIDFromBytes("foreign".toByteArray()).toString(),
                )
            }.exceptionOrNull()
            assertEquals(
                "The requested attachment does not belong to this album",
                foreign?.message,
            )
            assertEquals(2, runtime.albumOpens.size)

            // A non-album descriptor never reaches the album open path.
            val legacy = runCatching {
                repository.openAlbumItemMessage(CONVERSATION_ONE, "KITMEDIA1:legacy", first)
            }.exceptionOrNull()
            assertEquals(
                "This message does not reference readable secure media",
                legacy?.message,
            )
        } finally {
            directory.deleteRecursively()
            cacheDirectory.deleteRecursively()
        }
    }

    @Test
    fun `recipient hydration survives restart and never downloads an attachment twice`() =
        runTest {
            val descriptor = mediaV2Descriptor(caption = "Already local").encode()
            val album = checkNotNull(KitMediaMessageV2.parse(descriptor))
            val payloads = album.items.associate { item ->
                item.attachmentId to "received:${item.attachmentId}".toByteArray()
            }
            val hydrated = CompletableDeferred<Unit>()
            val runtime = FakeRuntime().apply {
                conversations += conversation(CONVERSATION_ONE, "Grace")
                projected += message(
                    recordKey = "in:received-album",
                    conversationId = CONVERSATION_ONE,
                    text = descriptor,
                    fromMe = false,
                )
                albumOpenPayloads = payloads
                onAlbumOpen = {
                    if (albumOpens.size == album.items.size) hydrated.complete(Unit)
                }
            }
            val disk = TestSecureMessagingStateStore()
            val authentication = MutableTestSessionStore(
                testSession(USER_TWO, sessionId = "session-one"),
            )
            val owner = checkNotNull(authentication.current()).fence()
            val cacheDirectory = Files.createTempDirectory("kit-recipient-hydration-cache").toFile()
            try {
                val firstRepository = repository(
                    runtime,
                    authenticationSessions = authentication,
                    secureMediaCache = SecureMediaCache(cacheDirectory),
                    localMediaLibrary = LocalMediaLibrary(disk, authentication),
                )
                runCurrent()
                hydrated.await()

                // Waiting on the public open path also waits for an in-flight hydration's
                // per-media publication lock. It must then be a local hit, not a second fetch.
                album.items.forEach { item ->
                    val opened = firstRepository.openAlbumItemMessage(
                        CONVERSATION_ONE,
                        descriptor,
                        item.attachmentId,
                        messageId = null,
                        fromCurrentUser = false,
                    )
                    assertTrue(opened.file.readBytes().contentEquals(payloads[item.attachmentId]))
                    val record = checkNotNull(
                        LocalMediaLibrary(disk, authentication).find(owner, item.attachmentId),
                    )
                    assertEquals(LocalMediaCollection.RECEIVED, record.collection)
                    assertEquals(LocalMediaAvailabilityState.AVAILABLE, record.availabilityState)
                }
                assertEquals(album.items.size, runtime.albumOpens.size)

                // Recreate both the repository and cache object over the same on-device files.
                // Initial background hydration and explicit playback must reuse those files.
                val restartedRepository = repository(
                    runtime,
                    authenticationSessions = authentication,
                    secureMediaCache = SecureMediaCache(cacheDirectory),
                    localMediaLibrary = LocalMediaLibrary(disk, authentication),
                )
                runCurrent()
                album.items.forEach { item ->
                    val opened = restartedRepository.openAlbumItemMessage(
                        CONVERSATION_ONE,
                        descriptor,
                        item.attachmentId,
                        messageId = null,
                        fromCurrentUser = false,
                    )
                    assertTrue(opened.file.readBytes().contentEquals(payloads[item.attachmentId]))
                }
                assertEquals(album.items.size, runtime.albumOpens.size)
            } finally {
                payloads.values.forEach { it.fill(0) }
                cacheDirectory.deleteRecursively()
            }
        }

    @Test
    fun `an incompatible reaction retires without blocking later text`() = runTest {
        val runtime = FakeRuntime(beforeSend = { text ->
            if (KitReactionMessage.parse(text) != null) {
                SecureMessagingConversationCapabilityUnavailableException(
                    "A conversation device does not support messaging_reactions_e2ee_v1",
                )
            } else {
                null
            }
        }).apply {
            conversations += conversation(CONVERSATION_ONE, "Grace")
        }
        val disk = TestSecureMessagingStateStore()
        val authentication = MutableTestSessionStore(
            testSession(USER_TWO, sessionId = "session-one"),
        )
        val queue = ImmediateSendIntentStore(disk, authentication)
        val directory = Files.createTempDirectory("kit-immediate-reaction-test").toFile()
        try {
            val spool = ImmediateMediaSpool(directory)
            val repository = repository(
                runtime,
                authenticationSessions = authentication,
                immediateSends = queue,
                immediateMediaSpool = spool,
            )
            runCurrent()
            val reaction = KitReactionMessage(
                targetMessageId = TARGET_MESSAGE_ID,
                emoji = "👍",
                action = KitReactionAction.ADD,
            ).encode()
            queue.enqueue(
                textIntent(OUTBOX_ID_ONE, CONVERSATION_ONE, reaction, 1L)
                    .copy(kind = ImmediateSendKind.REACTION),
            )
            queue.enqueue(textIntent(OUTBOX_ID_TWO, CONVERSATION_ONE, "still sends", 2L))

            val outcome = ImmediateSendDispatcher(queue, spool, repository).dispatch()

            assertEquals(ImmediateSendDispatchOutcome.COMMITTED, outcome)
            assertEquals(listOf(reaction, "still sends"), runtime.sendAttempts.map { it.second })
            assertTrue(queue.items.value.isEmpty())
            assertEquals(listOf("still sends"), runtime.projected.map { it.text })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `a legacy retry-required reaction retires without blocking later text`() = runTest {
        val runtime = FakeRuntime().apply {
            conversations += conversation(CONVERSATION_ONE, "Grace")
        }
        val disk = TestSecureMessagingStateStore()
        val authentication = MutableTestSessionStore(
            testSession(USER_TWO, sessionId = "session-one"),
        )
        val queue = ImmediateSendIntentStore(disk, authentication)
        val directory = Files.createTempDirectory("kit-immediate-retired-reaction-test").toFile()
        try {
            val spool = ImmediateMediaSpool(directory)
            val repository = repository(
                runtime,
                authenticationSessions = authentication,
                immediateSends = queue,
                immediateMediaSpool = spool,
            )
            runCurrent()
            val reaction = KitReactionMessage(
                targetMessageId = TARGET_MESSAGE_ID,
                emoji = "👍",
                action = KitReactionAction.ADD,
            ).encode()
            val owner = queue.enqueue(
                textIntent(OUTBOX_ID_ONE, CONVERSATION_ONE, reaction, 1L)
                    .copy(kind = ImmediateSendKind.REACTION),
            )
            val queuedReaction = queue.items.value.single()
            assertTrue(queue.markRetryRequiredForOwner(owner, queuedReaction))
            queue.enqueue(textIntent(OUTBOX_ID_TWO, CONVERSATION_ONE, "still sends", 2L))

            val outcome = ImmediateSendDispatcher(queue, spool, repository).dispatch()

            assertEquals(ImmediateSendDispatchOutcome.COMMITTED, outcome)
            assertEquals(listOf("still sends"), runtime.sendAttempts.map { it.second })
            assertTrue(queue.items.value.isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `an account without the edit capability neither offers nor enqueues a correction`() =
        runTest {
            val runtime = FakeRuntime().apply {
                conversations += conversation(CONVERSATION_ONE, "Grace")
            }
            val disk = TestSecureMessagingStateStore()
            val authentication = MutableTestSessionStore(
                testSession(USER_TWO, sessionId = "session-one"),
            )
            val queue = ImmediateSendIntentStore(disk, authentication)
            val directory = Files.createTempDirectory("kit-edit-gate-off-test").toFile()
            try {
                val repository = repository(
                    runtime,
                    authenticationSessions = authentication,
                    immediateSends = queue,
                    immediateMediaSpool = ImmediateMediaSpool(directory),
                )
                runCurrent()

                // The screen reads this to decide whether to show the Edit item at all.
                assertFalse(repository.messageEditsAvailable.value)

                val failure = runCatching {
                    repository.editMessage(CONVERSATION_ONE, TARGET_MESSAGE_ID, "corrected")
                }.exceptionOrNull()

                assertTrue(failure is SecureMessagingConversationCapabilityUnavailableException)
                // Nothing durable: the refusal happens before the offline queue is touched, so a
                // correction the account was never entitled to send cannot outlive the attempt.
                assertTrue(queue.items.value.isEmpty())
                assertTrue(runtime.sendAttempts.isEmpty())
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun `an account with the edit capability is refused by the thread rather than the gate`() =
        runTest {
            val runtime = FakeRuntime(messageEditsEnabled = true).apply {
                conversations += conversation(CONVERSATION_ONE, "Grace")
            }
            val disk = TestSecureMessagingStateStore()
            val authentication = MutableTestSessionStore(
                testSession(USER_TWO, sessionId = "session-one"),
            )
            val queue = ImmediateSendIntentStore(disk, authentication)
            val directory = Files.createTempDirectory("kit-edit-gate-on-test").toFile()
            try {
                val repository = repository(
                    runtime,
                    authenticationSessions = authentication,
                    immediateSends = queue,
                    immediateMediaSpool = ImmediateMediaSpool(directory),
                )
                runCurrent()

                assertTrue(repository.messageEditsAvailable.value)

                val failure = runCatching {
                    repository.editMessage(CONVERSATION_ONE, TARGET_MESSAGE_ID, "corrected")
                }.exceptionOrNull()

                // Past the capability gate and stopped by this device's own view of the thread
                // instead: no bubble here carries that ID. That is what distinguishes the two
                // refusals, and it is why the gate-off case above is the gate and not this.
                assertTrue(failure is IllegalArgumentException)
                assertTrue(queue.items.value.isEmpty())
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun `an incompatible edit retires without blocking later text`() = runTest {
        val runtime = FakeRuntime(
            beforeSend = { text ->
                if (KitEditMessage.parse(text) != null) {
                    SecureMessagingConversationCapabilityUnavailableException(
                        "A conversation device does not support messaging_message_edits_v1",
                    )
                } else {
                    null
                }
            },
            messageEditsEnabled = true,
        ).apply {
            conversations += conversation(CONVERSATION_ONE, "Grace")
        }
        val disk = TestSecureMessagingStateStore()
        val authentication = MutableTestSessionStore(
            testSession(USER_TWO, sessionId = "session-one"),
        )
        val queue = ImmediateSendIntentStore(disk, authentication)
        val directory = Files.createTempDirectory("kit-immediate-edit-test").toFile()
        try {
            val spool = ImmediateMediaSpool(directory)
            val repository = repository(
                runtime,
                authenticationSessions = authentication,
                immediateSends = queue,
                immediateMediaSpool = spool,
            )
            runCurrent()
            val edit = KitEditMessage(
                targetMessageId = TARGET_MESSAGE_ID,
                body = "corrected",
            ).encode()
            queue.enqueue(
                textIntent(OUTBOX_ID_ONE, CONVERSATION_ONE, edit, 1L)
                    .copy(kind = ImmediateSendKind.EDIT),
            )
            queue.enqueue(textIntent(OUTBOX_ID_TWO, CONVERSATION_ONE, "still sends", 2L))

            val outcome = ImmediateSendDispatcher(queue, spool, repository).dispatch()

            assertEquals(ImmediateSendDispatchOutcome.COMMITTED, outcome)
            assertEquals(listOf(edit, "still sends"), runtime.sendAttempts.map { it.second })
            assertTrue(queue.items.value.isEmpty())
            // The original wording still stands; only the correction disappeared.
            assertEquals(listOf("still sends"), runtime.projected.map { it.text })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `a server rejected edit is not endlessly recoverable across a restart`() = runTest {
        val runtime = FakeRuntime(
            beforeSend = { text ->
                if (KitEditMessage.parse(text) != null) {
                    KitWalletApiException(
                        code = "MESSAGE_EDIT_WINDOW_CLOSED",
                        message = "That message can no longer be edited",
                        statusCode = 409,
                        connectivity = false,
                    )
                } else {
                    null
                }
            },
            messageEditsEnabled = true,
        ).apply {
            conversations += conversation(CONVERSATION_ONE, "Grace")
        }
        val disk = TestSecureMessagingStateStore()
        val authentication = MutableTestSessionStore(
            testSession(USER_TWO, sessionId = "session-one"),
        )
        val queue = ImmediateSendIntentStore(disk, authentication)
        val directory = Files.createTempDirectory("kit-immediate-rejected-edit-test").toFile()
        try {
            val spool = ImmediateMediaSpool(directory)
            val repository = repository(
                runtime,
                authenticationSessions = authentication,
                immediateSends = queue,
                immediateMediaSpool = spool,
            )
            runCurrent()
            val edit = KitEditMessage(
                targetMessageId = TARGET_MESSAGE_ID,
                body = "corrected",
            ).encode()
            queue.enqueue(
                textIntent(OUTBOX_ID_ONE, CONVERSATION_ONE, edit, 1L)
                    .copy(kind = ImmediateSendKind.EDIT),
            )
            queue.enqueue(textIntent(OUTBOX_ID_TWO, CONVERSATION_ONE, "still sends", 2L))

            // A 409 is the server's final word, so nothing is scheduled to try again.
            assertEquals(
                ImmediateSendDispatchOutcome.IDLE,
                ImmediateSendDispatcher(queue, spool, repository).dispatch(),
            )
            assertEquals(listOf(edit), runtime.sendAttempts.map { it.second })

            val restartedQueue = ImmediateSendIntentStore(disk, authentication)
            restartedQueue.loadForCurrentOwner()
            val restartedRepository = repository(
                runtime,
                authenticationSessions = authentication,
                immediateSends = restartedQueue,
                immediateMediaSpool = spool,
            )
            runCurrent()

            val outcome = ImmediateSendDispatcher(
                restartedQueue,
                spool,
                restartedRepository,
            ).dispatch()

            // A correction has no retry bubble of its own, so a restart has to discard it rather
            // than present it forever — and the ordinary message queued behind it still goes out.
            assertEquals(ImmediateSendDispatchOutcome.COMMITTED, outcome)
            assertEquals(listOf(edit, "still sends"), runtime.sendAttempts.map { it.second })
            assertTrue(restartedQueue.items.value.isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `missing local media retires without blocking later text`() = runTest {
        val runtime = FakeRuntime().apply {
            conversations += conversation(CONVERSATION_ONE, "Grace")
        }
        val disk = TestSecureMessagingStateStore()
        val authentication = MutableTestSessionStore(
            testSession(USER_TWO, sessionId = "session-one"),
        )
        val queue = ImmediateSendIntentStore(disk, authentication)
        val directory = Files.createTempDirectory("kit-immediate-missing-media-test").toFile()
        val plaintext = "media removed before dispatch".toByteArray()
        try {
            val spool = ImmediateMediaSpool(directory)
            val material = spool.stage(OUTBOX_ID_ONE, SecureMediaSource.ofBytes(plaintext))
            val media = ImmediateSendIntent(
                id = OUTBOX_ID_ONE,
                conversationId = CONVERSATION_ONE,
                kind = ImmediateSendKind.MEDIA,
                createdAtEpochMillis = 1L,
                mediaType = "image/jpeg",
                mediaPlaintextBytes = material.plaintextBytes,
                mediaCiphertextBytes = material.ciphertextBytes,
                mediaKeyBase64 = material.keyBase64,
                mediaSha256Base64 = material.sha256Base64,
            )
            spool.discard(media.id)
            queue.enqueue(media)
            queue.enqueue(textIntent(OUTBOX_ID_TWO, CONVERSATION_ONE, "still sends", 2L))

            val repository = repository(
                runtime,
                authenticationSessions = authentication,
                immediateSends = queue,
                immediateMediaSpool = spool,
            )
            runCurrent()
            val outcome = ImmediateSendDispatcher(queue, spool, repository).dispatch()

            assertEquals(ImmediateSendDispatchOutcome.COMMITTED, outcome)
            assertEquals(listOf("still sends"), runtime.sendAttempts.map { it.second })
            assertTrue(queue.items.value.isEmpty())
        } finally {
            plaintext.fill(0)
            directory.deleteRecursively()
        }
    }

    @Test
    fun `a post-commit network failure preserves per-chat fifo while another chat continues`() =
        runTest {
            var failFirst = true
            val runtime = FakeRuntime(afterDurablyCommitted = { text ->
                if (text == "first" && failFirst) {
                    failFirst = false
                    IOException("response lost after durable commit")
                } else {
                    null
                }
            }).apply {
                conversations += conversation(CONVERSATION_ONE, "Grace")
                conversations += directConversation(CONVERSATION_TWO, "Emma", USER_THREE)
            }
            val disk = TestSecureMessagingStateStore()
            val authentication = MutableTestSessionStore(
                testSession(USER_TWO, sessionId = "session-one"),
            )
            val queue = ImmediateSendIntentStore(disk, authentication)
            val directory = Files.createTempDirectory("kit-immediate-post-commit-fifo-test").toFile()
            try {
                val spool = ImmediateMediaSpool(directory)
                val repository = repository(
                    runtime,
                    authenticationSessions = authentication,
                    immediateSends = queue,
                    immediateMediaSpool = spool,
                )
                runCurrent()
                queue.enqueue(textIntent(OUTBOX_ID_ONE, CONVERSATION_ONE, "first", 1L))
                queue.enqueue(textIntent(OUTBOX_ID_TWO, CONVERSATION_ONE, "second", 2L))
                queue.enqueue(textIntent(OUTBOX_ID_THREE, CONVERSATION_TWO, "other chat", 3L))
                val dispatcher = ImmediateSendDispatcher(queue, spool, repository)

                assertEquals(ImmediateSendDispatchOutcome.RETRY, dispatcher.dispatch())
                assertEquals(listOf("first", "other chat"), runtime.sendAttempts.map { it.second })
                assertEquals(listOf("second"), queue.items.value.map(ImmediateSendIntent::text))
                assertEquals(
                    AuthenticatedTextDeliveryState.PENDING,
                    runtime.projected.single { it.text == "first" }.deliveryState,
                )

                // Even a direct second dispatch cannot bypass the encrypted predecessor. This is
                // deliberately enforced below WorkManager's normal sync-before-dispatch ordering.
                assertEquals(ImmediateSendDispatchOutcome.RETRY, dispatcher.dispatch())
                assertEquals(listOf("first", "other chat"), runtime.sendAttempts.map { it.second })
                assertEquals(listOf("second"), queue.items.value.map(ImmediateSendIntent::text))

                // Once synchronization accepts the predecessor, its successor can be promoted.
                val firstIndex = runtime.projected.indexOfFirst { it.text == "first" }
                runtime.projected[firstIndex] = runtime.projected[firstIndex].copy(
                    deliveryState = AuthenticatedTextDeliveryState.SENT,
                )
                assertEquals(ImmediateSendDispatchOutcome.COMMITTED, dispatcher.dispatch())
                assertEquals(
                    listOf("first", "other chat", "second"),
                    runtime.sendAttempts.map { it.second },
                )
                assertTrue(queue.items.value.isEmpty())
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun `standalone and in chat payment receipts enter the durable queue`() = runTest {
        val disk = TestSecureMessagingStateStore()
        val authentication = MutableTestSessionStore(
            testSession(USER_TWO, sessionId = "session-one"),
        )
        val queue = ImmediateSendIntentStore(disk, authentication)
        val directory = Files.createTempDirectory("kit-immediate-payment-test").toFile()
        try {
            val spool = ImmediateMediaSpool(directory)
            val runtime = FakeRuntime(epoch = null).apply {
                cachedConversations += conversation(CONVERSATION_ONE, "Grace")
            }
            val repository = repository(
                runtime,
                authenticationSessions = authentication,
                immediateSends = queue,
                immediateMediaSpool = spool,
            )
            runtime.localHistoryActivations.value = localActivation()
            runCurrent()
            val descriptor = KitPaymentMessage(
                action = KitPaymentAction.TRANSFER,
                referenceId = PAYMENT_REFERENCE_ID,
                amountMinor = 500,
                currencyCode = "UGX",
                currencyScale = 0,
                note = "Lunch",
            ).encode()

            val standaloneChat = repository.openDirectConversation(
                Contact(USER_ONE, "Grace", "+256700000001", isKitUser = true),
            )
            repository.sendPaymentEvent(standaloneChat, descriptor)
            repository.sendPaymentEvent(CONVERSATION_ONE, descriptor)
            runCurrent()

            assertEquals(2, queue.items.value.size)
            assertTrue(queue.items.value.all { it.kind == ImmediateSendKind.PAYMENT_EVENT })
            assertTrue(queue.items.value.all { it.text == descriptor })
            assertTrue(runtime.sendAttempts.isEmpty())
        } finally {
            directory.deleteRecursively()
        }
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
    fun `a group presents its own photo and description, never a member's`() = runTest {
        val runtime = FakeRuntime().apply {
            conversations += groupConversation(
                id = GROUP_ONE,
                title = "Weekend savings",
                others = listOf(USER_ONE to "Aisha"),
            ).copy(
                description = "Deposits every Friday",
                photoUrl = "https://pay.kit.africa/conversations/$GROUP_ONE/photo/asset-1",
            )
        }
        val repository = repository(runtime, MutableStateFlow(emptyList()))

        runCurrent()

        val chat = repository.chats.value.single()
        assertTrue(chat.isGroup)
        assertEquals("Deposits every Friday", chat.description)
        assertEquals(
            "https://pay.kit.africa/conversations/$GROUP_ONE/photo/asset-1",
            chat.avatarUrl,
        )
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
            // A group borrows nobody's photo, however many members have one; without a photo
            // of its own it shows none, and without a description it claims none.
            assertNull(chat.avatarUrl)
            assertNull(chat.description)

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

    @Test
    fun `group request sync authority stays singular when its encrypted descriptor arrives later`() =
        runTest {
            val requestId = "00000000-0000-4000-8000-000000000031"
            val descriptor = checkNotNull(
                KitGroupPaymentRequestMessage.create(
                    action = KitGroupPaymentRequestAction.REQUESTED,
                    requestId = requestId,
                    amountMinor = 10_000,
                    currencyCode = "UGX",
                    currencyScale = 2,
                    note = "Weekend food",
                ),
            )
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
                groupRequestEvent(
                    id = 31,
                    type = "group_payment_request.created",
                    requestId = requestId,
                    actorId = USER_TWO,
                    at = 1_000,
                ),
            )
            val repository = repository(runtime, systemEvents = systemEvents)
            runCurrent()

            assertEquals(
                listOf("financial:31"),
                repository.conversation(GROUP_ONE).value.map { it.id },
            )

            // The old-client hint lands after the server row. It remains in the encrypted
            // projection, but the current client continues to render only server authority.
            runtime.projected += message(
                recordKey = "out:request-31",
                conversationId = GROUP_ONE,
                text = descriptor.encode(),
                fromMe = true,
                sentAt = Instant.ofEpochMilli(2_000),
            )
            runtime.projectionChanges.value++
            runCurrent()

            val rendered = repository.conversation(GROUP_ONE).value
            assertEquals(listOf("financial:31"), rendered.map { it.id })
            assertEquals(MessageKind.GROUP_PAYMENT_REQUEST, rendered.single().kind)
            assertEquals(KitGroupPaymentRequestAction.REQUESTED.wire,
                rendered.single().groupPaymentRequestAction)
            assertEquals(descriptor.encode(), runtime.projected.single().text)
        }

    @Test
    fun `group request sync authority replaces an earlier encrypted contribution descriptor`() =
        runTest {
            val requestId = "00000000-0000-4000-8000-000000000032"
            val contributionId = "00000000-0000-4000-8000-000000000033"
            val otherContributionId = "00000000-0000-4000-8000-000000000036"
            val descriptor = checkNotNull(
                KitGroupPaymentRequestMessage.create(
                    action = KitGroupPaymentRequestAction.CONTRIBUTED,
                    requestId = requestId,
                    contributionId = contributionId,
                    amountMinor = 2_500,
                ),
            )
            val otherDescriptor = checkNotNull(
                KitGroupPaymentRequestMessage.create(
                    action = KitGroupPaymentRequestAction.CONTRIBUTED,
                    requestId = requestId,
                    contributionId = otherContributionId,
                    amountMinor = 1_000,
                ),
            )
            val runtime = FakeRuntime().apply {
                conversations += groupConversation(
                    id = GROUP_ONE,
                    title = "Weekend savings",
                    others = listOf(USER_ONE to "Aisha"),
                )
                projected += message(
                    recordKey = "in:contribution-33",
                    conversationId = GROUP_ONE,
                    text = descriptor.encode(),
                    fromMe = false,
                    sentAt = Instant.ofEpochMilli(1_000),
                )
                projected += message(
                    recordKey = "in:contribution-36",
                    conversationId = GROUP_ONE,
                    text = otherDescriptor.encode(),
                    fromMe = false,
                    sentAt = Instant.ofEpochMilli(1_500),
                )
            }
            val systemEvents = ConversationSystemEventStore(TestSecureMessagingStateStore())
            val repository = repository(runtime, systemEvents = systemEvents)
            runCurrent()

            assertEquals(
                listOf("in:contribution-33", "in:contribution-36"),
                repository.conversation(GROUP_ONE).value.map { it.id },
            )

            systemEvents.record(
                GROUP_ONE,
                groupRequestEvent(
                    id = 32,
                    type = "group_payment_request.contributed",
                    requestId = requestId,
                    contributionId = contributionId,
                    actorId = USER_ONE,
                    at = 2_000,
                ),
            )
            runtime.projectionChanges.value++
            runCurrent()

            val rendered = repository.conversation(GROUP_ONE).value
            assertEquals(listOf("in:contribution-36", "financial:32"), rendered.map { it.id })
            val authority = rendered.last()
            assertEquals(MessageKind.GROUP_PAYMENT_REQUEST_EVENT, authority.kind)
            assertEquals(contributionId, authority.groupPaymentRequestContributionId)
            assertFalse(authority.fromMe)
        }

    @Test
    fun `group request coalescing survives process recovery and maps a final contribution to completion`() =
        runTest {
            val requestId = "00000000-0000-4000-8000-000000000034"
            val contributionId = "00000000-0000-4000-8000-000000000035"
            val descriptor = checkNotNull(
                KitGroupPaymentRequestMessage.create(
                    action = KitGroupPaymentRequestAction.CONTRIBUTED,
                    requestId = requestId,
                    contributionId = contributionId,
                    amountMinor = 2_500,
                ),
            )
            val durableState = TestSecureMessagingStateStore()
            ConversationSystemEventStore(durableState).record(
                GROUP_ONE,
                groupRequestEvent(
                    id = 34,
                    type = "group_payment_request.completed",
                    requestId = requestId,
                    contributionId = contributionId,
                    actorId = USER_ONE,
                    at = 2_000,
                ),
            )

            // A fresh store models process recovery after both durable paths have landed.
            val restoredEvents = ConversationSystemEventStore(durableState)
            val runtime = FakeRuntime(epoch = null).apply {
                cachedConversations += groupConversation(
                    id = GROUP_ONE,
                    title = "Weekend savings",
                    others = listOf(USER_ONE to "Aisha"),
                )
                localProjected += message(
                    recordKey = "in:final-contribution-35",
                    conversationId = GROUP_ONE,
                    text = descriptor.encode(),
                    fromMe = false,
                    sentAt = Instant.ofEpochMilli(1_000),
                )
            }
            val repository = repository(runtime, systemEvents = restoredEvents)
            runtime.localHistoryActivations.value = localActivation()
            runCurrent()

            val rendered = repository.conversation(GROUP_ONE).value
            assertEquals(listOf("financial:34"), rendered.map { it.id })
            assertEquals(KitGroupPaymentRequestAction.COMPLETED.wire,
                rendered.single().groupPaymentRequestAction)
            assertEquals(contributionId, rendered.single().groupPaymentRequestContributionId)
            assertEquals(descriptor.encode(), runtime.localProjected.single().text)
            assertTrue(repository.localHistoryReady.value)
            assertFalse(repository.readiness.value)
        }

    private fun groupRequestEvent(
        id: Long,
        type: String,
        requestId: String,
        actorId: String,
        at: Long,
        contributionId: String? = null,
    ) = ConversationSystemEvent(
        eventId = id,
        type = type,
        userId = USER_TWO,
        role = null,
        occurredAt = Instant.ofEpochMilli(at),
        paymentId = requestId,
        contributionId = contributionId,
        contributorUserId = actorId.takeIf { contributionId != null },
        contributionAmountMinor = "2500".takeIf { contributionId != null },
    )

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
        immediateSends: ImmediateSendIntentStore? = null,
        immediateMediaSpool: ImmediateMediaSpool? = null,
        secureMediaCache: SecureMediaCache? = null,
        localMediaLibrary: LocalMediaLibrary? = null,
        secureMediaUploadProcessor: SecureMediaUploadProcessor? = null,
        richMediaCapability: MessagingRichMediaCapability? = null,
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
            immediateSends = immediateSends,
            immediateMediaSpool = immediateMediaSpool,
            secureMediaCache = secureMediaCache,
            localMediaLibrary = localMediaLibrary,
            secureMediaUploadProcessor = secureMediaUploadProcessor,
            richMediaCapability = richMediaCapability,
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
        private val beforeSend: (String) -> Throwable? = { null },
        private val afterDurablyCommitted: (String) -> Throwable? = { null },
        /** What the server said about this account's message-correction capability. */
        private val messageEditsEnabled: Boolean = false,
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
        val idempotentClientIds = mutableListOf<String?>()
        val expectedOwners = mutableListOf<SessionFence?>()
        val replyTargets = mutableListOf<String?>()
        val albumAdmissions = mutableListOf<String>()

        /** Expected ciphertext digests, in exactly the order the dispatcher uploaded them. */
        val albumUploads = mutableListOf<String>()
        /** Media id and former key for each pre-encryption resumable lease renewal. */
        val attachmentRenewals = mutableListOf<Pair<String, String>>()
        val reboundAttachmentKeys = mutableMapOf<String, String>()
        var albumAdmissionFailure: Throwable? = null
        var beforeAlbumUpload: (attempt: Int) -> Throwable? = { null }

        /** Conversation, descriptor and attachment id of every received-album open, in order. */
        val albumOpens = mutableListOf<Triple<String, String, String>>()
        var albumOpenPayloads: Map<String, ByteArray> = emptyMap()
        var onAlbumOpen: (() -> Unit)? = null
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
            idempotentClientMessageId: String?,
        ) {
            requireCurrent(session)
            expectedOwners += expectedOwner
            idempotentClientMessageId?.let { stableId ->
                if (projected.any { it.clientMessageId == stableId }) {
                    onDurablyCommitted(stableId)
                    return
                }
                if (projected.any {
                        it.conversationId == conversationId &&
                            it.deliveryState == AuthenticatedTextDeliveryState.PENDING
                    }
                ) {
                    throw SecureMessagingPendingPredecessorException()
                }
            }
            sendAttempts += Triple(conversationId, text, retryClientMessageId)
            idempotentClientIds += idempotentClientMessageId
            replyTargets += replyToMessageId
            beforeSend(text)?.let { throw it }
            when (sendScenario) {
                SendScenario.NORMAL -> {
                    val committed = recordNormalSend(
                        conversationId,
                        text,
                        idempotentClientMessageId,
                    )
                    onDurablyCommitted(committed.clientMessageId)
                    afterDurablyCommitted(text)?.let { error ->
                        val committedIndex = projected.indexOfLast {
                            it.clientMessageId == committed.clientMessageId
                        }
                        projected[committedIndex] = committed.copy(
                            deliveryState = AuthenticatedTextDeliveryState.PENDING,
                        )
                        projectionChanges.value++
                        throw error
                    }
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

        override suspend fun prepareMediaDescriptor(
            session: SecureMessagingChatSession,
            conversationId: String,
            attachmentId: String,
            ciphertext: File,
            mediaType: String,
            keyMaterialBase64: String,
            plaintextBytes: Int,
            caption: String?,
            expectedCiphertextBytes: Long?,
            expectedCiphertextSha256Hex: String?,
        ): String {
            requireCurrent(session)
            check(conversations.any { it.id == conversationId })
            check(ciphertext.isFile && ciphertext.length() > 0L)
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(ciphertext.readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            return KitMediaMessage(
                attachmentId = attachmentId,
                storageKey = UUID.nameUUIDFromBytes(
                    "storage:$attachmentId".toByteArray(),
                ).toString(),
                mediaType = mediaType,
                ciphertextByteSize = ciphertext.length(),
                ciphertextSha256 = digest,
                keyMaterialBase64 = keyMaterialBase64,
                plaintextByteSize = plaintextBytes,
                caption = caption,
            ).encode()
        }

        override suspend fun assertMediaAlbumSendable(
            session: SecureMessagingChatSession,
            conversationId: String,
            expectedOwner: SessionFence?,
        ) {
            requireCurrent(session)
            albumAdmissions += conversationId
            albumAdmissionFailure?.let { throw it }
        }

        override suspend fun uploadAlbumAttachment(
            session: SecureMessagingChatSession,
            conversationId: String,
            attachmentId: String,
            ciphertext: File,
            mediaType: String,
            expectedCiphertextBytes: Long,
            expectedCiphertextSha256Hex: String,
        ): String {
            requireCurrent(session)
            check(ciphertext.isFile && ciphertext.length() == expectedCiphertextBytes) {
                "The spooled ciphertext under upload no longer matches its queued record"
            }
            beforeAlbumUpload(albumUploads.size + 1)?.let { throw it }
            albumUploads += expectedCiphertextSha256Hex
            // Deterministic per blob, so tests can predict the storage key each upload earns
            // and a repeated upload of the same content would be observable as such.
            return UUID.nameUUIDFromBytes(
                "storage:$expectedCiphertextSha256Hex".toByteArray(),
            ).toString()
        }

        override suspend fun renewUploadedAttachment(
            session: SecureMessagingChatSession,
            conversationId: String,
            attachmentId: String,
            existingStorageKey: String,
            ciphertext: File,
            mediaType: String,
            expectedCiphertextBytes: Long,
            expectedCiphertextSha256Hex: String,
        ): String {
            requireCurrent(session)
            check(conversations.any { it.id == conversationId })
            check(ciphertext.isFile && ciphertext.length() == expectedCiphertextBytes)
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(ciphertext.readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            check(digest == expectedCiphertextSha256Hex)
            attachmentRenewals += attachmentId to existingStorageKey
            return reboundAttachmentKeys[attachmentId] ?: existingStorageKey
        }

        override suspend fun openAlbumItemToFile(
            session: SecureMessagingChatSession,
            conversationId: String,
            descriptorText: String,
            attachmentId: String,
            destination: File,
        ): Int {
            requireCurrent(session)
            albumOpens += Triple(conversationId, descriptorText, attachmentId)
            onAlbumOpen?.invoke()
            val payload = checkNotNull(albumOpenPayloads[attachmentId]) {
                "no fake payload registered for attachment $attachmentId"
            }
            destination.writeBytes(payload)
            return payload.size
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
            idempotentClientMessageId: String? = null,
        ): AuthenticatedProjectedText {
            val attempt = sendAttempts.size
            val committed = message(
                recordKey = "out:normal-$attempt",
                conversationId = conversationId,
                text = text,
                fromMe = true,
                state = AuthenticatedTextDeliveryState.SENT,
                sentAt = Instant.parse("2026-07-20T12:00:00Z").plusSeconds(attempt.toLong()),
                clientMessageId = idempotentClientMessageId ?: "out:normal-$attempt",
            )
            projected += committed
            projectionChanges.value++
            return committed
        }

        private fun newSession(epoch: String) =
            SecureMessagingChatSession(epoch, Any(), messageEditsEnabled)
    }

    private companion object {
        const val CONVERSATION_ONE = "11111111-1111-4111-8111-111111111111"
        const val USER_ONE = "22222222-2222-4222-8222-222222222222"
        const val USER_TWO = "33333333-3333-4333-8333-333333333333"
        const val TARGET_MESSAGE_ID = "44444444-4444-4444-8444-444444444444"
        const val GROUP_ONE = "55555555-5555-4555-8555-555555555555"
        const val USER_THREE = "66666666-6666-4666-8666-666666666666"
        const val CONVERSATION_TWO = "77777777-7777-4777-8777-777777777777"
        const val OUTBOX_ID_ONE = "80000000-0000-4000-8000-000000000001"
        const val OUTBOX_ID_TWO = "80000000-0000-4000-8000-000000000002"
        const val OUTBOX_ID_THREE = "80000000-0000-4000-8000-000000000003"
        const val PAYMENT_REFERENCE_ID = "90000000-0000-4000-8000-000000000001"

        // Equal-timestamp ordering keys chosen so the pending message's client-ID fallback sorts
        // between the two server IDs: LOW_SERVER < PENDING_CLIENT < HIGH_SERVER.
        const val LOW_SERVER_MESSAGE_ID = "server-msg-0001"
        const val PENDING_CLIENT_MESSAGE_ID = "server-msg-0500"
        const val HIGH_SERVER_MESSAGE_ID = "server-msg-0999"

        fun conversation(id: String, name: String) = directConversation(id, name)

        fun textIntent(
            id: String,
            conversationId: String,
            text: String,
            createdAtEpochMillis: Long,
        ) = ImmediateSendIntent(
            id = id,
            conversationId = conversationId,
            kind = ImmediateSendKind.TEXT,
            createdAtEpochMillis = createdAtEpochMillis,
            text = text,
        )

        /** Album attachments from in-heap payloads, each one a supported still image. */
        fun albumSources(vararg payloads: String) = payloads.map {
            SecureMediaAlbumSource(SecureMediaSource.ofBytes(it.toByteArray()), "image/jpeg")
        }

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

        fun mediaV2Descriptor(caption: String?): KitMediaMessageV2 {
            val key = Base64.getEncoder()
                .encodeToString(ByteArray(MediaAttachmentCipher.KEY_MATERIAL_BYTES))
            return KitMediaMessageV2(
                items = listOf(
                    KitMediaMessageV2Item(
                        attachmentId = "11111111-1111-4111-8111-111111111111",
                        storageKey = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                        mediaType = "image/jpeg",
                        ciphertextByteSize = 1_088,
                        ciphertextSha256 = "1".repeat(64),
                        keyMaterialBase64 = key,
                        plaintextByteSize = 1_024,
                    ),
                    KitMediaMessageV2Item(
                        attachmentId = "22222222-2222-4222-8222-222222222222",
                        storageKey = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                        mediaType = "video/mp4",
                        ciphertextByteSize = 5_242_944,
                        ciphertextSha256 = "2".repeat(64),
                        keyMaterialBase64 = key,
                        plaintextByteSize = 5_242_880,
                    ),
                ),
                caption = caption,
            )
        }
    }
}
