package com.kit.wallet

import com.kit.wallet.data.remote.AccountVerificationDto
import com.kit.wallet.data.remote.CallDto
import com.kit.wallet.data.remote.CallParticipantDto
import com.kit.wallet.data.repository.initialCallPresentation
import com.kit.wallet.data.repository.resolveCallPresentation
import com.kit.wallet.data.repository.resolveRoomParticipant
import com.kit.wallet.data.repository.resolveRoomParticipantName
import com.kit.wallet.data.repository.toCallParticipantIdentities
import com.kit.wallet.ui.model.Contact
import com.kit.wallet.ui.model.AccountVerification
import com.kit.wallet.ui.model.AccountVerificationDesignation
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
        val official = AccountVerification(AccountVerificationDesignation.OFFICIAL, null)
        val presentation = resolveCallPresentation(
            serverName = "Flora Registered",
            participantUserIds = listOf(floraId),
            contacts = listOf(
                flora.copy(
                    avatarUrl = " https://pay.kit.africa/media/a1 ",
                    accountVerification = official,
                ),
            ),
        )

        assertEquals("https://pay.kit.africa/media/a1", presentation.avatarUrl)
        assertEquals(official, presentation.accountVerification)
    }

    @Test
    fun `structured call participant supplies first sighting name photo and badge`() {
        val identities = call(
            participantUserIds = emptyList(),
            participants = listOf(
                CallParticipantDto(
                    userId = floraId.uppercase(),
                    name = "Flora Registered",
                    avatarUrl = "https://pay.kit.africa/media/a1",
                    verification = AccountVerificationDto(
                        "official",
                        "2026-08-29T10:11:12Z",
                    ),
                ),
            ),
        ).toCallParticipantIdentities()

        val presentation = resolveCallPresentation(
            serverName = null,
            participantUserIds = identities.map { it.userId },
            contacts = emptyList(),
            participants = identities,
        )

        assertEquals(listOf(floraId), identities.map { it.userId })
        assertEquals("Flora Registered", presentation.name)
        assertEquals("https://pay.kit.africa/media/a1", presentation.avatarUrl)
        assertEquals(
            AccountVerificationDesignation.OFFICIAL,
            presentation.accountVerification?.designation,
        )
    }

    @Test
    fun `call participant merge is case insensitive and validates optional metadata independently`() {
        val secondId = "86d5c9b8-4c19-4f14-91a7-28c2500049d1"
        val identities = call(
            participantUserIds = listOf(floraId, secondId, "not-a-user"),
            participants = listOf(
                CallParticipantDto(
                    userId = floraId.uppercase(),
                    name = "First sighting",
                    avatarUrl = "http://attacker.example/avatar",
                    verification = AccountVerificationDto("Official", null),
                ),
                CallParticipantDto(
                    userId = secondId,
                    name = "Second person",
                    avatarUrl = "https://pay.kit.africa/media/a2",
                    verification = AccountVerificationDto("verified", "not-an-instant"),
                ),
                CallParticipantDto(userId = "bad", name = "Forged"),
            ),
        ).toCallParticipantIdentities(additionalUserIds = listOf(floraId.uppercase()))

        assertEquals(listOf(floraId, secondId), identities.map { it.userId })
        assertNull(identities[0].avatarUrl)
        assertNull(identities[0].accountVerification)
        assertEquals("https://pay.kit.africa/media/a2", identities[1].avatarUrl)
        assertEquals(
            AccountVerificationDesignation.VERIFIED,
            identities[1].accountVerification?.designation,
        )
        assertNull(identities[1].accountVerification?.since)
    }

    @Test
    fun `saved name does not erase first sighting photo or badge`() {
        val official = AccountVerification(AccountVerificationDesignation.OFFICIAL, null)
        val participant = com.kit.wallet.data.repository.CallParticipantIdentity(
            userId = floraId,
            name = "Flora Registered",
            avatarUrl = "https://pay.kit.africa/media/a1",
            accountVerification = official,
        )

        val presentation = resolveCallPresentation(
            serverName = "Call fallback",
            participantUserIds = listOf(floraId),
            contacts = listOf(flora.copy(avatarUrl = null, accountVerification = null)),
            participants = listOf(participant),
        )

        assertEquals("Flora from my contacts", presentation.name)
        assertEquals(participant.avatarUrl, presentation.avatarUrl)
        assertEquals(official, presentation.accountVerification)
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
                participantUserIds = listOf(floraId, secondId),
                contacts = listOf(
                    flora.copy(
                        accountVerification = AccountVerification(
                            AccountVerificationDesignation.VERIFIED,
                            null,
                        ),
                    ),
                    second,
                ),
            ).accountVerification,
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

    private fun call(
        participantUserIds: List<String>,
        participants: List<CallParticipantDto?>,
    ) = CallDto(
        id = "019f8c6f-cc57-720c-9a55-000000000001",
        name = null,
        participantUserIds = participantUserIds,
        participants = participants,
        direction = "incoming",
        type = "voice",
        state = "ringing",
        startedAt = "2026-08-29T10:00:00Z",
    )
}
