package com.example.recordshelf.history

import com.example.recordshelf.discogs.CollectionItem
import com.example.recordshelf.net.Http
import com.example.recordshelf.scrobble.Listen
import com.example.recordshelf.util.Text
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.BitSet

/** A time range for the stats: [from, to) in Unix seconds. */
data class Range(val id: String, val label: String, val from: Long, val to: Long)

/**
 * Everything the Stats tab shows, computed from the [History] in one pass per figure.
 * Local time ([zone]) decides days, weeks, hours and months. Records you own come from
 * [collection]; [vinyl] are the scrobbles made here from records (for the year in review).
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
        const val OBSESSION_MIN = 7
        const val WISH_MIN_PLAYS = 8
        const val WISH_MIN_TRACKS = 4
    }

    private val rules = zone.rules
    private val ownedKeys: Map<String, CollectionItem> = collection.associateBy { Text.albumKey(it.artist, it.title) }
    private val albumIndex: Map<String, Int> by lazy { h.albumKeys.withIndex().associate { (i, k) -> k to i } }

    // ---- time helpers ----

    private fun local(t: Long) = t + rules.getOffset(Instant.ofEpochSecond(t)).totalSeconds
    fun day(t: Long) = Math.floorDiv(local(t), 86_400L)
    private fun dow(day: Long) = Math.floorMod(day + 3, 7L).toInt() // 1970-01-01 was a Thursday; Monday = 0
    private fun hour(t: Long) = (Math.floorMod(local(t), 86_400L) / 3600).toInt()
    private fun dayStart(day: Long) = LocalDate.ofEpochDay(day).atStartOfDay(zone).toEpochSecond()
    private fun date(t: Long): LocalDate = Instant.ofEpochSecond(t).atZone(zone).toLocalDate()
    private fun yearStart(y: Int) = LocalDate.of(y, 1, 1).atStartOfDay(zone).toEpochSecond()

    fun ranges(): List<Range> {
        val today = date(nowSec)
        val out = arrayListOf(
            Range("12m", "Last 12 months", today.minusYears(1).plusDays(1).atStartOfDay(zone).toEpochSecond(), nowSec + 1),
            Range("all", "All time", 0, nowSec + 1),
        )
        if (h.size > 0) {
            for (y in today.year downTo date(h.ts[0]).year) {
                out += Range("y$y", y.toString(), yearStart(y), minOf(yearStart(y + 1), nowSec + 1))
            }
        }
        return out
    }

    fun range(id: String): Range = ranges().firstOrNull { it.id == id } ?: ranges().first()

    // ---- the Stats tab ----

    fun compute(r: Range): JSONObject {
        val i0 = h.lowerBound(r.from)
        val i1 = h.lowerBound(r.to)
        return JSONObject()
            .put("range", JSONObject().put("id", r.id).put("label", r.label).put("from", r.from).put("to", r.to))
            .put("ranges", JSONArray().also { a -> ranges().forEach { a.put(JSONObject().put("id", it.id).put("label", it.label)) } })
            .put("summary", summary(r, i0, i1))
            .put("clock", JSONArray(clock(i0, i1).toList()))
            .put("streaks", streaks(r, i0, i1))
            .put("newArtists", newArtists(r))
            .put("genres", genreDrift(i0, i1))
            .put("obsessions", obsessions(i0, i1))
            .put("rarely", rarelyPlayed(i0, i1))
            .put("wishlist", wishlist(i0, i1))
            .put("top", top(i0, i1, 10))
            .put("record", record(r, i0, i1))
            .put("pulse", pulse(i0, i1))
            .put("loops", loops(i0, i1))
    }

    // ---- the record, phases, loops ----

    /**
     * Every play of the range for the radial "record": its day (0 = first day of the range,
     * one groove each), minute of the day, and artist and track as indexes into tables that
     * list the range's artists by plays (index 0 is the most played).
     */
    fun record(r: Range, i0: Int, i1: Int): JSONObject {
        val out = JSONObject()
        if (i1 <= i0) return out.put("days", 0)
        val firstDay = day(maxOf(r.from, h.ts[i0]))
        val lastDay = maxOf(firstDay, day(minOf(r.to - 1, nowSec)))
        val plays = IntArray(h.artistNames.size)
        for (i in i0 until i1) plays[h.artist[i]]++
        val order = plays.indices.filter { plays[it] > 0 }.sortedByDescending { plays[it] }
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
            m.put((Math.floorMod(local(ts), 86_400L) / 60).toInt())
            a.put(local[h.artist[i]])
            t.put(trackLocal.getOrPut(h.track[i]) { titles.put(h.trackTitles[h.track[i]]); titles.length() - 1 })
        }
        return out.put("start", dayStart(firstDay) * 1000).put("days", (lastDay - firstDay + 1).toInt())
            .put("d", d).put("m", m).put("a", a).put("t", t).put("tracks", titles)
            .put("artists", JSONArray(order.map { h.artistNames[it] })).put("plays", JSONArray(order.map { plays[it] }))
    }

    /** Weekly plays of the range's most played artists, ordered by the week they peaked (a ridgeline). */
    fun pulse(i0: Int, i1: Int, max: Int = 16): JSONObject {
        val out = JSONObject().put("rows", JSONArray())
        if (i1 <= i0) return out
        val plays = IntArray(h.artistNames.size)
        for (i in i0 until i1) plays[h.artist[i]]++
        val top = plays.indices.filter { plays[it] > 0 }.sortedByDescending { plays[it] }.take(max)
        val slot = HashMap<Int, Int>().also { s -> top.forEachIndexed { k, a -> s[a] = k } }
        val d0 = day(h.ts[i0])
        val week0 = d0 - dow(d0)
        val weeks = ((day(h.ts[i1 - 1]) - week0) / 7 + 1).toInt()
        val per = if (weeks > 160) 4 else 1
        val bins = (weeks + per - 1) / per
        val counts = Array(top.size) { IntArray(bins) }
        for (i in i0 until i1) {
            val k = slot[h.artist[i]] ?: continue
            counts[k][((day(h.ts[i]) - week0) / 7 / per).toInt()]++
        }
        val rows = top.indices.map { k -> k to counts[k].indices.maxByOrNull { counts[k][it] }!! }.sortedBy { it.second }
        val arr = JSONArray()
        for ((k, peak) in rows) {
            arr.put(JSONObject().put("artist", h.artistNames[top[k]]).put("plays", plays[top[k]]).put("peak", peak).put("counts", JSONArray(counts[k].toList())))
        }
        return out.put("start", dayStart(week0) * 1000).put("binDays", 7 * per).put("bins", bins).put("rows", arr)
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

    // ---- one artist ----

    private val artistIndex: Map<String, Int> by lazy { h.artistNames.withIndex().associate { (i, n) -> Text.norm(n) to i } }

    /** Everything about one artist, over the whole history; [r] adds their rank and plays in that range. */
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
        fun monthStart(mk: Int) = LocalDate.of(mk / 12, mk % 12 + 1, 1).atStartOfDay(zone).toEpochSecond() * 1000
        fun cover(al: Int) = ownedKeys[h.albumKeys[al]]?.let { it.cover.ifEmpty { it.thumb } } ?: albumImages[h.albumKeys[al]] ?: ""
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
            .put("range", JSONObject().put("id", r.id).put("label", r.label).put("plays", inRange[a]).put("rank", rank))
            .put("tracks", JSONArray(tracks.entries.sortedByDescending { it.value }.take(10).map { JSONObject().put("title", h.trackTitles[it.key]).put("plays", it.value) }))
            .put("albums", JSONArray(albums.entries.sortedByDescending { it.value }.take(5).map { JSONObject().put("title", h.albumTitles[it.key]).put("plays", it.value).put("cover", cover(it.key)).put("owned", h.albumKeys[it.key] in ownedKeys) }))
            .put("owned", JSONArray(owned.map { JSONObject().put("id", it.releaseId).put("title", it.title).put("artist", it.artist).put("cover", it.cover.ifEmpty { it.thumb }).put("year", it.year) }))
            .put("genres", JSONArray(genres.of(h.artistNames[a], null).first.map { Genres.label(it) }))
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
        val first = if (r.from > 0) r.from else if (h.size > 0) h.ts[0] else nowSec
        val spanDays = maxOf(1L, day(minOf(r.to - 1, nowSec)) - day(first) + 1)
        return JSONObject().put("scrobbles", i1 - i0).put("artists", artists.cardinality()).put("tracks", tracks.cardinality())
            .put("albums", albums.cardinality()).put("days", days).put("spanDays", spanDays)
            .put("perDay", Math.round((i1 - i0) * 10.0 / spanDays) / 10.0)
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

    /** Days in a row with music: the current run (if the range reaches today), the longest, and artists' longest runs. */
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
        val today = day(nowSec)
        val current = if (r.to > nowSec && i1 > i0 && lastDay >= today - 1) run else 0
        val artists = JSONArray()
        (0 until n).filter { aBest[it] >= 3 }.sortedByDescending { aBest[it] }.take(3).forEach { a ->
            artists.put(
                JSONObject().put("artist", h.artistNames[a]).put("days", aBest[a])
                    .put("from", dayStart(aBestEnd[a] - aBest[a] + 1) * 1000).put("to", dayStart(aBestEnd[a]) * 1000),
            )
        }
        return JSONObject()
            .put("current", current).put("currentFrom", if (current > 0) dayStart(runStart) * 1000 else 0)
            .put("longest", best).put("longestFrom", if (best > 0) dayStart(bestStart) * 1000 else 0)
            .put("longestTo", if (best > 0) dayStart(bestEnd) * 1000 else 0)
            .put("artists", artists)
    }

    /** Artists heard for the first time ever, per month (or per year for long ranges). */
    fun newArtists(r: Range): JSONObject {
        val n = h.artistNames.size
        val first = LongArray(n) { -1 }
        val plays = IntArray(n)
        for (i in 0 until h.size) {
            val a = h.artist[i]
            if (first[a] < 0) first[a] = h.ts[i]
            plays[a]++
        }
        if (h.size == 0) return JSONObject().put("unit", "month").put("buckets", JSONArray())
        val from = maxOf(r.from, h.ts[0])
        val to = minOf(r.to - 1, nowSec)
        val d0 = date(from)
        val d1 = date(to)
        val months = (d1.year - d0.year) * 12 + (d1.monthValue - d0.monthValue) + 1
        val yearly = months > 36
        fun bucketOf(d: LocalDate) = if (yearly) d.year - d0.year else (d.year - d0.year) * 12 + (d.monthValue - d0.monthValue)
        val count = if (yearly) d1.year - d0.year + 1 else months
        val counts = IntArray(count)
        val members = Array(count) { ArrayList<Int>() }
        for (a in 0 until n) {
            val f = first[a]
            if (f < r.from || f >= r.to) continue
            val b = bucketOf(date(f))
            if (b in 0 until count) {
                counts[b]++
                members[b] += a
            }
        }
        val firstOfHistory = date(h.ts[0])
        val buckets = JSONArray()
        for (b in 0 until count) {
            val start = if (yearly) LocalDate.of(d0.year + b, 1, 1) else d0.withDayOfMonth(1).plusMonths(b.toLong())
            val isStart = if (yearly) start.year == firstOfHistory.year else start.year == firstOfHistory.year && start.monthValue == firstOfHistory.monthValue
            buckets.put(
                JSONObject().put("start", start.atStartOfDay(zone).toEpochSecond() * 1000).put("count", counts[b]).put("historyStart", isStart)
                    .put("top", JSONArray(members[b].sortedByDescending { plays[it] }.take(3).map { h.artistNames[it] })),
            )
        }
        return JSONObject().put("unit", if (yearly) "year" else "month").put("buckets", buckets)
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

    /** The genres that get their own color: the biggest over all time, so colors don't change with the range. */
    private val topGenres: List<Int> by lazy {
        val (g, _) = scrobbleGenre
        val totals = IntArray(genreKeys.size)
        for (x in g) if (x >= 0) totals[x]++
        totals.indices.filter { totals[it] > 0 }.sortedByDescending { totals[it] }.take(TOP_GENRES)
    }

    /** Share of each top genre per month, quarter or year of the range (part-to-whole over time). */
    fun genreDrift(i0: Int, i1: Int): JSONObject {
        val (g, src) = scrobbleGenre
        val top = topGenres
        val slot = HashMap<Int, Int>().also { m -> top.forEachIndexed { i, id -> m[id] = i } }
        val out = JSONObject()
            .put("names", JSONArray(top.map { Genres.label(genreKeys[it]) } + "Other"))
            .put("keys", JSONArray(top.map { genreKeys[it] } + "other"))
        if (i1 <= i0) return out.put("unit", "month").put("periods", JSONArray()).put("coverage", JSONObject())
        val d0 = date(h.ts[i0])
        val d1 = date(h.ts[i1 - 1])
        val months = (d1.year - d0.year) * 12 + (d1.monthValue - d0.monthValue) + 1
        val unit = when {
            months <= 15 -> 1
            months <= 60 -> 3
            else -> 12
        }
        val base = if (unit == 12) LocalDate.of(d0.year, 1, 1) else LocalDate.of(d0.year, ((d0.monthValue - 1) / unit) * unit + 1, 1)
        fun periodOf(d: LocalDate) = ((d.year - base.year) * 12 + (d.monthValue - base.monthValue)) / unit
        val count = periodOf(d1) + 1
        val counts = Array(count) { IntArray(top.size + 1) }
        val coverage = IntArray(3)
        for (i in i0 until i1) {
            coverage[src[i].toInt()]++
            val x = g[i]
            if (x < 0) continue
            counts[periodOf(date(h.ts[i]))][slot[x] ?: top.size]++
        }
        val periods = JSONArray()
        for (p in 0 until count) {
            val total = counts[p].sum()
            periods.put(
                JSONObject().put("start", base.plusMonths(p.toLong() * unit).atStartOfDay(zone).toEpochSecond() * 1000).put("total", total)
                    .put("shares", JSONArray(counts[p].map { if (total > 0) Math.round(it * 1000.0 / total) / 1000.0 else 0.0 })),
            )
        }
        val n = (i1 - i0).toDouble()
        val unitName = when (unit) {
            1 -> "month"
            3 -> "quarter"
            else -> "year"
        }
        return out.put("unit", unitName).put("periods", periods)
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

    /** Records you own, least played in the range first (all-time last play as the tie-breaker). */
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

    /** Wishlist albums without a cover image yet (artist, album, key), to look up on Last.fm. */
    fun wishlistNeedingImages(id: String): List<Triple<String, String, String>> {
        val r = range(id)
        val w = wishlist(h.lowerBound(r.from), h.lowerBound(r.to))
        return (0 until w.length()).map { w.getJSONObject(it) }.filter { it.optString("image").isEmpty() }
            .map { Triple(it.getString("artist"), it.getString("album"), it.getString("key")) }
    }

    fun top(i0: Int, i1: Int, max: Int): JSONObject {
        val ap = IntArray(h.artistNames.size)
        val tp = IntArray(h.trackTitles.size)
        val lp = IntArray(h.albumTitles.size)
        for (i in i0 until i1) {
            ap[h.artist[i]]++
            tp[h.track[i]]++
            if (h.album[i] >= 0) lp[h.album[i]]++
        }
        fun cover(al: Int) = ownedKeys[h.albumKeys[al]]?.let { it.cover.ifEmpty { it.thumb } } ?: albumImages[h.albumKeys[al]] ?: ""
        return JSONObject()
            .put("artists", JSONArray(ap.indices.filter { ap[it] > 0 }.sortedByDescending { ap[it] }.take(max).map { JSONObject().put("name", h.artistNames[it]).put("plays", ap[it]) }))
            .put("tracks", JSONArray(tp.indices.filter { tp[it] > 0 }.sortedByDescending { tp[it] }.take(max).map { JSONObject().put("title", h.trackTitles[it]).put("artist", h.artistNames[h.trackArtist[it]]).put("plays", tp[it]) }))
            .put("albums", JSONArray(lp.indices.filter { lp[it] > 0 }.sortedByDescending { lp[it] }.take(max).map { JSONObject().put("title", h.albumTitles[it]).put("artist", h.artistNames[h.albumArtist[it]]).put("plays", lp[it]).put("cover", cover(it)).put("owned", h.albumKeys[it] in ownedKeys) }))
    }

    // ---- year in review ----

    fun review(r: Range): JSONObject {
        val i0 = h.lowerBound(r.from)
        val i1 = h.lowerBound(r.to)
        val firstDate = date(if (r.from > 0) r.from else if (h.size > 0) h.ts[0] else nowSec)
        val lastDate = date(minOf(r.to - 1, nowSec))
        val months = IntArray(((lastDate.year - firstDate.year) * 12 + lastDate.monthValue - firstDate.monthValue + 1).coerceIn(1, 13))
        val dayCounts = HashMap<Long, Int>()
        for (i in i0 until i1) {
            val d = date(h.ts[i])
            val m = (d.year - firstDate.year) * 12 + d.monthValue - firstDate.monthValue
            if (m in months.indices) months[m]++
            val dd = d.toEpochDay()
            dayCounts[dd] = (dayCounts[dd] ?: 0) + 1
        }
        val busiest = dayCounts.maxByOrNull { it.value }
        val n = h.artistNames.size
        val first = LongArray(n) { -1 }
        val inRange = IntArray(n)
        for (i in 0 until h.size) {
            if (first[h.artist[i]] < 0) first[h.artist[i]] = h.ts[i]
            if (i in i0 until i1) inRange[h.artist[i]]++
        }
        val historyStart = if (h.size > 0) h.ts[0] else 0
        val newOnes = (0 until n).filter { first[it] in r.from until r.to }
        val vinylIn = vinyl.filter { it.ts in r.from until r.to }
        val vinylTop = vinylIn.groupBy { Text.albumKey(it.artist, it.album) }.entries.sortedByDescending { it.value.size }.take(3)
            .map { (k, v) -> JSONObject().put("title", v[0].album).put("artist", v[0].artist).put("plays", v.size).put("cover", ownedKeys[k]?.let { it.cover.ifEmpty { it.thumb } } ?: "") }
        val streak = streaks(r, i0, i1)
        val obs = obsessions(i0, i1, 1)
        return JSONObject()
            .put("range", JSONObject().put("id", r.id).put("label", r.label).put("from", r.from).put("to", r.to))
            .put("ranges", JSONArray().also { a -> ranges().filter { it.id != "all" }.forEach { a.put(JSONObject().put("id", it.id).put("label", it.label)) } })
            .put("summary", summary(r, i0, i1))
            .put("top", top(i0, i1, 5))
            .put("newArtists", newOnes.size)
            .put("historyStartsHere", h.size > 0 && historyStart in r.from until r.to)
            .put("topNew", JSONArray(newOnes.sortedByDescending { inRange[it] }.take(5).map { JSONObject().put("name", h.artistNames[it]).put("plays", inRange[it]).put("first", first[it] * 1000) }))
            .put("months", JSONArray(months.toList()))
            .put("monthsFrom", firstDate.withDayOfMonth(1).atStartOfDay(zone).toEpochSecond() * 1000)
            .put("busiestDay", if (busiest != null) JSONObject().put("day", dayStart(busiest.key) * 1000).put("count", busiest.value) else JSONObject.NULL)
            .put("streak", streak)
            .put("obsession", if (obs.length() > 0) obs.getJSONObject(0) else JSONObject.NULL)
            .put("clock", JSONArray(clock(i0, i1).toList()))
            .put("vinyl", JSONObject().put("count", vinylIn.size).put("share", if (i1 > i0) vinylIn.size.toDouble() / (i1 - i0) else 0.0).put("top", JSONArray(vinylTop)))
            .put(
                "first",
                if (i1 > i0) JSONObject().put("track", h.trackTitles[h.track[i0]]).put("artist", h.artistNames[h.artist[i0]]).put("ts", h.ts[i0] * 1000) else JSONObject.NULL,
            )
    }
}
