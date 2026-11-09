package com.nahsor1234.dailytracker

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyMergeTest {

    private fun entry(day: Int, updatedAt: Long, completed: Boolean = false, text: String = "") =
        DailyTrackerCloud.CloudEntry(
            date = LocalDate.of(2026, 11, day),
            completed = completed,
            description = text,
            updatedAt = updatedAt,
            timeZoneId = "Asia/Kolkata"
        )

    @Test
    fun localOnlyRecordIsUploaded() {
        val result = DailyMerge.merge(local = listOf(entry(1, 10)), remote = emptyList())
        assertEquals(listOf(entry(1, 10)), result.uploads)
        assertEquals(listOf(entry(1, 10)), result.merged)
    }

    @Test
    fun remoteOnlyRecordIsDownloadedNotUploaded() {
        val result = DailyMerge.merge(local = emptyList(), remote = listOf(entry(2, 5)))
        assertTrue(result.uploads.isEmpty())
        assertEquals(listOf(entry(2, 5)), result.merged)
    }

    @Test
    fun newerLocalWins() {
        val result = DailyMerge.merge(
            local = listOf(entry(3, 20, text = "local")),
            remote = listOf(entry(3, 10, text = "cloud"))
        )
        assertEquals("local", result.merged.single().description)
        assertEquals(1, result.uploads.size)
    }

    @Test
    fun newerRemoteWins() {
        val result = DailyMerge.merge(
            local = listOf(entry(4, 10, text = "local")),
            remote = listOf(entry(4, 20, text = "cloud"))
        )
        assertEquals("cloud", result.merged.single().description)
        assertTrue(result.uploads.isEmpty())
    }

    @Test
    fun exactTieKeepsTheCloudRecord() {
        val result = DailyMerge.merge(
            local = listOf(entry(5, 10, text = "local")),
            remote = listOf(entry(5, 10, text = "cloud"))
        )
        assertEquals("cloud", result.merged.single().description)
        assertTrue(result.uploads.isEmpty())
    }

    @Test
    fun mergedIsSortedByDate() {
        val result = DailyMerge.merge(
            local = listOf(entry(9, 1), entry(2, 1)),
            remote = listOf(entry(5, 1))
        )
        assertEquals(listOf(2, 5, 9), result.merged.map { it.date.dayOfMonth })
    }
}
