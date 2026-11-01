package com.nahsor1234.dailytracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text

private enum class TrackerDestination { PROGRESS, TIMER }

class MainActivity : ComponentActivity() {
    private var today by mutableStateOf(LocalDate.now(ZoneId.systemDefault()))
    private val repository by lazy(LazyThreadSafetyMode.NONE) {
        DailyEntryRepository(applicationContext)
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
        setContent {
            DailyTrackerTheme { DailyTrackerApp(repository, today) }
        }
    }
}

@Composable
private fun DailyTrackerApp(repository: DailyEntryRepository, today: LocalDate) {
    var destination by rememberSaveable { mutableStateOf(TrackerDestination.PROGRESS) }
    val stateHolder = rememberSaveableStateHolder()
    val colors = MaterialTheme.colorScheme

    Box(Modifier.fillMaxSize().background(colors.background)) {
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
                        repository, today, modifier = Modifier.padding(bottom = 108.dp)
                    )
                    TrackerDestination.TIMER -> TimerScreen(
                        modifier = Modifier.padding(bottom = 108.dp)
                    )
                }
            }
        }
        FloatingNavigationBar(
            selected = destination,
            onSelect = { destination = it },
            modifier = Modifier.align(Alignment.BottomCenter)
                .navigationBarsPadding().padding(bottom = 10.dp)
        )
    }
}

@Composable
private fun FloatingNavigationBar(
    selected: TrackerDestination,
    onSelect: (TrackerDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    // No platform shadow (shadowElevation): on a near-black background it renders as a dark halo
    // that reads as a rectangle behind the pill. A translucent fill + hairline border floats cleanly.
    BoxWithConstraints(
        modifier.width(248.dp).height(60.dp)
            .clip(CircleShape)
            .background(Color(0xE61A241D))
            .border(1.dp, Color.White.copy(alpha = 0.10f), CircleShape)
            .padding(5.dp)
    ) {
        val selectedIndex = if (selected == TrackerDestination.PROGRESS) 0 else 1
        val itemWidth = maxWidth / 2
        val pillOffset by androidx.compose.animation.core.animateDpAsState(
            targetValue = itemWidth * selectedIndex,
            animationSpec = tween(320),
            label = "navigation-pill"
        )
        Box(
            Modifier.offset(x = pillOffset).width(itemWidth).fillMaxHeight()
                .clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
        )
        Row(Modifier.fillMaxSize()) {
            NavigationItem("Progress", TrackerDestination.PROGRESS,
                selected == TrackerDestination.PROGRESS,
                { onSelect(TrackerDestination.PROGRESS) }, Modifier.weight(1f))
            NavigationItem("Timer", TrackerDestination.TIMER,
                selected == TrackerDestination.TIMER,
                { onSelect(TrackerDestination.TIMER) }, Modifier.weight(1f))
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
        tween(200), label = "nav-content"
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
        NavigationGlyph(destination, foreground, Modifier.size(20.dp))
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