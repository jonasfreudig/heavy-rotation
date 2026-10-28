package app.recordshelf

import app.recordshelf.history.Pick
import app.recordshelf.history.PlaylistMix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A genre's playlist mixed from your favorites, songs near them and new ones. */
class PlaylistMixTest {
    private fun pool(source: String, artists: Int, songs: Int) =
        (0 until artists * songs).map { Pick("$source artist ${it % artists}", "$source song $it", "", 100 - it, source) }

    @Test
    fun sharesCapsAndNoArtistTwiceInARow() {
        val core = pool("core", 6, 10)
        val near = pool("near", 8, 5)
        val fresh = pool("new", 10, 3)
        val list = PlaylistMix.mix(core, near, fresh, 40, "mix")
        assertEquals(40, list.size)
        assertEquals("15% new", 6, list.count { it.source == "new" })
        assertEquals("25% near", 10, list.count { it.source == "near" })
        assertTrue("no artist more than one in twelve (at least 2)", list.groupingBy { it.artist }.eachCount().values.all { it <= 4 })
        assertTrue("never the same artist twice in a row", list.zipWithNext().none { (a, b) -> a.artist == b.artist })
        assertEquals("each song once", 40, list.map { it.key }.toSet().size)
    }

    @Test
    fun whatOnePoolCantFillTheOthersDo() {
        val core = pool("core", 10, 10)
        val list = PlaylistMix.mix(core, emptyList(), emptyList(), 30, "discover")
        assertEquals("no near or new songs: all from your favorites", 30, list.size)
        assertTrue(list.all { it.source == "core" })
        assertEquals("only favorites in that mode", 0, PlaylistMix.mix(core, pool("near", 5, 5), pool("new", 5, 5), 20, "yours").count { it.source == "new" })
    }

    @Test
    fun anotherSeedPicksAnew() {
        val core = pool("core", 12, 10)
        val a = PlaylistMix.mix(core, emptyList(), emptyList(), 20, "yours", seed = 0)
        val b = PlaylistMix.mix(core, emptyList(), emptyList(), 20, "yours", seed = 42)
        assertNotEquals(a.map { it.key }.toSet(), b.map { it.key }.toSet())
        assertEquals("the very best without a seed", "core song 0", a.first { it.artist == "core artist 0" }.title)
    }
}
