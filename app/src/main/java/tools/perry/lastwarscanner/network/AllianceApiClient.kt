package tools.perry.lastwarscanner.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * HTTP client for the four /api/mobile/ endpoints.
 * Uses [HttpURLConnection] only — no OkHttp or Retrofit.
 *
 * All functions are suspend and run on [Dispatchers.IO].
 * All functions return [Result]<T>; failures wrap the exception rather than throwing.
 * Include the HTTP status code in the exception message so the caller can display it.
 */
class AllianceApiClient(private val session: SessionManager) {

    // ── Public API ────────────────────────────────────────────────────────────

    /** Login is stateless — URL and credentials come from the caller, not session storage. */
    suspend fun login(url: String, user: String, pass: String): Result<LoginResponse> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject().apply {
                    put("username", user)
                    put("password", pass)
                }.toString()
                val json = postJson("${url.trimEnd('/')}/api/mobile/login", body, token = null)
                LoginResponse(
                    token = json.getString("token"),
                    expiresAt = parseExpiresAt(json.getString("expires_at")),
                    userId = json.getInt("user_id"),
                    username = json.getString("username"),
                    memberId = if (json.isNull("member_id")) null else json.optInt("member_id"),
                    manageVs = json.optBoolean("manage_vs", false),
                    manageMembers = json.optBoolean("manage_members", false)
                )
            }
        }

    suspend fun getMembers(): Result<List<MemberSummary>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val json = getJson(endpoint("/api/mobile/members"))
                val arr = json.getJSONArray("members")
                List(arr.length()) { i ->
                    arr.getJSONObject(i).run {
                        MemberSummary(
                            id = getInt("id"),
                            name = getString("name"),
                            rank = optString("rank", "")
                        )
                    }
                }
            }
        }

    suspend fun preview(request: PreviewRequest): Result<PreviewResponse> =
        withContext(Dispatchers.IO) {
            runCatching {
                val entriesArr = JSONArray().apply {
                    request.entries.forEach { e ->
                        put(JSONObject().apply {
                            put("name", e.name)
                            put("score", e.score)
                            put("category", e.category)
                        })
                    }
                }
                val body = JSONObject().apply {
                    put("week_date", request.weekDate)
                    put("entries", entriesArr)
                }.toString()
                val json = postJson(endpoint("/api/mobile/preview"), body, session.getToken())
                parsePreviewResponse(json)
            }
        }

    suspend fun commit(request: CommitRequest): Result<CommitResponse> =
        withContext(Dispatchers.IO) {
            runCatching {
                val recordsArr = JSONArray().apply {
                    request.records.forEach { r ->
                        put(JSONObject().apply {
                            put("member_id", r.memberId)
                            put("original_name", r.originalName)
                            put("category", r.category)
                            put("score", r.score)
                        })
                    }
                }
                val aliasesArr = JSONArray().apply {
                    request.saveAliases.forEach { a ->
                        put(JSONObject().apply {
                            put("failed_alias", a.failedAlias)
                            put("member_id", a.memberId)
                            put("category", a.category)
                        })
                    }
                }
                val body = JSONObject().apply {
                    put("week_date", request.weekDate)
                    put("records", recordsArr)
                    put("save_aliases", aliasesArr)
                }.toString()
                val json = postJson(endpoint("/api/mobile/commit"), body, session.getToken())
                val errArr = json.optJSONArray("errors") ?: JSONArray()
                CommitResponse(
                    message = json.optString("message", ""),
                    vsRecordsSaved = json.optInt("vs_records_saved", 0),
                    powerRecordsSaved = json.optInt("power_records_saved", 0),
                    aliasesSaved = json.optInt("aliases_saved", 0),
                    errors = List(errArr.length()) { i -> errArr.getString(i) }
                )
            }
        }

    // ── JSON parsing helpers ──────────────────────────────────────────────────

    private fun parsePreviewResponse(json: JSONObject): PreviewResponse {
        fun parseMember(obj: JSONObject) = MemberSummary(
            id = obj.getInt("id"),
            name = obj.getString("name"),
            rank = obj.optString("rank", "")
        )

        fun parseMatch(obj: JSONObject): PreviewMatch {
            val memberObj = if (!obj.isNull("matched_member")) obj.optJSONObject("matched_member") else null
            return PreviewMatch(
                originalName = obj.getString("original_name"),
                matchedMember = memberObj?.let { parseMember(it) },
                matchType = obj.optString("match_type", ""),
                category = obj.getString("category"),
                score = obj.getLong("score")
            )
        }

        fun parseMatchList(arr: JSONArray) = List(arr.length()) { i -> parseMatch(arr.getJSONObject(i)) }
        fun parseMemberList(arr: JSONArray) = List(arr.length()) { i -> parseMember(arr.getJSONObject(i)) }

        return PreviewResponse(
            matched = parseMatchList(json.optJSONArray("matched") ?: JSONArray()),
            unresolved = parseMatchList(json.optJSONArray("unresolved") ?: JSONArray()),
            allMembers = parseMemberList(json.optJSONArray("all_members") ?: JSONArray()),
            totalSubmitted = json.optInt("total_submitted", 0),
            totalMatched = json.optInt("total_matched", 0),
            totalUnresolved = json.optInt("total_unresolved", 0)
        )
    }

    // ── HTTP primitives ───────────────────────────────────────────────────────

    private fun endpoint(path: String): String {
        val base = session.getServerUrl() ?: error("No server URL in session")
        return "$base$path"
    }

    private fun postJson(url: String, body: String, token: String?): JSONObject {
        val conn = open(url, "POST", token)
        conn.doOutput = true
        OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { it.write(body) }
        return readResponse(conn)
    }

    private fun getJson(url: String): JSONObject =
        readResponse(open(url, "GET", session.getToken()))

    private fun open(url: String, method: String, token: String?): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("Accept", "application/json")
            if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $token")
            connectTimeout = 10_000
            readTimeout = 30_000
        }

    private fun readResponse(conn: HttpURLConnection): JSONObject {
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.bufferedReader(StandardCharsets.UTF_8)?.readText() ?: "{}"
            if (code !in 200..299) {
                val err = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
                val msg = err.optString("message", "HTTP $code")
                throw HttpException(code, msg)
            }
            return JSONObject(raw)
        } finally {
            conn.disconnect()
        }
    }

    class HttpException(val code: Int, message: String) : Exception("HTTP $code: $message")

    companion object {
        /**
         * Parses the server's `expires_at` value, which may be either:
         *  - An ISO 8601 string: `"2026-04-14T03:39:19Z"`
         *  - A Unix epoch Long (seconds) sent as a JSON number
         * Returns Unix epoch seconds, or 0 on parse failure.
         */
        fun parseExpiresAt(raw: String): Long {
            // Try numeric first (future-proofing if server ever changes format)
            raw.toLongOrNull()?.let { return it }
            return try {
                val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                sdf.parse(raw)!!.time / 1_000L
            } catch (_: Exception) { 0L }
        }
    }
}
