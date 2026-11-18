package app.recordshelf

import app.recordshelf.history.Genres
import app.recordshelf.history.History
import app.recordshelf.history.Range
import app.recordshelf.history.Stats
import app.recordshelf.net.SetlistfmClient
import app.recordshelf.net.Setlists
import app.recordshelf.util.Text
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Setlists from setlist.fm, album genres and the pages' period. */
class ConcertsAndPagesTest {
    private val utc = ZoneId.of("UTC")
    private fun at(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0) = LocalDateTime.of(y, m, d, h, min).toEpochSecond(ZoneOffset.UTC)
    private fun history(vararg plays: Triple<Long, String, Pair<String, String>>) =
        History.Builder().also { b -> plays.forEach { (ts, artist, t) -> b.add(ts, artist, t.first, t.second) } }.build()

    /** A page as setlist.fm sends it: sets wrapped in "sets", an intro off a tape, a cover, an encore, a show to come. */
    private val page = """
        {"type":"setlists","itemsPerPage":20,"page":1,"total":3,"setlist":[
          {"id":"a3","eventDate":"14-11-2026","artist":{"mbid":"m1","name":"IDLES"},
           "venue":{"name":"Columbiahalle","city":{"name":"Berlin","country":{"code":"DE","name":"Germany"}}},"sets":{"set":[]},"url":"https://www.setlist.fm/a3"},
          {"id":"a2","eventDate":"02-10-2026","artist":{"mbid":"m1","name":"IDLES"},"tour":{"name":"TANGK Tour"},
           "venue":{"name":"Olympia","city":{"name":"Paris","country":{"code":"FR","name":"France"}}},
           "sets":{"set":[{"song":[{"name":"","tape":true},{"name":"IDEA 01"},{"name":"Colossus"},{"name":"Mr. Motivator"},{"name":"Gift Horse"},
             {"name":"Love Will Tear Us Apart","cover":{"name":"Joy Division"}},{"name":"Never Fight a Man With a Perm"}]},
             {"encore":1,"song":[{"name":"Rottweiler"}]}]},"url":"https://www.setlist.fm/a2"},
          {"id":"a1","eventDate":"30-09-2026","artist":{"mbid":"m1","name":"IDLES"},
           "venue":{"name":"Paradiso","city":{"name":"Amsterdam","country":{"code":"NL","name":"Netherlands"}}},
           "sets":{"set":[{"song":[{"name":"Colossus"},{"name":"IDEA 01"},{"name":"Mr. Motivator"},{"name":"Grace"},{"name":"Gift Horse"},{"name":"Danny Nedelko"}]},
             {"encore":1,"song":[{"name":"Rottweiler","with":{"name":"Jehnny Beth"}}]}]},"url":"https://www.setlist.fm/a1"}
        ]}
    """.trimIndent()

    @Test
    fun allShowsToComeAreReadBeforeThePastBegins() {
        val today = LocalDate.of(2026, 11, 18)
        fun show(id: Int, date: LocalDate, songs: Int) = app.recordshelf.net.Show(
            "s$id", date, "IDLES", "Hall", "City", "Country", "", "",
            List(songs) { app.recordshelf.net.SetSong("Song $it", "", "", false, false, "") },
        )
        // 45 shows to come (the farthest first, as setlist.fm sends them), then 60 past ones with setlists.
        val tour = (45 downTo 1).map { show(it, today.plusDays(it.toLong()), 0) } + (0 until 60).map { show(100 + it, today.minusDays(it.toLong()), 8) }
        val asked = ArrayList<Int>()
        fun pages(list: List<app.recordshelf.net.Show>) = { p: Int -> asked += p; list.drop((p - 1) * Setlists.PAGE).take(Setlists.PAGE) }
        val got = Setlists.gather(today, page = pages(tour))
        assertEquals("the first two pages are all to come; the third brings the last five and the past", listOf(1, 2, 3), asked)
        assertEquals("none of the next shows is missing", 45, got.count { it.date.isAfter(today) })
        assertEquals(today.plusDays(1), got.filter { it.date.isAfter(today) }.minOf { it.date })

        // The usual case: the past begins on the first page with enough setlists, and one page is all that is read.
        asked.clear()
        val usual = (2 downTo 1).map { show(it, today.plusDays(it.toLong()), 0) } + (0 until 40).map { show(100 + it, today.minusDays(it.toLong()), 8) }
        assertEquals(20, Setlists.gather(today, page = pages(usual)).size)
        assertEquals(listOf(1), asked)

        // Few recent setlists on the page where the past begins: one more page, and no more than that.
        asked.clear()
        val bare = (0 until 100).map { show(100 + it, today.minusDays(it.toLong()), 0) }
        assertEquals(40, Setlists.gather(today, page = pages(bare)).size)
        assertEquals(listOf(1, 2), asked)

        // A tour longer than the pages read: it stops at the limit; a short page ends the reading at once.
        asked.clear()
        val endless = (400 downTo 1).map { show(it, today.plusDays(it.toLong()), 0) }
        assertEquals(Setlists.PAGES_MAX * Setlists.PAGE, Setlists.gather(today, page = pages(endless)).size)
        asked.clear()
        assertEquals(3, Setlists.gather(today, page = pages(endless.take(3))).size)
        assertEquals(listOf(1), asked)
    }

    @Test
    fun setlistsAreReadAndTheirUsualSetFound() {
        val shows = SetlistfmClient.parse(JSONObject(page))
        assertEquals(3, shows.size)
        val paris = shows[1]
        assertEquals(LocalDate.of(2026, 10, 2), paris.date)
        assertEquals("Olympia", paris.venue)
        assertEquals("Paris", paris.city)
        assertEquals("TANGK Tour", paris.tour)
        assertEquals("the intro off a tape has no name and is left out", 7, paris.songs.size)
        assertEquals("Joy Division", paris.songs.first { it.title == "Love Will Tear Us Apart" }.cover)
        assertTrue(paris.songs.last().encore)
        assertEquals("Jehnny Beth", shows[2].songs.last().with)

        val lately = Setlists.lately(shows, LocalDate.of(2026, 10, 29))
        assertEquals("the show to come (no setlist yet) tells nothing", listOf("a2", "a1"), lately.map { it.id })
        val usual = Setlists.typical(lately)
        assertEquals("played at both, in their usual order, the encore last",
            listOf("IDEA 01", "Colossus", "Mr. Motivator", "Gift Horse", "Rottweiler"), usual.map { it.title })
        assertTrue(usual.last().encore)
        assertEquals(2, usual.first().shows)
        assertEquals("one show alone: all of it", 7, Setlists.typical(lately.take(1)).size)
        assertEquals(emptyList<Any>(), Setlists.typical(emptyList()))
    }

    @Test
    fun albumsGetTheirOwnGenres() {
        val tags = mapOf(
            Genres.artistKey("Radiohead") to Genres.fromTags(listOf("alternative" to 100, "rock" to 80, "alternative rock" to 60), "Radiohead"),
            Text.albumKey("Radiohead", "Kid A") to Genres.fromTags(listOf("electronic" to 100, "Kid A" to 70, "experimental" to 60, "albums i own" to 40), "Radiohead", "Kid A"),
        )
        assertFalse("the album's own title is no genre", "70:kid a" in tags.getValue(Text.albumKey("Radiohead", "Kid A")))
        val g = Genres(emptyList(), tags)
        assertEquals("electronic", g.detail("Radiohead", Text.albumKey("Radiohead", "Kid A"))!!.family)
        assertEquals(Genres.Source.LASTFM, g.detail("Radiohead", Text.albumKey("Radiohead", "Kid A"))!!.source)
        assertEquals("their other albums stay theirs", "indie", g.detail("Radiohead", Text.albumKey("Radiohead", "OK Computer"))!!.family)
        assertEquals("the artist alone", "indie", g.of("Radiohead", null).first.first())
        assertTrue(Genres.isAlbumKey(Text.albumKey("Radiohead", "Kid A")))
        assertFalse(Genres.isAlbumKey(Genres.artistKey("Radiohead")))
    }

    @Test
    fun albumsNeedingTagsAreTheMostPlayed() {
        val plays = (1..6).map { Triple(at(2025, 1, it), "A", "x$it" to "Big") } + Triple(at(2025, 2, 1), "A", "y" to "Small") +
            Triple(at(2025, 2, 2), "Various Artists", "z" to "Mix") + Triple(at(2025, 2, 3), "B", "w" to "")
        val s = Stats(history(*plays.toTypedArray()), utc, at(2025, 3, 1))
        val need = s.albumsNeedingTags(emptySet(), coverage = 1.0)
        assertEquals("no compilations, no songs without an album", listOf("Big", "Small"), need.map { it.second })
        assertEquals(Text.albumKey("A", "Big"), need.first().third)
        assertEquals(listOf("Small"), s.albumsNeedingTags(setOf(Text.albumKey("A", "Big")), coverage = 1.0).map { it.second })
    }

    @Test
    fun pagesTellThePeriodApart() {
        val h = history(
            Triple(at(2025, 5, 2), "A", "Old" to "First"), Triple(at(2025, 5, 3), "A", "Old" to "First"),
            Triple(at(2025, 6, 2), "A", "New" to "Second"), Triple(at(2025, 6, 3), "A", "New" to "Second"), Triple(at(2025, 6, 4), "A", "Old" to "First"),
        )
        val s = Stats(h, utc, at(2025, 7, 1))
        val june = Range("2025-06", "June 2025", at(2025, 6, 1, 0), at(2025, 7, 1, 0), "month")
        val a = s.artist("A", june)!!
        assertEquals("all time on the page", 5, a.getInt("plays"))
        assertEquals("New", a.getJSONArray("rangeTracks").getJSONObject(0).getString("title"))
        assertEquals(2, a.getJSONArray("rangeTracks").getJSONObject(0).getInt("plays"))
        assertEquals(listOf("Second", "First"), (0 until a.getJSONArray("rangeAlbums").length()).map { a.getJSONArray("rangeAlbums").getJSONObject(it).getString("title") })
        val al = s.album("A", "First", june)!!
        assertEquals(3, al.getInt("plays"))
        assertEquals(1, al.getJSONArray("rangeTracks").getJSONObject(0).getInt("plays"))
        assertEquals(mapOf("old" to 3, "new" to 2), s.songPlays("A"))
    }
}
