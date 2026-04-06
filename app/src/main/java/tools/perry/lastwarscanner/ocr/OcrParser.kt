package tools.perry.lastwarscanner.ocr

import android.graphics.Rect
import tools.perry.lastwarscanner.model.PlayerScore
import kotlin.math.abs

/**
 * Parser responsible for converting raw OCR lines into structured player score data.
 * It identifies the screen layout, boundaries, and extracts player names and scores
 * based on predefined layout rules.
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
     * @param lines The list of OCR text lines detected on the screen.
     * @param screenWidth The width of the captured screen.
     * @param screenHeight The height of the captured screen.
     * @return A [ParsedResult] containing the extracted information.
     */
    fun parse(lines: List<OcrLine>, screenWidth: Int, screenHeight: Int): ParsedResult {
        if (lines.isEmpty()) return ParsedResult(null, emptyList(), emptyList(), null, false)

        val allText = lines.joinToString(" ") { it.text }
        
        // 1. Identify layout with specific page signals
        val activeLayout = LayoutRegistry.ALL_LAYOUTS.find { layout ->
            layout.pageSignals.any { signal -> allText.contains(signal, ignoreCase = true) }
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

        // 3. Identify Tabs (Precise Element Level)
        val detectedTabs = mutableListOf<DayTab>()
        val tabAreaThreshold = screenHeight * 0.25 // Only look in top 25% of screen
        for (line in lines) {
            if (line.top > tabAreaThreshold) continue
            // Check individual elements (words) for higher precision
            for (element in line.elements) {
                val text = element.text.replace(".", "").trim()
                val matchedTab = activeLayout.tabSignals.find { it.equals(text, ignoreCase = true) }
                if (matchedTab != null) {
                    detectedTabs.add(DayTab(matchedTab, element.boundingBox))
                }
            }
        }

        // 4. Group by row
        val rowTolerance = (screenHeight * 0.02).toInt().coerceAtLeast(20) // 2% of screen height
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
        val sortedRows = rows.toSortedMap()

        for ((_, rowLines) in sortedRows) {
            val sorted = rowLines.sortedBy { it.left }
            var name = ""
            var score = ""

            for (col in activeLayout.columns) {
                // Calculate pixel coordinates from relative ratios
                val minX = (col.minXRatio * screenWidth).toInt()
                val maxX = (col.maxXRatio * screenWidth).toInt()
                
                val matchingBlocks = sorted.filter { it.left >= minX && it.left <= maxX }
                
                when (col.type) {
                    ColumnType.NAME -> {
                        val fullRowText = matchingBlocks.joinToString(" ") { it.text }
                        name = fullRowText
                            .replace(Regex("^\\d+\\s+"), "") 
                            .replace(Regex("\\b[Rr][1-5]\\b"), "")
                            .replace(Regex("^\\d+"), "")
                            .replace(Regex("^[^\\w\\s]+"), "")
                            .replace(Regex("[^\\w\\s]+$"), "")
                            .trim()
                    }
                    ColumnType.SCORE -> {
                        val rawScore = matchingBlocks.joinToString("") { it.text }
                        score = rawScore.replace(Regex("[^0-9]"), "")
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
