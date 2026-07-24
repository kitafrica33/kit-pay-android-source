package com.kit.wallet.feature.calls

import com.kit.wallet.ui.model.Contact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActiveCallContactPresentationTest {
    @Test
    fun `late contacts refresh active waiting remote and telecom presentations`() {
        val initial = ActiveCallUiState(
            name = "Flora Registered",
            video = true,
            phase = CallPhase.CONNECTED,
            muted = true,
            durationSeconds = 42,
            remoteParticipants = listOf(
                RemoteCallParticipant(
                    id = "$REMOTE_USER_ID:remote-device",
                    name = "Remote Registered",
                    speaking = true,
                    serverName = "Remote Registered",
                ),
            ),
            waitingCall = WaitingCall(
                callId = WAITING_CALL_ID,
                name = "Waiting Registered",
                video = false,
                callerUserId = WAITING_USER_ID,
                serverName = "Waiting Registered",
            ),
        )
        val source = ActiveCallContactPresentationSource(
            callId = ACTIVE_CALL_ID,
            serverName = "Flora Registered",
            participantUserIds = listOf(ACTIVE_USER_ID),
        )

        val refreshed = refreshActiveCallContactPresentation(
            state = initial,
            activeSource = source,
            contacts = listOf(
                contact(ACTIVE_USER_ID, "Flora saved", "+256700000001"),
                contact(WAITING_USER_ID, "Amina saved", "+256700000002"),
                contact(REMOTE_USER_ID, "Joel saved", "+256700000003"),
            ),
        )

        assertEquals("Flora saved", refreshed.state.name)
        assertEquals("Amina saved", refreshed.state.waitingCall?.name)
        assertEquals("Joel saved", refreshed.state.remoteParticipants.single().name)
        assertEquals(true, refreshed.state.remoteParticipants.single().speaking)
        assertEquals(CallPhase.CONNECTED, refreshed.state.phase)
        assertEquals(true, refreshed.state.muted)
        assertEquals(42L, refreshed.state.durationSeconds)
        assertEquals(
            TelecomPresentationUpdate(
                ACTIVE_CALL_ID,
                "Flora saved",
                "+256700000001",
                video = true,
            ),
            refreshed.activeTelecom,
        )
        assertEquals(
            TelecomPresentationUpdate(
                WAITING_CALL_ID,
                "Amina saved",
                "+256700000002",
                video = false,
            ),
            refreshed.waitingTelecom,
        )
    }

    @Test
    fun `later contact rename replaces prior saved names without losing server fallbacks`() {
        val source = ActiveCallContactPresentationSource(
            callId = ACTIVE_CALL_ID,
            serverName = "Flora Registered",
            participantUserIds = listOf(ACTIVE_USER_ID),
            fallbackPhone = "+256700000099",
        )
        val initial = ActiveCallUiState(
            name = "Old Flora",
            phase = CallPhase.CONNECTED,
            remoteParticipants = listOf(
                RemoteCallParticipant(
                    id = "$REMOTE_USER_ID:device",
                    name = "Old Joel",
                    serverName = "Remote Registered",
                ),
            ),
            waitingCall = WaitingCall(
                callId = WAITING_CALL_ID,
                name = "Old Amina",
                video = false,
                callerUserId = WAITING_USER_ID,
                serverName = "Waiting Registered",
            ),
        )

        val renamed = refreshActiveCallContactPresentation(
            initial,
            source,
            listOf(
                contact(ACTIVE_USER_ID, "New Flora", "+256700000001"),
                contact(WAITING_USER_ID, "New Amina", "+256700000002"),
                contact(REMOTE_USER_ID, "New Joel", "+256700000003"),
            ),
        )
        val removed = refreshActiveCallContactPresentation(renamed.state, source, emptyList())

        assertEquals("New Flora", renamed.state.name)
        assertEquals("New Amina", renamed.state.waitingCall?.name)
        assertEquals("New Joel", renamed.state.remoteParticipants.single().name)
        assertEquals("Flora Registered", removed.state.name)
        assertEquals("Waiting Registered", removed.state.waitingCall?.name)
        assertEquals("Remote Registered", removed.state.remoteParticipants.single().name)
        assertEquals("+256700000099", removed.activeTelecom?.phone)
        assertNull(removed.waitingTelecom?.phone)
    }

    private fun contact(id: String, name: String, phone: String) = Contact(
        id = id,
        name = name,
        phone = phone,
        isKitUser = true,
        savedInDevice = true,
    )

    private companion object {
        const val ACTIVE_CALL_ID = "019f8c6f-cc57-720c-9a55-000000000001"
        const val WAITING_CALL_ID = "019f8c6f-cc57-720c-9a55-000000000002"
        const val ACTIVE_USER_ID = "550e8400-e29b-41d4-a716-446655440000"
        const val WAITING_USER_ID = "86d5c9b8-4c19-4f14-91a7-28c2500049d1"
        const val REMOTE_USER_ID = "124f3f0b-9af2-4ba5-8e9f-a347cd11a100"
    }
}
