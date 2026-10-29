package app.recordshelf

import app.recordshelf.history.History
import app.recordshelf.history.Moments
import app.recordshelf.history.Photo
import app.recordshelf.history.Range
import app.recordshelf.history.Stats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** The whole top lists behind the pages' ranks, and which photos a story may show. */
class RanksAndPhotosTest {
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

    @Test
    fun storiesShowThePhotosTheSettingsAllow() {
        val h = history(
            Triple(at(2025, 6, 14, 18, 30), "Ezra Collective", "Ajala"),
            Triple(at(2025, 6, 14, 18, 36), "Ezra Collective", "Victory Dance"),
            Triple(at(2025, 6, 18, 10, 0), "Ezra Collective", "Ajala"),
        )
        val s = Stats(h, utc, at(2025, 7, 1))
        val r = Range("m2025-06", "June 2025", at(2025, 6, 1, 0), at(2025, 7, 1, 0), "month")
        val photos = listOf(
            Photo(1, at(2025, 6, 14, 18, 33), 4000, 3000), // a song under way
            Photo(2, at(2025, 6, 18, 10, 30), 4000, 3000), // half an hour after one began
            Photo(3, at(2025, 6, 24, 12, 0), 4000, 3000), // no music that day
        )
        fun ids(mode: String, hidden: Set<Long> = emptySet()) = s.moments(photos, r, mode, hidden).let { a -> (0 until a.length()).map { a.getJSONObject(it).getLong("id") } }
        assertEquals("by default only photos taken while a song played", listOf(1L), ids("playing"))
        assertEquals(listOf(1L, 2L), ids("near"))
        assertEquals(listOf(1L, 2L, 3L), ids("any"))
        assertEquals("hidden ones stay out", listOf(2L, 3L), ids("any", setOf(1L)))
        assertEquals(emptyList<Long>(), ids("playing", setOf(1L)))
    }

    @Test
    fun photoFoldersAreReadFromTheStoragesTop() {
        assertEquals("DCIM/Camera", Moments.folderOf("DCIM/Camera/", whole = false))
        assertEquals("DCIM/Camera", Moments.folderOf("/storage/emulated/0/DCIM/Camera/IMG_1.jpg", whole = true))
        assertEquals("Pictures/Snapseed", Moments.folderOf("/storage/1A2B-3C4D/Pictures/Snapseed/x.jpg", whole = true))
        assertTrue(Moments.byCamera("DCIM/Camera"))
        assertTrue(Moments.byCamera("DCIM"))
        assertTrue(Moments.byCamera("Pictures"))
        assertTrue(Moments.byCamera("Pictures/Snapseed"))
        assertFalse(Moments.byCamera("DCIM/Screenshots"))
        assertFalse(Moments.byCamera("Pictures/WhatsApp Images"))
        assertFalse(Moments.byCamera("Download"))
    }
}
