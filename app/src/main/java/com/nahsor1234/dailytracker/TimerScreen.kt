package com.nahsor1234.dailytracker

import android.Manifest
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

private enum class TimerMode { STOPWATCH, COUNTDOWN }

private const val DEFAULT_COUNTDOWN_MINUTES = 25L
private const val MAX_COUNTDOWN_MINUTES = 999L

private class TimerStateStore(context: android.content.Context) {
    private val prefs = context.applicationContext.getSharedPreferences("timer_state", android.content.Context.MODE_PRIVATE)
    fun long(key: String, fallback: Long = 0L) = prefs.getLong(key, fallback)
    fun bool(key: String, fallback: Boolean = false) = prefs.getBoolean(key, fallback)
    fun string(key: String, fallback: String) = prefs.getString(key, fallback) ?: fallback
    fun save(
        mode: String, watchBase: Long, watchStarted: Long, watchRunning: Boolean,
        minutes: String, remaining: Long, deadline: Long, countdownRunning: Boolean, finished: Boolean
    ) {
        prefs.edit()
            .putString("mode", mode).putLong("watch_base", watchBase)
            .putLong("watch_started", watchStarted).putBoolean("watch_running", watchRunning)
            .putString("minutes", minutes).putLong("remaining", remaining)
            .putLong("deadline", deadline).putBoolean("countdown_running", countdownRunning)
            .putBoolean("finished", finished).apply()
    }
}
private val DurationPresets = listOf(5, 15, 25, 45, 60)
private val ModeSegmentWidth = 120.dp // (248dp pill - 2 * 4dp padding) / 2 segments

// ---------------------------------------------------------------------------------------------
// Dot field. Three layers, all drawn as a pure function of the elapsed time (nothing is
// simulated and no separate animation clock is involved):
//
//  - Inner field (rings 1..10): the dots shrink away outside-in during one minute and pop back
//    inside-out the next. Leaving dots drift slightly outward and flash at the moving edge; gone
//    dots stay as tiny pinpoints so the shape of the field is always visible. While the timer
//    runs, ripples travel outward through the field ("pond"): each ring is pushed in and out and
//    swells as a wave crest passes. The ripple fades out when paused or finished.
//  - Seconds dot (ring 11): one bright dot with a short tail that circles once per minute.
//  - Outer ring (ring 12): 75 dots. For a countdown it empties clockwise over the whole session;
//    for the stopwatch it fills once per minute.
//
// In the last minute of a countdown the colour warms from green to amber, in the last ten
// seconds everything pulses, and at zero a shockwave sweeps outward and re-lights the field.
// ---------------------------------------------------------------------------------------------

private const val INNER_RINGS = 10
private const val SECONDS_RING = 11
private const val OUTER_RING = 12
private const val OUTER_DOTS = 75
// How long a single dot takes to shrink away / pop back in, and how far apart the dots of one ring
// start. These are real durations, so the dissolve looks the same however long a phase is: a
// fraction of a 60 s phase would make every dot crawl for several seconds and blur the edge.
private const val FRONT_WIDTH = 1_200f / 60_000f
private const val SEED_JITTER = 4_000f / 60_000f
private const val DRIFT_CELLS = 0.6f
private const val WAVE_WIDTH = 0.12f
private const val RIPPLE_FREQ = 0.18f   // crests per ring: about one crest every 5.5 rings
private const val RIPPLE_SPEED = 0.5f    // crests passing a ring per second
private const val RIPPLE_PUSH = 0.4f     // radial shift, in cells, at the centre (less near the edge)
private const val RIPPLE_SWELL = 0.2f    // size change at a crest
private const val WAVE_END = 1.3f
private const val PHASE_MILLIS = 60_000L
private const val URGENCY_MILLIS = 60_000L
private const val FINAL_BEAT_MILLIS = 10_000L

private val AmberTone = Color(0xFFE0A84A)
private val FrontGreen = Color(0xFFCFEBD6)
private val FrontAmber = Color(0xFFFFE3A8)

/** [x]/[y] are in cell units from the centre; [ux]/[uy] is the outward direction. */
private class Dot(
    val ring: Int,
    val x: Float,
    val y: Float,
    val ux: Float,
    val uy: Float,
    val rho: Float,
    val seed: Float
)

/** Built once per process and shared by every dot field. */
private val DotLayout: List<Dot> by lazy { buildDotField(INNER_RINGS) }
private val OuterCos: FloatArray by lazy {
    FloatArray(OUTER_DOTS) { cos(-PI / 2.0 + 2.0 * PI * it / OUTER_DOTS).toFloat() }
}
private val OuterSin: FloatArray by lazy {
    FloatArray(OUTER_DOTS) { sin(-PI / 2.0 + 2.0 * PI * it / OUTER_DOTS).toFloat() }
}

private fun buildDotField(rings: Int): List<Dot> = buildList {
    add(Dot(0, 0f, 0f, 0f, 0f, 0f, 0.5f))
    for (k in 1..rings) {
        val count = (2.0 * PI * k).roundToInt()
        val phase = if (k % 2 == 0) 0.0 else PI / count // half-step offset gives a hex-like weave
        for (i in 0 until count) {
            val angle = 2.0 * PI * i / count + phase - PI / 2.0
            val seed = (((k * 73856093) xor (i * 19349663)) and 0xFFFF) / 65535f
            val ux = cos(angle).toFloat()
            val uy = sin(angle).toFloat()
            add(Dot(k, ux * k, uy * k, ux, uy, k.toFloat() / rings, seed))
        }
    }
}

/**
 * 0 = fully present, 1 = fully gone. Each dot has its own short transition window, placed by its
 * ring (outside-in or inside-out) plus a little per-dot jitter. Both directions end a phase in
 * exactly the state the opposite direction starts from (every dot gone, or every dot present),
 * so consecutive phases join without a jump.
 */
private fun dotLeave(dot: Dot, progress: Float, dissolving: Boolean): Float {
    val span = 1f - FRONT_WIDTH - SEED_JITTER
    val threshold = (if (dissolving) 1f - dot.rho else dot.rho) * span + dot.seed * SEED_JITTER
    val u = ((progress - threshold) / FRONT_WIDTH).coerceIn(0f, 1f)
    return if (dissolving) u else 1f - u
}

private fun easeOutBack(x: Float): Float {
    val c1 = 1.70158f
    val c3 = c1 + 1f
    val t = x - 1f
    return 1f + c3 * t * t * t + c1 * t * t
}

/** Position (0..1) inside the current one-minute phase. */
private fun phaseProgress(elapsedMillis: Long): Float =
    (elapsedMillis.coerceAtLeast(0L) % PHASE_MILLIS).toFloat() / PHASE_MILLIS

/** Whether the current one-minute phase makes dots disappear; [dissolveFirst] sets the first one. */
private fun phaseDissolves(elapsedMillis: Long, dissolveFirst: Boolean): Boolean {
    val inEvenPhase = (elapsedMillis.coerceAtLeast(0L) / PHASE_MILLIS) % 2L == 0L
    return inEvenPhase == dissolveFirst
}

/**
 * [elapsedMillis] is read on every drawn frame, straight from the clock, so the animation is not
 * limited by how often the screen recomposes. While [animate] is true the canvas redraws every
 * frame; otherwise it only redraws when its state changes. [totalMillis] is the length of a
 * countdown session (drives the outer ring and the colour warm-up); pass null for the stopwatch.
 */
@Composable
private fun DotField(
    elapsedMillis: () -> Long,
    dissolveFirst: Boolean,
    running: Boolean,
    animate: Boolean,
    finished: Boolean,
    totalMillis: Long?,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val dots = DotLayout
    val frame = remember { mutableLongStateOf(0L) }
    LaunchedEffect(animate) {
        if (animate) {
            while (true) withFrameNanos { frame.longValue = it }
        }
    }
    val wave = remember { Animatable(0f) }
    LaunchedEffect(finished) {
        wave.snapTo(0f)
        if (finished) wave.animateTo(WAVE_END, tween(1400, easing = LinearEasing))
    }
    val breath by rememberInfiniteTransition(label = "finish-breath").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500), RepeatMode.Reverse),
        label = "finish-breath-value"
    )
    // The ripple eases in when the timer starts and relaxes when it stops, so a paused field is calm.
    val ripple by animateFloatAsState(
        targetValue = if (running && !finished) 1f else 0f,
        animationSpec = tween(600),
        label = "ripple"
    )
    val ringWave = remember { FloatArray(INNER_RINGS + 1) }
    val hasTotal = totalMillis != null && totalMillis > 0L

    Canvas(modifier) {
        // Reading the frame counter is what redraws the canvas on every frame while animating.
        frame.longValue

        val elapsed = elapsedMillis()
        val progress = phaseProgress(elapsed)
        val dissolving = phaseDissolves(elapsed, dissolveFirst)
        val remainingMillis = if (hasTotal) totalMillis!! - elapsed else Long.MAX_VALUE
        val urgency = if (hasTotal) {
            ((URGENCY_MILLIS - remainingMillis) / URGENCY_MILLIS.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
        val ringConsumed = if (hasTotal) {
            (elapsed.toFloat() / totalMillis!!).coerceIn(0f, 1f) * OUTER_DOTS
        } else {
            progress * OUTER_DOTS
        }
        val secondsAngle = -PI.toFloat() / 2f + 2f * PI.toFloat() * progress
        val finalBeat = hasTotal && !finished && remainingMillis in 1..FINAL_BEAT_MILLIS

        val center = Offset(size.width / 2f, size.height / 2f)
        val outerRadius = size.minDimension / 2f
        val cell = outerRadius / (OUTER_RING + 0.7f)
        val dotRadius = cell * 0.27f
        val live = running && !finished

        val base = if (urgency > 0f) lerp(colors.primary, AmberTone, urgency) else colors.primary
        val front = if (urgency > 0f) lerp(FrontGreen, FrontAmber, urgency) else FrontGreen
        val beat = if (finalBeat) 0.78f + 0.22f * sin(elapsed / 1000f * 4f * PI.toFloat()) else 1f
        val solid = base.copy(alpha = beat)
        val ghost = base.copy(alpha = 0.22f)
        val w = wave.value
        val rippleAmp = ripple
        if (rippleAmp > 0f) {
            val seconds = elapsed / 1000f
            for (ring in 0..INNER_RINGS) {
                ringWave[ring] = rippleAmp * sin(2f * PI.toFloat() * (ring * RIPPLE_FREQ - seconds * RIPPLE_SPEED))
            }
        }
        val breathe = if (finished && w >= WAVE_END) 0.92f + 0.08f * breath else 1f

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

        // Inner field.
        for (dot in dots) {
            // Ripple: push the dot along its ring's outward direction and swell it at a crest.
            val crest = if (rippleAmp > 0f) ringWave[dot.ring] else 0f
            val push = crest * RIPPLE_PUSH * (1f - 0.5f * dot.rho) * cell
            val swell = 1f + RIPPLE_SWELL * crest
            val position = Offset(
                center.x + dot.x * cell + dot.ux * push,
                center.y + dot.y * cell + dot.uy * push
            )
            if (finished) {
                val lit = ((w - dot.rho) / WAVE_WIDTH).coerceIn(0f, 1f)
                if (lit <= 0f) {
                    drawCircle(ghost, cell * 0.07f, position)
                    continue
                }
                val flash = (1f - abs(w - dot.rho) / 0.1f).coerceIn(0f, 1f)
                val tint = if (flash > 0f) lerp(base, front, flash * 0.85f) else base
                drawCircle(tint, dotRadius * easeOutBack(lit) * breathe, position)
                continue
            }
            val leave = dotLeave(dot, progress, dissolving)
            if (leave >= 1f) {
                drawCircle(ghost, cell * 0.07f * swell, position)
                continue
            }
            if (leave <= 0f) {
                drawCircle(solid, dotRadius * swell, position)
                continue
            }
            // In transition: shrink (or pop back in) while drifting outward.
            drawCircle(ghost.copy(alpha = 0.22f * minOf(1f, leave * 3f)), cell * 0.07f * swell, position)
            val visible = 1f - leave
            val scale = if (dissolving) 1f - leave.pow(2.2f) else easeOutBack(visible)
            val drift = (if (dissolving) leave.pow(1.6f) else (1f - visible) * (1f - visible)) * DRIFT_CELLS * cell
            val tint = if (live) lerp(base, front, sin(PI.toFloat() * leave) * 0.85f) else base
            drawCircle(
                color = tint,
                radius = dotRadius * maxOf(scale, 0f) * swell,
                center = Offset(position.x + dot.ux * drift, position.y + dot.uy * drift)
            )
        }

        // Outer ring: whole-session progress (countdown) or this minute's seconds (stopwatch).
        val edge = consumedEdge(ringConsumed)
        for (j in 0 until OUTER_DOTS) {
            val lit: Float
            val flash: Float
            if (finished) {
                lit = ((w - 1.1f) / WAVE_WIDTH).coerceIn(0f, 1f)
                flash = (1f - abs(w - 1.1f) / 0.1f).coerceIn(0f, 1f)
            } else {
                lit = if (hasTotal) (j + 1f - ringConsumed).coerceIn(0f, 1f) else (ringConsumed - j).coerceIn(0f, 1f)
                flash = if (live && j == edge) 1f else 0f
            }
            val tint = if (flash > 0f) lerp(base, front, 0.9f * flash) else base
            drawCircle(
                color = tint.copy(alpha = ((0.07f + 0.88f * lit) * beat).coerceIn(0f, 1f)),
                radius = cell * 0.3f * (0.6f + 0.4f * lit) * breathe,
                center = Offset(
                    center.x + OuterCos[j] * OUTER_RING * cell,
                    center.y + OuterSin[j] * OUTER_RING * cell
                )
            )
        }

        // Seconds dot with a short tail.
        if (!finished) {
            for (q in 5 downTo 0) {
                val angle = secondsAngle - q * 0.1f
                drawCircle(
                    color = front.copy(alpha = if (q == 0) 1f else 0.5f / q),
                    radius = cell * (if (q == 0) 0.42f else 0.3f - q * 0.03f),
                    center = Offset(
                        center.x + cos(angle) * SECONDS_RING * cell,
                        center.y + sin(angle) * SECONDS_RING * cell
                    )
                )
            }
        }
    }
}

private fun consumedEdge(consumed: Float): Int = consumed.toInt().coerceIn(0, OUTER_DOTS - 1)

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
internal fun TimerScreen(modifier: Modifier = Modifier, active: Boolean = true) {
    val context = LocalContext.current
    val timerStore = remember { TimerStateStore(context) }
    var mode by remember { mutableStateOf(runCatching { TimerMode.valueOf(timerStore.string("mode", TimerMode.STOPWATCH.name)) }.getOrDefault(TimerMode.STOPWATCH)) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var watchElapsedBase by remember { mutableLongStateOf(timerStore.long("watch_base")) }
    var watchStartedAt by remember { mutableLongStateOf(timerStore.long("watch_started")) }
    var watchRunning by remember { mutableStateOf(timerStore.bool("watch_running")) }
    var minutesText by remember { mutableStateOf(timerStore.string("minutes", DEFAULT_COUNTDOWN_MINUTES.toString())) }
    var remainingBase by remember { mutableLongStateOf(timerStore.long("remaining", DEFAULT_COUNTDOWN_MINUTES * 60_000L)) }
    var countdownDeadline by remember {
        mutableLongStateOf(TimerAlerts.reconcileDeadline(context, timerStore.long("deadline")))
    }
    var countdownRunning by remember { mutableStateOf(timerStore.bool("countdown_running")) }
    var finished by remember { mutableStateOf(timerStore.bool("finished")) }

    val haptics = rememberHaptics()
    val alertSettings = remember { TimerAlertSettings(context) }
    var soundOn by remember { mutableStateOf(alertSettings.soundEnabled) }
    var vibrationOn by remember { mutableStateOf(alertSettings.vibrationEnabled) }
    var keepScreenOn by remember { mutableStateOf(alertSettings.keepScreenOn) }
    var ringtoneUri by remember { mutableStateOf(alertSettings.ringtoneUri) }
    var notificationsBlocked by remember { mutableStateOf(false) }
    val ringtoneTitle by produceState("Default alarm", ringtoneUri) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                ringtoneUri?.let { RingtoneManager.getRingtone(context, Uri.parse(it))?.getTitle(context) }
            }.getOrNull() ?: "Default alarm"
        }
    }
    val ringtonePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val picked = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            alertSettings.ringtoneUri = picked?.toString()
            ringtoneUri = alertSettings.ringtoneUri
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> notificationsBlocked = !granted }

    // Re-check every time the Timer screen is shown, so a blocked notification is flagged here
    // before a countdown ever runs, not only once at the first start.
    LaunchedEffect(active) {
        if (active) notificationsBlocked = !TimerAlerts.notificationsAllowed(context)
    }

    // "Keep screen on": only while the Timer screen is the one being shown, in Countdown mode and
    // with the option switched on. The flag is released as soon as any of those stops being true
    // (leaving the screen, switching to Stopwatch, switching the option off), so normal screen
    // timeout applies everywhere else.
    val hostView = androidx.compose.ui.platform.LocalView.current
    val holdScreenAwake = keepScreenOn && active && mode == TimerMode.COUNTDOWN
    DisposableEffect(holdScreenAwake, hostView) {
        if (holdScreenAwake) hostView.keepScreenOn = true
        onDispose { if (holdScreenAwake) hostView.keepScreenOn = false }
    }

    // The screen stays composed while Progress is shown. All readings are derived from the
    // elapsed-realtime clock, so while hidden there is nothing to redraw at 20 Hz: a running
    // countdown only needs to wake at its deadline (to finish and alert), a stopwatch not at all.
    LaunchedEffect(watchRunning, countdownRunning, active) {
        while (watchRunning || countdownRunning) {
            now = SystemClock.elapsedRealtime()
            delay(
                when {
                    active -> 50L
                    countdownRunning -> (countdownDeadline - now).coerceIn(1L, 60_000L)
                    else -> 60_000L
                }
            )
        }
        now = SystemClock.elapsedRealtime()
    }

    LaunchedEffect(mode, watchElapsedBase, watchStartedAt, watchRunning, minutesText, remainingBase, countdownDeadline, countdownRunning, finished) {
        timerStore.save(
            mode.name, watchElapsedBase, watchStartedAt, watchRunning,
            minutesText, remainingBase, countdownDeadline, countdownRunning, finished
        )
    }

    val watchElapsed = watchElapsedBase +
        if (watchRunning) (now - watchStartedAt).coerceAtLeast(0L) else 0L
    val remaining = if (countdownRunning)
        (countdownDeadline - now).coerceAtLeast(0L) else remainingBase

    LaunchedEffect(countdownRunning, remaining) {
        if (countdownRunning && remaining <= 0L) {
            val overdue = SystemClock.elapsedRealtime() - countdownDeadline
            remainingBase = 0L
            countdownRunning = false
            finished = true
            // Only take over from the system alarm while the app is actually on screen. In the
            // background the system alarm (ringing service + notification with a Stop button) is
            // already handling it; cancelling it here is what used to leave the sound playing with
            // no notification to stop it.
            if (TimerAlertState.appInForeground) {
                TimerAlerts.cancel(context)
                TimerAlerts.dismissNotification(context)
                if (overdue < 3_000L) {
                    haptics.confirm()
                    TimerAlarmPlayer.start(context, alertSettings)
                }
            }
        }
    }

    val configuredMillis = remember(minutesText) {
        (minutesText.toLongOrNull()?.takeIf { it in 1L..MAX_COUNTDOWN_MINUTES } ?: 0L) * 60_000L
    }

    val startCountdown: () -> Unit = {
        val startedAt = SystemClock.elapsedRealtime()
        val deadline = startedAt + remainingBase
        TimerAlerts.schedule(context, deadline)
        now = startedAt
        countdownDeadline = deadline
        finished = false
        countdownRunning = true
        TimerAlarmPlayer.stop(context)
        val allowed = TimerAlerts.notificationsAllowed(context)
        notificationsBlocked = !allowed
        if (!allowed && Build.VERSION.SDK_INT >= 33 &&
            !alertSettings.notificationPermissionAsked
        ) {
            alertSettings.notificationPermissionAsked = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val onMinutesChange: (String) -> Unit = { input ->
        minutesText = input
        if (finished) TimerAlarmPlayer.stop(context)
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
                if (finished) TimerAlarmPlayer.stop(context)
                finished = false
            }
            Spacer(Modifier.height(20.dp))

            if (mode == TimerMode.STOPWATCH) {
                DotField(
                    elapsedMillis = {
                        watchElapsedBase +
                            if (watchRunning) (SystemClock.elapsedRealtime() - watchStartedAt).coerceAtLeast(0L) else 0L
                    },
                    dissolveFirst = false,
                    running = watchRunning,
                    animate = watchRunning && active,
                    finished = false,
                    totalMillis = null,
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
                    elapsedMillis = {
                        val left = if (countdownRunning) {
                            (countdownDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                        } else {
                            remainingBase
                        }
                        (configuredMillis - left).coerceIn(0L, configuredMillis)
                    },
                    dissolveFirst = true,
                    running = countdownRunning,
                    animate = countdownRunning && active,
                    finished = finished,
                    totalMillis = configuredMillis,
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
                            TimerAlerts.cancel(context)
                        } else if (remainingBase > 0L) {
                            startCountdown()
                        }
                    },
                    onReset = {
                        countdownRunning = false
                        countdownDeadline = 0L
                        remainingBase = configuredMillis
                        finished = false
                        TimerAlerts.cancel(context)
                        TimerAlerts.dismissNotification(context)
                        TimerAlarmPlayer.stop(context)
                    }
                )
            }

            if (mode == TimerMode.COUNTDOWN) {
                Spacer(Modifier.height(24.dp))
                TimerAlertsCard(
                    soundOn = soundOn,
                    vibrationOn = vibrationOn,
                    keepScreenOn = keepScreenOn,
                    soundName = ringtoneTitle,
                    notificationsBlocked = notificationsBlocked,
                    onSoundChange = {
                        soundOn = it
                        alertSettings.soundEnabled = it
                        haptics.toggle(it)
                    },
                    onVibrationChange = {
                        vibrationOn = it
                        alertSettings.vibrationEnabled = it
                        haptics.toggle(it)
                    },
                    onKeepScreenOnChange = {
                        keepScreenOn = it
                        alertSettings.keepScreenOn = it
                        haptics.toggle(it)
                    },
                    onPickSound = {
                        haptics.click()
                        val existing = ringtoneUri?.let(Uri::parse)
                        ringtonePicker.launch(
                            Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Timer sound")
                                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                                putExtra(
                                    RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                                    existing ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                                )
                            }
                        )
                    },
                    onTest = {
                        TimerAlarmPlayer.start(context, alertSettings, 3_000L)
                    }
                )
            }
            Spacer(Modifier.navigationBarsPadding().height(120.dp))
        }
    }
}

@Composable
private fun TimerAlertsCard(
    soundOn: Boolean,
    vibrationOn: Boolean,
    keepScreenOn: Boolean,
    soundName: String,
    notificationsBlocked: Boolean,
    onSoundChange: (Boolean) -> Unit,
    onVibrationChange: (Boolean) -> Unit,
    onKeepScreenOnChange: (Boolean) -> Unit,
    onPickSound: () -> Unit,
    onTest: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(24.dp)
    val testSource = rememberPressSource()
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(Color.White.copy(alpha = 0.035f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), shape)
            .padding(horizontal = 18.dp, vertical = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "COUNTDOWN OPTIONS", color = colors.primary,
                style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.8.sp)
            )
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.pressScale(testSource).clip(CircleShape)
                    .border(1.dp, colors.primary.copy(alpha = 0.45f), CircleShape)
                    .hapticClickable(testSource, haptic = { click() }, onClick = onTest)
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text("Test", color = colors.primary, style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium))
            }
        }
        Spacer(Modifier.height(10.dp))
        AlertToggleRow(
            title = "Sound",
            subtitle = if (soundOn) soundName else "Off",
            checked = soundOn,
            onCheckedChange = onSoundChange,
            onBodyClick = if (soundOn) onPickSound else null
        )
        HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
        AlertToggleRow(
            title = "Vibration",
            subtitle = if (vibrationOn) "On" else "Off",
            checked = vibrationOn,
            onCheckedChange = onVibrationChange,
            onBodyClick = null
        )
        HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
        AlertToggleRow(
            title = "Keep screen on",
            subtitle = if (keepScreenOn) "Screen stays awake on this countdown screen" else "Off",
            checked = keepScreenOn,
            onCheckedChange = onKeepScreenOnChange,
            onBodyClick = null
        )
        if (notificationsBlocked) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Notifications are blocked, so the alert can't reach you while the app is closed. " +
                    "Turn them on for DailyTracker in system settings.",
                color = colors.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun AlertToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onBodyClick: (() -> Unit)?
) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                .then(if (onBodyClick != null) Modifier.clickable(onClick = onBodyClick) else Modifier)
                .padding(vertical = 6.dp)
        ) {
            Text(title, color = colors.onSurface, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (onBodyClick != null) "$subtitle  ›" else subtitle,
                color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.onPrimary,
                checkedTrackColor = colors.primary,
                uncheckedThumbColor = colors.onSurfaceVariant,
                uncheckedTrackColor = Color.White.copy(alpha = 0.08f),
                uncheckedBorderColor = Color.White.copy(alpha = 0.18f)
            )
        )
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
    val haptics = rememberHaptics()
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
                        .clickable {
                            if (!selected) haptics.select()
                            onModeChange(option)
                        },
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
    val haptics = rememberHaptics()
    val line = Color.White.copy(alpha = 0.12f)
    Column(Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.4f)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DurationPresets.forEach { minutes ->
                val selected = value == minutes.toString()
                Box(
                    Modifier.weight(1f).height(40.dp).clip(CircleShape)
                        .background(if (selected) colors.primary else Color.Transparent)
                        .border(1.dp, if (selected) colors.primary else line, CircleShape)
                        .clickable(enabled = enabled) {
                            if (!selected) haptics.select()
                            onValueChange(minutes.toString())
                        },
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
    val resetSource = rememberPressSource()
    val startSource = rememberPressSource()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(28.dp)
    ) {
        Box(
            Modifier.pressScale(resetSource).size(64.dp).alpha(if (canReset) 1f else 0.45f).clip(CircleShape)
                .border(1.dp, Color.White.copy(alpha = 0.24f), CircleShape)
                .hapticClickable(resetSource, enabled = canReset, haptic = { heavy() }) { onReset() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                "Reset",
                // A little brighter than before (about 10% more contrast); size and weight unchanged.
                color = androidx.compose.ui.graphics.lerp(colors.onSurfaceVariant, colors.onSurface, 0.35f),
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium)
            )
        }
        Box(
            Modifier.pressScale(startSource, 0.9f).size(88.dp).alpha(if (startEnabled) 1f else 0.35f).clip(CircleShape)
                .background(colors.primary)
                .hapticClickable(startSource, enabled = startEnabled, haptic = {
                    if (isRunning) click() else confirm()
                }) { onStartPause() },
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