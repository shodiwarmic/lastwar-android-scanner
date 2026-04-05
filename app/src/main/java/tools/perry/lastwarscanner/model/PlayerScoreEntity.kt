package tools.perry.lastwarscanner.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "player_scores")
data class PlayerScoreEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val score: Long,
    val day: String = "Unknown",
    val timestamp: Long = System.currentTimeMillis()
)