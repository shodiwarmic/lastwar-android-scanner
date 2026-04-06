package tools.perry.lastwarscanner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tools.perry.lastwarscanner.model.AppDatabase
import tools.perry.lastwarscanner.model.MemberRow
import tools.perry.lastwarscanner.model.PlayerScoreEntity
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * Main activity for the Last War Scanner application.
 * This activity handles the user interface for starting/stopping the screen capture service,
 * displaying the scanned player scores in a sortable list, and exporting the data to CSV.
 */
class MainActivity : AppCompatActivity() {

    /**
     * Enum representing the columns that can be used for sorting the player list.
     */
    enum class SortColumn { PLAYER, MON, TUES, WED, THUR, FRI, SAT, POWER, KILLS, DONATION }

    /**
     * Enum representing the sort direction.
     */
    enum class SortOrder { ASC, DESC }

    private lateinit var tvStatus: TextView
    private lateinit var tvDetectedDay: TextView
    private lateinit var pbScanning: ProgressBar
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnClear: Button
    private lateinit var btnExport: Button
    private lateinit var rvScores: RecyclerView
    private lateinit var adapter: ScoreAdapter

    private lateinit var headerPlayer: TextView
    private lateinit var headerMon: TextView
    private lateinit var headerTues: TextView
    private lateinit var headerWed: TextView
    private lateinit var headerThur: TextView
    private lateinit var headerFri: TextView
    private lateinit var headerSat: TextView
    private lateinit var headerPower: TextView
    private lateinit var headerKills: TextView
    private lateinit var headerDonation: TextView

    private var currentSortColumn = SortColumn.PLAYER
    private var currentSortOrder = SortOrder.ASC
    private var activeTabName = "Unknown"
    private var observationJob: Job? = null

    private lateinit var db: AppDatabase

    /**
     * Receiver for OCR results from the [ScreenCaptureService].
     * Updates the UI status and progress bar when a scan is received.
     */
    private val ocrReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val isScanning = intent?.getBooleanExtra(ScreenCaptureService.EXTRA_SCANNING, false) ?: false
            val day = intent?.getStringExtra(ScreenCaptureService.EXTRA_DAY)
            
            runOnUiThread {
                pbScanning.visibility = if (isScanning) View.VISIBLE else View.INVISIBLE
                if (day != null) {
                    activeTabName = day
                    tvDetectedDay.text = getString(R.string.active_tab_format, activeTabName)
                    val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                    tvStatus.text = getString(R.string.status_scan_received_format, time)
                }
            }
        }
    }

    /**
     * Activity result launcher for requesting screen capture permission.
     * Starts the [ScreenCaptureService] if permission is granted.
     */
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_DATA, result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            updateUi(true)
        } else {
            tvStatus.text = getString(R.string.status_permission_denied)
        }
    }

    /**
     * Initializes the activity, sets up UI components, and starts observing the database.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        db = AppDatabase.getDatabase(this)

        tvStatus = findViewById(R.id.tvStatus)
        tvDetectedDay = findViewById(R.id.tvDetectedDay)
        pbScanning = findViewById(R.id.pbScanning)
        btnStart = findViewById(R.id.btnStartService)
        btnStop = findViewById(R.id.btnStopService)
        btnClear = findViewById(R.id.btnClearLogs)
        btnExport = findViewById(R.id.btnExportCsv)
        rvScores = findViewById(R.id.rvScores)

        headerPlayer = findViewById(R.id.headerPlayer)
        headerMon = findViewById(R.id.headerMon)
        headerTues = findViewById(R.id.headerTues)
        headerWed = findViewById(R.id.headerWed)
        headerThur = findViewById(R.id.headerThur)
        headerFri = findViewById(R.id.headerFri)
        headerSat = findViewById(R.id.headerSat)
        headerPower = findViewById(R.id.headerPower)
        headerKills = findViewById(R.id.headerKills)
        headerDonation = findViewById(R.id.headerDonation)

        setupRecyclerView()
        setupSortHeaders()
        updateHeaderUi()

        btnStart.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri())
                Toast.makeText(this, R.string.overlay_permission_toast, Toast.LENGTH_LONG).show()
                startActivity(intent)
            } else {
                val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                screenCaptureLauncher.launch(mpManager.createScreenCaptureIntent())
            }
        }

        btnStop.setOnClickListener {
            stopService(Intent(this, ScreenCaptureService::class.java))
            updateUi(false)
        }

        btnClear.setOnClickListener {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    db.playerScoreDao().deleteAll()
                }
            }
        }

        btnExport.setOnClickListener { exportToCsv() }

        observeDatabase()
    }

    /**
     * Configures the RecyclerView and its adapter.
     */
    private fun setupRecyclerView() {
        adapter = ScoreAdapter()
        rvScores.layoutManager = LinearLayoutManager(this)
        rvScores.adapter = adapter
    }

    /**
     * Exports the collected player scores to a CSV file and opens a share intent.
     */
    private fun exportToCsv() {
        lifecycleScope.launch {
            val scores = withContext(Dispatchers.IO) {
                db.playerScoreDao().getAllScores().first()
            }
            if (scores.isEmpty()) {
                Toast.makeText(this@MainActivity, R.string.error_no_data_export, Toast.LENGTH_SHORT).show()
                return@launch
            }

            val memberRows = withContext(Dispatchers.Default) { transformToMemberRows(scores) }
            val csvHeader = getString(R.string.csv_header) + "\n"
            val csvBody = memberRows.joinToString("\n") { row ->
                listOf(
                    row.name,
                    row.getScore("Mon") ?: 0L,
                    row.getScore("Tues") ?: 0L,
                    row.getScore("Wed") ?: 0L,
                    row.getScore("Thur") ?: 0L,
                    row.getScore("Fri") ?: 0L,
                    row.getScore("Sat") ?: 0L,
                    row.getScore("Power") ?: 0L,
                    row.getScore("Kills") ?: 0L,
                    row.getScore("Donation") ?: 0L
                ).joinToString(",")
            }

            val fileName = "LastWar_Export_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())}.csv"
            try {
                val file = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
                FileOutputStream(file).use { it.write((csvHeader + csvBody).toByteArray()) }
                
                val uri = FileProvider.getUriForFile(this@MainActivity, "${packageName}.fileprovider", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, getString(R.string.share_csv_title)))
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, getString(R.string.error_export_failed, e.localizedMessage ?: e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Sets up click listeners for the list headers to enable sorting.
     */
    private fun setupSortHeaders() {
        val clickListener = View.OnClickListener { view ->
            val clickedColumn = when (view.id) {
                R.id.headerPlayer -> SortColumn.PLAYER
                R.id.headerMon -> SortColumn.MON
                R.id.headerTues -> SortColumn.TUES
                R.id.headerWed -> SortColumn.WED
                R.id.headerThur -> SortColumn.THUR
                R.id.headerFri -> SortColumn.FRI
                R.id.headerSat -> SortColumn.SAT
                R.id.headerPower -> SortColumn.POWER
                R.id.headerKills -> SortColumn.KILLS
                R.id.headerDonation -> SortColumn.DONATION
                else -> SortColumn.PLAYER
            }

            if (currentSortColumn == clickedColumn) {
                currentSortOrder = if (currentSortOrder == SortOrder.ASC) SortOrder.DESC else SortOrder.ASC
            } else {
                currentSortColumn = clickedColumn
                currentSortOrder = if (clickedColumn == SortColumn.PLAYER) SortOrder.ASC else SortOrder.DESC
            }

            updateHeaderUi()
            observeDatabase() 
        }

        headerPlayer.setOnClickListener(clickListener)
        headerMon.setOnClickListener(clickListener)
        headerTues.setOnClickListener(clickListener)
        headerWed.setOnClickListener(clickListener)
        headerThur.setOnClickListener(clickListener)
        headerFri.setOnClickListener(clickListener)
        headerSat.setOnClickListener(clickListener)
        headerPower.setOnClickListener(clickListener)
        headerKills.setOnClickListener(clickListener)
        headerDonation.setOnClickListener(clickListener)
    }

    /**
     * Updates the visual representation of the column headers to reflect the current sort state.
     */
    private fun updateHeaderUi() {
        val headers = mapOf<SortColumn, Pair<TextView, String>>(
            SortColumn.PLAYER to Pair(headerPlayer, getString(R.string.header_member)),
            SortColumn.MON to Pair(headerMon, getString(R.string.header_mon)),
            SortColumn.TUES to Pair(headerTues, getString(R.string.header_tues)),
            SortColumn.WED to Pair(headerWed, getString(R.string.header_wed)),
            SortColumn.THUR to Pair(headerThur, getString(R.string.header_thur)),
            SortColumn.FRI to Pair(headerFri, getString(R.string.header_fri)),
            SortColumn.SAT to Pair(headerSat, getString(R.string.header_sat)),
            SortColumn.POWER to Pair(headerPower, getString(R.string.header_power)),
            SortColumn.KILLS to Pair(headerKills, getString(R.string.header_kills)),
            SortColumn.DONATION to Pair(headerDonation, getString(R.string.header_donation))
        )

        headers.forEach { (col, pair) ->
            val (view, baseText) = pair
            if (col == currentSortColumn) {
                val arrow = if (currentSortOrder == SortOrder.ASC) " ↑" else " ↓"
                view.text = getString(R.string.header_sort_format, baseText, arrow)
            } else {
                view.text = baseText
            }
        }
    }

    /**
     * Starts observing the database for player score changes and updates the UI accordingly.
     * Handles sorting and data transformation.
     */
    private fun observeDatabase() {
        observationJob?.cancel()
        observationJob = lifecycleScope.launch {
            db.playerScoreDao().getAllScores().collectLatest { scores ->
                val sortedList = withContext(Dispatchers.Default) {
                    val memberRows = transformToMemberRows(scores)
                    when (currentSortColumn) {
                        SortColumn.PLAYER -> if (currentSortOrder == SortOrder.ASC) memberRows.sortedBy { it.name } else memberRows.sortedByDescending { it.name }
                        SortColumn.MON -> sortRowsByKey(memberRows, "Mon")
                        SortColumn.TUES -> sortRowsByKey(memberRows, "Tues")
                        SortColumn.WED -> sortRowsByKey(memberRows, "Wed")
                        SortColumn.THUR -> sortRowsByKey(memberRows, "Thur")
                        SortColumn.FRI -> sortRowsByKey(memberRows, "Fri")
                        SortColumn.SAT -> sortRowsByKey(memberRows, "Sat")
                        SortColumn.POWER -> sortRowsByKey(memberRows, "Power")
                        SortColumn.KILLS -> sortRowsByKey(memberRows, "Kills")
                        SortColumn.DONATION -> sortRowsByKey(memberRows, "Donation")
                    }
                }
                adapter.submitList(sortedList)
            }
        }
    }

    /**
     * Transforms a list of [PlayerScoreEntity] objects into a grouped list of [MemberRow] objects.
     * Groups scores by player name and takes the latest score for each day/category.
     */
    private fun transformToMemberRows(entities: List<PlayerScoreEntity>): List<MemberRow> {
        val grouped = entities.groupBy { it.name.lowercase().trim() }
        return grouped.map { (_, playerEntities) ->
            val firstEntity = playerEntities.first()
            val scoresMap = playerEntities.groupBy { it.day }
                .mapValues { (_, dayEntities) -> dayEntities.maxByOrNull { it.timestamp }?.score ?: 0L }
            val latestTimestamp = playerEntities.maxOf { it.timestamp }
            MemberRow(firstEntity.name, scoresMap, latestTimestamp)
        }
    }

    /**
     * Sorts a list of [MemberRow] objects based on the current sort order and a specific key.
     */
    private fun sortRowsByKey(rows: List<MemberRow>, key: String): List<MemberRow> {
        return if (currentSortOrder == SortOrder.ASC) {
            rows.sortedBy { it.getScore(key) ?: -1L }
        } else {
            rows.sortedByDescending { it.getScore(key) ?: -1L }
        }
    }

    /**
     * Registers the [ocrReceiver] when the activity is resumed.
     */
    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(ScreenCaptureService.ACTION_OCR_RESULT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(ocrReceiver, filter, RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(ocrReceiver, filter)
        }
    }

    /**
     * Unregisters the [ocrReceiver] when the activity is paused.
     */
    override fun onPause() {
        super.onPause()
        unregisterReceiver(ocrReceiver)
    }

    /**
     * Updates the UI elements based on whether the scanning service is currently running.
     * @param isRunning True if the service is running, false otherwise.
     */
    private fun updateUi(isRunning: Boolean) {
        if (isRunning) {
            tvStatus.text = getString(R.string.status_scanning)
            btnStart.isEnabled = false
            btnStop.visibility = View.VISIBLE
        } else {
            tvStatus.text = getString(R.string.status_stopped)
            tvDetectedDay.text = getString(R.string.active_tab_unknown)
            pbScanning.visibility = View.INVISIBLE
            btnStart.isEnabled = true
            btnStop.visibility = View.GONE
        }
    }
}
