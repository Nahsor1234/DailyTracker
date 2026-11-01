package com.nahsor1234.dailytracker

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Locale

private enum class TimerMode {
    STOPWATCH,
    COUNTDOWN
}

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
    var countdownRemainingMillis by rememberSaveable {
        mutableLongStateOf(DEFAULT_COUNTDOWN_MINUTES * 60_000L)
    }
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
    } else {
        countdownRemainingMillis
    }

    LaunchedEffect(countdownRunning, countdownRemaining) {
        if (countdownRunning && countdownRemaining <= 0L) {
            countdownRemainingMillis = 0L
            countdownRunning = false
            countdownFinished = true
        }
    }

    val configuredCountdownMillis = remember(countdownMinutes) {
        val minutes = countdownMinutes.toLongOrNull()
            ?.takeIf { it in 1L..MAX_COUNTDOWN_MINUTES } ?: 0L
        minutes * 60_000L
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(28.dp))
            Text(
                text = "DailyTracker",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Timer",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Measure time or set a countdown.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(24.dp))
            TimerModeSelector(
                mode = mode,
                onModeChange = { mode = it }
            )
            Spacer(Modifier.height(18.dp))

            if (mode == TimerMode.STOPWATCH) {
                StopwatchPanel(
                    elapsedMillis = stopwatchElapsed,
                    isRunning = stopwatchRunning,
                    onStartPause = {
                        if (stopwatchRunning) {
                            val current = SystemClock.elapsedRealtime()
                            stopwatchElapsedBeforeStart +=
                                (current - stopwatchStartedAt).coerceAtLeast(0L)
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
                CountdownPanel(
                    minutes = countdownMinutes,
                    remainingMillis = countdownRemaining,
                    isRunning = countdownRunning,
                    isFinished = countdownFinished,
                    onMinutesChange = { value ->
                        if (!countdownRunning && value.length <= 3 && value.all { it.isDigit() }) {
                            countdownMinutes = value
                            countdownRemainingMillis = (
                                value.toLongOrNull()
                                    ?.takeIf { it in 1L..MAX_COUNTDOWN_MINUTES } ?: 0L
                                ) * 60_000L
                            countdownFinished = false
                        }
                    },
                    onPresetSelected = { minutes ->
                        if (!countdownRunning) {
                            countdownMinutes = minutes.toString()
                            countdownRemainingMillis = minutes * 60_000L
                            countdownFinished = false
                        }
                    },
                    onStartPause = {
                        if (countdownRunning) {
                            countdownRemainingMillis = (
                                countdownEndsAt - SystemClock.elapsedRealtime()
                                ).coerceAtLeast(0L)
                            countdownRunning = false
                            countdownEndsAt = 0L
                        } else if (countdownRemainingMillis > 0L) {
                            countdownEndsAt = SystemClock.elapsedRealtime() +
                                countdownRemainingMillis
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

            Spacer(Modifier.height(16.dp))
            Text(
                text = "Time remains accurate while switching between screens. Background alerts and sound are not configured yet.",
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
private fun TimerModeSelector(
    mode: TimerMode,
    onModeChange: (TimerMode) -> Unit
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(5.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            TimerMode.values().forEach { option ->
                val selected = option == mode
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(
                            if (selected) MaterialTheme.colorScheme.primaryContainer
                            else Color.Transparent
                        )
                        .clickable { onModeChange(option) }
                        .padding(horizontal = 20.dp, vertical = 11.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (option == TimerMode.STOPWATCH) "Stopwatch" else "Countdown",
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
private fun StopwatchPanel(
    elapsedMillis: Long,
    isRunning: Boolean,
    onStartPause: () -> Unit,
    onReset: () -> Unit
) {
    TimerDisplayCard(
        eyebrow = if (isRunning) "STOPWATCH RUNNING" else "STOPWATCH",
        time = formatStopwatch(elapsedMillis),
        status = if (isRunning) "Measuring elapsed time" else "Ready when you are"
    )

    Spacer(Modifier.height(18.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = onStartPause,
            modifier = Modifier.weight(1f).height(52.dp),
            shape = CircleShape
        ) {
            Text(if (isRunning) "Pause" else "Start")
        }
        OutlinedButton(
            onClick = onReset,
            modifier = Modifier.weight(1f).height(52.dp),
            enabled = isRunning || elapsedMillis > 0L,
            shape = CircleShape
        ) {
            Text("Reset")
        }
    }
}

@Composable
private fun CountdownPanel(
    minutes: String,
    remainingMillis: Long,
    isRunning: Boolean,
    isFinished: Boolean,
    onMinutesChange: (String) -> Unit,
    onPresetSelected: (Long) -> Unit,
    onStartPause: () -> Unit,
    onReset: () -> Unit
) {
    TimerDisplayCard(
        eyebrow = when {
            isFinished -> "COUNTDOWN COMPLETE"
            isRunning -> "COUNTDOWN RUNNING"
            else -> "COUNTDOWN"
        },
        time = formatCountdown(remainingMillis),
        status = when {
            isFinished -> "Time is up"
            isRunning -> "Counting down"
            else -> "Set a duration and start"
        },
        highlight = isFinished
    )

    Spacer(Modifier.height(18.dp))
    OutlinedTextField(
        value = minutes,
        onValueChange = onMinutesChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = !isRunning,
        singleLine = true,
        label = { Text("Duration in minutes") },
        supportingText = { Text("Choose 1–599 minutes") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        shape = RoundedCornerShape(16.dp)
    )

    Spacer(Modifier.height(10.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        listOf(5L, 10L, 15L, 25L, 45L).forEach { preset ->
            FilterChip(
                selected = minutes == preset.toString(),
                onClick = { onPresetSelected(preset) },
                enabled = !isRunning,
                label = { Text(preset.toString()) },
                modifier = Modifier.weight(1f)
            )
        }
    }

    Spacer(Modifier.height(18.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = onStartPause,
            modifier = Modifier.weight(1f).height(52.dp),
            enabled = isRunning || remainingMillis > 0L,
            shape = CircleShape
        ) {
            Text(if (isRunning) "Pause" else "Start")
        }
        OutlinedButton(
            onClick = onReset,
            modifier = Modifier.weight(1f).height(52.dp),
            enabled = isRunning || isFinished || remainingMillis !=
                ((minutes.toLongOrNull()?.takeIf { it in 1L..MAX_COUNTDOWN_MINUTES } ?: 0L) * 60_000L),
            shape = CircleShape
        ) {
            Text("Reset")
        }
    }
}

@Composable
private fun TimerDisplayCard(
    eyebrow: String,
    time: String,
    status: String,
    highlight: Boolean = false
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = eyebrow,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(22.dp))
            Text(
                text = time,
                color = if (highlight) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                fontSize = 40.sp,
                lineHeight = 46.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.5.sp,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatStopwatch(millis: Long): String {
    val tenths = millis.coerceAtLeast(0L) / 100L
    val totalSeconds = tenths / 10L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return String.format(
        Locale.ROOT,
        "%02d:%02d:%02d.%d",
        hours,
        minutes,
        seconds,
        tenths % 10L
    )
}

private fun formatCountdown(millis: Long): String {
    val totalSeconds = (millis.coerceAtLeast(0L) + 999L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
    }
}
