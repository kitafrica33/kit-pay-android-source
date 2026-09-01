package com.kit.wallet

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.kit.wallet.data.repository.CallRepository
import com.kit.wallet.data.repository.ChatRepository
import com.kit.wallet.data.repository.SecureMediaStillPreparingException
import com.kit.wallet.data.repository.WalletRepository
import com.kit.wallet.data.messaging.KitPaymentAction
import com.kit.wallet.data.messaging.KitPaymentMessage
import com.kit.wallet.data.messaging.SecureMediaFile
import com.kit.wallet.data.messaging.SecureMediaSource
import com.kit.wallet.data.messaging.SecureMessagingStateNotReadyException
import com.kit.wallet.data.remote.KIT_NETWORK_UNAVAILABLE_MESSAGE
import com.kit.wallet.data.remote.KitWalletApiException
import com.kit.wallet.data.realtime.KitConversationSignals
import com.kit.wallet.data.realtime.KitTypingSignals
import com.kit.wallet.feature.chat.ConversationComposerState
import com.kit.wallet.feature.chat.ConversationViewModel
import com.kit.wallet.feature.chat.MessageSoundPlayer
import com.kit.wallet.feature.chat.retryableOutgoingMessageIds
import com.kit.wallet.ui.model.Beneficiary
import com.kit.wallet.ui.model.BillProvider
import com.kit.wallet.ui.model.CallEntry
import com.kit.wallet.ui.model.ChatPreview
import com.kit.wallet.ui.model.Contact
import com.kit.wallet.ui.model.DeliveryState
import com.kit.wallet.ui.model.Message
import com.kit.wallet.ui.model.MessageKind
import com.kit.wallet.ui.model.MessageMediaItem
import com.kit.wallet.ui.model.PaymentEventKind
import com.kit.wallet.ui.model.Transaction
import com.kit.wallet.ui.model.TransferClaim
import com.kit.wallet.ui.model.TransferClaimStatus
import com.kit.wallet.ui.model.albumItemMediaKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationViewModelTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `send trims text and clears the composer instantly`() = runTest {
        val repository = FakeChatRepository()
        val viewModel = viewModel(repository)
        var clearRequests = 0

        viewModel.send("  hello securely  ") { clearRequests++ }

        assertEquals(listOf(CHAT_ID to "hello securely"), repository.sent)
        assertEquals(1, clearRequests)
        assertFalse(viewModel.sending.value)
        assertEquals(null, viewModel.error.value)
    }

    @Test
    fun `failed send before a durable outbox entry preserves the composer and surfaces the error`() = runTest {
        val repository = FakeChatRepository(failure = IllegalStateException("Ciphertext was not accepted"))
        val viewModel = viewModel(repository)
        var clearRequests = 0

        viewModel.send("send instantly") { clearRequests++ }

        assertEquals(0, clearRequests)
        assertEquals("Ciphertext was not accepted", viewModel.error.value)
        assertFalse(viewModel.sending.value)

        viewModel.clearError()
        assertEquals(null, viewModel.error.value)
    }

    @Test
    fun `secure state IOException preserves the composer instead of masquerading as offline`() = runTest {
        val repository = FakeChatRepository(
            failure = SecureMessagingStateNotReadyException("Secure messaging state is still opening"),
        )
        val viewModel = viewModel(repository)
        var clearRequests = 0

        viewModel.send("keep this draft") { clearRequests++ }

        assertEquals(0, clearRequests)
        assertEquals("Secure messaging state is still opening", viewModel.error.value)
    }

    @Test
    fun `offline failure before durable outbox preserves composer and uses safe error copy`() = runTest {
        val repository = FakeChatRepository(failure = connectivityFailure())
        val viewModel = viewModel(repository)
        var clearRequests = 0

        viewModel.send("keep this offline draft") { clearRequests++ }

        assertEquals(0, clearRequests)
        assertEquals(KIT_NETWORK_UNAVAILABLE_MESSAGE, viewModel.error.value)
    }

    @Test
    fun `offline failure clears composer only after its durable commit callback`() = runTest {
        val repository = FakeChatRepository(
            failure = connectivityFailure(),
            durablyCommitBeforeCompletion = true,
        )
        val viewModel = viewModel(repository)
        var clearRequests = 0

        viewModel.send("queued securely") { clearRequests++ }

        assertEquals(1, clearRequests)
        assertEquals(null, viewModel.error.value)
    }

    @Test
    fun `durable commit callback clears composer while transport is still pending`() = runTest {
        val transportRelease = CompletableDeferred<Unit>()
        val repository = FakeChatRepository(
            durablyCommitBeforeCompletion = true,
            blockUntil = transportRelease,
        )
        val viewModel = viewModel(repository)
        var clearRequests = 0

        viewModel.send("already durable") { clearRequests++ }

        assertEquals(1, clearRequests)
        assertFalse(transportRelease.isCompleted)

        transportRelease.complete(Unit)
        assertEquals(1, clearRequests)
    }

    @Test
    fun `concurrent identical send that fails cannot borrow the other durable commit`() = runTest {
        val failedAttemptRelease = CompletableDeferred<Unit>()
        val durableAttemptRelease = CompletableDeferred<Unit>()
        val repository = FakeChatRepository(
            sendOutcomes = listOf(
                FakeSendOutcome(
                    durablyCommitted = false,
                    failure = IllegalStateException("first operation failed before commit"),
                    completionGate = failedAttemptRelease,
                ),
                FakeSendOutcome(
                    durablyCommitted = true,
                    completionGate = durableAttemptRelease,
                ),
            ),
        )
        val viewModel = viewModel(repository)
        var failedAttemptClears = 0
        var durableAttemptClears = 0

        viewModel.send("identical") { failedAttemptClears++ }
        viewModel.send("identical") { durableAttemptClears++ }

        assertEquals(0, failedAttemptClears)
        assertEquals(1, durableAttemptClears)

        failedAttemptRelease.complete(Unit)
        assertEquals("first operation failed before commit", viewModel.error.value)
        assertEquals(0, failedAttemptClears)
        assertEquals(1, durableAttemptClears)

        durableAttemptRelease.complete(Unit)
        assertEquals(0, failedAttemptClears)
        assertEquals(1, durableAttemptClears)
    }

    @Test
    fun `composer generation preserves edits and identical retyping after send begins`() {
        val submitted = ConversationComposerState().edited("same text")
        val edited = submitted.edited("new draft")
        val retypedIdentically = submitted.edited("same text")

        assertEquals(edited, edited.clearIfUnchanged(submitted))
        assertEquals(retypedIdentically, retypedIdentically.clearIfUnchanged(submitted))
        assertTrue(edited.generation > submitted.generation)
        assertTrue(retypedIdentically.generation > submitted.generation)
        assertEquals("", submitted.clearIfUnchanged(submitted).text)
    }

    @Test
    fun `a second tap on send cannot resend the same composer content`() {
        val typed = ConversationComposerState().edited("hello")

        val firstTap = checkNotNull(typed.submitted())
        assertEquals("hello", firstTap.text)
        // The double tap: the composer still holds its text because nothing has committed yet.
        assertNull(firstTap.submitted())

        // A commit clears it, and the cleared composer has nothing to send either.
        assertNull(firstTap.clearIfUnchanged(firstTap).submitted())

        // Retyping the same words is a new message and must go through.
        val retyped = firstTap.edited("hello")
        assertEquals("hello", checkNotNull(retyped.submitted()).text)

        // So is tapping again after an attempt that failed before it was durably committed.
        assertEquals("hello", checkNotNull(firstTap.releasedForRetry().submitted()).text)
    }

    private fun connectivityFailure() = KitWalletApiException(
        code = "NETWORK_UNAVAILABLE",
        message = "failed to reach private-host.test",
        connectivity = true,
    )

    @Test
    fun `rapid sends are committed instantly without blocking on the network`() = runTest {
        val release = CompletableDeferred<Unit>()
        val repository = FakeChatRepository(blockUntil = release)
        val viewModel = viewModel(repository)

        // Neither send waits on the previous one: both are committed to the outbox immediately,
        // with no composer spinner and no dropped message.
        viewModel.send("one")
        viewModel.send("two")
        assertEquals(listOf(CHAT_ID to "one", CHAT_ID to "two"), repository.sent)
        assertFalse(viewModel.sending.value)

        release.complete(Unit)
        assertEquals(listOf(CHAT_ID to "one", CHAT_ID to "two"), repository.sent)
    }

    @Test
    fun `pending authenticated outgoing bubble can be retried explicitly`() = runTest {
        val release = CompletableDeferred<Unit>()
        val repository = FakeChatRepository(blockUntil = release)
        val viewModel = viewModel(repository)
        val pending = Message(
            id = "client-message-id",
            text = "retry this ciphertext",
            time = "12:00",
            fromMe = true,
            state = DeliveryState.SENDING,
        )
        var retried = false

        viewModel.retry(pending) { retried = true }

        assertTrue(repository.sent.isEmpty())
        assertEquals(
            listOf(Triple(CHAT_ID, pending.id, pending.text)),
            repository.retried,
        )
        assertEquals(pending.id, viewModel.retryingMessageId.value)
        assertTrue(viewModel.sending.value)

        release.complete(Unit)
        assertTrue(retried)
        assertEquals(null, viewModel.retryingMessageId.value)
        assertFalse(viewModel.sending.value)
    }

    @Test
    fun `received sent and permanently failed bubbles cannot enter retry send path`() = runTest {
        val repository = FakeChatRepository()
        val viewModel = viewModel(repository)

        viewModel.retry(
            Message("received", "hello", "12:00", fromMe = false),
        )
        viewModel.retry(
            Message(
                "sent",
                "hello",
                "12:00",
                fromMe = true,
                state = DeliveryState.SENT,
            ),
        )
        viewModel.retry(
            Message(
                "expired-media",
                "photo expired",
                "12:00",
                fromMe = true,
                state = DeliveryState.FAILED,
                kind = MessageKind.IMAGE,
                mediaDescriptor = "dead authenticated descriptor",
            ),
        )

        assertTrue(repository.sent.isEmpty())
        assertTrue(repository.retried.isEmpty())
        assertFalse(viewModel.sending.value)
    }

    @Test
    fun `stale roster bubble stops offering retry after its fresh copy exists`() {
        val stale = Message(
            "stale",
            "same authenticated text",
            "12:00",
            fromMe = true,
            state = DeliveryState.RETRY_REQUIRED,
        )
        val replacement = stale.copy(id = "replacement", state = DeliveryState.SENDING)

        assertEquals(
            setOf(replacement.id),
            retryableOutgoingMessageIds(listOf(stale, replacement)),
        )
        assertTrue(
            retryableOutgoingMessageIds(
                listOf(stale, replacement.copy(state = DeliveryState.SENT)),
            ).isEmpty(),
        )
    }

    @Test
    fun `deep linked conversation appears when its authenticated projection loads later`() = runTest {
        val repository = FakeChatRepository(initiallyLoaded = false)
        val viewModel = viewModel(repository)

        assertEquals(null, viewModel.chat.value)

        repository.publishChat()
        val loaded = listOf(Message("m1", "hi", "12:00", fromMe = false))
        repository.messages.value = loaded

        assertEquals(CHAT_ID, viewModel.chat.value?.id)
        // The conversation view merges call-log entries, so it mirrors the repository's messages
        // by content rather than being the same flow instance.
        assertEquals(loaded, viewModel.messages.value)
    }

    @Test
    fun `a live socket catches up once on entry and then never polls`() = runTest {
        val repository = FakeChatRepository()
        val realtime = RecordingConversationSignals()
        val viewModel = viewModel(repository, realtime = realtime)

        viewModel.setConversationVisible(true)
        runCurrent()
        assertEquals(1, repository.syncRequests)
        assertEquals(listOf(CHAT_ID), realtime.observed)

        // A `null` interval is the coordinator saying it is carrying this conversation
        // itself. Polling on top of a live socket is the duplicated work this feature
        // exists to remove, so two minutes of it must cost nothing.
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(1, repository.syncRequests)

        viewModel.setConversationVisible(false)
        assertEquals(listOf(CHAT_ID), realtime.released)
    }

    @Test
    fun `with no socket the conversation polls on the advertised interval and stops when hidden`() =
        runTest {
            val repository = FakeChatRepository()
            val realtime = RecordingConversationSignals().apply { syncInterval.value = 10_000L }
            val viewModel = viewModel(repository, realtime = realtime)

            viewModel.setConversationVisible(true)
            runCurrent()
            assertEquals(1, repository.syncRequests)

            advanceTimeBy(9_999)
            runCurrent()
            assertEquals(1, repository.syncRequests)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(2, repository.syncRequests)

            // The socket came back mid-wait. `collectLatest` has to cancel the pending
            // delay rather than let one more sync fire behind the nudges.
            realtime.syncInterval.value = null
            runCurrent()
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(2, repository.syncRequests)

            realtime.syncInterval.value = 10_000L
            runCurrent()
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(3, repository.syncRequests)

            viewModel.setConversationVisible(false)
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(3, repository.syncRequests)
        }

    @Test
    fun `visible conversation marks only unread inbound projection once`() = runTest {
        val repository = FakeChatRepository().apply {
            messages.value = listOf(
                Message(
                    id = "inbound-message",
                    text = "hello",
                    time = "12:00",
                    fromMe = false,
                    state = DeliveryState.DELIVERED,
                ),
            )
        }
        val viewModel = viewModel(repository)

        runCurrent()
        assertEquals(0, repository.readRequests)
        viewModel.setConversationVisible(true)
        runCurrent()
        assertEquals(1, repository.readRequests)

        viewModel.setConversationVisible(false)
        viewModel.setConversationVisible(true)
        runCurrent()
        assertEquals(1, repository.readRequests)
        viewModel.setConversationVisible(false)
    }

    @Test
    fun `failed visible read receipt retries on cadence and stops after local success`() = runTest {
        val repository = FakeChatRepository(readFailures = 1).apply {
            messages.value = listOf(
                Message(
                    id = "inbound-message",
                    text = "retry receipt",
                    time = "12:00",
                    fromMe = false,
                    state = DeliveryState.DELIVERED,
                ),
            )
        }
        val viewModel = viewModel(repository)

        viewModel.setConversationVisible(true)
        runCurrent()
        assertEquals(1, repository.readRequests)
        assertEquals(DeliveryState.DELIVERED, repository.messages.value.single().state)

        // The 45-second idle timer, not a message poll. Delivery rides the socket now;
        // this timer survives it because a receipt whose POST failed without changing
        // local state has no other retry path, and neither does a transfer that expires.
        advanceTimeBy(44_999)
        runCurrent()
        assertEquals(1, repository.readRequests)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, repository.readRequests)
        assertEquals(DeliveryState.READ, repository.messages.value.single().state)

        advanceTimeBy(90_000)
        runCurrent()
        assertEquals(2, repository.readRequests)
        viewModel.setConversationVisible(false)
    }

    @Test
    fun `hiding conversation cancels an in-flight read publication`() = runTest {
        val releaseRead = CompletableDeferred<Unit>()
        val repository = FakeChatRepository(readBlockUntil = releaseRead).apply {
            messages.value = listOf(
                Message(
                    id = "inbound-message",
                    text = "do not publish off-screen",
                    time = "12:00",
                    fromMe = false,
                    state = DeliveryState.DELIVERED,
                ),
            )
        }
        val viewModel = viewModel(repository)

        viewModel.setConversationVisible(true)
        runCurrent()
        assertEquals(1, repository.readRequests)

        viewModel.setConversationVisible(false)
        runCurrent()

        assertEquals(1, repository.readCancellations)
        assertEquals(DeliveryState.DELIVERED, repository.messages.value.single().state)
        releaseRead.complete(Unit)
        runCurrent()
        assertEquals(DeliveryState.DELIVERED, repository.messages.value.single().state)
    }

    @Test
    fun `open media window evicts its oldest handle without deleting the file`() = runTest {
        val opened = 25
        val repository = FakeChatRepository(
            media = (1..opened).associate { index ->
                "descriptor-$index" to byteArrayOf(index.toByte())
            },
        )
        val viewModel = viewModel(repository)

        (1..opened).forEach { index ->
            viewModel.openMedia(
                Message(
                    id = "message-$index",
                    text = "Photo",
                    time = "12:00",
                    fromMe = false,
                    mediaDescriptor = "descriptor-$index",
                ),
            )
        }

        // The window is a window onto the cache, not a second copy of it: the oldest handle is
        // dropped so the map cannot grow without bound, but the bytes stay where the on-disk
        // cache put them, ready to be handed straight back on the next open.
        assertEquals(
            (2..opened).map { "message-$it" }.toSet(),
            viewModel.mediaFiles.value.keys,
        )
        assertTrue(repository.mediaFile("descriptor-1").isFile)
        repository.deleteMediaScratch()
    }

    @Test
    fun `visible conversation preloads only five newest attachments`() = runTest {
        val projected = (1..7).map { index ->
            Message(
                id = "message-$index",
                text = "Photo",
                time = "12:00",
                fromMe = false,
                kind = MessageKind.IMAGE,
                mediaDescriptor = "descriptor-$index",
                mediaPlaintextBytes = index,
            )
        }
        val repository = FakeChatRepository(
            media = (1..7).associate { index ->
                "descriptor-$index" to byteArrayOf(index.toByte())
            },
        )
        val viewModel = viewModel(repository)

        repository.messages.value = projected
        runCurrent()
        assertEquals(0, repository.mediaOpenRequests)

        viewModel.setConversationVisible(true)
        runCurrent()

        assertEquals(
            (7 downTo 3).map { "descriptor-$it" },
            repository.openedSingleMedia,
        )
        assertEquals(
            (3..7).map { "message-$it" }.toSet(),
            viewModel.mediaFiles.value.keys,
        )

        viewModel.setConversationVisible(false)
        repository.deleteMediaScratch()
    }

    @Test
    fun `automatic preload stays serialized and does not duplicate projected or manual opens`() =
        runTest {
            val releaseFirst = CompletableDeferred<Unit>()
            val projected = (1..5).map { index ->
                Message(
                    id = "message-$index",
                    text = "Photo",
                    time = "12:00",
                    fromMe = false,
                    kind = MessageKind.IMAGE,
                    mediaDescriptor = "descriptor-$index",
                    mediaPlaintextBytes = index,
                )
            }
            val repository = FakeChatRepository(
                mediaBlockUntil = releaseFirst,
                media = (1..5).associate { index ->
                    "descriptor-$index" to byteArrayOf(index.toByte())
                },
            )
            val viewModel = viewModel(repository)
            repository.messages.value = projected

            viewModel.setConversationVisible(true)
            runCurrent()
            assertEquals(listOf("descriptor-5"), repository.openedSingleMedia)

            // The target set changes as a flow value but still projects the same five media rows.
            repository.messages.value = projected + Message(
                id = "text-only",
                text = "new captionless message",
                time = "12:01",
                fromMe = false,
            )
            viewModel.openMedia(projected.last())
            runCurrent()
            assertEquals(1, repository.mediaOpenRequests)

            releaseFirst.complete(Unit)
            runCurrent()

            assertEquals(
                (5 downTo 1).map { "descriptor-$it" },
                repository.openedSingleMedia,
            )
            assertEquals(5, repository.mediaOpenRequests)
            assertEquals(1, repository.maximumConcurrentMediaOpens)

            viewModel.setConversationVisible(false)
            repository.deleteMediaScratch()
        }

    @Test
    fun `queued preload stops when hidden while manual open remains available`() = runTest {
        val releaseBlockingOpen = CompletableDeferred<Unit>()
        val blockingRepository = FakeChatRepository(
            mediaBlockUntil = releaseBlockingOpen,
            media = mapOf("blocking-descriptor" to byteArrayOf(1)),
        )
        val blockingViewModel = viewModel(blockingRepository)
        blockingViewModel.openMedia(
            Message(
                id = "blocking-message",
                text = "Photo",
                time = "12:00",
                fromMe = false,
                mediaDescriptor = "blocking-descriptor",
            ),
        )
        runCurrent()
        assertEquals(1, blockingRepository.mediaOpenRequests)

        val target = Message(
            id = "preload-message",
            text = "Photo",
            time = "12:01",
            fromMe = false,
            kind = MessageKind.IMAGE,
            mediaDescriptor = "preload-descriptor",
            mediaPlaintextBytes = 1,
        )
        val repository = FakeChatRepository(
            media = mapOf("preload-descriptor" to byteArrayOf(2)),
        )
        val viewModel = viewModel(repository)
        repository.messages.value = listOf(target)

        viewModel.setConversationVisible(true)
        runCurrent()
        assertTrue(target.id in viewModel.mediaLoading.value)
        assertEquals(0, repository.mediaOpenRequests)

        viewModel.setConversationVisible(false)
        releaseBlockingOpen.complete(Unit)
        runCurrent()

        assertEquals(0, repository.mediaOpenRequests)
        assertTrue(viewModel.mediaLoading.value.isEmpty())
        assertTrue(viewModel.mediaErrors.value.isEmpty())

        // Explicit user intent remains valid even while the lifecycle-driven preloader is off.
        viewModel.openMedia(target)
        runCurrent()
        assertEquals(1, repository.mediaOpenRequests)
        assertTrue(target.id in viewModel.mediaFiles.value)

        blockingRepository.deleteMediaScratch()
        repository.deleteMediaScratch()
    }

    @Test
    fun `preload waits for positive plaintext sizes and skips staging album items`() = runTest {
        val single = Message(
            id = "single",
            text = "Photo",
            time = "12:00",
            fromMe = false,
            kind = MessageKind.IMAGE,
            mediaDescriptor = "single-descriptor",
            mediaPlaintextBytes = 0,
        )
        val delayedAlbumItem = MessageMediaItem("album-delayed", "image/jpeg", 0)
        val stagingAlbumItem = MessageMediaItem("staging:pending", "image/jpeg", 12)
        val readyAlbumItem = MessageMediaItem("album-ready", "image/jpeg", 12)
        val album = Message(
            id = "album",
            text = "Album",
            time = "12:01",
            fromMe = false,
            kind = MessageKind.MEDIA_ALBUM,
            mediaDescriptor = "album-descriptor",
            mediaItems = listOf(delayedAlbumItem, stagingAlbumItem, readyAlbumItem),
        )
        val repository = FakeChatRepository(
            media = mapOf("single-descriptor" to byteArrayOf(1)),
            albumMedia = mapOf(
                ("album-descriptor" to "album-delayed") to byteArrayOf(2),
                ("album-descriptor" to "album-ready") to byteArrayOf(3),
            ),
        )
        val viewModel = viewModel(repository)
        repository.messages.value = listOf(single, album)

        viewModel.setConversationVisible(true)
        runCurrent()

        assertEquals(
            listOf("album-descriptor" to "album-ready"),
            repository.openedAlbumMedia,
        )
        assertTrue(repository.openedSingleMedia.isEmpty())
        assertTrue(viewModel.mediaErrors.value.isEmpty())

        repository.messages.value = listOf(
            single.copy(mediaPlaintextBytes = 1),
            album.copy(
                mediaItems = listOf(
                    delayedAlbumItem.copy(plaintextBytes = 2),
                    stagingAlbumItem,
                    readyAlbumItem,
                ),
            ),
        )
        runCurrent()

        assertEquals(listOf("single-descriptor"), repository.openedSingleMedia)
        assertEquals(
            listOf(
                "album-descriptor" to "album-ready",
                "album-descriptor" to "album-delayed",
            ),
            repository.openedAlbumMedia,
        )
        assertFalse(
            albumItemMediaKey(album.id, stagingAlbumItem.attachmentId) in
                viewModel.mediaFiles.value,
        )
        assertTrue(viewModel.mediaErrors.value.isEmpty())

        viewModel.setConversationVisible(false)
        repository.deleteMediaScratch()
    }

    @Test
    fun `an early tap on a still preparing attachment never blocks the automatic open`() = runTest {
        val pending = Message(
            id = "pending-photo",
            text = "Photo",
            time = "12:00",
            fromMe = true,
            state = DeliveryState.SENDING,
            kind = MessageKind.IMAGE,
            mediaDescriptor = "local-descriptor",
            mediaPlaintextBytes = 0,
        )
        val repository = FakeChatRepository(media = mapOf("local-descriptor" to byteArrayOf(7)))
        repository.preparingMedia += "local-descriptor"
        val viewModel = viewModel(repository)
        repository.messages.value = listOf(pending)

        // Tapped while the import is still copying: the probe reaches the repository and is told
        // "not yet". That answer must leave no trace — a remembered error here used to refuse
        // every later claim, leaving a finished send unplayable until a manual retry.
        viewModel.openMedia(pending)
        runCurrent()
        assertEquals(listOf("local-descriptor"), repository.openedSingleMedia)
        assertTrue(viewModel.mediaErrors.value.isEmpty())
        assertTrue(viewModel.mediaLoading.value.isEmpty())
        assertFalse(pending.id in viewModel.mediaFiles.value)

        // The queue advances and the projection re-emits with a real byte count; the preloader
        // must now claim the same attachment by itself and hand the bubble a playable file.
        repository.preparingMedia.clear()
        repository.messages.value = listOf(pending.copy(mediaPlaintextBytes = 1))
        viewModel.setConversationVisible(true)
        runCurrent()

        assertEquals(listOf("local-descriptor", "local-descriptor"), repository.openedSingleMedia)
        assertTrue(pending.id in viewModel.mediaFiles.value)
        assertTrue(viewModel.mediaErrors.value.isEmpty())

        viewModel.setConversationVisible(false)
        repository.deleteMediaScratch()
    }

    @Test
    fun `a still preparing album item leaves no sticky error and opens once it advances`() = runTest {
        val item = MessageMediaItem("item-1", "image/jpeg", 12)
        val album = Message(
            id = "pending-album",
            text = "Album",
            time = "12:00",
            fromMe = true,
            state = DeliveryState.SENDING,
            kind = MessageKind.MEDIA_ALBUM,
            mediaDescriptor = "album-descriptor",
            mediaItems = listOf(item),
        )
        val repository = FakeChatRepository(
            albumMedia = mapOf(("album-descriptor" to "item-1") to byteArrayOf(9)),
        )
        repository.preparingMedia += "album-descriptor"
        val viewModel = viewModel(repository)
        repository.messages.value = listOf(album)
        val key = albumItemMediaKey(album.id, item.attachmentId)

        viewModel.openAlbumItem(album, item)
        runCurrent()
        assertEquals(listOf("album-descriptor" to "item-1"), repository.openedAlbumMedia)
        assertTrue(viewModel.mediaErrors.value.isEmpty())
        assertTrue(viewModel.mediaLoading.value.isEmpty())

        // Once the album's spool lands, the very same tap must succeed — no retry affordance
        // exists for a tile that was never allowed to look broken.
        repository.preparingMedia.clear()
        viewModel.openAlbumItem(album, item)
        runCurrent()

        assertTrue(key in viewModel.mediaFiles.value)
        assertTrue(viewModel.mediaErrors.value.isEmpty())

        repository.deleteMediaScratch()
    }

    @Test
    fun `multi photo receive is serialized one attachment at a time`() = runTest {
        val releaseFirst = CompletableDeferred<Unit>()
        val repository = FakeChatRepository(
            mediaBlockUntil = releaseFirst,
            media = (1..5).associate { index ->
                "descriptor-$index" to ByteArray(64) { index.toByte() }
            },
        )
        val viewModel = viewModel(repository)

        (1..5).forEach { index ->
            viewModel.openMedia(
                Message(
                    id = "message-$index",
                    text = "Photo",
                    time = "12:00",
                    fromMe = false,
                    mediaDescriptor = "descriptor-$index",
                ),
            )
        }
        runCurrent()

        assertEquals(1, repository.mediaOpenRequests)
        assertEquals(1, repository.maximumConcurrentMediaOpens)

        releaseFirst.complete(Unit)
        advanceUntilIdle()

        assertEquals(5, repository.mediaOpenRequests)
        assertEquals(1, repository.maximumConcurrentMediaOpens)
        // Five is well inside the handle window; serialization is what this test is about, and
        // the byte budget it used to assert here now belongs to SecureMediaCache.
        assertEquals(
            (1..5).map { "message-$it" }.toSet(),
            viewModel.mediaFiles.value.keys,
        )
        repository.deleteMediaScratch()
    }

    @Test
    fun `media receive remains serialized across separate conversation view models`() = runTest {
        val releaseFirst = CompletableDeferred<Unit>()
        val repository = FakeChatRepository(
            mediaBlockUntil = releaseFirst,
            media = mapOf(
                "descriptor-1" to byteArrayOf(1),
                "descriptor-2" to byteArrayOf(2),
            ),
        )
        val firstViewModel = viewModel(repository)
        val secondViewModel = viewModel(repository)

        firstViewModel.openMedia(
            Message("message-1", "Photo", "12:00", false, mediaDescriptor = "descriptor-1"),
        )
        runCurrent()
        secondViewModel.openMedia(
            Message("message-2", "Photo", "12:00", false, mediaDescriptor = "descriptor-2"),
        )
        runCurrent()

        assertEquals(1, repository.mediaOpenRequests)
        assertEquals(1, repository.maximumConcurrentMediaOpens)
        releaseFirst.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, repository.mediaOpenRequests)
        assertEquals(1, repository.maximumConcurrentMediaOpens)
    }

    @Test
    fun `cancelled media-open handoff publishes nothing`() = runTest {
        val release = CompletableDeferred<Unit>()
        val plaintext = byteArrayOf(21, 22, 23, 24)
        val repository = FakeChatRepository(
            mediaBlockUntil = release,
            media = mapOf("descriptor" to plaintext),
        )
        val viewModel = viewModel(repository)

        viewModel.openMedia(
            Message(
                id = "message",
                text = "Photo",
                time = "12:00",
                fromMe = false,
                mediaDescriptor = "descriptor",
            ),
        )
        runCurrent()
        assertEquals(1, repository.mediaOpenRequests)

        viewModel.viewModelScope.cancel()
        release.complete(Unit)
        runCurrent()

        // The attachment landed in the on-disk cache — that is the point of the cache — but a
        // ViewModel whose scope is gone must not publish a handle to a screen that has left.
        assertTrue(viewModel.mediaFiles.value.isEmpty())
        repository.deleteMediaScratch()
    }

    @Test
    fun `completed media send zeroizes picker plaintext`() = runTest {
        val repository = FakeChatRepository()
        val viewModel = viewModel(repository)
        val bytes = byteArrayOf(1, 2, 3, 4)

        viewModel.sendImage(bytes, "image/jpeg")

        assertEquals(1, repository.sentImages.size)
        assertEquals(CHAT_ID, repository.sentImages.single().first)
        assertEquals("image/jpeg", repository.sentImages.single().second)
        assertTrue(repository.sentImages.single().third.contentEquals(byteArrayOf(1, 2, 3, 4)))
        assertTrue(bytes.all { it == 0.toByte() })
    }

    @Test
    fun `rejected media send zeroizes picker plaintext`() = runTest {
        val repository = FakeChatRepository(initiallyReady = false)
        val viewModel = viewModel(repository)
        val bytes = byteArrayOf(5, 6, 7, 8)

        viewModel.sendImage(bytes, "image/jpeg")

        assertTrue(repository.sentImages.isEmpty())
        assertTrue(bytes.all { it == 0.toByte() })
    }

    @Test
    fun `media send into a cleared scope still zeroizes picker plaintext`() = runTest {
        val repository = FakeChatRepository()
        val viewModel = viewModel(repository)
        viewModel.viewModelScope.cancel()
        val bytes = byteArrayOf(9, 10, 11, 12)

        viewModel.sendImage(bytes, "image/jpeg")
        runCurrent()

        assertTrue(repository.sentImages.isEmpty())
        assertTrue(bytes.all { it == 0.toByte() })
    }

    @Test
    fun `accepting a held transfer records the acceptance in the conversation`() = runTest {
        val wallet = FakeWalletRepository(claims = listOf(pendingClaim(fromMe = false)))
        val repository = FakeChatRepository()
        val viewModel = viewModel(repository, wallet)

        viewModel.acceptTransfer(transferBubble(fromMe = false), claimableTransfersEnabled = true)

        assertEquals(listOf(CLAIM_ID to null), wallet.accepted)
        val posted = checkNotNull(KitPaymentMessage.parse(repository.sent.single().second))
        assertEquals(KitPaymentAction.ACCEPTED, posted.action)
        assertEquals(CLAIM_ID, posted.referenceId)
        assertEquals(250_000L, posted.amountMinor)
        assertEquals(null, posted.reason)
        assertEquals(null, viewModel.error.value)
        assertEquals(1, wallet.capabilityRefreshes)
        assertEquals(1, wallet.authoritativeReads)
        // The card's live state comes straight back from the settled claim, with no extra poll.
        assertEquals(
            TransferClaimStatus.ACCEPTED,
            viewModel.transferClaims.value[CLAIM_ID]?.status,
        )
    }

    @Test
    fun `reversing a transfer documents the reason it was reversed`() = runTest {
        val wallet = FakeWalletRepository(claims = listOf(pendingClaim(fromMe = true)))
        val repository = FakeChatRepository()
        val viewModel = viewModel(repository, wallet)

        viewModel.reverseTransfer(
            transferBubble(fromMe = true),
            "  Sent to the wrong person  ",
            paymentPin = "2580",
            claimableTransfersEnabled = true,
        )

        assertEquals(
            listOf(Triple(CLAIM_ID, "Sent to the wrong person", "2580")),
            wallet.reversed,
        )
        val posted = checkNotNull(KitPaymentMessage.parse(repository.sent.single().second))
        assertEquals(KitPaymentAction.REVERSED, posted.action)
        assertEquals("Sent to the wrong person", posted.reason)
        // The transfer's own note stays on the card above; the line carries only the reason.
        assertEquals(null, posted.note)
        assertEquals(1, wallet.capabilityRefreshes)
        assertEquals(1, wallet.authoritativeReads)
    }

    @Test
    fun `cached capability off prevents any transfer authority request`() = runTest {
        val wallet = FakeWalletRepository(claims = listOf(pendingClaim(fromMe = true)))
        val repository = FakeChatRepository()
        val viewModel = viewModel(repository, wallet)

        viewModel.reverseTransfer(
            transferBubble(fromMe = true),
            reason = null,
            paymentPin = "2580",
            claimableTransfersEnabled = false,
        )

        assertEquals(0, wallet.capabilityRefreshes)
        assertEquals(0, wallet.authoritativeReads)
        assertTrue(wallet.reversed.isEmpty())
        assertTrue(repository.sent.isEmpty())
    }

    @Test
    fun `tap-time capability refresh fails closed before reading or settling`() = runTest {
        val wallet = FakeWalletRepository(
            claims = listOf(pendingClaim(fromMe = true)),
            capabilityAvailable = false,
        )
        val repository = FakeChatRepository()
        val viewModel = viewModel(repository, wallet)

        viewModel.reverseTransfer(
            transferBubble(fromMe = true),
            reason = null,
            paymentPin = "2580",
            claimableTransfersEnabled = true,
        )

        assertEquals(1, wallet.capabilityRefreshes)
        assertEquals(0, wallet.authoritativeReads)
        assertTrue(wallet.reversed.isEmpty())
        assertTrue(repository.sent.isEmpty())
    }

    @Test
    fun `fresh claim read failure never falls back to the polled claim`() = runTest {
        val wallet = FakeWalletRepository(
            claims = listOf(pendingClaim(fromMe = true)),
            authoritativeFailure = IllegalStateException("fresh claim unavailable"),
        )
        val repository = FakeChatRepository()
        val viewModel = viewModel(repository, wallet)

        viewModel.reverseTransfer(
            transferBubble(fromMe = true),
            reason = null,
            paymentPin = "2580",
            claimableTransfersEnabled = true,
        )

        assertEquals(1, wallet.authoritativeReads)
        assertTrue(wallet.reversed.isEmpty())
        assertTrue(repository.sent.isEmpty())
        assertEquals("fresh claim unavailable", viewModel.error.value)
    }

    @Test
    fun `fresh settled claim overrides a stale pending card and prevents settlement`() = runTest {
        val stale = pendingClaim(fromMe = true)
        val wallet = FakeWalletRepository(
            claims = listOf(stale),
            authoritativeClaim = stale.copy(
                status = TransferClaimStatus.ACCEPTED,
                canReverse = false,
            ),
        )
        val repository = FakeChatRepository()
        val viewModel = viewModel(repository, wallet)

        viewModel.reverseTransfer(
            transferBubble(fromMe = true),
            reason = null,
            paymentPin = "2580",
            claimableTransfersEnabled = true,
        )

        assertEquals(1, wallet.authoritativeReads)
        assertTrue(wallet.reversed.isEmpty())
        assertTrue(repository.sent.isEmpty())
    }

    @Test
    fun `fresh claim for another conversation cannot be reversed`() = runTest {
        val foreign = pendingClaim(fromMe = true).copy(
            recipientUserId = "66666666-6666-4666-8666-666666666666",
        )
        val wallet = FakeWalletRepository(
            claims = listOf(foreign),
            authoritativeClaim = foreign,
        )
        val repository = FakeChatRepository()
        val viewModel = viewModel(repository, wallet)

        viewModel.reverseTransfer(
            transferBubble(fromMe = true),
            reason = null,
            paymentPin = "2580",
            claimableTransfersEnabled = true,
        )

        assertEquals(1, wallet.authoritativeReads)
        assertTrue(wallet.reversed.isEmpty())
        assertTrue(repository.sent.isEmpty())
    }

    @Test
    fun `rejecting a transfer documents the reason it was sent back`() = runTest {
        val wallet = FakeWalletRepository(claims = listOf(pendingClaim(fromMe = false)))
        val repository = FakeChatRepository()
        val viewModel = viewModel(repository, wallet)

        viewModel.rejectTransfer(
            transferBubble(fromMe = false),
            "I did not expect this",
            claimableTransfersEnabled = true,
        )

        assertEquals(listOf(CLAIM_ID to "I did not expect this"), wallet.rejected)
        val posted = checkNotNull(KitPaymentMessage.parse(repository.sent.single().second))
        assertEquals(KitPaymentAction.REJECTED, posted.action)
        assertEquals("I did not expect this", posted.reason)
    }

    @Test
    fun `an oversized reason is trimmed to what the descriptor can carry, not dropped`() = runTest {
        val wallet = FakeWalletRepository(claims = listOf(pendingClaim(fromMe = true)))
        val repository = FakeChatRepository()
        val viewModel = viewModel(repository, wallet)

        viewModel.reverseTransfer(
            transferBubble(fromMe = true),
            "x".repeat(400),
            paymentPin = "",
            claimableTransfersEnabled = true,
        )

        val posted = checkNotNull(KitPaymentMessage.parse(repository.sent.single().second))
        assertEquals("x".repeat(KitPaymentMessage.MAX_REASON_LENGTH), posted.reason)
    }

    @Test
    fun `a refused settlement surfaces the reason and writes nothing into the chat`() = runTest {
        val wallet = FakeWalletRepository(
            claims = listOf(
                pendingClaim(fromMe = true).copy(status = TransferClaimStatus.ACCEPTED),
            ),
            authoritativeClaim = pendingClaim(fromMe = true),
            authoritativeClaimAfterSettlementFailure = pendingClaim(fromMe = true).copy(
                status = TransferClaimStatus.ACCEPTED,
                canReverse = false,
            ),
            settleFailure = IllegalStateException("This transfer has already been accepted"),
        )
        val repository = FakeChatRepository()
        val viewModel = viewModel(repository, wallet)

        viewModel.reverseTransfer(
            transferBubble(fromMe = true),
            "too late",
            paymentPin = "2580",
            claimableTransfersEnabled = true,
        )

        assertTrue(repository.sent.isEmpty())
        assertEquals("This transfer has already been accepted", viewModel.error.value)
        // The claim is re-read so the card's buttons match reality before the next attempt.
        assertEquals(
            TransferClaimStatus.ACCEPTED,
            viewModel.transferClaims.value[CLAIM_ID]?.status,
        )
        assertFalse(viewModel.sending.value)
    }

    @Test
    fun `a bubble with no claim reference settles nothing`() = runTest {
        val wallet = FakeWalletRepository(claims = listOf(pendingClaim(fromMe = false)))
        val repository = FakeChatRepository()
        val viewModel = viewModel(repository, wallet)

        viewModel.acceptTransfer(
            transferBubble(fromMe = false).copy(paymentReferenceId = null),
            claimableTransfersEnabled = true,
        )

        assertTrue(wallet.accepted.isEmpty())
        assertTrue(repository.sent.isEmpty())
    }

    @Test
    fun `an expired transfer is written into the sender's chat exactly once`() = runTest {
        val wallet = FakeWalletRepository(
            claims = listOf(
                pendingClaim(fromMe = true).copy(status = TransferClaimStatus.EXPIRED),
            ),
        )
        val repository = FakeChatRepository().apply {
            messages.value = listOf(transferBubble(fromMe = true))
        }
        val viewModel = viewModel(repository, wallet)

        viewModel.setConversationVisible(true)
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()

        val posted = checkNotNull(KitPaymentMessage.parse(repository.sent.single().second))
        assertEquals(KitPaymentAction.EXPIRED, posted.action)
        assertEquals(CLAIM_ID, posted.referenceId)
        viewModel.setConversationVisible(false)
    }

    @Test
    fun `an expired transfer is not announced by the side that did not send it`() = runTest {
        val wallet = FakeWalletRepository(
            claims = listOf(
                pendingClaim(fromMe = false).copy(status = TransferClaimStatus.EXPIRED),
            ),
        )
        val repository = FakeChatRepository().apply {
            messages.value = listOf(transferBubble(fromMe = false))
        }
        val viewModel = viewModel(repository, wallet)

        viewModel.setConversationVisible(true)
        runCurrent()

        assertTrue(repository.sent.isEmpty())
        viewModel.setConversationVisible(false)
    }

    @Test
    fun `declining a request is a chat event, because a request holds no money`() = runTest {
        val wallet = FakeWalletRepository()
        val repository = FakeChatRepository()
        val viewModel = viewModel(repository, wallet)
        val request = KitPaymentMessage(
            action = KitPaymentAction.REQUEST,
            referenceId = REQUEST_ID,
            amountMinor = 250_000,
            currencyCode = "UGX",
            currencyScale = 2,
            note = "Rent",
        )

        viewModel.declinePaymentRequest(requestBubble(request, fromMe = false))

        assertTrue(wallet.cancelledRequests.isEmpty())
        val posted = checkNotNull(KitPaymentMessage.parse(repository.sent.single().second))
        assertEquals(KitPaymentAction.DECLINED, posted.action)
        assertEquals(REQUEST_ID, posted.referenceId)
        assertEquals("Rent", posted.note)
    }

    @Test
    fun `cancelling a request withdraws it server-side before saying so in chat`() = runTest {
        val wallet = FakeWalletRepository()
        val repository = FakeChatRepository()
        val viewModel = viewModel(repository, wallet)
        val request = KitPaymentMessage(
            action = KitPaymentAction.REQUEST,
            referenceId = REQUEST_ID,
            amountMinor = 250_000,
            currencyCode = "UGX",
            currencyScale = 2,
            note = null,
        )

        viewModel.cancelPaymentRequest(requestBubble(request, fromMe = true))

        assertEquals(listOf(REQUEST_ID), wallet.cancelledRequests)
        assertEquals(
            KitPaymentAction.CANCELLED,
            KitPaymentMessage.parse(repository.sent.single().second)?.action,
        )
    }

    @Test
    fun `a request that cannot be withdrawn is never reported as cancelled`() = runTest {
        val wallet = FakeWalletRepository(
            cancelFailure = IllegalStateException("This request was already paid"),
        )
        val repository = FakeChatRepository()
        val viewModel = viewModel(repository, wallet)
        val request = KitPaymentMessage(
            action = KitPaymentAction.REQUEST,
            referenceId = REQUEST_ID,
            amountMinor = 250_000,
            currencyCode = "UGX",
            currencyScale = 2,
            note = null,
        )

        viewModel.cancelPaymentRequest(requestBubble(request, fromMe = true))

        assertTrue(repository.sent.isEmpty())
        assertEquals("This request was already paid", viewModel.error.value)
    }

    private fun pendingClaim(fromMe: Boolean) = TransferClaim(
        id = CLAIM_ID,
        transactionId = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
        status = TransferClaimStatus.PENDING,
        amountMinor = 250_000,
        currencyCode = "UGX",
        currencyScale = 2,
        note = "Rent",
        senderUserId = if (fromMe) CURRENT_USER_ID else PEER_USER_ID,
        recipientUserId = if (fromMe) PEER_USER_ID else CURRENT_USER_ID,
        canAccept = !fromMe,
        canReject = !fromMe,
        canReverse = fromMe,
    )

    private fun transferBubble(fromMe: Boolean): Message {
        val descriptor = KitPaymentMessage(
            action = KitPaymentAction.TRANSFER,
            referenceId = CLAIM_ID,
            amountMinor = 250_000,
            currencyCode = "UGX",
            currencyScale = 2,
            note = "Rent",
        )
        return Message(
            id = "transfer-bubble",
            text = "",
            time = "12:00",
            fromMe = fromMe,
            kind = MessageKind.PAYMENT_TRANSFER,
            mediaDescriptor = descriptor.encode(),
            amountMinor = if (fromMe) -250_000 else 250_000,
            paymentReferenceId = CLAIM_ID,
            paymentEvent = PaymentEventKind.TRANSFER,
            paymentCurrencyCode = "UGX",
            paymentCurrencyScale = 2,
        )
    }

    private fun requestBubble(descriptor: KitPaymentMessage, fromMe: Boolean) = Message(
        id = "request-bubble",
        text = "",
        time = "12:00",
        fromMe = fromMe,
        kind = MessageKind.PAYMENT_REQUEST,
        mediaDescriptor = descriptor.encode(),
        amountMinor = descriptor.amountMinor,
        paymentReferenceId = descriptor.referenceId,
        paymentEvent = PaymentEventKind.REQUESTED,
        paymentCurrencyCode = descriptor.currencyCode,
        paymentCurrencyScale = descriptor.currencyScale,
    )

    @Test
    fun `an answer carries its target and the composer stops answering once it is sent`() = runTest {
        val repository = FakeChatRepository()
        val viewModel = viewModel(repository)
        val target = deliveredMessage("target-message", "what time is it?")

        viewModel.beginReply(target)
        assertEquals(target, viewModel.replyTarget.value)

        viewModel.send("half past four") {}

        assertEquals(listOf(CHAT_ID to "half past four"), repository.sent)
        assertEquals(listOf(target.id), repository.sentReplyTargets)
        // The bar above the composer goes with the message; the next remark is a fresh one.
        assertNull(viewModel.replyTarget.value)

        viewModel.send("and it is raining") {}
        assertEquals(listOf(target.id, null), repository.sentReplyTargets)
    }

    @Test
    fun `cancelling an answer sends the next message as a fresh remark`() = runTest {
        val repository = FakeChatRepository()
        val viewModel = viewModel(repository)

        viewModel.beginReply(deliveredMessage("target-message", "what time is it?"))
        viewModel.cancelReply()
        assertNull(viewModel.replyTarget.value)

        viewModel.send("never mind") {}

        assertEquals(listOf<String?>(null), repository.sentReplyTargets)
    }

    @Test
    fun `a message with no ID on the other end cannot be answered`() = runTest {
        val repository = FakeChatRepository()
        val viewModel = viewModel(repository)

        // Still on its way out: nothing has minted the ID an answer would have to point at, so
        // swiping it must leave the composer exactly as it was rather than quoting a stub.
        viewModel.beginReply(
            Message("outgoing", "still sending", "12:00", fromMe = true, state = DeliveryState.SENDING),
        )
        assertNull(viewModel.replyTarget.value)

        // A timeline record is the conversation talking about itself; there is no author to answer.
        viewModel.beginReply(
            Message(
                "notice",
                "Grace joined",
                "12:00",
                fromMe = false,
                state = DeliveryState.DELIVERED,
                kind = MessageKind.SYSTEM,
            ),
        )
        assertNull(viewModel.replyTarget.value)
    }

    @Test
    fun `a photo can be the answer just as a sentence can`() = runTest {
        val repository = FakeChatRepository()
        val viewModel = viewModel(repository)
        val target = deliveredMessage("target-message", "what does it look like?")

        viewModel.beginReply(target)
        viewModel.sendMedia(byteArrayOf(1, 2, 3), "image/jpeg")
        advanceUntilIdle()

        assertEquals(listOf(target.id), repository.sentImageReplyTargets)
        assertNull(viewModel.replyTarget.value)
    }

    /** A message that has reached the other end, and so has an ID an answer can point at. */
    private fun deliveredMessage(id: String, text: String) = Message(
        id = id,
        text = text,
        time = "12:00",
        fromMe = false,
        state = DeliveryState.DELIVERED,
    )

    private fun viewModel(
        repository: ChatRepository,
        wallet: WalletRepository = FakeWalletRepository(),
        realtime: KitConversationSignals = InertConversationSignals,
        typingSignaller: KitTypingSignals = RecordingTypingSignals(),
    ) = ConversationViewModel(
        chatRepo = repository,
        walletRepo = wallet,
        walletSync = NoOpTestWalletSync,
        callRepo = FakeCallRepository(),
        messageSounds = NoOpMessageSoundPlayer,
        realtime = realtime,
        typingSignaller = typingSignaller,
        savedStateHandle = SavedStateHandle(mapOf("chatId" to CHAT_ID)),
    )

    private object NoOpMessageSoundPlayer : MessageSoundPlayer {
        override fun playSent() = Unit
        override fun playReceived() = Unit
        override fun playPaymentReceived() = Unit
    }

    /** The conversation call-log merge is exercised elsewhere; these tests need no call data. */
    private class FakeCallRepository : CallRepository {
        override val calls: StateFlow<List<CallEntry>> = MutableStateFlow(emptyList())
        override suspend fun refresh() = Unit
    }

    /** Records what a conversation asks of the wallet, and answers with the claims it is given. */
    private class FakeWalletRepository(
        private val claims: List<TransferClaim> = emptyList(),
        private val authoritativeClaim: TransferClaim? = claims.firstOrNull(),
        private val authoritativeClaimAfterSettlementFailure: TransferClaim? = null,
        private val capabilityAvailable: Boolean = true,
        private val capabilityFailure: Exception? = null,
        private val authoritativeFailure: Exception? = null,
        private val settleFailure: Exception? = null,
        private val cancelFailure: Exception? = null,
    ) : WalletRepository {
        val accepted = mutableListOf<Pair<String, String?>>()
        val rejected = mutableListOf<Pair<String, String?>>()
        val reversed = mutableListOf<Triple<String, String?, String>>()
        val cancelledRequests = mutableListOf<String>()
        var capabilityRefreshes = 0
        var authoritativeReads = 0

        override val currentAccountId: String = CURRENT_USER_ID
        override val balanceMinor: StateFlow<Long> = MutableStateFlow(0L)
        override val transactions: StateFlow<List<Transaction>> = MutableStateFlow(emptyList())
        override val beneficiaries: StateFlow<List<Beneficiary>> = MutableStateFlow(emptyList())
        override fun transaction(id: String): Transaction? = null
        override suspend fun send(
            recipient: Contact,
            amountMinor: Long,
            note: String?,
            paymentPin: String,
        ): Transaction = error("Unused in conversation tests")
        override suspend fun request(from: Contact, amountMinor: Long, note: String?) =
            error("Unused in conversation tests")
        override suspend fun transferClaims(): List<TransferClaim> = claims
        override suspend fun refreshClaimableTransfersCapability(): Boolean {
            capabilityRefreshes++
            capabilityFailure?.let { throw it }
            return capabilityAvailable
        }
        override suspend fun transferClaim(claimId: String): TransferClaim {
            authoritativeReads++
            authoritativeFailure?.let { throw it }
            return checkNotNull(
                if (authoritativeReads > 1) {
                    authoritativeClaimAfterSettlementFailure ?: authoritativeClaim
                } else {
                    authoritativeClaim
                },
            ) { "No authoritative claim" }
        }
        override suspend fun acceptTransferClaim(claimId: String): TransferClaim =
            settle(claimId, null, accepted, TransferClaimStatus.ACCEPTED)
        override suspend fun rejectTransferClaim(claimId: String, reason: String?): TransferClaim =
            settle(claimId, reason, rejected, TransferClaimStatus.REJECTED)
        override suspend fun reverseTransferClaim(
            claimId: String,
            reason: String?,
            paymentPin: String,
        ): TransferClaim {
            reversed += Triple(claimId, reason, paymentPin)
            return settle(claimId, reason, null, TransferClaimStatus.REVERSED)
        }
        override suspend fun cancelChatPaymentRequest(requestId: String) {
            cancelledRequests += requestId
            cancelFailure?.let { throw it }
        }

        private fun settle(
            claimId: String,
            reason: String?,
            log: MutableList<Pair<String, String?>>?,
            settled: TransferClaimStatus,
        ): TransferClaim {
            log?.add(claimId to reason)
            settleFailure?.let { throw it }
            val claim = checkNotNull(authoritativeClaim).takeIf {
                it.id.equals(claimId, ignoreCase = true)
            } ?: error("No matching authoritative claim")
            return claim.copy(
                status = settled,
                reason = reason?.trim()?.takeIf(String::isNotBlank),
                canAccept = false,
                canReject = false,
                canReverse = false,
            )
        }
        override suspend fun payBill(
            provider: BillProvider,
            account: String,
            amountMinor: Long,
            paymentPin: String,
        ): Transaction = error("Unused in conversation tests")
        override suspend fun buyAirtime(
            productId: String,
            phone: String,
            amountMinor: Long,
            paymentPin: String,
        ): Transaction = error("Unused in conversation tests")
    }

    private data class FakeSendOutcome(
        val durablyCommitted: Boolean,
        val failure: Exception? = null,
        val completionGate: CompletableDeferred<Unit>? = null,
    )

    private class FakeChatRepository(
        private val failure: Exception? = null,
        private val durablyCommitBeforeCompletion: Boolean = failure == null,
        private val blockUntil: CompletableDeferred<Unit>? = null,
        private val sendOutcomes: List<FakeSendOutcome>? = null,
        private var readFailures: Int = 0,
        private val readBlockUntil: CompletableDeferred<Unit>? = null,
        initiallyLoaded: Boolean = true,
        initiallyReady: Boolean = true,
        private val mediaBlockUntil: CompletableDeferred<Unit>? = null,
        private val media: Map<String, ByteArray> = emptyMap(),
        private val albumMedia: Map<Pair<String, String>, ByteArray> = emptyMap(),
    ) : ChatRepository {
        // Attachments are handed to the UI as files now, so the fake writes real ones into a
        // scratch directory the test tears down; nothing here can pass while production still
        // expects bytes it can zeroize.
        private val mediaDirectory: File =
            File(System.getProperty("java.io.tmpdir"), "kit-media-test-${UUID.randomUUID()}")
                .apply { mkdirs() }

        fun mediaFile(descriptor: String): File = File(mediaDirectory, descriptor)

        fun deleteMediaScratch() {
            mediaDirectory.deleteRecursively()
        }

        private val preview = ChatPreview(CHAT_ID, "Grace", "", "", peerUserId = PEER_USER_ID)
        override val readiness: StateFlow<Boolean> = MutableStateFlow(initiallyReady)
        private val mutableChats = MutableStateFlow(if (initiallyLoaded) listOf(preview) else emptyList())
        override val chats: StateFlow<List<ChatPreview>> = mutableChats
        val messages = MutableStateFlow<List<Message>>(emptyList())
        val sent = mutableListOf<Pair<String, String>>()
        val retried = mutableListOf<Triple<String, String, String>>()
        val sentImages = mutableListOf<Triple<String, String, ByteArray>>()
        /** What each send in [sent] was answering, in the same order; null for a fresh remark. */
        val sentReplyTargets = mutableListOf<String?>()
        /** The same, for each send in [sentImages]. */
        val sentImageReplyTargets = mutableListOf<String?>()
        var syncRequests = 0
        var readRequests = 0
        var readCancellations = 0
        var mediaOpenRequests = 0
        var maximumConcurrentMediaOpens = 0
        private var concurrentMediaOpens = 0
        val openedSingleMedia = mutableListOf<String>()
        val openedAlbumMedia = mutableListOf<Pair<String, String>>()
        /** Descriptors whose queue rows are still importing or encrypting; opens answer "not yet". */
        val preparingMedia = mutableSetOf<String>()

        fun publishChat() {
            mutableChats.value = listOf(preview)
        }

        override fun chat(chatId: String): ChatPreview? =
            mutableChats.value.singleOrNull { it.id == chatId }

        override fun conversation(chatId: String): StateFlow<List<Message>> = messages

        override suspend fun openDirectConversation(contact: Contact): String = error("Not used")

        override suspend fun sendMessage(
            chatId: String,
            text: String,
            replyToMessageId: String?,
            onDurablyCommitted: (clientMessageId: String) -> Unit,
        ) {
            val attemptIndex = sent.size
            sent += chatId to text
            sentReplyTargets += replyToMessageId
            val outcome = sendOutcomes?.getOrNull(attemptIndex) ?: FakeSendOutcome(
                durablyCommitted = durablyCommitBeforeCompletion,
                failure = failure,
                completionGate = blockUntil,
            )
            val clientMessageId = "test-client-${attemptIndex + 1}"
            if (outcome.durablyCommitted) {
                onDurablyCommitted(clientMessageId)
            }
            outcome.completionGate?.await()
            outcome.failure?.let { throw it }
            if (!outcome.durablyCommitted) {
                onDurablyCommitted(clientMessageId)
            }
        }

        override suspend fun retryMessage(
            chatId: String,
            clientMessageId: String,
            text: String,
        ) {
            retried += Triple(chatId, clientMessageId, text)
            failure?.let { throw it }
            blockUntil?.await()
        }

        override suspend fun sendMediaMessage(
            chatId: String,
            source: SecureMediaSource,
            mediaType: String,
            caption: String?,
            replyToMessageId: String?,
        ) {
            sentImages += Triple(chatId, mediaType, source.open().use { it.readBytes() })
            sentImageReplyTargets += replyToMessageId
        }

        override suspend fun synchronizeConversation(chatId: String) {
            assertEquals(CHAT_ID, chatId)
            syncRequests++
        }

        override suspend fun markConversationRead(chatId: String) {
            assertEquals(CHAT_ID, chatId)
            readRequests++
            if (readFailures > 0) {
                readFailures--
                throw IllegalStateException("temporary read-receipt failure")
            }
            try {
                readBlockUntil?.await()
            } catch (cancelled: CancellationException) {
                readCancellations++
                throw cancelled
            }
            messages.value = messages.value.map { message ->
                if (!message.fromMe && message.state == DeliveryState.DELIVERED) {
                    message.copy(state = DeliveryState.READ)
                } else {
                    message
                }
            }
        }

        override suspend fun openImageMessage(
            chatId: String,
            mediaDescriptor: String,
            messageId: String?,
            fromCurrentUser: Boolean?,
        ): SecureMediaFile {
            openedSingleMedia += mediaDescriptor
            if (mediaDescriptor in preparingMedia) throw SecureMediaStillPreparingException()
            return openMediaFixture(
                fileName = mediaDescriptor,
                plaintext = { checkNotNull(media[mediaDescriptor]) },
            )
        }

        override suspend fun openAlbumItemMessage(
            chatId: String,
            mediaDescriptor: String,
            attachmentId: String,
            messageId: String?,
            fromCurrentUser: Boolean?,
        ): SecureMediaFile {
            openedAlbumMedia += mediaDescriptor to attachmentId
            if (mediaDescriptor in preparingMedia) throw SecureMediaStillPreparingException()
            return openMediaFixture(
                fileName = "$mediaDescriptor-$attachmentId",
                plaintext = {
                    checkNotNull(albumMedia[mediaDescriptor to attachmentId])
                },
            )
        }

        private suspend fun openMediaFixture(
            fileName: String,
            plaintext: () -> ByteArray,
        ): SecureMediaFile {
            mediaOpenRequests++
            concurrentMediaOpens++
            maximumConcurrentMediaOpens = maxOf(
                maximumConcurrentMediaOpens,
                concurrentMediaOpens,
            )
            return try {
                mediaBlockUntil?.await()
                val bytes = plaintext()
                val file = mediaFile(fileName)
                file.writeBytes(bytes)
                SecureMediaFile(file, "image/jpeg", file.length().toLong())
            } finally {
                concurrentMediaOpens--
            }
        }
    }

    private companion object {
        const val CHAT_ID = "11111111-1111-4111-8111-111111111111"
        const val CLAIM_ID = "22222222-2222-4222-8222-222222222222"
        const val REQUEST_ID = "33333333-3333-4333-8333-333333333333"
        const val CURRENT_USER_ID = "44444444-4444-4444-8444-444444444444"
        const val PEER_USER_ID = "55555555-5555-4555-8555-555555555555"
    }
}
