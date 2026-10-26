package app.recordshelf

import app.recordshelf.discogs.CollectionItem
import app.recordshelf.history.History
import app.recordshelf.history.Stats
import app.recordshelf.history.Years
import app.recordshelf.net.DzRelease
import app.recordshelf.util.Text
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class YearsTest {
    private fun rel(title: String, date: String, type: String = "album") = DzRelease(1, title, type, date, "", "")

    @Test
    fun earliestEditionOfTheTitle() {
        val releases = listOf(rel("Rumours (Super Deluxe)", "2013-01-29"), rel("Rumours", "1977-02-04"), rel("Tusk", "1979-10-12"), rel("Dreams", "1977-04-24", "single"))
        assertEquals(1977, Years.match(releases, "Rumours"))
        assertEquals("additions don't matter", 1977, Years.match(releases, "Rumours (Remastered)"))
        assertEquals(0, Years.match(releases, "Mirage"))
        assertEquals("a reissue on the shelf doesn't move the album", 1977, Years.earliest(0, 2016, 1977))
        assertEquals(0, Years.earliest(0, 0))
    }

    private fun at(y: Int, m: Int, d: Int) = LocalDateTime.of(y, m, d, 12, 0).toEpochSecond(ZoneOffset.UTC)

    @Test
    fun statsByDecade() {
        val b = History.Builder()
        repeat(3) { b.add(at(2026, 10, 1 + it), "Fleetwood Mac", "Dreams", "Rumours") }
        repeat(2) { b.add(at(2026, 10, 5 + it), "IDLES", "Grace", "TANGK") }
        b.add(at(2026, 10, 8), "Someone", "Single", "")
        b.add(at(2026, 10, 9), "Nobody", "Song", "Unknown Album")
        val h = b.build()
        val shelf = listOf(
            CollectionItem(1, 1, "Rumours", "Fleetwood Mac", 2016, 0, "", "", "LP", "", listOf("Rock"), listOf("Soft Rock"), 0),
            CollectionItem(2, 2, "TANGK", "IDLES", 2024, 0, "", "", "LP", "", listOf("Rock"), listOf("Post-Punk"), 0),
        )
        val years = mapOf(Text.albumKey("Fleetwood Mac", "Rumours") to 1977)
        val s = Stats(h, ZoneId.of("UTC"), at(2026, 10, 25), shelf, albumYears = years)
        val d = s.decadeOptions()
        assertEquals(5, d.getInt("known"))
        assertEquals(7, d.getInt("total"))
        val ds = d.getJSONArray("decades")
        assertEquals(listOf(1970 to 3, 2020 to 2), (0 until ds.length()).map { ds.getJSONObject(it).let { o -> o.getInt("decade") to o.getInt("plays") } })
        assertEquals("the shelf's 2016 reissue doesn't count over Deezer's 1977", 3, h.filter(s.inDecade(1970)).size)
        assertEquals(0, h.filter(s.inDecade(2010)).size)
        // TANGK has the shelf's year, but no lookup yet: still asked, once, with Nobody's album.
        val need = s.albumsNeedingYears(coverage = 1.0)
        assertEquals(listOf("IDLES", "Nobody"), need.map { it.first })
        assertEquals(listOf("TANGK" to Text.albumKey("IDLES", "TANGK")), need[0].second)
    }
}
