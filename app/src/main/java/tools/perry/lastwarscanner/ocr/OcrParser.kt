package tools.perry.lastwarscanner.ocr

import android.graphics.Rect
import android.util.Log
import tools.perry.lastwarscanner.model.CrashCandidate
import tools.perry.lastwarscanner.model.PlayerScore
import kotlin.math.abs

private const val TAG = "OcrParser"

/**
 * Parser responsible for converting raw OCR lines into structured player score data.
 * It identifies the screen layout, boundaries, and extracts player names and scores
 * based on the provided layout definitions.
 */
class OcrParser {

    /** Holds a partially-processed player row before adjacent-score validation. */
    private data class RawRow(
        val name: String,
        val score: String,
        /** All valid crash-token splits, ascending by score. Empty when no crash detected. */
        val candidates: List<Pair<String, String>>,
        val rowBounds: Rect?
    )

    /**
     * Data class representing the result of a parsing operation.
     * @property layout The detected [ScreenLayout].
     * @property players The list of [PlayerScore] objects extracted, each carrying its
     *   [PlayerScore.rowBounds] bounding box for snapshot cropping.
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
     * [group] is non-empty for layouts that use multi-group tab detection
     * (e.g. "category" or "period" on the alliance_contribution screen).
     */
    data class DayTab(val day: String, val bounds: Rect, val group: String = "")

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

        // 2. Find data boundaries.
        //    If OCR misses the header/footer signal, fall back to chrome fractions so footer
        //    content (e.g. "Your Alliance / [Tag] AllianceName") is never included as player rows.
        val chromeTopY = (screenHeight * activeLayout.chromeTopFraction).toInt()
        val chromeBotY = (screenHeight * (1f - activeLayout.chromeBottomFraction)).toInt()

        val headerSearchMin = (screenHeight * activeLayout.headerSearchYMin).toInt()
        val headerSearchMax = (screenHeight * activeLayout.headerSearchYMax).toInt()
        val headerRow = lines
            .filter { it.top >= headerSearchMin && it.bottom <= headerSearchMax }
            .find { line ->
                activeLayout.headerSignals.any { signal -> line.text.contains(signal, ignoreCase = true) }
            }
        val topBoundary = headerRow?.bottom ?: chromeTopY

        val footerSearchMin = (screenHeight * activeLayout.footerSearchYMin).toInt()
        val footerSearchMax = (screenHeight * activeLayout.footerSearchYMax).toInt()
        val footerRow = lines
            .filter { it.top >= footerSearchMin && it.bottom <= footerSearchMax }
            .find { line ->
                activeLayout.footerSignals.any { signal -> line.text.contains(signal, ignoreCase = true) }
            }
        val bottomBoundary = footerRow?.top ?: chromeBotY

        // 3. Identify tabs — respect both y_min and y_max from the tab search region
        val detectedTabs = mutableListOf<DayTab>()
        val tabAreaMin = (screenHeight * activeLayout.tabSearchRegionYMin).toInt()
        val tabAreaMax = (screenHeight * activeLayout.tabSearchRegionYMax).toInt()
        for (line in lines) {
            if (line.top > tabAreaMax || line.bottom < tabAreaMin) continue
            if (activeLayout.tabItems.isNotEmpty()) {
                // Normalise line text: strip punctuation, lowercase, collapse whitespace.
                // Use substring matching (not word-set) so that OCR-merged adjacent words
                // (e.g. "RankingWeekly" instead of "Ranking Weekly") are still detected.
                val lineNorm = line.text
                    .replace(Regex("[^a-zA-Z0-9\\s]"), " ")
                    .trim().lowercase()
                    .replace(Regex("\\s+"), " ")
                for (tabItem in activeLayout.tabItems) {
                    val anySignalMatched = tabItem.signals.any { sig ->
                        val sigNorm = sig.replace(Regex("[^a-zA-Z0-9\\s]"), " ")
                            .trim().lowercase()
                            .replace(Regex("\\s+"), " ")
                        sigNorm.isNotEmpty() && lineNorm.contains(sigNorm)
                    }
                    if (anySignalMatched) {
                        // Use category as the output key; fall back to id if category is blank.
                        val tabDay = tabItem.category.ifEmpty { tabItem.id }
                        // Derive the sampling bounds from the actual OCR element positions
                        // of the matched signal text. x_hint is used only as a tiebreaker
                        // when the same word sequence appears multiple times on one line
                        // (e.g. "Ranking" appears for each period tab on a shared line).
                        val tabBounds = findTabElementBounds(line, tabItem, screenWidth)
                        android.util.Log.d("LWS_TAB", "matched id=${tabItem.id} category=${tabDay} line='${line.text.take(30)}' elemBounds=${tabBounds.left}..${tabBounds.right} y=${tabBounds.top}..${tabBounds.bottom}")
                        detectedTabs.add(DayTab(tabDay, tabBounds, tabItem.group))
                    }
                }
            } else if (activeLayout.tabSignals.isNotEmpty()) {
                for (element in line.elements) {
                    val text = element.text.replace(".", "").trim()
                    val matchedTab = activeLayout.tabSignals.find { it.equals(text, ignoreCase = true) }
                    if (matchedTab != null) {
                        detectedTabs.add(DayTab(matchedTab, element.boundingBox))
                    }
                }
            }
        }

        // 4. Compute row-clustering parameters from the layout definition
        val rowTolerance = (screenHeight * activeLayout.rowClustering.yToleranceFraction)
            .toInt().coerceAtLeast(activeLayout.rowClustering.minTolerancePx)
        val wordGapPx = maxOf(
            (screenWidth * activeLayout.rowClustering.wordGapFraction).toInt(),
            activeLayout.rowClustering.minWordGapPx
        )

        // 5. Build raw rows using the strategy declared in the layout definition
        val rawRows: MutableList<RawRow> = when (activeLayout.rowClustering.strategy) {
            "score_anchored" -> buildScoreAnchoredRows(
                lines, screenWidth, screenHeight, topBoundary, bottomBoundary,
                activeLayout, rowTolerance, wordGapPx
            )
            else -> buildYProximityRows(
                lines, screenWidth, screenHeight, topBoundary, bottomBoundary,
                activeLayout, rowTolerance, wordGapPx
            )
        }

        // Sort by top-of-row y so that Pass 2's adjacent-row lookup finds the correct
        // leaderboard neighbours. Score-anchored rows come out in y order already, but
        // spanning-line and orphan rows are appended afterward and need to be merged in.
        rawRows.sortBy { it.rowBounds?.top ?: Int.MAX_VALUE }

        // Pass 2 — validate ambiguous crash tokens using adjacent row scores.
        // The leaderboard is sorted descending, so each row's score must be ≤ the row above
        // and ≥ the row below. If the rightmost split violates this, try alternatives.
        for (i in rawRows.indices) {
            val row = rawRows[i]
            if (row.candidates.size <= 1) continue
            val upper = if (i > 0) rawRows[i - 1].score.toLongOrNull() ?: Long.MAX_VALUE else Long.MAX_VALUE
            val lower = if (i < rawRows.size - 1) rawRows[i + 1].score.toLongOrNull() ?: 0L else 0L
            val validated = row.candidates.firstOrNull { (_, s) ->
                val v = s.toLongOrNull() ?: return@firstOrNull false
                v <= upper && v >= lower
            }
            if (validated != null && (validated.first != row.name || validated.second != row.score)) {
                rawRows[i] = row.copy(name = validated.first, score = validated.second)
            }
        }

        val players = rawRows.map { raw ->
            PlayerScore(
                name       = raw.name,
                score      = raw.score,
                rowBounds  = raw.rowBounds,
                candidates = raw.candidates.map { (n, s) -> CrashCandidate(n, s) }
            )
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

    // ─────────────────────────────────────────────────────────────────────────
    // Row-building strategies
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Score-anchored strategy.
     *
     * For each score token found in the score column:
     *   1. Merge near-duplicate detections (different OCR engines seeing the same number).
     *   2. Collect name-column lines within [score.top − upBand, score.bottom + downBand].
     *   3. Assign each name line to its closest anchor when bands overlap.
     *
     * Name lines with no score anchor are passed through crash-token recovery, which handles
     * the case where OCR merges a digit-ending player name with its score into one token.
     *
     * This strategy prevents footer rows (e.g. "Your Alliance / [Tag] AllianceName") from
     * being captured as player rows because they have no corresponding score-column token.
     */
    private fun buildScoreAnchoredRows(
        lines: List<OcrLine>,
        screenWidth: Int,
        screenHeight: Int,
        topBoundary: Int,
        bottomBoundary: Int,
        layout: ScreenLayout,
        rowTolerance: Int,
        wordGapPx: Int
    ): MutableList<RawRow> {
        val rawRows = mutableListOf<RawRow>()
        val upBand   = (screenHeight * layout.rowClustering.upBandFraction).toInt()
        val downBand = (screenHeight * layout.rowClustering.downBandFraction).toInt()

        val scoreColDef = layout.columns.firstOrNull { it.type == ColumnType.SCORE }
        val nameColDef  = layout.columns.firstOrNull { it.type == ColumnType.NAME }
        if (scoreColDef == null || nameColDef == null) return rawRows

        val scoreMinX = (scoreColDef.minXRatio * screenWidth).toInt()
        val scoreMaxX = (scoreColDef.maxXRatio * screenWidth).toInt()
        val nameMinX  = (nameColDef.minXRatio  * screenWidth).toInt()
        val nameMaxX  = (nameColDef.maxXRatio  * screenWidth).toInt()

        // Restrict to the player-list data region.
        // Drop:
        //  • standalone alliance tags: "[PoWr]"
        //  • alliance sub-lines: "[PoWr] Pantheon of Wrath" — contain a closing bracket,
        //    text after it, and NO digits. These appear on a second y-row below the player
        //    name on the daily ranking VS screen and must not be assigned to score anchors.
        val dataLines = lines.filter { line ->
            line.top >= topBoundary && line.bottom <= bottomBoundary &&
            line.text.trim().let { t ->
                !(t.startsWith("[") && t.endsWith("]")) &&          // e.g. "[PoWr]"
                !(t.contains(']') &&                                 // e.g. "[PoWr] Pantheon of Wrath"
                    t.substringAfter(']').any { it.isLetter() } &&
                    t.none { it.isDigit() })
            }
        }

        // --- Collect and deduplicate score anchors ---
        data class ScoreAnchor(val line: OcrLine, val numStr: String, val value: Long)

        Log.d(TAG, "buildScoreAnchoredRows: screenW=$screenWidth scoreCol=[$scoreMinX,$scoreMaxX] nameCol=[$nameMinX,$nameMaxX] top=$topBoundary bottom=$bottomBoundary upBand=$upBand downBand=$downBand")
        Log.d(TAG, "dataLines (${dataLines.size}): ${dataLines.joinToString(" | ") { "\"${it.text}\" x=[${it.left},${it.right}] y=[${it.top},${it.bottom}]" }}")

        val scoreCandidates = dataLines.mapNotNull { line ->
            if (line.left < scoreMinX || line.left > scoreMaxX) {
                Log.v(TAG, "  score reject (x=${line.left} outside [$scoreMinX,$scoreMaxX]): \"${line.text}\"")
                return@mapNotNull null
            }
            val numStr = line.text.replace(Regex("[^0-9]"), "")
            val value  = numStr.toLongOrNull() ?: run {
                Log.v(TAG, "  score reject (not numeric): \"${line.text}\"")
                return@mapNotNull null
            }
            if (value < layout.rowClustering.minScore) {
                Log.v(TAG, "  score reject (value=$value < minScore=${layout.rowClustering.minScore}): \"${line.text}\"")
                return@mapNotNull null
            }
            Log.d(TAG, "  score anchor: \"${line.text}\" → numStr=$numStr value=$value x=${line.left}")
            ScoreAnchor(line, numStr, value)
        }.sortedBy { it.line.top }

        // Merge near-duplicate detections: keep the anchor with the longest numeric string
        val anchors = mutableListOf<ScoreAnchor>()
        for (sc in scoreCandidates) {
            val idx = anchors.indexOfFirst { abs(it.line.top - sc.line.top) < rowTolerance }
            if (idx >= 0) {
                if (sc.numStr.length > anchors[idx].numStr.length) anchors[idx] = sc
            } else {
                anchors.add(sc)
            }
        }

        // --- Assign name-column lines to anchors ---
        val nameColLines = dataLines.filter { line ->
            line.right > nameMinX && line.left <= nameMaxX
        }

        val anchorToNameLines: Map<ScoreAnchor, MutableList<OcrLine>> =
            anchors.associateWith { mutableListOf() }
        val assignedLines = mutableSetOf<OcrLine>()

        for (nameLine in nameColLines) {
            val nameMid = (nameLine.top + nameLine.bottom) / 2
            val inBand = anchors.filter { anchor ->
                nameLine.top    >= anchor.line.top    - upBand &&
                nameLine.bottom <= anchor.line.bottom + downBand
            }
            if (inBand.isNotEmpty()) {
                val closest = inBand.minByOrNull { anchor ->
                    abs((anchor.line.top + anchor.line.bottom) / 2 - nameMid)
                }!!
                anchorToNameLines[closest]!!.add(nameLine)
                assignedLines.add(nameLine)
            }
        }

        // --- Build one row per anchor ---
        for (anchor in anchors) {
            val nameLines = anchorToNameLines[anchor]!!.sortedBy { it.left }
            Log.d(TAG, "anchor score=${anchor.numStr} (${anchor.value}) — nameLines(${nameLines.size}): ${nameLines.joinToString(" | ") { "\"${it.text}\" x=[${it.left},${it.right}]" }}")

            // Score-prefix bleed: detect on the RAW joined text BEFORE cleanName runs,
            // because cleanName's trailing-digit strip would otherwise consume the bleed
            // digits before we can use them to reconstruct the full score.
            // E.g. raw "[PoWr] OnlyJesus316 91" + anchor "9540" → score "919540", then
            // cleanName("[PoWr] OnlyJesus316") → "OnlyJesus316".
            val rawJoined = joinLines(nameLines, wordGapPx)
            var scoreStr = anchor.numStr
            Log.d(TAG, "  rawJoined=\"$rawJoined\"")
            // Score-prefix bleed detection. Bleed occurs when the first 1–3 digits of a score
            // are placed in the name column by OCR. Two cases:
            //   • Space-based: "PlayerName 91" + anchor "9540" → score "919540"
            //   • No-space: "PlayerName1" + anchor "8434000" → score "18434000"
            // We cannot tell locally whether a trailing digit is bleed or part of the player
            // name (e.g. "CheeseKillers2", "pudgey27", "Blindman3"), so we generate two
            // candidates and let Pass 2 resolve using adjacent-row scores:
            //   Candidate 0 (default): keep trailing digit in name, use anchor score as-is
            //   Candidate 1: treat trailing digit as bleed, prepend to score
            val spaceTrailDigits = Regex("\\s+(\\d{1,3})$").find(rawJoined)
            val noSpaceTrailDigit = if (spaceTrailDigits == null)
                Regex("([a-zA-Z])(\\d{1,2})$").find(rawJoined) else null

            val bleedCandidates = mutableListOf<Pair<String, String>>()
            when {
                spaceTrailDigits != null -> {
                    val prefix = spaceTrailDigits.groupValues[1]
                    val combined = prefix + scoreStr
                    val combinedValue = combined.toLongOrNull()
                    if (combinedValue != null && combinedValue >= layout.rowClustering.minScore) {
                        val cleanNoBleed   = cleanName(rawJoined)  // cleanName strips trailing " 91" via \s+\d+$
                        val cleanWithBleed = cleanName(rawJoined.substring(0, spaceTrailDigits.range.first))
                        if (cleanNoBleed.isNotEmpty() && cleanWithBleed.isNotEmpty()) {
                            bleedCandidates.add(cleanNoBleed to scoreStr)    // candidate 0: keep digits in name
                            bleedCandidates.add(cleanWithBleed to combined)  // candidate 1: treat as bleed
                            Log.d(TAG, "  bleed candidates (space): [0]=\"$cleanNoBleed\"/$scoreStr [1]=\"$cleanWithBleed\"/$combined")
                        }
                    } else {
                        Log.d(TAG, "  bleed candidate (space) prefix=\"$prefix\" combined=$combined rejected (value=$combinedValue)")
                    }
                }
                noSpaceTrailDigit != null -> {
                    val prefix = noSpaceTrailDigit.groupValues[2]
                    val combined = prefix + scoreStr
                    val combinedValue = combined.toLongOrNull()
                    if (combinedValue != null && combinedValue >= layout.rowClustering.minScore) {
                        val cleanWithDigit    = cleanName(rawJoined)  // keeps trailing letter+digit (e.g. "CheeseKillers2")
                        val cleanWithoutDigit = cleanName(rawJoined.substring(0, noSpaceTrailDigit.range.first + 1))
                        if (cleanWithDigit.isNotEmpty() && cleanWithoutDigit.isNotEmpty()) {
                            bleedCandidates.add(cleanWithDigit to scoreStr)    // candidate 0: name digit is real
                            bleedCandidates.add(cleanWithoutDigit to combined) // candidate 1: treat as bleed
                            Log.d(TAG, "  bleed candidates (no-space): [0]=\"$cleanWithDigit\"/$scoreStr [1]=\"$cleanWithoutDigit\"/$combined")
                        }
                    } else {
                        Log.d(TAG, "  bleed candidate (no-space) prefix=\"$prefix\" combined=$combined rejected (value=$combinedValue)")
                    }
                }
                else -> {
                    Log.d(TAG, "  no trailing digits detected")
                }
            }

            val name = cleanName(rawJoined)
            Log.d(TAG, "  rawJoined=\"$rawJoined\" → cleanName=\"$name\" scoreStr=$scoreStr bleedCandidates=${bleedCandidates.size}")
            if (name.isNotEmpty()) {
                val rowBounds = buildRowBounds(nameLines, anchor.line.boundingBox)
                rawRows.add(RawRow(name, scoreStr, bleedCandidates, rowBounds))
            }
        }

        // --- Spanning-line recovery ---
        // OCR sometimes returns an entire player row (name + score) as one bounding box
        // whose left edge is in the name column but right edge reaches into the score column
        // (e.g. "[PoWr] OnlyJesus316 923,540" x=[357,847] with scoreMinX=652).
        // The score anchor loop rejects it (left < scoreMinX), it ends up unassigned, and
        // crash-token recovery splits it badly. Handle it here before orphan grouping.
        //
        // The last whitespace-delimited token is normally the score. But OCR sometimes
        // merges the name directly into the score with no space (e.g. "Splendiddragon18,522,300"
        // as one token). In that case, use crashSplits on the last token to separate them.
        for (line in nameColLines) {
            if (line in assignedLines) continue
            if (line.right <= scoreMinX) continue  // purely in name column — real orphan, skip

            val tokens = line.text.trimEnd().split(Regex("\\s+")).filter { it.isNotEmpty() }
            val lastToken = tokens.lastOrNull() ?: continue

            val lastNumStr: String
            val nameRaw: String
            val spanCandidates: List<Pair<String, String>>
            if (lastToken.any { it.isLetter() }) {
                // Last token contains letters — it is a crash-merged "name+score" token
                // (e.g. "Splendiddragon18,522,300" or "CheeseKillers216,000,000").
                // We cannot tell locally whether the trailing digit is a name character
                // ("CheeseKillers2") or score bleed ("Splendiddragon1"), so store all
                // candidate splits and let Pass 2 resolve using adjacent-row scores.
                // Default to index 0 (smallest score, maximum name retained).
                val splits = crashSplits(lastToken)
                if (splits.isEmpty()) continue
                val prefix = tokens.dropLast(1)
                spanCandidates = splits.map { (n, s) ->
                    cleanName((prefix + n).joinToString(" ")) to s
                }.filter { it.first.isNotEmpty() }
                if (spanCandidates.isEmpty()) continue
                lastNumStr = spanCandidates[0].second   // default: smallest score
                nameRaw    = spanCandidates[0].first    // already cleaned
            } else {
                // Last token is a pure number — strip non-digits to get score.
                lastNumStr    = lastToken.replace(Regex("[^0-9]"), "")
                nameRaw       = tokens.dropLast(1).joinToString(" ")
                spanCandidates = emptyList()
            }

            val value = if (spanCandidates.isEmpty()) lastNumStr.toLongOrNull() ?: continue
                        else spanCandidates[0].second.toLongOrNull() ?: continue
            if (value < layout.rowClustering.minScore) continue
            val name = if (spanCandidates.isEmpty()) cleanName(nameRaw) else nameRaw
            Log.d(TAG, "spanning-line: \"${line.text}\" → name=\"$name\" score=$lastNumStr ($value) candidates=${spanCandidates.size}")
            if (name.isNotEmpty()) {
                assignedLines.add(line)
                rawRows.add(RawRow(name, lastNumStr, spanCandidates, line.boundingBox))
            }
        }

        // --- Crash-token recovery for orphan name-column lines ---
        // An orphan line has no associated score anchor, which happens when OCR merges a
        // digit-ending player name with its score into one bounding box (e.g.
        // "Ruthless54323,045,000"). Group orphans by y-proximity and attempt a crash split.
        val orphanLines = nameColLines.filter { it !in assignedLines }
        val orphanGroups = mutableMapOf<Int, MutableList<OcrLine>>()
        for (line in orphanLines.sortedBy { it.top }) {
            val key = orphanGroups.keys.find { abs(it - line.top) < rowTolerance }
            orphanGroups.getOrPut(key ?: line.top) { mutableListOf() }.add(line)
        }
        for ((_, group) in orphanGroups.toSortedMap()) {
            val sorted = group.sortedBy { it.left }
            val combined = joinLines(sorted, wordGapPx)
            val preprocessed = combined
                .replace(Regex("(\\d),\\s+(\\d)"), "$1,$2")
                .let { s ->
                    val last = s.indexOfLast { it.isDigit() }
                    if (last >= 0 && last < s.length - 1) s.substring(0, last + 1) else s
                }
            val candidates = crashSplits(preprocessed)
            if (candidates.isNotEmpty()) {
                val cleanName = cleanName(candidates[0].first)
                val scoreValue = candidates[0].second.toLongOrNull() ?: 0L
                if (cleanName.isNotEmpty() && scoreValue >= layout.rowClustering.minScore) {
                    val rowBounds = sorted.fold(null as Rect?) { acc, line ->
                        if (acc == null) Rect(line.left, line.top, line.right, line.bottom)
                        else acc.also { it.union(line.boundingBox) }
                    }
                    rawRows.add(RawRow(cleanName, candidates[0].second, candidates, rowBounds))
                }
            }
        }

        return rawRows
    }

    /**
     * Y-proximity strategy (fallback).
     *
     * Groups all OCR lines within the data region by vertical proximity, then extracts name
     * and score from each group using column x-ranges. This is the original algorithm and
     * is used when a layout does not specify `strategy: score_anchored`.
     */
    private fun buildYProximityRows(
        lines: List<OcrLine>,
        screenWidth: Int,
        screenHeight: Int,
        topBoundary: Int,
        bottomBoundary: Int,
        layout: ScreenLayout,
        rowTolerance: Int,
        wordGapPx: Int
    ): MutableList<RawRow> {
        val rawRows = mutableListOf<RawRow>()

        val rows = mutableMapOf<Int, MutableList<OcrLine>>()
        for (line in lines) {
            if (line.top < topBoundary || line.bottom > bottomBoundary) continue
            val cleanText = line.text.trim()
            // Skip standalone alliance tags like "[PoWr]" but keep merged elements
            // like "[PoWr] PlayerName" where the tag is followed by actual content.
            if (cleanText.startsWith("[") && cleanText.endsWith("]")) continue
            val existingKey = rows.keys.find { abs(it - line.top) < rowTolerance }
            val key = existingKey ?: line.top
            rows.getOrPut(key) { mutableListOf() }.add(line)
        }

        for ((_, rowLines) in rows.toSortedMap()) {
            val sorted = rowLines.sortedBy { it.left }
            var name = ""
            var score = ""

            for (col in layout.columns) {
                val minX = (col.minXRatio * screenWidth).toInt()
                val maxX = (col.maxXRatio * screenWidth).toInt()
                // For the name column, include any element whose right edge reaches into
                // the column even if its left edge starts before minX. This handles merged
                // OCR elements like "[PoWr] PlayerName" that begin from the rank-column
                // area (x < minX) but extend across the name column.
                val matchingBlocks = sorted.filter { line ->
                    if (col.type == ColumnType.NAME)
                        line.right > minX && line.left <= maxX
                    else
                        line.left >= minX && line.left <= maxX
                }

                when (col.type) {
                    ColumnType.NAME -> {
                        name = assembleAndCleanName(matchingBlocks, wordGapPx)
                    }
                    ColumnType.SCORE -> {
                        // Each recognizer may produce a duplicate detection for the same number.
                        // Concatenating them causes values like "1448634" + "14486434" = "144863414486434".
                        // Take the longest numeric string from any single block instead.
                        score = matchingBlocks
                            .map { it.text.replace(Regex("[^0-9]"), "") }
                            .filter { it.isNotEmpty() }
                            .maxByOrNull { it.length } ?: ""
                    }
                    ColumnType.IGNORE -> {}
                }
            }

            // Crash token recovery: when a digit-ending name is visually adjacent to the
            // score, OCR may merge them into one bounding box (e.g. "Ruthless54323,045,000").
            // The merged token lands in the name column and the score column is empty.
            var candidates = emptyList<Pair<String, String>>()
            if (score.isEmpty() && name.isNotEmpty()) {
                val preprocessed = name
                    .replace(Regex("(\\d),\\s+(\\d)"), "$1,$2")
                    .let { s ->
                        val last = s.indexOfLast { it.isDigit() }
                        if (last >= 0 && last < s.length - 1) s.substring(0, last + 1) else s
                    }
                candidates = crashSplits(preprocessed)
                if (candidates.isNotEmpty()) {
                    name  = cleanName(candidates[0].first)
                    score = candidates[0].second
                }
            }

            val scoreValue = score.toLongOrNull() ?: 0L
            if (name.isNotEmpty() && score.isNotEmpty() && scoreValue >= layout.rowClustering.minScore) {
                val rowBounds = rowLines.fold(null as Rect?) { acc, line ->
                    if (acc == null) Rect(line.left, line.top, line.right, line.bottom)
                    else acc.also { it.union(line.boundingBox) }
                }
                rawRows.add(RawRow(name, score, candidates, rowBounds))
            }
        }

        return rawRows
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Joins a list of [OcrLine]s into a single string, inserting a space between adjacent
     * lines only when the horizontal gap between them exceeds [wordGapPx]. Lines closer
     * together than [wordGapPx] are concatenated directly, which prevents a spurious space
     * being inserted into the middle of a word when OCR splits one glyph group across two
     * bounding boxes.
     */
    private fun joinLines(lines: List<OcrLine>, wordGapPx: Int): String {
        if (lines.isEmpty()) return ""
        val sb = StringBuilder()
        var prevRight = -1
        for (line in lines) {
            if (prevRight >= 0 && (line.left - prevRight) > wordGapPx) {
                sb.append(' ')
            }
            sb.append(line.text)
            prevRight = line.right
        }
        return sb.toString()
    }

    /**
     * Strips rank numbers, role indicators (R1–R5), alliance tags like [PoWr], and
     * trailing punctuation from a raw name string.
     */
    private fun cleanName(raw: String): String = raw
        .replace(Regex("^\\d+\\s+"), "")           // strip leading rank number + space
        .replace(Regex("\\b[Rr][1-5]\\b"), "")     // strip role indicators
        .replace(Regex("^\\d+"), "")               // strip any remaining leading digits
        .replace(Regex("\\s+\\[.*"), "")           // strip trailing " [TAG] alliance name" block — handles
                                                   //   rows where the alliance sub-line was merged AFTER the
                                                   //   player name (e.g. "Truckatroy [PoWr] Pantheon of Wrath")
        .replace(Regex("^.*?[]】〕」』)]\\s*"), "")  // strip everything up to the first closing bracket —
                                                   //   handles leading alliance tags [PoWr], OCR-mangled
                                                   //   opening (.3PoWr]), profile-pic prefix text, and
                                                   //   CJK bracket variants (】〕」』)
        .replace(Regex("\\s+\\d+$"), "")           // strip trailing score-bleed digits (e.g. "91" from "OnlyJesus316 91")
        .replace(Regex("[^\\w\\s]+$"), "")          // strip trailing punctuation
        .trim()

    /** Joins the lines with gap-aware spacing and applies [cleanName] in one step. */
    private fun assembleAndCleanName(lines: List<OcrLine>, wordGapPx: Int): String =
        cleanName(joinLines(lines, wordGapPx))

    /**
     * Builds the bounding box for a player row by unioning all name-line boxes with
     * the score line's bounding box.
     */
    private fun buildRowBounds(nameLines: List<OcrLine>, scoreBounds: Rect): Rect {
        val bounds = Rect(scoreBounds)
        for (line in nameLines) bounds.union(line.boundingBox)
        return bounds
    }

    /**
     * Finds the exact bounding box of the OCR elements that spell out a tab item's
     * signal text within [line].
     *
     * For each signal string, every contiguous sub-sequence of elements (length 1 up to
     * the number of signal words) is checked to see whether their combined text contains
     * all of the signal's words. When multiple positions match — which happens when the
     * same word appears on the line more than once (e.g. "Ranking" for three period tabs)
     * — [tabItem.xHint] picks the match whose centre is closest.
     *
     * Falls back to an x_hint-centred window when the line has no element-level data.
     */
    private fun findTabElementBounds(
        line: OcrLine,
        tabItem: TabItem,
        screenWidth: Int
    ): android.graphics.Rect {
        val elements = line.elements
        val targetX   = (tabItem.xHint * screenWidth).toInt()

        if (elements.isNotEmpty()) {
            var bestBounds: android.graphics.Rect? = null
            var bestDist = Int.MAX_VALUE

            for (sig in tabItem.signals) {
                val sigWords = sig.replace(Regex("[^a-zA-Z0-9\\s]"), " ")
                    .trim().lowercase()
                    .split(Regex("\\s+"))
                    .filter { it.isNotEmpty() }
                if (sigWords.isEmpty()) continue

                // Try every contiguous span of 1..sigWords.size elements.
                // A span matches when its combined text contains every signal word.
                // Using "contains all words" rather than strict positional matching
                // handles merged OCR elements (e.g. "WeeklyRanking" as one token).
                val maxSpan = sigWords.size.coerceAtMost(elements.size)
                for (spanLen in 1..maxSpan) {
                    for (start in 0..elements.size - spanLen) {
                        val span    = elements.subList(start, start + spanLen)
                        val combined = span.joinToString(" ") {
                            it.text.replace(Regex("[^a-zA-Z0-9]"), " ").lowercase()
                        }
                        if (!sigWords.all { combined.contains(it) }) continue

                        val bounds = span.fold(
                            android.graphics.Rect(span[0].boundingBox)
                        ) { acc, e -> acc.also { it.union(e.boundingBox) } }

                        val dist = kotlin.math.abs((bounds.left + bounds.right) / 2 - targetX)
                        if (dist < bestDist) {
                            bestDist   = dist
                            bestBounds = bounds
                        }
                    }
                }
            }

            if (bestBounds != null) return bestBounds
        }

        // Fallback: x_hint-centred window (used when element data is unavailable)
        val cx = targetX
        val hw = (screenWidth * 0.08).toInt()
        return android.graphics.Rect(
            (cx - hw).coerceAtLeast(0),
            line.boundingBox.top,
            (cx + hw).coerceAtMost(screenWidth),
            line.boundingBox.bottom
        )
    }

    companion object {
        /**
         * Returns all valid crash-token name/score splits for [text], ordered by
         * ascending score (rightmost split = smallest score = index 0).
         *
         * A crash token occurs when OCR merges a digit-ending player name with a
         * comma-grouped score (e.g. "Ruthless54323,045,000"). We find every position
         * where the suffix is a valid comma-grouped integer and the prefix still
         * contains at least one letter and no commas.
         */
        internal fun crashSplits(text: String): List<Pair<String, String>> {
            if (text.none { it.isLetter() }) return emptyList()
            val scorePattern = Regex("""^\d{1,3}(?:,\d{3})+$""")
            val splits = mutableListOf<Pair<String, String>>()
            for (i in 1 until text.length) {
                val suffix = text.substring(i)
                if (!scorePattern.matches(suffix)) continue
                val prefix = text.substring(0, i).trim()
                if (prefix.any { it.isLetter() } && !prefix.contains(',')) {
                    splits.add(prefix to suffix.replace(",", ""))
                }
            }
            return splits.sortedBy { it.second.toLongOrNull() ?: Long.MAX_VALUE }
        }
    }
}
