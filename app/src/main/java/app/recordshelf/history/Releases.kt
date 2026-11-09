package app.recordshelf.history

import app.recordshelf.net.DzRelease
import app.recordshelf.util.Text
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * One of your artists whose new releases are looked for: how much you care about them ([weight], 0..1, from all your
 * plays, the recent ones more, and your records of theirs), their plays, the last one (Unix seconds, 0 for none), the
 * records of theirs on your shelf, and whether they've gone [quiet] (played a lot, not for half a year).
 */
data class Fan(val name: String, val weight: Double, val plays: Int, val last: Long, val owned: Int, val quiet: Boolean = false)

/**
 * A new release by one of your artists, as Explore lists it. [kind]: "live" or "remix" for those, else "". [singles]: the
 * singles out before it that lead up to it, folded into it, and [eps] the same for EPs out shortly before an album.
 * [parts]: the titles of a series it heads, oldest first ("The Name of This Band Member is Tina", "... is Jerry", ...),
 * empty for a release on its own. [plays]: yours of it so far. [source]: "deezer", or "mb" for an album MusicBrainz
 * knows is coming that Deezer doesn't list yet.
 */
data class NewRelease(
    val artist: String,
    val title: String,
    val type: String,
    val date: String,
    val cover: String,
    val id: Long,
    val link: String,
    val kind: String,
    val singles: List<String>,
    val plays: Int,
    val score: Double,
    val source: String = "deezer",
    val eps: List<String> = emptyList(),
    val parts: List<String> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().put("artist", artist).put("title", title).put("type", type).put("date", date)
        .put("cover", cover).put("id", id).put("link", link).put("kind", kind).put("singles", JSONArray(singles)).put("plays", plays)
        .put("src", source).put("eps", JSONArray(eps)).put("parts", JSONArray(parts))
}

/**
 * New releases by your artists: what came out lately and what is coming, one record per title (a deluxe edition of an old
 * album is not new, and the singles before an album fold into it), the ones you care about most first.
 */
object Releases {
    /** Artists whose releases are looked for: played this often, or on your shelf; this many at most. */
    const val FAN_MIN_PLAYS = 15
    const val FANS = 300

    /** The ones you care about most are looked at daily, the rest weekly; MusicBrainz is asked about the first [ANNOUNCED] weekly. */
    const val DAILY = 40
    const val ANNOUNCED = 40

    /** Out this many days ago at most. */
    const val BACK_DAYS = 56L

    /** Coming this many days ahead at most. */
    const val AHEAD_DAYS = 240L

    /** Singles out this long before an album or EP lead up to it. */
    const val LEAD_DAYS = 150L

    /** An EP out this long before an album leads up to it (once the album is out). */
    const val EP_LEAD_DAYS = 30L

    /** Parts of a series come out at most this far apart. */
    const val SERIES_DAYS = 60L

    /** Kept per artist between checks: releases this recent (the window and the singles before it), and those still to come. */
    const val KEEP_DAYS = BACK_DAYS + LEAD_DAYS

    private val TYPE_RANK = mapOf("album" to 0, "ep" to 1, "single" to 2)

    /** A full date that is one ("YYYY-MM-DD"; Deezer gives "0000-00-00" for some). */
    fun dated(d: String) = d.length == 10 && runCatching { LocalDate.parse(d) }.isSuccess
    private fun rank(r: DzRelease) = TYPE_RANK[r.type] ?: 3

    private val LIVE = Regex("""\blive\s+(at|in|from|on|to)\b|[(\[]\s*live\b|\blive\s*[)\]]|\blive\s*$|\bunplugged\b|\bin concert\b|\blive sessions?\b""", RegexOption.IGNORE_CASE)
    private val REMIX = Regex("""\bremix(es|ed)?\b|\brework(s|ed)?\b""", RegexOption.IGNORE_CASE)

    /** What a release is besides new songs, from its title: "remix", "live" or "". */
    fun kind(title: String): String = when {
        REMIX.containsMatchIn(title) -> "remix"
        LIVE.containsMatchIn(title) -> "live"
        else -> ""
    }

    /**
     * Of an artist's [releases], what is kept until the next check: those of the last [KEEP_DAYS] days and still to
     * come, and older ones of the same title (so a deluxe edition knows its album is old). No compilations.
     */
    fun keep(releases: List<DzRelease>, today: LocalDate): List<DzRelease> {
        val from = today.minusDays(KEEP_DAYS).toString()
        val usable = releases.filter { it.type != "compile" && dated(it.date) }
        val titles = usable.filter { it.date >= from }.map { Text.normLoose(it.title) }.toSet()
        return usable.filter { it.date >= from || Text.normLoose(it.title) in titles }
    }

    /** What [find] gives: out lately, coming, and the releases of artists of yours who had gone quiet ([back], either). */
    class Found(val out: List<NewRelease>, val soon: List<NewRelease>, val back: List<NewRelease>)

    /**
     * New releases by your artists ([by]: each artist's releases by name; [fans]: by normed name) as of [today]. [Found.out]
     * came out in the last [BACK_DAYS] days, the ones you haven't played first, then by how much you care about the artist
     * and how new and how big the release is; [Found.soon] is due in the next [AHEAD_DAYS] days, soonest first. A title out
     * before isn't new (a deluxe edition of an old album, a single that became the album); singles out up to [LEAD_DAYS]
     * days before an album or EP fold into it. Artists you'd stopped playing ([Fan.quiet]) go to [Found.back] instead.
     * [hidden]: album keys you turned down. [plays]: yours of a release (artist, title, type). [songs]: an album's song
     * titles where known ([needSongs] says which to look up): a release named after a song of an album out soon after it
     * led up to that album, whatever the catalog calls it.
     */
    fun find(
        by: Map<String, List<DzRelease>>,
        fans: Map<String, Fan>,
        today: LocalDate,
        hidden: Set<String> = emptySet(),
        plays: (String, String, String) -> Int = { _, _, _ -> 0 },
        songs: (DzRelease) -> List<String>? = { null },
    ): Found {
        val from = today.minusDays(BACK_DAYS).toString()
        val to = today.plusDays(AHEAD_DAYS).toString()
        val now = today.toString()
        val out = ArrayList<NewRelease>()
        val soon = ArrayList<NewRelease>()
        val back = ArrayList<NewRelease>()
        for ((artist, rels) in by) {
            val fan = fans[Text.norm(artist)]
            val records = records(rels)
            val lead = HashMap<DzRelease, MutableList<DzRelease>>()
            val led = HashSet<DzRelease>()
            // An EP out shortly before an album that is out leads up to it (Queens of the Stone Age's "Run Away From the
            // Mob": four songs of "Perfecth", two days before it).
            val albums = records.filter { it.type == "album" && it.date <= now && it.id > 0 }.sortedBy { it.date }
            val epLead = HashMap<DzRelease, MutableList<DzRelease>>()
            // A release named after a song of an album out soon after it led up to it (Deezer calls "Run Away From the
            // Mob" an album in some places).
            for (e in records.filter { it.type != "single" }) {
                val want = Text.normLoose(e.title)
                val a = albums.firstOrNull { a ->
                    a !== e && a.date >= e.date && !LocalDate.parse(a.date).isAfter(LocalDate.parse(e.date).plusDays(LEAD_DAYS)) &&
                        songs(a)?.any { Text.normLoose(it) == want } == true
                } ?: continue
                epLead.getOrPut(a) { ArrayList() } += e
                led += e
            }
            for (e in records.filter { it.type == "ep" && it !in led }) {
                val d = LocalDate.parse(e.date)
                val a = albums.firstOrNull { it.date >= e.date && !LocalDate.parse(it.date).isAfter(d.plusDays(EP_LEAD_DAYS)) } ?: continue
                epLead.getOrPut(a) { ArrayList() } += e
                led += e
            }
            // Each single leads up to the first album or EP on or after it, if that comes soon enough.
            val bigOnes = records.filter { it.type != "single" && it !in led }.sortedBy { it.date }
            for (s in records.filter { it.type == "single" }.sortedBy { it.date }) {
                val d = LocalDate.parse(s.date)
                val a = bigOnes.firstOrNull { it.date >= s.date && !LocalDate.parse(it.date).isAfter(d.plusDays(LEAD_DAYS)) } ?: continue
                lead.getOrPut(a) { ArrayList() } += s
                led += s
            }
            val mine = ArrayList<NewRelease>()
            for (r in records) {
                if (r in led || r.date < from || r.date > to || Text.albumKey(artist, r.title) in hidden) continue
                val k = kind(r.title)
                val p = if (r.date <= now) plays(artist, r.title, r.type) else 0
                mine += NewRelease(
                    artist, r.title, r.type, r.date, r.cover, r.id, r.link, k, lead[r].orEmpty().map { it.title }, p,
                    score(fan, r, k, today), if (r.id > 0) "deezer" else "mb", epLead[r].orEmpty().map { it.title },
                )
            }
            for (x in series(mine, now)) when {
                fan?.quiet == true -> back += x
                x.date <= now -> out += x
                else -> soon += x
            }
        }
        out.sortWith(compareBy<NewRelease> { it.plays > 0 }.thenByDescending { it.score })
        soon.sortWith(compareBy<NewRelease> { it.date }.thenByDescending { it.score })
        back.sortWith(compareBy<NewRelease> { it.date > now }.thenByDescending { it.score })
        return Found(out, soon, back)
    }

    private fun words(title: String) = Text.norm(title).split(' ').filter { it.isNotEmpty() }

    /** How many words two titles start with alike. */
    private fun sharedWords(a: List<String>, b: List<String>): Int {
        var n = 0
        while (n < a.size && n < b.size && a[n] == b[n]) n++
        return n
    }

    /**
     * Releases of one artist that are parts of a series, as one: titles alike but for their last words ("The Name of
     * This Band Member is Chris", "... is David"), at least three words and half of each title alike, each out (or due)
     * within [SERIES_DAYS] of the one before. The newest part out by [now] heads it, with all the [NewRelease.parts] and all their plays.
     */
    fun series(list: List<NewRelease>, now: String): List<NewRelease> {
        val groups = ArrayList<MutableList<NewRelease>>()
        for (r in list.sortedBy { it.date }) {
            val w = words(r.title)
            val g = groups.firstOrNull { g ->
                val shared = g.minOf { sharedWords(words(it.title), w) }
                shared >= 3 && (g.map { words(it.title) } + listOf(w)).all { shared * 2 >= it.size } &&
                    LocalDate.parse(r.date).toEpochDay() - LocalDate.parse(g.last().date).toEpochDay() <= SERIES_DAYS
            }
            if (g != null) g += r else groups += mutableListOf(r)
        }
        return groups.map { g ->
            if (g.size == 1) g[0] else (g.lastOrNull { it.date <= now } ?: g.last()).copy(
                parts = g.map { it.title }, plays = g.sumOf { it.plays }, score = g.maxOf { it.score },
                singles = g.flatMap { it.singles }.distinct(), eps = g.flatMap { it.eps }.distinct(),
            )
        }
    }

    /**
     * The albums of [rels] (one artist's) whose songs would tell whether an album or EP out up to [LEAD_DAYS] before
     * them led up to them: out by [today], in the last [BACK_DAYS] days, on Deezer.
     */
    fun needSongs(rels: List<DzRelease>, today: LocalDate): List<DzRelease> {
        val records = records(rels)
        val from = today.minusDays(BACK_DAYS).toString()
        val now = today.toString()
        return records.filter { a ->
            a.type == "album" && a.id > 0 && a.date in from..now && records.any { e ->
                e !== a && e.type != "single" && e.date <= a.date && !LocalDate.parse(a.date).isAfter(LocalDate.parse(e.date).plusDays(LEAD_DAYS))
            }
        }
    }

    /**
     * An artist's releases as records, one per title: the album over an EP over a single, dated by the first release
     * that was as much (so "Romance (Deluxe Edition)" is as old as "Romance", and the album "Skinty Fia" is the album's
     * date, not its single's).
     */
    private fun records(rels: List<DzRelease>): List<DzRelease> =
        rels.filter { it.type != "compile" && dated(it.date) }.groupBy { Text.normLoose(it.title) }.values.map { g ->
            val best = g.minOf { rank(it) }
            g.filter { rank(it) <= best }.minBy { it.date }
        }

    /** How much a release matters to you: how much you care about its artist, then an album over an EP over a single, and newer. */
    private fun score(fan: Fan?, r: DzRelease, kind: String, today: LocalDate): Double {
        val type = when (r.type) { "album" -> 1.0; "ep" -> 0.75; else -> 0.5 } * (if (kind.isEmpty()) 1.0 else 0.6)
        val age = (today.toEpochDay() - LocalDate.parse(r.date).toEpochDay()).coerceIn(0, BACK_DAYS)
        return (0.2 + (fan?.weight ?: 0.0)) * type * (1.0 - 0.5 * age / BACK_DAYS)
    }

    private val NOT_AN_ALBUM = Regex("""\b(best of|greatest hits|the hits|collection|anthology|essentials?|b-sides|rarities|instrumentals?|soundtrack|o\.?s\.?t\.?|karaoke|acoustic)\b""", RegexOption.IGNORE_CASE)

    /** Whether an album of a new artist is one to start with: not live, remixed or a best-of. */
    fun toStartWith(title: String) = kind(title).isEmpty() && !NOT_AN_ALBUM.containsMatchIn(title)

    /** An artist's albums you haven't heard: [heard] of their [of] albums you have (played or own), and the others, newest first. */
    class Gap(val artist: String, val heard: Int, val of: Int, val missing: List<DzRelease>)

    /**
     * The albums of [artist] you haven't heard, from their discography ([entries], editions folded): studio albums out by
     * [today], not live, remixed or a best-of, that you never played ([plays] per title) or own ([owned]). Null unless you
     * know at least one of their albums and there is one you don't. A longer edition of one you know ("Skinty Fia go deo")
     * doesn't count as another album.
     */
    fun gaps(artist: String, entries: List<Discography.Entry>, plays: (String) -> Int, owned: (String) -> Boolean, today: LocalDate): Gap? {
        val now = today.toString()
        val albums = entries.filter {
            val r = it.release
            r.type == "album" && dated(r.date) && r.date <= now && kind(r.title).isEmpty() && !NOT_AN_ALBUM.containsMatchIn(r.title)
        }
        val (known, rest) = albums.partition { e -> e.editions.any { plays(it.title) > 0 || owned(it.title) } }
        if (known.isEmpty()) return null
        val knownTitles = known.map { Text.normLoose(it.release.title) }
        val missing = rest.map { it.release }.filter { m -> knownTitles.none { Text.normLoose(m.title).startsWith("$it ") } }
        if (missing.isEmpty()) return null
        return Gap(artist, known.size, known.size + missing.size, missing.sortedByDescending { it.date })
    }

    /**
     * Albums MusicBrainz knows are coming ([groups]: title, "album" or "ep", date, release group id, secondary types) that
     * Deezer doesn't list yet ([deezer]), as releases with Cover Art Archive's picture (if it has one by then).
     */
    fun announced(groups: List<MbGroup>, deezer: List<DzRelease>, today: LocalDate): List<DzRelease> {
        val have = deezer.map { Text.normLoose(it.title) }.toSet()
        val now = today.toString()
        return groups.filter { dated(it.date) && it.date > now && "Compilation" !in it.secondary && Text.normLoose(it.title) !in have }
            .map {
                DzRelease(0, it.title, it.type, it.date, "https://coverartarchive.org/release-group/${it.id}/front-250", "https://musicbrainz.org/release-group/${it.id}")
            }
    }
}

/** An album or EP as MusicBrainz groups its releases: [secondary] are its other types ("Live", "Compilation", …). */
data class MbGroup(val id: String, val title: String, val type: String, val date: String, val secondary: List<String>) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("type", type).put("date", date).put("secondary", JSONArray(secondary))

    companion object {
        fun fromJson(o: JSONObject) = MbGroup(
            o.optString("id"), o.optString("title"), o.optString("type"), o.optString("date"),
            o.optJSONArray("secondary")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
        )
    }
}
