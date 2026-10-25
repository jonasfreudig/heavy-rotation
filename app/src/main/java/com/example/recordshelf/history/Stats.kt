package com.example.recordshelf.history

import com.example.recordshelf.discogs.CollectionItem
import com.example.recordshelf.net.Http
import com.example.recordshelf.scrobble.Listen
import com.example.recordshelf.util.Text
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.BitSet

/** A period for the stats: [from, to) in Unix seconds. [kind] is week, month, year, 12m or all. */
data class Range(val id: String, val label: String, val from: Long, val to: Long, val kind: String = "")

/**
 * Everything the Stats tab and the story show, computed from the [History]. Local time
 * ([zone]) decides days, weeks, hours and months. Records you own come from [collection];
 * [vinyl] are the scrobbles made here from records.
 *
 * Periods have ids: "w2026-10-19" (the week starting that Monday), "m2026-10", "y2026",
 * "12m" (the last 12 months) and "all". Charts over time pick their resolution from the
 * period's length (days for a week or month, weeks or months for a year, and so on).
 */
class Stats(
    private val h: History,
    private val zone: ZoneId,
    private val nowSec: Long,
    private val collection: List<CollectionItem> = emptyList(),
    private val genres: Genres = Genres(emptyList(), emptyMap()),
    private val vinyl: List<Listen> = emptyList(),
    private val albumImages: Map<String, String> = emptyMap(),
) {
    companion object {
        const val TOP_GENRES = 6
        const val TOP_INKS = 8
        const val OBSESSION_MIN = 7
        const val WISH_MIN_PLAYS = 8
        const val WISH_MIN_TRACKS = 4

        /** Scrobbles carry no length; "hours" assume an average track. */
        const val MINUTES_PER_PLAY = 3.5
        private const val DAY_SEC = 86_400L
    }

    private val rules = zone.rules
    private val ownedKeys: Map<String, CollectionItem> = collection.associateBy { Text.albumKey(it.artist, it.title) }
    private val albumIndex: Map<String, Int> by lazy { h.albumKeys.withIndex().associate { (i, k) -> k to i } }
    private val artistIndex: Map<String, Int> by lazy { h.artistNames.withIndex().associate { (i, n) -> Text.norm(n) to i } }
    private val historyStart get() = if (h.size > 0) h.ts[0] else nowSec

    /** First play of each artist, over the whole history. */
    private val firstTs: LongArray by lazy {
        LongArray(h.artistNames.size) { -1 }.also { f -> for (i in 0 until h.size) if (f[h.artist[i]] < 0) f[h.artist[i]] = h.ts[i] }
    }

    // ---- time helpers ----

    private fun local(t: Long) = t + rules.getOffset(Instant.ofEpochSecond(t)).totalSeconds
    fun day(t: Long) = Math.floorDiv(local(t), DAY_SEC)
    private fun dow(day: Long) = Math.floorMod(day + 3, 7L).toInt() // 1970-01-01 was a Thursday; Monday = 0
    private fun hour(t: Long) = (Math.floorMod(local(t), DAY_SEC) / 3600).toInt()
    private fun dayStart(day: Long) = LocalDate.ofEpochDay(day).atStartOfDay(zone).toEpochSecond()
    private fun date(t: Long): LocalDate = Instant.ofEpochSecond(t).atZone(zone).toLocalDate()
    private fun ms(d: LocalDate) = d.atStartOfDay(zone).toEpochSecond() * 1000

    // ---- periods ----

    fun range(id: String): Range {
        val today = date(nowSec)
        fun span(kind: String, rid: String, label: String, a: LocalDate, b: LocalDate) =
            Range(rid, label, a.atStartOfDay(zone).toEpochSecond(), minOf(b.atStartOfDay(zone).toEpochSecond(), nowSec + 1), kind)
        val current = "m" + YearMonth.from(today)
        return try {
            when {
                id == "all" -> Range("all", "All time", 0, nowSec + 1, "all")
                id == "12m" -> span("12m", "12m", "Last 12 months", today.minusYears(1).plusDays(1), today.plusDays(1))
                id.startsWith("y") -> id.substring(1).toInt().let { y -> span("year", "y$y", "$y", LocalDate.of(y, 1, 1), LocalDate.of(y + 1, 1, 1)) }
                id.startsWith("m") -> YearMonth.parse(id.substring(1)).let { m -> span("month", "m$m", "$m", m.atDay(1), m.plusMonths(1).atDay(1)) }
                id.startsWith("w") -> LocalDate.parse(id.substring(1)).let { d ->
                    val mon = d.minusDays(d.dayOfWeek.value - 1L)
                    span("week", "w$mon", "Week of $mon", mon, mon.plusDays(7))
                }
                else -> range(current)
            }
        } catch (_: Exception) {
            range(current)
        }
    }

    /** The period [step] before (−1) or after (+1) [r], or null outside the history. */
    fun neighbor(r: Range, step: Int): Range? {
        val start = date(r.from)
        val id = when (r.kind) {
            "week" -> "w" + start.plusWeeks(step.toLong())
            "month" -> "m" + YearMonth.from(start).plusMonths(step.toLong())
            "year" -> "y" + (start.year + step)
            else -> return null
        }
        val n = range(id)
        return if (n.from > nowSec || n.to <= historyStart) null else n
    }

    /** What [r] is compared with: the period before it; for the running period only the same stretch of it. */
    fun previous(r: Range): Range? {
        val p = when (r.kind) {
            "week", "month", "year" -> neighbor(r, -1) ?: return null
            "12m" -> Range("p12m", "the 12 months before", r.from - (r.to - r.from), r.from, "12m")
            else -> return null
        }
        return if (r.to > nowSec) p.copy(to = minOf(p.to, p.from + (r.to - r.from))) else p
    }

    private fun rangeJson(r: Range) = JSONObject().put("id", r.id).put("kind", r.kind).put("label", r.label)
        .put("from", r.from).put("to", r.to).put("prev", neighbor(r, -1)?.id ?: JSONObject.NULL)
        .put("next", neighbor(r, 1)?.id ?: JSONObject.NULL).put("historyStart", historyStart)

    // ---- bins for charts over time ----

    enum class Unit(val id: String) { DAY("day"), WEEK("week"), MONTH("month"), QUARTER("quarter"), YEAR("year") }

    /** Calendar bins (days, Monday weeks, months, quarters, years) from [from] to [to]. */
    inner class Bins(val unit: Unit, from: LocalDate, to: LocalDate) {
        val base: LocalDate = when (unit) {
            Unit.DAY -> from
            Unit.WEEK -> from.minusDays(from.dayOfWeek.value - 1L)
            Unit.MONTH -> from.withDayOfMonth(1)
            Unit.QUARTER -> LocalDate.of(from.year, (from.monthValue - 1) / 3 * 3 + 1, 1)
            Unit.YEAR -> LocalDate.of(from.year, 1, 1)
        }
        private fun months(d: LocalDate) = (d.year - base.year) * 12 + d.monthValue - base.monthValue
        fun index(d: LocalDate): Int = when (unit) {
            Unit.DAY -> (d.toEpochDay() - base.toEpochDay()).toInt()
            Unit.WEEK -> ((d.toEpochDay() - base.toEpochDay()) / 7).toInt()
            Unit.MONTH -> months(d)
            Unit.QUARTER -> months(d) / 3
            Unit.YEAR -> d.year - base.year
        }
        fun start(i: Int): LocalDate = when (unit) {
            Unit.DAY -> base.plusDays(i.toLong())
            Unit.WEEK -> base.plusWeeks(i.toLong())
            Unit.MONTH -> base.plusMonths(i.toLong())
            Unit.QUARTER -> base.plusMonths(3L * i)
            Unit.YEAR -> base.plusYears(i.toLong())
        }
        val count = maxOf(1, index(to) + 1)
        fun startsJson() = JSONArray((0 until count).map { ms(start(it)) })
    }

    /**
     * Bins covering [r] (from the first scrobble at the earliest, up to today), at most about
     * [maxBins]. The size follows the kind of period, so a running year has the same
     * resolution as a finished one.
     */
    fun binsFor(r: Range, maxBins: Int): Bins {
        val from = date(maxOf(r.from, historyStart))
        val to = date(maxOf(minOf(r.to - 1, nowSec), maxOf(r.from, historyStart)))
        val span = when (r.kind) {
            "week" -> 7L
            "month" -> 31L
            "year", "12m" -> 366L
            else -> to.toEpochDay() - from.toEpochDay() + 1
        }
        val unit = when {
            span <= maxBins -> Unit.DAY
            span / 7 + 1 <= maxBins -> Unit.WEEK
            span / 30.44 + 1 <= maxBins -> Unit.MONTH
            span / 91.3 + 1 <= maxBins -> Unit.QUARTER
            else -> Unit.YEAR
        }
        return Bins(unit, from, to)
    }

    // ---- the Stats tab ----

    fun compute(r: Range): JSONObject {
        val i0 = h.lowerBound(r.from)
        val i1 = h.lowerBound(r.to)
        val prev = previous(r)
        val summary = summary(r, i0, i1)
        prev?.let { p -> summary.put("prev", summary(p, h.lowerBound(p.from), h.lowerBound(p.to)).put("label", p.label)) }
        return JSONObject()
            .put("range", rangeJson(r))
            .put("summary", summary)
            .put("insights", insights(r, i0, i1, prev))
            .put("record", record(r, i0, i1))
            .put("timeline", timeline(r, i0, i1))
            .put("river", river(r, i0, i1))
            .put("clock", JSONArray(clock(i0, i1).toList()))
            .put("streaks", streaks(r, i0, i1))
            .put("pulse", pulse(r, i0, i1))
            .put("newArtists", newArtists(r, i0, i1))
            .put("genres", genreDrift(r, i0, i1))
            .put("obsessions", obsessions(i0, i1))
            .put("loops", loops(i0, i1))
            .put("rarely", rarelyPlayed(i0, i1))
            .put("wishlist", wishlist(i0, i1))
            .put("top", top(i0, i1, 10))
    }

    fun summary(r: Range, i0: Int, i1: Int): JSONObject {
        val artists = BitSet()
        val tracks = BitSet()
        val albums = BitSet()
        var days = 0
        var lastDay = Long.MIN_VALUE
        for (i in i0 until i1) {
            artists.set(h.artist[i])
            tracks.set(h.track[i])
            if (h.album[i] >= 0) albums.set(h.album[i])
            val d = day(h.ts[i])
            if (d != lastDay) {
                days++
                lastDay = d
            }
        }
        val first = if (r.from > 0) maxOf(r.from, historyStart) else historyStart
        val spanDays = maxOf(1L, day(minOf(r.to - 1, nowSec)) - day(first) + 1)
        val n = i1 - i0
        var newArtists = 0
        artists.stream().forEach { if (firstTs[it] in r.from until r.to) newArtists++ }
        return JSONObject().put("scrobbles", n).put("artists", artists.cardinality()).put("tracks", tracks.cardinality())
            .put("albums", albums.cardinality()).put("days", days).put("spanDays", spanDays)
            .put("perDay", Math.round(n * 10.0 / spanDays) / 10.0)
            .put("hours", Math.round(n * MINUTES_PER_PLAY / 60))
            .put("newArtists", newArtists).put("historyStartsHere", h.size > 0 && historyStart in r.from until r.to)
    }

    /** Plays per weekday (Monday first) and hour: 7 × 24 values, row by row. */
    fun clock(i0: Int, i1: Int): IntArray {
        val c = IntArray(7 * 24)
        for (i in i0 until i1) {
            val t = h.ts[i]
            c[dow(day(t)) * 24 + hour(t)]++
        }
        return c
    }

    /** Plays per day, week or month across the period, with the average per bin. */
    fun timeline(r: Range, i0: Int, i1: Int): JSONObject {
        val b = binsFor(r, 60)
        val counts = IntArray(b.count)
        for (i in i0 until i1) counts[b.index(date(h.ts[i])).coerceIn(0, b.count - 1)]++
        return JSONObject().put("unit", b.unit.id).put("starts", b.startsJson()).put("counts", JSONArray(counts.toList()))
            .put("avg", Math.round(counts.average() * 10) / 10.0)
    }

    /** Days in a row with music: the current run (if the period reaches today), the longest, and artists' longest runs. */
    fun streaks(r: Range, i0: Int, i1: Int): JSONObject {
        var lastDay = Long.MIN_VALUE
        var run = 0
        var runStart = 0L
        var best = 0
        var bestStart = 0L
        var bestEnd = 0L
        val n = h.artistNames.size
        val aLast = LongArray(n) { Long.MIN_VALUE }
        val aRun = IntArray(n)
        val aBest = IntArray(n)
        val aBestEnd = LongArray(n)
        for (i in i0 until i1) {
            val d = day(h.ts[i])
            if (d != lastDay) {
                if (d == lastDay + 1) run++ else {
                    run = 1
                    runStart = d
                }
                lastDay = d
                if (run > best) {
                    best = run
                    bestStart = runStart
                    bestEnd = d
                }
            }
            val a = h.artist[i]
            if (aLast[a] != d) {
                aRun[a] = if (aLast[a] == d - 1) aRun[a] + 1 else 1
                aLast[a] = d
                if (aRun[a] > aBest[a]) {
                    aBest[a] = aRun[a]
                    aBestEnd[a] = d
                }
            }
        }
        // The current streak counts back from today (or yesterday) over the whole history, not just this period.
        var current = 0
        var currentFrom = 0L
        if (r.to > nowSec && h.size > 0) {
            var expect = day(h.ts[h.size - 1])
            if (expect >= day(nowSec) - 1) {
                for (i in h.size - 1 downTo 0) {
                    val d = day(h.ts[i])
                    if (d == expect) {
                        current++
                        expect--
                    } else if (d < expect) break
                }
                currentFrom = dayStart(expect + 1) * 1000
            }
        }
        val artists = JSONArray()
        (0 until n).filter { aBest[it] >= 3 }.sortedByDescending { aBest[it] }.take(3).forEach { a ->
            artists.put(
                JSONObject().put("artist", h.artistNames[a]).put("days", aBest[a])
                    .put("from", dayStart(aBestEnd[a] - aBest[a] + 1) * 1000).put("to", dayStart(aBestEnd[a]) * 1000),
            )
        }
        return JSONObject()
            .put("current", current).put("currentFrom", currentFrom)
            .put("longest", best).put("longestFrom", if (best > 0) dayStart(bestStart) * 1000 else 0)
            .put("longestTo", if (best > 0) dayStart(bestEnd) * 1000 else 0)
            .put("artists", artists)
    }

    /** Artists heard for the first time ever, per day, week, month or year of the period. */
    fun newArtists(r: Range, i0: Int, i1: Int): JSONObject {
        if (h.size == 0 || i1 <= i0) return JSONObject().put("unit", "month").put("buckets", JSONArray())
        val b = binsFor(r, 40)
        val plays = IntArray(h.artistNames.size)
        for (i in 0 until h.size) plays[h.artist[i]]++
        val counts = IntArray(b.count)
        val members = Array(b.count) { ArrayList<Int>() }
        for (a in firstTs.indices) {
            val f = firstTs[a]
            if (f < r.from || f >= r.to) continue
            val k = b.index(date(f))
            if (k in 0 until b.count) {
                counts[k]++
                members[k] += a
            }
        }
        val startIdx = b.index(date(historyStart))
        val buckets = JSONArray()
        for (k in 0 until b.count) {
            buckets.put(
                JSONObject().put("start", ms(b.start(k))).put("count", counts[k]).put("historyStart", k == startIdx)
                    .put("top", JSONArray(members[k].sortedByDescending { plays[it] }.take(3).map { h.artistNames[it] })),
            )
        }
        return JSONObject().put("unit", b.unit.id).put("buckets", buckets)
    }

    // ---- genres ----

    private val genreIds = HashMap<String, Int>()
    private val genreKeys = ArrayList<String>()

    /** Primary genre per scrobble (-1: unknown) and where it came from, worked out once per album/artist. */
    private val scrobbleGenre: Pair<IntArray, ByteArray> by lazy {
        val albumG = IntArray(h.albumTitles.size) { -2 }
        val albumS = ByteArray(h.albumTitles.size)
        val artistG = IntArray(h.artistNames.size) { -2 }
        val artistS = ByteArray(h.artistNames.size)
        fun id(keys: List<String>) = keys.firstOrNull()?.let { k -> genreIds.getOrPut(k) { genreKeys += k; genreKeys.size - 1 } } ?: -1
        val g = IntArray(h.size)
        val src = ByteArray(h.size)
        for (i in 0 until h.size) {
            val al = h.album[i]
            if (al >= 0) {
                if (albumG[al] == -2) {
                    val (k, s) = genres.of(h.artistNames[h.albumArtist[al]], h.albumKeys[al])
                    albumG[al] = id(k)
                    albumS[al] = s.ordinal.toByte()
                }
                g[i] = albumG[al]
                src[i] = albumS[al]
            } else {
                val a = h.artist[i]
                if (artistG[a] == -2) {
                    val (k, s) = genres.of(h.artistNames[a], null)
                    artistG[a] = id(k)
                    artistS[a] = s.ordinal.toByte()
                }
                g[i] = artistG[a]
                src[i] = artistS[a]
            }
        }
        g to src
    }

    /** The genres that get their own color: the biggest over all time, so colors don't change with the period. */
    private val topGenres: List<Int> by lazy {
        val (g, _) = scrobbleGenre
        val totals = IntArray(genreKeys.size)
        for (x in g) if (x >= 0) totals[x]++
        totals.indices.filter { totals[it] > 0 }.sortedByDescending { totals[it] }.take(TOP_GENRES)
    }

    /** Genre shares of the period, biggest first. */
    fun genreShares(i0: Int, i1: Int, max: Int): List<Pair<String, Double>> {
        val (g, _) = scrobbleGenre
        val counts = HashMap<Int, Int>()
        var known = 0
        for (i in i0 until i1) if (g[i] >= 0) {
            counts[g[i]] = (counts[g[i]] ?: 0) + 1
            known++
        }
        if (known == 0) return emptyList()
        return counts.entries.sortedByDescending { it.value }.take(max).map { Genres.label(genreKeys[it.key]) to it.value.toDouble() / known }
    }

    /** Share of each top genre per day, week, month, quarter or year of the period (part-to-whole over time). */
    fun genreDrift(r: Range, i0: Int, i1: Int): JSONObject {
        val (g, src) = scrobbleGenre
        val top = topGenres
        val slot = HashMap<Int, Int>().also { m -> top.forEachIndexed { i, id -> m[id] = i } }
        val out = JSONObject()
            .put("names", JSONArray(top.map { Genres.label(genreKeys[it]) } + "Other"))
            .put("keys", JSONArray(top.map { genreKeys[it] } + "other"))
        if (i1 <= i0) return out.put("unit", "month").put("periods", JSONArray()).put("coverage", JSONObject())
        val b = binsFor(r, 16)
        val counts = Array(b.count) { IntArray(top.size + 1) }
        val coverage = IntArray(3)
        for (i in i0 until i1) {
            coverage[src[i].toInt()]++
            val x = g[i]
            if (x < 0) continue
            counts[b.index(date(h.ts[i])).coerceIn(0, b.count - 1)][slot[x] ?: top.size]++
        }
        val periods = JSONArray()
        for (p in 0 until b.count) {
            val total = counts[p].sum()
            periods.put(
                JSONObject().put("start", ms(b.start(p))).put("total", total)
                    .put("shares", JSONArray(counts[p].map { if (total > 0) Math.round(it * 1000.0 / total) / 1000.0 else 0.0 })),
            )
        }
        val n = (i1 - i0).toDouble()
        return out.put("unit", b.unit.id).put("periods", periods)
            .put("coverage", JSONObject().put("discogs", coverage[0] / n).put("lastfm", coverage[1] / n).put("none", coverage[2] / n))
    }

    /** Artists that would need Last.fm tags for the genres: the most played ones Discogs doesn't cover. */
    fun artistsNeedingTags(have: Set<String>, max: Int = 300, coverage: Double = 0.9): List<String> {
        val plays = IntArray(h.artistNames.size)
        for (a in h.artist) plays[a]++
        val order = plays.indices.sortedByDescending { plays[it] }
        val out = ArrayList<String>()
        var covered = 0L
        for (a in order) {
            if (covered >= coverage * h.size || out.size >= max) break
            covered += plays[a]
            val name = h.artistNames[a]
            if (!genres.knowsFromDiscogs(name) && Genres.artistKey(name) !in have) out += name
        }
        return out
    }

    // ---- the record, the river, phases ----

    /**
     * Every play of the period for the radial "record": its day (0 = first day of the period,
     * one groove each), minute of the day, and artist and track as indexes into tables that
     * list the period's artists by plays (index 0 is the most played).
     */
    fun record(r: Range, i0: Int, i1: Int): JSONObject {
        val out = JSONObject()
        if (i1 <= i0) return out.put("days", 0)
        val firstDay = day(maxOf(r.from, historyStart))
        val lastDay = maxOf(firstDay, day(minOf(r.to - 1, nowSec)))
        val order = artistOrder(i0, i1)
        val plays = IntArray(h.artistNames.size)
        for (i in i0 until i1) plays[h.artist[i]]++
        val local = IntArray(h.artistNames.size) { -1 }
        order.forEachIndexed { k, a -> local[a] = k }
        val trackLocal = HashMap<Int, Int>()
        val titles = JSONArray()
        val d = JSONArray()
        val m = JSONArray()
        val a = JSONArray()
        val t = JSONArray()
        for (i in i0 until i1) {
            val ts = h.ts[i]
            d.put(day(ts) - firstDay)
            m.put((Math.floorMod(local(ts), DAY_SEC) / 60).toInt())
            a.put(local[h.artist[i]])
            t.put(trackLocal.getOrPut(h.track[i]) { titles.put(h.trackTitles[h.track[i]]); titles.length() - 1 })
        }
        return out.put("start", dayStart(firstDay) * 1000).put("days", (lastDay - firstDay + 1).toInt())
            .put("d", d).put("m", m).put("a", a).put("t", t).put("tracks", titles)
            .put("artists", JSONArray(order.map { h.artistNames[it] })).put("plays", JSONArray(order.map { plays[it] }))
    }

    /** The period's artists, most played first (the order the colors follow). */
    private fun artistOrder(i0: Int, i1: Int): List<Int> {
        val plays = IntArray(h.artistNames.size)
        for (i in i0 until i1) plays[h.artist[i]]++
        return plays.indices.filter { plays[it] > 0 }.sortedWith(compareByDescending<Int> { plays[it] }.thenBy { it })
    }

    /** The river: plays per bin of the [TOP_INKS] most played artists (same order and colors as the record) and everyone else. */
    fun river(r: Range, i0: Int, i1: Int): JSONObject {
        val b = binsFor(r, 40)
        val order = artistOrder(i0, i1).take(TOP_INKS)
        val slot = HashMap<Int, Int>().also { s -> order.forEachIndexed { k, a -> s[a] = k } }
        val series = Array(order.size) { IntArray(b.count) }
        val other = IntArray(b.count)
        for (i in i0 until i1) {
            val k = b.index(date(h.ts[i])).coerceIn(0, b.count - 1)
            val s = slot[h.artist[i]]
            if (s != null) series[s][k]++ else other[k]++
        }
        return JSONObject().put("unit", b.unit.id).put("starts", b.startsJson())
            .put("artists", JSONArray(order.map { h.artistNames[it] }))
            .put("series", JSONArray(series.map { JSONArray(it.toList()) })).put("other", JSONArray(other.toList()))
    }

    /** Plays per bin of the period's most played artists, ordered by the bin they peaked in (a ridgeline). */
    fun pulse(r: Range, i0: Int, i1: Int, max: Int = 16): JSONObject {
        val out = JSONObject().put("rows", JSONArray())
        if (i1 <= i0) return out
        val b = binsFor(r, 60)
        val plays = IntArray(h.artistNames.size)
        for (i in i0 until i1) plays[h.artist[i]]++
        val top = artistOrder(i0, i1).take(max)
        val slot = HashMap<Int, Int>().also { s -> top.forEachIndexed { k, a -> s[a] = k } }
        val counts = Array(top.size) { IntArray(b.count) }
        for (i in i0 until i1) {
            val k = slot[h.artist[i]] ?: continue
            counts[k][b.index(date(h.ts[i])).coerceIn(0, b.count - 1)]++
        }
        val rows = top.indices.map { k -> k to counts[k].indices.maxByOrNull { counts[k][it] }!! }.sortedBy { it.second }
        val arr = JSONArray()
        for ((k, peak) in rows) {
            arr.put(JSONObject().put("artist", h.artistNames[top[k]]).put("plays", plays[top[k]]).put("peak", peak).put("counts", JSONArray(counts[k].toList())))
        }
        return out.put("unit", b.unit.id).put("starts", b.startsJson()).put("bins", b.count).put("rows", arr)
    }

    // ---- lists ----

    /** A track played again and again in one week: best week per track, at least [OBSESSION_MIN] plays. */
    fun obsessions(i0: Int, i1: Int, max: Int = 12): JSONArray {
        val counts = HashMap<Long, Int>()
        for (i in i0 until i1) {
            val d = day(h.ts[i])
            val week = d - dow(d)
            val k = (h.track[i].toLong() shl 24) or (week and 0xFFFFFF)
            counts[k] = (counts[k] ?: 0) + 1
        }
        val best = HashMap<Int, Pair<Long, Int>>()
        for ((k, c) in counts) {
            if (c < OBSESSION_MIN) continue
            val tr = (k ushr 24).toInt()
            val week = k and 0xFFFFFF
            if (c > (best[tr]?.second ?: 0)) best[tr] = week to c
        }
        val a = JSONArray()
        best.entries.sortedByDescending { it.value.second }.take(max).forEach { (tr, wc) ->
            a.put(
                JSONObject().put("track", h.trackTitles[tr]).put("artist", h.artistNames[h.trackArtist[tr]])
                    .put("week", dayStart(wc.first) * 1000).put("count", wc.second),
            )
        }
        return a
    }

    /** Songs played back to back (consecutive plays, at most two hours apart): the longest run per song. */
    fun loops(i0: Int, i1: Int, max: Int = 5): JSONArray {
        val best = HashMap<Int, Pair<Int, Long>>()
        var run = 1
        var runStart = if (i1 > i0) h.ts[i0] else 0
        for (i in i0 + 1 until i1) {
            if (h.track[i] == h.track[i - 1] && h.ts[i] - h.ts[i - 1] < 7200) run++ else {
                run = 1
                runStart = h.ts[i]
            }
            if (run >= 3 && run > (best[h.track[i]]?.first ?: 0)) best[h.track[i]] = run to runStart
        }
        return JSONArray(
            best.entries.sortedByDescending { it.value.first }.take(max).map { (tr, v) ->
                JSONObject().put("track", h.trackTitles[tr]).put("artist", h.artistNames[h.trackArtist[tr]]).put("run", v.first).put("at", v.second * 1000)
            },
        )
    }

    /** Records you own, least played in the period first (all-time last play as the tie-breaker). */
    fun rarelyPlayed(i0: Int, i1: Int, max: Int = 12): JSONObject {
        val plays = IntArray(h.albumTitles.size)
        val last = LongArray(h.albumTitles.size)
        for (i in 0 until h.size) {
            val al = h.album[i]
            if (al < 0) continue
            last[al] = h.ts[i]
            if (i in i0 until i1) plays[al]++
        }
        class Row(val c: CollectionItem, val plays: Int, val last: Long)
        val rows = collection.distinctBy { it.releaseId }.map { c ->
            val al = albumIndex[Text.albumKey(c.artist, c.title)]
            Row(c, if (al != null) plays[al] else 0, if (al != null) last[al] else 0)
        }
        val a = JSONArray()
        rows.sortedWith(compareBy<Row> { it.plays }.thenBy { it.last }).take(max).forEach {
            a.put(
                JSONObject().put("id", it.c.releaseId).put("title", it.c.title).put("artist", it.c.artist)
                    .put("cover", it.c.cover.ifEmpty { it.c.thumb }).put("plays", it.plays).put("last", it.last * 1000),
            )
        }
        return JSONObject().put("items", a).put("owned", rows.size).put("unplayed", rows.count { it.plays == 0 })
            .put("neverPlayed", rows.count { it.last == 0L })
    }

    /** Albums you play a lot (whole albums: several different tracks) but don't own. */
    fun wishlist(i0: Int, i1: Int, max: Int = 12): JSONArray {
        val plays = IntArray(h.albumTitles.size)
        val last = LongArray(h.albumTitles.size)
        val pairs = HashSet<Long>()
        val distinct = IntArray(h.albumTitles.size)
        for (i in i0 until i1) {
            val al = h.album[i]
            if (al < 0) continue
            plays[al]++
            last[al] = h.ts[i]
            if (pairs.add((al.toLong() shl 32) or h.track[i].toLong())) distinct[al]++
        }
        val a = JSONArray()
        plays.indices
            .filter { plays[it] >= WISH_MIN_PLAYS && distinct[it] >= WISH_MIN_TRACKS && h.albumKeys[it] !in ownedKeys }
            .sortedByDescending { plays[it] }
            .take(max)
            .forEach { al ->
                val artist = h.artistNames[h.albumArtist[al]]
                a.put(
                    JSONObject().put("artist", artist).put("album", h.albumTitles[al]).put("plays", plays[al]).put("tracks", distinct[al])
                        .put("last", last[al] * 1000).put("key", h.albumKeys[al]).put("image", albumImages[h.albumKeys[al]] ?: "")
                        .put("search", "https://www.discogs.com/search/?type=master&q=" + Http.enc("$artist ${h.albumTitles[al]}")),
                )
            }
        return a
    }

    /** Albums of a period without a cover image yet (artist, album, key) — wishlist and top albums — to look up on Last.fm. */
    fun albumsNeedingImages(id: String): List<Triple<String, String, String>> {
        val r = range(id)
        val i0 = h.lowerBound(r.from)
        val i1 = h.lowerBound(r.to)
        val w = wishlist(i0, i1)
        val fromWish = (0 until w.length()).map { w.getJSONObject(it) }.filter { it.optString("image").isEmpty() }
            .map { Triple(it.getString("artist"), it.getString("album"), it.getString("key")) }
        val lp = IntArray(h.albumTitles.size)
        for (i in i0 until i1) if (h.album[i] >= 0) lp[h.album[i]]++
        val fromTop = lp.indices.filter { lp[it] > 0 }.sortedByDescending { lp[it] }.take(5)
            .filter { cover(it).isEmpty() }.map { Triple(h.artistNames[h.albumArtist[it]], h.albumTitles[it], h.albumKeys[it]) }
        return (fromTop + fromWish).distinctBy { it.third }
    }

    private fun cover(al: Int) = ownedKeys[h.albumKeys[al]]?.let { it.cover.ifEmpty { it.thumb } } ?: albumImages[h.albumKeys[al]] ?: ""

    fun top(i0: Int, i1: Int, max: Int): JSONObject {
        val ap = IntArray(h.artistNames.size)
        val tp = IntArray(h.trackTitles.size)
        val lp = IntArray(h.albumTitles.size)
        for (i in i0 until i1) {
            ap[h.artist[i]]++
            tp[h.track[i]]++
            if (h.album[i] >= 0) lp[h.album[i]]++
        }
        return JSONObject()
            .put("artists", JSONArray(ap.indices.filter { ap[it] > 0 }.sortedByDescending { ap[it] }.take(max).map { JSONObject().put("name", h.artistNames[it]).put("plays", ap[it]) }))
            .put("tracks", JSONArray(tp.indices.filter { tp[it] > 0 }.sortedByDescending { tp[it] }.take(max).map { JSONObject().put("title", h.trackTitles[it]).put("artist", h.artistNames[h.trackArtist[it]]).put("plays", tp[it]) }))
            .put("albums", JSONArray(lp.indices.filter { lp[it] > 0 }.sortedByDescending { lp[it] }.take(max).map { JSONObject().put("title", h.albumTitles[it]).put("artist", h.artistNames[h.albumArtist[it]]).put("plays", lp[it]).put("cover", cover(it)).put("owned", h.albumKeys[it] in ownedKeys) }))
    }

    // ---- insights and personality ----

    /**
     * Short observations about the period, most interesting first; each is a kind plus its
     * numbers (the page words them). Only what the data supports is included.
     */
    fun insights(r: Range, i0: Int, i1: Int, prev: Range?): JSONArray {
        val out = JSONArray()
        val n = i1 - i0
        if (n == 0) return out
        val ap = IntArray(h.artistNames.size)
        val tp = IntArray(h.trackTitles.size)
        val firstIn = LongArray(h.artistNames.size) { -1 }
        for (i in i0 until i1) {
            ap[h.artist[i]]++
            tp[h.track[i]]++
            if (firstIn[h.artist[i]] < 0) firstIn[h.artist[i]] = h.ts[i]
        }
        val top = ap.indices.maxByOrNull { ap[it] }!!
        // More or less than before
        if (prev != null) {
            val p0 = h.lowerBound(prev.from)
            val p1 = h.lowerBound(prev.to)
            val pn = p1 - p0
            if (pn > 0) out.put(JSONObject().put("kind", "volume").put("count", n).put("prev", pn).put("pct", Math.round((n - pn) * 100.0 / pn)).put("prevLabel", prev.label))
            if (p1 > p0) {
                val pp = IntArray(h.artistNames.size)
                for (i in p0 until p1) pp[h.artist[i]]++
                val prevTop = pp.indices.maxByOrNull { pp[it] }!!
                if (prevTop == top) out.put(JSONObject().put("kind", "stillTop").put("artist", h.artistNames[top]))
            }
        }
        out.put(JSONObject().put("kind", "topArtist").put("artist", h.artistNames[top]).put("plays", ap[top]).put("share", ap[top].toDouble() / n))
        val tt = tp.indices.maxByOrNull { tp[it] }!!
        if (tp[tt] >= 3) out.put(JSONObject().put("kind", "song").put("track", h.trackTitles[tt]).put("artist", h.artistNames[h.trackArtist[tt]]).put("plays", tp[tt]))
        // When
        val c = clock(i0, i1)
        val byDow = IntArray(7) { d -> (0 until 24).sumOf { c[d * 24 + it] } }
        val byHour = IntArray(24) { hr -> (0 until 7).sumOf { c[it * 24 + hr] } }
        val bestDow = byDow.indices.maxByOrNull { byDow[it] }!!
        // The two hours around the busiest single hour, so this agrees with the story's golden hour.
        val golden = (0 until 24).maxByOrNull { byHour[it] }!!
        val bestHour = if (byHour[(golden + 23) % 24] > byHour[(golden + 1) % 24]) (golden + 23) % 24 else golden
        if (n >= 20) out.put(JSONObject().put("kind", "peak").put("dow", bestDow).put("hour", bestHour).put("dowShare", byDow[bestDow].toDouble() / n))
        val night = (listOf(22, 23, 0, 1, 2, 3).sumOf { byHour[it] }).toDouble() / n
        if (n >= 20 && night >= 0.2) out.put(JSONObject().put("kind", "night").put("share", night))
        val weekend = (byDow[5] + byDow[6]).toDouble() / n
        if (n >= 20 && (weekend >= 0.4 || weekend <= 0.15) && r.kind != "week") out.put(JSONObject().put("kind", "weekend").put("share", weekend))
        // New and returning artists
        val startsHere = historyStart in r.from until r.to
        if (!startsHere) {
            val fresh = ap.indices.filter { ap[it] > 0 && firstTs[it] >= r.from }
            if (fresh.isNotEmpty()) {
                val best = fresh.maxByOrNull { ap[it] }!!
                out.put(JSONObject().put("kind", "discovery").put("count", fresh.size).put("best", h.artistNames[best]).put("plays", ap[best]).put("share", fresh.sumOf { ap[it] }.toDouble() / n))
            }
            val lastBefore = LongArray(h.artistNames.size)
            for (i in 0 until i0) lastBefore[h.artist[i]] = h.ts[i]
            val back = ap.indices.filter { ap[it] >= 5 && lastBefore[it] > 0 && firstIn[it] - lastBefore[it] >= 120 * DAY_SEC }.maxByOrNull { ap[it] }
            if (back != null) out.put(JSONObject().put("kind", "comeback").put("artist", h.artistNames[back]).put("plays", ap[back]).put("lastBefore", lastBefore[back] * 1000))
        }
        // Biggest day, streak, vinyl
        val days = HashMap<Long, Int>()
        for (i in i0 until i1) days.merge(day(h.ts[i]), 1, Int::plus)
        days.maxByOrNull { it.value }?.let { (d, cnt) -> if (r.kind != "week" || days.size > 1) out.put(JSONObject().put("kind", "bigDay").put("day", dayStart(d) * 1000).put("count", cnt)) }
        val st = streaks(r, i0, i1)
        if (st.getInt("longest") >= 4 && r.kind != "week") out.put(JSONObject().put("kind", "streak").put("days", st.getInt("longest")).put("to", st.getLong("longestTo")))
        val v = vinyl.count { it.ts in r.from until r.to }
        if (v > 0) out.put(JSONObject().put("kind", "vinyl").put("count", v).put("share", v.toDouble() / n))
        return out
    }

    /**
     * A listening personality for the period, Wrapped style: the trait the data shows most
     * strongly, measured against a threshold (score >= 1 means it clearly applies).
     */
    fun personality(r: Range, i0: Int, i1: Int): JSONObject {
        val n = i1 - i0
        if (n < 30) return JSONObject().put("id", "few").put("score", 0)
        val c = clock(i0, i1)
        val byHour = IntArray(24) { hr -> (0 until 7).sumOf { c[it * 24 + hr] } }
        val byDow = IntArray(7) { d -> (0 until 24).sumOf { c[d * 24 + it] } }
        val ap = IntArray(h.artistNames.size)
        for (i in i0 until i1) ap[h.artist[i]]++
        val sorted = ap.sortedDescending()
        val night = listOf(22, 23, 0, 1, 2, 3).sumOf { byHour[it] }.toDouble() / n
        val morning = (5..8).sumOf { byHour[it] }.toDouble() / n
        val weekend = (byDow[5] + byDow[6]).toDouble() / n
        val topShare = sorted[0].toDouble() / n
        val startsHere = historyStart in r.from until r.to
        val newShare = if (startsHere) 0.0 else ap.indices.filter { ap[it] > 0 && firstTs[it] >= r.from }.sumOf { ap[it] }.toDouble() / n
        val loop = loops(i0, i1, 1).optJSONObject(0)?.optInt("run") ?: 0
        val obs = obsessions(i0, i1, 1).optJSONObject(0)?.optInt("count") ?: 0
        val vinylShare = vinyl.count { it.ts in r.from until r.to }.toDouble() / n
        val shares = genreShares(i0, i1, 30)
        val variety = shares.count { it.second >= 0.03 }
        val scores = linkedMapOf(
            "nightOwl" to night / 0.30,
            "earlyBird" to morning / 0.22,
            "loyalist" to topShare / 0.15,
            "explorer" to newShare / 0.25,
            "onRepeat" to maxOf(loop / 5.0, obs / 15.0),
            "weekender" to weekend / 0.42,
            "crateDigger" to vinylShare / 0.10,
            "genreHopper" to variety / 9.0,
        )
        val ranked = scores.entries.sortedByDescending { it.value }
        val best = ranked.first()
        return JSONObject().put("id", if (best.value >= 1) best.key else "allRounder").put("score", best.value)
            .put("runnerUp", if (ranked[1].value >= 1) ranked[1].key else JSONObject.NULL)
            .put(
                "metrics",
                JSONObject().put("night", night).put("morning", morning).put("weekend", weekend).put("topShare", topShare)
                    .put("topArtist", h.artistNames[ap.indices.maxByOrNull { ap[it] }!!]).put("newShare", newShare)
                    .put("loop", loop).put("obsession", obs).put("vinyl", vinylShare).put("genres", variety),
            )
    }

    // ---- one artist ----

    /** Everything about one artist, over the whole history; [r] adds their rank and plays in that period. */
    fun artist(name: String, r: Range): JSONObject? {
        val a = artistIndex[Text.norm(name)] ?: return null
        val i0 = h.lowerBound(r.from)
        val i1 = h.lowerBound(r.to)
        var plays = 0
        var first = -1
        var last = -1
        val inRange = IntArray(h.artistNames.size)
        val months = HashMap<Int, Int>()
        val tracks = HashMap<Int, Int>()
        val albums = HashMap<Int, Int>()
        val hours = IntArray(24)
        var days = 0
        var lastDay = Long.MIN_VALUE
        var run = 0
        var best = 0
        var bestEnd = 0L
        for (i in 0 until h.size) {
            if (i in i0 until i1) inRange[h.artist[i]]++
            if (h.artist[i] != a) continue
            plays++
            if (first < 0) first = i
            last = i
            val dt = date(h.ts[i])
            val mk = dt.year * 12 + dt.monthValue - 1
            months[mk] = (months[mk] ?: 0) + 1
            tracks[h.track[i]] = (tracks[h.track[i]] ?: 0) + 1
            if (h.album[i] >= 0) albums[h.album[i]] = (albums[h.album[i]] ?: 0) + 1
            hours[hour(h.ts[i])]++
            val d = day(h.ts[i])
            if (d != lastDay) {
                days++
                run = if (d == lastDay + 1) run + 1 else 1
                lastDay = d
                if (run > best) {
                    best = run
                    bestEnd = d
                }
            }
        }
        if (plays == 0) return null
        val m0 = months.keys.minOrNull()!!
        val m1 = date(nowSec).let { it.year * 12 + it.monthValue - 1 }
        val busiest = months.maxByOrNull { it.value }!!
        fun monthStart(mk: Int) = ms(LocalDate.of(mk / 12, mk % 12 + 1, 1))
        val key = Text.norm(name)
        val owned = collection.filter { Text.norm(it.artist) == key || Text.norm(it.artist).startsWith("$key ") }.distinctBy { it.releaseId }
        val rank = if (inRange[a] > 0) inRange.count { it > inRange[a] } + 1 else 0
        return JSONObject()
            .put("name", h.artistNames[a]).put("plays", plays).put("days", days)
            .put("first", JSONObject().put("ts", h.ts[first] * 1000).put("track", h.trackTitles[h.track[first]]))
            .put("last", JSONObject().put("ts", h.ts[last] * 1000).put("track", h.trackTitles[h.track[last]]))
            .put("busiestMonth", JSONObject().put("start", monthStart(busiest.key)).put("count", busiest.value))
            .put("monthsFrom", monthStart(m0)).put("months", JSONArray((m0..maxOf(m0, m1)).map { months[it] ?: 0 }))
            .put("hours", JSONArray(hours.toList()))
            .put("streak", best).put("streakEnd", dayStart(bestEnd) * 1000)
            .put("range", JSONObject().put("id", r.id).put("kind", r.kind).put("label", r.label).put("from", r.from).put("plays", inRange[a]).put("rank", rank))
            .put("tracks", JSONArray(tracks.entries.sortedByDescending { it.value }.take(10).map { JSONObject().put("title", h.trackTitles[it.key]).put("plays", it.value) }))
            .put("albums", JSONArray(albums.entries.sortedByDescending { it.value }.take(5).map { JSONObject().put("title", h.albumTitles[it.key]).put("plays", it.value).put("cover", cover(it.key)).put("owned", h.albumKeys[it.key] in ownedKeys) }))
            .put("owned", JSONArray(owned.map { JSONObject().put("id", it.releaseId).put("title", it.title).put("artist", it.artist).put("cover", it.cover.ifEmpty { it.thumb }).put("year", it.year) }))
            .put("genres", JSONArray(genres.of(h.artistNames[a], null).first.map { Genres.label(it) }))
    }

    // ---- the story ----

    /** Everything for the Wrapped-style story of a period. */
    fun story(r: Range): JSONObject {
        val i0 = h.lowerBound(r.from)
        val i1 = h.lowerBound(r.to)
        val n = i1 - i0
        val prev = previous(r)
        val summary = summary(r, i0, i1)
        prev?.let { p -> summary.put("prev", summary(p, h.lowerBound(p.from), h.lowerBound(p.to)).put("label", p.label)) }
        val out = JSONObject().put("range", rangeJson(r)).put("summary", summary)
        if (n == 0) return out
        val ap = IntArray(h.artistNames.size)
        val tp = IntArray(h.trackTitles.size)
        val lp = IntArray(h.albumTitles.size)
        val aDays = IntArray(h.artistNames.size)
        val aLast = LongArray(h.artistNames.size) { Long.MIN_VALUE }
        for (i in i0 until i1) {
            val a = h.artist[i]
            ap[a]++
            tp[h.track[i]]++
            if (h.album[i] >= 0) lp[h.album[i]]++
            val d = day(h.ts[i])
            if (aLast[a] != d) {
                aDays[a]++
                aLast[a] = d
            }
        }
        // An artist's picture: the cover of their most played album that has one.
        fun artistCover(a: Int): String {
            var best = -1
            for (al in lp.indices) if (lp[al] > 0 && h.albumArtist[al] == a && cover(al).isNotEmpty() && (best < 0 || lp[al] > lp[best])) best = al
            return if (best >= 0) cover(best) else ""
        }
        val topA = ap.indices.filter { ap[it] > 0 }.sortedByDescending { ap[it] }.take(5)
        val topT = tp.indices.filter { tp[it] > 0 }.sortedByDescending { tp[it] }.take(5)
        val topL = lp.indices.filter { lp[it] > 0 }.sortedByDescending { lp[it] }.take(5)
        // The top song's biggest day.
        val songDays = HashMap<Long, Int>()
        if (topT.isNotEmpty()) for (i in i0 until i1) if (h.track[i] == topT[0]) songDays.merge(day(h.ts[i]), 1, Int::plus)
        val songBest = songDays.maxByOrNull { it.value }
        val c = clock(i0, i1)
        val byHour = IntArray(24) { hr -> (0 until 7).sumOf { c[it * 24 + hr] } }
        val days = HashMap<Long, Int>()
        for (i in i0 until i1) days.merge(day(h.ts[i]), 1, Int::plus)
        val busiest = days.maxByOrNull { it.value }
        val fresh = if (historyStart in r.from until r.to) emptyList() else ap.indices.filter { ap[it] > 0 && firstTs[it] >= r.from }
        val bestNew = fresh.maxByOrNull { ap[it] }
        val vinylIn = vinyl.filter { it.ts in r.from until r.to }
        val vinylTop = vinylIn.groupBy { Text.albumKey(it.artist, it.album) }.entries.sortedByDescending { it.value.size }.take(3)
            .map { (k, v) -> JSONObject().put("title", v[0].album).put("artist", v[0].artist).put("plays", v.size).put("cover", ownedKeys[k]?.let { it.cover.ifEmpty { it.thumb } } ?: "") }
        return out
            .put("personality", personality(r, i0, i1))
            .put("artists", JSONArray(topA.map { JSONObject().put("name", h.artistNames[it]).put("plays", ap[it]).put("share", ap[it].toDouble() / n).put("days", aDays[it]).put("cover", artistCover(it)) }))
            .put("tracks", JSONArray(topT.map { JSONObject().put("title", h.trackTitles[it]).put("artist", h.artistNames[h.trackArtist[it]]).put("plays", tp[it]) }))
            .put("songBestDay", if (songBest != null) JSONObject().put("day", dayStart(songBest.key) * 1000).put("count", songBest.value) else JSONObject.NULL)
            .put("albums", JSONArray(topL.map { JSONObject().put("title", h.albumTitles[it]).put("artist", h.artistNames[h.albumArtist[it]]).put("plays", lp[it]).put("cover", cover(it)).put("owned", h.albumKeys[it] in ownedKeys) }))
            .put("hours", JSONArray(byHour.toList()))
            .put("goldenHour", (0 until 24).maxByOrNull { byHour[it] }!!)
            .put("busiestDay", if (busiest != null) JSONObject().put("day", dayStart(busiest.key) * 1000).put("count", busiest.value) else JSONObject.NULL)
            .put("newArtists", JSONObject().put("count", fresh.size).put("best", bestNew?.let { JSONObject().put("name", h.artistNames[it]).put("plays", ap[it]).put("first", firstTs[it] * 1000) } ?: JSONObject.NULL))
            .put("loop", loops(i0, i1, 1).optJSONObject(0) ?: JSONObject.NULL)
            .put("obsession", obsessions(i0, i1, 1).optJSONObject(0) ?: JSONObject.NULL)
            .put("genres", JSONArray(genreShares(i0, i1, 5).map { JSONObject().put("name", it.first).put("share", it.second) }))
            .put("vinyl", JSONObject().put("count", vinylIn.size).put("share", vinylIn.size.toDouble() / n).put("top", JSONArray(vinylTop)))
            .put("streak", streaks(r, i0, i1))
            .put("insights", insights(r, i0, i1, prev))
            .put("first", JSONObject().put("track", h.trackTitles[h.track[i0]]).put("artist", h.artistNames[h.artist[i0]]).put("ts", h.ts[i0] * 1000))
    }
}
