package app.recordshelf

import app.recordshelf.history.History
import app.recordshelf.history.Stats
import app.recordshelf.lastfm.Scrobble
import app.recordshelf.net.SetSong
import app.recordshelf.net.Show
import app.recordshelf.scrobble.FixRules
import app.recordshelf.scrobble.FixRules.Rule
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Names fixed before they're scrobbled, concerts and their afterglow. */
class FixAndConcertsTest {
    private val utc = ZoneId.of("UTC")
    private fun at(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0) = LocalDateTime.of(y, m, d, h, min).toEpochSecond(ZoneOffset.UTC)
    private fun s(artist: String, track: String, album: String = "") = Scrobble(artist, track, album, timestamp = 1L)
    private fun presets(vararg ids: String) = FixRules.active(ids.toSet(), emptyList())

    @Test
    fun readyMadeFixesTakeOffWhatTheySay() {
        val all = presets("remaster", "explicit", "feat", "single", "topic")
        fun fix(x: Scrobble) = FixRules.apply(x, all)
        assertEquals("Heroes", fix(s("David Bowie", "Heroes - 2017 Remaster")).track)
        assertEquals("Heroes", fix(s("David Bowie", "Heroes (Remastered 2017)")).track)
        assertEquals("Golden Brown", fix(s("The Stranglers", "Golden Brown - Remastered")).track)
        assertEquals("Song", fix(s("A", "Song (Explicit)")).track)
        assertEquals("Song", fix(s("A", "Song [feat. Someone]")).track)
        assertEquals("Song", fix(s("A", "Song feat. Someone Else")).track)
        assertEquals("Heroes", fix(s("A", "x", "Heroes - Single")).album)
        assertEquals("Fontaines D.C.", fix(s("Fontaines D.C. - Topic", "Boys in the Better Land")).artist)
        assertEquals("names without them stay", "Remaster Me Not", fix(s("A", "Remaster Me Not")).track)
        assertEquals("off unless switched on", "Heroes - 2017 Remaster", FixRules.apply(s("A", "Heroes - 2017 Remaster"), presets()).track)
    }

    @Test
    fun ownRulesRenameReplaceAndKeepToTheirArtist() {
        val rules = listOf(
            Rule("1", "artist", "is", "Beatles, The", "The Beatles"),
            Rule("2", "title", "contains", " (Live)", "", artist = "IDLES"),
            Rule("3", "album", "matches", "^(.*) \\[Deluxe\\]$", "$1"),
            Rule("4", "title", "matches", "([", "broken"),
            Rule("5", "title", "is", "Everything", ""),
        )
        val beatles = FixRules.apply(Scrobble("beatles, the", "Help!", albumArtist = "Beatles, The", timestamp = 1), rules)
        assertEquals("The Beatles", beatles.artist)
        assertEquals("The Beatles", beatles.albumArtist)
        assertEquals("Colossus", FixRules.apply(s("IDLES", "Colossus (live)"), rules).track)
        assertEquals("only for that artist", "Song (Live)", FixRules.apply(s("Shame", "Song (Live)"), rules).track)
        assertEquals("Kid A", FixRules.apply(s("Radiohead", "x", "Kid A [Deluxe]"), rules).album)
        assertEquals("a broken pattern and an empty name change nothing", "Everything", FixRules.apply(s("A", "Everything"), rules).track)
        assertEquals("switched off", "Beatles, The", FixRules.apply(s("Beatles, The", "x"), listOf(rules[0].copy(on = false))).artist)
        val back = FixRules.parse(FixRules.toJson(rules))
        assertEquals(rules, back)
    }

    @Test
    fun concertsKeepTheirSetlistAndShowTheirAfterglow() {
        val show = Show("c1", LocalDate.of(2025, 6, 14), "IDLES", "Columbiahalle", "Berlin", "Germany", "", "https://www.setlist.fm/c1",
            listOf(SetSong("Colossus", "", "", false, false, ""), SetSong("Rottweiler", "", "Jehnny Beth", false, true, "")))
        assertEquals(show, Show.fromJson(JSONObject(show.toJson().toString())))
        assertNull(Show.fromJson(JSONObject().put("date", "not a date")))

        val plays = ArrayList<Triple<Long, String, String>>()
        plays += Triple(at(2025, 1, 2), "Shame", "One Rizla")
        repeat(3) { plays += Triple(at(2025, 6, 1, 10 + it), "IDLES", "Colossus") }
        plays += Triple(at(2025, 6, 14, 18), "IDLES", "Grace") // the day itself
        repeat(9) { plays += Triple(at(2025, 6, 16 + it, 9), "IDLES", "Rottweiler") }
        plays += Triple(at(2025, 8, 20), "IDLES", "Grace") // later than four weeks
        val h = History.Builder().also { b -> plays.forEach { (ts, a, t) -> b.add(ts, a, t, "") } }.build()
        val st = Stats(h, utc, at(2025, 9, 1))
        assertEquals(3 to 9, st.aroundDay("IDLES", LocalDate.of(2025, 6, 14)))
        assertEquals("never played: nothing either side", 0 to 0, st.aroundDay("Fontaines D.C.", LocalDate.of(2025, 6, 14)))
        assertNull("before the history starts", st.aroundDay("IDLES", LocalDate.of(2024, 12, 20)))
    }
}
