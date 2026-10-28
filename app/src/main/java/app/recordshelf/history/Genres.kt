package app.recordshelf.history

import app.recordshelf.discogs.CollectionItem
import app.recordshelf.util.Text

/**
 * Genres for scrobbles, in one vocabulary for all music: a couple of dozen families (see
 * [FAMILIES]) that both sources are sorted into. Records you own get their Discogs styles (the
 * most precise source); other music by an artist you own records of gets that artist's usual
 * styles; everything else uses the artist's Last.fm tags, cleaned of non-genres like "seen
 * live". "Indie", "Indie Rock" and "Garage Rock Revival" all land in Indie & Alternative.
 *
 * Inside each family, the styles and tags themselves are its subgenres ("indie:shoegaze"): a
 * scrobble's subgenre is the first specific one in its family ("Rock" or "Indie" alone tell none).
 */
class Genres(collection: List<CollectionItem>, private val artistTags: Map<String, List<String>>) {
    enum class Source { DISCOGS, LASTFM, NONE }

    /** A scrobble's genre in detail: its family, its style in that family ("" if none is told), and the source. */
    class Detail(val family: String, val style: String, val source: Source)

    /** (family, style) pairs of each record and of each artist you own records of, best first. */
    private val byAlbum = HashMap<String, List<Pair<String, String>>>()
    private val byArtist = HashMap<String, List<Pair<String, String>>>()

    /** How Discogs spells the styles on your shelf ("Post-Punk"), by [key]. */
    private val spelling = HashMap<String, String>()

    init {
        val artistCounts = HashMap<String, HashMap<Pair<String, String>, Int>>()
        for (c in collection) {
            val names = c.styles + c.genres
            names.forEach { spelling.putIfAbsent(key(it), it) }
            val p = pairs(names)
            if (p.isEmpty()) continue
            byAlbum[Text.albumKey(c.artist, c.title)] = p
            val counts = artistCounts.getOrPut(artistKey(c.artist)) { HashMap() }
            p.forEachIndexed { i, s -> counts[s] = (counts[s] ?: 0) + (p.size - i) }
        }
        for ((a, counts) in artistCounts) byArtist[a] = counts.entries.sortedByDescending { it.value }.map { it.key }.take(6)
    }

    private fun pairsOf(artist: String, albumKey: String?): Pair<List<Pair<String, String>>, Source> {
        albumKey?.let { k -> byAlbum[k]?.let { return it to Source.DISCOGS } }
        val a = artistKey(artist)
        byArtist[a]?.let { return it to Source.DISCOGS }
        artistTags[a]?.let { pairs(it) }?.takeIf { it.isNotEmpty() }?.let { return it to Source.LASTFM }
        return emptyList<Pair<String, String>>() to Source.NONE
    }

    /** Genre families (keys of [FAMILIES]) for an artist/album, best first, and where they came from. */
    fun of(artist: String, albumKey: String?): Pair<List<String>, Source> =
        pairsOf(artist, albumKey).let { (p, s) -> p.map { it.first }.distinct() to s }

    /** The main family of an artist/album and its style in that family; null if nothing is known. */
    fun detail(artist: String, albumKey: String?): Detail? {
        val (p, s) = pairsOf(artist, albumKey)
        val f = p.firstOrNull()?.first ?: return null
        return Detail(f, p.firstOrNull { it.first == f && it.second !in GENERIC && it.second != f }?.second.orEmpty(), s)
    }

    /** Whether Discogs already tells this artist's genres (no Last.fm lookup needed). */
    fun knowsFromDiscogs(artist: String) = byArtist.containsKey(artistKey(artist))

    /** A style's name, as Discogs spells it on your shelf when it does ("Post-Punk"), else tidied. */
    fun styleLabel(style: String): String = spelling[style] ?: PRETTY[style]
        ?: label(style).replace(Regex("^Post "), "Post-").replace(Regex("^Lo Fi$"), "Lo-Fi")

    /** The name of a family ("indie") or a subgenre ("indie:shoegaze"). */
    fun labelOf(key: String): String = if (':' in key) styleLabel(key.substringAfter(':')) else label(key)

    /** Whether your record [c] is in the family or subgenre [key] (as [resolve] gives it). */
    fun owns(c: CollectionItem, key: String): Boolean {
        val p = pairs(c.styles + c.genres)
        return if (':' in key) p.any { "${it.first}:${it.second}" == key } else p.any { it.first == key }
    }

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

        /** Words that only say the family again: no subgenre of their own. */
        private val GENERIC = setOf(
            "rock", "indie", "alternative", "punk", "metal", "pop", "electronic", "electronica", "dance", "ambient", "hip hop", "rap",
            "r&b", "rnb", "soul", "funk", "funk soul", "jazz", "blues", "folk", "folk, world, & country", "country", "classical",
            "reggae", "latin", "world", "world music", "soundtrack", "stage & screen", "experimental", "synth", "electro",
        )

        /** Names the plain capitalizing gets wrong. */
        private val PRETTY = mapOf(
            "post punk" to "Post-Punk", "post rock" to "Post-Rock", "post hardcore" to "Post-Hardcore", "lo fi" to "Lo-Fi",
            "trip hop" to "Trip Hop", "synth pop" to "Synth-pop", "electro pop" to "Electropop", "k pop" to "K-Pop", "j pop" to "J-Pop",
            "post punk revival" to "Post-Punk Revival", "dance punk" to "Dance-Punk", "art punk" to "Art Punk", "idm" to "IDM",
            "edm" to "EDM", "uk garage" to "UK Garage", "ebm" to "EBM", "new wave" to "New Wave", "mpb" to "MPB", "aor" to "AOR",
            "singer songwriter" to "Singer-Songwriter", "neo soul" to "Neo-Soul", "nu disco" to "Nu-Disco", "alt country" to "Alt-Country",
        )

        /** The (family, style) pairs of [names] (styles or tags, best first), each once. */
        fun pairs(names: List<String>): List<Pair<String, String>> = names.mapNotNull { n -> family(n)?.let { it to key(n) } }.distinct()

        /**
         * One spelling per genre: "Trip-Hop", "trip hop" and "Trip Hop" are all "trip hop", and so is
         * "triphop" (see [SAME]): Discogs and Last.fm's taggers write the same style apart or together.
         */
        fun key(s: String): String {
            // Accents aside too: "Forró" and "forro", "Norteño" and "norteno" are one style.
            val plain = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(MARKS, "")
            val k = plain.replace('-', ' ').replace('_', ' ').replace('/', ' ').trim().replace(SPACES, " ")
            return SAME[k] ?: k
        }
        private val MARKS = Regex("\\p{Mn}+")
        private val SPACES = Regex("\\s+")

        /** Spellings of one style, to the one kept. */
        private val SAME = mapOf(
            "synthpop" to "synth pop", "postpunk" to "post punk", "post punk rock" to "post punk", "triphop" to "trip hop", "lofi" to "lo fi",
            "electropop" to "electro pop", "dreampop" to "dream pop", "postrock" to "post rock", "posthardcore" to "post hardcore",
            "dark wave" to "darkwave", "cold wave" to "coldwave", "newwave" to "new wave", "crankwave" to "crank wave",
            "shoegazer" to "shoegaze", "shoegazing" to "shoegaze", "alt rock" to "alternative rock", "neosoul" to "neo soul",
            "nudisco" to "nu disco", "kpop" to "k pop", "jpop" to "j pop", "afro beat" to "afrobeat", "goth rock" to "gothic rock",
            "drum n bass" to "drum and bass", "drum & bass" to "drum and bass", "d&b" to "drum and bass", "dnb" to "drum and bass",
            "deutsch rap" to "deutschrap",
        )

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
            "synth pop" to "synth", "electro pop" to "synth", "neue deutsche welle" to "synth", "ndw" to "synth",
            "italo disco" to "synth", "ebm" to "synth", "darkwave" to "synth", "coldwave" to "synth", "minimal wave" to "synth",
            "synthwave" to "synth", "retrowave" to "synth", "outrun" to "synth", "new wave" to "synth",
            // Scenes that grew out of post-punk, whatever their names say.
            "crank wave" to "punk", "no wave" to "punk", "gothic rock" to "punk", "goth" to "punk", "deathrock" to "punk",
            "riot grrrl" to "punk",
            "vaporwave" to "electronic", "witch house" to "electronic", "electro swing" to "electronic",
            "bedroom pop" to "indie", "new rave" to "indie", "baggy" to "indie", "sadcore" to "indie", "hamburger schule" to "indie",
            "boogie" to "soul", "western swing" to "country", "liedermacher" to "folk", "prog" to "rock",
            // Beyond rock and indie: scenes whose names give no family away, or the wrong one.
            "c pop" to "pop", "mandopop" to "pop", "cantopop" to "pop", "opm" to "pop", "dangdut" to "pop", "enka" to "pop",
            "vocaloid" to "pop", "idol" to "pop", "bubblegum" to "pop", "eurovision" to "pop", "variete" to "pop",
            "merengue" to "latin", "vallenato" to "latin", "banda" to "latin", "norteno" to "latin", "regional mexican" to "latin",
            "corridos" to "latin", "corridos tumbados" to "latin", "ranchera" to "latin", "mariachi" to "latin", "sertanejo" to "latin",
            "forro" to "latin", "pagode" to "latin", "axe" to "latin", "tejano" to "latin", "son cubano" to "latin", "trova" to "latin",
            "nueva trova" to "latin", "musica popular brasileira" to "latin", "reggaeton" to "latin", "dembow" to "latin",
            "funk carioca" to "hiphop", "baile funk" to "hiphop", "brazilian funk" to "hiphop", "phonk" to "hiphop", "drift phonk" to "hiphop",
            "amapiano" to "dance", "gqom" to "dance", "kwaito" to "dance", "kuduro" to "dance", "jersey club" to "dance",
            "footwork" to "dance", "juke" to "dance", "2 step" to "dance", "speed garage" to "dance", "bass music" to "dance",
            "future bass" to "dance", "moombahton" to "dance", "eurobeat" to "dance", "happy hardcore" to "dance", "gabber" to "dance",
            "goa" to "dance", "big room" to "dance", "uk funky" to "dance",
            "breakcore" to "electronic", "chiptune" to "electronic", "8 bit" to "electronic", "bitpop" to "electronic", "plunderphonics" to "electronic",
            "musique concrete" to "ambient", "sound collage" to "ambient", "field recordings" to "ambient", "lowercase" to "ambient",
            "carnatic" to "classical", "hindustani" to "classical", "gregorian chant" to "classical", "chant" to "classical",
            "early music" to "classical", "renaissance" to "classical", "medieval" to "classical", "lieder" to "classical", "art song" to "classical",
            "qawwali" to "global", "sufi" to "global", "ghazal" to "global", "bhangra" to "global", "klezmer" to "global", "rebetiko" to "global",
            "arabesk" to "global", "rai" to "global", "chaabi" to "global", "gnawa" to "global", "mbalax" to "global", "soukous" to "global",
            "bongo flava" to "global", "kizomba" to "global", "zouk" to "global", "semba" to "global", "throat singing" to "global",
            "gamelan" to "global", "griot" to "global", "desert blues" to "global", "dangdut koplo" to "pop",
            "anime" to "soundtrack", "anison" to "soundtrack", "filmi" to "soundtrack",
            "visual kei" to "rock", "red dirt" to "country", "zydeco" to "country", "polka" to "folk", "volksmusik" to "folk",
            "shanty" to "folk", "sea shanty" to "folk", "cantautore" to "folk", "cantautor" to "folk",
            "quiet storm" to "rnb", "doo wop" to "soul", "lovers rock" to "reggae",
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
            "hiphop" to Regex("hip ?hop|\\brap\\b|\\btrap\\b|grime|\\bdrill\\b|boom bap|deutschrap"),
            "metal" to Regex("metal|grindcore|deathcore|djent"),
            "punk" to Regex("punk|post hardcore|\\bemo\\b"),
            "dance" to Regex("house|techno|trance|drum (and|n) bass|\\bdnb\\b|dubstep|disco|\\bdance\\b|hardstyle|eurodance|big beat"),
            "jazz" to Regex("jazz|swing|bebop|big band"),
            "blues" to Regex("blues"),
            "folk" to Regex("folk"),
            "country" to Regex("country|honky tonk"),
            "soul" to Regex("soul|funk"),
            "rnb" to Regex("r&b|\\brnb\\b"),
            "reggae" to Regex("reggae|\\bdub\\b|\\bska\\b|dancehall|rocksteady|ragga"),
            "ambient" to Regex("ambient|drone|avant ?garde"),
            "classical" to Regex("classical|orchestra|opera|baroque|contemporary|choral|minimalism|chamber music|symphon"),
            "soundtrack" to Regex("soundtrack|film|video game|\\bost\\b"),
            "latin" to Regex("latin|bossa|samba|salsa|reggaeton|cumbia|bachata|tropical"),
            "global" to Regex("afro|world|african|celtic|balkan|highlife|bollywood|arabic|thai|turkish"),
            "synth" to Regex("synth|new wave|darkwave|coldwave|minimal wave|italo"),
            "electronic" to Regex("electr|idm|chill|glitch|trip hop"),
            "indie" to Regex("indie|alternative|lo ?fi|post rock|math rock|slowcore|garage|madchester|gaze"),
            "pop" to Regex("pop"),
            "rock" to Regex("rock|krautrock|stoner|surf|glam|psychedel|\\bprog"),
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

        /**
         * The family or subgenre a key from anywhere stands for: a family, a family's name, a
         * subgenre ("indie:shoegaze"), or a style or tag ("Post-Punk" is "punk:post punk", "Rock" is "rock").
         */
        fun resolve(s: String): String {
            val k = key(s)
            if (':' in k) return k
            if (k in FAMILIES) return k
            FAMILIES.entries.firstOrNull { key(it.value) == k }?.let { return it.key }
            val f = family(k) ?: return k
            return if (k in GENERIC || k == f) f else "$f:$k"
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
