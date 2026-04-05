package tools.perry.lastwarscanner.ocr

import android.graphics.Rect

data class ScreenLayout(
    val id: String,
    val name: String,
    val pageSignals: List<String>,
    val headerSignals: List<String>,
    val footerSignals: List<String>,
    val tabSignals: List<String>,
    val columns: List<ColumnDefinition>
)

data class ColumnDefinition(
    val id: String,
    val type: ColumnType,
    val minX: Int = 0,
    val maxX: Int = Int.MAX_VALUE
)

enum class ColumnType { NAME, SCORE, IGNORE }

object LayoutRegistry {
    private val DAYS = listOf("Mon", "Tues", "Wed", "Thur", "Fri", "Sat")
    
    val DAILY_RANKING = ScreenLayout(
        id = "daily_ranking",
        name = "Daily Ranking",
        // Require "Daily" to avoid matching Strength page
        pageSignals = listOf("Daily Rank", "Daily Ranking"), 
        headerSignals = listOf("Commander", "Points"),
        footerSignals = listOf("Your Alliance"),
        tabSignals = DAYS,
        columns = listOf(
            ColumnDefinition("rank", ColumnType.IGNORE, maxX = 150),
            ColumnDefinition("name", ColumnType.NAME, minX = 150, maxX = 600),
            ColumnDefinition("score", ColumnType.SCORE, minX = 600)
        )
    )

    val STRENGTH_RANKING = ScreenLayout(
        id = "strength_ranking",
        name = "Strength Ranking",
        // Require "STRENGTH" to avoid matching Daily page
        pageSignals = listOf("STRENGTH", "Strength Ranking"),
        headerSignals = listOf("Commander", "Power", "Kills", "Donation"),
        footerSignals = listOf("Your Alliance"),
        tabSignals = listOf("Power", "Kills", "Donation"),
        columns = listOf(
            ColumnDefinition("rank", ColumnType.IGNORE, maxX = 150),
            ColumnDefinition("name", ColumnType.NAME, minX = 150, maxX = 600),
            ColumnDefinition("score", ColumnType.SCORE, minX = 600)
        )
    )

    // Reordered to check for specific words first
    val ALL_LAYOUTS = listOf(DAILY_RANKING, STRENGTH_RANKING)
}
