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
}
