package com.kit.wallet.data.messaging

import com.kit.wallet.data.mapper.DecimalMoney
import com.kit.wallet.data.repository.ChatRepository
import com.kit.wallet.data.repository.SentTransfer
import com.kit.wallet.data.repository.WalletRepository
import com.kit.wallet.data.repository.WalletTransferRecoveryResult
import com.kit.wallet.data.repository.WalletTransferSubmission
import com.kit.wallet.data.session.SessionFence
import com.kit.wallet.data.session.SessionInvalidatedException
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.ui.model.Contact
import com.kit.wallet.worker.SecureMessagingSyncScheduler
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The three crash boundaries around a wallet transfer's irreversible server request. */
internal enum class WalletTransferReceiptPhase {
    /** The exact local operation/idempotency identity exists; no transfer request has begun. */
    PREPARED,

    /** The transfer POST may have reached the server. Recovery must never replay it. */
    SUBMITTED,

    /** The exact returned transaction/claim has been bound and is safe to announce. */
    SETTLED,
}

/**
 * One wallet-originated transfer whose customer-facing E2EE chat card still needs a hand-off.
 *
 * This record deliberately contains no PIN, step-up token or bearer credential. It lives inside
 * [SecureMessagingStateStore], so the small amount/reference descriptor is hardware-encrypted and
 * erased with the exact authenticated messaging owner.
 */
internal data class WalletTransferChatReceipt(
    val id: String,
    val recipientUserId: String,
    val amountMinor: Long,
    val createdAtEpochMillis: Long,
    val phase: WalletTransferReceiptPhase,
    val submission: WalletTransferSubmission? = null,
    val descriptor: String? = null,
    val clientMessageId: String? = null,
) {
    val idempotencyKey: String get() = "android-transfer-$id"

    init {
        require(CANONICAL_UUID.matches(id)) { "Invalid wallet-transfer recovery ID" }
        require(CANONICAL_UUID.matches(recipientUserId)) {
            "Invalid wallet-transfer recovery recipient"
        }
        require(amountMinor in 1..MAX_AMOUNT_MINOR) {
            "Invalid wallet-transfer recovery amount"
        }
        require(createdAtEpochMillis > 0L) { "Invalid wallet-transfer recovery time" }
        when (phase) {
            // New records freeze the exact request while they are still PREPARED so a process
            // restart or explicit retry can reuse its idempotency identity. A null value remains
            // readable for PREPARED records written by the previous codec contract.
            WalletTransferReceiptPhase.PREPARED -> submission?.let(::validateSubmission)
            WalletTransferReceiptPhase.SUBMITTED,
            WalletTransferReceiptPhase.SETTLED,
            -> validateSubmission(requireNotNull(submission) {
                "A submitted wallet transfer needs its exact request"
            })
        }
        if (phase == WalletTransferReceiptPhase.SETTLED) {
            val event = requireNotNull(descriptor?.let(KitPaymentMessage::parse)) {
                "A settled wallet transfer needs a canonical payment descriptor"
            }
            require(event.action == KitPaymentAction.SENT || event.action == KitPaymentAction.TRANSFER) {
                "A wallet transfer recovery cannot carry a non-transfer event"
            }
            require(event.amountMinor == amountMinor) {
                "The wallet-transfer receipt amount changed"
            }
            require(clientMessageId == event.deterministicMessageId()) {
                "The wallet-transfer receipt identity changed"
            }
        } else {
            require(descriptor == null && clientMessageId == null) {
                "An unsettled wallet transfer cannot carry a chat receipt"
            }
        }
    }

    private fun validateSubmission(value: WalletTransferSubmission) {
        require(CANONICAL_UUID.matches(value.sourceWalletId)) {
            "Invalid wallet-transfer recovery source"
        }
        require(CANONICAL_UUID.matches(value.destinationWalletId)) {
            "Invalid wallet-transfer recovery destination"
        }
        require(value.amountMinor == amountMinor) { "The wallet-transfer intent amount changed" }
        require(value.currencyCode.matches(CURRENCY_CODE)) {
            "Invalid wallet-transfer recovery currency"
        }
        require(value.currencyScale in 0..9) { "Invalid wallet-transfer recovery currency scale" }
        require(
            com.kit.wallet.data.mapper.DecimalMoney.fromMinor(amountMinor, value.currencyScale) ==
                value.amount
        ) { "Invalid wallet-transfer recovery decimal amount" }
        require(value.note == null || value.note.length <= MAX_NOTE_LENGTH) {
            "Invalid wallet-transfer recovery note"
        }
    }

    companion object {
        const val MAX_AMOUNT_MINOR = 1_000_000_000_000L
        const val MAX_NOTE_LENGTH = 280
        val CANONICAL_UUID = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        )
        private val CURRENCY_CODE = Regex("^[A-Z]{3}$")
    }
}

/**
 * Binds the response to every submitted fact the transaction DTO can prove. The DTO does not expose
 * the private destination-wallet ID; replay provenance binds that request field. A public
 * counterparty/claim identity independently binds the intended recipient when the server returns
 * one, but its omission does not disprove the exact replay.
 */
internal fun WalletTransferChatReceipt.matchesSettledTransfer(sent: SentTransfer): Boolean {
    val exact = submission ?: return false
    val transaction = sent.transaction
    if (transaction.amountMinor == Long.MIN_VALUE || abs(transaction.amountMinor) != amountMinor ||
        transaction.walletId != exact.sourceWalletId ||
        !transaction.currencyCode.equals(exact.currencyCode, ignoreCase = true) ||
        transaction.currencyScale != exact.currencyScale || transaction.note != exact.note ||
        !transaction.rawType.equals("internal_transfer", ignoreCase = true) ||
        !transaction.rawDirection.equals("debit", ignoreCase = true)
    ) return false
    val returnedRecipients = listOfNotNull(
        transaction.counterpartyUserId,
        sent.claim?.recipientUserId,
    )
    if (returnedRecipients.any { !it.equals(recipientUserId, ignoreCase = true) }) return false
    val claim = sent.claim ?: return true
    return claim.transactionId.equals(transaction.id, ignoreCase = true) &&
        claim.amountMinor == amountMinor &&
        claim.currencyCode.equals(exact.currencyCode, ignoreCase = true) &&
        claim.currencyScale == exact.currencyScale
}

/** Strict, versioned bytes kept inside the encrypted secure-messaging state store. */
internal object WalletTransferChatReceiptCodec {
    private const val MAGIC = 0x4b545243 // KTRC
    private const val VERSION = 1

    fun encode(receipt: WalletTransferChatReceipt): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeInt(MAGIC)
            data.writeInt(VERSION)
            data.writeUTF(receipt.id)
            data.writeUTF(receipt.recipientUserId)
            data.writeLong(receipt.amountMinor)
            data.writeLong(receipt.createdAtEpochMillis)
            data.writeByte(receipt.phase.ordinal)
            data.writeBoolean(receipt.submission != null)
            receipt.submission?.let { submission ->
                data.writeUTF(submission.sourceWalletId)
                data.writeUTF(submission.destinationWalletId)
                data.writeUTF(submission.amount)
                data.writeLong(submission.amountMinor)
                data.writeUTF(submission.currencyCode)
                data.writeInt(submission.currencyScale)
                data.writeBoolean(submission.note != null)
                submission.note?.let(data::writeUTF)
            }
            data.writeBoolean(receipt.descriptor != null)
            receipt.descriptor?.let(data::writeUTF)
            data.writeBoolean(receipt.clientMessageId != null)
            receipt.clientMessageId?.let(data::writeUTF)
        }
        return output.toByteArray()
    }

    fun decode(bytes: ByteArray): WalletTransferChatReceipt? = runCatching {
        DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            require(data.readInt() == MAGIC) { "Invalid wallet-transfer recovery record" }
            require(data.readInt() == VERSION) { "Unsupported wallet-transfer recovery record" }
            val receipt = WalletTransferChatReceipt(
                id = data.readUTF(),
                recipientUserId = data.readUTF(),
                amountMinor = data.readLong(),
                createdAtEpochMillis = data.readLong(),
                phase = WalletTransferReceiptPhase.entries[data.readUnsignedByte()],
                submission = if (data.readBoolean()) {
                    WalletTransferSubmission(
                        sourceWalletId = data.readUTF(),
                        destinationWalletId = data.readUTF(),
                        amount = data.readUTF(),
                        amountMinor = data.readLong(),
                        currencyCode = data.readUTF(),
                        currencyScale = data.readInt(),
                        note = if (data.readBoolean()) data.readUTF() else null,
                    )
                } else {
                    null
                },
                descriptor = if (data.readBoolean()) data.readUTF() else null,
                clientMessageId = if (data.readBoolean()) data.readUTF() else null,
            )
            require(data.available() == 0) { "Trailing wallet-transfer recovery bytes" }
            receipt
        }
    }.getOrNull()
}

internal data class WalletTransferReceiptRecoveryBatch(
    val owner: SessionFence,
    val receipts: List<WalletTransferChatReceipt>,
)

/**
 * Fixed-slot encrypted journal for the one non-atomic boundary between money and chat.
 *
 * There are exactly [MAX_RECORDS] physical slots and recovery returns at most
 * [MAX_RECOVERY_BATCH] settled records. It never enumerates wallet transactions or chat history.
 * A `SUBMITTED` record is intentionally not replayed: without an exact server response, repeating
 * an irreversible money request from a background worker would be the more dangerous failure.
 */
@Singleton
internal class WalletTransferChatReceiptStore @Inject constructor(
    private val stateStore: SecureMessagingStateStore,
    private val sessions: SessionStore,
    private val clock: Clock,
) {
    private val mutex = Mutex()
    private val liveBySlot = mutableMapOf<Int, WalletTransferChatReceipt>()
    private val versions = mutableMapOf<Int, Long>()
    private var owner: SessionFence? = null
    private var loaded = false

    suspend fun prepareForOwner(
        expectedOwner: SessionFence,
        recipientUserId: String,
        submission: WalletTransferSubmission,
        operationId: String = UUID.randomUUID().toString(),
    ): WalletTransferChatReceipt = withOwner(expectedOwner) {
        loadLocked()
        pruneAbandonedLocked(clock.millis())
        val normalizedRecipient = recipientUserId.lowercase()
        val normalizedOperationId = operationId.lowercase()
        liveBySlot.values.firstOrNull { it.id == normalizedOperationId }?.let { existing ->
            check(
                existing.recipientUserId == normalizedRecipient &&
                    existing.amountMinor == submission.amountMinor &&
                    existing.submission == submission
            ) { "A wallet-transfer recovery ID belongs to another operation" }
            return@withOwner existing
        }
        val matching = liveBySlot.values.filter { existing ->
            existing.recipientUserId == normalizedRecipient && existing.submission == submission
        }
        check(matching.size <= 1) {
            "Multiple wallet transfers are awaiting recovery for the same exact intent"
        }
        matching.singleOrNull()?.let { return@withOwner it }
        check(liveBySlot.size < MAX_RECORDS) {
            "Too many wallet transfers are awaiting receipt recovery"
        }
        val receipt = WalletTransferChatReceipt(
            id = normalizedOperationId,
            recipientUserId = normalizedRecipient,
            amountMinor = submission.amountMinor,
            createdAtEpochMillis = clock.millis(),
            phase = WalletTransferReceiptPhase.PREPARED,
            submission = submission,
        )
        writeLocked(firstFreeSlotLocked(), receipt)
        receipt
    }

    suspend fun markSubmittedForOwner(
        expectedOwner: SessionFence,
        receiptId: String,
        submission: WalletTransferSubmission,
    ): WalletTransferChatReceipt = withOwner(expectedOwner) {
        loadLocked()
        val (slot, current) = requireReceiptLocked(receiptId)
        when (current.phase) {
            WalletTransferReceiptPhase.PREPARED -> {
                check(current.submission == null || current.submission == submission) {
                    "A prepared wallet-transfer recovery ID belongs to another intent"
                }
                current.copy(
                    phase = WalletTransferReceiptPhase.SUBMITTED,
                    submission = submission,
                ).also { writeLocked(slot, it) }
            }
            WalletTransferReceiptPhase.SUBMITTED,
            WalletTransferReceiptPhase.SETTLED,
            -> current.also {
                check(it.submission == submission) {
                    "A wallet-transfer recovery ID belongs to another submitted intent"
                }
            }
        }
    }

    suspend fun bindSettledForOwner(
        expectedOwner: SessionFence,
        receiptId: String,
        descriptor: String,
        clientMessageId: String,
    ): WalletTransferChatReceipt = withOwner(expectedOwner) {
        loadLocked()
        val (slot, current) = requireReceiptLocked(receiptId)
        val settled = current.copy(
            phase = WalletTransferReceiptPhase.SETTLED,
            descriptor = descriptor,
            clientMessageId = clientMessageId,
        )
        if (current.phase == WalletTransferReceiptPhase.SETTLED) {
            check(current == settled) { "A settled wallet-transfer receipt changed" }
            current
        } else {
            check(current.phase == WalletTransferReceiptPhase.SUBMITTED) {
                "A transfer must cross its submission boundary before settlement"
            }
            writeLocked(slot, settled)
            settled
        }
    }

    /** Removes only an operation proved not to have crossed the financial submission boundary. */
    suspend fun discardPreparedForOwner(expectedOwner: SessionFence, receiptId: String) {
        withOwner(expectedOwner) {
            loadLocked()
            val found = liveBySlot.entries.firstOrNull { it.value.id == receiptId } ?: return@withOwner
            if (found.value.phase == WalletTransferReceiptPhase.PREPARED) {
                writeLocked(found.key, null)
            }
        }
    }

    suspend fun recoveryBatchForCurrentOwner(): WalletTransferReceiptRecoveryBatch? {
        val expectedOwner = sessions.current()?.fence() ?: return null
        return withOwner(expectedOwner) {
            loadLocked()
            pruneAbandonedLocked(clock.millis())
            WalletTransferReceiptRecoveryBatch(
                owner = expectedOwner,
                receipts = liveBySlot.values
                    .asSequence()
                    .filter { it.phase != WalletTransferReceiptPhase.PREPARED }
                    .sortedWith(compareBy({ it.createdAtEpochMillis }, { it.id }))
                    .take(MAX_RECOVERY_BATCH)
                    .toList(),
            )
        }
    }

    /** Removes a submitted command only after exact backend recovery proves it never committed. */
    suspend fun discardNotCommittedForOwner(expectedOwner: SessionFence, receiptId: String) {
        withOwner(expectedOwner) {
            loadLocked()
            val found = liveBySlot.entries.firstOrNull { it.value.id == receiptId }
                ?: return@withOwner
            check(found.value.phase == WalletTransferReceiptPhase.SUBMITTED) {
                "Only an unresolved submitted transfer can be discarded as not committed"
            }
            writeLocked(found.key, null)
        }
    }

    suspend fun completeForOwner(
        expectedOwner: SessionFence,
        receiptId: String,
        clientMessageId: String,
    ) {
        withOwner(expectedOwner) {
            loadLocked()
            val found = liveBySlot.entries.firstOrNull { it.value.id == receiptId }
                ?: return@withOwner
            check(
                found.value.phase == WalletTransferReceiptPhase.SETTLED &&
                    found.value.clientMessageId == clientMessageId
            ) { "Only the exact durably handed-off wallet receipt can be completed" }
            writeLocked(found.key, null)
        }
    }

    internal suspend fun snapshotForOwner(
        expectedOwner: SessionFence,
    ): List<WalletTransferChatReceipt> = withOwner(expectedOwner) {
        loadLocked()
        liveBySlot.values.sortedWith(compareBy({ it.createdAtEpochMillis }, { it.id }))
    }

    private suspend fun <T> withOwner(
        expectedOwner: SessionFence,
        operation: suspend () -> T,
    ): T = sessions.withCurrentSession(expectedOwner) { current ->
        check(current.fence() == expectedOwner)
        stateStore.withStateLease {
            mutex.withLock {
                bindOwnerLocked(expectedOwner)
                operation()
            }
        }
    }

    private fun bindOwnerLocked(expectedOwner: SessionFence) {
        if (owner == expectedOwner) return
        liveBySlot.clear()
        versions.clear()
        owner = expectedOwner
        loaded = false
    }

    private suspend fun loadLocked() {
        if (loaded) return
        liveBySlot.clear()
        versions.clear()
        val page = stateStore.readNamespacePage(NAMESPACE, afterRecordKey = null, MAX_RECORDS + 1)
        check(page.nextAfterRecordKey == null && page.records().size <= MAX_RECORDS) {
            "Wallet-transfer recovery exceeded its fixed storage bound"
        }
        page.records().forEach { record ->
            try {
                val slot = slotFor(record.recordKey)
                check(versions.put(slot, record.version) == null) {
                    "Duplicate wallet-transfer recovery slot"
                }
                if (record.bytes.size == 1 && record.bytes[0] == TOMBSTONE) return@forEach
                val decoded = checkNotNull(WalletTransferChatReceiptCodec.decode(record.bytes)) {
                    "Unreadable wallet-transfer recovery record"
                }
                check(liveBySlot.values.none { it.id == decoded.id }) {
                    "Duplicate wallet-transfer recovery identity"
                }
                liveBySlot[slot] = decoded
            } finally {
                record.bytes.fill(0)
            }
        }
        loaded = true
    }

    private suspend fun pruneAbandonedLocked(nowEpochMillis: Long) {
        val staleSlots = liveBySlot.filter { (_, receipt) ->
            val age = nowEpochMillis - receipt.createdAtEpochMillis
            when (receipt.phase) {
                WalletTransferReceiptPhase.PREPARED -> age > PREPARED_RETENTION_MILLIS
                // A submitted command may have committed. Only the exact recovery endpoint may
                // prove otherwise, so age alone can never erase it.
                WalletTransferReceiptPhase.SUBMITTED -> false
                WalletTransferReceiptPhase.SETTLED -> false
            }
        }.keys.toList()
        staleSlots.forEach { writeLocked(it, null) }
    }

    private fun requireReceiptLocked(receiptId: String): Pair<Int, WalletTransferChatReceipt> {
        val found = liveBySlot.entries.firstOrNull { it.value.id == receiptId }
            ?: error("The wallet-transfer recovery record is no longer available")
        return found.key to found.value
    }

    private fun firstFreeSlotLocked(): Int = (0 until MAX_RECORDS).first { it !in liveBySlot }

    private suspend fun writeLocked(slot: Int, receipt: WalletTransferChatReceipt?) {
        val bytes = receipt?.let(WalletTransferChatReceiptCodec::encode) ?: byteArrayOf(TOMBSTONE)
        try {
            val committed = stateStore.write(
                namespace = NAMESPACE,
                recordKey = recordKey(slot),
                expectedVersion = versions[slot],
                bytes = bytes,
            )
            versions[slot] = committed.version
            if (receipt == null) liveBySlot.remove(slot) else liveBySlot[slot] = receipt
        } finally {
            bytes.fill(0)
        }
    }

    private fun recordKey(slot: Int): String = "receipt:${slot.toString().padStart(2, '0')}"

    private fun slotFor(recordKey: String): Int {
        require(recordKey.startsWith("receipt:")) { "Invalid wallet-transfer recovery key" }
        val slot = recordKey.removePrefix("receipt:").toIntOrNull()
        require(slot != null && slot in 0 until MAX_RECORDS && recordKey == recordKey(slot)) {
            "Invalid wallet-transfer recovery slot"
        }
        return slot
    }

    internal companion object {
        const val MAX_RECORDS = 32
        const val MAX_RECOVERY_BATCH = 8
        const val PREPARED_RETENTION_MILLIS = 24L * 60L * 60L * 1_000L
        const val NAMESPACE = "wallet-transfer-chat-receipt"
        const val TOMBSTONE: Byte = 0
    }
}

internal enum class WalletTransferReceiptRecoveryOutcome { IDLE, COMMITTED, RETRY }

/**
 * Bridges the wallet's irreversible transfer result into the ordinary E2EE immediate outbox.
 *
 * Recovery reads only the fixed journal above. It never repeats a transfer POST and never scans
 * transaction history looking for something that resembles the original operation.
 */
@Singleton
internal class WalletTransferChatReceiptCoordinator @Inject constructor(
    private val store: WalletTransferChatReceiptStore,
    private val sessions: SessionStore,
    private val wallet: WalletRepository,
    private val chats: ChatRepository,
    private val messagingSyncScheduler: SecureMessagingSyncScheduler? = null,
) {
    /** Prevents this process's pre-POST wake from racing ahead of the foreground submission. */
    private val activeSubmissions = ConcurrentHashMap.newKeySet<Pair<SessionFence, String>>()

    suspend fun send(
        recipient: Contact,
        amountMinor: Long,
        note: String?,
        paymentPin: String,
    ): SentTransfer {
        val owner = sessions.current()?.fence() ?: throw SessionInvalidatedException()
        val intendedSubmission = intendedSubmission(owner, recipient, amountMinor, note)
        val prepared = store.prepareForOwner(owner, recipient.id, intendedSubmission)
        var settledReceipt: WalletTransferChatReceipt? = null
        try {
            val sent = wallet.sendToContactForOwner(
                owner = owner,
                recipient = recipient,
                amountMinor = amountMinor,
                note = note,
                paymentPin = paymentPin,
                idempotencyKey = prepared.idempotencyKey,
                onSubmitting = { submission ->
                    withContext(NonCancellable) {
                        check(submission == intendedSubmission) {
                            "The wallet-transfer intent changed before submission"
                        }
                        activeSubmissions += owner to prepared.id
                        try {
                            store.markSubmittedForOwner(owner, prepared.id, submission)
                            runCatching { messagingSyncScheduler?.schedule() }
                        } catch (error: Throwable) {
                            activeSubmissions -= owner to prepared.id
                            throw error
                        }
                    }
                },
                onSettled = { exact ->
                    settledReceipt = withContext(NonCancellable) {
                        bindExactResult(owner, prepared, exact)
                    }
                },
            )
            val settled = settledReceipt ?: withContext(NonCancellable) {
                bindExactResult(owner, prepared, sent)
            }
            // Existing direct chats need no network to resolve, so hand them to the E2EE outbox
            // before returning. A new conversation is resolved by the bounded worker path.
            runCatching { handoffIfConversationKnown(owner, settled) }
            runCatching { messagingSyncScheduler?.schedule() }
            return sent
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                runCatching { store.discardPreparedForOwner(owner, prepared.id) }
            }
            throw cancelled
        } catch (error: Exception) {
            withContext(NonCancellable) {
                runCatching { store.discardPreparedForOwner(owner, prepared.id) }
            }
            throw error
        } finally {
            if (activeSubmissions.remove(owner to prepared.id)) {
                runCatching { messagingSyncScheduler?.schedule() }
            }
        }
    }

    private suspend fun intendedSubmission(
        owner: SessionFence,
        recipient: Contact,
        amountMinor: Long,
        note: String?,
    ): WalletTransferSubmission {
        val source = wallet.spendingSourceForOwner(owner)
        val destinationWalletId = requireNotNull(recipient.receivingWalletId) {
            "This contact cannot receive Kit Pay transfers yet"
        }
        return WalletTransferSubmission(
            sourceWalletId = source.walletId,
            destinationWalletId = destinationWalletId,
            amount = DecimalMoney.fromMinor(amountMinor, source.currencyScale),
            amountMinor = amountMinor,
            currencyCode = source.currencyCode,
            currencyScale = source.currencyScale,
            note = note,
        )
    }

    /** Called by the messaging worker after secure state is ready. Never invokes [wallet]. */
    suspend fun recover(): WalletTransferReceiptRecoveryOutcome {
        val batch = store.recoveryBatchForCurrentOwner()
            ?: return WalletTransferReceiptRecoveryOutcome.IDLE
        if (batch.receipts.isEmpty()) return WalletTransferReceiptRecoveryOutcome.IDLE
        var progressed = false
        var retry = false
        for (stored in batch.receipts) {
            if (sessions.current()?.fence() != batch.owner) break
            if (batch.owner to stored.id in activeSubmissions) {
                retry = true
                continue
            }
            try {
                val receipt = when (stored.phase) {
                    WalletTransferReceiptPhase.PREPARED -> continue
                    WalletTransferReceiptPhase.SUBMITTED -> when (
                        val recovered = wallet.recoverSentTransferForOwner(
                            owner = batch.owner,
                            submission = checkNotNull(stored.submission),
                            idempotencyKey = stored.idempotencyKey,
                        )
                    ) {
                        WalletTransferRecoveryResult.InProgress -> {
                            retry = true
                            continue
                        }
                        WalletTransferRecoveryResult.NotCommitted -> {
                            store.discardNotCommittedForOwner(batch.owner, stored.id)
                            progressed = true
                            continue
                        }
                        is WalletTransferRecoveryResult.Settled -> {
                            bindExactResult(batch.owner, stored, recovered.transfer)
                        }
                    }
                    WalletTransferReceiptPhase.SETTLED -> stored
                }
                val conversationId = knownConversationId(receipt.recipientUserId)
                    ?: chats.openDirectConversationForOwner(
                        batch.owner,
                        recoveryContact(receipt.recipientUserId),
                    )
                handoff(batch.owner, receipt, conversationId)
                progressed = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (invalidated: SessionInvalidatedException) {
                break
            } catch (_: Exception) {
                retry = true
            }
        }
        return when {
            retry || batch.receipts.size == WalletTransferChatReceiptStore.MAX_RECOVERY_BATCH ->
                WalletTransferReceiptRecoveryOutcome.RETRY
            progressed -> WalletTransferReceiptRecoveryOutcome.COMMITTED
            else -> WalletTransferReceiptRecoveryOutcome.IDLE
        }
    }

    private suspend fun bindExactResult(
        owner: SessionFence,
        prepared: WalletTransferChatReceipt,
        sent: SentTransfer,
    ): WalletTransferChatReceipt {
        require(prepared.matchesSettledTransfer(sent)) {
            "The returned transfer does not match its submitted intent"
        }
        val amount = sent.transaction.amountMinor
        val claim = sent.claim
        val event = KitPaymentMessage(
            action = if (claim == null) KitPaymentAction.SENT else KitPaymentAction.TRANSFER,
            referenceId = claim?.id ?: sent.transaction.id,
            amountMinor = abs(amount),
            currencyCode = sent.transaction.currencyCode,
            currencyScale = sent.transaction.currencyScale,
            note = sent.transaction.note
                ?.takeIf(String::isNotBlank)
                ?.take(KitPaymentMessage.MAX_NOTE_LENGTH),
        )
        val descriptor = event.encode()
        check(KitPaymentMessage.parse(descriptor) == event) {
            "The server returned an invalid transfer receipt"
        }
        return store.bindSettledForOwner(
            expectedOwner = owner,
            receiptId = prepared.id,
            descriptor = descriptor,
            clientMessageId = event.deterministicMessageId(),
        )
    }

    private suspend fun handoffIfConversationKnown(
        owner: SessionFence,
        receipt: WalletTransferChatReceipt,
    ): Boolean {
        val conversationId = knownConversationId(receipt.recipientUserId) ?: return false
        handoff(owner, receipt, conversationId)
        return true
    }

    private suspend fun handoff(
        owner: SessionFence,
        receipt: WalletTransferChatReceipt,
        conversationId: String,
    ) {
        check(receipt.phase == WalletTransferReceiptPhase.SETTLED)
        val descriptor = checkNotNull(receipt.descriptor)
        val clientMessageId = checkNotNull(receipt.clientMessageId)
        chats.capturePaymentEventForOwner(
            owner = owner,
            chatId = conversationId,
            descriptor = descriptor,
            clientMessageId = clientMessageId,
        )
        // If the process dies between those two durable writes, the same deterministic identity
        // is captured again on restart and the immediate outbox accepts it as the same intent.
        store.completeForOwner(owner, receipt.id, clientMessageId)
    }

    private fun knownConversationId(recipientUserId: String): String? = chats.chats.value
        .firstOrNull { preview ->
            !preview.isGroup && preview.peerUserId.equals(recipientUserId, ignoreCase = true)
        }
        ?.id

    private fun recoveryContact(recipientUserId: String) = Contact(
        id = recipientUserId,
        name = "Kit Pay contact",
        phone = "",
        isKitUser = true,
    )
}
