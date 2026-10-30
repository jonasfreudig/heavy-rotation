package app.recordshelf.net

import app.recordshelf.util.Text
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlin.math.abs

/** A song's lyrics: plain text, timed lines in LRC ("[01:23.45] a line") when known, or instrumental. */
data class Lyrics(val plain: String, val synced: String, val instrumental: Boolean) {
    fun toJson(): JSONObject = JSONObject().put("plain", plain).put("synced", synced).put("instrumental", instrumental)
}

/**
 * Lyrics from LRCLIB (lrclib.net), an open lyrics database anyone can use: no account, no key.
 * Asks for the exact song first (artist, title, album, length), then searches by artist and title,
 * taking a version of about the same length, timed lines first.
 */
class LrclibClient(private val base: String = "https://lrclib.net/api") {
    companion object {
        /** A version this many seconds longer or shorter is another recording (a live one, an edit). */
        const val LENGTH_SLACK = 8.0
    }

    /**
     * The lyrics of a song, or null if LRCLIB has none; [durSec] is its length (0: unknown). The exact lookup can be
     * slow or fail while LRCLIB fetches from elsewhere, so a failed one goes on to the search; only when both fail
     * is it an error.
     */
    fun find(artist: String, title: String, album: String, durSec: Int): Lyrics? {
        val p = linkedMapOf("artist_name" to artist, "track_name" to title)
        if (album.isNotBlank()) p["album_name"] = album
        if (durSec > 0) p["duration"] = durSec.toString()
        var failed: Exception? = null
        try {
            val r = Http.retryBusy { Http.get("$base/get?" + Http.form(p), timeoutMs = 10_000) }
            if (r.code == 200) parse(JSONObject(r.body))?.let { return it }
            else if (r.code != 404) failed = IOException("LRCLIB answered ${r.code}")
        } catch (e: Exception) {
            failed = e
        }
        val s = try {
            Http.retryBusy { Http.get("$base/search?" + Http.form(mapOf("track_name" to title, "artist_name" to artist)), timeoutMs = 15_000) }
        } catch (e: Exception) {
            throw failed ?: e
        }
        if (s.code != 200) {
            if (failed != null) throw failed
            return null
        }
        val hits = JSONArray(s.body)
        val want = Text.normLoose(title)
        val who = Text.norm(artist)
        return (0 until hits.length()).map { hits.getJSONObject(it) }
            // The same artist, or one credited with more ("Fela Kuti" and "Fela Kuti and Africa 70").
            .filter { Text.normLoose(it.optString("trackName")) == want && Text.norm(it.optString("artistName")).let { a -> a == who || a.startsWith("$who ") || who.startsWith("$a ") } }
            .filter { durSec <= 0 || it.optDouble("duration", 0.0) <= 0 || abs(it.optDouble("duration") - durSec) <= LENGTH_SLACK }
            .sortedWith(compareBy({ text(it, "syncedLyrics").isEmpty() }, { if (durSec > 0) abs(it.optDouble("duration", 0.0) - durSec) else 0.0 }))
            .firstNotNullOfOrNull { parse(it) }
    }

    /** JSON nulls come back as "null" from optString. */
    private fun text(o: JSONObject, k: String) = if (o.isNull(k)) "" else o.optString(k)

    private fun parse(o: JSONObject): Lyrics? {
        val l = Lyrics(text(o, "plainLyrics"), text(o, "syncedLyrics"), o.optBoolean("instrumental"))
        return l.takeIf { it.instrumental || it.plain.isNotBlank() || it.synced.isNotBlank() }
    }
}
