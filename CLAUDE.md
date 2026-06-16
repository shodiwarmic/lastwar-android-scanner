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
           → enhanceForOcr (per-channel autocontrast; matches the cloud OCR service)
           → OcrProcessor (5 parallel ML Kit recognizers)
           → OcrParser (YAML screen-definition match + player row extraction)
           → roster-keyed cross-frame dedup + score-sanity → Room DB
           → MainActivity (RecyclerView + CSV export) / SyncActivity (upload to alliance manager)
```

### Key Components

- **`ScreenCaptureService`** — Foreground service that owns the `MediaProjection`, fires a capture runnable on a 1.5s loop, orchestrates OCR, and writes results to Room. Sends broadcast intents to update the UI.
- **`OcrProcessor`** — Runs ML Kit text recognition in parallel across five scripts (Latin, Korean, Chinese Simplified, Japanese, Devanagari) to handle multilingual player names. Merges all recognized text blocks.
- **`OcrParser`** — Takes merged OCR text blocks and the active `ScreenLayout` to extract `PlayerScore` objects. The layout is chosen by matching `page_signals` (and rejecting `negative_signals`); rows use the `score_anchored` strategy, matching name lines to score anchors by vertical **overlap** (not containment — name text is taller than the score digits).
- **`ScreenLayout` / `ScreenDefinitionLoader`** — Layouts are YAML in the `screen_definitions` **git submodule** (`app/src/main/assets/screen_definitions`, shared with the cloud `lastwar-ocr-service`), loaded by `ScreenDefinitionLoader`; `ConstantsLoader` reads shared `constants.yaml`. Each layout defines page/tab signals, column x-ranges, and row-clustering params. (The old hardcoded `LayoutRegistry` was removed.)
- **`ImageUtils`** — Color-based tab detection: samples pixel regions for orange (Strength tabs) or white (Daily tabs) to determine which day/category is currently active.
- **`MainActivity`** — Displays a pivot table (members × days/categories) using `ScoreAdapter` + `MemberRow`. Handles MediaProjection permission flow, broadcasts from service, sort controls, and CSV export via FileProvider.
- **`AppDatabase`** / **`PlayerScoreDao`** — Room with KSP. Schema version 2; `fallbackToDestructiveMigration` is on — migrations are not written.

### Cross-frame deduplication

A scroll-through captures each player across several overlapping frames, so the service collapses repeats to one row. Each parsed row resolves to a **roster member** as its canonical identity (`RosterAliasResolver`: exact → personal/global/OCR alias, then a tightened unique-fuzzy match) and is stored under that member's name, so two garbled OCR reads of the same person merge under one key in the `GROUP BY name` sync query. `uniqueRosterMatch` snaps only on a single unambiguous close match — biased toward a visible duplicate over a silently-dropped player. Names matching no roster member fall back to legacy score-match + Levenshtein (`min(3, len/5+1)`). A per-frame **score-sanity** guard skips digit-inflation misreads (a score many times the row above it on the descending leaderboard).

**OCR preprocessing:** `enhanceForOcr` applies per-channel `autocontrast(cutoff=1)` to the OCR copy (original kept for colour-based tab detection), matching the cloud OCR service so both consumers read low-contrast coloured rows (top-3 ranks, pinned self-row) the same way. Do **not** grayscale — it erases the blue-channel separation that makes those rows readable. See the screen_definitions Consumer Contract "Pre-OCR enhancement".

### Module Structure

```
app/src/main/java/tools/perry/lastwarscanner/
├── MainActivity.kt
├── ScreenCaptureService.kt   # capture loop, OCR orchestration, enhanceForOcr, cross-frame dedup
├── ScoreAdapter.kt
├── model/          # Room entities, DAO, AppDatabase, UI model (MemberRow)
├── ocr/            # OcrProcessor, OcrParser, ScreenLayout, ScreenDefinitionLoader,
│                   #   ConstantsLoader, RosterAliasResolver
├── network/        # AllianceApiClient, MobileModels, SessionManager, RosterCache (alliance-manager API)
├── sync/           # SyncActivity, SyncViewModel, ReviewAdapter, ServerSetupActivity (upload flow)
└── image/          # ImageUtils (color-based tab detection)

app/src/main/assets/screen_definitions/   # YAML screen-definition contract (git submodule)
```

> First clone: run `git submodule update --init` — the `screen_definitions` assets are a submodule and are packaged into the APK.

## Tech Stack

- **Language**: Kotlin
- **Min SDK**: 24 (Android 7.0) / Target SDK: 34
- **OCR**: Google ML Kit Text Recognition (base + Korean, Chinese, Japanese, Devanagari bundles)
- **Database**: Room 2.6.1 with KSP (not KAPT)
- **Concurrency**: Kotlin Coroutines + Flow with `SupervisorJob` in the service
- **UI**: RecyclerView with `ListAdapter`/`DiffUtil`, Material Design 3
- **Screen capture**: `MediaProjectionManager` + `ImageReader` / `VirtualDisplay`
- **IPC**: `LocalBroadcastManager`-style explicit broadcasts between service and activity
- **Screen definitions**: YAML contract loaded from the `screen_definitions` git submodule, shared with the cloud OCR service (`lastwar-ocr-service`)
- **CI**: GitHub Actions builds the debug APK on push/PR to `main` (`.github/workflows/build-apk.yml`)