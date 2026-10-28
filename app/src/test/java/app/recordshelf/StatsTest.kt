package app.recordshelf

import app.recordshelf.discogs.CollectionItem
import app.recordshelf.history.Genres
import app.recordshelf.history.History
import app.recordshelf.history.Range
import app.recordshelf.history.Stats
import app.recordshelf.scrobble.Listen
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        // In a week that started mid-run, the current streak still counts the days before it.
        val run = history((1..12).map { Play(at(2025, 3, it, 9), "Daily", "t$it") })
        val s2 = Stats(run, utc, at(2025, 3, 12, 20))
        val week = s2.range("w2025-03-10")
        val ws = s2.streaks(week, run.lowerBound(week.from), run.lowerBound(week.to))
        assertEquals(12, ws.getInt("current"))
        assertEquals(at(2025, 3, 1, 0) * 1000, ws.getLong("currentFrom"))
        assertEquals(3, ws.getInt("longest"))
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
        val r = s.range("y2025")
        val n = s.newArtists(r, h.lowerBound(r.from), h.lowerBound(r.to))
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
        assertEquals("both styles are rock", listOf("rock") to Genres.Source.DISCOGS, genres.of("Fleetwood Mac", app.recordshelf.util.Text.albumKey("Fleetwood Mac", "Rumours")))
        assertEquals(listOf("rock", "soul"), genres.of("Khruangbin", null).first)
        assertEquals("other albums by an artist you own", Genres.Source.DISCOGS, genres.of("Fleetwood Mac", "fleetwood mac|tusk").second)
        assertEquals(Genres.Source.LASTFM, genres.of("Khruangbin", null).second)
        assertEquals(Genres.Source.NONE, genres.of("Nobody", null).second)
        assertTrue(genres.knowsFromDiscogs("Fleetwood Mac"))
        assertFalse(genres.knowsFromDiscogs("Khruangbin"))
    }

    @Test
    fun genreDriftSharesPerMonth() {
        val collection = listOf(item(1, "Fleetwood Mac", "Rumours", listOf("Pop Rock")))
        val genres = Genres(collection, mapOf(Genres.artistKey("Khruangbin") to listOf("thai funk")))
        val plays = listOf(
            Play(at(2025, 1, 5), "Fleetwood Mac", "Dreams", "Rumours"), Play(at(2025, 1, 6), "Fleetwood Mac", "Dreams", "Rumours"),
            Play(at(2025, 1, 7), "Khruangbin", "Maria", "Con Todo El Mundo"), Play(at(2025, 1, 8), "Nobody", "x"),
            Play(at(2025, 2, 5), "Khruangbin", "Maria", "Con Todo El Mundo"),
        )
        val h = history(plays)
        val s = Stats(h, utc, at(2025, 3, 1), collection, genres)
        val g = s.genreDrift(s.range("y2025"), 0, h.size)
        assertEquals("month", g.getString("unit"))
        assertEquals(listOf("Rock", "Soul & Funk", "Other"), (0 until g.getJSONArray("names").length()).map { g.getJSONArray("names").getString(it) })
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
    fun stylesAndTagsShareOneSetOfGenres() {
        val cases = mapOf(
            "Indie Rock" to "indie", "indie" to "indie", "Garage Rock Revival" to "indie", "alternative" to "indie",
            "Post-Punk" to "punk", "post-punk revival" to "punk", "electropunk" to "punk", "dance-punk" to "punk",
            "Hip Hop" to "hiphop", "Deutschrap" to "hiphop", "trip-hop" to "electronic", "Synth-pop" to "synth",
            "Deep House" to "dance", "Funk / Soul" to "soul", "thai funk" to "soul", "Folk, World, & Country" to "folk",
            "Stage & Screen" to "soundtrack", "Psychedelic Rock" to "rock", "dance pop" to "pop", "Black Metal" to "metal",
            "Contemporary R&B" to "rnb", "Bossa Nova" to "latin", "Afrobeat" to "global", "Ambient" to "ambient",
        )
        cases.forEach { (name, family) -> assertEquals(name, family, Genres.family(name)) }
        assertEquals(null, Genres.family("Spoken Word"))
        assertEquals("Punk & Post-Punk", Genres.label("punk"))
        assertEquals("a chip's name finds its family", "indie", Genres.resolve("Indie & Alternative"))
        assertEquals("a record's own style finds its family", "punk", Genres.resolve("post punk"))
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
        assertEquals("Fontaines D.C.", app.recordshelf.history.Artists.main("Fontaines D.C., ROSALÍA", solo))
        assertEquals("Earth, Wind & Fire", app.recordshelf.history.Artists.main("Earth, Wind & Fire", solo))
        assertEquals("Simon & Garfunkel", app.recordshelf.history.Artists.main("Simon & Garfunkel", solo))
        assertEquals("Macklemore", app.recordshelf.history.Artists.main("Macklemore & Ryan Lewis", solo))
        assertEquals("Gorillaz", app.recordshelf.history.Artists.main("Gorillaz feat. Bad Bunny", solo))
        assertEquals("Gorillaz", app.recordshelf.history.Artists.main("Gorillaz (feat. Bad Bunny)", solo))
        val fela = setOf("fela kuti")
        assertEquals("Fela Kuti", app.recordshelf.history.Artists.main("Fela Kuti and Africa 70", fela))
        assertEquals("Fela Kuti", app.recordshelf.history.Artists.main("Fela Kuti With Ginger Baker", fela))
        assertEquals("Fela Kuti", app.recordshelf.history.Artists.main("Fela Kuti x Roy Ayers", fela))
        assertEquals("Simon and Garfunkel", app.recordshelf.history.Artists.main("Simon and Garfunkel", fela))
        val store = app.recordshelf.history.MemoryStore()
        store.insert(
            listOf(
                app.recordshelf.lastfm.RecentTrack("Fontaines D.C.", "Starburster", "Romance", 100),
                app.recordshelf.lastfm.RecentTrack("Fontaines D.C., ROSALÍA", "Song", "", 200),
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
        val p = s.pulse(r, 0, h.size)
        val rows = p.getJSONArray("rows")
        assertEquals("ordered by peak", "IDLES", rows.getJSONObject(0).getString("artist"))
        assertEquals("a month in days", "day", p.getString("unit"))
        assertEquals(5, rows.getJSONObject(0).getJSONArray("counts").getInt(0))
        assertEquals(at(2025, 3, 3, 0) * 1000, p.getJSONArray("starts").getLong(0))
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
    fun periodsStepAndCompare() {
        val plays = listOf(Play(at(2024, 12, 30), "Air", "Talisman")) +
            (1..4).map { Play(at(2025, 9, it), "A", "s$it") } + (1..10).map { Play(at(2025, 10, it), "A", "t$it") }
        val h = history(plays)
        val s = Stats(h, utc, at(2025, 10, 25))
        val m = s.range("m2025-10")
        assertEquals("month", m.kind)
        assertEquals(at(2025, 10, 1, 0), m.from)
        assertEquals("the running month ends now", at(2025, 10, 25) + 1, m.to)
        assertEquals("m2025-09", s.neighbor(m, -1)!!.id)
        assertNull("nothing after now", s.neighbor(m, 1))
        assertEquals("w2025-10-20", s.range("w2025-10-22").id)
        assertEquals(at(2025, 10, 20, 0), s.range("w2025-10-22").from)
        assertEquals("y2024", s.neighbor(s.range("y2025"), -1)!!.id)
        assertNull("nothing before the history", s.neighbor(s.range("y2024"), -1))
        assertEquals("unknown ids mean this month", "m2025-10", s.range("nonsense").id)
        val p = s.previous(m)!!
        assertEquals("compared with the same stretch of September", at(2025, 9, 1, 0) + (m.to - m.from), p.to)
        val c = s.compute(m)
        assertEquals(10, c.getJSONObject("summary").getInt("scrobbles"))
        assertEquals(4, c.getJSONObject("summary").getJSONObject("prev").getInt("scrobbles"))
        assertEquals("10 plays of about 3.5 minutes", 1L, c.getJSONObject("summary").getLong("hours"))
        val tl = c.getJSONObject("timeline")
        assertEquals("day", tl.getString("unit"))
        assertEquals("October 1 to today", 25, tl.getJSONArray("counts").length())
        assertEquals(1, tl.getJSONArray("counts").getInt(0))
        assertEquals("m2025-09", c.getJSONObject("range").getString("prev"))
        assertTrue(c.getJSONObject("range").isNull("next"))
        assertEquals("a year in weeks", "week", s.compute(s.range("y2025")).getJSONObject("timeline").getString("unit"))
    }

    @Test
    fun riverUsesTheRecordsOrder() {
        val plays = (1..6).map { Play(at(2025, 10, it), "Top", "a") } + (1..3).map { Play(at(2025, 10, 10 + it), "Second", "b") } +
            listOf(Play(at(2025, 10, 20), "Third", "c"))
        val h = history(plays)
        val s = Stats(h, utc, at(2025, 10, 31))
        val m = s.range("m2025-10")
        val river = s.river(m, 0, h.size)
        val names = river.getJSONArray("artists")
        assertEquals(listOf("Top", "Second", "Third"), (0 until names.length()).map { names.getString(it) })
        assertEquals("day", river.getString("unit"))
        assertEquals(1, river.getJSONArray("series").getJSONArray(0).getInt(0))
        assertEquals(1, river.getJSONArray("series").getJSONArray(1).getInt(10))
        val rec = s.record(m, 0, h.size).getJSONArray("artists")
        assertEquals("Top", rec.getString(0))
    }

    @Test
    fun peakHoursHoldTheGoldenHour() {
        // 09:00 is the busiest single hour; 21:00 and 22:00 together beat 09:00 and 10:00, but the peak follows the golden hour.
        val plays = (1..8).map { Play(at(2025, 10, it, 9), "A", "a$it") } + listOf(Play(at(2025, 10, 9, 10), "A", "b")) +
            (1..6).map { Play(at(2025, 10, it, 21), "B", "c$it") } + (1..6).map { Play(at(2025, 10, it, 22), "B", "d$it") }
        val h = history(plays)
        val s = Stats(h, utc, at(2025, 10, 31, 23))
        val m = s.range("m2025-10")
        val ins = s.insights(m, 0, h.size, null)
        val peak = (0 until ins.length()).map { ins.getJSONObject(it) }.first { it.getString("kind") == "peak" }
        assertEquals(9, peak.getInt("hour"))
        assertEquals(9, s.story(m).getInt("goldenHour"))
    }

    @Test
    fun insightsAndPersonality() {
        // September: 10 afternoon plays of A. October: 40 night plays, B and D new, C back after months.
        val plays = listOf(Play(at(2025, 1, 5), "C", "old")) +
            (1..10).map { Play(at(2025, 9, it, 15), "A", "a$it") } +
            (1..14).map { Play(at(2025, 10, it, 23, 10), "A", "night") } +
            (1..12).map { Play(at(2025, 10, it, 1, 20), "B", "new") } +
            (1..8).map { Play(at(2025, 10, it, 22, 30), "C", "back") } +
            (1..6).map { Play(at(2025, 10, it, 2, 40), "D", "late") }
        val h = history(plays)
        val s = Stats(h, utc, at(2025, 10, 31, 23))
        val m = s.range("m2025-10")
        val i0 = h.lowerBound(m.from)
        val i1 = h.lowerBound(m.to)
        val ins = s.insights(m, i0, i1, s.previous(m))
        val kinds = (0 until ins.length()).associate { ins.getJSONObject(it).getString("kind") to ins.getJSONObject(it) }
        assertEquals(300L, kinds["volume"]!!.getLong("pct"))
        assertEquals("A", kinds["topArtist"]!!.getString("artist"))
        assertTrue("A was on top in September too", kinds.containsKey("stillTop"))
        assertEquals(2, kinds["discovery"]!!.getInt("count"))
        assertEquals("B", kinds["discovery"]!!.getString("best"))
        assertEquals("C", kinds["comeback"]!!.getString("artist"))
        assertEquals(1.0, kinds["night"]!!.getDouble("share"), 0.001)
        val p = s.personality(m, i0, i1)
        assertEquals("nightOwl", p.getString("id"))
        assertEquals(1.0, p.getJSONObject("metrics").getDouble("night"), 0.001)
    }

    @Test
    fun storyOfAPeriod() {
        val collection = listOf(item(1, "Fleetwood Mac", "Rumours", listOf("Pop Rock")))
        val plays = listOf(Play(at(2024, 12, 30), "Air", "Talisman", "Moon Safari")) +
            (1..6).map { Play(at(2025, 2, 14, 10 + it), "Fleetwood Mac", "Dreams", "Rumours") } +
            (1..3).map { Play(at(2025, 4, it), "Khruangbin", "Maria", "Con Todo El Mundo") }
        val h = history(plays)
        val vinyl = listOf(Listen("Fleetwood Mac", "Dreams", at(2025, 2, 14, 11), "Rumours"), Listen("Fleetwood Mac", "Dreams", at(2025, 2, 14, 12), "Rumours"))
        val s = Stats(h, utc, at(2025, 10, 25), collection, vinyl = vinyl)
        val st: JSONObject = s.story(s.range("y2025"))
        assertEquals(9, st.getJSONObject("summary").getInt("scrobbles"))
        val top = st.getJSONArray("artists").getJSONObject(0)
        assertEquals("Fleetwood Mac", top.getString("name"))
        assertEquals("the cover of their album on your shelf", "cover1", top.getString("cover"))
        assertEquals(1, top.getInt("days"))
        assertEquals("Dreams", st.getJSONArray("tracks").getJSONObject(0).getString("title"))
        assertEquals(6, st.getJSONObject("songBestDay").getInt("count"))
        assertEquals(at(2025, 2, 14, 0) * 1000, st.getJSONObject("busiestDay").getLong("day"))
        assertEquals(2, st.getJSONObject("newArtists").getInt("count"))
        assertEquals(2, st.getJSONObject("vinyl").getInt("count"))
        assertEquals("cover1", st.getJSONObject("vinyl").getJSONArray("top").getJSONObject(0).getString("cover"))
        assertEquals("Dreams", st.getJSONObject("first").getString("track"))
        assertEquals("noon has the most plays", 12, st.getInt("goldenHour"))
        assertTrue(s.story(s.range("y2024")).getJSONObject("summary").getBoolean("historyStartsHere"))
    }
}
