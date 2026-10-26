package app.recordshelf.history

import app.recordshelf.util.Text
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everyone on Last.fm, all time: how many people played an artist, album or song ([listeners])
 * and how often ([plays]). Looked up at [at] (ms); [listeners] 0 means Last.fm doesn't know it.
 */
data class Global(val listeners: Long, val plays: Long, val at: Long) {
    /** What the average listener played. A few heavy listeners pull this up, so most played less. */
    val perListener get() = if (listeners > 0) plays.toDouble() / listeners else 0.0
}

/** An artist's most played songs on Last.fm (title, everyone's plays), most played first, looked up at [at]. */
data class GlobalTop(val tracks: List<Pair<String, Long>>, val at: Long)

/** Something to look up on Last.fm: an artist's counts, a song's or an album's, or the artist's most played songs. */
data class GlobalAsk(val kind: Kind, val artist: String, val title: String = "") {
    enum class Kind { ARTIST, TRACK, ALBUM, TOP }
}

/** Last.fm's counts kept on the phone, by artist, album and song, and the top artists' most played songs. */
class Globals(val counts: Map<String, Global> = emptyMap(), val tops: Map<String, GlobalTop> = emptyMap()) {
    fun artist(name: String) = counts[artistKey(name)]?.takeIf { it.listeners > 0 && it.plays > 0 }
    fun track(artist: String, title: String) = counts[trackKey(artist, title)]?.takeIf { it.listeners > 0 && it.plays > 0 }
    fun album(artist: String, title: String) = counts[albumKey(artist, title)]?.takeIf { it.listeners > 0 && it.plays > 0 }
    fun top(artist: String) = tops[artistKey(artist)]?.tracks?.takeIf { it.isNotEmpty() }

    /** Whether [key] was looked up (found or not) less than [TTL] before [now]. */
    fun fresh(key: String, now: Long) = counts[key]?.let { now - it.at < TTL } ?: false
    fun freshTop(artist: String, now: Long) = tops[artistKey(artist)]?.let { now - it.at < TTL } ?: false

    fun toJson(): JSONObject {
        val c = JSONObject()
        for ((k, g) in counts) c.put(k, JSONArray().put(g.listeners).put(g.plays).put(g.at))
        val t = JSONObject()
        for ((k, top) in tops) t.put(k, JSONObject().put("at", top.at).put("t", JSONArray(top.tracks.map { JSONArray().put(it.first).put(it.second) })))
        return JSONObject().put("c", c).put("top", t)
    }

    companion object {
        /** Counts grow slowly: looked up again after a month. */
        const val TTL = 30L * 24 * 3600 * 1000

        fun artistKey(name: String) = "a|" + Text.norm(name)
        fun trackKey(artist: String, title: String) = "t|" + Text.norm(artist) + "|" + Text.norm(title)
        fun albumKey(artist: String, title: String) = "l|" + Text.albumKey(artist, title)

        fun fromJson(s: String?): Globals {
            if (s.isNullOrBlank()) return Globals()
            return try {
                val o = JSONObject(s)
                val counts = HashMap<String, Global>()
                o.optJSONObject("c")?.let { c ->
                    for (k in c.keys()) c.getJSONArray(k).let { counts[k] = Global(it.getLong(0), it.getLong(1), it.getLong(2)) }
                }
                val tops = HashMap<String, GlobalTop>()
                o.optJSONObject("top")?.let { t ->
                    for (k in t.keys()) {
                        val x = t.getJSONObject(k)
                        val list = x.getJSONArray("t")
                        tops[k] = GlobalTop((0 until list.length()).map { list.getJSONArray(it).let { p -> p.getString(0) to p.getLong(1) } }, x.getLong("at"))
                    }
                }
                Globals(counts, tops)
            } catch (_: Exception) {
                Globals()
            }
        }
    }
}
