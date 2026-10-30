package app.recordshelf.history

import app.recordshelf.util.Text

/**
 * The whole scrobble history in memory, as compact columns sorted by time, with artists,
 * tracks and albums numbered. Names are matched loosely ([Text.norm]), so "Motörhead" and
 * "Motorhead" are one artist; the name shown is the most recent spelling.
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

    /** Scrobbles left out because the user hid them ([Hidden]). */
    var hiddenPlays = 0
        private set

    /** The scrobbles [keep] picks (by index), as a history of their own: one genre's music, say. */
    fun filter(keep: (Int) -> Boolean): History {
        val b = Builder()
        for (i in 0 until size) if (keep(i)) b.add(ts[i], artistNames[artist[i]], trackTitles[track[i]], if (album[i] >= 0) albumTitles[album[i]] else "", if (also[i] >= 0) artistNames[also[i]] else "")
        return b.build().also { it.hiddenPlays = hiddenPlays }
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
        private fun norm(s: String) = norms.getOrPut(s) { Text.norm(s) }

        /** Adds one scrobble; oldest first is fastest, but any order works. [alsoName]: another artist credited on it. */
        fun add(t: Long, artistName: String, title: String, albumTitle: String, alsoName: String = "") {
            if (ts.size > 0 && t < ts[ts.size - 1]) sorted = false
            val aKey = norm(artistName)
            val a = artistIds.getOrPut(aKey) { artistNames += artistName; artistNames.size - 1 }
            artistNames[a] = artistName
            val tKey = aKey + "\u0000" + norm(title)
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
            return History(
                tsA, arA, trA, alA, artistNames.toTypedArray(), trackTitles.toTypedArray(), trackArtist.toArray(),
                albumTitles.toTypedArray(), albumArtist.toArray(), albumKeys.toTypedArray(), soA,
            )
        }
    }

    companion object {
        /**
         * Loads the store, counting features and collaborations for the main artist (see [Artists.main])
         * and leaving out what the user [hidden] (matched by the credited or the main artist).
         */
        fun from(store: HistoryStore, hidden: Hidden = Hidden.NONE): History {
            // Every credited name with its plays, and who plays on which album under their own name, so a
            // collaboration counts for the artist whose album it is on, else the one of them you play most.
            val raw = HashMap<String, Int>()
            val pairs = HashSet<String>()
            store.forEach { _, a, _, al ->
                raw.merge(a, 1, Int::plus)
                if (al.isNotBlank()) pairs += a + "\u0000" + al
            }
            val plays = HashMap<String, Int>()
            for ((a, n) in raw) plays.merge(Text.norm(a), n, Int::plus)
            val solo = plays.keys
            val onAlbum = HashMap<String, HashSet<String>>()
            for (p in pairs) {
                val k = p.indexOf('\u0000')
                onAlbum.getOrPut(Text.norm(p.substring(k + 1))) { HashSet() } += Text.norm(p.substring(0, k))
            }
            val credits = HashMap<String, Pair<String, String>>()
            val b = Builder()
            var skipped = 0
            // Nothing hidden (the usual case): no need to look each play up.
            val any = hidden.artists.isNotEmpty() || hidden.tracks.isNotEmpty()
            store.forEach { t, a, tr, al ->
                val c = credits.getOrPut(a + "\u0000" + al) {
                    val there = if (al.isBlank()) null else onAlbum[Text.norm(al)]
                    Artists.credit(a, solo, plays) { there != null && it in there }
                }
                val m = c.first
                if (any && (hidden.hides(m, tr) || hidden.hides(a, tr))) skipped++ else b.add(t, m, tr, al, c.second)
            }
            return b.build().also { it.hiddenPlays = skipped }
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
