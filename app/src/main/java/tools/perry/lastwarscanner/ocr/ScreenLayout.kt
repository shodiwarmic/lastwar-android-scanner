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
    /** Y-fraction range to search for the header signal. */
    val headerSearchYMin: Float = 0.0f,
    val headerSearchYMax: Float = 0.40f,
    /** Y-fraction range to search for the footer signal. */
    val footerSearchYMin: Float = 0.60f,
    val footerSearchYMax: Float = 1.0f,
    /** Flat list of all tab signal strings — used for legacy text-based tab matching. */
    val tabSignals: List<String>,
    val tabItems: List<TabItem> = emptyList(),
    val tabYHint: Float = 0.20f,
    /** Y-fraction range to search for tab labels. */
    val tabSearchRegionYMin: Float = 0.0f,
    val tabSearchRegionYMax: Float = 0.25f,
    /** "brightest" (white highlight) or "color_fraction" (orange highlight). */
    val tabIndicatorStrategy: String = "brightest",
    /** Minimum colour fraction for a tab to be considered active (color_fraction strategy). */
    val tabIndicatorMinFraction: Float = 0.10f,
    /** HSV colour range for the active-tab indicator (color_fraction strategy). Null = use hardcoded fallback. */
    val tabIndicatorHsvColor: HsvColorConfig? = null,
    /** Minimum gap between tabs for the brightest strategy. */
    val tabIndicatorMinGap: Float = 0.04f,
    /** Extra padding applied to a tab's bounding box before colour sampling. */
    val tabIndicatorBboxPaddingFraction: Float = 0.007f,
    val chromeTopFraction: Float = 0.22f,
    val chromeBottomFraction: Float = 0.12f,
    val columns: List<ColumnDefinition>,
    val rowClustering: RowClusteringConfig = RowClusteringConfig(),
    /**
     * Non-empty only for layouts with multiple independently-detected tab rows.
     * Maps group id (e.g. "category", "period") to its detection config.
     * When non-empty the service picks one winner per group and joins them with "_".
     */
    val tabGroupConfigs: Map<String, TabGroupConfig> = emptyMap()
)

/**
 * HSV colour range used for pixel-level colour detection.
 * Hue values are normalised to [0, 1] (divide Android's 0–360 range by 360).
 * Saturation and value are in [0, 1] as returned by [android.graphics.Color.RGBToHSV].
 */
data class HsvColorConfig(
    val hMin: Float = 0.014f,
    val hMax: Float = 0.153f,
    val sMin: Float = 0.40f,
    val vMin: Float = 0.55f
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
 * [group] is non-empty when the layout uses multi-group detection (e.g. alliance_contribution
 * has a "category" group and a "period" group, each sampled independently).
 */
data class TabItem(
    val id: String,
    val category: String,
    val signals: List<String>,
    val xHint: Float,
    val group: String = ""
)

/**
 * Per-group active-indicator config used by layouts that have more than one tab row
 * (e.g. [alliance_contribution] has a category row and a time-period row).
 * [strategy] is "color_fraction" (orange) or "brightest" (white).
 * [minFraction] is the minimum pixel fraction for a tab to be considered active.
 */
data class TabGroupConfig(
    val strategy: String = "color_fraction",
    val minFraction: Float = 0.10f
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
    val minWordGapPx: Int = 8,
    val yToleranceFraction: Float = 0.02f,
    val minTolerancePx: Int = 20,
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
