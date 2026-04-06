package tools.perry.lastwarscanner.ocr

import android.graphics.Rect

/**
 * Defines the structure and identifiers for a specific screen layout in the game.
 * Used by [OcrParser] to locate and extract relevant information from OCR results.
 * @property id Unique identifier for the layout.
 * @property name Human-readable name of the layout.
 * @property pageSignals List of strings that, if found, identify this screen as this layout.
 * @property headerSignals Strings identifying the start of the data table.
 * @property footerSignals Strings identifying the end of the data table.
 * @property tabSignals Strings identifying the day/category tabs on the screen.
 * @property columns Definition of column locations and types.
 */
data class ScreenLayout(
    val id: String,
    val name: String,
    val pageSignals: List<String>,
    val headerSignals: List<String>,
    val footerSignals: List<String>,
    val tabSignals: List<String>,
    val columns: List<ColumnDefinition>
)

/**
 * Defines a column's position relative to the screen width and its data type.
 */
data class ColumnDefinition(
    val id: String,
    val type: ColumnType,
    val minXRatio: Float = 0f,
    val maxXRatio: Float = 1f
)

/**
 * Types of data that can be found in a column.
 */
enum class ColumnType { NAME, SCORE, IGNORE }

/**
 * Registry of all known screen layouts in the game.
 */
object LayoutRegistry {
    private val DAYS = listOf("Mon", "Tues", "Wed", "Thur", "Fri", "Sat")
    
    val DAILY_RANKING = ScreenLayout(
        id = "daily_ranking",
        name = "Daily Ranking",
        pageSignals = listOf("Daily Rank", "Daily Ranking"), 
        headerSignals = listOf("Commander", "Points"),
        footerSignals = listOf("Your Alliance"),
        tabSignals = DAYS,
        columns = listOf(
            ColumnDefinition("rank", ColumnType.IGNORE, maxXRatio = 0.15f),
            ColumnDefinition("name", ColumnType.NAME, minXRatio = 0.15f, maxXRatio = 0.6f),
            ColumnDefinition("score", ColumnType.SCORE, minXRatio = 0.6f)
        )
    )

    val STRENGTH_RANKING = ScreenLayout(
        id = "strength_ranking",
        name = "Strength Ranking",
        pageSignals = listOf("STRENGTH", "Strength Ranking"),
        headerSignals = listOf("Commander", "Power", "Kills", "Donation"),
        footerSignals = listOf("Your Alliance"),
        tabSignals = listOf("Power", "Kills", "Donation"),
        columns = listOf(
            ColumnDefinition("rank", ColumnType.IGNORE, maxXRatio = 0.15f),
            ColumnDefinition("name", ColumnType.NAME, minXRatio = 0.15f, maxXRatio = 0.6f),
            ColumnDefinition("score", ColumnType.SCORE, minXRatio = 0.6f)
        )
    )

    val ALL_LAYOUTS = listOf(DAILY_RANKING, STRENGTH_RANKING)
}
