package app.recordshelf

import app.recordshelf.history.Discover
import app.recordshelf.history.History
import app.recordshelf.history.Like
import app.recordshelf.history.Stats
import app.recordshelf.net.DzRelease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Suggestions for music you haven't played: from your artists, through who is like them. */
class DiscoverTest {
    private fun at(y: Int, m: Int, d: Int) = LocalDateTime.of(y, m, d, 12, 0).toEpochSecond(ZoneOffset.UTC)

    @Test
    fun lastfmAndDeezerAgreeingCountsMoreThanEitherAlone() {
        val list = Discover.merge(
            listOf("Both" to 0.5, "Only Lastfm" to 0.5),
            listOf(Triple("both", 120_000L, "https://e/b.jpg"), Triple("Only Deezer", 50_000L, "")),
        )
        assertEquals(listOf("Both", "Only Lastfm", "Only Deezer"), list.map { it.name })
        val both = list[0]
        assertEquals(120_000L, both.fans)
        assertEquals("https://e/b.jpg", both.photo)
        assertTrue(both.likeness > list[1].likeness)
        // Deezer's first is worth 1, its last 0.4; alone, Deezer counts for 70%.
        assertEquals(0.7 * 0.4, list[2].likeness, 1e-9)
    }

    @Test
    fun severalOfYourArtistsPointingToOneRankItFirst() {
        val seeds = listOf("A" to 100.0, "B" to 80.0, "C" to 60.0)
        val likes = mapOf(
            "a" to listOf(Like("Lone", 0.9, 0.0), Like("Shared", 0.5, 0.0)),
            "b" to listOf(Like("Shared", 0.5, 0.0)),
            "c" to listOf(Like("Shared", 0.5, 0.0), Like("A", 0.9, 0.0)),
        )
        val s = Discover.suggest(seeds, likes, known = { false })
        assertEquals("Shared", s[0].name)
        assertEquals(listOf("A", "B", "C"), s[0].because.map { it.first })
        // Your own artists are never suggested to you.
        assertFalse(s.any { it.name == "A" })
    }

    @Test
    fun artistsYouKnowOrTurnedDownAreLeftOutAndTheirSourceWeakens() {
        val seeds = listOf("A" to 100.0, "B" to 100.0)
        val likes = mapOf(
            "a" to listOf(Like("Played", 0.9, 0.0), Like("Nope", 0.9, 0.0), Like("From A", 0.6, 0.0)),
            "b" to listOf(Like("From B", 0.6, 0.0)),
        )
        val plain = Discover.suggest(seeds, likes, known = { it == "Played" }, no = setOf("nope"))
        assertEquals(setOf("From A", "From B"), plain.map { it.name }.toSet())
        // Turning down three of A's suggestions makes A count for less: B's now comes first.
        val weaker = Discover.suggest(seeds, likes, known = { it == "Played" }, no = setOf("nope"), weaker = mapOf("a" to 3))
        assertEquals("From B", weaker[0].name)
    }

    @Test
    fun furtherOutPrefersLessKnownArtistsAndLooksTwoStepsAway() {
        val seeds = listOf("A" to 100.0)
        val likes = mapOf(
            "a" to listOf(Like("Famous", 0.8, 0.0, fans = 20_000_000), Like("Small", 0.6, 0.0, fans = 20_000)),
            "small" to listOf(Like("Tiny", 0.9, 0.0, fans = 3_000)),
        )
        assertEquals("Famous", Discover.suggest(seeds, likes, { false }, reach = Discover.CLOSE)[0].name)
        val far = Discover.suggest(seeds, likes, { false }, reach = Discover.FURTHER)
        assertEquals("Small", far[0].name)
        val tiny = far.first { it.name == "Tiny" }
        assertEquals("Small", tiny.via)
        // Close by, nothing comes from two steps away.
        assertFalse(Discover.suggest(seeds, likes, { false }, reach = Discover.CLOSE).any { it.name == "Tiny" })
    }

    @Test
    fun theMixTakesTwoSongsAnArtistAndNeverTheSameArtistTwiceInARow() {
        val sugg = Discover.suggest(listOf("Seed" to 1.0), mapOf("seed" to (1..6).map { Like("New $it", 1.0 - it * 0.1, 0.0) }), { false })
        val tops = sugg.associate { app.recordshelf.util.Text.norm(it.name) to (1..8).map { k -> "${it.name} song $k" } }
        val best = Discover.mix(sugg, tops, 10, 0)
        assertEquals(10, best.size)
        assertEquals(listOf("New 1 song 1", "New 2 song 1"), best.take(2).map { it.title })
        assertTrue(best.groupBy { it.artist }.values.all { it.size <= 2 })
        assertTrue(best.zipWithNext().none { (x, y) -> x.artist == y.artist })
        // Another seed makes another mix; the same seed the same one.
        val again = Discover.mix(sugg, tops, 10, 42)
        assertEquals(again, Discover.mix(sugg, tops, 10, 42))
        assertTrue(again.zipWithNext().none { (x, y) -> x.artist == y.artist })
        assertEquals(Discover.weekSeed(LocalDate.of(2026, 10, 26)), Discover.weekSeed(LocalDate.of(2026, 11, 1)))
        assertTrue(Discover.weekSeed(LocalDate.of(2026, 11, 1)) != Discover.weekSeed(LocalDate.of(2026, 11, 2)))
    }

    @Test
    fun newReleasesAreRecentOrComingAndNoCompilations() {
        fun r(title: String, type: String, date: String) = DzRelease(1, title, type, date, "", "")
        val today = LocalDate.of(2026, 10, 30)
        val list = Discover.newReleases(
            listOf(
                "A" to listOf(r("Old", "album", "2026-01-10"), r("New Album", "album", "2026-10-09"), r("Best Of", "compile", "2026-10-20")),
                "B" to listOf(r("Soon", "single", "2026-11-20"), r("Single", "single", "2026-10-28"), r("Single", "single", "2026-10-28")),
            ),
            today,
        )
        assertEquals(listOf("Soon", "Single", "New Album"), list.map { it.second.title })
    }

    @Test
    fun seedsAreTheArtistsYouPlayLatelyMore() {
        val b = History.Builder()
        // Old favorite: 60 plays two years ago. New one: 20 plays this month.
        repeat(60) { b.add(at(2024, 9, 1) + it * 600L, "Old", "Song $it", "") }
        repeat(20) { b.add(at(2026, 10, 10) + it * 600L, "New", "Song $it", "") }
        val s = Stats(b.build(), ZoneId.of("UTC"), at(2026, 10, 30))
        val seeds = s.discoverSeeds()
        assertEquals(listOf("New", "Old"), seeds.map { it.first })
        assertTrue(s.knows("old"))
        assertFalse(s.knows("Someone Else"))
    }
}
