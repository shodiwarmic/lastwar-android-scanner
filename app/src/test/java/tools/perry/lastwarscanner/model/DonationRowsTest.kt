package tools.perry.lastwarscanner.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class DonationRowsTest {

    private fun t(utc: String): Long = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(utc)!!.time

    private fun row(id: Long, name: String, score: Long, day: String, at: String) =
        PlayerScoreEntity(id = id, name = name, score = score, day = day, timestamp = t(at))

    @Test
    fun gameDateTurnsAtTwoUtc() {
        assertEquals("2026-10-13", DonationRows.gameDate(t("2026-10-14 01:59")))
        assertEquals("2026-10-14", DonationRows.gameDate(t("2026-10-14 02:00")))
        assertEquals("2026-10-14T01:30:00Z", DonationRows.capturedAt(t("2026-10-14 01:30")))
    }

    // Monday's daily total of 9,000 must not ride into Tuesday: a lower reading after the
    // reset is Tuesday's own row, with Tuesday's time — not MAX(score) with MAX(timestamp).
    @Test
    fun aLowerDailyReadingAfterTheResetIsItsOwnDay() {
        val rows = listOf(
            row(1, "Alder", 4000, DonationRows.DAILY, "2026-10-12 15:00"),
            row(2, "Alder", 9000, DonationRows.DAILY, "2026-10-13 01:00"), // still Monday
            row(3, "Alder", 300, DonationRows.DAILY, "2026-10-13 03:00"),  // Tuesday
        )
        val out = DonationRows.latestPerGameDay(rows, t("2026-10-13 12:00"))
        assertEquals(listOf(2L to 9000L, 3L to 300L), out.map { it.id to it.score })
    }

    // The week counter restarts on Monday: last Sunday's total stays Sunday's.
    @Test
    fun aLowerWeeklyReadingAfterMondaysResetIsItsOwnDay() {
        val rows = listOf(
            row(1, "Alder", 41000, DonationRows.WEEKLY, "2026-10-18 20:00"),
            row(2, "Alder", 1200, DonationRows.WEEKLY, "2026-10-19 10:00"),
        )
        val out = DonationRows.latestPerGameDay(rows, t("2026-10-19 12:00"))
        assertEquals(listOf("2026-10-18" to 41000L, "2026-10-19" to 1200L),
            out.map { DonationRows.gameDate(it.timestamp) to it.score })
    }

    @Test
    fun rowsOlderThanTheServerTakesAreLeftOut() {
        val rows = listOf(row(1, "Alder", 10, DonationRows.DAILY, "2026-09-01 12:00"))
        assertEquals(0, DonationRows.latestPerGameDay(rows, t("2026-10-14 12:00")).size)
    }

    @Test
    fun storeActionKeysOnTheGameDay() {
        val now = t("2026-10-14 20:00")
        // Nothing yet, or a different number: a new row.
        assertEquals(DonationRows.Store.INSERT, DonationRows.storeAction(null, 500, now))
        assertEquals(DonationRows.Store.INSERT,
            DonationRows.storeAction(row(1, "A", 400, DonationRows.DAILY, "2026-10-14 10:00"), 500, now))
        // The same number on a new game day: a new row, so that day has a reading.
        assertEquals(DonationRows.Store.INSERT,
            DonationRows.storeAction(row(1, "A", 500, DonationRows.DAILY, "2026-10-13 10:00"), 500, now))
        // The same number the same day: advance its time (Sunday's late scan finalises the week).
        assertEquals(DonationRows.Store.TOUCH,
            DonationRows.storeAction(row(1, "A", 500, DonationRows.WEEKLY, "2026-10-14 10:00"), 500, now))
    }
}
