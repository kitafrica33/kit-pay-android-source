package com.kit.wallet.data.messaging

import com.kit.wallet.data.mapper.DecimalMoney
import com.kit.wallet.data.mapper.toUiModel
import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.ContributeGroupPaymentRequest
import com.kit.wallet.data.remote.CreateGroupPaymentRecipient
import com.kit.wallet.data.remote.CreateGroupPaymentRequest
import com.kit.wallet.data.remote.CreatePaymentRequestDto
import com.kit.wallet.data.remote.GroupPaymentRequestContributionResultDto
import com.kit.wallet.data.remote.KitGroupPaymentRequestAction
import com.kit.wallet.data.remote.KitGroupPaymentRequestMessage
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.KitWalletApiException
import com.kit.wallet.data.remote.PaymentRequestDto
import com.kit.wallet.data.repository.ChatRepository
import com.kit.wallet.data.repository.ChatPaymentRequest
import com.kit.wallet.data.repository.matchesContributionIntent
import com.kit.wallet.data.repository.validateCreatedPaymentRequest
import com.kit.wallet.data.session.SessionFence
import com.kit.wallet.data.session.SessionInvalidatedException
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.ui.model.GroupPaymentSummary
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class FinancialCreationReceiptPhase { PREPARED, SUBMITTED, SETTLED }

internal sealed interface FinancialCreationIntent {
    data class PaymentRequest(
        val destinationWalletId: String,
        val peerUserId: String,
        val amount: String,
        val amountMinor: Long,
        val currencyCode: String,
        val currencyScale: Int,
        val note: String?,
    ) : FinancialCreationIntent

    data class GroupPayment(
        val request: CreateGroupPaymentRequest,
    ) : FinancialCreationIntent

    data class GroupRequestContribution(
        val requestId: String,
        val sourceWalletId: String,
        val amount: String,
        val amountMinor: Long,
    ) : FinancialCreationIntent
}

/** One result-ID-unknown mutation retained until its deterministic chat intent is durable. */
internal data class FinancialCreationReceipt(
    val id: String,
    val conversationId: String,
    val idempotencyKey: String,
    val createdAtEpochMillis: Long,
    val phase: FinancialCreationReceiptPhase,
    val intent: FinancialCreationIntent,
    val descriptor: String? = null,
    val clientMessageId: String? = null,
) {
    init {
        require(CANONICAL_UUID.matches(id)) { "Invalid financial recovery ID" }
        require(CANONICAL_UUID.matches(conversationId)) { "Invalid recovery conversation" }
        require(idempotencyKey.length in 16..128 && IDEMPOTENCY_KEY.matches(idempotencyKey)) {
            "Invalid financial recovery idempotency key"
        }
        validateIntent(intent)
        if (phase == FinancialCreationReceiptPhase.SETTLED) {
            val text = requireNotNull(descriptor) { "A settled recovery needs its descriptor" }
            require(CANONICAL_UUID.matches(requireNotNull(clientMessageId))) {
                "A settled recovery needs its deterministic message ID"
            }
            when (intent) {
                is FinancialCreationIntent.PaymentRequest -> {
                    val event = requireNotNull(KitPaymentMessage.parse(text))
                    require(event.action == KitPaymentAction.REQUEST)
                    require(event.amountMinor == intent.amountMinor)
                    require(clientMessageId == event.deterministicMessageId())
                }
                is FinancialCreationIntent.GroupPayment -> {
                    val event = requireNotNull(KitGroupPaymentMessage.parse(text))
                    require(event.action == KitGroupPaymentAction.SENT)
                    require(clientMessageId == event.announcementMessageId())
                }
                is FinancialCreationIntent.GroupRequestContribution -> {
                    val event = requireNotNull(KitGroupPaymentRequestMessage.parse(text))
                    require(event.action == KitGroupPaymentRequestAction.CONTRIBUTED)
                    require(event.requestId == intent.requestId.lowercase())
                    require(event.amountMinor == intent.amountMinor)
                    require(clientMessageId == event.deterministicMessageId())
                }
            }
        } else {
            require(descriptor == null && clientMessageId == null) {
                "An unresolved financial creation cannot carry a chat result"
            }
        }
    }

    private fun validateIntent(value: FinancialCreationIntent) {
        when (value) {
            is FinancialCreationIntent.PaymentRequest -> {
                require(CANONICAL_UUID.matches(value.destinationWalletId))
                require(CANONICAL_UUID.matches(value.peerUserId))
                require(value.amountMinor in 1..MAX_AMOUNT_MINOR)
                require(value.currencyCode.matches(CURRENCY_CODE) && value.currencyScale in 0..9)
                require(DecimalMoney.fromMinor(value.amountMinor, value.currencyScale) == value.amount)
                require(value.note == null || value.note.length <= MAX_NOTE_LENGTH)
            }
            is FinancialCreationIntent.GroupPayment -> validateGroupPaymentRequest(value.request)
            is FinancialCreationIntent.GroupRequestContribution -> {
                require(CANONICAL_UUID.matches(value.requestId))
                require(CANONICAL_UUID.matches(value.sourceWalletId))
                require(value.amountMinor in 1..MAX_AMOUNT_MINOR)
                require(value.amount.length <= MAX_MONEY_LENGTH && MONEY.matches(value.amount))
            }
        }
    }

    private fun validateGroupPaymentRequest(request: CreateGroupPaymentRequest) {
        require(CANONICAL_UUID.matches(request.sourceWalletId))
        require(request.splitMode in setOf("even", "custom"))
        require(request.audience in setOf("all", "selected"))
        require(
            request.note == null ||
                request.note.isNotBlank() && request.note.length <= MAX_NOTE_LENGTH
        )
        val recipients = request.recipients
        require((recipients?.size ?: 0) <= MAX_GROUP_RECIPIENTS)
        require(recipients.orEmpty().map { it.userId }.distinct().size == recipients.orEmpty().size)
        recipients.orEmpty().forEach { recipient ->
            require(CANONICAL_UUID.matches(recipient.userId))
            require(
                recipient.amount == null ||
                    recipient.amount.length <= MAX_MONEY_LENGTH && MONEY.matches(recipient.amount)
            )
        }
        require(
            request.totalAmount == null ||
                request.totalAmount.length <= MAX_MONEY_LENGTH && MONEY.matches(request.totalAmount)
        )
        when (request.splitMode) {
            "even" -> {
                require(request.totalAmount != null)
                require(request.recipients.orEmpty().all { it.amount == null })
                require(
                    request.audience == "all" && request.recipients == null ||
                        request.audience == "selected" && !request.recipients.isNullOrEmpty()
                )
            }
            "custom" -> {
                require(request.audience == "selected" && request.totalAmount == null)
                require(!request.recipients.isNullOrEmpty())
                require(request.recipients.all { it.amount != null })
            }
        }
    }

    internal companion object {
        const val MAX_AMOUNT_MINOR = 1_000_000_000_000L
        const val MAX_NOTE_LENGTH = 280
        const val MAX_GROUP_RECIPIENTS = 50
        const val MAX_MONEY_LENGTH = 64
        val CANONICAL_UUID = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        )
        val CURRENCY_CODE = Regex("^[A-Z]{3}$")
        val IDEMPOTENCY_KEY = Regex("^[A-Za-z0-9._:-]+$")
        val MONEY = Regex("^(0|[1-9][0-9]*)(\\.[0-9]+)?$")
    }
}

internal object FinancialCreationReceiptCodec {
    private const val MAGIC = 0x4b464352 // KFCR
    private const val VERSION = 1

    fun encode(receipt: FinancialCreationReceipt): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeInt(MAGIC)
            data.writeInt(VERSION)
            data.writeUTF(receipt.id)
            data.writeUTF(receipt.conversationId)
            data.writeUTF(receipt.idempotencyKey)
            data.writeLong(receipt.createdAtEpochMillis)
            data.writeByte(receipt.phase.ordinal)
            when (val intent = receipt.intent) {
                is FinancialCreationIntent.PaymentRequest -> {
                    data.writeByte(0)
                    data.writeUTF(intent.destinationWalletId)
                    data.writeUTF(intent.peerUserId)
                    data.writeUTF(intent.amount)
                    data.writeLong(intent.amountMinor)
                    data.writeUTF(intent.currencyCode)
                    data.writeInt(intent.currencyScale)
                    data.writeNullableUtf(intent.note)
                }
                is FinancialCreationIntent.GroupPayment -> {
                    data.writeByte(1)
                    val request = intent.request
                    data.writeUTF(request.sourceWalletId)
                    data.writeUTF(request.splitMode)
                    data.writeUTF(request.audience)
                    data.writeNullableUtf(request.totalAmount)
                    data.writeNullableUtf(request.note)
                    data.writeInt(request.recipients?.size ?: -1)
                    request.recipients.orEmpty().forEach { recipient ->
                        data.writeUTF(recipient.userId)
                        data.writeNullableUtf(recipient.amount)
                    }
                }
                is FinancialCreationIntent.GroupRequestContribution -> {
                    data.writeByte(2)
                    data.writeUTF(intent.requestId)
                    data.writeUTF(intent.sourceWalletId)
                    data.writeUTF(intent.amount)
                    data.writeLong(intent.amountMinor)
                }
            }
            data.writeNullableUtf(receipt.descriptor)
            data.writeNullableUtf(receipt.clientMessageId)
        }
        return output.toByteArray()
    }

    fun decode(bytes: ByteArray): FinancialCreationReceipt? = runCatching {
        DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            require(data.readInt() == MAGIC)
            require(data.readInt() == VERSION)
            val id = data.readUTF()
            val conversationId = data.readUTF()
            val key = data.readUTF()
            val createdAt = data.readLong()
            val phase = FinancialCreationReceiptPhase.entries[data.readUnsignedByte()]
            val intent = when (data.readUnsignedByte()) {
                0 -> FinancialCreationIntent.PaymentRequest(
                    destinationWalletId = data.readUTF(),
                    peerUserId = data.readUTF(),
                    amount = data.readUTF(),
                    amountMinor = data.readLong(),
                    currencyCode = data.readUTF(),
                    currencyScale = data.readInt(),
                    note = data.readNullableUtf(),
                )
                1 -> {
                    val sourceWalletId = data.readUTF()
                    val splitMode = data.readUTF()
                    val audience = data.readUTF()
                    val totalAmount = data.readNullableUtf()
                    val note = data.readNullableUtf()
                    val count = data.readInt()
                    require(count in -1..FinancialCreationReceipt.MAX_GROUP_RECIPIENTS)
                    val recipients = if (count < 0) null else List(count) {
                        CreateGroupPaymentRecipient(data.readUTF(), data.readNullableUtf())
                    }
                    FinancialCreationIntent.GroupPayment(
                        CreateGroupPaymentRequest(
                            sourceWalletId,
                            splitMode,
                            audience,
                            totalAmount,
                            note,
                            recipients,
                        ),
                    )
                }
                2 -> FinancialCreationIntent.GroupRequestContribution(
                    requestId = data.readUTF(),
                    sourceWalletId = data.readUTF(),
                    amount = data.readUTF(),
                    amountMinor = data.readLong(),
                )
                else -> error("Unknown financial recovery intent")
            }
            FinancialCreationReceipt(
                id = id,
                conversationId = conversationId,
                idempotencyKey = key,
                createdAtEpochMillis = createdAt,
                phase = phase,
                intent = intent,
                descriptor = data.readNullableUtf(),
                clientMessageId = data.readNullableUtf(),
            ).also { require(data.available() == 0) }
        }
    }.getOrNull()

    private fun DataOutputStream.writeNullableUtf(value: String?) {
        writeBoolean(value != null)
        value?.let(::writeUTF)
    }

    private fun DataInputStream.readNullableUtf(): String? = if (readBoolean()) readUTF() else null
}

internal data class FinancialCreationRecoveryBatch(
    val owner: SessionFence,
    val receipts: List<FinancialCreationReceipt>,
)

/** Fixed-slot, exact-login-owned journal for result-ID-unknown financial creations. */
@Singleton
internal class FinancialCreationReceiptStore @Inject constructor(
    private val stateStore: SecureMessagingStateStore,
    private val sessions: SessionStore,
    private val clock: Clock,
) {
    private val mutex = Mutex()
    private val live = mutableMapOf<Int, FinancialCreationReceipt>()
    private val versions = mutableMapOf<Int, Long>()
    private var owner: SessionFence? = null
    private var loaded = false

    suspend fun prepareForOwner(
        expectedOwner: SessionFence,
        conversationId: String,
        idempotencyKey: String,
        intent: FinancialCreationIntent,
    ): FinancialCreationReceipt = withOwner(expectedOwner) {
        loadLocked()
        prunePreparedLocked(clock.millis())
        live.values.firstOrNull {
            it.conversationId == conversationId.lowercase() && it.intent == intent
        }?.let { return@withOwner it }
        val id = deterministicUuid("financial-creation-recovery-v1|$idempotencyKey")
        live.values.firstOrNull { it.id == id }?.let { existing ->
            check(
                existing.conversationId == conversationId.lowercase() &&
                    existing.idempotencyKey == idempotencyKey && existing.intent == intent
            ) { "A financial recovery identity belongs to another operation" }
            return@withOwner existing
        }
        check(live.size < MAX_RECORDS) { "Too many financial events await recovery" }
        FinancialCreationReceipt(
            id = id,
            conversationId = conversationId.lowercase(),
            idempotencyKey = idempotencyKey,
            createdAtEpochMillis = clock.millis(),
            phase = FinancialCreationReceiptPhase.PREPARED,
            intent = intent,
        ).also { writeLocked(firstFreeSlotLocked(), it) }
    }

    suspend fun markSubmittedForOwner(
        expectedOwner: SessionFence,
        receiptId: String,
    ): FinancialCreationReceipt = withOwner(expectedOwner) {
        loadLocked()
        val (slot, current) = requireReceiptLocked(receiptId)
        if (current.phase == FinancialCreationReceiptPhase.PREPARED) {
            current.copy(phase = FinancialCreationReceiptPhase.SUBMITTED)
                .also { writeLocked(slot, it) }
        } else {
            current
        }
    }

    suspend fun bindSettledForOwner(
        expectedOwner: SessionFence,
        receiptId: String,
        descriptor: String,
        clientMessageId: String,
    ): FinancialCreationReceipt = withOwner(expectedOwner) {
        loadLocked()
        val (slot, current) = requireReceiptLocked(receiptId)
        val settled = current.copy(
            phase = FinancialCreationReceiptPhase.SETTLED,
            descriptor = descriptor,
            clientMessageId = clientMessageId,
        )
        if (current.phase == FinancialCreationReceiptPhase.SETTLED) {
            check(current == settled) { "A settled financial recovery result changed" }
            current
        } else {
            check(current.phase == FinancialCreationReceiptPhase.SUBMITTED)
            writeLocked(slot, settled)
            settled
        }
    }

    suspend fun discardPreparedForOwner(expectedOwner: SessionFence, receiptId: String) {
        withOwner(expectedOwner) {
            loadLocked()
            val found = live.entries.firstOrNull { it.value.id == receiptId } ?: return@withOwner
            if (found.value.phase == FinancialCreationReceiptPhase.PREPARED) {
                writeLocked(found.key, null)
            }
        }
    }

    suspend fun discardNotCommittedForOwner(expectedOwner: SessionFence, receiptId: String) {
        withOwner(expectedOwner) {
            loadLocked()
            val found = live.entries.firstOrNull { it.value.id == receiptId } ?: return@withOwner
            check(found.value.phase == FinancialCreationReceiptPhase.SUBMITTED)
            writeLocked(found.key, null)
        }
    }

    suspend fun recoveryBatchForCurrentOwner(): FinancialCreationRecoveryBatch? {
        val expectedOwner = sessions.current()?.fence() ?: return null
        return withOwner(expectedOwner) {
            loadLocked()
            prunePreparedLocked(clock.millis())
            FinancialCreationRecoveryBatch(
                expectedOwner,
                live.values.asSequence()
                    .filter { it.phase != FinancialCreationReceiptPhase.PREPARED }
                    .sortedWith(compareBy({ it.createdAtEpochMillis }, { it.id }))
                    .take(MAX_RECOVERY_BATCH)
                    .toList(),
            )
        }
    }

    suspend fun completeForOwner(
        expectedOwner: SessionFence,
        receiptId: String,
        clientMessageId: String,
    ) {
        withOwner(expectedOwner) {
            loadLocked()
            val found = live.entries.firstOrNull { it.value.id == receiptId } ?: return@withOwner
            check(
                found.value.phase == FinancialCreationReceiptPhase.SETTLED &&
                    found.value.clientMessageId == clientMessageId
            )
            writeLocked(found.key, null)
        }
    }

    internal suspend fun snapshotForOwner(owner: SessionFence): List<FinancialCreationReceipt> =
        withOwner(owner) {
            loadLocked()
            live.values.sortedWith(compareBy({ it.createdAtEpochMillis }, { it.id }))
        }

    private suspend fun <T> withOwner(
        expectedOwner: SessionFence,
        block: suspend () -> T,
    ): T = sessions.withCurrentSession(expectedOwner) {
        stateStore.withStateLease {
            mutex.withLock {
                bindOwnerLocked(expectedOwner)
                block()
            }
        }
    }

    private fun bindOwnerLocked(expectedOwner: SessionFence) {
        if (owner == expectedOwner) return
        live.clear()
        versions.clear()
        loaded = false
        owner = expectedOwner
    }

    private suspend fun loadLocked() {
        if (loaded) return
        live.clear()
        versions.clear()
        val page = stateStore.readNamespacePage(NAMESPACE, null, MAX_RECORDS + 1)
        check(page.nextAfterRecordKey == null && page.records().size <= MAX_RECORDS)
        page.records().forEach { record ->
            try {
                val slot = slotFor(record.recordKey)
                check(versions.put(slot, record.version) == null)
                if (record.bytes.size == 1 && record.bytes[0] == TOMBSTONE) return@forEach
                val decoded = checkNotNull(FinancialCreationReceiptCodec.decode(record.bytes))
                check(live.values.none { it.id == decoded.id })
                live[slot] = decoded
            } finally {
                record.bytes.fill(0)
            }
        }
        loaded = true
    }

    private suspend fun prunePreparedLocked(now: Long) {
        val stale = live.filterValues {
            it.phase == FinancialCreationReceiptPhase.PREPARED &&
                now - it.createdAtEpochMillis > PREPARED_RETENTION_MILLIS
        }.keys.toList()
        stale.forEach { writeLocked(it, null) }
    }

    private fun requireReceiptLocked(id: String): Pair<Int, FinancialCreationReceipt> {
        val found = live.entries.firstOrNull { it.value.id == id }
            ?: error("The financial recovery record is no longer available")
        return found.key to found.value
    }

    private fun firstFreeSlotLocked(): Int = (0 until MAX_RECORDS).first { it !in live }

    private suspend fun writeLocked(slot: Int, receipt: FinancialCreationReceipt?) {
        val bytes = receipt?.let(FinancialCreationReceiptCodec::encode) ?: byteArrayOf(TOMBSTONE)
        try {
            val result = stateStore.write(NAMESPACE, recordKey(slot), versions[slot], bytes)
            versions[slot] = result.version
            if (receipt == null) live.remove(slot) else live[slot] = receipt
        } finally {
            bytes.fill(0)
        }
    }

    private fun recordKey(slot: Int) = "receipt:${slot.toString().padStart(2, '0')}"

    private fun slotFor(key: String): Int {
        val slot = key.removePrefix("receipt:").toIntOrNull()
        require(slot != null && slot in 0 until MAX_RECORDS && key == recordKey(slot))
        return slot
    }

    internal companion object {
        const val MAX_RECORDS = 32
        const val MAX_RECOVERY_BATCH = 8
        const val PREPARED_RETENTION_MILLIS = 24L * 60L * 60L * 1_000L
        const val NAMESPACE = "financial-creation-chat-receipt"
        const val TOMBSTONE: Byte = 0
    }
}

internal enum class FinancialCreationRecoveryOutcome { IDLE, COMMITTED, RETRY }

/** Resolves only exact idempotency records and hands their result to the E2EE immediate outbox. */
@Singleton
internal class FinancialCreationReceiptCoordinator @Inject constructor(
    private val store: FinancialCreationReceiptStore,
    private val sessions: SessionStore,
    private val api: KitWalletApi,
    private val apiCalls: ApiCallExecutor,
    private val chats: ChatRepository,
    private val scheduler: SecureMessagingSyncScheduler? = null,
) {
    /** Keeps a pre-POST recovery wake from treating this process's active request as absent. */
    private val activeSubmissions = ConcurrentHashMap.newKeySet<Pair<SessionFence, String>>()

    suspend fun preparePaymentRequest(
        owner: SessionFence,
        conversationId: String,
        destinationWalletId: String,
        peerUserId: String,
        amountMinor: Long,
        currencyCode: String,
        currencyScale: Int,
        note: String?,
        idempotencyKey: String = "android-chat-request-${UUID.randomUUID()}",
    ): FinancialCreationReceipt = store.prepareForOwner(
        owner,
        conversationId,
        idempotencyKey,
        FinancialCreationIntent.PaymentRequest(
            destinationWalletId = destinationWalletId,
            peerUserId = peerUserId.lowercase(),
            amount = DecimalMoney.fromMinor(amountMinor, currencyScale),
            amountMinor = amountMinor,
            currencyCode = currencyCode,
            currencyScale = currencyScale,
            note = note,
        ),
    )

    suspend fun prepareGroupPayment(
        owner: SessionFence,
        conversationId: String,
        idempotencyKey: String,
        request: CreateGroupPaymentRequest,
    ): FinancialCreationReceipt = store.prepareForOwner(
        owner,
        conversationId,
        idempotencyKey,
        FinancialCreationIntent.GroupPayment(request),
    )

    suspend fun prepareGroupRequestContribution(
        owner: SessionFence,
        conversationId: String,
        idempotencyKey: String,
        requestId: String,
        sourceWalletId: String,
        amountMinor: Long,
        amount: String,
    ): FinancialCreationReceipt = store.prepareForOwner(
        owner,
        conversationId,
        idempotencyKey,
        FinancialCreationIntent.GroupRequestContribution(
            requestId.lowercase(),
            sourceWalletId,
            amount,
            amountMinor,
        ),
    )

    suspend fun markSubmitted(owner: SessionFence, receiptId: String) {
        activeSubmissions += owner to receiptId
        try {
            store.markSubmittedForOwner(owner, receiptId)
            runCatching { scheduler?.schedule() }
        } catch (error: Throwable) {
            activeSubmissions -= owner to receiptId
            throw error
        }
    }

    suspend fun bindPaymentRequest(
        owner: SessionFence,
        receipt: FinancialCreationReceipt,
        result: PaymentRequestDto,
    ): FinancialCreationReceipt {
        val intent = receipt.intent as? FinancialCreationIntent.PaymentRequest
            ?: error("This receipt is not a payment request")
        validateCreatedPaymentRequest(
            result,
            intent.destinationWalletId,
            intent.peerUserId,
            intent.amount,
            intent.currencyCode,
            intent.currencyScale,
            intent.note,
        )
        val event = KitPaymentMessage(
            KitPaymentAction.REQUEST,
            result.id,
            intent.amountMinor,
            intent.currencyCode,
            intent.currencyScale,
            result.note?.takeIf(String::isNotBlank)?.take(KitPaymentMessage.MAX_NOTE_LENGTH),
        )
        return bind(owner, receipt, event.encode(), event.deterministicMessageId())
    }

    suspend fun bindPaymentRequest(
        owner: SessionFence,
        receipt: FinancialCreationReceipt,
        result: ChatPaymentRequest,
    ): FinancialCreationReceipt {
        val intent = receipt.intent as? FinancialCreationIntent.PaymentRequest
            ?: error("This receipt is not a payment request")
        check(
            FinancialCreationReceipt.CANONICAL_UUID.matches(result.id.lowercase()) &&
                result.amountMinor == intent.amountMinor &&
                result.currencyCode == intent.currencyCode &&
                result.currencyScale == intent.currencyScale && result.note == intent.note
        ) { "The created payment request changed its submitted intent" }
        val event = KitPaymentMessage(
            KitPaymentAction.REQUEST,
            result.id,
            result.amountMinor,
            result.currencyCode,
            result.currencyScale,
            result.note?.takeIf(String::isNotBlank)?.take(KitPaymentMessage.MAX_NOTE_LENGTH),
        )
        return bind(owner, receipt, event.encode(), event.deterministicMessageId())
    }

    suspend fun bindGroupPayment(
        owner: SessionFence,
        receipt: FinancialCreationReceipt,
        result: GroupPaymentSummary,
    ): FinancialCreationReceipt {
        val intent = receipt.intent as? FinancialCreationIntent.GroupPayment
            ?: error("This receipt is not a group payment")
        check(result.conversationId.equals(receipt.conversationId, ignoreCase = true)) {
            "The recovered group payment belongs to another conversation"
        }
        check(result.splitMode == intent.request.splitMode && result.audience == intent.request.audience) {
            "The recovered group payment changed its split"
        }
        validateRecoveredGroupPayment(intent.request, result)
        val event = checkNotNull(
            KitGroupPaymentMessage.announcing(result, result.recipients.mapNotNull { it.userId }),
        ) { "The recovered group payment cannot be announced" }
        check(event.matchesAuthoritativePayment(result))
        return bind(owner, receipt, event.encode(), event.announcementMessageId())
    }

    suspend fun bindGroupRequestContribution(
        owner: SessionFence,
        receipt: FinancialCreationReceipt,
        result: GroupPaymentRequestContributionResultDto,
    ): FinancialCreationReceipt {
        val intent = receipt.intent as? FinancialCreationIntent.GroupRequestContribution
            ?: error("This receipt is not a group-request contribution")
        check(result.request.id == intent.requestId && result.isStructurallyValid()) {
            "The recovered contribution is invalid"
        }
        check(result.contribution.amount == intent.amount && result.contribution.isYours) {
            "The recovered contribution changed its intent"
        }
        val event = checkNotNull(
            KitGroupPaymentRequestMessage.create(
                KitGroupPaymentRequestAction.CONTRIBUTED,
                result.request.id,
                result.contribution.id,
                result.contribution.amountMinor.toLong(),
            ),
        )
        return bind(owner, receipt, event.encode(), event.deterministicMessageId())
    }

    suspend fun handoff(owner: SessionFence, receipt: FinancialCreationReceipt) {
        check(receipt.phase == FinancialCreationReceiptPhase.SETTLED)
        val descriptor = checkNotNull(receipt.descriptor)
        val messageId = checkNotNull(receipt.clientMessageId)
        when (receipt.intent) {
            is FinancialCreationIntent.PaymentRequest ->
                chats.capturePaymentEventForOwner(owner, receipt.conversationId, descriptor, messageId)
            is FinancialCreationIntent.GroupPayment ->
                chats.captureGroupPaymentEventForOwner(owner, receipt.conversationId, descriptor, messageId)
            is FinancialCreationIntent.GroupRequestContribution ->
                chats.captureGroupPaymentRequestEventForOwner(
                    owner,
                    receipt.conversationId,
                    descriptor,
                    messageId,
                )
        }
        store.completeForOwner(owner, receipt.id, messageId)
        runCatching { scheduler?.schedule() }
    }

    suspend fun discardPrepared(owner: SessionFence, receiptId: String) {
        try {
            store.discardPreparedForOwner(owner, receiptId)
        } finally {
            if (activeSubmissions.remove(owner to receiptId)) {
                runCatching { scheduler?.schedule() }
            }
        }
    }

    /** Retires a staged creation after its authoritative preflight proved no POST was attempted. */
    suspend fun discardNotSubmitted(owner: SessionFence, receiptId: String) {
        try {
            store.discardNotCommittedForOwner(owner, receiptId)
        } finally {
            if (activeSubmissions.remove(owner to receiptId)) {
                runCatching { scheduler?.schedule() }
            }
        }
    }

    suspend fun recover(): FinancialCreationRecoveryOutcome {
        val batch = store.recoveryBatchForCurrentOwner()
            ?: return FinancialCreationRecoveryOutcome.IDLE
        if (batch.receipts.isEmpty()) return FinancialCreationRecoveryOutcome.IDLE
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
                    FinancialCreationReceiptPhase.PREPARED -> continue
                    FinancialCreationReceiptPhase.SETTLED -> stored
                    FinancialCreationReceiptPhase.SUBMITTED -> when (
                        val result = recoverSubmitted(batch.owner, stored)
                    ) {
                        RecoveryResult.InProgress -> {
                            retry = true
                            continue
                        }
                        RecoveryResult.NotCommitted -> {
                            store.discardNotCommittedForOwner(batch.owner, stored.id)
                            progressed = true
                            continue
                        }
                        is RecoveryResult.Settled -> result.receipt
                    }
                }
                handoff(batch.owner, receipt)
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
            retry || batch.receipts.size == FinancialCreationReceiptStore.MAX_RECOVERY_BATCH ->
                FinancialCreationRecoveryOutcome.RETRY
            progressed -> FinancialCreationRecoveryOutcome.COMMITTED
            else -> FinancialCreationRecoveryOutcome.IDLE
        }
    }

    private suspend fun recoverSubmitted(
        owner: SessionFence,
        receipt: FinancialCreationReceipt,
    ): RecoveryResult {
        sessions.withCurrentSession(owner) { }
        return try {
            val settled = when (val intent = receipt.intent) {
                is FinancialCreationIntent.PaymentRequest -> bindPaymentRequest(
                    owner,
                    receipt,
                    apiCalls.executeExactRecovery {
                        api.recoverPaymentRequestCreation(
                            receipt.idempotencyKey,
                            CreatePaymentRequestDto(
                                intent.destinationWalletId,
                                intent.peerUserId,
                                intent.amount,
                                intent.note,
                            ),
                            owner,
                        )
                    },
                )
                is FinancialCreationIntent.GroupPayment -> bindGroupPayment(
                    owner,
                    receipt,
                    apiCalls.executeExactRecovery {
                        api.recoverGroupPayment(
                            receipt.conversationId,
                            receipt.idempotencyKey,
                            intent.request,
                            owner,
                        )
                    }.toUiModel() ?: error("Recovered group payment is invalid"),
                )
                is FinancialCreationIntent.GroupRequestContribution -> {
                    val authority = apiCalls.execute {
                        api.groupPaymentRequest(intent.requestId, owner)
                    }
                    val result = apiCalls.executeExactRecovery {
                        api.recoverGroupPaymentRequestContribution(
                            intent.requestId,
                            receipt.idempotencyKey,
                            ContributeGroupPaymentRequest(intent.sourceWalletId, intent.amount),
                            owner,
                        )
                    }
                    check(result.matchesContributionIntent(authority, intent.amount)) {
                        "Recovered contribution does not match its request"
                    }
                    bindGroupRequestContribution(owner, receipt, result)
                }
            }
            RecoveryResult.Settled(settled)
        } catch (error: KitWalletApiException) {
            when {
                error.statusCode == 409 && error.code == "IDEMPOTENCY_REQUEST_IN_PROGRESS" ->
                    RecoveryResult.InProgress
                error.statusCode == 404 && error.code == notFoundCode(receipt.intent) ->
                    RecoveryResult.NotCommitted
                else -> throw error
            }
        }
    }

    private suspend fun bind(
        owner: SessionFence,
        receipt: FinancialCreationReceipt,
        descriptor: String,
        messageId: String,
    ): FinancialCreationReceipt {
        val settled = store.bindSettledForOwner(
            owner,
            receipt.id,
            descriptor,
            messageId,
        )
        if (activeSubmissions.remove(owner to receipt.id)) {
            runCatching { scheduler?.schedule() }
        }
        return settled
    }

    private fun validateRecoveredGroupPayment(
        request: CreateGroupPaymentRequest,
        result: GroupPaymentSummary,
    ) {
        check(result.note == request.note) {
            "The recovered group payment changed its note"
        }
        check(
            result.recipientCount in 1..FinancialCreationReceipt.MAX_GROUP_RECIPIENTS &&
                result.recipients.size == result.recipientCount
        ) { "The recovered group payment returned an incomplete recipient roster" }
        val recovered = result.recipients.associateBy { recipient ->
            checkNotNull(recipient.userId).lowercase()
        }
        check(recovered.size == result.recipients.size) {
            "The recovered group payment returned duplicate recipients"
        }
        request.recipients?.let { submitted ->
            check(recovered.keys == submitted.mapTo(mutableSetOf()) { it.userId.lowercase() }) {
                "The recovered group payment changed its recipients"
            }
        }
        when (request.splitMode) {
            "even" -> {
                val submittedTotal = DecimalMoney.toMinor(
                    checkNotNull(request.totalAmount),
                    result.currencyScale,
                )
                check(submittedTotal > 0L && result.totalAmountMinor == submittedTotal) {
                    "The recovered group payment changed its total"
                }
                val shares = recovered.values.map { checkNotNull(it.amountMinor) }
                check(shares.all { it > 0L } && shares.sumExact() == submittedTotal) {
                    "The recovered group payment returned invalid even shares"
                }
                check((shares.maxOrNull() ?: 0L) - (shares.minOrNull() ?: 0L) <= 1L) {
                    "The recovered group payment was not divided evenly"
                }
            }
            "custom" -> {
                val submitted = checkNotNull(request.recipients).associate { recipient ->
                    recipient.userId.lowercase() to DecimalMoney.toMinor(
                        checkNotNull(recipient.amount),
                        result.currencyScale,
                    )
                }
                val recoveredAmounts = recovered.mapValues { (_, recipient) ->
                    checkNotNull(recipient.amountMinor)
                }
                check(submitted.values.all { it > 0L } && recoveredAmounts == submitted) {
                    "The recovered group payment changed its custom amounts"
                }
                result.totalAmountMinor?.let { total ->
                    check(total == submitted.values.sumExact()) {
                        "The recovered group payment changed its custom total"
                    }
                }
            }
        }
    }

    private fun Collection<Long>.sumExact(): Long = fold(0L) { total, value ->
        Math.addExact(total, value)
    }

    private fun notFoundCode(intent: FinancialCreationIntent): String = when (intent) {
        is FinancialCreationIntent.PaymentRequest -> "PAYMENT_REQUEST_RECOVERY_NOT_FOUND"
        is FinancialCreationIntent.GroupPayment -> "GROUP_PAYMENT_RECOVERY_NOT_FOUND"
        is FinancialCreationIntent.GroupRequestContribution ->
            "GROUP_PAYMENT_REQUEST_CONTRIBUTION_RECOVERY_NOT_FOUND"
    }

    private sealed interface RecoveryResult {
        data object InProgress : RecoveryResult
        data object NotCommitted : RecoveryResult
        data class Settled(val receipt: FinancialCreationReceipt) : RecoveryResult
    }
}
