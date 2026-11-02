package com.nahsor1234.dailytracker

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JavaTextStyle
import java.time.temporal.WeekFields
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

private const val MAX_DESCRIPTION_LENGTH = 5_000

private enum class SaveState {
    IDLE,
    SAVING,
    SAVED,
    FAILED
}

@Composable
internal fun DailyProgressScreen(
    repository: DailyEntryRepository,
    today: LocalDate,
    cloud: DailyTrackerCloud,
    cloudAccountId: String?,
    cloudDataRevision: Int,
    onCloudSyncResult: (Result<Int>) -> Unit,
    modifier: Modifier = Modifier
) {
    val locale = remember { Locale.getDefault() }

    var displayedMonth by remember { mutableStateOf(YearMonth.from(today)) }
    var selectedDate by remember { mutableStateOf(today) }
    var selectedEntry by remember { mutableStateOf<DailyEntry?>(null) }
    // Completed days per month. The neighbouring months are kept cached, so changing month never
    // shows an empty calendar or month strip while the database answers.
    val completedByMonth = remember { mutableStateMapOf<YearMonth, Set<LocalDate>>() }
    val completedDates = completedByMonth[displayedMonth] ?: emptySet()
    // The date whose record is held in selectedEntry. The panel under the calendar is drawn for
    // this date, so changing the selection never flashes a "Loading" panel, and a record can never
    // be edited under the wrong date while the next one is still being read.
    var loadedDate by remember { mutableStateOf<LocalDate?>(null) }
    var isLoading by remember { mutableStateOf(true) } // only true until the first record arrives
    var loadFailed by remember { mutableStateOf(false) }
    var saveState by remember { mutableStateOf(SaveState.IDLE) }
    var saveRevision by remember { mutableIntStateOf(0) }
    val screenActive = remember { AtomicBoolean(true) }

    DisposableEffect(Unit) {
        onDispose { screenActive.set(false) }
    }

    LaunchedEffect(today) {
        displayedMonth = YearMonth.from(today)
        selectedDate = today
    }

    DisposableEffect(selectedDate) {
        var active = true
        val requested = selectedDate
        loadFailed = false
        repository.loadEntry(requested) { result ->
            if (active) {
                result.fold(
                    onSuccess = { entry ->
                        selectedEntry = entry
                        loadedDate = requested
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

    LaunchedEffect(displayedMonth) {
        fun load(month: YearMonth) {
            repository.loadCompletedDates(month) { result ->
                if (screenActive.get()) result.onSuccess { completedByMonth[month] = it }
            }
        }
        load(displayedMonth)
        listOf(displayedMonth.minusMonths(1), displayedMonth.plusMonths(1)).forEach { neighbour ->
            if (!completedByMonth.containsKey(neighbour)) load(neighbour)
        }
        // Keep the cache small.
        completedByMonth.keys.removeAll {
            kotlin.math.abs(java.time.temporal.ChronoUnit.MONTHS.between(it, displayedMonth)) > 3
        }
    }

    LaunchedEffect(cloudDataRevision) {
        if (cloudDataRevision > 0) {
            repository.loadEntry(selectedDate) { result ->
                if (screenActive.get()) result.onSuccess { selectedEntry = it }
            }
            listOf(displayedMonth, displayedMonth.minusMonths(1), displayedMonth.plusMonths(1)).forEach { month ->
                repository.loadCompletedDates(month) { result ->
                    if (screenActive.get()) result.onSuccess { completedByMonth[month] = it }
                }
            }
        }
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
            if (result.isSuccess && cloudAccountId != null) {
                repository.syncCloud(cloud, cloudAccountId, onCloudSyncResult)
            }
            if (result.isSuccess) {
                val month = YearMonth.from(updatedEntry.date)
                completedByMonth[month]?.let { current ->
                    completedByMonth[month] = if (updatedEntry.completed) {
                        current + updatedEntry.date
                    } else {
                        current - updatedEntry.date
                    }
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

    val weekdayFormatter = remember(locale) { DateTimeFormatter.ofPattern("EEEE", locale) }
    val dayFormatter = remember(locale) { DateTimeFormatter.ofPattern("d MMMM yyyy", locale) }
    val todayFormatter = remember(locale) { DateTimeFormatter.ofPattern("EEE, d MMM", locale) }
    val monthNameFormatter = remember(locale) { DateTimeFormatter.ofPattern("MMMM", locale) }
    val primary = MaterialTheme.colorScheme.primary
    val haptics = rememberHaptics()

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(
            Modifier.fillMaxSize().drawBehind {
                // Static ambient glow in the top corner; the content scrolls over it.
                val glowCenter = Offset(size.width * 0.85f, 0f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(primary.copy(alpha = 0.14f), Color.Transparent),
                        center = glowCenter,
                        radius = size.width * 0.95f
                    ),
                    radius = size.width * 0.95f,
                    center = glowCenter
                )
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
            ) {
                Spacer(Modifier.height(28.dp))
                ProgressHeader(
                    todayLabel = today.format(todayFormatter).uppercase(locale),
                    monthName = displayedMonth.format(monthNameFormatter),
                    month = displayedMonth,
                    completedDates = completedDates,
                    today = today
                )
                Spacer(Modifier.height(22.dp))

                MonthCalendar(
                    month = displayedMonth,
                    selectedDate = selectedDate,
                    today = today,
                    completedFor = { completedByMonth[it] ?: emptySet() },
                    locale = locale,
                    onPreviousMonth = {
                        haptics.select()
                        val target = displayedMonth.minusMonths(1)
                        displayedMonth = target
                        selectedDate = target.atDay(
                            selectedDate.dayOfMonth.coerceAtMost(target.lengthOfMonth())
                        )
                    },
                    onNextMonth = {
                        haptics.select()
                        val target = displayedMonth.plusMonths(1)
                        displayedMonth = target
                        selectedDate = target.atDay(
                            selectedDate.dayOfMonth.coerceAtMost(target.lengthOfMonth())
                        )
                    },
                    onSelectDate = {
                        if (it != selectedDate) haptics.select()
                        selectedDate = it
                    }
                )

                Spacer(Modifier.height(28.dp))
                Text(
                    text = "SELECTED DAY",
                    color = MaterialTheme.colorScheme.primary,
                    style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
                )
                Spacer(Modifier.height(6.dp))
                AnimatedContent(
                    targetState = selectedDate,
                    transitionSpec = {
                        (slideInVertically(tween(260)) { it / 3 } + fadeIn(tween(220))) togetherWith
                            (slideOutVertically(tween(200)) { -it / 3 } + fadeOut(tween(140)))
                    },
                    label = "selected-day-title"
                ) { shown ->
                    Column {
                        Text(
                            text = shown.format(weekdayFormatter),
                            color = MaterialTheme.colorScheme.onBackground,
                            style = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Light, letterSpacing = (-0.5).sp)
                        )
                        Text(
                            text = shown.format(dayFormatter),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = when {
                        selectedDate.isBefore(today) -> "Past days are available for review only."
                        selectedDate.isAfter(today) -> "You can record this day when it arrives."
                        else -> "Record what happened today."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(16.dp))

                val panelDate = loadedDate ?: selectedDate
                Box(Modifier.animateContentSize(tween(260, easing = FastOutSlowInEasing))) {
                Reveal(key = panelDate) {
                    when {
                        isLoading -> InfoPanel(
                            title = "Loading day…",
                            description = "Opening the saved record on this device."
                        )
                        loadFailed -> InfoPanel(
                            title = "Couldn't load this day",
                            description = "Your existing records have not been changed. Select the day again to retry."
                        )
                        panelDate.isAfter(today) -> InfoPanel(
                            title = "Not available yet",
                            description = "Daily records can only be created or edited on the current local calendar date."
                        )
                        panelDate.isBefore(today) -> PastDayPanel(entry = selectedEntry)
                        else -> TodayEditor(
                            entry = selectedEntry ?: DailyEntry(
                                date = panelDate,
                                completed = false,
                                description = ""
                            ),
                            saveState = saveState,
                            onEntryChange = ::save
                        )
                    }
                }
                }

                Spacer(Modifier.navigationBarsPadding().height(120.dp))
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Shared building blocks
// ---------------------------------------------------------------------------------------------

private val CardShape = RoundedCornerShape(28.dp)
private val HairlineColor = Color.White.copy(alpha = 0.08f)
private val CardFill = Color.White.copy(alpha = 0.04f)

private fun Modifier.glassCard(): Modifier =
    this.fillMaxWidth()
        .clip(CardShape)
        .background(CardFill)
        .border(1.dp, HairlineColor, CardShape)

/** Fades and lifts its content in whenever [key] changes. */
@Composable
private fun Reveal(key: Any, content: @Composable () -> Unit) {
    val progress = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { progress.animateTo(1f, tween(380, easing = FastOutSlowInEasing)) }
    Box(
        Modifier.graphicsLayer {
            alpha = progress.value
            translationY = (1f - progress.value) * 22.dp.toPx()
        }
    ) { content() }
}

@Composable
private fun ProgressHeader(
    todayLabel: String,
    monthName: String,
    month: YearMonth,
    completedDates: Set<LocalDate>,
    today: LocalDate
) {
    val scheme = MaterialTheme.colorScheme
    val shownCount by animateIntAsState(completedDates.size, tween(700), label = "month-count")

    Text(
        text = todayLabel,
        color = scheme.primary,
        style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.4.sp)
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = "Progress",
        color = scheme.onBackground,
        style = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Light, letterSpacing = (-1).sp)
    )
    Spacer(Modifier.height(18.dp))
    MonthStrip(month, completedDates, today)
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = shownCount.toString(),
            color = scheme.onBackground,
            style = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Light, fontFeatureSettings = "tnum")
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "days completed in $monthName",
            modifier = Modifier.padding(bottom = 5.dp),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant
        )
    }
}

/** One capsule per day of the month; completed days fill in with a left-to-right stagger. */
@Composable
private fun MonthStrip(month: YearMonth, completedDates: Set<LocalDate>, today: LocalDate) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().height(14.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (day in 1..month.lengthOfMonth()) {
            key(day) {
                val date = month.atDay(day)
                val color by animateColorAsState(
                    targetValue = when {
                        date in completedDates -> scheme.primary
                        date.isAfter(today) -> Color.White.copy(alpha = 0.05f)
                        else -> Color.White.copy(alpha = 0.14f)
                    },
                    animationSpec = tween(350, delayMillis = day * 8),
                    label = "strip-color"
                )
                val height by animateDpAsState(
                    targetValue = if (date == today) 14.dp else 8.dp,
                    animationSpec = tween(300),
                    label = "strip-height"
                )
                Box(
                    Modifier.weight(1f).height(height).clip(CircleShape).drawBehind { drawRect(color) }
                )
            }
        }
    }
}

@Composable
private fun MonthCalendar(
    month: YearMonth,
    selectedDate: LocalDate,
    today: LocalDate,
    completedFor: (YearMonth) -> Set<LocalDate>,
    locale: Locale,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onSelectDate: (LocalDate) -> Unit
) {
    val firstDayOfWeek = WeekFields.of(locale).firstDayOfWeek
    val weekdays = remember(locale) {
        (0..6).map { offset ->
            firstDayOfWeek.plus(offset.toLong())
                .getDisplayName(JavaTextStyle.NARROW_STANDALONE, locale)
                .uppercase(locale)
        }
    }
    val labelFormatter = remember(locale) { DateTimeFormatter.ofPattern("MMMM yyyy", locale) }

    Column(Modifier.glassCard().padding(18.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AnimatedContent(
                targetState = month,
                modifier = Modifier.weight(1f),
                transitionSpec = {
                    val direction = if (targetState > initialState) 1 else -1
                    (slideInHorizontally(tween(320)) { it * direction / 3 } + fadeIn(tween(220))) togetherWith
                        (slideOutHorizontally(tween(320)) { -it * direction / 3 } + fadeOut(tween(160)))
                },
                label = "month-label"
            ) { shown ->
                Text(
                    text = shown.format(labelFormatter),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Light)
                )
            }
            NavChevron(forward = false, description = "Previous month", onClick = onPreviousMonth)
            Spacer(Modifier.width(8.dp))
            NavChevron(forward = true, description = "Next month", onClick = onNextMonth)
        }

        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth()) {
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

        AnimatedContent(
            targetState = month,
            transitionSpec = {
                val direction = if (targetState > initialState) 1 else -1
                ((slideInHorizontally(tween(340)) { it * direction / 4 } + fadeIn(tween(240))) togetherWith
                    (slideOutHorizontally(tween(340)) { -it * direction / 4 } + fadeOut(tween(160))))
                    // The grid always has six rows, so its size never changes between months.
                    .using(SizeTransform(clip = false) { _, _ -> snap() })
            },
            label = "calendar-grid"
        ) { shownMonth ->
            // Each grid uses its own month's data, so the outgoing grid keeps its markers while it
            // slides away instead of switching to the new month's data mid-animation.
            val completed = completedFor(shownMonth)
            val days = remember(shownMonth, locale) {
                val built = buildCalendarDays(shownMonth, firstDayOfWeek)
                built + List((42 - built.size).coerceAtLeast(0)) { null }
            }
            Column(Modifier.fillMaxWidth()) {
                days.chunked(7).forEach { week ->
                    Row(Modifier.fillMaxWidth()) {
                        week.forEach { date ->
                            if (date == null) {
                                Spacer(Modifier.weight(1f).aspectRatio(1f).padding(2.dp))
                            } else {
                                CalendarDayCell(
                                    date = date,
                                    isToday = date == today,
                                    isSelected = date == selectedDate,
                                    isCompleted = date in completed,
                                    isFuture = date.isAfter(today),
                                    onClick = { onSelectDate(date) },
                                    modifier = Modifier.weight(1f).aspectRatio(1f).padding(2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
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

@Composable
private fun NavChevron(forward: Boolean, description: String, onClick: () -> Unit) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    val source = rememberPressSource()
    Box(
        Modifier.pressScale(source).size(40.dp).clip(CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
            .hapticClickable(source, haptic = {}, onClick = onClick)
            .semantics {
                contentDescription = description
                role = Role.Button
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(16.dp)) {
            val w = size.width
            val h = size.height
            val path = Path().apply {
                if (forward) {
                    moveTo(w * 0.38f, h * 0.15f)
                    lineTo(w * 0.70f, h * 0.50f)
                    lineTo(w * 0.38f, h * 0.85f)
                } else {
                    moveTo(w * 0.62f, h * 0.15f)
                    lineTo(w * 0.30f, h * 0.50f)
                    lineTo(w * 0.62f, h * 0.85f)
                }
            }
            drawPath(
                path, tint,
                style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
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
    // One animated value per cell (was five). Colours, scale, ring and glow are all derived from
    // it while drawing, so a selection change does not recompose the 42 cells of the grid.
    val selection by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0f,
        animationSpec = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium),
        label = "day-selection"
    )
    val primary = scheme.primary
    val restingFill = primary.copy(alpha = if (isCompleted) 0.16f else 0f)
    val restingText = if (isFuture) scheme.onSurfaceVariant.copy(alpha = 0.4f) else scheme.onSurface
    val textColor = lerp(restingText, scheme.onPrimary, selection.coerceIn(0f, 1f))
    val markerColor = if (isCompleted) lerp(primary, scheme.onPrimary, selection.coerceIn(0f, 1f)) else Color.Transparent

    Box(modifier, contentAlignment = Alignment.Center) {
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer {
                    val scale = 0.9f + 0.1f * selection
                    scaleX = scale
                    scaleY = scale
                }
                .drawBehind {
                    val t = selection.coerceIn(0f, 1f)
                    // Soft glow that blooms around the selected day.
                    if (t > 0.01f) {
                        drawCircle(primary.copy(alpha = 0.22f * t), radius = size.minDimension / 2f * (1f + 0.22f * t))
                    }
                    drawCircle(lerp(restingFill, primary, t))
                    if (isToday) {
                        drawCircle(
                            primary.copy(alpha = 1f - t),
                            radius = size.minDimension / 2f - 0.5.dp.toPx(),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx())
                        )
                    }
                }
        )
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
                Modifier.padding(top = 2.dp).size(4.dp).clip(CircleShape).background(markerColor)
            )
        }
        Box(Modifier.matchParentSize().clip(CircleShape).clickable(onClick = onClick))
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

// ---------------------------------------------------------------------------------------------
// Day panels
// ---------------------------------------------------------------------------------------------

@Composable
private fun TodayEditor(
    entry: DailyEntry,
    saveState: SaveState,
    onEntryChange: (DailyEntry) -> Unit
) {
    Column(Modifier.glassCard().animateContentSize().padding(18.dp)) {
        CompletionToggle(
            completed = entry.completed,
            onChange = { onEntryChange(entry.copy(completed = it)) }
        )

        Spacer(Modifier.height(14.dp))
        Text(
            text = "DAILY DESCRIPTION",
            color = MaterialTheme.colorScheme.primary,
            style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
        )
        Spacer(Modifier.height(8.dp))
        DescriptionField(
            value = entry.description,
            onValueChange = { onEntryChange(entry.copy(description = it)) }
        )

        Spacer(Modifier.height(14.dp))
        SaveIndicator(saveState)
        Spacer(Modifier.height(2.dp))
        Text(
            text = "Cloud sync is not configured yet.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

/** Large circular toggle: ring fills, the check mark draws itself, and a small burst fires on completion. */
@Composable
private fun CompletionToggle(completed: Boolean, onChange: (Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    val check = remember { Animatable(if (completed) 1f else 0f) }
    val burst = remember { Animatable(1f) } // 1f = finished, nothing visible
    var previous by remember { mutableStateOf(completed) }

    LaunchedEffect(completed) {
        check.animateTo(if (completed) 1f else 0f, tween(if (completed) 380 else 200))
    }
    LaunchedEffect(completed) {
        if (completed && !previous) {
            burst.snapTo(0f)
            burst.animateTo(1f, tween(750, easing = FastOutSlowInEasing))
        }
        previous = completed
    }

    Row(
        Modifier.fillMaxWidth()
            .toggleable(
                value = completed,
                role = Role.Checkbox,
                onValueChange = {
                    if (it) haptics.confirm() else haptics.toggle(false)
                    onChange(it)
                }
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(64.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val c = center
                val r = 21.dp.toPx()
                val fillAmount = check.value

                drawCircle(Color.White.copy(alpha = 0.16f), r, c, style = Stroke(1.5.dp.toPx()))
                if (fillAmount > 0f) drawCircle(scheme.primary, r * fillAmount, c)

                val t = ((fillAmount - 0.35f) / 0.65f).coerceIn(0f, 1f)
                if (t > 0f) {
                    val tick = Path().apply {
                        moveTo(c.x - 8.dp.toPx(), c.y + 0.5.dp.toPx())
                        lineTo(c.x - 2.5.dp.toPx(), c.y + 6.dp.toPx())
                        lineTo(c.x + 8.5.dp.toPx(), c.y - 6.dp.toPx())
                    }
                    val measure = PathMeasure()
                    measure.setPath(tick, false)
                    val partial = Path()
                    measure.getSegment(0f, measure.length * t, partial, true)
                    drawPath(
                        partial, scheme.onPrimary,
                        style = Stroke(2.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }

                val b = burst.value
                if (b < 1f) {
                    val fade = 1f - b
                    drawCircle(
                        scheme.primary.copy(alpha = 0.55f * fade),
                        r + 14.dp.toPx() * b, c, style = Stroke(2.dp.toPx())
                    )
                    for (i in 0 until 10) {
                        val angle = Math.toRadians(i * 36.0 + 18.0)
                        val dist = r + 4.dp.toPx() + 14.dp.toPx() * b
                        drawCircle(
                            scheme.primary.copy(alpha = fade),
                            2.4.dp.toPx() * fade,
                            Offset(
                                c.x + kotlin.math.cos(angle).toFloat() * dist,
                                c.y + kotlin.math.sin(angle).toFloat() * dist
                            )
                        )
                    }
                }
            }
        }
        Spacer(Modifier.width(6.dp))
        Column {
            Crossfade(targetState = completed, animationSpec = tween(200), label = "toggle-title") { done ->
                Text(
                    text = if (done) "Day complete" else "Mark today complete",
                    color = scheme.onSurface,
                    style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium)
                )
            }
            Text(
                text = "Tap to toggle",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DescriptionField(value: String, onValueChange: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)
    val borderColor by animateColorAsState(
        targetValue = if (focused) scheme.primary else Color.White.copy(alpha = 0.12f),
        animationSpec = tween(220),
        label = "field-border"
    )
    val nearLimit = value.length > MAX_DESCRIPTION_LENGTH * 9 / 10
    val counterColor by animateColorAsState(
        targetValue = if (nearLimit) scheme.error else scheme.onSurfaceVariant,
        animationSpec = tween(200),
        label = "counter-color"
    )

    Column {
        Box(
            Modifier.fillMaxWidth().heightIn(min = 168.dp)
                .clip(shape)
                .background(Color.Black.copy(alpha = 0.18f))
                .border(1.dp, borderColor, shape)
                .padding(14.dp)
        ) {
            BasicTextField(
                value = value,
                onValueChange = { if (it.length <= MAX_DESCRIPTION_LENGTH) onValueChange(it) },
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
                textStyle = TextStyle(color = scheme.onSurface, fontSize = 15.sp, lineHeight = 22.sp),
                cursorBrush = SolidColor(scheme.primary),
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) {
                            Text(
                                "Write a short record of your day…",
                                color = scheme.onSurfaceVariant.copy(alpha = 0.6f),
                                style = TextStyle(fontSize = 15.sp, lineHeight = 22.sp)
                            )
                        }
                        inner()
                    }
                }
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "${value.length} / $MAX_DESCRIPTION_LENGTH",
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.End,
            color = counterColor,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun SaveIndicator(saveState: SaveState) {
    val scheme = MaterialTheme.colorScheme
    val pulse by rememberInfiniteTransition(label = "save-pulse").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
        label = "save-pulse-value"
    )
    val dotColor by animateColorAsState(
        targetValue = when (saveState) {
            SaveState.IDLE -> scheme.onSurfaceVariant.copy(alpha = 0.5f)
            SaveState.SAVING, SaveState.SAVED -> scheme.primary
            SaveState.FAILED -> scheme.error
        },
        animationSpec = tween(250),
        label = "save-dot"
    )
    val statusText = when (saveState) {
        SaveState.IDLE -> "Changes save automatically on this device."
        SaveState.SAVING -> "Saving on this device…"
        SaveState.SAVED -> "Saved on this device."
        SaveState.FAILED -> "Couldn't save. Your text is still shown; edit again to retry."
    }
    val statusColor = if (saveState == SaveState.FAILED) scheme.error else scheme.onSurfaceVariant

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(8.dp)
                .graphicsLayer { alpha = if (saveState == SaveState.SAVING) pulse else 1f }
                .background(dotColor, CircleShape)
        )
        Spacer(Modifier.width(8.dp))
        Crossfade(targetState = statusText, animationSpec = tween(180), label = "save-text") { text ->
            Text(text, style = MaterialTheme.typography.bodySmall, color = statusColor)
        }
    }
}

@Composable
private fun PastDayPanel(entry: DailyEntry?) {
    val scheme = MaterialTheme.colorScheme
    val completed = entry?.completed == true
    Column(
        Modifier.glassCard().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CheckBadge(completed)
            Spacer(Modifier.width(10.dp))
            Text(
                text = if (completed) "Completed" else "Not marked complete",
                color = scheme.onSurface,
                style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium)
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "READ ONLY",
                modifier = Modifier
                    .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                color = scheme.onSurfaceVariant,
                style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.5.sp)
            )
        }
        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
        Text(
            text = entry?.description?.takeIf(String::isNotBlank)
                ?: "No description was saved for this day.",
            style = MaterialTheme.typography.bodyMedium,
            color = if (entry?.description.isNullOrBlank()) scheme.onSurfaceVariant else scheme.onSurface
        )
        Text(
            text = "Past records cannot be edited.",
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CheckBadge(completed: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Canvas(Modifier.size(28.dp)) {
        val c = center
        val r = size.minDimension / 2f - 1.dp.toPx()
        if (completed) {
            drawCircle(scheme.primary, r, c)
            val tick = Path().apply {
                moveTo(c.x - 5.dp.toPx(), c.y + 0.3.dp.toPx())
                lineTo(c.x - 1.5.dp.toPx(), c.y + 4.dp.toPx())
                lineTo(c.x + 5.5.dp.toPx(), c.y - 4.dp.toPx())
            }
            drawPath(
                tick, scheme.onPrimary,
                style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        } else {
            drawCircle(Color.White.copy(alpha = 0.18f), r, c, style = Stroke(1.5.dp.toPx()))
        }
    }
}

@Composable
private fun InfoPanel(title: String, description: String) {