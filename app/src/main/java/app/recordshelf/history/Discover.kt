package app.recordshelf.history

import app.recordshelf.net.DzRelease
import app.recordshelf.util.Text
import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.IsoFields

/**
 * An artist like one of yours, as Last.fm and Deezer see it: [lastfm] is Last.fm's match (0..1; 0 when it didn't list
 * them), [deezer] comes from their place in Deezer's related artists (1 for the first, falling to 0.4; 0 when not listed).
 * [fans] and [photo] are Deezer's, when it listed them.
 */
data class Like(val name: String, val lastfm: Double, val deezer: Double, val fans: Long = 0, val photo: String = "") {
    /** How alike, 0..1. Both lists agreeing counts for more than either one alone. */
    val likeness: Double
        get() = when {
            lastfm > 0 && deezer > 0 -> minOf(1.0, 0.6 * lastfm + 0.4 * deezer + 0.15)
            lastfm > 0 -> lastfm
            else -> 0.7 * deezer
        }

    fun toJson(): JSONObject = JSONObject().put("n", name).put("l", lastfm).put("d", deezer).put("f", fans).put("p", photo)

    companion object {
        fun fromJson(o: JSONObject) = Like(o.optString("n"), o.optDouble("l", 0.0), o.optDouble("d", 0.0), o.optLong("f"), o.optString("p"))
    }
}

/**
 * An artist suggested to you. [because]: your artists that point to them, with how much each adds, the most first.
 * [like]: how alike each of those is to them (0..1). [via]: for one found two steps away, the suggestion that led there.
 */
data class Suggestion(
    val name: String,
    val score: Double,
    val because: List<Pair<String, Double>>,
    val like: Map<String, Double>,
    val fans: Long,
    val photo: String,
    val via: String,
)

/**
 * Suggestions for music you haven't played: artists that Last.fm's and Deezer's listeners find alike to the ones you
 * play, weighed by how much you play those lately. Several of your artists pointing to one artist rank it higher.
 */
object Discover {
    /** Your artists that suggestions start from. */
    const val SEEDS = 25

    /** Suggestions kept, and the best ones looked further from (for "further out"). */
    const val MAX = 40
    const val STEP2 = 8

    /** How far suggestions go: close to your taste, in between, or further out. */
    const val CLOSE = 0
    const val BETWEEN = 1
    const val FURTHER = 2

    /** Last.fm's similar artists and Deezer's related ones (name, fans, photo; best first) for one artist, as one list. */
    fun merge(lastfm: List<Pair<String, Double>>, deezer: List<Triple<String, Long, String>>): List<Like> {
        val out = LinkedHashMap<String, Like>()
        for ((n, m) in lastfm) if (n.isNotBlank()) out.putIfAbsent(Text.norm(n), Like(n, m.coerceIn(0.0, 1.0), 0.0))
        deezer.forEachIndexed { i, (n, fans, photo) ->
            if (n.isBlank()) return@forEachIndexed
            val d = 1.0 - 0.6 * i / maxOf(1, deezer.size - 1)
            val k = Text.norm(n)
            val l = out[k]
            out[k] = l?.copy(deezer = d, fans = fans, photo = photo.ifEmpty { l.photo }) ?: Like(n, 0.0, d, fans, photo)
        }
        return out.values.toList()
    }

    /**
     * The best artists for you that you don't know yet. [seeds]: your artists and their weight (any scale); [likes]: who
     * is like each artist, by [Text.norm] of the name (your artists', and for [FURTHER] the best suggestions' own).
     * [known]: whether you play or own an artist. [no]: names (normed) you said aren't for you. [weaker]: per seed (normed),
     * how many of its suggestions you turned down; each takes some of its weight. [reach]: [CLOSE], [BETWEEN] or
     * [FURTHER]; further out prefers less known artists and also looks two steps away.
     */
    fun suggest(
        seeds: List<Pair<String, Double>>,
        likes: Map<String, List<Like>>,
        known: (String) -> Boolean,
        no: Set<String> = emptySet(),
        weaker: Map<String, Int> = emptyMap(),
        reach: Int = BETWEEN,
        max: Int = MAX,
    ): List<Suggestion> {
        val top = seeds.maxOfOrNull { it.second }?.takeIf { it > 0 } ?: return emptyList()
        class Acc(var name: String) {
            var score = 0.0
            val from = LinkedHashMap<String, Double>()
            val like = HashMap<String, Double>()
            var fans = 0L
            var photo = ""
            var near = 0.0
            var via = ""
            var viaScore = 0.0
        }
        val acc = LinkedHashMap<String, Acc>()
        val seedKeys = seeds.map { Text.norm(it.first) }.toSet()
        fun take(l: Like): Acc? {
            val k = Text.norm(l.name)
            if (k.isEmpty() || k in no || k in seedKeys || known(l.name)) return null
            val a = acc.getOrPut(k) { Acc(l.name) }
            if (l.fans > 0) a.fans = l.fans
            if (l.photo.isNotEmpty()) a.photo = l.photo
            return a
        }
        for ((name, raw) in seeds) {
            val turned = weaker[Text.norm(name)] ?: 0
            val w = Math.sqrt(raw / top) * maxOf(0.4, Math.pow(0.85, turned.toDouble()))
            for (l in likes[Text.norm(name)].orEmpty()) {
                val a = take(l) ?: continue
                val c = w * l.likeness
                a.score += c
                a.near += c
                a.from.merge(name, c, Double::plus)
                a.like[name] = maxOf(a.like[name] ?: 0.0, l.likeness)
            }
        }
        if (reach == FURTHER) {
            // Two steps away: who the best suggestions are like, through them (at half their weight).
            for (b in acc.values.sortedByDescending { it.score }.take(STEP2)) {
                val through = b.score * 0.5
                for (l in likes[Text.norm(b.name)].orEmpty()) {
                    val a = take(l) ?: continue
                    if (a === b) continue
                    val c = through * l.likeness
                    a.score += c
                    if (c > a.viaScore) { a.viaScore = c; a.via = b.name }
                }
            }
        }
        // Well-known artists you'd have come across anyway count for less, the further out the more.
        fun popular(fans: Long): Double {
            val f = if (fans > 0) fans.toDouble() else 100_000.0
            return when (reach) {
                CLOSE -> 1.0
                BETWEEN -> 1.0 / (1.0 + 0.12 * maxOf(0.0, Math.log10(f / 200_000.0)))
                else -> 1.0 / (1.0 + 0.6 * maxOf(0.0, Math.log10(f / 30_000.0)))
            }
        }
        return acc.values.map { it to it.score * popular(it.fans) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(max)
            .map { (a, s) ->
                Suggestion(
                    a.name, s, a.from.entries.sortedByDescending { it.value }.map { it.key to it.value }, a.like, a.fans, a.photo,
                    // Named by the step it came through only when that is most of why.
                    if (a.viaScore > a.near) a.via else "",
                )
            }
    }

    /** The weekly mix's seed: the same all week, a new one each Monday. */
    fun weekSeed(d: LocalDate): Long = d.get(IsoFields.WEEK_BASED_YEAR) * 100L + d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)

    /** Artists in a mix of [size] songs: two songs each. */
    fun mixArtists(size: Int) = (size + 1) / 2

    /**
     * The discovery mix: [size] songs by suggested artists ([sugg], best first), two at most from each, picked among
     * their best-known songs ([tops] by normed name, most played first). [seed] picks which artists (the better ones more
     * often) and which of their songs; [seed] 0 takes the best artists and their best songs. One artist never twice in a row.
     */
    fun mix(sugg: List<Suggestion>, tops: Map<String, List<String>>, size: Int, seed: Long): List<Pick> {
        val rnd = java.util.Random(seed)
        val pool = sugg.filter { tops[Text.norm(it.name)].orEmpty().isNotEmpty() }
        val n = mixArtists(size)
        // A weighted draw without putting back: each artist's key u^(1/w), the biggest keys win.
        val chosen = if (seed == 0L) pool.take(n)
        else pool.map { it to Math.pow(rnd.nextDouble(), 1.0 / maxOf(1e-6, it.score)) }.sortedByDescending { it.second }.take(n).map { it.first }
        val picks = chosen.map { a ->
            val songs = tops[Text.norm(a.name)].orEmpty().take(8)
            val two = if (seed == 0L) songs.take(2)
            else songs.indices.map { it to Math.pow(rnd.nextDouble(), (it + 1.0)) }.sortedByDescending { it.second }.take(2).map { songs[it.first] }
            a.name to two
        }
        // First one song of each, then the second ones in another order, so the two of an artist sit apart.
        val first = picks.mapNotNull { (a, s) -> s.getOrNull(0)?.let { Pick(a, it, "", 0, "new") } }
        val second = picks.mapNotNull { (a, s) -> s.getOrNull(1)?.let { Pick(a, it, "", 0, "new") } }
        val turned = if (second.size > 1) second.drop(second.size / 2) + second.take(second.size / 2) else second
        val out = ArrayList<Pick>(first)
        for (p in turned) if (out.size < size && out.lastOrNull()?.artist != p.artist) out += p
        for (p in turned) if (out.size < size && p !in out) out += p
        return out.take(size)
    }

    /**
     * New releases by your artists ([by]: each artist's releases): out in the last [days] days or coming up to [ahead]
     * days from [today], newest first, without compilations, each title of an artist once.
     */
    fun newReleases(by: List<Pair<String, List<DzRelease>>>, today: LocalDate, days: Long = 56, ahead: Long = 60, max: Int = 12): List<Pair<String, DzRelease>> {
        val from = today.minusDays(days)
        val to = today.plusDays(ahead)
        return by.flatMap { (a, rs) ->
            rs.filter { it.type != "compile" }
                .mapNotNull { r -> runCatching { LocalDate.parse(r.date) }.getOrNull()?.takeIf { !it.isBefore(from) && !it.isAfter(to) }?.let { r } }
                .distinctBy { Text.normLoose(it.title) }
                .map { a to it }
        }
            .sortedWith(compareByDescending<Pair<String, DzRelease>> { it.second.date }.thenBy { it.second.type != "album" })
            .take(max)
    }
}
