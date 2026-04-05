package tools.perry.lastwarscanner.model

data class PlayerScore(
    val name: String,
    val score: String,
    val day: String = "Unknown"
)