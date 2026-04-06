# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
./gradlew assembleDebug          # Build debug APK
./gradlew assembleRelease        # Build release APK (minification disabled)
./gradlew test                   # Run unit tests
./gradlew connectedAndroidTest   # Run instrumented tests (requires device/emulator)
./gradlew lint                   # Run lint checks
./gradlew clean                  # Clean build artifacts
```

Single test class: `./gradlew test --tests "tools.perry.lastwarscanner.SomeTest"`

## Architecture Overview

**Last War Scanner** is a game utility app that captures alliance ranking screenshots from the "Last War" game via Android's Media Projection API, runs OCR on them, and persists player scores to a Room database for CSV export.

### Data Flow

```
Game Screen → Media Projection (ScreenCaptureService, every 1.5s)
           → OcrProcessor (5 parallel ML Kit recognizers)
           → OcrParser (layout detection + player row extraction)
           → Fuzzy name dedup (Levenshtein) → Room DB → MainActivity (RecyclerView + CSV export)
```

### Key Components

- **`ScreenCaptureService`** — Foreground service that owns the `MediaProjection`, fires a capture runnable on a 1.5s loop, orchestrates OCR, and writes results to Room. Sends broadcast intents to update the UI.
- **`OcrProcessor`** — Runs ML Kit text recognition in parallel across five scripts (Latin, Korean, Chinese Simplified, Japanese, Devanagari) to handle multilingual player names. Merges all recognized text blocks.
- **`OcrParser`** — Takes merged OCR text blocks and a `ScreenLayout` to extract `PlayerScore` objects. Detects which layout is active ("Daily Ranking" vs. "Strength Ranking") via page-signal strings.
- **`ScreenLayout` / `LayoutRegistry`** — Registry of known game screen layouts. Each layout defines column positions and which data fields (name, score, day/category) map to which visual columns.
- **`ImageUtils`** — Color-based tab detection: samples pixel regions for orange (Strength tabs) or white (Daily tabs) to determine which day/category is currently active.
- **`MainActivity`** — Displays a pivot table (members × days/categories) using `ScoreAdapter` + `MemberRow`. Handles MediaProjection permission flow, broadcasts from service, sort controls, and CSV export via FileProvider.
- **`AppDatabase`** / **`PlayerScoreDao`** — Room with KSP. Schema version 2; `fallbackToDestructiveMigration` is on — migrations are not written.

### Fuzzy Name Deduplication

When a new OCR result arrives, the service first tries an exact name lookup, then tries matching by score with a Levenshtein threshold of `min(3, name_length/5 + 1)`. If the incoming name is shorter (higher OCR confidence) it replaces the stored name.

### Module Structure

```
app/src/main/java/tools/perry/lastwarscanner/
├── MainActivity.kt
├── ScreenCaptureService.kt
├── ScoreAdapter.kt
├── model/          # Room entities, DAO, AppDatabase, UI model (MemberRow)
├── ocr/            # OcrProcessor, OcrParser, ScreenLayout
└── image/          # ImageUtils (color-based tab detection)
```

## Tech Stack

- **Language**: Kotlin
- **Min SDK**: 24 (Android 7.0) / Target SDK: 34
- **OCR**: Google ML Kit Text Recognition (base + Korean, Chinese, Japanese, Devanagari bundles)
- **Database**: Room 2.6.1 with KSP (not KAPT)
- **Concurrency**: Kotlin Coroutines + Flow with `SupervisorJob` in the service
- **UI**: RecyclerView with `ListAdapter`/`DiffUtil`, Material Design 3
- **Screen capture**: `MediaProjectionManager` + `ImageReader` / `VirtualDisplay`
- **IPC**: `LocalBroadcastManager`-style explicit broadcasts between service and activity