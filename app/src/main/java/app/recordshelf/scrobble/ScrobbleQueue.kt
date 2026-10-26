package app.recordshelf.scrobble

import app.recordshelf.lastfm.LastfmClient
import app.recordshelf.lastfm.LastfmException
import app.recordshelf.lastfm.Scrobble
import app.recordshelf.lastfm.ScrobbleResult
import app.recordshelf.util.JsonFile
import app.recordshelf.util.objects
import app.recordshelf.util.str
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class QueuedScrobble(val id: String, val s: Scrobble, val src: String, val addedMs: Long) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("s", s.toJson()).put("src", src).put("added", addedMs)

    companion object {
        fun fromJson(o: JSONObject) = QueuedScrobble(o.str("id"), Scrobble.fromJson(o.optJSONObject("s") ?: JSONObject()), o.str("src"), o.optLong("added"))
    }
}

fun interface ScrobbleSender {
    fun send(batch: List<Scrobble>): List<ScrobbleResult>
}

data class FlushResult(
    val sent: Int = 0,
    val ignored: Int = 0,
    val failed: Int = 0,
    val waiting: Int = 0,
    /** Something is left that a later attempt can send (offline, Last.fm busy, daily limit). */
    val retry: Boolean = false,
    val needsAuth: Boolean = false,
    val badKey: Boolean = false,
    val error: String? = null,
)

/**
 * Scrobbles waiting to be sent, kept on disk so nothing is lost offline or when the app is
 * killed. [flush] sends them oldest first in batches of 50 and only removes what Last.fm
 * answered for: accepted, ignored (with its reason) or rejected as invalid.
 */
class ScrobbleQueue(private val file: JsonFile) {
    private val items = ArrayList<QueuedScrobble>()

    init {
        try {
            file.read()?.let { JSONArray(it).objects().forEach { o -> items += QueuedScrobble.fromJson(o) } }
        } catch (_: Exception) {
        }
    }

    /** Adds [list], skipping scrobbles already waiting (same artist, track and time). Returns what was added. */
    @Synchronized
    fun add(list: List<QueuedScrobble>): List<QueuedScrobble> {
        val have = items.map { key(it.s) }.toHashSet()
        val added = list.filter { have.add(key(it.s)) }
        items += added
        save()
        return added
    }

    @Synchronized
    fun size() = items.size

    /** Start time (Unix seconds) of the oldest waiting scrobble, or 0. */
    @Synchronized
    fun oldestTimestamp(): Long = items.minOfOrNull { it.s.timestamp } ?: 0L

    @Synchronized
    fun snapshot(): List<QueuedScrobble> = items.toList()

    fun flush(sender: ScrobbleSender, onResult: (QueuedScrobble, String, String) -> Unit): FlushResult {
        var sent = 0
        var ignored = 0
        var failed = 0
        var dailyLimit = false
        val kept = HashSet<String>()
        fun result(retry: Boolean = false, needsAuth: Boolean = false, badKey: Boolean = false, error: String? = null) =
            FlushResult(sent, ignored, failed, size(), retry, needsAuth, badKey, error)
        fun stopFor(e: LastfmException) = result(
            retry = e.retryable, needsAuth = e.needsAuth, badKey = e.badKey,
            error = when {
                e.needsAuth -> "Last.fm wants you to allow access again"
                e.badKey -> "Last.fm doesn't accept the API key or secret"
                e.retryable -> "Last.fm is busy; trying again later"
                else -> e.message ?: "Last.fm error ${e.code}"
            },
        )
        val offline = { result(retry = true, error = "Can't reach Last.fm; trying again when online") }
        fun apply(q: QueuedScrobble, r: ScrobbleResult?) {
            when {
                r == null -> {
                    remove(q)
                    failed++
                    onResult(q, "failed", "Last.fm rejected this scrobble's data")
                }
                r.accepted -> {
                    remove(q)
                    sent++
                    onResult(q, "sent", "")
                }
                r.ignoredCode == 5 -> {
                    kept += q.id
                    dailyLimit = true
                }
                else -> {
                    remove(q)
                    ignored++
                    onResult(q, "ignored", r.message)
                }
            }
        }
        try {
            while (true) {
                val batch = synchronized(this) {
                    items.filter { it.id !in kept }.sortedBy { it.s.timestamp }.take(LastfmClient.MAX_BATCH)
                }
                if (batch.isEmpty()) break
                try {
                    val rs = sender.send(batch.map { it.s })
                    batch.forEachIndexed { i, q -> apply(q, rs.getOrNull(i) ?: ScrobbleResult(true)) }
                } catch (e: LastfmException) {
                    if (e.code != 6) return stopFor(e)
                    // "Invalid parameters" for the batch: send one by one to find the bad ones.
                    for (q in batch) {
                        try {
                            apply(q, sender.send(listOf(q.s)).firstOrNull() ?: ScrobbleResult(true))
                        } catch (e1: LastfmException) {
                            if (e1.code == 6) apply(q, null) else return stopFor(e1)
                        } catch (_: IOException) {
                            return offline()
                        }
                    }
                } catch (_: IOException) {
                    return offline()
                }
                save()
            }
        } finally {
            save()
        }
        return if (dailyLimit) result(retry = true, error = "Daily Last.fm limit reached; the rest goes out later") else result()
    }

    @Synchronized
    private fun remove(q: QueuedScrobble) {
        items.removeAll { it.id == q.id }
    }

    @Synchronized
    private fun save() {
        file.write(JSONArray().also { a -> items.forEach { a.put(it.toJson()) } }.toString())
    }

    private fun key(s: Scrobble) = "${s.artist.lowercase()}\u0000${s.track.lowercase()}\u0000${s.timestamp}"
}

/**
 * What the app scrobbled (or, in shadow mode, would have) and what became of it, newest
 * last; shown on the Activity tab. Big enough for about two weeks of phone listening, which
 * the shadow-mode comparison needs.
 */
class ActivityLog(private val file: JsonFile, private val max: Int = 2500) {
    private val entries = ArrayList<JSONObject>()

    init {
        try {
            file.read()?.let { entries += JSONArray(it).objects() }
        } catch (_: Exception) {
        }
    }

    /** [status]: waiting, sent, ignored, failed, or shadow (logged only). [app] names the phone app it came from. */
    @Synchronized
    fun add(q: QueuedScrobble, cover: String, status: String, msg: String = "", app: String = "") {
        entries += JSONObject().put("id", q.id).put("artist", q.s.artist).put("track", q.s.track).put("album", q.s.album)
            .put("ts", q.s.timestamp).put("src", q.src).put("cover", cover).put("status", status).put("msg", msg)
            .put("app", app).put("at", System.currentTimeMillis())
        while (entries.size > max) entries.removeAt(0)
    }

    /** Entries as listens (artist, track, time) for those matching [filter]. */
    @Synchronized
    fun listens(filter: (JSONObject) -> Boolean): List<Listen> =
        entries.filter(filter).map { Listen(it.str("artist"), it.str("track"), it.optLong("ts"), it.str("album")) }

    @Synchronized
    fun update(id: String, status: String, msg: String) {
        entries.lastOrNull { it.str("id") == id }?.put("status", status)?.put("msg", msg)
    }

    @Synchronized
    fun save() = file.write(JSONArray(entries).toString())

    @Synchronized
    fun toJson(limit: Int = 400): JSONArray = JSONArray(entries.takeLast(limit))
}
