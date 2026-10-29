package app.recordshelf.discogs

import app.recordshelf.net.Http
import app.recordshelf.util.objects
import app.recordshelf.util.str
import app.recordshelf.util.strings
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.OffsetDateTime

class DiscogsException(val status: Int, message: String) : IOException(message)

/**
 * Keeps requests under Discogs' limit (60 per minute with a token, a moving one-minute
 * window). Shared by all clients so parallel work can't add up past it.
 */
class RateLimiter(
    private val perMinute: Int = 55,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    private val times = ArrayDeque<Long>()
    private var remaining = Int.MAX_VALUE

    @Synchronized
    fun acquire() {
        var now = clock()
        while (times.isNotEmpty() && now - times.first() >= 60_000) times.removeFirst()
        if (times.size >= perMinute || (remaining <= 2 && times.isNotEmpty())) {
            sleep((60_000 - (now - times.first())).coerceAtLeast(250))
            now = clock()
            while (times.isNotEmpty() && now - times.first() >= 60_000) times.removeFirst()
            remaining = Int.MAX_VALUE
        }
        times.addLast(now)
    }

    /** Discogs reports what is left of the window in X-Discogs-Ratelimit-Remaining. */
    @Synchronized
    fun report(left: Int?) {
        if (left != null) remaining = left
    }

    fun pause(ms: Long) = sleep(ms)

    companion object {
        val shared = RateLimiter()

        /** Without a token Discogs allows 25 requests a minute. */
        val anonymous = RateLimiter(23)
    }
}

/**
 * Discogs API, with a personal access token (the user's own; reads a private collection) or
 * without one (a public collection, by username).
 */
class DiscogsClient(
    private val token: String?,
    private val base: String = API,
    private val limiter: RateLimiter = RateLimiter.shared,
) {
    companion object {
        const val API = "https://api.discogs.com"
        const val PER_PAGE = 100
    }

    /** The username the token belongs to. */
    fun identity(): String = get("/oauth/identity").getString("username")

    /** One page of the collection ("All" folder, newest first) and the number of pages. */
    fun collectionPage(user: String, page: Int, perPage: Int = PER_PAGE): Pair<List<CollectionItem>, Int> {
        val j = get("/users/${Http.enc(user)}/collection/folders/0/releases?page=$page&per_page=$perPage&sort=added&sort_order=desc")
        val pages = j.optJSONObject("pagination")?.optInt("pages", 1) ?: 1
        return j.optJSONArray("releases")?.objects().orEmpty().mapNotNull { parseItem(it) } to pages
    }

    /** The whole collection; [progress] gets (records so far, pages done, pages). */
    fun collection(user: String, progress: (Int, Int, Int) -> Unit = { _, _, _ -> }): List<CollectionItem> {
        val out = ArrayList<CollectionItem>()
        var page = 1
        var pages: Int
        do {
            val (items, n) = collectionPage(user, page)
            pages = n
            out += items
            progress(out.size, page, pages)
            page++
        } while (page <= pages)
        // A record can be in the collection twice (two copies); one tile is enough.
        return out.distinctBy { it.releaseId }
    }

    fun release(id: Long): Release {
        val j = get("/releases/$id")
        val artist = Names.join(j.optJSONArray("artists")).ifEmpty { "Unknown artist" }
        val images = j.optJSONArray("images")?.objects().orEmpty()
        val img = images.firstOrNull { it.str("type") == "primary" } ?: images.firstOrNull()
        return Release(
            id = j.optLong("id", id),
            title = j.str("title"),
            artist = artist,
            year = j.optInt("year"),
            cover = img?.str("uri").orEmpty(),
            thumb = img?.str("uri150").orEmpty().ifEmpty { j.str("thumb") },
            label = labelText(j.optJSONArray("labels")),
            format = formatText(j.optJSONArray("formats")),
            country = j.str("country"),
            genres = j.optJSONArray("genres")?.strings().orEmpty(),
            styles = j.optJSONArray("styles")?.strings().orEmpty(),
            tracks = Tracklist.parse(j.optJSONArray("tracklist"), artist),
            uri = j.str("uri"),
            masterId = j.optLong("master_id"),
            fetchedMs = System.currentTimeMillis(),
        )
    }

    private fun parseItem(o: JSONObject): CollectionItem? {
        val b = o.optJSONObject("basic_information") ?: return null
        val added = try {
            OffsetDateTime.parse(o.str("date_added")).toInstant().toEpochMilli()
        } catch (_: Exception) {
            0L
        }
        return CollectionItem(
            releaseId = b.optLong("id", o.optLong("id")),
            instanceId = o.optLong("instance_id"),
            title = b.str("title"),
            artist = Names.join(b.optJSONArray("artists")).ifEmpty { "Unknown artist" },
            year = b.optInt("year"),
            addedMs = added,
            thumb = b.str("thumb"),
            cover = b.str("cover_image"),
            format = formatText(b.optJSONArray("formats")),
            label = labelText(b.optJSONArray("labels")),
            genres = b.optJSONArray("genres")?.strings().orEmpty(),
            styles = b.optJSONArray("styles")?.strings().orEmpty(),
            masterId = b.optLong("master_id"),
        )
    }

    /** "Vinyl, LP, Album"; "2×Vinyl, LP" for doubles. */
    private fun formatText(formats: JSONArray?): String = formats?.objects().orEmpty().joinToString(" + ") { f ->
        val qty = f.str("qty").toIntOrNull() ?: 1
        val name = (if (qty > 1) "$qty×" else "") + f.str("name")
        (listOf(name) + (f.optJSONArray("descriptions")?.strings().orEmpty())).joinToString(", ")
    }

    private fun labelText(labels: JSONArray?): String {
        val l = labels?.objects()?.firstOrNull() ?: return ""
        val name = Names.clean(l.str("name"))
        val cat = l.str("catno").takeIf { it.isNotEmpty() && !it.equals("none", true) }
        return if (cat != null) "$name, $cat" else name
    }

    private fun get(path: String): JSONObject {
        val headers = if (token.isNullOrEmpty()) emptyMap() else mapOf("Authorization" to "Discogs token=$token")
        for (attempt in 0 until 4) {
            limiter.acquire()
            val r = Http.get(base + path, headers)
            limiter.report(r.header("X-Discogs-Ratelimit-Remaining")?.toIntOrNull())
            when {
                r.code == 429 -> limiter.pause(10_000L * (attempt + 1))
                r.code == 401 || r.code == 403 -> throw DiscogsException(r.code, if (token.isNullOrEmpty()) "This Discogs collection isn't public" else "Discogs didn't accept the token")
                r.code == 404 -> throw DiscogsException(404, "Not found on Discogs")
                r.code >= 500 -> throw DiscogsException(r.code, "Discogs has a problem right now (HTTP ${r.code})")
                r.code >= 400 -> throw DiscogsException(r.code, message(r.body) ?: "Discogs error ${r.code}")
                else -> return try {
                    JSONObject(r.body)
                } catch (_: Exception) {
                    throw DiscogsException(r.code, "Discogs sent something unreadable")
                }
            }
        }
        throw DiscogsException(429, "Discogs is busy; try again in a minute")
    }

    private fun message(body: String): String? = try {
        JSONObject(body).str("message").ifEmpty { null }
    } catch (_: Exception) {
        null
    }
}
