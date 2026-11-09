package com.nahsor1234.dailytracker

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Remembers that the first-run setup has been shown, so it appears only once. */
internal object SetupPrefs {
    private const val PREFS = "first_run_setup"
    private const val KEY_DONE = "done"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isDone(context: Context): Boolean = runCatching { prefs(context).getBoolean(KEY_DONE, false) }
        .getOrDefault(true)

    fun markDone(context: Context) {
        runCatching { prefs(context).edit().putBoolean(KEY_DONE, true).apply() }
    }
}

/**
 * Shown once, on the first launch. It explains in one line why each setting matters and takes the
 * person straight to the right Android screen. Nothing is forced: "Skip for now" always works, and
 * the same settings stay available later in Reminders.
 */
@Composable
internal fun FirstRunSetupScreen(onFinished: () -> Unit) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    var refresh by remember { mutableIntStateOf(0) }

    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refresh += 1
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        refresh += 1
    }

    val notifications = remember(refresh) { notificationsOk(context) }
    val exact = remember(refresh) { exactOk(context) }
    val battery = remember(refresh) { batteryOk(context) }

    fun open(intent: Intent) {
        runCatching { settingsLauncher.launch(intent) }
    }

    // The back button skips the setup instead of closing the app behind it.
    BackHandler { onFinished() }

    Column(
        Modifier.fillMaxSize().background(scheme.background)
            .statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 28.dp)
    ) {
        Text(
            "WELCOME",
            color = scheme.primary,
            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.4.sp)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Let's set up your alerts",
            color = scheme.onBackground,
            style = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Light, letterSpacing = (-0.5).sp)
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Reminders and the countdown only ring on time if your phone allows these three things. " +
                "It takes about a minute, and you can change any of them later.",
            color = scheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(24.dp))

        SetupStep(
            number = 1,
            title = "Notifications",
            reason = "So a reminder or a finished countdown can actually show up.",
            done = notifications,
            actionLabel = "Allow"
        ) {
            if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                runCatching { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
            } else {
                open(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        SetupStep(
            number = 2,
            title = "Exact alarms",
            reason = "So alarms go off at the minute you set, not a few minutes late.",
            done = exact,
            actionLabel = "Allow"
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
            }
        }
        Spacer(Modifier.height(12.dp))
        SetupStep(
            number = 3,
            title = "Battery",
            reason = "So the phone doesn't pause the app and hold your alarms back.",
            done = battery,
            actionLabel = "Allow"
        ) {
            open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
        }

        Spacer(Modifier.height(28.dp))
        val allDone = notifications && exact && battery
        PrimaryPill(
            text = if (allDone) "All set — start" else "Continue",
            onClick = onFinished
        )
        if (!allDone) {
            Spacer(Modifier.height(10.dp))
            val skipSource = rememberPressSource()
            Text(
                "Skip for now",
                modifier = Modifier.fillMaxWidth().clip(CircleShape)
                    .hapticClickable(skipSource, haptic = { click() }, onClick = onFinished)
                    .padding(vertical = 12.dp),
                color = scheme.onSurfaceVariant,
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

@Composable
private fun SetupStep(
    number: Int,
    title: String,
    reason: String,
    done: Boolean,
    actionLabel: String,
    onAction: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(22.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(Color.White.copy(alpha = 0.04f))
            .border(1.dp, if (done) scheme.primary.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.08f), shape)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "$number. $title",
                color = scheme.onSurface,
                style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium)
            )
            Spacer(Modifier.height(4.dp))
            Text(reason, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(12.dp))
        if (done) {
            Text(
                "✔ On",
                color = scheme.primary,
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            )
        } else {
            SmallPill(actionLabel, onAction)
        }
    }
}

@Composable
private fun SmallPill(text: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberPressSource()
    Text(
        text,
        color = scheme.primary,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.pressScale(source).clip(CircleShape)
            .border(1.dp, scheme.primary.copy(alpha = 0.5f), CircleShape)
            .hapticClickable(source, haptic = { click() }, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun PrimaryPill(text: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberPressSource()
    Row(
        Modifier.fillMaxWidth().pressScale(source).clip(CircleShape)
            .background(scheme.primary)
            .hapticClickable(source, haptic = { confirm() }, onClick = onClick)
            .padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        Text(text, color = scheme.onPrimary, style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold))
    }
}
