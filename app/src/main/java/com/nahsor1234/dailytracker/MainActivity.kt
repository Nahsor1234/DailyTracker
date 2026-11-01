package com.nahsor1234.dailytracker

import android.os.Bundle
import android.content.Intent
import android.net.Uri
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
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
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

class MainActivity : ComponentActivity() {
    private var today by mutableStateOf(LocalDate.now(ZoneId.systemDefault()))
    private var authCallbackUri by mutableStateOf<Uri?>(null)
    private val repository by lazy(LazyThreadSafetyMode.NONE) {
        DailyEntryRepository(applicationContext)
    }
    private val cloud by lazy(LazyThreadSafetyMode.NONE) {
        DailyTrackerCloud(applicationContext)
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
        ReminderNotificationChannels.create(this)
        ReminderScheduler.rescheduleAll(this)
        authCallbackUri = intent?.data
        setContent {
            DailyTrackerTheme {
                DailyTrackerApp(
                    repository = repository,
                    today = today,
                    cloud = cloud,
                    pendingAuthUri = authCallbackUri,
                    onAuthUriHandled = { authCallbackUri = null }
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
    pendingAuthUri: Uri?,
    onAuthUriHandled: () -> Unit
) {
    var destination by rememberSaveable { mutableStateOf(TrackerDestination.PROGRESS) }
    val stateHolder = rememberSaveableStateHolder()
    val colors = MaterialTheme.colorScheme
    var showReminders by rememberSaveable { mutableStateOf(false) }
    var showAccount by rememberSaveable { mutableStateOf(false) }
    var cloudAccountId by remember { mutableStateOf<String?>(null) }
    var cloudSyncStatus by rememberSaveable { mutableStateOf("") }
    var cloudDataRevision by remember { mutableIntStateOf(0) }
    var syncInProgress by remember { mutableStateOf(false) }
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
            cloudSyncStatus = "Synced " + it + " daily records."
        }.onFailure {
            cloudAccountId = null
            cloudSyncStatus = "Sync failed: " + (it.message ?: "Unknown error.")
        }
    }

    fun startCloudSync(account: DailyTrackerCloud.Account) {
        if (!account.verified) {
            cloudAccountId = null
            cloudSyncStatus = "Verify your email before cloud sync can start."
            return
        }
        cloudAccountId = account.id
        if (syncInProgress) return
        syncInProgress = true
        cloudSyncStatus = "Syncing daily history..."
        repository.syncCloud(cloud, account.id) { result ->
            syncInProgress = false
            handleCloudSyncResult(result)
        }
    }

    DisposableEffect(syncWorker) {
        onDispose { syncWorker.shutdownNow() }
    }

    LaunchedEffect(pendingAuthUri) {
        val callbackUri = pendingAuthUri ?: return@LaunchedEffect
        syncWorker.execute {
            val result = runCatching { cloud.acceptAuthCallback(callbackUri) }
            mainHandler.post {
                result.onSuccess { callback ->
                    showAccount = true
                    if (callback.type == "recovery") {
                        passwordRecoveryPending = true
                        cloudSyncStatus = "Choose a new password to finish account recovery."
                    } else {
                        passwordRecoveryPending = false
                        startCloudSync(callback.account)
                    }
                }.onFailure {
                    showAccount = true
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
                result.onSuccess {
                    account -> if (account != null && !passwordRecoveryPending) startCloudSync(account)
                }
                result.onFailure {
                    cloudSyncStatus = it.message ?: "Could not restore the cloud session."
                }
            }
        }
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
            // Pre-composes the Timer screen off-screen so the first Progress -> Timer slide is smooth.
            TimerScreenWarmUp(
                enabled = destination == TrackerDestination.PROGRESS,
                modifier = Modifier.fillMaxSize().statusBarsPadding()
            )
            AnimatedContent(
                targetState = destination,
                modifier = Modifier.fillMaxSize().statusBarsPadding(),
                transitionSpec = {
                    if (targetState == TrackerDestination.TIMER) {
                        (slideInHorizontally(tween(300)) { it } + fadeIn(tween(180))) togetherWith
                            (slideOutHorizontally(tween(300)) { -it / 3 } + fadeOut(tween(180)))
                    } else {
                        (slideInHorizontally(tween(300)) { -it } + fadeIn(tween(180))) togetherWith
                            (slideOutHorizontally(tween(300)) { it / 3 } + fadeOut(tween(180)))
                    }
                },
                label = "screen-slide"
            ) { target ->
                stateHolder.SaveableStateProvider(target) {
                    when (target) {
                        TrackerDestination.PROGRESS -> DailyProgressScreen(
                            repository = repository,
                            today = today,
                            cloud = cloud,
                            cloudAccountId = cloudAccountId,
                            cloudDataRevision = cloudDataRevision,
                            onCloudSyncResult = ::handleCloudSyncResult
                        )
                        TrackerDestination.TIMER -> TimerScreen()
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
                onSelect = { destination = it },
                modifier = Modifier.align(Alignment.BottomCenter)
                    .navigationBarsPadding().padding(bottom = 10.dp)
            )
            androidx.compose.material3.TextButton(
                onClick = { showAccount = true },
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(top = 8.dp, start = 12.dp)
            ) { Text("Account") }
            ReminderEntryButton(
                onClick = {
                    // The UI underneath stays composed, so drop focus (and the keyboard) explicitly.
                    focusManager.clearFocus()
                    showReminders = true
                },
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding()
                    .padding(top = 12.dp, end = 16.dp)
            )
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
    }
}

@Composable
private fun ReminderEntryButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tint = MaterialTheme.colorScheme.onSurface
    Box(
        modifier.size(44.dp).clip(CircleShape)
            .background(Color.White.copy(alpha = 0.045f))
            .border(1.dp, Color.White.copy(alpha = 0.09f), CircleShape)
            .clickable(onClick = onClick),
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
    val haptics = LocalHapticFeedback.current
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
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        onSelect(TrackerDestination.PROGRESS)
                    }, Modifier.weight(1f))
                NavigationItem("Timer", TrackerDestination.TIMER,
                    selected == TrackerDestination.TIMER,
                    {
                        if (selected != TrackerDestination.TIMER) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
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
