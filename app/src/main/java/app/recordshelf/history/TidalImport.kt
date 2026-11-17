package app.recordshelf.history

import app.recordshelf.util.Text
import java.io.IOException
import java.io.Reader
import java.time.Instant

/**
 * A play taken from a music service's own export of your listening: when it began (Unix seconds), and whether
 * that time is only worked out ([est]: it was reported together with others, after listening offline).
 */
data class ImportedPlay(val ts: Long, val artist: String, val track: String, val album: String, val est: Boolean)

/**
 * TIDAL's listening history, as its data export gives it ("streaming.csv": a row per stream with the artist, the
 * song, the album, when the stream was reported and how long it ran). Read in two goes over the file, so a history
 * of any size takes little memory: first how long each song is ([lengths]), then the plays ([plays]).
 *
 * Only the columns named here are looked at; the export also carries the address and the place of every stream.
 */
object TidalImport {
    /** Shorter than this is no play, on Last.fm either. */
    const val MIN_SEC = 30

    /** Rows reported within this of the one before came in together: listened to offline, sent once back online. */
    private const val BATCH_MS = 2000L

    private val NEEDED = listOf("artist_name", "track_title", "entry_date", "stream_duration_ms")

    /** What an import found: the rows read, and why some are no plays. */
    class Summary(val rows: Int, val plays: Int, val short: Int, val videos: Int, val doubles: Int, val unreadable: Int, val estimated: Int, val first: Long, val last: Long)

    /** The file's records after its header, each as column name to value. Throws if it isn't TIDAL's streaming file. */
    private fun rows(reader: Reader): Sequence<Map<String, String>> = sequence {
        val csv = Csv(reader)
        val head = csv.next()?.map { it.trim().lowercase() } ?: throw IOException("The file is empty.")
        if (!head.containsAll(NEEDED)) throw IOException("This isn't TIDAL's streaming.csv: it has no ${NEEDED.first { it !in head }} column.")
        while (true) {
            val r = csv.next() ?: break
            if (r.size == 1 && r[0].isBlank()) continue
            yield(head.indices.associate { head[it] to r.getOrElse(it) { "" } })
        }
    }

    private fun text(v: String?) = v?.trim().orEmpty().let { if (it == "null") "" else it }
    private fun seconds(v: String?) = v?.trim()?.toDoubleOrNull()?.let { it / 1000.0 } ?: -1.0
    private fun millis(v: String?) = try {
        Instant.parse(v?.trim()).toEpochMilli()
    } catch (_: Exception) {
        -1L
    }

    /** Tells one song from another in the file: its artist and title as written, lowercased. */
    fun songKey(artist: String, track: String) = artist.lowercase() + "\u0000" + track.lowercase()

    /**
     * How long each song is, in seconds, from its streams: the length most of them ran (a song played through runs
     * its whole length every time), the longest if no two ran alike. The file itself doesn't say.
     */
    fun lengths(reader: Reader): Map<String, Int> {
        val seen = HashMap<String, MutableList<Int>>()
        for (r in rows(reader)) {
            val d = seconds(r["stream_duration_ms"])
            if (d < MIN_SEC) continue
            seen.getOrPut(songKey(text(r["artist_name"]), text(r["track_title"]))) { ArrayList(2) } += Math.round(d).toInt()
        }
        return seen.mapValues { (_, d) ->
            val most = d.groupingBy { it }.eachCount().maxWith(compareBy<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
            if (most.value > 1) most.key else d.max()
        }
    }

    /**
     * The plays in the file, oldest first, each handed to [emit]. A stream counts by Last.fm's rule: 30 seconds at
     * least, and half the song or four minutes ([lengths], or [known] seconds for a song this phone knows the
     * length of). Its time is when it began: the file has when it was reported, at its end. Streams reported
     * together (after listening offline) are laid end to end before that moment, which is as close as it gets.
     * Videos and rows written twice are left out.
     */
    fun plays(reader: Reader, lengths: Map<String, Int>, known: (artist: String, track: String) -> Int = { _, _ -> 0 }, emit: (ImportedPlay) -> Unit): Summary {
        var rows = 0
        var plays = 0
        var short = 0
        var videos = 0
        var doubles = 0
        var unreadable = 0
        var estimated = 0
        var first = 0L
        var last = 0L

        class Row(val artist: String, val track: String, val album: String, val at: Long, val sec: Double)

        val batch = ArrayList<Row>()
        fun flush() {
            if (batch.isEmpty()) return
            // The last one ended when they were reported; each one before it ended as the next began.
            var end = batch.last().at
            val starts = LongArray(batch.size)
            for (i in batch.indices.reversed()) {
                starts[i] = end - Math.round(batch[i].sec * 1000)
                end = starts[i]
            }
            for (i in batch.indices) {
                val r = batch[i]
                val len = known(r.artist, r.track).takeIf { it > 0 } ?: lengths[songKey(r.artist, r.track)] ?: 0
                if (r.sec < MIN_SEC || r.sec < minOf(240.0, len / 2.0)) {
                    short++
                    continue
                }
                val ts = starts[i] / 1000
                // Worked out, if that moved it by more than a minute from "reported at its end".
                val est = kotlin.math.abs(starts[i] - (r.at - Math.round(r.sec * 1000))) > 60_000
                emit(ImportedPlay(ts, r.artist, r.track, r.album, est))
                plays++
                if (est) estimated++
                if (first == 0L || ts < first) first = ts
                if (ts > last) last = ts
            }
            batch.clear()
        }

        var prevAt = -1L
        var prevSame = HashSet<String>()
        for (r in rows(reader)) {
            rows++
            val artist = text(r["artist_name"])
            val track = text(r["track_title"])
            val at = millis(r["entry_date"])
            val sec = seconds(r["stream_duration_ms"])
            if (artist.isEmpty() || track.isEmpty() || at < 0 || sec < 0) {
                unreadable++
                continue
            }
            if (text(r["product_type"]).equals("VIDEO", ignoreCase = true)) {
                videos++
                continue
            }
            // The same song at the very same moment is the same stream written twice.
            if (at != prevAt) prevSame = HashSet()
            if (!prevSame.add(songKey(artist, track))) {
                doubles++
                continue
            }
            if (prevAt < 0 || at - prevAt >= BATCH_MS || at < prevAt) flush()
            prevAt = at
            batch += Row(artist, track, text(r["album_title"]), at, sec)
        }
        flush()
        return Summary(rows, plays, short, videos, doubles, unreadable, estimated, first, last)
    }
}

/** Comma-separated records with quoted fields: commas, line breaks and doubled quotes may be inside the quotes. */
class Csv(reader: Reader) {
    private val r = if (reader is java.io.BufferedReader) reader else java.io.BufferedReader(reader, 1 shl 16)
    private val field = StringBuilder()
    private var done = false

    /** The next record's fields, or null at the end. */
    fun next(): List<String>? {
        if (done) return null
        val out = ArrayList<String>(16)
        field.setLength(0)
        var quoted = false
        var any = false
        while (true) {
            val c = r.read()
            if (c < 0) {
                done = true
                if (!any && out.isEmpty()) return null
                out += field.toString()
                return out
            }
            any = true
            val ch = c.toChar()
            if (quoted) {
                if (ch == '"') {
                    r.mark(1)
                    if (r.read() == '"'.code) field.append('"') else {
                        r.reset()
                        quoted = false
                    }
                } else field.append(ch)
            } else when (ch) {
                '"' -> if (field.isEmpty()) quoted = true else field.append(ch)
                ',' -> {
                    out += field.toString()
                    field.setLength(0)
                }
                '\n' -> {
                    out += field.toString()
                    return out
                }
                '\r' -> Unit
                else -> field.append(ch)
            }
        }
    }
}

/** Plays read one at a time, oldest first: [next] steps to one (false at the end), whose fields are then here. */
interface PlaySource {
    fun next(): Boolean
    val ts: Long
    val artist: String
    val track: String
    val album: String
}

/** Two sources as one, oldest first; of two plays at the same second, [a]'s comes first. */
fun mergePlays(a: PlaySource, b: PlaySource, fn: (ts: Long, artist: String, track: String, album: String) -> Unit) {
    var x = a.next()
    var y = b.next()
    while (x || y) {
        if (x && (!y || a.ts <= b.ts)) {
            fn(a.ts, a.artist, a.track, a.album)
            x = a.next()
        } else {
            fn(b.ts, b.artist, b.track, b.album)
            y = b.next()
        }
    }
}

/**
 * Which imported plays Last.fm has too, so they aren't counted twice. Fed Last.fm's scrobbles ([add], oldest
 * first), then asked about each imported play in turn ([has], oldest first): a scrobble of the same song close by
 * in time answers for one imported play, and for no other after it.
 */
class Overlap {
    companion object {
        /** A scrobble this close to an imported play is that play. */
        const val NEAR = 10 * 60L

        /** For a play whose time is only worked out (reported after listening offline): the same half day. */
        const val FAR = 12 * 3600L

        /** The same song under either service's naming: the title without its additions, and the first word of the artist. */
        fun key(artist: String, track: String): String {
            val a = Text.norm(artist).removePrefix("the ").substringBefore(' ')
            return Text.normLoose(track) + "\u0000" + a
        }
    }

    private class Times(var ts: LongArray = LongArray(4), var used: BooleanArray = BooleanArray(4), var n: Int = 0)

    private val songs = HashMap<String, Times>()

    fun add(ts: Long, artist: String, track: String) {
        val t = songs.getOrPut(key(artist, track)) { Times() }
        if (t.n == t.ts.size) {
            t.ts = t.ts.copyOf(t.n * 2)
            t.used = t.used.copyOf(t.n * 2)
        }
        t.ts[t.n++] = ts
    }

    /** Whether Last.fm has the imported play of [track] by [artist] begun at [ts] ([est]: that time is only worked out). */
    fun has(ts: Long, artist: String, track: String, est: Boolean): Boolean {
        val t = songs[key(artist, track)] ?: return false
        val reach = if (est) FAR else NEAR
        var best = -1
        var gap = Long.MAX_VALUE
        for (i in 0 until t.n) {
            val g = kotlin.math.abs(t.ts[i] - ts)
            if (!t.used[i] && g <= reach && g < gap) {
                best = i
                gap = g
            }
        }
        if (best < 0) return false
        t.used[best] = true
        return true
    }
}
