package com.nahsor1234.dailytracker

import android.os.SystemClock
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private enum class TimerMode { STOPWATCH, COUNTDOWN }

private const val DEFAULT_COUNTDOWN_MINUTES = 25L
private const val MAX_COUNTDOWN_MINUTES = 599L
private val DurationPresets = listOf(5, 15, 25, 45, 60)
private val ModeSegmentWidth = 120.dp // (248dp pill - 2 * 4dp padding) / 2 segments

// ---------------------------------------------------------------------------------------------
// Dot field: concentric rings of dots that alternate between disappearing (outside-in) and
// reappearing (inside-out) in consecutive one-minute phases. The countdown starts with a
// disappearing phase, the stopwatch with an appearing one. The phase is a pure function of the
// elapsed time, so nothing is simulated and no separate animation clock is involved.
// ---------------------------------------------------------------------------------------------

private const val FIELD_RINGS = 12
private const val FRONT_WIDTH = 0.05f
private const val PHASE_MILLIS = 60_000L

private class Dot(val x: Float, val y: Float, val rho: Float, val seed: Float)

/** Built once per process and shared by every dot field. */
private val DotLayout: List<Dot> by lazy { buildDotField(FIELD_RINGS) }

private fun buildDotField(rings: Int): List<Dot> = buildList {
    add(Dot(0f, 0f, 0f, 0.5f))
    for (k in 1..rings) {
        val count = (2.0 * PI * k).roundToInt()
        val phase = if (k % 2 == 0) 0.0 else PI / count // half-step offset gives a hex-like weave
        for (i in 0 until count) {
            val angle = 2.0 * PI * i / count + phase - PI / 2.0
            val seed = (((k * 73856093) xor (i * 19349663)) and 0xFFFF) / 65535f
            add(
                Dot(
                    x = (cos(angle) * k / rings).toFloat(),
                    y = (sin(angle) * k / rings).toFloat(),
                    rho = k.toFloat() / rings,
                    seed = seed
                )
            )
        }
    }
}

/** 0 = fully gone / unlit, 1 = fully present / lit. */
private fun dotLevel(dot: Dot, progress: Float, dissolving: Boolean): Float =
    if (dissolving) {
        val threshold = (1f - dot.rho) * 0.90f + dot.seed * 0.04f
        ((threshold - progress) / FRONT_WIDTH + 1f).coerceIn(0f, 1f)
    } else {
        val threshold = dot.rho * 0.90f + dot.seed * 0.04f
        ((progress - threshold) / FRONT_WIDTH).coerceIn(0f, 1f)
    }

/** Position (0..1) inside the current one-minute phase. */
private fun phaseProgress(elapsedMillis: Long): Float =
    (elapsedMillis.coerceAtLeast(0L) % PHASE_MILLIS).toFloat() / PHASE_MILLIS

/**
 * Whether the current one-minute phase makes dots disappear. Phases alternate every minute and
 * [dissolveFirst] sets the direction of the first one. Both curves in [dotLevel] end a phase in
 * exactly the state the opposite curve starts from (every dot gone, or every dot present), so
 * consecutive phases join without a jump.
 */
private fun phaseDissolves(elapsedMillis: Long, dissolveFirst: Boolean): Boolean {
    val inEvenPhase = (elapsedMillis.coerceAtLeast(0L) / PHASE_MILLIS) % 2L == 0L
    return inEvenPhase == dissolveFirst
}

@Composable
private fun DotField(
    elapsedMillis: Long,
    dissolveFirst: Boolean,
    running: Boolean,
    finished: Boolean,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val dots = DotLayout
    val progress = phaseProgress(elapsedMillis)
    val dissolving = phaseDissolves(elapsedMillis, dissolveFirst)
    val pulse by rememberInfiniteTransition(label = "finish-pulse").animateFloat(
        initialValue = 0.05f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(tween(1500), RepeatMode.Reverse),
        label = "finish-pulse-value"
    )

    Canvas(modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val outerRadius = size.minDimension / 2f
        val cell = outerRadius / (FIELD_RINGS + 0.7f)
        val fieldRadius = cell * FIELD_RINGS
        val dotRadius = cell * 0.27f

        // Soft ambient glow behind the field gives depth without a hard container.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(colors.primary.copy(alpha = 0.16f), Color.Transparent),
                center = center,
                radius = outerRadius * 1.25f
            ),
            radius = outerRadius * 1.25f,
            center = center
        )

        for (dot in dots) {
            val level = dotLevel(dot, progress, dissolving)
            val lit = if (finished) pulse else level
            val front = if (running && !finished) 1f - abs(2f * level - 1f) else 0f
            val tint = lerp(colors.primary, Color.White, front * 0.9f)
                .copy(alpha = (0.07f + 0.88f * lit).coerceIn(0f, 1f))
            drawCircle(
                color = tint,
                radius = dotRadius * (0.55f + 0.45f * lit),
                center = Offset(center.x + dot.x * fieldRadius, center.y + dot.y * fieldRadius)
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------------------------

private const val WARM_UP_DELAY_MS = 700L // after the launch animations of the Progress screen
private const val WARM_UP_FRAMES = 3
private var timerWarmedUp = false // per process; only touched from the main thread

/**
 * Composes the Timer screen once, off-screen, while the user is still looking at Progress.
 *
 * The first composition of a screen in a fresh process is slow (class loading, first layout and
 * draw of every widget), and without this that cost lands inside the first slide transition,
 * which shows up as a stutter. Paying it here, while nothing is animating, makes the first
 * navigation as smooth as the later ones. The copy is moved fully out of view, hidden from
 * accessibility, kept out of saved state and removed after a few frames.
 */
@Composable
internal fun TimerScreenWarmUp(enabled: Boolean, modifier: Modifier = Modifier) {
    var active by remember { mutableStateOf(false) }

    LaunchedEffect(enabled) {
        if (!enabled || timerWarmedUp) return@LaunchedEffect
        delay(WARM_UP_DELAY_MS)
        try {
            active = true
            repeat(WARM_UP_FRAMES) { withFrameNanos { } }
            timerWarmedUp = true
        } finally {
            active = false
        }
    }

    if (active) {
        CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
            Box(
                modifier
                    .clearAndSetSemantics { }
                    .graphicsLayer { translationX = size.width }
            ) {
                TimerScreen()
            }
        }
    }
}

@Composable
internal fun TimerScreen(modifier: Modifier = Modifier) {
    var mode by rememberSaveable { mutableStateOf(TimerMode.STOPWATCH) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var watchElapsedBase by rememberSaveable { mutableLongStateOf(0L) }
    var watchStartedAt by rememberSaveable { mutableLongStateOf(0L) }
    var watchRunning by rememberSaveable { mutableStateOf(false) }
    var minutesText by rememberSaveable { mutableStateOf(DEFAULT_COUNTDOWN_MINUTES.toString()) }
    var remainingBase by rememberSaveable { mutableLongStateOf(DEFAULT_COUNTDOWN_MINUTES * 60_000L) }
    var countdownDeadline by rememberSaveable { mutableLongStateOf(0L) }
    var countdownRunning by rememberSaveable { mutableStateOf(false) }
    var finished by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(watchRunning, countdownRunning) {
        while (watchRunning || countdownRunning) {
            now = SystemClock.elapsedRealtime()
            delay(50L)
        }
        now = SystemClock.elapsedRealtime()
    }

    val watchElapsed = watchElapsedBase +
        if (watchRunning) (now - watchStartedAt).coerceAtLeast(0L) else 0L
    val remaining = if (countdownRunning)
        (countdownDeadline - now).coerceAtLeast(0L) else remainingBase

    LaunchedEffect(countdownRunning, remaining) {
        if (countdownRunning && remaining <= 0L) {
            remainingBase = 0L
            countdownRunning = false
            finished = true
        }
    }

    val configuredMillis = remember(minutesText) {
        (minutesText.toLongOrNull()?.takeIf { it in 1L..MAX_COUNTDOWN_MINUTES } ?: 0L) * 60_000L
    }
    val countdownElapsed = (configuredMillis - remaining).coerceIn(0L, configuredMillis)

    val onMinutesChange: (String) -> Unit = { input ->
        minutesText = input
        val parsed = input.toLongOrNull()?.takeIf { it in 1L..MAX_COUNTDOWN_MINUTES }
        if (parsed != null) {
            remainingBase = parsed * 60_000L
            finished = false
        }
    }

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Clears the Account + Reminders buttons pinned to the top-right corner.
            Spacer(Modifier.height(72.dp))
            TimerModeSelector(mode) {
                mode = it
                finished = false
            }
            Spacer(Modifier.height(20.dp))

            if (mode == TimerMode.STOPWATCH) {
                DotField(
                    elapsedMillis = watchElapsed,
                    dissolveFirst = false,
                    running = watchRunning,
                    finished = false,
                    modifier = Modifier.size(296.dp)
                )
                val (main, tenths) = splitStopwatch(watchElapsed)
                TimeReadout(main, tenths, if (watchRunning) "MEASURING" else "READY")
                Spacer(Modifier.height(32.dp))
                TimerControls(
                    isRunning = watchRunning,
                    canReset = watchRunning || watchElapsed > 0L,
                    startEnabled = true,
                    onStartPause = {
                        if (watchRunning) {
                            watchElapsedBase += (SystemClock.elapsedRealtime() - watchStartedAt).coerceAtLeast(0L)
                            watchStartedAt = 0L
                            watchRunning = false
                        } else {
                            // Refresh the shared clock too, otherwise the first frame after a long
                            // pause is drawn from a stale `now`.
                            val startedAt = SystemClock.elapsedRealtime()
                            now = startedAt
                            watchStartedAt = startedAt
                            watchRunning = true
                        }
                    },
                    onReset = {
                        watchRunning = false
                        watchStartedAt = 0L
                        watchElapsedBase = 0L
                    }
                )
            } else {
                DotField(
                    elapsedMillis = countdownElapsed,
                    dissolveFirst = true,
                    running = countdownRunning,
                    finished = finished,
                    modifier = Modifier.size(296.dp)
                )
                TimeReadout(
                    main = formatCountdown(remaining),
                    tenths = null,
                    status = when {
                        finished -> "TIME IS UP"
                        countdownRunning -> "STAY FOCUSED"
                        else -> "SET A DURATION"
                    }
                )
                Spacer(Modifier.height(24.dp))
                DurationPicker(minutesText, !countdownRunning, onMinutesChange)
                Spacer(Modifier.height(28.dp))
                TimerControls(
                    isRunning = countdownRunning,
                    canReset = countdownRunning || finished || remaining != configuredMillis,
                    startEnabled = countdownRunning || remaining > 0L,
                    onStartPause = {
                        if (countdownRunning) {
                            remainingBase = (countdownDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                            countdownDeadline = 0L
                            countdownRunning = false
                        } else if (remainingBase > 0L) {
                            val startedAt = SystemClock.elapsedRealtime()
                            now = startedAt
                            countdownDeadline = startedAt + remainingBase
                            finished = false
                            countdownRunning = true
                        }
                    },
                    onReset = {
                        countdownRunning = false
                        countdownDeadline = 0L
                        remainingBase = configuredMillis
                        finished = false
                    }
                )
            }

            Spacer(Modifier.height(20.dp))
            Text(
                "Alerts and sound aren't configured yet.",
                Modifier.fillMaxWidth().alpha(0.6f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.navigationBarsPadding().height(120.dp))
        }
    }
}

@Composable
private fun TimeReadout(main: String, tenths: String?, status: String) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.Center) {
        Text(
            main, color = colors.onBackground, maxLines = 1,
            style = TextStyle(
                fontSize = if (main.length > 5) 54.sp else 68.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = (-1.5).sp,
                fontFeatureSettings = "tnum"
            )
        )
        if (tenths != null) {
            Text(
                ".$tenths", color = colors.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 9.dp),
                style = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Light, fontFeatureSettings = "tnum")
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        status, color = colors.onSurfaceVariant,
        style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 2.4.sp)
    )
}

@Composable
private fun TimerModeSelector(mode: TimerMode, onModeChange: (TimerMode) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val selectedIndex = if (mode == TimerMode.STOPWATCH) 0 else 1
    // The pill has a fixed size, so no BoxWithConstraints (a subcomposing layout, which is
    // noticeably costly the first time it is composed) is needed to know the segment width.
    Box(
        Modifier.width(248.dp).height(44.dp).clip(CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.10f), CircleShape).padding(4.dp)
    ) {
        val segment = ModeSegmentWidth
        val offset by animateDpAsState(segment * selectedIndex, tween(320), label = "mode-pill")
        Box(
            Modifier.offset(x = offset).width(segment).fillMaxHeight()
                .clip(CircleShape).background(Color.White.copy(alpha = 0.10f))
        )
        Row(Modifier.fillMaxSize()) {
            TimerMode.values().forEach { option ->
                val selected = option == mode
                val textColor by animateColorAsState(
                    if (selected) colors.onBackground else colors.onSurfaceVariant,
                    tween(200), label = "mode-text"
                )
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(CircleShape)
                        .clickable { onModeChange(option) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (option == TimerMode.STOPWATCH) "Stopwatch" else "Countdown",
                        color = textColor,
                        style = TextStyle(
                            fontSize = 13.sp, letterSpacing = 0.4.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun DurationPicker(value: String, enabled: Boolean, onValueChange: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val line = Color.White.copy(alpha = 0.12f)
    Column(Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.4f)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DurationPresets.forEach { minutes ->
                val selected = value == minutes.toString()
                Box(
                    Modifier.weight(1f).height(40.dp).clip(CircleShape)
                        .background(if (selected) colors.primary else Color.Transparent)
                        .border(1.dp, if (selected) colors.primary else line, CircleShape)
                        .clickable(enabled = enabled) { onValueChange(minutes.toString()) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "$minutes",
                        color = if (selected) colors.onPrimary else colors.onSurfaceVariant,
                        style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Custom", color = colors.onSurfaceVariant, style = TextStyle(fontSize = 13.sp))
            Spacer(Modifier.width(12.dp))
            BasicTextField(
                value = value,
                onValueChange = { if (enabled && it.length <= 3 && it.all(Char::isDigit)) onValueChange(it) },
                enabled = enabled,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                cursorBrush = SolidColor(colors.primary),
                textStyle = TextStyle(
                    color = colors.onBackground, fontSize = 16.sp,
                    fontWeight = FontWeight.Medium, textAlign = TextAlign.Center
                ),
                modifier = Modifier.width(76.dp).height(40.dp).clip(CircleShape)
                    .border(1.dp, line, CircleShape),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { inner() }
                }
            )
            Spacer(Modifier.width(10.dp))
            Text("min", color = colors.onSurfaceVariant, style = TextStyle(fontSize = 13.sp))
        }
    }
}

@Composable
private fun TimerControls(
    isRunning: Boolean,
    canReset: Boolean,
    startEnabled: Boolean,
    onStartPause: () -> Unit,
    onReset: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(28.dp)
    ) {
        Box(
            Modifier.size(64.dp).alpha(if (canReset) 1f else 0.35f).clip(CircleShape)
                .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape)
                .clickable(enabled = canReset) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onReset()
                },
            contentAlignment = Alignment.Center
        ) {
            Text("Reset", color = colors.onSurfaceVariant, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium))
        }
        Box(
            Modifier.size(88.dp).alpha(if (startEnabled) 1f else 0.35f).clip(CircleShape)
                .background(colors.primary)
                .clickable(enabled = startEnabled) {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onStartPause()
                },
            contentAlignment = Alignment.Center
        ) {
            Crossfade(isRunning, animationSpec = tween(160), label = "play-pause") { running ->
                Canvas(Modifier.size(28.dp)) {
                    if (running) {
                        val radius = CornerRadius(3.dp.toPx())
                        drawRoundRect(colors.onPrimary, Offset(size.width * 0.12f, 0f),
                            Size(size.width * 0.28f, size.height), radius)
                        drawRoundRect(colors.onPrimary, Offset(size.width * 0.60f, 0f),
                            Size(size.width * 0.28f, size.height), radius)
                    } else {
                        val inset = 1.5.dp.toPx()
                        val path = Path().apply {
                            moveTo(size.width * 0.20f + inset, inset)
                            lineTo(size.width - inset, size.height / 2f)
                            lineTo(size.width * 0.20f + inset, size.height - inset)
                            close()
                        }
                        drawPath(path, colors.onPrimary)
                        drawPath(path, colors.onPrimary, style = Stroke(3.dp.toPx(), join = StrokeJoin.Round))
                    }
                }
            }
        }
        Spacer(Modifier.size(64.dp)) // balances the Reset button so Start/Pause stays centered
    }
}

private fun splitStopwatch(millis: Long): Pair<String, String> {
    val tenths = millis.coerceAtLeast(0L) / 100L
    val seconds = tenths / 10L
    val hours = seconds / 3600L
    val mins = (seconds % 3600L) / 60L
    val secs = seconds % 60L
    val main = if (hours > 0L) String.format(Locale.ROOT, "%d:%02d:%02d", hours, mins, secs)
    else String.format(Locale.ROOT, "%02d:%02d", mins, secs)
    return main to (tenths % 10L).toString()
}

private fun formatCountdown(millis: Long): String {
    val seconds = (millis.coerceAtLeast(0L) + 999L) / 1000L
    val hours = seconds / 3600L
    val mins = (seconds % 3600L) / 60L
    val secs = seconds % 60L
    return if (hours > 0L) String.format(Locale.ROOT, "%02d:%02d:%02d", hours, mins, secs)
    else String.format(Locale.ROOT, "%02d:%02d", mins, secs)
}