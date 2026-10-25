package com.example.recordshelf

import com.example.recordshelf.history.History
import com.example.recordshelf.history.Range
import com.example.recordshelf.history.Stats
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class ExploreTest {
    private val utc = ZoneId.of("UTC")
    private fun at(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0) = LocalDateTime.of(y, m, d, h, min).toEpochSecond(ZoneOffset.UTC)
    private val all = Range("all", "All time", 0, Long.MAX_VALUE)

    private class Play(val ts: Long, val artist: String, val track: String, val album: String = "")

    private fun history(plays: List<Play>) = History.Builder().also { b -> plays.forEach { b.add(it.ts, it.artist, it.track, it.album) } }.build()

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    private fun JSONArray.strings(key: String) = objects().map { it.getString(key) }

    @Test
    fun trackPage() {
        val plays = listOf(
            Play(at(2026, 1, 5, 9), "Fontaines D.C.", "Starburster", "Romance"),
            Play(at(2026, 1, 5, 9, 4), "IDLES", "Grace", "TANGK"),
            Play(at(2026, 1, 5, 10), "Fontaines D.C.", "Starburster", "Romance"),
            Play(at(2026, 1, 5, 10, 4), "IDLES", "Grace", "TANGK"),
            Play(at(2026, 3, 1, 22), "Fontaines D.C.", "Starburster", "Romance"),
            Play(at(2026, 3, 2, 22), "Fontaines D.C.", "Bug", "Romance"),
        )
        val s = Stats(history(plays), utc, at(2026, 3, 10))
        val t = s.track("Fontaines D.C. feat. Someone", "Starburster (Remastered)", all)!!
        assertEquals("found despite the feature and the addition", "Starburster", t.getString("title"))
        assertEquals(3, t.getInt("plays"))
        assertEquals(2, t.getInt("days"))
        assertEquals(1, t.getInt("rank"))
        assertEquals(at(2026, 1, 5, 9) * 1000, t.getLong("first"))
        assertEquals(listOf(2, 0, 1), (0 until 3).map { t.getJSONArray("months").getInt(it) })
        assertEquals(2, t.getJSONObject("bestDay").getInt("count"))
        assertEquals("Romance", t.getJSONObject("album").getString("title"))
        assertEquals(0.75, t.getDouble("shareOfArtist"), 0.001)
        val next = t.getJSONArray("next").getJSONObject(0)
        assertEquals("what came next, twice", "Grace", next.getString("title"))
        assertEquals(2, next.getInt("count"))
        assertNull(s.track("Nobody", "Nothing", all))

        val a = s.album("Fontaines D.C.", "Romance", all)!!
        assertEquals(4, a.getInt("plays"))
        assertEquals(listOf("Starburster", "Bug"), a.getJSONArray("trackList").strings("title"))
        assertEquals(1, a.getInt("rank"))
    }

    @Test
    fun exploreFindsTheLongTail() {
        val now = at(2026, 10, 25)
        val plays = ArrayList<Play>()
        // A favorite from early 2025, quiet since: lost.
        repeat(20) { plays += Play(at(2025, 2, 1 + it), "Shame", listOf("Concrete", "One Rizla", "Tasteless")[it % 3], "Songs of Praise") }
        // Today's big ones.
        repeat(60) { plays += Play(now - 86_400L * (it + 1), "IDLES", "Grace", "TANGK") }
        // A quiet regular: a play or two in many months.
        for (m in 1..9) plays += Play(at(2026, m, 3), "Ulepuschkinrose", "Lied", "")
        // Played all day once.
        repeat(5) { plays += Play(at(2026, 4, 4, 10 + it), "Binge Band", "Hit $it") }
        // This day last year.
        plays += Play(at(2025, 10, 25, 20), "Shame", "Concrete", "Songs of Praise")
        val s = Stats(history(plays), utc, now)
        val e = s.explore()
        assertEquals(listOf("Shame"), e.getJSONArray("lostArtists").strings("name"))
        assertEquals(listOf("Songs of Praise"), e.getJSONArray("lostAlbums").strings("title"))
        assertTrue("Ulepuschkinrose" in e.getJSONArray("regulars").strings("name"))
        assertEquals(listOf("Binge Band"), e.getJSONArray("oneDay").strings("name"))
        val otd = e.getJSONArray("onThisDay").getJSONObject(0)
        assertEquals(2025, otd.getInt("year"))
        assertEquals("Concrete", otd.getJSONArray("tracks").getJSONObject(0).getString("title"))

        val lost = s.playlist("lost")
        assertEquals("Lost favorites", lost.getString("name"))
        assertEquals(setOf("Concrete", "One Rizla"), lost.getJSONArray("tracks").strings("title").toSet())
        val top = s.playlist("top", "m2026-04")
        assertEquals("m2026-04", top.getJSONObject("range").getString("id"))
        assertEquals(6, top.getJSONArray("tracks").length())
        val oneDay: JSONObject = s.playlist("oneDay")
        assertEquals(2, oneDay.getJSONArray("tracks").length())
    }
}
