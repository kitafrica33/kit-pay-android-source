package com.kit.wallet

import com.kit.wallet.data.repository.provesFirstMessage
import com.kit.wallet.ui.model.DeliveryState
import com.kit.wallet.ui.model.Message
import com.kit.wallet.ui.model.MessageKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstMessageEvidenceTest {
    @Test
    fun `only content the author actually said counts as first-message evidence`() {
        val saidByAuthor = setOf(
            MessageKind.TEXT,
            MessageKind.VOICE_NOTE,
            MessageKind.IMAGE,
            MessageKind.VIDEO,
            MessageKind.DOCUMENT,
            MessageKind.MEDIA_ALBUM,
        )
        // Exhaustive over the enum so a future kind must take a side here deliberately.
        MessageKind.entries.forEach { kind ->
            assertEquals(
                "kind $kind",
                kind in saidByAuthor,
                sent(kind).provesFirstMessage(),
            )
        }
    }

    @Test
    fun `an unsupported attachment placeholder is never evidence in any delivery state`() {
        // The placeholder stands for reserved content nothing validated; whatever its delivery
        // state says happened to the row, it can prove nothing about what its author said.
        DeliveryState.entries.forEach { state ->
            assertFalse(
                sent(MessageKind.UNSUPPORTED_ATTACHMENT)
                    .copy(state = state)
                    .provesFirstMessage(),
            )
        }
    }

    @Test
    fun `a media album proves only once it really left this device`() {
        val album = sent(MessageKind.MEDIA_ALBUM)
        assertTrue(album.provesFirstMessage())
        assertTrue(album.copy(state = DeliveryState.DELIVERED).provesFirstMessage())
        assertTrue(album.copy(state = DeliveryState.READ).provesFirstMessage())
        listOf(
            DeliveryState.SENDING,
            DeliveryState.RETRY_REQUIRED,
            DeliveryState.FAILED,
            DeliveryState.SCHEDULED,
            DeliveryState.UNCONFIRMED,
        ).forEach { state ->
            assertFalse(album.copy(state = state).provesFirstMessage())
        }
        assertFalse(album.copy(fromMe = false).provesFirstMessage())
    }

    private fun sent(kind: MessageKind) = Message(
        id = "milestone-message",
        text = "evidence",
        time = "12:00",
        fromMe = true,
        state = DeliveryState.SENT,
        kind = kind,
    )
}
