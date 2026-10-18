package tools.perry.lastwarscanner.network

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AllianceApiClientJsonTest {

    @Test
    fun previewAndCommitCarryCapturedAt() {
        val preview = JSONObject(AllianceApiClient.previewBody(PreviewRequest("2026-10-12", listOf(
            ScanEntry("Alder", 900, "donation_daily", "2026-10-13T20:00:00Z"),
            ScanEntry("Birch", 5, "monday"),
        ))))
        val entries = preview.getJSONArray("entries")
        assertEquals("2026-10-13T20:00:00Z", entries.getJSONObject(0).getString("captured_at"))
        assertFalse(entries.getJSONObject(1).has("captured_at"))

        val commit = JSONObject(AllianceApiClient.commitBody(CommitRequest("2026-10-12", listOf(
            CommitRecord(7, "Alder", "donation_weekly", 4100, "2026-10-13T20:00:00Z"),
        ), emptyList())))
        val rec = commit.getJSONArray("records").getJSONObject(0)
        assertEquals("donation_weekly", rec.getString("category"))
        assertEquals("2026-10-13T20:00:00Z", rec.getString("captured_at"))
    }

    // The server echoes captured_at; every commit record is built from the preview's matches,
    // so dropping it here would send no time at all.
    @Test
    fun thePreviewsEchoIsKept() {
        val json = JSONObject("""{"matched":[{"original_name":"Alder","matched_member":{"id":7,"name":"Alder","rank":"R3"},
            "match_type":"exact","category":"donation_daily","score":900,"captured_at":"2026-10-13T01:30:00Z"}],
            "unresolved":[{"original_name":"Bxrch","matched_member":null,"match_type":"","category":"donation_daily","score":5,
            "captured_at":"2026-10-12T20:00:00Z"}],"all_members":[]}""")
        val p = AllianceApiClient.parsePreviewResponse(json)
        assertEquals("2026-10-13T01:30:00Z", p.matched[0].capturedAt)
        assertEquals("2026-10-12T20:00:00Z", p.unresolved[0].capturedAt)
    }

    @Test
    fun commitCountsAreRead() {
        val r = AllianceApiClient.parseCommitResponse(JSONObject("""{"message":"ok","errors":["x"],
            "records_saved":{"donation_daily":3,"donation_weekly":2},"records_unchanged":{"donation_daily":1}}"""))
        assertEquals(mapOf("donation_daily" to 3, "donation_weekly" to 2), r.recordsSaved)
        assertEquals(1, r.recordsUnchanged["donation_daily"])
        assertEquals(emptyMap<String, Int>(), AllianceApiClient.parseCommitResponse(JSONObject("{}")).recordsSaved)
    }

    // An older server answers a donation record 200 with an "invalid category" line, and a
    // 400 in plain text: the server's words reach the screen, not a fixed guess.
    @Test
    fun errorMessagesAreTheServers() {
        assertEquals("Invalid week_date: must be YYYY-MM-DD", AllianceApiClient.errorMessage(400, "Invalid week_date: must be YYYY-MM-DD\n"))
        assertEquals("nope", AllianceApiClient.errorMessage(400, """{"message":"nope"}"""))
        assertEquals("HTTP 502", AllianceApiClient.errorMessage(502, ""))
    }
}
