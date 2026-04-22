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
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
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
import tools.perry.lastwarscanner.network.AllianceApiClient
import tools.perry.lastwarscanner.network.RosterCache
import tools.perry.lastwarscanner.network.SessionManager
import tools.perry.lastwarscanner.sync.ServerSetupActivity
import tools.perry.lastwarscanner.sync.SyncActivity
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * Main activity for the Last War Scanner application.
 * This activity handles the user interface for starting/stopping the screen capture service,
 * displaying the scanned player scores in a expandable card list, and exporting the data to CSV.
 */
class MainActivity : AppCompatActivity() {

    enum class SortMode { NAME_ASC, NAME_DESC, RECENT }

    private lateinit var tvStatus: TextView
    private lateinit var tvDetectedDay: TextView
    private lateinit var pbScanning: ProgressBar
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnClear: Button
    private lateinit var btnExport: Button
    private lateinit var btnSync: Button
    private lateinit var btnRefreshRoster: Button
    private lateinit var rvScores: RecyclerView
    private lateinit var adapter: ScoreAdapter

    private lateinit var etSearch: EditText
    private lateinit var btnSort: Button
    private lateinit var tvMemberCount: TextView

    private var currentSortMode = SortMode.NAME_ASC
    private var searchQuery = ""
    private var activeTabName = "Unknown"
    private var observationJob: Job? = null

    private lateinit var db: AppDatabase
    private lateinit var sessionManager: SessionManager

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
                    // Persist for SyncActivity day pre-selection
                    getSharedPreferences(SyncActivity.SYNC_PREFS, MODE_PRIVATE)
                        .edit().putString(SyncActivity.KEY_LAST_ACTIVE_DAY, day).apply()
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
        sessionManager = SessionManager(this)

        tvStatus = findViewById(R.id.tvStatus)
        tvDetectedDay = findViewById(R.id.tvDetectedDay)
        pbScanning = findViewById(R.id.pbScanning)
        btnStart = findViewById(R.id.btnStartService)
        btnStop = findViewById(R.id.btnStopService)
        btnClear = findViewById(R.id.btnClearLogs)
        btnExport = findViewById(R.id.btnExportCsv)
        btnSync = findViewById(R.id.btnSyncToServer)
        btnRefreshRoster = findViewById(R.id.btnRefreshRoster)
        rvScores = findViewById(R.id.rvScores)
        etSearch = findViewById(R.id.etSearch)
        btnSort = findViewById(R.id.btnSort)
        tvMemberCount = findViewById(R.id.tvMemberCount)

        setupRecyclerView()

        // Search
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString() ?: ""
                observeDatabase()
            }
        })

        // Sort cycling: NAME_ASC -> NAME_DESC -> RECENT -> NAME_ASC
        btnSort.setOnClickListener {
            currentSortMode = when (currentSortMode) {
                SortMode.NAME_ASC  -> SortMode.NAME_DESC
                SortMode.NAME_DESC -> SortMode.RECENT
                SortMode.RECENT    -> SortMode.NAME_ASC
            }
            btnSort.text = when (currentSortMode) {
                SortMode.NAME_ASC  -> getString(R.string.sort_name_asc)
                SortMode.NAME_DESC -> getString(R.string.sort_name_desc)
                SortMode.RECENT    -> getString(R.string.sort_recent)
            }
            observeDatabase()
        }

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

        btnSync.setOnClickListener {
            val target = if (sessionManager.isLoggedIn()) SyncActivity::class.java
                         else ServerSetupActivity::class.java
            startActivity(Intent(this, target))
        }

        updateRosterButton()
        btnRefreshRoster.setOnClickListener { refreshRoster() }

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
     * Starts observing the database for player score changes and updates the UI accordingly.
     * Handles filtering, sorting, and data transformation.
     */
    private fun observeDatabase() {
        observationJob?.cancel()
        observationJob = lifecycleScope.launch {
            db.playerScoreDao().getAllScores().collectLatest { scores ->
                val result = withContext(Dispatchers.Default) {
                    val rows = transformToMemberRows(scores)
                    val filtered = if (searchQuery.isBlank()) rows
                        else rows.filter { it.name.contains(searchQuery, ignoreCase = true) }
                    when (currentSortMode) {
                        SortMode.NAME_ASC  -> filtered.sortedBy { it.name }
                        SortMode.NAME_DESC -> filtered.sortedByDescending { it.name }
                        SortMode.RECENT    -> filtered.sortedByDescending { it.latestTimestamp }
                    }
                }
                tvMemberCount.text = "${result.size} member${if (result.size != 1) "s" else ""}"
                adapter.submitList(result)
            }
        }
    }

    /**
     * Exports the collected player scores to a CSV file and opens a share intent.
     * Dynamically builds columns from all keys present in the current data.
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
            val allKeys = memberRows.flatMap { it.scores.keys }.distinct()
                .sortedBy { ScoreAdapter.categoryOrder(it) }
            val csvHeader = (listOf("Member") + allKeys.map { ScoreAdapter.keyLabel(it) }).joinToString(",")
            val csvBody = memberRows.joinToString("\n") { row ->
                (listOf(row.name) + allKeys.map { key -> row.getScore(key) ?: 0L }).joinToString(",")
            }

            val fileName = "LastWar_Export_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())}.csv"
            try {
                val file = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
                FileOutputStream(file).use { it.write(("$csvHeader\n$csvBody").toByteArray()) }

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
        updateRosterButton()
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

    /** Shows the roster button (when logged in) and updates its member count label. */
    private fun updateRosterButton() {
        if (!sessionManager.isLoggedIn()) {
            btnRefreshRoster.visibility = View.GONE
            return
        }
        btnRefreshRoster.visibility = View.VISIBLE
        val count = RosterCache.getCount(this)
        btnRefreshRoster.text = if (count > 0)
            getString(R.string.btn_refresh_roster, count)
        else
            getString(R.string.btn_refresh_roster_empty)
    }

    /** Fetches the member roster from the web API and saves it to [RosterCache]. */
    private fun refreshRoster() {
        btnRefreshRoster.isEnabled = false
        btnRefreshRoster.text = getString(R.string.roster_refreshing)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                AllianceApiClient(sessionManager).getMembers()
            }
            result.fold(
                onSuccess = { members ->
                    val names = members.map { it.name }
                    RosterCache.save(this@MainActivity, names)
                    updateRosterButton()
                    Toast.makeText(this@MainActivity, getString(R.string.roster_refresh_success, names.size), Toast.LENGTH_SHORT).show()
                },
                onFailure = { e ->
                    updateRosterButton()
                    Toast.makeText(this@MainActivity, getString(R.string.roster_refresh_failed, e.localizedMessage ?: e.message), Toast.LENGTH_LONG).show()
                }
            )
            btnRefreshRoster.isEnabled = true
        }
    }
}
