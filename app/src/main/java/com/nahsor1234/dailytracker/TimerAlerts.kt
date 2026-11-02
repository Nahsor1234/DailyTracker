package com.nahsor1234.dailytracker

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** User choices for the countdown alert, stored on this device. */
internal class TimerAlertSettings(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("timer_alerts", Context.MODE_PRIVATE)

    var soundEnabled: Boolean
        get() = prefs.getBoolean(KEY_SOUND, true)
        set(value) { prefs.edit().putBoolean(KEY_SOUND, value).apply() }

    var vibrationEnabled: Boolean
        get() = prefs.getBoolean(KEY_VIBRATION, true)
        set(value) { prefs.edit().putBoolean(KEY_VIBRATION, value).apply() }

    /** null = the phone's default alarm sound. */
    var ringtoneUri: String?
        get() = prefs.getString(KEY_RINGTONE, null)
        set(value) {
            prefs.edit().apply { if (value == null) remove(KEY_RINGTONE) else putString(KEY_RINGTONE, value) }.apply()
        }

    var notificationPermissionAsked: Boolean
        get() = prefs.getBoolean(KEY_ASKED, false)
        set(value) { prefs.edit().putBoolean(KEY_ASKED, value).apply() }

    fun soundUri(): Uri =
        ringtoneUri?.let(Uri::parse)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

    private companion object {
        const val KEY_SOUND = "sound"
        const val KEY_VIBRATION = "vibration"
        const val KEY_RINGTONE = "ringtone"
        const val KEY_ASKED = "notification_permission_asked"
    }
}

/** Lets the alarm receiver know whether the app is on screen (and so handles the alert itself). */
internal object TimerAlertState {
    @Volatile
    var appInForeground: Boolean = false
}

/**
 * Schedules the "countdown finished" alert with the system, so it still fires when the app is in
 * the background or has been closed, and shows it as a notification.
 */
internal object TimerAlerts {
    const val ACTION_FINISHED = "com.nahsor1234.dailytracker.action.TIMER_FINISHED"
    const val NOTIFICATION_ID = 7001
    private const val CHANNEL_PREFIX = "dailytracker_timer_"
    private val VIBRATION_PATTERN = longArrayOf(0, 600, 300, 600, 300, 900)

    /** [deadlineElapsedRealtime] uses the same clock as the countdown itself. */
    fun schedule(context: Context, deadlineElapsedRealtime: Long) {
        val app = context.applicationContext
        ensureChannel(app, TimerAlertSettings(app))
        val manager = app.getSystemService(AlarmManager::class.java)
        val pending = alarmIntent(app)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()) {
            manager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadlineElapsedRealtime, pending)
        } else {
            // No exact-alarm access: an alarm-clock alarm is still exact and needs no permission.
            val wallClock = System.currentTimeMillis() +
                (deadlineElapsedRealtime - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            manager.setAlarmClock(AlarmManager.AlarmClockInfo(wallClock, openAppIntent(app)), pending)
        }
    }

    fun cancel(context: Context) {
        val app = context.applicationContext
        app.getSystemService(AlarmManager::class.java).cancel(alarmIntent(app))
    }

    fun dismissNotification(context: Context) {
        context.applicationContext.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    fun notificationsAllowed(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 ||
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED)

    /**
     * Channel settings cannot be changed after creation, so the sound/vibration choice is part
     * of the channel id. Older timer channels are removed so the system settings list stays tidy.
     */
    private fun channelId(settings: TimerAlertSettings): String =
        CHANNEL_PREFIX +
            (if (settings.soundEnabled) "s" else "q") +
            (if (settings.vibrationEnabled) "v" else "q") +
            (if (settings.soundEnabled) settings.soundUri().toString().hashCode().toUInt().toString(16) else "")

    fun ensureChannel(context: Context, settings: TimerAlertSettings): String {
        val id = channelId(settings)
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(id) == null) {
            manager.notificationChannels
                .filter { it.id.startsWith(CHANNEL_PREFIX) && it.id != id }
                .forEach { manager.deleteNotificationChannel(it.id) }
            val channel = NotificationChannel(id, "Countdown timer", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Alert when a countdown finishes"
                if (settings.soundEnabled) {
                    setSound(
                        settings.soundUri(),
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                } else {
                    setSound(null, null)
                }
                enableVibration(settings.vibrationEnabled)
                if (settings.vibrationEnabled) vibrationPattern = VIBRATION_PATTERN
            }
            manager.createNotificationChannel(channel)
        }
        return id
    }

    fun postFinishedNotification(context: Context) {
        val app = context.applicationContext
        if (!notificationsAllowed(app)) return
        val channel = ensureChannel(app, TimerAlertSettings(app))
        val notification = Notification.Builder(app, channel)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Time is up")
            .setContentText("Your countdown has finished.")
            .setCategory(Notification.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setTimeoutAfter(5 * 60_000L)
            .setContentIntent(openAppIntent(app))
            .build()
        app.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun alarmIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            NOTIFICATION_ID,
            Intent(context, TimerAlarmReceiver::class.java).setAction(ACTION_FINISHED),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun openAppIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    internal val vibrationWaveform: LongArray get() = VIBRATION_PATTERN
}

/** Fires at the countdown deadline. If the app is on screen it handles the alert itself. */
internal class TimerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TimerAlerts.ACTION_FINISHED) return
        if (TimerAlertState.appInForeground) return
        TimerAlerts.postFinishedNotification(context)
    }
}

/**
 * In-app alert: loops the chosen sound and vibrates until stopped, the user dismisses it, or
 * [AUTO_STOP_MS] passes. [ringing] is Compose state so the UI can offer a Stop button anywhere.
 */
internal object TimerAlarmPlayer {
    private const val AUTO_STOP_MS = 30_000L
    private val handler = Handler(Looper.getMainLooper())
    private var ringtone: Ringtone? = null
    private var appContext: Context? = null
    private val autoStop = Runnable { appContext?.let(::stop) }

    var ringing by mutableStateOf(false)
        private set

    fun start(context: Context, settings: TimerAlertSettings, durationMs: Long = AUTO_STOP_MS) {
        val app = context.applicationContext
        stop(app)
        appContext = app
        if (settings.soundEnabled) {
            runCatching {
                val tone = RingtoneManager.getRingtone(app, settings.soundUri()) ?: return@runCatching
                tone.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) tone.isLooping = true
                tone.play()
                ringtone = tone
            }
        }
        if (settings.vibrationEnabled) {
            runCatching {
                app.getSystemService(Vibrator::class.java)
                    ?.vibrate(VibrationEffect.createWaveform(TimerAlerts.vibrationWaveform, 0))
            }
        }
        ringing = true
        handler.postDelayed(autoStop, durationMs)
    }

    fun stop(context: Context) {
        handler.removeCallbacks(autoStop)
        runCatching { ringtone?.stop() }
        ringtone = null
        runCatching { context.applicationContext.getSystemService(Vibrator::class.java)?.cancel() }
        ringing = false
    }
}