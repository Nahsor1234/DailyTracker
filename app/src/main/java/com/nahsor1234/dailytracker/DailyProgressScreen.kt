package com.nahsor1234.dailytracker

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

private const val MAX_DESCRIPTION_LENGTH = 5_000

private enum class SaveState {
    IDLE,
    SAVING,
    SAVED,
    FAILED
}

@Composable
internal fun DailyProgressScreen(repository: DailyEntryRepository, today: LocalDate) {
    val locale = remember { Locale.getDefault() }

    var displayedMonth by remember { mutableStateOf(YearMonth.from(today)) }
    var selectedDate by remember { mutableStateOf(today) }
    var selectedEntry by remember { mutableStateOf<DailyEntry?>(null) }
    var completedDates by remember { mutableStateOf<Set<LocalDate>>(emptySet()) }
    var isLoading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var saveState by remember { mutableStateOf(SaveState.IDLE) }
    var saveRevision by remember { mutableIntStateOf(0) }

    LaunchedEffect(today) {
        displayedMonth = YearMonth.from(today)
        selectedDate = today
    }

    DisposableEffect(selectedDate) {
        var active = true
        isLoading = true
        loadFailed = false
        repository.loadEntry(selectedDate) { result ->
            if (active) {
                result.fold(
                    onSuccess = { entry ->
                        selectedEntry = entry
                        isLoading = false
                        loadFailed = false
                        saveState = if (entry == null) SaveState.IDLE else SaveState.SAVED
                    },
                    onFailure = {
                        selectedEntry = null
                        isLoading = false
                        loadFailed = true
                    }
                )
            }
        }
        onDispose { active = false }
    }

    DisposableEffect(displayedMonth) {
        var active = true
        completedDates = emptySet()
        repository.loadCompletedDates(displayedMonth) { result ->
            if (active) {
                result.onSuccess { completedDates = it }
            }
        }
        onDispose { active = false }
    }

    fun save(updatedEntry: DailyEntry) {
        selectedEntry = updatedEntry
        saveState = SaveState.SAVING
        saveRevision += 1
        val requestRevision = saveRevision

        repository.saveEntry(updatedEntry) { result ->
            if (selectedDate == updatedEntry.date && requestRevision == saveRevision) {
                saveState = if (result.isSuccess) SaveState.SAVED else SaveState.FAILED
            }
            if (result.isSuccess && YearMonth.from(updatedEntry.date) == displayedMonth) {
                completedDates = if (updatedEntry.completed) {
                    completedDates + updatedEntry.date
                } else {
                    completedDates - updatedEntry.date
                }
            }
        }
    }

    val monthFormatter = remember(locale) {
        DateTimeFormatter.ofPattern("MMMM yyyy", locale)
    }
    val selectedDateFormatter = remember(locale) {
        DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", locale)
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(24.dp))
            Text(
                text = "DailyTracker",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Daily progress",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "A clear record of how each day went.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(22.dp))

            MonthCalendar(
                month = displayedMonth,
                selectedDate = selectedDate,
                today = today,
                completedDates = completedDates,
                locale = locale,
                monthLabel = displayedMonth.format(monthFormatter),
                onPreviousMonth = {
                    val target = displayedMonth.minusMonths(1)
                    displayedMonth = target
                    selectedDate = target.atDay(
                        selectedDate.dayOfMonth.coerceAtMost(target.lengthOfMonth())
                    )
                },
                onNextMonth = {
                    val target = displayedMonth.plusMonths(1)
                    displayedMonth = target
                    selectedDate = target.atDay(
                        selectedDate.dayOfMonth.coerceAtMost(target.lengthOfMonth())
                    )
                },
                onSelectDate = { selectedDate = it }
            )

            Spacer(Modifier.height(24.dp))
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    text = "SELECTED DAY",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = selectedDate.format(selectedDateFormatter),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = when {
                        selectedDate.isBefore(today) -> "Past days are available for review only."
                        selectedDate.isAfter(today) -> "You can record this day when it arrives."
                        else -> "Record what happened today."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(14.dp))

            when {
                isLoading -> InfoPanel(
                    title = "Loading day…",
                    description = "Opening the saved record on this device."
                )
                loadFailed -> InfoPanel(
                    title = "Couldn't load this day",
                    description = "Your existing records have not been changed. Select the day again to retry."
                )
                selectedDate.isAfter(today) -> InfoPanel(
                    title = "Not available yet",
                    description = "Daily records can only be created or edited on the current local calendar date."
                )
                selectedDate.isBefore(today) -> PastDayPanel(entry = selectedEntry)
                else -> TodayEditor(
                    entry = selectedEntry ?: DailyEntry(
                        date = selectedDate,
                        completed = false,
                        description = ""
                    ),
                    saveState = saveState,
                    onEntryChange = ::save
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun MonthCalendar(
    month: YearMonth,
    selectedDate: LocalDate,
    today: LocalDate,
    completedDates: Set<LocalDate>,
    locale: Locale,
    monthLabel: String,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onSelectDate: (LocalDate) -> Unit
) {
    val firstDayOfWeek = WeekFields.of(locale).firstDayOfWeek
    val weekdays = remember(locale) {
        (0..6).map { offset ->
            firstDayOfWeek.plus(offset.toLong())
                .getDisplayName(TextStyle.NARROW_STANDALONE, locale)
                .uppercase(locale)
        }
    }
    val calendarDays = remember(month, locale) {
        buildCalendarDays(month, firstDayOfWeek)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = monthLabel,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
                TextButton(onClick = onPreviousMonth, modifier = Modifier.size(40.dp)) {
                    Text("‹", fontSize = 27.sp, lineHeight = 27.sp)
                }
                TextButton(onClick = onNextMonth, modifier = Modifier.size(40.dp)) {
                    Text("›", fontSize = 27.sp, lineHeight = 27.sp)
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                weekdays.forEach { day ->
                    Text(
                        text = day,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            calendarDays.chunked(7).forEach { week ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    week.forEach { date ->
                        if (date == null) {
                            Spacer(
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .padding(2.dp)
                            )
                        } else {
                            CalendarDayCell(
                                date = date,
                                isToday = date == today,
                                isSelected = date == selectedDate,
                                isCompleted = date in completedDates,
                                isFuture = date.isAfter(today),
                                onClick = { onSelectDate(date) },
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .padding(2.dp)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
            Spacer(Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LegendItem(MaterialTheme.colorScheme.primary, "Completed")
                LegendItem(MaterialTheme.colorScheme.onSurface, "Today", outlined = true)
            }
        }
    }
}

@Composable
private fun CalendarDayCell(
    date: LocalDate,
    isToday: Boolean,
    isSelected: Boolean,
    isCompleted: Boolean,
    isFuture: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) scheme.primary else Color.Transparent,
        animationSpec = tween(durationMillis = 160),
        label = "calendar-day-background"
    )
    val textColor = when {
        isSelected -> scheme.onPrimary
        isFuture -> scheme.onSurfaceVariant.copy(alpha = 0.45f)
        else -> scheme.onSurface
    }
    val markerColor = when {
        isSelected && isCompleted -> scheme.onPrimary
        isCompleted -> scheme.primary
        else -> Color.Transparent
    }

    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(backgroundColor)
            .then(
                if (isToday && !isSelected) {
                    Modifier.border(1.dp, scheme.primary, CircleShape)
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = date.dayOfMonth.toString(),
                color = textColor,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isToday || isSelected) FontWeight.SemiBold else FontWeight.Normal
            )
            Spacer(
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(markerColor)
            )
        }
    }
}

@Composable
private fun LegendItem(
    color: Color,
    label: String,
    outlined: Boolean = false
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .then(
                    if (outlined) Modifier.border(1.dp, color, CircleShape)
                    else Modifier.background(color, CircleShape)
                )
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TodayEditor(
    entry: DailyEntry,
    saveState: SaveState,
    onEntryChange: (DailyEntry) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surface)
            .animateContentSize()
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = entry.completed,
                onCheckedChange = { onEntryChange(entry.copy(completed = it)) }
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "I've completed my day.",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = "DAILY DESCRIPTION",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = entry.description,
            onValueChange = { value ->
                if (value.length <= MAX_DESCRIPTION_LENGTH) {
                    onEntryChange(entry.copy(description = value))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            minLines = 5,
            maxLines = 8,
            label = { Text("What did you do today?") },
            placeholder = { Text("Write a short record of your day…") },
            supportingText = {
                Text(
                    text = "${entry.description.length} / $MAX_DESCRIPTION_LENGTH",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.End
                )
            },
            shape = RoundedCornerShape(16.dp)
        )

        Spacer(Modifier.height(10.dp))
        val statusText = when (saveState) {
            SaveState.IDLE -> "Changes save automatically on this device."
            SaveState.SAVING -> "Saving on this device…"
            SaveState.SAVED -> "Saved on this device."
            SaveState.FAILED -> "Couldn't save. Your text is still shown; edit again to retry."
        }
        val statusColor = if (saveState == SaveState.FAILED) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodySmall,
            color = statusColor
        )
        Text(
            text = "Cloud sync is not configured yet.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
        )
    }
}

@Composable
private fun PastDayPanel(entry: DailyEntry?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (entry?.completed == true) "Completed" else "Not marked complete",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "READ ONLY",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
        Text(
            text = entry?.description?.takeIf(String::isNotBlank)
                ?: "No description was saved for this day.",
            style = MaterialTheme.typography.bodyMedium,
            color = if (entry?.description.isNullOrBlank()) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            }
        )
        Text(
            text = "Past records cannot be edited.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun InfoPanel(title: String, description: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun buildCalendarDays(
    month: YearMonth,
    firstDayOfWeek: DayOfWeek
): List<LocalDate?> {
    val offset = (month.atDay(1).dayOfWeek.value - firstDayOfWeek.value + 7) % 7
    val days = buildList<LocalDate?> {
        repeat(offset) { add(null) }
        for (day in 1..month.lengthOfMonth()) add(month.atDay(day))
    }
    return days + List((7 - days.size % 7) % 7) { null }
}
