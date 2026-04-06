package tools.perry.lastwarscanner.model

/**
 * Simple data class representing a player's score for a specific day/category.
 * Used during the OCR parsing process before persistence.
 * @property name The player's name.
 * @property score The score value as a string (to handle formatting).
 * @property day The category or day associated with this score.
 */
data class PlayerScore(
    val name: String,
    val score: String,
    val day: String = "Unknown"
)