package app.recordshelf

import app.recordshelf.history.Atlas
import app.recordshelf.history.History
import app.recordshelf.history.Stats
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.hypot

/** The musical atlas: the universe of linked artists, the family tree of first plays, the landscape. */
class AtlasTest {
    private fun day(d: Int) = LocalDateTime.of(2026, 1, 1, 20, 0).toEpochSecond(ZoneOffset.UTC) + d * 86_400L

    /**
     * Fifty days of A1-A4 played together, then (after A1 leads to X a few times) X leading into
     * B1, which leads to B2, B3 and B4, played together from then on.
     */
    private fun history(): History {
        val b = History.Builder()
        fun session(d: Int, vararg artists: String) = artists.forEachIndexed { i, a -> b.add(day(d) + i * 240L, a, "$a song ${i % 3}", "$a album") }
        for (d in 0 until 50) session(d, "A1", "A2", "A3", "A4", "A1", "A2", "A3", "A4")
        for (d in listOf(50, 52, 54)) session(d, "A1", "X", "A2")
        session(62, "X", "B1", "B2", "B3", "B4", "B1", "B2", "B3", "B4")
        session(64, "X", "B1", "B2", "B3", "B4")
        for (d in 66 until 110) session(d, "B1", "B2", "B3", "B4", "B1", "B2", "B3", "B4")
        return b.build()
    }

    private fun stats() = Stats(history(), ZoneId.of("UTC"), day(115))

    @Test
    fun universeGroupsWhatYouPlayTogether() {
        val u = stats().atlas().getJSONObject("universe")
        val nodes = u.getJSONArray("nodes")
        val byName = (0 until nodes.length()).associate { nodes.getJSONObject(it).getString("name") to nodes.getJSONObject(it) }
        val c = { n: String -> byName.getValue(n).getInt("c") }
        assertEquals(c("A1"), c("A2"))
        assertEquals(c("A1"), c("A4"))
        assertEquals(c("B1"), c("B3"))
        assertNotEquals("two constellations", c("A1"), c("B1"))
        assertTrue("X links the two", "X" in (0 until u.getJSONArray("bridges").length()).map { u.getJSONArray("bridges").getJSONObject(it).getString("name") })
        // Linked stars sit close.
        fun d(a: String, b: String) = hypot(byName.getValue(a).getDouble("x") - byName.getValue(b).getDouble("x"), byName.getValue(a).getDouble("y") - byName.getValue(b).getDouble("y"))
        val within = listOf(d("A1", "A2"), d("A3", "A4"), d("B1", "B2"), d("B3", "B4")).average()
        val across = listOf(d("A1", "B1"), d("A2", "B3"), d("A4", "B4")).average()
        assertTrue("within $within, across $across", within * 2 < across)
        assertEquals("what you play right after A4", "A1", byName.getValue("A4").getJSONObject("after").getString("name"))
        assertEquals(mapOf("2026" to 103), byName.getValue("A1").getJSONObject("years").toMap())
    }

    @Test
    fun familyTreeFollowsFirstPlays() {
        val s = stats()
        val t = s.atlas().getJSONObject("tree")
        assertEquals(9, t.getInt("kept"))
        assertEquals("A1-A4 were there from the start", 4, t.getInt("start"))
        assertEquals("X after A1, B1 after X, B2 after B1...", 5, t.getInt("after"))
        assertEquals(0, t.getInt("blue"))
        val gw = t.getJSONArray("gateways")
        assertEquals(listOf("A1" to 5, "X" to 4, "B1" to 3, "B2" to 2), (0 until gw.length()).map { gw.getJSONObject(it).getString("name") to gw.getJSONObject(it).getInt("d") })
        val b2 = s.artist("B2", s.range("all"))!!
        val o = b2.getJSONObject("origin")
        assertEquals("after", o.getString("how"))
        assertEquals("B1", o.getString("from"))
        val chain = o.getJSONArray("chain")
        assertEquals(listOf("B1", "X", "A1"), (0 until chain.length()).map { chain.getJSONObject(it).getString("name") })
        assertEquals("start", chain.getJSONObject(2).getString("how"))
        val x = s.artist("X", s.range("all"))!!
        assertEquals("B1", x.getJSONArray("ledTo").getJSONObject(0).getString("name"))
    }

    @Test
    fun landscapeReadsTheShapes() {
        val l = stats().atlas().getJSONObject("landscape")
        assertEquals("January to April", 4, l.getInt("months"))
        val series = l.getJSONArray("series")
        assertTrue("ordered by when they peaked", series.getJSONObject(0).getString("name").startsWith("A"))
        assertTrue(series.getJSONObject(series.length() - 1).getString("name").startsWith("B"))
        assertTrue((0 until series.length()).map { series.getJSONObject(it) }.all { it.getJSONArray("counts").length() == 4 })
        // The kinds of mountain, from plays per month.
        assertEquals(Atlas.Shape("obsession", 2), Atlas.shape(intArrayOf(0, 0, 30, 20, 1, 0, 0, 0)))
        assertEquals(Atlas.Shape("companion", 14), Atlas.shape(IntArray(14) { 10 + it % 3 }))
        assertEquals(Atlas.Shape("comeback", 7), Atlas.shape(intArrayOf(20, 10, 0, 0, 0, 0, 0, 0, 0, 15, 10)))
        assertEquals(Atlas.Shape("phase", 6), Atlas.shape(intArrayOf(5, 8, 9, 7, 6, 5, 0, 0)))
        assertEquals(Atlas.Shape("phase", 0), Atlas.shape(IntArray(3)))
    }

    @Test
    fun emptyHistory() {
        val a = Stats(History.Builder().build(), ZoneId.of("UTC"), day(1)).atlas()
        assertTrue(a.optBoolean("empty"))
    }

    private fun JSONObject.toMap(): Map<String, Int> = keys().asSequence().associateWith { getInt(it) }
}
