package com.example.recordshelf

import com.example.recordshelf.MiniHttp.Res
import com.example.recordshelf.tidal.TidalClient
import com.example.recordshelf.tidal.TidalException
import com.example.recordshelf.tidal.TidalHit
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder
import java.util.Base64

/** Against a fake server answering in the documented TIDAL API format (JSON:API, tidal-api-oas.json). */
class TidalClientTest {
    private lateinit var server: MiniHttp
    private lateinit var client: TidalClient

    @Before
    fun setUp() {
        server = MiniHttp().start()
        client = TidalClient("cid", "csecret", server.base + "/v2", server.base + "/token", "https://login.tidal.com/authorize")
    }

    @After
    fun tearDown() = server.stop()

    private val search = """
        {"data":[{"id":"Fontaines D.C. Starburster","type":"searchResults","attributes":{"query":"Fontaines D.C. Starburster","trackingId":"t1"},
          "relationships":{"tracks":{"data":[{"id":"1001","type":"tracks"},{"id":"1002","type":"tracks"},{"id":"1003","type":"tracks"}],"links":{"self":"/x"}}}}],
         "included":[
          {"id":"1001","type":"tracks","attributes":{"title":"Starburster","version":"Live at Glastonbury","duration":"PT3M","explicit":false,"isrc":"X","popularity":0.5,
            "externalLinks":[{"href":"https://tidal.com/browse/track/1001","meta":{"type":"TIDAL_SHARING"}}]},"relationships":{"artists":{"data":[{"id":"7","type":"artists"}]}}},
          {"id":"1002","type":"tracks","attributes":{"title":"Starburster","duration":"PT3M","explicit":false,"isrc":"Y","popularity":0.9,
            "externalLinks":[{"href":"https://tidal.com/browse/track/1002","meta":{"type":"TIDAL_SHARING"}}]},"relationships":{"artists":{"data":[{"id":"7","type":"artists"}]}}},
          {"id":"1003","type":"tracks","attributes":{"title":"Starburster (Cover)"},"relationships":{"artists":{"data":[{"id":"8","type":"artists"}]}}},
          {"id":"7","type":"artists","attributes":{"name":"Fontaines D.C.","popularity":0.7}},
          {"id":"8","type":"artists","attributes":{"name":"Someone Else","popularity":0.1}}],
         "links":{"self":"/searchResults"}}
    """.trimIndent()

    private fun query(r: MiniHttp.Req) = r.query.split('&').map { it.split('=', limit = 2) }.map { URLDecoder.decode(it[0], "UTF-8") to URLDecoder.decode(it.getOrElse(1) { "" }, "UTF-8") }

    @Test
    fun pkceMatchesTheRfcExample() {
        // RFC 7636, appendix B.
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", TidalClient.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        val v = TidalClient.verifier()
        assertEquals(43, v.length)
        assertTrue(v.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        val url = client.authUrl("CH", "ST")
        assertTrue(url.startsWith("https://login.tidal.com/authorize?response_type=code&client_id=cid&redirect_uri=recordshelf%3A%2F%2Ftidal&scope=playlists.write"))
        assertTrue(url.endsWith("&code_challenge_method=S256&code_challenge=CH&state=ST"))
    }

    @Test
    fun tokens() {
        server.handler = { r ->
            val p = r.params
            when (p["grant_type"]) {
                "client_credentials" -> if (r.headers["authorization"] == "Basic " + Base64.getEncoder().encodeToString("cid:csecret".toByteArray())) {
                    Res(200, """{"access_token":"app1","token_type":"Bearer","expires_in":86400}""")
                } else Res(401, """{"error":"invalid_client"}""")
                "authorization_code" -> if (p["client_id"] == "cid" && p["code"] == "c0de" && p["code_verifier"] == "ver" && p["redirect_uri"] == "recordshelf://tidal") {
                    Res(200, """{"access_token":"user1","token_type":"Bearer","scope":"playlists.write","expires_in":86400,"refresh_token":"ref1"}""")
                } else Res(400, """{"error":"invalid_grant","error_description":"Invalid code"}""")
                "refresh_token" -> if (p["refresh_token"] == "ref1") Res(200, """{"access_token":"user2","token_type":"Bearer","expires_in":86400}""")
                else Res(400, """{"error":"invalid_grant","error_description":"Refresh token expired"}""")
                else -> Res(400, "{}")
            }
        }
        val app = client.appToken(1_000)
        assertEquals("app1", app.access)
        assertEquals(1_000 + 86_400_000 - 60_000, app.expiresAt)
        val user = client.exchange("c0de", "ver", nowMs = 0)
        assertEquals("user1", user.access)
        assertEquals("ref1", user.refresh)
        val again = client.refresh("ref1", 0)
        assertEquals("user2", again.access)
        assertEquals("keeps the refresh token when none comes back", "ref1", again.refresh)
        try {
            client.refresh("old", 0)
            fail()
        } catch (e: TidalException) {
            assertTrue(e.authFailed)
            assertEquals("Refresh token expired", e.message)
        }
    }

    @Test
    fun searchFindsTheRightSong() {
        server.handler = { r -> if (r.path == "/v2/searchResults") Res(200, search) else Res(404, "") }
        val hits = client.search("app1", "Fontaines D.C. Starburster", "tracks", "DE")
        val q = query(server.requests.last())
        assertTrue(("filter[query]" to "Fontaines D.C. Starburster") in q)
        assertTrue(("countryCode" to "DE") in q)
        assertTrue(("include" to "tracks") in q && ("include" to "tracks.artists") in q)
        assertEquals("Bearer app1", server.requests.last().headers["authorization"])
        assertEquals(listOf("1001", "1002", "1003"), hits.map { it.id })
        assertEquals(listOf("Fontaines D.C."), hits[0].artists)
        val best = TidalClient.bestTrack(hits, "Fontaines D.C.", "Starburster")!!
        assertEquals("the studio version, not the live one", "1002", best.id)
        assertEquals("https://tidal.com/browse/track/1002", best.link)
        assertEquals("1001", TidalClient.bestTrack(hits, "Fontaines D.C.", "Starburster (Live at Glastonbury)")!!.id)
        assertNull("another artist's song of that name doesn't count", TidalClient.bestTrack(hits, "IDLES", "Starburster"))
    }

    @Test
    fun searchWithoutNestedIncludesStillMatchesTitles() {
        val plain = JSONObject(search).apply {
            val inc = getJSONArray("included")
            for (i in 0 until inc.length()) inc.getJSONObject(i).remove("relationships")
        }.toString()
        server.handler = { r -> if (r.query.contains("tracks.artists")) Res(400, """{"errors":[{"detail":"Invalid include"}]}""") else Res(200, plain) }
        val hits = client.search("app1", "Fontaines D.C. Starburster", "tracks", "DE")
        assertEquals(2, server.requests.size)
        assertTrue(hits.all { it.artists.isEmpty() })
        assertEquals("1002", TidalClient.bestTrack(hits, "Fontaines D.C.", "Starburster")!!.id)
        assertEquals("no link from the API: built from the id", TidalClient.link("tracks", "1003"), hits[2].link)
    }

    @Test
    fun albumsAndArtists() {
        val hits = listOf(
            TidalHit("1", "albums", "Romance (Deluxe Edition)", "", listOf("Fontaines D.C."), "l1"),
            TidalHit("2", "albums", "Romance", "", listOf("Other Band"), "l2"),
        )
        assertEquals("1", TidalClient.bestAlbum(hits, "Fontaines D.C.", "Romance")!!.id)
        val artists = listOf(TidalHit("9", "artists", "Fontaines DC", "", emptyList(), "a9"), TidalHit("7", "artists", "Fontaines D.C.", "", emptyList(), "a7"))
        assertEquals("7", TidalClient.bestArtist(artists, "Fontaines D.C.")!!.id)
    }

    @Test
    fun createPlaylistAndAddSongsFiftyAtATime() {
        server.handler = { r ->
            when {
                r.path == "/v2/playlists" -> Res(201, """{"data":{"id":"pl-1","type":"playlists","attributes":{"name":"Lost favorites","accessType":"UNLISTED",
                    "externalLinks":[{"href":"https://tidal.com/browse/playlist/pl-1","meta":{"type":"TIDAL_SHARING"}}]}},"links":{"self":"/playlists/pl-1"}}""")
                r.path == "/v2/playlists/pl-1/relationships/items" -> Res(200, """{"data":[],"links":{"self":"/x"}}""")
                else -> Res(404, "")
            }
        }
        val (id, link) = client.createPlaylist("user1", "Lost favorites", "Artists you loved who have gone quiet")
        assertEquals("pl-1", id)
        assertEquals("https://tidal.com/browse/playlist/pl-1", link)
        val create = server.requests[0]
        assertEquals("application/vnd.api+json", create.headers["content-type"])
        val attrs = JSONObject(create.body).getJSONObject("data").also { assertEquals("playlists", it.getString("type")) }.getJSONObject("attributes")
        assertEquals("Lost favorites", attrs.getString("name"))
        assertEquals("UNLISTED", attrs.getString("accessType"))

        client.addTracks("user1", "pl-1", (1..120).map { "t$it" })
        val adds = server.requests.drop(1)
        assertEquals(listOf(50, 50, 20), adds.map { JSONObject(it.body).getJSONArray("data").length() })
        val first = JSONObject(adds[0].body).getJSONArray("data").getJSONObject(0)
        assertEquals("t1", first.getString("id"))
        assertEquals("tracks", first.getString("type"))
        assertEquals("t120", JSONObject(adds[2].body).getJSONArray("data").getJSONObject(19).getString("id"))
    }

    @Test
    fun errorsCarryTheirMeaning() {
        server.handler = { r ->
            if (r.path == "/v2/searchResults") Res(429, """{"errors":[{"status":"429","title":"Too many requests"}]}""", mapOf("Retry-After" to "3"))
            else Res(401, """{"errors":[{"status":"401","detail":"Expired token"}]}""")
        }
        try {
            client.search("app1", "x", "tracks", "DE")
            fail()
        } catch (e: TidalException) {
            assertEquals(429, e.code)
            assertEquals(3, e.retryAfterSec)
        }
        try {
            client.createPlaylist("old", "n", "d")
            fail()
        } catch (e: TidalException) {
            // The JVM's HttpURLConnection drops the body of a 401 to a streamed POST; the code is what counts.
            assertTrue(e.authFailed)
        }
    }
}
