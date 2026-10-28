package app.recordshelf.history

import app.recordshelf.util.Text
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.random.Random

/**
 * A song for a mixed playlist and where it came from: your favorites in a genre ("core"), songs
 * you often played right next to them ("near"), or songs you never played ("new").
 */
data class Pick(val artist: String, val title: String, val album: String, val plays: Int, val source: String) {
    val key get() = Text.norm(artist) + "\u0000" + Text.normLoose(title)

    fun toJson(): JSONObject = JSONObject().put("artist", artist).put("title", title).put("album", album).put("plays", plays).put("source", source)
}

/**
 * Mixes a playlist of a chosen size from the three pools, each best first. Each pool gets its
 * share for the mode (what one can't fill goes to the others, your favorites first), no artist
 * more than about one song in twelve, and the pools are spread through the list, never the same
 * artist twice in a row if it can be helped. A [seed] other than 0 picks anew among the best
 * (for "make another"); 0 takes the very best.
 */
object PlaylistMix {
    /** Shares of core, near and new for each mode. */
    val MODES = mapOf(
        "yours" to doubleArrayOf(0.8, 0.2, 0.0),
        "mix" to doubleArrayOf(0.6, 0.25, 0.15),
        "discover" to doubleArrayOf(0.4, 0.2, 0.4),
    )

    fun mix(core: List<Pick>, near: List<Pick>, fresh: List<Pick>, size: Int, mode: String, seed: Long = 0): List<Pick> {
        val share = MODES[mode] ?: MODES.getValue("mix")
        val best = maxOf(size * 2, 20)
        val pools = listOf(core, near, fresh).map { p -> if (seed == 0L) p else p.take(best).shuffled(Random(seed)) + p.drop(best) }
        val cap = maxOf(2, ceil(size / 12.0).toInt())
        val perArtist = HashMap<String, Int>()
        val seen = HashSet<String>()
        val chosen = pools.map { ArrayList<Pick>() }
        fun take(k: Int, n: Int) {
            var left = n
            for (p in pools[k]) {
                if (left <= 0) break
                val a = Text.norm(p.artist)
                if (p.key in seen || (perArtist[a] ?: 0) >= cap) continue
                seen += p.key
                perArtist.merge(a, 1, Int::plus)
                chosen[k] += p
                left--
            }
        }
        val want = IntArray(3) { Math.round(size * share[it]).toInt() }.also { it[0] = size - it[1] - it[2] }
        for (k in 0..2) take(k, want[k])
        for (k in 0..2) {
            val missing = size - chosen.sumOf { it.size }
            if (missing > 0) take(k, missing)
        }
        return interleave(chosen)
    }

    /** The pools spread through the list in their proportions, never the same artist twice in a row if it can be helped. */
    private fun interleave(pools: List<List<Pick>>): List<Pick> {
        val total = pools.sumOf { it.size }
        val rest = pools.map { ArrayList(it) }
        val used = IntArray(pools.size)
        val out = ArrayList<Pick>(total)
        while (out.size < total) {
            val prev = out.lastOrNull()?.let { Text.norm(it.artist) }
            val order = pools.indices.filter { rest[it].isNotEmpty() }.sortedBy { (used[it] + 0.5) / pools[it].size }
            val k = order.firstOrNull { k -> rest[k].take(5).any { Text.norm(it.artist) != prev } } ?: order.first()
            val i = rest[k].take(5).indexOfFirst { Text.norm(it.artist) != prev }.coerceAtLeast(0)
            out += rest[k].removeAt(i)
            used[k]++
        }
        return out
    }
}
