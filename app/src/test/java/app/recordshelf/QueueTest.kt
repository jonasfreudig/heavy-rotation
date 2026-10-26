package app.recordshelf

import app.recordshelf.lastfm.LastfmException
import app.recordshelf.lastfm.Scrobble
import app.recordshelf.lastfm.ScrobbleResult
import app.recordshelf.scrobble.ActivityLog
import app.recordshelf.scrobble.QueuedScrobble
import app.recordshelf.scrobble.ScrobbleQueue
import app.recordshelf.scrobble.ScrobbleSender
import app.recordshelf.util.JsonFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class QueueTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun file() = JsonFile(File(tmp.root, "queue.json"))
    private fun q(i: Int, ts: Long = 1_700_000_000L + i) = QueuedScrobble("id$i", Scrobble("Artist", "Track $i", "Album", timestamp = ts), "vinyl", 0)
    private val ok = ScrobbleSender { b -> b.map { ScrobbleResult(true) } }

    private class Log {
        val entries = ArrayList<Pair<String, String>>()
        val fn: (QueuedScrobble, String, String) -> Unit = { q, status, _ -> entries += q.id to status }
    }

    @Test
    fun sendsOldestFirstInBatchesOfFifty() {
        val queue = ScrobbleQueue(file())
        queue.add((1..120).map { q(it, 2_000_000_000L - it) })
        val batches = ArrayList<List<Scrobble>>()
        val log = Log()
        val r = queue.flush({ b -> batches += b; b.map { ScrobbleResult(true) } }, log.fn)
        assertEquals(listOf(50, 50, 20), batches.map { it.size })
        val sent = batches.flatten().map { it.timestamp }
        assertEquals(sent.sorted(), sent)
        assertEquals(120, r.sent)
        assertEquals(0, r.waiting)
        assertNull(r.error)
        assertEquals(120, log.entries.count { it.second == "sent" })
        assertEquals(0, ScrobbleQueue(file()).size())
    }

    @Test
    fun offlineKeepsEverything() {
        val queue = ScrobbleQueue(file())
        queue.add(listOf(q(1), q(2), q(3)))
        val log = Log()
        val r = queue.flush({ throw IOException("no network") }, log.fn)
        assertTrue(r.retry)
        assertEquals(3, r.waiting)
        assertTrue(log.entries.isEmpty())
        assertEquals(3, ScrobbleQueue(file()).size())
    }

    @Test
    fun progressIsSavedBetweenBatches() {
        val queue = ScrobbleQueue(file())
        queue.add((1..70).map { q(it) })
        var calls = 0
        val r = queue.flush({ b -> if (calls++ == 0) b.map { ScrobbleResult(true) } else throw IOException("lost connection") }, Log().fn)
        assertEquals(50, r.sent)
        assertEquals(20, r.waiting)
        assertEquals("what was sent is gone from disk too", 20, ScrobbleQueue(file()).size())
    }

    @Test
    fun ignoredScrobblesAreDroppedWithTheirReason() {
        val queue = ScrobbleQueue(file())
        queue.add(listOf(q(1), q(2)))
        val reasons = HashMap<String, String>()
        val r = queue.flush({ listOf(ScrobbleResult(true), ScrobbleResult(false, 3, "Too old")) }) { q, status, msg -> reasons[q.id] = "$status:$msg" }
        assertEquals(1, r.sent)
        assertEquals(1, r.ignored)
        assertEquals(0, r.waiting)
        assertEquals("ignored:Too old", reasons["id2"])
    }

    @Test
    fun dailyLimitKeepsTheRestForLater() {
        val queue = ScrobbleQueue(file())
        queue.add(listOf(q(1), q(2), q(3)))
        val r = queue.flush({ b -> b.mapIndexed { i, _ -> if (i == 0) ScrobbleResult(true) else ScrobbleResult(false, 5, "Daily limit") } }, Log().fn)
        assertEquals(1, r.sent)
        assertEquals(2, r.waiting)
        assertTrue(r.retry)
        assertTrue(r.error!!.contains("Daily"))
    }

    @Test
    fun expiredSessionStopsAndKeeps() {
        val queue = ScrobbleQueue(file())
        queue.add(listOf(q(1)))
        val r = queue.flush({ throw LastfmException(9, "Invalid session key") }, Log().fn)
        assertTrue(r.needsAuth)
        assertFalse(r.retry)
        assertEquals(1, r.waiting)
    }

    @Test
    fun lastFmBusyIsRetried() {
        val queue = ScrobbleQueue(file())
        queue.add(listOf(q(1)))
        val r = queue.flush({ throw LastfmException(11, "Service Offline") }, Log().fn)
        assertTrue(r.retry)
        assertEquals(1, r.waiting)
    }

    @Test
    fun invalidParametersAreFoundOneByOne() {
        val queue = ScrobbleQueue(file())
        queue.add(listOf(q(1), q(2), q(3)))
        val log = Log()
        val r = queue.flush({ b ->
            if (b.size > 1 || b[0].track == "Track 2") throw LastfmException(6, "Invalid parameters")
            listOf(ScrobbleResult(true))
        }, log.fn)
        assertEquals(2, r.sent)
        assertEquals(1, r.failed)
        assertEquals(0, r.waiting)
        assertEquals("failed", log.entries.first { it.first == "id2" }.second)
    }

    @Test
    fun sameScrobbleIsQueuedOnce() {
        val queue = ScrobbleQueue(file())
        assertEquals(1, queue.add(listOf(q(1, 5))).size)
        assertEquals(0, queue.add(listOf(QueuedScrobble("other", Scrobble("artist", "TRACK 1", timestamp = 5), "vinyl", 0))).size)
        assertEquals(1, queue.size())
        assertEquals(5L, queue.oldestTimestamp())
        assertEquals(1, ScrobbleQueue(file()).size())
        queue.flush(ok, Log().fn)
        assertEquals(0L, queue.oldestTimestamp())
    }

    @Test
    fun activityLogKeepsStatusAcrossRestarts() {
        val f = JsonFile(File(tmp.root, "activity.json"))
        val log = ActivityLog(f, max = 3)
        (1..4).forEach { log.add(q(it), "cover", "waiting") }
        log.update("id4", "ignored", "Too old")
        log.save()
        val back = ActivityLog(f).toJson()
        assertEquals(3, back.length())
        assertEquals("id2", back.getJSONObject(0).getString("id"))
        assertEquals("ignored", back.getJSONObject(2).getString("status"))
        assertEquals("Too old", back.getJSONObject(2).getString("msg"))
    }
}
