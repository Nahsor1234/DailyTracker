package com.nahsor1234.dailytracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.ZoneId
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text

private enum class TrackerDestination {
    PROGRESS,
    TIMER
}

/**
 * Android entry point. Both destinations remain composed while switching so
 * screen-local state is retained when the user moves between them.
 */
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
        window.statusBarColor = android.graphics.Color.rgb(17, 21, 16)
        window.navigationBarColor = android.graphics.Color.rgb(17, 21, 16)
        window.decorView.systemUiVisibility = 0

        setContent {
            DailyTrackerTheme {
                DailyTrackerApp(repository = repository, today = today)
            }
        }
    }
}

@Composable
private fun DailyTrackerApp(repository: DailyEntryRepository, today: LocalDate) {
    var destination by rememberSaveable { mutableStateOf(TrackerDestination.PROGRESS) }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = if (destination == TrackerDestination.PROGRESS) {
                Modifier.fillMaxSize()
            } else {
                Modifier.size(0.dp)
            }
        ) {
            DailyProgressScreen(
                repository = repository,
                today = today,
                modifier = Modifier.padding(bottom = 108.dp)
            )
        }

        Box(
            modifier = if (destination == TrackerDestination.TIMER) {
                Modifier.fillMaxSize()
            } else {
                Modifier.size(0.dp)
            }
        ) {
            TimerScreen(modifier = Modifier.padding(bottom = 108.dp))
        }

        FloatingNavigationBar(
            selected = destination,
            onSelect = { destination = it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 10.dp)
        )
    }
}

@Composable
private fun FloatingNavigationBar(
    selected: TrackerDestination,
    onSelect: (TrackerDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme

    Surface(
        modifier = modifier
            .width(264.dp)
            .height(64.dp),
        shape = CircleShape,
        color = colors.surfaceVariant,
        shadowElevation = 12.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(6.dp)
        ) {
            NavigationItem(
                label = "Progress",
                icon = "▦",
                isSelected = selected == TrackerDestination.PROGRESS,
                onClick = { onSelect(TrackerDestination.PROGRESS) },
                modifier = Modifier.weight(1f)
            )
            NavigationItem(
                label = "Timer",
                icon = "◷",
                isSelected = selected == TrackerDestination.TIMER,
                onClick = { onSelect(TrackerDestination.TIMER) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun NavigationItem(
    label: String,
    icon: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val containerColor by animateColorAsState(
        targetValue = if (isSelected) colors.primaryContainer else Color.Transparent,
        animationSpec = tween(durationMillis = 180),
        label = "navigation-item-container"
    )
    val foregroundColor by animateColorAsState(
        targetValue = if (isSelected) colors.onPrimaryContainer else colors.onSurfaceVariant,
        animationSpec = tween(durationMillis = 180),
        label = "navigation-item-content"
    )

    Row(
        modifier = modifier
            .fillMaxHeight()
            .clip(CircleShape)
            .background(containerColor)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = label
                selected = isSelected
                role = Role.Tab
            }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            text = icon,
            color = foregroundColor,
            fontSize = 19.sp
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = label,
            color = foregroundColor,
            fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
        )
    }
}
