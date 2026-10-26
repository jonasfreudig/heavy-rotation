package app.recordshelf

import app.recordshelf.MiniHttp.Res
import app.recordshelf.history.Global
import app.recordshelf.history.GlobalAsk
import app.recordshelf.history.GlobalTop
import app.recordshelf.history.Globals
import app.recordshelf.history.History
import app.recordshelf.history.Stats
import app.recordshelf.lastfm.LastfmClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** You against everyone: Last.fm's counts, kept on the phone, and the comparisons made from them. */
class VsTest {
    private fun at(d: Int, i: Int) = LocalDateTime.of(2026, 9, d, 10, 0).toEpochSecond(ZoneOffset.UTC) + i * 300L
    private val now = at(28, 0)
    private val nowMs = now * 1000

    /** A: 30 plays over three spellings of two songs; B: 12 plays; C: 5 plays; an hour apart from September 1. */
    private fun history(): History {
        val b = History.Builder()
        var i = 0
        fun add(n: Int, artist: String, title: String, album: String) = repeat(n) { b.add(at(1, 0) + (i++) * 3600L, artist, title, album) }
        add(20, "A", "Song A1", "First")
        add(6, "A", "Song A1 (Remastered)", "First")
        add(4, "A", "Hit", "First")
        add(12, "B", "Quiet One", "Small")
        add(5, "C", "Middle", "Mid")
        return b.build()
    }

    private fun globals() = Globals(
        mapOf(
            Globals.artistKey("A") to Global(2_000_000, 100_000_000, nowMs),
            Globals.artistKey("B") to Global(5_000, 50_000, nowMs),
            Globals.artistKey("C") to Global(300_000, 3_000_000, nowMs),
            Globals.trackKey("A", "Song A1") to Global(1_000_000, 5_000_000, nowMs),
            Globals.trackKey("B", "Quiet One") to Global(0, 0, nowMs),
        ),
        mapOf(Globals.artistKey("A") to GlobalTop(listOf("Hit" to 9_000_000L, "Song A1" to 5_000_000L), nowMs)),
    )

    private fun stats(g: Globals = globals()) = Stats(history(), ZoneId.of("UTC"), now, globals = g)

    @Test
    fun youAgainstEveryone() {
        val v = stats().vs()
        assertEquals(47, v.getInt("knownPlays"))
        assertEquals(3, v.getInt("knownArtists"))
        val tiers = v.getJSONArray("tiers")
        assertEquals("A is a household name", 30, tiers.getJSONObject(0).getInt("plays"))
        assertEquals(5, tiers.getJSONObject(1).getInt("plays"))
        assertEquals("B is underground", 12, tiers.getJSONObject(4).getInt("plays"))
        assertEquals("B", tiers.getJSONObject(4).getString("top"))
        assertEquals("half the plays are A's", 2_000_000L, v.getLong("median"))
        val fans = v.getJSONArray("fans")
        assertEquals("C has too few plays to be a fan of", 2, fans.length())
        assertEquals("B", fans.getJSONObject(0).getString("name"))
        assertEquals(1.2, fans.getJSONObject(0).getDouble("ratio"), 1e-9)
        assertEquals(10.0, fans.getJSONObject(0).getDouble("avg"), 1e-9)
        assertEquals(0.6, fans.getJSONObject(1).getDouble("ratio"), 1e-9)
        assertEquals("B", v.getJSONArray("shares").getJSONObject(0).getString("name"))
        assertEquals(12.0 / 50_000, v.getJSONArray("shares").getJSONObject(0).getDouble("share"), 1e-12)
        assertEquals(listOf("A"), (0 until v.getJSONArray("gems").length()).map { v.getJSONArray("gems").getJSONObject(it).getString("name") })
        val song = v.getJSONArray("songs").getJSONObject(0)
        assertEquals("Song A1", song.getString("title"))
        assertEquals(4.0, song.getDouble("ratio"), 1e-9)
        val taste = v.getJSONArray("taste").getJSONObject(0)
        assertEquals("A", taste.getString("artist"))
        assertEquals("Song A1", taste.getJSONObject("mine").getString("title"))
        assertEquals("both spellings count", 26, taste.getJSONObject("mine").getInt("plays"))
        assertEquals(2, taste.getJSONObject("mine").getInt("rank"))
        assertEquals("Hit", taste.getJSONObject("theirs").getString("title"))
        assertEquals(4, taste.getJSONObject("theirs").getInt("mine"))
        assertEquals(2, taste.getInt("heard"))
    }

    @Test
    fun pagesCompareToo() {
        val s = stats()
        val a = s.artist("A", s.range("all"))!!
        assertEquals(0.6, a.getJSONObject("global").getDouble("ratio"), 1e-9)
        val top = a.getJSONArray("globalTop")
        assertEquals("Hit", top.getJSONObject(0).getString("title"))
        assertEquals(26, top.getJSONObject(1).getInt("mine"))
        val t = s.track("A", "Song A1", s.range("all"))!!
        assertEquals(4.0, t.getJSONObject("global").getDouble("ratio"), 1e-9)
        assertEquals(2, t.getInt("globalRank"))
        val b = s.track("B", "Quiet One", s.range("all"))!!
        assertFalse("Last.fm didn't know it", b.has("global"))
    }

    @Test
    fun whatToLookUp() {
        val fresh = stats().globalsNeeded(nowMs)
        assertEquals(
            "artists first, then the top lists not kept yet, then songs with 4+ plays, most played first",
            listOf(
                GlobalAsk(GlobalAsk.Kind.TOP, "B"), GlobalAsk(GlobalAsk.Kind.TOP, "C"),
                GlobalAsk(GlobalAsk.Kind.TRACK, "A", "Song A1 (Remastered)"), GlobalAsk(GlobalAsk.Kind.TRACK, "C", "Middle"), GlobalAsk(GlobalAsk.Kind.TRACK, "A", "Hit"),
            ),
            fresh,
        )
        val none = stats(Globals()).globalsNeeded(nowMs)
        assertEquals(GlobalAsk(GlobalAsk.Kind.ARTIST, "A"), none[0])
        assertEquals(3 + 3 + 5, none.size)
        val later = stats().globalsNeeded(nowMs + Globals.TTL + 1)
        assertTrue("a month later everything is asked again", later.size > fresh.size)
    }

    @Test
    fun keptOnThePhone() {
        val g = Globals.fromJson(globals().toJson().toString())
        assertEquals(50.0, g.artist("A")!!.perListener, 1e-9)
        assertNull("unknown to Last.fm", g.track("B", "Quiet One"))
        assertTrue(g.fresh(Globals.trackKey("B", "Quiet One"), nowMs))
        assertEquals(listOf("Hit", "Song A1"), g.top("a")!!.map { it.first })
        assertEquals(0, Globals.fromJson("not json").counts.size)
    }

    @Test
    fun lastfmCounts() {
        val server = MiniHttp().start()
        try {
            server.handler = { r ->
                val p = r.params
                when {
                    p["autocorrect"] != "1" -> Res(400, """{"error":6,"message":"no autocorrect"}""")
                    p["method"] == "artist.getInfo" && p["artist"] == "IDLES" ->
                        Res(200, """{"artist":{"name":"IDLES","stats":{"listeners":"812345","playcount":"45678901","userplaycount":"1240"}}}""")
                    p["method"] == "track.getInfo" -> Res(200, """{"track":{"name":"Grace","listeners":"1000","playcount":"5000"}}""")
                    p["method"] == "album.getInfo" -> Res(200, """{"album":{"name":"TANGK","listeners":"300","playcount":"9000"}}""")
                    p["method"] == "artist.getTopTracks" && p["artist"] == "IDLES" ->
                        Res(200, """{"toptracks":{"track":[{"name":"Never Fight a Man With a Perm","playcount":"9000000"},{"name":"Grace","playcount":"2000000"}]}}""")
                    p["method"] == "artist.getTopTracks" -> Res(200, """{"toptracks":{"track":{"name":"Only One","playcount":"7"}}}""")
                    else -> Res(200, """{"error":6,"message":"The artist you supplied could not be found"}""")
                }
            }
            val c = LastfmClient("k", "s", server.base + "/2.0/")
            val a = c.artistCounts("IDLES")!!
            assertEquals(812_345L, a.listeners)
            assertEquals(45_678_901L, a.playcount)
            assertNull(c.artistCounts("Nobody At All"))
            assertEquals(5000L, c.trackCounts("IDLES", "Grace")!!.playcount)
            assertEquals(300L, c.albumCounts("IDLES", "TANGK")!!.listeners)
            assertEquals(listOf("Never Fight a Man With a Perm", "Grace"), c.artistTopTracks("IDLES").map { it.first })
            assertEquals("one track comes as an object", listOf("Only One" to 7L), c.artistTopTracks("Tiny"))
        } finally {
            server.stop()
        }
    }
}
