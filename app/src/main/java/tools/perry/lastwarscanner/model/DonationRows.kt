package tools.perry.lastwarscanner.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The Donation screen's two tabs, and the storage and sync rules they need.
 *
 * Power and kills only rise, so "the highest score, the latest time" describes a member. A
 * donation is a counter that resets — *today* at the 02:00 UTC daily reset, *week* on
 * Monday — so a reading only means something on the game day it was read. Rows are therefore
 * kept and sent per **game day** (UTC−2), each with its own score and its own time, and the
 * server derives the days from them (alliance-manager `donations.go`).
 */
object DonationRows {
    const val DAILY = "donation_daily"
    const val WEEKLY = "donation_weekly"
    val CATEGORIES = listOf(DAILY, WEEKLY)

    /** The game day starts at 02:00 UTC (a fixed UTC−2, no daylight saving). */
    private const val GAME_OFFSET_MS = 2 * 60 * 60 * 1000L

    /** The server refuses a reading more than 30 days old; older rows are not sent. */
    const val MAX_AGE_MS = 29L * 24 * 60 * 60 * 1000

    fun isDonation(category: String) = category in CATEGORIES

    /** A timestamp's game date, YYYY-MM-DD. */
    fun gameDate(timestampMs: Long): String = utcFormat("yyyy-MM-dd").format(Date(timestampMs - GAME_OFFSET_MS))

    /** RFC 3339 UTC, the mobile API's `captured_at`. */
    fun capturedAt(timestampMs: Long): String = utcFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").format(Date(timestampMs))

    private fun utcFormat(pattern: String) =
        SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    /**
     * What to send for one category: for each name and game day, the latest row, with its own
     * score and its own time — never the day's highest score (a counter that resets is not
     * monotone). Rows older than [MAX_AGE_MS] before [nowMs] are left out.
     */
    fun latestPerGameDay(rows: List<PlayerScoreEntity>, nowMs: Long): List<PlayerScoreEntity> =
        rows.filter { it.timestamp >= nowMs - MAX_AGE_MS }
            .groupBy { it.name to gameDate(it.timestamp) }
            .values
            .map { group -> group.maxWith(compareBy<PlayerScoreEntity>({ it.timestamp }, { it.id })) }
            .sortedWith(compareBy({ it.name }, { it.timestamp }))

    /** What the capture service does with a donation reading. */
    enum class Store { INSERT, TOUCH }

    /**
     * Keyed on (name, category, game day), unlike power and kills (name only, across
     * categories): a reading on a new game day is a new row even when the number is the same,
     * a different number on the same day is a new row, and the same number on the same day
     * advances the stored row's time — Sunday's last reading is what makes a week final.
     * [latest] is the member's latest row in this category.
     */
    fun storeAction(latest: PlayerScoreEntity?, score: Long, nowMs: Long): Store =
        if (latest != null && latest.score == score && gameDate(latest.timestamp) == gameDate(nowMs)) Store.TOUCH
        else Store.INSERT
}
