package com.nahsor1234.dailytracker

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * Rings an alarm or a finished countdown: loops the sound and vibration until the user dismisses
 * it, however the app was left. Running as a foreground service is what lets this continue with
 * the app closed and the screen off.
 */
internal class AlarmRingService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var request: RingRequest? = null
    private var player: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioFocus: AudioFocusRequest? = null
    private val giveUp = Runnable { finish(missed = true) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ring = RingRequest.from(intent)
        if (ring == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        // A second alarm firing while one is ringing replaces it; the first is kept as "missed".
        request?.takeIf { it.key != ring.key }?.let { AlarmNotifications.postMissed(this, it) }
        handler.removeCallbacks(giveUp)
        stopPlayback()

        // The system requires startForeground() promptly after startForegroundService(), so this
        // comes before anything that could be slow.
        AlarmNotifications.ensureChannels(this)
        val started = runCatching {
            val notification = AlarmNotifications.ringing(this, ring)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    AlarmNotifications.RING_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(AlarmNotifications.RING_ID, notification)
            }
        }.isSuccess
        if (!started) {
            AlarmNotifications.postFallbackAlarm(this, ring)
            request = null
            AlarmRinging.current = null
            stopSelf()
            return START_NOT_STICKY
        }

        request = ring
        AlarmRinging.current = ring
        startPlayback(ring)
        handler.postDelayed(giveUp, AlarmRinging.RING_LIMIT_MS)
        return START_NOT_STICKY
    }

    /** Ends the alert. [missed] keeps a quiet notification behind for an alarm nobody answered. */
    private fun finish(missed: Boolean) {
        val ring = request
        request = null
        AlarmRinging.current = null
        stopPlayback()
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (missed && ring != null) AlarmNotifications.postMissed(this, ring)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopPlayback()
        AlarmRinging.current = null
        super.onDestroy()
    }

    private fun startPlayback(ring: RingRequest) {
        if (!ring.sound && !ring.vibrate) return
        wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dailytracker:ring")
            ?.apply {
                setReferenceCounted(false)
                acquire(AlarmRinging.RING_LIMIT_MS + 10_000L)
            }

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        if (ring.sound) {
            requestFocus(attributes)
            player = openPlayer(ring.ringtoneUri, attributes)
        }
        if (ring.vibrate) vibrate(attributes)
    }

    /**
     * Tries the chosen ringtone first. If it can no longer be read (a deleted file, a revoked
     * permission), the phone's default alarm sound is used rather than ringing in silence.
     */
    private fun openPlayer(custom: String?, attributes: AudioAttributes): MediaPlayer? {
        val candidates = listOfNotNull(
            custom?.let(Uri::parse),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        )
        for (uri in candidates) {
            val candidate = MediaPlayer()
            try {
                candidate.setAudioAttributes(attributes)
                candidate.setDataSource(this, uri)
                candidate.isLooping = true
                candidate.prepare()
                candidate.start()
                return candidate
            } catch (_: Exception) {
                runCatching { candidate.release() }
            }
        }
        return null
    }

    private fun requestFocus(attributes: AudioAttributes) {
        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(attributes)
            .build()
        runCatching { getSystemService(AudioManager::class.java).requestAudioFocus(focusRequest) }
        audioFocus = focusRequest
    }

    @Suppress("DEPRECATION")
    private fun vibrate(attributes: AudioAttributes) {
        val vibrator = getSystemService(Vibrator::class.java) ?: return
        if (!vibrator.hasVibrator()) return
        runCatching {
            vibrator.vibrate(VibrationEffect.createWaveform(TimerAlerts.vibrationWaveform, 0), attributes)
        }
    }

    private fun stopPlayback() {
        player?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        player = null
        runCatching { getSystemService(Vibrator::class.java)?.cancel() }
        audioFocus?.let { runCatching { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) } }
        audioFocus = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }
}

/** Entry points for everything that starts, stops or postpones an alert. */
internal object AlarmRinging {
    const val ACTION_DISMISS = "com.nahsor1234.dailytracker.action.DISMISS_ALARM"
    const val ACTION_SNOOZE = "com.nahsor1234.dailytracker.action.SNOOZE_ALARM"
    const val ACTION_SNOOZE_FIRE = "com.nahsor1234.dailytracker.action.SNOOZE_FIRE"
    const val SNOOZE_MINUTES = 10L
    const val RING_LIMIT_MS = 5 * 60_000L

    /** What the service is ringing right now. The service and the app share one process. */
    @Volatile
    var current: RingRequest? = null

    /** Shows [ring] now: ringing for alarms and countdowns, a notification for plain reminders. */
    fun deliver(context: Context, ring: RingRequest) {
        val app = context.applicationContext
        AlarmNotifications.ensureChannels(app)
        when {
            !ring.alarm -> AlarmNotifications.postPlain(app, ring, AlarmNotifications.CHANNEL_REMINDERS)
            // Muted countdown: nothing should make noise, so no service and no wake lock.
            !ring.sound && !ring.vibrate -> AlarmNotifications.postPlain(app, ring, AlarmNotifications.CHANNEL_QUIET)
            else -> try {
                app.startForegroundService(ring.putInto(Intent(app, AlarmRingService::class.java)))
            } catch (_: RuntimeException) {
                // e.g. ForegroundServiceStartNotAllowedException: still alert, just less elegantly.
                AlarmNotifications.postFallbackAlarm(app, ring)
            }
        }
    }

    /** Stops the ringing service. With [source] set, only if it is ringing for that source. */
    fun stop(context: Context, source: String? = null) {
        val active = current ?: return
        if (source != null && active.source != source) return
        val app = context.applicationContext
        app.stopService(Intent(app, AlarmRingService::class.java))
    }

    /** The Dismiss / Stop / Done button, or swiping the notification away. */
    fun dismiss(context: Context, ring: RingRequest) {
        val app = context.applicationContext
        val active = current
        if (ring.alarm) {
            when {
                active?.key == ring.key -> stop(app)
                // Not ringing any more (or never started): only the notification is left to clear.
                active == null -> app.getSystemService(NotificationManager::class.java)
                    .cancel(AlarmNotifications.RING_ID)
            }
        } else {
            app.getSystemService(NotificationManager::class.java).cancel(AlarmNotifications.notificationId(ring))
        }
    }

    fun snooze(context: Context, ring: RingRequest) {
        val app = context.applicationContext
        val triggerAt = System.currentTimeMillis() + SNOOZE_MINUTES * 60_000L
        ExactAlarms.set(app, triggerAt, snoozeOperation(app, ring), asAlarmClock = ring.alarm)
    }

    fun cancelSnooze(context: Context, source: String, id: Long) {
        val app = context.applicationContext
        val existing = PendingIntent.getBroadcast(
            app,
            snoozeRequestCode(source, id),
            Intent(app, AlarmActionReceiver::class.java).setAction(ACTION_SNOOZE_FIRE),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        app.getSystemService(AlarmManager::class.java).cancel(existing)
        existing.cancel()
    }

    private fun snoozeOperation(context: Context, ring: RingRequest): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            snoozeRequestCode(ring.source, ring.id),
            ring.putInto(Intent(context, AlarmActionReceiver::class.java).setAction(ACTION_SNOOZE_FIRE)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun snoozeRequestCode(source: String, id: Long): Int = "snooze:$source:$id".hashCode()
}

/** Handles the buttons on the alert notification, and a snoozed alert coming due again. */
internal class AlarmActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ring = RingRequest.from(intent) ?: return
        when (intent.action) {
            AlarmRinging.ACTION_DISMISS -> AlarmRinging.dismiss(context, ring)
            AlarmRinging.ACTION_SNOOZE -> {
                AlarmRinging.dismiss(context, ring)
                AlarmRinging.snooze(context, ring)
            }
            AlarmRinging.ACTION_SNOOZE_FIRE -> AlarmRinging.deliver(context, ring)
        }
    }
}

/**
 * Schedules an alarm for an exact time. Alarm-clock alarms are exempt from Doze and from the
 * "exact alarms" permission (which is off by default on recent Android), so they are used for
 * anything that must ring on time. Plain reminders use a regular exact alarm when allowed, so they
 * do not put an alarm icon in the status bar, and fall back to an alarm-clock alarm otherwise.
 */
internal object ExactAlarms {
    fun set(context: Context, triggerAtMillis: Long, operation: PendingIntent, asAlarmClock: Boolean) {
        val manager = context.getSystemService(AlarmManager::class.java)
        val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
        if (!asAlarmClock && exactAllowed) {
            try {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation)
                return
            } catch (_: SecurityException) {
                // The permission was revoked between the check and the call.
            }
        }
        manager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAtMillis, openAppIntent(context)), operation)
    }

    fun openAppIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            OPEN_APP_REQUEST,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private const val OPEN_APP_REQUEST = 7002
}
