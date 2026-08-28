package com.kit.wallet

import com.kit.wallet.data.messaging.KitEditMessage
import com.kit.wallet.data.messaging.KitMediaMessage
import com.kit.wallet.data.messaging.KitMediaMessageV2
import com.kit.wallet.data.messaging.KitMediaMessageV2Item
import com.kit.wallet.data.messaging.KitReactionAction
import com.kit.wallet.data.messaging.KitReactionMessage
import com.kit.wallet.data.repository.AuthenticatedProjectedText
import com.kit.wallet.data.repository.AuthenticatedTextDeliveryState
import com.kit.wallet.data.repository.authenticatedProjectionOrder
import com.kit.wallet.data.repository.foldAuthenticatedEdits
import com.kit.wallet.ui.model.DeliveryState
import com.kit.wallet.ui.model.MESSAGE_EDIT_WINDOW_MILLIS
import com.kit.wallet.ui.model.Message
import com.kit.wallet.ui.model.MessageKind
import com.kit.wallet.ui.model.acceptsEdits
import com.kit.wallet.ui.model.editWindowRemainingMillis
import java.time.Instant
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageEditTest {
    // MARK: the wire descriptor

    @Test
    fun `an edit round-trips its target and its wording`() {
        val edit = KitEditMessage(targetMessageId = TARGET, body = "See you at eight")

        val parsed = KitEditMessage.parse(edit.encode())

        assertEquals(edit, parsed)
        assertTrue(KitEditMessage.isEditText(edit.encode()))
    }

    @Test
    fun `the wording is carried verbatim, separators and all`() {
        // The body is the last field and is not encoded, so punctuation that happens to look like
        // a field separator costs nothing and survives exactly as it was typed.
        val body = "Send R&D the invoice: total=200&VAT=36"

        val parsed = KitEditMessage.parse(KitEditMessage(TARGET, body).encode())

        assertEquals(body, parsed?.body)
    }

    @Test
    fun `an edit descriptor has exactly one spelling`() {
        // Anything that does not re-encode to itself is refused rather than interpreted, so a
        // later parser cannot find a second meaning in already-authenticated bytes.
        assertNull(KitEditMessage.parse("${KitEditMessage.PREFIX}v=1&t=$TARGET"))
        assertNull(KitEditMessage.parse("${KitEditMessage.PREFIX}v=2&t=$TARGET&b=Hello"))
        assertNull(KitEditMessage.parse("${KitEditMessage.PREFIX}v=1&t=${TARGET.uppercase()}&b=Hi"))
        assertNull(KitEditMessage.parse("${KitEditMessage.PREFIX}v=1&t=not-a-uuid-at-all&b=Hi"))
        assertNull(KitEditMessage.parse("${KitEditMessage.PREFIX}v=1&t=$TARGET&x=Hello"))
        assertNull(KitEditMessage.parse("Meet me at ${KitEditMessage.PREFIX}v=1&t=$TARGET&b=Hi"))
        assertNull(KitEditMessage.parse("See you at eight"))
    }

    @Test
    fun `a correction has to be something the composer could have sent`() {
        assertFalse(KitEditMessage.isAcceptableBody(""))
        assertFalse(KitEditMessage.isAcceptableBody("  padded  "))
        assertFalse(KitEditMessage.isAcceptableBody("a".repeat(KitEditMessage.MAX_DESCRIPTOR_LENGTH)))
        assertTrue(KitEditMessage.isAcceptableBody("See you at eight"))
    }

    @Test
    fun `an edit cannot smuggle in a descriptor the composer would refuse`() {
        // Otherwise editing a message would become a way to author a payment, a reaction or a
        // second edit — content the send path deliberately reserves to itself.
        listOf(
            KitReactionMessage(TARGET, "👍", KitReactionAction.ADD).encode(),
            KitEditMessage(TARGET, "See you at eight").encode(),
            "   ${KitEditMessage.PREFIX}v=1&t=$TARGET&b=Hi",
        ).forEach { body ->
            assertFalse(body, KitEditMessage.isAcceptableBody(body))
        }
    }

    @Test
    fun `a correction may be as long as the message it replaces was`() {
        // The ceiling is on the whole descriptor, so the header and the target UUID come out of
        // the same allowance the wire gives ordinary text.
        val header = KitEditMessage.PREFIX.length + "v=1&t=".length + TARGET.length + "&b=".length
        val longest = "a".repeat(KitEditMessage.MAX_DESCRIPTOR_LENGTH - header)

        assertTrue(KitEditMessage.isAcceptableBody(longest))
        assertFalse(KitEditMessage.isAcceptableBody(longest + "a"))
        assertEquals(longest, KitEditMessage.parse(KitEditMessage(TARGET, longest).encode())?.body)
    }

    @Test
    fun `the client and the server agree on how long fifteen minutes is`() {
        assertEquals(15L * 60L * 1_000L, KitEditMessage.EDIT_WINDOW_MILLIS)
        assertEquals(KitEditMessage.EDIT_WINDOW_MILLIS, MESSAGE_EDIT_WINDOW_MILLIS)
    }

    // MARK: folding the projection

    @Test
    fun `an edit replaces the wording of its own author's message`() {
        val folded = fold(
            message(TARGET, "See you at seven", fromMe = true),
            edit(TARGET, "See you at eight", fromMe = true),
        )

        assertEquals("See you at eight", folded.getValue(TARGET).text)
        assertTrue(folded.getValue(TARGET).editedAtEpochMillis > 0)
    }

    @Test
    fun `the last correction wins and replay does not change that`() {
        val entries = listOf(
            message(TARGET, "See you at seven", fromMe = false),
            edit(TARGET, "See you at eight", fromMe = false),
            edit(TARGET, "See you at nine", fromMe = false),
        )
        val expected = fold(*entries.toTypedArray())

        assertEquals("See you at nine", expected.getValue(TARGET).text)
        assertEquals(expected, fold(*(entries + entries).toTypedArray()))
        assertEquals(expected, fold(*entries.reversed().toTypedArray()))
    }

    @Test
    fun `nobody can reword somebody else's message`() {
        // Identity comes from the authenticated Signal sender of the carrying message, so a peer
        // cannot pass off a correction as the original author's second thought.
        val folded = fold(
            message(TARGET, "See you at seven", fromMe = true),
            edit(TARGET, "Send me your PIN", fromMe = false),
        )

        assertTrue(folded.isEmpty())
    }

    @Test
    fun `an edit for an unknown message annotates nothing`() {
        // Paging or a partial sync can deliver a correction before its target authenticates here.
        val folded = fold(
            message(TARGET, "See you at seven", fromMe = true),
            edit(OTHER_TARGET, "See you at eight", fromMe = true),
        )

        assertTrue(folded.isEmpty())
    }

    @Test
    fun `a permanently failed correction never rewords anything`() {
        val folded = fold(
            message(TARGET, "See you at seven", fromMe = true),
            edit(
                TARGET,
                "See you at eight",
                fromMe = true,
                deliveryState = AuthenticatedTextDeliveryState.PERMANENT_FAILURE,
            ),
        )

        assertTrue(folded.isEmpty())
    }

    @Test
    fun `reactions and edits stay out of each other's way`() {
        // Neither is a bubble of its own, and neither is a thing the other can act on: an edit of
        // a reaction, or of an earlier edit, would be a correction with nothing to show for it.
        val reactionId = nextMessageId()
        val editId = nextMessageId()
        val folded = fold(
            message(TARGET, "See you at seven", fromMe = true),
            projected(
                reactionId,
                KitReactionMessage(TARGET, "👍", KitReactionAction.ADD).encode(),
                fromMe = true,
            ),
            projected(editId, KitEditMessage(TARGET, "See you at eight").encode(), fromMe = true),
            projected(
                nextMessageId(),
                KitEditMessage(reactionId, "See you at nine").encode(),
                fromMe = true,
            ),
            projected(
                nextMessageId(),
                KitEditMessage(editId, "See you at ten").encode(),
                fromMe = true,
            ),
        )

        assertEquals(setOf(TARGET), folded.keys)
        assertEquals("See you at eight", folded.getValue(TARGET).text)
    }

    @Test
    fun `a photo cannot be reworded into a sentence`() {
        val folded = fold(
            message(TARGET, MEDIA_DESCRIPTOR, fromMe = true),
            edit(TARGET, "See you at eight", fromMe = true),
        )

        // Its descriptor is what points recipients at ciphertext they may already hold; a
        // correction that replaced it would strand the media with nothing left to name it.
        assertTrue(folded.isEmpty())
    }

    @Test
    fun `an inbound edit targeting a media v2 album is never folded`() {
        val folded = fold(
            message(TARGET, MEDIA_V2_DESCRIPTOR, fromMe = false),
            edit(TARGET, "Replace the album caption", fromMe = false),
        )

        assertTrue(folded.isEmpty())
    }

    // MARK: what the composer offers

    @Test
    fun `a media bubble offers no edit affordance`() {
        val now = BASE.toEpochMilli()
        assertFalse(uiMessage(now = now, mediaDescriptor = MEDIA_DESCRIPTOR).acceptsEdits(now))
    }

    @Test
    fun `only a settled bubble of one's own is still editable`() {
        val now = BASE.toEpochMilli()
        assertTrue(uiMessage(now = now).acceptsEdits(now))
        assertFalse(uiMessage(now = now, fromMe = false).acceptsEdits(now))

        // A call record is not something anyone said, so there is no wording to correct.
        assertFalse(uiMessage(now = now, kind = MessageKind.CALL).acceptsEdits(now))

        // A send still on its way is identified by its client ID, which the server ID replaces on
        // acknowledgement — an edit pinned to it in between would be stranded.
        val settled = setOf(DeliveryState.SENT, DeliveryState.DELIVERED, DeliveryState.READ)
        DeliveryState.entries.forEach { state ->
            assertEquals(state.name, state in settled, uiMessage(now = now, state = state).acceptsEdits(now))
        }
    }

    @Test
    fun `the window closes fifteen minutes after the message was sent`() {
        val sentAt = BASE.toEpochMilli()
        val message = uiMessage(now = sentAt)

        assertEquals(MESSAGE_EDIT_WINDOW_MILLIS, message.editWindowRemainingMillis(sentAt))
        assertTrue(message.acceptsEdits(sentAt + MESSAGE_EDIT_WINDOW_MILLIS - 1))
        assertFalse(message.acceptsEdits(sentAt + MESSAGE_EDIT_WINDOW_MILLIS))
        assertEquals(0L, message.editWindowRemainingMillis(sentAt + MESSAGE_EDIT_WINDOW_MILLIS))

        // A bubble with no timestamp of its own has no window to be inside of.
        assertFalse(message.copy(sortEpochMillis = 0L).acceptsEdits(sentAt))
    }

    // MARK: helpers

    private fun uiMessage(
        now: Long,
        fromMe: Boolean = true,
        kind: MessageKind = MessageKind.TEXT,
        state: DeliveryState = DeliveryState.READ,
        mediaDescriptor: String? = null,
    ) = Message(
        id = TARGET,
        text = "See you at seven",
        time = "09:00",
        fromMe = fromMe,
        state = state,
        kind = kind,
        sortEpochMillis = now,
        mediaDescriptor = mediaDescriptor,
    )

    private fun fold(vararg entries: AuthenticatedProjectedText) =
        foldAuthenticatedEdits(entries.sortedWith(authenticatedProjectionOrder))

    private fun message(
        messageId: String,
        text: String,
        fromMe: Boolean,
    ): AuthenticatedProjectedText = projected(messageId, text, fromMe)

    private fun edit(
        targetMessageId: String,
        body: String,
        fromMe: Boolean,
        deliveryState: AuthenticatedTextDeliveryState = AuthenticatedTextDeliveryState.SENT,
    ): AuthenticatedProjectedText = projected(
        messageId = nextMessageId(),
        text = KitEditMessage(targetMessageId, body).encode(),
        fromMe = fromMe,
        deliveryState = deliveryState,
    )

    private fun projected(
        messageId: String,
        text: String,
        fromMe: Boolean,
        deliveryState: AuthenticatedTextDeliveryState = AuthenticatedTextDeliveryState.SENT,
    ): AuthenticatedProjectedText {
        val sequence = ordinal++
        return AuthenticatedProjectedText(
            recordKey = messageId,
            messageId = messageId,
            serverMessageId = "%08d".format(sequence),
            clientMessageId = messageId,
            conversationId = CONVERSATION,
            senderUserId = if (fromMe) SELF_USER else PEER_USER,
            fromCurrentUser = fromMe,
            text = text,
            sentAt = BASE.plusSeconds(sequence.toLong()),
            deliveryState = deliveryState,
        )
    }

    private fun nextMessageId(): String = "bbbbbbbb-bbbb-4bbb-8bbb-%012d".format(ordinal)

    private var ordinal = 0

    private companion object {
        const val CONVERSATION = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        const val TARGET = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val OTHER_TARGET = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
        const val SELF_USER = "11111111-1111-4111-8111-111111111111"
        const val PEER_USER = "22222222-2222-4222-8222-222222222222"
        val BASE: Instant = Instant.parse("2026-08-24T09:00:00Z")
        val MEDIA_DESCRIPTOR: String = KitMediaMessage(
            attachmentId = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
            storageKey = "attachments/eeeeeeee",
            mediaType = "image/jpeg",
            ciphertextByteSize = 4_096,
            ciphertextSha256 = "a".repeat(64),
            keyMaterialBase64 = Base64.getEncoder().encodeToString(ByteArray(64) { 7 }),
            plaintextByteSize = 4_000,
            caption = "A photo",
        ).encode()
        val MEDIA_V2_DESCRIPTOR: String = KitMediaMessageV2(
            items = listOf(
                KitMediaMessageV2Item(
                    attachmentId = "11111111-1111-4111-8111-111111111111",
                    storageKey = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                    mediaType = "image/jpeg",
                    ciphertextByteSize = 1_088,
                    ciphertextSha256 = "1".repeat(64),
                    keyMaterialBase64 = Base64.getEncoder().encodeToString(ByteArray(64)),
                    plaintextByteSize = 1_024,
                ),
                KitMediaMessageV2Item(
                    attachmentId = "22222222-2222-4222-8222-222222222222",
                    storageKey = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                    mediaType = "video/mp4",
                    ciphertextByteSize = 5_242_944,
                    ciphertextSha256 = "2".repeat(64),
                    keyMaterialBase64 = Base64.getEncoder().encodeToString(ByteArray(64) { 1 }),
                    plaintextByteSize = 5_242_880,
                ),
            ),
            caption = "Family photos",
        ).encode()
    }
}
