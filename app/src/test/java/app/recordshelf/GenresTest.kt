package app.recordshelf

import app.recordshelf.discogs.CollectionItem
import app.recordshelf.history.Genres
import app.recordshelf.util.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** That genres come out right for music of all kinds, from both sources. */
class GenresTest {
    private fun item(id: Long, artist: String, title: String, genres: List<String>, styles: List<String>) =
        CollectionItem(id, id, title, artist, 2020, 0, "", "", "LP", "", genres, styles, 0)

    /** The families each Discogs genre's styles may be in, as a listener would file them. */
    private val fits = mapOf(
        "Rock" to setOf("rock", "indie", "punk", "metal", "synth", "pop", "folk", "country", "ambient", "electronic", "reggae", "soul"),
        "Electronic" to setOf("dance", "electronic", "ambient", "synth", "hiphop", "indie", "pop", "jazz", "classical", "reggae", "latin", "folk"),
        "Funk / Soul" to setOf("soul", "rnb", "dance", "global"),
        "Hip Hop" to setOf("hiphop", "rnb", "electronic", "dance"),
        "Jazz" to setOf("jazz", "latin", "global", "pop"),
        "Blues" to setOf("blues"),
        "Pop" to setOf("pop", "indie", "synth", "global"),
        "Folk, World, & Country" to setOf("global", "folk", "country", "latin", "classical", "soul"),
        "Latin" to setOf("latin"),
        "Reggae" to setOf("reggae"),
        "Classical" to setOf("classical"),
        "Stage & Screen" to setOf("soundtrack"),
    )

    @Test
    fun everyDiscogsStyleLandsInAFamilyOfItsGenre() {
        // Discogs' 400 most used styles, each under its genre ("Rock---Post-Punk").
        val styles = javaClass.classLoader!!.getResource("fixtures/discogs_styles.txt")!!.readText().lines().filter { it.isNotBlank() }
        assertEquals(400, styles.size)
        val wrong = styles.mapNotNull { line ->
            val (genre, style) = line.split("---")
            val got = Genres.discogs(listOf(style), listOf(genre)).firstOrNull { it.first.second == Genres.key(style) }?.first?.first
            val ok = fits[genre]?.let { got in it } ?: (got == null)
            if (ok) null else "$line: $got"
        }
        assertEquals("styles in the wrong family", emptyList<String>(), wrong)
        // The ones whose words mislead, as Discogs means them.
        val meant = mapOf(
            "Funk / Soul---Psychedelic" to "soul", "Funk / Soul---Swingbeat" to "rnb", "Funk / Soul---Rhythm & Blues" to "soul",
            "Hip Hop---RnB/Swing" to "rnb", "Hip Hop---G-Funk" to "hiphop", "Hip Hop---Instrumental" to "hiphop",
            "Electronic---Hardcore" to "dance", "Rock---Hardcore" to "punk", "Electronic---Progressive Breaks" to "electronic",
            "Rock---Symphonic Rock" to "rock", "Rock---Brit Pop" to "indie", "Jazz---Fusion" to "jazz", "Jazz---Hard Bop" to "jazz",
            "Latin---Afro-Cuban" to "latin", "Jazz---Afro-Cuban Jazz" to "jazz", "Classical---Romantic" to "classical",
            "Electronic---Italodance" to "dance", "Reggae---Soca" to "reggae", "Folk, World, & Country---Hillbilly" to "country",
        )
        meant.forEach { (line, family) ->
            val (genre, style) = line.split("---")
            assertEquals(line, family, Genres.discogs(listOf(style), listOf(genre)).first().first.first)
        }
        assertEquals("a style is a subgenre of its family", "jazz:hard bop", Genres.resolve("Hard Bop"))
        assertEquals("no family for what isn't music", emptyList<Any>(), Genres.discogs(listOf("Spoken Word"), listOf("Non-Music")))
    }

    @Test
    fun commonLastfmTagsFindTheirFamily() {
        val tags = mapOf(
            "rock" to "rock", "electronic" to "electronic", "alternative" to "indie", "indie" to "indie", "pop" to "pop",
            "metal" to "metal", "alternative rock" to "indie", "classic rock" to "rock", "jazz" to "jazz", "experimental" to "ambient",
            "ambient" to "ambient", "folk" to "folk", "indie rock" to "indie", "electronica" to "electronic", "singer-songwriter" to "folk",
            "Hip-Hop" to "hiphop", "hard rock" to "rock", "punk" to "punk", "dance" to "dance", "chillout" to "electronic",
            "black metal" to "metal", "soul" to "soul", "death metal" to "metal", "heavy metal" to "metal", "progressive rock" to "rock",
            "psychedelic" to "rock", "House" to "dance", "Soundtrack" to "soundtrack", "piano" to "classical", "thrash metal" to "metal",
            "punk rock" to "punk", "emo" to "punk", "metalcore" to "metal", "rap" to "hiphop", "post-rock" to "indie", "funk" to "soul",
            "blues" to "blues", "trance" to "dance", "techno" to "dance", "hardcore" to "punk", "industrial" to "electronic",
            "trip-hop" to "electronic", "acoustic" to "folk", "reggae" to "reggae", "country" to "country", "j-rock" to "rock",
            "power metal" to "metal", "grunge" to "indie", "synthpop" to "synth", "new wave" to "synth", "gothic" to "punk",
            "post-punk" to "punk", "drum and bass" to "dance", "idm" to "electronic", "Classical" to "classical", "downtempo" to "electronic",
            "latin" to "latin", "j-pop" to "pop", "Lounge" to "electronic", "rnb" to "rnb", "avant-garde" to "ambient", "darkwave" to "synth",
            "Nu Metal" to "metal", "minimal" to "dance", "Ska" to "reggae", "jazz fusion" to "jazz", "neo-soul" to "soul", "shoegaze" to "indie",
            "dream pop" to "indie", "lo-fi" to "indie", "krautrock" to "rock", "bossa nova" to "latin", "afrobeat" to "global", "dub" to "reggae",
            "deep house" to "dance", "garage rock" to "indie", "stoner rock" to "rock", "britpop" to "indie", "uk garage" to "dance",
            "grime" to "hiphop", "trap" to "hiphop", "contemporary r&b" to "rnb", "spiritual jazz" to "jazz", "uk jazz" to "jazz",
        )
        tags.forEach { (tag, family) -> assertEquals(tag, family, Genres.family(tag)) }
        // Nothing for what says nothing of the music.
        listOf("seen live", "female vocalists", "british", "80s", "instrumental", "london", "beautiful").forEach {
            assertTrue(it, Genres.fromTags(listOf(it to 100), "Somebody").drop(1).none { t -> Genres.family(t.substringAfter(':')) != null })
        }
    }

    @Test
    fun recordsAndTagsAreWeighedTogether() {
        // As on Discogs and Last.fm: a jazz record filed under Hip Hop for its guests, funk records with a "Psychedelic" style.
        val shelf = listOf(
            item(1, "Ezra Collective", "Where I'm Meant To Be", listOf("Hip Hop", "Jazz"), emptyList()),
            item(2, "Surprise Chef", "Superb", listOf("Funk / Soul"), listOf("Soul", "Psychedelic")),
            item(3, "Surprise Chef", "Education & Recreation", listOf("Jazz", "Funk / Soul"), listOf("Funk")),
        )
        val tags = mapOf(
            Genres.artistKey("Ezra Collective") to Genres.fromTags(listOf("jazz" to 100, "jazz fusion" to 45, "london" to 20, "british" to 15), "Ezra Collective"),
            Genres.artistKey("Surprise Chef") to Genres.fromTags(
                listOf("jazz" to 100, "funk" to 80, "jazz fusion" to 40, "psychedelic" to 35, "instrumental" to 30, "australian" to 20, "deep funk" to 10),
                "Surprise Chef",
            ),
        )
        val g = Genres(shelf, tags)
        val ezra = g.detail("Ezra Collective", Text.albumKey("Ezra Collective", "Where I'm Meant To Be"))!!
        assertEquals("Last.fm and the record's Jazz outweigh Hip Hop", "jazz", ezra.family)
        assertEquals("jazz fusion", ezra.style)
        assertEquals("jazz", g.detail("Ezra Collective", null)!!.family)
        val chef = g.of("Surprise Chef", null).first
        assertEquals("soul", chef.first())
        assertTrue("jazz too", "jazz" in chef)
        assertFalse("\"Psychedelic\" funk isn't rock", "rock" in chef)
        assertEquals("soul", g.detail("Surprise Chef", Text.albumKey("Surprise Chef", "Superb"))!!.family)
        assertTrue(g.owns(shelf[1], "soul"))
        assertFalse(g.owns(shelf[1], "rock"))
    }

    @Test
    fun wordsForASoundCountOnlyAlone() {
        val g = Genres(emptyList(), mapOf(
            Genres.artistKey("A") to Genres.fromTags(listOf("psychedelic" to 100, "funk" to 60), "A"),
            Genres.artistKey("B") to Genres.fromTags(listOf("psychedelic" to 100, "instrumental" to 50), "B"),
        ))
        assertEquals(listOf("soul"), g.of("A", null).first)
        assertEquals("nothing else to go on", listOf("rock"), g.of("B", null).first)
    }

    @Test
    fun tagsAreKeptWithTheirWeights() {
        val kept = Genres.fromTags(listOf("Jazz" to 100, "seen live" to 90, "Funk" to 55, "1970s" to 40, "jazz" to 30, "Soul" to 5), "X")
        assertEquals(listOf("v2", "100:jazz", "55:funk"), kept)
        assertTrue(Genres.tagsCurrent(kept))
        assertEquals(listOf("jazz" to 100, "funk" to 55), Genres.readTags(kept))
        assertFalse("kept by an older version", Genres.tagsCurrent(listOf("jazz", "funk")))
        assertEquals(listOf("jazz" to 100, "funk" to 70, "soul" to 40), Genres.readTags(listOf("jazz", "funk", "soul")))
    }
}
