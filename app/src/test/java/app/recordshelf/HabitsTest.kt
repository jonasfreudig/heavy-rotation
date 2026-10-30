package app.recordshelf

import app.recordshelf.history.History
import app.recordshelf.history.Stats
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Habits (who shows up week after week, who came in one wave) and the new artists of a period, with how you found them. */
class HabitsTest {
    private fun at(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0) = LocalDateTime.of(y, m, d, h, min).toEpochSecond(ZoneOffset.UTC)

    private fun stats(): Stats {
        val b = History.Builder()
        // Steady: a play every week of the year so far. Wave: thirty plays in March, none after.
        for (w in 0 until 40) b.add(at(2026, 1, 5) + w * 7 * 86_400L, "Steady", "Song $w", "Every Week")
        repeat(30) { b.add(at(2026, 3, 2) + it * 20 * 3_600L, "Wave", "Wave $it", "One Month") }
        // Newbie comes on right after Gateway, in the same session, and stays (twelve plays).
        b.add(at(2026, 4, 1, 20, 0), "Gateway", "Door", "Hall")
        repeat(12) { b.add(at(2026, 4, 1, 20, 4) + it * 86_400L, "Newbie", "Found $it", "First") }
        return Stats(b.build(), ZoneId.of("UTC"), at(2026, 10, 27))
    }

    private fun rows(o: JSONObject, key: String) = o.getJSONArray(key).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    @Test
    fun steadyHabitsComeFirstAndWavesShow() {
        val s = stats()
        val hb = s.compute(s.range("y2026")).getJSONObject("habits")
        val rows = rows(hb, "rows")
        assertEquals("Steady", rows[0].getString("name"))
        assertEquals(40, rows[0].getInt("present"))
        val wave = rows.first { it.getString("name") == "Wave" }
        assertEquals("all of it in one month", 1.0, wave.getDouble("peakShare"), 1e-9)
        assertEquals("the most played keeps the record's first color", 0, rows.first { it.getString("name") == "Steady" }.getInt("ink"))
        assertEquals(hb.getJSONArray("starts").length(), rows[0].getJSONArray("counts").length())
    }

    @Test
    fun newArtistsSayHowYouFoundThem() {
        val s = stats()
        val na = s.compute(s.range("y2026")).getJSONObject("newArtists")
        val newbie = rows(na, "found").first { it.getString("name") == "Newbie" }
        assertEquals("Gateway", newbie.getString("from"))
        assertEquals(12, newbie.getInt("plays"))
        assertEquals("Wave and Newbie have ten plays or more; Steady was there from the history's first days", 2, na.getInt("kept"))
    }

    @Test
    fun theCalendarHasEveryDayAndMarksNewArtistsAndRepeats() {
        val s = stats()
        val april = s.compute(s.range("m2026-04")).getJSONObject("calendar")
        assertEquals("day", april.getString("unit"))
        assertEquals(30, april.getInt("days"))
        assertEquals(30, april.getJSONArray("counts").length())
        assertEquals("Gateway and Newbie came on April 1", listOf(0), (0 until april.getJSONArray("newDays").length()).map { april.getJSONArray("newDays").getInt(it) })
        val all = s.compute(s.range("all")).getJSONObject("calendar")
        assertEquals("month", all.getString("unit"))
        val counts = all.getJSONArray("counts")
        assertEquals(40 + 30 + 1 + 12, (0 until counts.length()).sumOf { counts.getInt(it) })

        // A song four times in a row on May 6, and another seven times in one week, most on May 13.
        val b = History.Builder()
        b.add(at(2026, 5, 1), "Someone", "Before", "A")
        repeat(4) { b.add(at(2026, 5, 6, 20) + it * 240L, "Looper", "Again", "B") }
        repeat(3) { b.add(at(2026, 5, 12) + it * 3_600L, "Fan", "Favorite", "C") }
        repeat(4) { b.add(at(2026, 5, 13) + it * 3_600L, "Fan", "Favorite", "C") }
        val t = Stats(b.build(), ZoneId.of("UTC"), at(2026, 5, 20))
        val may = t.compute(t.range("m2026-05")).getJSONObject("calendar")
        assertEquals("only the days so far", 20, may.getJSONArray("counts").length())
        assertEquals(31, may.getInt("days"))
        val rep = may.getJSONArray("repeatDays").let { a -> (0 until a.length()).map { a.getInt(it) } }
        assertEquals(listOf(5, 12), rep)
        val ob = t.compute(t.range("m2026-05")).getJSONArray("obsessions").getJSONObject(0)
        assertEquals("C", ob.getString("album"))
    }

    @Test
    fun theChartSaysWhereEachStoodThePeriodBefore() {
        val s = stats()
        val top = s.compute(s.range("m2026-04")).getJSONObject("top")
        assertEquals(true, top.getBoolean("compared"))
        val artists = rows(top, "artists")
        assertEquals("Newbie", artists[0].getString("name"))
        assertEquals("not played in March", 0, artists[0].getInt("last"))
        assertEquals("second in March, after Wave", 2, artists.first { it.getString("name") == "Steady" }.getInt("last"))
        assertEquals("all time has nothing before it", false, s.compute(s.range("all")).getJSONObject("top").getBoolean("compared"))
    }

    @Test
    fun theStripHasEveryPeriodSinceTheHistoryStarted() {
        val s = stats()
        val months = rows(s.compute(s.range("m2026-04")).getJSONObject("strip"), "items")
        assertEquals("m2026-01", months.first().getString("id"))
        assertEquals("m2026-10", months.last().getString("id"))
        assertEquals(40 + 30 + 1 + 12, months.sumOf { it.getInt("plays") })
        val weeks = rows(s.compute(s.range("w2026-04-01")).getJSONObject("strip"), "items")
        assertEquals("w2026-01-05", weeks.first().getString("id"))
        assertEquals(0, rows(s.compute(s.range("all")).getJSONObject("strip"), "items").size)
    }
}
