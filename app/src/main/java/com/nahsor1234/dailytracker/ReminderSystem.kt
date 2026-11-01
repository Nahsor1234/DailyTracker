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
import android.media.RingtoneManager
import android.os.Build
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

internal enum class ReminderKind { NOTIFICATION, ALARM }

internal data class TrackerReminder(
    val id: Long,
    val title: String,
    val kind: ReminderKind,
    val hour: Int,
    val minute: Int,
    val daily: Boolean,
    val dateEpochDay: Long,
    val enabled: Boolean,
    val ringtoneUri: String? = null
) {
    fun displayTime(): String = String.format(java.util.Locale.getDefault(), "%02d:%02d", hour, minute)
}

internal class ReminderStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("tracker_reminders", Context.MODE_PRIVATE)

    @Synchronized
    fun all(): List<TrackerReminder> {
        val json = preferences.getString(KEY_REMINDERS, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(json)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        TrackerReminder(
                            id = item.getLong("id"),
                            title = item.getString("title"),
                            kind = ReminderKind.valueOf(item.getString("kind")),
                            hour = item.getInt("hour"),
                            minute = item.getInt("minute"),
                            daily = item.getBoolean("daily"),
                            dateEpochDay = item.getLong("date"),
                            enabled = item.getBoolean("enabled"),
                            ringtoneUri = item.optString("ringtoneUri").takeIf { it.isNotBlank() }
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun save(reminders: List<TrackerReminder>) {
        val array = JSONArray()
        reminders.forEach { reminder ->
            array.put(
                JSONObject().apply {
                    put("id", reminder.id)
                    put("title", reminder.title)
                    put("kind", reminder.kind.name)
                    put("hour", reminder.hour)
                    put("minute", reminder.minute)
                    put("daily", reminder.daily)
                    put("date", reminder.dateEpochDay)
                    put("enabled", reminder.enabled)
                    reminder.ringtoneUri?.let { put("ringtoneUri", it) }
                }
            )
        }
        preferences.edit().putString(KEY_REMINDERS, array.toString()).apply()
    }

    fun upsert(reminder: TrackerReminder) {
        val updated = all().filterNot { it.id == reminder.id } + reminder
        save(updated.sortedBy { it.hour * 60 + it.minute })
    }

    fun remove(id: Long) = save(all().filterNot { it.id == id })

    fun find(id: Long): TrackerReminder? = all().firstOrNull { it.id == id }

    companion object {
        private const val KEY_REMINDERS = "reminders_json"
    }
}

internal object ReminderScheduler {
    const val ACTION_FIRE = "com.nahsor1234.dailytracker.action.FIRE_REMINDER"
    const val EXTRA_REMINDER_ID = "reminder_id"

    fun canScheduleExact(context: Context): Boolean {
        val manager = context.getSystemService(AlarmManager::class.java)
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
    }

    fun schedule(context: Context, reminder: TrackerReminder) {
        cancel(context, reminder.id)
        if (!reminder.enabled) return
        val manager = context.getSystemService(AlarmManager::class.java)
        if (reminder.kind == ReminderKind.ALARM) ReminderNotificationChannels.ensureAlarmChannel(context, reminder.ringtoneUri)
        val triggerAt = nextTriggerMillis(reminder)
        if (triggerAt == null) {
            ReminderStore(context).upsert(reminder.copy(enabled = false))
            return
        }
        val pendingIntent = pendingIntent(context, reminder.id)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && manager.canScheduleExactAlarms()) {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        } else {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
    }

    fun cancel(context: Context, id: Long) {
        val manager = context.getSystemService(AlarmManager::class.java)
        manager.cancel(pendingIntent(context, id))
    }

    fun rescheduleAll(context: Context) {
        ReminderStore(context).all().filter { it.enabled }.forEach { schedule(context, it) }
    }

    private fun pendingIntent(context: Context, id: Long): PendingIntent {
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_REMINDER_ID, id)
        }
        val requestCode = (id xor (id ushr 32)).toInt()
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun nextTriggerMillis(reminder: TrackerReminder): Long? {
        val zone = ZoneId.systemDefault()
        val now = java.time.ZonedDateTime.now(zone)
        val time = LocalTime.of(reminder.hour, reminder.minute)
        val next = if (reminder.daily) {
            var candidate = now.toLocalDate().atTime(time).atZone(zone)
            if (!candidate.isAfter(now)) candidate = candidate.plusDays(1)
            candidate
        } else {
            val date = LocalDate.ofEpochDay(reminder.dateEpochDay)
            val candidate = date.atTime(time).atZone(zone)
            candidate.takeIf { it.isAfter(now) } ?: return null
        }
        return next.toInstant().toEpochMilli()
    }
}

internal object ReminderNotificationChannels {
    const val NOTIFICATIONS = "dailytracker_reminders"
    private const val ALARM_CHANNEL_PREFIX = "dailytracker_alarm_"

    fun alarmChannelId(ringtoneUri: String?): String {
        val sound = ringtoneUri ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)?.toString().orEmpty()
        return ALARM_CHANNEL_PREFIX + sound.hashCode().toUInt().toString(16)
    }

    fun create(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val notificationChannel = NotificationChannel(
            NOTIFICATIONS, "Reminders", NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "Scheduled study and daily reminders" }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(notificationChannel)
        ensureAlarmChannel(context, null)
    }

    fun ensureAlarmChannel(context: Context, ringtoneUri: String?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val soundUri = ringtoneUri?.let(Uri::parse)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val channel = NotificationChannel(
            alarmChannelId(ringtoneUri), "DailyTracker alarm", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "User-scheduled DailyTracker alarms"
            enableVibration(true)
            setSound(soundUri, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun show(context: Context, reminder: TrackerReminder) {
        create(context)
        if (!context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return

        val openApp = PendingIntent.getActivity(
            context,
            reminder.id.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val channel = if (reminder.kind == ReminderKind.ALARM) alarmChannelId(reminder.ringtoneUri) else NOTIFICATIONS
        val notification = Notification.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(reminder.title)
            .setContentText(if (reminder.kind == ReminderKind.ALARM) "Your scheduled alarm" else "Your scheduled reminder")
            .setCategory(if (reminder.kind == ReminderKind.ALARM) Notification.CATEGORY_ALARM else Notification.CATEGORY_REMINDER)
            .setPriority(if (reminder.kind == ReminderKind.ALARM) Notification.PRIORITY_HIGH else Notification.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(reminder.id.hashCode(), notification)
    }
}

internal class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderScheduler.ACTION_FIRE) return
        val id = intent.getLongExtra(ReminderScheduler.EXTRA_REMINDER_ID, -1L)
        if (id < 0L) return
        val store = ReminderStore(context)
        val reminder = store.find(id) ?: return
        if (!reminder.enabled) return

        ReminderNotificationChannels.show(context, reminder)
        if (reminder.daily) {
            ReminderScheduler.schedule(context, reminder)
        } else {
            store.upsert(reminder.copy(enabled = false))
        }
    }
}

internal class ReminderRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> ReminderScheduler.rescheduleAll(context)
        }
    }
}
