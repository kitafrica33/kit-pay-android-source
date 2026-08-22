package com.kit.wallet

import com.kit.wallet.data.remote.DeviceContactDto
import com.kit.wallet.data.repository.DeviceContactSyncCandidate
import com.kit.wallet.data.repository.sanitizeDeviceContactsForSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DeviceContactSyncSanitizerTest {
    @Test
    fun `malformed and service contacts do not suppress a valid contact`() {
        val contacts = sanitizeDeviceContactsForSync(
            sequenceOf(
                candidate("*165#", "Mobile money service"),
                candidate("911", "Emergency service"),
                candidate("flora@example.com", "SIP contact"),
                candidate("+256700000001,123", "Dial pause"),
                candidate("256+700000001", "Misplaced plus"),
                candidate("+256\u00a0700\u2011000\u2011001", "  Amina  ", favorite = true),
            ),
        )

        assertEquals(
            listOf(DeviceContactDto("+256700000001", "Amina", favorite = true)),
            contacts,
        )
    }

    @Test
    fun `overlong number is rejected rather than truncated into an existing number`() {
        val validPhone = "+256700000001"
        val contacts = sanitizeDeviceContactsForSync(
            sequenceOf(
                candidate(validPhone + "9".repeat(30), "Wrong contact"),
                candidate(validPhone, "Flora"),
            ),
        )

        assertEquals(listOf(DeviceContactDto(validPhone, "Flora")), contacts)
    }

    @Test
    fun `formatted duplicate numbers retain the first valid address book row`() {
        val contacts = sanitizeDeviceContactsForSync(
            sequenceOf(
                candidate("+256 (700) 000-001", "Amina", favorite = true),
                candidate("+256.700.000.001", "Duplicate"),
            ),
        )

        assertEquals(
            listOf(DeviceContactDto("+256700000001", "Amina", favorite = true)),
            contacts,
        )
    }

    @Test
    fun `name limit preserves a complete supplementary Unicode code point`() {
        val longName = "A".repeat(159) + "\uD83D\uDE00" + "discarded"
        val contact = sanitizeDeviceContactsForSync(
            sequenceOf(candidate("0700123456", longName)),
        ).single()

        assertEquals(160, contact.name.codePointCount(0, contact.name.length))
        assertEquals("A".repeat(159) + "\uD83D\uDE00", contact.name)
        assertFalse(contact.name.last().isHighSurrogate())
    }

    @Test
    fun `blank display name falls back to the complete sanitized phone`() {
        val contact = sanitizeDeviceContactsForSync(
            sequenceOf(candidate(" 0700 123 456 ", " \t ")),
        ).single()

        assertEquals(DeviceContactDto("+256700123456", "+256700123456"), contact)
    }

    @Test
    fun `invalid rows do not consume the upload limit`() {
        val contacts = sanitizeDeviceContactsForSync(
            sequenceOf(
                candidate("*123#", "Service"),
                candidate("0700123456", "First"),
                candidate("0700123457", "Second"),
                candidate("0700123458", "Beyond limit"),
            ),
            limit = 2,
        )

        assertEquals(
            listOf(
                DeviceContactDto("+256700123456", "First"),
                DeviceContactDto("+256700123457", "Second"),
            ),
            contacts,
        )
    }

    @Test
    fun `local and international Uganda formats normalize to one identity`() {
        val contacts = sanitizeDeviceContactsForSync(
            sequenceOf(
                candidate("0772 123 456", "Local"),
                candidate("+256 772 123 456", "International duplicate"),
                candidate("00256 701 234 567", "International prefix"),
            ),
        )

        assertEquals(
            listOf(
                DeviceContactDto("+256772123456", "Local"),
                DeviceContactDto("+256701234567", "International prefix"),
            ),
            contacts,
        )
    }

    @Test
    fun `foreign international numbers retain their country identity`() {
        val contacts = sanitizeDeviceContactsForSync(
            sequenceOf(
                candidate("+44 7700 900123", "London"),
                candidate("+256 700 900123", "Kampala"),
            ),
        )

        assertEquals(listOf("+447700900123", "+256700900123"), contacts.map { it.phone })
    }

    @Test
    fun `complete address books larger than ten thousand are retained`() {
        val contacts = sanitizeDeviceContactsForSync(
            (0..10_000).asSequence().map { index ->
                candidate("+2567${index.toString().padStart(8, '0')}", "Contact $index")
            },
        )

        assertEquals(10_001, contacts.size)
        assertEquals("Contact 10000", contacts.last().name)
    }

    private fun candidate(
        phone: String?,
        name: String?,
        favorite: Boolean = false,
    ) = DeviceContactSyncCandidate(phone, name, favorite)
}
