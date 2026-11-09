package com.nahsor1234.dailytracker

import android.content.Intent
import android.net.Uri
import android.media.RingtoneManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.drop
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReminderScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { ReminderStore(context) }
    val colors = MaterialTheme.colorScheme
    var reminders by remember { mutableStateOf(store.all()) }
    var kind by remember { mutableStateOf(ReminderKind.NOTIFICATION) }
    var title by remember { mutableStateOf("") }
    var hour by remember { mutableIntStateOf(8) }
    var minute by remember { mutableIntStateOf(0) }
    var daily by remember { mutableStateOf(true) }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var editingId by remember { mutableStateOf<Long?>(null) }
    var ringtoneUri by remember { mutableStateOf<String?>(null) }
    var deleteConfirmation by remember { mutableStateOf<TrackerReminder?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    val haptics = rememberHaptics()

    val ringtonePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val picked = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            ringtoneUri = picked?.toString()
            message = null
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        message = if (granted) "Permission granted. Tap Save reminder again." else
            "Notifications are blocked. Enable them in system settings to receive reminders."
    }

    fun refresh() {
        reminders = store.all()
    }

    fun resetEditor() {
        title = ""
        hour = 8
        minute = 0
        daily = true
        date = LocalDate.now()
        editingId = null
        ringtoneUri = null
        message = null
    }

    fun persistReminder() {
        val cleanTitle = title.trim()
        if (cleanTitle.isEmpty()) {
            message = "Add a name for this reminder."
            haptics.reject()
            return
        }
        val now = java.time.ZonedDateTime.now()
        val scheduled = date.atTime(hour, minute)
        if (!daily && !scheduled.isAfter(now.toLocalDateTime())) {
            message = "Choose a future date and time."
            haptics.reject()
            return
        }
        val id = editingId ?: System.currentTimeMillis()
        val reminder = TrackerReminder(
            id = id,
            title = cleanTitle,
            kind = kind,
            hour = hour,
            minute = minute,
            daily = daily,
            dateEpochDay = date.toEpochDay(),
            enabled = true,
            ringtoneUri = if (kind == ReminderKind.ALARM) ringtoneUri else null
        )
        store.upsert(reminder)
        ReminderScheduler.schedule(context, reminder)
        refresh()
        resetEditor()
        message = "Reminder saved on this device."
        haptics.confirm()
    }

    val notificationAllowed = remember {
        context.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()
    } && (Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED)

    // Material 3 pickers. The date picker only allows today or later, as the old dialog did.
    if (showDatePicker) {
        val utc = java.time.ZoneOffset.UTC
        val dateState = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(utc).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    !java.time.Instant.ofEpochMilli(utcTimeMillis).atZone(utc).toLocalDate()
                        .isBefore(LocalDate.now())
            }
        )
        val pickerColors = DatePickerDefaults.colors(containerColor = colors.surface)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            colors = pickerColors,
            confirmButton = {
                TextButton(onClick = {
                    dateState.selectedDateMillis?.let {
                        date = java.time.Instant.ofEpochMilli(it).atZone(utc).toLocalDate()
                    }
                    message = null
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } }
        ) {
            DatePicker(state = dateState, colors = pickerColors)
        }
    }
    if (showTimePicker) {
        val timeState = rememberTimePickerState(
            initialHour = hour,
            initialMinute = minute,
            is24Hour = android.text.format.DateFormat.is24HourFormat(context)
        )
        // A short tick each time the hour, the minute or AM/PM changes, for notifications and alarms.
        LaunchedEffect(timeState) {
            snapshotFlow { Triple(timeState.hour, timeState.minute, timeState.isAfternoon) }
                .drop(1)
                .collect { haptics.tick() }
        }
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            containerColor = colors.surface,
            title = { Text("Select time") },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(onClick = {
                    hour = timeState.hour
                    minute = timeState.minute
                    message = null
                    showTimePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("Cancel") } }
        )
    }

    Surface(Modifier.fillMaxSize(), color = colors.background) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .statusBarsPadding().padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { haptics.click(); onBack() }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                    Text("‹  Back", color = colors.primary, fontSize = 15.sp)
                }
                Spacer(Modifier.weight(1f))
                Text("SCHEDULE", color = colors.primary, fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text("Reminders", color = colors.onBackground,
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Light))
            Spacer(Modifier.height(4.dp))
            Text("Keep your day on track, at the right time.",
                color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(18.dp))
            AlarmReliabilityCard()
            Spacer(Modifier.height(22.dp))

            BoxWithConstraints(
                Modifier.fillMaxWidth().height(52.dp).clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.045f))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), CircleShape).padding(4.dp)
            ) {
                val indicatorOffset by animateDpAsState(
                    targetValue = if (kind == ReminderKind.ALARM) maxWidth / 2 else 0.dp,
                    animationSpec = tween(300, easing = FastOutSlowInEasing),
                    label = "reminder-type-indicator"
                )
                Box(
                    Modifier.offset(x = indicatorOffset).width(maxWidth / 2).fillMaxHeight()
                        .clip(CircleShape)
                        .background(colors.primary.copy(alpha = 0.18f))
                )
                Row(Modifier.fillMaxWidth()) {
                    ReminderKind.entries.forEach { option ->
                        val selected = kind == option
                        Box(
                            Modifier.weight(1f).clip(CircleShape)
                                .clickable {
                                    if (kind != option) haptics.select()
                                    kind = option
                                    message = null
                                }.padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                if (option == ReminderKind.NOTIFICATION) "Notification" else "Alarm",
                                color = if (selected) colors.primary else colors.onSurfaceVariant,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
                    .background(Color.White.copy(alpha = 0.035f))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(24.dp))
                    .padding(18.dp)
                    .animateContentSize()
            ) {
                Text(if (editingId == null) "NEW ${if (kind == ReminderKind.ALARM) "ALARM" else "NOTIFICATION"}"
                    else "EDIT REMINDER",
                    color = colors.primary, fontSize = 10.sp, letterSpacing = 1.8.sp,
                    fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                Text("Name", color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                TextField(
                    value = title,
                    onValueChange = { title = it.take(70) },
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)),
                    singleLine = true,
                    placeholder = { Text("e.g. Start studying Physics") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Black.copy(alpha = 0.18f),
                        unfocusedContainerColor = Color.Black.copy(alpha = 0.18f),
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    )
                )
                Spacer(Modifier.height(18.dp))
                Text("Repeat", color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RepeatChoice("Daily", daily, Modifier.weight(1f)) { daily = true }
                    RepeatChoice("Once", !daily, Modifier.weight(1f)) { daily = false }
                }
                if (!daily) {
                    Spacer(Modifier.height(14.dp))
                    SelectionRow(
                        label = "Date",
                        value = date.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.getDefault()))
                    ) {
                        showDatePicker = true
                    }
                }
                Spacer(Modifier.height(14.dp))
                SelectionRow(
                    label = "Time",
                    value = LocalTime.of(hour, minute).format(DateTimeFormatter.ofPattern("hh:mm a", Locale.getDefault()))
                ) {
                    showTimePicker = true
                }
                Spacer(Modifier.height(18.dp))

                if (kind == ReminderKind.ALARM) {
                    val selectedRingtone = ringtoneUri?.let(Uri::parse)
                    val ringtoneLabel = selectedRingtone?.let {
                        RingtoneManager.getRingtone(context, it)?.getTitle(context)
                    } ?: "Default alarm sound"
                    SelectionRow(label = "Ringtone", value = ringtoneLabel) {
                        val pickerIntent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Choose alarm ringtone")
                            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                            putExtra(
                                RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                                selectedRingtone ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                            )
                        }
                        ringtonePicker.launch(pickerIntent)
                    }
                    Spacer(Modifier.height(12.dp))
                }

                Button(
                    onClick = {
                        haptics.click()
                        val allowed = context.getSystemService(android.app.NotificationManager::class.java)
                            .areNotificationsEnabled() &&
                            (Build.VERSION.SDK_INT < 33 ||
                                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                                android.content.pm.PackageManager.PERMISSION_GRANTED)
                        if (!allowed && Build.VERSION.SDK_INT >= 33) {
                            permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        } else if (!allowed) {
                            message = "Enable notifications for DailyTracker in system settings."
                        } else {
                            persistReminder()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = CircleShape
                ) {
                    Text(if (editingId == null) "Save reminder" else "Save changes",
                        fontWeight = FontWeight.SemiBold)
                }
                if (editingId != null) {
                    TextButton(onClick = { haptics.click(); resetEditor() }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text("Cancel editing", color = colors.onSurfaceVariant)
                    }
                }
                message?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, color = if (it.startsWith("Choose") || it.startsWith("Add")) colors.error
                        else colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }

            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Scheduled", color = colors.onBackground,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Light))
                Spacer(Modifier.weight(1f))
                Text(reminders.size.toString().padStart(2, '0'), color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(10.dp))
            if (reminders.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                        .background(Color.White.copy(alpha = 0.025f))
                        .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(20.dp))
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Nothing scheduled", color = colors.onSurface,
                        style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text("Your active reminders and alarms will appear here.",
                        color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            } else {
                reminders.forEach { reminder ->
                    ReminderListItem(
                        reminder = reminder,
                        onToggle = { enabled ->
                            val updated = reminder.copy(enabled = enabled)
                            store.upsert(updated)
                            if (enabled) ReminderScheduler.schedule(context, updated)
                            else ReminderScheduler.cancel(context, reminder.id)
                            refresh()
                        },
                        onEdit = {
                            kind = reminder.kind
                            title = reminder.title
                            hour = reminder.hour
                            minute = reminder.minute
                            daily = reminder.daily
                            date = LocalDate.ofEpochDay(reminder.dateEpochDay)
                            ringtoneUri = reminder.ringtoneUri
                            editingId = reminder.id
                            message = null
                        },
                        onDelete = { deleteConfirmation = reminder }
                    )
                    Spacer(Modifier.height(9.dp))
                }
            }
            Spacer(Modifier.navigationBarsPadding().height(32.dp))
        }
        deleteConfirmation?.let { reminder ->
            AlertDialog(
                onDismissRequest = { deleteConfirmation = null },
                title = { Text("Delete reminder?") },
                text = { Text("\u201c" + reminder.title + "\u201d will be permanently removed.") },
                confirmButton = {
                    TextButton(onClick = {
                        haptics.heavy()
                        ReminderScheduler.cancel(context, reminder.id)
                        store.remove(reminder.id)
                        refresh()
                        if (editingId == reminder.id) resetEditor()
                        deleteConfirmation = null
                    }) { Text("Delete", color = colors.error) }
                },
                dismissButton = {
                    TextButton(onClick = { haptics.click(); deleteConfirmation = null }) { Text("Cancel") }
                }
            )
        }
    }
}

@Composable
private fun RepeatChoice(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val source = rememberPressSource()
    Box(
        modifier.pressScale(source, 0.96f).height(42.dp).clip(CircleShape)
            .background(if (selected) colors.primary.copy(alpha = 0.16f) else Color.Transparent)
            .border(1.dp, if (selected) colors.primary.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.12f), CircleShape)
            .hapticClickable(source, haptic = { if (!selected) select() }, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (selected) colors.primary else colors.onSurfaceVariant,
            fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
    }
}

@Composable
private fun SelectionRow(label: String, value: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val source = rememberPressSource()
    Row(
        Modifier.pressScale(source, 0.98f).fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.16f))
            .hapticClickable(source, haptic = { click() }, onClick = onClick).padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(3.dp))
            Text(value, color = colors.onSurface, style = MaterialTheme.typography.titleMedium)
        }
        Text("Change  ›", color = colors.primary, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ReminderListItem(
    reminder: TrackerReminder,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.035f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(20.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(reminder.title, color = colors.onSurface, fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(3.dp))
            Text(
                "${if (reminder.kind == ReminderKind.ALARM) "ALARM" else "NOTIFICATION"}  ·  ${if (reminder.daily) "Daily" else LocalDate.ofEpochDay(reminder.dateEpochDay).format(DateTimeFormatter.ofPattern("d MMM"))}",
                color = colors.primary, fontSize = 10.sp, letterSpacing = 0.7.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(3.dp))
            Text(
                LocalTime.of(reminder.hour, reminder.minute).format(DateTimeFormatter.ofPattern("hh:mm a", Locale.getDefault())),
                color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall
            )
            Row(
                Modifier.clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.045f))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { haptics.click(); onEdit() },
                    contentPadding = PaddingValues(horizontal = 9.dp, vertical = 0.dp),
                    modifier = Modifier.height(30.dp)
                ) { Text("Edit", color = colors.onSurfaceVariant, fontSize = 12.sp) }
                TextButton(
                    onClick = { haptics.heavy(); onDelete() },
                    contentPadding = PaddingValues(horizontal = 9.dp, vertical = 0.dp),
                    modifier = Modifier.height(30.dp)
                ) { Text("Delete", color = colors.error, fontSize = 12.sp) }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Switch(checked = reminder.enabled, onCheckedChange = { haptics.toggle(it); onToggle(it) })
            Text(if (reminder.enabled) "ON" else "OFF", color = colors.onSurfaceVariant,
                fontSize = 9.sp, letterSpacing = 1.sp)
        }
    }
}