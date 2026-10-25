package com.example.recordshelf

import com.example.recordshelf.MiniHttp.Companion.fixture
import com.example.recordshelf.MiniHttp.Res
import com.example.recordshelf.lastfm.LastfmClient
import com.example.recordshelf.scrobble.Compare
import com.example.recordshelf.scrobble.CompareResult
import com.example.recordshelf.scrobble.Listen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompareTest {
    private fun l(artist: String, title: String, ts: Long) = Listen(artist, title, ts)

    @Test
    fun sortsListensIntoMatchedRenamedAndMissing() {
        val ours = listOf(
            l("Khruangbin", "Maria También", 1_000),
            l("Fleetwood Mac", "Dreams", 1_300),
            l("Daft Punk, Pharrell Williams", "Get Lucky", 1_600),
            l("Air", "La femme d'argent", 2_400),
        )
        val theirs = listOf(
            l("Khruangbin", "Maria Tambien", 1_004), // accent dropped: same after normalizing
            l("Fleetwood Mac", "Dreams", 1_301),
            l("Daft Punk", "Get Lucky", 1_602), // named differently
            l("Portishead", "Roads", 2_000), // we missed this one
        )
        val r = Compare.run(ours, theirs)
        assertEquals(2, r.matched)
        assertEquals(listOf("Get Lucky" to "Get Lucky"), r.renamed.map { it.first.title to it.second.title })
        assertEquals(listOf("Roads"), r.onlyTheirs.map { it.title })
        assertEquals(listOf("La femme d'argent"), r.onlyOurs.map { it.title })
        assertEquals(4, r.theirs)
        assertEquals(4, r.ours)
    }

    @Test
    fun repeatsAreMatchedOneToOne() {
        val ours = listOf(l("A", "Loop", 1_000), l("A", "Loop", 1_200), l("A", "Loop", 1_400))
        val theirs = listOf(l("A", "Loop", 1_001), l("A", "Loop", 1_199))
        val r = Compare.run(ours, theirs)
        assertEquals(2, r.matched)
        assertEquals(listOf(1_400L), r.onlyOurs.map { it.ts })
    }

    @Test
    fun farApartIsNotTheSameListen() {
        val r = Compare.run(listOf(l("A", "B", 1_000)), listOf(l("A", "B", 2_000)))
        assertEquals(0, r.matched)
        assertEquals(1, r.onlyOurs.size)
        assertEquals(1, r.onlyTheirs.size)
    }

    @Test
    fun resultSurvivesSaving() {
        val r = Compare.run(listOf(l("A", "B", 10), l("C", "D", 500)), listOf(l("A", "B", 12), l("C", "D (Live)", 505), l("E", "F", 900)))
        val back = CompareResult.fromJson(r.toJson())
        assertEquals(r, back)
    }

    @Test
    fun recentTracksSkipNowPlaying() {
        val server = MiniHttp().start()
        try {
            server.handler = { Res(200, fixture("lastfm_recent_tracks.json")) }
            val c = LastfmClient("k", "s", server.base + "/2.0/")
            val r = c.recentTracks("vinylfan", 1_761_300_000, 1_761_400_100, 1)
            assertEquals(4, r.pages)
            assertEquals(702, r.total)
            val list = r.tracks
            assertEquals(listOf("Como Me Quieres", "Dreams"), list.map { it.title })
            assertEquals(1_761_400_000L, list[0].ts)
            assertEquals("Con Todo El Mundo", list[0].album)
            val p = server.requests.single().params
            assertEquals("user.getRecentTracks", p["method"])
            assertEquals("1761300000", p["from"])
            assertEquals("1761400100", p["to"])
            assertEquals("200", p["limit"])
            c.recentTracks("vinylfan", 0, 0, 2)
            val open = server.requests.last().params
            assertTrue("open ends are left out", "from" !in open && "to" !in open)
            assertEquals("2", open["page"])
        } finally {
            server.stop()
        }
    }
}
