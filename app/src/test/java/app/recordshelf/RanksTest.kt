package app.recordshelf

import app.recordshelf.history.History
import app.recordshelf.history.Stats
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** The whole top lists behind the pages' ranks. */
class RanksTest {
    private val utc = ZoneId.of("UTC")
    private fun at(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0) = LocalDateTime.of(y, m, d, h, min).toEpochSecond(ZoneOffset.UTC)
    private fun history(vararg plays: Triple<Long, String, String>) =
        History.Builder().also { b -> plays.forEach { (ts, artist, title) -> b.add(ts, artist, title, "Album of $artist") } }.build()

    @Test
    fun aTopListHasEveryPlaceWithTiesSharingTheirRank() {
        val base = at(2025, 6, 1)
        val plays = listOf("One", "One", "One", "Two", "Two", "Three", "Three", "Four").mapIndexed { k, title -> Triple(base + k * 600L, "X", title) } +
            Triple(at(2025, 6, 20), "Y", "Five")
        val s = Stats(history(*plays.toTypedArray()), utc, at(2025, 7, 1))
        val all = s.range("all")
        val list = s.rankList(all, "tracks", 0, 2, "X", "Four", around = true)
        assertEquals(5, list.getInt("total"))
        val rows = list.getJSONArray("rows")
        val titles = (0 until rows.length()).map { rows.getJSONObject(it).getString("title") }
        assertEquals("the top two, then the places around the page's song (ties by name)", listOf("One", "Three", "Two", "Five", "Four"), titles)
        assertEquals(listOf(1, 2, 2, 4, 4), (0 until rows.length()).map { rows.getJSONObject(it).getInt("rank") })
        assertEquals("Four", rows.getJSONObject(list.getInt("focus")).getString("title"))
        assertEquals("no song asked for: no place marked", -1, s.rankList(all, "tracks", 0, 100, "", "", around = true).getInt("focus"))
        val next = s.rankList(all, "tracks", 2, 100, "", "", around = false).getJSONArray("rows")
        assertEquals("further down, from place 3", 3, next.length())
        assertEquals(2, next.getJSONObject(0).getInt("pos"))

        val artists = s.rankList(all, "artists", 0, 100, "Y", "", around = true)
        assertEquals("X", artists.getJSONArray("rows").getJSONObject(0).getString("name"))
        assertEquals(1, artists.getInt("focus"))
        val albums = s.rankList(all, "albums", 0, 100, "X", "Album of X", around = true)
        assertEquals(0, albums.getInt("focus"))
        val page = s.artist("Y", all)!!
        assertEquals("the artist page's own all-time rank", 2, page.getInt("rank"))
        assertEquals(2, page.getInt("artists"))
    }
}
