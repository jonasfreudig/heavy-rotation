package app.recordshelf

import app.recordshelf.history.Csv
import app.recordshelf.history.ImportedPlay
import app.recordshelf.history.Overlap
import app.recordshelf.history.PlaySource
import app.recordshelf.history.TidalImport
import app.recordshelf.history.mergePlays
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.StringReader
import java.time.Instant

/** TIDAL's own export of your listening, read into plays, and which of them Last.fm has already. */
class TidalImportTest {
    private val head = "artist_name,track_title,entry_date,stream_duration_ms,product_type,ip_address,client_name_from_session,country_name,region_name,city_name,postal_code,time_zone,album_id,album_title"
    private fun row(artist: String, title: String, at: String, ms: Int, album: String = "Album", type: String = "TRACK") =
        "$artist,$title,$at,$ms.0,$type,203.0.113.7,null,Nowhere,Region,City,00000,Europe/Berlin,1,$album"

    private fun file(vararg rows: String) = (listOf(head) + rows).joinToString("\n") + "\n"
    private fun sec(iso: String) = Instant.parse(iso).epochSecond

    private fun read(text: String, known: (String, String) -> Int = { _, _ -> 0 }): Pair<List<ImportedPlay>, TidalImport.Summary> {
        val out = ArrayList<ImportedPlay>()
        val s = TidalImport.plays(StringReader(text), TidalImport.lengths(StringReader(text)), known) { out += it }
        return out to s
    }

    @Test
    fun readsQuotedFieldsWithCommasQuotesAndLineBreaks() {
        val csv = Csv(StringReader("a,\"Tyler, The Creator feat. Kali Uchis\",\"say \"\"hi\"\"\"\r\nx,\"two\nlines\",\nlast"))
        assertEquals(listOf("a", "Tyler, The Creator feat. Kali Uchis", "say \"hi\""), csv.next())
        assertEquals(listOf("x", "two\nlines", ""), csv.next())
        assertEquals(listOf("last"), csv.next())
        assertNull(csv.next())
    }

    @Test
    fun aPlayBeginsWhenItsStreamDidAndCountsByLastFmsRule() {
        val (plays, s) = read(
            file(
                // Reported at its end: it began 181 seconds earlier.
                row("\"Tyler, The Creator feat. Kali Uchis\"", "See You Again", "2017-09-01T13:53:09.922Z", 181000, "Flower Boy"),
                row("Beatsteaks", "Break Down", "2017-09-01T14:32:54.728Z", 148000, "YOURS"),
                // Skipped after 20 seconds; and under half of a song that usually runs 300.
                row("Beatsteaks", "40 Degrees", "2017-09-01T14:33:20.000Z", 20000),
                row("Band", "Long Song", "2017-09-02T10:05:00.000Z", 300000),
                row("Band", "Long Song", "2017-09-02T11:05:00.000Z", 300000),
                row("Band", "Long Song", "2017-09-02T12:02:00.000Z", 120000),
                // A video is no scrobble.
                row("Band", "The Video", "2017-09-02T13:00:00.000Z", 200000, type = "VIDEO"),
                // No album in the export.
                row("Band", "Loose One", "2017-09-02T14:00:00.000Z", 100000, album = "null"),
            ),
        )
        assertEquals(listOf("See You Again", "Break Down", "Long Song", "Long Song", "Loose One"), plays.map { it.track })
        assertEquals("Tyler, The Creator feat. Kali Uchis", plays[0].artist)
        assertEquals(sec("2017-09-01T13:50:08Z"), plays[0].ts)
        assertEquals("Flower Boy", plays[0].album)
        assertEquals("", plays.last().album)
        assertTrue(plays.none { it.est })
        assertEquals(8, s.rows)
        assertEquals(5, s.plays)
        assertEquals(2, s.short)
        assertEquals(1, s.videos)
        assertEquals(sec("2017-09-01T13:50:08Z"), s.first)
    }

    @Test
    fun aSongThisPhoneKnowsTheLengthOfGoesByThat() {
        // Played once, for 100 seconds: half of nothing known. The phone knows it runs 400.
        val text = file(row("Band", "Epic", "2020-01-01T10:00:00.000Z", 100000))
        assertEquals(1, read(text).first.size)
        assertEquals(0, read(text) { _, t -> if (t == "Epic") 400 else 0 }.first.size)
    }

    @Test
    fun streamsReportedTogetherAreLaidEndToEndBeforeThatMoment() {
        // Three songs heard offline and reported in one go: all carry the same time.
        val (plays, s) = read(
            file(
                row("Fugees", "Red Intro", "2017-09-01T18:53:09.520Z", 111000),
                row("Fugees", "How Many Mics", "2017-09-01T18:53:09.532Z", 201000),
                row("Fugees", "Ready or Not", "2017-09-01T18:53:09.542Z", 227000),
                row("Fugees", "Zealots", "2017-09-01T18:57:30.000Z", 250000),
            ),
        )
        val end = sec("2017-09-01T18:53:09Z")
        assertEquals(listOf(end - 227 - 201 - 111, end - 227 - 201, end - 227), plays.take(3).map { it.ts })
        // The last of them is where its own time says; the ones before it are worked out.
        assertEquals(listOf(true, true, false, false), plays.map { it.est })
        assertEquals(2, s.estimated)
        assertEquals(sec("2017-09-01T18:53:20Z"), plays[3].ts)
    }

    @Test
    fun aRowWrittenTwiceIsOnePlayAndABrokenOneIsSkipped() {
        val (plays, s) = read(
            file(
                row("Shame", "Concrete", "2019-05-01T10:00:00.000Z", 200000),
                row("Shame", "Concrete", "2019-05-01T10:00:00.000Z", 200000),
                row("Shame", "", "2019-05-01T10:10:00.000Z", 200000),
                row("Shame", "Tasteless", "not a date", 200000),
            ),
        )
        assertEquals(listOf("Concrete"), plays.map { it.track })
        assertEquals(1, s.doubles)
        assertEquals(2, s.unreadable)
    }

    @Test
    fun anotherFileIsTurnedAway() {
        try {
            read("name,date\nx,y\n")
            throw AssertionError("taken")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("streaming.csv"))
        }
    }

    @Test
    fun aScrobbleOfTheSameSongCloseByAnswersForOneImportedPlay() {
        val o = Overlap()
        val t = 1_700_000_000L
        o.add(t, "Tyler, The Creator", "See You Again (feat. Kali Uchis)")
        o.add(t + 3600, "IDLES", "Grace")
        // The same song under the other service's naming, three minutes off.
        assertTrue(o.has(t + 180, "Tyler, The Creator feat. Kali Uchis", "See You Again", false))
        // That scrobble is spoken for: a second play of the song just after is one Last.fm missed.
        assertFalse(o.has(t + 400, "Tyler, The Creator feat. Kali Uchis", "See You Again", false))
        // Another artist's song of that name, and one too far off in time.
        assertFalse(o.has(t + 3600, "Someone Else", "Grace", false))
        assertFalse(o.has(t + 3600 + 3 * 3600, "IDLES", "Grace", false))
        // A play whose time was only worked out matches across the half day.
        assertTrue(o.has(t + 3600 + 3 * 3600, "IDLES", "Grace", true))
    }

    private class Listed(private val rows: List<Triple<Long, String, String>>) : PlaySource {
        private var i = -1
        override fun next() = ++i < rows.size
        override val ts get() = rows[i].first
        override val artist get() = rows[i].second
        override val track get() = rows[i].third
        override val album get() = ""
    }

    @Test
    fun scrobblesAndImportedPlaysComeAsOneHistoryOldestFirst() {
        val out = ArrayList<String>()
        mergePlays(
            Listed(listOf(Triple(50L, "L", "five"), Triple(70L, "L", "seven"), Triple(90L, "L", "nine"))),
            Listed(listOf(Triple(10L, "T", "one"), Triple(20L, "T", "two"), Triple(70L, "T", "also seven"), Triple(95L, "T", "last"))),
        ) { ts, _, track, _ -> out += "$ts $track" }
        assertEquals(listOf("10 one", "20 two", "50 five", "70 seven", "70 also seven", "90 nine", "95 last"), out)
        val none = ArrayList<Long>()
        mergePlays(Listed(emptyList()), Listed(listOf(Triple(1L, "T", "x")))) { ts, _, _, _ -> none += ts }
        assertEquals(listOf(1L), none)
    }

    @Test
    fun theNearestFreeScrobbleIsTheOneTaken() {
        val o = Overlap()
        val t = 1_700_000_000L
        o.add(t, "Shame", "Concrete")
        o.add(t + 240, "Shame", "Concrete")
        assertTrue(o.has(t + 250, "shame", "Concrete", false))
        assertTrue(o.has(t + 10, "shame", "Concrete", false))
        assertFalse(o.has(t + 20, "shame", "Concrete", false))
    }
}
