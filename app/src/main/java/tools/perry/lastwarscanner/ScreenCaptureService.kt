package tools.perry.lastwarscanner

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.Activity.RESULT_CANCELED
import android.app.Activity.RESULT_OK
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.*
import tools.perry.lastwarscanner.image.ImageUtils
import tools.perry.lastwarscanner.model.AppDatabase
import tools.perry.lastwarscanner.model.PlayerScoreEntity
import tools.perry.lastwarscanner.network.RosterCache
import tools.perry.lastwarscanner.ocr.OcrParser
import tools.perry.lastwarscanner.ocr.OcrProcessor
import tools.perry.lastwarscanner.ocr.ScreenDefinitionLoader
import tools.perry.lastwarscanner.ocr.ScreenLayout
import android.graphics.Rect
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * Foreground service that captures the screen content, performs OCR to identify player scores,
 * and saves the results into the local database.
 * It uses [MediaProjection] for screen capture and [OcrProcessor] for text extraction.
 */
class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())
    private val ocrProcessor = OcrProcessor()
    private val ocrParser = OcrParser()
    private lateinit var db: AppDatabase
    private var screenLayouts: List<ScreenLayout> = emptyList()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isProcessing = AtomicBoolean(false)

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null

    companion object {
        private const val TAG = "LastWarScanner"
        private const val NOTIFICATION_ID = 101
        private const val CHANNEL_ID = "screen_capture_channel"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val ACTION_OCR_RESULT = "tools.perry.lastwarscanner.OCR_RESULT"
        const val EXTRA_DAY = "extra_day"
        const val EXTRA_SCANNING = "extra_scanning"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Initializes the service and starts it as a foreground service with a notification.
     */
    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startForegroundService()
    }

    /**
     * Sets up a transparent overlay view at the top of the screen.
     * This overlay is used to provide visual feedback (flashing) when a scan is performed.
     */
    private fun setupOverlay() {
        if (overlayView != null) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            120, // Height of the top flash bar
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
        }

        overlayView = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
        }
        
        try {
            windowManager.addView(overlayView, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay: ${e.message}")
        }
    }

    /**
     * Briefly changes the overlay color to provide visual feedback that a screen capture has occurred.
     */
    private fun flashOverlay() {
        handler.post {
            overlayView?.setBackgroundColor(ContextCompat.getColor(this, R.color.flash_overlay))
            handler.postDelayed({ 
                overlayView?.setBackgroundColor(Color.TRANSPARENT)
            }, 150)
        }
    }

    /**
     * Handles service start commands, receiving the screen capture permission result.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, RESULT_CANCELED) ?: RESULT_CANCELED
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA)
        }

        if (resultCode == RESULT_OK && data != null) {
            db = AppDatabase.getDatabase(this)
            screenLayouts = ScreenDefinitionLoader.loadAll(this)
            setupOverlay()
            handler.postDelayed({ setupMediaProjection(resultCode, data) }, 100)
        } else {
            stopSelf()
        }

        return START_NOT_STICKY
    }

    /**
     * Configures the foreground service notification and starts the service in the foreground.
     */
    private fun startForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * Sets up the [MediaProjection] and [VirtualDisplay] for screen capture.
     * @param resultCode The result code from the screen capture permission request.
     * @param data The intent data from the screen capture permission request.
     */
    private fun setupMediaProjection(resultCode: Int, data: Intent) {
        val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            mediaProjection = mpManager.getMediaProjection(resultCode, data)
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, handler)

            val metrics = resources.displayMetrics
            imageReader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 5)
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenCapture", metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader?.surface, null, handler
            )
            handler.postDelayed(captureRunnable, 1000)
        } catch (_: Exception) {
            stopSelf()
        }
    }

    /**
     * Runnable that periodically triggers a screen capture.
     */
    private val captureRunnable = object : Runnable {
        override fun run() {
            if (!isProcessing.get()) {
                captureScreen()
            }
            handler.postDelayed(this, 1500)
        }
    }

    /**
     * Captures the current screen content, converts it to a bitmap, and processes it via OCR.
     * Identified player scores are matched with existing records and saved to the database.
     */
    private fun captureScreen() {
        val reader = imageReader ?: return
        val image = try { reader.acquireLatestImage() } catch (_: Exception) { null }
        if (image == null) return

        isProcessing.set(true)
        sendScanningBroadcast(true)
        flashOverlay()

        serviceScope.launch(Dispatchers.IO) {
            var bitmap: Bitmap? = null
            var ocrBitmap: Bitmap? = null
            try {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * image.width

                bitmap = createBitmap(image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(buffer)
                image.close()

                // Pre-OCR hint: fast single-pixel colour check before running the full
                // (expensive) OCR pipeline. Skip this frame only if every layout that
                // has a pre_ocr_hint fails its check — layouts without a hint are assumed
                // to always be potential matches, so OCR still runs for those.
                val shouldRunOcr = screenLayouts.any { layout ->
                    layout.preOcrHint == null || checkPreOcrHint(bitmap, layout.preOcrHint)
                }
                if (!shouldRunOcr) {
                    sendScanningBroadcast(false)
                    isProcessing.set(false)
                    bitmap.recycle()
                    return@launch
                }

                // OCR runs on the contrast-adjusted image only — matching the cloud OCR service
                // (lastwar-ocr-service run_ocr → enhance_for_ocr). enhanceForOcr now applies the same
                // per-channel autocontrast(cutoff=1) the cloud uses: it preserves colour and cleanly
                // separates the low-contrast rows (top-3 ranks, pinned self-row). Single pass
                // (5 recognizers) keeps it fast. The original bitmap is kept for colour tab detection.
                ocrBitmap = enhanceForOcr(bitmap)
                val inputImage = InputImage.fromBitmap(ocrBitmap, 0)
                ocrProcessor.process(inputImage, onSuccess = { lines ->
                    val result = ocrParser.parse(lines, bitmap.width, bitmap.height, screenLayouts)
                    if (result.isConfirmedRankingPage) {
                        var activeDay = "Unknown"
                        val layout = result.layout
                        val bboxPad = layout?.tabIndicatorBboxPaddingFraction ?: 0.007f
                        val padPx = ((bitmap.width + bitmap.height) / 2 * bboxPad).toInt()
                        val tabGroupConfigs = layout?.tabGroupConfigs ?: emptyMap()

                        fun sampleTab(tab: OcrParser.DayTab, useOrange: Boolean, minFraction: Float): Pair<String, Float>? {
                            val paddedBounds = android.graphics.Rect(
                                (tab.bounds.left   - padPx).coerceAtLeast(0),
                                (tab.bounds.top    - padPx).coerceAtLeast(0),
                                (tab.bounds.right  + padPx).coerceAtMost(bitmap.width),
                                (tab.bounds.bottom + padPx).coerceAtMost(bitmap.height)
                            )
                            val hsvColor = layout?.tabIndicatorHsvColor
                            val pct = if (useOrange) {
                                if (hsvColor != null) {
                                    // Use YAML-defined HSV range — more accurate than the hardcoded RGB check
                                    ImageUtils.getColorPercentage(bitmap, paddedBounds) { r, g, b ->
                                        checkHsv(r, g, b, hsvColor)
                                    }
                                } else {
                                    val orange = tools.perry.lastwarscanner.ocr.ConstantsLoader.load(applicationContext).orangeRgb
                                    ImageUtils.getColorPercentage(bitmap, paddedBounds) { r, g, b -> orange.matches(r, g, b) }
                                }
                            } else {
                                val white = tools.perry.lastwarscanner.ocr.ConstantsLoader.load(applicationContext).whiteRgb
                                ImageUtils.getColorPercentage(bitmap, paddedBounds) { r, g, b -> white.matches(r, g, b) }
                            }
                            Log.d(TAG, "  sampleTab raw: day=${tab.day} pct=${"%.4f".format(pct)} minFraction=$minFraction paddedBounds=[${paddedBounds.left},${paddedBounds.top},${paddedBounds.right},${paddedBounds.bottom}]")
                            return if (pct > minFraction) tab.day to pct else null
                        }

                        if (tabGroupConfigs.isNotEmpty()) {
                            val groupCandidates = mutableMapOf<String, MutableList<Pair<String, Float>>>()
                            for (tab in result.dayTabs) {
                                val groupId = tab.group.ifEmpty { continue }
                                val groupConfig = tabGroupConfigs[groupId] ?: continue
                                val useOrange = groupConfig.strategy == "color_fraction"
                                val sampled = sampleTab(tab, useOrange, groupConfig.minFraction)
                                Log.d(TAG, "tab sample: group=$groupId id=${tab.day} bounds=${tab.bounds.left}..${tab.bounds.right} y=${tab.bounds.top}..${tab.bounds.bottom} pct=${sampled?.second} passed=${sampled != null}")
                                sampled?.let { groupCandidates.getOrPut(groupId) { mutableListOf() }.add(it) }
                            }
                            val winners = tabGroupConfigs.keys.sorted()
                                .mapNotNull { gId -> groupCandidates[gId]?.maxByOrNull { it.second }?.first }
                            Log.d(TAG, "tab winners: $winners (need ${tabGroupConfigs.size})")
                            if (winners.size == tabGroupConfigs.size) {
                                activeDay = winners.joinToString("_")
                            }
                        } else {
                            val useOrange = layout?.tabIndicatorStrategy == "color_fraction"
                            val minFraction = layout?.tabIndicatorMinFraction ?: 0.10f
                            val minGap = layout?.tabIndicatorMinGap ?: 0.04f
                            val candidates = result.dayTabs.mapNotNull { sampleTab(it, useOrange, minFraction) }
                            if (candidates.isNotEmpty()) {
                                if (useOrange) {
                                    // color_fraction: each tab passes independently, pick the strongest
                                    activeDay = candidates.maxByOrNull { it.second }?.first ?: "Unknown"
                                } else {
                                    // brightest: require a clear gap between winner and runner-up
                                    // to avoid picking the wrong day tab when two tabs look similar
                                    val sorted = candidates.sortedByDescending { it.second }
                                    val winner = sorted[0]
                                    val runnerUp = sorted.getOrNull(1)
                                    if (runnerUp == null || (winner.second - runnerUp.second) >= minGap) {
                                        activeDay = winner.first
                                    }
                                }
                            }
                        }

                        if (result.players.isNotEmpty()) {
                            serviceScope.launch {
                                // Fetch the full member roster once per frame. Used as a
                                // score-independent fallback when exact and score-based lookups
                                // both fail (e.g. OCR noise variant of a known name, or the
                                // player's score changed since the last scan).
                                val knownNames = db.playerScoreDao().getAllKnownNames()
                                // Web roster: members and aliases scoped to the current user
                                // (see lastwar-screen-definitions/README.md, "Name resolution").
                                // Lookup 4 runs the alias-priority match first and only falls
                                // back to fuzzy name matching when no alias resolves.
                                val rosterMembers = RosterCache.getMembers(applicationContext)
                                val rosterNames = rosterMembers.map { it.name }

                                var prevScore = -1L  // last accepted row's score; the leaderboard is score-descending
                                for (idx in result.players.indices) {
                                    val player = result.players[idx]
                                    // Resolve crash-token ambiguity: if the row had multiple
                                    // valid name/score splits, find the one that best matches
                                    // an existing DB record (or a roster alias) before committing.
                                    val resolvedPlayer = if (player.candidates.size > 1) {
                                        resolveCrashCandidate(player, knownNames, rosterMembers) ?: player
                                    } else player

                                    val scoreLong = resolvedPlayer.score.toLongOrNull() ?: continue

                                    // Score-sanity: the list is score-descending, so a score many
                                    // times the row above it is a digit-inflation misread (e.g.
                                    // 4,849,644 read as 48,496,441). Skip that reading so the sync's
                                    // GROUP BY MAX(score) can't lock in the inflated value; the player
                                    // is recovered from a clean frame. 8x clears legit gaps and the
                                    // pinned own-row (~3x), which is last and never checked.
                                    if (idx < result.players.size - 1 && prevScore > 0 && scoreLong > prevScore * 8) {
                                        Log.d(TAG, "  score-sanity skip: \"${resolvedPlayer.name}\" score=$scoreLong >> rowAbove=$prevScore")
                                        continue
                                    }
                                    prevScore = scoreLong

                                    // Identity: resolve to a roster member FIRST (the canonical
                                    // dedup key), so two garbled OCR reads of the same member collapse
                                    // under one name in the GROUP BY sync. Exact/alias first, then
                                    // fuzzy against roster names.
                                    var canonicalName = resolvedPlayer.name
                                    if (rosterMembers.isNotEmpty()) {
                                        val aliasMatch = tools.perry.lastwarscanner.ocr.RosterAliasResolver
                                            .resolve(resolvedPlayer.name, rosterMembers)
                                        val rosterName = aliasMatch?.member?.name
                                            ?: uniqueRosterMatch(resolvedPlayer.name, rosterNames)
                                        if (rosterName != null) {
                                            canonicalName = rosterName
                                            Log.d(TAG, "  roster ${aliasMatch?.matchType ?: "fuzzy"}: \"${resolvedPlayer.name}\" → \"$rosterName\"")
                                        }
                                    }

                                    // Existing DB row for this identity (keyed on canonical name).
                                    var latest = db.playerScoreDao().getLatestPlayerEntry(canonicalName)

                                    // Fallback for rows that matched no roster member: keep the
                                    // prior score-match then DB-name-fuzzy heuristics so unknown /
                                    // not-yet-rostered players still merge across frames.
                                    if (latest == null && canonicalName == resolvedPlayer.name) {
                                        val byScore = db.playerScoreDao().getPlayersByScore(scoreLong)
                                            .firstOrNull { isSimilar(resolvedPlayer.name, it.name) }
                                        if (byScore != null) {
                                            latest = byScore
                                            canonicalName = byScore.name
                                        } else {
                                            val knownMatch = knownNames.firstOrNull { isSimilar(resolvedPlayer.name, it) }
                                            if (knownMatch != null) {
                                                latest = db.playerScoreDao().getLatestPlayerEntry(knownMatch)
                                                canonicalName = if (
                                                    resolvedPlayer.name.length < knownMatch.length &&
                                                    knownMatch.contains(resolvedPlayer.name)
                                                ) resolvedPlayer.name else knownMatch
                                            }
                                        }
                                    }

                                    val finalName = canonicalName

                                    // Always save/refresh the crop so the review screen has a
                                    // current image even when the score hasn't changed.
                                    val snapshotPath = resolvedPlayer.rowBounds?.let { bounds ->
                                        saveRowCrop(bitmap, bounds, finalName, activeDay)
                                    }

                                    Log.d(TAG, "player: ocr=\"${resolvedPlayer.name}\" → final=\"$finalName\" score=$scoreLong latestName=\"${latest?.name}\" latestScore=${latest?.score}")

                                    if (latest == null || latest.score != scoreLong) {
                                        Log.d(TAG, "  → INSERT name=\"$finalName\" score=$scoreLong day=$activeDay")
                                        db.playerScoreDao().insert(PlayerScoreEntity(
                                            name = finalName,
                                            score = scoreLong,
                                            day = activeDay,
                                            timestamp = System.currentTimeMillis(),
                                            rowSnapshotPath = snapshotPath
                                        ))
                                    } else if (snapshotPath != null) {
                                        // Score unchanged — just refresh the snapshot path.
                                        db.playerScoreDao().updateSnapshotPath(finalName, activeDay, snapshotPath)
                                    }
                                }
                                sendResultBroadcast(activeDay)
                                isProcessing.set(false)
                                ocrBitmap?.recycle()
                                bitmap.recycle()
                            }
                        } else {
                            sendScanningBroadcast(false)
                            isProcessing.set(false)
                            ocrBitmap?.recycle()
                            bitmap.recycle()
                        }
                    } else {
                        sendScanningBroadcast(false)
                        isProcessing.set(false)
                        ocrBitmap?.recycle()
                        bitmap.recycle()
                    }
                }, onError = { exception ->
                    Log.e(TAG, "OCR Error: ${exception.message}")
                    sendScanningBroadcast(false)
                    isProcessing.set(false)
                    ocrBitmap?.recycle()
                    bitmap.recycle()
                }, enhancedImage = null)
            } catch (e: Exception) {
                Log.e(TAG, "Capture Error: ${e.message}")
                try { image.close() } catch (_: Exception) {}
                sendScanningBroadcast(false)
                isProcessing.set(false)
                ocrBitmap?.recycle()
                bitmap?.recycle()
            }
        }
    }

    /**
     * Sends a broadcast intent to notify listeners (e.g., MainActivity) about the scanning status.
     * @param isScanning True if a scan is currently being processed.
     */
    private fun sendScanningBroadcast(isScanning: Boolean) {
        val intent = Intent(ACTION_OCR_RESULT)
        intent.putExtra(EXTRA_SCANNING, isScanning)
        sendBroadcast(intent)
    }

    /**
     * Sends a broadcast intent with the detected active day and scanning status.
     * @param day The name of the detected active tab/day.
     */
    private fun sendResultBroadcast(day: String) {
        val intent = Intent(ACTION_OCR_RESULT)
        intent.putExtra(EXTRA_DAY, day)
        intent.putExtra(EXTRA_SCANNING, false)
        sendBroadcast(intent)
    }

    /**
     * Per-channel contrast stretch matching the cloud OCR service's
     * `ImageOps.autocontrast(img, cutoff=1)` (lastwar-ocr-service app/utils/image_utils.py).
     * For each of R/G/B independently it clips the lightest and darkest 1% of pixels, then
     * linearly stretches the surviving range to 0–255. Colour is preserved (NOT grayscale):
     * the top-3 rank rows (gold/silver/bronze) and the pinned self-player row use coloured
     * text on coloured backgrounds that differ mainly in the blue channel, and stretching per
     * channel roughly doubles that separation so OCR reads the score. Grayscaling instead
     * collapses that separation and erases the text (regressed to ~21/97). The original bitmap
     * is kept unchanged for the colour-based tab detection.
     */
    private fun enhanceForOcr(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        val histR = IntArray(256)
        val histG = IntArray(256)
        val histB = IntArray(256)
        for (p in pixels) {
            histR[(p ushr 16) and 0xFF]++
            histG[(p ushr 8) and 0xFF]++
            histB[p and 0xFF]++
        }

        val cutoff = pixels.size / 100   // 1% per PIL cutoff=1
        val lutR = buildAutocontrastLut(histR, cutoff)
        val lutG = buildAutocontrastLut(histG, cutoff)
        val lutB = buildAutocontrastLut(histB, cutoff)

        for (i in pixels.indices) {
            val p = pixels[i]
            pixels[i] = (p and 0xFF000000.toInt()) or
                (lutR[(p ushr 16) and 0xFF] shl 16) or
                (lutG[(p ushr 8) and 0xFF] shl 8) or
                lutB[p and 0xFF]
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    /**
     * Builds a 256-entry LUT that clips [cutoff] pixels from each tail of [hist] and linearly
     * stretches the surviving [lo,hi] range to 0–255 — the per-channel core of PIL's
     * autocontrast. Returns the identity map for a flat/degenerate channel.
     */
    private fun buildAutocontrastLut(hist: IntArray, cutoff: Int): IntArray {
        var lo = 0
        var acc = 0
        for (v in 0..255) { acc += hist[v]; if (acc > cutoff) { lo = v; break } }
        var hi = 255
        acc = 0
        for (v in 255 downTo 0) { acc += hist[v]; if (acc > cutoff) { hi = v; break } }

        val lut = IntArray(256)
        if (hi <= lo) {
            for (v in 0..255) lut[v] = v
        } else {
            val scale = 255.0 / (hi - lo)
            for (v in 0..255) {
                lut[v] = when {
                    v <= lo -> 0
                    v >= hi -> 255
                    else -> ((v - lo) * scale).toInt().coerceIn(0, 255)
                }
            }
        }
        return lut
    }

    /**
     * Tries each crash-token candidate against the existing DB roster, in priority order:
     *   1. Exact name match in the DB.
     *   2. Score match + Levenshtein fuzzy name match.
     *   3. Fuzzy name match against [knownNames] regardless of score — handles the case
     *      where the score has changed since the last scan of this player.
     *
     * Returns the resolved [PlayerScore] with the canonical DB name, or null if no
     * candidate matched (caller should fall back to candidates[0]).
     */
    private suspend fun resolveCrashCandidate(
        player: tools.perry.lastwarscanner.model.PlayerScore,
        knownNames: List<String>,
        rosterMembers: List<tools.perry.lastwarscanner.network.MemberSummary>
    ): tools.perry.lastwarscanner.model.PlayerScore? {
        // Step 1: exact name match
        for (candidate in player.candidates) {
            if (db.playerScoreDao().getLatestPlayerEntry(candidate.name) != null) {
                return player.copy(name = candidate.name, score = candidate.score, candidates = emptyList())
            }
        }
        // Step 2: score match + fuzzy name match
        for (candidate in player.candidates) {
            val s = candidate.score.toLongOrNull() ?: continue
            for (dbEntry in db.playerScoreDao().getPlayersByScore(s)) {
                if (isSimilar(candidate.name, dbEntry.name)) {
                    return player.copy(name = candidate.name, score = candidate.score, candidates = emptyList())
                }
            }
        }
        // Step 3: fuzzy name match against all known members (score-independent).
        // The candidate with the name closest to a roster entry wins; use the canonical
        // DB name so subsequent dedup logic resolves against the correct record.
        for (candidate in player.candidates) {
            val match = knownNames.find { isSimilar(candidate.name, it) }
            if (match != null) {
                return player.copy(name = match, score = candidate.score, candidates = emptyList())
            }
        }
        // Step 4: web-roster alias resolver. Mirrors the backend's resolveOCRPlayer
        // walk over candidates × resolveMemberAlias (Exact → Personal → Global → OCR).
        if (rosterMembers.isNotEmpty()) {
            val pairs = player.candidates.map { it.name to it.score }
            val matched = tools.perry.lastwarscanner.ocr.RosterAliasResolver
                .resolveCandidates(pairs, rosterMembers)
            if (matched != null) {
                val (m, chosen) = matched
                return player.copy(name = m.member.name, score = chosen.second, candidates = emptyList())
            }
        }
        return null
    }

    /**
     * Returns true if the RGB pixel matches the given HSV colour range.
     * Hue is normalised to [0, 1] (Android's [Color.RGBToHSV] returns 0–360).
     */
    private fun checkHsv(r: Int, g: Int, b: Int, config: tools.perry.lastwarscanner.ocr.HsvColorConfig): Boolean {
        val hsv = FloatArray(3)
        android.graphics.Color.RGBToHSV(r, g, b, hsv)
        val h = hsv[0] / 360f
        return h >= config.hMin && h <= config.hMax && hsv[1] >= config.sMin && hsv[2] >= config.vMin
    }

    /**
     * Checks the single pixel at the layout's pre_ocr_hint position against the HSV colour
     * range defined in the hint. Returns true if the pixel colour falls within the range,
     * meaning this frame is plausibly showing the layout's screen.
     */
    private fun checkPreOcrHint(bitmap: android.graphics.Bitmap, hint: tools.perry.lastwarscanner.ocr.PreOcrHint): Boolean {
        val x = (hint.xHint * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val y = (hint.yHint * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        val pixel = bitmap.getPixel(x, y)
        val config = tools.perry.lastwarscanner.ocr.HsvColorConfig(
            hMin = hint.hsvHMin, hMax = hint.hsvHMax, sMin = hint.hsvSMin, vMin = hint.hsvVMin
        )
        return checkHsv(android.graphics.Color.red(pixel), android.graphics.Color.green(pixel),
            android.graphics.Color.blue(pixel), config)
    }

    private fun isSimilar(name1: String, name2: String): Boolean {
        val distance = levenshtein(name1.lowercase(), name2.lowercase())
        return distance <= min(3, name1.length / 5 + 1)
    }

    private fun levenshtein(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j
        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = min(min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost)
            }
        }
        return dp[s1.length][s2.length]
    }

    /**
     * Returns the roster name that *uniquely and closely* matches [name], or null when the match
     * is ambiguous or too far. Tighter than [isSimilar], and requires a single unambiguous best
     * candidate so two different garbled OCR reads can't both snap onto the same member (which
     * would silently drop a player). Bias is toward NOT merging: an unmatched read stays distinct
     * — at worst a visible duplicate in the review screen, never a silently-lost player.
     */
    private fun uniqueRosterMatch(name: String, rosterNames: List<String>): String? {
        val needle = name.lowercase()
        val maxDist = minOf(2, name.length / 6 + 1)
        val scored = rosterNames
            .map { it to levenshtein(needle, it.lowercase()) }
            .filter { it.second <= maxDist }
            .sortedBy { it.second }
        if (scored.isEmpty()) return null
        // Require an unambiguous winner: the best must be strictly closer than the runner-up.
        if (scored.size > 1 && scored[0].second == scored[1].second) return null
        return scored[0].first
    }

    /**
     * Crops a player row from the captured bitmap and saves it as a JPEG in internal storage.
     * Files are written to [filesDir]/row_crops/ with a deterministic name (sanitized player
     * name + day), so repeated scans of the same player+day overwrite the previous crop.
     *
     * @return Absolute path of the saved file, or null if saving failed.
     */
    private fun saveRowCrop(bitmap: Bitmap, bounds: Rect, name: String, day: String): String? {
        return try {
            val padding = 8
            val left   = (bounds.left   - padding).coerceIn(0, bitmap.width)
            val top    = (bounds.top    - padding).coerceIn(0, bitmap.height)
            val right  = (bounds.right  + padding).coerceIn(0, bitmap.width)
            val bottom = (bounds.bottom + padding).coerceIn(0, bitmap.height)
            val w = right - left
            val h = bottom - top
            if (w <= 0 || h <= 0) return null

            val crop = Bitmap.createBitmap(bitmap, left, top, w, h)
            val dir = File(filesDir, "row_crops").also { it.mkdirs() }
            val safeName = name.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(48)
            val file = File(dir, "${safeName}_${day}.jpg")
            file.outputStream().use { crop.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            crop.recycle()
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "saveRowCrop failed: ${e.message}")
            null
        }
    }

    /**
     * Cleans up resources, stops media projection, and removes the overlay when the service is destroyed.
     */
    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        handler.removeCallbacks(captureRunnable)
        virtualDisplay?.release()
        mediaProjection?.stop()
        imageReader?.close()
        ocrProcessor.close()
        overlayView?.let { windowManager.removeView(it) }
    }
}
