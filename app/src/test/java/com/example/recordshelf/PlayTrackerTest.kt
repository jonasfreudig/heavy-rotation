package com.example.recordshelf

import com.example.recordshelf.lastfm.Scrobble
import com.example.recordshelf.scrobble.Play
import com.example.recordshelf.scrobble.PlayState
import com.example.recordshelf.scrobble.PlayTracker
import com.example.recordshelf.scrobble.TrackMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayTrackerTest {
    private class Rec : PlayTracker.Output {
        val now = ArrayList<String>()
        val scrobbles = ArrayList<Scrobble>()
        override fun nowPlaying(play: Play) {
            now += play.meta.title
        }

        override fun scrobble(play: Play) {
            scrobbles += play.toScrobble()
        }
    }

    private val rec = Rec()
    private val t = PlayTracker(rec)
    private var mono = 5_000L
    private var wall = 1_700_000_000_000L

    private fun m(title: String, durSec: Int = 200, album: String = "Album") = TrackMeta("Artist", title, album, "", durSec * 1000L)
    private fun meta(x: TrackMeta?) = t.onMetadata(x, mono, wall)
    private fun play(pos: Long = 0) = t.onState(PlayState.PLAYING, pos, mono, wall)
    private fun pause(pos: Long = 0) = t.onState(PlayState.PAUSED, pos, mono, wall)
    private fun advance(ms: Long) {
        mono += ms
        wall += ms
    }

    /** Lets [ms] pass the way the service does: waking up whenever the tracker asks to. */
    private fun run(ms: Long) {
        val end = mono + ms
        repeat(100) {
            val d = t.nextCheckIn(mono)
            if (d == null || mono + d > end) {
                advance(end - mono)
                return
            }
            advance(d)
            t.onTick(mono)
        }
    }

    @Test
    fun countsAtHalfTheLengthWithTheStartTime() {
        val start = wall
        meta(m("One"))
        play()
        run(2_999)
        assertTrue(rec.now.isEmpty())
        run(1)
        assertEquals(listOf("One"), rec.now)
        run(96_999)
        assertTrue(rec.scrobbles.isEmpty())
        run(1)
        val s = rec.scrobbles.single()
        assertEquals(start / 1000, s.timestamp)
        assertEquals("One", s.track)
        assertEquals("Album", s.album)
        assertEquals(200, s.duration)
        run(200_000)
        assertEquals("only once", 1, rec.scrobbles.size)
    }

    @Test
    fun skippedTrackDoesNotCount() {
        meta(m("One"))
        play()
        run(50_000)
        val twoStart = wall
        meta(m("Two"))
        run(100_000)
        assertEquals(listOf("Two"), rec.scrobbles.map { it.track })
        assertEquals(twoStart / 1000, rec.scrobbles[0].timestamp)
    }

    @Test
    fun pausesDoNotCount() {
        val start = wall
        meta(m("One"))
        play()
        run(60_000)
        pause(60_000)
        run(600_000)
        assertNull("nothing to wait for while paused", t.nextCheckIn(mono))
        play(60_000)
        run(39_999)
        assertTrue(rec.scrobbles.isEmpty())
        run(1)
        assertEquals(start / 1000, rec.scrobbles.single().timestamp)
    }

    @Test
    fun longTracksCountAfterFourMinutes() {
        meta(m("Epic", 900))
        play()
        run(239_999)
        assertTrue(rec.scrobbles.isEmpty())
        run(1)
        assertEquals(1, rec.scrobbles.size)
    }

    @Test
    fun tracksOfThirtySecondsOrLessNeverCount() {
        meta(m("Interlude", 30))
        play()
        run(30_000)
        assertTrue(rec.now.isEmpty())
        assertTrue(rec.scrobbles.isEmpty())
    }

    @Test
    fun unknownLengthNeedsFourMinutes() {
        meta(m("Mystery", 0))
        play()
        run(239_999)
        assertTrue(rec.scrobbles.isEmpty())
        run(1)
        assertEquals(1, rec.scrobbles.size)
    }

    @Test
    fun repeatingTheSameTrackIsANewListen() {
        val start = wall
        meta(m("Loop"))
        play()
        run(200_000)
        assertEquals(1, rec.scrobbles.size)
        // The player loops: same metadata, position back at the start.
        play(300)
        run(100_000)
        assertEquals(2, rec.scrobbles.size)
        assertEquals(start / 1000, rec.scrobbles[0].timestamp)
        assertEquals((start + 200_000 - 300) / 1000, rec.scrobbles[1].timestamp)
    }

    @Test
    fun restartingBeforeItCountsIsTheSameListen() {
        val start = wall
        meta(m("One"))
        play()
        run(30_000)
        play(0) // seeked back to the start
        run(70_000)
        assertEquals(start / 1000, rec.scrobbles.single().timestamp)
    }

    @Test
    fun moreDetailsForTheSameTrackKeepTheListen() {
        val start = wall
        meta(TrackMeta("Artist", "One"))
        play()
        run(10_000)
        meta(m("One", 200, "Album"))
        run(89_999)
        assertTrue(rec.scrobbles.isEmpty())
        run(1)
        val s = rec.scrobbles.single()
        assertEquals(start / 1000, s.timestamp)
        assertEquals("Album", s.album)
        assertEquals(200, s.duration)
        assertEquals(listOf("One"), rec.now)
    }

    @Test
    fun lateWakeUpStillCountsWhenThePlayerCloses() {
        val start = wall
        meta(m("One"))
        play()
        advance(150_000) // the timer never fired (phone asleep)
        t.onDestroyed(mono)
        assertEquals(start / 1000, rec.scrobbles.single().timestamp)
        assertNull(t.play)
    }

    @Test
    fun trackPickedWhilePausedStartsWhenItPlays() {
        meta(m("One"))
        advance(60_000)
        val start = wall
        play()
        run(100_000)
        assertEquals(start / 1000, rec.scrobbles.single().timestamp)
    }

    @Test
    fun resumingMidTrackBacksUpTheStart() {
        meta(m("One"))
        val start = wall
        play(120_000)
        run(100_000)
        assertEquals((start - 120_000) / 1000, rec.scrobbles.single().timestamp)
    }

    @Test
    fun noMetadataNoListen() {
        meta(TrackMeta("", "Untitled"))
        play()
        run(300_000)
        assertNull(t.play)
        assertTrue(rec.scrobbles.isEmpty())
    }

    @Test
    fun clearedMetadataPausesTheListenUntilTheTrackReturns() {
        val start = wall
        meta(m("One"))
        play()
        run(60_000)
        meta(null) // player blanks out briefly (new session, track switch)
        run(30_000)
        meta(m("One"))
        run(39_999)
        assertTrue("the blank stretch didn't count", rec.scrobbles.isEmpty())
        run(1)
        assertEquals(start / 1000, rec.scrobbles.single().timestamp)
    }

    @Test
    fun somethingUnusableInBetweenIsNotCreditedToTheLastTrack() {
        meta(m("Skipped"))
        play()
        run(50_000)
        meta(TrackMeta("", "Advertisement", durationMs = 30_000))
        run(120_000)
        meta(m("Next"))
        run(99_999)
        assertTrue(rec.scrobbles.isEmpty())
        run(1)
        assertEquals(listOf("Next"), rec.scrobbles.map { it.track })
    }

    @Test
    fun thresholds() {
        assertEquals(100_000L, PlayTracker.thresholdMs(200_000))
        assertEquals(240_000L, PlayTracker.thresholdMs(600_000))
        assertEquals(240_000L, PlayTracker.thresholdMs(0))
    }
}
