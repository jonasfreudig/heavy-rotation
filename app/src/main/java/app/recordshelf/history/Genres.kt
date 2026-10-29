package app.recordshelf.history

import app.recordshelf.discogs.CollectionItem
import app.recordshelf.util.Text

/**
 * Genres for scrobbles, in one vocabulary for all music: a couple of dozen families (see
 * [FAMILIES]) that both sources are sorted into, then weighed together. A record you own brings
 * its Discogs styles, each read within the record's Discogs genres ("Psychedelic" on a Funk /
 * Soul record is psychedelic soul, not rock); the artist's other records on your shelf count a
 * little; the artist's Last.fm tags, cleaned of non-genres like "seen live", count as much as the
 * record, and so do the album's own Last.fm tags when it has some (an artist's electronic album
 * among their rock ones). The family with the most weight is the scrobble's genre: one source's slip (Discogs
 * filing a jazz record under Hip Hop for its guest rappers) doesn't decide it alone.
 * "Indie", "Indie Rock" and "Garage Rock Revival" all land in Indie & Alternative.
 *
 * Inside each family, the styles and tags themselves are its subgenres ("indie:shoegaze"): a
 * scrobble's subgenre is its weightiest specific one in its family ("Rock" or "Indie" alone tell none).
 */
class Genres(collection: List<CollectionItem>, tags: Map<String, List<String>>) {
    enum class Source { DISCOGS, LASTFM, NONE }

    /** A scrobble's genre in detail: its family, its style in that family ("" if none is told), and the source most of it came from. */
    class Detail(val family: String, val style: String, val source: Source)

    /**
     * Weighed (family, style) pairs, each set summing to 1: of each record, of each artist you own records of, of each
     * artist's Last.fm tags, of each album's Last.fm tags.
     */
    private val byAlbum = HashMap<String, Map<Pair<String, String>, Double>>()
    private val byArtist = HashMap<String, Map<Pair<String, String>, Double>>()
    private val byTags = HashMap<String, Map<Pair<String, String>, Double>>()
    private val byAlbumTags = HashMap<String, Map<Pair<String, String>, Double>>()

    /** How Discogs spells the styles on your shelf ("Post-Punk"), by [key]. */
    private val spelling = HashMap<String, String>()

    init {
        val artistSums = LinkedHashMap<String, LinkedHashMap<Pair<String, String>, Double>>()
        for (c in collection) {
            (c.styles + c.genres).forEach { spelling.putIfAbsent(key(it), it) }
            val v = votes(discogs(c.styles, c.genres)) ?: continue
            byAlbum[Text.albumKey(c.artist, c.title)] = v
            val sums = artistSums.getOrPut(artistKey(c.artist)) { LinkedHashMap() }
            v.forEach { (p, w) -> sums.merge(p, w, Double::plus) }
        }
        for ((a, sums) in artistSums) votes(sums.toList())?.let { byArtist[a] = it }
        // Kept tags by [artistKey], or by [Text.albumKey] for an album's own.
        for ((k, kept) in tags) votes(tagVotes(readTags(kept)))?.let { if (isAlbumKey(k)) byAlbumTags[k] = it else byTags[k] = it }
    }

    /** All that is known of an artist/album: families by weight, best first, the weight of each (family, style), and each family's main source. */
    private class Tally(val families: List<Pair<String, Double>>, val styles: Map<Pair<String, String>, Double>, val source: (String) -> Source)

    private fun tally(artist: String, albumKey: String?): Tally? {
        val a = artistKey(artist)
        val parts = listOfNotNull(
            albumKey?.let { byAlbum[it] }?.let { Triple(it, RECORD_SHARE, Source.DISCOGS) },
            albumKey?.let { byAlbumTags[it] }?.let { Triple(it, ALBUM_TAGS_SHARE, Source.LASTFM) },
            byArtist[a]?.let { Triple(it, SHELF_SHARE, Source.DISCOGS) },
            byTags[a]?.let { Triple(it, TAGS_SHARE, Source.LASTFM) },
        )
        if (parts.isEmpty()) return null
        val total = parts.sumOf { it.second }
        val styles = LinkedHashMap<Pair<String, String>, Double>()
        val fams = LinkedHashMap<String, Double>()
        val bySource = HashMap<Pair<String, Source>, Double>()
        for ((v, share, src) in parts) for ((p, w) in v) {
            val x = w * share / total
            styles.merge(p, x, Double::plus)
            fams.merge(p.first, x, Double::plus)
            bySource.merge(p.first to src, x, Double::plus)
        }
        return Tally(fams.entries.sortedByDescending { it.value }.map { it.key to it.value }, styles) { f ->
            if ((bySource[f to Source.DISCOGS] ?: 0.0) >= (bySource[f to Source.LASTFM] ?: 0.0)) Source.DISCOGS else Source.LASTFM
        }
    }

    /** Genre families (keys of [FAMILIES]) for an artist/album, best first (the minor ones left out), and where the first came from. */
    fun of(artist: String, albumKey: String?): Pair<List<String>, Source> {
        val t = tally(artist, albumKey) ?: return emptyList<String>() to Source.NONE
        val (best, top) = t.families.first()
        return t.families.filter { it.second >= maxOf(top * SHOWN, SHOWN_MIN) }.map { it.first } to t.source(best)
    }

    /** The main family of an artist/album and its style in that family; null if nothing is known. */
    fun detail(artist: String, albumKey: String?): Detail? {
        val t = tally(artist, albumKey) ?: return null
        val f = t.families.first().first
        val style = t.styles.entries.filter { it.key.first == f && it.key.second !in GENERIC && it.key.second != f }.maxByOrNull { it.value }
        return Detail(f, style?.key?.second.orEmpty(), t.source(f))
    }

    /** A style's name, as Discogs spells it on your shelf when it does ("Post-Punk"), else tidied. */
    fun styleLabel(style: String): String = spelling[style] ?: PRETTY[style]
        ?: label(style).replace(Regex("^Post "), "Post-").replace(Regex("^Lo Fi$"), "Lo-Fi")

    /** The name of a family ("indie") or a subgenre ("indie:shoegaze"). */
    fun labelOf(key: String): String = if (':' in key) styleLabel(key.substringAfter(':')) else label(key)

    /** Whether your record [c] is in the family or subgenre [key] (as [resolve] gives it). */
    fun owns(c: CollectionItem, key: String): Boolean {
        val p = discogs(c.styles, c.genres).map { it.first }
        return if (':' in key) p.any { "${it.first}:${it.second}" == key } else p.any { it.first == key }
    }

    companion object {
        /** How much each source weighs: the record itself, the artist's records on your shelf, the artist's and the album's Last.fm tags. */
        private const val RECORD_SHARE = 2.0
        private const val SHELF_SHARE = 1.0
        private const val TAGS_SHARE = 2.0
        private const val ALBUM_TAGS_SHARE = 2.0

        /** An artist/album's other families show beside its main one from this share of the main one's weight (and this much of all). */
        private const val SHOWN = 0.25
        private const val SHOWN_MIN = 0.1

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

        /**
         * Words for a sound more than a genre ("psychedelic" funk, "experimental" jazz, a "piano" ballad): they count only
         * when nothing else on the record or among the tags names a genre, else they'd pull a jazz band into rock.
         */
        private val SOUNDS = setOf(
            "psychedelic", "psych", "experimental", "avant garde", "avantgarde", "progressive", "acoustic", "chill", "chillout",
            "chill out", "lounge", "minimal", "atmospheric", "piano", "vocal", "ballad", "beat", "abstract", "dance", "easy listening",
        )

        /** Names the plain capitalizing gets wrong. */
        private val PRETTY = mapOf(
            "post punk" to "Post-Punk", "post rock" to "Post-Rock", "post hardcore" to "Post-Hardcore", "lo fi" to "Lo-Fi",
            "trip hop" to "Trip Hop", "synth pop" to "Synth-pop", "electro pop" to "Electropop", "k pop" to "K-Pop", "j pop" to "J-Pop",
            "post punk revival" to "Post-Punk Revival", "dance punk" to "Dance-Punk", "art punk" to "Art Punk", "idm" to "IDM",
            "edm" to "EDM", "uk garage" to "UK Garage", "ebm" to "EBM", "new wave" to "New Wave", "mpb" to "MPB", "aor" to "AOR",
            "singer songwriter" to "Singer-Songwriter", "neo soul" to "Neo-Soul", "nu disco" to "Nu-Disco", "alt country" to "Alt-Country",
            "hard bop" to "Hard Bop", "post bop" to "Post-Bop", "g funk" to "G-Funk", "hi nrg" to "Hi-NRG",
        )

        /**
         * Discogs' genres, by [key], with the families their styles may fall in: a style read as some other family
         * (its words mislead: "Psychedelic" is a Funk / Soul style too) goes in the first. From Discogs' own list of
         * styles per genre (see the tests).
         */
        private val DISCOGS = mapOf(
            "rock" to listOf("rock", "indie", "punk", "metal", "synth", "pop", "folk", "country", "ambient", "electronic", "reggae", "soul"),
            "electronic" to listOf("dance", "electronic", "ambient", "synth", "hiphop", "indie", "pop", "jazz", "classical", "reggae", "latin", "folk"),
            "funk soul" to listOf("soul", "rnb", "dance", "global"),
            "hip hop" to listOf("hiphop", "rnb", "electronic", "dance"),
            "jazz" to listOf("jazz", "latin", "global", "pop"),
            "blues" to listOf("blues"),
            "pop" to listOf("pop", "indie", "synth", "global"),
            "folk, world, & country" to listOf("global", "folk", "country", "latin", "classical", "soul"),
            "latin" to listOf("latin"),
            "reggae" to listOf("reggae"),
            "classical" to listOf("classical"),
            "stage & screen" to listOf("soundtrack"),
        )

        /** Discogs' genres for what isn't music (or no genre of it): their styles get no family. */
        private val NOT_MUSIC = setOf("non music", "children's", "brass & military")

        /**
         * The weighed (family, style) pairs of a Discogs record: each style read within the record's genres (a style no
         * word of which tells a family goes in the record's genre, when it has just one), then the genres at half weight.
         */
        fun discogs(styles: List<String>, genres: List<String>): List<Pair<Pair<String, String>, Double>> {
            val gk = genres.map { key(it) }
            val within = gk.mapNotNull { DISCOGS[it] }
            val music = gk.none { it in NOT_MUSIC }
            val named = styles.map { key(it) to family(it) }
            val plain = named.any { (k, f) -> f != null && k !in SOUNDS }
            val out = ArrayList<Pair<Pair<String, String>, Double>>()
            named.forEachIndexed { i, (k, f) ->
                if (plain && k in SOUNDS) return@forEachIndexed
                val fam = when {
                    within.isEmpty() -> f
                    f != null && within.any { f in it } -> f
                    f != null || (within.size == 1 && music) -> within.first().first()
                    else -> null
                } ?: return@forEachIndexed
                out += (fam to k) to maxOf(0.7, 1.0 - 0.05 * i)
            }
            gk.forEach { k -> family(k)?.let { out += (it to k) to 0.5 } }
            return out
        }

        /** The weighed (family, tag) pairs of an artist's Last.fm tags (tag, weight 0..100). */
        private fun tagVotes(tags: List<Pair<String, Int>>): List<Pair<Pair<String, String>, Double>> {
            val named = tags.mapNotNull { (k, w) -> family(k)?.let { Triple(it, k, w) } }
            val plain = named.any { it.second !in SOUNDS }
            return named.filter { !plain || it.second !in SOUNDS }.map { (f, k, w) -> (f to k) to w / 100.0 }
        }

        /** [pairs] merged and scaled to sum 1; null if there are none. */
        private fun votes(pairs: List<Pair<Pair<String, String>, Double>>): Map<Pair<String, String>, Double>? {
            val m = LinkedHashMap<Pair<String, String>, Double>()
            pairs.forEach { (p, w) -> m.merge(p, w, Double::plus) }
            val sum = m.values.sum()
            if (sum <= 0.0) return null
            for (e in m.entries) e.setValue(e.value / sum)
            return m
        }

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
            "deutsch rap" to "deutschrap", "brit pop" to "britpop", "powerviolence" to "power violence", "postbop" to "post bop",
            "hardbop" to "hard bop", "gfunk" to "g funk", "hinrg" to "hi nrg",
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
            "crank wave" to "punk", "no wave" to "punk", "gothic rock" to "punk", "goth" to "punk", "gothic" to "punk", "deathrock" to "punk",
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
            // Discogs' own styles whose words mislead or tell nothing (all of them are checked in the tests).
            "boogie woogie" to "blues", "fusion" to "jazz", "modal" to "jazz", "free improvisation" to "jazz",
            "swingbeat" to "rnb", "rnb swing" to "rnb", "g funk" to "hiphop", "bounce" to "hiphop", "crunk" to "hiphop",
            "gangsta" to "hiphop", "horrorcore" to "hiphop", "miami bass" to "hiphop", "screw" to "hiphop", "chopped and screwed" to "hiphop",
            "turntablism" to "hiphop", "conscious" to "hiphop", "britcore" to "hiphop", "cut up dj" to "hiphop", "dj battle tool" to "hiphop",
            "breaks" to "electronic", "progressive breaks" to "electronic", "broken beat" to "electronic", "berlin school" to "electronic",
            "illbient" to "electronic", "rhythmic noise" to "electronic", "italodance" to "dance", "dungeon synth" to "ambient",
            "hi nrg" to "dance", "hands up" to "dance", "jumpstyle" to "dance", "makina" to "dance", "schranz" to "dance",
            "speedcore" to "dance", "new beat" to "dance", "bassline" to "dance",
            "symphonic rock" to "rock", "psychobilly" to "rock", "crust" to "punk", "melodic hardcore" to "punk", "power violence" to "punk",
            "goregrind" to "metal", "pornogrind" to "metal", "noisecore" to "metal", "thrash" to "metal", "ye ye" to "pop",
            "bolero" to "latin", "mambo" to "latin", "cha cha" to "latin", "son montuno" to "latin", "descarga" to "latin",
            "boogaloo" to "latin", "rumba" to "latin", "guaguanco" to "latin", "guajira" to "latin", "guaracha" to "latin",
            "pachanga" to "latin", "charanga" to "latin", "porro" to "latin", "compas" to "latin", "baiao" to "latin",
            "batucada" to "latin", "beguine" to "latin", "cubano" to "latin", "nueva cancion" to "latin",
            "hillbilly" to "country", "cajun" to "country", "nordic" to "folk", "laiko" to "global", "romani" to "global",
            "sega" to "global", "entekhno" to "global", "pacific" to "global",
            "kayokyoku" to "pop", "light music" to "pop", "music hall" to "pop", "novelty" to "pop", "calypso" to "reggae", "soca" to "reggae",
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
            "jazz" to Regex("jazz|\\bswing\\b|\\bbop\\b|bebop|big band|dixieland|ragtime"),
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

        /** Whether a key of kept tags is an album's ([Text.albumKey], with its "|") rather than an artist's. */
        fun isAlbumKey(k: String) = '|' in k

        /** The mark before Last.fm tags kept with their weights; older versions kept three, without. */
        private const val TAGS_V = "v2"
        private const val TAGS_MAX = 10
        private const val TAG_MIN = 10

        /**
         * An artist's or album's Last.fm tags (name, weight 0..100, best first) as they are kept: the genre-like ones, up to
         * [TAGS_MAX] of them, each as "weight:tag" after the mark [TAGS_V]. Tags that are just the artist's name or the
         * album's [title] say nothing of the music.
         */
        fun fromTags(tags: List<Pair<String, Int>>, artist: String, title: String = ""): List<String> {
            val self = setOf(key(artist), key(title), key(Text.normLoose(title)))
            return listOf(TAGS_V) + tags.asSequence()
                .filter { it.second >= TAG_MIN }
                .map { key(it.first) to it.second }
                .filter { (k, _) -> k.length in 2..30 && k !in NOT_GENRES && !YEARS.matches(k) && k !in self }
                .distinctBy { it.first }
                .take(TAGS_MAX)
                .map { (k, w) -> "$w:$k" }
                .toList()
        }

        /** Whether kept tags are of this version (with weights): older ones are looked up again. */
        fun tagsCurrent(kept: List<String>) = kept.firstOrNull() == TAGS_V

        /** Kept tags back as (tag, weight); older ones, best first without weights, get falling ones. */
        fun readTags(kept: List<String>): List<Pair<String, Int>> =
            if (tagsCurrent(kept)) kept.drop(1).mapNotNull { e -> e.substringBefore(':').toIntOrNull()?.let { e.substringAfter(':') to it } }
            else kept.mapIndexed { i, k -> k to maxOf(TAG_MIN, 100 - 30 * i) }
    }
}
