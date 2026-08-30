package com.kit.wallet

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerificationBadgePlacementContractTest {
    @Test
    fun `avatars cannot accept or render account verification`() {
        val root = repositoryRoot()
        val avatar = source(root, "ui/components/Avatar.kt")
        val signature = avatar.substringAfter("fun KitAvatar(").substringBefore(") {")

        assertFalse(signature.contains("AccountVerification"))
        assertFalse(signature.contains("verification", ignoreCase = true))
        assertFalse(avatar.contains("AccountVerificationBadge"))
        assertFalse(avatar.contains("Icons.Rounded.Verified"))

        val activeCall = source(root, "feature/calls/ActiveCallScreen.kt")
        assertFalse(activeCall.contains("AccountVerificationBadge"))
        assertFalse(
            Regex("""KitAvatarPhoto\([\s\S]{0,300}AccountVerificationBadge""")
                .containsMatchIn(activeCall),
        )

        val sourceRoot = File(root, "app/src/main/java/com/kit/wallet")
        val verifiedIconFiles = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { Regex("""Icons\.Rounded\.Verified\b""").containsMatchIn(it.readText()) }
            .map { it.relativeTo(sourceRoot).invariantSeparatorsPath }
            .toSet()
        assertEquals(
            setOf(
                "feature/support/SupportBadge.kt",
                "ui/components/AccountVerificationBadge.kt",
            ),
            verifiedIconFiles,
        )
    }

    @Test
    fun `authoritative account seal is inline with displayed names on every identity surface`() {
        val root = repositoryRoot()
        val badge = source(root, "ui/components/AccountVerificationBadge.kt")
        val identitySurfaces = mapOf(
            "feature/calls/ActiveCallScreen.kt" to 3,
            "feature/calls/CallsScreen.kt" to 1,
            "feature/chat/ChatsScreen.kt" to 3,
            "feature/chat/ConversationScreen.kt" to 1,
            "feature/chat/GroupProfileScreen.kt" to 1,
            "feature/chat/MessageInfoScreen.kt" to 1,
            "feature/chat/NewGroupScreen.kt" to 1,
            "feature/chat/SharedTextShareScreen.kt" to 2,
            "feature/contacts/ContactsScreen.kt" to 1,
            "feature/funding/TopUpSheet.kt" to 1,
            "feature/home/HomeScreen.kt" to 1,
            "feature/settings/SettingsScreen.kt" to 1,
            "feature/wallet/RequestMoneyScreen.kt" to 1,
            "feature/wallet/SendMoneyScreen.kt" to 4,
            "feature/wallet/TransactionDetailScreen.kt" to 1,
        )

        assertTrue(badge.contains("private fun AccountVerificationBadge("))
        assertTrue(badge.contains("fun VerifiedAccountName("))
        assertTrue(badge.contains("append(name)"))
        assertTrue(badge.contains("appendInlineContent("))
        identitySurfaces.forEach { (path, expectedCount) ->
            assertEquals(
                path,
                expectedCount,
                Regex("""VerifiedAccountName\(""").findAll(source(root, path)).count(),
            )
        }
    }

    @Test
    fun `official support seal stays beside support names and never enters its avatar`() {
        val root = repositoryRoot()
        val supportBadge = source(root, "feature/support/SupportBadge.kt")
        val supportAvatar = supportBadge.substringAfter("fun SupportAvatar(")
        val hub = source(root, "feature/support/SupportHubScreen.kt")
        val ticket = source(root, "feature/support/SupportTicketScreen.kt")

        assertFalse(supportAvatar.contains("SupportVerifiedBadge"))
        assertFalse(supportAvatar.contains("Icons.Rounded.Verified"))
        assertTrue(
            Regex("""ticket\.identityDisplayName[\s\S]{0,300}SupportVerifiedBadge\(ticket\.identityVerified""")
                .containsMatchIn(hub),
        )
        assertTrue(
            Regex("""ticket\.identityDisplayName[\s\S]{0,300}SupportVerifiedBadge\(ticket\.identityVerified""")
                .containsMatchIn(ticket),
        )
        assertTrue(
            Regex(
                """message\.sender\.displayName[\s\S]{0,800}""" +
                    """SupportVerifiedBadge\([\s\S]{0,100}message\.sender\.verifiedOfficialSupport""",
            )
                .containsMatchIn(ticket),
        )
    }

    private fun source(root: File, path: String): String =
        File(root, "app/src/main/java/com/kit/wallet/$path").readText()

    private fun repositoryRoot(): File {
        val workingDirectory = File(checkNotNull(System.getProperty("user.dir"))).canonicalFile
        return generateSequence(workingDirectory) { it.parentFile }
            .firstOrNull { File(it, "app/build.gradle.kts").isFile }
            ?: error("Could not locate the Android repository root from $workingDirectory")
    }
}
