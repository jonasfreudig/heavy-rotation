package app.recordshelf

import app.recordshelf.discogs.CollectionItem
import app.recordshelf.history.History
import app.recordshelf.history.Stats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** One badge per record on the shelf, the strongest that fits. */
class BadgesTest {
    private fun at(y: Int, m: Int, d: Int, h: Int = 12) = LocalDateTime.of(y, m, d, h, 0).toEpochSecond(ZoneOffset.UTC)
    private val now = at(2026, 10, 27)

    private fun record(id: Long, artist: String, title: String, addedSec: Long) =
        CollectionItem(id, id, title, artist, 2020, addedSec * 1000, "", "", "LP", "", listOf("Rock"), emptyList(), 0)

    private fun badges(): org.json.JSONObject {
        val b = History.Builder()
        // Old Love: your most played, but not since 2024. Heavy: thirty plays before, twenty in the last ten days.
        repeat(60) { b.add(at(2024, 1, 1) + it * 3_600L, "Old Love", "Song ${it % 8}", "Then") }
        repeat(30) { b.add(at(2026, 3, 1) + it * 3_600L, "Heavy", "Song ${it % 9}", "Now") }
        repeat(20) { b.add(at(2026, 10, 18) + it * 3 * 3_600L, "Heavy", "Song ${it % 9}", "Now") }
        // Daily: forty plays this year, the last a week ago, only a few lately.
        repeat(40) { b.add(at(2026, 2, 1) + it * 6 * 86_400L, "Daily", "Song ${it % 7}", "Every Day") }
        // A song from an album played once: on the shelf, not "still sealed".
        b.add(at(2026, 5, 5), "Once", "Only Song", "Tried")
        val shelf = listOf(
            record(1, "Old Love", "Then", at(2020, 1, 1)),
            record(2, "Heavy", "Now", at(2025, 6, 1)),
            record(3, "Daily", "Every Day", at(2025, 6, 1)),
            record(4, "Untouched", "Still Wrapped", at(2024, 9, 1)),
            record(5, "Just", "In", at(2026, 10, 22)),
            record(6, "Once", "Tried", at(2025, 1, 1)),
        )
        return Stats(b.build(), ZoneId.of("UTC"), now, shelf).shelfBadges()
    }

    @Test
    fun eachRecordGetsItsStrongestBadge() {
        val b = badges()
        assertEquals("heavy", b.getJSONObject("2").getString("k"))
        assertEquals("plays in the last two weeks", 20, b.getJSONObject("2").getInt("n"))
        assertEquals("lost", b.getJSONObject("1").getString("k"))
        assertEquals("worn", b.getJSONObject("3").getString("k"))
        assertEquals("third most played, after Old Love and Heavy", 3, b.getJSONObject("3").getInt("rank"))
        assertEquals("sealed", b.getJSONObject("4").getString("k"))
        assertEquals("new before sealed", "new", b.getJSONObject("5").getString("k"))
        assertFalse("played once: no badge", b.has("6"))
    }
}
