package tools.perry.lastwarscanner.sync

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tools.perry.lastwarscanner.model.AppDatabase
import tools.perry.lastwarscanner.network.AliasMapping
import tools.perry.lastwarscanner.network.AllianceApiClient
import tools.perry.lastwarscanner.network.CommitRecord
import tools.perry.lastwarscanner.network.CommitRequest
import tools.perry.lastwarscanner.network.CommitResponse
import tools.perry.lastwarscanner.network.DayMapping
import tools.perry.lastwarscanner.network.PreviewRequest
import tools.perry.lastwarscanner.network.PreviewResponse
import tools.perry.lastwarscanner.network.ScanEntry
import tools.perry.lastwarscanner.network.SessionManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

// ── State machine ─────────────────────────────────────────────────────────────

sealed class SyncState {
    object Idle : SyncState()
    object LoadingPreview : SyncState()
    data class AwaitingReview(
        val preview: PreviewResponse,
        /** Maps original_name → manually selected member_id. */
        val resolutions: Map<String, Int>,
        val weekDate: String
    ) : SyncState()
    object Committing : SyncState()
    data class Success(val response: CommitResponse) : SyncState()
    data class Error(val message: String, val canRetry: Boolean) : SyncState()
}

// ── ViewModel ─────────────────────────────────────────────────────────────────

class SyncViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.getDatabase(app)
    val session = SessionManager(app)
    private val api = AllianceApiClient(session)

    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state.asStateFlow()

    /**
     * Reads Room for the given display day (e.g. "Mon"), filters out skipped
     * categories, maps to [ScanEntry], and calls /api/mobile/preview.
     *
     * [day] must be a key from [DayMapping.toBackendKey].
     */
    fun startPreview(weekDate: String, day: String) {
        viewModelScope.launch {
            _state.value = SyncState.LoadingPreview
            val category = DayMapping.toBackendKey[day]
            if (category == null) {
                _state.value = SyncState.Error("Unknown day: $day", canRetry = false)
                return@launch
            }

            val entries = withContext(Dispatchers.IO) {
                db.playerScoreDao().getLatestScoresForDay(day)
                    .filter { it.day !in DayMapping.skipped }
                    .map { ScanEntry(name = it.name, score = it.score, category = category) }
            }

            if (entries.isEmpty()) {
                _state.value = SyncState.Error(
                    "No scan data found for $day",
                    canRetry = false
                )
                return@launch
            }

            val result = api.preview(PreviewRequest(weekDate = weekDate, entries = entries))
            result.fold(
                onSuccess = { preview ->
                    _state.value = SyncState.AwaitingReview(
                        preview = preview,
                        resolutions = emptyMap(),
                        weekDate = weekDate
                    )
                },
                onFailure = { e -> _state.value = mapError(e) }
            )
        }
    }

    /**
     * Called when the user picks a member for an unresolved entry.
     * Updates the resolutions map in the current [SyncState.AwaitingReview] state.
     */
    fun updateResolution(originalName: String, memberId: Int) {
        val current = _state.value as? SyncState.AwaitingReview ?: return
        _state.value = current.copy(
            resolutions = current.resolutions + (originalName to memberId)
        )
    }

    /**
     * Builds the [CommitRequest] from the current state and posts it.
     * Unresolved entries without a manual resolution are skipped (server accepts partial).
     * A 200 with non-empty [CommitResponse.errors] is surfaced as [SyncState.Success], not error.
     */
    fun commit(saveAliasChoices: List<AliasMapping>) {
        val current = _state.value as? SyncState.AwaitingReview ?: return
        viewModelScope.launch {
            _state.value = SyncState.Committing
            val records = buildCommitRecords(current)
            val request = CommitRequest(
                weekDate = current.weekDate,
                records = records,
                saveAliases = saveAliasChoices
            )
            val result = api.commit(request)
            result.fold(
                onSuccess = { _state.value = SyncState.Success(it) },
                onFailure = { e -> _state.value = mapError(e) }
            )
        }
    }

    /** Returns state to [SyncState.Idle]. Call on back navigation or after Success is acknowledged. */
    fun reset() {
        _state.value = SyncState.Idle
    }

    // ── Week-date calculation ─────────────────────────────────────────────────

    /**
     * Returns the ISO date (YYYY-MM-DD) of the Monday that starts the current
     * game week, computed in UTC so week boundaries are device-locale–independent.
     */
    fun currentWeekDate(): String {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        val daysFromMonday = if (dow == Calendar.SUNDAY) 6 else dow - Calendar.MONDAY
        cal.add(Calendar.DAY_OF_YEAR, -daysFromMonday)
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(cal.time)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildCommitRecords(state: SyncState.AwaitingReview): List<CommitRecord> {
        val records = mutableListOf<CommitRecord>()
        // Auto-matched entries
        for (match in state.preview.matched) {
            val member = match.matchedMember ?: continue
            records += CommitRecord(
                memberId = member.id,
                originalName = match.originalName,
                category = match.category,
                score = match.score
            )
        }
        // Manually resolved entries
        for (match in state.preview.unresolved) {
            val memberId = state.resolutions[match.originalName] ?: continue
            records += CommitRecord(
                memberId = memberId,
                originalName = match.originalName,
                category = match.category,
                score = match.score
            )
        }
        return records
    }

    private fun mapError(e: Throwable): SyncState.Error {
        return when (e) {
            is AllianceApiClient.HttpException -> when (e.code) {
                401 -> SyncState.Error(
                    "Session expired. Please log in again.",
                    canRetry = false
                )
                403 -> SyncState.Error(
                    "Your account does not have VS import permission.",
                    canRetry = false
                )
                400 -> SyncState.Error(
                    "Date format error — please reinstall or report this bug.",
                    canRetry = false
                )
                in 500..599 -> SyncState.Error(
                    "Server error (HTTP ${e.code}). Try again shortly.",
                    canRetry = true
                )
                else -> SyncState.Error(e.message ?: "Request failed", canRetry = false)
            }
            else -> SyncState.Error(
                "Connection timed out. Check server URL and try again.",
                canRetry = true
            )
        }
    }
}
