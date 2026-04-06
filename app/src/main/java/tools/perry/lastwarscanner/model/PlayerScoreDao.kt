package tools.perry.lastwarscanner.model

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object (DAO) for the [PlayerScoreEntity] table.
 * Provides methods for querying, inserting, and deleting player score records.
 */
@Dao
interface PlayerScoreDao {
    /**
     * Retrieves all player scores from the database, ordered by timestamp in descending order.
     * @return A [Flow] containing the list of player score entities.
     */
    @Query("SELECT * FROM player_scores ORDER BY timestamp DESC")
    fun getAllScores(): Flow<List<PlayerScoreEntity>>

    /**
     * Inserts a new player score record or replaces an existing one if there's a conflict.
     * @param score The player score entity to insert.
     * @return The row ID of the inserted record.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(score: PlayerScoreEntity): Long

    /**
     * Deletes all records from the player scores table.
     * @return The number of records deleted.
     */
    @Query("DELETE FROM player_scores")
    fun deleteAll(): Int

    /**
     * Retrieves the most recent score entry for a specific player name.
     * @param name The player's name.
     * @return The latest player score entity, or null if not found.
     */
    @Query("SELECT * FROM player_scores WHERE name = :name ORDER BY timestamp DESC LIMIT 1")
    fun getLatestPlayerEntry(name: String): PlayerScoreEntity?

    /**
     * Retrieves all player score records with a specific numeric score.
     * @param score The numeric score value to search for.
     * @return A list of matching player score entities.
     */
    @Query("SELECT * FROM player_scores WHERE score = :score")
    fun getPlayersByScore(score: Long): List<PlayerScoreEntity>
    
    /**
     * Retrieves a list of all unique player names stored in the database.
     * @return A list of unique player names.
     */
    @Query("SELECT DISTINCT name FROM player_scores")
    fun getAllKnownNames(): List<String>
}
