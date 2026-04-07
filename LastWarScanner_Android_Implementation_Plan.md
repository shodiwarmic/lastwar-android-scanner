# Last War Scanner — Android Implementation Plan
## Mobile API Bridge Integration

Based on backend commit `418b067`

---

## Project Summary

| | |
|---|---|
| New files | 4 |
| Modified files | 3 |
| New dependencies | None |
| Min SDK | API 24 (unchanged) |
| Phases | 5 |
| Estimated net lines | ~780 |
| Schema changes | None |

The integration adds a focused sync flow on top of the existing OCR scanning engine without altering any current scanning behaviour. If the user never configures a server URL the app behaves exactly as it does today.

---

## Architecture

The Android app already performs OCR locally via ML Kit and stores results in a Room database. The new sync flow adds a thin HTTP layer that submits those structured results to the web app's import pipeline:

```
ScreenCaptureService  →  Room DB  (existing, unchanged)
                                    ↓
                          [NEW] SyncViewModel
                                    ↓
                          [NEW] AllianceApiClient
                                    ↓
POST /api/mobile/preview  →  POST /api/mobile/commit
```

> OCR stays entirely on-device. Only the structured text results (player name + score pairs) cross the network. No images are transmitted.

---

## Phase 1 — Networking & Session Layer

This phase creates the HTTP client and credential storage. All other phases depend on it.

### New file: `network/AllianceApiClient.kt`

A plain-Kotlin HTTP client using `HttpURLConnection`. No new library dependency. Handles all four backend endpoints.

**Public interface**

| Function | Returns | Notes |
|---|---|---|
| `login(url, user, pass)` | `Result<LoginResponse>` | Issues JWT, stores nothing |
| `getMembers()` | `Result<List<MemberSummary>>` | Needed for unresolved picker |
| `preview(request)` | `Result<PreviewResponse>` | Read-only resolution pass |
| `commit(request)` | `Result<CommitResponse>` | Persists data on server |

**Implementation notes**

- All functions are `suspend` functions running on `Dispatchers.IO`.
- Prepend the stored base URL to every path. Strip trailing slash from the URL before concatenation.
- Set `Content-Type: application/json` and `Authorization: Bearer <token>` on every authenticated request.
- Deserialise JSON with `org.json.JSONObject` / `JSONArray` — already available on Android, zero new dependencies.
- Return `Result<T>` (Kotlin stdlib). Wrap network exceptions and non-2xx status codes as `Result.failure()`. Include the HTTP status code in the exception message for error display.
- Timeouts: connect 10 s, read 30 s.
- `week_date` must be formatted as `YYYY-MM-DD` exactly. Use `SimpleDateFormat("yyyy-MM-dd", Locale.US)` — do not use locale-sensitive formatters.

---

### New file: `network/SessionManager.kt`

Manages credential persistence across app restarts.

**Stored values**

| Key | Type | Notes |
|---|---|---|
| `server_url` | String | Base URL, e.g. `https://war.example.com` |
| `jwt_token` | String | Raw JWT string |
| `token_expires_at` | Long | Unix epoch seconds from JWT `exp` claim |
| `username` | String | Display only; not used for auth |

> **Storage requirement:** Use `EncryptedSharedPreferences` (`androidx.security:security-crypto`). The backend team explicitly called this out. Plain `SharedPreferences` is not acceptable for the JWT.

`security-crypto` is already a transitive dependency of several AndroidX libraries. Confirm with `./gradlew dependencies | grep security-crypto` before adding it explicitly to `build.gradle.kts`.

**Public functions**

- `saveSession(url, token, expiresAt, username)` — writes all four values atomically
- `clearSession()` — removes all four values
- `isLoggedIn(): Boolean` — returns true if token is present and `expiresAt` is more than 60 seconds in the future
- `getToken(): String?` — returns raw JWT or null
- `getServerUrl(): String?` — returns base URL or null

---

## Phase 2 — Data Models

Kotlin data classes mirroring the backend JSON contracts. No Room annotations — these are network models only.

### New file: `network/MobileModels.kt`

**Request models**

| Class | Fields |
|---|---|
| `LoginRequest` | `username: String, password: String` |
| `ScanEntry` | `name: String, score: Long, category: String` |
| `PreviewRequest` | `week_date: String, entries: List<ScanEntry>` |
| `CommitRecord` | `member_id: Int, original_name: String, category: String, score: Long` |
| `AliasMapping` | `failed_alias: String, member_id: Int, category: String` |
| `CommitRequest` | `week_date: String, records: List<CommitRecord>, save_aliases: List<AliasMapping>` |

**Response models**

| Class | Fields |
|---|---|
| `LoginResponse` | `token, expires_at, user_id, username, member_id?, manage_vs, manage_members` |
| `MemberSummary` | `id: Int, name: String, rank: String` |
| `PreviewMatch` | `original_name, matched_member?, match_type, category, score` |
| `PreviewResponse` | `matched, unresolved, all_members, total_submitted, total_matched, total_unresolved` |
| `CommitResponse` | `message, vs_records_saved, power_records_saved, aliases_saved, errors: List<String>` |

**Category constants**

```kotlin
object Category {
    val VS_DAYS = listOf("monday","tuesday","wednesday","thursday","friday","saturday")
    const val POWER = "power"
}
```

**Day name mapping**

The Android app uses short display names from `OcrParser` / `LayoutRegistry`. Map them to backend category strings before building any request:

| Android name | API category | Notes |
|---|---|---|
| `Mon` | `monday` | |
| `Tues` | `tuesday` | |
| `Wed` | `wednesday` | |
| `Thur` | `thursday` | |
| `Fri` | `friday` | |
| `Sat` | `saturday` | |
| `Power` | `power` | Routes to `power_history` on server, not `vs_points` |
| `Kills` | — skip — | No mobile endpoint; omit from sync payload |
| `Donation` | — skip — | No mobile endpoint; omit from sync payload |

> Kills and Donation categories have no corresponding commit path in the backend API. Filter them out when building the `PreviewRequest`. The data remains in the local Room DB and can be manually entered on the web if needed.

---

## Phase 3 — ViewModel & Business Logic

### New file: `sync/SyncViewModel.kt`

An `AndroidViewModel` that drives the entire sync flow. The UI observes `LiveData`/`StateFlow` from this class and never calls the API client directly.

**State machine**

```kotlin
sealed class SyncState {
    object Idle : SyncState()
    object LoadingPreview : SyncState()
    data class AwaitingReview(
        val preview: PreviewResponse,
        val resolutions: Map<String, Int>  // original_name -> member_id
    ) : SyncState()
    object Committing : SyncState()
    data class Success(val response: CommitResponse) : SyncState()
    data class Error(val message: String, val canRetry: Boolean) : SyncState()
}
```

**Key functions**

| Function | Responsibility |
|---|---|
| `startPreview(weekDate, day)` | Reads Room DB for the given day, maps to `ScanEntry` list, calls preview endpoint, transitions state to `AwaitingReview` or `Error` |
| `updateResolution(name, id)` | Called when user picks a member for an unresolved name. Updates the resolutions map within `AwaitingReview` state. |
| `commit(saveAliasChoices)` | Merges auto-matched and manually resolved records, calls commit endpoint, transitions to `Success` or `Error`. |
| `reset()` | Returns state to `Idle`. Called on back navigation or after `Success` is acknowledged. |

**Data loading from Room**

When `startPreview` is called, read from the existing `player_scores` table:

- Query all entries for the requested day using `PlayerScoreDao.getLatestScoresForDay(day: String)`. This query does not exist yet — add it in Phase 5.
- Filter out entries with category `Kills` or `Donation` before building the request.
- If the result set is empty, transition to `Error("No scan data found for $day", canRetry = false)` without making a network call.

**Week date calculation**

```kotlin
fun currentWeekDate(): String {
    val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    val dow = cal.get(Calendar.DAY_OF_WEEK)
    val daysFromMonday = if (dow == Calendar.SUNDAY) 6 else dow - Calendar.MONDAY
    cal.add(Calendar.DAY_OF_YEAR, -daysFromMonday)
    return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
}
```

> The server validates `week_date` strictly. Do not allow the user to type a custom date in the initial release. A date picker can be added later if needed.

**Error handling policy**

| Condition | `canRetry` | Message |
|---|---|---|
| 401 from any endpoint | false — re-auth required | Session expired. Please log in again. |
| 403 (no manage_vs) | false | Your account does not have VS import permission. |
| 400 bad week_date | false | Date format error — please reinstall or report this bug. |
| Network timeout | true | Connection timed out. Check server URL and try again. |
| 200 with `errors[]` non-empty | false | Import completed with partial errors. Show errors list. |
| 5xx | true | Server error (HTTP $code). Try again shortly. |

---

## Phase 4 — UI

Three screens and one dialog. All use existing Material3 components already in the project dependencies.

### New file: `sync/ServerSetupActivity.kt`

Shown the first time the user taps "Sync to Web" and whenever `SessionManager.isLoggedIn()` returns false.

**Layout** (`res/layout/activity_server_setup.xml`)

- Server URL — `EditText`, `inputType=textUri`, hint: `https://your-alliance-server.com`
- Username — `EditText`, `inputType=text`
- Password — `EditText`, `inputType=textPassword`
- Connect button — full width, disabled until all three fields are non-empty
- Progress indicator — hidden until Connect is tapped
- Error `TextView` — hidden until a login failure occurs

**Behaviour**

- On Connect tap: disable all inputs, show progress, call `AllianceApiClient.login()`.
- On success: call `SessionManager.saveSession()`, `finish()` and let the caller proceed to `SyncActivity`.
- On 403 (`force_password_change`): show "Your password must be changed before using the mobile app. Please visit the web interface."
- On 401: show "Invalid username or password."
- On network error: show error, re-enable inputs for retry.
- Strip trailing slash from the URL before saving. Prepend `https://` if no scheme is present.

---

### New file: `sync/SyncActivity.kt`

The main sync screen. Launched from `MainActivity`. Observes `SyncViewModel` state.

**Screen 1 — Day selection (`state = Idle`)**

- Shows the active tab detected during the last scan session (read from `SharedPreferences` key `"last_active_day"`).
- A spinner lets the user override the day. Options: Mon, Tues, Wed, Thur, Fri, Sat, Power.
- Shows the computed week date as a non-editable label: `"Week of YYYY-MM-DD"`.
- Start Sync button calls `SyncViewModel.startPreview()`.
- A small "Logged in as [username] on [server]" line with a Log Out link.

**Screen 2 — Review (`state = AwaitingReview`)**

- `RecyclerView` with two sections: Matched and Needs Review (unresolved).
- Matched items show name, detected category, and score with a green check. Non-interactive.
- Unresolved items show the OCR name, score, and a member picker. Tapping the picker opens a filtered `AlertDialog` populated from `preview.all_members`. When the user picks a member, `SyncViewModel.updateResolution()` is called.
- Each unresolved item has an alias save checkbox (default: checked, saves as OCR alias) once a member is selected.
- A Submit button at the bottom calls `SyncViewModel.commit()`. Always enabled — the user can submit with some names still unresolved; they will be skipped server-side.
- A `"X matched, Y need review"` summary line above the list.

**Screen 3 — Result (`state = Success` or `Error`)**

- Success: green check, summary text from `CommitResponse.message`, counts for VS records, power records, and aliases saved.
- If `CommitResponse.errors` is non-empty, show a collapsible "Partial errors" section.
- Error: message from `SyncState.Error` with a Retry button if `canRetry` is true.
- Both screens show a Done button that calls `SyncViewModel.reset()` and finishes the activity.

---

### Modify: `MainActivity.kt`

Two small additions only.

**Add Sync to Web button**

- Add alongside Export CSV and Clear History, same outlined style.
- On click: if `SessionManager.isLoggedIn()` → start `SyncActivity`; else → start `ServerSetupActivity`.

**Persist last active day**

When the OCR broadcast receiver fires and updates `activeTabName`, also write it to plain `SharedPreferences` under key `"last_active_day"`. `SyncActivity` reads this to pre-populate the day selector. Plain `SharedPreferences` is fine here — the day name is not sensitive.

---

### Member picker dialog

Used within the Review screen for unresolved names. Implemented as a reusable function.

- `AlertDialog` with a search `EditText` and a filtered `ListView`.
- Populated from `all_members` returned in the preview response, sorted alphabetically.
- Filters by name substring, case-insensitive, in real time.
- A "Skip / Do Not Import" option at the top of the list clears any previous selection.

---

## Phase 5 — Build & Manifest Changes

### `build.gradle.kts`

Add if `security-crypto` is not already present transitively:

```kotlin
implementation("androidx.security:security-crypto:1.1.0-alpha06")
```

Verify first with `./gradlew dependencies | grep security-crypto`.

---

### `AndroidManifest.xml`

```xml
<uses-permission android:name="android.permission.INTERNET" />

<activity android:name=".sync.ServerSetupActivity"
    android:windowSoftInputMode="adjustResize" />

<activity android:name=".sync.SyncActivity"
    android:windowSoftInputMode="adjustResize" />
```

> `INTERNET` is a normal-level permission — no runtime request needed. It must be declared or all network calls will silently fail on Android 6+.

---

### `PlayerScoreDao.kt`

Add one new query:

```kotlin
@Query("""
    SELECT name, MAX(score) as score, day FROM player_scores
    WHERE day = :day
    GROUP BY name
    ORDER BY name ASC
""")
fun getLatestScoresForDay(day: String): List<PlayerScoreEntity>
```

Returns one row per player for the given day, taking the highest score observed. No migration needed.

---

## Delivery Order

Each phase can be reviewed and merged independently. No phase breaks the existing app.

| # | Phase | Depends on |
|---|---|---|
| 1 | Models (`MobileModels.kt`) | Nothing |
| 2 | Session layer (`SessionManager.kt`) | Phase 1 |
| 3 | API client (`AllianceApiClient.kt`) | Phases 1 & 2 |
| 4 | ViewModel (`SyncViewModel.kt`) + DAO query | Phases 1–3 |
| 5 | UI (`ServerSetupActivity` + `SyncActivity` + `MainActivity` changes) | Phases 1–4 |
| 6 | Build & Manifest | Phase 5 |

---

## Implementation Notes

### No existing HTTP client
The current app has zero network code. Do not add OkHttp or Retrofit — `HttpURLConnection` keeps the dependency count flat and is sufficient for four simple endpoints. Migrating to OkHttp in a future release is straightforward if needed.

### Partial success is the normal case
The backend commit endpoint returns HTTP 200 even when some records were skipped. Always check `CommitResponse.errors` after a 200 response. A non-empty errors list should be surfaced to the user — do not silently ignore it.

### Token expiry handling
The JWT is valid for 7 days. Recommended approach:

1. Before every API call, check `SessionManager.isLoggedIn()`.
2. If false, redirect to `ServerSetupActivity` before making the call.
3. If the server returns 401 despite the local check passing (clock skew, server-side issue), clear the session and redirect to `ServerSetupActivity`.

Do not silently retry with stored credentials on a 401 — re-authentication requires user interaction.

### Kills and Donation categories
The `ScreenCaptureService` can detect Kills and Donation from Strength Rankings. These must be filtered from the sync payload — the backend has no commit path for them in the mobile API. Suppress them silently and show a note in the UI if the user tries to sync a Strength Ranking session: `"Power scores will sync. Kills and Donation are not yet supported via mobile sync."`

### SSL / HTTPS
`HttpURLConnection` rejects self-signed certificates by default — this is correct behaviour for production. If testing against a local server with a self-signed cert, use a test network security config. Do not ship any code that disables certificate validation globally.

### Scan data currency
The ViewModel reads from Room at the moment `startPreview()` is called. If the OCR service is actively scanning while the user opens the sync flow, new rows may arrive in Room after the preview response is returned. This is acceptable — the commit uses the snapshot taken at preview time. Do not attempt to refresh local data between preview and commit.

### Server URL usability
Most alliance members are not developers. Be forgiving on input:

- Accept `http://` and `https://`. Warn but do not block `http://`.
- Silently strip trailing slashes.
- If the user omits the scheme entirely, prepend `https://`.
- Validate URL syntax before making any network call.

### week_date must be YYYY-MM-DD
The backend returns 400 for any other format. Use `Locale.US` in `SimpleDateFormat` unconditionally — device locale settings can change the separator or digit format. This is the most common source of silent date-related bugs in Android network code.

### EncryptedSharedPreferences initialisation
`EncryptedSharedPreferences.create()` can throw on first launch on some devices if the KeyStore is unavailable. Wrap initialisation in a try-catch and fall back to a descriptive error screen rather than crashing.

### Existing scanning is unaffected
The OCR service, Room database, main activity list, sorting, and CSV export are untouched. The sync feature is purely additive.

---

## Testing Checklist

Items marked `[Unit]` can be tested without a device. Items marked `[Device]` require a running Android instance connected to the server.

### Phase 1 & 2 — Networking & Session
- [ ] `[Unit]` `SessionManager.isLoggedIn()` returns false when no token is stored
- [ ] `[Unit]` `SessionManager.isLoggedIn()` returns false when stored token is expired
- [ ] `[Unit]` `SessionManager.isLoggedIn()` returns true with valid future expiry
- [ ] `[Device]` `AllianceApiClient.login()` returns `LoginResponse` for correct credentials
- [ ] `[Device]` `AllianceApiClient.login()` returns failure for wrong password
- [ ] `[Device]` `AllianceApiClient.login()` returns 403 result for `force_password_change` user
- [ ] `[Device]` Token is stored in `EncryptedSharedPreferences` (verify via App Inspection — the raw file should be unreadable)

### Phase 3 — ViewModel
- [ ] `[Unit]` `currentWeekDate()` returns Monday of the current week in `YYYY-MM-DD` format
- [ ] `[Unit]` `startPreview()` transitions to `Error` when local Room DB has no data for requested day
- [ ] `[Unit]` `updateResolution()` correctly updates the resolutions map
- [ ] `[Unit]` Day name mapping covers all six VS days and power correctly
- [ ] `[Unit]` Kills and Donation are excluded from preview request payload
- [ ] `[Device]` `startPreview()` transitions to `AwaitingReview` with correct matched/unresolved split
- [ ] `[Device]` `commit()` transitions to `Success` with valid data
- [ ] `[Device]` `commit()` surfaces partial errors from `CommitResponse.errors`
- [ ] `[Device]` 401 response transitions to `Error` with `canRetry = false`

### Phase 4 — UI
- [ ] `[Device]` Tapping Sync to Web with no session opens `ServerSetupActivity`
- [ ] `[Device]` Successful login opens `SyncActivity`
- [ ] `[Device]` Log Out link clears session and returns to `ServerSetupActivity` on next sync
- [ ] `[Device]` Unresolved member picker filters by search text in real time
- [ ] `[Device]` Selecting a member for an unresolved entry enables the alias checkbox
- [ ] `[Device]` Submitting with some names still unresolved completes without crashing
- [ ] `[Device]` Success screen shows correct counts from `CommitResponse`
- [ ] `[Device]` Error screen shows Retry button only when `canRetry = true`

### Regression
- [ ] `[Device]` OCR scanning starts and stops as before
- [ ] `[Device]` Player list populates correctly after a scan
- [ ] `[Device]` Sort by column headers works
- [ ] `[Device]` Export CSV produces a valid file
- [ ] `[Device]` Clear History deletes all rows

---

## Effort Estimate

| File | Est. lines | Notes |
|---|---|---|
| `MobileModels.kt` | ~60 | Data classes only |
| `SessionManager.kt` | ~80 | Including `EncryptedSharedPreferences` boilerplate |
| `AllianceApiClient.kt` | ~180 | Four endpoints + error handling |
| `SyncViewModel.kt` | ~120 | State machine + DAO integration |
| `ServerSetupActivity.kt` + layout | ~90 | Simple form + login call |
| `SyncActivity.kt` + layout | ~200 | Three-screen flow + `RecyclerView` adapter |
| `MainActivity.kt` changes | ~25 | One button, one `SharedPreferences` write |
| `PlayerScoreDao.kt` addition | ~10 | One query |
| Build / Manifest changes | ~15 | Permission + activity registration |
| **Total** | **~780** | **Across 8 files** |

> Line count is higher than the original 590 estimate due to Kotlin verbosity in UI adapters and `EncryptedSharedPreferences` boilerplate. Core logic is ~400 lines; the remainder is Android scaffolding.
