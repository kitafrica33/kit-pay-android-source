package com.kit.wallet

import com.kit.wallet.feature.contacts.ContactSyncEffect
import com.kit.wallet.feature.contacts.ContactSyncEvent
import com.kit.wallet.feature.contacts.ContactSyncStage
import com.kit.wallet.feature.contacts.contactSyncDisclosurePresentation
import com.kit.wallet.feature.contacts.decideContactSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactPermissionDisclosureTest {
    @Test
    fun `disclosure names every uploaded contact field and purpose`() {
        val disclosure = contactSyncDisclosurePresentation()
        val body = disclosure.body.lowercase()

        assertTrue(disclosure.title.contains("Upload"))
        assertTrue(body.contains("names"))
        assertTrue(body.contains("phone numbers"))
        assertTrue(body.contains("favorite status"))
        assertTrue(body.contains("to kit pay"))
        assertTrue(body.contains("find people who use kit pay"))
        assertEquals("Agree and continue", disclosure.confirmLabel)
        assertEquals("Not now", disclosure.cancelLabel)
    }

    @Test
    fun `cancel from disclosure returns idle without an effect`() {
        val opened = decideContactSync(ContactSyncStage.IDLE, ContactSyncEvent.START)
        val cancelled = decideContactSync(opened.nextStage, ContactSyncEvent.CANCEL)

        assertEquals(ContactSyncStage.DISCLOSURE, opened.nextStage)
        assertEquals(ContactSyncEffect.NONE, opened.effect)
        assertEquals(ContactSyncStage.IDLE, cancelled.nextStage)
        assertEquals(ContactSyncEffect.NONE, cancelled.effect)
    }

    @Test
    fun `agreement requests Android permission before any sync`() {
        val decision = decideContactSync(
            stage = ContactSyncStage.DISCLOSURE,
            event = ContactSyncEvent.AGREE,
            permissionGranted = false,
        )

        assertEquals(ContactSyncStage.AWAITING_PERMISSION, decision.nextStage)
        assertEquals(ContactSyncEffect.REQUEST_PERMISSION, decision.effect)
    }

    @Test
    fun `granted permission after agreement triggers one sync`() {
        val decision = decideContactSync(
            stage = ContactSyncStage.AWAITING_PERMISSION,
            event = ContactSyncEvent.PERMISSION_RESULT,
            permissionGranted = true,
        )

        assertEquals(ContactSyncStage.IDLE, decision.nextStage)
        assertEquals(ContactSyncEffect.SYNC, decision.effect)
    }

    @Test
    fun `permission result without prior agreement cannot sync`() {
        val decision = decideContactSync(
            stage = ContactSyncStage.IDLE,
            event = ContactSyncEvent.PERMISSION_RESULT,
            permissionGranted = true,
        )

        assertEquals(ContactSyncStage.IDLE, decision.nextStage)
        assertEquals(ContactSyncEffect.NONE, decision.effect)
    }

    @Test
    fun `agreement with an existing permission syncs without requesting again`() {
        val decision = decideContactSync(
            stage = ContactSyncStage.DISCLOSURE,
            event = ContactSyncEvent.AGREE,
            permissionGranted = true,
        )

        assertEquals(ContactSyncStage.IDLE, decision.nextStage)
        assertEquals(ContactSyncEffect.SYNC, decision.effect)
    }

    @Test
    fun `permission denial returns idle without sync`() {
        val decision = decideContactSync(
            stage = ContactSyncStage.AWAITING_PERMISSION,
            event = ContactSyncEvent.PERMISSION_RESULT,
            permissionGranted = false,
        )

        assertEquals(ContactSyncStage.IDLE, decision.nextStage)
        assertEquals(ContactSyncEffect.NONE, decision.effect)
    }
}
