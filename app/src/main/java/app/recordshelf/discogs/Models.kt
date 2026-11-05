package app.recordshelf.discogs

import app.recordshelf.util.Text
import app.recordshelf.util.objects
import app.recordshelf.util.str
import app.recordshelf.util.strings
import app.recordshelf.util.toJsonArray
import org.json.JSONArray
import org.json.JSONObject

/** One record in the user's Discogs collection (from the collection listing, no tracklist). */
data class CollectionItem(
    val releaseId: Long,
    val instanceId: Long,
    val title: String,
    val artist: String,
    val year: Int,
    val addedMs: Long,
    val thumb: String,
    val cover: String,
    val format: String,
    val label: String,
    val genres: List<String>,
    val styles: List<String>,
    val masterId: Long,
) {
    fun toJson(): JSONObject = JSONObject().put("id", releaseId).put("instance", instanceId).put("title", title)
        .put("artist", artist).put("year", year).put("added", addedMs).put("thumb", thumb).put("cover", cover)
        .put("format", format).put("label", label).put("genres", genres.toJsonArray()).put("styles", styles.toJsonArray())
        .put("master", masterId)

    /** The album's usual cover, as looked up on Deezer and kept in [images] by album key; "" until then or if it has none. */
    fun usualCover(images: Map<String, String>): String = images[Text.albumKey(artist, title)].orEmpty()

    /**
     * The picture this record shows: the album's usual cover, or your copy's own (Discogs) when you chose it ([own]) or
     * the album has no other. A white sleeve of a special pressing would otherwise stand for the album everywhere.
     */
    fun face(images: Map<String, String>, own: Boolean): String =
        (if (own) "" else usualCover(images)).ifEmpty { cover.ifEmpty { thumb } }

    /** The same, small: for the shelf's tiles and lists. */
    fun faceThumb(images: Map<String, String>, own: Boolean): String =
        (if (own) "" else usualCover(images).replace("/500x500-", "/250x250-")).ifEmpty { thumb.ifEmpty { cover } }

    companion object {
        fun fromJson(o: JSONObject) = CollectionItem(
            o.optLong("id"), o.optLong("instance"), o.str("title"), o.str("artist"), o.optInt("year"), o.optLong("added"),
            o.str("thumb"), o.str("cover"), o.str("format"), o.str("label"),
            o.optJSONArray("genres")?.strings().orEmpty(), o.optJSONArray("styles")?.strings().orEmpty(), o.optLong("master"),
        )

        fun listToJson(list: List<CollectionItem>) = JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }
        fun listFromJson(text: String?): List<CollectionItem> = try {
            if (text == null) emptyList() else JSONArray(text).objects().map { fromJson(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

/**
 * A track of a release. [side] is the vinyl side ("A", "B", ...), "Disc 2" for numbered
 * discs, or "" when the release has no sides. [durSec] is 0 when no source knows the length;
 * [durSrc] says where the length came from (discogs, musicbrainz, lastfm).
 */
data class Track(
    val pos: String,
    val side: String,
    val title: String,
    val artist: String,
    val durSec: Int,
    val durSrc: String,
) {
    fun toJson(): JSONObject = JSONObject().put("pos", pos).put("side", side).put("title", title)
        .put("artist", artist).put("dur", durSec).put("src", durSrc)

    companion object {
        fun fromJson(o: JSONObject) = Track(o.str("pos"), o.str("side"), o.str("title"), o.str("artist"), o.optInt("dur"), o.str("src"))
    }
}

data class Release(
    val id: Long,
    val title: String,
    val artist: String,
    val year: Int,
    val cover: String,
    val thumb: String,
    val label: String,
    val format: String,
    val country: String,
    val genres: List<String>,
    val styles: List<String>,
    val tracks: List<Track>,
    val uri: String,
    val masterId: Long,
    val fetchedMs: Long = 0,
) {
    /** The sides in tracklist order ("A", "B", ...), without "". */
    val sides: List<String> get() = tracks.map { it.side }.filter { it.isNotEmpty() }.distinct()

    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("artist", artist).put("year", year)
        .put("cover", cover).put("thumb", thumb).put("label", label).put("format", format).put("country", country)
        .put("genres", genres.toJsonArray()).put("styles", styles.toJsonArray())
        .put("tracks", JSONArray().also { a -> tracks.forEach { a.put(it.toJson()) } })
        .put("uri", uri).put("master", masterId).put("fetched", fetchedMs)

    companion object {
        fun fromJson(o: JSONObject) = Release(
            o.optLong("id"), o.str("title"), o.str("artist"), o.optInt("year"), o.str("cover"), o.str("thumb"),
            o.str("label"), o.str("format"), o.str("country"),
            o.optJSONArray("genres")?.strings().orEmpty(), o.optJSONArray("styles")?.strings().orEmpty(),
            o.optJSONArray("tracks")?.objects().orEmpty().map { Track.fromJson(it) },
            o.str("uri"), o.optLong("master"), o.optLong("fetched"),
        )
    }
}
