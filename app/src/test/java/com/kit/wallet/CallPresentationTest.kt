package com.kit.wallet

import com.kit.wallet.data.repository.initialCallPresentation
import com.kit.wallet.data.repository.resolveCallPresentation
import com.kit.wallet.data.repository.resolveRoomParticipant
import com.kit.wallet.data.repository.resolveRoomParticipantName
import com.kit.wallet.ui.model.Contact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallPresentationTest {
    private val floraId = "550e8400-e29b-41d4-a716-446655440000"
    private val flora = Contact(
        id = floraId,
        name = "Flora from my contacts",
        phone = "+256 700 000 001",
        registeredName = "Flora Registered",
        savedInDevice = true,
    )

    @Test
    fun `saved contact name and phone override registered call presentation`() {
        val presentation = resolveCallPresentation(
            serverName = "Flora Registered",
            participantUserIds = listOf(floraId),
            contacts = listOf(flora),
        )

        assertEquals("Flora from my contacts", presentation.name)
        assertEquals("+256 700 000 001", presentation.phone)
    }

    @Test
    fun `unresolved UUID is never rendered while an outgoing call starts`() {
        val presentation = initialCallPresentation(floraId, contacts = emptyList())

        assertEquals("Kit Pay contact", presentation.name)
        assertNull(presentation.phone)
    }

    @Test
    fun `Laravel UUIDv7 is never rendered while an outgoing call starts`() {
        val presentation = initialCallPresentation(
            "019f8c6f-cc57-720c-9a55-0d1cdf434d62",
            contacts = emptyList(),
        )

        assertEquals("Kit Pay contact", presentation.name)
        assertNull(presentation.phone)
    }

    @Test
    fun `loaded contact name is available before the call API responds`() {
        assertEquals(
            "Flora from my contacts",
            initialCallPresentation(floraId.uppercase(), listOf(flora)).name,
        )
    }

    @Test
    fun `registered name remains the fallback when participant is not saved`() {
        assertEquals(
            "Flora Registered",
            resolveCallPresentation(" Flora Registered ", listOf(floraId), emptyList()).name,
        )
    }

    @Test
    fun `group call uses each locally saved participant name in server order`() {
        val secondId = "86d5c9b8-4c19-4f14-91a7-28c2500049d1"
        val second = Contact(secondId, "Amina", "+256700000002")

        assertEquals(
            "Flora from my contacts, Amina",
            resolveCallPresentation(
                serverName = "Server group name",
                participantUserIds = listOf(floraId, secondId),
                contacts = listOf(second, flora),
            ).name,
        )
    }

    @Test
    fun `single matched participant carries the contact profile photo`() {
        val presentation = resolveCallPresentation(
            serverName = "Flora Registered",
            participantUserIds = listOf(floraId),
            contacts = listOf(flora.copy(avatarUrl = " https://pay.kit.africa/media/a1 ")),
        )

        assertEquals("https://pay.kit.africa/media/a1", presentation.avatarUrl)
    }

    @Test
    fun `group calls and blank photo URLs resolve without an avatar`() {
        val secondId = "86d5c9b8-4c19-4f14-91a7-28c2500049d1"
        val second = Contact(secondId, "Amina", "+256700000002", avatarUrl = "https://pay.kit.africa/media/a2")

        assertNull(
            resolveCallPresentation(
                serverName = null,
                participantUserIds = listOf(floraId, secondId),
                contacts = listOf(flora.copy(avatarUrl = "https://pay.kit.africa/media/a1"), second),
            ).avatarUrl,
        )
        assertNull(
            resolveCallPresentation(
                serverName = null,
                participantUserIds = listOf(floraId),
                contacts = listOf(flora.copy(avatarUrl = "   ")),
            ).avatarUrl,
        )
    }

    @Test
    fun `LiveKit participant identity uses the locally saved contact name`() {
        assertEquals(
            "Flora from my contacts",
            resolveRoomParticipantName(
                identity = "$floraId:server-device-id",
                serverName = "Flora Registered",
                contacts = listOf(flora),
            ),
        )
    }

    @Test
    fun `a participant on a call carries their photo as well as their name`() {
        // The grid tile for someone whose camera is off shows their face; it only can if the
        // participant resolver hands back more than a display name.
        val resolved = resolveRoomParticipant(
            identity = "$floraId:server-device-id",
            serverName = "Flora Registered",
            contacts = listOf(flora.copy(avatarUrl = "https://pay.kit.africa/media/a1")),
        )
        assertEquals("Flora from my contacts", resolved.name)
        assertEquals("https://pay.kit.africa/media/a1", resolved.avatarUrl)
    }

    @Test
    fun `a participant nobody has saved has no photo and no name to borrow`() {
        val resolved = resolveRoomParticipant(
            identity = "$floraId:server-device-id",
            serverName = "Flora Registered",
            contacts = emptyList(),
        )
        assertEquals("Flora Registered", resolved.name)
        assertNull(resolved.avatarUrl)
    }
}
