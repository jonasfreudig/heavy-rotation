package com.example.recordshelf

import com.example.recordshelf.MiniHttp.Companion.fixture
import com.example.recordshelf.MiniHttp.Res
import com.example.recordshelf.discogs.DiscogsClient
import com.example.recordshelf.discogs.DiscogsException
import com.example.recordshelf.discogs.Names
import com.example.recordshelf.discogs.RateLimiter
import com.example.recordshelf.discogs.Tracklist
import com.example.recordshelf.mb.MusicBrainzClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.time.OffsetDateTime

class DiscogsTest {
    private lateinit var server: MiniHttp
    private val slept = ArrayList<Long>()
    private lateinit var limiter: RateLimiter

    @Before
    fun setUp() {
        server = MiniHttp().start()
        limiter = RateLimiter(55, System::currentTimeMillis) { slept += it }
    }

    @After
    fun tearDown() = server.stop()

    private fun client(token: String? = "tok-abc") = DiscogsClient(token, server.base, limiter)

    @Test
    fun identitySendsTokenAndUserAgent() {
        server.handler = { Res(200, """{"id":1,"username":"vinylfan","resource_url":"x","consumer_name":"x"}""") }
        assertEquals("vinylfan", client().identity())
        val r = server.requests.single()
        assertEquals("/oauth/identity", r.path)
        assertEquals("Discogs token=tok-abc", r.headers["authorization"])
        assertTrue(r.headers["user-agent"]!!.startsWith("RecordShelf/"))
    }

    @Test
    fun collectionPagesAreJoinedAndCopiesMerged() {
        server.handler = { r ->
            val page = r.params["page"]
            Res(200, fixture(if (page == "2") "discogs_collection_p2.json" else "discogs_collection_p1.json"), mapOf("X-Discogs-Ratelimit-Remaining" to "58"))
        }
        val progress = ArrayList<Triple<Int, Int, Int>>()
        val list = client().collection("vinylfan") { n, p, ps -> progress += Triple(n, p, ps) }
        assertEquals("two copies of Rumours become one tile", 2, list.size)
        assertEquals(listOf(Triple(2, 1, 2), Triple(3, 2, 2)), progress)
        val first = server.requests.first()
        assertEquals("/users/vinylfan/collection/folders/0/releases", first.path)
        assertEquals("100", first.params["per_page"])
        assertEquals("added", first.params["sort"])
        assertEquals("desc", first.params["sort_order"])

        val rumours = list[0]
        assertEquals(526351L, rumours.releaseId)
        assertEquals("Fleetwood Mac", rumours.artist)
        assertEquals("Vinyl, LP, Album", rumours.format)
        assertEquals("Warner Bros. Records · BSK 3010", rumours.label)
        assertEquals(OffsetDateTime.parse("2024-06-22T08:43:47-07:00").toInstant().toEpochMilli(), rumours.addedMs)
        assertEquals("https://i.discogs.com/cover-rumours.jpeg", rumours.cover)
        assertEquals(listOf("Pop Rock", "Soft Rock", "AOR"), rumours.styles)

        val sg = list[1]
        assertEquals("Simon & Garfunkel", sg.artist)
        assertEquals("2×Vinyl, LP, Album, Reissue", sg.format)
        assertEquals("Columbia · KCS 9914", sg.label)
    }

    @Test
    fun waitsAndRetriesWhenRateLimited() {
        var calls = 0
        server.handler = { if (calls++ == 0) Res(429, """{"message":"You are making requests too quickly."}""") else Res(200, """{"username":"vinylfan"}""") }
        assertEquals("vinylfan", client().identity())
        assertEquals(2, server.requests.size)
        assertEquals(listOf(10_000L), slept)
    }

    @Test
    fun badTokenIs401() {
        server.handler = { Res(401, """{"message":"You must authenticate to access this resource."}""") }
        try {
            client("wrong").identity()
            fail()
        } catch (e: DiscogsException) {
            assertEquals(401, e.status)
        }
    }

    @Test
    fun releaseFromRealResponse() {
        server.handler = { Res(200, fixture("discogs_release_526351.json")) }
        val r = client(null).release(526351)
        assertNull("no token, no header", server.requests.single().headers["authorization"])
        assertEquals("Rumours", r.title)
        assertEquals("Fleetwood Mac", r.artist)
        assertEquals(1977, r.year)
        assertEquals("Warner Bros. Records · BSK 3010", r.label)
        assertEquals("Vinyl, LP, Album", r.format)
        assertEquals(listOf("A", "B"), r.sides)
        assertEquals(11, r.tracks.size)
        assertTrue(r.cover.startsWith("https://i.discogs.com/"))
        assertTrue(r.uri.contains("526351"))
    }

    @Test
    fun limiterKeepsToTheMinuteWindow() {
        var now = 0L
        val waits = ArrayList<Long>()
        val l = RateLimiter(3, { now }) { waits += it; now += it }
        repeat(3) {
            l.acquire()
            now += 1000
        }
        l.acquire()
        assertEquals(listOf(57_000L), waits)
    }

    @Test
    fun limiterSlowsDownWhenDiscogsSaysFewAreLeft() {
        var now = 0L
        val waits = ArrayList<Long>()
        val l = RateLimiter(55, { now }) { waits += it; now += it }
        l.acquire()
        l.report(1)
        now += 5000
        l.acquire()
        assertEquals(listOf(55_000L), waits)
    }

    // ---- tracklists ----

    @Test
    fun tracklistOfRumours() {
        val j = JSONObject(fixture("discogs_release_526351.json"))
        val t = Tracklist.parse(j.getJSONArray("tracklist"), "Fleetwood Mac")
        assertEquals(11, t.size)
        assertEquals(6, t.count { it.side == "A" })
        assertEquals(5, t.count { it.side == "B" })
        assertEquals("Dreams", t[1].title)
        assertEquals(254, t[1].durSec)
        assertEquals("discogs", t[1].durSrc)
        assertTrue(t.all { it.artist == "Fleetwood Mac" })
    }

    @Test
    fun tracklistOfASingleWithLetterPositions() {
        val j = JSONObject(fixture("discogs_release_249504.json"))
        val t = Tracklist.parse(j.getJSONArray("tracklist"), "Rick Astley")
        assertEquals(listOf("A", "B"), t.map { it.side })
        assertEquals(listOf(212, 210), t.map { it.durSec })
    }

    @Test
    fun headingsSuitesAndCompilationArtists() {
        val tl = JSONArray(
            """[
              {"type_":"heading","position":"","title":"Side One","duration":""},
              {"type_":"track","position":"A1","title":"Rockfort Rock","duration":"3:01","artists":[{"name":"Sound Dimension","join":""}]},
              {"type_":"index","position":"","title":"Suite","duration":"","sub_tracks":[
                {"type_":"track","position":"A2a","title":"Part One","duration":"2:00"},
                {"type_":"track","position":"A2b","title":"Part Two","duration":""}]},
              {"type_":"index","position":"B1","title":"Medley","duration":"","sub_tracks":[
                {"type_":"track","position":"B1a","title":"One","duration":"1:30"},
                {"type_":"track","position":"B1b","title":"Two","duration":"2:00"}]},
              {"type_":"track","position":"B2","title":"   ","duration":"1:00"}
            ]""",
        )
        val t = Tracklist.parse(tl, "Various Artists")
        assertEquals(listOf("Rockfort Rock", "Part One", "Part Two", "Medley"), t.map { it.title })
        assertEquals("Sound Dimension", t[0].artist)
        assertEquals("Various Artists", t[1].artist)
        assertEquals(listOf("A", "A", "A", "B"), t.map { it.side })
        assertEquals(0, t[2].durSec)
        assertEquals("", t[2].durSrc)
        assertEquals("a medley with its own position is one track, as long as its parts", 210, t[3].durSec)
    }

    @Test
    fun sides() {
        assertEquals("A", Tracklist.sideOf("A1"))
        assertEquals("B", Tracklist.sideOf("b2"))
        assertEquals("AA", Tracklist.sideOf("AA"))
        assertEquals("C", Tracklist.sideOf("C10"))
        assertEquals("A", Tracklist.sideOf("A1a"))
        assertEquals("Disc 1", Tracklist.sideOf("1-3"))
        assertEquals("", Tracklist.sideOf("7"))
        assertEquals("", Tracklist.sideOf(""))
    }

    @Test
    fun artistNames() {
        assertEquals("Nirvana", Names.clean("Nirvana (2)"))
        assertEquals("Earth, Wind & Fire", Names.clean("Earth, Wind & Fire"))
        assertEquals("A, B Feat. C", Names.join(JSONArray("""[{"name":"A","join":","},{"name":"B (4)","join":"Feat."},{"name":"C","join":""}]""")))
        assertEquals("Various Artists", Names.join(JSONArray("""[{"name":"Various","join":""}]""")))
        assertEquals("", Names.join(null))
    }

    // ---- MusicBrainz ----

    @Test
    fun musicBrainzLookupByDiscogsLink() {
        server.handler = { r ->
            when (r.path) {
                "/url" -> if (r.params["resource"] == "https://www.discogs.com/release/526351") Res(200, fixture("mb_url_526351.json")) else Res(404, """{"error":"Not Found"}""")
                "/release/081ea37e-db59-4332-8cd2-ad020cb93af6" -> Res(200, fixture("mb_release_081ea37e.json"))
                else -> Res(404, "")
            }
        }
        val mb = MusicBrainzClient(server.base, minGapMs = 0) { }
        val id = mb.releaseForDiscogs(526351)
        assertEquals("081ea37e-db59-4332-8cd2-ad020cb93af6", id)
        val tracks = mb.tracks(id!!)
        assertEquals(11, tracks.size)
        assertEquals("Don’t Stop", tracks[3].title)
        assertEquals(191, tracks[3].durSec)
        assertEquals("ONE 1", tracks[0].number)
        assertNull(mb.releaseForDiscogs(1))
        assertTrue(server.requests.all { it.headers["user-agent"]!!.startsWith("RecordShelf/") })
    }

    @Test
    fun musicBrainzSearchKeepsGoodMatches() {
        server.handler = { Res(200, """{"releases":[{"id":"r1","score":100},{"id":"r2","score":91},{"id":"r3","score":60}]}""") }
        val mb = MusicBrainzClient(server.base, minGapMs = 0) { }
        assertEquals(listOf("r1", "r2"), mb.search("Fleetwood Mac", "Rumours \"77\""))
        assertEquals("release:\"Rumours \\\"77\\\"\" AND artist:\"Fleetwood Mac\"", server.requests.single().params["query"])
    }
}
