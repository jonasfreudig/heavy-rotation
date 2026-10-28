package app.recordshelf.history

import app.recordshelf.discogs.CollectionItem
import app.recordshelf.util.Text

/**
 * Genres for scrobbles, in one vocabulary for all music: a couple of dozen families (see
 * [FAMILIES]) that both sources are sorted into. Records you own get their Discogs styles (the
 * most precise source); other music by an artist you own records of gets that artist's usual
 * styles; everything else uses the artist's Last.fm tags, cleaned of non-genres like "seen
 * live". "Indie", "Indie Rock" and "Garage Rock Revival" all land in Indie & Alternative.
 */
class Genres(collection: List<CollectionItem>, private val artistTags: Map<String, List<String>>) {
    enum class Source { DISCOGS, LASTFM, NONE }

    private val byAlbum = HashMap<String, List<String>>()
    private val byArtist = HashMap<String, List<String>>()

    init {
        val artistCounts = HashMap<String, HashMap<String, Int>>()
        for (c in collection) {
            val g = families(c.styles + c.genres)
            if (g.isEmpty()) continue
            byAlbum[Text.albumKey(c.artist, c.title)] = g
            val counts = artistCounts.getOrPut(artistKey(c.artist)) { HashMap() }
            g.forEachIndexed { i, s -> counts[s] = (counts[s] ?: 0) + (g.size - i) }
        }
        for ((a, counts) in artistCounts) byArtist[a] = counts.entries.sortedByDescending { it.value }.map { it.key }.take(3)
    }

    /** Genre families (keys of [FAMILIES]) for an artist/album, best first, and where they came from. */
    fun of(artist: String, albumKey: String?): Pair<List<String>, Source> {
        albumKey?.let { k -> byAlbum[k]?.let { return it to Source.DISCOGS } }
        val a = artistKey(artist)
        byArtist[a]?.let { return it to Source.DISCOGS }
        artistTags[a]?.let { families(it) }?.takeIf { it.isNotEmpty() }?.let { return it to Source.LASTFM }
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

        fun label(key: String) = FAMILIES[key] ?: key.split(' ').joinToString(" ") { w -> if (w == "and" || w == "n") w else w.replaceFirstChar { it.uppercase() } }

        /** The genre families everything is sorted into, by key, with their names. */
        val FAMILIES = linkedMapOf(
            "rock" to "Rock", "indie" to "Indie & Alternative", "punk" to "Punk & Post-Punk", "metal" to "Metal",
            "pop" to "Pop", "synth" to "New Wave & Synth", "electronic" to "Electronic", "dance" to "Dance & Club",
            "ambient" to "Ambient & Experimental", "hiphop" to "Hip Hop", "rnb" to "R&B", "soul" to "Soul & Funk",
            "jazz" to "Jazz", "blues" to "Blues", "folk" to "Folk & Singer-Songwriter", "country" to "Country & Americana",
            "classical" to "Classical", "reggae" to "Reggae & Dub", "latin" to "Latin", "global" to "Global", "soundtrack" to "Soundtrack",
        )

        /** Names whose words would lead elsewhere ("dance pop" is pop, "noise rock" punk), or that no word gives away. */
        private val ALIASES = mapOf(
            "indie pop" to "indie", "jangle pop" to "indie", "noise pop" to "indie", "dream pop" to "indie", "chillwave" to "indie",
            "dance pop" to "pop", "art pop" to "pop", "power pop" to "pop", "vocal" to "pop", "ballad" to "pop", "chanson" to "pop",
            "schlager" to "pop", "easy listening" to "pop", "europop" to "pop", "k pop" to "pop", "j pop" to "pop",
            "synth pop" to "synth", "synthpop" to "synth", "electropop" to "synth", "electro pop" to "synth", "neue deutsche welle" to "synth",
            "italo disco" to "synth", "ebm" to "synth",
            "pop rock" to "rock", "soft rock" to "rock", "blues rock" to "rock", "hard rock" to "rock", "art rock" to "rock",
            "experimental rock" to "rock", "beat" to "rock", "mod" to "rock", "aor" to "rock", "classic rock" to "rock",
            "noise rock" to "punk", "hardcore" to "punk", "emo" to "punk", "screamo" to "punk", "oi" to "punk",
            "folk rock" to "folk", "singer songwriter" to "folk", "acoustic" to "folk", "americana" to "country", "bluegrass" to "country",
            "trip hop" to "electronic", "leftfield" to "electronic", "abstract" to "electronic", "lounge" to "electronic", "idm" to "electronic",
            "downtempo" to "electronic", "breakbeat" to "electronic", "industrial" to "electronic",
            "minimal" to "dance", "garage house" to "dance", "uk garage" to "dance", "jungle" to "dance", "edm" to "dance",
            "experimental" to "ambient", "noise" to "ambient", "drone" to "ambient", "new age" to "ambient",
            "rnb" to "rnb", "r&b" to "rnb", "r n b" to "rnb", "new jack swing" to "rnb", "gospel" to "soul", "motown" to "soul",
            "stage & screen" to "soundtrack", "score" to "soundtrack", "musical" to "soundtrack",
            "mpb" to "latin", "tango" to "latin", "flamenco" to "latin", "fado" to "global",
            "psychedelic" to "rock", "psych" to "rock", "piano" to "classical", "grunge" to "indie", "britpop" to "indie", "shoegaze" to "indie",
        )

        /** Words that tell a family, checked in this order (punk before electro: "electropunk" is punk). */
        private val RULES = listOf(
            "hiphop" to Regex("hip hop|\\brap\\b|\\btrap\\b|grime|\\bdrill\\b|boom bap|deutschrap"),
            "metal" to Regex("metal|grindcore|deathcore|djent"),
            "punk" to Regex("punk|post hardcore"),
            "dance" to Regex("house|techno|trance|drum (and|n) bass|\\bdnb\\b|dubstep|disco|\\bdance\\b|hardstyle|eurodance|big beat"),
            "jazz" to Regex("jazz|swing|bebop|big band"),
            "blues" to Regex("blues"),
            "folk" to Regex("folk"),
            "country" to Regex("country|honky tonk"),
            "soul" to Regex("soul|funk"),
            "rnb" to Regex("r&b|\\brnb\\b"),
            "reggae" to Regex("reggae|\\bdub\\b|\\bska\\b|dancehall|rocksteady"),
            "ambient" to Regex("ambient|drone|avant ?garde"),
            "classical" to Regex("classical|orchestra|opera|baroque|contemporary|choral|minimalism|chamber music"),
            "soundtrack" to Regex("soundtrack|film|video game|\\bost\\b"),
            "latin" to Regex("latin|bossa|samba|salsa|reggaeton|cumbia|bachata|tropical"),
            "global" to Regex("afro|world|african|celtic|balkan|highlife|bollywood|arabic|thai|turkish"),
            "synth" to Regex("synth|new wave|wave\\b|\\bwave"),
            "electronic" to Regex("electr|idm|chill|glitch|trip hop"),
            "indie" to Regex("indie|alternative|lo ?fi|post rock|math rock|slowcore|garage|madchester"),
            "pop" to Regex("pop"),
            "rock" to Regex("rock|krautrock|stoner|surf|glam|psychedel"),
        )

        /** The family of one style or tag (a [key]), or null for what isn't a genre ("Spoken Word", "Children's"). */
        fun family(s: String): String? {
            val k = key(s)
            if (k in FAMILIES) return k
            ALIASES[k]?.let { return it }
            return RULES.firstOrNull { it.second.containsMatchIn(k) }?.first
        }

        /** The families of [names] (styles or tags, best first), each once, best first. */
        fun families(names: List<String>): List<String> = names.mapNotNull { family(it) }.distinct()

        /** The family a key from anywhere stands for: a family, a family's name, or a style or tag ("Post-Punk"). */
        fun resolve(s: String): String {
            val k = key(s)
            if (k in FAMILIES) return k
            FAMILIES.entries.firstOrNull { key(it.value) == k }?.let { return it.key }
            return family(k) ?: k
        }

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
