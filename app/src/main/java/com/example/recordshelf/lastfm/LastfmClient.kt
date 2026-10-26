package com.example.recordshelf.lastfm

import com.example.recordshelf.net.Http
import com.example.recordshelf.scrobble.SourceTrack
import com.example.recordshelf.util.arrayOrOne
import com.example.recordshelf.util.objects
import com.example.recordshelf.util.str
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest

/** An error answer from the Last.fm API (https://www.last.fm/api/errorcodes). */
class LastfmException(val code: Int, message: String) : Exception(message) {
    /** Last.fm is busy or down; worth trying again later. */
    val retryable get() = code == 8 || code == 11 || code == 16 || code == 29

    /** The session or token is not (or no longer) valid: the user has to allow access again. */
    val needsAuth get() = code == 4 || code == 9 || code == 14 || code == 15 || code == 17

    /** The API key or secret is wrong or suspended. */
    val badKey get() = code == 10 || code == 13 || code == 26
}

data class Scrobble(
    val artist: String,
    val track: String,
    val album: String = "",
    val albumArtist: String = "",
    /** Start of playback, Unix seconds. */
    val timestamp: Long,
    val duration: Int = 0,
    val trackNumber: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject().put("artist", artist).put("track", track).put("album", album)
        .put("albumArtist", albumArtist).put("timestamp", timestamp).put("duration", duration).put("trackNumber", trackNumber)

    companion object {
        fun fromJson(o: JSONObject) = Scrobble(
            o.str("artist"), o.str("track"), o.str("album"), o.str("albumArtist"),
            o.optLong("timestamp"), o.optInt("duration"), o.optInt("trackNumber"),
        )
    }
}

/** Per-scrobble answer: accepted, or ignored with Last.fm's reason code (1..5). */
data class ScrobbleResult(val accepted: Boolean, val ignoredCode: Int = 0, val message: String = "")

data class AlbumInfo(val tracks: List<SourceTrack>, val userPlaycount: Int, val image: String)

data class TopAlbum(val artist: String, val album: String, val playcount: Int)

/** Everyone on Last.fm, all time: how many people played something, and how often. */
data class GlobalCount(val listeners: Long, val playcount: Long)

/** A scrobble from the user's history; [ts] is Unix seconds. */
data class RecentTrack(val artist: String, val title: String, val album: String, val ts: Long)

/** One page of user.getRecentTracks: the scrobbles, how many pages the range has, how many scrobbles in total. */
data class RecentPage(val tracks: List<RecentTrack>, val pages: Int, val total: Int)

/**
 * Last.fm API client for one API account (key + shared secret, created by the user).
 * Write calls are signed and need a session key from the web auth flow:
 * [getToken] -> user allows access at [authUrl] -> [getSession].
 */
class LastfmClient(private val apiKey: String, private val secret: String, private val base: String = API) {

    companion object {
        const val API = "https://ws.audioscrobbler.com/2.0/"
        const val MAX_BATCH = 50

        fun md5(s: String): String =
            MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        /** Human text for the "ignored" codes of track.scrobble. */
        fun ignoredText(code: Int, fallback: String = ""): String = when (code) {
            1 -> "Last.fm ignores this artist"
            2 -> "Last.fm ignores this track"
            3 -> "Too old: Last.fm only takes scrobbles from the last 2 weeks"
            4 -> "Time is in the future"
            5 -> "Daily Last.fm scrobble limit reached"
            else -> fallback.ifEmpty { "Ignored by Last.fm" }
        }
    }

    /** api_sig: md5 of all parameters (except format/callback) sorted by name, name+value, then the secret. */
    fun sign(params: Map<String, String>): String {
        val sb = StringBuilder()
        for (k in params.keys.filter { it != "format" && it != "callback" }.sorted()) sb.append(k).append(params[k])
        sb.append(secret)
        return md5(sb.toString())
    }

    fun authUrl(token: String) = "https://www.last.fm/api/auth/?api_key=${Http.enc(apiKey)}&token=${Http.enc(token)}"

    fun getToken(): String = call("auth.getToken", emptyMap(), signed = true, post = false).getString("token")

    /** Trades an approved token for (username, session key). Throws code 14 while it is not approved yet. */
    fun getSession(token: String): Pair<String, String> {
        val s = call("auth.getSession", mapOf("token" to token), signed = true, post = false).getJSONObject("session")
        return s.getString("name") to s.getString("key")
    }

    fun updateNowPlaying(sk: String, s: Scrobble) {
        val p = linkedMapOf("artist" to s.artist, "track" to s.track, "sk" to sk)
        if (s.album.isNotEmpty()) p["album"] = s.album
        if (s.albumArtist.isNotEmpty() && s.albumArtist != s.artist) p["albumArtist"] = s.albumArtist
        if (s.duration > 0) p["duration"] = s.duration.toString()
        if (s.trackNumber > 0) p["trackNumber"] = s.trackNumber.toString()
        call("track.updateNowPlaying", p, signed = true, post = true)
    }

    /** Sends up to [MAX_BATCH] scrobbles; the results are in the same order. */
    fun scrobble(sk: String, batch: List<Scrobble>): List<ScrobbleResult> {
        require(batch.size in 1..MAX_BATCH)
        val p = LinkedHashMap<String, String>()
        p["sk"] = sk
        batch.forEachIndexed { i, s ->
            p["artist[$i]"] = s.artist
            p["track[$i]"] = s.track
            p["timestamp[$i]"] = s.timestamp.toString()
            if (s.album.isNotEmpty()) p["album[$i]"] = s.album
            if (s.albumArtist.isNotEmpty() && s.albumArtist != s.artist) p["albumArtist[$i]"] = s.albumArtist
            if (s.duration > 0) p["duration[$i]"] = s.duration.toString()
            if (s.trackNumber > 0) p["trackNumber[$i]"] = s.trackNumber.toString()
        }
        val sc = call("track.scrobble", p, signed = true, post = true).getJSONObject("scrobbles")
        val arr = sc.arrayOrOne("scrobble")
        return List(batch.size) { i ->
            val ig = arr.optJSONObject(i)?.optJSONObject("ignoredMessage")
            val code = ig?.str("code")?.toIntOrNull() ?: 0
            ScrobbleResult(code == 0, code, if (code == 0) "" else ignoredText(code, ig?.str("#text").orEmpty()))
        }
    }

    /** Track list (with lengths when Last.fm knows them) and, with [user], that user's play count. Null if unknown. */
    fun albumInfo(artist: String, album: String, user: String? = null): AlbumInfo? {
        val p = linkedMapOf("artist" to artist, "album" to album, "autocorrect" to "1")
        if (!user.isNullOrEmpty()) p["username"] = user
        val json = try {
            call("album.getInfo", p, signed = false, post = false)
        } catch (e: LastfmException) {
            if (e.code == 6) return null
            throw e
        }
        val a = json.optJSONObject("album") ?: return null
        val tracks = a.optJSONObject("tracks")?.arrayOrOne("track")?.objects().orEmpty().map { t ->
            SourceTrack(t.optJSONObject("@attr")?.str("rank").orEmpty(), t.str("name"), t.optInt("duration", 0))
        }
        val image = a.optJSONArray("image")?.objects()?.map { it.str("#text") }?.lastOrNull { it.isNotEmpty() }.orEmpty()
        return AlbumInfo(tracks, a.str("userplaycount").toIntOrNull() ?: 0, image)
    }

    /** One page of the user's all-time top albums, plus the number of pages. */
    fun topAlbums(user: String, page: Int, limit: Int = 1000): Pair<List<TopAlbum>, Int> {
        val t = call(
            "user.getTopAlbums",
            mapOf("user" to user, "period" to "overall", "limit" to limit.toString(), "page" to page.toString()),
            signed = false, post = false,
        ).getJSONObject("topalbums")
        val pages = t.optJSONObject("@attr")?.str("totalPages")?.toIntOrNull() ?: 1
        val list = t.arrayOrOne("album").objects().map { a ->
            TopAlbum(a.optJSONObject("artist")?.str("name").orEmpty(), a.str("name"), a.str("playcount").toIntOrNull() ?: 0)
        }
        return list to pages
    }

    /**
     * One page of the user's scrobbles between [from] and [to] (Unix seconds; 0 leaves that
     * end open), newest first. The "now playing" entry (no time yet) is left out.
     */
    fun recentTracks(user: String, from: Long, to: Long, page: Int, limit: Int = 200): RecentPage {
        val p = linkedMapOf("user" to user, "page" to page.toString(), "limit" to limit.toString())
        if (from > 0) p["from"] = from.toString()
        if (to > 0) p["to"] = to.toString()
        val t = call("user.getRecentTracks", p, signed = false, post = false).getJSONObject("recenttracks")
        val attr = t.optJSONObject("@attr")
        val list = t.arrayOrOne("track").objects().mapNotNull { o ->
            val ts = o.optJSONObject("date")?.str("uts")?.toLongOrNull() ?: return@mapNotNull null
            RecentTrack(o.optJSONObject("artist")?.str("#text").orEmpty(), o.str("name"), o.optJSONObject("album")?.str("#text").orEmpty(), ts)
        }
        return RecentPage(list, attr?.str("totalPages")?.toIntOrNull() ?: 1, attr?.str("total")?.toIntOrNull() ?: list.size)
    }

    /** An artist's top tags with Last.fm's weight (0..100), best first. Empty if Last.fm doesn't know the artist. */
    fun artistTopTags(artist: String): List<Pair<String, Int>> {
        val json = try {
            call("artist.getTopTags", mapOf("artist" to artist, "autocorrect" to "1"), signed = false, post = false)
        } catch (e: LastfmException) {
            if (e.code == 6) return emptyList()
            throw e
        }
        return json.optJSONObject("toptags")?.arrayOrOne("tag")?.objects().orEmpty()
            .map { it.str("name") to (it.str("count").toIntOrNull() ?: 0) }
    }

    /** Everyone's all-time listeners and plays of an artist; null if Last.fm doesn't know it. */
    fun artistCounts(artist: String): GlobalCount? =
        info("artist.getInfo", linkedMapOf("artist" to artist))?.optJSONObject("artist")?.optJSONObject("stats")?.let { counts(it) }

    /** The same for one song. */
    fun trackCounts(artist: String, track: String): GlobalCount? =
        info("track.getInfo", linkedMapOf("artist" to artist, "track" to track))?.optJSONObject("track")?.let { counts(it) }

    /** The same for one album. */
    fun albumCounts(artist: String, album: String): GlobalCount? =
        info("album.getInfo", linkedMapOf("artist" to artist, "album" to album))?.optJSONObject("album")?.let { counts(it) }

    /** An artist's most played songs on Last.fm with everyone's plays, most played first; empty if unknown. */
    fun artistTopTracks(artist: String, limit: Int = 50): List<Pair<String, Long>> =
        info("artist.getTopTracks", linkedMapOf("artist" to artist, "limit" to limit.toString()))?.optJSONObject("toptracks")
            ?.arrayOrOne("track")?.objects().orEmpty().map { it.str("name") to (it.str("playcount").toLongOrNull() ?: 0L) }

    private fun counts(o: JSONObject): GlobalCount? {
        val listeners = o.str("listeners").toLongOrNull() ?: return null
        return GlobalCount(listeners, o.str("playcount").toLongOrNull() ?: 0L)
    }

    /** An unsigned lookup with Last.fm's spelling corrections; null when Last.fm doesn't know the thing. */
    private fun info(method: String, p: LinkedHashMap<String, String>): JSONObject? {
        p["autocorrect"] = "1"
        return try {
            call(method, p, signed = false, post = false)
        } catch (e: LastfmException) {
            if (e.code == 6) null else throw e
        }
    }

    private fun call(method: String, params: Map<String, String>, signed: Boolean, post: Boolean): JSONObject {
        val p = LinkedHashMap<String, String>()
        p["method"] = method
        p["api_key"] = apiKey
        p.putAll(params)
        if (signed) p["api_sig"] = sign(p)
        p["format"] = "json"
        val r = if (post) Http.post(base, p) else Http.get(base + "?" + Http.form(p))
        val json = try {
            JSONObject(r.body)
        } catch (_: Exception) {
            throw IOException("Last.fm answered HTTP ${r.code} without data")
        }
        if (json.has("error")) throw LastfmException(json.optInt("error"), json.str("message"))
        if (r.code >= 400) throw IOException("Last.fm answered HTTP ${r.code}")
        return json
    }
}
