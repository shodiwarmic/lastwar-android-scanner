package tools.perry.lastwarscanner.network

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * SharedPreferences cache for the alliance roster (member names + aliases).
 *
 * The cache is written after a successful [AllianceApiClient.getMembers] call
 * and read by [tools.perry.lastwarscanner.ScreenCaptureService] each frame.
 * It powers the on-device alias resolver — see
 * [tools.perry.lastwarscanner.ocr.RosterAliasResolver] — which mirrors the
 * backend's Exact → Personal → Global → OCR hierarchy from
 * `handlers_vs_import.go:resolveMemberAlias`.
 *
 * Schema version 2 stores `[{name, aliases:[{alias, category}, ...]}, ...]`.
 * Older v1 caches (just a name array) are detected and discarded so the next
 * sync repopulates them with the new shape.
 */
object RosterCache {

    private const val PREFS_NAME       = "roster_cache"
    private const val KEY_MEMBERS_JSON = "members_v2"
    private const val KEY_COUNT        = "member_count"
    private const val KEY_VERSION      = "schema_version"
    private const val SCHEMA_VERSION   = 2

    /**
     * Returns the cached canonical member names (no aliases). Empty when the
     * cache is unpopulated. Kept for callers that only need names.
     */
    fun getNames(context: Context): List<String> = getMembers(context).map { it.name }

    /**
     * Returns the cached members with their visible aliases. Empty when the
     * cache is unpopulated or stored under an older schema version.
     */
    fun getMembers(context: Context): List<MemberSummary> {
        val p = prefs(context)
        if (p.getInt(KEY_VERSION, 0) != SCHEMA_VERSION) return emptyList()
        val raw = p.getString(KEY_MEMBERS_JSON, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                val aliasesArr = o.optJSONArray("aliases") ?: JSONArray()
                MemberSummary(
                    id      = o.optInt("id", 0),
                    name    = o.getString("name"),
                    rank    = o.optString("rank", ""),
                    aliases = List(aliasesArr.length()) { j ->
                        val a = aliasesArr.getJSONObject(j)
                        AliasEntry(alias = a.getString("alias"), category = a.optString("category", ""))
                    }
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    /** Persists [members] to the cache, overwriting any previous roster. */
    fun save(context: Context, members: List<MemberSummary>) {
        val arr = JSONArray()
        members.forEach { m ->
            val aliases = JSONArray()
            m.aliases.forEach { a ->
                aliases.put(JSONObject().apply {
                    put("alias", a.alias)
                    put("category", a.category)
                })
            }
            arr.put(JSONObject().apply {
                put("id", m.id)
                put("name", m.name)
                put("rank", m.rank)
                put("aliases", aliases)
            })
        }
        prefs(context).edit()
            .putString(KEY_MEMBERS_JSON, arr.toString())
            .putInt(KEY_COUNT, members.size)
            .putInt(KEY_VERSION, SCHEMA_VERSION)
            .apply()
    }

    /**
     * Returns the number of members stored in the cache.
     * Faster than [getMembers] when only the count is needed (no JSON parsing).
     */
    fun getCount(context: Context): Int {
        val p = prefs(context)
        if (p.getInt(KEY_VERSION, 0) != SCHEMA_VERSION) return 0
        return p.getInt(KEY_COUNT, 0)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
