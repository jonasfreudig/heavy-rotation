package com.example.recordshelf

import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.recordshelf.lastfm.RecentTrack

/**
 * The Last.fm history on the phone (SQLite), plus two small caches for the stats: artists'
 * genre tags and album cover URLs. A scrobble is identified by time, artist and track, so
 * importing the same page twice changes nothing.
 */
class HistoryDb(ctx: Context) : SQLiteOpenHelper(ctx, "history.db", null, 1), BackupDb {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE scrobbles (ts INTEGER NOT NULL, artist TEXT NOT NULL, track TEXT NOT NULL, " +
                "album TEXT NOT NULL DEFAULT '', PRIMARY KEY (ts, artist, track)) WITHOUT ROWID",
        )
        db.execSQL("CREATE TABLE artist_tags (artist TEXT PRIMARY KEY, tags TEXT NOT NULL, fetched INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE album_images (album TEXT PRIMARY KEY, url TEXT NOT NULL, fetched INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

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

    override fun forEach(fn: (Long, String, String, String) -> Unit) {
        readableDatabase.rawQuery("SELECT ts, artist, track, album FROM scrobbles ORDER BY ts", null).use { c ->
            while (c.moveToNext()) fn(c.getLong(0), c.getString(1), c.getString(2), c.getString(3))
        }
    }

    override fun clear() {
        writableDatabase.execSQL("DELETE FROM scrobbles")
    }

    /** Genre keys per artist key ([com.example.recordshelf.history.Genres.artistKey]); empty lists are kept so they aren't looked up again. */
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

    override fun saveImage(albumKey: String, url: String) {
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO album_images (album, url, fetched) VALUES (?, ?, ?)",
            arrayOf(albumKey, url, System.currentTimeMillis()),
        )
    }
}
