package app.recordshelf

import app.recordshelf.MiniHttp.Res
import app.recordshelf.discogs.CollectionItem
import app.recordshelf.history.Genres
import app.recordshelf.history.History
import app.recordshelf.history.Range
import app.recordshelf.history.Stats
import app.recordshelf.net.DeezerClient
import app.recordshelf.util.Palette
import app.recordshelf.util.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class PicturesTest {
    @Test
    fun paletteFindsTheCommonAndTheVividColor() {
        val px = IntArray(1000) { i ->
            when {
                i < 700 -> 0xff202020.toInt() // mostly near-black
                i < 950 -> 0xffd03030.toInt() // a strong red
                else -> 0xffffffff.toInt() // some white
            }
        }
        val c = Palette.of(px)!!
        assertEquals("#202020", Palette.hex(c.dominant))
        assertEquals("#d03030", Palette.hex(c.vibrant))
        val gray = Palette.of(IntArray(100) { 0xff808080.toInt() })!!
        assertEquals("nothing vivid: the common color", "#808080", Palette.hex(gray.vibrant))
        assertNull("fully transparent", Palette.of(IntArray(10)))
    }

    @Test
    fun deezerPhotoOfThatArtist() {
        val server = MiniHttp().start()
        try {
            server.handler = { r ->
                if (r.path == "/search/artist" && r.params["q"] == "Fontaines D.C.") Res(
                    200,
                    """{"data":[
                    {"id":2,"name":"Fontaines D.C.","picture_big":"https://cdn-images.dzcdn.net/images/artist//500x500-000000-80-0-0.jpg","nb_fan":0,"type":"artist"},
                    {"id":1,"name":"Fontaines D.C.","picture_big":"https://cdn-images.dzcdn.net/images/artist/ee62/500x500-000000-80-0-0.jpg","nb_fan":137249,"type":"artist"},
                    {"id":3,"name":"Fontaines DC Tribute","picture_big":"https://cdn-images.dzcdn.net/images/artist/aa11/500x500-000000-80-0-0.jpg","nb_fan":900000,"type":"artist"}],"total":3}""",
                ) else if (r.path == "/search/artist") Res(200, """{"error":{"type":"Exception","message":"Quota limit exceeded","code":4}}""")
                else Res(404, "")
            }
            val d = DeezerClient(server.base)
            assertEquals("https://cdn-images.dzcdn.net/images/artist/ee62/500x500-000000-80-0-0.jpg", d.artistPhoto("Fontaines D.C."))
            assertEquals("%20 for spaces, not +", true, server.requests.last().query.contains("Fontaines%20D.C."))
            try {
                d.artistPhoto("IDLES")
                fail()
            } catch (_: IOException) {
                // over the quota: tried again later
            }
        } finally {
            server.stop()
        }
    }

    private fun at(y: Int, m: Int, d: Int) = LocalDateTime.of(y, m, d, 12, 0).toEpochSecond(ZoneOffset.UTC)

    @Test
    fun photosAndWeeks() {
        val b = History.Builder()
        // Monday 2026-10-05 twice, Wednesday 2026-10-14 once; "now" is Sunday 2026-10-25.
        b.add(at(2026, 10, 5), "IDLES", "Grace", "TANGK")
        b.add(at(2026, 10, 5) + 300, "IDLES", "Grace", "TANGK")
        b.add(at(2026, 10, 14), "IDLES", "Grace", "TANGK")
        val all = Range("all", "All time", 0, Long.MAX_VALUE)
        val s = Stats(b.build(), ZoneId.of("UTC"), at(2026, 10, 25), albumImages = mapOf(Stats.photoKey("idles") to "https://photo/idles.jpg"))
        val t = s.track("IDLES", "Grace", all)!!
        assertEquals(LocalDateTime.of(2026, 10, 5, 0, 0).toEpochSecond(ZoneOffset.UTC) * 1000, t.getLong("weeksFrom"))
        assertEquals(listOf(2, 1, 0), (0 until t.getJSONArray("weeks").length()).map { t.getJSONArray("weeks").getInt(it) })
        val a = s.artist("IDLES", all)!!
        assertEquals("https://photo/idles.jpg", a.getString("photo"))
        assertEquals(3, a.getJSONArray("weeks").length())
        assertEquals("https://photo/idles.jpg", s.top(0, 3, 5).getJSONArray("artists").getJSONObject(0).getString("photo"))
        assertEquals("looked up already", emptyList<String>(), s.artistsNeedingPhotos("all"))
    }

    @Test
    fun aRecordShowsTheAlbumsUsualCoverUnlessYouChoseYourCopy() {
        // A pressing in a plain white sleeve: Discogs pictures that sleeve.
        val c = CollectionItem(7, 1, "Dogrel", "Fontaines D.C.", 2019, 0, "https://discogs/white-150.jpg", "https://discogs/white.jpg", "LP", "", listOf("Rock"), emptyList(), 0)
        val usual = "https://cdn-images.dzcdn.net/images/cover/abc/500x500-000000-80-0-0.jpg"
        val key = Text.albumKey("Fontaines D.C.", "Dogrel")
        val images = mapOf(key to usual)
        assertEquals(usual, c.face(images, own = false))
        assertEquals("a smaller one for the shelf's tiles", "https://cdn-images.dzcdn.net/images/cover/abc/250x250-000000-80-0-0.jpg", c.faceThumb(images, own = false))
        assertEquals("https://discogs/white.jpg", c.face(images, own = true))
        assertEquals("https://discogs/white-150.jpg", c.faceThumb(images, own = true))
        assertEquals("no other cover: your copy's", "https://discogs/white.jpg", c.face(mapOf(key to ""), own = false))
        val b = History.Builder()
        b.add(at(2026, 10, 5), "Fontaines D.C.", "Big", "Dogrel")
        val h = b.build()
        fun albumCover(own: Set<Long>) = Stats(h, ZoneId.of("UTC"), at(2026, 10, 25), listOf(c), Genres(listOf(c), emptyMap()), albumImages = images, ownCovers = own)
            .top(0, 1, 5).getJSONArray("albums").getJSONObject(0).getString("cover")
        assertEquals("the charts show the usual cover", usual, albumCover(emptySet()))
        assertEquals("or your copy's, as chosen", "https://discogs/white.jpg", albumCover(setOf(7L)))
    }
}
