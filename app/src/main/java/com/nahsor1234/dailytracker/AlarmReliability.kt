package com.nahsor1234.dailytracker

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date

/**
 * A small record of when each alarm was expected and when it really fired. It is what lets the
 * app (1) tell the user how late the last alarm was, and (2) notice an alarm that the phone never
 * delivered, instead of silently dropping it the next time the app opens.
 */
internal object AlarmLog {
    private const val PREFS = "alarm_log"
    private const val KEY_LAST_FIRED = "last_fired_at"
    private const val KEY_LAST_LATE = "last_late_ms"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(source: String, id: Long) = "expected:$source:$id"

    /** When this alarm is next due, in wall-clock millis; 0 if unknown. */
    fun expected(context: Context, source: String, id: Long): Long =
        prefs(context).getLong(key(source, id), 0L)

    fun scheduled(context: Context, source: String, id: Long, triggerAtMillis: Long) {
        prefs(context).edit().putLong(key(source, id), triggerAtMillis).commit()
    }

    fun clear(context: Context, source: String, id: Long) {
        prefs(context).edit().remove(key(source, id)).commit()
    }

    /** Records a delivery. [expectedAt] is what [expected] returned just before the alarm fired. */
    fun fired(context: Context, expectedAt: Long) {
        val now = System.currentTimeMillis()
        prefs(context).edit()
            .putLong(KEY_LAST_FIRED, now)
            .putLong(KEY_LAST_LATE, if (expectedAt > 0L) (now - expectedAt).coerceAtLeast(0L) else -1L)
            .commit()
    }

    fun lastFiredAt(context: Context): Long = prefs(context).getLong(KEY_LAST_FIRED, 0L)

    /** How late the last alarm was, in millis; -1 if that is not known. */
    fun lastLateMs(context: Context): Long = prefs(context).getLong(KEY_LAST_LATE, -1L)
}

private fun notificationsOk(context: Context): Boolean = TimerAlerts.notificationsAllowed(context)

private fun exactOk(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

private fun batteryOk(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

/**
 * Shows the phone settings that decide whether alarms arrive on time, and a button to fix each one
 * that is wrong. Re-checks every time the user comes back from a settings screen.
 */
@Composable
internal fun AlarmReliabilityCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(22.dp)
    var refresh by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refresh += 1
    }

    // Keyed on [refresh], so everything is re-read after returning from a system settings screen.
    val notifications = remember(refresh) { notificationsOk(context) }
    val exact = remember(refresh) { exactOk(context) }
    val battery = remember(refresh) { batteryOk(context) }
    val lastFired = remember(refresh) { AlarmLog.lastFiredAt(context) }
    val lastLate = remember(refresh) { AlarmLog.lastLateMs(context) }

    fun open(intent: Intent) {
        runCatching { launcher.launch(intent) }
    }

    fun appDetails() = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    Column(
        modifier.fillMaxWidth().clip(shape)
            .background(Color.White.copy(alpha = 0.035f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), shape)
            .padding(16.dp)
    ) {
        Text(
            "ALARM RELIABILITY", color = colors.primary, fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold, letterSpacing = 1.8.sp
        )
        Spacer(Modifier.height(8.dp))
        StatusRow(
            title = "Notifications",
            ok = notifications,
            okText = "Allowed",
            badText = "Blocked: alerts cannot be shown",
            action = "Fix"
        ) {
            open(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            )
        }
        StatusRow(
            title = "Exact alarms",
            ok = exact,
            okText = "Allowed",
            badText = "Off: Android may delay alarms",
            action = "Allow"
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
            }
        }
        StatusRow(
            title = "Battery",
            ok = battery,
            okText = "Unrestricted",
            badText = "Optimized: the phone may hold alarms back",
            action = "Allow"
        ) {
            open(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                lastFired == 0L -> "No alarm has fired yet."
                lastLate < 0L -> "Last alarm fired at " + DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(lastFired)) + "."
                lastLate < 5_000L -> "Last alarm fired at " + DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(lastFired)) + ", on time."
                else -> "Last alarm fired at " + DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(lastFired)) +
                    ", " + (lastLate / 1000L) + " s late."
            },
            color = colors.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Xiaomi, Redmi, POCO, Oppo, Realme, Vivo, OnePlus and Samsung phones also hold back apps that " +
                "are closed. Allow Autostart, set Battery to No restrictions, and lock the app in Recents.",
            color = colors.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(8.dp))
        ReliabilityPill("Open app settings") { open(appDetails()) }
    }
}

@Composable
private fun StatusRow(
    title: String,
    ok: Boolean,
    okText: String,
    badText: String,
    action: String,
    onAction: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = colors.onSurface, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (ok) okText else badText,
                color = if (ok) colors.primary else colors.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (!ok) ReliabilityPill(action, onClick = onAction)
    }
}

@Composable
private fun ReliabilityPill(text: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    val source = rememberPressSource()
    Text(
        text,
        color = colors.primary,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.pressScale(source).clip(CircleShape)
            .border(1.dp, colors.primary.copy(alpha = 0.45f), CircleShape)
            .hapticClickable(source, haptic = { click() }, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp)
    )
}
