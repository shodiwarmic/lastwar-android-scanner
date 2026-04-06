package tools.perry.lastwarscanner.model

/**
 * Represents a row in the member score list, grouping all scores for a specific player.
 * @property name The name of the player.
 * @property scores A map of category/day names to score values.
 * @property latestTimestamp The timestamp of the most recent score update for this player.
 */
data class MemberRow(
    val name: String,
    val scores: Map<String, Long> = emptyMap(),
    val latestTimestamp: Long = 0
) {
    /**
     * Retrieves the score for a specific category or day.
     * @param key The name of the category (e.g., "Mon", "Power").
     * @return The score value, or null if not found.
     */
    fun getScore(key: String): Long? = scores[key]
}