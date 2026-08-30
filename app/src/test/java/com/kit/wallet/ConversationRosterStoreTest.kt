package com.kit.wallet

import com.kit.wallet.data.messaging.ConversationRosterStore
import com.kit.wallet.data.messaging.SecureMessagingLifecycleGuard
import com.kit.wallet.data.messaging.SecureMessagingSessionBinding
import com.kit.wallet.data.repository.AuthenticatedConversation
import com.kit.wallet.data.repository.AuthenticatedConversationMember
import com.kit.wallet.ui.model.AccountVerification
import com.kit.wallet.ui.model.AccountVerificationDesignation
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationRosterStoreTest {
    @Test
    fun `version three round trip preserves member avatars and verification offline`() = runTest {
        val state = TestSecureMessagingStateStore()
        val store = ConversationRosterStore(state)
        val activation = activation()
        val verification = AccountVerification(
            AccountVerificationDesignation.OFFICIAL_SUPPORT,
            "2026-08-29T09:10:11Z",
        )
        val conversation = conversation(
            id = CONVERSATION_ID,
            peer = AuthenticatedConversationMember(
                userId = PEER_USER_ID,
                name = "Kit Customer Support",
                role = "member",
                avatarUrl = "https://pay.kit.africa/media/support-avatar",
                accountVerification = verification,
            ),
        )

        store.replace(activation, listOf(conversation))
        val restored = store.read(activation).single()

        assertEquals(conversation.description, restored.description)
        assertEquals(conversation.photoUrl, restored.photoUrl)
        assertEquals(
            "https://pay.kit.africa/media/support-avatar",
            restored.memberNamed(PEER_USER_ID)?.avatarUrl,
        )
        assertEquals(verification, restored.memberNamed(PEER_USER_ID)?.accountVerification)
    }

    @Test
    fun `version one and two rosters remain readable without inventing identity metadata`() =
        runTest {
            val state = TestSecureMessagingStateStore()
            val store = ConversationRosterStore(state)
            val activation = activation()
            state.write(
                namespace = NAMESPACE,
                recordKey = "roster:$LEGACY_CONVERSATION_ID",
                expectedVersion = null,
                bytes = encodedLegacyRoster(version = 1, id = LEGACY_CONVERSATION_ID),
            )
            state.write(
                namespace = NAMESPACE,
                recordKey = "roster:$GROUP_ID",
                expectedVersion = null,
                bytes = encodedLegacyRoster(version = 2, id = GROUP_ID),
            )

            val restored = store.read(activation).associateBy(AuthenticatedConversation::id)

            assertNull(restored.getValue(LEGACY_CONVERSATION_ID).description)
            assertNull(restored.getValue(LEGACY_CONVERSATION_ID).photoUrl)
            assertEquals("Savings", restored.getValue(GROUP_ID).description)
            assertEquals(
                "https://pay.kit.africa/media/group-avatar",
                restored.getValue(GROUP_ID).photoUrl,
            )
            restored.values.forEach { row ->
                row.members.forEach { member ->
                    assertNull(member.avatarUrl)
                    assertNull(member.accountVerification)
                }
            }
        }

    private fun activation() = SecureMessagingLifecycleGuard().let { lifecycle ->
        val fence = lifecycle.beginSession(
            SecureMessagingSessionBinding(
                sessionEpoch = "roster-test-session",
                userId = CURRENT_USER_ID,
                serverDeviceId = "roster-test-device",
                installationId = "roster-test-installation",
            ),
        )
        lifecycle.activationCapability(fence)
    }

    private fun conversation(
        id: String,
        peer: AuthenticatedConversationMember,
    ) = AuthenticatedConversation(
        id = id,
        type = "group",
        title = "Support group",
        viewerUserId = CURRENT_USER_ID,
        currentUserRole = "owner",
        members = listOf(
            AuthenticatedConversationMember(CURRENT_USER_ID, "Me", "owner"),
            peer,
        ),
        description = "Savings",
        photoUrl = "https://pay.kit.africa/media/group-avatar",
    )

    private fun encodedLegacyRoster(version: Int, id: String): ByteArray =
        ByteArrayOutputStream().let { output ->
            DataOutputStream(output).use { data ->
                data.writeByte(version)
                data.writeUTF(id)
                data.writeUTF("group")
                data.writeUTF("Support group")
                data.writeUTF(CURRENT_USER_ID)
                data.writeUTF("owner")
                if (version >= 2) {
                    data.writeUTF("Savings")
                    data.writeUTF("https://pay.kit.africa/media/group-avatar")
                }
                data.writeInt(2)
                data.writeUTF(CURRENT_USER_ID)
                data.writeUTF("Me")
                data.writeUTF("owner")
                data.writeUTF(PEER_USER_ID)
                data.writeUTF("Kit Customer Support")
                data.writeUTF("member")
            }
            output.toByteArray()
        }

    private companion object {
        const val NAMESPACE = "conversation-roster-v1"
        const val CURRENT_USER_ID = "550e8400-e29b-41d4-a716-446655440000"
        const val PEER_USER_ID = "86d5c9b8-4c19-4f14-91a7-28c2500049d1"
        const val CONVERSATION_ID = "019f8c6f-cc57-720c-9a55-000000000001"
        const val LEGACY_CONVERSATION_ID = "019f8c6f-cc57-720c-9a55-000000000002"
        const val GROUP_ID = "019f8c6f-cc57-720c-9a55-000000000003"
    }
}
