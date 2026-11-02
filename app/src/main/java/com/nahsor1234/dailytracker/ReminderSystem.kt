package com.nahsor1234.dailytracker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
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
        // commit(), not apply(): the alarm receiver saves and the process may be killed straight after.
        preferences.edit().putString(KEY_REMINDERS, array.toString()).commit()
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

    fun schedule(context: Context, reminder: TrackerReminder) {
        val manager = context.getSystemService(AlarmManager::class.java)
        manager.cancel(pendingIntent(context, reminder.id))
        if (!reminder.enabled) return
        val triggerAt = nextTriggerMillis(reminder)
        if (triggerAt == null) {
            ReminderStore(context).upsert(reminder.copy(enabled = false))
            return
        }
        ExactAlarms.set(
            context,
            triggerAt,
            pendingIntent(context, reminder.id),
            asAlarmClock = reminder.kind == ReminderKind.ALARM
        )
        AlarmLog.scheduled(context, RingRequest.SOURCE_REMINDER, reminder.id, triggerAt)
    }

    /** Removes the reminder's alarm and any snooze it has pending. */
    fun cancel(context: Context, id: Long) {
        val manager = context.getSystemService(AlarmManager::class.java)
        manager.cancel(pendingIntent(context, id))
        AlarmRinging.cancelSnooze(context, RingRequest.SOURCE_REMINDER, id)
        AlarmLog.clear(context, RingRequest.SOURCE_REMINDER, id)
    }

    /** An alarm up to this late is still rung (late) when the app finds it was never delivered. */
    private const val LATE_RING_WINDOW_MS = 10 * 60_000L
    private const val MISSED_GRACE_MS = 30_000L

    fun rescheduleAll(context: Context) {
        val store = ReminderStore(context)
        store.all().filter { it.enabled }.forEach { reminder ->
            val handled = recoverIfMissed(context, store, reminder)
            // A one-time reminder that was just handled is finished; everything else is re-armed.
            if (!(handled && !reminder.daily)) schedule(context, reminder)
        }
    }

    /**
     * If this reminder was due before now but never fired (the phone held the alarm back, or
     * force-stopped the app), say so instead of silently moving on. Recent ones still ring, older
     * ones leave a "Missed" note. Returns true if it was missed.
     */
    private fun recoverIfMissed(context: Context, store: ReminderStore, reminder: TrackerReminder): Boolean {
        val expected = AlarmLog.expected(context, RingRequest.SOURCE_REMINDER, reminder.id)
        val now = System.currentTimeMillis()
        if (expected <= 0L || expected > now - MISSED_GRACE_MS) return false

        AlarmLog.clear(context, RingRequest.SOURCE_REMINDER, reminder.id)
        if (!reminder.daily) store.upsert(reminder.copy(enabled = false))
        val ring = RingRequest.forReminder(reminder)
        if (now - expected <= LATE_RING_WINDOW_MS) {
            AlarmLog.fired(context, expected)
            AlarmRinging.deliver(context, ring)
        } else {
            AlarmNotifications.postMissed(context, ring)
        }
        return true
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

internal class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderScheduler.ACTION_FIRE) return
        val id = intent.getLongExtra(ReminderScheduler.EXTRA_REMINDER_ID, -1L)
        if (id < 0L) return
        val store = ReminderStore(context)
        val reminder = store.find(id) ?: return
        if (!reminder.enabled) return

        // A delivery that arrives long before the next due time is a leftover of something already
        // handled (e.g. after the app caught up on a missed alarm): ignore it rather than ring twice.
        val expected = AlarmLog.expected(context, RingRequest.SOURCE_REMINDER, id)
        if (expected > 0L && expected - System.currentTimeMillis() > 60_000L) return
        AlarmLog.fired(context, expected)

        // Arm the next occurrence before showing anything, so a daily reminder keeps coming even
        // if showing this one fails.
        runCatching {
            if (reminder.daily) {
                ReminderScheduler.schedule(context, reminder)
            } else {
                store.upsert(reminder.copy(enabled = false))
                AlarmLog.clear(context, RingRequest.SOURCE_REMINDER, id)
            }
        }
        AlarmRinging.deliver(context, RingRequest.forReminder(reminder))
    }
}

/** Re-arms every alarm after anything that makes the system forget or shift them. */
internal class ReminderRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" -> {
                runCatching { ReminderScheduler.rescheduleAll(context) }
                runCatching { TimerAlerts.restore(context) }
            }
        }
    }
}
