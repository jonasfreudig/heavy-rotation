package app.recordshelf

import app.recordshelf.history.History
import app.recordshelf.history.Stats
import app.recordshelf.util.finiteJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Time listened, milestones, and Through the years: the race, every play, gaps, old and new, the depth number. */
class ThroughTest {
    private fun at(y: Int, m: Int, d: Int, sec: Long = 0) = LocalDateTime.of(y, m, d, 12, 0).toEpochSecond(ZoneOffset.UTC) + sec

    @Test
    fun minutesFromTheGapsBetweenPlays() {
        val b = History.Builder()
        // "Long" takes 10 minutes before the next play, "Short" 2; the last "Short" has nothing after it and keeps its usual length.
        for (k in 0 until 4) {
            val t0 = at(2026, 10, 1 + k)
            b.add(t0, "A", "Long", "")
            b.add(t0 + 600, "B", "Short", "")
            b.add(t0 + 720, "B", "Short", "")
        }
        val s = Stats(b.build(), ZoneId.of("UTC"), at(2026, 10, 30))
        val m = s.minutes(0, 12)
        val tracks = m.getJSONArray("tracks")
        assertEquals("Long", tracks.getJSONObject(0).getString("title"))
        assertEquals(600, tracks.getJSONObject(0).getInt("len"))
        assertEquals(40, tracks.getJSONObject(0).getLong("min"))
        assertEquals("by plays it is second", 2, tracks.getJSONObject(0).getInt("byPlays"))
        assertEquals(120, tracks.getJSONObject(1).getInt("len"))
        assertEquals(40 + 16, m.getLong("total"))
    }

    @Test
    fun milestonesOfEverythingArtistsAndSongs() {
        val b = History.Builder()
        repeat(1_000) { b.add(at(2025, 1, 1, it * 300L), if (it < 120) "A" else "B", if (it < 60) "One" else "Two", "") }
        val s = Stats(b.build(), ZoneId.of("UTC"), at(2026, 10, 30))
        val all = s.milestones(0, 1_000)
        val got = (0 until all.length()).map { all.getJSONObject(it).let { o -> "${o.getString("kind")} ${o.getInt("n")} ${o.getString("artist")}" } }
        assertTrue(got.toString(), "scrobble 1 A" in got && "scrobble 1000 B" in got && "artist 100 A" in got && "track 50 A" in got)
        assertEquals("in the order they came", "scrobble 1 A", got.first())
        val late = s.milestones(500, 1_000)
        assertTrue((0 until late.length()).none { late.getJSONObject(it).getInt("n") == 1 })
    }

    @Test
    fun throughTheYears() {
        val b = History.Builder()
        // Old: 60 plays in 2020, quiet since. Back: 60 plays in 2021, then again in 2026. New: 30 plays this October.
        repeat(60) { b.add(at(2020, 3, 1, it * 300L), "Old", "o", "") }
        repeat(60) { b.add(at(2021, 3, 1, it * 300L), "Back", "b", "") }
        repeat(10) { b.add(at(2026, 9, 1, it * 300L), "Back", "b", "") }
        repeat(30) { b.add(at(2026, 10, 1, it * 300L), "New", "n", "") }
        val s = Stats(b.build(), ZoneId.of("UTC"), at(2026, 10, 30))
        val j = s.throughTheYears()
        assertEquals(emptyList<String>(), j.finiteJson())

        val race = j.getJSONObject("race")
        val months = race.getInt("months")
        assertEquals((2026 - 2020) * 12 + 10 - 3 + 1, months)
        val back = (0 until race.getJSONArray("artists").length()).map { race.getJSONArray("artists").getJSONObject(it) }.first { it.getString("name") == "Back" }
        assertEquals(70, back.getJSONArray("cum").getInt(months - 1))

        val sc = j.getJSONObject("scatter")
        assertEquals(listOf("Old", "Back", "New"), (0 until 3).map { sc.getJSONArray("artists").getJSONObject(it).getString("name") })

        val gaps = j.getJSONObject("gaps")
        assertEquals("Old", gaps.getJSONArray("quiet").getJSONObject(0).getString("name"))
        assertEquals("Back", gaps.getJSONArray("back").getJSONObject(0).getString("name"))
        assertEquals(10, gaps.getJSONArray("back").getJSONObject(0).getInt("since"))

        val ages = j.getJSONObject("ages")
        assertEquals("Back", ages.getJSONArray("old").getJSONObject(0).getString("name"))
        assertEquals("New", ages.getJSONArray("new").getJSONObject(0).getString("name"))

        // Three artists with 60+ plays: 3 artists with 3 or more, not 4 with 4.
        val d = j.getJSONObject("depth")
        assertEquals(3, d.getJSONObject("artists").getInt("n"))
        assertEquals(1, d.getJSONObject("artists").getInt("next"))
        assertEquals(listOf(2020 to 1, 2021 to 2, 2026 to 3), (0 until d.getJSONArray("years").length()).map { d.getJSONArray("years").getJSONObject(it).let { y -> y.getInt("year") to y.getInt("n") } })
    }
}
