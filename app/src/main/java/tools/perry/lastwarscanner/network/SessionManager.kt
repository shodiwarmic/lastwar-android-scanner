package tools.perry.lastwarscanner.network

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Persists JWT session data using [EncryptedSharedPreferences].
 * Falls back to plain SharedPreferences if the Keystore is unavailable on first launch.
 *
 * The password is never stored — only the JWT and its expiry are retained.
 */
class SessionManager(context: Context) {

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context, PREFS_NAME, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.w(TAG, "EncryptedSharedPreferences unavailable, falling back to plain prefs", e)
        context.getSharedPreferences("${PREFS_NAME}_plain", Context.MODE_PRIVATE)
    }

    /** Atomically writes all four session values after a successful login. */
    fun saveSession(url: String, token: String, expiresAt: Long, username: String) {
        prefs.edit()
            .putString(KEY_SERVER_URL, url.trimEnd('/'))
            .putString(KEY_JWT_TOKEN, token)
            .putLong(KEY_TOKEN_EXPIRES_AT, expiresAt)
            .putString(KEY_USERNAME, username)
            .apply()
    }

    fun clearSession() = prefs.edit().clear().apply()

    /**
     * Returns true only if a token is stored and its expiry is more than
     * 60 seconds in the future (guards against clock-skew edge cases).
     */
    fun isLoggedIn(): Boolean {
        val token = getToken() ?: return false
        val expiresAt = prefs.getLong(KEY_TOKEN_EXPIRES_AT, 0L)
        val nowSeconds = System.currentTimeMillis() / 1_000L
        return token.isNotBlank() && expiresAt > nowSeconds + 60
    }

    /** Raw JWT string, or null if no session. */
    fun getToken(): String? = prefs.getString(KEY_JWT_TOKEN, null)?.ifBlank { null }

    /** Stored base URL (trailing slash already stripped), or null. */
    fun getServerUrl(): String? = prefs.getString(KEY_SERVER_URL, null)?.ifBlank { null }

    /** Username for display, or null. */
    fun getUsername(): String? = prefs.getString(KEY_USERNAME, null)?.ifBlank { null }

    companion object {
        private const val TAG = "SessionManager"
        private const val PREFS_NAME = "last_war_session"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_JWT_TOKEN = "jwt_token"
        private const val KEY_TOKEN_EXPIRES_AT = "token_expires_at"
        private const val KEY_USERNAME = "username"
    }
}
