package tools.perry.lastwarscanner.ocr

import tools.perry.lastwarscanner.network.AliasEntry
import tools.perry.lastwarscanner.network.MemberSummary

/**
 * On-device mirror of the backend's `resolveMemberAlias` function
 * (lastwar-alliance-manager/handlers_vs_import.go).
 *
 * Tries, in order:
 *   1. Exact name (case-insensitive)
 *   2. Personal alias  (category = "personal")
 *   3. Global   alias  (category = "global")
 *   4. OCR      alias  (category = "ocr")
 *
 * Returns the canonical [MemberSummary] and a [Match.matchType] string
 * that matches the backend's vocabulary so logs are comparable across
 * platforms. Returns `null` when no entry resolves.
 *
 * The roster passed in must already be scoped to what the current user is
 * permitted to see — the backend's `loadMobileRoster` strips other users'
 * personal aliases before they reach the device, so this resolver can
 * match against `category = "personal"` rows directly.
 *
 * See the Consumer Contract section of lastwar-screen-definitions/README.md
 * for the canonical algorithm.
 */
object RosterAliasResolver {

    private val ALIAS_PRIORITY = listOf("personal", "global", "ocr")

    data class Match(val member: MemberSummary, val matchType: String)

    /** Resolves [candidate] against [roster] using the alias hierarchy. */
    fun resolve(candidate: String, roster: List<MemberSummary>): Match? {
        if (candidate.isBlank() || roster.isEmpty()) return null
        val needle = candidate.trim().lowercase()

        // 1. Exact name match
        roster.firstOrNull { it.name.equals(candidate, ignoreCase = true) }
            ?.let { return Match(it, "exact") }

        // 2-4. Alias categories in priority order
        for (category in ALIAS_PRIORITY) {
            val hit = findAlias(roster, needle, category) ?: continue
            return Match(hit, "${category}_alias")
        }
        return null
    }

    /**
     * Tries each [candidates] split through [resolve]; returns the first match.
     * Mirrors the backend's `resolveOCRPlayer` walk over `OCRPlayer.Candidates`.
     * The caller picks whether to use the matched candidate's name+score or
     * fall back to `candidates[0]` when this returns null.
     */
    fun resolveCandidates(
        candidates: List<Pair<String, String>>,
        roster: List<MemberSummary>
    ): Pair<Match, Pair<String, String>>? {
        for (c in candidates) {
            val m = resolve(c.first, roster) ?: continue
            return m to c
        }
        return null
    }

    private fun findAlias(
        roster: List<MemberSummary>,
        needleLower: String,
        category: String
    ): MemberSummary? {
        for (m in roster) {
            for (a in m.aliases) {
                if (a.category == category && a.alias.lowercase() == needleLower) return m
            }
        }
        return null
    }

    /** Equivalent of [AliasEntry] match with case-insensitive comparison. */
    @Suppress("unused") // kept for consumers that want a direct alias check
    fun matches(alias: AliasEntry, candidate: String): Boolean =
        alias.alias.equals(candidate, ignoreCase = true)
}
