package com.nahsor1234.dailytracker

/**
 * The rule used to merge this device's daily records with the cloud copy. It is kept free of any
 * Android or network code so it can be unit-tested.
 *
 * For each date the record with the newest updated_at wins. An exact tie keeps the cloud record.
 */
internal object DailyMerge {

    data class Result(
        /** Every date, with the winning record for each, oldest date first. */
        val merged: List<DailyTrackerCloud.CloudEntry>,
        /** Local records that are newer than (or missing from) the cloud and must be uploaded. */
        val uploads: List<DailyTrackerCloud.CloudEntry>
    )

    fun merge(
        local: List<DailyTrackerCloud.CloudEntry>,
        remote: List<DailyTrackerCloud.CloudEntry>
    ): Result {
        val remoteByDate = remote.associateBy { it.date }
        val merged = remoteByDate.toMutableMap()
        val uploads = mutableListOf<DailyTrackerCloud.CloudEntry>()
        for (entry in local) {
            val cloud = remoteByDate[entry.date]
            if (cloud == null || entry.updatedAt > cloud.updatedAt) {
                uploads += entry
                merged[entry.date] = entry
            }
        }
        return Result(
            merged = merged.values.sortedBy { it.date },
            uploads = uploads.sortedBy { it.date }
        )
    }
}
