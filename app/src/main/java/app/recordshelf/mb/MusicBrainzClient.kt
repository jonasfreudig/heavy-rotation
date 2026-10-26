package app.recordshelf.mb

import app.recordshelf.net.Http
import app.recordshelf.scrobble.SourceTrack
import app.recordshelf.util.objects
import app.recordshelf.util.str
import org.json.JSONObject
import java.io.IOException

/**
 * MusicBrainz, used for track lengths Discogs doesn't have. Many MusicBrainz releases link to
 * the exact Discogs release, which gives the right pressing; a text search is the fallback.
 * MusicBrainz allows about one request per second.
 */
class MusicBrainzClient(
    private val base: String = API,
    private val minGapMs: Long = 1100,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    companion object {
        const val API = "https://musicbrainz.org/ws/2"
        private var last = 0L
        private val lock = Any()
    }

    /** The MusicBrainz release linked to this Discogs release, or null. */
    fun releaseForDiscogs(discogsId: Long): String? {
        val res = Http.enc("https://www.discogs.com/release/$discogsId")
        val j = get("/url?resource=$res&inc=release-rels&fmt=json") ?: return null
        return j.optJSONArray("relations")?.objects().orEmpty()
            .firstNotNullOfOrNull { it.optJSONObject("release")?.str("id")?.ifEmpty { null } }
    }

    /** All tracks of a release (every medium in order) with lengths in seconds (0 if unknown). */
    fun tracks(mbid: String): List<SourceTrack> {
        val j = get("/release/$mbid?inc=recordings&fmt=json") ?: return emptyList()
        return j.optJSONArray("media")?.objects().orEmpty().flatMap { m ->
            m.optJSONArray("tracks")?.objects().orEmpty().map { t ->
                val ms = t.optLong("length", 0).takeIf { it > 0 } ?: t.optJSONObject("recording")?.optLong("length", 0) ?: 0
                SourceTrack(t.str("number"), t.str("title"), ((ms + 500) / 1000).toInt())
            }
        }
    }

    /** Release ids that match artist + title well (best first). */
    fun search(artist: String, title: String, limit: Int = 5): List<String> {
        val q = "release:\"${quote(title)}\" AND artist:\"${quote(artist)}\""
        val j = get("/release?query=${Http.enc(q)}&fmt=json&limit=$limit") ?: return emptyList()
        return j.optJSONArray("releases")?.objects().orEmpty().filter { it.optInt("score") >= 90 }.map { it.str("id") }
    }

    private fun quote(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun get(path: String): JSONObject? {
        for (attempt in 0 until 3) {
            synchronized(lock) {
                val wait = last + minGapMs - System.currentTimeMillis()
                if (wait > 0) sleep(wait)
                last = System.currentTimeMillis()
            }
            val r = Http.get(base + path)
            when {
                r.code == 404 -> return null
                r.code == 503 || r.code == 429 -> sleep(1500L * (attempt + 1))
                r.code >= 400 -> throw IOException("MusicBrainz answered HTTP ${r.code}")
                else -> return try {
                    JSONObject(r.body)
                } catch (_: Exception) {
                    throw IOException("MusicBrainz sent something unreadable")
                }
            }
        }
        throw IOException("MusicBrainz is busy")
    }
}
