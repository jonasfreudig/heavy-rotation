package app.recordshelf

import app.recordshelf.history.Genres
import app.recordshelf.history.History
import app.recordshelf.history.Moments
import app.recordshelf.history.Photo
import app.recordshelf.history.Range
import app.recordshelf.history.Stats
import app.recordshelf.net.SetlistfmClient
import app.recordshelf.net.Setlists
import app.recordshelf.util.Text
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Setlists from setlist.fm, photos for the story, album genres and the pages' period. */
class ConcertsAndMomentsTest {
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
    fun momentsArePhotosOfTheBestTimesSpreadOut() {
        val day = 86_400L
        val t0 = at(2025, 6, 1)
        // A burst of photos at a gig with music playing, a lone photo in silence, two days with one photo each.
        val gig = (0 until 8).map { Photo(100L + it, t0 + 3 * day + it * 120, 4000, 3000) }
        val quiet = Photo(200, t0 + 10 * day, 3000, 4000)
        val walk = Photo(300, t0 + 20 * day, 3000, 4000)
        val beach = Photo(400, t0 + 20 * day + 3600, 3000, 4000)
        val playing = { t: Long -> t in (t0 + 3 * day + 300)..(t0 + 3 * day + 700) || t == walk.ts }
        val near = { t: Long -> playing(t) || t == beach.ts }
        val picked = Moments.pick(gig + quiet + walk + beach, 30 * day, playing, near, max = 3)
        assertEquals(3, picked.size)
        assertTrue("the gig's photo is one taken while a song played", picked[0].id in 103L..105L)
        assertTrue("walk and beach are an hour apart: only the one with music", walk in picked && beach !in picked)
        assertEquals("in the order they were taken", picked.sortedBy { it.ts }, picked)
        assertEquals(emptyList<Photo>(), Moments.pick(emptyList(), day, { false }, { false }))
    }

    @Test
    fun aPhotoKnowsWhatWasPlaying() {
        val h = history(
            Triple(at(2025, 6, 14, 18, 30), "Ezra Collective", "Ajala" to "Where I'm Meant To Be"),
            Triple(at(2025, 6, 14, 18, 36), "Ezra Collective", "Victory Dance" to "Where I'm Meant To Be"),
        )
        val s = Stats(h, utc, at(2025, 7, 1))
        val during = s.playAt(at(2025, 6, 14, 18, 33))!!
        assertEquals("Ajala", during.getString("title"))
        assertTrue(during.getBoolean("playing"))
        val after = s.playAt(at(2025, 6, 14, 19, 0))!!
        assertEquals("Victory Dance", after.getString("title"))
        assertFalse("long over, but around then", after.getBoolean("playing"))
        assertNull(s.playAt(at(2025, 6, 15, 12, 0)))
        val r = Range("2025-06", "June 2025", at(2025, 6, 1, 0), at(2025, 7, 1, 0), "month")
        val m = s.moments(listOf(Photo(7, at(2025, 6, 14, 18, 33), 4000, 3000)), r)
        assertEquals(1, m.length())
        assertEquals("Ajala", m.getJSONObject(0).getJSONObject("song").getString("title"))
        assertEquals(at(2025, 6, 14, 18, 33) * 1000, m.getJSONObject(0).getLong("ts"))
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
