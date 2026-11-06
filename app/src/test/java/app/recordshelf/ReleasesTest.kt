package app.recordshelf

import app.recordshelf.discogs.CollectionItem
import app.recordshelf.history.Discography
import app.recordshelf.history.Discover
import app.recordshelf.history.Fan
import app.recordshelf.history.History
import app.recordshelf.history.Like
import app.recordshelf.history.MbGroup
import app.recordshelf.history.Pick
import app.recordshelf.history.Releases
import app.recordshelf.history.Stats
import app.recordshelf.net.DzRelease
import app.recordshelf.util.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** New releases by your artists, albums you're missing, songs like your favorites and suggestions you took up. */
class ReleasesTest {
    private val today = LocalDate.of(2026, 11, 6)
    private var nextId = 1L
    private fun r(title: String, type: String, date: String) = DzRelease(nextId++, title, type, date, "", "")
    private fun fan(name: String, weight: Double, quiet: Boolean = false) = Text.norm(name) to Fan(name, weight, 100, 0, 0, quiet)
    private fun at(y: Int, m: Int, d: Int) = LocalDateTime.of(y, m, d, 12, 0).toEpochSecond(ZoneOffset.UTC)

    @Test
    fun singlesFoldIntoTheAlbumTheyLeadUpToAndOldTitlesArentNew() {
        val found = Releases.find(
            mapOf(
                "Fontaines D.C." to listOf(
                    r("Romance", "album", "2024-08-23"),
                    // A deluxe edition of an old album isn't new.
                    r("Romance (Deluxe Edition)", "album", "2026-10-30"),
                    r("Marianne", "single", "2026-08-18"),
                    r("Tongue", "single", "2026-09-30"),
                    // The title track came out as a single first: one record, the album's.
                    r("Dopamine Chamber", "single", "2026-10-01"),
                    r("Dopamine Chamber", "album", "2026-10-16"),
                    r("Best Of", "compile", "2026-10-20"),
                ),
            ),
            mapOf(fan("Fontaines D.C.", 1.0)), today,
        )
        assertEquals(listOf("Dopamine Chamber"), found.out.map { it.title })
        val album = found.out[0]
        assertEquals("album", album.type)
        assertEquals("2026-10-16", album.date)
        assertEquals(listOf("Marianne", "Tongue"), album.singles)
        assertTrue(found.soon.isEmpty())
    }

    @Test
    fun comingSoonGoesSoonestFirstWithItsSinglesAndQuietFavoritesComeBackApart() {
        val found = Releases.find(
            mapOf(
                "Big" to listOf(r("Lead Single", "single", "2026-10-20"), r("Next Album", "album", "2027-01-15")),
                "Small" to listOf(r("Soon EP", "ep", "2026-11-20")),
                "Gone" to listOf(r("Comeback", "album", "2026-10-25")),
            ),
            mapOf(fan("Big", 1.0), fan("Small", 0.2), fan("Gone", 0.8, quiet = true)), today,
        )
        assertEquals(listOf("Soon EP", "Next Album"), found.soon.map { it.title })
        assertEquals(listOf("Lead Single"), found.soon[1].singles)
        assertTrue(found.out.isEmpty())
        assertEquals(listOf("Comeback"), found.back.map { it.title })
    }

    @Test
    fun outLatelyPutsWhatYouHaventPlayedFirstThenWhoYouCareAboutAndLeavesOutWhatYouHid() {
        val found = Releases.find(
            mapOf(
                "Loved" to listOf(r("Heard Already", "album", "2026-10-30")),
                "Liked" to listOf(r("Fresh Album", "album", "2026-10-28"), r("Live at the Hall", "album", "2026-10-29")),
                "Minor" to listOf(r("A Single", "single", "2026-11-01"), r("Old", "album", "2026-06-01")),
            ),
            mapOf(fan("Loved", 1.0), fan("Liked", 0.6), fan("Minor", 0.1)), today,
            hidden = setOf(Text.albumKey("Minor", "Nothing")),
            plays = { _, t, _ -> if (t == "Heard Already") 12 else 0 },
        )
        assertEquals(listOf("Fresh Album", "Live at the Hall", "A Single", "Heard Already"), found.out.map { it.title })
        assertEquals("live", found.out[1].kind)
        assertEquals(12, found.out.last().plays)
        // Too old for "lately".
        assertFalse(found.out.any { it.title == "Old" })
        val hidden = Releases.find(mapOf("Minor" to listOf(r("A Single", "single", "2026-11-01"))), mapOf(fan("Minor", 0.1)), today, hidden = setOf(Text.albumKey("Minor", "A Single")))
        assertTrue(hidden.out.isEmpty())
    }

    @Test
    fun releasesWithoutARealDateAreLeftOut() {
        val found = Releases.find(mapOf("A" to listOf(r("Undated", "album", "0000-00-00"), r("Dated", "album", "2026-10-30"))), mapOf(fan("A", 1.0)), today)
        assertEquals(listOf("Dated"), found.out.map { it.title })
        assertTrue(Releases.keep(listOf(r("Undated", "single", "0000-00-00")), today).isEmpty())
    }

    @Test
    fun whatIsKeptBetweenChecksStillKnowsTheOldAlbumOfADeluxeEdition() {
        val kept = Releases.keep(
            listOf(r("Romance", "album", "2024-08-23"), r("Romance (Deluxe)", "album", "2026-10-30"), r("Skinty Fia", "album", "2022-04-22"), r("Hits", "compile", "2026-10-01")),
            today,
        )
        assertEquals(listOf("Romance", "Romance (Deluxe)"), kept.map { it.title })
    }

    @Test
    fun albumsMusicBrainzKnowsAreComingFillInWhatDeezerDoesntListYet() {
        val soon = Releases.announced(
            listOf(
                MbGroup("rg1", "Announced", "album", "2027-02-01", emptyList()),
                MbGroup("rg2", "On Deezer Too", "album", "2026-12-01", emptyList()),
                MbGroup("rg3", "Some Year", "album", "2027", emptyList()),
                MbGroup("rg4", "Hits", "album", "2027-01-01", listOf("Compilation")),
                MbGroup("rg5", "Old", "album", "2020-01-01", emptyList()),
            ),
            listOf(r("On Deezer Too", "album", "2026-12-01")), today,
        )
        assertEquals(listOf("Announced"), soon.map { it.title })
        assertTrue(soon[0].cover.endsWith("/release-group/rg1/front-250"))
        assertEquals(0L, soon[0].id)
    }

    @Test
    fun albumsYouAreMissingAreTheStudioAlbumsYouNeverPlayed() {
        val entries = Discography.merge(
            listOf(
                r("Songs of Praise", "album", "2018-01-12"),
                r("Drunk Tank Pink", "album", "2021-01-15"),
                r("Food for Worms", "album", "2023-02-24"),
                r("Cutthroat", "album", "2025-09-05"),
                r("Live at the Windmill", "album", "2024-05-01"),
                r("Cutthroat Remixes", "album", "2026-01-01"),
                r("Food for Worms Expanded", "album", "2023-10-01"),
                r("Next", "album", "2027-01-01"),
            ),
        )
        val played = setOf("Songs of Praise", "Food for Worms")
        val g = Releases.gaps("Shame", entries, { if (it in played) 20 else 0 }, { it == "Cutthroat" }, today)!!
        assertEquals(listOf("Drunk Tank Pink"), g.missing.map { it.title })
        assertEquals(3, g.heard)
        assertEquals(4, g.of)
        // You don't know any of their albums: nothing to fill.
        assertNull(Releases.gaps("Shame", entries, { 0 }, { false }, today))
    }

    @Test
    fun songsLikeYourFavoritesLeaveOutWhatYouPlayedAndSpreadTheArtists() {
        val seeds = listOf(Pick("A", "Hit", "", 20, "yours"), Pick("B", "Tune", "", 5, "yours"))
        val sims = mapOf(
            seeds[0].key to listOf(Triple("X", "One", 0.9), Triple("X", "Two", 0.8), Triple("X", "Three", 0.7), Triple("Y", "Played", 0.95), Triple("B", "Tune", 0.9)),
            seeds[1].key to listOf(Triple("Z", "Other", 0.9), Triple("X", "One", 0.5)),
        )
        val mix = Discover.songMix(seeds, sims, { _, t -> t == "Played" }, 10)
        assertEquals(listOf("One", "Other", "Two"), mix.map { it.title })
        assertTrue(mix.zipWithNext().none { (x, y) -> x.artist == y.artist })
    }

    @Test
    fun aSuggestionYouTookUpMakesItsArtistsCountMore() {
        val seeds = listOf("A" to 100.0, "B" to 100.0)
        val likes = mapOf("a" to listOf(Like("From A", 0.6, 0.0)), "b" to listOf(Like("From B", 0.6, 0.0)))
        assertEquals("From A", Discover.suggest(seeds, likes, { false })[0].name)
        assertEquals("From B", Discover.suggest(seeds, likes, { false }, stronger = mapOf("b" to 2))[0].name)
    }

    @Test
    fun fansAreYourArtistsAndYourShelfTheOnesYouPlayMostAndLatelyFirst() {
        val b = History.Builder()
        repeat(200) { b.add(at(2023, 3, 1) + it * 600L, "Old Love", "Song ${it % 20}", "") }
        repeat(60) { b.add(at(2026, 10, 28) + it * 600L, "New Love", "Song ${it % 10}", "") }
        repeat(5) { b.add(at(2026, 10, 1) + it * 600L, "Passing", "Song $it", "") }
        val shelf = listOf(CollectionItem(1, 1, "A Record", "Only On Vinyl", 1999, 0, "", "", "Vinyl", "", emptyList(), emptyList(), 0))
        val s = Stats(b.build(), ZoneId.of("UTC"), at(2026, 11, 6), shelf)
        val fans = s.releaseFans
        assertEquals(setOf("Old Love", "New Love", "Only On Vinyl"), fans.map { it.name }.toSet())
        assertTrue(fans.first { it.name == "Old Love" }.quiet)
        assertFalse(fans.first { it.name == "New Love" }.quiet)
        assertEquals(1, fans.first { it.name == "Only On Vinyl" }.owned)
        assertEquals(listOf("New Love" to 60), s.recentLeaders(14, 2, 10))
        assertEquals(60, s.playsSince("new love", at(2026, 10, 1)))
        assertEquals("New Love", s.recentTopSongs(30, 3)[0].artist)
    }
}
