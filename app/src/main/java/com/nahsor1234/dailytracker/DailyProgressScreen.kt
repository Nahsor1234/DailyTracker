package com.nahsor1234.dailytracker

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
private const val MAX_NOTE_LENGTH = 500

/** Dates with a note are tinted with this warm colour (completed days use the green primary). */
private val NoteColor = Color(0xFFE8C77A)

private val YearMonthSaver = Saver<YearMonth, String>(save = { it.toString() }, restore = { YearMonth.parse(it) })
private val LocalDateSaver = Saver<LocalDate, String>(save = { it.toString() }, restore = { LocalDate.parse(it) })

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

    // Saved, so rotating the phone keeps the month and day you were looking at.
    var displayedMonth by rememberSaveable(stateSaver = YearMonthSaver) { mutableStateOf(YearMonth.from(today)) }
    var selectedDate by rememberSaveable(stateSaver = LocalDateSaver) { mutableStateOf(today) }
    var knownToday by rememberSaveable(stateSaver = LocalDateSaver) { mutableStateOf(today) }
    val notesByDate = remember { mutableStateMapOf<LocalDate, String>() }
    var noteEditorDate by remember { mutableStateOf<LocalDate?>(null) }
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

    // Jump to the new day only when the date really changed (midnight), not on every recreation.
    LaunchedEffect(today) {
        if (knownToday != today) {
            knownToday = today
            displayedMonth = YearMonth.from(today)
            selectedDate = today
        }
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
        repository.loadNotes(
            displayedMonth.minusMonths(1).atDay(1),
            displayedMonth.plusMonths(1).atEndOfMonth()
        ) { result ->
            if (screenActive.get()) result.onSuccess { loaded ->
                val from = displayedMonth.minusMonths(1).atDay(1)
                val to = displayedMonth.plusMonths(1).atEndOfMonth()
                notesByDate.keys.removeAll { it >= from && it <= to }
                notesByDate.putAll(loaded)
            }
        }
        // Keep the cache small.
        completedByMonth.keys.removeAll {
            kotlin.math.abs(java.time.temporal.ChronoUnit.MONTHS.between(it, displayedMonth)) > 3
        }
    }

    LaunchedEffect(cloudDataRevision) {
        if (cloudDataRevision > 0) {
            // A sync result must never replace what is on screen while an edit is being saved:
            // that made the text jump back to an older version.
            val revisionAtRequest = saveRevision
            repository.loadEntry(selectedDate) { result ->
                if (screenActive.get() && saveRevision == revisionAtRequest && !repository.hasPendingSave()) {
                    result.onSuccess { selectedEntry = it }
                }
            }
            listOf(displayedMonth, displayedMonth.minusMonths(1), displayedMonth.plusMonths(1)).forEach { month ->
                repository.loadCompletedDates(month) { result ->
                    if (screenActive.get()) result.onSuccess { completedByMonth[month] = it }
                }
            }
        }
    }

    fun save(updatedEntry: DailyEntry) {
        val previous = selectedEntry
        // Typing only changes the description of a record that already exists: coalesce those.
        val isTyping = previous != null &&
            previous.date == updatedEntry.date &&
            previous.completed == updatedEntry.completed &&
            previous.description != updatedEntry.description
        selectedEntry = updatedEntry
        saveState = SaveState.SAVING
        saveRevision += 1
        val requestRevision = saveRevision

        val persist: (DailyEntry, (Result<Unit>) -> Unit) -> Unit =
            if (isTyping) repository::saveEntryDebounced else repository::saveEntry
        persist(updatedEntry) { result ->
            if (selectedDate == updatedEntry.date && requestRevision == saveRevision) {
                saveState = if (result.isSuccess) SaveState.SAVED else SaveState.FAILED
            }
            if (result.isSuccess && cloudAccountId != null) {
                // Collapsed into one sync a few seconds later instead of one per keystroke pause.
                repository.syncCloudSoon(cloud, cloudAccountId, onCloudSyncResult)
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
                    .imePadding()
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
                    notes = notesByDate,
                    onLongPressDate = { date ->
                        haptics.heavy()
                        noteEditorDate = date
                    },
                    onPreviousMonth = {
                        haptics.select()
                        val target = displayedMonth.minusMonths(1)
                        displayedMonth = target
                        // Land on today (current month) or the 1st, never on a random future day.
                        selectedDate = if (target == YearMonth.from(today)) today else target.atDay(1)
                    },
                    onNextMonth = {
                        haptics.select()
                        val target = displayedMonth.plusMonths(1)
                        displayedMonth = target
                        selectedDate = if (target == YearMonth.from(today)) today else target.atDay(1)
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

                notesByDate[selectedDate]?.let { note ->
                    Spacer(Modifier.height(12.dp))
                    NoteCard(note = note, onEdit = { noteEditorDate = selectedDate })
                }

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
                            signedIn = cloudAccountId != null,
                            onEntryChange = ::save
                        )
                    }
                }
                }

                Spacer(Modifier.navigationBarsPadding().height(120.dp))
            }
        }
    }

    noteEditorDate?.let { date ->
        NoteEditorDialog(
            date = date,
            dateLabel = date.format(selectedDateFormatter),
            existing = notesByDate[date],
            onDismiss = { noteEditorDate = null },
            onSave = { text ->
                noteEditorDate = null
                repository.saveNote(date, text) { result ->
                    result.onSuccess {
                        val trimmed = text.trim()
                        if (trimmed.isEmpty()) notesByDate.remove(date) else notesByDate[date] = trimmed
                    }
                }
            }
        )
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
    notes: Map<LocalDate, String>,
    onLongPressDate: (LocalDate) -> Unit,
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

        // Render one 42-cell grid at a time. Keeping outgoing and incoming grids alive
        // during AnimatedContent doubled the calendar's composition and drawing work.
        val completed = completedFor(month)
        val days = remember(month, locale) {
            val built = buildCalendarDays(month, firstDayOfWeek)
            built + List((42 - built.size).coerceAtLeast(0)) { null }
        }
        // Swipe left/right to change month. The grid follows the finger, then slides out and the
        // next month slides in with a short spring. Only a graphicsLayer offset changes per frame,
        // so nothing is recomposed while dragging.
        var dragOffset by remember { mutableFloatStateOf(0f) }
        var gridWidth by remember { mutableFloatStateOf(1f) }
        val dragState = rememberDraggableState { delta -> dragOffset += delta }
        Column(
            Modifier.fillMaxWidth()
                .onSizeChanged { gridWidth = it.width.toFloat().coerceAtLeast(1f) }
                .graphicsLayer {
                    translationX = dragOffset
                    alpha = 1f - (kotlin.math.abs(dragOffset) / gridWidth).coerceIn(0f, 1f) * 0.7f
                }
                .draggable(
                    state = dragState,
                    orientation = Orientation.Horizontal,
                    onDragStopped = { velocity ->
                        val threshold = gridWidth * 0.22f
                        val next = dragOffset < -threshold || velocity < -900f
                        val previous = dragOffset > threshold || velocity > 900f
                        if (next || previous) {
                            val direction = if (next) -1f else 1f
                            animate(dragOffset, direction * gridWidth * 0.6f, animationSpec = tween(110)) { v, _ ->
                                dragOffset = v
                            }
                            if (next) onNextMonth() else onPreviousMonth()
                            dragOffset = -direction * gridWidth * 0.35f
                            animate(
                                dragOffset, 0f,
                                animationSpec = spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)
                            ) { v, _ -> dragOffset = v }
                        } else {
                            animate(
                                dragOffset, 0f,
                                animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium)
                            ) { v, _ -> dragOffset = v }
                        }
                    }
                )
        ) {
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
                                hasNote = date in notes,
                                onClick = { onSelectDate(date) },
                                onLongClick = { onLongPressDate(date) },
                                modifier = Modifier.weight(1f).aspectRatio(1f).padding(2.dp)
                            )
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
            LegendCheck(MaterialTheme.colorScheme.primary, "Completed")
            LegendItem(MaterialTheme.colorScheme.onSurface, "Today", outlined = true)
            LegendItem(NoteColor, "Note")
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CalendarDayCell(
    date: LocalDate,
    isToday: Boolean,
    isSelected: Boolean,
    isCompleted: Boolean,
    isFuture: Boolean,
    hasNote: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
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
    // Completed days get the light green tint; days with a note get a warm tint of their own.
    val restingFill = when {
        isCompleted -> primary.copy(alpha = 0.16f)
        hasNote -> NoteColor.copy(alpha = 0.20f)
        else -> Color.Transparent
    }
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
                    // A completed day that also has a note keeps its green tint plus a warm ring.
                    if (hasNote && isCompleted) {
                        drawCircle(
                            NoteColor.copy(alpha = 0.75f * (1f - t)),
                            radius = size.minDimension / 2f - 0.5.dp.toPx(),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx())
                        )
                    }
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
            // A small check mark replaces the old dot. The slot keeps its size when empty, so the
            // day numbers never shift.
            Canvas(Modifier.padding(top = 1.dp).size(width = 10.dp, height = 8.dp)) {
                if (isCompleted) {
                    val w = size.width
                    val h = size.height
                    val tick = Path().apply {
                        moveTo(w * 0.08f, h * 0.55f)
                        lineTo(w * 0.38f, h * 0.88f)
                        lineTo(w * 0.94f, h * 0.10f)
                    }
                    drawPath(
                        tick, markerColor,
                        style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }
            }
        }
        Box(
            Modifier.matchParentSize().clip(CircleShape)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
        )
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
private fun LegendCheck(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Canvas(Modifier.size(width = 11.dp, height = 9.dp)) {
            val w = size.width
            val h = size.height
            val tick = Path().apply {
                moveTo(w * 0.08f, h * 0.55f)
                lineTo(w * 0.38f, h * 0.88f)
                lineTo(w * 0.94f, h * 0.10f)
            }
            drawPath(tick, color, style = Stroke(1.7.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
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
    signedIn: Boolean,
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
            text = if (signedIn) "Synced to your account automatically."
            else "Sign in from the account button to back up and sync.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

/**
 * Large circular toggle with a Material 3 Expressive feel: the fill springs in with a bouncy
 * overshoot while its edge briefly wobbles like a scalloped cookie and settles into a circle, and
 * the check mark draws itself in right after. Un-checking simply shrinks the fill away.
 * Everything is driven by springs and read only while drawing, so tapping never recomposes.
 */
@Composable
private fun CompletionToggle(completed: Boolean, onChange: (Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    val fill = remember { Animatable(if (completed) 1f else 0f) }
    val wobble = remember { Animatable(0f) }
    val tick = remember { Animatable(if (completed) 1f else 0f) }
    var first by remember { mutableStateOf(true) }

    LaunchedEffect(completed) {
        if (first) {
            // First composition: show the current state without animating.
            first = false
            fill.snapTo(if (completed) 1f else 0f)
            tick.snapTo(if (completed) 1f else 0f)
            return@LaunchedEffect
        }
        if (completed) {
            launch {
                fill.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 420f))
            }
            launch {
                wobble.snapTo(1f)
                wobble.animateTo(0f, spring(dampingRatio = 0.42f, stiffness = 170f))
            }
            launch {
                tick.snapTo(0f)
                delay(90)
                tick.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 380f))
            }
        } else {
            launch { tick.animateTo(0f, tween(120)) }
            launch { fill.animateTo(0f, spring(dampingRatio = 1f, stiffness = 700f)) }
            wobble.snapTo(0f)
        }
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
                val baseRadius = 21.dp.toPx()
                val f = fill.value
                val w = wobble.value

                // Empty ring, fading out as the fill grows over it.
                drawCircle(
                    Color.White.copy(alpha = 0.16f * (1f - f.coerceIn(0f, 1f))),
                    baseRadius, c, style = Stroke(1.5.dp.toPx())
                )

                if (f > 0.001f) {
                    // Scalloped edge: 8 soft bumps whose size follows the wobble spring.
                    val radius = baseRadius * f
                    val amplitude = 0.13f * w
                    val spin = (1f - w) * 0.9f
                    val shape = Path()
                    val steps = 72
                    for (i in 0..steps) {
                        val angle = 2.0 * Math.PI * i / steps
                        val r = radius * (1f + amplitude * kotlin.math.cos(8.0 * angle + spin).toFloat())
                        val x = c.x + kotlin.math.cos(angle).toFloat() * r
                        val y = c.y + kotlin.math.sin(angle).toFloat() * r
                        if (i == 0) shape.moveTo(x, y) else shape.lineTo(x, y)
                    }
                    shape.close()
                    drawPath(shape, scheme.primary)
                }

                val t = tick.value.coerceIn(0f, 1f)
                if (t > 0f) {
                    val check = Path().apply {
                        moveTo(c.x - 8.dp.toPx(), c.y + 0.5.dp.toPx())
                        lineTo(c.x - 2.5.dp.toPx(), c.y + 6.dp.toPx())
                        lineTo(c.x + 8.5.dp.toPx(), c.y - 6.dp.toPx())
                    }
                    val measure = PathMeasure()
                    measure.setPath(check, false)
                    val partial = Path()
                    measure.getSegment(0f, measure.length * t, partial, true)
                    drawPath(
                        partial, scheme.onPrimary,
                        style = Stroke(2.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
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
    val bringIntoView = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
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
                .bringIntoViewRequester(bringIntoView)
                .clip(shape)
                .background(Color.Black.copy(alpha = 0.18f))
                .border(1.dp, borderColor, shape)
                .padding(14.dp)
        ) {
            BasicTextField(
                value = value,
                onValueChange = { if (it.length <= MAX_DESCRIPTION_LENGTH) onValueChange(it) },
                modifier = Modifier.fillMaxWidth().onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) {
                        // Wait for the keyboard to finish opening, then scroll the box above it.
                        scope.launch {
                            delay(280)
                            bringIntoView.bringIntoView()
                        }
                    }
                },
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
    Column(
        Modifier.glassCard().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurface,
            style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium)
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun NoteCard(note: String, onEdit: () -> Unit) {
    val source = rememberPressSource()
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier.fillMaxWidth().pressScale(source, 0.98f).clip(shape)
            .background(NoteColor.copy(alpha = 0.10f))
            .border(1.dp, NoteColor.copy(alpha = 0.35f), shape)
            .hapticClickable(source, onClick = onEdit)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = "NOTE \u00b7 TAP TO EDIT",
            color = NoteColor,
            style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = note,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/** Opens on long-press of a date. Saving empty text, or pressing Remove, deletes the note. */
@Composable
private fun NoteEditorDialog(
    date: LocalDate,
    dateLabel: String,
    existing: String?,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by remember(date) { mutableStateOf(existing.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add a note" else "Edit note") },
        text = {
            Column {
                Text(
                    dateLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { if (it.length <= MAX_NOTE_LENGTH) text = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("e.g. Revise chemistry notes") },
                    minLines = 2,
                    maxLines = 5,
                    supportingText = { Text("${text.length} / $MAX_NOTE_LENGTH") }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (existing != null) {
                    TextButton(onClick = { onSave("") }) {
                        Text("Remove", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
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