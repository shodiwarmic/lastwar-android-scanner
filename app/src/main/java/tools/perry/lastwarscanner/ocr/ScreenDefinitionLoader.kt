package tools.perry.lastwarscanner.ocr

import android.content.Context
import org.yaml.snakeyaml.Yaml

/**
 * Loads screen definitions from bundled YAML assets using SnakeYAML.
 *
 * Asset layout expected under app/src/main/assets/:
 *   screen_definitions/catalog.yaml
 *   screen_definitions/screens/daily_ranking.yaml
 *   screen_definitions/screens/strength_ranking.yaml
 *   screen_definitions/screens/weekly_ranking.yaml
 *
 * Definitions are loaded in catalog priority order (lowest number first) so
 * that [OcrParser] checks the most-specific screen before the broadest match.
 */
object ScreenDefinitionLoader {

    private const val CATALOG_PATH = "screen_definitions/catalog.yaml"
    private const val SCREENS_BASE = "screen_definitions"

    @Volatile private var _cached: List<ScreenLayout>? = null

    /**
     * Returns all screen layouts parsed from the bundled YAML assets,
     * ordered by catalog priority. Results are cached after the first load.
     */
    fun loadAll(context: Context): List<ScreenLayout> {
        _cached?.let { return it }
        synchronized(this) {
            _cached?.let { return it }
            val loaded = parseAll(context)
            _cached = loaded
            return loaded
        }
    }

    private fun parseAll(context: Context): List<ScreenLayout> {
        val yaml = Yaml()

        // Load and sort catalog entries by priority
        val catalog = context.assets.open(CATALOG_PATH).use { stream ->
            @Suppress("UNCHECKED_CAST")
            yaml.load<Map<String, Any>>(stream)
        }
        @Suppress("UNCHECKED_CAST")
        val entries = (catalog["screens"] as? List<Map<String, Any>> ?: emptyList())
            .sortedBy { (it["priority"] as? Int) ?: 99 }

        return entries.mapNotNull { entry ->
            val relPath = entry["file"] as? String ?: return@mapNotNull null
            val assetPath = "$SCREENS_BASE/$relPath"
            try {
                context.assets.open(assetPath).use { stream ->
                    @Suppress("UNCHECKED_CAST")
                    val raw = yaml.load<Map<String, Any>>(stream)
                    parseLayout(raw)
                }
            } catch (e: Exception) {
                android.util.Log.e("ScreenDefLoader", "Failed to load $assetPath: ${e.message}")
                null
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseLayout(raw: Map<String, Any>): ScreenLayout {
        val id   = raw["id"]   as? String ?: ""
        val name = raw["name"] as? String ?: ""

        val ident           = raw["identification"]  as? Map<String, Any> ?: emptyMap()
        val pageSignals     = ident["page_signals"]   as? List<String>    ?: emptyList()
        val negativeSignals = ident["negative_signals"] as? List<String>  ?: emptyList()
        val preOcrHint      = (ident["pre_ocr_hint"] as? Map<String, Any>)?.let { parsePreOcrHint(it) }

        val boundaries   = raw["boundaries"] as? Map<String, Any> ?: emptyMap()
        val headerSignals = ((boundaries["header"] as? Map<String, Any>)
            ?.get("signals") as? List<String>) ?: emptyList()
        val footerSignals = ((boundaries["footer"] as? Map<String, Any>)
            ?.get("signals") as? List<String>) ?: emptyList()

        val chromeRaw          = raw["chrome"] as? Map<String, Any>
        val chromeTopFraction  = (chromeRaw?.get("top_fraction")    as? Number)?.toFloat() ?: 0.22f
        val chromeBotFraction  = (chromeRaw?.get("bottom_fraction") as? Number)?.toFloat() ?: 0.12f

        val tabsRaw            = raw["tabs"] as? Map<String, Any>
        val tabItems           = parseTabItems(tabsRaw)
        val tabYHint           = (tabsRaw?.get("y_hint") as? Number)?.toFloat() ?: 0.20f
        val tabIndicatorStrategy = ((tabsRaw?.get("active_indicator") as? Map<String, Any>)
            ?.get("strategy") as? String) ?: "brightest"
        // Flat list of all signal strings, used for legacy text-based tab matching
        val tabSignals = tabItems.flatMap { it.signals }.distinct()

        val columnsRaw = raw["columns"] as? List<Map<String, Any>> ?: emptyList()
        val columns = columnsRaw.map { col ->
            ColumnDefinition(
                id        = col["id"] as? String ?: "",
                type      = when (col["type"] as? String) {
                    "name"  -> ColumnType.NAME
                    "score" -> ColumnType.SCORE
                    else    -> ColumnType.IGNORE
                },
                minXRatio = (col["x_min"] as? Number)?.toFloat() ?: 0f,
                maxXRatio = (col["x_max"] as? Number)?.toFloat() ?: 1f,
            )
        }

        val rcRaw       = raw["row_clustering"] as? Map<String, Any>
        val saRaw       = rcRaw?.get("score_anchored") as? Map<String, Any>
        val rowCluster  = RowClusteringConfig(
            strategy         = rcRaw?.get("strategy") as? String ?: "score_anchored",
            upBandFraction   = (saRaw?.get("up_band_fraction")   as? Number)?.toFloat() ?: 0.021f,
            downBandFraction = (saRaw?.get("down_band_fraction") as? Number)?.toFloat() ?: 0.002f,
            minScore         = (rcRaw?.get("min_score")          as? Number)?.toInt()   ?: 1000,
            wordGapFraction  = (rcRaw?.get("word_gap_fraction")  as? Number)?.toFloat() ?: 0.015f,
            minWordGapPx     = (rcRaw?.get("min_word_gap_px")    as? Number)?.toInt()   ?: 8,
        )

        return ScreenLayout(
            id                   = id,
            name                 = name,
            pageSignals          = pageSignals,
            negativeSignals      = negativeSignals,
            preOcrHint           = preOcrHint,
            headerSignals        = headerSignals,
            footerSignals        = footerSignals,
            tabSignals           = tabSignals,
            tabItems             = tabItems,
            tabYHint             = tabYHint,
            tabIndicatorStrategy = tabIndicatorStrategy,
            chromeTopFraction    = chromeTopFraction,
            chromeBottomFraction = chromeBotFraction,
            columns              = columns,
            rowClustering        = rowCluster,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parsePreOcrHint(raw: Map<String, Any>): PreOcrHint {
        val colorRaw = raw["color"] as? Map<String, Any>
        val hsvRaw   = colorRaw?.get("hsv_override") as? Map<String, Any>
        return PreOcrHint(
            xHint    = (raw["x_hint"]    as? Number)?.toFloat() ?: 0.5f,
            yHint    = (raw["y_hint"]    as? Number)?.toFloat() ?: 0.2f,
            hsvHMin  = (hsvRaw?.get("h_min") as? Number)?.toFloat() ?: 0.014f,
            hsvHMax  = (hsvRaw?.get("h_max") as? Number)?.toFloat() ?: 0.153f,
            hsvSMin  = (hsvRaw?.get("s_min") as? Number)?.toFloat() ?: 0.40f,
            hsvVMin  = (hsvRaw?.get("v_min") as? Number)?.toFloat() ?: 0.55f,
            confidence = (raw["confidence"] as? Number)?.toFloat() ?: 0.88f,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseTabItems(tabsRaw: Map<String, Any>?): List<TabItem> {
        val items = tabsRaw?.get("items") as? List<Map<String, Any>> ?: return emptyList()
        return items.map { item ->
            TabItem(
                id       = item["id"]       as? String      ?: "",
                category = item["category"] as? String      ?: "",
                signals  = item["signals"]  as? List<String> ?: emptyList(),
                xHint    = (item["x_hint"]  as? Number)?.toFloat() ?: 0.5f,
            )
        }
    }
}
