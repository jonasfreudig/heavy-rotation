package app.recordshelf

import app.recordshelf.scrobble.PlanTrack
import app.recordshelf.scrobble.Planner
import app.recordshelf.scrobble.Spin
import app.recordshelf.scrobble.SpinItem
import app.recordshelf.scrobble.When
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpinTest {
    private fun pt(title: String, dur: Int, side: String = "A") = PlanTrack("Artist", title, "Album", "Artist", dur, 1, "${side}1", side, false)

    private fun spin(tracks: List<PlanTrack>, start: Long = 0, sides: List<String> = listOf("A", "B")): Spin {
        val starts = Planner.starts(tracks, When.AT, start, start)
        return Spin("s", 1, "Album", "Artist", "", sides, tracks.zip(starts).map { (t, s) -> SpinItem(t, s) }.toMutableList())
    }

    // ---- planning ----

    @Test
    fun startingNowPlaysBackToBackWithAPauseToFlip() {
        val now = 1_000_000L
        val starts = Planner.starts(listOf(pt("a", 100), pt("b", 200), pt("c", 300, "B")), When.NOW, 0, now)
        assertEquals(listOf(now, now + 100_000, now + 300_000 + Planner.FLIP_GAP_MS), starts)
    }

    @Test
    fun justFinishedEndsNow() {
        val now = 10_000_000L
        val tracks = listOf(pt("a", 100), pt("b", 200, "B"))
        val starts = Planner.starts(tracks, When.FINISHED, 0, now)
        assertEquals(now, starts.last() + 200_000)
        assertEquals(now - 300_000 - Planner.FLIP_GAP_MS, starts.first())
    }

    @Test
    fun earlierStartsAtTheGivenTime() {
        assertEquals(listOf(5_000L, 105_000L), Planner.starts(listOf(pt("a", 100), pt("b", 50)), When.AT, 5_000, 99_999_999))
    }

    @Test
    fun lastFmRuleHalfOrFourMinutes() {
        assertEquals(50_000L, Planner.dueMs(0, 100))
        assertEquals(240_000L, Planner.dueMs(0, 600))
        assertTrue(Planner.tooOld(0, Planner.MAX_AGE_MS + 1))
        assertFalse(Planner.tooOld(0, Planner.MAX_AGE_MS))
    }

    // ---- the session ----

    @Test
    fun tracksCountOnceHalfHasPlayed() {
        val s = spin(listOf(pt("a", 100), pt("b", 200)))
        assertTrue(s.takeDue(49_999).isEmpty())
        assertEquals(listOf("a"), s.takeDue(50_000).map { it.track.title })
        assertTrue("never twice", s.takeDue(60_000).isEmpty())
        assertEquals(listOf("b"), s.takeDue(200_000).map { it.track.title })
        assertFalse("still playing b", s.isOver(200_000))
        assertTrue(s.isOver(300_000))
    }

    @Test
    fun currentTrackAndNextWakeUp() {
        val s = spin(listOf(pt("a", 100), pt("b", 200)))
        assertEquals(0, s.currentIndex(10_000))
        assertEquals(50_000L, s.nextEventMs(10_000))
        s.takeDue(50_000)
        assertEquals(100_000L, s.nextEventMs(50_000))
        assertEquals(1, s.currentIndex(150_000))
        assertEquals(200_000L, s.nextEventMs(150_000))
        s.takeDue(200_000)
        assertEquals(300_000L, s.nextEventMs(200_000))
        assertEquals(-1, s.currentIndex(300_000))
        assertNull(s.nextEventMs(300_000))
    }

    @Test
    fun stopCountsOnlyWhatPlayedHalfway() {
        val s = spin(listOf(pt("a", 100), pt("b", 200), pt("c", 200)))
        val counted = s.stop(160_000)
        assertEquals(listOf("a"), counted.map { it.track.title })
        assertEquals(listOf(SpinItem.SCROBBLED, SpinItem.SKIPPED, SpinItem.SKIPPED), s.items.map { it.state })
        assertTrue(s.isOver(160_000))
    }

    @Test
    fun flipDropsTheRestOfTheSideAndStartsTheNextNow() {
        val s = spin(listOf(pt("a1", 100), pt("a2", 100)))
        assertEquals("B", s.nextSide())
        val counted = s.flip(listOf(pt("b1", 100, "B"), pt("b2", 100, "B")), 130_000)
        assertEquals(listOf("a1"), counted.map { it.track.title })
        assertEquals(SpinItem.SKIPPED, s.items[1].state)
        assertEquals(130_000L, s.items[2].startMs)
        assertEquals(230_000L, s.items[3].startMs)
        assertEquals("b1", s.items[s.currentIndex(130_000)].track.title)
        assertNull("B was the last side", s.nextSide())
    }

    @Test
    fun flipWhileTheLastTrackOverlapsPicksTheNewSide() {
        val s = spin(listOf(pt("a1", 100)))
        s.flip(listOf(pt("b1", 100, "B")), 80_000)
        assertEquals(SpinItem.SCROBBLED, s.items[0].state)
        assertEquals("b1", s.items[s.currentIndex(90_000)].track.title)
    }

    @Test
    fun resyncMovesLaterTracksAndCountsEarlierOnes() {
        val s = spin(listOf(pt("a", 100), pt("b", 100), pt("c", 100), pt("d", 100, "B")))
        // At 30 s the user says track c is playing: the guess was behind.
        s.resync(2, 30_000)
        assertEquals(30_000L, s.items[2].startMs)
        assertEquals(130_000L + Planner.FLIP_GAP_MS, s.items[3].startMs)
        assertEquals(-70_000L, s.items[1].startMs)
        assertEquals(-170_000L, s.items[0].startMs)
        assertEquals(listOf("a", "b"), s.takeDue(30_000).map { it.track.title })
        assertEquals(2, s.currentIndex(30_000))
        assertEquals(-1, s.nowPlaying)
    }

    @Test
    fun resyncBackwardsKeepsWhatWasSent() {
        val s = spin(listOf(pt("a", 100), pt("b", 100), pt("c", 100)))
        s.takeDue(160_000) // a and b counted
        s.resync(1, 160_000) // the record is actually still on b
        assertEquals(SpinItem.SCROBBLED, s.items[1].state)
        assertEquals(160_000L, s.items[1].startMs)
        assertEquals(260_000L, s.items[2].startMs)
        assertTrue(s.takeDue(300_000).isEmpty())
        assertEquals(listOf("c"), s.takeDue(310_000).map { it.track.title })
    }

    @Test
    fun nextSideOfADoubleAlbum() {
        val s = spin(listOf(pt("b1", 100, "B")), sides = listOf("A", "B", "C", "D"))
        assertEquals("C", s.nextSide())
    }

    @Test
    fun survivesSavingAndLoading() {
        val s = spin(listOf(pt("a", 100), pt("b", 200, "B")), start = 5_000)
        s.takeDue(60_000)
        s.nowPlaying = 0
        val back = Spin.fromJson(s.toJson())
        assertEquals(s.toJson().toString(), back.toJson().toString())
        assertEquals(listOf(SpinItem.SCROBBLED, SpinItem.PLANNED), back.items.map { it.state })
        assertEquals("B", back.items[1].track.side)
        val ui = s.toUiJson()
        assertEquals("scrobbled", ui.getJSONArray("items").getJSONObject(0).getString("state"))
    }
}
