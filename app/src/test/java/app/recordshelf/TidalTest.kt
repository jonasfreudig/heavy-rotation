package app.recordshelf

import app.recordshelf.MiniHttp.Res
import app.recordshelf.tidal.Tidal
import app.recordshelf.tidal.TidalClient
import app.recordshelf.tidal.TidalException
import app.recordshelf.tidal.TidalStore
import app.recordshelf.util.JsonFile
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.URLDecoder

class TidalTest {
    private class MemStore : TidalStore {
        override var tidalClientId = "cid"
        override var tidalSecret = "sec"
        override var tidalAccess = ""
        override var tidalAccessExp = 0L
        override var tidalRefresh = ""
        override var tidalVerifier = ""
        override var tidalState = ""
    }

    private lateinit var server: MiniHttp
    private lateinit var cacheFile: File
    private val store = MemStore()
    private var clock = 1_000_000L
    private val slept = ArrayList<Long>()

    /** Songs the fake catalog knows: title -> (id, artist). */
    private val catalog = mapOf("Starburster" to ("1002" to "Fontaines D.C."), "Grace" to ("2001" to "IDLES"))

    private fun tidal() = Tidal(store, JsonFile(cacheFile), { "DE" }, { id, s -> TidalClient(id, s, server.base + "/v2", server.base + "/token") }, { slept += it }, { clock })

    private fun query(r: MiniHttp.Req) = r.query.split('&').associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }

    private fun searchAnswer(q: String): String {
        val hit = catalog.entries.firstOrNull { q.endsWith(it.key) }
        val refs = JSONArray()
        val included = JSONArray()
        if (hit != null) {
            val (id, artist) = hit.value
            refs.put(JSONObject().put("id", id).put("type", "tracks"))
            included.put(JSONObject("""{"id":"$id","type":"tracks","attributes":{"title":"${hit.key}"},"relationships":{"artists":{"data":[{"id":"a$id","type":"artists"}]}}}"""))
            included.put(JSONObject("""{"id":"a$id","type":"artists","attributes":{"name":"$artist"}}"""))
        }
        return JSONObject().put("data", JSONArray().put(JSONObject().put("id", q).put("type", "searchResults").put("relationships", JSONObject().put("tracks", JSONObject().put("data", refs)))))
            .put("included", included).put("links", JSONObject().put("self", "/x")).toString()
    }

    @Before
    fun setUp() {
        server = MiniHttp().start()
        cacheFile = File.createTempFile("tidal", ".json").also { it.delete() }
        server.handler = { r ->
            when {
                r.path == "/token" && r.params["grant_type"] == "client_credentials" -> Res(200, """{"access_token":"app","expires_in":3600}""")
                r.path == "/token" && r.params["grant_type"] == "authorization_code" -> Res(200, """{"access_token":"user","expires_in":3600,"refresh_token":"ref"}""")
                r.path == "/token" && r.params["grant_type"] == "refresh_token" -> Res(200, """{"access_token":"user2","expires_in":3600}""")
                r.path == "/v2/searchResults" -> Res(200, searchAnswer(query(r).getValue("filter[query]")))
                r.path == "/v2/playlists" -> Res(201, """{"data":{"id":"pl9","type":"playlists","attributes":{"name":"x"}},"links":{"self":"/p"}}""")
                r.path.endsWith("/relationships/items") -> Res(200, """{"data":[],"links":{"self":"/i"}}""")
                else -> Res(404, "")
            }
        }
    }

    @After
    fun tearDown() {
        server.stop()
        cacheFile.delete()
    }

    private fun count(path: String) = server.requests.count { it.path == path }

    @Test
    fun findsOnceThenFromTheCache() {
        val t = tidal()
        assertEquals("1002", t.find("tracks", "Fontaines D.C.", "Starburster")!!.id)
        assertEquals("https://tidal.com/browse/track/1002", t.find("tracks", "Fontaines D.C.", "Starburster")!!.link)
        assertEquals(1, count("/v2/searchResults"))
        assertEquals("one app token for both", 1, count("/token"))
        // A fresh start reads the cache file; spelling and "(Remastered)" don't matter.
        assertEquals("1002", tidal().find("tracks", "fontaines d.c.", "Starburster (Remastered)")!!.id)
        assertEquals(1, count("/v2/searchResults"))
        // Not on TIDAL: searched with and without the addition, then remembered for a while.
        assertNull(t.find("tracks", "Nobody", "Song (Live)"))
        val searches = count("/v2/searchResults")
        assertNull(t.find("tracks", "Nobody", "Song (Live)"))
        assertEquals(searches, count("/v2/searchResults"))
        clock += Tidal.MISS_TTL_MS + 1
        t.find("tracks", "Nobody", "Song (Live)")
        assertTrue(count("/v2/searchResults") > searches)
    }

    @Test
    fun waitsWhenTidalAsksToSlowDown() {
        var first = true
        val normal = server.handler
        server.handler = { r ->
            if (r.path == "/v2/searchResults" && first) {
                first = false
                Res(429, """{"errors":[{"status":"429"}]}""", mapOf("Retry-After" to "2"))
            } else normal(r)
        }
        assertEquals("2001", tidal().find("tracks", "IDLES", "Grace")!!.id)
        assertEquals(listOf(2000L), slept)
    }

    @Test
    fun loginNeedsTheMatchingState() {
        val t = tidal()
        val url = t.startLogin()
        val state = store.tidalState
        assertTrue(url.contains("state=$state"))
        assertTrue(url.contains("code_challenge=" + TidalClient.challenge(store.tidalVerifier)))
        try {
            t.finishLogin("recordshelf://tidal?code=abc&state=wrong")
            fail()
        } catch (e: TidalException) {
            assertFalse(t.loggedIn)
        }
        try {
            t.finishLogin("recordshelf://tidal?error=access_denied&error_description=The+user+said+no&state=$state")
            fail()
        } catch (e: TidalException) {
            assertEquals("The user said no", e.message)
        }
        t.finishLogin("recordshelf://tidal?code=abc&state=$state")
        assertTrue(t.loggedIn)
        assertEquals("user", store.tidalAccess)
        assertEquals("ref", store.tidalRefresh)
        assertEquals("", store.tidalVerifier)
    }

    @Test
    fun playlistWithTheSongsTidalHas() {
        store.tidalRefresh = "ref"
        val t = tidal()
        val progress = ArrayList<Pair<Int, Int>>()
        val r = t.createPlaylist(
            "Lost favorites", "Artists you loved",
            listOf("Fontaines D.C." to "Starburster", "Nobody" to "Song", "IDLES" to "Grace", "Fontaines D.C." to "Starburster"),
        ) { done, found -> progress += done to found }
        assertEquals(listOf(1 to 1, 2 to 1, 3 to 2, 4 to 3), progress)
        assertEquals(2, r.added)
        assertEquals(listOf("Nobody" to "Song"), r.missing)
        assertEquals("pl9", r.id)
        assertEquals("https://tidal.com/browse/playlist/pl9", r.link)
        val add = server.requests.first { it.path == "/v2/playlists/pl9/relationships/items" }
        assertEquals("Bearer user2", add.headers["authorization"])
        assertEquals(listOf("1002", "2001"), JSONObject(add.body).getJSONArray("data").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("id") } })
    }

    @Test
    fun playlistNeedsALogin() {
        try {
            tidal().createPlaylist("x", "y", listOf("IDLES" to "Grace"))
            fail()
        } catch (e: TidalException) {
            assertTrue(e.authFailed)
        }
        assertEquals("nothing searched before signing in", 0, count("/v2/searchResults"))
    }
}
