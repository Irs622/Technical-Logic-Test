# Technology Stack

## Core
- **Language:** Kotlin 1.9.24 / 2.0+
- **Build Tool:** Gradle (Kotlin DSL - `.gradle.kts`)
- **Android Gradle Plugin (AGP):** 8.3+

## UI & Presentation
- **Jetpack Compose:** Compose BOM (Material 3, Foundation, UI tooling)
- **Lifecycle:** `androidx.lifecycle:lifecycle-runtime-compose`
- **Activity:** `androidx.activity:activity-compose`

## Architecture & Data
- **Coroutines & Flow:** `org.jetbrains.kotlinx:kotlinx-coroutines-android`
- **Architecture:** Unidirectional Data Flow (UDF) + MVVM / MVI
- **Persistence:** Room Database / Jetpack DataStore
- **Networking:** Retrofit / OkHttp / Ktor Client
