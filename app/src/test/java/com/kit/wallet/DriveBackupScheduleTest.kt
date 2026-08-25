package com.kit.wallet

import com.kit.wallet.data.backup.DriveBackupState
import com.kit.wallet.data.backup.MessageBackupFrequency
import com.kit.wallet.feature.backup.formatByteSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the overnight backup actually runs.
 *
 * This is decided entirely on the phone, from a stored timestamp, which makes the clock the thing
 * to be careful about: a device that believes it is a year in the future must not stop backing up
 * for a year once its clock is corrected.
 */
class DriveBackupScheduleTest {
    private val connected = DriveBackupState(
        accountId = "user-1",
        connected = true,
        frequency = MessageBackupFrequency.DAILY,
    )
    private val day = 24L * 60 * 60 * 1000

    @Test fun `a schedule that is off is never due`() {
        val off = connected.copy(frequency = MessageBackupFrequency.OFF)
        assertFalse(off.isDue(now = 10 * day))
    }

    @Test fun `a schedule that has never run is due immediately`() {
        assertTrue(connected.isDue(now = 1))
    }

    @Test fun `a backup taken within the interval is not due again`() {
        val state = connected.copy(lastBackupAtEpochMillis = 10 * day)
        assertFalse(state.isDue(now = 10 * day + day / 2))
    }

    @Test fun `a backup older than the interval is due`() {
        val state = connected.copy(lastBackupAtEpochMillis = 10 * day)
        assertTrue(state.isDue(now = 11 * day))
    }

    /**
     * A phone whose clock is corrected backwards — a fresh boot before NTP, or a user changing the
     * date — would otherwise record a backup in the future and never back up again.
     */
    @Test fun `a clock that jumped backwards does not postpone backups forever`() {
        val state = connected.copy(lastBackupAtEpochMillis = 100 * day)
        assertTrue(state.isDue(now = 10 * day))
    }

    @Test fun `weekly waits a week`() {
        val state = connected.copy(
            frequency = MessageBackupFrequency.WEEKLY,
            lastBackupAtEpochMillis = 0,
        )
        assertFalse(state.isDue(now = 6 * day))
        assertTrue(state.isDue(now = 7 * day))
    }
}

class BackupSizeFormatTest {
    /** Normalised, because the separator follows the device locale and the unit does not. */
    private fun size(bytes: Long) = formatByteSize(bytes).replace(',', '.')

    @Test fun `sizes are shown in the units a phone's storage screen uses`() {
        assertEquals("0 B", size(0))
        assertEquals("999 B", size(999))
        assertEquals("1 KB", size(1_000))
        assertEquals("2.4 MB", size(2_400_000))
        assertEquals("1.10 GB", size(1_100_000_000))
    }
}
