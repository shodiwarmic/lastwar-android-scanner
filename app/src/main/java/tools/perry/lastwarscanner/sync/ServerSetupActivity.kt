package tools.perry.lastwarscanner.sync

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import tools.perry.lastwarscanner.R
import tools.perry.lastwarscanner.network.AllianceApiClient
import tools.perry.lastwarscanner.network.SessionManager

/**
 * Shown when the user has no valid session. Collects server URL, username, and password,
 * tests the connection via /api/mobile/login, then stores the JWT via [SessionManager].
 * The password is not persisted after a successful login.
 */
class ServerSetupActivity : AppCompatActivity() {

    private lateinit var etServerUrl: TextInputEditText
    private lateinit var etUsername: TextInputEditText
    private lateinit var etPassword: TextInputEditText
    private lateinit var btnConnect: Button
    private lateinit var pbSetup: ProgressBar
    private lateinit var tvSetupError: TextView

    private lateinit var session: SessionManager
    private lateinit var api: AllianceApiClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_server_setup)

        session = SessionManager(this)
        api = AllianceApiClient(session)

        etServerUrl = findViewById(R.id.etServerUrl)
        etUsername = findViewById(R.id.etUsername)
        etPassword = findViewById(R.id.etPassword)
        btnConnect = findViewById(R.id.btnConnect)
        pbSetup = findViewById(R.id.pbSetup)
        tvSetupError = findViewById(R.id.tvSetupError)

        // Pre-fill server URL if one was saved previously
        session.getServerUrl()?.let { etServerUrl.setText(it) }

        val watcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                btnConnect.isEnabled =
                    etServerUrl.text?.isNotBlank() == true &&
                    etUsername.text?.isNotBlank() == true &&
                    etPassword.text?.isNotBlank() == true
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        }
        etServerUrl.addTextChangedListener(watcher)
        etUsername.addTextChangedListener(watcher)
        etPassword.addTextChangedListener(watcher)

        btnConnect.setOnClickListener { connect() }
    }

    private fun connect() {
        val rawUrl = etServerUrl.text?.toString()?.trim() ?: return
        val user = etUsername.text?.toString()?.trim() ?: return
        val pass = etPassword.text?.toString() ?: return

        // Prepend https:// if the user omitted the scheme
        val url = when {
            rawUrl.startsWith("http://") || rawUrl.startsWith("https://") -> rawUrl
            else -> "https://$rawUrl"
        }.trimEnd('/')

        setLoading(true)
        lifecycleScope.launch {
            api.login(url, user, pass).fold(
                onSuccess = { resp ->
                    session.saveSession(
                        url = url,
                        token = resp.token,
                        expiresAt = resp.expiresAt,
                        username = resp.username
                    )
                    setLoading(false)
                    finish()   // return to SyncActivity
                },
                onFailure = { e ->
                    setLoading(false)
                    val msg = when {
                        e is AllianceApiClient.HttpException && e.code == 403 ->
                            getString(R.string.setup_error_force_password_change)
                        e is AllianceApiClient.HttpException && e.code == 401 ->
                            getString(R.string.setup_error_invalid_credentials)
                        e is AllianceApiClient.HttpException ->
                            getString(R.string.setup_error_server, e.code, e.message)
                        else ->
                            getString(R.string.setup_error_network, e.localizedMessage ?: e.message)
                    }
                    showError(msg)
                }
            )
        }
    }

    private fun setLoading(loading: Boolean) {
        pbSetup.visibility = if (loading) View.VISIBLE else View.GONE
        btnConnect.isEnabled = !loading
        etServerUrl.isEnabled = !loading
        etUsername.isEnabled = !loading
        etPassword.isEnabled = !loading
        if (loading) tvSetupError.visibility = View.GONE
    }

    private fun showError(message: String) {
        tvSetupError.text = message
        tvSetupError.visibility = View.VISIBLE
    }
}
