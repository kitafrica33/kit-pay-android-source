package com.kit.wallet

import com.kit.wallet.feature.chat.canLeaveGroup
import com.kit.wallet.feature.chat.canEditGroupPhoto
import com.kit.wallet.feature.chat.groupMemberActions
import com.kit.wallet.feature.chat.groupMemberCountLabel
import com.kit.wallet.feature.chat.groupTypingLabel
import com.kit.wallet.ui.model.ChatMember
import com.kit.wallet.ui.model.ChatMemberRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The group presentation rules, which mirror the server's own membership rules so the UI never
 * offers an action that would be refused.
 */
class GroupChatPresentationTest {
    private fun member(
        id: String,
        role: ChatMemberRole = ChatMemberRole.MEMBER,
        isSelf: Boolean = false,
    ) = ChatMember(userId = id, name = id.replaceFirstChar(Char::uppercase), role = role, isSelf = isSelf)

    @Test
    fun `typing label names one, two, then counts the rest`() {
        assertNull(groupTypingLabel(emptyList()))
        assertEquals("Aisha is typing…", groupTypingLabel(listOf("Aisha")))
        assertEquals("Aisha and Brian are typing…", groupTypingLabel(listOf("Aisha", "Brian")))
        assertEquals(
            "Aisha and 2 others are typing…",
            groupTypingLabel(listOf("Aisha", "Brian", "Grace")),
        )
    }

    @Test
    fun `a typist with no usable name is left out rather than shown blank`() {
        assertNull(groupTypingLabel(listOf("", "   ")))
        assertEquals("Aisha is typing…", groupTypingLabel(listOf("Aisha", " ")))
    }

    @Test
    fun `member count reads as a sentence at one and at many`() {
        assertEquals("1 participant", groupMemberCountLabel(1))
        assertEquals("7 participants", groupMemberCountLabel(7))
    }

    @Test
    fun `a plain member is offered nothing at all`() {
        val viewer = member("me", ChatMemberRole.MEMBER, isSelf = true)

        assertFalse(groupMemberActions(viewer, member("brian")).any)
        assertFalse(groupMemberActions(viewer, member("grace", ChatMemberRole.ADMIN)).any)
        assertFalse(canEditGroupPhoto(viewer))
    }

    @Test
    fun `only owners and admins may edit the group photo`() {
        assertFalse(canEditGroupPhoto(null))
        assertFalse(canEditGroupPhoto(member("me", ChatMemberRole.MEMBER, isSelf = true)))
        assertTrue(canEditGroupPhoto(member("me", ChatMemberRole.ADMIN, isSelf = true)))
        assertTrue(canEditGroupPhoto(member("me", ChatMemberRole.OWNER, isSelf = true)))
    }

    @Test
    fun `an admin may only remove ordinary members`() {
        val viewer = member("me", ChatMemberRole.ADMIN, isSelf = true)

        val onMember = groupMemberActions(viewer, member("brian"))
        assertTrue(onMember.canRemove)
        assertFalse(onMember.canPromote)
        assertFalse(onMember.canMakeOwner)

        // Only an owner may act on another admin or on an owner, so an admin is offered neither.
        assertFalse(groupMemberActions(viewer, member("grace", ChatMemberRole.ADMIN)).any)
        assertFalse(groupMemberActions(viewer, member("ama", ChatMemberRole.OWNER)).any)
    }

    @Test
    fun `an owner may manage another owner while preserving its own ownership`() {
        val viewer = member("me", ChatMemberRole.OWNER, isSelf = true)

        val onMember = groupMemberActions(viewer, member("brian"))
        assertTrue(onMember.canPromote)
        assertTrue(onMember.canMakeOwner)
        assertTrue(onMember.canRemove)
        assertFalse(onMember.canDemote)

        val onAdmin = groupMemberActions(viewer, member("grace", ChatMemberRole.ADMIN))
        assertTrue(onAdmin.canDemote)
        assertTrue(onAdmin.canMakeOwner)
        assertTrue(onAdmin.canRemove)
        assertFalse(onAdmin.canPromote)

        val onOwner = groupMemberActions(viewer, member("ama", ChatMemberRole.OWNER))
        assertTrue(onOwner.canDemote)
        assertTrue(onOwner.canRemove)
        assertFalse(onOwner.canPromote)
        assertFalse(onOwner.canMakeOwner)
    }

    @Test
    fun `nobody is offered moderation of their own row`() {
        val viewer = member("me", ChatMemberRole.OWNER, isSelf = true)

        assertFalse(groupMemberActions(viewer, viewer).any)
        // Same account arriving as a second row with a different case still gets nothing.
        assertFalse(groupMemberActions(viewer, member("ME", ChatMemberRole.OWNER)).any)
    }

    @Test
    fun `a roster with no viewer offers nothing and cannot be left`() {
        val roster = listOf(member("brian"), member("grace", ChatMemberRole.OWNER))

        assertFalse(groupMemberActions(null, member("brian")).any)
        assertFalse(canLeaveGroup(roster))
    }

    @Test
    fun `the last owner can leave because the server hands ownership on`() {
        val alone = listOf(
            member("me", ChatMemberRole.OWNER, isSelf = true),
            member("brian", ChatMemberRole.ADMIN),
        )
        assertTrue(canLeaveGroup(alone))

        val shared = alone + member("grace", ChatMemberRole.OWNER)
        assertTrue(canLeaveGroup(shared))

        assertTrue(canLeaveGroup(listOf(member("me", ChatMemberRole.OWNER, isSelf = true))))
    }

    @Test
    fun `everybody who is not the last owner may leave`() {
        assertTrue(
            canLeaveGroup(
                listOf(
                    member("me", ChatMemberRole.ADMIN, isSelf = true),
                    member("grace", ChatMemberRole.OWNER),
                ),
            ),
        )
        assertTrue(
            canLeaveGroup(
                listOf(
                    member("me", ChatMemberRole.MEMBER, isSelf = true),
                    member("grace", ChatMemberRole.OWNER),
                ),
            ),
        )
    }
}
