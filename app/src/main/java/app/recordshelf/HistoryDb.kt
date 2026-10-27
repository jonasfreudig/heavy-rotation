package app.recordshelf

import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.recordshelf.lastfm.RecentTrack
import app.recordshelf.scrobble.Listen

/**
 * The Last.fm history on the phone (SQLite), plus two small caches for the stats: artists'
 * genre tags and album cover URLs. A scrobble is identified by time, artist and track, so
 * importing the same page twice changes nothing.
 */
class HistoryDb(ctx: Context) : SQLiteOpenHelper(ctx, "history.db", null, 2), BackupDb {
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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createVinyl(db)
    }

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
        return n
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
}
