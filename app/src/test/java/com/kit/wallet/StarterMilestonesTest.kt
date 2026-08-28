package com.kit.wallet

import com.kit.wallet.feature.home.StarterMilestone
import com.kit.wallet.feature.home.StarterMilestones
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The durable milestone store makes claims that outlive the caches ("this account has
 * transacted before"), so these tests pin its three safety properties: facts are recorded
 * only when the disk write really happened, keys never name an account in plaintext, and
 * one account's markers are invisible to — and undeletable by — every other account.
 */
class StarterMilestonesTest {

    private val prefs = FakeSharedPreferences()
    private val store = StarterMilestones(
        prefsProvider = { prefs },
        ioDispatcher = Dispatchers.Unconfined,
    )

    @Test
    fun `a recorded milestone is durable and bumps the revision`() = runBlocking {
        assertFalse(store.recorded(StarterMilestone.FIRST_TRANSACTION, "account-a"))
        store.record(StarterMilestone.FIRST_TRANSACTION, "account-a")
        assertTrue(store.recorded(StarterMilestone.FIRST_TRANSACTION, "account-a"))
        assertEquals(1L, store.revision.value)
    }

    @Test
    fun `re-recording is a no-op, not a rewrite`() = runBlocking {
        store.record(StarterMilestone.FIRST_MESSAGE, "account-a")
        store.record(StarterMilestone.FIRST_MESSAGE, "account-a")
        assertEquals(1L, store.revision.value)
    }

    @Test
    fun `milestones are independent facts`() = runBlocking {
        store.record(StarterMilestone.FIRST_MESSAGE, "account-a")
        assertFalse(store.recorded(StarterMilestone.FIRST_TRANSACTION, "account-a"))
    }

    @Test
    fun `account ids are canonicalized before keying`() = runBlocking {
        store.record(StarterMilestone.FIRST_MESSAGE, "  Account-A ")
        assertTrue(store.recorded(StarterMilestone.FIRST_MESSAGE, "account-a"))
    }

    @Test
    fun `a blank or absent account id records nothing`() = runBlocking {
        store.record(StarterMilestone.FIRST_MESSAGE, null)
        store.record(StarterMilestone.FIRST_MESSAGE, "   ")
        assertEquals(0L, store.revision.value)
        assertFalse(store.recorded(StarterMilestone.FIRST_MESSAGE, null))
        assertTrue(prefs.values.isEmpty()) // not even the install secret was minted
    }

    // ---- fail closed when the disk says no ----

    @Test
    fun `a failed milestone commit records nothing and announces nothing`() = runBlocking {
        store.record(StarterMilestone.FIRST_MESSAGE, "account-a") // establishes the secret
        prefs.failCommits = true
        store.record(StarterMilestone.FIRST_TRANSACTION, "account-a")
        assertFalse(store.recorded(StarterMilestone.FIRST_TRANSACTION, "account-a"))
        assertEquals(1L, store.revision.value)
    }

    @Test
    fun `a secret that cannot persist means nothing is ever recorded`() = runBlocking {
        prefs.failCommits = true
        store.record(StarterMilestone.FIRST_MESSAGE, "account-a")
        assertFalse(store.recorded(StarterMilestone.FIRST_MESSAGE, "account-a"))
        assertEquals(0L, store.revision.value)
        assertTrue(prefs.values.isEmpty())
    }

    // ---- the preference file never names an account ----

    @Test
    fun `stored keys expose no account id, only keyed fingerprints`() = runBlocking {
        store.record(StarterMilestone.FIRST_MESSAGE, "Account-42")
        store.record(StarterMilestone.FIRST_TRANSACTION, "Account-42")
        val milestoneKeys = prefs.values.keys.filter { it != "install_secret_v1" }
        assertEquals(2, milestoneKeys.size)
        milestoneKeys.forEach { key ->
            assertFalse(key.contains("account-42", ignoreCase = true))
            assertTrue(
                "key must be keyword:hmac-sha256-hex, was $key",
                key.matches(Regex("^(first_message|first_transaction):[0-9a-f]{64}$")),
            )
        }
    }

    @Test
    fun `the same account fingerprints differently on a different install`() = runBlocking {
        val otherPrefs = FakeSharedPreferences()
        val otherInstall = StarterMilestones(
            prefsProvider = { otherPrefs },
            ioDispatcher = Dispatchers.Unconfined,
        )
        store.record(StarterMilestone.FIRST_MESSAGE, "account-a")
        otherInstall.record(StarterMilestone.FIRST_MESSAGE, "account-a")
        // A fresh install starts blank (no cross-device claims)…
        assertNotEquals(prefs.values.keys, otherPrefs.values.keys)
        // …and its keys share nothing with the first install's beyond the keyword prefix.
        assertEquals(
            emptySet<String>(),
            prefs.values.keys.intersect(otherPrefs.values.keys) - setOf("install_secret_v1"),
        )
    }

    @Test
    fun `the same owner signing back in re-derives the same markers`() = runBlocking {
        store.record(StarterMilestone.FIRST_TRANSACTION, "account-a")
        // Sign-out keeps the preference file; a new process gets a fresh instance over it.
        val afterRestart = StarterMilestones(
            prefsProvider = { prefs },
            ioDispatcher = Dispatchers.Unconfined,
        )
        assertTrue(afterRestart.recorded(StarterMilestone.FIRST_TRANSACTION, "account-a"))
    }

    @Test
    fun `accounts cannot see each other's milestones`() = runBlocking {
        store.record(StarterMilestone.FIRST_TRANSACTION, "account-a")
        assertFalse(store.recorded(StarterMilestone.FIRST_TRANSACTION, "account-b"))
    }

    @Test
    fun `deleting one account clears exactly its markers`() = runBlocking {
        store.record(StarterMilestone.FIRST_MESSAGE, "account-a")
        store.record(StarterMilestone.FIRST_TRANSACTION, "account-a")
        store.record(StarterMilestone.FIRST_TRANSACTION, "account-b")
        store.clearForAccount("account-a")
        assertFalse(store.recorded(StarterMilestone.FIRST_MESSAGE, "account-a"))
        assertFalse(store.recorded(StarterMilestone.FIRST_TRANSACTION, "account-a"))
        assertTrue(store.recorded(StarterMilestone.FIRST_TRANSACTION, "account-b"))
    }
}
