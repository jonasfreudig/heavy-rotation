package app.recordshelf.net

import app.recordshelf.util.Text
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** An artist in Deezer's catalog; [photo] is "" when Deezer has none. */
data class DzArtist(val id: Long, val name: String, val photo: String, val fans: Long, val link: String) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("photo", photo).put("fans", fans).put("link", link)

    companion object {
        fun fromJson(o: JSONObject) = DzArtist(o.optLong("id"), o.optString("name"), o.optString("photo"), o.optLong("fans"), o.optString("link"))
    }
}

/** One release of an artist: [type] is "album", "ep", "single" or "compile"; [date] is "YYYY-MM-DD". */
data class DzRelease(val id: Long, val title: String, val type: String, val date: String, val cover: String, val link: String) {
    val year get() = date.take(4).toIntOrNull() ?: 0

    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("type", type).put("date", date).put("cover", cover).put("link", link)

    companion object {
        fun fromJson(o: JSONObject) = DzRelease(o.optLong("id"), o.optString("title"), o.optString("type"), o.optString("date"), o.optString("cover"), o.optString("link"))
    }
}

/** An album with its tracklist (lengths in seconds, in order). */
data class DzAlbum(
    val id: Long, val title: String, val artist: String, val date: String, val label: String, val cover: String, val link: String,
    val genres: List<String>, val tracks: List<Pair<String, Int>>,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("artist", artist).put("date", date).put("label", label)
        .put("cover", cover).put("link", link).put("genres", JSONArray(genres))
        .put("tracks", JSONArray(tracks.map { (t, sec) -> JSONObject().put("title", t).put("sec", sec) }))
}

/** Artists, their photos and their discographies from Deezer's public catalog, which needs no account. */
class DeezerClient(private val base: String = "https://api.deezer.com") {

    private fun get(path: String): JSONObject {
        val r = Http.get(base + path)
        if (r.code !in 200..299) throw IOException("Deezer answered ${r.code}")
        val o = JSONObject(r.body)
        // Deezer reports errors (like its request quota) with status 200.
        o.optJSONObject("error")?.let { throw IOException("Deezer: ${it.optString("message")}") }
        return o
    }

    private fun q(s: String) = Http.enc(s).replace("+", "%20")

    /** The artist with exactly this name, the most followed one if several share it; null if none. */
    fun findArtist(name: String): DzArtist? {
        val data = get("/search/artist?q=${q(name)}&limit=10").optJSONArray("data") ?: return null
        return (0 until data.length()).map { data.getJSONObject(it) }
            .filter { Text.norm(it.optString("name")) == Text.norm(name) }
            .maxByOrNull { it.optLong("nb_fan") }
            ?.let { DzArtist(it.optLong("id"), it.optString("name"), it.optString("picture_big").takeIf { p -> hasPhoto(p) } ?: "", it.optLong("nb_fan"), it.optString("link")) }
    }

    /** Artists Deezer's listeners also play with this one, the closest first. */
    fun related(artistId: Long, limit: Int = 25): List<DzArtist> {
        val data = get("/artist/$artistId/related?limit=$limit").optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).map { data.getJSONObject(it) }.map {
            DzArtist(it.optLong("id"), it.optString("name"), it.optString("picture_big").takeIf { p -> hasPhoto(p) } ?: "", it.optLong("nb_fan"), it.optString("link"))
        }
    }

    /** A square photo (500 px) of the artist with this name; null if Deezer has none. */
    fun artistPhoto(name: String): String? = findArtist(name)?.photo?.ifEmpty { null }

    /** Artists without a photo come with an empty image id: ".../images/artist//500x500-...". */
    private fun hasPhoto(url: String) = url.startsWith("https://") && !url.contains("/artist//")

    /** Every album, EP and single of an artist, as Deezer lists them (newest first), up to [maxPages] pages of 100. */
    fun releases(artistId: Long, maxPages: Int = 5): List<DzRelease> {
        val out = ArrayList<DzRelease>()
        var path: String? = "/artist/$artistId/albums?limit=100"
        var pages = 0
        while (path != null && pages++ < maxPages) {
            val o = get(path)
            val data = o.optJSONArray("data") ?: break
            for (i in 0 until data.length()) {
                val a = data.getJSONObject(i)
                out += DzRelease(a.optLong("id"), a.optString("title"), a.optString("record_type"), a.optString("release_date"), a.optString("cover_big"), a.optString("link"))
            }
            path = o.optString("next").takeIf { it.startsWith(base) }?.removePrefix(base)
        }
        return out
    }

    /**
     * The album called [title] by [artist], as a music app names it ("(Deluxe)" and the like don't
     * matter), with its tracklist; null if Deezer has none.
     */
    fun findAlbum(artist: String, title: String): DzAlbum? = searchAlbum(artist, title)?.let { album(it.optLong("id")) }

    /** The cover (500 px) of the album called [title] by [artist]; null if Deezer has none. One request, no tracklist. */
    fun albumCover(artist: String, title: String): String? =
        searchAlbum(artist, title)?.let { it.optString("cover_big").ifEmpty { it.optString("cover_xl") } }?.takeIf { it.startsWith("https://") }

    /** Deezer's search hit for the album, matched the way a music app names it; null if none. */
    private fun searchAlbum(artist: String, title: String): JSONObject? {
        val want = Text.normLoose(title)
        val who = Text.norm(artist).removePrefix("the ")
        fun pick(query: String): JSONObject? {
            val data = get("/search/album?q=${q(query)}&limit=25").optJSONArray("data") ?: return null
            return (0 until data.length()).map { data.getJSONObject(it) }.firstOrNull {
                val a = Text.norm(it.optJSONObject("artist")?.optString("name").orEmpty()).removePrefix("the ")
                Text.normLoose(it.optString("title")) == want && (a == who || who.startsWith("$a ") || a.startsWith("$who "))
            }
        }
        return pick("artist:\"$artist\" album:\"$title\"") ?: pick("$artist $title")
    }

    /** One album with its tracklist, label and genres. */
    fun album(id: Long): DzAlbum {
        val a = get("/album/$id")
        val tracks = a.optJSONObject("tracks")?.optJSONArray("data")
        val genres = a.optJSONObject("genres")?.optJSONArray("data")
        return DzAlbum(
            id, a.optString("title"), a.optJSONObject("artist")?.optString("name").orEmpty(), a.optString("release_date"), a.optString("label"),
            a.optString("cover_xl").ifEmpty { a.optString("cover_big") }, a.optString("link"),
            (0 until (genres?.length() ?: 0)).map { genres!!.getJSONObject(it).optString("name") },
            (0 until (tracks?.length() ?: 0)).map { tracks!!.getJSONObject(it).let { t -> t.optString("title") to t.optInt("duration") } },
        )
    }
}
