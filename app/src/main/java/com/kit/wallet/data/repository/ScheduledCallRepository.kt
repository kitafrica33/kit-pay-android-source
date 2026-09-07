package com.kit.wallet.data.repository

import com.kit.wallet.data.remote.*
import com.kit.wallet.data.session.SessionFence
import com.kit.wallet.data.session.SessionInvalidatedException
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.feature.calls.CallInviteLink
import com.kit.wallet.feature.calls.ScheduledCallPolicy
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

data class ScheduledCallFeatures(
    val calls: Boolean = false,
    val scheduling: Boolean = false,
    val inviteLinks: Boolean = false,
    val serverTime: Instant? = null,
)

@Singleton
class ScheduledCallRepository @Inject constructor(
    private val api: ScheduledCallsApi,
    private val apiCalls: ApiCallExecutor,
    private val sessions: SessionStore,
    private val contacts: ContactRepository,
) {
    fun currentOwner(): SessionFence = sessions.current()?.fence() ?: throw SessionInvalidatedException()

    suspend fun features(owner: SessionFence = currentOwner()): ScheduledCallFeatures = owned(owner) {
        val response = apiCalls.executeWithMeta { api.capabilities(owner) }
        val flags = response.data.features.orEmpty()
        val calls = flags["calls"] == true
        ScheduledCallFeatures(
            calls = calls,
            scheduling = calls && flags["calls_scheduling"] == true,
            inviteLinks = calls && flags["calls_invite_links"] == true,
            serverTime = response.meta?.serverTime?.let { runCatching { Instant.parse(it) }.getOrNull() },
        )
    }

    suspend fun list(owner: SessionFence, cursor: String? = null): ScheduledCallsPageDto = owned(owner) {
        requireScheduling(owner)
        require(cursor == null || (cursor.isNotBlank() && cursor.length <= 4096))
        apiCalls.execute { api.list(owner, cursor) }.also { page ->
            require(page.items.size <= 50) { "Invalid scheduled call response." }
            page.items.forEach(::validateSchedule)
        }
    }

    suspend fun create(request: CreateScheduledCallRequest, owner: SessionFence, retry: Boolean = false): ScheduledCallDto = owned(owner) {
        val capability = requireScheduling(owner)
        requireId(request.clientScheduleId)
        ScheduledCallPolicy.recipients(request.recipientUserIds, owner.accountId)
        require(request.type == "voice" || request.type == "video")
        ScheduledCallPolicy.title(request.title.orEmpty())
        val startsAt = Instant.parse(request.startsAt)
        // A retained identical command may already have succeeded before its response was lost.
        // The server checks its idempotency key before its fresh-create time admission.
        // The editor validates the user's selection against its server-time anchor. Never veto
        // that selection using a drifting device clock when discovery has no server timestamp.
        if (!retry && capability.serverTime != null) ScheduledCallPolicy.validateStart(startsAt, capability.serverTime)
        apiCalls.execute { api.create(request, owner) }.also {
            validateSchedule(it)
            require(it.clientScheduleId == request.clientScheduleId) { "Invalid scheduled call response." }
        }
    }

    suspend fun update(schedule: ScheduledCallDto, startsAt: String, title: String?, owner: SessionFence): ScheduledCallDto = owned(owner) {
        val capability = requireScheduling(owner)
        requireOrganizer(schedule, owner)
        val parsedStart = Instant.parse(startsAt)
        if (capability.serverTime != null) ScheduledCallPolicy.validateStart(parsedStart, capability.serverTime)
        val request = UpdateScheduledCallRequest(schedule.revision, startsAt, ScheduledCallPolicy.title(title.orEmpty()))
        apiCalls.execute { api.update(requireId(schedule.id), request, owner) }.also {
            validateSchedule(it, schedule.id)
        }
    }

    suspend fun cancel(schedule: ScheduledCallDto, owner: SessionFence): ScheduledCallDto = owned(owner) {
        requireScheduling(owner)
        requireOrganizer(schedule, owner)
        apiCalls.execute { api.cancel(requireId(schedule.id), ScheduledCallRevisionRequest(schedule.revision), owner) }
            .also { validateSchedule(it, schedule.id) }
    }

    suspend fun respond(schedule: ScheduledCallDto, response: String, owner: SessionFence): ScheduledCallDto = owned(owner) {
        requireScheduling(owner)
        require(response in setOf("accepted", "declined"))
        apiCalls.execute { api.respond(requireId(schedule.id), ScheduledCallResponseRequest(response), owner) }
            .also { validateSchedule(it, schedule.id) }
    }

    suspend fun shareSchedule(schedule: ScheduledCallDto, owner: SessionFence): CallInviteLinkDto = owned(owner) {
        check(requireInvites(owner).scheduling) { "Scheduled calls are not available for this account yet." }
        requireOrganizer(schedule, owner)
        apiCalls.execute { api.scheduleInvite(requireId(schedule.id), owner) }.also(::validateShare)
    }

    suspend fun shareCall(callId: String, owner: SessionFence = currentOwner()): CallInviteLinkDto = owned(owner) {
        requireInvites(owner)
        apiCalls.execute { api.callInvite(requireId(callId), owner) }.also(::validateShare)
    }

    suspend fun preview(token: String, owner: SessionFence = currentOwner()): CallInvitePreviewDto = owned(owner) {
        previewWithFeatures(token, owner, requireInvites(owner))
    }

    private suspend fun previewWithFeatures(token: String, owner: SessionFence, features: ScheduledCallFeatures): CallInvitePreviewDto = owned(owner) {
        requireNotNull(CallInviteLink.fromToken(token)) { "This call invitation is invalid." }
        apiCalls.execute { api.preview(token, owner) }.also { preview ->
            ScheduledCallPolicy.requireFreshExpiry(preview.expiresAt, preview.serverTime)
            when (preview.kind) {
                "call" -> validateInviteCall(requireNotNull(preview.call))
                "scheduled_call" -> {
                    check(features.scheduling) { "Scheduled calls are not available for this account yet." }
                    validateSchedule(requireNotNull(preview.scheduledCall))
                }
                else -> error("This invitation cannot be opened in this version of Kit Pay.")
            }
        }
    }

    /** Called only inside ActiveCallViewModel after permissions and call ownership admission. */
    suspend fun redeem(token: String, expectedOwner: SessionFence = currentOwner()): CallConnection = owned(expectedOwner) {
        val features = requireInvites(expectedOwner)
        requireNotNull(CallInviteLink.fromToken(token)) { "This call invitation is invalid." }
        // Re-inspect immediately before mutation: a revoked/expired link or a different account
        // cannot use an earlier preview as authority. POST remains authoritative for races.
        val inspected = previewWithFeatures(token, expectedOwner, features)
        val expectedCallId = inspected.call?.id ?: inspected.scheduledCall?.callId
        require(expectedCallId != null) { "This scheduled call has not started. Return to its invitation." }
        val redeemed = apiCalls.execute { api.redeem(token, RedeemCallInviteRequest(), expectedOwner) }
        require(redeemed.kind == "call") { "This scheduled call has not started yet." }
        val call = requireNotNull(redeemed.call) { "The call server returned an incomplete response." }
        val rtc = requireNotNull(redeemed.rtc) { "The call server returned an incomplete response." }
        validatedInviteConnection(call, rtc, redeemed.serverTime, expectedCallId, expectedOwner.accountId, contacts.contacts.value)
    }

    private suspend fun requireScheduling(owner: SessionFence): ScheduledCallFeatures = features(owner).also {
        check(it.scheduling) { "Scheduled calls are not available for this account yet." }
    }

    private suspend fun requireInvites(owner: SessionFence): ScheduledCallFeatures = features(owner).also {
        check(it.inviteLinks) { "Call invitations are not available for this account yet." }
    }

    private suspend fun <T> owned(owner: SessionFence, operation: suspend () -> T): T {
        sessions.withCurrentSession(owner) { }
        val result = operation()
        return sessions.withCurrentSession(owner) { result }
    }
}

private fun requireId(id: String): String = requireNotNull(ScheduledCallPolicy.canonicalId(id)) {
    "The call server returned an invalid identifier."
}

internal fun validateSchedule(schedule: ScheduledCallDto, expectedId: String? = null) {
    requireId(schedule.id)
    require(expectedId == null || schedule.id == expectedId) { "Invalid scheduled call response." }
    requireId(schedule.clientScheduleId)
    requireId(schedule.organizerUserId)
    schedule.callId?.let(::requireId)
    schedule.conversationId?.let(::requireId)
    require(schedule.type in setOf("voice", "video") && schedule.revision >= 1)
    require(schedule.status in setOf("scheduled", "queued", "started", "cancelled", "skipped"))
    require(schedule.participants.size <= 21)
    schedule.participants.forEach { requireId(it.userId) }
    Instant.parse(schedule.startsAt)
    Instant.parse(schedule.serverTime)
}

private fun requireOrganizer(schedule: ScheduledCallDto, owner: SessionFence) {
    require(schedule.organizerUserId.equals(owner.accountId, ignoreCase = true)) { "Only the organizer can change this call." }
    require(schedule.status == "scheduled") { "This scheduled call has already changed. Refresh to continue." }
}

private fun validateShare(link: CallInviteLinkDto) {
    requireId(link.id)
    val parsed = requireNotNull(CallInviteLink.fromDeepLink(link.shareUrl)) { "Invalid call invitation." }
    require(parsed.token == link.token) { "Invalid call invitation." }
    Instant.parse(link.expiresAt)
}

private fun validateInviteCall(call: CallDto) {
    requireId(call.id)
    require(call.type in setOf("voice", "video") && call.state in setOf("ringing", "active")) {
        "This call is no longer available."
    }
    call.participantUserIds.orEmpty().forEach(::requireId)
    call.participants.orEmpty().filterNotNull().forEach { requireId(requireNotNull(it.userId)) }
}

/** Mapping is independently testable; credentials never reach a room with mismatched call truth. */
internal fun validatedInviteConnection(
    call: CallDto,
    rtc: RtcCredentialsDto,
    serverTime: String,
    expectedCallId: String,
    ownId: String?,
    contacts: List<com.kit.wallet.ui.model.Contact> = emptyList(),
): CallConnection {
    validateInviteCall(call)
    require(requireId(call.id) == requireId(expectedCallId)) { "This call invitation has changed." }
    require(call.state == "active" && call.participantState == "joined" && call.isHeld == false) {
        "The call has not joined successfully."
    }
    require(rtc.provider == "livekit" && ScheduledCallPolicy.secureRtcUrl(rtc.url)) { "The call server returned an unsupported connection." }
    require(rtc.token.isNotBlank() && rtc.room.isNotBlank()) { "The call server returned incomplete credentials." }
    ScheduledCallPolicy.requireFreshExpiry(rtc.expiresAt, serverTime)
    val participants = call.toCallParticipantIdentities().filterNot { it.userId.equals(ownId, ignoreCase = true) }
    val ids = participants.map(CallParticipantIdentity::userId)
    val presentation = resolveCallPresentation(call.name, ids, contacts, participants)
    return CallConnection(
        callId = requireId(call.id), name = presentation.name, phone = presentation.phone,
        participantUserIds = ids, participants = participants, avatarUrl = presentation.avatarUrl,
        accountVerification = presentation.accountVerification, video = call.type == "video",
        provider = rtc.provider, url = rtc.url, token = rtc.token, room = rtc.room,
        ringExpiresAt = call.ringExpiresAt, ringServerTime = call.serverTime,
        answeredAt = call.answeredAt, serverTime = serverTime, conversationId = call.conversationId,
        canHold = call.canHold == true, holdRevision = call.holdRevision,
    )
}
