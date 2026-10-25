package com.example.recordshelf.discogs

import com.example.recordshelf.util.Text
import com.example.recordshelf.util.objects
import com.example.recordshelf.util.str
import org.json.JSONArray
import org.json.JSONObject

/** Discogs artist names as Last.fm knows them. */
object Names {
    private val number = Regex("\\s\\(\\d+\\)$")

    /** "Nirvana (2)" -> "Nirvana" (Discogs numbers artists that share a name). */
    fun clean(name: String): String = name.replace(number, "").trim()

    /** Joins an "artists" array with its join words: "A & B", "A Feat. B", "A, B". */
    fun join(artists: JSONArray?): String {
        val list = artists?.objects().orEmpty()
        val sb = StringBuilder()
        list.forEachIndexed { i, a ->
            val n = clean(a.str("name"))
            sb.append(if (n == "Various") "Various Artists" else n)
            if (i < list.size - 1) {
                val j = a.str("join").trim()
                sb.append(if (j.isEmpty() || j == ",") ", " else " $j ")
            }
        }
        return sb.toString()
    }
}

/** Turns a Discogs tracklist into playable tracks with sides. */
object Tracklist {
    private val lettered = Regex("^([A-Z]{1,2})(\\d.*)?$")
    private val disc = Regex("^(\\d+)[-.]\\d+")

    /** "A1" -> "A", "B" -> "B", "AA2" -> "AA", "2-3" -> "Disc 2", "7" -> "". */
    fun sideOf(pos: String): String {
        val p = pos.trim().uppercase()
        lettered.find(p)?.let { return it.groupValues[1] }
        disc.find(p)?.let { return "Disc " + it.groupValues[1] }
        return ""
    }

    fun parse(tracklist: JSONArray?, releaseArtist: String): List<Track> {
        val out = ArrayList<Track>()
        fun add(o: JSONObject, durOverride: Int = 0) {
            val title = o.str("title").trim()
            if (title.isEmpty()) return
            val pos = o.str("position").trim()
            val artist = Names.join(o.optJSONArray("artists")).ifEmpty { releaseArtist }
            val dur = Text.parseDuration(o.str("duration")).takeIf { it > 0 } ?: durOverride
            out += Track(pos, sideOf(pos), title, artist, dur, if (dur > 0) "discogs" else "")
        }
        for (o in tracklist?.objects().orEmpty()) {
            when (o.str("type_").ifEmpty { "track" }) {
                "heading" -> Unit
                "index" -> {
                    // A medley or suite. With its own position it is one track (as streaming
                    // services list it); otherwise its parts are the tracks.
                    val subs = o.optJSONArray("sub_tracks")?.objects().orEmpty().filter { it.str("type_").ifEmpty { "track" } == "track" }
                    if (o.str("position").isNotEmpty() || subs.isEmpty()) add(o, subs.sumOf { Text.parseDuration(it.str("duration")) })
                    else subs.forEach { add(it) }
                }
                else -> add(o)
            }
        }
        return out
    }
}
