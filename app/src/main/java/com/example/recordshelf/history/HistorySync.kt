package com.example.recordshelf.history

import com.example.recordshelf.lastfm.RecentPage
import com.example.recordshelf.lastfm.RecentTrack

/** Where the scrobble history is kept (SQLite in the app, memory in tests). */
interface HistoryStore {
    /** Adds scrobbles, skipping ones already stored (same time, artist and track). Returns how many were new. */
    fun insert(rows: List<RecentTrack>): Int
    fun count(): Int

    /** Newest / oldest scrobble time (Unix seconds), 0 when empty. */
    fun newest(): Long
    fun oldest(): Long

    /** Every scrobble, oldest first. */
    fun forEach(fn: (ts: Long, artist: String, track: String, album: String) -> Unit)
    fun clear()
}

class MemoryStore : HistoryStore {
    private val rows = java.util.TreeMap<String, RecentTrack>()
    private fun key(r: RecentTrack) = "%012d\u0000%s\u0000%s".format(r.ts, r.artist, r.title)

    @Synchronized
    override fun insert(rows: List<RecentTrack>): Int = rows.count { this.rows.putIfAbsent(key(it), it) == null }

    @Synchronized
    override fun count() = rows.size

    @Synchronized
    override fun newest() = rows.lastEntry()?.value?.ts ?: 0L

    @Synchronized
    override fun oldest() = rows.firstEntry()?.value?.ts ?: 0L

    @Synchronized
    override fun forEach(fn: (Long, String, String, String) -> Unit) = rows.values.forEach { fn(it.ts, it.artist, it.title, it.album) }

    @Synchronized
    override fun clear() = rows.clear()
}

/**
 * Copies Last.fm history into a [HistoryStore], newest first, 200 at a time. Instead of
 * deep page numbers (slow and unreliable on Last.fm for long histories) it always asks
 * for the first page below a moving time cursor, so an interrupted import resumes from
 * the saved cursor. Works whether Last.fm treats the range ends as inclusive or not:
 * the overlap is deduplicated by the store.
 *
 * [fetch] is user.getRecentTracks(from, to, page 1, limit [pageSize]).
 */
class HistorySync(
    private val fetch: (from: Long, to: Long) -> RecentPage,
    private val store: HistoryStore,
    private val pageSize: Int = 200,
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
) {
    class Progress(val fetched: Int, val total: Int, val next: Long)

    /**
     * Fetches the scrobbles from [from] (0: since the very first) up to [cursor], going back
     * in time. After each page [onPage] gets the progress and the cursor to resume from
     * (0 once done). Returns how many scrobbles were new.
     */
    fun walk(from: Long, cursor: Long, cancelled: () -> Boolean = { false }, onPage: (Progress) -> Unit = {}): Int {
        var to = cursor
        var added = 0
        var fetched = 0
        var total = -1
        var prevOldest = Long.MAX_VALUE
        while (!cancelled()) {
            val page = fetch(from, to)
            if (total < 0) total = page.total
            if (page.tracks.isEmpty()) {
                onPage(Progress(fetched, total, 0))
                break
            }
            added += store.insert(page.tracks)
            fetched += page.tracks.size
            val oldest = page.tracks.minOf { it.ts }
            val last = page.tracks.size < pageSize || page.pages <= 1 || (from > 0 && oldest <= from)
            // Ask again from just above the oldest time, so scrobbles sharing that second aren't
            // lost if "to" is exclusive; step past it if that made no progress.
            val next = if (oldest < prevOldest) oldest + 1 else oldest - 1
            prevOldest = oldest
            onPage(Progress(fetched, total, if (last) 0 else next))
            if (last) break
            to = next
            pause(250)
        }
        return added
    }
}
