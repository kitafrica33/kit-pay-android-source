package com.kit.wallet

import com.kit.wallet.data.messaging.MEMBERSHIP_ADDED_EVENT
import com.kit.wallet.data.messaging.MEMBERSHIP_REMOVED_EVENT
import com.kit.wallet.data.messaging.MEMBERSHIP_ROLE_CHANGED_EVENT
import com.kit.wallet.data.repository.conversationSystemMessageText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The copy a group's timeline puts on a membership change.
 *
 * The rule these all serve is that the line may never claim more than the sync event does: it says
 * who the change was about, never who made it, and never whether a departure was a choice.
 */
class ConversationSystemMessageTest {
    @Test fun `joining names the viewer, the member, or nobody`() {
        assertEquals("You joined this group", text(MEMBERSHIP_ADDED_EVENT, isViewer = true))
        assertEquals("Amina joined this group", text(MEMBERSHIP_ADDED_EVENT, name = "Amina"))
        assertEquals("Someone joined this group", text(MEMBERSHIP_ADDED_EVENT))
        // An unnamed subject still gets a line: the group really did change.
        assertEquals("Someone joined this group", text(MEMBERSHIP_ADDED_EVENT, name = "   "))
    }

    @Test fun `a departure commits to neither leaving nor being removed`() {
        // Both arrive as the same event, so a line that picked one would be wrong half the time.
        assertEquals(
            "You are no longer in this group",
            text(MEMBERSHIP_REMOVED_EVENT, isViewer = true),
        )
        assertEquals(
            "Brian is no longer in this group",
            text(MEMBERSHIP_REMOVED_EVENT, name = "Brian"),
        )
        assertEquals("Someone is no longer in this group", text(MEMBERSHIP_REMOVED_EVENT))
    }

    @Test fun `role changes read as what the member now is`() {
        assertEquals(
            "You are now an owner of this group",
            text(MEMBERSHIP_ROLE_CHANGED_EVENT, role = "owner", isViewer = true),
        )
        assertEquals(
            "Amina is now an owner of this group",
            text(MEMBERSHIP_ROLE_CHANGED_EVENT, role = "owner", name = "Amina"),
        )
        assertEquals("You are now an admin", text(MEMBERSHIP_ROLE_CHANGED_EVENT, role = "admin", isViewer = true))
        assertEquals("Amina is now an admin", text(MEMBERSHIP_ROLE_CHANGED_EVENT, role = "admin", name = "Amina"))
        assertEquals(
            "You are no longer an admin",
            text(MEMBERSHIP_ROLE_CHANGED_EVENT, role = "member", isViewer = true),
        )
        assertEquals(
            "Amina is no longer an admin",
            text(MEMBERSHIP_ROLE_CHANGED_EVENT, role = "member", name = "Amina"),
        )
    }

    @Test fun `an unnamed role change says what changed without naming anyone`() {
        assertEquals("This group has another owner", text(MEMBERSHIP_ROLE_CHANGED_EVENT, role = "owner"))
        assertEquals("This group has another admin", text(MEMBERSHIP_ROLE_CHANGED_EVENT, role = "admin"))
        assertEquals("This group has one fewer admin", text(MEMBERSHIP_ROLE_CHANGED_EVENT, role = "member"))
    }

    @Test fun `nothing worth saying produces no line at all`() {
        // The wire accepts "moderator" and this build has no rights for it, so it renders nothing
        // rather than a line a reader would have to guess at.
        assertNull(text(MEMBERSHIP_ROLE_CHANGED_EVENT, role = "moderator", name = "Amina"))
        assertNull(text(MEMBERSHIP_ROLE_CHANGED_EVENT, role = null, name = "Amina"))
        assertNull(text("conversation.created", name = "Amina"))
        assertNull(text("membership.reinstated", isViewer = true))
    }

    @Test fun `the viewer is always the viewer, whatever the address book calls them`() {
        assertEquals(
            "You joined this group",
            text(MEMBERSHIP_ADDED_EVENT, name = "Amina", isViewer = true),
        )
    }

    private fun text(
        type: String,
        role: String? = null,
        name: String? = null,
        isViewer: Boolean = false,
    ): String? = conversationSystemMessageText(
        type = type,
        role = role,
        name = name,
        isViewer = isViewer,
    )
}
