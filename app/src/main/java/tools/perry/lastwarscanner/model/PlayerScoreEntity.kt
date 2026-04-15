package tools.perry.lastwarscanner.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents a single score entry for a player in the database.
 * @property id The unique identifier for this entry (auto-generated).
 * @property name The player's name.
 * @property score The numeric score value.
 * @property day The category or day associated with this score (e.g., "Mon", "Power").
 * @property timestamp The time when this score was captured.
 * @property rowSnapshotPath Absolute path to a JPEG crop of the player's row in the original
 *   screenshot (rank → score). Used to display a visual reference during manual upload review.
 *   Null if no snapshot was captured.
 */
@Entity(tableName = "player_scores")
data class PlayerScoreEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val score: Long,
    val day: String = "Unknown",
    val timestamp: Long = System.currentTimeMillis(),
    val rowSnapshotPath: String? = null
)
