package com.kit.wallet.data.repository

import android.content.Context
import com.kit.wallet.data.remote.AbuseReportConsentDto
import com.kit.wallet.data.remote.AbuseReportSelectedMessageDto
import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.CreateAbuseReportRequestDto
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.session.SessionInvalidatedException
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.ui.model.ChatPreview
import com.kit.wallet.ui.model.ChatMember
import com.kit.wallet.ui.model.DeliveryState
import com.kit.wallet.ui.model.Message
import com.kit.wallet.ui.model.MessageKind
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

object AbuseReportContract {
    const val MAXIMUM_SELECTED_MESSAGES = 5
    const val MAXIMUM_PRESENTED_MESSAGES = 50
    const val MAXIMUM_MESSAGE_CHARACTERS = 4_000
    const val MAXIMUM_SELECTED_MESSAGE_BYTES = 12_000
    const val MAXIMUM_NOTE_CHARACTERS = 1_000

    fun canonicalUuid(value: String?): String? {
        val trimmed = value?.trim().orEmpty()
        return runCatching { UUID.fromString(trimmed).toString() }.getOrNull()
    }

    fun validIdempotencyKey(value: String): Boolean =
        value.length in 16..128 && IDEMPOTENCY_KEY.matches(value)

    fun limitedNote(value: String): String {
        val end = value.offsetByCodePoints(0, value.codePointCount(0, value.length)
            .coerceAtMost(MAXIMUM_NOTE_CHARACTERS))
        return value.substring(0, end)
    }

    private val IDEMPOTENCY_KEY = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{15,127}")
}

data class AbuseReportContext(
    val currentUserId: String,
    val conversationId: String,
    val participantUserIds: Set<String>,
) {
    companion object {
        /** Uses only the authenticated conversation roster; a display name never grants access. */
        fun create(
            currentUserId: String?,
            chat: ChatPreview,
            groupMembers: List<ChatMember> = emptyList(),
        ): AbuseReportContext? {
            val current = AbuseReportContract.canonicalUuid(currentUserId) ?: return null
            val conversation = AbuseReportContract.canonicalUuid(chat.id) ?: return null
            val participants = if (chat.isGroup) {
                val canonical = groupMembers.mapNotNull {
                    AbuseReportContract.canonicalUuid(it.userId)
                }
                val authenticatedViewer = groupMembers.singleOrNull(ChatMember::isSelf)
                    ?.userId?.let(AbuseReportContract::canonicalUuid)
                if (canonical.size != groupMembers.size || canonical.toSet().size != canonical.size ||
                    current !in canonical || authenticatedViewer != current
                ) return null
                canonical.toSet()
            } else {
                val peer = AbuseReportContract.canonicalUuid(chat.peerUserId) ?: return null
                if (current == peer) return null
                setOf(current, peer)
            }
            if (participants.size < 2) return null
            return AbuseReportContext(current, conversation, participants)
        }
    }
}

enum class AbuseReportTargetType(val wireValue: String) {
    ACCOUNT("account"),
    MESSAGE("message"),
}

sealed class AbuseReportTarget(
    val type: AbuseReportTargetType,
    val reportedUserId: String,
) {
    init {
        require(AbuseReportContract.canonicalUuid(reportedUserId) == reportedUserId)
    }

    data class AccountTarget(val accountUserId: String) :
        AbuseReportTarget(AbuseReportTargetType.ACCOUNT, accountUserId)

    data class MessageTarget(
        val canonicalMessageId: String,
        val senderUserId: String,
    ) : AbuseReportTarget(AbuseReportTargetType.MESSAGE, senderUserId) {
        init {
            require(AbuseReportContract.canonicalUuid(canonicalMessageId) == canonicalMessageId) {
                "A report target must use a canonical server message ID"
            }
        }
    }

    val messageId: String?
        get() = (this as? MessageTarget)?.canonicalMessageId
}

enum class AbuseReportReason(val wireValue: String, val title: String) {
    SPAM("spam", "Spam"),
    SCAM_OR_FRAUD("scam_or_fraud", "Scam or fraud"),
    HARASSMENT_OR_BULLYING("harassment_or_bullying", "Harassment or bullying"),
    HATE_SPEECH("hate_speech", "Hate speech"),
    CREDIBLE_THREAT("credible_threat", "Threats or violence"),
    SEXUAL_CONTENT("sexual_content", "Sexual content"),
    CHILD_SAFETY("child_safety", "Child safety"),
    SELF_HARM("self_harm", "Self-harm"),
    IMPERSONATION("impersonation", "Impersonation"),
    ILLEGAL_ACTIVITY("illegal_activity", "Illegal activity"),
    PRIVACY_VIOLATION("privacy_violation", "Privacy violation"),
    OTHER("other", "Something else"),
    ;

    companion object {
        fun fromWire(value: String?): AbuseReportReason? = entries.singleOrNull {
            it.wireValue == value
        }
    }
}

data class AbuseReportSelectedMessage(
    val messageId: String,
    val senderUserId: String,
    val plaintext: String,
) {
    init {
        require(AbuseReportContract.canonicalUuid(messageId) == messageId)
        require(AbuseReportContract.canonicalUuid(senderUserId) == senderUserId)
        require(plaintext.isNotBlank())
        require(plaintext.codePointCount(0, plaintext.length) <=
            AbuseReportContract.MAXIMUM_MESSAGE_CHARACTERS)
        require(plaintext.toByteArray(StandardCharsets.UTF_8).size <=
            AbuseReportContract.MAXIMUM_SELECTED_MESSAGE_BYTES)
    }
}

data class AbuseReportMessageCandidate(
    val messageId: String,
    val senderUserId: String,
    val plaintext: String,
    val fromMe: Boolean,
    val senderName: String?,
    val time: String,
    val sortEpochMillis: Long,
    val isReportTarget: Boolean,
) {
    val plaintextBytes: Int get() = plaintext.toByteArray(StandardCharsets.UTF_8).size
}

object AbuseReportSelectionPolicy {
    fun accountTarget(context: AbuseReportContext, chat: ChatPreview): AbuseReportTarget.AccountTarget? {
        if (chat.isGroup) return null
        val peer = AbuseReportContract.canonicalUuid(chat.peerUserId) ?: return null
        if (peer == context.currentUserId || peer !in context.participantUserIds) return null
        return AbuseReportTarget.AccountTarget(peer)
    }

    fun messageTarget(message: Message, context: AbuseReportContext): AbuseReportTarget.MessageTarget? {
        if (message.fromMe || message.state !in REPORTABLE_DELIVERY_STATES ||
            message.kind == MessageKind.CALL || message.kind == MessageKind.SYSTEM
        ) {
            return null
        }
        val messageId = AbuseReportContract.canonicalUuid(message.id) ?: return null
        val senderUserId = AbuseReportContract.canonicalUuid(message.senderUserId) ?: return null
        if (senderUserId == context.currentUserId || senderUserId !in context.participantUserIds) {
            return null
        }
        return AbuseReportTarget.MessageTarget(messageId, senderUserId)
    }

    fun candidates(
        messages: List<Message>,
        context: AbuseReportContext,
        target: AbuseReportTarget,
    ): List<AbuseReportMessageCandidate> {
        if (
            target.reportedUserId == context.currentUserId ||
            target.reportedUserId !in context.participantUserIds
        ) return emptyList()
        val targetMessageId = target.messageId
        val allowedSenders = setOf(context.currentUserId, target.reportedUserId)
        val seen = mutableSetOf<String>()
        val candidates = messages.asSequence()
            .filter { it.kind == MessageKind.TEXT }
            .filter { it.state in REPORTABLE_DELIVERY_STATES }
            .mapNotNull { message ->
                val id = AbuseReportContract.canonicalUuid(message.id) ?: return@mapNotNull null
                val sender = AbuseReportContract.canonicalUuid(message.senderUserId)
                    ?: return@mapNotNull null
                val text = message.text
                if (
                    sender !in context.participantUserIds || sender !in allowedSenders ||
                    message.fromMe != (sender == context.currentUserId) ||
                    (id == targetMessageId && sender != target.reportedUserId) ||
                    !seen.add(id) || text.isBlank() ||
                    text.codePointCount(0, text.length) >
                    AbuseReportContract.MAXIMUM_MESSAGE_CHARACTERS ||
                    text.toByteArray(StandardCharsets.UTF_8).size >
                    AbuseReportContract.MAXIMUM_SELECTED_MESSAGE_BYTES
                ) return@mapNotNull null
                AbuseReportMessageCandidate(
                    messageId = id,
                    senderUserId = sender,
                    plaintext = text,
                    fromMe = message.fromMe,
                    senderName = message.senderName,
                    time = message.time,
                    sortEpochMillis = message.sortEpochMillis,
                    isReportTarget = id == targetMessageId && sender == target.reportedUserId,
                )
            }
            .sortedWith(
                compareByDescending<AbuseReportMessageCandidate> { it.sortEpochMillis }
                    .thenByDescending { it.messageId },
            )
            .toMutableList()

        val targetIndex = candidates.indexOfFirst { it.isReportTarget }
        if (targetIndex > 0) candidates.add(0, candidates.removeAt(targetIndex))
        return candidates.take(AbuseReportContract.MAXIMUM_PRESENTED_MESSAGES)
    }

    fun canSelect(
        candidate: AbuseReportMessageCandidate,
        selectedIds: Set<String>,
        candidates: List<AbuseReportMessageCandidate>,
    ): Boolean {
        if (candidate.messageId in selectedIds) return true
        if (selectedIds.size >= AbuseReportContract.MAXIMUM_SELECTED_MESSAGES) return false
        val selectedBytes = candidates.asSequence()
            .filter { it.messageId in selectedIds }
            .sumOf(AbuseReportMessageCandidate::plaintextBytes)
        return selectedBytes + candidate.plaintextBytes <=
            AbuseReportContract.MAXIMUM_SELECTED_MESSAGE_BYTES
    }

    fun selectedPayloads(
        selectedIds: Set<String>,
        candidates: List<AbuseReportMessageCandidate>,
    ): List<AbuseReportSelectedMessage> {
        val selected = candidates.filter { it.messageId in selectedIds }
        require(selected.size == selectedIds.size)
        require(selected.size <= AbuseReportContract.MAXIMUM_SELECTED_MESSAGES)
        require(selected.sumOf(AbuseReportMessageCandidate::plaintextBytes) <=
            AbuseReportContract.MAXIMUM_SELECTED_MESSAGE_BYTES)
        return selected.sortedWith(
            compareBy<AbuseReportMessageCandidate> { it.sortEpochMillis }
                .thenBy { it.messageId },
        ).map { AbuseReportSelectedMessage(it.messageId, it.senderUserId, it.plaintext) }
    }

    private val REPORTABLE_DELIVERY_STATES = setOf(
        DeliveryState.SENT,
        DeliveryState.DELIVERED,
        DeliveryState.READ,
    )
}

data class AbuseReportRequest(
    val reporterUserId: String,
    val targetType: AbuseReportTargetType,
    val reportedUserId: String,
    val conversationId: String,
    val messageId: String?,
    val reason: AbuseReportReason,
    val reporterNote: String?,
    val selectedMessages: List<AbuseReportSelectedMessage>,
) {
    init {
        require(AbuseReportContract.canonicalUuid(reporterUserId) == reporterUserId)
        require(AbuseReportContract.canonicalUuid(reportedUserId) == reportedUserId)
        require(AbuseReportContract.canonicalUuid(conversationId) == conversationId)
        require(reporterUserId != reportedUserId)
        require((targetType == AbuseReportTargetType.MESSAGE) == (messageId != null))
        messageId?.let { require(AbuseReportContract.canonicalUuid(it) == it) }
        require((reporterNote?.codePointCount(0, reporterNote.length) ?: 0) <=
            AbuseReportContract.MAXIMUM_NOTE_CHARACTERS)
        require(selectedMessages.size <= AbuseReportContract.MAXIMUM_SELECTED_MESSAGES)
        require(selectedMessages.map { it.messageId }.toSet().size == selectedMessages.size)
        require(selectedMessages.all {
            it.senderUserId == reporterUserId || it.senderUserId == reportedUserId
        })
        messageId?.let { targetMessageId ->
            require(selectedMessages.none {
                it.messageId == targetMessageId && it.senderUserId != reportedUserId
            })
        }
        require(selectedMessages.sumOf {
            it.plaintext.toByteArray(StandardCharsets.UTF_8).size
        } <= AbuseReportContract.MAXIMUM_SELECTED_MESSAGE_BYTES)
    }

    fun fingerprint(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun include(value: String?) {
            if (value == null) {
                digest.update(ByteBuffer.allocate(4).putInt(-1).array())
                return
            }
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        include("kit-pay-abuse-report-request-v1")
        include(reporterUserId)
        include(targetType.wireValue)
        include(reportedUserId)
        include(conversationId)
        include(messageId)
        include(reason.wireValue)
        include(reporterNote)
        selectedMessages.forEach {
            include(it.messageId)
            include(it.senderUserId)
            include(it.plaintext)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    internal fun toDto() = CreateAbuseReportRequestDto(
        targetType = targetType.wireValue,
        reportedUserId = reportedUserId,
        conversationId = conversationId,
        messageId = messageId,
        reasonCode = reason.wireValue,
        reporterNote = reporterNote,
        selectedMessages = selectedMessages.takeIf(List<*>::isNotEmpty)?.map {
            AbuseReportSelectedMessageDto(it.messageId, it.plaintext)
        },
        consent = AbuseReportConsentDto(
            shareReportWithModerators = true,
            shareSelectedMessagePlaintext = selectedMessages.isNotEmpty(),
        ),
    )

    companion object {
        fun create(
            context: AbuseReportContext,
            target: AbuseReportTarget,
            reason: AbuseReportReason,
            reporterNote: String?,
            selectedMessages: List<AbuseReportSelectedMessage>,
            shareSelectedMessagePlaintext: Boolean,
        ): AbuseReportRequest {
            val trimmedNote = reporterNote?.trim()?.takeIf(String::isNotEmpty)
            require(shareSelectedMessagePlaintext == selectedMessages.isNotEmpty())
            require(target.reportedUserId != context.currentUserId)
            require(target.reportedUserId in context.participantUserIds)
            return AbuseReportRequest(
                reporterUserId = context.currentUserId,
                targetType = target.type,
                reportedUserId = target.reportedUserId,
                conversationId = context.conversationId,
                messageId = target.messageId,
                reason = reason,
                reporterNote = trimmedNote,
                selectedMessages = selectedMessages,
            )
        }
    }
}

data class AbuseReportReceipt(
    val id: String,
    val targetType: AbuseReportTargetType,
    val reason: AbuseReportReason,
    val conversationId: String,
    val messageId: String?,
    val selectedMessageCount: Int,
    val submittedAt: Instant,
)

interface AbuseReportingRepository {
    suspend fun submit(request: AbuseReportRequest, idempotencyKey: String): AbuseReportReceipt
}

@Singleton
class RemoteAbuseReportingRepository @Inject constructor(
    private val api: KitWalletApi,
    private val apiCalls: ApiCallExecutor,
    private val sessions: SessionStore,
) : AbuseReportingRepository {
    override suspend fun submit(
        request: AbuseReportRequest,
        idempotencyKey: String,
    ): AbuseReportReceipt {
        require(AbuseReportContract.validIdempotencyKey(idempotencyKey))
        val owner = sessions.current()?.fence() ?: throw SessionInvalidatedException()
        if (owner.accountId != request.reporterUserId) throw SessionInvalidatedException()
        val response = apiCalls.execute {
            api.submitAbuseReport(idempotencyKey, request.toDto(), owner)
        }
        sessions.withCurrentSession(owner) { }
        val id = AbuseReportContract.canonicalUuid(response.id)
        val targetType = AbuseReportTargetType.entries.singleOrNull {
            it.wireValue == response.targetType
        }
        val reason = AbuseReportReason.fromWire(response.reasonCode)
        val conversationId = AbuseReportContract.canonicalUuid(response.conversationId)
        val messageId = response.messageId?.let(AbuseReportContract::canonicalUuid)
        val count = response.selectedMessageCount
        val submittedAt = response.submittedAt?.let {
            runCatching { Instant.parse(it) }.getOrNull()
        }
        check(
            id != null && response.status == "received" &&
                targetType == request.targetType && reason == request.reason &&
                conversationId == request.conversationId &&
                (response.messageId == null || messageId != null) &&
                messageId == request.messageId && count == request.selectedMessages.size &&
                count in 0..AbuseReportContract.MAXIMUM_SELECTED_MESSAGES && submittedAt != null,
        ) { "The abuse-report receipt did not confirm the submitted report" }
        return AbuseReportReceipt(
            id = id,
            targetType = checkNotNull(targetType),
            reason = checkNotNull(reason),
            conversationId = conversationId,
            messageId = messageId,
            selectedMessageCount = count,
            submittedAt = submittedAt,
        )
    }
}

interface AbuseReportAttemptStore {
    fun keyFor(accountId: String, requestFingerprint: String): String
    fun complete(accountId: String, requestFingerprint: String, idempotencyKey: String)
}

/**
 * Persists only one-way request/account fingerprints and the replay key, never report text.
 * Writing synchronously before the POST means a timeout or process restart can replay safely.
 */
@Singleton
class SharedPreferencesAbuseReportAttemptStore @Inject constructor(
    @ApplicationContext context: Context,
) : AbuseReportAttemptStore {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val lock = Any()

    override fun keyFor(accountId: String, requestFingerprint: String): String = synchronized(lock) {
        val owner = digest(accountId)
        val existing = preferences.takeIf {
            it.getString(OWNER, null) == owner &&
                it.getString(FINGERPRINT, null) == requestFingerprint
        }?.getString(KEY, null)
        if (existing != null && AbuseReportContract.validIdempotencyKey(existing)) return existing
        val created = "android-abuse-report-${UUID.randomUUID()}"
        check(preferences.edit()
            .putString(OWNER, owner)
            .putString(FINGERPRINT, requestFingerprint)
            .putString(KEY, created)
            .commit()) { "Could not persist the abuse-report retry key" }
        created
    }

    override fun complete(
        accountId: String,
        requestFingerprint: String,
        idempotencyKey: String,
    ) = synchronized(lock) {
        if (preferences.getString(OWNER, null) == digest(accountId) &&
            preferences.getString(FINGERPRINT, null) == requestFingerprint &&
            preferences.getString(KEY, null) == idempotencyKey
        ) {
            check(preferences.edit().clear().commit()) {
                "Could not clear the completed abuse-report retry key"
            }
        }
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        const val PREFERENCES = "kit_abuse_report_attempt_v1"
        const val OWNER = "owner_sha256"
        const val FINGERPRINT = "request_sha256"
        const val KEY = "idempotency_key"
    }
}
