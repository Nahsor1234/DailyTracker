# DailyTracker

DailyTracker is a standalone Android app for recording daily progress, reviewing previous days, tracking time, and managing personal reminders.

## Implemented

- Daily Progress screen with a locale-aware monthly calendar.
- Separate visual states for today, selected dates, completed dates, and future dates.
- Current-day completion checkbox and free-text description (up to 5,000 characters).
- Automatic local persistence using SQLite.
- Past records are read-only; future records cannot be edited.
- Calendar dates are stored as local ISO dates. A device time-zone change does not move an existing record to another date.
- Floating pill-shaped navigation between Progress and Timer.
- Stopwatch with start, pause, and reset controls.
- Configurable countdown with common duration presets, pause, resume, and reset.
- Both destinations retain their in-memory screen state while navigating between them.
- Timer calculations use Android's monotonic elapsed-realtime clock to avoid counter drift.
- Reminder scheduling with boot/time-zone rescheduling and notification channels.
- Email/password account creation and sign-in, email verification/resend, password-reset email, and in-app password recovery.
- Encrypted persistent Supabase sessions backed by Android Keystore.
- Local-first, two-way daily-history synchronization with timestamp-based conflict resolution.

## Current implementation stage

Daily Progress, Timer, Reminders, and the first Supabase account/sync workflows are implemented. Daily records remain stored in SQLite and are synchronized with the signed-in user's Supabase daily_progress rows. Sync runs after session restoration, after a successful daily save, and from the Account dialog. Remote-only records are downloaded; the newest updated_at wins, while an exact timestamp tie preserves the server record.

The first account used on an installation is associated with that installation's local history. Signing into a different account does not upload those records to the new account. Signing out does not delete local or cloud records.

The app talks to the Supabase project `tbrnzealznagqednxdem`. The schema is in `supabase/migrations/`; it creates `daily_progress` with row-level security so each user only sees their own rows.

Email verification and password recovery use the Android callback URI shown below. Add this exact URI to Supabase Dashboard → Authentication → URL Configuration → Additional Redirect URLs:

com.nahsor1234.dailytracker://auth/callback

This URI must be allow-listed before signup confirmation or password-recovery links can return to the app. The app also needs normal Supabase email delivery configured for the project.

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

GitHub Actions builds the debug APK, verifies the permanent signing identity, uploads the APK artifact, and publishes a stable GitHub Release for pushes to `main`.

The CI workflow uses Ubuntu 24.04, JDK 17, and the committed Gradle 8.13 Wrapper with distribution checksum verification. It reuses the runner's Android API 35 and Build Tools 35.0.0 packages when present, and caches Gradle state. Same-repository pull requests can populate the cache; fork pull requests remain read-only. Release publishing runs separately with write permission limited to that job.

For a local build, run:

```bash
./gradlew :app:assembleDebug
```

## Data and security notes

- SQLite remains the local source of truth; cloud data is merged into it without replacing the database.
- Supabase access is user-scoped through the existing daily_progress row-level security policies.
- The Android client contains only the Supabase project URL and publishable key. Never embed a service-role or secret key in the APK.
- Session tokens are encrypted with AES-GCM using a non-exportable Android Keystore key.
- Database schema changes must use versioned migrations; existing user records must not be dropped.
