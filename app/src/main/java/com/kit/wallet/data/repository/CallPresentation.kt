package com.kit.wallet.data.repository

import com.kit.wallet.ui.model.Contact

/** Local, viewer-specific presentation for a Kit Pay call participant. */
internal data class CallPresentation(
    val name: String,
    val phone: String? = null,
)

/**
 * Resolves call participants against the current user's address book before falling back to the
 * registered name returned by the server. A participant UUID is never suitable display text.
 */
internal fun resolveCallPresentation(
    serverName: String?,
    participantUserIds: List<String>,
    contacts: List<Contact>,
): CallPresentation {
    val contactsById = contacts
        .asSequence()
        .filter { it.isKitUser && it.id.isNotBlank() }
        .associateBy { it.id.lowercase() }
    val matched = participantUserIds
        .asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinctBy(String::lowercase)
        .mapNotNull { contactsById[it.lowercase()] }
        .toList()
    val localNames = matched
        .mapNotNull { it.name.safeCallDisplayText() }
        .distinct()

    return CallPresentation(
        name = localNames.takeIf(List<String>::isNotEmpty)?.joinToString(", ")
            ?: serverName.toCallDisplayName(),
        phone = matched.singleOrNull()?.phone?.trim()?.takeIf(String::isNotEmpty),
    )
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

/** Resolves LiveKit's `public-user-uuid:device-id` identity without displaying either UUID. */
internal fun resolveRoomParticipantName(
    identity: String?,
    serverName: String?,
    contacts: List<Contact>,
): String {
    val publicUserId = identity
        ?.substringBefore(':')
        ?.trim()
        ?.takeIf(CANONICAL_USER_ID::matches)
    return resolveCallPresentation(
        serverName = serverName,
        participantUserIds = listOfNotNull(publicUserId),
        contacts = contacts,
    ).name
}

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
