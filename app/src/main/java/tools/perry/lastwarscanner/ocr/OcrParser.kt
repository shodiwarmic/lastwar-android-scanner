package tools.perry.lastwarscanner.ocr

import android.graphics.Rect
import tools.perry.lastwarscanner.model.PlayerScore
import kotlin.math.abs

/**
 * Parser responsible for converting raw OCR lines into structured player score data.
 * It identifies the screen layout, boundaries, and extracts player names and scores
 * based on the provided layout definitions.
 */
class OcrParser {

    /**
     * Data class representing the result of a parsing operation.
     * @property layout The detected [ScreenLayout].
     * @property players The list of [PlayerScore] objects extracted.
     * @property dayTabs The list of detected day/category tabs and their bounds.
     * @property pageSignalBounds The bounding box of the text that identified the page.
     * @property isConfirmedRankingPage True if the page was successfully identified as a valid ranking page.
     */
    data class ParsedResult(
        val layout: ScreenLayout?,
        val players: List<PlayerScore>,
        val dayTabs: List<DayTab>,
        val pageSignalBounds: Rect?,
        val isConfirmedRankingPage: Boolean
    )

    /**
     * Represents a detected day or category tab on the screen.
     */
    data class DayTab(val day: String, val bounds: Rect)

    /**
     * Parses a list of [OcrLine] objects into a [ParsedResult].
     *
     * @param lines The list of OCR text lines detected on the screen.
     * @param screenWidth The width of the captured screen.
     * @param screenHeight The height of the captured screen.
     * @param layouts The ordered list of screen layouts to match against, loaded from the
     *   YAML screen definitions via [ScreenDefinitionLoader]. Layouts are tested in list order
     *   so higher-priority layouts (lower priority number in catalog) should come first.
     */
    fun parse(
        lines: List<OcrLine>,
        screenWidth: Int,
        screenHeight: Int,
        layouts: List<ScreenLayout>
    ): ParsedResult {
        if (lines.isEmpty() || layouts.isEmpty()) {
            return ParsedResult(null, emptyList(), emptyList(), null, false)
        }

        val allText = lines.joinToString(" ") { it.text }

        // 1. Identify layout: at least one page_signal present, no negative_signals present
        val activeLayout = layouts.find { layout ->
            layout.pageSignals.any { signal -> allText.contains(signal, ignoreCase = true) } &&
            layout.negativeSignals.none { signal -> allText.contains(signal, ignoreCase = true) }
        }

        if (activeLayout == null) return ParsedResult(null, emptyList(), emptyList(), null, false)

        // 2. Find boundaries
        val headerRow = lines.find { line ->
            activeLayout.headerSignals.any { signal -> line.text.contains(signal, ignoreCase = true) }
        }
        val topBoundary = headerRow?.bottom ?: 0

        val footerRow = lines.find { line ->
            activeLayout.footerSignals.any { signal -> line.text.contains(signal, ignoreCase = true) }
        }
        val bottomBoundary = footerRow?.top ?: screenHeight

        // 3. Identify tabs
        val detectedTabs = mutableListOf<DayTab>()
        val tabAreaThreshold = screenHeight * 0.25
        for (line in lines) {
            if (line.top > tabAreaThreshold) continue
            for (element in line.elements) {
                val text = element.text.replace(".", "").trim()
                if (activeLayout.tabItems.isNotEmpty()) {
                    for (tabItem in activeLayout.tabItems) {
                        val matched = tabItem.signals.any { sig ->
                            sig.replace(".", "").trim().equals(text, ignoreCase = true)
                        }
                        if (matched) {
                            // Use the first signal as the stored day key for DB compatibility
                            detectedTabs.add(DayTab(tabItem.signals[0], element.boundingBox))
                            break
                        }
                    }
                } else if (activeLayout.tabSignals.isNotEmpty()) {
                    val matchedTab = activeLayout.tabSignals.find { it.equals(text, ignoreCase = true) }
                    if (matchedTab != null) {
                        detectedTabs.add(DayTab(matchedTab, element.boundingBox))
                    }
                }
            }
        }

        // 4. Group OCR lines into rows by vertical proximity
        val rowTolerance = (screenHeight * 0.02).toInt().coerceAtLeast(20)
        val rows = mutableMapOf<Int, MutableList<OcrLine>>()

        for (line in lines) {
            if (line.top < topBoundary || line.bottom > bottomBoundary) continue
            val cleanText = line.text.trim()
            if (cleanText.startsWith("[") || cleanText.endsWith("]")) continue
            val existingKey = rows.keys.find { abs(it - line.top) < rowTolerance }
            val key = existingKey ?: line.top
            rows.getOrPut(key) { mutableListOf() }.add(line)
        }

        val players = mutableListOf<PlayerScore>()

        for ((_, rowLines) in rows.toSortedMap()) {
            val sorted = rowLines.sortedBy { it.left }
            var name = ""
            var score = ""

            for (col in activeLayout.columns) {
                val minX = (col.minXRatio * screenWidth).toInt()
                val maxX = (col.maxXRatio * screenWidth).toInt()
                val matchingBlocks = sorted.filter { it.left >= minX && it.left <= maxX }

                when (col.type) {
                    ColumnType.NAME -> {
                        name = matchingBlocks.joinToString(" ") { it.text }
                            .replace(Regex("^\\d+\\s+"), "")
                            .replace(Regex("\\b[Rr][1-5]\\b"), "")
                            .replace(Regex("^\\d+"), "")
                            .replace(Regex("^[^\\w\\s]+"), "")
                            .replace(Regex("[^\\w\\s]+$"), "")
                            .trim()
                    }
                    ColumnType.SCORE -> {
                        score = matchingBlocks.joinToString("") { it.text }
                            .replace(Regex("[^0-9]"), "")
                    }
                    ColumnType.IGNORE -> {}
                }
            }

            if (name.isNotEmpty() && score.isNotEmpty()) {
                players.add(PlayerScore(name, score))
            }
        }

        val signalText = activeLayout.pageSignals.first()
        val pageSignalBounds = lines.find { it.text.contains(signalText, ignoreCase = true) }?.boundingBox

        return ParsedResult(
            layout = activeLayout,
            players = players,
            dayTabs = detectedTabs,
            pageSignalBounds = pageSignalBounds,
            isConfirmedRankingPage = true
        )
    }
}
