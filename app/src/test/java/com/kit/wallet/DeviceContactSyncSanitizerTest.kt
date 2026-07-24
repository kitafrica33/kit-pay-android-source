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
                candidate("+256761146015,123", "Dial pause"),
                candidate("256+761146015", "Misplaced plus"),
                candidate("+256\u00a0761\u2011146\u2011015", "  Flora  ", favorite = true),
            ),
        )

        assertEquals(
            listOf(DeviceContactDto("+256761146015", "Flora", favorite = true)),
            contacts,
        )
    }

    @Test
    fun `overlong number is rejected rather than truncated into an existing number`() {
        val validPhone = "+256761146015"
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
                candidate("+256 (761) 146-015", "Flora", favorite = true),
                candidate("+256.761.146.015", "Duplicate"),
            ),
        )

        assertEquals(
            listOf(DeviceContactDto("+256761146015", "Flora", favorite = true)),
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

        assertEquals(DeviceContactDto("0700123456", "0700123456"), contact)
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
                DeviceContactDto("0700123456", "First"),
                DeviceContactDto("0700123457", "Second"),
            ),
            contacts,
        )
    }

    private fun candidate(
        phone: String?,
        name: String?,
        favorite: Boolean = false,
    ) = DeviceContactSyncCandidate(phone, name, favorite)
}
