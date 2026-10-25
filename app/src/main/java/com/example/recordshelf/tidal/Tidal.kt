package com.example.recordshelf.tidal

import com.example.recordshelf.util.JsonFile
import com.example.recordshelf.util.Text
import org.json.JSONObject
import java.net.URLDecoder

/** Where TIDAL keeps the user's app credentials and login; Prefs implements it, tests use a map. */
interface TidalStore {
    var tidalClientId: String
    var tidalSecret: String
    var tidalAccess: String
    var tidalAccessExp: Long
    var tidalRefresh: String
    var tidalVerifier: String
    var tidalState: String
}

class PlaylistResult(val id: String, val link: String, val added: Int, val missing: List<Pair<String, String>>)

/**
 * TIDAL for the app: the user's own client id and secret, their login, and a cache of what was
 * found in the catalog, so a song is searched only once. Built on [TidalClient].
 */
class Tidal(
    private val store: TidalStore,
    private val cache: JsonFile,
    private val country: () -> String,
    private val clientFor: (String, String) -> TidalClient = { id, secret -> TidalClient(id, secret) },
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    companion object {
        /** A search that found nothing is tried again after this long (the catalog grows). */
        const val MISS_TTL_MS = 30L * 24 * 3_600_000
    }

    @Volatile private var appToken: TidalToken? = null

    val hasApp get() = store.tidalClientId.isNotEmpty() && store.tidalSecret.isNotEmpty()
    val loggedIn get() = store.tidalRefresh.isNotEmpty()

    private fun client() = clientFor(store.tidalClientId, store.tidalSecret)

    /** Checks the client id and secret by getting an app token. */
    @Synchronized
    fun checkApp() {
        appToken = null
        appAccess()
    }

    @Synchronized
    private fun appAccess(): String {
        appToken?.takeIf { it.expiresAt > now() }?.let { return it.access }
        return client().appToken(now()).also { appToken = it }.access
    }

    /** The signed-in user's access token, refreshed when it has run out. */
    @Synchronized
    private fun userAccess(): String {
        if (store.tidalAccess.isNotEmpty() && store.tidalAccessExp > now()) return store.tidalAccess
        if (store.tidalRefresh.isEmpty()) throw TidalException(401, "Sign in to TIDAL first")
        val t = client().refresh(store.tidalRefresh, now())
        save(t)
        return t.access
    }

    private fun save(t: TidalToken) {
        store.tidalAccess = t.access
        store.tidalAccessExp = t.expiresAt
        if (t.refresh.isNotEmpty()) store.tidalRefresh = t.refresh
    }

    /** The address of TIDAL's login page; the verifier and state wait for the redirect. */
    fun startLogin(): String {
        val v = TidalClient.verifier()
        val s = TidalClient.state()
        store.tidalVerifier = v
        store.tidalState = s
        return client().authUrl(TidalClient.challenge(v), s)
    }

    /** The redirect (recordshelf://tidal?code=…&state=…) after the user approved, or an error from TIDAL. */
    fun finishLogin(uri: String) {
        val q = uri.substringAfter('?', "").split('&').filter { it.contains('=') }
            .associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        q["error"]?.let { throw TidalException(400, q["error_description"] ?: it, oauthError = it) }
        val code = q["code"] ?: throw TidalException(400, "TIDAL sent no login code")
        if (store.tidalState.isEmpty() || q["state"] != store.tidalState) throw TidalException(400, "This login link is outdated. Try signing in again.")
        val t = client().exchange(code, store.tidalVerifier, nowMs = now())
        store.tidalVerifier = ""
        store.tidalState = ""
        store.tidalRefresh = ""
        save(t)
    }

    fun signOut() {
        store.tidalAccess = ""
        store.tidalAccessExp = 0
        store.tidalRefresh = ""
    }

    fun forget() {
        signOut()
        store.tidalClientId = ""
        store.tidalSecret = ""
        appToken = null
        cache.delete()
        links.clear()
    }

    // ---- finding things in the catalog ----

    private val links: MutableMap<String, JSONObject> by lazy {
        val m = HashMap<String, JSONObject>()
        try {
            val o = JSONObject(cache.read() ?: "{}")
            for (k in o.keys()) m[k] = o.getJSONObject(k)
        } catch (_: Exception) {
        }
        m
    }

    private fun saveLinks() = cache.write(JSONObject(links as Map<*, *>).toString())

    /** One search, waiting once if TIDAL asks to slow down, with a fresh app token if the old one expired. */
    private fun search(query: String, kind: String): List<TidalHit> {
        var retried = false
        while (true) {
            try {
                return client().search(appAccess(), query, kind, country())
            } catch (e: TidalException) {
                if (retried) throw e
                retried = true
                when {
                    e.code == 429 -> sleep((e.retryAfterSec.coerceIn(1, 10)) * 1000L)
                    e.code == 401 -> synchronized(this) { appToken = null }
                    else -> throw e
                }
            }
        }
    }

    /**
     * The song ("tracks"), album ("albums") or artist ("artists") in the TIDAL catalog, or null
     * if there's no clear match. Results (and misses, for a while) are kept on the phone.
     */
    fun find(kind: String, artist: String, title: String = ""): TidalHit? {
        val key = "$kind|${Text.norm(artist)}|${Text.normLoose(title)}"
        synchronized(links) { links[key] }?.let { c ->
            if (c.optString("id").isNotEmpty()) return TidalHit(c.getString("id"), kind, title.ifEmpty { artist }, "", emptyList(), c.getString("link"))
            if (now() - c.optLong("at") < MISS_TTL_MS) return null
        }
        val queries = if (kind == "artists") listOf(artist) else listOf("$artist $title", "$artist ${title.replace(Regex("\\s*[(\\[].*?[)\\]]"), "").substringBefore(" - ").trim()}").distinct()
        var hit: TidalHit? = null
        for (q in queries) {
            val hits = search(q, kind)
            hit = when (kind) {
                "artists" -> TidalClient.bestArtist(hits, artist)
                "albums" -> TidalClient.bestAlbum(hits, artist, title)
                else -> TidalClient.bestTrack(hits, artist, title)
            }
            if (hit != null) break
        }
        synchronized(links) {
            links[key] = if (hit != null) JSONObject().put("id", hit.id).put("link", hit.link).put("at", now()) else JSONObject().put("at", now())
            saveLinks()
        }
        return hit
    }

    /**
     * A playlist in the user's TIDAL: each song looked up ([progress] with songs done and found),
     * then the playlist created and filled. Songs TIDAL doesn't have come back as missing.
     */
    fun createPlaylist(name: String, note: String, tracks: List<Pair<String, String>>, progress: (Int, Int) -> Unit = { _, _ -> }): PlaylistResult {
        userAccess() // signed in? Better to know before searching forty songs.
        val ids = ArrayList<String>()
        val missing = ArrayList<Pair<String, String>>()
        tracks.forEachIndexed { i, (artist, title) ->
            val hit = find("tracks", artist, title)
            if (hit != null) ids += hit.id else missing += artist to title
            progress(i + 1, ids.size)
        }
        val unique = ids.distinct()
        if (unique.isEmpty()) throw TidalException(404, "None of these songs were found on TIDAL")
        val token = userAccess()
        val (id, link) = client().createPlaylist(token, name, note)
        client().addTracks(token, id, unique)
        return PlaylistResult(id, link, unique.size, missing)
    }
}
