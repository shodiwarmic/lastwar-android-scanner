package tools.perry.lastwarscanner.model

import android.graphics.Rect

/**
 * Simple data class representing a player's score for a specific day/category.
 * Used during the OCR parsing process before persistence.
 * @property name The player's name.
 * @property score The score value as a string (to handle formatting).
 * @property day The category or day associated with this score.
 * @property rowBounds Bounding box of the full row in the captured bitmap (rank → score).
 *   Used by [tools.perry.lastwarscanner.ScreenCaptureService] to crop a row snapshot.
 *   Not persisted to the database.
 */
data class PlayerScore(
    val name: String,
    val score: String,
    val day: String = "Unknown",
    val rowBounds: Rect? = null
)
