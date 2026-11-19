package app.recordshelf

import app.recordshelf.history.History
import app.recordshelf.history.MemoryStore
import app.recordshelf.history.Stats
import app.recordshelf.lastfm.RecentTrack
import app.recordshelf.util.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** One song under two spellings and credits, and one artist under two names. */
class SameSongsAndArtistsTest {
    private val utc = ZoneId.of("UTC")
    private fun at(y: Int, m: Int, d: Int = 1, min: Int = 0) = LocalDateTime.of(y, m, d, 12, 0).toEpochSecond(ZoneOffset.UTC) + min * 60L

    private fun history(rows: List<RecentTrack>) = History.from(MemoryStore().also { it.insert(rows) })

    private fun names(h: History) = (0 until h.size).map { h.artistNames[h.artist[it]] }.toSet()

    @Test
    fun aSongIsOneSongWithAndWithoutItsGuest() {
        assertEquals("the god of lying", Text.songKey("The God of Lying (feat. IDLES)"))
        assertEquals("the god of lying", Text.songKey("The God Of Lying"))
        assertEquals("Forgive", Text.withoutGuest("Forgive feat. Washed Out"))
        assertEquals("Stronger Than Me", Text.withoutGuest("Stronger Than Me Featuring Amy Winehouse & Blackout"))
        assertEquals("a remix is still told apart", "song club mix", Text.songKey("Song (Club Mix) [feat. Someone]"))
        assertEquals("a title that is nothing else stays", "(feat. Someone)", Text.withoutGuest("(feat. Someone)"))

        var t = at(2026, 3)
        val rows = ArrayList<RecentTrack>()
        // IDLES are played more than Gorillaz, so a song by both would be theirs without an album to say otherwise.
        repeat(20) { rows += RecentTrack("IDLES", "Grace", "TANGK", t++) }
        (1..6).forEach { rows += RecentTrack("Gorillaz", "Mountain Song $it", "The Mountain", t++) }
        repeat(2) { rows += RecentTrack("Gorillaz, IDLES", "The God of Lying (feat. IDLES)", "The Mountain", t++) }
        rows += RecentTrack("Gorillaz, IDLES", "The God Of Lying", "The Mountain", t++)
        // The single, named after the song and with no artist playing on it alone.
        repeat(3) { rows += RecentTrack("Gorillaz, IDLES", "The God of Lying", "The God Of Lying (feat. IDLES)", t++) }
        rows += RecentTrack("Gorillaz, IDLES", "The God of Lying (feat. IDLES)", "The God Of Lying (feat. IDLES)", t++)
        val h = history(rows)

        val song = (0 until h.size).filter { Text.songKey(h.trackTitles[h.track[it]]) == "the god of lying" }
        assertEquals(7, song.size)
        assertEquals("one song, however it was spelled and wherever it was played from", 1, song.map { h.track[it] }.toSet().size)
        assertEquals("it is on a Gorillaz album, so its single is theirs too", setOf("Gorillaz"), song.map { h.artistNames[h.artist[it]] }.toSet())
        assertEquals(setOf("IDLES"), song.map { h.artistNames[h.also[it]] }.toSet())
        assertEquals("the single's plays count for the album", setOf("The Mountain"), song.map { h.albumTitles[h.album[it]] }.toSet())

        val s = Stats(h, utc, t + 100)
        val idles = s.artist("IDLES", s.range("all"))!!.getJSONArray("tracks")
        val theirs = (0 until idles.length()).map { idles.getJSONObject(it) }.filter { Text.songKey(it.getString("title")) == "the god of lying" }
        assertEquals("the guest's page lists it once", 1, theirs.size)
        assertEquals(7, theirs[0].getInt("plays"))
        val id = s.findTrack("Gorillaz", "The God of Lying")
        assertTrue(id >= 0)
        assertEquals("either spelling finds it", id, s.findTrack("Gorillaz", "The God Of Lying (feat. IDLES)"))
        assertEquals("and so does the guest's name, as their page asks for it", id, s.findTrack("IDLES", "The God of Lying (feat. IDLES)"))
        assertEquals(7, s.track("IDLES", "The God of Lying", s.range("all"))!!.getInt("plays"))
    }

    @Test
    fun aRenamedBandIsOneArtist() {
        val rows = ArrayList<RecentTrack>()
        // Played as Slaves for years. Then the band renamed itself, and the music service everything it has of them.
        for (m in 1..12) {
            listOf("Cut And Run", "Chokehold", "Magnolia").forEach { rows += RecentTrack("Slaves", it, "Acts Of Fear And Love", at(2020, m, 3, rows.size)) }
            listOf("Sockets", "The Hunter").forEach { rows += RecentTrack("Slaves", it, "Are You Satisfied? (Deluxe)", at(2020, m, 4, rows.size)) }
        }
        for (m in 5..9) {
            listOf("Cut And Run", "Chokehold").forEach { rows += RecentTrack("SOFT PLAY", it, "Acts of Fear and Love", at(2023, m, 3, rows.size)) }
            listOf("Sockets", "The Hunter").forEach { rows += RecentTrack("Soft Play", it, "Are You Satisfied?", at(2023, m, 4, rows.size)) }
            listOf("Punk's Dead", "Mirror Muscles").forEach { rows += RecentTrack("Soft Play", it, "HEAVY JELLY", at(2024, m, 5, rows.size)) }
        }
        // Now and then something still calls them by the old name.
        rows += RecentTrack("Slaves", "Sockets", "Are You Satisfied? (Deluxe)", at(2024, 11, 1))
        val h = history(rows)

        assertEquals(mapOf("slaves" to "Soft Play"), h.sameAs)
        assertEquals("every play counts for the name they have now", setOf("Soft Play"), names(h))
        assertEquals("and the albums are one each", 3, h.albumTitles.size)
        assertEquals(5 + 2, h.trackTitles.size)

        val s = Stats(h, utc, at(2024, 12))
        val page = s.artist("Slaves", s.range("all"))!!
        assertEquals("the old name finds them", "Soft Play", page.getString("name"))
        assertEquals(rows.size, page.getInt("plays"))
        assertTrue(s.findTrack("Slaves", "Chokehold") >= 0)
        assertEquals(12 * 3 + 5 * 2, s.albumPlaysOf("Slaves", "Acts Of Fear And Love"))
    }

    @Test
    fun aSongsCoverStandsInForASingleButNotForAnAlbum() {
        val rows = ArrayList<RecentTrack>()
        repeat(3) { rows += RecentTrack("Roosevelt", "Forgive", "Forgive", at(2021, 1, 3, rows.size)) }
        repeat(2) { rows += RecentTrack("Chumbawamba", "Tubthumping", "Full 90s", at(2021, 2, 3, rows.size)) }
        rows += RecentTrack("Chumbawamba", "Amnesia", "Full 90s", at(2021, 2, 9))
        listOf("Band of Gypsies", "Reefer Man", "Reefer Man", "Crazy").forEach { rows += RecentTrack("Cypress Hill", it, "Elephants on Acid", at(2021, 3, 3, rows.size)) }
        val h = history(rows)
        val s = Stats(h, utc, at(2021, 4))
        assertEquals("a single is its song", "Forgive" to true, s.albumSong("Roosevelt", "Forgive"))
        assertEquals("a compilation you know two songs of: the one played more", "Tubthumping" to true, s.albumSong("Chumbawamba", "Full 90s"))
        assertEquals("an album has its own cover or none", "Reefer Man" to false, s.albumSong("Cypress Hill", "Elephants on Acid"))
        assertEquals(null, s.albumSong("Cypress Hill", "Black Sunday"))
    }

    @Test
    fun aNameWithWordsAddedIsTheSameArtist() {
        val rows = ArrayList<RecentTrack>()
        // Two services spell him two ways, in the same years: no name follows the other, and the one played more is his.
        for (m in 1..6) {
            listOf("Medication", "Living It Up").forEach { rows += RecentTrack("Damian Marley", it, "Stony Hill", at(2021, m, 3, rows.size)) }
            listOf("Medication", "Living It Up", "Time Travel").forEach { rows += RecentTrack("Damian \"Jr. Gong\" Marley", it, "Stony Hill", at(2021, m, 9, rows.size)) }
        }
        rows += RecentTrack("Stephen Marley", "Medication", "Other", at(2021, 7))
        val h = history(rows)
        assertEquals(mapOf("damian marley" to "Damian \"Jr. Gong\" Marley"), h.sameAs)
        assertEquals(setOf("Damian \"Jr. Gong\" Marley", "Stephen Marley"), names(h))
        assertEquals("one album, three songs of it", 3, (0 until h.size).filter { h.albumTitles[h.album[it]] == "Stony Hill" }.map { h.track[it] }.toSet().size)

        // A band that dropped a word of its name, played under the old one only with guests: one of the two songs
        // played under it is played under the new one too.
        val b = ArrayList<RecentTrack>()
        for (m in 1..3) {
            listOf("Füchse", "Mikro in der Hand").forEach { b += RecentTrack("Absolute Beginner feat. Samy Deluxe", it, "Bambule", at(2019, m, 3, b.size)) }
            listOf("Füchse (feat. Samy Deluxe)", "Liebes Lied", "Hammerhart").forEach { b += RecentTrack("Beginner", it, "Bambule", at(2020, m, 3, b.size)) }
        }
        // The played-more name stays, whatever was played last.
        b += RecentTrack("Absolute Beginner feat. Samy Deluxe", "Füchse", "Bambule", at(2021, 1))
        val hb = history(b)
        assertEquals(mapOf("absolute beginner" to "Beginner"), hb.sameAs)
        assertEquals(setOf("Beginner"), names(hb))
        assertEquals("the guest is still credited", "Samy Deluxe", hb.artistNames[hb.also[0]])
        assertEquals("four songs", 4, hb.trackTitles.size)

        // An artist and the same one with company: the plays count for the artist, however often the company was played.
        val j = ArrayList<RecentTrack>()
        repeat(5) { j += RecentTrack("Jah Sun Meets House Of Riddim", "Never Give Up", "Never Give Up", at(2021, 1, 3, j.size)) }
        repeat(2) { j += RecentTrack("Jah Sun", "Never Give Up", "Never Give Up", at(2021, 2, 3, j.size)) }
        repeat(4) { j += RecentTrack("Ying Yang Twins & Lil Jon & The East Side Boyz", "Get Low", "Kings of Crunk", at(2021, 3, 3, j.size)) }
        j += RecentTrack("Lil Jon & The East Side Boyz", "Get Low", "Kings of Crunk", at(2021, 4))
        // The same two, joined another way: the name played more.
        repeat(3) { j += RecentTrack("MXM & Pavel", "Bock", "Bock", at(2021, 5, 3, j.size)) }
        j += RecentTrack("MXM, Pavel", "Bock", "Bock", at(2021, 6))
        val hj = history(j)
        assertEquals(
            mapOf("jah sun meets house of riddim" to "Jah Sun", "ying yang twins and lil jon and the east side boyz" to "Lil Jon & The East Side Boyz", "mxm pavel" to "MXM & Pavel"),
            hj.sameAs,
        )
        assertEquals(setOf("Jah Sun", "Lil Jon & The East Side Boyz", "MXM & Pavel"), names(hj))

        // One song of many in common, on a best-of with a title like any other: not enough.
        val k = ArrayList<RecentTrack>()
        listOf("Intro", "One", "Two", "Three").forEach { k += RecentTrack("Kings", it, "Live", at(2019, 1, 3, k.size)) }
        listOf("Intro", "Sex on Fire", "Closer", "Crawl").forEach { k += RecentTrack("Kings of Leon", it, "Live", at(2019, 2, 3, k.size)) }
        assertEquals(emptyMap<String, String>(), history(k).sameAs)
    }

    @Test
    fun artistsWhoOnlyShareATitleStayApart() {
        val rows = ArrayList<RecentTrack>()
        fun play(artist: String, album: String, songs: List<String>, y: Int, m: Int) = songs.forEach { rows += RecentTrack(artist, it, album, at(y, m, 1, rows.size)) }
        // Two albums that are both called Fever, each with a song of that name.
        play("Balthazar", "Fever", listOf("Fever", "Entertainment", "Changes"), 2019, 2)
        play("The Black Keys", "Fever", listOf("Fever"), 2021, 2)
        // One album covered whole by another artist, first played after the original was last played.
        play("Taylor Swift", "1989", listOf("Style", "Blank Space", "Shake It Off"), 2015, 1)
        play("Taylor Swift", "Red", listOf("Red", "22"), 2015, 2)
        play("Ryan Adams", "1989", listOf("Style", "Blank Space", "Shake It Off"), 2016, 1)
        play("Ryan Adams", "Gold", listOf("New York, New York", "Firecracker"), 2016, 2)
        // Two compilations with songs by both, each their own.
        for (album in listOf("Tanzania Music 2017", "Best Songs of Tanzania 2017")) {
            play("Darassa", album, listOf("Muziki", "Too Much"), 2018, 1)
            play("Diamond Platnumz", album, listOf("Kidogo", "Salome"), 2019, 1)
        }
        // A duo's two albums, credited to the duo by one service and to one of the two by another, in the same years.
        for (m in 1..6) for (album in listOf("Madvillainy", "Madvillainy 2")) {
            play("Madvillain", album, listOf("Accordion", "Figaro"), 2022, m)
            play("MF DOOM", album, listOf("Accordion", "Figaro"), 2022, m)
        }
        play("MF DOOM", "MM..FOOD", listOf("Hoe Cakes", "Rapp Snitch Knishes"), 2022, 7)
        val h = history(rows)
        assertEquals(emptyMap<String, String>(), h.sameAs)
        assertEquals(setOf("Balthazar", "The Black Keys", "Taylor Swift", "Ryan Adams", "Darassa", "Diamond Platnumz", "Madvillain", "MF DOOM"), names(h))
    }
}
