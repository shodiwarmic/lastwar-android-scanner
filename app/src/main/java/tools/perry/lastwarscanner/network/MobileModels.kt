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

/**
 * One alias the current user is allowed to see for a member.
 *
 * [category] is one of `"personal"` (only the current user), `"global"`
 * (every user), or `"ocr"` (every user; created by accepting an OCR-side
 * suggestion). The backend filters out other users' personal aliases
 * before the roster reaches the device — see `loadMobileRoster` in the
 * alliance-manager.
 */
data class AliasEntry(val alias: String, val category: String)

/**
 * One member of the alliance roster as served by `/api/mobile/members`.
 *
 * [aliases] carries the rows the current user is permitted to see
 * (their own personals + every global + every OCR alias). The scanner's
 * RosterAliasResolver runs the same Exact → Personal → Global → OCR
 * lookup the backend does in `resolveMemberAlias` so on-device
 * disambiguation matches server-side behaviour. See the Consumer
 * Contract section of lastwar-screen-definitions/README.md.
 */
data class MemberSummary(
    val id: Int,
    val name: String,
    val rank: String,
    val aliases: List<AliasEntry> = emptyList()
)

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

    /** Categories stored locally that have no mobile API commit path. Includes both
     *  current YAML-style keys and legacy display-name keys for backwards compatibility. */
    val skipped: Set<String> = setOf(
        // Legacy keys
        "Kills", "Donation",
        // Current YAML category keys
        "kills", "donation_daily", "donation_weekly",
        "weekly",
        "mutual_assistance_daily", "mutual_assistance_weekly", "mutual_assistance_season",
        "siege_daily", "siege_weekly", "siege_season",
        "rare_soil_war_daily", "rare_soil_war_weekly", "rare_soil_war_season",
        "defeat_daily", "defeat_weekly", "defeat_season",
    )

    /** Ordered list of display names shown in the day selector spinner. */
    val syncableDisplayNames: List<String> = listOf("Mon", "Tues", "Wed", "Thur", "Fri", "Sat", "Power")
}
