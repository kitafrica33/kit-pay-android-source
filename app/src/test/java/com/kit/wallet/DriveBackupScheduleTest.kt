package com.kit.wallet

import com.kit.wallet.data.backup.DriveBackupState
import com.kit.wallet.data.backup.MessageBackupFrequency
import com.kit.wallet.data.backup.MessageBackupRunStatus
import com.kit.wallet.feature.backup.ChatBackupUiState
import com.kit.wallet.feature.backup.automaticBackupStatusText
import com.kit.wallet.feature.backup.formatByteSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test fun `an empty-history run waits for the selected cadence instead of spinning`() {
        val state = connected.copy(
            lastAttemptAtEpochMillis = 10 * day,
            lastRunStatus = MessageBackupRunStatus.NOTHING_TO_BACK_UP,
        )

        assertFalse(state.isDue(now = 10 * day + day / 2))
        assertTrue(state.isDue(now = 11 * day))
        assertEquals(11 * day, state.nextDueAtEpochMillis(now = 10 * day + day / 2))
    }

    @Test fun `a retryable failure remains immediately due`() {
        val state = connected.copy(
            lastAttemptAtEpochMillis = 10 * day,
            lastRunStatus = MessageBackupRunStatus.RETRYING,
            lastFailureAtEpochMillis = 10 * day,
            consecutiveFailures = 2,
        )

        assertTrue(state.isDue(now = 10 * day + 1))
        assertEquals(10 * day + 1, state.nextDueAtEpochMillis(now = 10 * day + 1))
    }

    @Test fun `a retry remains due even after a recent successful backup`() {
        val now = 10 * day + day / 2
        val state = connected.copy(
            lastBackupAtEpochMillis = 10 * day,
            lastAttemptAtEpochMillis = now - 1,
            lastRunStatus = MessageBackupRunStatus.RETRYING,
            lastFailureAtEpochMillis = now - 1,
            consecutiveFailures = 1,
        )

        assertTrue(state.isDue(now))
        assertEquals(now, state.nextDueAtEpochMillis(now))
    }

    @Test fun `an in-process running backup is not booked twice`() {
        val state = connected.copy(
            lastAttemptAtEpochMillis = 10 * day,
            lastRunStatus = MessageBackupRunStatus.RUNNING,
        )

        assertFalse(state.isDue(now = 10 * day + 1))
        assertNull(state.nextDueAtEpochMillis(now = 10 * day + 1))
    }

    @Test fun `a running marker from a dead process becomes a visible retry`() {
        val state = connected.copy(
            lastBackupAtEpochMillis = 9 * day,
            lastAttemptAtEpochMillis = 10 * day,
            lastRunStatus = MessageBackupRunStatus.RUNNING,
            consecutiveFailures = 2,
        ).normalizedAfterProcessRestart()

        assertEquals(MessageBackupRunStatus.RETRYING, state.lastRunStatus)
        assertEquals(10 * day, state.lastFailureAtEpochMillis)
        assertEquals(3, state.consecutiveFailures)
        assertTrue(state.isDue(now = 10 * day + 1))
        assertEquals(
            "The last backup could not reach Google Drive. Kit Pay will retry automatically.",
            automaticBackupStatusText(ChatBackupUiState(lastRunStatus = state.lastRunStatus)),
        )
    }

    @Test fun `next due is absent when disconnected or switched off`() {
        assertNull(connected.copy(connected = false).nextDueAtEpochMillis(10 * day))
        assertNull(
            connected.copy(frequency = MessageBackupFrequency.OFF)
                .nextDueAtEpochMillis(10 * day),
        )
    }

    @Test fun `automatic backup status explains recovery without exposing an exception`() {
        assertEquals(
            "The last backup could not reach Google Drive. Kit Pay will retry automatically.",
            automaticBackupStatusText(
                ChatBackupUiState(lastRunStatus = MessageBackupRunStatus.RETRYING),
            ),
        )
        assertEquals(
            "Reconnect Google Drive to resume automatic backup.",
            automaticBackupStatusText(
                ChatBackupUiState(lastRunStatus = MessageBackupRunStatus.NEEDS_SIGN_IN),
            ),
        )
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
