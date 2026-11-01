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
    private val mainHandler = Handler(Looper.getMainLooper())

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

    fun saveEntry(entry: DailyEntry, onResult: (Result<Unit>) -> Unit) {
        executor.execute {
            val result = runCatching {
                require(entry.date == LocalDate.now(ZoneId.systemDefault())) {
                    "Only the current local date can be edited."
                }
                require(entry.description.length <= MAX_DESCRIPTION_LENGTH) {
                    "The daily description exceeds the 5,000-character limit."
                }
                val values = ContentValues().apply {
                    put(COLUMN_LOCAL_DATE, entry.date.toString())
                    put(COLUMN_COMPLETED, if (entry.completed) 1 else 0)
                    put(COLUMN_DESCRIPTION, entry.description)
                    put(COLUMN_UPDATED_AT, System.currentTimeMillis())
                    put(COLUMN_TIME_ZONE, ZoneId.systemDefault().id)
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
        executor.execute {
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

                val localEntries = readAllCloudEntries()
                val mergedEntries = cloud.sync(localEntries)
                val localByDate = localEntries.associateBy { it.date }

                val writable = database.writableDatabase
                writable.beginTransaction()
                try {
                    for (entry in mergedEntries) {
                        if (localByDate[entry.date] == entry) continue
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

                mergedEntries.size
            }
            mainHandler.post { onResult(result) }
        }
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
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Add versioned migrations before changing this schema.
            // Never drop user records as an upgrade fallback.
        }
    }

    private companion object {
        const val MAX_DESCRIPTION_LENGTH = 5_000
        const val DATABASE_NAME = "dailytracker.db"
        const val DATABASE_VERSION = 1
        const val TABLE_DAILY_ENTRIES = "daily_entries"
        const val COLUMN_LOCAL_DATE = "local_date"
        const val COLUMN_COMPLETED = "completed"
        const val COLUMN_DESCRIPTION = "description"
        const val COLUMN_UPDATED_AT = "updated_at_epoch_ms"
        const val COLUMN_TIME_ZONE = "time_zone_id"
        const val CLOUD_BINDING_PREFS = "dailytracker_cloud_binding"
        const val CLOUD_OWNER_KEY = "user_id"
    }
}
