package app.recordshelf

import app.recordshelf.history.HistoryStore
import app.recordshelf.lastfm.RecentTrack
import app.recordshelf.scrobble.Listen
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** The parts of the history database a backup carries, besides the scrobbles. */
interface BackupDb : HistoryStore {
    fun tags(): Map<String, List<String>>
    fun saveTags(artistKey: String, tags: List<String>)
    fun images(): Map<String, String>
    fun saveImage(albumKey: String, url: String)

    /** Every record play scrobbled from this app (the activity log only keeps the latest). */
    fun vinyl(): List<Listen> = emptyList()
    fun addVinyl(rows: List<Listen>) {}
    fun clearVinyl() {}
}

/**
 * Everything worth keeping in one zip file: the Last.fm history (with the genre tags and
 * covers looked up for it), the app's files (activity log, collection, hide list, tracklists,
 * release years, Last.fm counts and other caches), the settings and the page's own settings.
 *
 * Keys, tokens and logins are left out on purpose (the file may be shared or synced, and
 * connecting again takes a tap), and so are the scrobbles still waiting to be sent (the app the
 * backup came from may still send them) and a record spinning right now.
 */
object Backup {
    const val FORMAT = 1
    private const val MANIFEST = "backup.json"
    private const val SCROBBLES = "history/scrobbles.tsv"
    private const val VINYL = "history/vinyl.tsv"
    private const val TAGS = "history/tags.json"
    private const val IMAGES = "history/images.json"
    private const val PREFS = "prefs.json"
    private const val UI = "ui.json"
    private const val FILES = "files/"

    /** Files of the app that stay out: what still has to be sent, and the record spinning now. */
    val SKIP = setOf("queue.json", "spin.json")
    private const val DAMAGED = "This backup file is incomplete or damaged (cut short while it was copied?). Nothing was changed."

    /** What a restore brought back. */
    data class Restored(val scrobbles: Int, val files: Int, val prefs: JSONObject, val ui: JSONObject)

    /** Writes the backup to [out]: [prefs] are the plain settings (no secrets), [ui] the page's. Returns the number of scrobbles. */
    fun write(out: OutputStream, filesDir: File, db: BackupDb, prefs: Map<String, *>, ui: JSONObject, version: String, now: Long): Int {
        var scrobbles = 0
        val files = filesDir.walkTopDown().filter { it.isFile && it.name.endsWith(".json") && it.relativeTo(filesDir).invariantSeparatorsPath !in SKIP }.toList()
        val vinyl = db.vinyl()
        ZipOutputStream(out.buffered()).use { zip ->
            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
            // First, so a restore can tell a backup of this app before it changes anything.
            put(MANIFEST, JSONObject().put("format", FORMAT).put("app", "Record Shelf").put("version", version).put("created", now)
                .put("scrobbles", db.count()).put("files", files.size).put("vinyl", vinyl.size).toString())
            zip.putNextEntry(ZipEntry(SCROBBLES))
            val w = zip.bufferedWriter()
            db.forEach { ts, artist, track, album ->
                w.write("$ts\t${field(artist)}\t${field(track)}\t${field(album)}\n")
                scrobbles++
            }
            w.flush()
            zip.closeEntry()
            put(VINYL, vinyl.joinToString("") { "${it.ts}\t${field(it.artist)}\t${field(it.title)}\t${field(it.album)}\n" })
            put(TAGS, JSONObject(db.tags().mapValues { JSONArray(it.value) }).toString())
            put(IMAGES, JSONObject(db.images()).toString())
            put(PREFS, prefsJson(prefs).toString())
            put(UI, ui.toString())
            for (f in files) {
                zip.putNextEntry(ZipEntry(FILES + f.relativeTo(filesDir).invariantSeparatorsPath))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return scrobbles
    }

    /**
     * Reads a backup through without changing anything, and throws [IOException] unless it is a
     * whole backup of this app: every part there and readable, as many scrobbles and files as it
     * says it holds. A file cut short (by a sync, while copying) is turned away before the history
     * on the phone is touched.
     */
    fun check(input: InputStream) {
        ZipInputStream(input.buffered()).use { zip ->
            val m = manifest(zip)
            var scrobbles = 0
            var vinyl = 0
            var files = 0
            var prefs = false
            try {
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.isDirectory) continue
                    when {
                        e.name == SCROBBLES || e.name == VINYL -> {
                            val r = BufferedReader(InputStreamReader(zip))
                            var n = 0
                            while (true) if (row(r.readLine() ?: break) != null) n++
                            if (e.name == SCROBBLES) scrobbles = n else vinyl = n
                        }
                        e.name == PREFS || e.name == UI || e.name == TAGS || e.name == IMAGES -> {
                            JSONObject(zip.readBytes().decodeToString())
                            if (e.name == PREFS) prefs = true
                        }
                        else -> {
                            // Read to the end, which checks it arrived whole.
                            val buf = ByteArray(8192)
                            while (zip.read(buf) > 0) Unit
                            if (e.name.startsWith(FILES)) files++
                        }
                    }
                }
            } catch (_: Exception) {
                throw IOException(DAMAGED)
            }
            if (!prefs || scrobbles < m.optInt("scrobbles") || files < m.optInt("files") || vinyl < m.optInt("vinyl")) throw IOException(DAMAGED)
        }
    }

    /** The description at the start: nothing changes unless this is a backup of this app. */
    private fun manifest(zip: ZipInputStream): JSONObject {
        val first = try {
            zip.nextEntry
        } catch (_: Exception) {
            null
        }
        val m = if (first?.name == MANIFEST) {
            try {
                JSONObject(zip.readBytes().decodeToString())
            } catch (_: Exception) {
                null
            }
        } else null
        // "Record Shelf" names the backup format, whatever the app is called.
        if (m == null || m.optString("app") != "Record Shelf") throw IOException("This isn't a Record Shelf backup.")
        if (m.optInt("format") > FORMAT) throw IOException("This backup comes from a newer version of the app. Update the app first.")
        return m
    }

    /** One line of the scrobble or record-play lists, or null if it can't be read. */
    private fun row(line: String): RecentTrack? {
        val p = line.split('\t')
        if (p.size < 4) return null
        val ts = p[0].toLongOrNull() ?: return null
        return RecentTrack(unfield(p[1]), unfield(p[2]), unfield(p[3]), ts)
    }

    /**
     * Reads a backup from [input] and puts it in place: the history replaces the one on the
     * phone, the files are written over the app's. The settings and the page's settings come
     * back in the result, for the caller to apply. Throws [IOException] if it isn't a backup
     * of this app; [check] it first, so a damaged one changes nothing.
     */
    fun read(input: InputStream, filesDir: File, db: BackupDb): Restored {
        var prefs = JSONObject()
        var ui = JSONObject()
        var scrobbles = 0
        var files = 0
        val tags = HashMap<String, List<String>>()
        val images = HashMap<String, String>()
        val root = filesDir.canonicalFile
        var historyCleared = false
        var vinyl: List<Listen>? = null
        ZipInputStream(input.buffered()).use { zip ->
            manifest(zip)
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory) continue
                when {
                    e.name == PREFS -> prefs = JSONObject(zip.readBytes().decodeToString())
                    e.name == UI -> ui = JSONObject(zip.readBytes().decodeToString())
                    e.name == TAGS -> JSONObject(zip.readBytes().decodeToString()).let { o ->
                        for (k in o.keys()) o.getJSONArray(k).let { a -> tags[k] = (0 until a.length()).map { a.getString(it) } }
                    }
                    e.name == IMAGES -> JSONObject(zip.readBytes().decodeToString()).let { o -> for (k in o.keys()) images[k] = o.getString(k) }
                    e.name == SCROBBLES -> {
                        db.clear()
                        historyCleared = true
                        val r = BufferedReader(InputStreamReader(zip))
                        val batch = ArrayList<RecentTrack>(2000)
                        while (true) {
                            batch += row(r.readLine() ?: break) ?: continue
                            if (batch.size == 2000) {
                                scrobbles += db.insert(batch)
                                batch.clear()
                            }
                        }
                        scrobbles += db.insert(batch)
                    }
                    e.name == VINYL -> {
                        val r = BufferedReader(InputStreamReader(zip))
                        val list = ArrayList<Listen>()
                        while (true) list += row(r.readLine() ?: break)?.let { Listen(it.artist, it.title, it.ts, it.album) } ?: continue
                        vinyl = list
                    }
                    e.name.startsWith(FILES) -> {
                        // Only into the app's own folder, whatever the entry's name says.
                        val target = File(root, e.name.removePrefix(FILES)).canonicalFile
                        if (!target.path.startsWith(root.path + File.separator) || !target.name.endsWith(".json") || target.name in SKIP) continue
                        target.parentFile?.mkdirs()
                        target.outputStream().use { zip.copyTo(it) }
                        files++
                    }
                }
            }
        }
        if (!historyCleared) db.clear()
        // An older backup has no record plays of their own: the app fills them from the restored activity log.
        db.clearVinyl()
        vinyl?.let { db.addVinyl(it) }
        for ((k, v) in tags) db.saveTags(k, v)
        for ((k, v) in images) db.saveImage(k, v)
        return Restored(scrobbles, files, prefs, ui)
    }

    /** Settings as JSON with their types ("s", "l", "i", "b", "f"), so they come back exactly. */
    fun prefsJson(prefs: Map<String, *>): JSONObject {
        val o = JSONObject()
        for ((k, v) in prefs) {
            val t = when (v) {
                is String -> "s"
                is Long -> "l"
                is Int -> "i"
                is Boolean -> "b"
                is Float -> "f"
                else -> continue
            }
            o.put(k, JSONObject().put("t", t).put("v", v))
        }
        return o
    }

    /** A text field for the scrobble lines: tabs and line breaks can't be in it. */
    private fun field(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "")
    private fun unfield(s: String): String {
        if ('\\' !in s) return s
        val b = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                b.append(when (s[i + 1]) { 't' -> '\t'; 'n' -> '\n'; else -> s[i + 1] })
                i += 2
            } else {
                b.append(c)
                i++
            }
        }
        return b.toString()
    }
}
