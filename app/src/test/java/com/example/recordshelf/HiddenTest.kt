package com.example.recordshelf

import com.example.recordshelf.history.Hidden
import com.example.recordshelf.history.History
import com.example.recordshelf.history.Junk
import com.example.recordshelf.history.MemoryStore
import com.example.recordshelf.lastfm.RecentTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenTest {
    private fun store(vararg rows: RecentTrack) = MemoryStore().also { it.insert(rows.toList()) }

    @Test
    fun hiddenScrobblesStayOutOfTheHistory() {
        val s = store(
            RecentTrack("IDLES", "Grace", "TANGK", 100),
            RecentTrack("HBO Max", "HBO Max", "", 200),
            RecentTrack("Gameplay Channel", "Loop Hero Launch Trailer", "", 300),
            RecentTrack("Gameplay Channel", "Another Video", "", 400),
            RecentTrack("Fontaines D.C. feat. Someone", "Starburster", "Romance", 500),
        )
        val hidden = Hidden().hideTrack("hbo max", "HBO MAX").hideArtist("Gameplay Channel").hideTrack("Fontaines D.C.", "Starburster")
        val h = History.from(s, hidden)
        assertEquals("matched loosely, by artist, and by the main artist of a feature", listOf(100L), h.ts.toList())
        assertEquals(4, h.hiddenPlays)
        assertEquals(5, History.from(s).size)
    }

    @Test
    fun hideShowKeepAndJson() {
        val h = Hidden().hideTrack("A", "Clip").hideArtist("B").keep("C", "Song")
        assertTrue(h.hides("a", "clip"))
        assertTrue(h.hides("B", "anything"))
        assertTrue(h.isKept("C", "Song"))
        val back = Hidden.fromJson(h.toJson().toString())
        assertTrue(back.hides("A", "Clip"))
        assertTrue(back.hides("B", "x"))
        assertTrue(back.isKept("c", "song"))
        val shown = back.showTrack("A", "Clip").showArtist("b")
        assertFalse(shown.hides("A", "Clip"))
        assertFalse(shown.hides("B", "x"))
        assertEquals("hiding a kept track forgets the keep", false, back.hideTrack("C", "Song").isKept("C", "Song"))
        assertEquals("broken files mean nothing hidden", 0, Hidden.fromJson("{oops").artists.size)
    }

    @Test
    fun junkReasons() {
        assertEquals(listOf("app", "sameName"), Junk.reasons("HBO Max", "HBO Max"))
        assertEquals(listOf("video"), Junk.reasons("Some Channel", "Loop Hero Launch Trailer"))
        assertEquals(listOf("videoTitle"), Junk.reasons("Channel", "CAULSE | V"))
        assertEquals(listOf("video"), Junk.reasons("Show", "Folge 12"))
        assertEquals(listOf("clip"), Junk.reasons("Someone", "Dubz", quickRepeats = 3))
        assertTrue(Junk.reasons("Fontaines D.C.", "Starburster").isEmpty())
        assertTrue(Junk.reasons("Modest Mouse", "Trailer Trash").contains("video")) // only suggested without an album, see below
    }

    @Test
    fun scanSuggestsAlbumlessJunkMostPlayedFirst() {
        val rows = ArrayList<RecentTrack>()
        var t = 1_000_000L
        // A 25-second clip on loop, ten times: scrobbled seconds apart.
        repeat(10) { rows += RecentTrack("Someone", "Dubz", "", t); t += 25 }
        t += 1000
        repeat(4) { rows += RecentTrack("HBO Max", "HBO Max", "", t); t += 3600 }
        // The same words, but a real release: not suggested.
        repeat(3) { rows += RecentTrack("Modest Mouse", "Trailer Trash", "The Lonesome Crowded West", t); t += 300 }
        // Music without an album: listed for a look, not suggested.
        repeat(5) { rows += RecentTrack("Ulepuschkinrose", "Lied", "", t); t += 300 }
        rows += RecentTrack("Kept Channel", "Episode 4", "", t)
        val h = History.from(store(*rows.toTypedArray()))
        val (sugg, other) = Junk.scan(h, Hidden().keep("Kept Channel", "Episode 4"))
        assertEquals(listOf("Dubz", "HBO Max"), sugg.map { h.trackTitles[it.track] })
        assertEquals(10, sugg[0].plays)
        assertEquals(listOf("clip"), sugg[0].reasons)
        assertEquals(listOf("Lied"), other.map { h.trackTitles[it.track] })
    }
}
