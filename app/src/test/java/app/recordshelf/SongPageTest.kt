package app.recordshelf

import app.recordshelf.history.History
import app.recordshelf.history.Stats
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** A song's page: where in a listening session it sits, and its runs on repeat. */
class SongPageTest {
    private fun at(d: Int, min: Int) = LocalDateTime.of(2026, 1, d, 20, 0).toEpochSecond(ZoneOffset.UTC) + min * 60L

    @Test
    fun sessionsAndRepeats() {
        val b = History.Builder()
        // Day 5: X opens, then A and B. Day 6: X three times in a row, then C. Day 7: A, then X closes. Day 8: X on its own.
        listOf(5 to "X", 5 to "A", 5 to "B", 6 to "X", 6 to "X", 6 to "X", 6 to "C", 7 to "A", 7 to "X", 8 to "X")
            .groupBy { it.first }
            .forEach { (d, songs) -> songs.forEachIndexed { k, (_, song) -> b.add(at(d, k * 4), "Art", song, "Album") } }
        val s = Stats(b.build(), ZoneId.of("UTC"), at(20, 0))
        val t = s.track("Art", "X", s.range("all"))!!
        val sits = t.getJSONObject("sits")
        assertEquals(2, sits.getInt("open"))
        assertEquals(2, sits.getInt("middle"))
        assertEquals(1, sits.getInt("close"))
        assertEquals(1, sits.getInt("alone"))
        assertEquals("three openers among nine plays in sessions of two or more", 3.0 / 9, sits.getDouble("baseOpen"), 1e-9)
        val runs = t.getJSONArray("runs")
        assertEquals(1, runs.length())
        assertEquals(3, runs.getJSONObject(0).getInt("n"))
        assertEquals(at(6, 0) * 1000, runs.getJSONObject(0).getLong("at"))
    }
}
