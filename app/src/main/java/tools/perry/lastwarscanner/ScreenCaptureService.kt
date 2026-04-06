package tools.perry.lastwarscanner

import android.app.*
import android.content.Context
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
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.*
import tools.perry.lastwarscanner.image.ImageUtils
import tools.perry.lastwarscanner.model.AppDatabase
import tools.perry.lastwarscanner.model.PlayerScoreEntity
import tools.perry.lastwarscanner.ocr.OcrParser
import tools.perry.lastwarscanner.ocr.OcrProcessor
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
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
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
            overlayView?.setBackgroundColor(Color.parseColor("#FF00FF"))
            overlayView?.alpha = 0.5f
            handler.postDelayed({ 
                overlayView?.setBackgroundColor(Color.TRANSPARENT)
                overlayView?.alpha = 1.0f
            }, 150)
        }
    }

    /**
     * Handles service start commands, receiving the screen capture permission result.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA)
        }

        if (resultCode == Activity.RESULT_OK && data != null) {
            db = AppDatabase.getDatabase(this)
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
            val channel = NotificationChannel(CHANNEL_ID, "Screen Capture Service", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Last War Scanner")
            .setContentText("Scanning screen for ranking data...")
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
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
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
        } catch (e: Exception) {
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
        val image = try { reader.acquireLatestImage() } catch (e: Exception) { null }
        if (image == null) return

        isProcessing.set(true)
        sendScanningBroadcast(true)
        flashOverlay()

        serviceScope.launch(Dispatchers.IO) {
            var bitmap: Bitmap? = null
            try {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * image.width

                bitmap = Bitmap.createBitmap(image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(buffer)
                image.close()

                val inputImage = InputImage.fromBitmap(bitmap, 0)
                ocrProcessor.process(inputImage, onSuccess = { lines ->
                    val result = ocrParser.parse(lines, bitmap.width, bitmap.height)
                    if (result.isConfirmedRankingPage) {
                        var activeDay = "Unknown"
                        val layoutId = result.layout?.id ?: ""

                        val candidates = mutableListOf<Pair<String, Float>>()
                        for (tab in result.dayTabs) {
                            val pct = if (layoutId == "strength_ranking") {
                                ImageUtils.getColorPercentage(bitmap, tab.bounds) { r, g, b -> ImageUtils.isOrange(r, g, b) }
                            } else {
                                ImageUtils.getColorPercentage(bitmap, tab.bounds) { r, g, b -> ImageUtils.isWhite(r, g, b) }
                            }
                            if (pct > 0.10f) candidates.add(tab.day to pct)
                        }

                        if (candidates.isNotEmpty()) {
                            activeDay = candidates.maxByOrNull { it.second }?.first ?: "Unknown"
                        }

                        if (result.players.isNotEmpty()) {
                            serviceScope.launch {
                                for (player in result.players) {
                                    val scoreLong = player.score.toLongOrNull() ?: continue
                                    var latest = db.playerScoreDao().getLatestPlayerEntry(player.name)
                                    
                                    if (latest == null) {
                                        val playersWithSameScore = db.playerScoreDao().getPlayersByScore(scoreLong)
                                        for (candidate in playersWithSameScore) {
                                            if (isSimilar(player.name, candidate.name)) {
                                                latest = candidate
                                                break
                                            }
                                        }
                                    }

                                    val nameChanged = latest != null && player.name.length < latest.name.length && latest.name.contains(player.name)
                                    
                                    if (latest == null || latest.score != scoreLong || nameChanged) {
                                        val finalName = if (nameChanged) player.name else (latest?.name ?: player.name)
                                        db.playerScoreDao().insert(PlayerScoreEntity(
                                            name = finalName,
                                            score = scoreLong,
                                            day = activeDay,
                                            timestamp = System.currentTimeMillis()
                                        ))
                                    }
                                }
                                sendResultBroadcast(activeDay, false)
                                isProcessing.set(false)
                                bitmap.recycle()
                            }
                        } else {
                            sendScanningBroadcast(false)
                            isProcessing.set(false)
                            bitmap.recycle()
                        }
                    } else {
                        sendScanningBroadcast(false)
                        isProcessing.set(false)
                        bitmap.recycle()
                    }
                }, onError = { 
                    Log.e(TAG, "OCR Error: ${it.message}") 
                    sendScanningBroadcast(false)
                    isProcessing.set(false)
                    bitmap?.recycle()
                })
            } catch (e: Exception) {
                Log.e(TAG, "Capture Error: ${e.message}")
                try { image.close() } catch (ex: Exception) {}
                sendScanningBroadcast(false)
                isProcessing.set(false)
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
     * @param isScanning True if a scan is currently being processed.
     */
    private fun sendResultBroadcast(day: String, isScanning: Boolean) {
        val intent = Intent(ACTION_OCR_RESULT)
        intent.putExtra(EXTRA_DAY, day)
        intent.putExtra(EXTRA_SCANNING, isScanning)
        sendBroadcast(intent)
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
