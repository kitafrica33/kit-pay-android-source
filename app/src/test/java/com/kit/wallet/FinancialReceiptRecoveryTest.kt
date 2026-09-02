package com.kit.wallet

import com.kit.wallet.data.mapper.DecimalMoney
import com.kit.wallet.data.messaging.FinancialCreationIntent
import com.kit.wallet.data.messaging.FinancialCreationReceipt
import com.kit.wallet.data.messaging.FinancialCreationReceiptCodec
import com.kit.wallet.data.messaging.FinancialCreationReceiptCoordinator
import com.kit.wallet.data.messaging.FinancialCreationReceiptPhase
import com.kit.wallet.data.messaging.FinancialCreationRecoveryOutcome
import com.kit.wallet.data.messaging.FinancialCreationReceiptStore
import com.kit.wallet.data.messaging.GroupPaymentAudience
import com.kit.wallet.data.messaging.GroupPaymentSplitMode
import com.kit.wallet.data.messaging.KitGroupPaymentAction
import com.kit.wallet.data.messaging.KitGroupPaymentMessage
import com.kit.wallet.data.messaging.KitPaymentAction
import com.kit.wallet.data.messaging.KitPaymentMessage
import com.kit.wallet.data.messaging.WalletTransferChatReceipt
import com.kit.wallet.data.messaging.WalletTransferChatReceiptCodec
import com.kit.wallet.data.messaging.WalletTransferChatReceiptCoordinator
import com.kit.wallet.data.messaging.WalletTransferChatReceiptStore
import com.kit.wallet.data.messaging.WalletTransferReceiptPhase
import com.kit.wallet.data.messaging.WalletTransferReceiptRecoveryOutcome
import com.kit.wallet.data.messaging.matchesSettledTransfer
import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.CreateGroupPaymentRecipient
import com.kit.wallet.data.remote.CreateGroupPaymentRequest
import com.kit.wallet.data.remote.CurrencyDto
import com.kit.wallet.data.remote.KitGroupPaymentRequestAction
import com.kit.wallet.data.remote.KitGroupPaymentRequestMessage
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.PaymentRequestDto
import com.kit.wallet.data.repository.ChatRepository
import com.kit.wallet.data.repository.SentTransfer
import com.kit.wallet.data.repository.WalletRepository
import com.kit.wallet.data.repository.WalletTransferRecoveryResult
import com.kit.wallet.data.repository.WalletTransferSubmission
import com.kit.wallet.data.repository.WalletSpendingSource
import com.kit.wallet.data.session.SessionInvalidatedException
import com.kit.wallet.ui.model.Contact
import com.kit.wallet.ui.model.GroupPaymentRecipient
import com.kit.wallet.ui.model.GroupPaymentShareStatus
import com.kit.wallet.ui.model.GroupPaymentSummary
import com.kit.wallet.ui.model.ChatPreview
import com.kit.wallet.ui.model.Transaction
import com.kit.wallet.ui.model.TxStatus
import com.kit.wallet.ui.model.TxType
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.IOException
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FinancialReceiptRecoveryTest {
    private val disk = TestSecureMessagingStateStore()
    private val sessions = MutableTestSessionStore(testSession(OWNER_A))
    private val clock = MutableClock(NOW)

    @Test
    fun `wallet receipt codec preserves every crash boundary and rejects trailing bytes`() {
        val prepared = WalletTransferChatReceipt(
            id = OPERATION_ONE,
            recipientUserId = USER_ONE,
            amountMinor = 2_500,
            createdAtEpochMillis = NOW.toEpochMilli(),
            phase = WalletTransferReceiptPhase.PREPARED,
        )
        val submitted = prepared.copy(
            phase = WalletTransferReceiptPhase.SUBMITTED,
            submission = transferSubmission(),
        )
        val event = KitPaymentMessage(
            KitPaymentAction.SENT,
            TRANSACTION_ID,
            2_500,
            "UGX",
            2,
            "Lunch",
        )
        val settled = submitted.copy(
            phase = WalletTransferReceiptPhase.SETTLED,
            descriptor = event.encode(),
            clientMessageId = event.deterministicMessageId(),
        )

        listOf(prepared, submitted, settled).forEach { receipt ->
            val encoded = WalletTransferChatReceiptCodec.encode(receipt)
            assertEquals(receipt, WalletTransferChatReceiptCodec.decode(encoded))
            assertNull(WalletTransferChatReceiptCodec.decode(encoded + byteArrayOf(1)))
            encoded.fill(0)
        }
    }

    @Test
    fun `wallet receipt binds recovered source recipient currency amount and note`() {
        val submitted = WalletTransferChatReceipt(
            id = OPERATION_ONE,
            recipientUserId = USER_ONE,
            amountMinor = 2_500,
            createdAtEpochMillis = NOW.toEpochMilli(),
            phase = WalletTransferReceiptPhase.SUBMITTED,
            submission = transferSubmission(),
        )
        val exact = sentTransfer()

        assertTrue(submitted.matchesSettledTransfer(exact))
        assertTrue(
            submitted.matchesSettledTransfer(
                exact.copy(transaction = exact.transaction.copy(counterpartyUserId = null)),
            ),
        )
        listOf(
            exact.copy(transaction = exact.transaction.copy(walletId = WALLET_THREE)),
            exact.copy(transaction = exact.transaction.copy(counterpartyUserId = USER_TWO)),
            exact.copy(transaction = exact.transaction.copy(currencyCode = "KES")),
            exact.copy(transaction = exact.transaction.copy(currencyScale = 0)),
            exact.copy(transaction = exact.transaction.copy(amountMinor = -2_499)),
            exact.copy(transaction = exact.transaction.copy(note = "Changed")),
        ).forEach { changed ->
            assertFalse(submitted.matchesSettledTransfer(changed))
        }
    }

    @Test
    fun `wallet journal survives restart and only exact settled handoff removes it`() = runTest {
        val owner = checkNotNull(sessions.current()).fence()
        val first = WalletTransferChatReceiptStore(disk, sessions, clock)
        val prepared = first.prepareForOwner(owner, USER_ONE, transferSubmission(), OPERATION_ONE)
        val submitted = first.markSubmittedForOwner(owner, prepared.id, transferSubmission())
        val event = KitPaymentMessage(
            KitPaymentAction.SENT,
            TRANSACTION_ID,
            2_500,
            "UGX",
            2,
            "Lunch",
        )
        val settled = first.bindSettledForOwner(
            owner,
            submitted.id,
            event.encode(),
            event.deterministicMessageId(),
        )

        val restarted = WalletTransferChatReceiptStore(disk, sessions, clock)
        assertEquals(listOf(settled), restarted.snapshotForOwner(owner))
        restarted.completeForOwner(owner, settled.id, event.deterministicMessageId())
        assertTrue(WalletTransferChatReceiptStore(disk, sessions, clock)
            .snapshotForOwner(owner).isEmpty())
    }

    @Test
    fun `wallet journal expires prepared records but never age prunes submitted records`() = runTest {
        val owner = checkNotNull(sessions.current()).fence()
        val store = WalletTransferChatReceiptStore(disk, sessions, clock)
        store.prepareForOwner(owner, USER_ONE, transferSubmission(), OPERATION_ONE)
        val retained = store.prepareForOwner(
            owner,
            USER_TWO,
            transferSubmission(
                destinationWalletId = WALLET_THREE,
                amountMinor = 3_000,
                amount = "30.00",
                note = null,
            ),
            OPERATION_TWO,
        )
        store.markSubmittedForOwner(
            owner,
            retained.id,
            transferSubmission(
                destinationWalletId = WALLET_THREE,
                amountMinor = 3_000,
                amount = "30.00",
                note = null,
            ),
        )

        clock.advance(WalletTransferChatReceiptStore.PREPARED_RETENTION_MILLIS + 1L)
        val batch = store.recoveryBatchForCurrentOwner()

        assertEquals(listOf(OPERATION_TWO), batch?.receipts?.map { it.id })
        assertEquals(
            listOf(OPERATION_TWO),
            WalletTransferChatReceiptStore(disk, sessions, clock)
                .snapshotForOwner(owner)
                .map { it.id },
        )
    }

    @Test
    fun `wallet retry identity is reused only for the exact submitted intent`() = runTest {
        val owner = checkNotNull(sessions.current()).fence()
        val store = WalletTransferChatReceiptStore(disk, sessions, clock)
        val exact = transferSubmission()
        val submitted = store.prepareForOwner(owner, USER_ONE, exact, OPERATION_ONE).let {
            store.markSubmittedForOwner(owner, it.id, exact)
        }

        assertEquals(
            submitted.id,
            store.prepareForOwner(owner, USER_ONE, exact, OPERATION_TWO).id,
        )
        assertEquals(
            OPERATION_TWO,
            store.prepareForOwner(
                owner,
                USER_ONE,
                exact.copy(note = "Dinner"),
                OPERATION_TWO,
            ).id,
        )
        assertEquals(
            OPERATION_THREE,
            store.prepareForOwner(
                owner,
                USER_ONE,
                exact.copy(sourceWalletId = WALLET_THREE),
                OPERATION_THREE,
            ).id,
        )
        assertEquals(
            OPERATION_FOUR,
            store.prepareForOwner(
                owner,
                USER_ONE,
                exact.copy(destinationWalletId = WALLET_THREE),
                OPERATION_FOUR,
            ).id,
        )
    }

    @Test
    fun `timeout then immediate wallet retry converges on one financial operation`() = runTest {
        val owner = checkNotNull(sessions.current()).fence()
        val store = WalletTransferChatReceiptStore(disk, sessions, clock)
        val attemptedKeys = mutableListOf<String>()
        val financialOperations = mutableMapOf<String, SentTransfer>()
        var attempts = 0
        val wallet = proxy<WalletRepository> { method, args ->
            when (method) {
                "spendingSourceForOwner" -> WalletSpendingSource(
                    walletId = WALLET_ONE,
                    currencyCode = "UGX",
                    currencyScale = 2,
                    availableBalanceMinor = 100_000,
                )
                "sendToContactForOwner" -> {
                    attempts++
                    val idempotencyKey = args[5] as String
                    attemptedKeys += idempotencyKey
                    @Suppress("UNCHECKED_CAST")
                    val onSubmitting = args[6] as suspend (WalletTransferSubmission) -> Unit
                    runBlocking { onSubmitting(transferSubmission()) }
                    val sent = financialOperations.getOrPut(idempotencyKey, ::sentTransfer)
                    if (attempts == 1) throw IOException("Injected response timeout")
                    @Suppress("UNCHECKED_CAST")
                    val onSettled = args[7] as suspend (SentTransfer) -> Unit
                    runBlocking { onSettled(sent) }
                    sent
                }
                else -> error("Unexpected wallet call: $method")
            }
        }
        var handoffs = 0
        val chats = proxy<ChatRepository> { method, _ ->
            when (method) {
                "getChats" -> MutableStateFlow(
                    listOf(
                        ChatPreview(
                            id = CONVERSATION_ID,
                            name = "Ama",
                            lastMessage = "",
                            time = "",
                            peerUserId = USER_ONE,
                        ),
                    ),
                )
                "capturePaymentEventForOwner" -> {
                    handoffs++
                    Unit
                }
                else -> error("Unexpected chat call: $method")
            }
        }
        val coordinator = WalletTransferChatReceiptCoordinator(store, sessions, wallet, chats)
        val recipient = Contact(
            id = USER_ONE,
            name = "Ama",
            phone = "+256700000001",
            receivingWalletId = WALLET_TWO,
        )

        val firstFailure = runCatching {
            coordinator.send(recipient, 2_500, "Lunch", "1234")
        }.exceptionOrNull()
        assertTrue(firstFailure is IOException || firstFailure?.cause is IOException)
        assertEquals(
            WalletTransferReceiptPhase.SUBMITTED,
            store.snapshotForOwner(owner).single().phase,
        )

        assertEquals(sentTransfer(), coordinator.send(recipient, 2_500, "Lunch", "1234"))
        assertEquals(2, attemptedKeys.size)
        assertEquals(1, attemptedKeys.toSet().size)
        assertEquals(1, financialOperations.size)
        assertEquals(1, handoffs)
        assertTrue(store.snapshotForOwner(owner).isEmpty())
    }

    @Test
    fun `wallet recovery never replays a mutation and only exact proof retires submitted work`() =
        runTest {
            val owner = checkNotNull(sessions.current()).fence()
            val store = WalletTransferChatReceiptStore(disk, sessions, clock)
            val submitted = store.prepareForOwner(
                owner,
                USER_ONE,
                transferSubmission(),
                OPERATION_ONE,
            ).let {
                store.markSubmittedForOwner(owner, it.id, transferSubmission())
            }
            val calls = mutableListOf<String>()
            var recovery: WalletTransferRecoveryResult = WalletTransferRecoveryResult.InProgress
            val wallet = proxy<WalletRepository> { method, _ ->
                calls += method
                when (method) {
                    "recoverSentTransferForOwner" -> recovery
                    else -> error("Unexpected wallet call: $method")
                }
            }
            val chats = proxy<ChatRepository> { method, _ ->
                when (method) {
                    "getChats" -> MutableStateFlow(emptyList<ChatPreview>())
                    else -> error("Unexpected chat call: $method")
                }
            }
            val coordinator = WalletTransferChatReceiptCoordinator(
                store,
                sessions,
                wallet,
                chats,
            )

            assertEquals(WalletTransferReceiptRecoveryOutcome.RETRY, coordinator.recover())
            assertEquals(listOf("recoverSentTransferForOwner"), calls)
            assertEquals(listOf(submitted), store.snapshotForOwner(owner))

            recovery = WalletTransferRecoveryResult.NotCommitted
            assertEquals(WalletTransferReceiptRecoveryOutcome.COMMITTED, coordinator.recover())
            assertEquals(
                listOf("recoverSentTransferForOwner", "recoverSentTransferForOwner"),
                calls,
            )
            assertTrue(store.snapshotForOwner(owner).isEmpty())
        }

    @Test
    fun `settled wallet recovery retries one deterministic outbox handoff without another API read`() =
        runTest {
            val owner = checkNotNull(sessions.current()).fence()
            val store = WalletTransferChatReceiptStore(disk, sessions, clock)
            store.prepareForOwner(owner, USER_ONE, transferSubmission(), OPERATION_ONE).also {
                store.markSubmittedForOwner(owner, it.id, transferSubmission())
            }
            var recoveryReads = 0
            val wallet = proxy<WalletRepository> { method, _ ->
                when (method) {
                    "recoverSentTransferForOwner" -> {
                        recoveryReads++
                        WalletTransferRecoveryResult.Settled(sentTransfer())
                    }
                    else -> error("Unexpected wallet call: $method")
                }
            }
            val handoffs = mutableListOf<Pair<String, String>>()
            var failFirstHandoff = true
            val chats = proxy<ChatRepository> { method, args ->
                when (method) {
                    "getChats" -> MutableStateFlow(
                        listOf(
                            ChatPreview(
                                id = CONVERSATION_ID,
                                name = "Ama",
                                lastMessage = "",
                                time = "",
                                peerUserId = USER_ONE,
                            ),
                        ),
                    )
                    "capturePaymentEventForOwner" -> {
                        handoffs += args[2] as String to (args[3] as String)
                        if (failFirstHandoff) {
                            failFirstHandoff = false
                            error("Injected handoff interruption")
                        }
                        Unit
                    }
                    else -> error("Unexpected chat call: $method")
                }
            }
            val coordinator = WalletTransferChatReceiptCoordinator(
                store,
                sessions,
                wallet,
                chats,
            )

            assertEquals(WalletTransferReceiptRecoveryOutcome.RETRY, coordinator.recover())
            assertEquals(WalletTransferReceiptPhase.SETTLED, store.snapshotForOwner(owner).single().phase)
            assertEquals(WalletTransferReceiptRecoveryOutcome.COMMITTED, coordinator.recover())
            assertEquals(1, recoveryReads)
            assertEquals(2, handoffs.size)
            assertEquals(handoffs.first(), handoffs.last())
            assertTrue(store.snapshotForOwner(owner).isEmpty())
        }

    @Test
    fun `financial creation codec round trips every result unknown mutation`() {
        val payment = paymentReceipt()
        val groupRequest = CreateGroupPaymentRequest(
            sourceWalletId = WALLET_ONE,
            splitMode = "custom",
            audience = "selected",
            note = "Dinner",
            recipients = listOf(
                CreateGroupPaymentRecipient(USER_ONE, "10.00"),
                CreateGroupPaymentRecipient(USER_TWO, "20.00"),
            ),
        )
        val groupEvent = checkNotNull(
            KitGroupPaymentMessage.create(
                action = KitGroupPaymentAction.SENT,
                groupPaymentId = GROUP_PAYMENT_ID,
                splitMode = GroupPaymentSplitMode.CUSTOM,
                audience = GroupPaymentAudience.SELECTED,
                recipientCount = 2,
                currencyCode = "UGX",
                currencyScale = 2,
                note = "Dinner",
                recipientUserIds = listOf(USER_ONE, USER_TWO),
            ),
        )
        val group = FinancialCreationReceipt(
            id = OPERATION_TWO,
            conversationId = CONVERSATION_ID,
            idempotencyKey = "group-payment:$OPERATION_TWO",
            createdAtEpochMillis = NOW.toEpochMilli(),
            phase = FinancialCreationReceiptPhase.SETTLED,
            intent = FinancialCreationIntent.GroupPayment(groupRequest),
            descriptor = groupEvent.encode(),
            clientMessageId = groupEvent.announcementMessageId(),
        )
        val contributionEvent = checkNotNull(
            KitGroupPaymentRequestMessage.create(
                KitGroupPaymentRequestAction.CONTRIBUTED,
                GROUP_REQUEST_ID,
                CONTRIBUTION_ID,
                1_500,
            ),
        )
        val contribution = FinancialCreationReceipt(
            id = OPERATION_THREE,
            conversationId = CONVERSATION_ID,
            idempotencyKey = "group-contribution:$OPERATION_THREE",
            createdAtEpochMillis = NOW.toEpochMilli(),
            phase = FinancialCreationReceiptPhase.SETTLED,
            intent = FinancialCreationIntent.GroupRequestContribution(
                GROUP_REQUEST_ID,
                WALLET_ONE,
                "15.00",
                1_500,
            ),
            descriptor = contributionEvent.encode(),
            clientMessageId = contributionEvent.deterministicMessageId(),
        )

        listOf(payment, group, contribution).forEach { receipt ->
            val encoded = FinancialCreationReceiptCodec.encode(receipt)
            assertEquals(receipt, FinancialCreationReceiptCodec.decode(encoded))
            assertNull(FinancialCreationReceiptCodec.decode(encoded + byteArrayOf(1)))
            encoded.fill(0)
        }
    }

    @Test
    fun `creation journal skips prepared work and retains submitted work beyond one day`() = runTest {
        val owner = checkNotNull(sessions.current()).fence()
        val store = FinancialCreationReceiptStore(disk, sessions, clock)
        store.prepareForOwner(
            owner,
            CONVERSATION_ID,
            "android-chat-request:$OPERATION_ONE",
            paymentIntent(USER_ONE),
        )
        val submitted = store.prepareForOwner(
            owner,
            CONVERSATION_TWO,
            "android-chat-request:$OPERATION_TWO",
            paymentIntent(USER_TWO),
        )
        store.markSubmittedForOwner(owner, submitted.id)

        assertEquals(listOf(submitted.id), store.recoveryBatchForCurrentOwner()?.receipts?.map { it.id })
        clock.advance(FinancialCreationReceiptStore.PREPARED_RETENTION_MILLIS + 1L)
        assertEquals(listOf(submitted.id), store.recoveryBatchForCurrentOwner()?.receipts?.map { it.id })
        assertEquals(
            listOf(submitted.id),
            FinancialCreationReceiptStore(disk, sessions, clock)
                .snapshotForOwner(owner)
                .map { it.id },
        )
    }

    @Test
    fun `receipt stores reject an obsolete owner after account replacement`() = runTest {
        val oldOwner = checkNotNull(sessions.current()).fence()
        val walletStore = WalletTransferChatReceiptStore(disk, sessions, clock)
        walletStore.prepareForOwner(oldOwner, USER_ONE, transferSubmission(), OPERATION_ONE)
        disk.eraseAll()
        sessions.save(testSession(OWNER_B))

        assertTrue(
            runCatching {
                walletStore.markSubmittedForOwner(oldOwner, OPERATION_ONE, transferSubmission())
            }.exceptionOrNull() is SessionInvalidatedException,
        )
        val newOwner = checkNotNull(sessions.current()).fence()
        assertEquals(
            OPERATION_TWO,
            walletStore.prepareForOwner(
                newOwner,
                USER_TWO,
                transferSubmission(
                    destinationWalletId = WALLET_THREE,
                    amountMinor = 3_000,
                    amount = "30.00",
                    note = null,
                ),
                OPERATION_TWO,
            ).id,
        )
    }

    @Test
    fun `creation binding rejects changed note roster and custom amounts`() = runTest {
        val owner = checkNotNull(sessions.current()).fence()
        val store = FinancialCreationReceiptStore(disk, sessions, clock)
        val coordinator = FinancialCreationReceiptCoordinator(
            store,
            sessions,
            noCallsProxy(),
            ApiCallExecutor(Moshi.Builder().add(KotlinJsonAdapterFactory()).build()),
            noCallsProxy(),
        )
        val request = CreateGroupPaymentRequest(
            sourceWalletId = WALLET_ONE,
            splitMode = "custom",
            audience = "selected",
            note = "Dinner",
            recipients = listOf(
                CreateGroupPaymentRecipient(USER_ONE, "10.00"),
                CreateGroupPaymentRecipient(USER_TWO, "20.00"),
            ),
        )
        val receipt = coordinator.prepareGroupPayment(
            owner,
            CONVERSATION_ID,
            "group-payment:$OPERATION_ONE",
            request,
        )
        coordinator.markSubmitted(owner, receipt.id)
        val exact = GroupPaymentSummary(
            id = GROUP_PAYMENT_ID,
            conversationId = CONVERSATION_ID,
            splitMode = "custom",
            audience = "selected",
            currencyCode = "UGX",
            currencyScale = 2,
            recipientCount = 2,
            totalAmountMinor = 3_000,
            note = "Dinner",
            recipients = listOf(
                GroupPaymentRecipient(USER_ONE, "Ama", GroupPaymentShareStatus.PENDING, 1_000),
                GroupPaymentRecipient(USER_TWO, "Ben", GroupPaymentShareStatus.PENDING, 2_000),
            ),
        )

        assertTrue(
            runCatching {
                coordinator.bindGroupPayment(
                    owner,
                    receipt,
                    exact.copy(recipients = exact.recipients.mapIndexed { index, recipient ->
                        if (index == 0) recipient.copy(amountMinor = 999) else recipient
                    }),
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                coordinator.bindGroupPayment(owner, receipt, exact.copy(note = "Changed"))
            }.isFailure,
        )
        val settled = coordinator.bindGroupPayment(owner, receipt, exact)
        assertEquals(FinancialCreationReceiptPhase.SETTLED, settled.phase)

        val payment = coordinator.preparePaymentRequest(
            owner,
            CONVERSATION_TWO,
            WALLET_TWO,
            USER_ONE,
            2_500,
            "UGX",
            2,
            "Original note",
            "android-chat-request:$OPERATION_THREE",
        )
        coordinator.markSubmitted(owner, payment.id)
        val response = PaymentRequestDto(
            id = PAYMENT_REQUEST_ID,
            type = "payment_request",
            status = "pending",
            destinationWalletId = WALLET_TWO,
            requestedFromUserId = USER_ONE,
            amount = "25.00",
            currency = CurrencyDto("UGX", "2"),
            note = "Changed note",
        )
        assertTrue(
            runCatching { coordinator.bindPaymentRequest(owner, payment, response) }.isFailure,
        )
    }

    @Test
    fun `pre post wake cannot retire the foreground creation before submission starts`() = runTest {
        val owner = checkNotNull(sessions.current()).fence()
        val store = FinancialCreationReceiptStore(disk, sessions, clock)
        val apiCalls = mutableListOf<String>()
        val coordinator = FinancialCreationReceiptCoordinator(
            store,
            sessions,
            proxy<KitWalletApi> { method, _ ->
                apiCalls += method
                error("Unexpected API call: $method")
            },
            ApiCallExecutor(Moshi.Builder().add(KotlinJsonAdapterFactory()).build()),
            noCallsProxy(),
        )
        val receipt = coordinator.preparePaymentRequest(
            owner,
            CONVERSATION_ID,
            WALLET_TWO,
            USER_ONE,
            2_500,
            "UGX",
            2,
            "Lunch",
            "android-chat-request:$OPERATION_ONE",
        )
        coordinator.markSubmitted(owner, receipt.id)

        assertEquals(FinancialCreationRecoveryOutcome.RETRY, coordinator.recover())
        assertTrue(apiCalls.isEmpty())
        assertEquals(listOf(receipt.id), store.snapshotForOwner(owner).map { it.id })

        coordinator.discardNotSubmitted(owner, receipt.id)
        assertTrue(store.snapshotForOwner(owner).isEmpty())
    }

    @Test
    fun `deterministic financial event ids are stable and action scoped`() {
        val request = KitPaymentMessage(
            KitPaymentAction.REQUEST,
            PAYMENT_REQUEST_ID,
            2_500,
            "UGX",
            2,
            "Lunch",
        )
        val changedPresentation = request.copy(note = "Updated display copy")

        assertEquals(request.deterministicMessageId(), changedPresentation.deterministicMessageId())
        assertNotEquals(
            request.deterministicMessageId(),
            request.copy(action = KitPaymentAction.CANCELLED).deterministicMessageId(),
        )
    }

    private fun paymentReceipt(): FinancialCreationReceipt {
        val event = KitPaymentMessage(
            KitPaymentAction.REQUEST,
            PAYMENT_REQUEST_ID,
            2_500,
            "UGX",
            2,
            "Lunch",
        )
        return FinancialCreationReceipt(
            id = OPERATION_ONE,
            conversationId = CONVERSATION_ID,
            idempotencyKey = "android-chat-request:$OPERATION_ONE",
            createdAtEpochMillis = NOW.toEpochMilli(),
            phase = FinancialCreationReceiptPhase.SETTLED,
            intent = paymentIntent(USER_ONE),
            descriptor = event.encode(),
            clientMessageId = event.deterministicMessageId(),
        )
    }

    private fun paymentIntent(peer: String) = FinancialCreationIntent.PaymentRequest(
        destinationWalletId = WALLET_TWO,
        peerUserId = peer,
        amount = "25.00",
        amountMinor = 2_500,
        currencyCode = "UGX",
        currencyScale = 2,
        note = "Lunch",
    )

    private fun transferSubmission(
        destinationWalletId: String = WALLET_TWO,
        amountMinor: Long = 2_500,
        amount: String = "25.00",
        note: String? = "Lunch",
    ) = WalletTransferSubmission(
        sourceWalletId = WALLET_ONE,
        destinationWalletId = destinationWalletId,
        amount = amount,
        amountMinor = amountMinor,
        currencyCode = "UGX",
        currencyScale = 2,
        note = note,
    )

    private fun sentTransfer() = SentTransfer(
        transaction = Transaction(
            id = TRANSACTION_ID,
            counterparty = "Ama",
            note = "Lunch",
            amountMinor = -2_500,
            time = "12:00 PM",
            dateGroup = "Today",
            type = TxType.SEND,
            status = TxStatus.COMPLETED,
            reference = "transfer-reference",
            currencyCode = "UGX",
            currencyScale = 2,
            walletId = WALLET_ONE,
            rawType = "internal_transfer",
            rawDirection = "debit",
            counterpartyUserId = USER_ONE,
            customerProjectionVerified = true,
        ),
        claim = null,
    )

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> noCallsProxy(): T = Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
    ) { _, method, _ -> error("Unexpected ${method.name} call") } as T

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> proxy(
        crossinline handler: (String, Array<out Any?>) -> Any?,
    ): T = Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
    ) { proxy, method, arguments ->
        when (method.name) {
            "toString" -> "${T::class.java.simpleName} test proxy"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === arguments?.firstOrNull()
            else -> handler(method.name, arguments.orEmpty())
        }
    } as T

    private class MutableClock(
        private var value: Instant,
        private val zone: ZoneId = ZoneOffset.UTC,
    ) : Clock() {
        fun advance(millis: Long) {
            value = value.plusMillis(millis)
        }

        override fun getZone(): ZoneId = zone
        override fun withZone(zone: ZoneId): Clock = MutableClock(value, zone)
        override fun instant(): Instant = value
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-01T12:00:00Z")
        const val OWNER_A = "10000000-0000-4000-8000-000000000001"
        const val OWNER_B = "10000000-0000-4000-8000-000000000002"
        const val USER_ONE = "20000000-0000-4000-8000-000000000001"
        const val USER_TWO = "20000000-0000-4000-8000-000000000002"
        const val WALLET_ONE = "30000000-0000-4000-8000-000000000001"
        const val WALLET_TWO = "30000000-0000-4000-8000-000000000002"
        const val WALLET_THREE = "30000000-0000-4000-8000-000000000003"
        const val CONVERSATION_ID = "40000000-0000-4000-8000-000000000001"
        const val CONVERSATION_TWO = "40000000-0000-4000-8000-000000000002"
        const val OPERATION_ONE = "50000000-0000-4000-8000-000000000001"
        const val OPERATION_TWO = "50000000-0000-4000-8000-000000000002"
        const val OPERATION_THREE = "50000000-0000-4000-8000-000000000003"
        const val OPERATION_FOUR = "50000000-0000-4000-8000-000000000004"
        const val TRANSACTION_ID = "60000000-0000-4000-8000-000000000001"
        const val PAYMENT_REQUEST_ID = "60000000-0000-4000-8000-000000000002"
        const val GROUP_PAYMENT_ID = "60000000-0000-4000-8000-000000000003"
        const val GROUP_REQUEST_ID = "60000000-0000-4000-8000-000000000004"
        const val CONTRIBUTION_ID = "60000000-0000-4000-8000-000000000005"
    }
}
