package app.recordshelf

import app.recordshelf.MiniHttp.Companion.fixture
import app.recordshelf.MiniHttp.Res
import app.recordshelf.lastfm.LastfmClient
import app.recordshelf.lastfm.LastfmException
import app.recordshelf.lastfm.Scrobble
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException

class LastfmClientTest {
    private lateinit var server: MiniHttp
    private val key = "0123456789abcdef0123456789abcdef"
    private val secret = "fedcba9876543210fedcba9876543210"
    private lateinit var client: LastfmClient

    @Before
    fun setUp() {
        server = MiniHttp().start()
        client = LastfmClient(key, secret, server.base + "/2.0/")
    }

    @After
    fun tearDown() = server.stop()

    /** What Last.fm does on its side: recompute api_sig from the received parameters. */
    private fun validSig(p: Map<String, String>): Boolean {
        val s = p.keys.filter { it != "format" && it != "api_sig" }.sorted().joinToString("") { it + p[it] } + secret
        return LastfmClient.md5(s) == p["api_sig"]
    }

    @Test
    fun signatureMatchesReference() {
        // Expected values computed independently with Python's hashlib.
        val c = LastfmClient("abc123", "s3cret")
        assertEquals("eb7df9d8d2174c491e42da18c3c1c11a", c.sign(mapOf("method" to "auth.getSession", "api_key" to "abc123", "token" to "xyz789", "format" to "json")))
        val c2 = LastfmClient("k", "sec")
        val p = mapOf(
            "method" to "track.scrobble", "api_key" to "k", "sk" to "SK", "artist[0]" to "Motörhead",
            "track[0]" to "Ace of Spades", "timestamp[0]" to "1700000000", "album[0]" to "Rumours",
        )
        assertEquals("6ae664d62a8e43ca90fc5d9b0c36c751", c2.sign(p))
    }

    @Test
    fun webAuthFlow() {
        server.handler = { r ->
            val p = r.params
            when {
                !validSig(p) -> Res(403, """{"error":13,"message":"Invalid method signature supplied"}""")
                p["method"] == "auth.getToken" -> Res(200, """{"token":"tok123"}""")
                p["method"] == "auth.getSession" && p["token"] == "tok123" -> Res(200, """{"session":{"name":"vinylfan","key":"sk-abc","subscriber":0}}""")
                else -> Res(400, """{"error":6,"message":"Invalid parameters"}""")
            }
        }
        val token = client.getToken()
        assertEquals("tok123", token)
        assertEquals("https://www.last.fm/api/auth/?api_key=$key&token=tok123", client.authUrl(token))
        assertEquals("vinylfan" to "sk-abc", client.getSession(token))
        assertTrue(server.requests.all { it.method == "GET" && it.params["format"] == "json" })
    }

    @Test
    fun tokenNotApprovedYetIsCode14() {
        server.handler = { Res(403, """{"error":14,"message":"Unauthorized Token - This token has not been authorized"}""") }
        try {
            client.getSession("tok")
            fail()
        } catch (e: LastfmException) {
            assertEquals(14, e.code)
            assertTrue(e.needsAuth)
        }
    }

    @Test
    fun scrobbleBatchSendsIndexedParamsAndReadsIgnoredCodes() {
        server.handler = { r -> if (validSig(r.params)) Res(200, fixture("lastfm_scrobble_batch.json")) else Res(403, """{"error":13,"message":"bad sig"}""") }
        val batch = listOf(
            Scrobble("Fleetwood Mac", "Second Hand News", "Rumours", "Fleetwood Mac", 1_700_000_000, 163, 1),
            Scrobble("Fleetwood Mac", "Dreams", "Rumours", "", 1_690_000_000, 254, 2),
            Scrobble("Motörhead", "Ace of Spades", "", "", 1_700_000_300),
        )
        val res = client.scrobble("sk-abc", batch)
        val p = server.requests.single().params
        assertEquals("POST", server.requests.single().method)
        assertEquals("track.scrobble", p["method"])
        assertEquals("sk-abc", p["sk"])
        assertEquals("Motörhead", p["artist[2]"])
        assertEquals("1690000000", p["timestamp[1]"])
        assertEquals("163", p["duration[0]"])
        assertEquals("1", p["trackNumber[0]"])
        assertFalse("album artist equal to the artist is left out", p.containsKey("albumArtist[0]"))
        assertFalse(p.containsKey("album[2]"))
        assertEquals(listOf(true, false, true), res.map { it.accepted })
        assertEquals(3, res[1].ignoredCode)
        assertTrue(res[1].message.contains("2 weeks"))
    }

    @Test
    fun singleScrobbleAnswerIsAnObject() {
        server.handler = { Res(200, fixture("lastfm_scrobble_single.json")) }
        val res = client.scrobble("sk", listOf(Scrobble("Miles Davis", "So What", "Kind of Blue", timestamp = 1_700_000_000)))
        assertEquals(1, res.size)
        assertTrue(res[0].accepted)
    }

    @Test
    fun invalidSessionIsAnAuthError() {
        server.handler = { Res(403, """{"error":9,"message":"Invalid session key - Please re-authenticate"}""") }
        try {
            client.scrobble("old", listOf(Scrobble("A", "B", timestamp = 1)))
            fail()
        } catch (e: LastfmException) {
            assertEquals(9, e.code)
            assertTrue(e.needsAuth)
            assertFalse(e.retryable)
        }
    }

    @Test
    fun serviceTroubleIsRetryable() {
        server.handler = { Res(200, """{"error":11,"message":"Service Offline"}""") }
        try {
            client.updateNowPlaying("sk", Scrobble("A", "B", timestamp = 1))
            fail()
        } catch (e: LastfmException) {
            assertTrue(e.retryable)
        }
    }

    @Test(expected = IOException::class)
    fun gatewayErrorPageIsANetworkError() {
        server.handler = { Res(503, "<html>Service Unavailable</html>") }
        client.scrobble("sk", listOf(Scrobble("A", "B", timestamp = 1)))
    }

    @Test
    fun albumInfoWithOneTrackAndUserPlaycount() {
        server.handler = { Res(200, fixture("lastfm_album_info.json")) }
        val info = client.albumInfo("Rick Astley", "Never Gonna Give You Up", "vinylfan")!!
        assertEquals(1, info.tracks.size)
        assertEquals(213, info.tracks[0].durSec)
        assertEquals("1", info.tracks[0].number)
        assertEquals(42, info.userPlaycount)
        assertTrue(info.image.endsWith("300x300/x.png"))
        val p = server.requests.single().params
        assertEquals("album.getInfo", p["method"])
        assertEquals("vinylfan", p["username"])
        assertFalse("read calls are not signed", p.containsKey("api_sig"))
    }

    @Test
    fun unknownAlbumIsNull() {
        server.handler = { Res(404, """{"error":6,"message":"Album not found"}""") }
        assertEquals(null, client.albumInfo("Nobody", "Nothing"))
    }

    @Test
    fun topAlbumsPage() {
        server.handler = { Res(200, fixture("lastfm_top_albums.json")) }
        val (list, pages) = client.topAlbums("vinylfan", 1)
        assertEquals(3, pages)
        assertEquals(3, list.size)
        assertEquals("Fleetwood Mac", list[0].artist)
        assertEquals(180, list[0].playcount)
        assertEquals("overall", server.requests.single().params["period"])
    }
}
