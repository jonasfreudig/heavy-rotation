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

/** The musical atlas: the universe of linked artists and the family tree of first plays. */
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
    fun whetherAnArtistWasAFindIsOnlySaidWhereItIsLikely() {
        val year = 365L * 86_400
        assertEquals("around from the start", Atlas.KNEW to "start", Atlas.found(3 * 86_400L, 1, 0, 2017))
        assertEquals("all of their music is newer than the history", Atlas.FOUND to "newer", Atlas.found(30 * 86_400L, 12, 2019, 2017))
        assertEquals("music from the year it starts could have been around", Atlas.UNSURE to "", Atlas.found(30 * 86_400L, 12, 2017, 2017))
        assertEquals("three years in, an old favorite would have turned up", Atlas.FOUND to "late", Atlas.found(3 * year + 86_400, 14, 1977, 2017))
        assertEquals("a year in, beginning with one song", Atlas.FOUND to "small", Atlas.found(year + 86_400, 1, 1977, 2017))
        assertEquals("a year in with a whole catalogue: can't be told", Atlas.UNSURE to "", Atlas.found(year + 86_400, 9, 1977, 2017))
        assertEquals("the first year, old music: can't be told", Atlas.UNSURE to "", Atlas.found(200 * 86_400L, 1, 1977, 2017))
        assertEquals("no years known", Atlas.UNSURE to "", Atlas.found(200 * 86_400L, 1, 0, 2017))
    }

    @Test
    fun theTreeHoldsOnlyFindsAndTakesYourWord() {
        // In the first months nothing can be told: X, B1..B4 came 50 to 64 days in, with music of no known year.
        val open = stats().atlas().getJSONObject("tree")
        assertEquals(9, open.getInt("kept"))
        assertEquals(4, open.getInt("knew"))
        assertEquals(0, open.getInt("found"))
        assertEquals(5, open.getInt("open"))
        assertEquals(0, open.getJSONArray("gateways").length())
        assertEquals(listOf("B1", "B2", "B3", "B4", "X"), (0 until 5).map { open.getJSONArray("unsure").getJSONObject(it).getString("name") }.sorted())
        assertEquals("unsure", stats().artist("B2", stats().range("all"))!!.getJSONObject("origin").getString("state"))

        // You say B1 was a find and that you knew B3: B1 hangs on X (which stays open, so nothing leads to X), B3 is out.
        val said = Stats(history(), ZoneId.of("UTC"), day(115), known = mapOf("b1" to false, "b2" to false, "b3" to true))
        val t = said.atlas().getJSONObject("tree")
        assertEquals(2, t.getInt("found"))
        assertEquals(5, t.getInt("knew"))
        assertEquals(2, t.getInt("open"))
        val nodes = t.getJSONArray("nodes")
        val byName = (0 until nodes.length()).associate { nodes.getJSONObject(it).getString("name") to nodes.getJSONObject(it) }
        assertEquals(setOf("X", "B1", "B2"), byName.keys)
        assertEquals("X is where the branch starts", -2, byName.getValue("X").getInt("p"))
        assertEquals(byName.getValue("X").getInt("id"), byName.getValue("B1").getInt("p"))
        assertEquals(byName.getValue("B1").getInt("id"), byName.getValue("B2").getInt("p"))
        assertEquals(listOf("X" to 2), (0 until t.getJSONArray("gateways").length()).map { t.getJSONArray("gateways").getJSONObject(it).let { g -> g.getString("name") to g.getInt("d") } })
        assertEquals(listOf("B1", "B2", "B3"), (0 until 3).map { t.getJSONArray("said").getJSONObject(it).getString("name") }.sorted())
        val o = said.artist("B2", said.range("all"))!!.getJSONObject("origin")
        assertEquals("found", o.getString("state"))
        assertEquals("said", o.getString("why"))
        assertEquals(listOf("B1", "X"), (0 until o.getJSONArray("chain").length()).map { o.getJSONArray("chain").getJSONObject(it).getString("name") })
        assertEquals(0, said.artist("B3", said.range("all"))!!.getJSONObject("origin").getJSONArray("chain").length())
        assertEquals("B3 came after B2 but is no find", 0, said.artist("B2", said.range("all"))!!.getJSONArray("ledTo").length())
    }

    @Test
    fun familyTreeFollowsFirstPlays() {
        // Every one of them said to be a find, so the tree is the whole chain of first plays.
        val s = Stats(history(), ZoneId.of("UTC"), day(115), known = listOf("x", "b1", "b2", "b3", "b4").associateWith { false })
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
    fun emptyHistory() {
        val a = Stats(History.Builder().build(), ZoneId.of("UTC"), day(1)).atlas()
        assertTrue(a.optBoolean("empty"))
    }

    private fun JSONObject.toMap(): Map<String, Int> = keys().asSequence().associateWith { getInt(it) }
}
