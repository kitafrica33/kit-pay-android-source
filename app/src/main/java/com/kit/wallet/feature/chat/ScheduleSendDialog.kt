package com.kit.wallet.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kit.wallet.data.messaging.ScheduledSend
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** One tap that means a whole send time, so the common cases never open a picker at all. */
internal data class ScheduleSendPreset(val label: String, val atEpochMillis: Long)

/**
 * Wall-clock time that recomposes as it passes.
 *
 * "Today 23:50" has to stop saying Today at midnight, and a scheduled bubble can sit on screen for
 * hours. A half-minute tick is far finer than the labels it feeds and costs one coroutine.
 */
@Composable
internal fun rememberNowEpochMillis(tickMillis: Long = 30_000L): Long {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(tickMillis) {
        while (true) {
            delay(tickMillis)
            now = System.currentTimeMillis()
        }
    }
    return now
}

/**
 * The two or three times somebody actually means by "later", for the moment they are asking.
 *
 * Only offered when they are far enough out to be a real choice: "This evening" at ten past six is
 * not a schedule, it is a send, and offering it would be a slower way of pressing Send.
 */
internal fun scheduleSendPresets(
    nowEpochMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): List<ScheduleSendPreset> {
    val now = Instant.ofEpochMilli(nowEpochMillis).atZone(zone)
    val today = now.toLocalDate()
    val candidates = listOf(
        ScheduleSendPreset(
            "In an hour",
            now.plusHours(1).truncatedTo(ChronoUnit.MINUTES).toInstant().toEpochMilli(),
        ),
        ScheduleSendPreset(
            "This evening",
            today.atTime(EVENING).atZone(zone).toInstant().toEpochMilli(),
        ),
        ScheduleSendPreset(
            "Tomorrow morning",
            today.plusDays(1).atTime(MORNING).atZone(zone).toInstant().toEpochMilli(),
        ),
        ScheduleSendPreset(
            "Next Monday morning",
            nextMonday(today).atTime(MORNING).atZone(zone).toInstant().toEpochMilli(),
        ),
    )
    return candidates.filter { ScheduledSend.schedulingError(it.atEpochMillis, nowEpochMillis) == null }
}

private fun nextMonday(from: LocalDate): LocalDate {
    var candidate = from.plusDays(1)
    while (candidate.dayOfWeek.value != 1) candidate = candidate.plusDays(1)
    return candidate
}

/**
 * The send time a date cell and a clock face add up to.
 *
 * Material's date picker answers in UTC midnight of the day that was tapped, which is a date and
 * not an instant; the time is read in the device's own zone, because that is the clock the person
 * setting it is looking at.
 */
internal fun scheduledSendEpochMillis(
    dateUtcMillis: Long,
    hour: Int,
    minute: Int,
    zone: ZoneId = ZoneId.systemDefault(),
): Long = Instant.ofEpochMilli(dateUtcMillis)
    .atZone(ZoneOffset.UTC)
    .toLocalDate()
    .atTime(hour, minute)
    .atZone(zone)
    .toInstant()
    .toEpochMilli()

/**
 * When a scheduled entry is due, in the fewest words that still say it exactly.
 *
 * Days near today are named rather than dated, because "Tomorrow 08:00" is read at a glance and
 * "26 Aug 08:00" has to be worked out. The year appears only when it is not this one.
 */
internal fun formatScheduledFor(
    atEpochMillis: Long,
    nowEpochMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val at = Instant.ofEpochMilli(atEpochMillis).atZone(zone)
    val today = Instant.ofEpochMilli(nowEpochMillis).atZone(zone).toLocalDate()
    val date = at.toLocalDate()
    val clock = TIME_FORMAT.format(at)
    val days = ChronoUnit.DAYS.between(today, date)
    return when {
        days == 0L -> "Today $clock"
        days == 1L -> "Tomorrow $clock"
        days in 2..6 -> "${WEEKDAY_FORMAT.format(at)} $clock"
        date.year == today.year -> "${DAY_MONTH_FORMAT.format(at)} $clock"
        else -> "${DAY_MONTH_YEAR_FORMAT.format(at)} $clock"
    }
}

/**
 * Picks the time a message or request will go out.
 *
 * Presets first, a full date-and-time picker behind one more tap, and the same validation the queue
 * itself applies — so the picker can never accept a time the dispatcher would refuse.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduleSendDialog(
    heading: String,
    confirmLabel: String,
    nowEpochMillis: Long,
    initialEpochMillis: Long? = null,
    explanation: String = "It stays on this device, encrypted, until it goes out.",
    onDismiss: () -> Unit,
    onSchedule: (Long) -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    var stage by remember { mutableStateOf(if (initialEpochMillis == null) Stage.PRESETS else Stage.DATE) }
    var problem by remember { mutableStateOf<String?>(null) }

    val seed = remember(initialEpochMillis, nowEpochMillis) {
        Instant.ofEpochMilli(initialEpochMillis ?: (nowEpochMillis + DEFAULT_LEAD_MILLIS))
            .atZone(zone)
    }
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = seed.toLocalDate()
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli(),
        selectableDates = remember(nowEpochMillis) { ScheduleSelectableDates(nowEpochMillis, zone) },
    )
    // Clock format deliberately left to the device: `rememberTimePickerState` reads the system
    // setting, so somebody on a 24-hour phone is not handed an AM/PM dial.
    val timePickerState = rememberTimePickerState(
        initialHour = seed.hour,
        initialMinute = seed.minute,
    )

    fun confirm(atEpochMillis: Long) {
        val error = ScheduledSend.schedulingError(atEpochMillis, nowEpochMillis)
        if (error != null) {
            problem = error
            return
        }
        onSchedule(atEpochMillis)
    }

    when (stage) {
        Stage.PRESETS -> {
            val presets = remember(nowEpochMillis) { scheduleSendPresets(nowEpochMillis, zone) }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(heading) },
                text = {
                    Column {
                        Text(
                            explanation,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Time zone: ${zone.id}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        presets.forEach { preset ->
                            ScheduleChoiceRow(
                                icon = Icons.Rounded.Schedule,
                                label = preset.label,
                                detail = formatScheduledFor(preset.atEpochMillis, nowEpochMillis, zone),
                                onClick = { confirm(preset.atEpochMillis) },
                            )
                        }
                        ScheduleChoiceRow(
                            icon = Icons.Rounded.CalendarMonth,
                            label = "Pick a date and time",
                            detail = null,
                            onClick = {
                                problem = null
                                stage = Stage.DATE
                            },
                        )
                        problem?.let { message ->
                            Spacer(Modifier.height(8.dp))
                            Text(
                                message,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
            )
        }
        Stage.DATE -> AlertDialog(
            onDismissRequest = onDismiss,
            title = null,
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    DatePicker(state = datePickerState, title = null)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = datePickerState.selectedDateMillis != null,
                    onClick = { stage = Stage.TIME },
                ) { Text("Next") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
        Stage.TIME -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(heading) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    TimePicker(state = timePickerState)
                    problem?.let { message ->
                        Text(
                            message,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val date = datePickerState.selectedDateMillis ?: return@TextButton
                        confirm(
                            scheduledSendEpochMillis(
                                dateUtcMillis = date,
                                hour = timePickerState.hour,
                                minute = timePickerState.minute,
                                zone = zone,
                            ),
                        )
                    },
                ) { Text(confirmLabel) }
            },
            dismissButton = { TextButton(onClick = { stage = Stage.DATE }) { Text("Back") } },
        )
    }
}

@Composable
private fun ScheduleChoiceRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    detail: String?,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .size(32.dp)
                .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(17.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Today through a year out, matching the queue's own horizon so the two cannot disagree. */
@OptIn(ExperimentalMaterial3Api::class)
private class ScheduleSelectableDates(
    private val nowEpochMillis: Long,
    private val zone: ZoneId,
) : SelectableDates {
    private val first = Instant.ofEpochMilli(nowEpochMillis).atZone(zone).toLocalDate()
    private val last = first.plusDays(365)

    override fun isSelectableDate(utcTimeMillis: Long): Boolean {
        val date = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()
        return !date.isBefore(first) && !date.isAfter(last)
    }

    override fun isSelectableYear(year: Int): Boolean = year in first.year..last.year
}

private enum class Stage { PRESETS, DATE, TIME }

private val EVENING: LocalTime = LocalTime.of(18, 0)
private val MORNING: LocalTime = LocalTime.of(8, 0)

/** Where the manual picker opens when there is nothing to reopen: an hour out, on the hour. */
private const val DEFAULT_LEAD_MILLIS = 60L * 60 * 1_000

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val WEEKDAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE")
private val DAY_MONTH_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")
private val DAY_MONTH_YEAR_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")
