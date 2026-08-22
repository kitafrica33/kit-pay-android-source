package com.kit.wallet.feature.calls

import com.kit.wallet.ui.model.Contact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DirectCallChatContactTest {
    private val amina = Contact("user-a", "Amina", "+256700000001", isKitUser = true)
    private val brian = Contact("user-b", "Brian", "+256700000002", isKitUser = true)

    @Test fun `one matched Kit participant can open a direct chat`() {
        val source = ActiveCallContactPresentationSource(null, null, listOf("USER-A"))
        assertEquals(amina, directCallChatContact(source, listOf(amina, brian)))
    }

    @Test fun `group and unmatched calls do not guess a direct chat`() {
        assertNull(
            directCallChatContact(
                ActiveCallContactPresentationSource(null, null, listOf("user-a", "user-b")),
                listOf(amina, brian),
            ),
        )
        assertNull(
            directCallChatContact(
                ActiveCallContactPresentationSource(null, null, listOf("unknown")),
                listOf(amina),
            ),
        )
    }
}
