package tools.perry.lastwarscanner.sync

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.ViewFlipper
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import tools.perry.lastwarscanner.R
import tools.perry.lastwarscanner.network.DayMapping
import tools.perry.lastwarscanner.network.MemberSummary

/**
 * Three-screen sync flow driven by [SyncViewModel]:
 *   Screen 0 — Day selection (Idle / error after reset)
 *   Screen 1 — Review matched / unresolved entries (AwaitingReview)
 *   Screen 2 — Result: success counts or error message (Success / Error)
 */
class SyncActivity : AppCompatActivity() {

    // ── Views ──────────────────────────────────────────────────────────────────
    private lateinit var viewFlipper: ViewFlipper
    private lateinit var loadingOverlay: LinearLayout
    private lateinit var tvLoadingLabel: TextView

    // Screen 0
    private lateinit var tvLoginStatus: TextView
    private lateinit var tvLogOut: TextView
    private lateinit var spinnerDay: Spinner
    private lateinit var tvWeekDate: TextView
    private lateinit var tvSkippedNote: TextView
    private lateinit var btnStartSync: Button

    // Screen 1
    private lateinit var tvReviewSummary: TextView
    private lateinit var rvReview: RecyclerView
    private lateinit var btnSubmit: Button

    // Screen 2
    private lateinit var tvResultTitle: TextView
    private lateinit var tvResultBody: TextView
    private lateinit var btnRetry: Button
    private lateinit var btnDone: Button

    // ── State ──────────────────────────────────────────────────────────────────
    private lateinit var vm: SyncViewModel
    private lateinit var reviewAdapter: ReviewAdapter

    /** Members available for the picker, populated from PreviewResponse.allMembers. */
    private var allMembers: List<MemberSummary> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sync)
        vm = ViewModelProvider(this)[SyncViewModel::class.java]
        bindViews()
        setupScreen0()
        setupScreen1()
        setupScreen2()
        observeState()
    }

    // ── View binding ───────────────────────────────────────────────────────────

    private fun bindViews() {
        viewFlipper = findViewById(R.id.viewFlipper)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        tvLoadingLabel = findViewById(R.id.tvLoadingLabel)

        tvLoginStatus = findViewById(R.id.tvLoginStatus)
        tvLogOut = findViewById(R.id.tvLogOut)
        spinnerDay = findViewById(R.id.spinnerDay)
        tvWeekDate = findViewById(R.id.tvWeekDate)
        tvSkippedNote = findViewById(R.id.tvSkippedNote)
        btnStartSync = findViewById(R.id.btnStartSync)

        tvReviewSummary = findViewById(R.id.tvReviewSummary)
        rvReview = findViewById(R.id.rvReview)
        btnSubmit = findViewById(R.id.btnSubmit)

        tvResultTitle = findViewById(R.id.tvResultTitle)
        tvResultBody = findViewById(R.id.tvResultBody)
        btnRetry = findViewById(R.id.btnRetry)
        btnDone = findViewById(R.id.btnDone)
    }

    // ── Screen 0 setup ─────────────────────────────────────────────────────────

    private fun setupScreen0() {
        val session = vm.session

        // Login status line
        val username = session.getUsername() ?: getString(R.string.sync_unknown_user)
        val server = session.getServerUrl() ?: ""
        tvLoginStatus.text = getString(R.string.sync_logged_in_as, username, server)

        tvLogOut.setOnClickListener {
            session.clearSession()
            startActivity(Intent(this, ServerSetupActivity::class.java))
            finish()
        }

        // Day spinner — pre-select last active day
        val days = DayMapping.syncableDisplayNames
        spinnerDay.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, days).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val lastDay = getSharedPreferences(SYNC_PREFS, MODE_PRIVATE)
            .getString(KEY_LAST_ACTIVE_DAY, days.first()) ?: days.first()
        val initialIdx = days.indexOf(lastDay).coerceAtLeast(0)
        spinnerDay.setSelection(initialIdx)

        // Week date label
        val weekDate = vm.currentWeekDate()
        tvWeekDate.text = getString(R.string.sync_week_of, weekDate)

        // Note about skipped categories
        val skippedList = DayMapping.skipped.joinToString(", ")
        tvSkippedNote.text = getString(R.string.sync_skipped_note, skippedList)
        tvSkippedNote.visibility = View.VISIBLE

        btnStartSync.setOnClickListener {
            if (!vm.session.isLoggedIn()) {
                startActivity(Intent(this, ServerSetupActivity::class.java))
                return@setOnClickListener
            }
            val day = days[spinnerDay.selectedItemPosition]
            vm.startPreview(weekDate, day)
        }
    }

    // ── Screen 1 setup ─────────────────────────────────────────────────────────

    private fun setupScreen1() {
        reviewAdapter = ReviewAdapter { originalName ->
            showMemberPickerDialog(originalName)
        }
        rvReview.layoutManager = LinearLayoutManager(this)
        rvReview.adapter = reviewAdapter

        // Submit is always enabled — unresolved entries are skipped server-side
        btnSubmit.setOnClickListener {
            val aliasChoices = reviewAdapter.getAliasChoices()
            vm.commit(aliasChoices)
        }
    }

    // ── Screen 2 setup ─────────────────────────────────────────────────────────

    private fun setupScreen2() {
        btnDone.setOnClickListener {
            vm.reset()
            // State transitions to Idle, observer will flip back to screen 0
        }
        btnRetry.setOnClickListener {
            vm.reset()
        }
    }

    // ── State observation ──────────────────────────────────────────────────────

    private fun observeState() {
        lifecycleScope.launch {
            vm.state.collect { state ->
                when (state) {
                    is SyncState.Idle -> {
                        loadingOverlay.visibility = View.GONE
                        viewFlipper.displayedChild = SCREEN_DAY_SELECT
                    }
                    is SyncState.LoadingPreview, is SyncState.Committing -> {
                        val label = if (state is SyncState.LoadingPreview)
                            getString(R.string.sync_loading_preview)
                        else
                            getString(R.string.sync_loading_commit)
                        tvLoadingLabel.text = label
                        loadingOverlay.visibility = View.VISIBLE
                    }
                    is SyncState.AwaitingReview -> {
                        loadingOverlay.visibility = View.GONE
                        allMembers = state.preview.allMembers

                        tvReviewSummary.text = getString(
                            R.string.sync_review_summary,
                            state.preview.totalMatched,
                            state.preview.totalUnresolved
                        )
                        reviewAdapter.setData(state.preview)

                        // Reflect any manual resolutions already in state
                        for ((name, memberId) in state.resolutions) {
                            val member = allMembers.firstOrNull { it.id == memberId } ?: continue
                            reviewAdapter.updateResolution(name, member)
                        }

                        viewFlipper.displayedChild = SCREEN_REVIEW
                    }
                    is SyncState.Success -> {
                        loadingOverlay.visibility = View.GONE
                        tvResultTitle.text = getString(R.string.sync_result_title_success)
                        tvResultBody.text = buildSuccessBody(state.response)
                        btnRetry.visibility = View.GONE
                        viewFlipper.displayedChild = SCREEN_RESULT
                    }
                    is SyncState.Error -> {
                        loadingOverlay.visibility = View.GONE
                        tvResultTitle.text = getString(R.string.sync_result_title_error)
                        tvResultBody.text = state.message
                        btnRetry.visibility = if (state.canRetry) View.VISIBLE else View.GONE
                        viewFlipper.displayedChild = SCREEN_RESULT
                    }
                }
            }
        }
    }

    // ── Member picker dialog ───────────────────────────────────────────────────

    private fun showMemberPickerDialog(originalName: String) {
        if (allMembers.isEmpty()) return

        val sorted = allMembers.sortedBy { it.name }
        val displayNames = sorted.map { it.name }.toMutableList()
        displayNames.add(0, getString(R.string.review_skip_member))

        var filtered = displayNames.toList()
        val dialogView = layoutInflater.inflate(R.layout.dialog_member_picker, null)
        val etSearch = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etPickerSearch)
        val listView = dialogView.findViewById<android.widget.ListView>(R.id.lvPickerMembers)

        val listAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, filtered.toMutableList())
        listView.adapter = listAdapter

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.review_picker_title, originalName))
            .setView(dialogView)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val query = s?.toString()?.trim()?.lowercase() ?: ""
                val newList = if (query.isEmpty()) displayNames
                else displayNames.filter { it.lowercase().contains(query) || it == getString(R.string.review_skip_member) }
                listAdapter.clear()
                listAdapter.addAll(newList)
                listAdapter.notifyDataSetChanged()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })

        listView.setOnItemClickListener { _, _, position, _ ->
            val picked = listAdapter.getItem(position) ?: return@setOnItemClickListener
            if (picked == getString(R.string.review_skip_member)) {
                // "Skip" — no action needed; unresolved stays unresolved
            } else {
                val member = sorted.firstOrNull { it.name == picked } ?: return@setOnItemClickListener
                reviewAdapter.updateResolution(originalName, member)
                vm.updateResolution(originalName, member.id)

                // Update summary count
                val current = vm.state.value as? SyncState.AwaitingReview
                if (current != null) {
                    tvReviewSummary.text = getString(
                        R.string.sync_review_summary,
                        current.resolutions.size + current.preview.totalMatched,
                        current.preview.totalUnresolved - current.resolutions.size
                    )
                }
            }
            dialog.dismiss()
        }

        dialog.show()
    }

    // ── Result body builders ───────────────────────────────────────────────────

    private fun buildSuccessBody(response: tools.perry.lastwarscanner.network.CommitResponse): String {
        val sb = StringBuilder()
        sb.append(response.message)
        sb.append("\n\n")
        sb.append(getString(R.string.sync_result_vs, response.vsRecordsSaved))
        sb.append("\n")
        sb.append(getString(R.string.sync_result_power, response.powerRecordsSaved))
        sb.append("\n")
        sb.append(getString(R.string.sync_result_aliases, response.aliasesSaved))
        if (response.errors.isNotEmpty()) {
            sb.append("\n\n")
            sb.append(getString(R.string.sync_result_partial_errors, response.errors.size))
            response.errors.forEach { sb.append("\n• $it") }
        }
        return sb.toString()
    }

    companion object {
        private const val SCREEN_DAY_SELECT = 0
        private const val SCREEN_REVIEW = 1
        private const val SCREEN_RESULT = 2
        const val SYNC_PREFS = "sync_prefs"
        const val KEY_LAST_ACTIVE_DAY = "last_active_day"
    }
}
