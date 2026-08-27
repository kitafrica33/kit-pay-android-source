package com.kit.wallet.feature.chat

import com.kit.wallet.ui.model.ChatMember
import com.kit.wallet.ui.model.ChatMemberRole

/**
 * The presentation rules a group needs and a direct chat does not: who is typing by name, what a
 * participant list may offer on each row, and whether this account can walk out of the group.
 *
 * Every rule here mirrors one the server enforces. The point is not to be the check — the server
 * is, and a refusal still surfaces — but to never offer an action that is going to be refused.
 */

/**
 * "Aisha is typing…", and who else.
 *
 * Null when nobody nameable is typing, which covers a direct chat (whose one possible typist is
 * already named at the top of the screen) and a group whose typist this device has no name for.
 * The bubble is drawn from the boolean either way; this only decides whether it is labelled.
 */
internal fun groupTypingLabel(names: List<String>): String? {
    val named = names.filter(String::isNotBlank)
    return when (named.size) {
        0 -> null
        1 -> "${named[0]} is typing…"
        2 -> "${named[0]} and ${named[1]} are typing…"
        else -> "${named[0]} and ${named.size - 1} others are typing…"
    }
}

/** The count line under a group's name. */
internal fun groupMemberCountLabel(count: Int): String =
    if (count == 1) "1 participant" else "$count participants"

/** Only the same roles the server accepts may open photo-changing controls. */
internal fun canEditGroupPhoto(viewer: ChatMember?): Boolean =
    viewer?.role?.canManageMembers == true

/** What the account looking at a participant list may do to one of its rows. */
internal data class GroupMemberActions(
    val canPromote: Boolean = false,
    val canDemote: Boolean = false,
    val canMakeOwner: Boolean = false,
    val canRemove: Boolean = false,
) {
    val any: Boolean get() = canPromote || canDemote || canMakeOwner || canRemove
}

/**
 * The actions [viewer] may take on [member], mirroring the server's own membership rules:
 *
 * - managing anybody at all takes an owner or an admin;
 * - a row for this account offers nothing — leaving is its own action, not moderation;
 * - only an owner may promote to admin, or change or remove an owner or another admin;
 * - another owner may demote or remove an owner because the acting owner keeps the group owned.
 */
internal fun groupMemberActions(viewer: ChatMember?, member: ChatMember): GroupMemberActions {
    val viewerRole = viewer?.role ?: return GroupMemberActions()
    if (!viewerRole.canManageMembers) return GroupMemberActions()
    if (member.isSelf || member.userId.equals(viewer.userId, ignoreCase = true)) {
        return GroupMemberActions()
    }
    val owner = viewerRole == ChatMemberRole.OWNER
    return when (member.role) {
        ChatMemberRole.OWNER -> GroupMemberActions(
            canDemote = owner,
            canRemove = owner,
        )
        ChatMemberRole.ADMIN -> GroupMemberActions(
            canDemote = owner,
            canMakeOwner = owner,
            canRemove = owner,
        )
        ChatMemberRole.MEMBER -> GroupMemberActions(
            canPromote = owner,
            canMakeOwner = owner,
            canRemove = true,
        )
    }
}

/**
 * Whether this account can leave the group as it currently stands.
 *
 * Every active member can leave. If the departing member is the last owner, the server performs
 * a deterministic ownership handoff before removal; an otherwise-empty group needs no successor.
 * This safety action deliberately remains available while feature gates are dark.
 */
internal fun canLeaveGroup(members: List<ChatMember>): Boolean {
    return members.any(ChatMember::isSelf)
}
