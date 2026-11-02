package com.nahsor1234.dailytracker

import android.app.AlarmManager
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
import android.provider.Settings
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
 * Schedules the "countdown finished" alert with the system, so it still rings when the app is in
 * the background or has been closed. The ringing itself is done by [AlarmRingService].
 *
 * The countdown runs on the elapsed-realtime clock, but alarms are set on the wall clock, and the
 * elapsed clock restarts from zero on reboot. So the deadline is also kept in wall-clock terms,
 * which is what lets a countdown survive a restart.
 */
internal object TimerAlerts {
    const val ACTION_FINISHED = "com.nahsor1234.dailytracker.action.TIMER_FINISHED"

    /** Id earlier versions used for the "finished" notification; still cleared when the app opens. */
    const val NOTIFICATION_ID = 7001
    private const val ALARM_REQUEST_CODE = 7001
    private const val ALARM_PREFS = "timer_alarm"
    private const val KEY_WALL = "wall_deadline"
    private const val KEY_ELAPSED = "elapsed_deadline"
    private const val KEY_BOOT = "boot_count"
    private val VIBRATION_PATTERN = longArrayOf(0, 600, 300, 600, 300, 900)

    /** [deadlineElapsedRealtime] uses the same clock as the countdown itself. */
    fun schedule(context: Context, deadlineElapsedRealtime: Long) {
        val app = context.applicationContext
        AlarmNotifications.ensureChannels(app)
        val wallDeadline = System.currentTimeMillis() + (deadlineElapsedRealtime - SystemClock.elapsedRealtime())
        ExactAlarms.set(app, wallDeadline, alarmIntent(app), asAlarmClock = true)
        alarmPrefs(app).edit()
            .putLong(KEY_WALL, wallDeadline)
            .putLong(KEY_ELAPSED, deadlineElapsedRealtime)
            .putInt(KEY_BOOT, bootCount(app))
            .commit()
    }

    fun cancel(context: Context) {
        val app = context.applicationContext
        app.getSystemService(AlarmManager::class.java).cancel(alarmIntent(app))
        clearSchedule(app)
    }

    /** Forgets the saved deadline: the alert has fired or been cancelled. */
    fun clearSchedule(context: Context) {
        alarmPrefs(context.applicationContext).edit().clear().commit()
    }

    /** Silences the countdown alert if it is ringing, and clears its notification. */
    fun dismissNotification(context: Context) {
        val app = context.applicationContext
        AlarmRinging.stop(app, RingRequest.SOURCE_TIMER)
        app.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    /**
     * The countdown deadline on the current elapsed-realtime clock. After a reboot the saved value
     * means nothing, so it is rebuilt from the wall-clock deadline.
     */
    fun reconcileDeadline(context: Context, savedDeadline: Long): Long {
        val app = context.applicationContext
        val prefs = alarmPrefs(app)
        val wall = prefs.getLong(KEY_WALL, 0L)
        if (wall == 0L) return savedDeadline
        if (prefs.getInt(KEY_BOOT, -1) == bootCount(app)) return prefs.getLong(KEY_ELAPSED, savedDeadline)
        return SystemClock.elapsedRealtime() + (wall - System.currentTimeMillis())
    }

    /** Re-arms a running countdown after a reboot or a clock/time-zone change. */
    fun restore(context: Context) {
        val app = context.applicationContext
        if (alarmPrefs(app).getLong(KEY_WALL, 0L) == 0L) return
        val deadline = reconcileDeadline(app, 0L)
        if (deadline > SystemClock.elapsedRealtime()) {
            schedule(app, deadline)
        } else {
            // It ran out while the phone was off; leave a note rather than ringing late.
            clearSchedule(app)
            AlarmNotifications.postMissed(app, RingRequest.forTimer(app))
        }
    }

    fun notificationsAllowed(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 ||
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED)

    private fun alarmPrefs(context: Context) =
        context.getSharedPreferences(ALARM_PREFS, Context.MODE_PRIVATE)

    private fun bootCount(context: Context): Int =
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }
            .getOrDefault(-1)

    private fun alarmIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            Intent(context, TimerAlarmReceiver::class.java).setAction(ACTION_FINISHED),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    internal val vibrationWaveform: LongArray get() = VIBRATION_PATTERN
}

/** Fires at the countdown deadline. If the app is on screen it handles the alert itself. */
internal class TimerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TimerAlerts.ACTION_FINISHED) return
        TimerAlerts.clearSchedule(context)
        if (TimerAlertState.appInForeground) return
        AlarmRinging.deliver(context, RingRequest.forTimer(context))
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