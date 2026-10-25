package com.example.recordshelf

import com.example.recordshelf.discogs.CollectionItem
import com.example.recordshelf.history.Genres
import com.example.recordshelf.history.History
import com.example.recordshelf.history.Range
import com.example.recordshelf.history.Stats
import com.example.recordshelf.scrobble.Listen
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class StatsTest {
    private val utc = ZoneId.of("UTC")
    private fun at(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0) = LocalDateTime.of(y, m, d, h, min).toEpochSecond(ZoneOffset.UTC)

    private class Play(val ts: Long, val artist: String, val track: String, val album: String = "")

    private fun history(plays: List<Play>) = History.Builder().also { b -> plays.forEach { b.add(it.ts, it.artist, it.track, it.album) } }.build()

    private fun item(id: Long, artist: String, title: String, styles: List<String>) =
        CollectionItem(id, id, title, artist, 1977, 0, "", "cover$id", "Vinyl, LP", "", listOf("Rock"), styles, 0)

    private val all = Range("all", "All time", 0, Long.MAX_VALUE)

    @Test
    fun historyMergesSpellingsAndSorts() {
        val h = history(listOf(Play(100, "Motörhead", "Ace Of Spades", "Ace of Spades"), Play(300, "Motorhead", "Ace of Spades"), Play(200, "Air", "Talisman")))
        assertEquals(listOf(100L, 200L, 300L), h.ts.toList())
        assertEquals(2, h.artistNames.size)
        assertEquals("the latest spelling wins", "Motorhead", h.artistNames[h.artist[0]])
        assertEquals(h.track[0], h.track[2])
        assertEquals(-1, h.album[1])
        assertEquals(1, h.lowerBound(150))
    }

    @Test
    fun listeningClockAndSummary() {
        val h = history(
            listOf(
                Play(at(2025, 10, 20, 8, 5), "A", "x"), Play(at(2025, 10, 20, 8, 40), "B", "y"),
                Play(at(2025, 10, 22, 22, 10), "A", "z"),
            ),
        )
        val s = Stats(h, utc, at(2025, 10, 25))
        val c = s.clock(0, h.size)
        assertEquals(2, c[0 * 24 + 8]) // Monday 08:00
        assertEquals(1, c[2 * 24 + 22]) // Wednesday 22:00
        assertEquals(3, c.sum())
        val sum = s.summary(Range("x", "x", at(2025, 10, 20, 0), at(2025, 10, 27, 0)), 0, h.size)
        assertEquals(3, sum.getInt("scrobbles"))
        assertEquals(2, sum.getInt("artists"))
        assertEquals(2, sum.getInt("days"))
    }

    @Test
    fun clockUsesLocalTime() {
        val berlin = ZoneId.of("Europe/Berlin")
        val h = history(listOf(Play(at(2025, 7, 7, 21, 30), "A", "x"))) // 23:30 in Berlin (summer time)
        assertEquals(1, Stats(h, berlin, at(2025, 7, 8)).clock(0, 1)[0 * 24 + 23])
    }

    @Test
    fun streaks() {
        val days = listOf(1, 2, 3, 4, 5, 8, 9)
        val plays = days.map { Play(at(2025, 3, it), "Daily", "t$it") } + (1..4).map { Play(at(2025, 3, it, 20), "Fan", "f") }
        val h = history(plays)
        val s = Stats(h, utc, at(2025, 3, 9, 23))
        val st = s.streaks(all, 0, h.size)
        assertEquals(5, st.getInt("longest"))
        assertEquals(at(2025, 3, 1, 0) * 1000, st.getLong("longestFrom"))
        assertEquals(2, st.getInt("current"))
        val artists = st.getJSONArray("artists")
        assertEquals("Daily", artists.getJSONObject(0).getString("artist"))
        assertEquals(5, artists.getJSONObject(0).getInt("days"))
        assertEquals("Fan", artists.getJSONObject(1).getString("artist"))
        assertEquals(4, artists.getJSONObject(1).getInt("days"))
        // Three days later the run is over.
        assertEquals(0, Stats(h, utc, at(2025, 3, 12)).streaks(all, 0, h.size).getInt("current"))
    }

    @Test
    fun newArtistsPerMonth() {
        val h = history(
            listOf(
                Play(at(2025, 1, 10), "Old", "a"), Play(at(2025, 3, 2), "Bloom", "b"), Play(at(2025, 3, 5), "Bigger", "c"),
                Play(at(2025, 3, 6), "Bigger", "d"), Play(at(2025, 3, 7), "Old", "e"), Play(at(2025, 6, 1), "Later", "f"),
            ),
        )
        val s = Stats(h, utc, at(2025, 6, 30))
        val n = s.newArtists(Range("y2025", "2025", at(2025, 1, 1, 0), at(2026, 1, 1, 0)))
        assertEquals("month", n.getString("unit"))
        val b = n.getJSONArray("buckets")
        assertEquals("January to June", 6, b.length())
        assertTrue(b.getJSONObject(0).getBoolean("historyStart"))
        assertEquals(2, b.getJSONObject(2).getInt("count"))
        assertEquals("Bigger", b.getJSONObject(2).getJSONArray("top").getString(0))
        assertEquals(0, b.getJSONObject(3).getInt("count"))
        assertEquals(1, b.getJSONObject(5).getInt("count"))
    }

    @Test
    fun genresPreferDiscogsThenLastfmTags() {
        val collection = listOf(item(1, "Fleetwood Mac", "Rumours", listOf("Pop Rock", "Soft Rock")))
        val genres = Genres(collection, mapOf(Genres.artistKey("Khruangbin") to listOf("psychedelic rock", "funk")))
        assertEquals(listOf("pop rock", "soft rock") to Genres.Source.DISCOGS, genres.of("Fleetwood Mac", com.example.recordshelf.util.Text.albumKey("Fleetwood Mac", "Rumours")))
        assertEquals("other albums by an artist you own", Genres.Source.DISCOGS, genres.of("Fleetwood Mac", "fleetwood mac|tusk").second)
        assertEquals(Genres.Source.LASTFM, genres.of("Khruangbin", null).second)
        assertEquals(Genres.Source.NONE, genres.of("Nobody", null).second)
        assertTrue(genres.knowsFromDiscogs("Fleetwood Mac"))
        assertFalse(genres.knowsFromDiscogs("Khruangbin"))
    }

    @Test
    fun genreDriftSharesPerMonth() {
        val collection = listOf(item(1, "Fleetwood Mac", "Rumours", listOf("Pop Rock")))
        val genres = Genres(collection, mapOf(Genres.artistKey("Khruangbin") to listOf("psychedelic rock")))
        val plays = listOf(
            Play(at(2025, 1, 5), "Fleetwood Mac", "Dreams", "Rumours"), Play(at(2025, 1, 6), "Fleetwood Mac", "Dreams", "Rumours"),
            Play(at(2025, 1, 7), "Khruangbin", "Maria", "Con Todo El Mundo"), Play(at(2025, 1, 8), "Nobody", "x"),
            Play(at(2025, 2, 5), "Khruangbin", "Maria", "Con Todo El Mundo"),
        )
        val h = history(plays)
        val g = Stats(h, utc, at(2025, 3, 1), collection, genres).genreDrift(0, h.size)
        assertEquals("month", g.getString("unit"))
        assertEquals(listOf("Pop Rock", "Psychedelic Rock", "Other"), (0 until g.getJSONArray("names").length()).map { g.getJSONArray("names").getString(it) })
        val jan = g.getJSONArray("periods").getJSONObject(0)
        assertEquals(3, jan.getInt("total"))
        assertEquals(0.667, jan.getJSONArray("shares").getDouble(0), 0.001)
        assertEquals(1.0, g.getJSONArray("periods").getJSONObject(1).getJSONArray("shares").getDouble(1), 0.001)
        val cov = g.getJSONObject("coverage")
        assertEquals(0.4, cov.getDouble("discogs"), 0.001)
        assertEquals(0.4, cov.getDouble("lastfm"), 0.001)
        assertEquals(0.2, cov.getDouble("none"), 0.001)
    }

    @Test
    fun lastfmTagsAreCleanedToGenres() {
        val tags = listOf(
            "seen live" to 100, "trip-hop" to 90, "british" to 80, "90s" to 70, "Electronic" to 60, "Portishead" to 50,
            "downtempo" to 40, "female vocalists" to 30, "chillout" to 5,
        )
        assertEquals(listOf("trip hop", "electronic", "downtempo"), Genres.fromTags(tags, "Portishead"))
        assertEquals("Trip Hop", Genres.label("trip hop"))
        assertEquals("trip hop", Genres.key("Trip-Hop"))
    }

    @Test
    fun obsessionIsTheBestWeekOfATrack() {
        val plays = (0 until 8).map { Play(at(2025, 3, 3 + (it % 7), 10 + it), "Loop", "On Repeat") } +
            (0 until 3).map { Play(at(2025, 3, 17 + it), "Loop", "On Repeat") } +
            (0 until 6).map { Play(at(2025, 3, 3 + it), "Almost", "Not Quite") }
        val h = history(plays)
        val o = Stats(h, utc, at(2025, 4, 1)).obsessions(0, h.size)
        assertEquals(1, o.length())
        assertEquals("On Repeat", o.getJSONObject(0).getString("track"))
        assertEquals(8, o.getJSONObject(0).getInt("count"))
        assertEquals("week starts on Monday", at(2025, 3, 3, 0) * 1000, o.getJSONObject(0).getLong("week"))
    }

    @Test
    fun rarelyPlayedAndWishlist() {
        val collection = listOf(item(1, "Fleetwood Mac", "Rumours", listOf("Pop Rock")), item(2, "Joni Mitchell", "Blue", listOf("Folk Rock")))
        val plays = (1..10).map { Play(at(2025, 5, it), "Fleetwood Mac", "Dreams $it", "Rumours (Super Deluxe)") } +
            (1..10).map { Play(at(2025, 6, it), "Beach House", "Song ${it % 5}", "Bloom") } +
            (1..12).map { Play(at(2025, 7, it), "One Hit", "Single", "Single") }
        val h = history(plays)
        val s = Stats(h, utc, at(2025, 8, 1), collection)
        val rare = s.rarelyPlayed(0, h.size)
        val items = rare.getJSONArray("items")
        assertEquals("Blue", items.getJSONObject(0).getString("title"))
        assertEquals(0, items.getJSONObject(0).getInt("plays"))
        assertEquals("Rumours", items.getJSONObject(1).getString("title"))
        assertEquals("matched despite the edition name", 10, items.getJSONObject(1).getInt("plays"))
        assertEquals(1, rare.getInt("neverPlayed"))
        val wish = s.wishlist(0, h.size)
        assertEquals("owned and single-track albums are left out", 1, wish.length())
        assertEquals("Bloom", wish.getJSONObject(0).getString("album"))
        assertEquals(5, wish.getJSONObject(0).getInt("tracks"))
        assertTrue(wish.getJSONObject(0).getString("search").startsWith("https://www.discogs.com/search/"))
    }

    @Test
    fun featuresCountForTheMainArtist() {
        val solo = setOf("fontaines d c", "macklemore")
        assertEquals("Fontaines D.C.", com.example.recordshelf.history.Artists.main("Fontaines D.C., ROSALÍA", solo))
        assertEquals("Earth, Wind & Fire", com.example.recordshelf.history.Artists.main("Earth, Wind & Fire", solo))
        assertEquals("Simon & Garfunkel", com.example.recordshelf.history.Artists.main("Simon & Garfunkel", solo))
        assertEquals("Macklemore", com.example.recordshelf.history.Artists.main("Macklemore & Ryan Lewis", solo))
        assertEquals("Gorillaz", com.example.recordshelf.history.Artists.main("Gorillaz feat. Bad Bunny", solo))
        assertEquals("Gorillaz", com.example.recordshelf.history.Artists.main("Gorillaz (feat. Bad Bunny)", solo))
        val store = com.example.recordshelf.history.MemoryStore()
        store.insert(
            listOf(
                com.example.recordshelf.lastfm.RecentTrack("Fontaines D.C.", "Starburster", "Romance", 100),
                com.example.recordshelf.lastfm.RecentTrack("Fontaines D.C., ROSALÍA", "Song", "", 200),
            ),
        )
        val h = History.from(store)
        assertEquals(1, h.artistNames.size)
    }

    @Test
    fun recordPhasesAndLoops() {
        val plays = (1..5).map { Play(at(2025, 3, 3, 21, it * 4), "IDLES", "Grace") } + // five in a row
            listOf(Play(at(2025, 3, 4, 8), "Shame", "Alphabet"), Play(at(2025, 3, 20, 9, 30), "Shame", "Alphabet"))
        val h = history(plays)
        val s = Stats(h, utc, at(2025, 3, 31))
        val r = Range("x", "x", at(2025, 3, 3, 0), at(2025, 4, 1, 0))
        val rec = s.record(r, 0, h.size)
        assertEquals(29, rec.getInt("days"))
        assertEquals("IDLES", rec.getJSONArray("artists").getString(0))
        assertEquals(0, rec.getJSONArray("d").getInt(0))
        assertEquals(21 * 60 + 4, rec.getJSONArray("m").getInt(0))
        assertEquals(17, rec.getJSONArray("d").getInt(6))
        assertEquals("Grace", rec.getJSONArray("tracks").getString(rec.getJSONArray("t").getInt(0)))
        val p = s.pulse(0, h.size)
        val rows = p.getJSONArray("rows")
        assertEquals("ordered by peak week", "IDLES", rows.getJSONObject(0).getString("artist"))
        assertEquals(5, rows.getJSONObject(0).getJSONArray("counts").getInt(0))
        assertEquals(at(2025, 3, 3, 0) * 1000, p.getLong("start"))
        val loops = s.loops(0, h.size)
        assertEquals(1, loops.length())
        assertEquals(5, loops.getJSONObject(0).getInt("run"))
        val a = s.artist("shame", s.range("all"))!!
        assertEquals(2, a.getInt("plays"))
        assertEquals("Alphabet", a.getJSONObject("first").getString("track"))
        assertEquals(1, a.getJSONArray("hours").getInt(8))
        assertEquals(2, a.getJSONObject("range").getInt("rank"))
        assertEquals(null, s.artist("Nobody", s.range("all")))
    }

    @Test
    fun rangesAndYearInReview() {
        val collection = listOf(item(1, "Fleetwood Mac", "Rumours", listOf("Pop Rock")))
        val plays = listOf(Play(at(2024, 12, 30), "Air", "Talisman", "Moon Safari")) +
            (1..6).map { Play(at(2025, 2, 14, 10 + it), "Fleetwood Mac", "Dreams", "Rumours") } +
            (1..3).map { Play(at(2025, 4, it), "Khruangbin", "Maria", "Con Todo El Mundo") }
        val h = history(plays)
        val vinyl = listOf(Listen("Fleetwood Mac", "Dreams", at(2025, 2, 14, 11), "Rumours"), Listen("Fleetwood Mac", "Dreams", at(2025, 2, 14, 12), "Rumours"))
        val s = Stats(h, utc, at(2025, 10, 25), collection, vinyl = vinyl)
        assertEquals(listOf("12m", "all", "y2025", "y2024"), s.ranges().map { it.id })
        val r: JSONObject = s.review(s.range("y2025"))
        assertEquals(9, r.getJSONObject("summary").getInt("scrobbles"))
        assertEquals("Fleetwood Mac", r.getJSONObject("top").getJSONArray("artists").getJSONObject(0).getString("name"))
        assertEquals(at(2025, 2, 14, 0) * 1000, r.getJSONObject("busiestDay").getLong("day"))
        assertEquals(6, r.getJSONObject("busiestDay").getInt("count"))
        assertEquals(2, r.getInt("newArtists"))
        assertFalse(r.getBoolean("historyStartsHere"))
        assertEquals(10, r.getJSONArray("months").length())
        assertEquals(6, r.getJSONArray("months").getInt(1))
        val v = r.getJSONObject("vinyl")
        assertEquals(2, v.getInt("count"))
        assertEquals("cover1", v.getJSONArray("top").getJSONObject(0).getString("cover"))
        assertEquals("Dreams", r.getJSONObject("first").getString("track"))
        assertTrue(s.review(s.range("y2024")).getBoolean("historyStartsHere"))
    }
}
