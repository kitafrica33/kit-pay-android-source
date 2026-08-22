package com.kit.wallet

import com.kit.wallet.feature.contacts.orderContactsForDisplay
import com.kit.wallet.ui.model.Contact
import org.junit.Assert.assertEquals
import org.junit.Test

class ContactDisplayOrderingTest {
    @Test
    fun `Kit Pay members appear before invite-only contacts then sort by name`() {
        val ordered = orderContactsForDisplay(
            listOf(
                contact("Amina", isKitUser = false),
                contact("zoe", isKitUser = true),
                contact("Brian", isKitUser = false),
                contact("alice", isKitUser = true),
            ),
        )

        assertEquals(listOf("alice", "zoe", "Amina", "Brian"), ordered.map(Contact::name))
    }

    private fun contact(name: String, isKitUser: Boolean) = Contact(
        id = name,
        name = name,
        phone = "+256700000000",
        isKitUser = isKitUser,
    )
}
