package com.kit.wallet.data.repository

import com.kit.wallet.data.media.isTrustedProfileAvatarUrl
import com.kit.wallet.data.remote.CallDto
import com.kit.wallet.ui.model.AccountVerification
import com.kit.wallet.ui.model.Contact

/** Local, viewer-specific presentation for a Kit Pay call participant. */
internal data class CallPresentation(
    val name: String,
    val phone: String? = null,
    val avatarUrl: String? = null,
    val accountVerification: AccountVerification? = null,
)

/**
 * Resolves call participants against the current user's address book before falling back to the
 * registered name returned by the server. A participant UUID is never suitable display text.
 */
internal fun resolveCallPresentation(
    serverName: String?,
    participantUserIds: List<String>,
    contacts: List<Contact>,
    participants: List<CallParticipantIdentity> = emptyList(),
): CallPresentation {
    val contactsById = contacts
        .asSequence()
        .filter { it.isKitUser && it.id.isNotBlank() }
        .associateBy { it.id.lowercase() }
    val participantIds = (participantUserIds + participants.map(CallParticipantIdentity::userId))
        .asSequence()
        .map(String::trim)
        .filter(CANONICAL_USER_ID::matches)
        .distinctBy(String::lowercase)
        .toList()
    val participantsById = participants.associateBy { it.userId.lowercase() }
    val localNames = participantIds
        .mapNotNull { userId ->
            contactsById[userId.lowercase()]?.name.safeCallDisplayText()
                ?: participantsById[userId.lowercase()]?.name.safeCallDisplayText()
        }
        .distinct()
    val singleId = participantIds.singleOrNull()?.lowercase()
    val matched = singleId?.let(contactsById::get)
    val firstSighting = singleId?.let(participantsById::get)

    return CallPresentation(
        name = localNames.takeIf(List<String>::isNotEmpty)?.joinToString(", ")
            ?: serverName.toCallDisplayName(),
        phone = matched?.phone?.trim()?.takeIf(String::isNotEmpty),
        avatarUrl = matched?.avatarUrl?.trim()?.takeIf(::isTrustedProfileAvatarUrl)
            ?: firstSighting?.avatarUrl,
        // A multi-party call has no single account identity for its aggregate avatar. For a
        // one-to-one call, carry only the exact designation attached to the matched public ID.
        accountVerification = matched?.accountVerification ?: firstSighting?.accountVerification,
    )
}

/**
 * Merges the legacy ID list with the richer participant rows without allowing presentation data
 * to float onto a different account. Invalid IDs are discarded; malformed optional metadata
 * becomes null and cannot create a badge or an off-origin image fetch.
 */
internal fun CallDto.toCallParticipantIdentities(
    additionalUserIds: List<String> = emptyList(),
): List<CallParticipantIdentity> {
    val richById = linkedMapOf<String, CallParticipantIdentity>()
    participants.orEmpty().forEach { nullable ->
        val row = nullable ?: return@forEach
        val userId = canonicalCallUserId(row.userId) ?: return@forEach
        richById.putIfAbsent(
            userId,
            CallParticipantIdentity(
                userId = userId,
                name = row.name.safeCallDisplayText(),
                avatarUrl = row.avatarUrl?.trim()?.takeIf(::isTrustedProfileAvatarUrl),
                accountVerification = AccountVerification.fromServerValues(
                    row.verification?.designation,
                    row.verification?.since,
                ),
            ),
        )
    }
    val orderedIds = (participantUserIds.orEmpty() + richById.keys + additionalUserIds)
        .mapNotNull(::canonicalCallUserId)
        .distinctBy(String::lowercase)
    return orderedIds.map { userId ->
        richById[userId] ?: CallParticipantIdentity(userId = userId)
    }
}

/** Initial active-call label that deliberately converts an unresolved UUID to neutral copy. */
internal fun initialCallPresentation(target: String?, contacts: List<Contact>): CallPresentation {
    val value = target?.trim().orEmpty()
    return if (CANONICAL_USER_ID.matches(value)) {
        resolveCallPresentation(serverName = null, participantUserIds = listOf(value), contacts)
    } else {
        CallPresentation(value.toCallDisplayName())
    }
}

/**
 * Resolves LiveKit's `public-user-uuid:device-id` identity without displaying either UUID.
 *
 * Returns the whole presentation rather than just the name: a participant whose camera is off is
 * drawn as their avatar, and an avatar wants the photo as much as it wants the initials.
 */
internal fun resolveRoomParticipant(
    identity: String?,
    serverName: String?,
    contacts: List<Contact>,
    participants: List<CallParticipantIdentity> = emptyList(),
): CallPresentation {
    val publicUserId = identity
        ?.substringBefore(':')
        ?.trim()
        ?.takeIf(CANONICAL_USER_ID::matches)
    return resolveCallPresentation(
        serverName = serverName,
        participantUserIds = listOfNotNull(publicUserId),
        contacts = contacts,
        participants = participants,
    )
}

internal fun resolveRoomParticipantName(
    identity: String?,
    serverName: String?,
    contacts: List<Contact>,
    participants: List<CallParticipantIdentity> = emptyList(),
): String = resolveRoomParticipant(identity, serverName, contacts, participants).name

internal fun canonicalCallUserId(value: String?): String? = value
    ?.trim()
    ?.takeIf(CANONICAL_USER_ID::matches)
    ?.lowercase()

internal fun String?.toCallDisplayName(): String =
    this.safeCallDisplayText() ?: DEFAULT_CALL_DISPLAY_NAME

private fun String?.safeCallDisplayText(): String? = this
    ?.filterNot(Char::isISOControl)
    ?.trim()
    ?.take(MAX_CALL_DISPLAY_NAME_LENGTH)
    ?.takeIf(String::isNotBlank)
    ?.takeUnless(CANONICAL_USER_ID::matches)

private const val DEFAULT_CALL_DISPLAY_NAME = "Kit Pay contact"
private const val MAX_CALL_DISPLAY_NAME_LENGTH = 160
private val CANONICAL_USER_ID = Regex(
    "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-" +
        "[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$",
)
