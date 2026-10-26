package app.recordshelf.history

import app.recordshelf.discogs.CollectionItem
import app.recordshelf.util.Text

/**
 * Genres for scrobbles. Records you own get their Discogs styles (the most precise source);
 * other music by an artist you own records of gets that artist's usual styles; everything
 * else uses the artist's Last.fm tags, cleaned of non-genres like "seen live".
 */
class Genres(collection: List<CollectionItem>, private val artistTags: Map<String, List<String>>) {
    enum class Source { DISCOGS, LASTFM, NONE }

    private val byAlbum = HashMap<String, List<String>>()
    private val byArtist = HashMap<String, List<String>>()

    init {
        val artistCounts = HashMap<String, HashMap<String, Int>>()
        for (c in collection) {
            val g = (c.styles.ifEmpty { c.genres }).map { key(it) }.filter { it.isNotEmpty() }
            if (g.isEmpty()) continue
            byAlbum[Text.albumKey(c.artist, c.title)] = g
            val counts = artistCounts.getOrPut(artistKey(c.artist)) { HashMap() }
            g.forEachIndexed { i, s -> counts[s] = (counts[s] ?: 0) + (g.size - i) }
        }
        for ((a, counts) in artistCounts) byArtist[a] = counts.entries.sortedByDescending { it.value }.map { it.key }.take(3)
    }

    /** Genre keys (see [key]) for an artist/album, best first, and where they came from. */
    fun of(artist: String, albumKey: String?): Pair<List<String>, Source> {
        albumKey?.let { k -> byAlbum[k]?.let { return it to Source.DISCOGS } }
        val a = artistKey(artist)
        byArtist[a]?.let { return it to Source.DISCOGS }
        artistTags[a]?.takeIf { it.isNotEmpty() }?.let { return it to Source.LASTFM }
        return emptyList<String>() to Source.NONE
    }

    /** Whether Discogs already tells this artist's genres (no Last.fm lookup needed). */
    fun knowsFromDiscogs(artist: String) = byArtist.containsKey(artistKey(artist))

    companion object {
        private val NOT_GENRES = setOf(
            "seen live", "favorites", "favourites", "favorite", "favourite", "my favorite", "my favourite", "love", "loved",
            "awesome", "beautiful", "albums i own", "spotify", "under 2000 listeners", "check out", "good", "cool", "amazing",
            "male vocalists", "female vocalists", "male vocalist", "female vocalist", "vocalists", "all", "music", "best",
            "british", "american", "uk", "usa", "us", "german", "deutsch", "french", "canadian", "swedish", "australian", "english",
            "irish", "japanese", "icelandic", "norwegian", "scottish", "brazilian", "dutch", "finnish", "italian", "spanish",
            "korean", "danish", "belgian", "mexican", "jamaican", "polish", "russian", "new zealand", "welsh", "african",
        )
        private val YEARS = Regex("^(\\d0s|\\d{4}s?|\\d0 s)$")

        /** One spelling per genre: "Trip-Hop", "trip hop" and "Trip Hop" are all "trip hop". */
        fun key(s: String) = s.lowercase().replace('-', ' ').replace('_', ' ').trim().replace(Regex("\\s+"), " ")

        fun label(key: String) = key.split(' ').joinToString(" ") { w -> if (w == "and" || w == "n") w else w.replaceFirstChar { it.uppercase() } }

        fun artistKey(artist: String) = Text.norm(artist)

        /** Up to three genre keys from an artist's Last.fm tags (name, weight 0..100). */
        fun fromTags(tags: List<Pair<String, Int>>, artist: String): List<String> {
            val self = key(artist)
            return tags.asSequence()
                .filter { it.second >= 10 }
                .map { key(it.first) }
                .filter { it.length in 2..30 && it !in NOT_GENRES && !YEARS.matches(it) && it != self }
                .distinct()
                .take(3)
                .toList()
        }
    }
}
