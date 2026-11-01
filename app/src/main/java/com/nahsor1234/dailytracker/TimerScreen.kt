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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
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
import androidx.compose.ui.graphics.Path
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
    var x: Float, var y: Float, var vx: Float, var vy: Float,
    val radius: Float, val color: Color
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
    val progress = if (configuredMillis > 0L)
        (1f - remaining.toFloat() / configuredMillis).coerceIn(0f, 1f) else 0f

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
    val interaction = remember { MutableInteractionSource() }
    val dragging by interaction.collectIsDraggedAsState()
    Column(Modifier.fillMaxWidth()) {
        Text("Custom duration", style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(7.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Box(Modifier.fillMaxWidth().height(38.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxWidth().height(38.dp)) {
                        val left = 8.dp.toPx()
                        val right = size.width - 8.dp.toPx()
                        val cy = size.height / 2f
                        val fraction = ((sliderValue - 1f) / 598f).coerceIn(0f, 1f)
                        val activeEnd = left + (right - left) * fraction
                        drawLine(Color(0xFF354535), Offset(left, cy), Offset(right, cy),
                            5.dp.toPx(), cap = StrokeCap.Round)
                        if (dragging && activeEnd > left + 2f) {
                            val path = Path().apply {
                                moveTo(left, cy)
                                for (i in 0..50) {
                                    val t = i / 50f
                                    val x = left + (activeEnd - left) * t
                                    val wave = sin(i * 0.88f) * 4.5.dp.toPx() * sin(Math.PI * t).toFloat()
                                    lineTo(x, cy + wave)
                                }
                            }
                            drawPath(path, MaterialTheme.colorScheme.primary,
                                style = Stroke(5.dp.toPx(), cap = StrokeCap.Round))
                        } else {
                            drawLine(MaterialTheme.colorScheme.primary, Offset(left, cy),
                                Offset(activeEnd, cy), 5.dp.toPx(), cap = StrokeCap.Round)
                        }
                    }
                    Slider(
                        value = sliderValue,
                        onValueChange = { onValueChange(it.toInt().coerceIn(1, 599).toString()) },
                        valueRange = 1f..599f, enabled = enabled, interactionSource = interaction,
                        modifier = Modifier.fillMaxWidth().height(38.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent,
                            disabledThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            disabledActiveTrackColor = Color.Transparent,
                            disabledInactiveTrackColor = Color.Transparent
                        )
                    )
                }
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
    particles: MutableList<DialParticle>,
    emitParticles: Boolean,
    releaseParticles: Boolean,
    onReleaseFinished: () -> Unit = {}
) {
    val colors = MaterialTheme.colorScheme
    LaunchedEffect(emitParticles, releaseParticles) {
        var accumulator = 0f
        while (isActive && (emitParticles || releaseParticles || particles.isNotEmpty())) {
            val dt = 0.016f
            if (emitParticles && !releaseParticles) {
                accumulator += 32f * dt
                while (accumulator >= 1f && particles.size < 520) {
                    val a = Random.nextFloat() * 0.72f - 0.36f
                    particles.add(DialParticle(
                        x = 132f + sin(a) * 17f, y = 17f,
                        vx = Random.nextFloat() * 12f - 6f, vy = 0f,
                        radius = Random.nextFloat() * 1.7f + 2.1f,
                        color = ParticlePalette.random()
                    ))
                    accumulator -= 1f
                }
            }
            particles.forEach { p ->
                p.vy += (if (releaseParticles) 760f else 500f) * dt
                p.x += p.vx * dt
                p.y += p.vy * dt
                if (!releaseParticles) {
                    val dx = p.x - 132f
                    val dy = p.y - 132f
                    val distance = sqrt(dx * dx + dy * dy)
                    val boundary = 113f - p.radius
                    if (distance > boundary && distance > 0f) {
                        val nx = dx / distance
                        val ny = dy / distance
                        p.x = 132f + nx * boundary
                        p.y = 132f + ny * boundary
                        val dot = p.vx * nx + p.vy * ny
                        p.vx -= 1.25f * dot * nx
                        p.vy -= 1.25f * dot * ny
                    }
                }
            }
            if (!releaseParticles) {
                for (i in 0 until particles.size) {
                    val a = particles[i]
                    for (j in i + 1 until particles.size) {
                        val b = particles[j]
                        val dx = b.x - a.x
                        val dy = b.y - a.y
                        val d2 = dx * dx + dy * dy
                        val min = a.radius + b.radius
                        if (d2 < min * min && d2 > 0.001f) {
                            val d = sqrt(d2)
                            val nx = dx / d
                            val ny = dy / d
                            val overlap = (min - d) * 0.5f
                            a.x -= nx * overlap; a.y -= ny * overlap
                            b.x += nx * overlap; b.y += ny * overlap
                            val relative = (b.vx - a.vx) * nx + (b.vy - a.vy) * ny
                            if (relative < 0f) {
                                a.vx += relative * 0.2f * nx; a.vy += relative * 0.2f * ny
                                b.vx -= relative * 0.2f * nx; b.vy -= relative * 0.2f * ny
                            }
                        }
                    }
                }
            }
            if (releaseParticles) particles.removeAll { it.y > 280f }
            delay(16L)
        }
        if (releaseParticles) {
            particles.clear()
            onReleaseFinished()
        }
    }

    Box(Modifier.size(264.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(5.dp)) {
            val stroke = 9.dp.toPx()
            val radius = size.minDimension / 2f - stroke / 2f
            drawCircle(colors.surfaceVariant, style = Stroke(stroke))
            drawArc(colors.primary, -90f, 360f * progress.coerceIn(0f, 1f), false,
                style = Stroke(stroke, cap = StrokeCap.Round))
            val angle = Math.toRadians(-90.0 + 360.0 * progress.coerceIn(0f, 1f))
            val ball = Offset(center.x + cos(angle).toFloat() * radius,
                center.y + sin(angle).toFloat() * radius)
            drawCircle(Color(0xFFE0F0D9), stroke * 0.88f, ball)
            drawCircle(colors.primary, stroke * 0.42f, ball)
            val chamberRadius = size.minDimension / 2f - 14.dp.toPx()
            particles.forEach { p ->
                val x = center.x + (p.x - 132f) / 132f * chamberRadius
                val y = center.y + (p.y - 132f) / 132f * chamberRadius
                val r = p.radius / 132f * chamberRadius
                drawCircle(p.color, r, Offset(x, y))
                drawCircle(Color.White.copy(alpha = 0.28f), r * 0.23f,
                    Offset(x - r * 0.28f, y - r * 0.3f))
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
