package com.nahsor1234.dailytracker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build

/**
 * Everything the ringing service and the notification buttons need to know about one alert. It is
 * copied into the extras of every intent involved, so a button press never depends on the app
 * process still holding any state.
 */
internal data class RingRequest(
    val source: String,
    val id: Long,
    val title: String,
    val text: String,
    /** true: ring in a loop until dismissed. false: a single notification. */
    val alarm: Boolean,
    val ringtoneUri: String?,
    val sound: Boolean,
    val vibrate: Boolean,
    val snoozeable: Boolean
) {
    val key: String get() = "$source:$id"

    fun putInto(intent: Intent): Intent = intent.apply {
        putExtra(X_SOURCE, source)
        putExtra(X_ID, id)
        putExtra(X_TITLE, title)
        putExtra(X_TEXT, text)
        putExtra(X_ALARM, alarm)
        putExtra(X_RINGTONE, ringtoneUri)
        putExtra(X_SOUND, sound)
        putExtra(X_VIBRATE, vibrate)
        putExtra(X_SNOOZEABLE, snoozeable)
    }

    companion object {
        const val SOURCE_REMINDER = "reminder"
        const val SOURCE_TIMER = "timer"

        private const val X_SOURCE = "ring_source"
        private const val X_ID = "ring_id"
        private const val X_TITLE = "ring_title"
        private const val X_TEXT = "ring_text"
        private const val X_ALARM = "ring_alarm"
        private const val X_RINGTONE = "ring_ringtone"
        private const val X_SOUND = "ring_sound"
        private const val X_VIBRATE = "ring_vibrate"
        private const val X_SNOOZEABLE = "ring_snoozeable"

        fun from(intent: Intent?): RingRequest? {
            if (intent == null) return null
            val source = intent.getStringExtra(X_SOURCE) ?: return null
            return RingRequest(
                source = source,
                id = intent.getLongExtra(X_ID, 0L),
                title = intent.getStringExtra(X_TITLE).orEmpty(),
                text = intent.getStringExtra(X_TEXT).orEmpty(),
                alarm = intent.getBooleanExtra(X_ALARM, true),
                ringtoneUri = intent.getStringExtra(X_RINGTONE),
                sound = intent.getBooleanExtra(X_SOUND, true),
                vibrate = intent.getBooleanExtra(X_VIBRATE, true),
                snoozeable = intent.getBooleanExtra(X_SNOOZEABLE, false)
            )
        }

        fun forReminder(reminder: TrackerReminder): RingRequest {
            val alarm = reminder.kind == ReminderKind.ALARM
            return RingRequest(
                source = SOURCE_REMINDER,
                id = reminder.id,
                title = reminder.title,
                text = (if (alarm) "Alarm" else "Reminder") + " • " + reminder.displayTime(),
                alarm = alarm,
                ringtoneUri = reminder.ringtoneUri,
                sound = true,
                vibrate = true,
                snoozeable = true
            )
        }

        fun forTimer(context: Context): RingRequest {
            val settings = TimerAlertSettings(context)
            return RingRequest(
                source = SOURCE_TIMER,
                id = 0L,
                title = "Time is up",
                text = "Your countdown has finished",
                alarm = true,
                ringtoneUri = settings.ringtoneUri,
                sound = settings.soundEnabled,
                vibrate = settings.vibrationEnabled,
                snoozeable = false
            )
        }
    }
}

/** Channels, and the notifications built on them. */
internal object AlarmNotifications {
    /** Silent on purpose: the ringing service plays the sound itself, in a loop. */
    const val CHANNEL_RINGING = "dailytracker_ringing"
    const val CHANNEL_REMINDERS = "dailytracker_reminders_v2"
    const val CHANNEL_QUIET = "dailytracker_quiet"
    const val CHANNEL_FALLBACK = "dailytracker_fallback_alarm"
    const val RING_ID = 7100

    private val ACCENT = Color.parseColor("#5E9B6B")
    private val REMINDER_VIBRATION = longArrayOf(0, 250, 150, 250)

    /**
     * Channel settings cannot change after creation, which is why the old per-alarm channels (one
     * short sound each) are removed here rather than edited.
     */
    fun ensureChannels(context: Context, cleanup: Boolean = false) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (cleanup) {
            manager.notificationChannels
                .filter {
                    it.id == "dailytracker_reminders" ||
                        it.id.startsWith("dailytracker_alarm_") ||
                        it.id.startsWith("dailytracker_timer_")
                }
                .forEach { manager.deleteNotificationChannel(it.id) }
        }

        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RINGING, "Ringing alarms and timers", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Shown while an alarm or countdown is ringing, with Dismiss and Snooze buttons"
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_REMINDERS, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Scheduled study and daily reminders"
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build()
                )
                enableVibration(true)
                vibrationPattern = REMINDER_VIBRATION
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_QUIET, "Silent alerts and missed alarms", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Alerts you muted, and alarms that rang while you were away"
                setSound(null, null)
                enableVibration(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_FALLBACK, "Alarm backup", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Used only if the system does not let DailyTracker ring in the background"
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 600, 300, 600, 300, 900)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }

    fun notificationId(ring: RingRequest): Int = if (ring.alarm) RING_ID else ring.key.hashCode()

    fun canPost(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 ||
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED)

    /** The ongoing notification of the foreground service while an alarm rings. */
    fun ringing(context: Context, ring: RingRequest): Notification {
        val builder = base(context, ring, CHANNEL_RINGING)
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // Swiping the notification away must silence the alarm too, not just hide the buttons.
            .setDeleteIntent(actionIntent(context, ring, AlarmRinging.ACTION_DISMISS))
        addButtons(context, builder, ring)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    /** A one-off notification (plain reminders, and alarms the user muted). */
    fun postPlain(context: Context, ring: RingRequest, channel: String) {
        if (!canPost(context)) return
        val builder = base(context, ring, channel)
            .setCategory(if (ring.alarm) Notification.CATEGORY_ALARM else Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
        addButtons(context, builder, ring)
        show(context, notificationId(ring), builder.build())
    }

    /**
     * Used when the system refuses to start the ringing service. The notification repeats its
     * alarm sound until it is dismissed, so the alarm is still not silent.
     */
    fun postFallbackAlarm(context: Context, ring: RingRequest) {
        if (!canPost(context)) return
        val builder = base(context, ring, CHANNEL_FALLBACK)
            .setCategory(Notification.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setTimeoutAfter(AlarmRinging.RING_LIMIT_MS)
        addButtons(context, builder, ring)
        val notification = builder.build()
        notification.flags = notification.flags or Notification.FLAG_INSISTENT
        show(context, RING_ID, notification)
    }

    fun postMissed(context: Context, ring: RingRequest) {
        if (!canPost(context)) return
        val missed = ring.copy(text = "Missed • " + ring.text, alarm = false, snoozeable = false)
        val builder = base(context, missed, CHANNEL_QUIET)
            .setCategory(Notification.CATEGORY_STATUS)
            .setAutoCancel(true)
        show(context, ("missed:" + ring.key).hashCode(), builder.build())
    }

    fun actionIntent(context: Context, ring: RingRequest, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            (action + ":" + ring.key).hashCode(),
            ring.putInto(Intent(context, AlarmActionReceiver::class.java).setAction(action)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun base(context: Context, ring: RingRequest, channel: String): Notification.Builder =
        Notification.Builder(context, channel)
            .setSmallIcon(if (ring.alarm) R.drawable.ic_stat_alarm else R.drawable.ic_stat_reminder)
            .setColor(ACCENT)
            .setContentTitle(ring.title)
            .setContentText(ring.text)
            .setSubText(context.getString(R.string.app_name))
            .setStyle(Notification.BigTextStyle().bigText(ring.text))
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(ExactAlarms.openAppIntent(context))

    private fun addButtons(context: Context, builder: Notification.Builder, ring: RingRequest) {
        val dismissLabel = when {
            ring.source == RingRequest.SOURCE_TIMER -> "Stop"
            ring.alarm -> "Dismiss"
            else -> "Done"
        }
        builder.addAction(
            Notification.Action.Builder(null, dismissLabel, actionIntent(context, ring, AlarmRinging.ACTION_DISMISS)).build()
        )
        if (ring.snoozeable) {
            builder.addAction(
                Notification.Action.Builder(
                    null,
                    "Snooze ${AlarmRinging.SNOOZE_MINUTES} min",
                    actionIntent(context, ring, AlarmRinging.ACTION_SNOOZE)
                ).build()
            )
        }
    }

    private fun show(context: Context, id: Int, notification: Notification) {
        context.getSystemService(NotificationManager::class.java).notify(id, notification)
    }
}
