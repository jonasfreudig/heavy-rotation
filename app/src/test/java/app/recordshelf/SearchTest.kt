package app.recordshelf

import app.recordshelf.discogs.CollectionItem
import app.recordshelf.history.History
import app.recordshelf.history.Stats
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Searching everything you listened to: artists, albums and songs, best matches first. */
class SearchTest {
    private val t0 = LocalDateTime.of(2026, 9, 1, 10, 0).toEpochSecond(ZoneOffset.UTC)

    private fun stats(collection: List<CollectionItem> = emptyList()): Stats {
        val b = History.Builder()
        var i = 0
        fun add(n: Int, artist: String, title: String, album: String) = repeat(n) { b.add(t0 + (i++) * 600L, artist, title, album) }
        add(6, "The Strokes", "Reptilia", "Room on Fire")
        add(4, "The Strokes", "Last Nite", "Is This It")
        add(2, "Strokes Tribute Band", "Someday", "Covers")
        add(5, "Fontaines D.C.", "Starburster", "Romance")
        add(3, "Khruangbin", "María También", "Con Todo El Mundo")
        add(20, "Wildfire", "Burn", "Embers")
        add(1, "Firebird", "Wings", "Feathers")
        return Stats(b.build(), ZoneId.of("UTC"), t0 + 86_400L, collection)
    }

    private fun JSONArray.names(key: String) = (0 until length()).map { getJSONObject(it).getString(key) }

    @Test
    fun findsArtistsAlbumsAndSongs() {
        val r = stats().search("strokes")
        assertEquals("strokes", r.getString("q"))
        assertEquals(listOf("The Strokes", "Strokes Tribute Band"), r.getJSONArray("artists").names("name"))
        assertEquals(10, r.getJSONArray("artists").getJSONObject(0).getInt("plays"))
        // Albums and songs match through their artist too.
        assertEquals(listOf("Room on Fire", "Is This It", "Covers"), r.getJSONArray("albums").names("title"))
        assertEquals(listOf("Reptilia", "Last Nite", "Someday"), r.getJSONArray("tracks").names("title"))
    }

    @Test
    fun everyWordCountsAcrossTitleAndArtist() {
        val r = stats().search("fire strokes")
        assertEquals(listOf("Room on Fire"), r.getJSONArray("albums").names("title"))
        assertEquals(0, r.getJSONArray("artists").length())
    }

    @Test
    fun accentsAndCaseDontMatter() {
        val r = stats().search("MARIA tambien")
        assertEquals(listOf("María También"), r.getJSONArray("tracks").names("title"))
        assertEquals("Khruangbin", r.getJSONArray("tracks").getJSONObject(0).getString("artist"))
    }

    @Test
    fun wordStartsBeatPlays() {
        // "Firebird" starts with the query; "Wildfire" only contains it, though played far more.
        assertEquals(listOf("Firebird", "Wildfire"), stats().search("fire").getJSONArray("artists").names("name"))
    }

    @Test
    fun ownedAlbumsAreMarked() {
        val owned = listOf(CollectionItem(1, 1, "Room On Fire", "The Strokes", 2003, 0, "", "", "LP", "", listOf("Rock"), listOf("Indie Rock"), 0))
        val albums = stats(owned).search("room").getJSONArray("albums")
        assertTrue(albums.getJSONObject(0).getBoolean("owned"))
        assertFalse(stats().search("room").getJSONArray("albums").getJSONObject(0).getBoolean("owned"))
    }

    @Test
    fun emptyQueryFindsNothing() {
        val r = stats().search("  ")
        assertEquals(0, r.getJSONArray("artists").length() + r.getJSONArray("albums").length() + r.getJSONArray("tracks").length())
    }
}
