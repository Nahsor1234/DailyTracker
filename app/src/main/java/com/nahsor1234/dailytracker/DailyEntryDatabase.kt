package com.nahsor1234.dailytracker

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Handler
import android.os.Looper
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Records are keyed by the user's local calendar date (yyyy-MM-dd).
 * Changing device time zones does not move an existing record to another date.
 */
internal data class DailyEntry(
    val date: LocalDate,
    val completed: Boolean,
    val description: String
)

internal class DailyEntryRepository(context: Context) {

    private val appContext = context.applicationContext
    private val database = DailyEntryOpenHelper(appContext)
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "dailytracker-database").apply { isDaemon = true }
    }
    private val syncExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "dailytracker-cloud-sync").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    private val syncRunning = AtomicBoolean(false)
    private val syncRerun = AtomicBoolean(false)
    private var queuedSync: Triple<DailyTrackerCloud, String, (Result<Int>) -> Unit>? = null
    private val queuedSyncRunnable = Runnable { flushQueuedSync() }

    /** True while a typed edit is still waiting for its debounced write. Main thread only. */
    fun hasPendingSave(): Boolean = pendingEntry != null

    /**
     * Asks for a sync a few seconds from now instead of right away, so typing does not start a
     * network round trip after every pause. Repeated calls collapse into one. Main thread only.
     */
    fun syncCloudSoon(
        cloud: DailyTrackerCloud,
        accountId: String,
        onResult: (Result<Int>) -> Unit
    ) {
        queuedSync = Triple(cloud, accountId, onResult)
        mainHandler.removeCallbacks(queuedSyncRunnable)
        mainHandler.postDelayed(queuedSyncRunnable, SYNC_DELAY_MS)
    }

    /** Runs a waiting [syncCloudSoon] now (used when the app goes to the background). */
    fun flushQueuedSync() {
        mainHandler.removeCallbacks(queuedSyncRunnable)
        val queued = queuedSync ?: return
        queuedSync = null
        syncCloud(queued.first, queued.second, queued.third)
    }

    fun loadEntry(date: LocalDate, onResult: (Result<DailyEntry?>) -> Unit) {
        executor.execute {
            val result = runCatching {
                val cursor = database.readableDatabase.query(
                    TABLE_DAILY_ENTRIES,
                    arrayOf(COLUMN_COMPLETED, COLUMN_DESCRIPTION),
                    "$COLUMN_LOCAL_DATE = ?",
                    arrayOf(date.toString()),
                    null,
                    null,
                    null,
                    "1"
                )
                cursor.use {
                    if (it.moveToFirst()) {
                        DailyEntry(
                            date = date,
                            completed = it.getInt(0) == 1,
                            description = it.getString(1).orEmpty()
                        )
                    } else {
                        null
                    }
                }
            }
            mainHandler.post { onResult(result) }
        }
    }

    fun loadCompletedDates(
        month: YearMonth,
        onResult: (Result<Set<LocalDate>>) -> Unit
    ) {
        executor.execute {
            val result = runCatching {
                val cursor = database.readableDatabase.query(
                    TABLE_DAILY_ENTRIES,
                    arrayOf(COLUMN_LOCAL_DATE),
                    "$COLUMN_COMPLETED = 1 AND $COLUMN_LOCAL_DATE BETWEEN ? AND ?",
                    arrayOf(month.atDay(1).toString(), month.atEndOfMonth().toString()),
                    null,
                    null,
                    "$COLUMN_LOCAL_DATE ASC"
                )
                cursor.use {
                    buildSet {
                        while (it.moveToNext()) {
                            add(LocalDate.parse(it.getString(0)))
                        }
                    }
                }
            }
            mainHandler.post { onResult(result) }
        }
    }

    private var pendingEntry: DailyEntry? = null
    private var pendingCallback: ((Result<Unit>) -> Unit)? = null
    private val flushPendingRunnable = Runnable { flushPendingSave() }

    /**
     * For text typing: remembers the latest edit and writes it once the user pauses, instead of
     * writing (and, when signed in, syncing to the cloud) on every keystroke. Must be called on
     * the main thread. [flushPendingSave] writes it immediately when the app goes to the background.
     */
    fun saveEntryDebounced(entry: DailyEntry, onResult: (Result<Unit>) -> Unit) {
        pendingEntry = entry
        pendingCallback = onResult
        mainHandler.removeCallbacks(flushPendingRunnable)
        mainHandler.postDelayed(flushPendingRunnable, SAVE_DEBOUNCE_MS)
    }

    /** Writes a debounced edit right now, if there is one. Main thread only. */
    fun flushPendingSave() {
        mainHandler.removeCallbacks(flushPendingRunnable)
        val entry = pendingEntry ?: return
        val callback = pendingCallback ?: return
        pendingEntry = null
        pendingCallback = null
        saveEntry(entry, callback)
    }

    fun saveEntry(entry: DailyEntry, onResult: (Result<Unit>) -> Unit) {
        // A direct save carries the newest full entry, so any waiting debounced edit is obsolete.
        mainHandler.removeCallbacks(flushPendingRunnable)
        pendingEntry = null
        pendingCallback = null
        executor.execute {
            val result = runCatching {
                val zone = ZoneId.systemDefault()
                val today = LocalDate.now(zone)
                // An edit that was already open when midnight passed is still saved to its own day
                // for a short grace period, so text typed around midnight is not lost.
                val justAfterMidnight = entry.date == today.minusDays(1) &&
                    System.currentTimeMillis() - today.atStartOfDay(zone).toInstant().toEpochMilli() <
                    MIDNIGHT_GRACE_MS
                require(entry.date == today || justAfterMidnight) {
                    "Only the current local date can be edited."
                }
                require(entry.description.length <= MAX_DESCRIPTION_LENGTH) {
                    "The daily description exceeds the 5,000-character limit."
                }
                // Never stamp a record older than its previous version, even if the phone clock
                // was moved backwards: otherwise the older copy could win the next sync.
                val previousStamp = database.writableDatabase.rawQuery(
                    "SELECT $COLUMN_UPDATED_AT FROM $TABLE_DAILY_ENTRIES WHERE $COLUMN_LOCAL_DATE = ?",
                    arrayOf(entry.date.toString())
                ).use { if (it.moveToFirst()) it.getLong(0) else 0L }
                val values = ContentValues().apply {
                    put(COLUMN_LOCAL_DATE, entry.date.toString())
                    put(COLUMN_COMPLETED, if (entry.completed) 1 else 0)
                    put(COLUMN_DESCRIPTION, entry.description)
                    put(COLUMN_UPDATED_AT, maxOf(System.currentTimeMillis(), previousStamp + 1L))
                    put(COLUMN_TIME_ZONE, zone.id)
                }
                val inserted = database.writableDatabase.insertWithOnConflict(
                    TABLE_DAILY_ENTRIES,
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE
                )
                check(inserted != -1L) { "The daily record could not be saved." }
            }
            mainHandler.post { onResult(result) }
        }
    }

    /**
     * Performs a two-way merge on the database executor. Existing local history is associated
     * with the first account used on this installation; a different account is blocked to
     * prevent accidentally uploading one person's local records into another account.
     */
    fun syncCloud(
        cloud: DailyTrackerCloud,
        accountId: String,
        onResult: (Result<Int>) -> Unit
    ) {
        // Only one sync runs at a time. A request that arrives meanwhile is remembered and runs once
        // more afterwards, so a burst of saves never queues up a pile of full downloads.
        if (!syncRunning.compareAndSet(false, true)) {
            syncRerun.set(true)
            return
        }
        // Network requests run on a separate queue so a slow connection cannot block
        // local entry reads or saves on the database executor.
        syncExecutor.execute {
            val result = runCatching {
                require(accountId.isNotBlank()) { "A valid account is required to sync." }
                val binding = appContext.getSharedPreferences(CLOUD_BINDING_PREFS, Context.MODE_PRIVATE)
                val existingOwner = binding.getString(CLOUD_OWNER_KEY, null)
                require(existingOwner == null || existingOwner == accountId) {
                    "This device's local history is linked to a different account. No records were synced."
                }

                if (existingOwner == null) {
                    // Persist the account binding before any cloud write so a process death
                    // cannot leave uploaded local history unassociated with its owner.
                    check(binding.edit().putString(CLOUD_OWNER_KEY, accountId).commit()) {
                        "The local account association could not be saved."
                    }
                }

                val localEntries = runOnDatabaseExecutor { readAllCloudEntries() }
                val localByDate = localEntries.associateBy { it.date }
                val mergedEntries = cloud.sync(localEntries)

                runOnDatabaseExecutor {
                    val currentLocalByDate = readAllCloudEntries().associateBy { it.date }
                    val writable = database.writableDatabase
                    writable.beginTransaction()
                    try {
                        for (entry in mergedEntries) {
                            val snapshotEntry = localByDate[entry.date]
                            val currentEntry = currentLocalByDate[entry.date]
                            val shouldApply = when {
                                currentEntry == null -> true
                                currentEntry == snapshotEntry -> true
                                currentEntry.updatedAt < entry.updatedAt -> true
                                else -> false
                            }
                            if (!shouldApply || currentEntry == entry) continue

                            val values = ContentValues().apply {
                                put(COLUMN_LOCAL_DATE, entry.date.toString())
                                put(COLUMN_COMPLETED, if (entry.completed) 1 else 0)
                                put(COLUMN_DESCRIPTION, entry.description)
                                put(COLUMN_UPDATED_AT, entry.updatedAt)
                                put(COLUMN_TIME_ZONE, entry.timeZoneId)
                            }
                            val rowId = writable.insertWithOnConflict(
                                TABLE_DAILY_ENTRIES,
                                null,
                                values,
                                SQLiteDatabase.CONFLICT_REPLACE
                            )
                            check(rowId != -1L) {
                                "A cloud record could not be saved to local storage."
                            }
                        }
                        writable.setTransactionSuccessful()
                    } finally {
                        writable.endTransaction()
                    }
                }

                mergedEntries.size
            }
            syncRunning.set(false)
            mainHandler.post {
                onResult(result)
                // The flag is cleared even when the run failed, so a failure does not retry in a loop.
                if (syncRerun.getAndSet(false) && result.isSuccess) {
                    syncCloud(cloud, accountId, onResult)
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Date notes: short plans or reminders attached to any calendar date. They live only on this
    // device (there is no cloud table for them), and are never touched by the daily-history sync.
    // ---------------------------------------------------------------------------------------------

    /** Notes for every date from [from] to [to], inclusive. */
    fun loadNotes(
        from: LocalDate,
        to: LocalDate,
        onResult: (Result<Map<LocalDate, String>>) -> Unit
    ) {
        executor.execute {
            val result = runCatching {
                val cursor = database.readableDatabase.query(
                    TABLE_DATE_NOTES,
                    arrayOf(COLUMN_LOCAL_DATE, COLUMN_NOTE),
                    "$COLUMN_LOCAL_DATE BETWEEN ? AND ?",
                    arrayOf(from.toString(), to.toString()),
                    null,
                    null,
                    null
                )
                cursor.use {
                    val notes = HashMap<LocalDate, String>()
                    while (it.moveToNext()) {
                        notes[LocalDate.parse(it.getString(0))] = it.getString(1).orEmpty()
                    }
                    notes as Map<LocalDate, String>
                }
            }
            mainHandler.post { onResult(result) }
        }
    }

    /** Saves the note for [date]. Blank text removes the note. */
    fun saveNote(date: LocalDate, text: String, onResult: (Result<Unit>) -> Unit) {
        executor.execute {
            val result = runCatching {
                val trimmed = text.trim()
                require(trimmed.length <= MAX_NOTE_LENGTH) {
                    "A note can be at most $MAX_NOTE_LENGTH characters."
                }
                val writable = database.writableDatabase
                if (trimmed.isEmpty()) {
                    writable.delete(TABLE_DATE_NOTES, "$COLUMN_LOCAL_DATE = ?", arrayOf(date.toString()))
                } else {
                    val values = ContentValues().apply {
                        put(COLUMN_LOCAL_DATE, date.toString())
                        put(COLUMN_NOTE, trimmed)
                        put(COLUMN_UPDATED_AT, System.currentTimeMillis())
                    }
                    val rowId = writable.insertWithOnConflict(
                        TABLE_DATE_NOTES,
                        null,
                        values,
                        SQLiteDatabase.CONFLICT_REPLACE
                    )
                    check(rowId != -1L) { "The note could not be saved." }
                }
                Unit
            }
            mainHandler.post { onResult(result) }
        }
    }

    private fun <T> runOnDatabaseExecutor(block: () -> T): T {
        val task = FutureTask<T> { block() }
        executor.execute(task)
        return task.get()
    }

    private fun readAllCloudEntries(): List<DailyTrackerCloud.CloudEntry> {
        val cursor = database.readableDatabase.query(
            TABLE_DAILY_ENTRIES,
            arrayOf(
                COLUMN_LOCAL_DATE,
                COLUMN_COMPLETED,
                COLUMN_DESCRIPTION,
                COLUMN_UPDATED_AT,
                COLUMN_TIME_ZONE
            ),
            null,
            null,
            null,
            null,
            "$COLUMN_LOCAL_DATE ASC"
        )
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    add(
                        DailyTrackerCloud.CloudEntry(
                            date = LocalDate.parse(it.getString(0)),
                            completed = it.getInt(1) == 1,
                            description = it.getString(2).orEmpty(),
                            updatedAt = it.getLong(3),
                            timeZoneId = it.getString(4).orEmpty().ifBlank { "UTC" }
                        )
                    )
                }
            }
        }
    }

    private class DailyEntryOpenHelper(context: Context) : SQLiteOpenHelper(
        context,
        DATABASE_NAME,
        null,
        DATABASE_VERSION
    ) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABLE_DAILY_ENTRIES (
                    $COLUMN_LOCAL_DATE TEXT NOT NULL PRIMARY KEY,
                    $COLUMN_COMPLETED INTEGER NOT NULL DEFAULT 0,
                    $COLUMN_DESCRIPTION TEXT NOT NULL DEFAULT '',
                    $COLUMN_UPDATED_AT INTEGER NOT NULL,
                    $COLUMN_TIME_ZONE TEXT NOT NULL
                )
                """.trimIndent()
            )
            createNotesTable(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Versioned migrations only add to the schema. Never drop user records as a fallback.
            if (oldVersion < 2) createNotesTable(db)
        }

        private fun createNotesTable(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_DATE_NOTES (
                    $COLUMN_LOCAL_DATE TEXT NOT NULL PRIMARY KEY,
                    $COLUMN_NOTE TEXT NOT NULL,
                    $COLUMN_UPDATED_AT INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    private companion object {
        const val MAX_DESCRIPTION_LENGTH = 5_000
        const val MAX_NOTE_LENGTH = 500
        const val SAVE_DEBOUNCE_MS = 500L
        const val SYNC_DELAY_MS = 6_000L
        const val MIDNIGHT_GRACE_MS = 10 * 60_000L
        const val DATABASE_NAME = "dailytracker.db"
        const val DATABASE_VERSION = 2
        const val TABLE_DAILY_ENTRIES = "daily_entries"
        const val TABLE_DATE_NOTES = "date_notes"
        const val COLUMN_NOTE = "note"
        const val COLUMN_LOCAL_DATE = "local_date"
        const val COLUMN_COMPLETED = "completed"
        const val COLUMN_DESCRIPTION = "description"
        const val COLUMN_UPDATED_AT = "updated_at_epoch_ms"
        const val COLUMN_TIME_ZONE = "time_zone_id"
        const val CLOUD_BINDING_PREFS = "dailytracker_cloud_binding"
        const val CLOUD_OWNER_KEY = "user_id"
    }
}
