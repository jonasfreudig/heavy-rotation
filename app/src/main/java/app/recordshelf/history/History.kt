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
) {
    val size get() = ts.size

    /** Scrobbles left out because the user hid them ([Hidden]). */
    var hiddenPlays = 0
        private set

    /** The scrobbles [keep] picks (by index), as a history of their own: one genre's music, say. */
    fun filter(keep: (Int) -> Boolean): History {
        val b = Builder()
        for (i in 0 until size) if (keep(i)) b.add(ts[i], artistNames[artist[i]], trackTitles[track[i]], if (album[i] >= 0) albumTitles[album[i]] else "")
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

        /** Adds one scrobble; oldest first is fastest, but any order works. */
        fun add(t: Long, artistName: String, title: String, albumTitle: String) {
            if (ts.size > 0 && t < ts[ts.size - 1]) sorted = false
            val aKey = Text.norm(artistName)
            val a = artistIds.getOrPut(aKey) { artistNames += artistName; artistNames.size - 1 }
            artistNames[a] = artistName
            val tKey = aKey + "\u0000" + Text.norm(title)
            val tr = trackIds.getOrPut(tKey) { trackTitles += title; trackArtist.add(a); trackTitles.size - 1 }
            trackTitles[tr] = title
            var al = -1
            if (albumTitle.isNotBlank()) {
                val k = Text.albumKey(artistName, albumTitle)
                al = albumIds.getOrPut(k) { albumTitles += albumTitle; albumArtist.add(a); albumKeys += k; albumTitles.size - 1 }
                albumTitles[al] = albumTitle
            }
            ts.add(t)
            artist.add(a)
            track.add(tr)
            album.add(al)
        }

        fun build(): History {
            var tsA = ts.toArray()
            var arA = artist.toArray()
            var trA = track.toArray()
            var alA = album.toArray()
            if (!sorted) {
                val order = tsA.indices.sortedBy { tsA[it] }
                tsA = LongArray(order.size) { tsA[order[it]] }
                arA = IntArray(order.size) { arA[order[it]] }
                trA = IntArray(order.size) { trA[order[it]] }
                alA = IntArray(order.size) { alA[order[it]] }
            }
            return History(
                tsA, arA, trA, alA, artistNames.toTypedArray(), trackTitles.toTypedArray(), trackArtist.toArray(),
                albumTitles.toTypedArray(), albumArtist.toArray(), albumKeys.toTypedArray(),
            )
        }
    }

    companion object {
        /**
         * Loads the store, counting features and collaborations for the main artist (see [Artists.main])
         * and leaving out what the user [hidden] (matched by the credited or the main artist).
         */
        fun from(store: HistoryStore, hidden: Hidden = Hidden.NONE): History {
            val raw = HashSet<String>()
            store.forEach { _, a, _, _ -> raw += a }
            val solo = raw.mapTo(HashSet()) { Text.norm(it) }
            val main = HashMap<String, String>()
            val b = Builder()
            var skipped = 0
            // Nothing hidden (the usual case): no need to look each play up.
            val any = hidden.artists.isNotEmpty() || hidden.tracks.isNotEmpty()
            store.forEach { t, a, tr, al ->
                val m = main.getOrPut(a) { Artists.main(a, solo) }
                if (any && (hidden.hides(m, tr) || hidden.hides(a, tr))) skipped++ else b.add(t, m, tr, al)
            }
            return b.build().also { it.hiddenPlays = skipped }
        }
    }
}

/** Artist credits as they come from players: "A feat. B", "A, B", "A & B". */
object Artists {
    private val feat = Regex("\\s+\\(?(?:feat\\.?|ft\\.?|featuring)\\s+", RegexOption.IGNORE_CASE)

    /**
     * The artist a play counts for. "feat." credits always go to the first name. "A, B" and
     * "A & B" go to A only if A is also played on its own ([solo] holds normalized names),
     * so bands like "Earth, Wind & Fire" or "Simon & Garfunkel" stay whole.
     */
    fun main(raw: String, solo: Set<String>): String {
        feat.find(raw)?.let { m ->
            val head = raw.substring(0, m.range.first).trim()
            if (head.isNotEmpty()) return main(head, solo)
        }
        for (sep in listOf(", ", " & ")) {
            val i = raw.indexOf(sep)
            if (i > 0) {
                val head = raw.substring(0, i).trim()
                if (Text.norm(head) in solo) return head
            }
        }
        return raw
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
