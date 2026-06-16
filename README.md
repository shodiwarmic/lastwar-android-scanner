# Last War Scanner

[![Build APK](https://github.com/shodiwarmic/lastwar-android-scanner/actions/workflows/build-apk.yml/badge.svg)](https://github.com/shodiwarmic/lastwar-android-scanner/actions/workflows/build-apk.yml)

Last War Scanner is an Android utility designed to automate the collection of alliance ranking data from the game "Last War". It uses high-precision OCR (Optical Character Recognition) to capture player names and scores directly from your screen, compiling them into a sortable pivot table and allowing for easy CSV export.

## Features

- **Automated Screen Scanning**: Uses Media Projection to read ranking data in real-time as you scroll through game lists.
- **Support for All Ranking Types**:
    - **Daily Rankings**: Automatically detects and categorizes scores for Monday through Saturday.
    - **Strength Rankings**: Specifically handles Power, Kills, and Donation categories.
- **Rhythmic Scanning with Visual Feedback**: A high-contrast magenta bar flashes at the top of the screen whenever a snapshot is taken, signaling when it's safe to scroll to the next page.
- **Smart Name Resolution**: Resolves scanned names against your alliance roster and merges OCR variants (e.g. "gustavooo" vs "gustovooo") into a single consistent record.
- **Sync to the Web**: Upload scanned VS, Power, and Kills scores straight to your alliance-manager site, pairing OCR names to roster members on-device.
- **Data Management**:
    - **Live Pivot Table**: View all member scores across all days and categories in a single consolidated view.
    - **Interactive Sorting**: Sort members by name or by specific day/category scores.
    - **CSV Export**: Export your entire database to a formatted CSV file for easy sharing or spreadsheet analysis.
- **Performance Optimized**: Heavy data processing and OCR are handled off-thread to ensure the UI remains smooth and responsive.

## How to Use

1. **Launch the App**: Open Last War Scanner and tap **"Start Screen Scanning"**.
2. **Grant Permissions**:
    - Allow the app to record your screen (Media Projection).
    - Grant **"Display over other apps"** permission to see the visual snapshot indicator.
3. **Open the Game**: Navigate to the ranking screen you wish to scan (e.g., Daily Ranking or Strength Ranking).
4. **Scan Rhythmicly**:
    - Watch for the **Magenta Flash** at the top of your screen.
    - When it flashes, the scanner has captured the current page.
    - Scroll down roughly one full page.
    - Wait for the next flash and repeat until you reach the end of the list.
5. **Export Data**: Return to the app to see your consolidated results. Use the **"Export CSV"** button to share the data.

## Installation

### Prerequisites
- Android 7.0 (API 24) or higher.
- Overlay permission enabled for visual feedback.

### Build from Source
1. Clone the repository **with submodules**: `git clone --recurse-submodules …` (or run `git submodule update --init` after cloning) — the `screen_definitions` assets are a git submodule.
2. Open in Android Studio.
3. Build and deploy to your device.

## Technical Details

- **OCR Engine**: Google ML Kit (specialized models for Latin, Korean, Chinese, Japanese, and Devanagari), with per-channel autocontrast pre-processing for low-contrast rows.
- **Screen Definitions**: YAML layout contract shared with the cloud OCR service via a git submodule.
- **Database**: Room Persistence Library.
- **Concurrency**: Kotlin Coroutines and Flow for reactive data updates.
- **Name Reconciliation**: Resolves OCR names to your alliance roster (alias hierarchy), with Levenshtein fuzzy matching as a fallback.

## License

This project is for personal and alliance management use. Use responsibly and in accordance with the game's terms of service.
