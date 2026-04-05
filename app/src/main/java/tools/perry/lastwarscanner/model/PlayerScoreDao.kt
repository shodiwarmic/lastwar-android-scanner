package tools.perry.lastwarscanner.model

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface PlayerScoreDao {
    @Query("SELECT * FROM player_scores ORDER BY timestamp DESC")
    fun getAllScores(): Flow<List<PlayerScoreEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(score: PlayerScoreEntity): Long

    @Query("DELETE FROM player_scores")
    fun deleteAll(): Int

    @Query("SELECT * FROM player_scores WHERE name = :name ORDER BY timestamp DESC LIMIT 1")
    fun getLatestPlayerEntry(name: String): PlayerScoreEntity?

    @Query("SELECT * FROM player_scores WHERE score = :score")
    fun getPlayersByScore(score: Long): List<PlayerScoreEntity>
    
    @Query("SELECT DISTINCT name FROM player_scores")
    fun getAllKnownNames(): List<String>
}
