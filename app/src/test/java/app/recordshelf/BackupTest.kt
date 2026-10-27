package app.recordshelf

import app.recordshelf.lastfm.RecentTrack
import app.recordshelf.scrobble.Listen
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Backups: everything worth keeping goes into one zip and comes back the same; secrets and the send queue stay out. */
class BackupTest {
    @get:Rule val tmp = TemporaryFolder()

    /** The history database in memory. */
    private class FakeDb : BackupDb {
        val rows = sortedSetOf<RecentTrack>(compareBy({ it.ts }, { it.artist }, { it.title }))
        val tags = HashMap<String, List<String>>()
        val images = HashMap<String, String>()
        override fun insert(rows: List<RecentTrack>) = rows.count { this.rows.add(it) }
        override fun count() = rows.size
        override fun newest() = rows.lastOrNull()?.ts ?: 0L
        override fun oldest() = rows.firstOrNull()?.ts ?: 0L
        override fun forEach(fn: (Long, String, String, String) -> Unit) = rows.forEach { fn(it.ts, it.artist, it.title, it.album) }
        override fun clear() = rows.clear()
        override fun tags(): Map<String, List<String>> = tags
        override fun saveTags(artistKey: String, tags: List<String>) { this.tags[artistKey] = tags }
        override fun images(): Map<String, String> = images
        override fun saveImage(albumKey: String, url: String) { images[albumKey] = url }
        val vinyl = ArrayList<Listen>()
        override fun vinyl(): List<Listen> = vinyl.toList()
        override fun addVinyl(rows: List<Listen>) { vinyl += rows }
        override fun clearVinyl() = vinyl.clear()
    }

    private fun source(): Pair<FakeDb, File> {
        val db = FakeDb()
        db.insert(listOf(
            RecentTrack("IDLES", "Grace", "TANGK", 1_700_000_000),
            RecentTrack("Weird\tArtist", "Two\nLines \\ backslash", "", 1_700_000_100),
            RecentTrack("Air", "La Femme d'Argent", "Moon Safari", 1_700_000_200),
        ))
        db.saveTags("idles", listOf("post punk"))
        db.saveTags("nobody", emptyList())
        db.saveImage("air|moon safari", "https://img/air.jpg")
        db.addVinyl(listOf(Listen("Air", "La Femme d'Argent", 1_700_000_200, "Moon Safari")))
        val dir = tmp.newFolder("from")
        File(dir, "hidden.json").writeText("""{"tracks":["dubz"]}""")
        File(dir, "queue.json").writeText("[1,2,3]")
        File(dir, "spin.json").writeText("{}")
        File(dir, "releases").mkdirs()
        File(dir, "releases/42.json").writeText("""{"id":42}""")
        File(dir, "notes.txt").writeText("not json")
        return db to dir
    }

    private fun backup(): Pair<ByteArray, Int> {
        val (db, dir) = source()
        val out = ByteArrayOutputStream()
        val prefs = mapOf("theme" to "dark", "historyCursor" to 1_700_000_000L, "defaultTrackSec" to 210, "historyDone" to true)
        val n = Backup.write(out, dir, db, prefs, JSONObject().put("shelfView", "list"), "0.20", 1_800_000_000_000L)
        return out.toByteArray() to n
    }

    @Test
    fun roundTrip() {
        val (bytes, n) = backup()
        assertEquals(3, n)
        val db = FakeDb()
        db.insert(listOf(RecentTrack("Old", "Stuff", "", 1)))
        db.addVinyl(listOf(Listen("Old", "Record", 1)))
        val dir = tmp.newFolder("to")
        File(dir, "queue.json").writeText("[9]")
        val r = Backup.read(ByteArrayInputStream(bytes), dir, db)
        assertEquals(3, r.scrobbles)
        assertEquals("what was there before is replaced", listOf("IDLES", "Weird\tArtist", "Air"), db.rows.map { it.artist })
        assertEquals("Two\nLines \\ backslash", db.rows.elementAt(1).title)
        assertEquals(listOf("post punk"), db.tags["idles"])
        assertEquals("looked up, nothing found: kept so it isn't asked again", emptyList<String>(), db.tags["nobody"])
        assertEquals("https://img/air.jpg", db.images["air|moon safari"])
        assertEquals("the record plays come along", listOf("La Femme d'Argent"), db.vinyl.map { it.title })
        assertEquals("""{"tracks":["dubz"]}""", File(dir, "hidden.json").readText())
        assertEquals("""{"id":42}""", File(dir, "releases/42.json").readText())
        assertEquals("the queue still to send stays as it was", "[9]", File(dir, "queue.json").readText())
        assertFalse(File(dir, "spin.json").exists())
        assertFalse(File(dir, "notes.txt").exists())
        assertEquals(2, r.files)
        assertEquals("list", r.ui.getString("shelfView"))
        val p = r.prefs
        assertEquals("s", p.getJSONObject("theme").getString("t"))
        assertEquals(1_700_000_000L, p.getJSONObject("historyCursor").getLong("v"))
        assertEquals("l", p.getJSONObject("historyCursor").getString("t"))
        assertEquals("i", p.getJSONObject("defaultTrackSec").getString("t"))
        assertEquals(true, p.getJSONObject("historyDone").getBoolean("v"))
    }

    @Test
    fun aDamagedFileIsTurnedAwayBeforeAnythingChanges() {
        val (bytes, _) = backup()
        Backup.check(ByteArrayInputStream(bytes))
        for (cut in listOf(bytes.size / 4, bytes.size / 3, bytes.size / 2)) {
            try {
                Backup.check(ByteArrayInputStream(bytes.copyOf(cut)))
                fail("cut at $cut of ${bytes.size}")
            } catch (e: IOException) {
                assertTrue(e.message!!.contains("incomplete"))
            }
        }
        // Whole entries, but fewer scrobbles than it says (cut where one entry ended), and no settings.
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("backup.json"))
            z.write("""{"format":1,"app":"Record Shelf","scrobbles":5}""".toByteArray())
            z.putNextEntry(ZipEntry("history/scrobbles.tsv"))
            z.write("1\tA\tB\t\n2\tA\tC\t\n".toByteArray())
        }
        try {
            Backup.check(ByteArrayInputStream(out.toByteArray()))
            fail("short")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("Nothing was changed"))
        }
    }

    @Test
    fun filesStayInTheAppsFolder() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("backup.json"))
            z.write("""{"format":1,"app":"Record Shelf"}""".toByteArray())
            z.putNextEntry(ZipEntry("files/../escaped.json"))
            z.write("{}".toByteArray())
            z.putNextEntry(ZipEntry("files/ok.json"))
            z.write("{}".toByteArray())
        }
        val dir = tmp.newFolder("safe")
        val r = Backup.read(ByteArrayInputStream(out.toByteArray()), dir, FakeDb())
        assertEquals(1, r.files)
        assertFalse(File(dir.parentFile, "escaped.json").exists())
        assertTrue(File(dir, "ok.json").exists())
    }

    @Test
    fun onlyRecordShelfBackups() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("photo.jpg"))
            z.write(byteArrayOf(1, 2, 3))
        }
        val db = FakeDb()
        db.insert(listOf(RecentTrack("Keep", "Me", "", 5)))
        try {
            Backup.read(ByteArrayInputStream(out.toByteArray()), tmp.newFolder("x"), db)
            fail("not a backup")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("isn't a Record Shelf backup"))
        }
        assertEquals("a wrong file changes nothing", 1, db.count())
        val newer = ByteArrayOutputStream()
        ZipOutputStream(newer).use { z ->
            z.putNextEntry(ZipEntry("backup.json"))
            z.write("""{"format":99,"app":"Record Shelf"}""".toByteArray())
        }
        try {
            Backup.read(ByteArrayInputStream(newer.toByteArray()), tmp.newFolder("y"), FakeDb())
            fail("newer format")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("newer version"))
        }
    }
}
