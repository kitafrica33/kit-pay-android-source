package com.kit.wallet

import com.kit.wallet.data.contacts.AccountScopedContactDiscoveryLedger
import com.kit.wallet.data.contacts.contactDiscoveryPreferenceKey
import com.kit.wallet.data.repository.contactDiscoveryUploadAllowed
import com.kit.wallet.data.session.SessionFence
import com.kit.wallet.feature.settings.contactDiscoveryToggleChecked
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactDiscoveryConsentTest {
    @Test
    fun `account A consent never becomes account B consent`() {
        val stored = mutableMapOf<String, Boolean>()
        val ledger = ledger(stored)

        assertTrue(ledger.set("account-a", true))

        assertTrue(ledger.allowed("account-a"))
        assertFalse(ledger.allowed("account-b"))
        assertFalse(ledger.allowed(null))
        assertNotEquals(
            contactDiscoveryPreferenceKey("account-a"),
            contactDiscoveryPreferenceKey("account-b"),
        )
    }

    @Test
    fun `disable invalidates an in-flight authorization even after re-enable`() {
        val stored = mutableMapOf<String, Boolean>()
        val ledger = ledger(stored)
        val fence = SessionFence("session-a", "scope-a", "account-a")
        assertTrue(ledger.set("account-a", true))
        val first = ledger.authorization("account-a", fence)
        assertNotNull(first)

        assertTrue(ledger.set("account-a", false))
        assertFalse(ledger.isCurrent("account-a", requireNotNull(first)))
        assertNull(ledger.authorization("account-a", fence))

        assertTrue(ledger.set("account-a", true))
        assertFalse(ledger.isCurrent("account-a", first))
        assertNotNull(ledger.authorization("account-a", fence))
    }

    @Test
    fun `unknown or malformed account identity fails closed`() {
        val stored = mutableMapOf<String, Boolean>()
        val ledger = ledger(stored)

        listOf(null, "", "  ", "bad\naccount", "x".repeat(257)).forEach { accountId ->
            assertFalse(ledger.set(accountId, true))
            assertFalse(ledger.allowed(accountId))
            assertNull(contactDiscoveryPreferenceKey(accountId))
        }
        assertTrue(stored.isEmpty())
    }

    @Test
    fun `permission revocation closes upload and visible toggle immediately`() {
        val stored = mutableMapOf<String, Boolean>()
        val ledger = ledger(stored)
        val fence = SessionFence("session-a", "scope-a", "account-a")
        ledger.set("account-a", true)
        val authorization = requireNotNull(ledger.authorization("account-a", fence))

        assertTrue(contactDiscoveryUploadAllowed(true, authorization))
        assertFalse(contactDiscoveryUploadAllowed(false, authorization))
        assertFalse(contactDiscoveryUploadAllowed(true, null))
        assertTrue(contactDiscoveryToggleChecked(true, true, true))
        assertFalse(contactDiscoveryToggleChecked(true, true, false))
        assertFalse(contactDiscoveryToggleChecked(true, false, true))
    }

    private fun ledger(stored: MutableMap<String, Boolean>) =
        AccountScopedContactDiscoveryLedger(
            read = stored::get,
            write = { key, value ->
                stored[key] = value
                true
            },
        )
}
