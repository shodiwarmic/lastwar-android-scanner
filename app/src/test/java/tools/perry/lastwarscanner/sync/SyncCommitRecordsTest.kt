package tools.perry.lastwarscanner.sync

import org.junit.Assert.assertEquals
import org.junit.Test
import tools.perry.lastwarscanner.network.MemberSummary
import tools.perry.lastwarscanner.network.PreviewMatch
import tools.perry.lastwarscanner.network.PreviewResponse

// A reading taken before the 02:00 UTC reset and synced after it keeps its own time on both
// the automatic and the manually resolved record, so the server files it on the day it was read.
class SyncCommitRecordsTest {
    @Test
    fun capturedAtSurvivesPreviewToCommit() {
        val alder = MemberSummary(7, "Alder", "R3")
        val preview = PreviewResponse(
            matched = listOf(PreviewMatch("Alder", alder, "exact", "donation_daily", 900, "2026-10-13T01:30:00Z")),
            unresolved = listOf(PreviewMatch("Bxrch", null, "", "donation_weekly", 50, "2026-10-13T01:31:00Z")),
            allMembers = listOf(alder), totalSubmitted = 2, totalMatched = 1, totalUnresolved = 1
        )
        val state = SyncState.AwaitingReview(preview, mapOf("Bxrch" to 8), "2026-10-12")
        val records = buildCommitRecordsFor(state)
        assertEquals(listOf("2026-10-13T01:30:00Z", "2026-10-13T01:31:00Z"), records.map { it.capturedAt })
        assertEquals(listOf(7, 8), records.map { it.memberId })
    }
}
