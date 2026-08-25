package com.kit.wallet

import com.kit.wallet.data.repository.KeyedBeneficiaryPhoneIdentity
import com.kit.wallet.data.repository.canonicalBeneficiaryId
import com.kit.wallet.data.repository.canonicalContactPhone
import com.kit.wallet.ui.model.BeneficiaryIdentity
import com.kit.wallet.ui.model.Contact
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A face beside a payout destination is an identity claim, so every ambiguity fails to initials. */
class BeneficiaryIdentityTest {
    private val identities = KeyedBeneficiaryPhoneIdentity {
        SecretKeySpec(ByteArray(32) { index -> (index + 1).toByte() }, "HmacSHA256")
    }

    private fun contact(
        id: String,
        name: String,
        phone: String,
        isKitUser: Boolean = true,
        avatarUrl: String? = null,
    ) = Contact(id = id, name = name, phone = phone, isKitUser = isKitUser, avatarUrl = avatarUrl)

    @Test
    fun `local and international Uganda spellings have one keyed identity`() {
        val expected = identities.digest("+256712345678")

        assertEquals(expected, identities.digest("0712 345 678"))
        assertEquals(expected, identities.digest("256-712-345-678"))
        assertEquals(expected, identities.digest("00256 712 345 678"))
        assertEquals(64, expected?.length)
        assertTrue(expected.orEmpty().all { it.isDigit() || it in 'a'..'f' })
        assertTrue(!expected.orEmpty().contains("712345678"))
    }

    @Test
    fun `equal subscriber digits in different countries never collide`() {
        assertNotEquals(
            identities.digest("+254712345678"),
            identities.digest("+256712345678"),
        )
        assertEquals("+254712345678", canonicalContactPhone("254712345678"))
        assertEquals("+256712345678", canonicalContactPhone("0712345678"))
    }

    @Test
    fun `malformed masked and underspecified numbers have no identity`() {
        listOf(null, "", "1234567", "+254 7** *** 678", "*****5678", "+0", "+abc")
            .forEach { assertNull(identities.digest(it)) }
    }

    @Test
    fun `a masked or raw suffix can never resolve to a contact`() {
        val contacts = listOf(contact("user-1", "Asha", "+254712345678"))

        assertNull(BeneficiaryIdentity.contactFor("712345678", contacts, identities::digest))
        assertNull(
            BeneficiaryIdentity.avatarUrlFor(
                savedPhoneIdentity = identities.digest("+254 7** *** 678"),
                contacts = contacts,
                knownPhotos = mapOf("user-1" to "https://example.test/asha.jpg"),
                phoneIdentityOf = identities::digest,
            ),
        )
    }

    @Test
    fun `two different accounts on one canonical number resolve to nobody`() {
        val contacts = listOf(
            contact("user-1", "Asha", "+254712345678"),
            contact("user-2", "Someone else", "00254712345678"),
        )
        assertNull(
            BeneficiaryIdentity.contactFor(
                identities.digest("+254712345678"),
                contacts,
                identities::digest,
            ),
        )
    }

    @Test
    fun `duplicate rows for one nonempty account are not ambiguity`() {
        val contacts = listOf(
            contact(
                "user-1",
                "Asha",
                "+254712345678",
                avatarUrl = "https://example.test/asha.jpg",
            ),
            contact("USER-1", "Asha work", "00254712345678"),
            contact("", "Malformed", "+254712345678"),
        )
        assertEquals(
            "user-1",
            BeneficiaryIdentity.contactFor(
                identities.digest("+254712345678"),
                contacts,
                identities::digest,
            )?.id,
        )
    }

    @Test
    fun `a contact who is not on Kit Pay is never matched`() {
        val contacts = listOf(contact("user-1", "Asha", "+254712345678", isKitUser = false))
        assertNull(
            BeneficiaryIdentity.contactFor(
                identities.digest("+254712345678"),
                contacts,
                identities::digest,
            ),
        )
    }

    @Test
    fun `server identity and current owner cache win over local phone fallback`() {
        val contacts = listOf(
            contact(
                "user-1",
                "Asha",
                "+254712345678",
                avatarUrl = "https://example.test/stale.jpg",
            ),
        )
        assertEquals(
            "https://example.test/from-server.jpg",
            BeneficiaryIdentity.avatarUrlFor(
                kitUserId = "user-9",
                serverAvatarUrl = "https://example.test/from-server.jpg",
                savedPhoneIdentity = identities.digest("+254712345678"),
                contacts = contacts,
                knownPhotos = mapOf("user-9" to "https://example.test/cached.jpg"),
                phoneIdentityOf = identities::digest,
            ),
        )
        assertEquals(
            "https://example.test/cached.jpg",
            BeneficiaryIdentity.avatarUrlFor(
                kitUserId = "User-9",
                knownPhotos = mapOf("user-9" to "https://example.test/cached.jpg"),
            ),
        )
    }

    @Test
    fun `device-saved destination can use its exact contact and cached photo`() {
        val contacts = listOf(contact("user-1", "Asha", "+254712345678"))
        assertEquals(
            "https://example.test/cached.jpg",
            BeneficiaryIdentity.avatarUrlFor(
                savedPhoneIdentity = identities.digest("+254712345678"),
                contacts = contacts,
                knownPhotos = mapOf("user-1" to "https://example.test/cached.jpg"),
                phoneIdentityOf = identities::digest,
            ),
        )
    }

    @Test
    fun `malformed beneficiary identifiers are refused`() {
        assertEquals("bnf_123:mobile.1", canonicalBeneficiaryId(" bnf_123:mobile.1 "))
        listOf(null, "", "   ", "bad/id", "bad\nrow", "x".repeat(129))
            .forEach { assertNull(canonicalBeneficiaryId(it)) }
    }

    @Test
    fun `nothing known means initials`() {
        assertNull(BeneficiaryIdentity.avatarUrlFor())
    }
}
