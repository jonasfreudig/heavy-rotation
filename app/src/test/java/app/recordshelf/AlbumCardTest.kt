package app.recordshelf

import app.recordshelf.history.History
import app.recordshelf.history.Stats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Album cards: times played through, counting only an album's songs played as an album. */
class AlbumCardTest {
    private fun at(d: Int, sec: Long = 0) = LocalDateTime.of(2026, 3, d, 20, 0).toEpochSecond(ZoneOffset.UTC) + sec

    private fun stats(): Stats {
        val b = History.Builder()
        // "LP" (six songs) played through on days 1 to 5, the first time only its first half.
        for (d in 1..5) {
            val songs = if (d == 1) 1..3 else 1..6
            for (k in songs) b.add(at(d, k * 240L), "Band", "Song $k", "LP")
        }
        // Its first song alone, on shuffle between other things: no album listening.
        for (d in 6..25) {
            b.add(at(d), "Band", "Song 1", "LP")
            b.add(at(d, 200), "Other", "Tune", "Single")
        }
        return Stats(b.build(), ZoneId.of("UTC"), at(28))
    }

    @Test
    fun timesThroughCountOnlyTheAlbumPlayedAsOne() {
        val c = stats().albumCard("Band", "LP")!!
        assertEquals("four times all six songs, once half of them", 4.5, c.getDouble("through"), 1e-9)
        assertEquals("bronze (2 and more), not silver (5)", 0, c.getInt("tier"))
        val tierAt = c.getJSONArray("tierAt")
        assertEquals(1, tierAt.length())
        assertEquals("bronze with the run that took it past twice through", at(3, 6 * 240L) * 1000, tierAt.getLong(0))
        assertEquals("all its plays, the ones on shuffle too", 3 + 4 * 6 + 20, c.getInt("plays"))
    }

    @Test
    fun aSingleOnShuffleIsNoCard() {
        val s = stats()
        assertEquals(0.0, s.albumCard("Other", "Single")!!.getDouble("through"), 1e-9)
        assertEquals(-1, s.albumCard("Other", "Single")!!.getInt("tier"))
        assertNull(s.albumCard("Nobody", "Nothing"))
        val cards = s.cards()
        assertEquals(listOf("LP"), (0 until cards.getJSONArray("albums").length()).map { cards.getJSONArray("albums").getJSONObject(it).getString("title") })
        assertEquals(1, s.cardsSummary().getInt("albums"))
    }
}
