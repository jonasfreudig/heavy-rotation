package app.recordshelf

import app.recordshelf.history.History
import app.recordshelf.history.Stats
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** The Day tab and the days of a week: sessions, firsts, the same date in other years, the usual for the weekday. */
class DayTest {
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0) = LocalDateTime.of(y, m, d, h, min).toEpochSecond(ZoneOffset.UTC)
    private val now = at(2026, 10, 27, 14)

    private fun stats(): Stats {
        val b = History.Builder()
        // A year ago today, and two earlier Tuesdays with four morning plays each.
        repeat(2) { b.add(at(2025, 10, 27, 20, it * 4), "Shame", "Alphabet", "Cutthroat") }
        for (d in listOf(13, 20)) repeat(4) { b.add(at(2026, 10, d, 9, it * 4), "Khruangbin", "Maria También", "Con Todo El Mundo") }
        // Today: a morning session of ten (one song three times), then an afternoon one with a new artist.
        repeat(10) { b.add(at(2026, 10, 27, 8, it * 4), "Khruangbin", if (it % 3 == 0) "Maria También" else "Song $it", "Con Todo El Mundo") }
        repeat(3) { b.add(at(2026, 10, 27, 13, it * 4), "Fontaines D.C.", "Starburster", "Romance") }
        b.add(at(2026, 10, 27, 13, 14), "Mdou Moctar", "Afrique Victime", "Afrique Victime")
        return Stats(b.build(), ZoneId.of("UTC"), now)
    }

    private fun day(): JSONObject = stats().let { it.compute(it.range("d2026-10-27")) }.getJSONObject("day")

    @Test
    fun aDayIsAPeriod() {
        val s = stats()
        val r = s.range("d2026-10-27")
        assertEquals("day", r.kind)
        assertEquals(at(2026, 10, 27, 0), r.from)
        val j = s.compute(r).getJSONObject("range")
        assertTrue("today is still running", j.getBoolean("running"))
        assertEquals("d2026-10-26", j.getString("prev"))
    }

    @Test
    fun sessionsSplitAtHalfAnHour() {
        val ss = day().getJSONArray("sessions")
        assertEquals(2, ss.length())
        assertEquals(10, ss.getJSONObject(0).getInt("count"))
        assertEquals("Khruangbin", ss.getJSONObject(0).getString("artist"))
        assertEquals("Con Todo El Mundo", ss.getJSONObject(0).getString("album"))
        assertEquals(4, ss.getJSONObject(1).getInt("count"))
        assertEquals("Fontaines D.C.", ss.getJSONObject(1).getString("artist"))
    }

    @Test
    fun firstsRepeatsAndNews() {
        val d = day()
        assertEquals("Maria También", d.getJSONObject("first").getString("title"))
        assertEquals("Afrique Victime", d.getJSONObject("latest").getString("title"))
        val top = d.getJSONArray("repeats").getJSONObject(0)
        assertEquals("Maria También", top.getString("title"))
        assertEquals("three times today", 4, top.getInt("plays"))
        // Fontaines D.C. and Mdou Moctar play here for the first time, the more played first.
        assertEquals(listOf("Fontaines D.C.", "Mdou Moctar"), (0 until d.getJSONArray("newArtists").length()).map { d.getJSONArray("newArtists").getString(it) })
        assertEquals("six morning songs, Starburster and Afrique Victime; Maria También is old", 8, d.getInt("newSongs"))
    }

    @Test
    fun thisDateInOtherYearsAndTheUsualTuesday() {
        val d = day()
        val y = d.getJSONArray("onThisDay").getJSONObject(0)
        assertEquals(2025, y.getInt("year"))
        assertEquals("d2025-10-27", y.getString("id"))
        assertEquals("Cutthroat", y.getString("album"))
        assertEquals(2, y.getInt("plays"))
        // Up to 14:00 on the last eight Tuesdays: 4 + 4 plays.
        val u = d.getJSONObject("usual")
        assertEquals(8, u.getInt("weeks"))
        assertEquals(1.0, u.getDouble("plays"), 1e-9)
    }

    @Test
    fun theStripKeepsTheClocksHoursOnADstDay() {
        // Clocks went forward at 2:00 on March 29th in Berlin: a play at 20:00 there is 19 hours after midnight.
        val berlin = ZoneId.of("Europe/Berlin")
        val b = History.Builder()
        b.add(LocalDateTime.of(2026, 3, 29, 20, 0).atZone(berlin).toEpochSecond(), "Air", "Playground Love", "The Virgin Suicides")
        val s = Stats(b.build(), berlin, LocalDateTime.of(2026, 3, 30, 12, 0).atZone(berlin).toEpochSecond())
        val plays = s.compute(s.range("d2026-03-29")).getJSONObject("day").getJSONArray("plays")
        assertEquals(20.0, plays.getJSONArray(0).getDouble(0), 1e-9)
    }

    @Test
    fun theDaysOfAWeek() {
        val s = stats()
        val w = s.compute(s.range("w2026-10-26")).getJSONObject("week")
        val days = w.getJSONArray("days")
        assertEquals(0, days.getInt(0))
        assertEquals(14, days.getInt(1))
        assertTrue("Wednesday hasn't come yet", days.isNull(2))
        assertEquals("last Tuesday", 4, w.getJSONArray("last").getInt(1))
        assertEquals(1L, w.getLong("today"))
        assertEquals(14, w.getJSONArray("strips").getJSONArray(1).length())
        assertFalse(s.compute(s.range("w2026-10-19")).getJSONObject("range").getBoolean("running"))
    }
}
