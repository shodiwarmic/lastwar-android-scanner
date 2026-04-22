package tools.perry.lastwarscanner.network

import android.content.Context
import org.json.JSONArray

/**
 * Lightweight SharedPreferences cache for the web roster (member names).
 *
 * The cache is written after a successful [AllianceApiClient.getMembers] call
 * and read by [tools.perry.lastwarscanner.ScreenCaptureService] each frame as
 * Lookup 4 — fuzzy-matching OCR names against canonical server-side names before
 * falling back to creating a new DB entry.
 */
object RosterCache {

    private const val PREFS_NAME = "roster_cache"
    private const val KEY_NAMES  = "member_names"
    private const val KEY_COUNT  = "member_count"

    /** Returns the cached list of member names, or an empty list if not yet populated. */
    fun getNames(context: Context): List<String> {
        val json = prefs(context).getString(KEY_NAMES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            List(arr.length()) { arr.getString(it) }
        } catch (_: Exception) { emptyList() }
    }

    /** Persists [names] to the cache, overwriting any previous roster. */
    fun save(context: Context, names: List<String>) {
        prefs(context).edit()
            .putString(KEY_NAMES, JSONArray(names).toString())
            .putInt(KEY_COUNT, names.size)
            .apply()
    }

    /**
     * Returns the number of members stored in the cache.
     * Faster than [getNames] when only the count is needed (no JSON parsing).
     */
    fun getCount(context: Context): Int =
        prefs(context).getInt(KEY_COUNT, 0)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
