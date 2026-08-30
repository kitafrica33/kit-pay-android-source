package com.kit.wallet.feature.chat

import androidx.lifecycle.SavedStateHandle
import com.kit.wallet.data.messaging.ScheduledSend
import com.kit.wallet.data.remote.CreateScheduledPaymentRequest
import com.kit.wallet.data.remote.CurrencyDto
import com.kit.wallet.data.remote.ScheduledGroupPaymentPlanDto
import com.kit.wallet.data.remote.ScheduledGroupPlanRecipientDto
import com.kit.wallet.data.remote.ScheduledGroupStepUpDto
import com.kit.wallet.data.remote.ScheduledGroupStepUpIntentDto
import com.kit.wallet.data.session.SessionFence
import com.kit.wallet.data.session.SessionInvalidatedException
import java.math.BigDecimal
import java.time.Instant

internal enum class PendingServerSchedulePhase { PREPARED, SUBMITTED }

internal sealed interface PendingServerSchedule {
    val chatId: String
    val idempotencyKey: String
    val phase: PendingServerSchedulePhase

    data class Direct(
        override val chatId: String,
        override val idempotencyKey: String,
        override val phase: PendingServerSchedulePhase,
        val request: CreateScheduledPaymentRequest,
        val currencyCode: String,
        val currencyScale: Int,
        val amountMinor: Long,
        val recipientName: String,
    ) : PendingServerSchedule

    data class Group(
        override val chatId: String,
        override val idempotencyKey: String,
        override val phase: PendingServerSchedulePhase,
        val plan: ScheduledGroupPaymentPlanDto,
        val amountMinor: Long,
        val recipientNames: List<String>,
    ) : PendingServerSchedule

    fun preview(): ServerSchedulePreview = when (this) {
        is Direct -> ServerSchedulePreview(
            amountMinor = amountMinor,
            note = request.note,
            scheduledAtEpochMillis = Instant.parse(request.scheduledFor).toEpochMilli(),
            currencyCode = currencyCode,
            currencyScale = currencyScale,
            recipientNames = listOf(recipientName),
        )
        is Group -> ServerSchedulePreview(
            amountMinor = amountMinor,
            note = plan.note,
            scheduledAtEpochMillis = Instant.parse(plan.scheduledFor).toEpochMilli(),
            currencyCode = plan.currency.code,
            currencyScale = plan.currency.scale.toInt(),
            recipientNames = recipientNames,
        )
    }
}

/** One bounded, process-restorable server schedule operation per conversation ViewModel. */
internal class PendingServerScheduleStore(
    private val state: SavedStateHandle,
    private val currentOwner: () -> SessionFence?,
) {
    private data class Stored(
        val owner: SessionFence,
        val operation: PendingServerSchedule,
    )

    private var pending: Stored? = state.get<ArrayList<String>>(STATE_KEY)
        ?.let { encoded -> runCatching { decode(encoded) }.getOrNull() }
        ?.takeIf { it.owner == currentOwner() }
        .also { restored ->
            if (restored == null) state.remove<ArrayList<String>>(STATE_KEY)
        }

    fun current(chatId: String): PendingServerSchedule? = ownedPending()?.operation?.also {
        check(it.chatId == chatId.lowercase()) { "A pending schedule belongs to another chat" }
    }

    /** Re-proves ownership immediately before any approval or network work begins. */
    fun requireOwner(expected: PendingServerSchedule): SessionFence {
        val stored = ownedPending() ?: throw SessionInvalidatedException()
        check(stored.operation.phase == expected.phase &&
            stored.operation.idempotencyKey == expected.idempotencyKey &&
            sameIntent(stored.operation, expected)
        ) { "The pending scheduled payment changed before approval" }
        return stored.owner
    }

    /**
     * Restores only an operation that is still safe to present. A submitted operation keeps its
     * immutable key even after its time passes because replay is the only safe way to resolve an
     * ambiguous POST; a merely reviewed operation cannot start once its schedule or plan expires.
     */
    fun restore(chatId: String, nowEpochMillis: Long): PendingServerSchedule? {
        val operation = current(chatId) ?: return null
        if (operation.phase == PendingServerSchedulePhase.SUBMITTED) return operation
        val fresh = when (operation) {
            is PendingServerSchedule.Direct -> ScheduledSend.schedulingError(
                Instant.parse(operation.request.scheduledFor).toEpochMilli(),
                nowEpochMillis,
            ) == null
            is PendingServerSchedule.Group ->
                operation.plan.isStructurallyValid(Instant.ofEpochMilli(nowEpochMillis))
        }
        if (!fresh) {
            discardPrepared()
            return null
        }
        return operation
    }

    fun stage(
        candidate: PendingServerSchedule,
        expectedOwner: SessionFence = currentOwner() ?: throw SessionInvalidatedException(),
    ): PendingServerSchedule {
        validate(candidate)
        if (currentOwner() != expectedOwner) {
            ownedPending()
            throw SessionInvalidatedException()
        }
        ownedPending()?.operation?.let { existing ->
            check(sameIntent(existing, candidate)) {
                "Resolve or dismiss the pending scheduled payment before changing it"
            }
            return existing
        }
        return candidate.withPhase(PendingServerSchedulePhase.PREPARED).also {
            pending = Stored(expectedOwner, it)
            persist()
        }
    }

    /** Persist the ambiguity boundary before step-up or the create POST starts. */
    fun markSubmitted(expected: PendingServerSchedule): PendingServerSchedule {
        val stored = ownedPending() ?: throw SessionInvalidatedException()
        val current = stored.operation
        check(current.phase == expected.phase && current.idempotencyKey == expected.idempotencyKey &&
            sameIntent(current, expected)
        ) {
            "The pending scheduled payment changed before approval"
        }
        return current.withPhase(PendingServerSchedulePhase.SUBMITTED).also {
            pending = stored.copy(operation = it)
            persist()
        }
    }

    fun complete(expected: PendingServerSchedule) {
        val current = ownedPending()?.operation ?: throw SessionInvalidatedException()
        check(current.idempotencyKey == expected.idempotencyKey && sameIntent(current, expected)) {
            "Another scheduled payment is still pending"
        }
        pending = null
        persist()
    }

    /** A reviewed-only operation is safe to discard; a submitted one may already exist remotely. */
    fun discardPrepared() {
        if (ownedPending()?.operation?.phase == PendingServerSchedulePhase.PREPARED) {
            pending = null
            persist()
        }
    }

    internal fun snapshot(): List<String>? = ownedPending()?.let(::encode)

    private fun ownedPending(): Stored? {
        val stored = pending ?: return null
        if (stored.owner != currentOwner()) {
            pending = null
            state.remove<ArrayList<String>>(STATE_KEY)
            return null
        }
        return stored
    }

    private fun persist() {
        val encoded = snapshot()
        if (encoded == null) state.remove<ArrayList<String>>(STATE_KEY)
        else state[STATE_KEY] = ArrayList(encoded)
    }

    private fun sameIntent(
        first: PendingServerSchedule,
        second: PendingServerSchedule,
    ): Boolean = when {
        first is PendingServerSchedule.Direct && second is PendingServerSchedule.Direct ->
            first.chatId == second.chatId && first.request == second.request &&
                first.currencyCode == second.currencyCode &&
                first.currencyScale == second.currencyScale &&
                first.amountMinor == second.amountMinor
        first is PendingServerSchedule.Group && second is PendingServerSchedule.Group ->
            first.chatId == second.chatId && first.plan == second.plan &&
                first.amountMinor == second.amountMinor
        else -> false
    }

    private fun PendingServerSchedule.withPhase(
        next: PendingServerSchedulePhase,
    ): PendingServerSchedule = when (this) {
        is PendingServerSchedule.Direct -> copy(phase = next)
        is PendingServerSchedule.Group -> copy(phase = next)
    }

    private fun validate(operation: PendingServerSchedule) {
        check(UUID.matches(operation.chatId) && RETRY_KEY.matches(operation.idempotencyKey)) {
            "Invalid pending scheduled payment identity"
        }
        when (operation) {
            is PendingServerSchedule.Direct -> {
                val request = operation.request
                check(request.conversationId == operation.chatId && UUID.matches(request.sourceWalletId) &&
                    UUID.matches(request.destinationWalletId) &&
                    request.sourceWalletId != request.destinationWalletId &&
                    CURRENCY.matches(operation.currencyCode) && operation.currencyScale in 0..6 &&
                    operation.amountMinor > 0L && operation.recipientName.isNotBlank() &&
                    operation.recipientName.length <= MAX_NAME_LENGTH &&
                    (request.note?.length ?: 0) <= MAX_NOTE_LENGTH &&
                    runCatching { Instant.parse(request.scheduledFor) }.isSuccess &&
                    runCatching {
                        BigDecimal(request.amount).movePointRight(operation.currencyScale)
                            .longValueExact() == operation.amountMinor &&
                            BigDecimal.valueOf(operation.amountMinor, operation.currencyScale)
                                .toPlainString() == request.amount
                    }.getOrDefault(false)
                ) { "Invalid pending direct schedule" }
            }
            is PendingServerSchedule.Group -> {
                val scale = operation.plan.currency.scale.toIntOrNull()
                check(operation.plan.conversationId == operation.chatId && scale != null &&
                    operation.amountMinor > 0L && operation.recipientNames.size ==
                    operation.plan.recipientCount && operation.recipientNames.all {
                        it.isNotBlank() && it.length <= MAX_NAME_LENGTH
                    } && operation.plan.isStructurallyValid(Instant.EPOCH) &&
                    runCatching {
                        BigDecimal(operation.plan.totalAmount).movePointRight(scale)
                            .longValueExact() == operation.amountMinor
                    }.getOrDefault(false)
                ) { "Invalid pending group schedule" }
            }
        }
    }

    private fun encode(stored: Stored): List<String> = buildList {
        val operation = stored.operation
        add(VERSION)
        add(stored.owner.sessionId)
        add(stored.owner.cacheScopeId)
        addNullable(stored.owner.accountId)
        add(if (operation is PendingServerSchedule.Direct) DIRECT else GROUP)
        add(operation.phase.name)
        add(operation.chatId)
        add(operation.idempotencyKey)
        when (operation) {
            is PendingServerSchedule.Direct -> {
                add(operation.request.sourceWalletId)
                add(operation.request.destinationWalletId)
                add(operation.request.amount)
                addNullable(operation.request.note)
                add(operation.request.scheduledFor)
                add(operation.currencyCode)
                add(operation.currencyScale.toString())
                add(operation.amountMinor.toString())
                add(operation.recipientName)
            }
            is PendingServerSchedule.Group -> {
                add(operation.amountMinor.toString())
                add(operation.recipientNames.size.toString())
                addAll(operation.recipientNames)
                val plan = operation.plan
                add(plan.planId)
                add(plan.conversationId)
                add(plan.sourceWalletId)
                add(plan.splitMode)
                add(plan.audience)
                add(plan.totalAmount)
                add(plan.currency.code)
                add(plan.currency.scale)
                addNullable(plan.note)
                add(plan.recipientCount.toString())
                plan.recipients.forEach { recipient ->
                    add(recipient.userId)
                    add(recipient.destinationWalletId)
                    add(recipient.amount)
                }
                add(plan.rosterFingerprint)
                add(plan.frozenRecipients)
                add(plan.planHash)
                add(plan.scheduledFor)
                add(plan.expiresAt)
            }
        }
    }

    private fun MutableList<String>.addNullable(value: String?) {
        add(if (value == null) NULL else VALUE)
        add(value.orEmpty())
    }

    private fun decode(encoded: List<String>): Stored {
        check(encoded.size <= MAX_FIELDS) { "Saved scheduled payment is too large" }
        val reader = FieldReader(encoded)
        check(reader.take() == VERSION) { "Unknown saved scheduled payment version" }
        val owner = SessionFence(
            sessionId = reader.take(),
            cacheScopeId = reader.take(),
            accountId = reader.takeNullable(),
        )
        check(owner.sessionId.isNotBlank() && owner.cacheScopeId.isNotBlank() &&
            owner.accountId?.isNotBlank() != false
        ) { "Invalid saved scheduled payment owner" }
        val type = reader.take()
        val phase = runCatching { PendingServerSchedulePhase.valueOf(reader.take()) }
            .getOrElse { error("Invalid saved scheduled payment phase") }
        val chatId = reader.take()
        val key = reader.take()
        val decoded = when (type) {
            DIRECT -> {
                val request = CreateScheduledPaymentRequest(
                    sourceWalletId = reader.take(),
                    destinationWalletId = reader.take(),
                    amount = reader.take(),
                    note = reader.takeNullable(),
                    scheduledFor = reader.take(),
                    conversationId = chatId,
                )
                PendingServerSchedule.Direct(
                    chatId = chatId,
                    idempotencyKey = key,
                    phase = phase,
                    request = request,
                    currencyCode = reader.take(),
                    currencyScale = reader.takeInt(),
                    amountMinor = reader.takeLong(),
                    recipientName = reader.take(),
                )
            }
            GROUP -> {
                val amountMinor = reader.takeLong()
                val names = reader.takeStrings(MAX_RECIPIENTS)
                val planId = reader.take()
                val conversationId = reader.take()
                val sourceWalletId = reader.take()
                val splitMode = reader.take()
                val audience = reader.take()
                val totalAmount = reader.take()
                val currency = CurrencyDto(reader.take(), reader.take())
                val note = reader.takeNullable()
                val recipients = reader.takeRecipients()
                val rosterFingerprint = reader.take()
                val frozenRecipients = reader.take()
                val planHash = reader.take()
                val scheduledFor = reader.take()
                val expiresAt = reader.take()
                val intent = ScheduledGroupStepUpIntentDto(
                    action = "create",
                    planId = planId,
                    planHash = planHash,
                    conversationId = conversationId,
                    sourceWalletId = sourceWalletId,
                    splitMode = splitMode,
                    audience = audience,
                    totalAmount = totalAmount,
                    currency = currency.code,
                    note = note,
                    scheduledFor = scheduledFor,
                    rosterFingerprint = rosterFingerprint,
                    frozenRecipients = frozenRecipients,
                )
                PendingServerSchedule.Group(
                    chatId = chatId,
                    idempotencyKey = key,
                    phase = phase,
                    plan = ScheduledGroupPaymentPlanDto(
                        planId = planId,
                        conversationId = conversationId,
                        sourceWalletId = sourceWalletId,
                        splitMode = splitMode,
                        audience = audience,
                        totalAmount = totalAmount,
                        currency = currency,
                        note = note,
                        recipientCount = recipients.size,
                        recipients = recipients,
                        rosterFingerprint = rosterFingerprint,
                        frozenRecipients = frozenRecipients,
                        planHash = planHash,
                        scheduledFor = scheduledFor,
                        expiresAt = expiresAt,
                        stepUp = ScheduledGroupStepUpDto(
                            ScheduledGroupPaymentPlanDto.PURPOSE,
                            intent,
                        ),
                    ),
                    amountMinor = amountMinor,
                    recipientNames = names,
                )
            }
            else -> error("Invalid saved scheduled payment type")
        }
        reader.requireFinished()
        validate(decoded)
        return Stored(owner, decoded)
    }

    private class FieldReader(private val fields: List<String>) {
        private var index = 0

        fun take(): String = fields.getOrNull(index++)
            ?: error("Truncated saved scheduled payment")

        fun takeInt(): Int = take().toIntOrNull()
            ?: error("Invalid saved scheduled payment number")

        fun takeLong(): Long = take().toLongOrNull()
            ?: error("Invalid saved scheduled payment number")

        fun takeNullable(): String? = when (take()) {
            NULL -> take().also { check(it.isEmpty()) }
                .let { null }
            VALUE -> take()
            else -> error("Invalid saved scheduled payment null field")
        }

        fun takeStrings(max: Int): List<String> {
            val count = takeInt()
            check(count in 1..max) { "Invalid saved scheduled payment list" }
            return List(count) { take() }
        }

        fun takeRecipients(): List<ScheduledGroupPlanRecipientDto> {
            val count = takeInt()
            check(count in 1..MAX_RECIPIENTS) { "Invalid saved schedule recipients" }
            return List(count) {
                ScheduledGroupPlanRecipientDto(take(), take(), take())
            }
        }

        fun requireFinished() {
            check(index == fields.size) { "Unexpected saved scheduled payment fields" }
        }
    }

    private companion object {
        const val STATE_KEY = "pendingServerScheduleV1"
        const val VERSION = "2"
        const val DIRECT = "direct"
        const val GROUP = "group"
        const val NULL = "0"
        const val VALUE = "1"
        const val MAX_FIELDS = 256
        const val MAX_RECIPIENTS = 50
        const val MAX_NOTE_LENGTH = 280
        const val MAX_NAME_LENGTH = 200
        val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        val CURRENCY = Regex("^[A-Z]{3}$")
        val RETRY_KEY = Regex("^[A-Za-z0-9._:-]{16,128}$")
    }
}
