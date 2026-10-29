package app.recordshelf.net

import app.recordshelf.util.Text
import app.recordshelf.util.arrayOrOne
import app.recordshelf.util.objects
import app.recordshelf.util.str
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

/** A song as played at a show: a [cover] of another artist's, with a guest ([with]), off a tape (an intro), in the encore. */
data class SetSong(val title: String, val cover: String, val with: String, val tape: Boolean, val encore: Boolean, val info: String)

/** One show and what was played there, in order (the sets one after the other). */
data class Show(
    val id: String, val date: LocalDate, val artist: String, val venue: String, val city: String, val country: String,
    val tour: String, val url: String, val songs: List<SetSong>,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("date", date.toString()).put("artist", artist).put("venue", venue)
        .put("city", city).put("country", country).put("tour", tour).put("url", url)
        .put("songs", JSONArray(songs.map { JSONObject().put("t", it.title).put("c", it.cover).put("w", it.with).put("tape", it.tape).put("e", it.encore).put("i", it.info) }))

    companion object {
        /** A show as [toJson] keeps it; null if it can't be read. */
        fun fromJson(o: JSONObject): Show? = try {
            Show(
                o.str("id"), LocalDate.parse(o.str("date")), o.str("artist"), o.str("venue"), o.str("city"), o.str("country"), o.str("tour"), o.str("url"),
                o.arrayOrOne("songs").objects().map { SetSong(it.str("t"), it.str("c"), it.str("w"), it.optBoolean("tape"), it.optBoolean("e"), it.str("i")) },
            )
        } catch (_: Exception) {
            null
        }
    }
}

/** A song of an artist's usual set lately: in how many of the shows, and where in them (0 opens, 1 closes). */
data class Typical(val title: String, val shows: Int, val pos: Double, val cover: String, val encore: Boolean)

/**
 * Setlists from setlist.fm, with the user's own API key (free for non-commercial use, applied for
 * in the settings of a setlist.fm account). setlist.fm allows two requests a second.
 */
class SetlistfmClient(private val key: String, private val base: String = "https://api.setlist.fm/rest/1.0") {
    class KeyRefused : IOException("setlist.fm didn't accept the key")
    class Busy : IOException("setlist.fm is busy")

    private var lastCall = 0L

    /** A page of setlist.fm's answer; null when it has nothing for it (it says 404 then). */
    private fun get(path: String, params: Map<String, String>): JSONObject? {
        synchronized(this) {
            val wait = lastCall + GAP_MS - System.currentTimeMillis()
            if (wait > 0) Thread.sleep(wait)
            lastCall = System.currentTimeMillis()
        }
        val r = Http.get("$base$path?" + Http.form(params), mapOf("x-api-key" to key, "Accept-Language" to "en"))
        return when (r.code) {
            200 -> JSONObject(r.body)
            404 -> null
            401, 403 -> throw KeyRefused()
            429 -> throw Busy()
            else -> throw IOException("setlist.fm answered ${r.code}")
        }
    }

    /** An artist on setlist.fm: their MusicBrainz id, name and page. */
    data class Artist(val mbid: String, val name: String, val url: String)

    /** The artist setlist.fm knows by this name (the most relevant of that name); null if none. */
    fun findArtist(name: String): Artist? {
        val o = get("/search/artists", mapOf("artistName" to name, "p" to "1", "sort" to "relevance")) ?: return null
        val all = o.arrayOrOne("artist").objects().filter { it.str("mbid").isNotEmpty() }
        val want = Text.norm(name).removePrefix("the ")
        return all.firstOrNull { Text.norm(it.str("name")).removePrefix("the ") == want }?.let { Artist(it.str("mbid"), it.str("name"), it.str("url")) }
    }

    /**
     * Page [page] of the shows [user] marked as attended on setlist.fm, the newest first (20 a page), and how
     * many there are in all. Nothing when setlist.fm doesn't know the user or they marked none.
     */
    fun attended(user: String, page: Int = 1): Pair<List<Show>, Int> {
        val o = get("/user/" + java.net.URLEncoder.encode(user, "UTF-8").replace("+", "%20") + "/attended", mapOf("p" to page.toString()))
            ?: return emptyList<Show>() to 0
        return parse(o) to o.optInt("total")
    }

    /** Page [page] of an artist's setlists, the newest first (20 a page, shows to come among them). */
    fun setlists(mbid: String, page: Int = 1): List<Show> = get("/artist/$mbid/setlists", mapOf("p" to page.toString()))?.let { parse(it) }.orEmpty()

    companion object {
        private const val GAP_MS = 600L
        private val DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy")

        /** The setlists of one page of setlist.fm's answer. */
        fun parse(o: JSONObject): List<Show> = o.arrayOrOne("setlist").objects().mapNotNull { s ->
            val date = try {
                LocalDate.parse(s.str("eventDate"), DATE)
            } catch (_: Exception) {
                return@mapNotNull null
            }
            val venue = s.optJSONObject("venue")
            val city = venue?.optJSONObject("city")
            // setlist.fm wraps the sets ("sets": {"set": [...]}); a bare list is read as well.
            val sets = s.optJSONObject("sets")?.arrayOrOne("set")?.objects() ?: s.arrayOrOne("set").objects()
            val songs = sets.flatMap { set ->
                val encore = set.optInt("encore") > 0
                set.arrayOrOne("song").objects().map { x ->
                    SetSong(
                        x.str("name").trim(), x.optJSONObject("cover")?.str("name").orEmpty(), x.optJSONObject("with")?.str("name").orEmpty(),
                        x.optBoolean("tape"), encore, x.str("info"),
                    )
                }
            }.filter { it.title.isNotEmpty() }
            Show(
                s.str("id"), date, s.optJSONObject("artist")?.str("name").orEmpty(), venue?.str("name").orEmpty(), city?.str("name").orEmpty(),
                city?.optJSONObject("country")?.str("name").orEmpty(), s.optJSONObject("tour")?.str("name").orEmpty(), s.str("url"), songs,
            )
        }
    }
}

/** What an artist plays lately, from their recent setlists. */
object Setlists {
    /** Shows with at least this many songs (not off a tape) count; the newest this many of them. */
    const val SONGS_MIN = 5
    const val SHOWS = 12

    /** A song is part of the usual set when it was played at this share of the shows (at two of them at least). */
    const val SHARE = 0.3

    /** The recent shows that tell what they play: past ones with a setlist of some length, newest first. */
    fun lately(shows: List<Show>, today: LocalDate): List<Show> =
        shows.filter { !it.date.isAfter(today) && it.songs.count { s -> !s.tape } >= SONGS_MIN }.sortedByDescending { it.date }.take(SHOWS)

    /** The usual set of [shows]: the songs played at enough of them, in their usual order, the encore songs last. */
    fun typical(shows: List<Show>): List<Typical> {
        if (shows.isEmpty()) return emptyList()
        class Acc(val title: String) {
            var count = 0
            var pos = 0.0
            var cover = ""
            var encore = 0
        }
        val m = LinkedHashMap<String, Acc>()
        for (s in shows) {
            val songs = s.songs.filter { !it.tape }
            val seen = HashSet<String>()
            songs.forEachIndexed { i, x ->
                val k = Text.normLoose(x.title)
                if (!seen.add(k)) return@forEachIndexed
                val a = m.getOrPut(k) { Acc(x.title) }
                a.count++
                a.pos += if (songs.size > 1) i.toDouble() / (songs.size - 1) else 0.0
                if (x.cover.isNotEmpty()) a.cover = x.cover
                if (x.encore) a.encore++
            }
        }
        val need = if (shows.size == 1) 1 else maxOf(2, ceil(SHARE * shows.size).toInt())
        return m.values.filter { it.count >= need }
            .map { Typical(it.title, it.count, it.pos / it.count, it.cover, it.encore * 2 > it.count) }
            .sortedWith(compareBy<Typical> { it.encore }.thenBy { it.pos })
    }
}
