package app.recordshelf

import app.recordshelf.MiniHttp.Companion.fixture
import app.recordshelf.discogs.Track
import app.recordshelf.discogs.Tracklist
import app.recordshelf.scrobble.Durations
import app.recordshelf.scrobble.SourceTrack
import app.recordshelf.util.Text
import app.recordshelf.util.objects
import app.recordshelf.util.str
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class DurationsTest {
    private fun t(pos: String, title: String, dur: Int = 0) = Track(pos, Tracklist.sideOf(pos), title, "X", dur, if (dur > 0) "discogs" else "")

    private fun mbRumours(): List<SourceTrack> =
        JSONObject(fixture("mb_release_081ea37e.json")).getJSONArray("media").objects().flatMap { m ->
            m.getJSONArray("tracks").objects().map { SourceTrack(it.str("number"), it.str("title"), (it.optLong("length") / 1000).toInt()) }
        }

    @Test
    fun fillsMissingLengthsFromMusicBrainzByTitle() {
        val discogs = Tracklist.parse(JSONObject(fixture("discogs_release_526351.json")).getJSONArray("tracklist"), "Fleetwood Mac")
        val blank = discogs.map { it.copy(durSec = 0, durSrc = "") }
        val filled = Durations.fill(blank, mbRumours(), "musicbrainz")
        assertEquals(0, Durations.missing(filled))
        assertEquals(discogs.map { it.durSec }, filled.map { it.durSec })
        assertEquals(setOf("musicbrainz"), filled.map { it.durSrc }.toSet())
        // "Don't Stop" vs "Don’t Stop" and "I Don't Want To Know" vs "I Don’t Want to Know"
        assertEquals(191, filled.first { it.title == "Don't Stop" }.durSec)
        assertEquals(191, filled.first { it.title == "I Don't Want To Know" }.durSec)
    }

    @Test
    fun keepsLengthsDiscogsHas() {
        val tracks = listOf(t("A1", "One", 100), t("A2", "Two"))
        val filled = Durations.fill(tracks, listOf(SourceTrack("1", "One", 999), SourceTrack("2", "Two", 222)), "lastfm")
        assertEquals(listOf(100, 222), filled.map { it.durSec })
        assertEquals(listOf("discogs", "lastfm"), filled.map { it.durSrc })
    }

    @Test
    fun matchesWithoutEditionNotes() {
        val filled = Durations.fill(listOf(t("A1", "Dreams")), listOf(SourceTrack("", "Dreams (2004 Remaster)", 257)), "lastfm")
        assertEquals(257, filled[0].durSec)
    }

    @Test
    fun matchesByPositionThenByOrder() {
        val tracks = listOf(t("A1", "Intro"), t("A2", "Song"))
        val byPos = Durations.fill(tracks, listOf(SourceTrack("A2", "Different Name", 200)), "musicbrainz")
        assertEquals(listOf(0, 200), byPos.map { it.durSec })
        val byOrder = Durations.fill(tracks, listOf(SourceTrack("1", "Opening", 60), SourceTrack("2", "Main", 300)), "musicbrainz")
        assertEquals(listOf(60, 300), byOrder.map { it.durSec })
    }

    @Test
    fun noGuessWhenNothingMatches() {
        val tracks = listOf(t("A1", "Intro"), t("A2", "Song"), t("B1", "Outro"))
        val filled = Durations.fill(tracks, listOf(SourceTrack("1", "Other", 60)), "musicbrainz")
        assertEquals(3, Durations.missing(filled))
    }

    @Test
    fun sourceTrackIsUsedOnce() {
        val tracks = listOf(t("A1", "Dreams"), t("B1", "Dreams"))
        val filled = Durations.fill(tracks, listOf(SourceTrack("", "Dreams", 254), SourceTrack("", "Dreams", 300)), "lastfm")
        assertEquals(listOf(254, 300), filled.map { it.durSec })
    }

    @Test
    fun textHelpers() {
        assertEquals(Text.norm("Don’t Stop"), Text.norm("Don't Stop"))
        assertEquals("motorhead", Text.norm("Motörhead"))
        assertEquals("simon and garfunkel", Text.norm("Simon & Garfunkel"))
        assertEquals("go your own way", Text.normLoose("Go Your Own Way - 2004 Remaster"))
        assertEquals("dreams", Text.normLoose("Dreams [Live]"))
        assertEquals(Text.albumKey("The Beatles", "Abbey Road (Remastered)"), Text.albumKey("Beatles", "Abbey Road"))
        assertEquals(Text.albumKey("Fleetwood Mac", "Rumours (Super Deluxe)"), Text.albumKey("Fleetwood Mac", "Rumours"))
        assertEquals(275, Text.parseDuration("4:35"))
        assertEquals(3723, Text.parseDuration("1:02:03"))
        assertEquals(0, Text.parseDuration(""))
        assertEquals(0, Text.parseDuration("abc"))
        assertEquals(0, Text.parseDuration(null))
        assertEquals("4:35", Text.formatDuration(275))
        assertEquals("1:02:03", Text.formatDuration(3723))
    }
}
