package com.kit.wallet

import com.kit.wallet.data.messaging.SecureMediaAlbumSource
import com.kit.wallet.data.messaging.SecureMediaSource
import com.kit.wallet.data.repository.ChatRepository
import com.kit.wallet.data.repository.ContactRepository
import com.kit.wallet.data.session.SessionFence
import com.kit.wallet.feature.chat.IncomingShareAccess
import com.kit.wallet.feature.chat.SharedInboxAccess
import com.kit.wallet.feature.chat.SharedInboxBatch
import com.kit.wallet.feature.chat.SharedInboxItem
import com.kit.wallet.feature.chat.SharedInboxOwner
import com.kit.wallet.feature.chat.SharedInboxPolicy
import com.kit.wallet.feature.chat.SharedRecipient
import com.kit.wallet.feature.chat.SharedTextSendStart
import com.kit.wallet.feature.chat.SharedTextShareViewModel
import com.kit.wallet.feature.chat.incomingShareAccess
import com.kit.wallet.feature.chat.shareRecipientSections
import com.kit.wallet.feature.chat.pinnedShareRecipient
import com.kit.wallet.ui.model.ChatPreview
import com.kit.wallet.ui.model.Contact
import com.kit.wallet.ui.model.Message
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class SharedTextShareViewModelTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `an offline device with an open encrypted outbox may pick a recipient`() {
        assertEquals(
            IncomingShareAccess.RECIPIENT_PICKER,
            incomingShareAccess(
                capabilitiesLoaded = true,
                capabilityLoadFailed = true,
                secureMessagingServerCompatible = false,
                localOutboxAvailable = true,
            ),
        )
    }

    @Test
    fun `an offline device without a local outbox must retry capability setup`() {
        assertEquals(
            IncomingShareAccess.RETRY_CAPABILITIES,
            incomingShareAccess(
                capabilitiesLoaded = true,
                capabilityLoadFailed = true,
                secureMessagingServerCompatible = false,
                localOutboxAvailable = false,
            ),
        )
    }

    @Test
    fun `a definitive incompatible server response refuses sharing`() {
        assertEquals(
            IncomingShareAccess.UNAVAILABLE,
            incomingShareAccess(
                capabilitiesLoaded = true,
                capabilityLoadFailed = false,
                secureMessagingServerCompatible = false,
                localOutboxAvailable = true,
            ),
        )
    }

    @Test
    fun `picker puts five mixed recent chats first and contacts alphabetically`() {
        val chats = listOf(
            chat("one", "Newest group", isGroup = true),
            chat("two", "Grace", peerUserId = "grace"),
            chat("three", "Team", isGroup = true),
            chat("four", "Florence", peerUserId = "florence"),
            chat("five", "Work", isGroup = true),
            chat("six", "Older group", isGroup = true),
            chat("seven", "Zed", peerUserId = "zed"),
        )
        val contacts = listOf(
            contact("zed", "Zed"),
            contact("ama", "Ama"),
            contact("grace", "Grace"),
            contact("off-kit", "Visitor", isKitUser = false),
        )

        val sections = shareRecipientSections(chats, contacts)

        assertEquals(
            listOf("one", "two", "three", "four", "five"),
            sections.recent.map { it.chat.id },
        )
        assertEquals(listOf("Ama", "Zed"), sections.contacts.map { it.name })
        assertEquals("seven", sections.contacts.single { it.name == "Zed" }.existingChatId)
        assertEquals(listOf("six"), sections.otherGroups.map { it.chat.id })
    }

    @Test
    fun `picker search finds contacts by registered name and groups beyond recents`() {
        val chats = (1..5).map { chat("chat-$it", "Recent $it") } +
            chat("family", "Family abroad", isGroup = true)
        val emma = contact("emma", "My brother", registeredName = "Namisi Emmanuel")

        assertEquals(
            listOf("My brother"),
            shareRecipientSections(chats, listOf(emma), "Emmanuel").contacts.map { it.name },
        )
        assertEquals(
            listOf("family"),
            shareRecipientSections(chats, listOf(emma), "family").otherGroups.map { it.chat.id },
        )
    }

    @Test
    fun `send accepts a current group conversation`() = runTest {
        val repository = FakeChatRepository(listOf(chat(GROUP_ID, "Family", isGroup = true)))
        val viewModel = viewModel(FakeSharedInbox(), repository)
        viewModel.begin("request")
        val done = CompletableDeferred<Unit>()

        val result = viewModel.send(
            "request",
            conversation(repository, GROUP_ID),
            batch(text = "Private text"),
        ) { done.complete(Unit) }
        done.await()

        assertEquals(SharedTextSendStart.STARTED, result)
        assertEquals(listOf(GROUP_ID to "Private text"), repository.sentMessages)
        assertTrue(viewModel.sendState.value.sent)
    }

    @Test
    fun `send accepts a current direct conversation`() = runTest {
        val repository = FakeChatRepository(listOf(chat(DIRECT_ID, "Grace")))
        val viewModel = viewModel(FakeSharedInbox(), repository)
        viewModel.begin("request")
        val done = CompletableDeferred<Unit>()

        val result = viewModel.send(
            "request",
            conversation(repository, DIRECT_ID),
            batch(text = "Hello"),
            onFinished = { done.complete(Unit) },
        )
        done.await()

        assertEquals(SharedTextSendStart.STARTED, result)
        assertEquals(listOf(DIRECT_ID to "Hello"), repository.sentMessages)
        assertTrue(viewModel.sendState.value.sent)
    }

    @Test
    fun `one share request is durably handed to the outbox only once`() = runTest {
        val repository = FakeChatRepository(listOf(chat(DIRECT_ID, "Grace")))
        val viewModel = viewModel(FakeSharedInbox(), repository)
        val recipient = conversation(repository, DIRECT_ID)
        val shared = batch(text = "One copy")
        viewModel.begin("request")
        val done = CompletableDeferred<Unit>()

        val first = viewModel.send("request", recipient, shared) { done.complete(Unit) }
        done.await()
        val duplicate = viewModel.send("request", recipient, shared) {}

        assertEquals(SharedTextSendStart.STARTED, first)
        assertEquals(SharedTextSendStart.REJECTED, duplicate)
        assertEquals(listOf(DIRECT_ID to "One copy"), repository.sentMessages)
    }

    @Test
    fun `a Kit Pay contact without a thread is resolved before local outbox send`() = runTest {
        val grace = contact("grace", "Grace")
        val repository = FakeChatRepository(emptyList())
        val viewModel = viewModel(FakeSharedInbox(), repository, listOf(grace))
        viewModel.begin("request")
        val done = CompletableDeferred<Unit>()

        val result = viewModel.send(
            "request",
            SharedRecipient.Person(grace, existingChatId = null),
            batch(text = "Hello"),
        ) { done.complete(Unit) }
        done.await()

        assertEquals(SharedTextSendStart.STARTED, result)
        assertEquals(listOf(grace), repository.openedContacts)
        assertEquals(listOf(OPENED_DIRECT_ID to "Hello"), repository.sentMessages)
    }

    @Test
    fun `an older contact thread is reused without a network conversation lookup`() = runTest {
        val grace = contact("grace", "Grace")
        val repository = FakeChatRepository(
            listOf(chat(EXISTING_DIRECT_ID, "Grace", peerUserId = grace.id)),
            ready = false,
            localHistoryReady = true,
        )
        val viewModel = viewModel(FakeSharedInbox(), repository, listOf(grace))
        viewModel.begin("request")
        val done = CompletableDeferred<Unit>()

        val result = viewModel.send(
            "request",
            SharedRecipient.Person(grace, existingChatId = EXISTING_DIRECT_ID),
            batch(text = "Still works offline"),
        ) { done.complete(Unit) }
        done.await()

        assertEquals(SharedTextSendStart.STARTED, result)
        assertTrue(repository.openedContacts.isEmpty())
        assertEquals(listOf(EXISTING_DIRECT_ID to "Still works offline"), repository.sentMessages)
    }

    @Test
    fun `send uses the local outbox while live messaging transport is offline`() = runTest {
        val repository = FakeChatRepository(
            initialChats = listOf(chat(DIRECT_ID, "Grace")),
            ready = false,
            localHistoryReady = true,
        )
        val viewModel = viewModel(FakeSharedInbox(), repository)
        viewModel.begin("request")
        val done = CompletableDeferred<Unit>()

        val result = viewModel.send(
            "request",
            conversation(repository, DIRECT_ID),
            batch(text = "Queued offline"),
        ) { done.complete(Unit) }
        done.await()

        assertEquals(SharedTextSendStart.STARTED, result)
        assertEquals(listOf(DIRECT_ID to "Queued offline"), repository.sentMessages)
    }

    @Test
    fun `send rejects a share carrying nothing`() = runTest {
        val repository = FakeChatRepository(listOf(chat(DIRECT_ID, "Grace")))
        val viewModel = viewModel(FakeSharedInbox(), repository)
        viewModel.begin("request")

        val result = viewModel.send(
            "request",
            conversation(repository, DIRECT_ID),
            batch(),
        ) {}

        assertEquals(SharedTextSendStart.REJECTED, result)
        assertTrue(repository.sentMessages.isEmpty())
    }

    @Test
    fun `files go before the words written about them`() = runTest {
        val repository = FakeChatRepository(listOf(chat(DIRECT_ID, "Grace")))
        val viewModel = viewModel(FakeSharedInbox(), repository)
        val shared = batch(
            text = "Here it is",
            items = listOf(item("one", "image/jpeg"), item("two", "application/pdf")),
        )
        viewModel.begin("request")
        val done = CompletableDeferred<Unit>()

        val result = viewModel.send(
            "request",
            conversation(repository, DIRECT_ID),
            shared,
        ) { done.complete(Unit) }
        done.await()

        assertEquals(SharedTextSendStart.STARTED, result)
        assertEquals(listOf("image/jpeg", "application/pdf"), repository.sentMedia.map { it.second })
        assertEquals(listOf(DIRECT_ID to "Here it is"), repository.sentMessages)
        assertTrue(viewModel.sendState.value.sent)
    }

    @Test
    fun `a delivered share leaves nothing staged`() = runTest {
        val repository = FakeChatRepository(listOf(chat(DIRECT_ID, "Grace")))
        val inbox = FakeSharedInbox()
        val shared = batch(items = listOf(item("one", "image/jpeg")))
        val viewModel = viewModel(inbox, repository)
        viewModel.begin("request")
        val done = CompletableDeferred<Unit>()

        viewModel.send(
            "request",
            conversation(repository, DIRECT_ID),
            shared,
        ) { done.complete(Unit) }
        done.await()

        assertEquals(listOf(shared.id), inbox.discarded)
    }

    @Test
    fun `a share that cannot be read back is not half sent`() = runTest {
        val repository = FakeChatRepository(listOf(chat(DIRECT_ID, "Grace")))
        val viewModel = viewModel(FakeSharedInbox(readable = false), repository)
        viewModel.begin("request")
        val done = CompletableDeferred<Unit>()

        viewModel.send(
            "request",
            conversation(repository, DIRECT_ID),
            batch(text = "Here it is", items = listOf(item("one", "image/jpeg"))),
        ) { done.complete(Unit) }
        done.await()

        assertTrue(repository.sentMedia.isEmpty())
        assertTrue(repository.sentMessages.isEmpty())
        assertTrue(!viewModel.sendState.value.sent)
        assertEquals("That shared file could no longer be read.", viewModel.sendState.value.error)
    }

    @Test
    fun `a pinned older direct chat remains reachable without a matching contact`() {
        val chats = (1..5).map { index ->
            chat("40000000-0000-4000-8000-00000000000$index", "Recent $index")
        } + chat(EXISTING_DIRECT_ID, "Older direct")

        assertEquals(
            EXISTING_DIRECT_ID,
            pinnedShareRecipient(chats, EXISTING_DIRECT_ID)?.chat?.id,
        )
    }

    @Test
    fun `reserved shared text is rejected before any media is queued`() = runTest {
        val repository = FakeChatRepository(listOf(chat(DIRECT_ID, "Grace")))
        val viewModel = viewModel(FakeSharedInbox(), repository)
        viewModel.begin("request")

        val result = viewModel.send(
            "request",
            conversation(repository, DIRECT_ID),
            batch(
                text = "KITMEDIA1:not-a-real-attachment",
                items = listOf(item("one", "image/jpeg")),
            ),
        ) {}

        assertEquals(SharedTextSendStart.REJECTED, result)
        assertTrue(repository.sentMedia.isEmpty())
        assertTrue(repository.sentMessages.isEmpty())
    }

    @Test
    fun `partial retry stays pinned and queues every component exactly once`() = runTest {
        val repository = FakeChatRepository(
            listOf(chat(DIRECT_ID, "Grace"), chat(GROUP_ID, "Family", isGroup = true)),
        ).apply { failMediaAttempt = 2 }
        val inbox = FakeSharedInbox()
        val shared = batch(
            text = "Both files",
            items = listOf(item("one", "image/jpeg"), item("two", "application/pdf")),
        )
        val viewModel = viewModel(inbox, repository)
        viewModel.begin("request")
        val firstDone = CompletableDeferred<Unit>()

        viewModel.send("request", conversation(repository, DIRECT_ID), shared) {
            firstDone.complete(Unit)
        }
        firstDone.await()

        assertEquals(DIRECT_ID, inbox.pinnedConversationId)
        assertEquals(1, repository.sentMedia.size)
        assertTrue(inbox.discarded.isEmpty())

        val wrongDestinationDone = CompletableDeferred<Unit>()
        viewModel.send("request", conversation(repository, GROUP_ID), shared) {
            wrongDestinationDone.complete(Unit)
        }
        wrongDestinationDone.await()
        assertTrue(repository.sentMedia.none { it.first == GROUP_ID })

        val retryDone = CompletableDeferred<Unit>()
        viewModel.send("request", conversation(repository, DIRECT_ID), shared) {
            retryDone.complete(Unit)
        }
        retryDone.await()

        assertEquals(2, repository.sentMedia.size)
        assertEquals(2, repository.sentMedia.map { it.third }.toSet().size)
        assertEquals(listOf(DIRECT_ID to "Both files"), repository.sentMessages)
        assertEquals(listOf(shared.id), inbox.discarded)
        assertTrue(viewModel.sendState.value.sent)
    }

    @Test
    fun `a capable multi-file share coalesces into exactly one album message`() = runTest {
        val repository = FakeChatRepository(listOf(chat(DIRECT_ID, "Grace")))
            .apply { albumsAvailable.value = true }
        val inbox = FakeSharedInbox()
        val viewModel = viewModel(inbox, repository)
        viewModel.begin("request")
        val done = CompletableDeferred<Unit>()

        val result = viewModel.send(
            "request",
            conversation(repository, DIRECT_ID),
            batch(
                text = "Here it is",
                items = listOf(item("one", "image/jpeg"), item("two", "application/pdf")),
            ),
        ) { done.complete(Unit) }
        done.await()

        assertEquals(SharedTextSendStart.STARTED, result)
        // One message: both files in shared order, the words as its caption — never a text send.
        assertEquals(
            listOf(Triple(DIRECT_ID, listOf("image/jpeg", "application/pdf"), "Here it is")),
            repository.sentAlbums,
        )
        assertTrue(repository.sentMedia.isEmpty())
        assertTrue(repository.sentMessages.isEmpty())
        assertEquals(true, inbox.pinnedAlbumDelivery)
        assertEquals(1, viewModel.sendState.value.durablyQueuedComponents)
        assertEquals(listOf(BATCH_ID), inbox.discarded)
        assertTrue(viewModel.sendState.value.sent)
    }

    @Test
    fun `a single-file share folds its words into the one legacy media caption`() = runTest {
        val repository = FakeChatRepository(listOf(chat(DIRECT_ID, "Grace")))
            .apply { albumsAvailable.value = true }
        val inbox = FakeSharedInbox()
        val viewModel = viewModel(inbox, repository)
        viewModel.begin("request")
        val done = CompletableDeferred<Unit>()
        val shared = item("one", "image/jpeg")

        viewModel.send(
            "request",
            conversation(repository, DIRECT_ID),
            batch(text = "Just this", items = listOf(shared)),
        ) { done.complete(Unit) }
        done.await()

        // One `KITMEDIA1` message with the words as its caption: never an album for one file,
        // and no longer a file bubble chased by a separate text bubble.
        assertTrue(repository.sentAlbums.isEmpty())
        assertEquals(
            listOf(
                Triple(
                    DIRECT_ID,
                    "image/jpeg",
                    SharedInboxPolicy.deliveryMessageId(BATCH_ID, DIRECT_ID, shared.id),
                ),
            ),
            repository.sentMedia,
        )
        assertEquals(listOf<String?>("Just this"), repository.mediaCaptionsById.values.toList())
        assertTrue(repository.sentMessages.isEmpty())
        assertEquals(false, inbox.pinnedAlbumDelivery)
        assertEquals(1, viewModel.sendState.value.durablyQueuedComponents)
        assertEquals(listOf(BATCH_ID), inbox.discarded)
        assertTrue(viewModel.sendState.value.sent)
    }

    @Test
    fun `words too large for a caption keep the classic two-message single-file shape`() = runTest {
        val repository = FakeChatRepository(listOf(chat(DIRECT_ID, "Grace")))
        val inbox = FakeSharedInbox()
        val viewModel = viewModel(inbox, repository)
        viewModel.begin("request")
        val done = CompletableDeferred<Unit>()
        // Well inside the shared-text limit, one byte past what a KITMEDIA1 caption may carry.
        val words = "y".repeat(2_049)

        viewModel.send(
            "request",
            conversation(repository, DIRECT_ID),
            batch(text = words, items = listOf(item("one", "image/jpeg"))),
        ) { done.complete(Unit) }
        done.await()

        // Nothing is truncated and nothing fails: the file goes captionless and the words
        // follow whole as their own message, files first so the text reads as commentary.
        assertEquals(listOf("image/jpeg"), repository.sentMedia.map { it.second })
        assertEquals(listOf<String?>(null), repository.mediaCaptionsById.values.toList())
        assertEquals(listOf(DIRECT_ID to words), repository.sentMessages)
        assertEquals(2, viewModel.sendState.value.durablyQueuedComponents)
        assertEquals(listOf(BATCH_ID), inbox.discarded)
        assertTrue(viewModel.sendState.value.sent)
    }

    @Test
    fun `a folded caption retries under the same identity without splitting`() = runTest {
        val repository = FakeChatRepository(listOf(chat(DIRECT_ID, "Grace")))
            .apply { failMediaAttempt = 1 }
        val inbox = FakeSharedInbox()
        val shared = batch(text = "Just this", items = listOf(item("one", "image/jpeg")))
        val viewModel = viewModel(inbox, repository)
        viewModel.begin("request")
        val firstDone = CompletableDeferred<Unit>()

        viewModel.send("request", conversation(repository, DIRECT_ID), shared) {
            firstDone.complete(Unit)
        }
        firstDone.await()

        assertEquals("simulated second component failure", viewModel.sendState.value.error)
        assertTrue(repository.sentMedia.isEmpty())
        assertTrue(inbox.discarded.isEmpty())

        // The retry re-derives the identical fold from the same pinned words. The fake enforces
        // what production enforces — one identity, one caption — so a re-shaped or re-worded
        // replay would fail this send rather than pass silently.
        val retryDone = CompletableDeferred<Unit>()
        viewModel.send("request", conversation(repository, DIRECT_ID), shared) {
            retryDone.complete(Unit)
        }
        retryDone.await()

        assertEquals(listOf("image/jpeg"), repository.sentMedia.map { it.second })
        assertEquals(listOf<String?>("Just this"), repository.mediaCaptionsById.values.toList())
        assertTrue(repository.sentMessages.isEmpty())
        assertEquals(listOf(BATCH_ID), inbox.discarded)
        assertTrue(viewModel.sendState.value.sent)
    }

    @Test
    fun `an album pin outlives the capability and a retry cannot split the message`() = runTest {
        val repository = FakeChatRepository(listOf(chat(DIRECT_ID, "Grace")))
            .apply {
                albumsAvailable.value = true
                albumFailure = "simulated album outage"
            }
        val inbox = FakeSharedInbox()
        val shared = batch(
            text = "Both files",
            items = listOf(item("one", "image/jpeg"), item("two", "application/pdf")),
        )
        val viewModel = viewModel(inbox, repository)
        viewModel.begin("request")
        val firstDone = CompletableDeferred<Unit>()

        viewModel.send("request", conversation(repository, DIRECT_ID), shared) {
            firstDone.complete(Unit)
        }
        firstDone.await()

        assertEquals("simulated album outage", viewModel.sendState.value.error)
        assertEquals(true, inbox.pinnedAlbumDelivery)
        assertTrue(inbox.discarded.isEmpty())

        // The capability reads false on retry — a fresh session, say — but the pinned shape
        // wins: the same content must never re-queue as per-item components.
        repository.albumsAvailable.value = false
        val retryDone = CompletableDeferred<Unit>()
        viewModel.send("request", conversation(repository, DIRECT_ID), shared) {
            retryDone.complete(Unit)
        }
        retryDone.await()

        assertEquals(1, repository.sentAlbums.size)
        assertTrue(repository.sentMedia.isEmpty())
        assertTrue(repository.sentMessages.isEmpty())
        assertEquals(listOf(BATCH_ID), inbox.discarded)
        assertTrue(viewModel.sendState.value.sent)
    }

    @Test
    fun `a share pinned per-item never becomes an album when the capability appears`() = runTest {
        val repository = FakeChatRepository(listOf(chat(DIRECT_ID, "Grace")))
            .apply { failMediaAttempt = 2 }
        val inbox = FakeSharedInbox()
        val shared = batch(
            text = "Both files",
            items = listOf(item("one", "image/jpeg"), item("two", "application/pdf")),
        )
        val viewModel = viewModel(inbox, repository)
        viewModel.begin("request")
        val firstDone = CompletableDeferred<Unit>()

        viewModel.send("request", conversation(repository, DIRECT_ID), shared) {
            firstDone.complete(Unit)
        }
        firstDone.await()

        assertEquals(false, inbox.pinnedAlbumDelivery)
        assertEquals(1, repository.sentMedia.size)

        // The first component is already durably queued per-item. An album appearing now would
        // duplicate that file inside a second message, so the recorded shape must win.
        repository.albumsAvailable.value = true
        val retryDone = CompletableDeferred<Unit>()
        viewModel.send("request", conversation(repository, DIRECT_ID), shared) {
            retryDone.complete(Unit)
        }
        retryDone.await()

        assertTrue(repository.sentAlbums.isEmpty())
        assertEquals(2, repository.sentMedia.size)
        assertEquals(listOf(DIRECT_ID to "Both files"), repository.sentMessages)
        assertEquals(listOf(BATCH_ID), inbox.discarded)
        assertTrue(viewModel.sendState.value.sent)
    }

    @Test
    fun `an album whose caption cannot be carried fails whole and keeps the share`() = runTest {
        val repository = FakeChatRepository(listOf(chat(DIRECT_ID, "Grace")))
            .apply {
                albumsAvailable.value = true
                albumFailure = "This caption is too long to send with these attachments"
            }
        val inbox = FakeSharedInbox()
        val viewModel = viewModel(inbox, repository)
        viewModel.begin("request")
        val done = CompletableDeferred<Unit>()

        viewModel.send(
            "request",
            conversation(repository, DIRECT_ID),
            batch(
                text = "y".repeat(4_000),
                items = listOf(item("one", "image/jpeg"), item("two", "application/pdf")),
            ),
        ) { done.complete(Unit) }
        done.await()

        // Nothing was truncated or sent piecemeal instead: the whole review fails visibly and
        // the staged share survives for the user to shorten or resend.
        assertEquals(
            "This caption is too long to send with these attachments",
            viewModel.sendState.value.error,
        )
        assertTrue(!viewModel.sendState.value.sent)
        assertTrue(repository.sentAlbums.isEmpty())
        assertTrue(repository.sentMedia.isEmpty())
        assertTrue(repository.sentMessages.isEmpty())
        assertTrue(inbox.discarded.isEmpty())
    }

    @Test
    fun `a share from another authenticated session cannot enter the outbox`() = runTest {
        val original = testSession(OWNER_ID)
        val sessions = MutableTestSessionStore(original)
        val repository = FakeChatRepository(listOf(chat(DIRECT_ID, "Grace")))
        val viewModel = viewModel(FakeSharedInbox(), repository, sessions = sessions)
        sessions.save(testSession("10000000-0000-4000-8000-000000000099"))
        viewModel.begin("request")

        val result = viewModel.send(
            "request",
            conversation(repository, DIRECT_ID),
            batch(text = "private", owner = SharedInboxOwner.from(original.fence())),
        ) {}

        assertEquals(SharedTextSendStart.REJECTED, result)
        assertTrue(repository.sentMessages.isEmpty())
    }

    @Test
    fun `a new login for the same account cannot claim an older session share`() = runTest {
        val original = testSession(OWNER_ID, sessionId = "session-old", cacheScopeId = "scope-old")
        val sessions = MutableTestSessionStore(original)
        val repository = FakeChatRepository(listOf(chat(DIRECT_ID, "Grace")))
        val viewModel = viewModel(FakeSharedInbox(), repository, sessions = sessions)
        sessions.save(testSession(OWNER_ID, sessionId = "session-new", cacheScopeId = "scope-new"))
        viewModel.begin("request")

        val result = viewModel.send(
            "request",
            conversation(repository, DIRECT_ID),
            batch(text = "private", owner = SharedInboxOwner.from(original.fence())),
        ) {}

        assertEquals(SharedTextSendStart.REJECTED, result)
        assertTrue(repository.sentMessages.isEmpty())
        assertTrue(repository.sentMedia.isEmpty())
    }

    private fun batch(
        text: String? = null,
        items: List<SharedInboxItem> = emptyList(),
        owner: SharedInboxOwner = SharedInboxOwner.from(testSession(OWNER_ID).fence()),
    ) = SharedInboxBatch(
        id = BATCH_ID,
        receivedAtMillis = 1_700_000_000_000L,
        items = items,
        text = text,
        owner = owner,
    )

    private fun item(id: String, mediaType: String): SharedInboxItem {
        val canonicalId = UUID.nameUUIDFromBytes(id.toByteArray()).toString()
        return SharedInboxItem(
            id = canonicalId,
            fileName = canonicalId,
            mediaType = mediaType,
            displayName = id,
            byteCount = 4,
        )
    }

    private fun chat(
        id: String,
        name: String,
        isGroup: Boolean = false,
        peerUserId: String? = null,
    ) = ChatPreview(
        id = id,
        name = name,
        lastMessage = "",
        time = "",
        peerUserId = peerUserId,
        isGroup = isGroup,
    )

    private fun contact(
        id: String,
        name: String,
        isKitUser: Boolean = true,
        registeredName: String? = null,
    ) = Contact(
        id = id,
        name = name,
        phone = "+256700000000",
        isKitUser = isKitUser,
        registeredName = registeredName,
    )

    private fun viewModel(
        inbox: FakeSharedInbox,
        repository: FakeChatRepository,
        contacts: List<Contact> = emptyList(),
        sessions: MutableTestSessionStore = MutableTestSessionStore(testSession(OWNER_ID)),
    ) = SharedTextShareViewModel(
        inbox,
        repository,
        FakeContactRepository(contacts),
        sessions,
    )

    private fun conversation(repository: FakeChatRepository, id: String) =
        SharedRecipient.Conversation(checkNotNull(repository.chat(id)))

    private class FakeSharedInbox(private val readable: Boolean = true) : SharedInboxAccess {
        val discarded = mutableListOf<String>()
        var pinnedConversationId: String? = null
        var pinnedAlbumDelivery: Boolean? = null

        override fun source(batch: SharedInboxBatch, item: SharedInboxItem): SecureMediaSource {
            require(readable) { "That shared file could no longer be read." }
            return SecureMediaSource.ofBytes(ByteArray(item.byteCount) { 7 })
        }

        override fun pinDestination(
            batch: SharedInboxBatch,
            conversationId: String,
            albumDelivery: Boolean,
        ): SharedInboxBatch {
            check(pinnedConversationId == null || pinnedConversationId == conversationId)
            // Mirrors the durable store: the first pin records the shape, later pins keep it.
            val shape = if (pinnedConversationId == null) albumDelivery else pinnedAlbumDelivery
            pinnedConversationId = conversationId
            pinnedAlbumDelivery = shape
            return batch.copy(pinnedConversationId = conversationId, albumDelivery = shape)
        }

        override fun discard(batch: SharedInboxBatch) {
            discarded += batch.id
        }
    }

    private class FakeChatRepository(
        initialChats: List<ChatPreview>,
        ready: Boolean = true,
        localHistoryReady: Boolean = ready,
    ) : ChatRepository {
        override val readiness: StateFlow<Boolean> = MutableStateFlow(ready)
        override val localHistoryReady: StateFlow<Boolean> = MutableStateFlow(localHistoryReady)
        override val chats: StateFlow<List<ChatPreview>> = MutableStateFlow(initialChats)
        val albumsAvailable = MutableStateFlow(false)
        override val mediaAlbumsAvailable: StateFlow<Boolean> get() = albumsAvailable
        val sentMessages = mutableListOf<Pair<String, String>>()
        val sentMedia = mutableListOf<Triple<String, String, String>>()
        /** chatId, ordered media types, caption — one entry per distinct queued album. */
        val sentAlbums = mutableListOf<Triple<String, List<String>, String?>>()
        /** The caption each distinct media component was first queued under, by identity. */
        val mediaCaptionsById = linkedMapOf<String, String?>()
        val openedContacts = mutableListOf<Contact>()
        private val mediaById = linkedMapOf<String, Triple<String, String, String>>()
        private val textById = linkedMapOf<String, Triple<String, String, String>>()
        private val albumById = linkedMapOf<String, Triple<String, List<String>, String?>>()
        var failMediaAttempt: Int? = null
        var albumFailure: String? = null
        private var mediaAttempt = 0

        override suspend fun sendMediaMessage(
            chatId: String,
            source: SecureMediaSource,
            mediaType: String,
            caption: String?,
            replyToMessageId: String?,
        ) {
            sentMedia += Triple(chatId, mediaType, "legacy-${sentMedia.size}")
        }

        override suspend fun sendIdempotentMediaMessageForOwner(
            owner: SessionFence,
            chatId: String,
            source: SecureMediaSource,
            mediaType: String,
            clientMessageId: String,
            caption: String?,
        ) {
            mediaAttempt++
            if (failMediaAttempt == mediaAttempt) {
                failMediaAttempt = null
                error("simulated second component failure")
            }
            val record = Triple(chatId, mediaType, clientMessageId)
            val previous = mediaById.putIfAbsent(clientMessageId, record)
            check(previous == null || previous == record)
            // Production folds the caption into the idempotent identity: a replay arriving with
            // different words is the same fault as a replay arriving with different bytes.
            if (mediaCaptionsById.containsKey(clientMessageId)) {
                check(mediaCaptionsById[clientMessageId] == caption) {
                    "An immediate-send identity belongs to different media"
                }
            } else {
                mediaCaptionsById[clientMessageId] = caption
            }
            if (previous == null) sentMedia += record
        }

        override suspend fun sendIdempotentMediaAlbumMessageForOwner(
            owner: SessionFence,
            chatId: String,
            attachments: List<SecureMediaAlbumSource>,
            clientMessageId: String,
            caption: String?,
        ) {
            albumFailure?.let { failure ->
                albumFailure = null
                throw IllegalArgumentException(failure)
            }
            val record = Triple(chatId, attachments.map(SecureMediaAlbumSource::mediaType), caption)
            val previous = albumById.putIfAbsent(clientMessageId, record)
            check(previous == null || previous == record)
            if (previous == null) sentAlbums += record
        }

        override suspend fun sendIdempotentMessageForOwner(
            owner: SessionFence,
            chatId: String,
            text: String,
            clientMessageId: String,
        ) {
            val record = Triple(chatId, text, clientMessageId)
            val previous = textById.putIfAbsent(clientMessageId, record)
            check(previous == null || previous == record)
            if (previous == null) sentMessages += chatId to text
        }

        override fun chat(chatId: String): ChatPreview? = chats.value.firstOrNull { it.id == chatId }

        override fun conversation(chatId: String): StateFlow<List<Message>> =
            MutableStateFlow(emptyList())

        override suspend fun openDirectConversation(contact: Contact): String {
            openedContacts += contact
            return OPENED_DIRECT_ID
        }

        override suspend fun sendMessage(
            chatId: String,
            text: String,
            replyToMessageId: String?,
            onDurablyCommitted: (clientMessageId: String) -> Unit,
        ) {
            sentMessages += chatId to text
            onDurablyCommitted("test-client-${sentMessages.size}")
        }
    }

    private class FakeContactRepository(initialContacts: List<Contact>) : ContactRepository {
        override val contacts: StateFlow<List<Contact>> = MutableStateFlow(initialContacts)

        override suspend fun refresh() = Unit

        override suspend fun syncDeviceContacts() = Unit
    }

    private companion object {
        const val OWNER_ID = "10000000-0000-4000-8000-000000000001"
        const val BATCH_ID = "20000000-0000-4000-8000-000000000001"
        const val DIRECT_ID = "30000000-0000-4000-8000-000000000001"
        const val GROUP_ID = "30000000-0000-4000-8000-000000000002"
        const val EXISTING_DIRECT_ID = "30000000-0000-4000-8000-000000000003"
        const val OPENED_DIRECT_ID = "30000000-0000-4000-8000-000000000004"
    }
}
