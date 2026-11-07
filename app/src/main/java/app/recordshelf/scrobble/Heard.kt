package app.recordshelf.scrobble

import app.recordshelf.util.JsonFile
import app.recordshelf.util.Text
import app.recordshelf.util.objects
import app.recordshelf.util.str
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Where on the shelf a heard song is: the record, and the song's place on it. */
data class ShelfHit(val releaseId: Long, val album: String, val artist: String, val cover: String, val index: Int, val side: String) {
    fun toJson(): JSONObject = JSONObject().put("release", releaseId).put("album", album).put("artist", artist).put("cover", cover)
        .put("i", index).put("side", side)

    companion object {
        fun fromJson(o: JSONObject?) = o?.let { ShelfHit(it.optLong("release"), it.str("album"), it.str("artist"), it.str("cover"), it.optInt("i"), it.str("side")) }
    }
}

/**
 * A song recognised around the phone, by Pixel's Now Playing ([src] "nowplaying") or by Identify ("identify").
 * It waits as [NEW] until you say yes ([SCROBBLED]) or no ([DROPPED]); [VINYL] means the record it is on was
 * scrobbled from it. Heard from [firstAt] to [lastAt]: Now Playing tells again every minute or so while the song goes on.
 */
class HeardSong(
    val id: String,
    val artist: String,
    val title: String,
    var album: String,
    val firstAt: Long,
    var lastAt: Long,
    val src: String,
    var status: String = NEW,
    var cover: String = "",
    var shelf: ShelfHit? = null,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("artist", artist).put("title", title).put("album", album)
        .put("first", firstAt).put("last", lastAt).put("src", src).put("status", status).put("cover", cover)
        .put("shelf", shelf?.toJson() ?: JSONObject.NULL)

    companion object {
        const val NEW = "new"
        const val SCROBBLED = "scrobbled"
        const val DROPPED = "dropped"
        const val VINYL = "vinyl"

        fun fromJson(o: JSONObject) = HeardSong(
            o.str("id"), o.str("artist"), o.str("title"), o.str("album"), o.optLong("first"), o.optLong("last"), o.str("src"),
            o.str("status").ifEmpty { NEW }, o.str("cover"), ShelfHit.fromJson(o.optJSONObject("shelf")),
        )
    }
}

object Heard {
    /** The apps that are Pixel's Now Playing: Android System Intelligence (and its older name), the 2026 app, and its port to other phones. */
    val NOW_PLAYING_APPS = setOf(
        "com.google.android.as",
        "com.google.intelligence.sense",
        "com.google.android.apps.pixel.nowplaying",
        "com.kieronquinn.app.pixelambientmusic",
    )

    /** Android System Intelligence posts many other things; Now Playing's songs come on this channel. */
    fun isNowPlaying(pkg: String, channel: String): Boolean = when (pkg) {
        "com.google.android.as", "com.google.intelligence.sense" -> channel.contains("ambientmusic", ignoreCase = true)
        else -> pkg in NOW_PLAYING_APPS
    }

    /** How Now Playing words a song when its own words can't be read: English, then German. */
    val FALLBACK_FORMATS = listOf("%1\$s by %2\$s", "%1\$s von %2\$s")

    /** The same song heard again within this time is the same listen. */
    const val SAME_LISTEN_MS = 10 * 60_000L

    /** How long a song waits for a yes or no: Last.fm takes no scrobbles older than two weeks. */
    const val KEEP_MS = 14 * 86_400_000L

    /** Now Playing usually knows a song some 20 seconds in. */
    const val LAG_MS = 20_000L

    private val slot = Regex("%(?:(\\d)\\$)?s")

    /**
     * Every way [text] reads as (artist, title) in [format] (Now Playing's "%1$s by %2$s", the title being 1 and the
     * artist 2, in whatever order and words the language has). "Stand by Me by Ben E. King" reads two ways.
     */
    fun readings(text: String, format: String): List<Pair<String, String>> {
        val slots = slot.findAll(format).toList()
        if (slots.size != 2) return emptyList()
        val firstIsTitle = (slots[0].groupValues[1].ifEmpty { "1" }) == "1"
        val prefix = format.substring(0, slots[0].range.first)
        val middle = format.substring(slots[0].range.last + 1, slots[1].range.first)
        val suffix = format.substring(slots[1].range.last + 1)
        val t = text.trim()
        if (middle.isEmpty() || !t.startsWith(prefix) || !t.endsWith(suffix) || t.length < prefix.length + suffix.length) return emptyList()
        val inner = t.substring(prefix.length, t.length - suffix.length)
        val out = ArrayList<Pair<String, String>>()
        var at = inner.indexOf(middle)
        while (at >= 0) {
            val a = inner.substring(0, at).trim()
            val b = inner.substring(at + middle.length).trim()
            if (a.isNotEmpty() && b.isNotEmpty()) out += if (firstIsTitle) b to a else a to b
            at = inner.indexOf(middle, at + 1)
        }
        return out
    }

    /**
     * The (artist, title) Now Playing's [text] names. [formats] in order of trust (its own words first); among several
     * readings, one naming an artist you know ([known]) wins, else the one with the shortest artist: a title has
     * " by " in it more often than a name does.
     */
    fun parse(text: String, formats: List<String>, known: (String) -> Boolean): Pair<String, String>? {
        val all = formats.flatMap { readings(text, it) }
        if (all.isEmpty()) return null
        return all.firstOrNull { known(it.first) } ?: readings(text, formats.first { readings(text, it).isNotEmpty() }).minByOrNull { it.first.length }
    }

    private fun who(name: String) = Text.norm(name).removePrefix("the ")

    /** Whether two credits name the same artist, a band's credit with a guest or "& The ..." counting as its first name. */
    fun sameArtist(a: String, b: String): Boolean {
        val x = who(a)
        val y = who(b)
        if (x.isEmpty() || y.isEmpty()) return false
        if (x == y) return true
        val lx = Text.leadArtist(a)?.let { who(it) }
        val ly = Text.leadArtist(b)?.let { who(it) }
        return lx == y || ly == x || (lx != null && lx == ly)
    }

    fun sameSong(a: String, b: String) = Text.normLoose(a) == Text.normLoose(b)

    /** The index of the song [title] on a tracklist ([titles]), or -1. */
    fun trackIndex(titles: List<String>, title: String): Int = titles.indexOfFirst { sameSong(it, title) }
}

/** The songs heard lately, newest last; kept for two weeks. */
class HeardLog(private val file: JsonFile, private val max: Int = 300) {
    private val songs = ArrayList<HeardSong>()

    init {
        try {
            file.read()?.let { t -> songs += JSONArray(t).objects().map { HeardSong.fromJson(it) } }
        } catch (_: Exception) {
        }
    }

    /**
     * Notes [artist]'s [title] heard at [at]. Heard again within [Heard.SAME_LISTEN_MS] of the last time, it is the
     * same listen: its time stretches, and a yes or no given already stands. Returns the song and whether it is new.
     */
    @Synchronized
    fun add(artist: String, title: String, album: String, at: Long, src: String): Pair<HeardSong, Boolean> {
        prune(at)
        val same = songs.lastOrNull { Heard.sameArtist(it.artist, artist) && Heard.sameSong(it.title, title) && at - it.lastAt in -Heard.SAME_LISTEN_MS..Heard.SAME_LISTEN_MS }
        if (same != null) {
            same.lastAt = maxOf(same.lastAt, at)
            if (same.album.isEmpty()) same.album = album
            return same to false
        }
        val s = HeardSong(UUID.randomUUID().toString(), artist, title, album, at, at, src)
        songs += s
        while (songs.size > max) songs.removeAt(0)
        return s to true
    }

    @Synchronized
    fun get(id: String): HeardSong? = songs.firstOrNull { it.id == id }

    @Synchronized
    fun all(): List<HeardSong> = songs.toList()

    /** The songs still waiting for a yes or no. */
    @Synchronized
    fun waiting(now: Long = System.currentTimeMillis()): List<HeardSong> = songs.filter { it.status == HeardSong.NEW && now - it.firstAt < Heard.KEEP_MS }

    @Synchronized
    fun prune(now: Long) {
        songs.removeAll { now - it.lastAt > Heard.KEEP_MS }
    }

    @Synchronized
    fun save() = file.write(JSONArray().also { a -> songs.forEach { a.put(it.toJson()) } }.toString())

    @Synchronized
    fun toJson(now: Long = System.currentTimeMillis()): JSONArray = JSONArray().also { a -> waiting(now).forEach { a.put(it.toJson()) } }
}
