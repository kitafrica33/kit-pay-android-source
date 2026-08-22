package com.kit.wallet

import com.kit.wallet.data.messaging.SecureMessagingComposerDraftStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ComposerDraftStoreTest {
    @Test fun `round trips and overwrites a conversation draft`() = runTest {
        val store = SecureMessagingComposerDraftStore(TestSecureMessagingStateStore())

        assertNull(store.read(CONVERSATION_ID))
        store.save(CONVERSATION_ID, "buying airtime for mum")
        assertEquals("buying airtime for mum", store.read(CONVERSATION_ID))
        store.save(CONVERSATION_ID, "buying airtime for mum tonight")
        assertEquals("buying airtime for mum tonight", store.read(CONVERSATION_ID))
    }

    @Test fun `clearing and blank saves remove the draft`() = runTest {
        val store = SecureMessagingComposerDraftStore(TestSecureMessagingStateStore())

        store.save(CONVERSATION_ID, "half-typed")
        store.clear(CONVERSATION_ID)
        assertNull(store.read(CONVERSATION_ID))

        store.save(CONVERSATION_ID, "typed again")
        store.save(CONVERSATION_ID, "   ")
        assertNull(store.read(CONVERSATION_ID))
    }

    @Test fun `drafts are per conversation and reject invalid identifiers`() = runTest {
        val store = SecureMessagingComposerDraftStore(TestSecureMessagingStateStore())

        store.save(CONVERSATION_ID, "for grace")
        store.save(OTHER_CONVERSATION_ID, "for okello")
        assertEquals("for grace", store.read(CONVERSATION_ID))
        assertEquals("for okello", store.read(OTHER_CONVERSATION_ID))

        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { store.save("../escape", "text") }
        }
    }

    private companion object {
        const val CONVERSATION_ID = "0199aaaa-bbbb-4ccc-8ddd-eeeeffff0001"
        const val OTHER_CONVERSATION_ID = "0199aaaa-bbbb-4ccc-8ddd-eeeeffff0002"
    }
}
