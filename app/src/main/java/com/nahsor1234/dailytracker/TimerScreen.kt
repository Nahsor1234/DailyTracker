package com.nahsor1234.dailytracker

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

private enum class TimerMode { STOPWATCH, COUNTDOWN }
private const val DEFAULT_COUNTDOWN_MINUTES = 25L
private const val MAX_COUNTDOWN_MINUTES = 599L

@Composable
internal fun TimerScreen(modifier: Modifier = Modifier) {
    var mode by rememberSaveable { mutableStateOf(TimerMode.STOPWATCH) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var stopwatchElapsedBeforeStart by rememberSaveable { mutableLongStateOf(0L) }
    var stopwatchStartedAt by rememberSaveable { mutableLongStateOf(0L) }
    var stopwatchRunning by rememberSaveable { mutableStateOf(false) }
    var countdownMinutes by rememberSaveable { mutableStateOf(DEFAULT_COUNTDOWN_MINUTES.toString()) }
    var countdownRemainingMillis by rememberSaveable { mutableLongStateOf(DEFAULT_COUNTDOWN_MINUTES * 60_000L) }
    var countdownEndsAt by rememberSaveable { mutableLongStateOf(0L) }
    var countdownRunning by rememberSaveable { mutableStateOf(false) }
    var countdownFinished by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(stopwatchRunning, countdownRunning) {
        while (stopwatchRunning || countdownRunning) {
            now = SystemClock.elapsedRealtime()
            delay(100L)
        }
        now = SystemClock.elapsedRealtime()
    }
    val stopwatchElapsed = stopwatchElapsedBeforeStart +
        if (stopwatchRunning) (now - stopwatchStartedAt).coerceAtLeast(0L) else 0L
    val countdownRemaining = if (countdownRunning) {
        (countdownEndsAt - now).coerceAtLeast(0L)
    } else countdownRemainingMillis
    LaunchedEffect(countdownRunning, countdownRemaining) {
        if (countdownRunning && countdownRemaining <= 0L) {
            countdownRemainingMillis = 0L
            countdownRunning = false
            countdownFinished = true
        }
    }
    val configuredCountdownMillis = remember(countdownMinutes) {
        (countdownMinutes.toLongOrNull()?.takeIf { it in 1L..MAX_COUNTDOWN_MINUTES } ?: 0L) * 60_000L
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(28.dp))
            Text("DailyTracker", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text("Timer", style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text("Make your time count.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            TimerModeSelector(mode) { mode = it }
            Spacer(Modifier.height(20.dp))

            if (mode == TimerMode.STOPWATCH) {
                TimerDial(
                    eyebrow = if (stopwatchRunning) "STOPWATCH RUNNING" else "STOPWATCH",
                    time = formatStopwatch(stopwatchElapsed),
                    status = if (stopwatchRunning) "Measuring elapsed time" else "Ready when you are",
                    progress = (stopwatchElapsed % 60_000L) / 60_000f
                )
                Spacer(Modifier.height(22.dp))
                TimerActions(
                    isRunning = stopwatchRunning,
                    canReset = stopwatchRunning || stopwatchElapsed > 0L,
                    onStartPause = {
                        if (stopwatchRunning) {
                            stopwatchElapsedBeforeStart +=
                                (SystemClock.elapsedRealtime() - stopwatchStartedAt).coerceAtLeast(0L)
                            stopwatchStartedAt = 0L
                            stopwatchRunning = false
                        } else {
                            stopwatchStartedAt = SystemClock.elapsedRealtime()
                            stopwatchRunning = true
                        }
                    },
                    onReset = {
                        stopwatchRunning = false
                        stopwatchStartedAt = 0L
                        stopwatchElapsedBeforeStart = 0L
                    }
                )
            } else {
                val progress = if (configuredCountdownMillis > 0L)
                    (countdownRemaining.toFloat() / configuredCountdownMillis).coerceIn(0f, 1f)
                else 0f
                TimerDial(
                    eyebrow = when {
                        countdownFinished -> "COUNTDOWN COMPLETE"
                        countdownRunning -> "COUNTDOWN RUNNING"
                        else -> "FOCUS COUNTDOWN"
                    },
                    time = formatCountdown(countdownRemaining),
                    status = when {
                        countdownFinished -> "Time is up"
                        countdownRunning -> "Stay focused"
                        else -> "Set a duration and begin"
                    },
                    progress = progress,
                    isFinished = countdownFinished
                )
                Spacer(Modifier.height(20.dp))
                Text("QUICK DURATION", modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(5L, 15L, 25L, 45L).forEach { preset ->
                        FilterChip(
                            selected = countdownMinutes == preset.toString(),
                            onClick = {
                                if (!countdownRunning) {
                                    countdownMinutes = preset.toString()
                                    countdownRemainingMillis = preset * 60_000L
                                    countdownFinished = false
                                }
                            },
                            enabled = !countdownRunning,
                            label = { Text("${preset}m") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = countdownMinutes,
                    onValueChange = { value ->
                        if (!countdownRunning && value.length <= 3 && value.all(Char::isDigit)) {
                            countdownMinutes = value
                            countdownRemainingMillis =
                                (value.toLongOrNull()?.takeIf { it in 1L..MAX_COUNTDOWN_MINUTES } ?: 0L) * 60_000L
                            countdownFinished = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !countdownRunning,
                    singleLine = true,
                    label = { Text("Custom duration (minutes)") },
                    supportingText = { Text("1–599 minutes") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(16.dp)
                )
                Spacer(Modifier.height(18.dp))
                TimerActions(
                    isRunning = countdownRunning,
                    canReset = countdownRunning || countdownFinished || countdownRemaining != configuredCountdownMillis,
                    startEnabled = countdownRunning || countdownRemaining > 0L,
                    onStartPause = {
                        if (countdownRunning) {
                            countdownRemainingMillis =
                                (countdownEndsAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                            countdownRunning = false
                            countdownEndsAt = 0L
                        } else if (countdownRemainingMillis > 0L) {
                            countdownEndsAt = SystemClock.elapsedRealtime() + countdownRemainingMillis
                            countdownFinished = false
                            countdownRunning = true
                        }
                    },
                    onReset = {
                        countdownRunning = false
                        countdownEndsAt = 0L
                        countdownRemainingMillis = configuredCountdownMillis
                        countdownFinished = false
                    }
                )
            }

            Spacer(Modifier.height(18.dp))
            Text(
                "Timer continues accurately while you switch screens. Background alerts and sound are not configured yet.",
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun TimerModeSelector(mode: TimerMode, onModeChange: (TimerMode) -> Unit) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.padding(5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TimerMode.values().forEach { option ->
                val selected = option == mode
                Box(
                    modifier = Modifier.clip(CircleShape)
                        .background(if (selected) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable { onModeChange(option) }
                        .padding(horizontal = 20.dp, vertical = 11.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (option == TimerMode.STOPWATCH) "Stopwatch" else "Countdown",
                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun TimerDial(
    eyebrow: String,
    time: String,
    status: String,
    progress: Float,
    isFinished: Boolean = false
) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.size(264.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(5.dp)) {
            val stroke = 9.dp.toPx()
            drawCircle(colors.surfaceVariant, style = Stroke(stroke))
            drawArc(
                color = if (isFinished) colors.secondary else colors.primary,
                startAngle = -90f,
                sweepAngle = 360f * progress.coerceIn(0f, 1f),
                useCenter = false,
                style = Stroke(stroke, cap = StrokeCap.Round)
            )
            if (progress > 0f) {
                val angle = Math.toRadians(-90.0 + 360.0 * progress)
                val r = size.minDimension / 2f - stroke / 2f
                drawCircle(colors.primary, radius = stroke * 0.72f,
                    center = Offset(center.x + cos(angle).toFloat() * r, center.y + sin(angle).toFloat() * r))
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth(0.82f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(eyebrow, style = MaterialTheme.typography.labelMedium,
                color = if (isFinished) colors.secondary else colors.primary,
                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Text(time, color = colors.onBackground,
                fontSize = if (time.length > 8) 29.sp else 38.sp,
                lineHeight = 44.sp, fontWeight = FontWeight.Medium,
                letterSpacing = (-0.8).sp, textAlign = TextAlign.Center, maxLines = 1)
            Spacer(Modifier.height(10.dp))
            Text(status, style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun TimerActions(
    isRunning: Boolean,
    canReset: Boolean,
    onStartPause: () -> Unit,
    onReset: () -> Unit,
    startEnabled: Boolean = true
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = onStartPause, modifier = Modifier.weight(1f).height(54.dp),
            enabled = startEnabled, shape = CircleShape) {
            Text(if (isRunning) "Pause" else "Start", fontWeight = FontWeight.SemiBold)
        }
        OutlinedButton(onClick = onReset, modifier = Modifier.weight(1f).height(54.dp),
            enabled = canReset, shape = CircleShape) { Text("Reset") }
    }
}

private fun formatStopwatch(millis: Long): String {
    val tenths = millis.coerceAtLeast(0L) / 100L
    val totalSeconds = tenths / 10L
    return String.format(Locale.ROOT, "%02d:%02d:%02d.%d",
        totalSeconds / 3_600L, (totalSeconds % 3_600L) / 60L,
        totalSeconds % 60L, tenths % 10L)
}

private fun formatCountdown(millis: Long): String {
    val totalSeconds = (millis.coerceAtLeast(0L) + 999L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds)
    else String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
}
