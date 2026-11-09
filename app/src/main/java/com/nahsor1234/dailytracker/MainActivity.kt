package com.nahsor1234.dailytracker

import android.os.Bundle
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.compose.material3.AlertDialog
import kotlinx.coroutines.delay
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import kotlin.math.PI
import kotlin.math.sin
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton

private enum class TrackerDestination { PROGRESS, TIMER }

internal enum class SyncPhase { SIGNED_OUT, SYNCING, SYNCED, OFFLINE, ERROR }

/** Turns a raw failure into a sentence a person can act on. */
internal fun friendlySyncError(error: Throwable): String {
    val raw = error.message.orEmpty()
    return when {
        raw.contains("another account", ignoreCase = true) ||
            raw.contains("different account", ignoreCase = true) ->
            "This device's history belongs to a different account, so nothing was synced."
        raw.isBlank() -> "Sync didn't work this time. Tap to try again."
        else -> "Sync didn't work: $raw"
    }
}

/** "Synced just now", "Synced 5 min ago", ... */
internal fun syncAgeText(lastSyncedAt: Long, now: Long): String {
    if (lastSyncedAt <= 0L) return "Synced"
    val minutes = ((now - lastSyncedAt) / 60_000L).coerceAtLeast(0L)
    return when {
        minutes < 1L -> "Synced just now"
        minutes < 60L -> "Synced $minutes min ago"
        minutes < 24L * 60L -> "Synced ${minutes / 60L} h ago"
        else -> "Synced ${minutes / (24L * 60L)} d ago"
    }
}

class MainActivity : ComponentActivity() {
    private var today by mutableStateOf(LocalDate.now(ZoneId.systemDefault()))
    private var authCallbackUri by mutableStateOf<Uri?>(null)
    private val repository by lazy(LazyThreadSafetyMode.NONE) {
        DailyEntryRepository(applicationContext)
    }
    private val cloud by lazy(LazyThreadSafetyMode.NONE) {
        DailyTrackerCloud(applicationContext)
    }

    // Bumped every time the app comes to the foreground, so the UI can retry a sync that failed
    // while the phone was offline.
    private var startCount by mutableIntStateOf(0)

    // Keeps "today" correct while the app stays open across midnight or after a clock/time-zone
    // change. Without it the screen kept showing yesterday's editor until the app was reopened.
    private val dateChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            today = LocalDate.now(ZoneId.systemDefault())
        }
    }
    private var dateReceiverRegistered = false

    override fun onStart() {
        super.onStart()
        TimerAlertState.appInForeground = true
        startCount += 1
        runCatching {
            ContextCompat.registerReceiver(
                this,
                dateChangeReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_DATE_CHANGED)
                    addAction(Intent.ACTION_TIME_CHANGED)
                    addAction(Intent.ACTION_TIMEZONE_CHANGED)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            dateReceiverRegistered = true
        }
        today = LocalDate.now(ZoneId.systemDefault())
        // Opening the app silences whatever is ringing; the notification's own buttons do the same.
        runCatching {
            AlarmRinging.stop(this)
            TimerAlerts.dismissNotification(this)
        }
    }

    override fun onStop() {
        TimerAlertState.appInForeground = false
        TimerAlarmPlayer.stop(this)
        repository.flushPendingSave()
        repository.flushQueuedSync()
        if (dateReceiverRegistered) {
            runCatching { unregisterReceiver(dateChangeReceiver) }
            dateReceiverRegistered = false
        }
        super.onStop()
    }

    // While something is ringing, either volume button silences it (and is not also used to
    // change the volume).
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if ((keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP ||
                keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) &&
            (TimerAlarmPlayer.ringing || AlarmRinging.current != null)
        ) {
            TimerAlarmPlayer.stop(this)
            TimerAlerts.dismissNotification(this)
            AlarmRinging.stop(this)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /** A sign-in link is used once: forget it here so rotating the phone cannot replay it. */
    private fun consumeAuthLink() {
        authCallbackUri = null
        setIntent(Intent(intent).apply { data = null })
    }

    override fun onResume() {
        super.onResume()
        today = LocalDate.now(ZoneId.systemDefault())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        runCatching { AlarmNotifications.ensureChannels(this, cleanup = true) }
        // Re-arming alarms reads stored reminders from disk: keep that off the first frame.
        val appContext = applicationContext
        Thread({ runCatching { ReminderScheduler.rescheduleAll(appContext) } }, "dailytracker-reminders").start()
        // Only a fresh launch reads the link. After a rotation or process restart the same intent
        // is delivered again, and the link (already used) must not be processed a second time.
        if (savedInstanceState == null) authCallbackUri = intent?.data
        setContent {
            DailyTrackerTheme {
                DailyTrackerApp(
                    repository = repository,
                    today = today,
                    cloud = cloud,
                    startCount = startCount,
                    pendingAuthUri = authCallbackUri,
                    onAuthUriHandled = ::consumeAuthLink
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        authCallbackUri = intent.data
    }
}

@Composable
private fun DailyTrackerApp(
    repository: DailyEntryRepository,
    today: LocalDate,
    cloud: DailyTrackerCloud,
    startCount: Int,
    pendingAuthUri: Uri?,
    onAuthUriHandled: () -> Unit
) {
    val appContext = LocalContext.current.applicationContext
    var destination by rememberSaveable { mutableStateOf(TrackerDestination.PROGRESS) }
    val stateHolder = rememberSaveableStateHolder()
    val colors = MaterialTheme.colorScheme
    var showReminders by rememberSaveable { mutableStateOf(false) }
    // Once the Timer has been opened it stays composed (like Progress), so switching screens only
    // moves two already-built layers instead of building/destroying a screen mid-animation.
    var timerVisited by rememberSaveable { mutableStateOf(false) }
    var showAccount by rememberSaveable { mutableStateOf(false) }
    var cloudAccountId by remember { mutableStateOf<String?>(null) }
    var cloudSyncStatus by rememberSaveable { mutableStateOf("") }
    var syncPhase by rememberSaveable { mutableStateOf(SyncPhase.SIGNED_OUT) }
    var lastSyncedAt by rememberSaveable { mutableLongStateOf(0L) }
    var clockNow by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var cloudDataRevision by remember { mutableIntStateOf(0) }
    var showSyncToast by remember { mutableStateOf(false) }
    var pendingLink by remember { mutableStateOf<DailyTrackerCloud.PendingAuthLink?>(null) }
    var showSetup by rememberSaveable { mutableStateOf(!SetupPrefs.isDone(appContext)) }
    var passwordRecoveryPending by rememberSaveable { mutableStateOf(false) }
    val syncWorker = remember(cloud) {
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "dailytracker-session-check").apply { isDaemon = true }
        }
    }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val focusManager = LocalFocusManager.current

    fun handleCloudSyncResult(result: Result<Int>) {
        result.onSuccess {
            cloudDataRevision += 1
            lastSyncedAt = System.currentTimeMillis()
            syncPhase = SyncPhase.SYNCED
            cloudSyncStatus = "Your history is up to date."
        }.onFailure { error ->
            when (error) {
                // The sign-in itself is no longer valid: this is the only case that signs out.
                is DailyTrackerCloud.CloudAuthException -> {
                    cloudAccountId = null
                    syncPhase = SyncPhase.SIGNED_OUT
                    cloudSyncStatus = error.message ?: "Please sign in again."
                }
                // Offline or server busy: stay signed in and try again later.
                is DailyTrackerCloud.CloudTransientException -> {
                    syncPhase = SyncPhase.OFFLINE
                    cloudSyncStatus = error.message ?: "No connection."
                }
                else -> {
                    syncPhase = SyncPhase.ERROR
                    cloudSyncStatus = friendlySyncError(error)
                }
            }
        }
    }

    fun startCloudSync(account: DailyTrackerCloud.Account) {
        if (!account.verified) {
            cloudAccountId = null
            syncPhase = SyncPhase.SIGNED_OUT
            cloudSyncStatus = "Verify your email before cloud sync can start."
            return
        }
        cloudAccountId = account.id
        syncPhase = SyncPhase.SYNCING
        cloudSyncStatus = "Syncing your history..."
        repository.syncCloud(cloud, account.id, ::handleCloudSyncResult)
    }

    fun retrySync() {
        val id = cloudAccountId ?: return
        syncPhase = SyncPhase.SYNCING
        repository.syncCloud(cloud, id, ::handleCloudSyncResult)
    }

    DisposableEffect(syncWorker) {
        onDispose { syncWorker.shutdownNow() }
    }

    // Each successful sync shows its message for about 3 seconds.
    LaunchedEffect(lastSyncedAt) {
        // Only for a sync that just happened, not when the value is restored after a rotation.
        if (lastSyncedAt > 0L && System.currentTimeMillis() - lastSyncedAt < 5_000L) {
            showSyncToast = true
            delay(3_000)
            showSyncToast = false
        }
    }

    // Keeps "Synced 2 min ago" fresh.
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            clockNow = System.currentTimeMillis()
        }
    }

    // Coming back to the app after being offline: try again once.
    LaunchedEffect(startCount) {
        clockNow = System.currentTimeMillis()
        if (startCount > 1 && cloudAccountId != null &&
            (syncPhase == SyncPhase.OFFLINE || syncPhase == SyncPhase.ERROR)
        ) retrySync()
    }

    // A link is only read here. Nothing changes until the user confirms it in the dialog below.
    LaunchedEffect(pendingAuthUri) {
        val callbackUri = pendingAuthUri ?: return@LaunchedEffect
        syncWorker.execute {
            val result = runCatching { cloud.parseAuthCallback(callbackUri) }
            mainHandler.post {
                result.onSuccess { pendingLink = it }
                    .onFailure {
                        showAccount = true
                        syncPhase = SyncPhase.ERROR
                        cloudSyncStatus = "Authentication link failed: " + (it.message ?: "Invalid link.")
                    }
                onAuthUriHandled()
            }
        }
    }

    LaunchedEffect(cloud) {
        syncWorker.execute {
            val result = runCatching { cloud.currentAccount() }
            mainHandler.post {
                result.onSuccess { account ->
                    if (account != null && !passwordRecoveryPending) startCloudSync(account)
                }
                result.onFailure {
                    cloudSyncStatus = it.message ?: "Could not restore the cloud session."
                }
            }
        }
    }

    pendingLink?.let { link ->
        val replacing = cloudAccountId != null
        AlertDialog(
            onDismissRequest = { pendingLink = null },
            title = {
                Text(
                    when (link.type) {
                        "recovery" -> "Reset your password?"
                        else -> "Sign in with this link?"
                    }
                )
            },
            text = {
                Text(
                    buildString {
                        append("This link is for ")
                        append(link.email.ifBlank { "an account" })
                        append(". Only continue if you asked for it from DailyTracker.")
                        if (replacing) append(" You are already signed in; continuing switches accounts.")
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val accepted = link
                    pendingLink = null
                    syncPhase = SyncPhase.SYNCING
                    syncWorker.execute {
                        val result = runCatching { cloud.completeAuthCallback(accepted) }
                        mainHandler.post {
                            showAccount = true
                            result.onSuccess { callback ->
                                if (callback.type == "recovery") {
                                    passwordRecoveryPending = true
                                    syncPhase = SyncPhase.SIGNED_OUT
                                    cloudSyncStatus = "Choose a new password to finish account recovery."
                                } else {
                                    passwordRecoveryPending = false
                                    startCloudSync(callback.account)
                                }
                            }.onFailure {
                                syncPhase = SyncPhase.ERROR
                                cloudSyncStatus = "Authentication link failed: " + (it.message ?: "Invalid link.")
                            }
                        }
                    }
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { pendingLink = null }) { Text("Cancel") } }
        )
    }

    BackHandler(enabled = showReminders || showAccount) {
        if (showReminders) showReminders = false else showAccount = false
    }

    // The main UI is composed once and stays composed while Reminders is open; only its zoom and
    // fade change. Swapping it out with AnimatedContent disposes the whole Progress screen
    // (calendar, month strip, database-backed state, nav bar entrance) and rebuilds it from
    // scratch inside the zoom-out transition, which is what made returning to Progress stutter.
    // Progress state (selected day, month) is also kept now instead of resetting to today.
    // The animated values are only read inside graphicsLayer, so no recomposition per frame.
    val mainScale by animateFloatAsState(
        targetValue = if (showReminders) 1.02f else 1f,
        animationSpec = tween(260, easing = FastOutSlowInEasing),
        label = "main-zoom"
    )
    val mainAlpha by animateFloatAsState(
        targetValue = if (showReminders) 0f else 1f,
        animationSpec = tween(180),
        label = "main-fade"
    )

    // 0f = Progress fully shown, 1f = Timer fully shown. Only read inside graphicsLayer blocks,
    // so the slide runs without recomposing either screen.
    val timerFraction by animateFloatAsState(
        targetValue = if (destination == TrackerDestination.TIMER) 1f else 0f,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "screen-slide"
    )

    Box(Modifier.fillMaxSize().background(colors.background)) {
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer {
                    scaleX = mainScale
                    scaleY = mainScale
                    alpha = mainAlpha
                }
                // While Reminders covers it, keep the hidden UI out of accessibility.
                .then(if (showReminders) Modifier.clearAndSetSemantics { } else Modifier)
        ) {
            // Pre-composes the Timer screen off-screen so the very first Progress -> Timer slide is
            // smooth. After the Timer has been opened once this is no longer needed.
            TimerScreenWarmUp(
                enabled = destination == TrackerDestination.PROGRESS && !timerVisited,
                modifier = Modifier.fillMaxSize().statusBarsPadding()
            )
            // Both screens stay composed. Previously AnimatedContent destroyed Progress when
            // leaving it and rebuilt it (calendar, 30-capsule strip, DB loads, entrance animations)
            // inside the Timer -> Progress slide, which caused the stutter.
            Box(Modifier.fillMaxSize().statusBarsPadding()) {
                Box(
                    Modifier.fillMaxSize()
                        .graphicsLayer {
                            val f = timerFraction
                            translationX = if (f >= 1f) -size.width else -size.width / 3f * f
                            alpha = (1f - f / 0.6f).coerceIn(0f, 1f)
                        }
                        .then(
                            if (destination != TrackerDestination.PROGRESS) Modifier.clearAndSetSemantics { }
                            else Modifier
                        )
                ) {
                    stateHolder.SaveableStateProvider(TrackerDestination.PROGRESS) {
                        DailyProgressScreen(
                            repository = repository,
                            today = today,
                            cloud = cloud,
                            cloudAccountId = cloudAccountId,
                            cloudDataRevision = cloudDataRevision,
                            onCloudSyncResult = { result ->
                                handleCloudSyncResult(result)
                            }
                        )
                    }
                }
                if (timerVisited) {
                    Box(
                        Modifier.fillMaxSize()
                            .graphicsLayer {
                                val f = timerFraction
                                translationX = if (f <= 0f) size.width else size.width * (1f - f)
                                alpha = (f / 0.6f).coerceIn(0f, 1f)
                            }
                            .then(
                                if (destination != TrackerDestination.TIMER) Modifier.clearAndSetSemantics { }
                                else Modifier
                            )
                    ) {
                        stateHolder.SaveableStateProvider(TrackerDestination.TIMER) {
                            TimerScreen(active = destination == TrackerDestination.TIMER)
                        }
                    }
                }
            }
            val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(150.dp + navInset)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, colors.background.copy(alpha = 0.88f), colors.background)
                        )
                    )
            )
            FloatingNavigationBar(
                selected = destination,
                onSelect = {
                    // Both screens stay composed, so drop focus (and the keyboard) explicitly.
                    focusManager.clearFocus()
                    if (it == TrackerDestination.TIMER) timerVisited = true
                    destination = it
                },
                modifier = Modifier.align(Alignment.BottomCenter)
                    .navigationBarsPadding().padding(bottom = 10.dp)
            )
            // A success message appears at the top centre for a few seconds and fades away. A
            // failure ("Offline", "Sync failed") stays until it is fixed or tapped, because it
            // needs an action.
            AnimatedVisibility(
                visible = syncPhase == SyncPhase.OFFLINE || syncPhase == SyncPhase.ERROR ||
                    (syncPhase == SyncPhase.SYNCED && showSyncToast),
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding()
                    .padding(top = 64.dp),
                enter = fadeIn(tween(220)) + slideInVertically(tween(260, easing = FastOutSlowInEasing)) { -it / 2 },
                exit = fadeOut(tween(400)) + slideOutVertically(tween(300, easing = FastOutSlowInEasing)) { -it / 2 }
            ) {
                SyncStatusPill(
                    phase = syncPhase,
                    lastSyncedAt = lastSyncedAt,
                    now = clockNow,
                    onClick = {
                        focusManager.clearFocus()
                        if (syncPhase == SyncPhase.OFFLINE || syncPhase == SyncPhase.ERROR) retrySync()
                        else showAccount = true
                    }
                )
            }
            Row(
                Modifier.align(Alignment.TopEnd).statusBarsPadding()
                    .padding(top = 12.dp, end = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AccountEntryButton(
                    onClick = {
                        focusManager.clearFocus()
                        showAccount = true
                    },
                    signedIn = cloudAccountId != null
                )
                ReminderEntryButton(
                    onClick = {
                        // The UI underneath stays composed, so drop focus (and the keyboard) explicitly.
                        focusManager.clearFocus()
                        showReminders = true
                    }
                )
            }
        }

        if (showAccount) {
            CloudAccountDialog(
                cloud = cloud,
                syncStatus = cloudSyncStatus,
                passwordRecoveryPending = passwordRecoveryPending,
                onSignedIn = { account ->
                    if (!passwordRecoveryPending) startCloudSync(account)
                },
                onPasswordRecoveryComplete = { account ->
                    passwordRecoveryPending = false
                    startCloudSync(account)
                    cloudSyncStatus = "Password updated."
                },
                onSignedOut = {
                    cloudAccountId = null
                    syncPhase = SyncPhase.SIGNED_OUT
                    passwordRecoveryPending = false
                    cloudSyncStatus = "Signed out. Local history remains on this device."
                },
                onDismiss = { showAccount = false }
            )
        }

        AnimatedVisibility(
            visible = showReminders,
            enter = scaleIn(initialScale = 0.94f, animationSpec = tween(260, easing = FastOutSlowInEasing)) +
                fadeIn(tween(180)),
            exit = scaleOut(targetScale = 0.94f, animationSpec = tween(260, easing = FastOutSlowInEasing)) +
                fadeOut(tween(180))
        ) {
            ReminderScreen(onBack = { showReminders = false })
        }

        AnimatedVisibility(
            visible = showSetup,
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(200))
        ) {
            FirstRunSetupScreen(onFinished = {
                SetupPrefs.markDone(appContext)
                showSetup = false
            })
        }

        // Shown while the countdown alert is ringing, whichever screen is open.
        AnimatedVisibility(
            visible = TimerAlarmPlayer.ringing,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 68.dp),
            enter = slideInVertically(tween(320, easing = FastOutSlowInEasing)) { -it } + fadeIn(tween(200)),
            exit = slideOutVertically(tween(240, easing = FastOutSlowInEasing)) { -it } + fadeOut(tween(160))
        ) {
            TimerRingingBanner()
        }
    }
}

@Composable
private fun TimerRingingBanner() {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val source = rememberPressSource()
    val pulse by rememberInfiniteTransition(label = "ring-pulse").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "ring-pulse-value"
    )
    Row(
        Modifier.pressScale(source, 0.96f)
            .clip(CircleShape)
            .background(Brush.horizontalGradient(listOf(Color(0xFF222E25), Color(0xFF161E18))))
            .border(1.dp, scheme.primary.copy(alpha = 0.55f), CircleShape)
            .hapticClickable(source, haptic = { heavy() }) {
                TimerAlarmPlayer.stop(context)
                TimerAlerts.dismissNotification(context)
            }
            .padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
            .semantics { contentDescription = "Time is up. Tap to stop the alert."; role = Role.Button },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(9.dp).graphicsLayer { alpha = pulse }
                .clip(CircleShape).background(scheme.primary)
        )
        Spacer(Modifier.width(10.dp))
        Text("Time is up", color = scheme.onBackground, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(14.dp))
        Box(
            Modifier.clip(CircleShape).background(scheme.primary)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text("Stop", color = scheme.onPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun AccountEntryButton(onClick: () -> Unit, signedIn: Boolean, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val tint = if (signedIn) scheme.primary else scheme.onSurface
    val source = rememberPressSource()
    Box(
        modifier.pressScale(source).size(44.dp).clip(CircleShape)
            .background(Color.White.copy(alpha = 0.045f))
            .border(1.dp, if (signedIn) scheme.primary.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.09f), CircleShape)
            .hapticClickable(source, onClick = onClick)
            .semantics {
                contentDescription = if (signedIn) "Account, signed in" else "Account, sign in"
                role = Role.Button
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(21.dp)) {
            val stroke = Stroke(width = 1.7.dp.toPx(), cap = StrokeCap.Round)
            // Head
            drawCircle(tint, size.width * 0.20f, Offset(size.width / 2f, size.height * 0.32f), style = stroke)
            // Shoulders
            drawArc(
                color = tint,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(size.width * 0.14f, size.height * 0.60f),
                size = Size(size.width * 0.72f, size.height * 0.60f),
                style = stroke
            )
        }
    }
}

@Composable
private fun ReminderEntryButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tint = MaterialTheme.colorScheme.onSurface
    val source = rememberPressSource()
    Box(
        modifier.pressScale(source).size(44.dp).clip(CircleShape)
            .background(Color.White.copy(alpha = 0.045f))
            .border(1.dp, Color.White.copy(alpha = 0.09f), CircleShape)
            .hapticClickable(source, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(21.dp)) {
            val stroke = Stroke(width = 1.7.dp.toPx(), cap = StrokeCap.Round)
            drawArc(
                color = tint,
                startAngle = 205f,
                sweepAngle = 130f,
                useCenter = false,
                topLeft = Offset(size.width * 0.18f, size.height * 0.12f),
                size = Size(size.width * 0.64f, size.height * 0.72f),
                style = stroke
            )
            drawLine(tint, Offset(size.width * 0.18f, size.height * 0.68f),
                Offset(size.width * 0.82f, size.height * 0.68f), stroke.width, cap = StrokeCap.Round)
            drawLine(tint, Offset(size.width * 0.40f, size.height * 0.86f),
                Offset(size.width * 0.60f, size.height * 0.86f), stroke.width, cap = StrokeCap.Round)
            drawCircle(tint, 1.2.dp.toPx(), Offset(size.width / 2f, size.height * 0.92f))
        }
    }
}

@Composable
private fun FloatingNavigationBar(
    selected: TrackerDestination,
    onSelect: (TrackerDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    val selectedIndex = if (selected == TrackerDestination.PROGRESS) 0 else 1
    val itemWidth = 125.dp // (260dp bar - 2 * 5dp padding) / 2 items

    // Entrance: the bar rises and fades in once at launch.
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(Unit) { entrance.animateTo(1f, tween(650, easing = FastOutSlowInEasing)) }

    // Indicator position in item units (0 = Progress, 1 = Timer). The spring overshoots slightly,
    // and the indicator stretches while it travels, which gives the liquid feel.
    val position = remember { Animatable(selectedIndex.toFloat()) }
    LaunchedEffect(selectedIndex) {
        position.animateTo(selectedIndex.toFloat(), spring(dampingRatio = 0.62f, stiffness = 320f))
    }

    Box(
        modifier.width(260.dp).height(62.dp)
            .graphicsLayer {
                alpha = entrance.value
                translationY = (1f - entrance.value) * 36.dp.toPx()
            }
    ) {
        Box(
            Modifier.fillMaxSize()
                .clip(CircleShape)
                .background(
                    Brush.verticalGradient(listOf(Color(0xF2222E25), Color(0xF2161E18)))
                )
                .drawWithContent {
                    drawContent()
                    val stroke = 1.5.dp.toPx()
                    val corner = CornerRadius(size.height / 2f)
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.10f),
                        cornerRadius = corner,
                        style = Stroke(stroke)
                    )
                }
                .padding(5.dp)
        ) {
            Box(
                Modifier.width(itemWidth).fillMaxHeight()
                    .graphicsLayer {
                        translationX = itemWidth.toPx() * position.value
                        scaleX = 1f + 0.28f * sin(PI.toFloat() * position.value.coerceIn(0f, 1f))
                    }
                    .clip(CircleShape)
                    .background(
                        Brush.verticalGradient(
                            listOf(scheme.primary.copy(alpha = 0.30f), scheme.primary.copy(alpha = 0.16f))
                        )
                    )
                    .border(1.dp, scheme.primary.copy(alpha = 0.35f), CircleShape)
            )
            Row(Modifier.fillMaxSize()) {
                NavigationItem("Progress", TrackerDestination.PROGRESS,
                    selected == TrackerDestination.PROGRESS,
                    {
                        if (selected != TrackerDestination.PROGRESS) {
                            haptics.select()
                        }
                        onSelect(TrackerDestination.PROGRESS)
                    }, Modifier.weight(1f))
                NavigationItem("Timer", TrackerDestination.TIMER,
                    selected == TrackerDestination.TIMER,
                    {
                        if (selected != TrackerDestination.TIMER) {
                            haptics.select()
                        }
                        onSelect(TrackerDestination.TIMER)
                    }, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun NavigationItem(
    label: String,
    destination: TrackerDestination,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val foreground by animateColorAsState(
        if (isSelected) colors.primary else colors.onSurfaceVariant,
        tween(220), label = "nav-content"
    )
    val iconScale by animateFloatAsState(
        targetValue = if (isSelected) 1.14f else 1f,
        animationSpec = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow),
        label = "nav-icon-scale"
    )
    Row(
        modifier.fillMaxHeight().clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = label
                selected = isSelected
                role = Role.Tab
            }.padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        NavigationGlyph(
            destination, foreground,
            Modifier.size(20.dp).graphicsLayer {
                scaleX = iconScale
                scaleY = iconScale
            }
        )
        Spacer(Modifier.width(7.dp))
        Text(label, color = foreground, fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium)
    }
}

@Composable
private fun NavigationGlyph(destination: TrackerDestination, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = 1.8.dp.toPx())
        if (destination == TrackerDestination.PROGRESS) {
            val gap = size.width * 0.13f
            val cell = (size.width - gap) / 2f
            val radius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
            listOf(
                androidx.compose.ui.geometry.Offset(0f, 0f),
                androidx.compose.ui.geometry.Offset(cell + gap, 0f),
                androidx.compose.ui.geometry.Offset(0f, cell + gap),
                androidx.compose.ui.geometry.Offset(cell + gap, cell + gap)
            ).forEach { point ->
                drawRoundRect(tint, point, androidx.compose.ui.geometry.Size(cell, cell), radius, style = stroke)
            }
        } else {
            val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
            drawCircle(tint, size.minDimension * 0.39f, center, style = stroke)
            drawLine(tint, center,
                androidx.compose.ui.geometry.Offset(center.x, center.y - size.height * 0.23f),
                stroke.width, cap = StrokeCap.Round)
            drawLine(tint, center,
                androidx.compose.ui.geometry.Offset(center.x + size.width * 0.18f, center.y + size.height * 0.10f),
                stroke.width, cap = StrokeCap.Round)
        }
    }
}
@Composable
private fun SyncStatusPill(
    phase: SyncPhase,
    lastSyncedAt: Long,
    now: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberPressSource()
    val pulse by rememberInfiniteTransition(label = "sync-pulse").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
        label = "sync-pulse-value"
    )
    val (label, dot) = when (phase) {
        SyncPhase.SYNCING -> "Syncing\u2026" to scheme.primary
        SyncPhase.SYNCED -> syncAgeText(lastSyncedAt, now) to scheme.primary
        SyncPhase.OFFLINE -> "Offline \u00b7 tap to retry" to scheme.onSurfaceVariant
        SyncPhase.ERROR -> "Sync failed \u00b7 tap to retry" to scheme.error
        SyncPhase.SIGNED_OUT -> "" to scheme.onSurfaceVariant
    }
    Row(
        modifier.height(44.dp).pressScale(source).clip(CircleShape)
            .background(Color.White.copy(alpha = 0.045f))
            .border(1.dp, Color.White.copy(alpha = 0.09f), CircleShape)
            .hapticClickable(source, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(8.dp)
                .graphicsLayer { alpha = if (phase == SyncPhase.SYNCING) pulse else 1f }
                .clip(CircleShape).background(dot)
        )
        Spacer(Modifier.width(8.dp))
        Text(label, color = scheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}
