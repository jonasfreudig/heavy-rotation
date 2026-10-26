package app.recordshelf.tidal

import app.recordshelf.net.Http
import app.recordshelf.net.HttpResponse
import app.recordshelf.util.Text
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** A song, album or artist in the TIDAL catalog: its id, title (or name), version, credited artists and share link. */
data class TidalHit(val id: String, val type: String, val title: String, val version: String, val artists: List<String>, val link: String)

/** An access token; [refresh] is only set for a user's login. [expiresAt] is in ms, a minute early. */
data class TidalToken(val access: String, val expiresAt: Long, val refresh: String = "")

class TidalException(val code: Int, message: String, val retryAfterSec: Int = 0, val oauthError: String = "") : Exception(message) {
    /** The token or the login is no longer valid. */
    val authFailed get() = code == 401 || oauthError == "invalid_grant"

    /** TIDAL doesn't know this client id and secret. */
    val badClient get() = oauthError == "invalid_client" || oauthError == "unauthorized_client"
}

/**
 * The public TIDAL API (developer.tidal.com): catalog search with an app token (client
 * credentials), and creating playlists for a user who signed in (authorization code with PKCE).
 * The client id and secret come from the user's own app on the TIDAL dashboard.
 */
class TidalClient(
    private val clientId: String,
    private val clientSecret: String = "",
    private val api: String = API,
    private val tokenUrl: String = TOKEN,
    private val loginUrl: String = LOGIN,
) {
    companion object {
        const val API = "https://openapi.tidal.com/v2"
        const val TOKEN = "https://auth.tidal.com/v1/oauth2/token"
        const val LOGIN = "https://login.tidal.com/authorize"

        /** Registered as the app's redirect URI on the dashboard; MainActivity receives it. */
        const val REDIRECT = "recordshelf://tidal"
        const val SCOPES = "playlists.write"

        /** Songs per "add to playlist" request (the API's maximum). */
        const val MAX_ITEMS = 50
        private const val JSON_API = "application/vnd.api+json"
        private val random = SecureRandom()

        private fun b64url(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        /** PKCE: a random code verifier (43 url-safe characters). */
        fun verifier(): String = b64url(ByteArray(32).also { random.nextBytes(it) })

        /** PKCE: the S256 code challenge for [verifier]. */
        fun challenge(verifier: String): String = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

        fun state(): String = b64url(ByteArray(16).also { random.nextBytes(it) })

        /** A share link for something TIDAL gave no link for (type is "tracks", "albums", "artists" or "playlists"). */
        fun link(type: String, id: String) = "https://tidal.com/browse/${type.removeSuffix("s")}/$id"

        /** The hit that is this song by this artist, preferring the plain version over live or remixed ones. */
        fun bestTrack(hits: List<TidalHit>, artist: String, title: String): TidalHit? = best(hits, artist, title)

        /** The hit that is this album by this artist; "Deluxe" and "Remastered" additions don't matter. */
        fun bestAlbum(hits: List<TidalHit>, artist: String, title: String): TidalHit? = best(hits, artist, title)

        fun bestArtist(hits: List<TidalHit>, name: String): TidalHit? = hits.firstOrNull { Text.norm(it.title) == Text.norm(name) }

        private fun best(hits: List<TidalHit>, artist: String, title: String): TidalHit? {
            val wantA = Text.norm(artist)
            fun titleOk(h: TidalHit) = Text.normLoose(h.title) == Text.normLoose(title) || Text.norm(h.title) == Text.norm(title)
            fun artistOk(h: TidalHit) = h.artists.any { a -> Text.norm(a).let { it == wantA || wantA.startsWith("$it ") || it.startsWith("$wantA ") } }
            // A version ("Live", "Remix") only if the title asks for it.
            fun plain(h: TidalHit) = h.version.isBlank() || Text.norm(title).contains(Text.norm(h.version))
            val ok = hits.filter { titleOk(it) && (artistOk(it) || it.artists.isEmpty()) }
            return ok.firstOrNull { plain(it) && it.artists.isNotEmpty() } ?: ok.firstOrNull { plain(it) } ?: ok.firstOrNull()
        }
    }

    fun authUrl(challenge: String, state: String, redirect: String = REDIRECT, scopes: String = SCOPES): String =
        "$loginUrl?" + Http.form(
            linkedMapOf(
                "response_type" to "code", "client_id" to clientId, "redirect_uri" to redirect, "scope" to scopes,
                "code_challenge_method" to "S256", "code_challenge" to challenge, "state" to state,
            ),
        )

    /** A token for the catalog, without a user (client credentials). */
    fun appToken(nowMs: Long = System.currentTimeMillis()): TidalToken {
        val basic = Base64.getEncoder().encodeToString("$clientId:$clientSecret".toByteArray(Charsets.UTF_8))
        return token(Http.post(tokenUrl, mapOf("grant_type" to "client_credentials"), mapOf("Authorization" to "Basic $basic")), nowMs)
    }

    /** After the user approved: the code from the redirect for access and refresh tokens. */
    fun exchange(code: String, verifier: String, redirect: String = REDIRECT, nowMs: Long = System.currentTimeMillis()): TidalToken =
        token(
            Http.post(tokenUrl, mapOf("grant_type" to "authorization_code", "client_id" to clientId, "code" to code, "redirect_uri" to redirect, "code_verifier" to verifier)),
            nowMs,
        )

    fun refresh(refreshToken: String, nowMs: Long = System.currentTimeMillis()): TidalToken {
        val t = token(Http.post(tokenUrl, mapOf("grant_type" to "refresh_token", "client_id" to clientId, "refresh_token" to refreshToken)), nowMs)
        return if (t.refresh.isEmpty()) t.copy(refresh = refreshToken) else t
    }

    private fun token(r: HttpResponse, nowMs: Long): TidalToken {
        if (r.code !in 200..299) throw error(r)
        val o = JSONObject(r.body)
        return TidalToken(o.getString("access_token"), nowMs + o.optLong("expires_in", 3600) * 1000 - 60_000, o.optString("refresh_token"))
    }

    private fun headers(token: String) = mapOf("Authorization" to "Bearer $token", "Accept" to JSON_API)

    private fun error(r: HttpResponse): TidalException {
        val o = try {
            JSONObject(r.body)
        } catch (_: Exception) {
            JSONObject()
        }
        // JSON:API errors from the API, OAuth errors from the token endpoint.
        val msg = o.optJSONArray("errors")?.optJSONObject(0)?.let { it.optString("detail").ifEmpty { it.optString("title") } }
            ?: o.optString("error_description").ifEmpty { o.optString("error") }
        return TidalException(r.code, msg.ifEmpty { "TIDAL error ${r.code}" }, r.header("Retry-After")?.toIntOrNull() ?: 0, o.optString("error"))
    }

    private fun searchUrl(query: String, includes: List<String>, country: String) =
        "$api/searchResults?filter%5Bquery%5D=${Http.enc(query).replace("+", "%20")}&countryCode=${Http.enc(country)}&explicitFilter=INCLUDE" +
            includes.joinToString("") { "&include=${Http.enc(it)}" }

    /**
     * Songs, albums or artists ([kind]: "tracks", "albums", "artists") for [query], best first.
     * Songs and albums come with their artists' names where the API includes them.
     */
    fun search(token: String, query: String, kind: String, country: String): List<TidalHit> {
        val withArtists = if (kind == "artists") listOf(kind) else listOf(kind, "$kind.artists")
        var r = Http.get(searchUrl(query.take(256), withArtists, country), headers(token))
        // Without nested includes the titles still tell a lot.
        if (r.code == 400 && withArtists.size > 1) r = Http.get(searchUrl(query.take(256), listOf(kind), country), headers(token))
        if (r.code !in 200..299) throw error(r)
        return parseSearch(JSONObject(r.body), kind)
    }

    fun parseSearch(doc: JSONObject, kind: String): List<TidalHit> {
        val included = HashMap<String, JSONObject>()
        doc.optJSONArray("included")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { included["${it.optString("type")}/${it.optString("id")}"] = it } }
        val result = doc.optJSONArray("data")?.optJSONObject(0) ?: doc.optJSONObject("data") ?: return emptyList()
        val refs = result.optJSONObject("relationships")?.optJSONObject(kind)?.optJSONArray("data") ?: return emptyList()
        return (0 until refs.length()).mapNotNull { k ->
            val ref = refs.getJSONObject(k)
            val type = ref.optString("type")
            val id = ref.optString("id")
            val o = included["$type/$id"] ?: return@mapNotNull null
            val a = o.optJSONObject("attributes") ?: JSONObject()
            val artists = o.optJSONObject("relationships")?.optJSONObject("artists")?.optJSONArray("data")?.let { arr ->
                (0 until arr.length()).mapNotNull { included["artists/" + arr.getJSONObject(it).optString("id")]?.optJSONObject("attributes")?.optString("name") }
            }.orEmpty()
            TidalHit(id, type, a.optString(if (type == "artists") "name" else "title"), a.optString("version"), artists, shareLink(a) ?: link(type, id))
        }
    }

    private fun shareLink(attributes: JSONObject): String? {
        val l = attributes.optJSONArray("externalLinks") ?: return null
        return (0 until l.length()).mapNotNull { l.optJSONObject(it) }.firstOrNull { it.optJSONObject("meta")?.optString("type") == "TIDAL_SHARING" }?.optString("href")
    }

    /** A new playlist of the signed-in user; returns its id and link. */
    fun createPlaylist(token: String, name: String, description: String): Pair<String, String> {
        val body = JSONObject().put(
            "data",
            JSONObject().put("type", "playlists").put(
                "attributes",
                JSONObject().put("name", name.take(250)).put("description", description.take(500)).put("accessType", "UNLISTED"),
            ),
        )
        val r = Http.postBody("$api/playlists", body.toString(), JSON_API, headers(token))
        if (r.code !in 200..299) throw error(r)
        val d = JSONObject(r.body).getJSONObject("data")
        val id = d.getString("id")
        return id to (d.optJSONObject("attributes")?.let { shareLink(it) } ?: link("playlists", id))
    }

    /** Adds songs to a playlist, [MAX_ITEMS] per request, in order. */
    fun addTracks(token: String, playlistId: String, trackIds: List<String>) {
        for (chunk in trackIds.chunked(MAX_ITEMS)) {
            val data = JSONArray(chunk.map { JSONObject().put("id", it).put("type", "tracks") })
            val r = Http.postBody("$api/playlists/${Http.enc(playlistId)}/relationships/items", JSONObject().put("data", data).toString(), JSON_API, headers(token))
            if (r.code !in 200..299) throw error(r)
        }
    }
}
