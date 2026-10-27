package app.recordshelf

import app.recordshelf.MiniHttp.Res
import app.recordshelf.history.HistorySync
import app.recordshelf.history.MemoryStore
import app.recordshelf.lastfm.LastfmClient
import app.recordshelf.lastfm.RecentPage
import app.recordshelf.lastfm.RecentTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class HistorySyncTest {
    /** Last.fm's user.getRecentTracks over a fixed history; the range ends can be inclusive or exclusive. */
    private class FakeLastfm(var all: List<RecentTrack>, val inclusive: Boolean) {
        var calls = 0
        fun fetch(from: Long, to: Long): RecentPage {
            calls++
            val sel = all.filter {
                (from == 0L || (if (inclusive) it.ts >= from else it.ts > from)) &&
                    (to == 0L || (if (inclusive) it.ts <= to else it.ts < to))
            }.sortedByDescending { it.ts }
            return RecentPage(sel.take(200), maxOf(1, (sel.size + 199) / 200), sel.size)
        }
    }

    private val base = 1_600_000_000L

    /** 1000 scrobbles, 3 minutes apart, with a cluster of five in the same second. */
    private fun history(n: Int = 1000): List<RecentTrack> = (0 until n).map { i ->
        val ts = if (i in 398..402) base + 398 * 180 else base + i * 180L
        RecentTrack("Artist ${i % 37}", "Track $i", "Album ${i % 11}", ts)
    }

    @Test
    fun fullImportGetsEverythingWhateverTheRangeSemantics() {
        for (inclusive in listOf(true, false)) {
            val lfm = FakeLastfm(history(), inclusive)
            val store = MemoryStore()
            val pages = ArrayList<HistorySync.Progress>()
            val added = HistorySync(lfm::fetch, store) { }.walk(0, base + 10_000_000) { pages += it }
            assertEquals("inclusive=$inclusive", 1000, added)
            assertEquals(1000, store.count())
            assertTrue("few requests, inclusive=$inclusive: ${lfm.calls}", lfm.calls <= 7)
            assertEquals(1000, pages.first().total)
            assertEquals("done", 0L, pages.last().next)
        }
    }

    @Test
    fun interruptedImportResumesFromTheCursor() {
        val lfm = FakeLastfm(history(), inclusive = false)
        val store = MemoryStore()
        var cursor = 0L
        var seen = 0
        HistorySync(lfm::fetch, store) { }.walk(0, base + 10_000_000, cancelled = { seen >= 2 }) {
            seen++
            cursor = it.next
        }
        assertEquals("two pages; the boundary scrobble is read twice on purpose", 399, store.count())
        assertTrue(cursor > 0)
        HistorySync(lfm::fetch, store) { }.walk(0, cursor)
        assertEquals(1000, store.count())
    }

    @Test
    fun laterSyncsOnlyFetchWhatIsNew() {
        val lfm = FakeLastfm(history(), inclusive = true)
        val store = MemoryStore()
        val sync = HistorySync(lfm::fetch, store) { }
        sync.walk(0, base + 10_000_000)
        lfm.all = lfm.all + (1..50).map { RecentTrack("New", "Song $it", "", base + 1000 * 180L + it * 60) }
        lfm.calls = 0
        val added = sync.walk(store.newest(), base + 10_000_000)
        assertEquals(50, added)
        assertEquals(1050, store.count())
        assertEquals(1, lfm.calls)
    }

    @Test
    fun aShortPageMidHistoryDoesntEndTheImport() {
        // Last.fm now and then sends fewer than asked for, with more still to come.
        val lfm = FakeLastfm(history(), inclusive = false)
        val store = MemoryStore()
        HistorySync({ f, t -> lfm.fetch(f, t).let { p -> if (lfm.calls == 1) RecentPage(p.tracks.take(150), p.pages, p.total) else p } }, store) { }
            .walk(0, base + 10_000_000)
        assertEquals(1000, store.count())
    }

    private class State : HistorySync.RefreshState {
        override var syncedTo = 0L
        override var from = 0L
        override var to = 0L
        override var cursor = 0L
    }

    @Test
    fun refreshFindsScrobblesThatCameLate() {
        val lfm = FakeLastfm(history(), inclusive = true)
        val store = MemoryStore()
        val sync = HistorySync(lfm::fetch, store) { }
        sync.walk(0, base + 10_000_000)
        val st = State()
        val now = base + 1000 * 180L
        lfm.calls = 0
        assertEquals(0, sync.refresh(st, now, 14 * 86_400L))
        assertEquals("nothing new: one count", 1, lfm.calls)
        // A record logged afterwards for yesterday evening: older than the newest scrobble stored.
        lfm.all = lfm.all + RecentTrack("Late", "Vinyl", "", now - 86_400)
        assertEquals(1, sync.refresh(st, now + 600, 14 * 86_400L))
        assertEquals(1001, store.count())
        assertEquals(0L, st.cursor)
    }

    @Test
    fun anInterruptedRefreshLeavesNoGap() {
        val lfm = FakeLastfm(history(), inclusive = false)
        val store = MemoryStore()
        val sync = HistorySync(lfm::fetch, store) { }
        sync.walk(0, base + 10_000_000)
        val st = State()
        val back = base + 1000 * 180L
        sync.refresh(st, back, 3600)
        // Three weeks away, a thousand plays; the connection drops after the first page.
        lfm.all = lfm.all + (1..1000).map { RecentTrack("Away", "Song $it", "", back + it * 1800L) }
        var calls = 0
        val flaky = HistorySync({ f, t -> if (++calls == 3) throw IOException("offline") else lfm.fetch(f, t) }, store) { }
        try {
            flaky.refresh(st, back + 21 * 86_400L, 3600)
            fail("offline")
        } catch (_: IOException) {
        }
        assertTrue("the walk is under way", st.cursor > 0)
        sync.refresh(st, back + 21 * 86_400L + 60, 3600)
        assertEquals("the older part of the three weeks too", 2000, store.count())
        assertEquals(0L, st.cursor)
    }

    @Test
    fun countsAStretch() {
        val store = MemoryStore()
        store.insert(history())
        assertEquals(10, store.countBetween(base, base + 9 * 180))
    }

    @Test
    fun emptyHistoryEndsAtOnce() {
        val lfm = FakeLastfm(emptyList(), inclusive = true)
        assertEquals(0, HistorySync(lfm::fetch, MemoryStore()) { }.walk(0, base))
        assertEquals(1, lfm.calls)
    }

    @Test
    fun artistTopTagsFromLastfm() {
        val server = MiniHttp().start()
        try {
            server.handler = { r ->
                if (r.params["artist"] == "Nobody") Res(200, """{"error":6,"message":"The artist you supplied could not be found"}""")
                else Res(200, """{"toptags":{"tag":[{"count":100,"name":"trip-hop"},{"count":"62","name":"electronic"}],"@attr":{"artist":"Portishead"}}}""")
            }
            val c = LastfmClient("k", "s", server.base + "/2.0/")
            assertEquals(listOf("trip-hop" to 100, "electronic" to 62), c.artistTopTags("Portishead"))
            assertEquals(emptyList<Pair<String, Int>>(), c.artistTopTags("Nobody"))
            assertEquals("artist.getTopTags", server.requests.first().params["method"])
        } finally {
            server.stop()
        }
    }
}
