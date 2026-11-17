package app.recordshelf

import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.recordshelf.history.HistoryStore
import app.recordshelf.history.ImportedPlay
import app.recordshelf.history.Overlap
import app.recordshelf.history.PlaySource
import app.recordshelf.history.mergePlays
import app.recordshelf.lastfm.RecentTrack
import app.recordshelf.scrobble.Listen

/**
 * The Last.fm history on the phone (SQLite), plus two small caches for the stats: artists'
 * genre tags and album cover URLs. A scrobble is identified by time, artist and track, so
 * importing the same page twice changes nothing.
 */
class HistoryDb(ctx: Context) : SQLiteOpenHelper(ctx, "history.db", null, 4), BackupDb {
    private companion object {
        /** Scrobbles read per query by [forEach]. */
        const val CHUNK = 5000
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE scrobbles (ts INTEGER NOT NULL, artist TEXT NOT NULL, track TEXT NOT NULL, " +
                "album TEXT NOT NULL DEFAULT '', PRIMARY KEY (ts, artist, track)) WITHOUT ROWID",
        )
        db.execSQL("CREATE TABLE artist_tags (artist TEXT PRIMARY KEY, tags TEXT NOT NULL, fetched INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE album_images (album TEXT PRIMARY KEY, url TEXT NOT NULL, fetched INTEGER NOT NULL)")
        createVinyl(db)
        createLengths(db)
        createImported(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createVinyl(db)
        if (oldVersion < 3) createLengths(db)
        if (oldVersion < 4) createImported(db)
    }

    /**
     * Plays from a music service's own export of your listening (TIDAL's), next to Last.fm's scrobbles. [est]: the
     * time is only worked out. [dup]: 1 when Last.fm has that play too (it isn't counted again), 2 while an import
     * is still writing it.
     */
    private fun createImported(db: SQLiteDatabase) = db.execSQL(
        "CREATE TABLE imported (ts INTEGER NOT NULL, artist TEXT NOT NULL, track TEXT NOT NULL, album TEXT NOT NULL DEFAULT '', " +
            "est INTEGER NOT NULL DEFAULT 0, src TEXT NOT NULL DEFAULT '', dup INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (ts, artist, track)) WITHOUT ROWID",
    )

    /** Songs' real lengths in seconds, as the player or the record reported them when this app scrobbled them. */
    private fun createLengths(db: SQLiteDatabase) = db.execSQL("CREATE TABLE track_lengths (track TEXT PRIMARY KEY, sec INTEGER NOT NULL)")

    /** Every record play scrobbled from this app; the activity log only keeps the latest. */
    private fun createVinyl(db: SQLiteDatabase) = db.execSQL(
        "CREATE TABLE vinyl (ts INTEGER NOT NULL, artist TEXT NOT NULL, track TEXT NOT NULL, " +
            "album TEXT NOT NULL DEFAULT '', PRIMARY KEY (ts, artist, track)) WITHOUT ROWID",
    )

    override fun insert(rows: List<RecentTrack>): Int {
        val db = writableDatabase
        var n = 0
        db.beginTransaction()
        try {
            val st = db.compileStatement("INSERT OR IGNORE INTO scrobbles (ts, artist, track, album) VALUES (?, ?, ?, ?)")
            for (r in rows) {
                st.bindLong(1, r.ts)
                st.bindString(2, r.artist)
                st.bindString(3, r.title)
                st.bindString(4, r.album)
                n += st.executeUpdateDelete()
                st.clearBindings()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        // New scrobbles from the time imported plays cover: which of those Last.fm has is looked at again.
        if (n > 0 && rows.minOf { it.ts } <= importedEnd() + Overlap.FAR) dedupeDue = true
        return n
    }

    // ---- imported plays ----

    /** Which imported plays Last.fm has too is worked out before the next read (at the start, and when scrobbles came or went). */
    @Volatile private var dedupeDue = true

    /** An import is writing: its rows don't count yet. */
    @Volatile private var importing = false

    /** The last imported play's time, asked once (-1: not yet). */
    @Volatile private var importedTo = -1L
    private fun importedEnd(): Long {
        if (importedTo < 0) importedTo = importedSpan().second
        return importedTo
    }

    /** A new import begins: its rows stay out of the stats until [endImport]. */
    fun beginImport() {
        importing = true
    }

    /** Adds plays of an import under way (the same play twice is kept once). */
    fun addImported(rows: List<ImportedPlay>, src: String, pending: Boolean) {
        if (rows.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val st = db.compileStatement("INSERT OR REPLACE INTO imported (ts, artist, track, album, est, src, dup) VALUES (?, ?, ?, ?, ?, ?, ?)")
            for (r in rows) {
                st.bindLong(1, r.ts)
                st.bindString(2, r.artist)
                st.bindString(3, r.track)
                st.bindString(4, r.album)
                st.bindLong(5, if (r.est) 1 else 0)
                st.bindString(6, src)
                st.bindLong(7, if (pending) 2 else 0)
                st.executeInsert()
                st.clearBindings()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        importedTo = -1
        dedupeDue = true
    }

    /**
     * The import is over. Done [ok]: what the file before it had and this one doesn't goes, and the new plays count.
     * Broken off: what it wrote so far stays next to what was there.
     */
    fun endImport(ok: Boolean) {
        if (ok) writableDatabase.execSQL("DELETE FROM imported WHERE dup != 2")
        importing = false
        dedupeImported()
    }

    override fun addImported(rows: List<ImportedPlay>, src: String) = addImported(rows, src, pending = false)

    override fun clearImported() {
        writableDatabase.execSQL("DELETE FROM imported")
        importedTo = -1
    }

    override fun forEachImported(fn: (ImportedPlay, String) -> Unit) {
        val p = Pager("imported", "", withMore = true)
        while (p.next()) fn(ImportedPlay(p.ts, p.artist, p.track, p.album, p.est), p.src)
    }

    /** Imported plays in all, and those that count (Last.fm doesn't have them). */
    fun importedCount() = DatabaseUtils.queryNumEntries(readableDatabase, "imported").toInt()
    fun importedUsed() = DatabaseUtils.longForQuery(readableDatabase, "SELECT COUNT(*) FROM imported WHERE dup = 0", null).toInt()

    /** The first and the last imported play (Unix seconds), 0 when there is none. */
    fun importedSpan(): Pair<Long, Long> =
        DatabaseUtils.longForQuery(readableDatabase, "SELECT IFNULL(MIN(ts), 0) FROM imported", null) to DatabaseUtils.longForQuery(readableDatabase, "SELECT IFNULL(MAX(ts), 0) FROM imported", null)

    /**
     * Marks the imported plays Last.fm has too ([Overlap]): only from Last.fm's first scrobble on can there be any.
     * Plays an import just wrote count from here on.
     */
    @Synchronized
    fun dedupeImported() {
        if (importing) return
        dedupeDue = false
        if (importedCount() == 0) return
        val o = Overlap()
        val any = count() > 0
        if (any) forEach { ts, artist, track, _ -> o.add(ts, artist, track) }
        val from = if (any) oldest() - Overlap.FAR else Long.MAX_VALUE
        writableDatabase.execSQL("UPDATE imported SET dup = 0 WHERE dup != 0 AND ts < ?", arrayOf<Any>(from))
        if (!any) return
        class Change(val ts: Long, val artist: String, val track: String, val dup: Int)
        val changes = ArrayList<Change>()
        readableDatabase.rawQuery("SELECT ts, artist, track, est, dup FROM imported WHERE ts >= ? ORDER BY ts, artist, track", arrayOf(from.toString())).use { c ->
            while (c.moveToNext()) {
                val dup = if (o.has(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3) != 0)) 1 else 0
                if (dup != c.getInt(4)) changes += Change(c.getLong(0), c.getString(1), c.getString(2), dup)
            }
        }
        if (changes.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val st = db.compileStatement("UPDATE imported SET dup = ? WHERE ts = ? AND artist = ? AND track = ?")
            for (ch in changes) {
                st.bindLong(1, ch.dup.toLong())
                st.bindLong(2, ch.ts)
                st.bindString(3, ch.artist)
                st.bindString(4, ch.track)
                st.executeUpdateDelete()
                st.clearBindings()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** One of the two tables read a chunk at a time, oldest first: [next] steps to a row, whose fields are then here. */
    private inner class Pager(private val table: String, private val where: String, private val withMore: Boolean = false) : PlaySource {
        override var ts = Long.MIN_VALUE
        override var artist = ""
        override var track = ""
        override var album = ""
        var est = false
        var src = ""
        private val rows = ArrayList<Array<String>>()
        private var at = 0
        private var end = false
        private var lastTs = Long.MIN_VALUE
        private var lastArtist = ""
        private var lastTrack = ""

        override fun next(): Boolean {
            if (at >= rows.size) {
                if (end) return false
                rows.clear()
                at = 0
                val cols = if (withMore) "ts, artist, track, album, est, src" else "ts, artist, track, album"
                readableDatabase.rawQuery(
                    "SELECT $cols FROM $table WHERE (ts, artist, track) > (CAST(? AS INTEGER), ?, ?) $where ORDER BY ts, artist, track LIMIT $CHUNK",
                    arrayOf(lastTs.toString(), lastArtist, lastTrack),
                ).use { c ->
                    while (c.moveToNext()) rows += Array(c.columnCount) { c.getString(it) }
                }
                if (rows.size < CHUNK) end = true
                if (rows.isEmpty()) return false
                rows.last().let { lastTs = it[0].toLong(); lastArtist = it[1]; lastTrack = it[2] }
            }
            val r = rows[at++]
            ts = r[0].toLong()
            artist = r[1]
            track = r[2]
            album = r[3]
            if (withMore) {
                est = r[4] != "0"
                src = r[5]
            }
            return true
        }
    }

    /**
     * Every play for the stats, oldest first: Last.fm's scrobbles, and with them the imported plays Last.fm
     * doesn't have.
     */
    fun forEachPlay(fn: (Long, String, String, String) -> Unit) {
        if (dedupeDue) dedupeImported()
        mergePlays(Pager("scrobbles", ""), Pager("imported", "AND dup = 0"), fn)
    }

    /** The whole listening history as the stats read it ([forEachPlay]); the counts and times are of both together. */
    val everything: HistoryStore = object : HistoryStore {
        override fun insert(rows: List<RecentTrack>) = this@HistoryDb.insert(rows)
        override fun count() = this@HistoryDb.count() + importedUsed()
        override fun newest() = maxOf(this@HistoryDb.newest(), importedSpan().second)
        override fun oldest(): Long {
            val mine = this@HistoryDb.oldest()
            val theirs = importedSpan().first
            return if (mine == 0L) theirs else if (theirs == 0L) mine else minOf(mine, theirs)
        }

        override fun forEach(fn: (ts: Long, artist: String, track: String, album: String) -> Unit) = forEachPlay(fn)
        override fun clear() = this@HistoryDb.clear()
    }

    override fun count() = DatabaseUtils.queryNumEntries(readableDatabase, "scrobbles").toInt()

    override fun newest() = DatabaseUtils.longForQuery(readableDatabase, "SELECT IFNULL(MAX(ts), 0) FROM scrobbles", null)

    override fun oldest() = DatabaseUtils.longForQuery(readableDatabase, "SELECT IFNULL(MIN(ts), 0) FROM scrobbles", null)

    override fun countBetween(from: Long, to: Long) =
        DatabaseUtils.longForQuery(readableDatabase, "SELECT COUNT(*) FROM scrobbles WHERE ts BETWEEN ? AND ?", arrayOf(from.toString(), to.toString())).toInt()

    override fun forEach(fn: (Long, String, String, String) -> Unit) {
        // A chunk at a time, each a fresh query for the rows after the last one read. An import adding
        // older scrobbles meanwhile can't shift what is read (no row comes twice), and nothing has to
        // wait for this long read: one transaction around it froze the screen for seconds.
        var ts = Long.MIN_VALUE
        var artist = ""
        var track = ""
        while (true) {
            var n = 0
            readableDatabase.rawQuery(
                "SELECT ts, artist, track, album FROM scrobbles WHERE (ts, artist, track) > (CAST(? AS INTEGER), ?, ?) ORDER BY ts, artist, track LIMIT $CHUNK",
                arrayOf(ts.toString(), artist, track),
            ).use { c ->
                while (c.moveToNext()) {
                    ts = c.getLong(0)
                    artist = c.getString(1)
                    track = c.getString(2)
                    fn(ts, artist, track, c.getString(3))
                    n++
                }
            }
            if (n < CHUNK) break
        }
    }

    override fun vinyl(): List<Listen> {
        val out = ArrayList<Listen>()
        readableDatabase.rawQuery("SELECT ts, artist, track, album FROM vinyl ORDER BY ts", null).use { c ->
            while (c.moveToNext()) out += Listen(c.getString(1), c.getString(2), c.getLong(0), c.getString(3))
        }
        return out
    }

    fun vinylCount() = DatabaseUtils.queryNumEntries(readableDatabase, "vinyl").toInt()

    override fun addVinyl(rows: List<Listen>) {
        if (rows.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val st = db.compileStatement("INSERT OR IGNORE INTO vinyl (ts, artist, track, album) VALUES (?, ?, ?, ?)")
            for (r in rows) {
                st.bindLong(1, r.ts)
                st.bindString(2, r.artist)
                st.bindString(3, r.title)
                st.bindString(4, r.album)
                st.executeUpdateDelete()
                st.clearBindings()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun clearVinyl() {
        writableDatabase.execSQL("DELETE FROM vinyl")
    }

    override fun clear() {
        writableDatabase.execSQL("DELETE FROM scrobbles")
        dedupeDue = true
    }

    /** Genre keys per artist key ([app.recordshelf.history.Genres.artistKey]); empty lists are kept so they aren't looked up again. */
    override fun tags(): Map<String, List<String>> {
        val out = HashMap<String, List<String>>()
        readableDatabase.rawQuery("SELECT artist, tags FROM artist_tags", null).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = c.getString(1).split('|').filter { it.isNotEmpty() }
        }
        return out
    }

    override fun saveTags(artistKey: String, tags: List<String>) {
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO artist_tags (artist, tags, fetched) VALUES (?, ?, ?)",
            arrayOf(artistKey, tags.joinToString("|"), System.currentTimeMillis()),
        )
    }

    /** Cover URLs per album key; "" means Last.fm had none (don't ask again). */
    override fun images(): Map<String, String> {
        val out = HashMap<String, String>()
        readableDatabase.rawQuery("SELECT album, url FROM album_images", null).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = c.getString(1)
        }
        return out
    }

    /** One album's looked-up picture, or null. */
    fun image(albumKey: String): String? =
        readableDatabase.rawQuery("SELECT url FROM album_images WHERE album = ?", arrayOf(albumKey)).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    /** Forgets the lookups that found no picture before [before] (ms), so they are tried again. */
    fun dropMissedImages(before: Long) {
        writableDatabase.execSQL("DELETE FROM album_images WHERE url = '' AND fetched < ?", arrayOf(before))
    }

    /** The same for the pictures of [keys] alone. */
    fun dropMissedImages(keys: Collection<String>, before: Long) {
        for (part in keys.chunked(400)) {
            writableDatabase.execSQL(
                "DELETE FROM album_images WHERE url = '' AND fetched < ? AND album IN (${part.joinToString(",") { "?" }})",
                arrayOf<Any>(before, *part.toTypedArray()),
            )
        }
    }

    /** Forgets looked-up pictures whose address contains [host], so they are looked up again elsewhere. */
    fun dropImagesFrom(host: String) {
        writableDatabase.execSQL("DELETE FROM album_images WHERE url LIKE ?", arrayOf("%$host%"))
    }

    override fun saveImage(albumKey: String, url: String) {
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO album_images (album, url, fetched) VALUES (?, ?, ?)",
            arrayOf(albumKey, url, System.currentTimeMillis()),
        )
    }

    /** Seconds per song key ([app.recordshelf.history.Stats.lengthKey]). */
    override fun lengths(): Map<String, Int> {
        val out = HashMap<String, Int>()
        readableDatabase.rawQuery("SELECT track, sec FROM track_lengths", null).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = c.getInt(1)
        }
        return out
    }

    override fun saveLength(trackKey: String, sec: Int) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO track_lengths (track, sec) VALUES (?, ?)", arrayOf<Any>(trackKey, sec))
    }
}
