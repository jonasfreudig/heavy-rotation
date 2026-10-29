package app.recordshelf.history

import app.recordshelf.discogs.CollectionItem
import app.recordshelf.net.Http
import app.recordshelf.scrobble.Listen
import app.recordshelf.util.Text
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
    @Volatile private var albumImages: Map<String, String> = emptyMap(),
    /** Release years looked up per album key (0: not found); see [Years]. */
    private val albumYears: Map<String, Int> = emptyMap(),
    /** Last.fm's all-time counts for everyone, for the comparisons. */
    @Volatile private var globals: Globals = Globals(),
) {
    /** New covers, photos or Last.fm counts arrived: the same stats, now with them (nothing else is worked out again). */
    fun withPictures(images: Map<String, String>, g: Globals) {
        albumImages = images
        globals = g
    }

    companion object {
        const val TOP_GENRES = 6
        const val TOP_INKS = 8

        /** Top lists go this far; the page shows 10 of them unless you ask for 50 or 100. */
        const val TOP_MAX = 100

        /** A full top list opened from a page far down it also shows this many places either side of that page's. */
        const val AROUND = 8

        /** A new artist you "kept": played this often since. */
        const val KEPT_PLAYS = 10
        const val OBSESSION_MIN = 7
        const val WISH_MIN_PLAYS = 8
        const val WISH_MIN_TRACKS = 4

        // Badges on the shelf: heavy rotation (the last two weeks), new arrivals, lost favorites and worn in.
        const val BADGE_HEAVY_DAYS = 14L
        const val BADGE_HEAVY_MIN = 12
        const val BADGE_NEW_DAYS = 30L
        const val BADGE_FAV_MIN = 30
        const val BADGE_WORN_TOP = 3
        const val BADGE_LOST_TOP = 10
        const val BADGE_LOST_DAYS = 182L

        /** Explore: quiet for this long counts as lost. */
        const val LOST_DAYS = 180L
        const val LOST_MIN = 10
        const val LOST_ALBUM_MIN = 8
        const val EXPLORE_MAX = 15
        const val REGULARS_SKIP_TOP = 25
        const val REGULARS_MIN_MONTHS = 4
        const val DEEP_CUT_ARTISTS = 12
        const val TAIL_PLAYS = 5
        const val PLAYLIST_SIZE = 40
        const val GENRE_ALL_MAX = 300
        const val GENRE_ALSO_MAX = 12

        /** Songs listed for the period on an artist or album page. */
        const val RANGE_LIST = 5

        /** A photo is taken "while" a song played up to this long after it started, "around" it within this much. */
        const val PLAYING_SEC = 7 * 60L
        const val NEAR_SEC = 45 * 60L

        // You against everyone: what is looked up on Last.fm, and what the lists need.
        const val VS_ARTISTS = 200
        const val VS_ARTIST_MIN = 3
        const val VS_TOPS = 25
        const val VS_TRACKS = 150
        const val VS_TRACK_MIN = 4
        const val VS_LIST = 10
        const val FAN_MIN = 10
        const val GEM_MIN = 20

        /** Listener tiers, from household names down to the underground. */
        val VS_TIERS = longArrayOf(1_000_000, 250_000, 50_000, 10_000, 0)

        /** Artist photos live in the image cache next to album covers, under this prefix and the artist's name. */
        const val ARTIST_PHOTO = "@artist:"
        fun photoKey(name: String) = ARTIST_PHOTO + Text.norm(name)

        /** Scrobbles carry no length; "hours" assume an average track. */
        const val MINUTES_PER_PLAY = 3.5

        /** A listening session ends after half an hour without a play. */
        private const val SESSION_GAP = 30 * 60L
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
                id.startsWith("d") -> LocalDate.parse(id.substring(1)).let { d -> span("day", "d$d", "$d", d, d.plusDays(1)) }
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
            "day" -> "d" + start.plusDays(step.toLong())
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

    private fun rangeJson(r: Range) = JSONObject().put("id", r.id).put("kind", r.kind).put("label", r.label).put("running", r.to > nowSec)
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
            "day" -> 1L
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
            .put("habits", habits(r, i0, i1))
            .put("newArtists", newArtists(r, i0, i1))
            .put("genres", genreDrift(r, i0, i1))
            .put("decades", decades(i0, i1))
            .put("obsessions", obsessions(i0, i1))
            .put("loops", loops(i0, i1))
            .put("rarely", rarelyPlayed(i0, i1))
            .put("wishlist", wishlist(i0, i1))
            .put("top", top(i0, i1, TOP_MAX))
            .also { o ->
                if (r.kind == "day") o.put("day", dayDetails(r, i0, i1))
                if (r.kind == "week") o.put("week", weekDays(r))
            }
    }

    // ---- one day (the Day tab) and the days of a week ----

    /** First play of each song, over the whole history. */
    private val trackFirst: LongArray by lazy {
        LongArray(h.trackTitles.size) { -1 }.also { f -> for (i in 0 until h.size) if (f[h.track[i]] < 0) f[h.track[i]] = h.ts[i] }
    }

    /** The [max] most played artists among plays [i0]..[i1]: their index → color 1.. (0 for everyone else). */
    private fun colors(i0: Int, i1: Int, max: Int = 5): Map<Int, Int> {
        val n = HashMap<Int, Int>()
        for (i in i0 until i1) n.merge(h.artist[i], 1, Int::plus)
        return n.entries.sortedByDescending { it.value }.take(max).withIndex().associate { (k, e) -> e.key to k + 1 }
    }

    /** Plays [i0]..[i1] as [hour of the day, color] pairs, for a 24-hour strip ([start]: that day's midnight; the clock's hours on a DST day too). */
    private fun strip(start: Long, i0: Int, i1: Int, colors: Map<Int, Int>) =
        JSONArray((i0 until i1).map { i -> JSONArray().put(Math.round((local(h.ts[i]) - local(start)) / 36.0) / 100.0).put(colors[h.artist[i]] ?: 0) })

    private fun playJson(i: Int) = JSONObject().put("title", h.trackTitles[h.track[i]]).put("artist", h.artistNames[h.artist[i]])
        .put("album", if (h.album[i] >= 0) h.albumTitles[h.album[i]] else "").put("cover", if (h.album[i] >= 0) cover(h.album[i]) else "")
        .put("ts", h.ts[i])

    /**
     * The Day tab: the plays on a 24-hour strip, the listening sessions (a new one after half an
     * hour without a play), first and latest song, what repeated, what was new, the same date in
     * other years, vinyl, and the usual for this weekday up to the same time of day (last 8 weeks).
     */
    fun dayDetails(r: Range, i0: Int, i1: Int): JSONObject {
        val c = colors(i0, i1)
        val o = JSONObject().put("artists", JSONArray(c.entries.sortedBy { it.value }.map { h.artistNames[it.key] }))
            .put("plays", strip(r.from, i0, i1, c))
        val sessions = JSONArray()
        var s0 = i0
        while (s0 < i1) {
            var e = s0
            while (e + 1 < i1 && h.ts[e + 1] - h.ts[e] <= SESSION_GAP) e++
            val n = e - s0 + 1
            val sa = HashMap<Int, Int>()
            val sl = HashMap<Int, Int>()
            for (i in s0..e) {
                sa.merge(h.artist[i], 1, Int::plus)
                if (h.album[i] >= 0) sl.merge(h.album[i], 1, Int::plus)
            }
            val a = sa.maxBy { it.value }
            val al = sl.maxByOrNull { it.value }?.takeIf { it.value * 2 >= n }
            val end = h.ts[e] + (MINUTES_PER_PLAY * 60).toLong()
            sessions.put(
                JSONObject().put("from", h.ts[s0]).put("to", end).put("count", n).put("color", c[a.key] ?: 0)
                    .put("artist", h.artistNames[a.key]).put("artistShare", a.value.toDouble() / n)
                    .put("album", al?.let { h.albumTitles[it.key] } ?: "").put("albumArtist", al?.let { h.artistNames[h.albumArtist[it.key]] } ?: "")
                    .put("vinyl", vinyl.count { it.ts in h.ts[s0] - 60..end }),
            )
            s0 = e + 1
        }
        o.put("sessions", sessions).put("vinyl", vinyl.count { it.ts in r.from until r.to })
        if (i1 > i0) o.put("first", playJson(i0)).put("latest", playJson(i1 - 1))
        // What repeated: songs, then the artists that carried the day.
        val tp = HashMap<Int, Int>()
        val lastOf = HashMap<Int, Int>()
        val ap = HashMap<Int, Int>()
        for (i in i0 until i1) {
            tp.merge(h.track[i], 1, Int::plus)
            lastOf[h.track[i]] = i
            ap.merge(h.artist[i], 1, Int::plus)
        }
        o.put("repeats", JSONArray(tp.entries.filter { it.value >= 2 }.sortedByDescending { it.value }.take(5).map { (t, k) -> playJson(lastOf.getValue(t)).put("plays", k) }))
        o.put("topArtists", JSONArray(ap.entries.sortedByDescending { it.value }.take(3).map { (a, k) -> JSONObject().put("name", h.artistNames[a]).put("plays", k).put("photo", artistPhoto(h.artistNames[a])) }))
        // New today: artists and songs heard for the first time ever.
        o.put("newArtists", JSONArray(ap.keys.filter { firstTs[it] in r.from until r.to }.sortedByDescending { ap[it] }.take(8).map { h.artistNames[it] }))
            .put("newSongs", tp.keys.count { trackFirst[it] in r.from until r.to })
        // The same date in earlier years.
        val d = date(r.from)
        val years = JSONArray()
        var y = d.year - 1
        val firstYear = date(historyStart).year
        while (y >= firstYear && years.length() < 4) {
            if (!(d.monthValue == 2 && d.dayOfMonth == 29 && !java.time.Year.isLeap(y.toLong()))) {
                val dd = d.withYear(y)
                val j0 = h.lowerBound(dd.atStartOfDay(zone).toEpochSecond())
                val j1 = h.lowerBound(dd.plusDays(1).atStartOfDay(zone).toEpochSecond())
                if (j1 > j0) {
                    val la = HashMap<Int, Int>()
                    val aa = HashMap<Int, Int>()
                    for (i in j0 until j1) {
                        if (h.album[i] >= 0) la.merge(h.album[i], 1, Int::plus)
                        aa.merge(h.artist[i], 1, Int::plus)
                    }
                    val al = la.maxByOrNull { it.value }
                    val ar = aa.maxBy { it.value }
                    years.put(
                        JSONObject().put("year", y).put("id", "d$dd").put("plays", j1 - j0).put("artist", h.artistNames[ar.key])
                            .put("album", al?.let { h.albumTitles[it.key] } ?: "").put("albumArtist", al?.let { h.artistNames[h.albumArtist[it.key]] } ?: "")
                            .put("albumPlays", al?.value ?: 0).put("cover", al?.let { cover(it.key) } ?: ""),
                    )
                }
            }
            y--
        }
        o.put("onThisDay", years)
        // The usual for this weekday, up to the same time of day.
        val upto = minOf(r.to, nowSec + 1) - r.from
        var weeks = 0
        var plays = 0
        for (k in 1..8) {
            val a = d.minusWeeks(k.toLong()).atStartOfDay(zone).toEpochSecond()
            if (a + DAY_SEC <= historyStart) break
            plays += h.lowerBound(a + upto) - h.lowerBound(a)
            weeks++
        }
        if (weeks > 0) o.put("usual", JSONObject().put("plays", Math.round(plays * 10.0 / weeks) / 10.0).put("weeks", weeks))
        return o
    }

    /** The days of week [r]: plays per day, the same days a week earlier, and each day's plays on a 24-hour strip. */
    fun weekDays(r: Range): JSONObject {
        val mon = date(r.from)
        val c = colors(h.lowerBound(r.from), h.lowerBound(r.to))
        val days = JSONArray()
        val last = JSONArray()
        val strips = JSONArray()
        for (k in 0..6) {
            val a = mon.plusDays(k.toLong()).atStartOfDay(zone).toEpochSecond()
            val b = mon.plusDays(k + 1L).atStartOfDay(zone).toEpochSecond()
            val la = mon.plusDays(k - 7L).atStartOfDay(zone).toEpochSecond()
            val lb = mon.plusDays(k - 6L).atStartOfDay(zone).toEpochSecond()
            val j0 = h.lowerBound(a)
            val j1 = h.lowerBound(b)
            days.put(if (a > nowSec) JSONObject.NULL else j1 - j0)
            last.put(h.lowerBound(lb) - h.lowerBound(la))
            strips.put(strip(a, j0, j1, c))
        }
        return JSONObject().put("days", days).put("last", last).put("strips", strips)
            .put("artists", JSONArray(c.entries.sortedBy { it.value }.map { h.artistNames[it.key] }))
            .put("today", if (nowSec in r.from until r.to) date(nowSec).toEpochDay() - mon.toEpochDay() else -1)
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
        // Who they are: the period's new artists, the ones you kept playing first, with how you found them.
        val (_, from) = origins
        val here = IntArray(h.artistNames.size)
        for (i in i0 until i1) here[h.artist[i]]++
        // Not the ones from the first weeks of the history: everything is "new" there.
        val fresh = firstTs.indices.filter { firstTs[it] in r.from until r.to && firstTs[it] >= historyStart + Atlas.START_GRACE }
        val found = fresh.sortedWith(compareByDescending<Int> { plays[it] }.thenBy { firstTs[it] }).take(60).map { a ->
            JSONObject().put("name", h.artistNames[a]).put("first", firstTs[a] * 1000).put("plays", plays[a]).put("here", here[a])
                .put("from", if (from[a] >= 0) h.artistNames[from[a]] else "").put("photo", artistPhoto(h.artistNames[a]))
        }
        return JSONObject().put("unit", b.unit.id).put("buckets", buckets)
            .put("found", JSONArray(found)).put("count", fresh.size).put("kept", fresh.count { plays[it] >= KEPT_PLAYS })
    }

    /**
     * Habits: for the period's most played artists, the bins (weeks over a year or more, days over
     * a month) they were played in and how often, and how much of their plays their busiest month
     * holds. A steady habit shows up most weeks; a wave crowds into one month. Rows in the record's
     * order, so the first [TOP_INKS] keep their colors.
     */
    fun habits(r: Range, i0: Int, i1: Int, max: Int = 16): JSONObject {
        val out = JSONObject().put("rows", JSONArray())
        if (i1 <= i0) return out
        val b = binsFor(r, 160)
        val top = artistOrder(i0, i1).take(max)
        val slot = HashMap<Int, Int>().also { s -> top.forEachIndexed { k, a -> s[a] = k } }
        val counts = Array(top.size) { IntArray(b.count) }
        val months = Array(top.size) { HashMap<Int, Int>() }
        val any = BooleanArray(b.count)
        for (i in i0 until i1) {
            val k = b.index(date(h.ts[i])).coerceIn(0, b.count - 1)
            any[k] = true
            val s = slot[h.artist[i]] ?: continue
            counts[s][k]++
            months[s].merge(monthKey(h.ts[i]), 1, Int::plus)
        }
        val rows = top.indices.map { s ->
            val total = counts[s].sum()
            val peak = months[s].maxBy { it.value }
            JSONObject().put("name", h.artistNames[top[s]]).put("ink", if (s < TOP_INKS) s else -1)
                .put("counts", JSONArray(counts[s].toList())).put("present", counts[s].count { it > 0 })
                .put("peakShare", peak.value.toDouble() / total).put("peakMonth", monthStart(peak.key)).put("plays", total)
        }.sortedByDescending { it.getInt("present") }
        return out.put("unit", b.unit.id).put("starts", b.startsJson()).put("rows", JSONArray(rows))
            .put("active", any.count { it }).put("empty", JSONArray(any.indices.filter { !any[it] }))
    }

    // ---- genres ----

    private val genreIds = HashMap<String, Int>()
    private val genreKeys = ArrayList<String>()

    private val subIds = HashMap<String, Int>()
    private val subKeys = ArrayList<String>()

    /**
     * Primary genre family per scrobble (-1: unknown), where it came from, and its subgenre in that
     * family (-1: none told), worked out once per album/artist.
     */
    private val scrobbleGenre: Triple<IntArray, ByteArray, IntArray> by lazy {
        val albumG = IntArray(h.albumTitles.size) { -2 }
        val albumS = ByteArray(h.albumTitles.size)
        val albumSub = IntArray(h.albumTitles.size)
        val artistG = IntArray(h.artistNames.size) { -2 }
        val artistS = ByteArray(h.artistNames.size)
        val artistSub = IntArray(h.artistNames.size)
        fun id(d: Genres.Detail?) = d?.family?.let { k -> genreIds.getOrPut(k) { genreKeys += k; genreKeys.size - 1 } } ?: -1
        fun subId(d: Genres.Detail?) = d?.takeIf { it.style.isNotEmpty() }?.let { "${it.family}:${it.style}" }
            ?.let { k -> subIds.getOrPut(k) { subKeys += k; subKeys.size - 1 } } ?: -1
        val g = IntArray(h.size)
        val src = ByteArray(h.size)
        val sub = IntArray(h.size)
        for (i in 0 until h.size) {
            val al = h.album[i]
            if (al >= 0) {
                if (albumG[al] == -2) {
                    val d = genres.detail(h.artistNames[h.albumArtist[al]], h.albumKeys[al])
                    albumG[al] = id(d)
                    albumS[al] = (d?.source ?: Genres.Source.NONE).ordinal.toByte()
                    albumSub[al] = subId(d)
                }
                g[i] = albumG[al]
                src[i] = albumS[al]
                sub[i] = albumSub[al]
            } else {
                val a = h.artist[i]
                if (artistG[a] == -2) {
                    val d = genres.detail(h.artistNames[a], null)
                    artistG[a] = id(d)
                    artistS[a] = (d?.source ?: Genres.Source.NONE).ordinal.toByte()
                    artistSub[a] = subId(d)
                }
                g[i] = artistG[a]
                src[i] = artistS[a]
                sub[i] = artistSub[a]
            }
        }
        Triple(g, src, sub)
    }

    /** All-time plays per family and per subgenre (by [genreKeys] and [subKeys]). */
    private val genreTotals: Pair<IntArray, IntArray> by lazy {
        val (g, _, sub) = scrobbleGenre
        val fam = IntArray(genreKeys.size)
        val subs = IntArray(subKeys.size)
        for (i in g.indices) {
            if (g[i] >= 0) fam[g[i]]++
            if (sub[i] >= 0) subs[sub[i]]++
        }
        fam to subs
    }

    /** A family's subgenres with plays, most played first. */
    private fun subsOf(family: String, max: Int): List<Int> {
        val subs = genreTotals.second
        return subKeys.indices.filter { subs[it] > 0 && subKeys[it].startsWith("$family:") }.sortedByDescending { subs[it] }.take(max)
    }

    private fun subJson(s: Int) = JSONObject().put("key", subKeys[s]).put("label", genres.labelOf(subKeys[s])).put("plays", genreTotals.second[s])

    /** The genres that get their own color: the biggest over all time, so colors don't change with the period. */
    private val topGenres: List<Int> by lazy {
        val (g, _) = scrobbleGenre
        val totals = IntArray(genreKeys.size)
        for (x in g) if (x >= 0) totals[x]++
        totals.indices.filter { totals[it] > 0 }.sortedByDescending { totals[it] }.take(TOP_GENRES)
    }

    /** The biggest genres over all time (by each scrobble's main genre), each with its biggest subgenres, to filter and browse by. */
    fun genreOptions(max: Int = 24, subsMax: Int = 16): JSONArray {
        val totals = genreTotals.first
        return JSONArray(
            totals.indices.filter { totals[it] > 0 }.sortedByDescending { totals[it] }.take(max)
                .map { f ->
                    JSONObject().put("key", genreKeys[f]).put("label", Genres.label(genreKeys[f])).put("plays", totals[f])
                        .put("subs", JSONArray(subsOf(genreKeys[f], subsMax).map { subJson(it) }))
                },
        )
    }

    // ---- decades: when the music you played came out ----

    /** Release year per album (0: unknown): Deezer's earliest edition or the record on your shelf, whichever is earlier. */
    private val albumYear: IntArray by lazy {
        IntArray(h.albumTitles.size) { al -> Years.earliest(albumYears[h.albumKeys[al]] ?: 0, owned(al)?.year ?: 0) }
    }

    private fun yearOf(i: Int) = h.album[i].let { if (it < 0) 0 else albumYear[it] }

    /** Plays per decade of release between [i0] and [i1], and how many of them have a known year. */
    fun decades(i0: Int, i1: Int): JSONObject {
        val counts = java.util.TreeMap<Int, Int>()
        var known = 0
        for (i in i0 until i1) {
            val y = yearOf(i)
            if (y > 0) {
                counts.merge(y / 10 * 10, 1, Int::plus)
                known++
            }
        }
        return JSONObject().put("known", known).put("total", i1 - i0)
            .put("decades", JSONArray(counts.map { (d, n) -> JSONObject().put("decade", d).put("plays", n) }))
    }

    /** The decades of the whole history, to filter the stats by. */
    fun decadeOptions(): JSONObject = decades(0, h.size)

    /** Whether a scrobble's album came out in the ten years from [decade]. */
    fun inDecade(decade: Int): (Int) -> Boolean = { i -> yearOf(i).let { it >= decade && it < decade + 10 } }

    /**
     * Artists whose albums have no year looked up yet, with those albums (title, key), most played
     * first, until the albums with a year cover [coverage] of all album plays.
     */
    fun albumsNeedingYears(maxArtists: Int = 400, coverage: Double = 0.95): List<Pair<String, List<Pair<String, String>>>> {
        val total = albumPlays.sum().toDouble()
        var covered = albumPlays.indices.filter { h.albumKeys[it] in albumYears }.sumOf { albumPlays[it] }.toDouble()
        val byArtist = HashMap<Int, MutableList<Int>>()
        for (al in albumPlays.indices) if (albumPlays[al] > 0 && h.albumKeys[al] !in albumYears) byArtist.getOrPut(h.albumArtist[al]) { ArrayList() } += al
        val out = ArrayList<Pair<String, List<Pair<String, String>>>>()
        for ((a, als) in byArtist.entries.sortedByDescending { e -> e.value.sumOf { albumPlays[it] } }) {
            if (covered >= coverage * total || out.size >= maxArtists) break
            covered += als.sumOf { albumPlays[it] }
            out += h.artistNames[a] to als.map { h.albumTitles[it] to h.albumKeys[it] }
        }
        return out
    }

    /**
     * One genre's page (the plays whose main genre is [key]): its life in plays, its top artists,
     * albums and songs, its artists and albums gone quiet, and its decades; null if none.
     */
    fun genrePage(asked: String): JSONObject? {
        val key = Genres.resolve(asked)
        val sub = h.filter(inGenre(key))
        val family = key.substringBefore(':')
        // A style that is no play's main one: its family's page instead.
        if (sub.size == 0) return if (':' in key) genrePage(family) else null
        val s = Stats(sub, zone, nowSec, collection, genres, vinyl, albumImages, albumYears, globals)
        val o = s.life { true } ?: return null
        val ex = s.explore()
        val (famTotals, subTotals) = genreTotals
        val fid = genreIds[family] ?: -1
        val famPlays = if (fid >= 0) famTotals[fid] else 0
        // Its place among the families, or among all subgenres.
        val rank = if (':' in key) subIds[key]?.let { id -> subTotals.count { it > subTotals[id] } + 1 } ?: 0
        else if (fid >= 0) famTotals.count { it > famPlays } + 1 else 0
        val inside = subsOf(family, 40)
        val per = IntArray(sub.artistNames.size)
        for (i in 0 until sub.size) per[sub.artist[i]]++
        val all = per.indices.sortedByDescending { per[it] }.take(GENRE_ALL_MAX).map { JSONObject().put("name", sub.artistNames[it]).put("plays", per[it]) }
        // Artists mostly filed elsewhere who are partly this family too (Surprise Chef: Soul & Funk, and Jazz).
        val also = if (':' in key || fid < 0) emptyList() else {
            val plays = IntArray(h.artistNames.size).also { p -> for (a in h.artist) p[a]++ }
            plays.indices.filter { a -> plays[a] > 0 && artistGenre[a] != fid && family in genres.of(h.artistNames[a], null).first }
                .sortedByDescending { plays[it] }.take(GENRE_ALSO_MAX).map { JSONObject().put("name", h.artistNames[it]).put("plays", plays[it]) }
        }
        return o.put("key", key).put("kind", if (':' in key) "sub" else "family").put("all", JSONArray(all)).put("label", genres.labelOf(key))
            .put("also", JSONArray(also))
            .put("share", sub.size.toDouble() / maxOf(1, h.size)).put("ofFamily", sub.size.toDouble() / maxOf(1, famPlays))
            .put("family", JSONObject().put("key", family).put("label", Genres.label(family)).put("plays", famPlays).put("rank", if (fid >= 0) famTotals.count { it > famPlays } + 1 else 0))
            .put("inside", JSONArray(inside.map { subJson(it) }))
            .put("general", maxOf(0, famPlays - subKeys.indices.filter { subKeys[it].startsWith("$family:") }.sumOf { subTotals[it] }))
            .put("shelf", JSONArray(collection.filter { genres.owns(it, key) }.map { it.releaseId }.distinct()))
            .put("artists", sub.artistNames.size).put("rank", rank).put("top", s.top(0, sub.size, TOP_MAX))
            .put("lost", ex.getJSONArray("lostArtists")).put("lostAlbums", ex.getJSONArray("lostAlbums"))
            .put("decades", s.decades(0, sub.size))
    }

    /** The name of a genre or style key from anywhere ("indie", "indie:shoegaze", "Post-Punk"). */
    fun genreLabel(key: String) = genres.labelOf(Genres.resolve(key))

    /** Whether you ever played this song. */
    fun played(artist: String, title: String) = findTrack(artist, title) >= 0

    /**
     * The pools for a genre's playlist: your songs in it, most played first (a play in the last
     * year counts one and a half), and songs by other artists you often played right next to the
     * top of them (twice at least, within 20 minutes).
     */
    fun genrePicks(key: String, max: Int = 200): Pair<List<Pick>, List<Pick>> {
        val inG = inGenre(key)
        val recent = nowSec - 365 * DAY_SEC
        val score = HashMap<Int, Double>()
        for (i in 0 until h.size) if (inG(i)) score.merge(h.track[i], if (h.ts[i] >= recent) 1.5 else 1.0, Double::plus)
        val core = score.entries.sortedWith(compareByDescending<Map.Entry<Int, Double>> { it.value }.thenBy { it.key }).map { it.key }.take(max)
        val coreSet = core.toHashSet()
        val top = core.take(60).toHashSet()
        val near = HashMap<Int, Int>()
        for (i in 0 until h.size) {
            if (h.track[i] !in top) continue
            for (j in intArrayOf(i - 1, i + 1)) {
                if (j < 0 || j >= h.size || Math.abs(h.ts[j] - h.ts[i]) > 1200) continue
                val t = h.track[j]
                if (t in coreSet || h.trackArtist[t] == h.trackArtist[h.track[i]]) continue
                near.merge(t, 1, Int::plus)
            }
        }
        fun pick(t: Int, source: String) = Pick(h.artistNames[h.trackArtist[t]], h.trackTitles[t], trackAlbum[t].let { if (it >= 0) h.albumTitles[it] else "" }, trackPlays[t], source)
        return core.map { pick(it, "core") } to
            near.entries.filter { it.value >= 2 }.sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key }).take(max).map { pick(it.key, "near") }
    }

    /** Every genre family's share of all your plays, with its biggest subgenres: the map on the Explore tab. */
    fun genreMap(): JSONObject {
        val known = genreTotals.first.sum()
        return JSONObject().put("all", h.size).put("known", known).put("families", genreOptions(30, 6))
    }

    // ---- the musical atlas: universe, family tree, landscape (all time) ----

    /** Each artist's first scrobble (index), and what came right before it: an artist, -1 (nothing in that session) or -2 (around from the start). */
    private val origins: Pair<IntArray, IntArray> by lazy {
        val first = IntArray(h.artistNames.size) { -1 }
        for (i in 0 until h.size) if (first[h.artist[i]] < 0) first[h.artist[i]] = i
        val from = IntArray(h.artistNames.size) { a ->
            val f = first[a]
            when {
                f < 0 -> -1
                h.ts[f] < h.ts[0] + Atlas.START_GRACE -> -2
                f > 0 && h.ts[f] - h.ts[f - 1] <= Atlas.SESSION_GAP && h.artist[f - 1] != a -> h.artist[f - 1]
                else -> -1
            }
        }
        first to from
    }

    /** The genre most of an artist's plays are filed under (an index into the genre keys; -1 if none). */
    private val artistGenre: IntArray by lazy {
        val (g, _) = scrobbleGenre
        val counts = HashMap<Long, Int>()
        for (i in 0 until h.size) if (g[i] >= 0) counts.merge(Atlas.pair(h.artist[i], g[i]), 1, Int::plus)
        val best = IntArray(h.artistNames.size) { -1 }
        val bestN = IntArray(h.artistNames.size)
        for ((k, c) in counts) {
            val a = Atlas.first(k)
            if (c > bestN[a]) {
                bestN[a] = c
                best[a] = Atlas.second(k)
            }
        }
        best
    }

    private fun genreOfArtist(a: Int) = artistGenre[a].let { if (it >= 0) Genres.label(genreKeys[it]) else "" }

    /** How you found artist [a]: "after" (with [from]), "start" (around from the start) or "blue" (nothing right before it). */
    private fun originJson(a: Int): JSONObject {
        val (first, from) = origins
        val o = JSONObject().put("how", when { from[a] >= 0 -> "after"; from[a] == -2 -> "start"; else -> "blue" })
        if (first[a] >= 0) o.put("at", h.ts[first[a]] * 1000)
        if (from[a] >= 0) o.put("from", h.artistNames[from[a]])
        return o
    }

    /** The atlas, worked out once per history: see [Atlas]. */
    fun atlas(): JSONObject = atlasJson

    private val atlasJson: JSONObject by lazy {
        val o = JSONObject().put("plays", h.size)
        if (h.size == 0) return@lazy o.put("empty", true)
        val l = artistLife
        val trans = Atlas.transitions(h)
        val m0 = monthKey(h.ts[0])
        // To the last play, should one be dated after now (a phone clock set wrong once).
        val nm = maxOf(monthKey(nowSec), monthKey(h.ts[h.size - 1])) - m0 + 1
        // The universe
        val stars = h.artistNames.indices.filter { l.plays[it] >= Atlas.STAR_MIN }.sortedByDescending { l.plays[it] }.take(Atlas.STARS).toIntArray()
        val index = HashMap<Int, Int>()
        stars.forEachIndexed { i, a -> index[a] = i }
        val monthly = Array(stars.size) { DoubleArray(nm) }
        val years = Array(stars.size) { java.util.TreeMap<Int, Int>() }
        for (i in 0 until h.size) {
            val s = index[h.artist[i]] ?: continue
            monthly[s][monthKey(h.ts[i]) - m0] += 1.0
            years[s].merge(date(h.ts[i]).year, 1, Int::plus)
        }
        val plays = IntArray(stars.size) { l.plays[stars[it]] }
        val links = Atlas.links(stars, plays, trans, monthly)
        val pos = Atlas.layout(stars.size, links)
        val cl = Atlas.clusters(stars.size, links, plays)
        // What you usually play right before and right after each star.
        val before = IntArray(stars.size) { -1 }
        val beforeN = IntArray(stars.size)
        val after = IntArray(stars.size) { -1 }
        val afterN = IntArray(stars.size)
        for ((k, c) in trans) {
            if (c < 2) continue
            index[Atlas.second(k)]?.let { s -> if (c > beforeN[s]) { beforeN[s] = c; before[s] = Atlas.first(k) } }
            index[Atlas.first(k)]?.let { s -> if (c > afterN[s]) { afterN[s] = c; after[s] = Atlas.second(k) } }
        }
        fun round(v: Double) = Math.round(v * 1000) / 1000.0
        fun near(a: Int, n: Int) = if (a >= 0) JSONObject().put("name", h.artistNames[a]).put("n", n) else JSONObject.NULL
        val nodes = JSONArray(stars.indices.map { i ->
            val a = stars[i]
            JSONObject().put("name", h.artistNames[a]).put("plays", plays[i]).put("x", round(pos[i][0])).put("y", round(pos[i][1])).put("c", cl[i])
                .put("genre", genreOfArtist(a)).put("photo", artistPhoto(h.artistNames[a])).put("origin", originJson(a))
                .put("years", JSONObject(years[i].mapKeys { it.key.toString() } as Map<*, *>))
                .put("before", near(before[i], beforeN[i])).put("after", near(after[i], afterN[i]))
        })
        val nc = (cl.maxOrNull() ?: -1) + 1
        val clusters = JSONArray((0 until nc).map { c ->
            val members = stars.indices.filter { cl[it] == c }
            val byGenre = HashMap<String, Int>()
            members.forEach { i -> genreOfArtist(stars[i]).takeIf { it.isNotEmpty() }?.let { byGenre.merge(it, plays[i], Int::plus) } }
            JSONObject().put("name", h.artistNames[stars[members[0]]]).put("genre", byGenre.maxByOrNull { it.value }?.key ?: "")
                .put("plays", members.sumOf { plays[it] }).put("size", members.size).put("top", JSONArray(members.take(5).map { h.artistNames[stars[it]] }))
        })
        // Bridges: stars strongly linked both into their own constellation and into another one.
        val pull = Array(stars.size) { HashMap<Int, Double>() }
        for (lk in links) {
            if (cl[lk.b] >= 0) pull[lk.a].merge(cl[lk.b], lk.w, Double::plus)
            if (cl[lk.a] >= 0) pull[lk.b].merge(cl[lk.a], lk.w, Double::plus)
        }
        val bridges = stars.indices.filter { cl[it] >= 0 }.mapNotNull { i ->
            val own = pull[i][cl[i]] ?: 0.0
            val other = pull[i].filterKeys { it != cl[i] }.maxByOrNull { it.value } ?: return@mapNotNull null
            Triple(i, other.key, minOf(own, other.value))
        }.filter { it.third > 0 }.sortedByDescending { it.third }.take(4)
        o.put("universe", JSONObject().put("nodes", nodes).put("clusters", clusters)
            .put("links", JSONArray(links.map { JSONArray().put(it.a).put(it.b).put(it.together).put(round(it.sameTime)).put(round(it.w)) }))
            .put("bridges", JSONArray(bridges.map { (i, c2, _) -> JSONObject().put("name", h.artistNames[stars[i]]).put("from", cl[i]).put("to", c2) }))
            .put("plays", plays.sum()))
        // The family tree: the artists you kept playing, and everyone on the way to them.
        val (first, from) = origins
        val kept = BooleanArray(h.artistNames.size) { l.plays[it] >= Atlas.KEPT_MIN }
        val inTree = kept.copyOf()
        for (a in kept.indices) if (kept[a]) {
            var p = from[a]
            while (p >= 0 && !inTree[p]) {
                inTree[p] = true
                p = from[p]
            }
        }
        // Latest first, so each artist's count is complete before it's added to the one before it.
        val desc = IntArray(h.artistNames.size)
        for (a in h.artistNames.indices.filter { inTree[it] && first[it] >= 0 }.sortedByDescending { first[it] }) {
            val p = from[a]
            if (p >= 0) desc[p] += desc[a] + (if (kept[a]) 1 else 0)
        }
        val treeIds = h.artistNames.indices.filter { inTree[it] && first[it] >= 0 }
        o.put("tree", JSONObject()
            .put("nodes", JSONArray(treeIds.map { a ->
                JSONObject().put("id", a).put("name", h.artistNames[a]).put("plays", l.plays[a]).put("at", h.ts[first[a]] * 1000).put("p", from[a]).put("d", desc[a]).put("kept", kept[a])
            }))
            .put("gateways", JSONArray(treeIds.filter { desc[it] >= 2 }.sortedByDescending { desc[it] }.take(8).map { a ->
                JSONObject().put("id", a).put("name", h.artistNames[a]).put("d", desc[a]).put("photo", artistPhoto(h.artistNames[a]))
            }))
            .put("after", kept.indices.count { kept[it] && from[it] >= 0 }).put("blue", kept.indices.count { kept[it] && from[it] == -1 })
            .put("start", kept.indices.count { kept[it] && from[it] == -2 }).put("kept", kept.count { it }))
        // The landscape: your most played artists' plays per month.
        val tops = h.artistNames.indices.filter { l.plays[it] > 0 }.sortedByDescending { l.plays[it] }.take(Atlas.MOUNTAINS)
        val topIndex = HashMap<Int, Int>()
        tops.forEachIndexed { i, a -> topIndex[a] = i }
        val counts = Array(tops.size) { IntArray(nm) }
        for (i in 0 until h.size) topIndex[h.artist[i]]?.let { counts[it][monthKey(h.ts[i]) - m0]++ }
        val series = tops.indices.map { i ->
            val c = counts[i]
            val peak = c.indices.maxByOrNull { c[it] } ?: 0
            val s = Atlas.shape(c)
            JSONObject().put("name", h.artistNames[tops[i]]).put("plays", l.plays[tops[i]]).put("counts", JSONArray(c.toList()))
                .put("peak", c[peak]).put("peakAt", monthStart(m0 + peak)).put("kind", s.kind).put("months", s.months)
                .put("genre", genreOfArtist(tops[i])).put("photo", artistPhoto(h.artistNames[tops[i]]))
                .put("from", monthStart(m0 + (c.indexOfFirst { it > 0 }.takeIf { it >= 0 } ?: 0)))
                .put("_peak", peak)
        }.sortedBy { it.getInt("_peak") }.onEach { it.remove("_peak") }
        o.put("landscape", JSONObject().put("monthsFrom", monthStart(m0)).put("months", nm).put("series", JSONArray(series)))
    }

    // ---- you against everyone on Last.fm (all time) ----

    /** You against everyone: your all-time plays [mine] next to Last.fm's counts [g]. */
    private fun vsJson(mine: Int, g: Global): JSONObject = JSONObject().put("mine", mine).put("listeners", g.listeners).put("plays", g.plays)
        .put("avg", g.perListener).put("ratio", mine / maxOf(1.0, g.perListener)).put("share", mine.toDouble() / maxOf(1L, g.plays))

    /**
     * What to look up on Last.fm for the comparisons, most played first: your top artists and
     * songs, and the most played songs worldwide of your very top artists. Fresh ones are skipped.
     */
    fun globalsNeeded(nowMs: Long): List<GlobalAsk> {
        val l = artistLife
        val artists = h.artistNames.indices.filter { l.plays[it] >= VS_ARTIST_MIN }.sortedByDescending { l.plays[it] }.take(VS_ARTISTS)
        val tracks = trackPlays.indices.filter { trackPlays[it] >= VS_TRACK_MIN }.sortedByDescending { trackPlays[it] }.take(VS_TRACKS)
        val out = ArrayList<GlobalAsk>()
        for (a in artists) if (!globals.fresh(Globals.artistKey(h.artistNames[a]), nowMs)) out += GlobalAsk(GlobalAsk.Kind.ARTIST, h.artistNames[a])
        for (a in artists.take(VS_TOPS)) if (!globals.freshTop(h.artistNames[a], nowMs)) out += GlobalAsk(GlobalAsk.Kind.TOP, h.artistNames[a])
        for (t in tracks) {
            val a = h.artistNames[h.trackArtist[t]]
            if (!globals.fresh(Globals.trackKey(a, h.trackTitles[t]), nowMs)) out += GlobalAsk(GlobalAsk.Kind.TRACK, a, h.trackTitles[t])
        }
        return out
    }

    /** Your plays per song of artist [a], spellings ("(Remastered)") added up, keyed by [Text.normLoose]; with the most played spelling. */
    private fun songsOf(a: Int): Map<String, Pair<Int, Int>> {
        val m = HashMap<String, Pair<Int, Int>>()
        for (t in trackPlays.indices) {
            if (h.trackArtist[t] != a || trackPlays[t] == 0) continue
            val k = Text.normLoose(h.trackTitles[t])
            val prev = m[k]
            m[k] = if (prev == null) t to trackPlays[t] else (if (trackPlays[t] > trackPlays[prev.first]) t else prev.first) to prev.second + trackPlays[t]
        }
        return m
    }

    /** Your most played song by artist [a] against everyone's most played, and where yours ranks worldwide (0: not in their top 50). */
    private fun taste(a: Int): JSONObject? {
        val name = h.artistNames[a]
        val top = globals.top(name) ?: return null
        val mine = songsOf(a)
        val fav = mine.values.maxByOrNull { it.second } ?: return null
        val favKey = Text.normLoose(h.trackTitles[fav.first])
        val rank = top.indexOfFirst { Text.normLoose(it.first) == favKey } + 1
        return JSONObject().put("artist", name).put("photo", artistPhoto(name))
            .put("mine", JSONObject().put("title", h.trackTitles[fav.first]).put("plays", fav.second).put("rank", rank))
            .put("theirs", JSONObject().put("title", top[0].first).put("plays", top[0].second).put("mine", mine[Text.normLoose(top[0].first)]?.second ?: 0))
            .put("heard", top.take(10).count { (mine[Text.normLoose(it.first)]?.second ?: 0) > 0 }).put("of", minOf(10, top.size))
    }

    /**
     * You against everyone on Last.fm, all time: how mainstream your listening is (plays by how
     * many people listen to the artist), artists and songs you play far more than their average
     * listener, your share of an artist's plays worldwide, favorites few people know, and your
     * favorite song by each top artist against everyone's.
     */
    fun vs(): JSONObject {
        val l = artistLife
        val known = h.artistNames.indices.filter { l.plays[it] > 0 }.mapNotNull { a -> globals.artist(h.artistNames[a])?.let { a to it } }
        val knownPlays = known.sumOf { l.plays[it.first] }
        val o = JSONObject().put("plays", h.size).put("knownPlays", knownPlays).put("knownArtists", known.size).put("artists", l.plays.count { it > 0 })
        if (known.isEmpty()) return o
        val tierPlays = IntArray(VS_TIERS.size)
        val tierArtists = IntArray(VS_TIERS.size)
        val tierTop = IntArray(VS_TIERS.size) { -1 }
        for ((a, g) in known) {
            val k = VS_TIERS.indexOfFirst { g.listeners >= it }
            tierPlays[k] += l.plays[a]
            tierArtists[k]++
            if (tierTop[k] < 0 || l.plays[a] > l.plays[tierTop[k]]) tierTop[k] = a
        }
        o.put("tiers", JSONArray(VS_TIERS.indices.map { k ->
            JSONObject().put("min", VS_TIERS[k]).put("plays", tierPlays[k]).put("artists", tierArtists[k]).put("top", if (tierTop[k] >= 0) h.artistNames[tierTop[k]] else "")
        }))
        // Half of your plays go to artists with fewer listeners than this.
        var acc = 0
        o.put("median", known.sortedBy { it.second.listeners }.first { acc += l.plays[it.first]; acc * 2 >= knownPlays }.second.listeners)
        fun artistVs(a: Int, g: Global) = vsJson(l.plays[a], g).put("name", h.artistNames[a]).put("photo", artistPhoto(h.artistNames[a]))
        val fans = known.filter { l.plays[it.first] >= FAN_MIN }
        o.put("fans", JSONArray(fans.sortedByDescending { l.plays[it.first] / it.second.perListener }.take(VS_LIST).map { artistVs(it.first, it.second) }))
        o.put("shares", JSONArray(fans.sortedByDescending { l.plays[it.first].toDouble() / it.second.plays }.take(5).map { artistVs(it.first, it.second) }))
        o.put("gems", JSONArray(known.filter { l.plays[it.first] >= GEM_MIN }.sortedBy { it.second.listeners }.take(8).map { artistVs(it.first, it.second) }))
        val songs = trackPlays.indices.filter { trackPlays[it] >= VS_TRACK_MIN }
            .mapNotNull { t -> globals.track(h.artistNames[h.trackArtist[t]], h.trackTitles[t])?.let { t to it } }
        o.put("songs", JSONArray(songs.sortedByDescending { trackPlays[it.first] / it.second.perListener }.take(VS_LIST).map { (t, g) -> vsJson(trackPlays[t], g).put("title", h.trackTitles[t]).put("artist", h.artistNames[h.trackArtist[t]]) }))
        o.put("taste", JSONArray(known.sortedByDescending { l.plays[it.first] }.take(VS_TOPS).mapNotNull { taste(it.first) }.take(8)))
        return o
    }

    /** Artists in the comparisons without a photo yet. */
    fun vsNeedingPhotos(vs: JSONObject): List<String> = listOf("fans", "shares", "gems", "taste").flatMap { k ->
        val a = vs.optJSONArray(k) ?: JSONArray()
        (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("photo").isEmpty() }.map { it.optString("name").ifEmpty { it.optString("artist") } }
    }.distinct().filter { it.isNotEmpty() && photoKey(it) !in albumImages }

    /** Whether a scrobble's main genre is [key], the same genre the shares and the drift count it under. */
    fun inGenre(key: String): (Int) -> Boolean {
        val (g, _, sub) = scrobbleGenre
        val k = Genres.resolve(key)
        if (':' in k) {
            val id = subIds[k] ?: return { false }
            return { sub[it] == id }
        }
        val id = genreIds[k] ?: return { false }
        return { g[it] == id }
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

    /** Artists whose Last.fm tags the genres still need ([have] has them): the most played ones, Discogs or not (both are weighed). */
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
            if (Genres.artistKey(name) !in have) out += name
        }
        return out
    }

    /**
     * Albums whose own Last.fm tags the genres still need ([have] has them, by [Text.albumKey]): the most played,
     * as (artist, title, key). Songs without an album and "Various Artists" compilations need none.
     */
    fun albumsNeedingTags(have: Set<String>, max: Int = 300, coverage: Double = 0.8): List<Triple<String, String, String>> {
        val total = albumPlays.sum()
        val out = ArrayList<Triple<String, String, String>>()
        var covered = 0L
        for (al in albumPlays.indices.sortedByDescending { albumPlays[it] }) {
            if (covered >= coverage * total || out.size >= max || albumPlays[al] == 0) break
            covered += albumPlays[al]
            val artist = h.artistNames[h.albumArtist[al]]
            if (h.albumTitles[al].isBlank() || Text.norm(artist).startsWith("various")) continue
            if (h.albumKeys[al] !in have) out += Triple(artist, h.albumTitles[al], h.albumKeys[al])
        }
        return out
    }

    /** Your plays of each song by [artist], keyed by [Text.normLoose] of its title (versions added up). */
    fun songPlays(artist: String): Map<String, Int> {
        val a = artistIndex[Text.norm(artist)] ?: artistIndex[Text.norm(Artists.main(artist, artistIndex.keys))] ?: return emptyMap()
        return songsOf(a).mapValues { it.value.second }
    }

    /**
     * The play under way at [ts] (s): the last one that started at most [PLAYING_SEC] before, and true. If none,
     * the nearest within [NEAR_SEC], and false. Null if nothing was played around then.
     */
    private fun playNear(ts: Long): Pair<Int, Boolean>? {
        val i = h.lowerBound(ts + 1) - 1
        if (i >= 0 && ts - h.ts[i] <= PLAYING_SEC) return i to true
        val near = listOf(i, i + 1).filter { it in 0 until h.size }.minByOrNull { kotlin.math.abs(h.ts[it] - ts) } ?: return null
        return if (kotlin.math.abs(h.ts[near] - ts) <= NEAR_SEC) near to false else null
    }

    /** What was playing at [ts] (s), as [playNear] finds it, with "playing" telling which of the two it is. */
    fun playAt(ts: Long): JSONObject? = playNear(ts)?.let { (i, on) -> playJson(i).put("playing", on) }

    /**
     * The photos of [photos] worth showing in the story of [r], with what was playing when each was taken: of the ones
     * taken while a song played ([mode] "playing"), around music ("near") or at any time ("any"), none of [hidden].
     */
    fun moments(photos: List<Photo>, r: Range, mode: String = "playing", hidden: Set<Long> = emptySet()): JSONArray {
        val fit = photos.filter { p ->
            p.id !in hidden && when (mode) {
                "any" -> true
                "near" -> playNear(p.ts) != null
                else -> playNear(p.ts)?.second == true
            }
        }
        val picked = Moments.pick(fit, r.to - r.from, { t -> playNear(t)?.second == true }, { t -> playNear(t) != null })
        return JSONArray(picked.map { p ->
            JSONObject().put("id", p.id).put("ts", p.ts * 1000).put("w", p.w).put("h", p.h).put("song", playAt(p.ts) ?: JSONObject.NULL)
        })
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
        // Fine enough for the stream to flow (weeks over a year or two).
        val b = binsFor(r, 90)
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

    /**
     * A river for one album or artist, over all its time with you since [first]: plays per bin of
     * its parts ([partOf]: songs or albums, -1 for none), the [TOP_INKS] most played in the
     * record's colors, around the rest of it. Null when there is too little to flow.
     */
    private fun pageRiver(first: Long, match: (Int) -> Boolean, partOf: (Int) -> Int, name: (Int) -> String): JSONObject? {
        val b = binsFor(Range("life", "", first, nowSec + 1, "all"), 60)
        if (b.count < 4) return null
        val i0 = h.lowerBound(first)
        val counts = HashMap<Int, Int>()
        for (i in i0 until h.size) if (match(i)) partOf(i).takeIf { it >= 0 }?.let { counts.merge(it, 1, Int::plus) }
        val order = counts.entries.sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key }).map { it.key }.take(TOP_INKS)
        if (order.size < 2) return null
        val slot = HashMap<Int, Int>().also { s -> order.forEachIndexed { k, p -> s[p] = k } }
        val series = Array(order.size) { IntArray(b.count) }
        val other = IntArray(b.count)
        for (i in i0 until h.size) {
            if (!match(i)) continue
            val k = b.index(date(h.ts[i])).coerceIn(0, b.count - 1)
            val s = slot[partOf(i)]
            if (s != null) series[s][k]++ else other[k]++
        }
        return JSONObject().put("unit", b.unit.id).put("starts", b.startsJson())
            .put("artists", JSONArray(order.map(name)))
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
            val al = findAlbum(c.artist, c.title).takeIf { it >= 0 }
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

    /**
     * One badge per record on your shelf (by release id), the strongest that fits: your heavy
     * rotation (the most played of the last two weeks), a new arrival (added this month), a lost
     * favorite (one of your most played, quiet for half a year), worn in (your three most played)
     * or still sealed (never played).
     */
    fun shelfBadges(): JSONObject {
        val plays = IntArray(h.albumTitles.size)
        val recent = IntArray(h.albumTitles.size)
        val last = LongArray(h.albumTitles.size)
        val since = nowSec - BADGE_HEAVY_DAYS * DAY_SEC
        for (i in 0 until h.size) {
            val al = h.album[i]
            if (al < 0) continue
            plays[al]++
            last[al] = h.ts[i]
            if (h.ts[i] >= since) recent[al]++
        }
        val records = collection.distinctBy { it.releaseId }.map { it to findAlbum(it.artist, it.title) }
        val albums = records.map { it.second }.filter { it >= 0 }.distinct()
        val heavy = albums.filter { recent[it] >= BADGE_HEAVY_MIN }.maxByOrNull { recent[it] }
        val top = albums.filter { plays[it] >= BADGE_FAV_MIN }.sortedByDescending { plays[it] }
        val lost = top.take(BADGE_LOST_TOP).filter { nowSec - last[it] >= BADGE_LOST_DAYS * DAY_SEC }.toSet()
        val worn = top.take(BADGE_WORN_TOP).filter { it !in lost }
        val newSince = (nowSec - BADGE_NEW_DAYS * DAY_SEC) * 1000
        val out = JSONObject()
        for ((c, al) in records) {
            val b = when {
                al >= 0 && al == heavy -> JSONObject().put("k", "heavy").put("n", recent[al])
                c.addedMs >= newSince -> JSONObject().put("k", "new").put("at", c.addedMs)
                al in lost -> JSONObject().put("k", "lost").put("n", plays[al]).put("at", last[al] * 1000)
                al in worn -> JSONObject().put("k", "worn").put("n", plays[al]).put("rank", top.indexOf(al) + 1)
                al < 0 -> JSONObject().put("k", "sealed").put("at", c.addedMs)
                else -> null
            }
            if (b != null) out.put(c.releaseId.toString(), b)
        }
        return out
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
            .filter { plays[it] >= WISH_MIN_PLAYS && distinct[it] >= WISH_MIN_TRACKS && owned(it) == null }
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

    /** Your records by the album in the history they are, found the way [findAlbum] finds them. */
    private val ownedByAlbum: Map<Int, CollectionItem> by lazy {
        HashMap<Int, CollectionItem>().also { m -> collection.forEach { c -> findAlbum(c.artist, c.title).takeIf { it >= 0 }?.let { m.putIfAbsent(it, c) } } }
    }

    /** The record of yours album [al] is: same artist and title, the main artist's (a duo's record), or a compilation's. */
    private fun owned(al: Int): CollectionItem? = ownedKeys[h.albumKeys[al]] ?: ownedByAlbum[al]

    private fun cover(al: Int) = owned(al)?.let { it.cover.ifEmpty { it.thumb } } ?: albumImages[h.albumKeys[al]] ?: ""

    /** The artist's photo (Deezer), "" until it was looked up or if there is none. */
    fun artistPhoto(name: String) = albumImages[photoKey(name)].orEmpty()

    /** Of [names], those whose photo hasn't been looked up yet. */
    private fun withoutPhoto(names: List<String>) = names.filter { photoKey(it) !in albumImages }.distinct()

    /** Top artists of a period without a photo yet, to look up. */
    fun artistsNeedingPhotos(id: String, max: Int = 10): List<String> {
        val r = range(id)
        val ap = IntArray(h.artistNames.size)
        for (i in h.lowerBound(r.from) until h.lowerBound(r.to)) ap[h.artist[i]]++
        // The period's best new finds too: they show with their faces.
        val fresh = firstTs.indices.filter { firstTs[it] in r.from until r.to }.sortedByDescending { artistLife.plays[it] }.take(8)
        return withoutPhoto((ap.indices.filter { ap[it] > 0 }.sortedByDescending { ap[it] }.take(max) + fresh).map { h.artistNames[it] })
    }

    /** Monday-based week number of a time. */
    private fun weekKey(t: Long) = Math.floorDiv(day(t) + 3, 7)
    private fun weekStart(k: Long) = dayStart(k * 7 - 3) * 1000

    /** Plays per week from the first week in [weeks] until this week. */
    private fun weeksJson(o: JSONObject, weeks: Map<Long, Int>): JSONObject {
        if (weeks.isEmpty()) return o
        val w0 = weeks.keys.min()
        val w1 = maxOf(w0, weekKey(nowSec))
        return o.put("weeksFrom", weekStart(w0)).put("weeks", JSONArray((w0..w1).map { weeks[it] ?: 0 }))
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
        return JSONObject()
            .put("artists", JSONArray(ap.indices.filter { ap[it] > 0 }.sortedByDescending { ap[it] }.take(max).map { JSONObject().put("name", h.artistNames[it]).put("plays", ap[it]).put("photo", artistPhoto(h.artistNames[it])) }))
            .put("tracks", JSONArray(tp.indices.filter { tp[it] > 0 }.sortedByDescending { tp[it] }.take(max).map { JSONObject().put("title", h.trackTitles[it]).put("artist", h.artistNames[h.trackArtist[it]]).put("plays", tp[it]) }))
            .put("albums", JSONArray(lp.indices.filter { lp[it] > 0 }.sortedByDescending { lp[it] }.take(max).map { JSONObject().put("title", h.albumTitles[it]).put("artist", h.artistNames[h.albumArtist[it]]).put("plays", lp[it]).put("cover", cover(it)).put("owned", owned(it) != null) }))
    }

    /**
     * The whole top list of [kind] ("tracks", "albums" or "artists") in [r], as a page shows it: places [from]
     * until [from] + [n], and with [around] also the few around the one a page came from ([focusArtist], with
     * [focusTitle] for songs and albums) if that is further down. Equal plays share their rank, like the pages' "#3".
     */
    fun rankList(r: Range, kind: String, from: Int, n: Int, focusArtist: String, focusTitle: String, around: Boolean): JSONObject {
        val i0 = h.lowerBound(r.from)
        val i1 = h.lowerBound(r.to)
        val of: (Int) -> Int = when (kind) {
            "albums" -> { i -> h.album[i] }
            "artists" -> { i -> h.artist[i] }
            else -> { i -> h.track[i] }
        }
        val name: (Int) -> String = when (kind) {
            "albums" -> { x -> h.albumTitles[x] }
            "artists" -> { x -> h.artistNames[x] }
            else -> { x -> h.trackTitles[x] }
        }
        val counts = IntArray(when (kind) { "albums" -> h.albumTitles.size; "artists" -> h.artistNames.size; else -> h.trackTitles.size })
        for (i in i0 until i1) of(i).let { if (it >= 0) counts[it]++ }
        val order = counts.indices.filter { counts[it] > 0 }.sortedWith(compareByDescending<Int> { counts[it] }.thenBy { name(it).lowercase() })
        val ranks = IntArray(order.size)
        for (p in order.indices) ranks[p] = if (p > 0 && counts[order[p]] == counts[order[p - 1]]) ranks[p - 1] else p + 1
        val focus = when {
            focusArtist.isEmpty() -> -1
            kind == "albums" -> findAlbum(focusArtist, focusTitle)
            kind == "artists" -> artistIndex[Text.norm(focusArtist)] ?: artistIndex[Text.norm(Artists.main(focusArtist, artistIndex.keys))] ?: -1
            else -> findTrack(focusArtist, focusTitle)
        }.let { x -> if (x < 0) -1 else order.indexOf(x) }
        fun row(p: Int): JSONObject {
            val x = order[p]
            val o = JSONObject().put("pos", p).put("rank", ranks[p]).put("plays", counts[x])
            return when (kind) {
                "albums" -> o.put("title", h.albumTitles[x]).put("artist", h.artistNames[h.albumArtist[x]]).put("cover", cover(x)).put("owned", owned(x) != null)
                "artists" -> o.put("name", h.artistNames[x]).put("photo", artistPhoto(h.artistNames[x]))
                else -> o.put("title", h.trackTitles[x]).put("artist", h.artistNames[h.trackArtist[x]]).put("cover", trackAlbum[x].let { if (it >= 0) cover(it) else "" })
            }
        }
        val to = minOf(order.size, from + n)
        val rows = JSONArray((from until to).map { row(it) })
        if (around && focus >= to) for (p in maxOf(to, focus - AROUND) until minOf(order.size, focus + AROUND + 1)) rows.put(row(p))
        return JSONObject().put("kind", kind).put("total", order.size).put("focus", focus).put("rows", rows)
            .put("range", JSONObject().put("id", r.id).put("kind", r.kind).put("label", r.label).put("from", r.from))
    }

    // ---- search ----

    private class Plays(val artist: IntArray, val track: IntArray, val album: IntArray)
    /** All-time plays per artist, song and album, worked out on the first search. */
    private val allPlays: Plays by lazy {
        val ap = IntArray(h.artistNames.size)
        val tp = IntArray(h.trackTitles.size)
        val lp = IntArray(h.albumTitles.size)
        for (i in 0 until h.size) {
            ap[h.artist[i]]++
            tp[h.track[i]]++
            if (h.album[i] >= 0) lp[h.album[i]]++
        }
        Plays(ap, tp, lp)
    }
    private val artistNorms by lazy { Array(h.artistNames.size) { Text.norm(h.artistNames[it]) } }
    private val trackNorms by lazy { Array(h.trackTitles.size) { Text.norm(h.trackTitles[it]) } }
    private val albumNorms by lazy { Array(h.albumTitles.size) { Text.norm(h.albumTitles[it]) } }

    /**
     * Artists, albums and songs from the whole history that match [query]: each of its words has
     * to appear in the name (for albums and songs, in the title or the artist). Names with a word
     * starting like the query come first, then the most played; [max] of each.
     */
    fun search(query: String, max: Int = 6): JSONObject {
        val q = Text.norm(query)
        val out = JSONObject().put("q", query)
        if (q.isEmpty()) return out.put("artists", JSONArray()).put("albums", JSONArray()).put("tracks", JSONArray())
        val words = q.split(' ')
        val p = allPlays
        fun best(n: Int, plays: IntArray, name: (Int) -> String, hay: (Int) -> String): List<Int> =
            (0 until n).filter { plays[it] > 0 && words.all { w -> hay(it).contains(w) } }
                .sortedWith(compareBy({ if (" ${name(it)}".contains(" $q")) 0 else 1 }, { -plays[it] }))
                .take(max)
        val artists = best(h.artistNames.size, p.artist, { artistNorms[it] }, { artistNorms[it] })
        val albums = best(h.albumTitles.size, p.album, { albumNorms[it] }, { albumNorms[it] + " " + artistNorms[h.albumArtist[it]] })
        val tracks = best(h.trackTitles.size, p.track, { trackNorms[it] }, { trackNorms[it] + " " + artistNorms[h.trackArtist[it]] })
        return out
            .put("artists", JSONArray(artists.map { JSONObject().put("name", h.artistNames[it]).put("plays", p.artist[it]).put("photo", artistPhoto(h.artistNames[it])) }))
            .put("albums", JSONArray(albums.map { JSONObject().put("title", h.albumTitles[it]).put("artist", h.artistNames[h.albumArtist[it]]).put("plays", p.album[it]).put("cover", cover(it)).put("owned", owned(it) != null) }))
            .put("tracks", JSONArray(tracks.map { JSONObject().put("title", h.trackTitles[it]).put("artist", h.artistNames[h.trackArtist[it]]).put("plays", p.track[it]) }))
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
        // A single day has no busiest weekday, biggest day or weekend share to speak of.
        val oneDay = r.kind == "day"
        if (n >= 20 && !oneDay) out.put(JSONObject().put("kind", "peak").put("dow", bestDow).put("hour", bestHour).put("dowShare", byDow[bestDow].toDouble() / n))
        val night = (listOf(22, 23, 0, 1, 2, 3).sumOf { byHour[it] }).toDouble() / n
        if (n >= 20 && night >= 0.2) out.put(JSONObject().put("kind", "night").put("share", night))
        val weekend = (byDow[5] + byDow[6]).toDouble() / n
        if (n >= 20 && (weekend >= 0.4 || weekend <= 0.15) && r.kind != "week" && !oneDay) out.put(JSONObject().put("kind", "weekend").put("share", weekend))
        moreInsights(r, i0, i1).forEach { out.put(it) }
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
        days.maxByOrNull { it.value }?.let { (d, cnt) -> if (!oneDay && days.size > 1) out.put(JSONObject().put("kind", "bigDay").put("day", dayStart(d) * 1000).put("count", cnt)) }
        val st = streaks(r, i0, i1)
        if (st.getInt("longest") >= 4 && r.kind != "week") out.put(JSONObject().put("kind", "streak").put("days", st.getInt("longest")).put("to", st.getLong("longestTo")))
        val v = vinyl.count { it.ts in r.from until r.to }
        if (v > 0) out.put(JSONObject().put("kind", "vinyl").put("count", v).put("share", v.toDouble() / n))
        return out
    }

    /** How many different songs of each album you ever played (to tell an album played through). */
    private val albumSongs: IntArray by lazy {
        val seen = HashSet<Long>()
        IntArray(h.albumTitles.size).also { c -> for (i in 0 until h.size) h.album[i].takeIf { it >= 0 }?.let { al -> if (seen.add((al.toLong() shl 32) or h.track[i].toLong())) c[al]++ } }
    }

    /**
     * Highlights that need a closer look than counting: the longest session; albums played through
     * (one after another, most of the songs of each you know); a genre you played much more than in
     * the year before; the decade most of it came from; a song on repeat; how much came off your shelf.
     */
    internal fun moreInsights(r: Range, i0: Int, i1: Int): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        val n = i1 - i0
        if (n == 0) return out
        // The longest session (a break of half an hour ends one).
        var s0 = i0
        var bestS = i0
        var bestE = i0
        for (i in i0 until i1) {
            if (i > i0 && h.ts[i] - h.ts[i - 1] > SESSION_GAP) s0 = i
            if (i - s0 > bestE - bestS) {
                bestS = s0
                bestE = i
            }
        }
        val count = bestE - bestS + 1
        if (count >= 8) {
            val who = (bestS..bestE).groupingBy { h.artist[it] }.eachCount().maxByOrNull { it.value }!!
            val minutes = ((h.ts[bestE] - h.ts[bestS]) / 60 + Math.round(MINUTES_PER_PLAY)).toInt()
            out += JSONObject().put("kind", "session").put("start", h.ts[bestS] * 1000).put("minutes", minutes).put("count", count)
                .put("artist", if (who.value * 3 >= count) h.artistNames[who.key] else "")
        }
        // Albums played through: a run of one album's songs, close together, with most of the songs of it you know.
        val runs = HashMap<Int, Int>()
        var i = i0
        while (i < i1) {
            val al = h.album[i]
            var j = i + 1
            while (j < i1 && h.album[j] == al && h.ts[j] - h.ts[j - 1] <= 20 * 60) j++
            if (al >= 0) {
                val distinct = (i until j).map { h.track[it] }.toSet().size
                if (distinct >= maxOf(4, Math.ceil(albumSongs[al] * 0.75).toInt())) runs.merge(al, 1, Int::plus)
            }
            i = j
        }
        if (runs.isNotEmpty()) {
            val top = runs.entries.sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key }).first()
            out += JSONObject().put("kind", "albums").put("count", runs.values.sum()).put("albums", runs.size)
                .put("title", h.albumTitles[top.key]).put("artist", h.artistNames[h.albumArtist[top.key]]).put("times", top.value)
        }
        // A genre played much more than in the year before (not over all time: no before).
        if (r.kind != "all" && n >= 20) {
            val (g, _, _) = scrobbleGenre
            val b0 = h.lowerBound(r.from - 365 * DAY_SEC)
            if (i0 - b0 >= 100) {
                val now = IntArray(genreKeys.size)
                val before = IntArray(genreKeys.size)
                for (k in i0 until i1) if (g[k] >= 0) now[g[k]]++
                for (k in b0 until i0) if (g[k] >= 0) before[g[k]]++
                val known = now.sum().toDouble()
                val knownBefore = before.sum().toDouble()
                if (known >= 15 && knownBefore >= 50) {
                    now.indices.map { f -> Triple(f, now[f] / known, before[f] / knownBefore) }
                        .filter { (_, s, b) -> s >= 0.15 && s - b >= 0.1 }
                        .maxByOrNull { (_, s, b) -> s - b }
                        ?.let { (f, s, b) -> out += JSONObject().put("kind", "genreShift").put("key", genreKeys[f]).put("label", Genres.label(genreKeys[f])).put("share", s).put("before", b) }
                }
            }
        }
        // The decade most of it came out in.
        val decades = HashMap<Int, Int>()
        var dated = 0
        for (k in i0 until i1) yearOf(k).takeIf { it > 0 }?.let { decades.merge(it / 10 * 10, 1, Int::plus); dated++ }
        if (n >= 20 && dated * 2 >= n) {
            val (d, c) = decades.maxByOrNull { it.value }!!
            val share = c.toDouble() / dated
            val current = date(nowSec).year / 10 * 10
            if ((d < current && share >= 0.35) || (d == current && share >= 0.7)) out += JSONObject().put("kind", "decade").put("decade", d).put("share", share).put("current", d == current)
        }
        // A song on repeat.
        loops(i0, i1, 1).optJSONObject(0)?.takeIf { it.optInt("run") >= 4 }?.let { out += JSONObject(it.toString()).put("kind", "loop") }
        // Off your shelf: plays of albums you own, wherever they were played.
        val ownedPlays = HashMap<Int, Int>()
        for (k in i0 until i1) h.album[k].takeIf { it >= 0 && owned(it) != null }?.let { ownedPlays.merge(it, 1, Int::plus) }
        val shelfShare = ownedPlays.values.sum().toDouble() / n
        if (n >= 20 && shelfShare >= 0.2) {
            val top = ownedPlays.maxByOrNull { it.value }!!.key
            out += JSONObject().put("kind", "shelf").put("share", shelfShare).put("title", h.albumTitles[top]).put("artist", h.artistNames[h.albumArtist[top]])
        }
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
        // "A & B" from a record counts for A, like the history does.
        val a = artistIndex[Text.norm(name)] ?: artistIndex[Text.norm(Artists.main(name, artistIndex.keys))] ?: return null
        val i0 = h.lowerBound(r.from)
        val i1 = h.lowerBound(r.to)
        var plays = 0
        var first = -1
        var last = -1
        val inRange = IntArray(h.artistNames.size)
        val months = HashMap<Int, Int>()
        val tracks = HashMap<Int, Int>()
        val albums = HashMap<Int, Int>()
        val rangeTracks = HashMap<Int, Int>()
        val rangeAlbums = HashMap<Int, Int>()
        val hours = IntArray(24)
        val weeks = HashMap<Long, Int>()
        var days = 0
        var lastDay = Long.MIN_VALUE
        var run = 0
        var best = 0
        var bestEnd = 0L
        val allTime = IntArray(h.artistNames.size)
        for (i in 0 until h.size) {
            allTime[h.artist[i]]++
            if (i in i0 until i1) inRange[h.artist[i]]++
            if (h.artist[i] != a) continue
            if (i in i0 until i1) {
                rangeTracks.merge(h.track[i], 1, Int::plus)
                if (h.album[i] >= 0) rangeAlbums.merge(h.album[i], 1, Int::plus)
            }
            plays++
            weeks.merge(weekKey(h.ts[i]), 1, Int::plus)
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
        val o = weeksJson(JSONObject(), weeks)
            .put("name", h.artistNames[a]).put("plays", plays).put("days", days).put("photo", artistPhoto(h.artistNames[a]))
            .put("rank", rank(allTime, a)).put("artists", allTime.count { it > 0 })
            .put("cover", albums.entries.sortedByDescending { it.value }.map { cover(it.key) }.firstOrNull { it.isNotEmpty() } ?: "")
            .put("first", JSONObject().put("ts", h.ts[first] * 1000).put("track", h.trackTitles[h.track[first]]))
            .put("last", JSONObject().put("ts", h.ts[last] * 1000).put("track", h.trackTitles[h.track[last]]))
            .put("busiestMonth", JSONObject().put("start", monthStart(busiest.key)).put("count", busiest.value))
            .put("monthsFrom", monthStart(m0)).put("months", JSONArray((m0..maxOf(m0, m1)).map { months[it] ?: 0 }))
            .put("hours", JSONArray(hours.toList()))
            .put("streak", best).put("streakEnd", dayStart(bestEnd) * 1000)
            .put("range", JSONObject().put("id", r.id).put("kind", r.kind).put("label", r.label).put("from", r.from).put("plays", inRange[a]).put("rank", rank))
            .put("tracks", JSONArray(tracks.entries.sortedByDescending { it.value }.take(TOP_MAX).map { JSONObject().put("title", h.trackTitles[it.key]).put("plays", it.value) }))
            .put("albums", JSONArray(albums.entries.sortedByDescending { it.value }.take(5).map { JSONObject().put("title", h.albumTitles[it.key]).put("plays", it.value).put("cover", cover(it.key)).put("owned", owned(it.key) != null) }))
            .put("owned", JSONArray(owned.map { JSONObject().put("id", it.releaseId).put("title", it.title).put("artist", it.artist).put("cover", it.cover.ifEmpty { it.thumb }).put("year", it.year) }))
            .put("genres", JSONArray(genres.of(h.artistNames[a], null).first.map { Genres.label(it) }))
            .put("rangeTracks", JSONArray(rangeTracks.entries.sortedByDescending { it.value }.take(RANGE_LIST).map { JSONObject().put("title", h.trackTitles[it.key]).put("plays", it.value) }))
            .put("rangeAlbums", JSONArray(rangeAlbums.entries.sortedByDescending { it.value }.take(3).map { JSONObject().put("title", h.albumTitles[it.key]).put("plays", it.value).put("cover", cover(it.key)) }))
        // Their albums as a river, over all your time with them.
        pageRiver(h.ts[first], { h.artist[it] == a }, { h.album[it] }, { h.albumTitles[it] })?.let { o.put("river", it) }
        // How you found them, back as far as the history goes, and who they led you to.
        val chain = JSONArray()
        var p = origins.second[a]
        while (p >= 0 && chain.length() < 6) {
            chain.put(originJson(p).put("name", h.artistNames[p]).put("plays", artistLife.plays[p]))
            p = origins.second[p]
        }
        o.put("origin", originJson(a).put("chain", chain))
        o.put("ledTo", JSONArray(h.artistNames.indices.filter { origins.second[it] == a && artistLife.plays[it] >= Atlas.KEPT_MIN }
            .sortedByDescending { artistLife.plays[it] }.take(8).map { JSONObject().put("name", h.artistNames[it]).put("plays", artistLife.plays[it]).put("at", h.ts[origins.first[it]] * 1000) }))
        globals.artist(h.artistNames[a])?.let { o.put("global", vsJson(plays, it)) }
        globals.top(h.artistNames[a])?.let { top ->
            val mine = songsOf(a)
            o.put("globalTop", JSONArray(top.take(10).mapIndexed { i, (title, gp) ->
                JSONObject().put("rank", i + 1).put("title", title).put("plays", gp).put("mine", mine[Text.normLoose(title)]?.second ?: 0)
            }))
        }
        return o
    }

    // ---- one track, one album ----

    private val trackIndex: Map<String, Int> by lazy {
        h.trackTitles.indices.associateBy { Hidden.key(h.artistNames[h.trackArtist[it]], h.trackTitles[it]) }
    }

    /** The album each track was last scrobbled with, -1 if never. */
    private val trackAlbum: IntArray by lazy {
        IntArray(h.trackTitles.size) { -1 }.also { ta -> for (i in 0 until h.size) if (h.album[i] >= 0) ta[h.track[i]] = h.album[i] }
    }

    /** A track as a player or Last.fm credits it ("A feat. B", "Song (Remastered)", other spellings); -1 if it isn't in the history. */
    fun findTrack(artist: String, title: String): Int {
        trackIndex[Hidden.key(artist, title)]?.let { return it }
        val main = Artists.main(artist, artistIndex.keys)
        trackIndex[Hidden.key(main, title)]?.let { return it }
        val a = artistIndex[Text.norm(main)] ?: return -1
        val loose = Text.normLoose(title)
        return h.trackTitles.indices.firstOrNull { h.trackArtist[it] == a && Text.normLoose(h.trackTitles[it]) == loose } ?: -1
    }

    /** All-time plays of an album, any edition ("(Deluxe Edition)" and the like count for the album). */
    fun albumPlaysOf(artist: String, title: String): Int = findAlbum(artist, title).let { if (it < 0) 0 else albumPlays[it] }

    private fun findAlbum(artist: String, title: String): Int =
        albumIndex[Text.albumKey(artist, title)] ?: albumIndex[Text.albumKey(Artists.main(artist, artistIndex.keys), title)]
            ?: (if (Text.norm(artist).startsWith("various")) albumByTitle[Text.normLoose(title)] else null) ?: -1

    /** Albums by title alone, for titles only one album has: how a compilation (Discogs' "Various") finds its plays. */
    private val albumByTitle: Map<String, Int> by lazy {
        val m = HashMap<String, Int>()
        val twice = HashSet<String>()
        h.albumTitles.forEachIndexed { al, t ->
            val k = Text.normLoose(t)
            if (k.isEmpty() || k in twice) return@forEachIndexed
            if (m.put(k, al) != null) {
                m.remove(k)
                twice += k
            }
        }
        m
    }

    private fun monthKey(t: Long) = date(t).let { it.year * 12 + it.monthValue - 1 }
    private fun monthStart(mk: Int) = ms(LocalDate.of(mk / 12, mk % 12 + 1, 1))

    /** Plays over time of whatever [match] picks: first and last play, days, plays per month, time of day, biggest day. */
    private fun life(match: (Int) -> Boolean): JSONObject? {
        var plays = 0
        var first = -1
        var last = -1
        var days = 0
        var lastDay = Long.MIN_VALUE
        var dayCount = 0
        var bestDay = 0L
        var bestDayCount = 0
        val months = HashMap<Int, Int>()
        val hours = IntArray(24)
        val weeks = HashMap<Long, Int>()
        for (i in 0 until h.size) {
            if (!match(i)) continue
            plays++
            weeks.merge(weekKey(h.ts[i]), 1, Int::plus)
            if (first < 0) first = i
            last = i
            months.merge(monthKey(h.ts[i]), 1, Int::plus)
            hours[hour(h.ts[i])]++
            val d = day(h.ts[i])
            if (d != lastDay) {
                days++
                lastDay = d
                dayCount = 0
            }
            if (++dayCount > bestDayCount) {
                bestDayCount = dayCount
                bestDay = d
            }
        }
        if (plays == 0) return null
        val m0 = months.keys.minOrNull()!!
        val m1 = monthKey(nowSec)
        val busiest = months.maxByOrNull { it.value }!!
        return weeksJson(JSONObject(), weeks).put("plays", plays).put("days", days)
            .put("first", h.ts[first] * 1000).put("last", h.ts[last] * 1000)
            .put("monthsFrom", monthStart(m0)).put("months", JSONArray((m0..maxOf(m0, m1)).map { months[it] ?: 0 }))
            .put("busiestMonth", JSONObject().put("start", monthStart(busiest.key)).put("count", busiest.value))
            .put("hours", JSONArray(hours.toList()))
            .put("bestDay", JSONObject().put("day", dayStart(bestDay) * 1000).put("count", bestDayCount))
    }

    /** Rank (1 = most) of [x] among [counts], and how many have any. */
    private fun rank(counts: IntArray, x: Int) = if (counts[x] > 0) counts.count { it > counts[x] } + 1 else 0

    /**
     * One song: its life in plays, rank, the album it's on, what you tend to play right after it,
     * where in a listening session it sits (opener, middle, closer, against how often any song
     * does) and its runs on repeat (back to back, within 20 minutes of each other).
     */
    fun track(artist: String, title: String, r: Range): JSONObject? {
        val t = findTrack(artist, title)
        if (t < 0) return null
        val o = life { h.track[it] == t } ?: return null
        val i0 = h.lowerBound(r.from)
        val i1 = h.lowerBound(r.to)
        val all = IntArray(h.trackTitles.size)
        val inRange = IntArray(h.trackTitles.size)
        var artistPlays = 0
        val next = HashMap<Int, Int>()
        // Where plays sit in their session: [opener, middle, closer, alone], for this song and for every play.
        val sits = IntArray(4)
        val sitsAll = IntArray(4)
        val runs = JSONArray()
        var run = 0
        var runAt = 0L
        for (i in 0 until h.size) {
            all[h.track[i]]++
            if (i in i0 until i1) inRange[h.track[i]]++
            if (h.artist[i] == h.trackArtist[t]) artistPlays++
            // What followed it within 20 minutes, not counting repeats.
            if (h.track[i] == t && i + 1 < h.size && h.track[i + 1] != t && h.ts[i + 1] - h.ts[i] < 1200) next.merge(h.track[i + 1], 1, Int::plus)
            val starts = i == 0 || h.ts[i] - h.ts[i - 1] > SESSION_GAP
            val ends = i == h.size - 1 || h.ts[i + 1] - h.ts[i] > SESSION_GAP
            val at = if (starts && ends) 3 else if (starts) 0 else if (ends) 2 else 1
            sitsAll[at]++
            if (h.track[i] != t) continue
            sits[at]++
            if (i > 0 && h.track[i - 1] == t && h.ts[i] - h.ts[i - 1] < 1200) {
                run++
            } else {
                if (run >= 2) runs.put(JSONObject().put("n", run).put("at", runAt * 1000))
                run = 1
                runAt = h.ts[i]
            }
        }
        if (run >= 2) runs.put(JSONObject().put("n", run).put("at", runAt * 1000))
        val inSessions = maxOf(1, sitsAll[0] + sitsAll[1] + sitsAll[2])
        val al = trackAlbum[t]
        o.put("title", h.trackTitles[t]).put("artist", h.artistNames[h.trackArtist[t]])
            .put("rank", rank(all, t)).put("tracks", all.count { it > 0 })
            .put("shareOfArtist", all[t].toDouble() / maxOf(1, artistPlays))
            .put("range", JSONObject().put("id", r.id).put("kind", r.kind).put("label", r.label).put("from", r.from).put("plays", inRange[t]).put("rank", rank(inRange, t)))
            .put("next", JSONArray(next.entries.sortedByDescending { it.value }.take(5).map { (n, c) -> JSONObject().put("title", h.trackTitles[n]).put("artist", h.artistNames[h.trackArtist[n]]).put("count", c) }))
            .put("sits", JSONObject().put("open", sits[0]).put("middle", sits[1]).put("close", sits[2]).put("alone", sits[3])
                .put("baseOpen", sitsAll[0].toDouble() / inSessions).put("baseClose", sitsAll[2].toDouble() / inSessions))
            .put("runs", runs)
        if (al >= 0) o.put("album", albumJson(al))
        val artistName = h.artistNames[h.trackArtist[t]]
        o.put("genres", JSONArray(genres.of(artistName, if (al >= 0) h.albumKeys[al] else null).first.map { Genres.label(it) }))
        globals.track(artistName, h.trackTitles[t])?.let { o.put("global", vsJson(all[t], it)) }
        globals.top(artistName)?.let { top -> o.put("globalRank", top.indexOfFirst { Text.normLoose(it.first) == Text.normLoose(h.trackTitles[t]) } + 1) }
        return o
    }

    private fun albumJson(al: Int): JSONObject = JSONObject().put("title", h.albumTitles[al]).put("artist", h.artistNames[h.albumArtist[al]])
        .put("cover", cover(al)).put("key", h.albumKeys[al]).put("owned", owned(al)?.releaseId ?: 0)

    /** One album: its life in plays, rank, and which of its tracks you play. */
    fun album(artist: String, title: String, r: Range): JSONObject? {
        val al = findAlbum(artist, title)
        if (al < 0) return null
        val o = life { h.album[it] == al } ?: return null
        val i0 = h.lowerBound(r.from)
        val i1 = h.lowerBound(r.to)
        val all = IntArray(h.albumTitles.size)
        val inRange = IntArray(h.albumTitles.size)
        val tracks = HashMap<Int, Int>()
        val rangeTracks = HashMap<Int, Int>()
        for (i in 0 until h.size) {
            val x = h.album[i]
            if (x < 0) continue
            all[x]++
            if (i in i0 until i1) inRange[x]++
            if (x == al) tracks.merge(h.track[i], 1, Int::plus)
            if (x == al && i in i0 until i1) rangeTracks.merge(h.track[i], 1, Int::plus)
        }
        globals.album(h.artistNames[h.albumArtist[al]], h.albumTitles[al])?.let { o.put("global", vsJson(all[al], it)) }
        // Its songs as a river, over all your time with it.
        pageRiver(o.getLong("first") / 1000, { h.album[it] == al }, { h.track[it] }, { h.trackTitles[it] })?.let { o.put("river", it) }
        return o.put("info", albumJson(al)).put("title", h.albumTitles[al]).put("artist", h.artistNames[h.albumArtist[al]])
            .put("rank", rank(all, al)).put("albums", all.count { it > 0 })
            .put("range", JSONObject().put("id", r.id).put("kind", r.kind).put("label", r.label).put("from", r.from).put("plays", inRange[al]).put("rank", rank(inRange, al)))
            .put("trackList", JSONArray(tracks.entries.sortedByDescending { it.value }.map { (t, c) -> JSONObject().put("title", h.trackTitles[t]).put("plays", c) }))
            .put("rangeTracks", JSONArray(rangeTracks.entries.sortedByDescending { it.value }.take(RANGE_LIST).map { (t, c) -> JSONObject().put("title", h.trackTitles[t]).put("plays", c) }))
            .put("genres", JSONArray(genres.of(h.artistNames[h.albumArtist[al]], h.albumKeys[al]).first.map { Genres.label(it) }))
    }

    // ---- explore: the long tail, relative to today ----

    private class ArtistLife(n: Int) {
        val plays = IntArray(n)
        val first = LongArray(n)
        val last = LongArray(n)
        val days = IntArray(n)
        val months = IntArray(n)
        val peakMonth = IntArray(n)
        val peakCount = IntArray(n)
        val lastDay = LongArray(n) { Long.MIN_VALUE }
        val curMonth = IntArray(n) { -1 }
        val curCount = IntArray(n)
    }

    private val artistLife: ArtistLife by lazy {
        val l = ArtistLife(h.artistNames.size)
        for (i in 0 until h.size) {
            val a = h.artist[i]
            val t = h.ts[i]
            if (l.plays[a]++ == 0) l.first[a] = t
            l.last[a] = t
            val d = day(t)
            if (d != l.lastDay[a]) {
                l.days[a]++
                l.lastDay[a] = d
            }
            val m = monthKey(t)
            if (m != l.curMonth[a]) {
                l.months[a]++
                l.curMonth[a] = m
                l.curCount[a] = 0
            }
            if (++l.curCount[a] > l.peakCount[a]) {
                l.peakCount[a] = l.curCount[a]
                l.peakMonth[a] = m
            }
        }
        l
    }

    private val albumPlays: IntArray by lazy { IntArray(h.albumTitles.size).also { p -> for (x in h.album) if (x >= 0) p[x]++ } }
    private val trackPlays: IntArray by lazy { IntArray(h.trackTitles.size).also { p -> for (x in h.track) p[x]++ } }

    /** An artist's best covered album, for a picture. */
    private fun artistPicture(a: Int): String {
        var best = -1
        for (al in albumPlays.indices) if (h.albumArtist[al] == a && albumPlays[al] > 0 && (best < 0 || albumPlays[al] > albumPlays[best]) && cover(al).isNotEmpty()) best = al
        return if (best >= 0) cover(best) else ""
    }

    private val sinceSec get() = nowSec - LOST_DAYS * DAY_SEC

    /** Artists you played a lot that have gone quiet for half a year or more, most played first. */
    private val lostArtists: List<Int> by lazy {
        val l = artistLife
        l.plays.indices.filter { l.plays[it] >= LOST_MIN && l.last[it] < sinceSec }.sortedByDescending { l.plays[it] }.take(EXPLORE_MAX)
    }

    /** Albums (several different tracks played) gone quiet for half a year or more. */
    private val lostAlbums: List<Int> by lazy {
        val last = LongArray(h.albumTitles.size)
        val tracks = Array(h.albumTitles.size) { HashSet<Int>() }
        for (i in 0 until h.size) {
            val x = h.album[i]
            if (x < 0) continue
            last[x] = h.ts[i]
            if (tracks[x].size < 3) tracks[x] += h.track[i]
        }
        albumPlays.indices.filter { albumPlays[it] >= LOST_ALBUM_MIN && tracks[it].size >= 3 && last[it] < sinceSec }
            .sortedByDescending { albumPlays[it] }.take(EXPLORE_MAX)
    }

    /** Artists outside your top ones who keep coming back: many different months, never many plays. */
    private val regulars: List<Int> by lazy {
        val l = artistLife
        val top = l.plays.indices.sortedByDescending { l.plays[it] }.take(minOf(REGULARS_SKIP_TOP, l.plays.size / 5)).toSet()
        l.plays.indices.filter { it !in top && l.months[it] >= REGULARS_MIN_MONTHS && l.last[it] >= nowSec - 365 * DAY_SEC }
            .sortedWith(compareByDescending<Int> { l.months[it] }.thenByDescending { l.plays[it] }).take(EXPLORE_MAX)
    }

    /** Artists played several times on one single day and never again. */
    private val oneDayers: List<Int> by lazy {
        val l = artistLife
        val today = day(nowSec)
        l.plays.indices.filter { l.days[it] == 1 && l.plays[it] >= 3 && today - day(l.last[it]) >= 30 }.sortedByDescending { l.plays[it] }.take(EXPLORE_MAX)
    }

    /** Tracks of your favorite artists you've played only once or twice. */
    private val deepCuts: List<Int> by lazy {
        val l = artistLife
        val top = l.plays.indices.sortedByDescending { l.plays[it] }.take(DEEP_CUT_ARTISTS)
        val rankOf = top.withIndex().associate { (i, a) -> a to i }
        val last = LongArray(h.trackTitles.size).also { lt -> for (i in 0 until h.size) lt[h.track[i]] = h.ts[i] }
        h.trackTitles.indices.filter { h.trackArtist[it] in rankOf && trackPlays[it] in 1..2 }
            .groupBy { h.trackArtist[it] }.toList().sortedBy { rankOf[it.first] }
            .flatMap { (_, ts) -> ts.sortedByDescending { last[it] }.take(3) }.take(EXPLORE_MAX * 2)
    }

    /** Most played tracks of each of [artists], in that order. */
    private fun topTracksOf(artists: List<Int>, per: Int): Map<Int, List<Int>> {
        val want = artists.toSet()
        val byArtist = HashMap<Int, MutableList<Int>>()
        for (t in h.trackTitles.indices) if (h.trackArtist[t] in want) byArtist.getOrPut(h.trackArtist[t]) { ArrayList() } += t
        return byArtist.mapValues { (_, ts) -> ts.sortedByDescending { trackPlays[it] }.take(per) }
    }

    private fun trackJson(t: Int): JSONObject = JSONObject().put("title", h.trackTitles[t]).put("artist", h.artistNames[h.trackArtist[t]])
        .put("album", trackAlbum[t].let { if (it >= 0) h.albumTitles[it] else "" }).put("plays", trackPlays[t])

    /** Everything on the Explore page. */
    fun explore(): JSONObject {
        val l = artistLife
        val tops = topTracksOf(lostArtists + regulars + oneDayers, 1)
        fun topTrack(a: Int) = tops[a]?.firstOrNull()?.let { h.trackTitles[it] } ?: ""
        fun artistJson(a: Int) = JSONObject().put("name", h.artistNames[a]).put("plays", l.plays[a]).put("first", l.first[a] * 1000).put("last", l.last[a] * 1000)
            .put("months", l.months[a]).put("days", l.days[a]).put("cover", artistPicture(a)).put("photo", artistPhoto(h.artistNames[a])).put("track", topTrack(a))
            .put("peak", JSONObject().put("start", monthStart(l.peakMonth[a])).put("count", l.peakCount[a]))
        val albumLast = LongArray(h.albumTitles.size).also { la -> for (i in 0 until h.size) if (h.album[i] >= 0) la[h.album[i]] = h.ts[i] }
        // This day in earlier years.
        val today = date(nowSec)
        val years = java.util.TreeMap<Int, MutableList<Int>>(Comparator.reverseOrder())
        for (i in 0 until h.size) {
            val d = date(h.ts[i])
            if (d.year < today.year && d.monthValue == today.monthValue && d.dayOfMonth == today.dayOfMonth) years.getOrPut(d.year) { ArrayList() } += i
        }
        val onThisDay = JSONArray(
            years.map { (y, idx) ->
                val ac = idx.groupingBy { h.artist[it] }.eachCount()
                val tc = idx.groupingBy { h.track[it] }.eachCount()
                JSONObject().put("year", y).put("plays", idx.size)
                    .put("artists", JSONArray(ac.entries.sortedByDescending { it.value }.take(3).map { h.artistNames[it.key] }))
                    .put("tracks", JSONArray(tc.entries.sortedByDescending { it.value }.take(4).map { trackJson(it.key).put("plays", it.value) }))
            },
        )
        val few = l.plays.count { it in 1 until TAIL_PLAYS }
        return JSONObject()
            .put("genreMap", genreMap())
            .put("tail", JSONObject().put("artists", l.plays.count { it > 0 }).put("few", few).put("fewPlays", TAIL_PLAYS))
            .put("lostArtists", JSONArray(lostArtists.map { artistJson(it) }))
            .put("lostAlbums", JSONArray(lostAlbums.map { albumJson(it).put("plays", albumPlays[it]).put("last", albumLast[it] * 1000) }))
            .put("regulars", JSONArray(regulars.map { artistJson(it) }))
            .put("oneDay", JSONArray(oneDayers.map { artistJson(it) }))
            .put("deepCuts", JSONArray(deepCuts.map { trackJson(it) }))
            .put("onThisDay", onThisDay)
    }

    /** Artists on the Explore page without a photo yet. */
    fun exploreNeedingPhotos(): List<String> = withoutPhoto((lostArtists + regulars + oneDayers).map { h.artistNames[it] })

    /** Covers missing on the Explore page, to look up on Last.fm. */
    fun exploreNeedingImages(): List<Triple<String, String, String>> =
        lostAlbums.filter { cover(it).isEmpty() }.map { Triple(h.artistNames[h.albumArtist[it]], h.albumTitles[it], h.albumKeys[it]) }

    // ---- playlists ----

    /**
     * A playlist from the Explore lists ([kind] lost, lostAlbums, regulars, oneDay, deepCuts,
     * onThisDay, dig) or the top tracks of a period (top, with [rangeId]), as name, note and tracks.
     */
    fun playlist(kind: String, rangeId: String = "", size: Int = PLAYLIST_SIZE): JSONObject {
        fun ofArtists(artists: List<Int>, per: Int): List<Int> {
            val tops = topTracksOf(artists, per)
            // Round-robin, so one artist's songs don't come in a block.
            return (0 until per).flatMap { k -> artists.mapNotNull { tops[it]?.getOrNull(k) } }
        }
        val (name, note, tracks) = when (kind) {
            "lost" -> Triple("Lost favorites", "Artists you loved who have gone quiet", ofArtists(lostArtists, 2))
            "lostAlbums" -> {
                val want = lostAlbums.toSet()
                val counts = HashMap<Int, HashMap<Int, Int>>()
                for (i in 0 until h.size) if (h.album[i] in want) counts.getOrPut(h.album[i]) { HashMap() }.merge(h.track[i], 1, Int::plus)
                val per = lostAlbums.map { al -> counts[al].orEmpty().entries.sortedByDescending { it.value }.take(3).map { it.key } }
                Triple("Albums to rediscover", "The songs you played most from albums gone quiet", (0 until 3).flatMap { k -> per.mapNotNull { it.getOrNull(k) } })
            }
            "regulars" -> Triple("Quiet regulars", "Always around, never on top", ofArtists(regulars, 2))
            "oneDay" -> Triple("One-day wonders", "Played all day once, never again", ofArtists(oneDayers, 2))
            "deepCuts" -> Triple("Deep cuts", "Songs by your favorite artists you hardly know", deepCuts)
            "onThisDay" -> {
                val today = date(nowSec)
                val c = HashMap<Int, Int>()
                for (i in 0 until h.size) {
                    val d = date(h.ts[i])
                    if (d.year < today.year && d.monthValue == today.monthValue && d.dayOfMonth == today.dayOfMonth) c.merge(h.track[i], 1, Int::plus)
                }
                Triple("On this day", "What you played on this day in earlier years", c.entries.sortedByDescending { it.value }.map { it.key })
            }
            "dig" -> {
                // Songs you played a few times and not for months, shuffled anew each time.
                val last = LongArray(h.trackTitles.size).also { lt -> for (i in 0 until h.size) lt[h.track[i]] = h.ts[i] }
                val pool = h.trackTitles.indices.filter { trackPlays[it] in 2..6 && last[it] < nowSec - 120 * DAY_SEC }
                Triple("Crate dig", "Songs you played a few times, then forgot", pool.shuffled())
            }
            else -> {
                // The page names it after the period, in the phone's language.
                val r = range(rangeId)
                val tp = IntArray(h.trackTitles.size)
                for (i in h.lowerBound(r.from) until h.lowerBound(r.to)) tp[h.track[i]]++
                return JSONObject().put("kind", "top").put("range", JSONObject().put("id", r.id).put("kind", r.kind).put("label", r.label).put("from", r.from).put("to", r.to))
                    .put("tracks", JSONArray(tp.indices.filter { tp[it] > 0 }.sortedByDescending { tp[it] }.take(size).map { trackJson(it).put("plays", tp[it]) }))
            }
        }
        return JSONObject().put("kind", kind).put("name", name).put("note", note)
            .put("tracks", JSONArray(tracks.distinct().take(size).map { trackJson(it) }))
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
            .put("artists", JSONArray(topA.map { JSONObject().put("name", h.artistNames[it]).put("plays", ap[it]).put("share", ap[it].toDouble() / n).put("days", aDays[it]).put("cover", artistPhoto(h.artistNames[it]).ifEmpty { artistCover(it) }) }))
            .put("vs", topA.firstOrNull()?.let { a -> globals.artist(h.artistNames[a])?.let { vsJson(artistLife.plays[a], it).put("name", h.artistNames[a]).put("photo", artistPhoto(h.artistNames[a])) } } ?: JSONObject.NULL)
            .put("tracks", JSONArray(topT.map { JSONObject().put("title", h.trackTitles[it]).put("artist", h.artistNames[h.trackArtist[it]]).put("plays", tp[it]).put("cover", trackAlbum[it].let { al -> if (al >= 0) cover(al) else "" }) }))
            .put("songBestDay", if (songBest != null) JSONObject().put("day", dayStart(songBest.key) * 1000).put("count", songBest.value) else JSONObject.NULL)
            .put("albums", JSONArray(topL.map { JSONObject().put("title", h.albumTitles[it]).put("artist", h.artistNames[h.albumArtist[it]]).put("plays", lp[it]).put("cover", cover(it)).put("owned", owned(it) != null) }))
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
