package app.recordshelf.history

import app.recordshelf.util.Text

/**
 * The whole scrobble history in memory, as compact columns sorted by time, with artists,
 * tracks and albums numbered. Names are matched loosely ([Text.norm]), so "Motörhead" and
 * "Motorhead" are one artist; the name shown is the most recent spelling. A song is one song
 * with and without the guest its title names ([Text.songKey]), and an artist played under two
 * names is one artist ([sameAs]).
 */
class History private constructor(
    /** Unix seconds, ascending. */
    val ts: LongArray,
    val artist: IntArray,
    val track: IntArray,
    /** -1 when the scrobble has no album. */
    val album: IntArray,
    val artistNames: Array<String>,
    val trackTitles: Array<String>,
    val trackArtist: IntArray,
    val albumTitles: Array<String>,
    val albumArtist: IntArray,
    val albumKeys: Array<String>,
    /**
     * The other artist credited on a play, -1 for none: ROSALÍA on Fontaines D.C.'s "Where Is Gone?". Their page and the
     * top artist lists count the play for them too; everything that splits your plays between artists uses [artist].
     */
    val also: IntArray,
) {
    val size get() = ts.size

    /**
     * The album keys of longer editions, and of singles all of whose songs count for an album, with that album's key
     * ("…|skinty fia go deo" or "…|quiet life", "…|cutthroat").
     */
    var editionOf: Map<String, String> = emptyMap()
        private set

    /**
     * Singles and EPs whose songs count for the album that has them too (see Builder.shortReleases), with all their
     * own plays: a discography shows those on the single's card.
     */
    var singlePlays: Map<String, Int> = emptyMap()
        private set

    /** Scrobbles left out because the user hid them ([Hidden]). */
    var hiddenPlays = 0
        private set

    /**
     * The names that count for an artist played under another name too, each (normalized) with that artist's name:
     * a band that renamed itself ("slaves" to Soft Play) or is spelled two ways. See [Artists.sameArtists].
     */
    var sameAs: Map<String, String> = emptyMap()
        private set

    /** The scrobbles [keep] picks (by index), as a history of their own: one genre's music, say. */
    fun filter(keep: (Int) -> Boolean): History {
        val b = Builder()
        for (i in 0 until size) if (keep(i)) b.add(ts[i], artistNames[artist[i]], trackTitles[track[i]], if (album[i] >= 0) albumTitles[album[i]] else "", if (also[i] >= 0) artistNames[also[i]] else "")
        return b.build().also { it.hiddenPlays = hiddenPlays; it.editionOf = editionOf + it.editionOf; it.singlePlays = singlePlays + it.singlePlays; it.sameAs = sameAs }
    }

    /** First index with ts >= [t]. */
    fun lowerBound(t: Long): Int {
        var lo = 0
        var hi = ts.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (ts[mid] < t) lo = mid + 1 else hi = mid
        }
        return lo
    }

    class Builder {
        private val ts = LongArrayList()
        private val artist = IntArrayList()
        private val track = IntArrayList()
        private val album = IntArrayList()
        private val also = IntArrayList()
        private val artistIds = HashMap<String, Int>()
        private val artistNames = ArrayList<String>()
        private val trackIds = HashMap<String, Int>()
        private val trackTitles = ArrayList<String>()
        private val trackArtist = IntArrayList()
        private val albumIds = HashMap<String, Int>()
        private val albumTitles = ArrayList<String>()
        private val albumArtist = IntArrayList()
        private val albumKeys = ArrayList<String>()
        private var sorted = true

        // Names normalized once per spelling: a long history repeats the same few thousand names
        // tens of thousands of times, and normalizing (accents, punctuation) is the slow part.
        private val norms = HashMap<String, String>()
        private val albumKeyOf = HashMap<String, String>()
        private val songKeys = HashMap<String, String>()
        private fun norm(s: String) = norms.getOrPut(s) { Text.norm(s) }

        /** Adds one scrobble; oldest first is fastest, but any order works. [alsoName]: another artist credited on it. */
        fun add(t: Long, artistName: String, title: String, albumTitle: String, alsoName: String = "") {
            if (ts.size > 0 && t < ts[ts.size - 1]) sorted = false
            val aKey = norm(artistName)
            val a = artistIds.getOrPut(aKey) { artistNames += artistName; artistNames.size - 1 }
            artistNames[a] = artistName
            // "Song" and "Song (feat. Someone)" are one song: a player names the guest on one release and not on another.
            val tKey = aKey + "\u0000" + songKeys.getOrPut(title) { Text.songKey(title) }
            val tr = trackIds.getOrPut(tKey) { trackTitles += title; trackArtist.add(a); trackTitles.size - 1 }
            trackTitles[tr] = title
            var al = -1
            if (albumTitle.isNotBlank()) {
                val k = albumKeyOf.getOrPut(artistName + "\u0000" + albumTitle) { Text.albumKey(artistName, albumTitle) }
                al = albumIds.getOrPut(k) { albumTitles += albumTitle; albumArtist.add(a); albumKeys += k; albumTitles.size - 1 }
                albumTitles[al] = albumTitle
            }
            ts.add(t)
            artist.add(a)
            track.add(tr)
            album.add(al)
            val other = if (alsoName.isBlank()) -1 else norm(alsoName).let { k -> if (k == aKey) -1 else artistIds.getOrPut(k) { artistNames += alsoName; artistNames.size - 1 } }
            also.add(other)
        }

        fun build(): History {
            var tsA = ts.toArray()
            var arA = artist.toArray()
            var trA = track.toArray()
            var alA = album.toArray()
            var soA = also.toArray()
            if (!sorted) {
                val order = tsA.indices.sortedBy { tsA[it] }
                tsA = LongArray(order.size) { tsA[order[it]] }
                arA = IntArray(order.size) { arA[order[it]] }
                trA = IntArray(order.size) { trA[order[it]] }
                alA = IntArray(order.size) { alA[order[it]] }
                soA = IntArray(order.size) { soA[order[it]] }
            }
            val into = editions(alA, trA)
            val single = shortReleases(alA, trA, into)
            if (into.indices.all { into[it] == it }) {
                return History(
                    tsA, arA, trA, alA, artistNames.toTypedArray(), trackTitles.toTypedArray(), trackArtist.toArray(),
                    albumTitles.toTypedArray(), albumArtist.toArray(), albumKeys.toTypedArray(), soA,
                ).also { it.singlePlays = single }
            }
            // The editions go; every other album gets its new number, and the editions' plays the album's.
            val kept = into.indices.filter { into[it] == it }
            val newId = IntArray(into.size).also { n -> kept.forEachIndexed { k, x -> n[x] = k } }
            for (i in alA.indices) if (alA[i] >= 0) alA[i] = newId[into[alA[i]]]
            val artistA = albumArtist.toArray()
            return History(
                tsA, arA, trA, alA, artistNames.toTypedArray(), trackTitles.toTypedArray(), trackArtist.toArray(),
                Array(kept.size) { albumTitles[kept[it]] }, IntArray(kept.size) { artistA[kept[it]] }, Array(kept.size) { albumKeys[kept[it]] }, soA,
            ).also { h ->
                h.editionOf = into.indices.filter { into[it] != it }.associate { albumKeys[it] to albumKeys[into[it]] }
                h.singlePlays = single
            }
        }

        /**
         * A song counts for the album it's on, not for a single or EP that has it too: "More" for Sorry's 925, not the
         * single "More"; "Quiet Life" for shame's Cutthroat. A short release (at most [SHORT] songs played) gives its
         * plays of a song to the same artist's album you played that very song from too (at least [BIG] songs played,
         * the one with the most if several; never a best-of or live album). Songs only the short release has stay with
         * it, and a release left with none counts as that album, like an edition. Changes [alA] and [into]; returns
         * each release that gave songs away with all its own plays, by key (for its card in a discography).
         */
        private fun shortReleases(alA: IntArray, trA: IntArray, into: IntArray): Map<String, Int> {
            val songs = HashMap<Int, HashSet<Int>>()
            for (i in alA.indices) if (alA[i] >= 0) songs.getOrPut(into[alA[i]]) { HashSet() } += trA[i]
            val who = { a: Int -> albumKeys[a].substringBefore('|') }
            // Each song, with the albums (not best-ofs or live ones) it was played from.
            val albumsWith = HashMap<Int, MutableList<Int>>()
            for ((a, s) in songs) if (s.size >= BIG && !NOT_FOR_SINGLES.containsMatchIn(albumTitles[a])) for (t in s) albumsWith.getOrPut(t) { ArrayList() } += a
            if (albumsWith.isEmpty()) return emptyMap()
            // Each short release's songs that have an album: (release, song) to that album.
            val home = HashMap<Long, Int>()
            for ((r, s) in songs) {
                if (s.size > SHORT) continue
                for (t in s) {
                    val a = albumsWith[t]?.filter { who(it) == who(r) }?.maxByOrNull { songs.getValue(it).size } ?: continue
                    home[(r.toLong() shl 32) or t.toLong()] = a
                }
            }
            if (home.isEmpty()) return emptyMap()
            val gave = home.keys.mapTo(HashSet()) { (it shr 32).toInt() }
            val own = HashMap<Int, Int>()
            val kept = HashMap<Int, Int>()
            val went = HashMap<Int, HashMap<Int, Int>>()
            for (i in alA.indices) {
                if (alA[i] < 0) continue
                val r = into[alA[i]]
                if (r !in gave) continue
                own.merge(r, 1, Int::plus)
                val a = home[(r.toLong() shl 32) or trA[i].toLong()]
                if (a == null) { kept.merge(r, 1, Int::plus); continue }
                alA[i] = a
                went.getOrPut(r) { HashMap() }.merge(a, 1, Int::plus)
            }
            // A release with nothing of its own left: its name finds the album most of it went to.
            for (r in gave) if ((kept[r] ?: 0) == 0) {
                val a = went.getValue(r).maxBy { it.value }.key
                for (x in into.indices) if (into[x] == r) into[x] = a
            }
            return gave.associate { albumKeys[it] to (own[it] ?: 0) }
        }

        /**
         * Longer editions counted as the album they extend: the same artist's album with its title and more words
         * ("Skinty Fia go deo", "Skinty Fia"), when at least half the songs you played from the smaller of the two you
         * played from the other too, extra versions ("Jackie Down the Line (Live)") as their song. Albums whose titles
         * only start alike (Led Zeppelin II) share no songs and stay apart. Each album's number, or the one it counts as.
         */
        private fun editions(alA: IntArray, trA: IntArray): IntArray {
            val into = IntArray(albumKeys.size) { it }
            // The albums whose title starts with another one's whole title: the shortest such first.
            val pairs = albumKeys.indices.mapNotNull { b ->
                val k = albumKeys[b]
                val from = k.indexOf('|') + 1
                val shorter = generateSequence(k.indexOf(' ', from)) { k.indexOf(' ', it + 1).takeIf { n -> n > 0 } }
                    .takeWhile { it > 0 }.mapNotNull { albumIds[k.substring(0, it)] }.toList()
                if (shorter.isEmpty()) null else b to shorter
            }
            if (pairs.isEmpty()) return into
            val wanted = HashSet<Int>().also { w -> pairs.forEach { (b, s) -> w += b; w += s } }
            val loose = HashMap<Int, String>()
            val songs = HashMap<Int, HashSet<String>>()
            for (i in alA.indices) {
                val al = alA[i]
                if (al < 0 || al !in wanted) continue
                songs.getOrPut(al) { HashSet() } += loose.getOrPut(trA[i]) { Text.normLoose(trackTitles[trA[i]]) }
            }
            for ((b, shorter) in pairs.sortedBy { albumKeys[it.first].length }) {
                val sb = songs[b] ?: continue
                for (a in shorter) {
                    val sa = songs[a] ?: continue
                    val shared = sb.count { it in sa }
                    if (shared > 0 && shared * 2 >= minOf(sa.size, sb.size)) {
                        into[b] = into[a]
                        break
                    }
                }
            }
            return into
        }
    }

    companion object {
        /** A single or EP: at most this many songs played from it. An album: at least [BIG]. See shortReleases. */
        private const val SHORT = 5
        private const val BIG = 6

        /** Albums a single doesn't count as: best-ofs, collections and live recordings ("Live Through This" isn't one). */
        private val NOT_FOR_SINGLES = Regex("""\b(best of|greatest hits|the hits|collection|anthology|essentials?|singles|b-sides|rarities|live (at|in|from)|unplugged|remix(es|ed))\b|\(live\)|\blive$""", RegexOption.IGNORE_CASE)

        /**
         * Loads the store, counting features and collaborations for the main artist (see [Artists.main])
         * and leaving out what the user [hidden] (matched by the credited or the main artist).
         */
        fun from(store: HistoryStore, hidden: Hidden = Hidden.NONE): History {
            // Every credited name with its plays, and who plays on which album under their own name, so a
            // collaboration counts for the artist whose album it is on, else the one of them you play most.
            // Also each credited name's albums with the songs played from them (name, NUL, album), for telling a
            // name that is another artist's (see [Artists.sameArtists]).
            val raw = HashMap<String, Artists.Seen>()
            val songsOn = HashMap<String, HashSet<String>>()
            store.forEach { t, a, tr, al ->
                raw.getOrPut(a) { Artists.Seen() }.add(t)
                if (al.isNotBlank()) songsOn.getOrPut(a + "\u0000" + al) { HashSet() } += tr
            }
            val plays = HashMap<String, Int>()
            for ((a, s) in raw) plays.merge(Text.norm(a), s.n, Int::plus)
            val solo = plays.keys
            val onAlbum = HashMap<String, HashSet<String>>()
            for (p in songsOn.keys) {
                val k = p.indexOf('\u0000')
                onAlbum.getOrPut(Text.norm(p.substring(k + 1))) { HashSet() } += Text.norm(p.substring(0, k))
            }
            val same = Artists.sameArtists(raw, songsOn, solo, plays, onAlbum)
            // A song by several artists counts for the one whose album it is on, on its single too: "The God of
            // Lying" by "Gorillaz, IDLES" is on a Gorillaz album, so its single isn't an IDLES song. Each such credit
            // and song (credit, NUL, song key) with the artist who has it on an album (normalized).
            val owners = HashMap<String, String>()
            for ((p, songs) in songsOn) {
                val k = p.indexOf('\u0000')
                val credit = p.substring(0, k)
                if (!Artists.several(credit)) continue
                val there = onAlbum[Text.norm(p.substring(k + 1))] ?: continue
                val owner = Artists.onTheirAlbum(credit, solo) { it in there } ?: continue
                for (s in songs) owners.merge(credit + "\u0000" + Text.songKey(s), owner) { x, y -> if ((plays[y] ?: 0) > (plays[x] ?: 0) || ((plays[y] ?: 0) == (plays[x] ?: 0) && y < x)) y else x }
            }
            val shared = owners.keys.mapTo(HashSet()) { it.substringBefore('\u0000') }
            val songKeys = HashMap<String, String>()
            val credits = HashMap<String, Pair<String, String>>()
            fun named(n: String) = if (n.isEmpty() || same.isEmpty()) n else same[Text.norm(n)] ?: n
            val b = Builder()
            var skipped = 0
            // Nothing hidden (the usual case): no need to look each play up.
            val any = hidden.artists.isNotEmpty() || hidden.tracks.isNotEmpty()
            store.forEach { t, a, tr, al ->
                val owner = if (a in shared) owners[a + "\u0000" + songKeys.getOrPut(tr) { Text.songKey(tr) }] else null
                val c = if (owner != null) {
                    credits.getOrPut(a + "\u0000\u0000" + owner) { Artists.credit(a, solo, plays) { it == owner }.let { (m, o) -> named(m) to named(o) } }
                } else {
                    credits.getOrPut(a + "\u0000" + al) {
                        val there = if (al.isBlank()) null else onAlbum[Text.norm(al)]
                        Artists.credit(a, solo, plays) { there != null && it in there }.let { (m, o) -> named(m) to named(o) }
                    }
                }
                val m = c.first
                if (any && (hidden.hides(m, tr) || hidden.hides(a, tr))) skipped++ else b.add(t, m, tr, al, c.second)
            }
            return b.build().also { it.hiddenPlays = skipped; it.sameAs = same }
        }
    }
}

/** Artist credits as they come from players: "A feat. B", "A, B", "A & B", "A and B", "A with B", "A x B", "A / B". */
object Artists {
    private val feat = Regex("\\s+\\(?(?:feat\\.?|ft\\.?|featuring)\\s+", RegexOption.IGNORE_CASE)

    /** What joins two names in a credit ("Fela Kuti and Africa 70", "A x B" for a collaboration). */
    private val SEPARATORS = listOf(", ", " & ", " and ", " with ", " x ", " / ", " + ", " vs. ", " vs ")

    /**
     * The artist a play counts for. "feat." credits always go to the first name. "A, B", "A & B",
     * "A and B" and the like go to whichever of the names is also played on its own ([solo] holds
     * normalized names), the most played one ([plays], by normalized name) when several are, else the
     * first: "ROSALÍA, Fontaines D.C." counts for Fontaines D.C. when you play them and not her. Bands
     * like "Earth, Wind & Fire" or "Simon and Garfunkel", none of whose parts plays alone, stay whole.
     */
    fun main(raw: String, solo: Set<String>, plays: Map<String, Int> = emptyMap()): String = credit(raw, solo, plays).first

    /** Whether a credit names several artists joined by a separator ("A, B", "A & B"; not "A feat. B", which always counts for A). */
    fun several(raw: String): Boolean = !feat.containsMatchIn(raw) && names(raw).size > 1

    /**
     * Of a credit naming several, the one (normalized) who plays under their own name on an album ([onAlbum], as in
     * [credit]): the first of them if several do. Null if none of them does.
     */
    fun onTheirAlbum(raw: String, solo: Set<String>, onAlbum: (String) -> Boolean): String? =
        names(raw).map { Text.norm(it) }.firstOrNull { it in solo && onAlbum(it) }

    /** How a credited name was played: how often, when first and last, and how often in each quarter of a year. */
    class Seen {
        var n = 0
            private set
        var first = Long.MAX_VALUE
            private set
        var last = Long.MIN_VALUE
            private set
        private var q0 = 0
        private var per = IntArray(0)

        /** One play at [t] (seconds). */
        fun add(t: Long) {
            n++
            if (t < first) first = t
            if (t > last) last = t
            val q = quarter(t)
            if (per.isEmpty()) q0 = q
            if (q < q0) {
                per = IntArray(q0 - q + per.size).also { per.copyInto(it, q0 - q) }
                q0 = q
            }
            if (q - q0 >= per.size) per = per.copyOf(q - q0 + 1)
            per[q - q0]++
        }

        /** The plays in the quarters before the one [t] is in. */
        fun before(t: Long): Int {
            val q = quarter(t)
            var sum = 0
            for (i in per.indices) if (q0 + i < q) sum += per[i]
            return sum
        }

        private fun quarter(t: Long) = Math.floorDiv(t, QUARTER).toInt()

        private companion object {
            const val QUARTER = 91L * 24 * 3600
        }
    }

    /** An album counts as shared by two names when this many of its songs were played under both. */
    private const val SAME_SONGS = 2

    /** Two names with nothing in common are one artist when they share this many albums. */
    private const val SAME_ALBUMS = 2

    /** An album title played under more names than this says nothing ("Greatest Hits"). */
    private const val MAX_SHARING = 6

    /**
     * The names that are another artist's, each (normalized) with the name its plays count for. Two names are one
     * artist when the same album with the same songs ([SAME_SONGS] of them) was played under both, and
     *  - one name is the other with words added (Damian Marley and Damian "Jr. Gong" Marley, Beginner and Absolute
     *    Beginner); between such names one song is enough where it is half of what one of them has of the album, or
     *  - they share [SAME_ALBUMS] such albums, which are at least half the albums of one of them, and one name follows
     *    the other in time (nine in ten plays under the older name came before the first under the newer): a band
     *    that renamed itself, as Slaves did to Soft Play. A streaming service renames all it has; the history keeps
     *    what it was called when played.
     * A cover of one album, a compilation both are on and two albums of the same title share no songs or too few
     * albums. A renamed band counts for its new name. Of two spellings it is the one naming the fewest artists
     * (Jah Sun, not "Jah Sun Meets House Of Riddim"), then the one played most. [seen]: every credited
     * name as it came; [songsOn]: each name's
     * albums (name, NUL, album) with the songs played from them. A credit of several artists says this of the one it
     * counts for ([credit] with [solo], [plays] and who plays under their own name on which album, [onAlbum]):
     * "Absolute Beginner feat. Samy Deluxe" of Absolute Beginner.
     */
    fun sameArtists(
        seen: Map<String, Seen>, songsOn: Map<String, Set<String>>, solo: Set<String>, plays: Map<String, Int> = emptyMap(),
        onAlbum: Map<String, Set<String>> = emptyMap(),
    ): Map<String, String> {
        // Each album title with the names it was played under and each one's songs of it; a name's albums.
        val byAlbum = HashMap<String, HashMap<String, HashSet<String>>>()
        for ((k, songs) in songsOn) {
            val cut = k.indexOf('\u0000')
            val album = k.substring(cut + 1)
            val there = onAlbum[Text.norm(album)]
            val name = credit(k.substring(0, cut), solo, plays) { there != null && it in there }.first
            byAlbum.getOrPut(Text.normLoose(album)) { HashMap() }.getOrPut(Text.norm(name)) { HashSet() } += songs
        }
        val shared = HashMap<Pair<String, String>, Int>()
        val albums = HashMap<String, Int>()
        for (names in byAlbum.values) {
            for ((n, s) in names) if (s.size >= SAME_SONGS) albums.merge(n, 1, Int::plus)
            if (names.size < 2 || names.size > MAX_SHARING) continue
            val keyed = names.mapValues { (_, s) -> s.mapTo(HashSet()) { Text.songKey(it) } }
            val list = keyed.keys.sorted()
            for (i in list.indices) for (j in i + 1 until list.size) {
                val mine = keyed.getValue(list[i])
                val other = keyed.getValue(list[j])
                val both = mine.count { it in other }
                if (both >= SAME_SONGS || (both > 0 && both * 2 >= minOf(mine.size, other.size) && alike(list[i], list[j]))) shared.merge(list[i] to list[j], 1, Int::plus)
            }
        }
        if (shared.isEmpty()) return emptyMap()
        // How each of those names (normalized) was played, over its spellings, and the spelling played last.
        val asked = shared.keys.flatMapTo(HashSet()) { listOf(it.first, it.second) }
        val first = HashMap<String, Long>()
        val last = HashMap<String, Long>()
        val shown = HashMap<String, String>()
        val spellings = HashMap<String, MutableList<Seen>>()
        for ((raw, s) in seen) {
            val name = main(raw, solo, plays)
            val n = Text.norm(name)
            if (n !in asked) continue
            first.merge(n, s.first) { a, b -> minOf(a, b) }
            if (s.last >= (last[n] ?: Long.MIN_VALUE)) {
                last[n] = s.last
                shown[n] = name
            }
            spellings.getOrPut(n) { ArrayList() } += s
        }
        fun follows(old: String, new: String): Boolean {
            val from = first[new] ?: return false
            val all = spellings[old] ?: return false
            return all.sumOf { it.before(from) } * 10 >= all.sumOf { it.n } * 9
        }
        val root = HashMap<String, String>()
        fun find(x: String): String {
            var r = x
            while (true) r = root[r] ?: return r
        }
        // The names a band left behind: never the one a group counts for, even when something still plays under it.
        val left = HashSet<String>()
        for ((p, n) in shared.entries.sortedBy { it.key.first + "\u0000" + it.key.second }) {
            val (x, y) = p
            if (!alike(x, y)) {
                val (old, new) = if ((first[x] ?: 0L) <= (first[y] ?: 0L)) x to y else y to x
                if (n < SAME_ALBUMS || n * 2 < minOf(albums[x] ?: 0, albums[y] ?: 0) || !follows(old, new)) continue
                left += old
            }
            val a = find(x)
            val b = find(y)
            if (a != b) root[a] = b
        }
        if (root.isEmpty()) return emptyMap()
        val groups = HashMap<String, HashSet<String>>()
        for (x in root.keys) {
            groups.getOrPut(find(x)) { HashSet() } += x
            groups.getValue(find(x)) += find(x)
        }
        val out = HashMap<String, String>()
        for (g in groups.values) {
            val top = g.filter { it !in left }.ifEmpty { g }
                .maxWith(compareBy<String> { -parts(shown[it] ?: it) }.thenBy { n -> spellings[n]?.sumOf { it.n } ?: 0 }.thenBy { last[it] ?: Long.MIN_VALUE }.thenBy { it })
            val name = shown[top] ?: continue
            for (x in g) if (x != top) out[x] = name
        }
        return out
    }

    /** Words that join two artists in a name without being one of the [SEPARATORS]. */
    private val JOINS = setOf("meets", "presents", "featuring", "feat", "ft")

    /** How many artists a name names: "A, B & C" three, "Jah Sun Meets House Of Riddim" two. */
    private fun parts(name: String): Int =
        names(name).size + Text.norm(name).split(' ').let { w -> if (w.size < 3) 0 else w.subList(1, w.size - 1).count { it in JOINS } }

    /** Whether one of two normalized names is the other with words added: "damian marley" and "damian jr gong marley". */
    private fun alike(a: String, b: String): Boolean {
        val x = a.split(' ')
        val y = b.split(' ')
        val (short, long) = if (x.size <= y.size) x to y else y to x
        return short.size < long.size && long.containsAll(short)
    }

    /** The names in a credit, split at every separator ("A, B & C" is three). */
    private fun names(raw: String): List<String> {
        var names = listOf(raw)
        for (sep in SEPARATORS) names = names.flatMap { it.split(sep, ignoreCase = true) }
        return names.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * Who a play counts for ([main], as above) and the other artist credited on it ("" for none), who gets it too where
     * only their own plays matter. [onAlbum]: whether a name (normalized) plays under its own name on this play's album;
     * that one is the main artist, as it is their record ("Where Is Gone?" on a Fontaines D.C. album, ROSALÍA on it).
     */
    fun credit(raw: String, solo: Set<String>, plays: Map<String, Int> = emptyMap(), onAlbum: (String) -> Boolean = { false }): Pair<String, String> {
        feat.find(raw)?.let { m ->
            val head = raw.substring(0, m.range.first).trim()
            if (head.isNotEmpty()) {
                val (main, also) = credit(head, solo, plays, onAlbum)
                val guest = names(raw.substring(m.range.last + 1).trim().removeSuffix(")")).firstOrNull().orEmpty()
                return main to also.ifEmpty { guest }
            }
        }
        val names = names(raw)
        if (names.size < 2) return raw to ""
        val known = names.withIndex().filter { Text.norm(it.value) in solo }
        if (known.isEmpty()) return raw to ""
        val main = known.firstOrNull { onAlbum(Text.norm(it.value)) }
            ?: known.maxWith(compareBy<IndexedValue<String>> { plays[Text.norm(it.value)] ?: 0 }.thenByDescending { it.index })
        return main.value to (names.withIndex().firstOrNull { it.index != main.index }?.value ?: "")
    }
}

/** Growable primitive arrays, so 100k+ scrobbles don't become 100k+ boxed objects. */
internal class LongArrayList {
    private var a = LongArray(1024)
    var size = 0
        private set

    fun add(v: Long) {
        if (size == a.size) a = a.copyOf(a.size * 2)
        a[size++] = v
    }

    operator fun get(i: Int) = a[i]
    fun toArray(): LongArray = a.copyOf(size)
}

internal class IntArrayList {
    private var a = IntArray(1024)
    var size = 0
        private set

    fun add(v: Int) {
        if (size == a.size) a = a.copyOf(a.size * 2)
        a[size++] = v
    }

    fun toArray(): IntArray = a.copyOf(size)
}
