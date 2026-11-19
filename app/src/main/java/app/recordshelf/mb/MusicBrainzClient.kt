package app.recordshelf.mb

import app.recordshelf.net.Http
import app.recordshelf.net.coreTitle
import app.recordshelf.net.sameButEdition
import app.recordshelf.scrobble.SourceTrack
import app.recordshelf.util.Text
import app.recordshelf.util.objects
import app.recordshelf.util.str
import org.json.JSONObject
import java.io.IOException

/**
 * MusicBrainz, used for track lengths Discogs doesn't have. Many MusicBrainz releases link to
 * the exact Discogs release, which gives the right pressing; a text search is the fallback.
 * MusicBrainz allows about one request per second. Its Cover Art Archive ([covers]) has the covers of
 * albums Deezer doesn't list.
 */
class MusicBrainzClient(
    private val base: String = API,
    private val minGapMs: Long = 1100,
    private val covers: String = COVERS,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    companion object {
        const val API = "https://musicbrainz.org/ws/2"
        const val COVERS = "https://coverartarchive.org"

        /** A cover is asked for from this many albums of the name at most (the album, then its EP or single). */
        private const val COVER_TRIES = 2
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

    /**
     * Where an artist is from: the ISO 3166 code of the best match by that exact name, "" when there is none or it
     * names no country (an area that is a city, or none). For the world map.
     */
    fun artistCountry(name: String): String {
        val j = get("/artist?query=${Http.enc("artist:\"${quote(name)}\"")}&fmt=json&limit=5") ?: return ""
        val norm = Text.norm(name)
        val best = j.optJSONArray("artists")?.objects().orEmpty()
            .firstOrNull { it.optInt("score") >= 90 && (Text.norm(it.str("name")) == norm || Text.norm(it.str("sort-name")) == norm) } ?: return ""
        return best.str("country").ifEmpty {
            best.optJSONObject("area")?.optJSONArray("iso-3166-1-codes")?.optString(0).orEmpty()
        }.uppercase()
    }

    /** The MusicBrainz id of the artist with exactly this name (the best match), "" if there is none. */
    fun artistId(name: String): String {
        val j = get("/artist?query=${Http.enc("artist:\"${quote(name)}\"")}&fmt=json&limit=5") ?: return ""
        val norm = Text.norm(name)
        return j.optJSONArray("artists")?.objects().orEmpty()
            .firstOrNull { it.optInt("score") >= 90 && (Text.norm(it.str("name")) == norm || Text.norm(it.str("sort-name")) == norm) }?.str("id").orEmpty()
    }

    /** An artist's albums and EPs (release groups), with their first release date ("" if none yet) and other types ("Live", …). */
    fun releaseGroups(artistId: String): List<app.recordshelf.history.MbGroup> {
        val j = get("/release-group?artist=${Http.enc(artistId)}&type=album%7Cep&fmt=json&limit=100") ?: return emptyList()
        return j.optJSONArray("release-groups")?.objects().orEmpty().map { g ->
            app.recordshelf.history.MbGroup(
                g.str("id"), g.str("title"), g.str("primary-type").lowercase(), g.str("first-release-date"),
                g.optJSONArray("secondary-types")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
            )
        }
    }

    /**
     * The front cover (500 px) of the album [title] by [artist] from the Cover Art Archive; null if MusicBrainz
     * doesn't know the album or the archive has no cover of it. What a player adds to a title ("(Deluxe)", "EP")
     * doesn't matter. Of several releases of that name the album comes before an EP and a single.
     */
    fun albumCover(artist: String, title: String): String? {
        val q = "releasegroup:\"${quote(coreTitle(title))}\" AND artist:\"${quote(artist)}\""
        val j = get("/release-group?query=${Http.enc(q)}&fmt=json&limit=10") ?: return null
        val want = Text.normLoose(title)
        val who = Text.norm(artist).removePrefix("the ")
        val groups = j.optJSONArray("release-groups")?.objects().orEmpty().filter { g ->
            val name = Text.normLoose(g.str("title"))
            g.optInt("score") >= 90 && (name == want || sameButEdition(name, want)) && g.optJSONArray("artist-credit")?.objects().orEmpty().any { c ->
                listOf(c.str("name"), c.optJSONObject("artist")?.str("name").orEmpty()).any { n ->
                    val a = Text.norm(n).removePrefix("the ")
                    a.isNotEmpty() && (a == who || a.startsWith("$who ") || a.endsWith(" $who") || who.startsWith("$a "))
                }
            }
        }.sortedBy { when (it.str("primary-type").lowercase()) { "album" -> 0; "ep" -> 1; "single" -> 3; else -> 2 } }
        return groups.take(COVER_TRIES).firstNotNullOfOrNull { front(it.str("id")) }
    }

    /** The archive's front picture of a release group, null if it has none. */
    private fun front(groupId: String): String? {
        if (groupId.isEmpty()) return null
        val r = Http.get("$covers/release-group/${Http.enc(groupId)}")
        if (r.code == 404) return null
        if (r.code !in 200..299) throw IOException("The Cover Art Archive answered HTTP ${r.code}")
        val images = try {
            JSONObject(r.body).optJSONArray("images")?.objects().orEmpty()
        } catch (_: Exception) {
            throw IOException("The Cover Art Archive sent something unreadable")
        }
        val pic = images.firstOrNull { it.optBoolean("front") } ?: return null
        val thumbs = pic.optJSONObject("thumbnails")
        val url = thumbs?.str("500").orEmpty().ifEmpty { thumbs?.str("large").orEmpty() }.ifEmpty { pic.str("image") }
        // The archive lists its pictures with http addresses, and serves them over https too.
        return url.replaceFirst("http://", "https://").takeIf { it.startsWith("https://") }
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
