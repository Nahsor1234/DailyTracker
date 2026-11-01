# DailyTracker

DailyTracker is a standalone Android app for recording daily progress, reviewing previous days, tracking time, and managing personal reminders.

## Implemented

- Daily Progress screen with a locale-aware monthly calendar.
- Separate visual states for today, selected dates, completed dates, and future dates.
- Current-day completion checkbox and free-text description (up to 5,000 characters).
- Automatic local persistence using SQLite.
- Past records are read-only; future records cannot be edited.
- Calendar dates are stored as local ISO dates. A device time-zone change does not move an existing record to another date.

## Current implementation stage

This is the first complete vertical slice: Daily Progress and its local persistence. Countdown/stopwatch, reminders, authentication, and Supabase synchronization remain later stages. The UI reports that cloud synchronization is not configured; daily records do not depend on network access.

## Android configuration

- Kotlin + Jetpack Compose + Material 3
- Minimum SDK: 26
- Compile SDK: 35
- Target SDK: 35 (review against current Play requirements before release)
- JDK: 17
- Android Gradle Plugin: 8.13.2
- Gradle: 8.13
- Kotlin: 2.3.21

Application ID: `com.nahsor1234.dailytracker`

## Build

The GitHub Actions workflow builds the debug APK, verifies the permanent signing identity, uploads the APK artifact, and publishes a GitHub prerelease for pushes to `main`.

For a local build, generate the Gradle wrapper with Gradle 8.13 if it is not already present, then run:

```bash
gradle wrapper --gradle-version 8.13
./gradlew :app:assembleDebug
```

## Data and security notes

- Daily records are stored locally in the app's private SQLite database.
- The app does not yet authenticate users or synchronize records to Supabase.
- No Supabase service-role key or privileged backend secret is embedded in the Android client.
- Database schema changes must use versioned migrations; existing user records must not be dropped.
