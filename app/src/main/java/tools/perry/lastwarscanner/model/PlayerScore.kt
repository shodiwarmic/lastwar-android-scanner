package tools.perry.lastwarscanner.model

import android.graphics.Rect

/**
 * A single candidate name/score split produced by crash-token recovery.
 * Candidates are ordered by ascending score (rightmost split = smallest score = index 0).
 * The caller should prefer a candidate whose name matches an existing DB record.
 */
data class CrashCandidate(val name: String, val score: String)

/**
 * Simple data class representing a player's score for a specific day/category.
 * Used during the OCR parsing process before persistence.
 * @property name The player's name (primary split if a crash token was detected).
 * @property score The score value as a pure digit string.
 * @property day The category or day associated with this score.
 * @property rowBounds Bounding box of the full row in the captured bitmap (rank → score).
 *   Used by [tools.perry.lastwarscanner.ScreenCaptureService] to crop a row snapshot.
 *   Not persisted to the database.
 * @property candidates Non-empty when the OCR line was a crash token with multiple valid
 *   name/score splits. Ordered ascending by score; [candidates][0] matches [name]/[score].
 *   The consumer should try each candidate against a known roster before committing.
 */
data class PlayerScore(
    val name: String,
    val score: String,
    val day: String = "Unknown",
    val rowBounds: Rect? = null,
    val candidates: List<CrashCandidate> = emptyList()
)
