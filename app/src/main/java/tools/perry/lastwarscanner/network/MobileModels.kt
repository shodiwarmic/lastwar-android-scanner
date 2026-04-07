package tools.perry.lastwarscanner.network

// ── Request models ────────────────────────────────────────────────────────────

data class LoginRequest(val username: String, val password: String)

data class ScanEntry(val name: String, val score: Long, val category: String)

data class PreviewRequest(val weekDate: String, val entries: List<ScanEntry>)

data class CommitRecord(
    val memberId: Int,
    val originalName: String,
    val category: String,
    val score: Long
)

data class AliasMapping(
    val failedAlias: String,
    val memberId: Int,
    val category: String
)

data class CommitRequest(
    val weekDate: String,
    val records: List<CommitRecord>,
    val saveAliases: List<AliasMapping>
)

// ── Response models ───────────────────────────────────────────────────────────

data class LoginResponse(
    val token: String,
    /** Unix epoch seconds from the JWT `exp` claim. */
    val expiresAt: Long,
    val userId: Int,
    val username: String,
    val memberId: Int?,
    val manageVs: Boolean,
    val manageMembers: Boolean
)

data class MemberSummary(val id: Int, val name: String, val rank: String)

data class PreviewMatch(
    val originalName: String,
    /** Null when the entry could not be resolved automatically. */
    val matchedMember: MemberSummary?,
    val matchType: String,
    val category: String,
    val score: Long
)

data class PreviewResponse(
    val matched: List<PreviewMatch>,
    val unresolved: List<PreviewMatch>,
    val allMembers: List<MemberSummary>,
    val totalSubmitted: Int,
    val totalMatched: Int,
    val totalUnresolved: Int
)

/** HTTP 200 with a non-empty [errors] list is the normal partial-success case. */
data class CommitResponse(
    val message: String,
    val vsRecordsSaved: Int,
    val powerRecordsSaved: Int,
    val aliasesSaved: Int,
    val errors: List<String>
)

// ── Category constants ────────────────────────────────────────────────────────

object Category {
    val VS_DAYS = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday")
    const val POWER = "power"
}

// ── Day name mapping ──────────────────────────────────────────────────────────

object DayMapping {
    /**
     * Maps the display names used by OcrParser / LayoutRegistry to the backend
     * category strings expected by /api/mobile/preview and /api/mobile/commit.
     */
    val toBackendKey: Map<String, String> = mapOf(
        "Mon"   to "monday",
        "Tues"  to "tuesday",
        "Wed"   to "wednesday",
        "Thur"  to "thursday",
        "Fri"   to "friday",
        "Sat"   to "saturday",
        "Power" to "power"
    )

    /** Categories present locally that have no mobile API commit path. */
    val skipped: Set<String> = setOf("Kills", "Donation")

    /** Ordered list of display names shown in the day selector spinner. */
    val syncableDisplayNames: List<String> = listOf("Mon", "Tues", "Wed", "Thur", "Fri", "Sat", "Power")
}
