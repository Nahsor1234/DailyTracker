package com.nahsor1234.dailytracker

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

private enum class TimerMode { STOPWATCH, COUNTDOWN }
private const val DEFAULT_COUNTDOWN_MINUTES = 25L
private const val MAX_COUNTDOWN_MINUTES = 599L
private val ParticlePalette = listOf(
    Color(0xFFB8D9A9), Color(0xFFA4C895), Color(0xFFD0E5BD),
    Color(0xFF91B583), Color(0xFFC2DCB3)
)
private data class DialParticle(
    val x: Float,
    val y: Float,
    val bornAtMs: Long,
    val radius: Float,
    val color: Color,
    val drainDelayMs: Long,
    val drainDrift: Float
)

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
    val particles = remember { mutableStateListOf<DialParticle>() }
    var releaseParticles by remember { mutableStateOf(false) }
    var completedParticleMinute by rememberSaveable { mutableLongStateOf(0L) }

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
            releaseParticles = true
        }
    }
    val configuredMillis = remember(minutesText) {
        (minutesText.toLongOrNull()?.takeIf { it in 1L..MAX_COUNTDOWN_MINUTES } ?: 0L) * 60_000L
    }
    val countdownElapsedMillis = (configuredMillis - remaining).coerceIn(0L, configuredMillis)
    val progress = if (configuredMillis > 0L)
        (countdownElapsedMillis.toFloat() / configuredMillis).coerceIn(0f, 1f) else 0f
    val countdownMinuteProgress = (countdownElapsedMillis % 60_000L) / 60_000f
    val completedCountdownMinutes = countdownElapsedMillis / 60_000L

    // A release happens only after a full elapsed minute, never on the first timer tick.
    LaunchedEffect(countdownRunning, completedCountdownMinutes) {
        if (countdownRunning && completedCountdownMinutes > completedParticleMinute) {
            releaseParticles = true
        }
        completedParticleMinute = completedCountdownMinutes
    }

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
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
            TimerModeSelector(mode) {
                mode = it
                particles.clear()
                releaseParticles = false
                finished = false
            }
            Spacer(Modifier.height(18.dp))

            if (mode == TimerMode.STOPWATCH) {
                ParticleTimerDial(
                    eyebrow = if (watchRunning) "STOPWATCH RUNNING" else "STOPWATCH",
                    time = formatStopwatch(watchElapsed),
                    status = if (watchRunning) "Measuring elapsed time" else "Ready when you are",
                    progress = (watchElapsed % 60_000L) / 60_000f,
                    particles = particles,
                    emitParticles = false,
                    releaseParticles = false
                )
                Spacer(Modifier.height(22.dp))
                TimerActions(watchRunning, watchRunning || watchElapsed > 0L, {
                    if (watchRunning) {
                        watchElapsedBase += (SystemClock.elapsedRealtime() - watchStartedAt).coerceAtLeast(0L)
                        watchStartedAt = 0L
                        watchRunning = false
                    } else {
                        watchStartedAt = SystemClock.elapsedRealtime()
                        watchRunning = true
                    }
                }, {
                    watchRunning = false
                    watchStartedAt = 0L
                    watchElapsedBase = 0L
                })
            } else {
                ParticleTimerDial(
                    eyebrow = if (finished) "COUNTDOWN COMPLETE" else "FOCUS COUNTDOWN",
                    time = formatCountdown(remaining),
                    status = when {
                        finished -> "Time is up"
                        countdownRunning -> "Stay focused"
                        else -> "Set a duration and begin"
                    },
                    progress = progress,
                    indicatorProgress = countdownMinuteProgress,
                    particles = particles,
                    emitParticles = countdownRunning,
                    releaseParticles = releaseParticles,
                    onReleaseFinished = {
                        releaseParticles = false
                        particles.clear()
                    }
                )
                Spacer(Modifier.height(8.dp))
                CustomDurationControl(minutesText, !countdownRunning) { input ->
                    minutesText = input
                    val parsed = input.toLongOrNull()?.takeIf { it in 1L..MAX_COUNTDOWN_MINUTES }
                    if (parsed != null) {
                        remainingBase = parsed * 60_000L
                        finished = false
                        releaseParticles = false
                        particles.clear()
                    }
                }
                Spacer(Modifier.height(18.dp))
                TimerActions(
                    countdownRunning,
                    countdownRunning || finished || remaining != configuredMillis,
                    onStartPause = {
                        if (countdownRunning) {
                            remainingBase = (countdownDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                            countdownDeadline = 0L
                            countdownRunning = false
                        } else if (remainingBase > 0L) {
                            countdownDeadline = SystemClock.elapsedRealtime() + remainingBase
                            finished = false
                            releaseParticles = false
                            countdownRunning = true
                        }
                    },
                    onReset = {
                        countdownRunning = false
                        countdownDeadline = 0L
                        remainingBase = configuredMillis
                        finished = false
                        releaseParticles = false
                        particles.clear()
                    },
                    startEnabled = countdownRunning || remaining > 0L
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(
                "Timer continues accurately while you switch screens. Background alerts and sound are not configured yet.",
                Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun TimerModeSelector(mode: TimerMode, onModeChange: (TimerMode) -> Unit) {
    val selectedIndex = if (mode == TimerMode.STOPWATCH) 0 else 1
    BoxWithConstraints(
        Modifier.fillMaxWidth(0.82f).clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant).padding(5.dp)
    ) {
        val width = maxWidth / 2
        val offset by animateDpAsState(width * selectedIndex, tween(320), label = "mode-pill")
        Box(Modifier.offset(x = offset).width(width).height(46.dp)
            .clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer))
        Row(Modifier.fillMaxWidth()) {
            TimerMode.values().forEach { option ->
                val selected = option == mode
                Box(Modifier.weight(1f).height(46.dp).clip(CircleShape)
                    .clickable { onModeChange(option) }, contentAlignment = Alignment.Center) {
                    Text(if (option == TimerMode.STOPWATCH) "Stopwatch" else "Countdown",
                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun CustomDurationControl(value: String, enabled: Boolean, onValueChange: (String) -> Unit) {
    val sliderValue = value.toFloatOrNull()?.coerceIn(1f, 599f) ?: 25f
    Column(Modifier.fillMaxWidth()) {
        Text("Custom duration", style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(7.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Slider(
                    value = sliderValue,
                    onValueChange = { onValueChange(it.toInt().coerceIn(1, 599).toString()) },
                    valueRange = 1f..599f,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth().height(38.dp)
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("1 min", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("599 min", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                    BasicTextField(
                        value = value,
                        onValueChange = { if (enabled && it.length <= 3 && it.all(Char::isDigit)) onValueChange(it) },
                        enabled = enabled, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = MaterialTheme.typography.titleMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center),
                        modifier = Modifier.width(66.dp).padding(horizontal = 5.dp, vertical = 10.dp),
                        decorationBox = { inner -> Box(contentAlignment = Alignment.Center) { inner() } }
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text("Minutes", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ParticleTimerDial(
    eyebrow: String,
    time: String,
    status: String,
    progress: Float,
    indicatorProgress: Float = progress,
    particles: MutableList<DialParticle>,
    emitParticles: Boolean,
    releaseParticles: Boolean,
    onReleaseFinished: () -> Unit = {}
) {
    val colors = MaterialTheme.colorScheme
    var animationTimeMs by rememberSaveable { mutableLongStateOf(0L) }
    var releaseStartedAt by remember { mutableLongStateOf(0L) }

    // A staggered close-packed layout fills the circular chamber from the bottom upward.
    // It avoids frame-by-frame particle collision calculations and remains deterministic.
    val particleSlots = remember {
        buildList {
            val center = 132f
            val chamberRadius = 124f
            val spacing = 7.1f
            var row = 0
            var y = center + chamberRadius - 3f
            while (y >= center - chamberRadius + 3f) {
                val dy = y - center
                val halfWidth = sqrt((chamberRadius * chamberRadius - dy * dy).coerceAtLeast(0f))
                val offset = if (row % 2 == 0) 0f else spacing / 2f
                var x = center - halfWidth + 3f + offset
                while (x <= center + halfWidth - 3f) {
                    add(Offset(x, y))
                    x += spacing
                }
                y -= spacing * 0.87f
                row++
            }
        }
    }

    LaunchedEffect(emitParticles, releaseParticles) {
        var emission = 0f
        var previousRealtimeMs = SystemClock.elapsedRealtime()
        if (releaseParticles) releaseStartedAt = animationTimeMs

        while (isActive && (emitParticles || releaseParticles)) {
            val realtimeNow = SystemClock.elapsedRealtime()
            val frameDeltaMs = (realtimeNow - previousRealtimeMs).coerceIn(1L, 40L)
            previousRealtimeMs = realtimeNow
            animationTimeMs += frameDeltaMs
            val nowMs = animationTimeMs

            if (emitParticles && !releaseParticles) {
                emission += 18f * frameDeltaMs.toFloat() / 1_000f
                while (emission >= 1f && particles.size < particleSlots.size) {
                    val slot = particleSlots[particles.size]
                    val jitterX = Random.nextFloat() * 1.8f - 0.9f
                    val jitterY = Random.nextFloat() * 0.8f - 0.4f
                    val heightFraction = ((228f - slot.y) / 192f).coerceIn(0f, 1f)
                    particles.add(
                        DialParticle(
                            x = slot.x + jitterX,
                            y = slot.y + jitterY,
                            bornAtMs = nowMs,
                            radius = Random.nextFloat() * 0.65f + 2.25f,
                            color = ParticlePalette.random(),
                            drainDelayMs = (heightFraction * 220f + Random.nextFloat() * 40f).toLong(),
                            drainDrift = Random.nextFloat() * 12f - 6f
                        )
                    )
                    emission -= 1f
                }
            }

            if (releaseParticles && nowMs - releaseStartedAt >= 1_100L) {
                particles.clear()
                break
            }
            if (releaseParticles && particles.isEmpty()) break
            delay(16L)
        }

        if (releaseParticles) {
            particles.clear()
            onReleaseFinished()
        }
    }

    Box(Modifier.size(264.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(5.dp)) {
            val currentFrameTime = animationTimeMs
            val stroke = 9.dp.toPx()
            val radius = size.minDimension / 2f - stroke / 2f
            drawCircle(colors.surfaceVariant, style = Stroke(stroke))
            drawArc(
                colors.primary, -90f, 360f * progress.coerceIn(0f, 1f), false,
                style = Stroke(stroke, cap = StrokeCap.Round)
            )

            val angle = Math.toRadians(-90.0 + 360.0 * indicatorProgress.coerceIn(0f, 1f))
            val ball = Offset(
                center.x + cos(angle).toFloat() * radius,
                center.y + sin(angle).toFloat() * radius
            )
            drawCircle(Color(0xFFE0F0D9), stroke * 0.9f, ball)
            drawCircle(colors.primary, stroke * 0.43f, ball)

            val chamberRadius = size.minDimension / 2f - 14.dp.toPx()
            particles.forEach { particle ->
                val age = (currentFrameTime - particle.bornAtMs).coerceAtLeast(0L)
                val fall = (age / 520f).coerceIn(0f, 1f)
                val easedFall = 1f - (1f - fall) * (1f - fall)
                val releaseElapsed = (currentFrameTime - releaseStartedAt).coerceAtLeast(0L).toFloat()
                val drainProgress = if (releaseParticles) {
                    ((releaseElapsed - particle.drainDelayMs) / 800f).coerceIn(0f, 1f)
                } else 0f
                val easedDrain = drainProgress * drainProgress
                val baseX = center.x + (particle.x - 132f) / 132f * chamberRadius
                val x = baseX + particle.drainDrift * easedDrain
                val targetY = center.y + (particle.y - 132f) / 132f * chamberRadius
                val startY = center.y - chamberRadius - 12.dp.toPx()
                val settledY = startY + (targetY - startY) * easedFall
                val y = settledY + easedDrain * size.height * 1.05f
                val r = particle.radius / 132f * chamberRadius
                drawCircle(particle.color, r, Offset(x, y))
                drawCircle(
                    Color.White.copy(alpha = 0.34f), r * 0.25f,
                    Offset(x - r * 0.28f, y - r * 0.3f)
                )
            }
        }
        Column(Modifier.fillMaxWidth(0.82f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(eyebrow, style = MaterialTheme.typography.labelMedium,
                color = colors.primary, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            AnimatedContent(time, transitionSpec = {
                (fadeIn(tween(100)) + slideInVertically(tween(120)) { it / 5 }) togetherWith
                    (fadeOut(tween(90)) + slideOutVertically(tween(90)) { -it / 5 })
            }, label = "timer-digits") { shown ->
                Text(shown, color = colors.onBackground,
                    fontSize = if (shown.length > 8) 29.sp else 38.sp,
                    lineHeight = 44.sp, fontWeight = FontWeight.Medium,
                    letterSpacing = (-0.8).sp, textAlign = TextAlign.Center, maxLines = 1)
            }
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
        Button(onClick = onStartPause, Modifier.weight(1f).height(54.dp),
            enabled = startEnabled, shape = CircleShape) {
            AnimatedContent(isRunning, transitionSpec = {
                (fadeIn(tween(150)) + slideInVertically(tween(150)) { it / 2 }) togetherWith
                    (fadeOut(tween(100)) + slideOutVertically(tween(100)) { -it / 2 })
            }, label = "start-pause") { running ->
                Text(if (running) "Pause" else "Start", fontWeight = FontWeight.SemiBold)
            }
        }
        OutlinedButton(onClick = onReset, Modifier.weight(1f).height(54.dp),
            enabled = canReset, shape = CircleShape) { Text("Reset") }
    }
}

private fun formatStopwatch(millis: Long): String {
    val tenths = millis.coerceAtLeast(0L) / 100L
    val seconds = tenths / 10L
    return String.format(Locale.ROOT, "%02d:%02d:%02d.%d",
        seconds / 3600L, (seconds % 3600L) / 60L, seconds % 60L, tenths % 10L)
}
private fun formatCountdown(millis: Long): String {
    val seconds = (millis.coerceAtLeast(0L) + 999L) / 1000L
    val hours = seconds / 3600L
    val mins = (seconds % 3600L) / 60L
    val secs = seconds % 60L
    return if (hours > 0L) String.format(Locale.ROOT, "%02d:%02d:%02d", hours, mins, secs)
    else String.format(Locale.ROOT, "%02d:%02d", mins, secs)
}
