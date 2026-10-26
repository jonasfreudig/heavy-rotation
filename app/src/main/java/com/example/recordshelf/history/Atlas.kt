package com.example.recordshelf.history

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The musical atlas, worked out from the order of your scrobbles alone:
 *  - the universe: your top artists as stars, linked when you play them back to back or in the
 *    same months, placed so linked stars sit close, and grouped into constellations;
 *  - the family tree: what you played right before your very first play of each artist;
 *  - the landscape: each top artist's plays per month as a mountain, read as a short obsession,
 *    a companion over years, a comeback after a long gap, or a phase.
 */
object Atlas {
    /** Plays at most this far apart (seconds) belong to one listening session. */
    const val SESSION_GAP = 1800L

    /** Stars in the universe, and the plays an artist needs to be one. */
    const val STARS = 150
    const val STAR_MIN = 5

    /** Artists that count in the family tree: the ones you kept playing. */
    const val KEPT_MIN = 5

    /** Artists first played this soon after the history starts were already around; how you found them is unknown. */
    const val START_GRACE = 14L * 24 * 3600

    /** Mountains in the landscape. */
    const val MOUNTAINS = 14

    /** Two stars linked: played back to back [together] times, [sameTime] how alike their months are (0..1), [w] the strength (0..1). */
    data class Link(val a: Int, val b: Int, val together: Int, val sameTime: Double, val w: Double)

    fun pair(x: Int, y: Int) = (x.toLong() shl 32) or (y.toLong() and 0xffffffffL)
    fun first(p: Long) = (p shr 32).toInt()
    fun second(p: Long) = p.toInt()

    /** How often artist x was played right before artist y in one session, keyed by [pair] (x, y). */
    fun transitions(h: History): HashMap<Long, Int> {
        val m = HashMap<Long, Int>()
        for (i in 1 until h.size) {
            val x = h.artist[i - 1]
            val y = h.artist[i]
            if (x != y && h.ts[i] - h.ts[i - 1] <= SESSION_GAP) m.merge(pair(x, y), 1, Int::plus)
        }
        return m
    }

    fun cosine(a: DoubleArray, b: DoubleArray): Double {
        var d = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            d += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        return if (na == 0.0 || nb == 0.0) 0.0 else d / sqrt(na * nb)
    }

    /**
     * Links between the stars ([artists], with their [plays] and plays per month): each keeps its
     * three strongest back-to-back partners (played one right after the other at least twice,
     * relative to how much both are played); stars with fewer than two of those also get the star
     * they share the most months with, when that is a close match.
     */
    fun links(artists: IntArray, plays: IntArray, trans: Map<Long, Int>, monthly: Array<DoubleArray>): List<Link> {
        val n = artists.size
        val together = Array(n) { IntArray(n) }
        val alike = Array(n) { DoubleArray(n) }
        for (i in 0 until n) for (j in i + 1 until n) {
            val t = (trans[pair(artists[i], artists[j])] ?: 0) + (trans[pair(artists[j], artists[i])] ?: 0)
            together[i][j] = t
            together[j][i] = t
            val c = cosine(monthly[i], monthly[j])
            alike[i][j] = c
            alike[j][i] = c
        }
        fun strength(i: Int, j: Int) = together[i][j] / sqrt(max(1, plays[i]).toDouble() * max(1, plays[j]))
        val keep = LinkedHashSet<Long>()
        for (i in 0 until n) {
            val partners = (0 until n).filter { it != i && together[i][it] >= 2 }.sortedByDescending { strength(i, it) }.take(3)
            partners.forEach { keep += pair(min(i, it), max(i, it)) }
            // Stars you rarely play next to anything else get the star they share the most months with.
            if (partners.size >= 2) continue
            val best = (0 until n).filter { it != i }.maxByOrNull { alike[i][it] }
            if (best != null && alike[i][best] >= 0.7) keep += pair(min(i, best), max(i, best))
        }
        val top = keep.maxOfOrNull { strength(first(it), second(it)) }?.takeIf { it > 0 } ?: 1.0
        return keep.map { p ->
            val i = first(p)
            val j = second(p)
            Link(i, j, together[i][j], alike[i][j], max(0.05, 0.7 * strength(i, j) / top + 0.3 * alike[i][j]))
        }.sortedWith(compareBy({ it.a }, { it.b }))
    }

    /**
     * Places the stars so linked ones sit close and the rest keep their distance (a spring layout,
     * started on a sunflower spiral with the most played in the middle, so it comes out the same
     * every time). Coordinates within -1..1.
     */
    fun layout(n: Int, links: List<Link>, iterations: Int = 300): Array<DoubleArray> {
        val x = DoubleArray(n)
        val y = DoubleArray(n)
        val golden = Math.PI * (3 - sqrt(5.0))
        for (i in 0 until n) {
            val r = sqrt((i + 0.5) / max(1, n))
            x[i] = r * cos(i * golden)
            y[i] = r * sin(i * golden)
        }
        val k = sqrt(4.0 / max(1, n))
        val dx = DoubleArray(n)
        val dy = DoubleArray(n)
        for (step in 0 until iterations) {
            val temp = 0.08 * (1 - step.toDouble() / iterations) + 0.002
            dx.fill(0.0)
            dy.fill(0.0)
            for (i in 0 until n) for (j in i + 1 until n) {
                val ex = x[i] - x[j]
                val ey = y[i] - y[j]
                val f = k * k / max(ex * ex + ey * ey, 1e-6)
                dx[i] += ex * f
                dy[i] += ey * f
                dx[j] -= ex * f
                dy[j] -= ey * f
            }
            for (l in links) {
                val ex = x[l.a] - x[l.b]
                val ey = y[l.a] - y[l.b]
                val f = sqrt(ex * ex + ey * ey) / k * (0.3 + 1.7 * l.w)
                dx[l.a] -= ex * f
                dy[l.a] -= ey * f
                dx[l.b] += ex * f
                dy[l.b] += ey * f
            }
            for (i in 0 until n) {
                // A little gravity keeps unlinked stars and small groups from drifting off.
                dx[i] -= x[i] * 0.6 / k
                dy[i] -= y[i] * 0.6 / k
                val len = sqrt(dx[i] * dx[i] + dy[i] * dy[i])
                if (len > 0) {
                    val m = min(len, temp) / len
                    x[i] += dx[i] * m
                    y[i] += dy[i] * m
                }
            }
        }
        val cx = x.average()
        val cy = y.average()
        var r = 1e-9
        for (i in 0 until n) r = max(r, max(kotlin.math.abs(x[i] - cx), kotlin.math.abs(y[i] - cy)))
        return Array(n) { doubleArrayOf((x[it] - cx) / r, (y[it] - cy) / r) }
    }

    /**
     * Constellations: groups of stars more linked to each other than to the rest (each star takes
     * the group its links pull hardest towards, until nothing changes). Numbered by their plays,
     * biggest first; stars in groups smaller than [minSize] are loose (-1).
     */
    fun clusters(n: Int, links: List<Link>, plays: IntArray, minSize: Int = 3): IntArray {
        val adj = Array(n) { ArrayList<Pair<Int, Double>>() }
        for (l in links) {
            adj[l.a] += l.b to l.w
            adj[l.b] += l.a to l.w
        }
        val label = IntArray(n) { it }
        for (round in 0 until 40) {
            var changed = false
            for (u in 0 until n) {
                if (adj[u].isEmpty()) continue
                val score = HashMap<Int, Double>()
                for ((v, w) in adj[u]) score.merge(label[v], w, Double::plus)
                // Ties go to the group of the more played star (smaller index), so the result is stable.
                val best = score.entries.maxWith(compareBy<Map.Entry<Int, Double>> { it.value }.thenByDescending { it.key }).key
                if (best != label[u]) {
                    label[u] = best
                    changed = true
                }
            }
            if (!changed) break
        }
        val groups = (0 until n).groupBy { label[it] }.values.filter { it.size >= minSize }.sortedByDescending { g -> g.sumOf { plays[it] } }
        val out = IntArray(n) { -1 }
        groups.forEachIndexed { c, g -> g.forEach { out[it] = c } }
        return out
    }

    /** The kind of mountain an artist's plays per month make, and its length in months. */
    data class Shape(val kind: String, val months: Int)

    /**
     * Reads plays per month: a comeback (six or more quiet months between two real stretches), an
     * obsession (most plays within four months), a companion (a year or more of active months) or a phase.
     */
    fun shape(counts: IntArray): Shape {
        val total = counts.sum()
        if (total == 0) return Shape("phase", 0)
        val peak = counts.max()
        val floor = max(2, (peak + 9) / 10)
        val active = counts.indices.filter { counts[it] >= floor }
        var gap = 0
        var before = 0
        var k0 = 0
        for (k in 1 until active.size) {
            while (k0 <= active[k - 1]) before += counts[k0++]
            val g = active[k] - active[k - 1] - 1
            if (g > gap && before >= total * 0.1 && total - before >= total * 0.1) gap = g
        }
        val top4 = counts.sortedDescending().take(4).sum()
        return when {
            gap >= 6 -> Shape("comeback", gap)
            active.size <= 4 && top4 >= total * 0.75 -> Shape("obsession", active.size)
            active.size >= 12 -> Shape("companion", active.last() - active.first() + 1)
            else -> Shape("phase", active.size)
        }
    }
}
