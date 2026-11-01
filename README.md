# DailyTracker

A standalone Android application for daily progress records, countdown and stopwatch sessions, and personal reminders.

## Current repository state

This repository starts with the Android project foundation only. Product screens and feature implementations are intentionally not included yet; UI work will begin only when explicitly requested.

## Android configuration

- Kotlin
- Jetpack Compose
- Material 3 dependency baseline
- Minimum SDK: 26
- Compile SDK: 35
- Target SDK: 35 (revisit against current Google Play requirements before release)
- JDK: 17
- Android Gradle Plugin: 8.13.2
- Gradle: 8.13
- Kotlin: 2.3.21

The package/application ID is initially set to `com.nahsor1234.dailytracker`. Treat this as a project setup assumption and confirm before publishing.

## Create the Gradle wrapper

The wrapper is intentionally not committed in this scaffold. With Gradle 8.13 installed, run:

```bash
gradle wrapper --gradle-version 8.13
```

Then open the repository in Android Studio and sync Gradle. Ensure JDK 17 and Android SDK Platform 35 are installed.

## Build

After generating the wrapper:

```bash
./gradlew :app:assembleDebug
```

Build status is unverified until this command is run in an Android development environment.

## Scope notes

- No GitHub Actions or CI workflow files are included.
- No Supabase credentials or privileged secrets belong in the Android client.
- Persistence, synchronization, authentication, and product UI will be implemented in separately reviewed stages.
