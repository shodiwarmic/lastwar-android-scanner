package tools.perry.lastwarscanner.ocr

/**
 * Defines the structure and identifiers for a specific screen layout in the game.
 * Used by [OcrParser] to locate and extract relevant information from OCR results.
 */
data class ScreenLayout(
    val id: String,
    val name: String,
    val pageSignals: List<String>,
    val negativeSignals: List<String> = emptyList(),
    val preOcrHint: PreOcrHint? = null,
    val headerSignals: List<String>,
    val footerSignals: List<String>,
    /** Flat list of all tab signal strings — used for legacy text-based tab matching. */
    val tabSignals: List<String>,
    val tabItems: List<TabItem> = emptyList(),
    val tabYHint: Float = 0.20f,
    /** "brightest" (white highlight) or "color_fraction" (orange highlight). */
    val tabIndicatorStrategy: String = "brightest",
    val chromeTopFraction: Float = 0.22f,
    val chromeBottomFraction: Float = 0.12f,
    val columns: List<ColumnDefinition>,
    val rowClustering: RowClusteringConfig = RowClusteringConfig()
)

/**
 * Hint used for pre-OCR color sampling to confirm a screen before running full OCR.
 */
data class PreOcrHint(
    val xHint: Float,
    val yHint: Float,
    val hsvHMin: Float,
    val hsvHMax: Float,
    val hsvSMin: Float,
    val hsvVMin: Float,
    val confidence: Float
)

/**
 * Represents a single tab item on the screen (e.g. a day tab or category tab).
 */
data class TabItem(
    val id: String,
    val category: String,
    val signals: List<String>,
    val xHint: Float
)

/**
 * Configuration for the score-anchored row clustering algorithm in [OcrParser].
 */
data class RowClusteringConfig(
    val strategy: String = "score_anchored",
    val upBandFraction: Float = 0.021f,
    val downBandFraction: Float = 0.002f,
    val minScore: Int = 1000,
    val wordGapFraction: Float = 0.015f,
    val minWordGapPx: Int = 8
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
 * Hardcoded fallback registry — used only when YAML assets are unavailable.
 */
object LayoutRegistry {
    private val DAYS = listOf("Mon", "Tues", "Wed", "Thur", "Fri", "Sat")

    val DAILY_RANKING = ScreenLayout(
        id = "daily_ranking",
        name = "Daily Ranking",
        pageSignals = listOf("Daily Rank", "Daily Ranking"),
        negativeSignals = listOf("Strength Ranking", "STRENGTH"),
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
        tabIndicatorStrategy = "color_fraction",
        columns = listOf(
            ColumnDefinition("rank", ColumnType.IGNORE, maxXRatio = 0.15f),
            ColumnDefinition("name", ColumnType.NAME, minXRatio = 0.15f, maxXRatio = 0.6f),
            ColumnDefinition("score", ColumnType.SCORE, minXRatio = 0.6f)
        )
    )

    val ALL_LAYOUTS = listOf(STRENGTH_RANKING, DAILY_RANKING)
}
