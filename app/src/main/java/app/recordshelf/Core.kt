package app.recordshelf

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import app.recordshelf.discogs.CollectionItem
import app.recordshelf.discogs.DiscogsClient
import app.recordshelf.discogs.RateLimiter
import app.recordshelf.discogs.DiscogsException
import app.recordshelf.discogs.Release
import app.recordshelf.history.Discography
import app.recordshelf.history.Discover
import app.recordshelf.history.Genres
import app.recordshelf.history.Global
import app.recordshelf.history.GlobalAsk
import app.recordshelf.history.GlobalTop
import app.recordshelf.history.Globals
import app.recordshelf.history.Hidden
import app.recordshelf.history.History
import app.recordshelf.history.HistorySync
import app.recordshelf.history.ImportedPlay
import app.recordshelf.history.TidalImport
import app.recordshelf.history.Junk
import app.recordshelf.history.Like
import app.recordshelf.history.Fan
import app.recordshelf.history.MbGroup
import app.recordshelf.history.Pick
import app.recordshelf.history.Releases
import app.recordshelf.history.Stats
import app.recordshelf.history.Suggestion
import app.recordshelf.history.Years
import app.recordshelf.lastfm.LastfmClient
import app.recordshelf.lastfm.LastfmException
import app.recordshelf.lastfm.Scrobble
import app.recordshelf.mb.MusicBrainzClient
import app.recordshelf.net.DeezerClient
import app.recordshelf.net.DzArtist
import app.recordshelf.net.DzRelease
import app.recordshelf.net.Http
import app.recordshelf.net.SetlistfmClient
import app.recordshelf.net.Setlists
import app.recordshelf.net.Show
import app.recordshelf.scrobble.ActivityLog
import app.recordshelf.scrobble.Compare
import app.recordshelf.scrobble.Durations
import app.recordshelf.scrobble.FixRules
import app.recordshelf.scrobble.FlushResult
import app.recordshelf.scrobble.Heard
import app.recordshelf.scrobble.HeardLog
import app.recordshelf.scrobble.HeardSong
import app.recordshelf.scrobble.Listen
import app.recordshelf.scrobble.ShelfHit
import app.recordshelf.scrobble.PlanTrack
import app.recordshelf.scrobble.Planner
import app.recordshelf.scrobble.QueuedScrobble
import app.recordshelf.scrobble.ScrobbleQueue
import app.recordshelf.scrobble.Spin
import app.recordshelf.scrobble.SpinItem
import app.recordshelf.scrobble.When
import app.recordshelf.tidal.Tidal
import app.recordshelf.tidal.TidalClient
import app.recordshelf.tidal.TidalException
import app.recordshelf.util.JsonFile
import app.recordshelf.util.Palette
import app.recordshelf.util.Text
import app.recordshelf.util.finiteJson
import app.recordshelf.util.objects
import app.recordshelf.util.strings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors

/**
 * The app's state and work, independent of the screen: Discogs collection, release lookups,
 * the record that is spinning, the scrobble queue and the Last.fm connection. The UI talks
 * to it through [handle] and gets updates through [Ui.push]; alarms, notification buttons
 * and the background flush job call in directly.
 *
 * Threads: collection syncs on one executor, release lookups on another, and everything
 * that touches the spin session or the queue on a third, so those steps never interleave.
 */
@SuppressLint("StaticFieldLeak")
object Core {
    interface Ui {
        fun push(fn: String, json: String)
        fun openUrl(url: String)

        /** Lets the user pick where to save a backup ([save], suggesting [name]) or which one to restore. */
        fun pickBackup(save: Boolean, name: String)

        /** Lets the user pick TIDAL's streaming.csv (from its data export). */
        fun pickTidal()
    }

    private const val HOUR = 3_600_000L
    private const val SIDE_LINGER = 15 * 60_000L
    private const val END_LINGER = 3 * 60_000L

    /** A record you might be playing (a song of it heard around you) is asked about once in this time. */
    private const val OFFER_AGAIN_MS = 3 * HOUR

    /** How long Identify listens. */
    private const val IDENTIFY_MS = 10_000L

    /** A genre page not built by then reports where it got stuck. */
    private const val GENRE_WAIT_SEC = 20L

    /** Last.fm takes scrobbles up to two weeks late (a phone that was offline, a record logged afterwards). */
    private const val LATE_SEC = 14 * 24 * 3600L
    private const val HIDDEN_FILE = "hidden.json"

    /** What you said about artists the history can't tell: known before it starts, or found at their first play. */
    private const val KNOWN_FILE = "known.json"
    private const val DISCO_FILE = "discographies.json"
    private const val YEARS_FILE = "album_years.json"
    private const val GLOBALS_FILE = "global_counts.json"
    private const val COUNTRIES_FILE = "artist_countries.json"

    /** Discographies are looked up again after a week, so new releases show up. */
    private const val DISCO_TTL = 7L * 24 * HOUR
    private const val DISCO_MAX = 150

    /** Album covers looked up for the activity list at a time (newest first). */
    private const val COVER_BATCH = 40

    /** Covers whose colors are kept (and handed to the page when it starts). */
    private const val PALETTES_KEPT = 1500

    /** Covers looked up for the binder's cards before the page hears of them and other lookups get a turn. */
    private const val CARD_COVER_LOT = 12

    /** Counts the ways covers are looked up: one more, and the covers none was found for are asked for again. */
    private const val COVER_LOOKUPS = 2

    /** After the Cover Art Archive failed to answer, it isn't asked again for this long (ms). */
    private const val ARCHIVE_PAUSE = 15 * 60_000L

    /** Albums played this many days back have a missing cover asked again after a day (see retryFreshMisses). */
    private const val FRESH_MISS_DAYS = 14L

    private lateinit var ctx: Context
    lateinit var prefs: Prefs
        private set
    private lateinit var files: File
    private lateinit var queue: ScrobbleQueue
    private lateinit var activity: ActivityLog
    private val main = Handler(Looper.getMainLooper())

    /**
     * One background thread for one kind of work. A task that throws (a full disk, an answer that
     * makes no sense) ends there: it doesn't take the app down, and phone scrobbling with it.
     */
    private fun serial(): java.util.concurrent.Executor {
        val e = Executors.newSingleThreadExecutor()
        return java.util.concurrent.Executor { r ->
            e.execute {
                try {
                    r.run()
                } catch (t: Throwable) {
                    failed(t)
                }
            }
        }
    }

    @Volatile private var lastFailure = 0L

    /** Said at most once a minute, so a full disk doesn't bring a toast with every scrobble. */
    private fun failed(t: Throwable) {
        val now = System.currentTimeMillis()
        if (now - lastFailure < 60_000) return
        lastFailure = now
        toast("Something went wrong in the background (${t.message ?: t.javaClass.simpleName})")
    }
    private val syncExec = serial()

    /**
     * Genre pages, on their own line so they don't wait behind the rest of Stats, each built on a worker that is
     * watched: a page that fails or hangs says so (where it hung, too) instead of loading for good.
     */
    private val genreExec = serial()
    private val genreWorkers = Executors.newCachedThreadPool()
    private fun watchedGenrePage(key: String): JSONObject {
        val worker = java.util.concurrent.atomic.AtomicReference<Thread>()
        val job = genreWorkers.submit<JSONObject> {
            worker.set(Thread.currentThread())
            currentStats()?.genrePage(key) ?: JSONObject().put("missing", true)
        }
        return try {
            job.get(GENRE_WAIT_SEC, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: java.util.concurrent.TimeoutException) {
            val at = worker.get()?.stackTrace.orEmpty().filter { it.className.startsWith("app.recordshelf") }.take(6)
                .joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
            job.cancel(true)
            android.util.Log.w("HeavyRotation", "genre page $key hung at $at")
            JSONObject().put("error", "Took longer than $GENRE_WAIT_SEC s, at $at")
        } catch (e: java.util.concurrent.ExecutionException) {
            val c = e.cause ?: e
            android.util.Log.w("HeavyRotation", "genre page $key", c)
            val at = c.stackTrace.filter { it.className.startsWith("app.recordshelf") }.take(4).joinToString(" < ") { "${it.methodName}:${it.lineNumber}" }
            JSONObject().put("error", "${c.javaClass.simpleName}: ${c.message.orEmpty()} at $at")
        }
    }
    private val lookupExec = serial()
    private val scrobExec = serial()

    @Volatile
    private var ready = false

    @Volatile
    private var ui: Ui? = null

    @Volatile
    private var collection: List<CollectionItem> = emptyList()

    /** Last.fm all-time plays per album key ([Text.albumKey]), for the collection. */
    @Volatile
    private var plays: Map<String, Int> = emptyMap()

    /** Times each release was scrobbled from this app. */
    @Volatile
    private var spinCounts: Map<String, Int> = emptyMap()

    /** Only touched on [scrobExec]; [spinUi] is the copy other threads read. */
    private var spin: Spin? = null

    @Volatile
    private var spinUi: String? = null

    @Volatile private var syncing = false
    @Volatile private var syncDone = 0
    @Volatile private var syncPage = 0
    @Volatile private var syncPages = 0
    @Volatile private var syncError = ""
    @Volatile private var lfBusy = false
    @Volatile private var lfError = ""
    @Volatile private var flushError = ""
    @Volatile private var needsAuth = false
    @Volatile private var comparing = false
    @Volatile private var compareError = ""

    /** The last shadow-mode comparison (see [compareInner]), as JSON. */
    @Volatile private var compareJson: String? = null

    // ---- history and stats ----
    /** Network work for the history: import, check, tags, covers. */
    private val historyExec = serial()

    /**
     * Fetching new scrobbles ([refreshHistoryInner]), on a line of its own: behind covers, counts or
     * release years it waited minutes.
     */
    private val refreshExec = serial()

    /** The history database looked over at the start (on [historyExec]); a refresh waits for that. */
    private val historyReady = java.util.concurrent.CountDownLatch(1)

    /** When new scrobbles were last asked for, and whether a fetch is waiting to start ([refreshHistorySoon]). */
    @Volatile private var refreshTried = 0L
    private val refreshQueued = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Computing stats, so a long import doesn't hold them up. */
    private val statsExec = serial()
    private val db by lazy { HistoryDb(ctx) }

    @Volatile private var history: History? = null

    /** Built from [history] with the current collection, tags and covers; null when any of those changed. */
    @Volatile private var stats: Stats? = null

    /** Counts changes to what the stats are built from: a build that started before one isn't kept. */
    @Volatile private var statsGen = 0

    /** The day [stats] were built on: "today", this week and the running periods move on at midnight. */
    @Volatile private var statsDay = 0L

    /** Counts the page's requests for the stats; one overtaken by a newer one is skipped. */
    @Volatile private var statsAsks = 0

    /** A stats update came while the app was in the background; it is worked out when the page is back. */
    @Volatile private var statsMissed = false

    /** Whether every record play scrobbled from here is in the history database yet (see [vinylListens]). */
    @Volatile private var vinylSeeded = false
    @Volatile private var statsRange = ""
    @Volatile private var histCount = -1
    @Volatile private var importing = false
    @Volatile private var cancelImport = false
    @Volatile private var histSyncing = false
    @Volatile private var histDone = 0
    @Volatile private var histTotal = 0
    @Volatile private var histError = ""
    @Volatile private var tagsDone = 0
    @Volatile private var tagsTotal = 0

    /** What the user keeps out of the stats; loaded on first use. */
    @Volatile private var hiddenCache: Hidden? = null

    // ---- pictures: artist photos (Deezer) and the colors of covers and photos ----
    private val deezer = DeezerClient()
    private val lrclib = app.recordshelf.net.LrclibClient()

    /** Deezer lookups for pages (discographies, tracklists), apart from the history work. */
    private val webExec = serial()

    /**
     * The Stats tab shows one genre's music when set (a genre key; "" for everything), and/or one decade's (e.g. 1970; 0 for all),
     * and/or only the record plays scrobbled here ([statsVinyl]).
     */
    @Volatile private var statsGenre = ""
    @Volatile private var statsDecade = 0
    @Volatile private var statsVinyl = false
    @Volatile private var filteredStats: Stats? = null
    @Volatile private var filteredKey = ""
    @Volatile private var filteredBase: Stats? = null

    // ---- release years per album, for the decade filter ----
    private val albumYears: MutableMap<String, Int> by lazy {
        val m = HashMap<String, Int>()
        try {
            val o = JSONObject(file(YEARS_FILE).read() ?: "{}")
            for (k in o.keys()) m[k] = o.optInt(k)
        } catch (_: Exception) {
        }
        m
    }
    @Volatile private var yearsBusy = false
    @Volatile private var yearsDone = 0
    @Volatile private var yearsTotal = 0
    @Volatile private var yearsTried = 0L

    private fun yearsSnapshot(): Map<String, Int> = synchronized(albumYears) { HashMap(albumYears) }

    private fun saveYears() = synchronized(albumYears) { file(YEARS_FILE).write(JSONObject(albumYears as Map<*, *>).toString()) }
    // ---- where your artists are from, for the world map: MusicBrainz, kept on the phone ----

    /** Artist (Text.norm) to ISO 3166 code; "" when MusicBrainz knows no country for them. */
    private val countries: MutableMap<String, String> by lazy {
        val m = HashMap<String, String>()
        try {
            val o = JSONObject(file(COUNTRIES_FILE).read() ?: "{}")
            for (k in o.keys()) m[k] = o.optString(k)
        } catch (_: Exception) {
        }
        m
    }
    private val countryExec = serial()
    @Volatile private var countriesBusy = false

    private fun saveCountries() = synchronized(countries) { file(COUNTRIES_FILE).write(JSONObject(countries as Map<*, *>).toString()) }

    /** Your top artists by country: plays and the most played of them each, and how many are looked up yet. */
    /** Where the artists of [top] (names and plays) are from, as far as known: plays, artists and the top ones per country. */
    private fun worldMapJson(top: List<Pair<String, Int>>): JSONObject {
        val known = synchronized(countries) { HashMap(countries) }
        val by = HashMap<String, MutableList<Pair<String, Int>>>()
        var looked = 0
        for ((name, plays) in top) {
            val c = known[Text.norm(name)] ?: continue
            looked++
            if (c.isNotEmpty()) by.getOrPut(c) { ArrayList() } += name to plays
        }
        val o = JSONObject()
        for ((c, l) in by) {
            o.put(c, JSONObject().put("plays", l.sumOf { it.second }).put("n", l.size)
                .put("artists", JSONArray(l.sortedByDescending { it.second }.take(8).map { JSONObject().put("name", it.first).put("plays", it.second) })))
        }
        return JSONObject().put("total", top.size).put("looked", looked).put("busy", countriesBusy).put("countries", o)
            .put("plays", top.sumOf { it.second }).put("placed", by.values.sumOf { l -> l.sumOf { it.second } })
    }

    private fun pushWorldMap() = statsExec.execute {
        currentStats()?.let { s -> push("onWorldMap") { worldMapJson(s.mapArtists()) } }
        // The period on Stats has its map too.
        shownStats()?.let { s -> val id = statsRange; push("onStatsMap") { worldMapJson(s.mapArtistsIn(s.range(id))).put("range", id) } }
    }

    /** Looks up where [names] are from, one a second (MusicBrainz's pace), the map growing every ten; stops when offline. */
    private fun lookUpCountries(names: List<String>) {
        if (countriesBusy || names.isEmpty()) return
        countriesBusy = true
        countryExec.execute {
            var fails = 0
            try {
                val mb = MusicBrainzClient()
                for ((k, name) in names.withIndex()) {
                    val c = try {
                        mb.artistCountry(name).also { fails = 0 }
                    } catch (_: java.io.IOException) {
                        if (++fails >= 3) break
                        continue
                    }
                    synchronized(countries) { countries[Text.norm(name)] = c }
                    if (k % 10 == 9) {
                        saveCountries()
                        pushWorldMap()
                    }
                }
            } finally {
                countriesBusy = false
                saveCountries()
                pushWorldMap()
            }
        }
    }

    // ---- you against everyone: Last.fm's all-time counts, kept on the phone ----
    private val globalsStore: Pair<MutableMap<String, Global>, MutableMap<String, GlobalTop>> by lazy {
        val g = Globals.fromJson(file(GLOBALS_FILE).read())
        HashMap(g.counts) to HashMap(g.tops)
    }
    @Volatile private var globalsBusy = false
    @Volatile private var globalsDone = 0
    @Volatile private var globalsTotal = 0
    @Volatile private var globalsTried = 0L

    /** Counts lookups in a row that failed (offline, Last.fm busy): each waits longer before the next try. */
    @Volatile private var globalsFails = 0

    /** Whether the counts may be looked up now: not running, and a minute after the last try (doubling after each failure, up to 6 hours). */
    private fun globalsDue() = !globalsBusy && System.currentTimeMillis() - globalsTried > minOf(6 * HOUR, 60_000L shl minOf(globalsFails, 9))

    private fun globalsSnapshot(): Globals = synchronized(globalsStore) { Globals(HashMap(globalsStore.first), HashMap(globalsStore.second)) }

    private fun saveGlobals() = file(GLOBALS_FILE).write(globalsSnapshot().toJson().toString())

    /** Looks one thing up on Last.fm and keeps it; things Last.fm doesn't know are kept too, so they aren't asked again. */
    private fun lookupGlobal(c: LastfmClient, ask: GlobalAsk) {
        val now = System.currentTimeMillis()
        if (ask.kind == GlobalAsk.Kind.TOP) {
            val top = c.artistTopTracks(ask.artist)
            synchronized(globalsStore) { globalsStore.second[Globals.artistKey(ask.artist)] = GlobalTop(top, now) }
            return
        }
        val (key, n) = when (ask.kind) {
            GlobalAsk.Kind.ARTIST -> Globals.artistKey(ask.artist) to c.artistCounts(ask.artist)
            GlobalAsk.Kind.TRACK -> Globals.trackKey(ask.artist, ask.title) to c.trackCounts(ask.artist, ask.title)
            else -> Globals.albumKey(ask.artist, ask.title) to c.albumCounts(ask.artist, ask.title)
        }
        synchronized(globalsStore) { globalsStore.first[key] = Global(n?.listeners ?: 0, n?.playcount ?: 0, now) }
    }

    /** Last.fm's counts for your most played artists and songs, a few lookups a second. On [historyExec]. */
    private fun globalsInner() {
        if (globalsBusy) return
        val c = lastfm() ?: return
        val s = currentStats() ?: return
        globalsTried = System.currentTimeMillis()
        val need = s.globalsNeeded(globalsTried)
        if (need.isEmpty()) return
        globalsBusy = true
        globalsDone = 0
        globalsTotal = need.size
        pushState()
        try {
            for (ask in need) {
                lookupGlobal(c, ask)
                globalsDone++
                if (globalsDone % 25 == 0) {
                    saveGlobals()
                    pushState()
                }
                Thread.sleep(250) // Last.fm asks for at most 5 requests a second
            }
            globalsFails = 0
        } catch (_: Exception) {
            // Offline or Last.fm busy: the rest next time, a while later.
            globalsFails++
        } finally {
            saveGlobals()
            globalsBusy = false
            refreshPictures()
            pushState()
        }
    }

    /** Hands the stats the covers, photos and Last.fm counts looked up so far, without working anything else out again. */
    private fun refreshPictures() {
        val images = db.images()
        val g = globalsSnapshot()
        val own = prefs.ownCovers
        stats?.withPictures(images, g, own)
        filteredStats?.withPictures(images, g, own)
    }

    /**
     * After covers or photos arrived: a page that asked for them ([after]) is shown again; lookups
     * the Stats tab started bring the Stats tab up to date.
     */
    private fun picturesArrived(after: (() -> Unit)?) {
        refreshPictures()
        statsExec.execute { if (after != null) after() else statsInner() }
    }

    /** The lookups for one page (an artist, song or album beyond the most played), then that page again. On [webExec]. */
    private fun globalsFor(asks: List<GlobalAsk>, msg: JSONObject) {
        val c = lastfm() ?: return
        if (asks.isEmpty()) return
        webExec.execute {
            try {
                for (a in asks) lookupGlobal(c, a)
            } catch (_: Exception) {
                return@execute
            }
            saveGlobals()
            refreshPictures()
            handle(msg)
        }
    }

    /** Does the page for [j] (an artist, song or album page) still need Last.fm's counts? */
    private fun pageAsks(kind: GlobalAsk.Kind, j: JSONObject): List<GlobalAsk> {
        if (j.optBoolean("missing")) return emptyList()
        val now = System.currentTimeMillis()
        val g = globalsSnapshot()
        val out = ArrayList<GlobalAsk>()
        when (kind) {
            GlobalAsk.Kind.ARTIST -> {
                val n = j.optString("name")
                if (!g.fresh(Globals.artistKey(n), now)) out += GlobalAsk(GlobalAsk.Kind.ARTIST, n)
                if (!g.freshTop(n, now)) out += GlobalAsk(GlobalAsk.Kind.TOP, n)
            }
            GlobalAsk.Kind.TRACK -> {
                val a = j.optString("artist")
                val t = j.optString("title")
                if (!g.fresh(Globals.trackKey(a, t), now)) out += GlobalAsk(GlobalAsk.Kind.TRACK, a, t)
                if (!g.freshTop(a, now)) out += GlobalAsk(GlobalAsk.Kind.TOP, a)
            }
            else -> {
                val a = j.optString("artist")
                val t = j.optString("title")
                if (!g.fresh(Globals.albumKey(a, t), now)) out += GlobalAsk(GlobalAsk.Kind.ALBUM, a, t)
            }
        }
        return out
    }

    private val paletteExec = serial()

    /** Colors per picture URL, kept across starts (covers don't change). */
    private val palettes: MutableMap<String, JSONObject> by lazy {
        val m = LinkedHashMap<String, JSONObject>()
        try {
            val o = JSONObject(file("palettes.json").read() ?: "{}")
            for (k in o.keys()) m[k] = o.getJSONObject(k)
        } catch (_: Exception) {
        }
        m
    }

    // ---- TIDAL: exact links and playlists with the user's own TIDAL app ----
    private val tidalExec = serial()
    private val tidal by lazy { Tidal(prefs, file("tidal_links.json"), { java.util.Locale.getDefault().country.ifEmpty { "US" } }) }
    @Volatile private var tidalBusy = false
    @Volatile private var tidalError = ""

    /** The tidy-up lists for [tidyFor] (see [tidy]). */
    @Volatile private var tidyJson: JSONObject? = null
    @Volatile private var tidyFor: History? = null

    private fun file(name: String) = JsonFile(File(files, name))
    private val spinFile get() = file("spin.json")

    fun ensure(context: Context) {
        if (ready) return
        synchronized(this) {
            if (ready) return
            ctx = context.applicationContext
            prefs = Prefs(ctx)
            files = ctx.filesDir
            queue = ScrobbleQueue(file("queue.json"))
            activity = ActivityLog(file("activity.json"))
            collection = CollectionItem.listFromJson(file("collection.json").read())
            plays = readCounts("plays.json")
            spinCounts = readCounts("spins.json")
            spin = spinFile.read()?.let {
                try {
                    Spin.fromJson(JSONObject(it))
                } catch (_: Exception) {
                    null
                }
            }
            spinUi = spin?.toUiJson()?.toString()
            compareJson = file("compare.json").read()
            Phone.refresh(ctx)
            ready = true
            historyExec.execute {
                try {
                    // Covers once fetched from Last.fm go (its terms leave artwork out); Deezer's come instead.
                    db.dropImagesFrom("lastfm")
                    // Covers and photos none was found for: tried again after a month (Deezer adds and renames).
                    db.dropMissedImages(System.currentTimeMillis() - 30 * 24 * HOUR)
                    // Lookups used to take a same-named single's cover for the album; those come again as pages need them.
                    if (!prefs.coversRechecked) {
                        db.dropImagesFrom("dzcdn")
                        prefs.coversRechecked = true
                    }
                    // Lookups began to try the first artist of a credit naming several ("D'Angelo & The Vanguard"):
                    // the pictures and discographies none was found for come again as pages need them.
                    // Lookups began to ask Deezer in more ways, to take a song's cover where its album isn't listed,
                    // and to ask the Cover Art Archive: covers none was found for are asked for again.
                    if (prefs.coverLookups < COVER_LOOKUPS) {
                        db.dropMissedImages(Long.MAX_VALUE)
                        prefs.coverLookups = COVER_LOOKUPS
                    }
                    if (!prefs.creditsRechecked) {
                        db.dropMissedImages(Long.MAX_VALUE)
                        runCatching {
                            synchronized(discoLock) {
                                val cache = JSONObject(file(DISCO_FILE).read() ?: "{}")
                                cache.keys().asSequence().toList().filter { !cache.getJSONObject(it).has("artist") }.forEach { cache.remove(it) }
                                file(DISCO_FILE).write(cache.toString())
                            }
                        }
                        prefs.creditsRechecked = true
                    }
                    // The usual covers of records on the shelf not looked up yet (after this, so the database is ready).
                    lookUpShelfCovers()
                    histCount = db.count()
                    // Settings back without their history (Android's cloud backup leaves the database out): import again.
                    if (histCount == 0 && (prefs.historyDone || prefs.historyCursor > 0)) {
                        prefs.historyDone = false
                        prefs.historyCursor = 0
                        prefs.refreshCursor = 0
                        prefs.historySyncedTo = 0
                    }
                    pushState()
                } finally {
                    historyReady.countDown()
                }
            }
        }
    }

    fun attachUi(u: Ui) {
        ui = u
        // What came while the app was in the background: the latest of each, then the stats if they changed.
        val back = synchronized(missed) { LinkedHashMap(missed).also { missed.clear() } }
        for ((fn, s) in back) main.post { ui?.push(fn, s) }
        if (statsMissed) {
            statsMissed = false
            statsExec.execute { statsInner() }
        }
    }

    fun detachUi(u: Ui) {
        if (ui === u) ui = null
        // Identify listens only with the app in front: gone, it stops, and nothing is sent to be looked up.
        identifyStop = true
    }

    /** The app came to the front: finish a pending Last.fm approval, catch up the spin, send what waits. */
    fun onResume() {
        Phone.refresh(ctx)
        if (Phone.access && !Phone.connected && Phone.mode() != Phone.OFF) {
            // Access is granted but the system hasn't (re)bound the listener: ask it to.
            try {
                android.service.notification.NotificationListenerService.requestRebind(PhoneListener.component(ctx))
            } catch (_: Exception) {
            }
        }
        pushState()
        if (prefs.lastfmPendingToken.isNotEmpty()) checkLastfm(manual = false)
        scrobExec.execute {
            tickInner()
            if (queue.size() > 0) flushInner()
        }
        // Discogs asks that what it gave isn't shown more than 6 hours older than on discogs.com: the shelf syncs again.
        if (prefs.discogsUser.isNotEmpty() && !syncing && System.currentTimeMillis() - prefs.lastSync > 6 * HOUR) sync()
        if (prefs.lastfmSession.isNotEmpty() && collection.isNotEmpty() && System.currentTimeMillis() - prefs.playsUpdated > 12 * HOUR) {
            syncExec.execute { refreshPlaysInner() }
        }
        // An import that was interrupted continues; a finished one picks up new scrobbles.
        if (prefs.historyCursor > 0 && !importing && !prefs.importPaused) historyExec.execute { importInner() }
        else refreshHistorySoon()
        // Once a week the whole history is counted against Last.fm, and a year that comes up short fetched again.
        if (prefs.historyDone && !checking && System.currentTimeMillis() - prefs.historyChecked > 7 * 24 * HOUR) {
            historyExec.execute { checkHistoryInner(repair = true, quiet = true) }
        }
    }

    /** Messages from the page (see app.js). Main thread. */
    fun handle(msg: JSONObject) {
        when (msg.optString("t")) {
            "hello" -> {
                Phone.refresh(ctx)
                pushAll()
                // The colors of the covers seen so far, all at once: a page then opens in its cover's colors. Asked for
                // one by one as pages opened, each came a moment after its page, which changed color before your eyes.
                paletteExec.execute {
                    val all = synchronized(palettes) { JSONObject(palettes as Map<*, *>).toString() }
                    push("onPalettes") { JSONObject(all) }
                }
            }
            "setPhoneMode" -> {
                val mode = msg.optString("mode").takeIf { it in listOf(Phone.OFF, Phone.SHADOW, Phone.ON) } ?: return
                if (mode == Phone.SHADOW && prefs.phoneMode != Phone.SHADOW) prefs.shadowSince = System.currentTimeMillis()
                prefs.phoneMode = mode
                PhoneListener.instance?.reset()
                pushState()
            }
            "setPhoneApp" -> {
                Phone.setEnabled(ctx, msg.optString("pkg"), msg.optBoolean("on"))
                pushState()
            }
            "fixPreset" -> {
                val id = msg.optString("id")
                prefs.fixPresets = if (msg.optBoolean("on")) prefs.fixPresets + id else prefs.fixPresets - id
                pushState()
            }
            "fixSave" -> {
                val r = FixRules.Rule.fromJson(msg.optJSONObject("rule") ?: JSONObject())
                if (r.find.isNotEmpty()) {
                    val rules = FixRules.parse(prefs.fixRules)
                    val rule = if (r.id.isEmpty()) r.copy(id = UUID.randomUUID().toString()) else r
                    prefs.fixRules = FixRules.toJson(if (rules.any { it.id == rule.id }) rules.map { if (it.id == rule.id) rule else it } else rules + rule)
                }
                pushState()
            }
            "fixDelete" -> {
                prefs.fixRules = FixRules.toJson(FixRules.parse(prefs.fixRules).filter { it.id != msg.optString("id") })
                pushState()
            }
            "comparePano" -> syncExec.execute { compareInner() }
            "historyImport" -> {
                prefs.importPaused = false
                historyExec.execute { importInner() }
            }
            "historyCheck" -> historyExec.execute { checkHistoryInner(msg.optBoolean("repair")) }
            "sleep" -> when {
                msg.optBoolean("cancel") -> SleepTimer.cancel(ctx)
                msg.optBoolean("song") -> if (!SleepTimer.startEndOfSong(ctx)) toast("The app playing doesn't say how long the song is")
                else -> SleepTimer.start(ctx, msg.optInt("minutes", 30))
            }
            "historyCancel" -> {
                // Paused stays paused, also when the app comes back to the front.
                cancelImport = true
                prefs.importPaused = true
            }
            "historyReset" -> historyExec.execute {
                db.clear()
                prefs.historyCursor = 0
                prefs.historyDone = false
                prefs.refreshCursor = 0
                prefs.historySyncedTo = 0
                prefs.importPaused = false
                prefs.historyLastSync = 0
                history = null
                dropStats()
                histCount = 0
                pushState()
                push("onStats") { JSONObject().put("empty", true) }
                importInner()
            }
            "stats" -> {
                statsRange = msg.optString("range").ifEmpty { statsRange }
                if (msg.has("genre")) statsGenre = msg.optString("genre")
                if (msg.has("decade")) statsDecade = msg.optInt("decade")
                if (msg.has("vinyl")) statsVinyl = msg.optBoolean("vinyl")
                // Stepping through periods quickly: only the last one asked for is worked out.
                val ask = ++statsAsks
                statsExec.execute { if (ask == statsAsks) statsInner() }
                refreshHistorySoon()
            }
            "artist" -> {
                val name = msg.optString("name")
                val id = msg.optString("range").ifEmpty { statsRange }
                statsExec.execute {
                    val s = currentStats()
                    val j = s?.artist(name, s.range(id)) ?: JSONObject().put("name", name).put("missing", true)
                    if (j.has("missing")) {
                        j.put("photo", s?.artistPhoto(name).orEmpty())
                        val best = topsOf(null, name, lookUp = false)
                        if (best != null) j.put("best", JSONArray(best.take(10)))
                        // Not looked up yet: once, then the page again.
                        else if (lastfm() != null && topsAsked.add(Text.norm(name))) discoverExec.execute { if (topsOf(lastfm(), name) != null) { saveDiscover(); handle(msg) } }
                    }
                    concertsOf(j.optString("name").ifEmpty { name }, s).takeIf { it.length() > 0 }?.let { j.put("concerts", it) }
                    push("onArtist") { j.put("req", msg.optInt("req")) }
                    // No photo yet: Deezer may have one; the page comes again if so.
                    val n = j.optString("name")
                    if (j.optString("photo").isEmpty() && Stats.photoKey(n) !in db.images().keys) historyExec.execute { fetchPhotosInner(listOf(n)) { handle(msg) } }
                    // Covers of the albums their songs are on, for the top tracks and the song cards.
                    val covers = missingCovers(j, db.images().keys, n)
                    if (covers.isNotEmpty()) historyExec.execute { fetchImagesInner(covers) { handle(msg) } }
                    // Everyone's counts on Last.fm, for "you vs everyone"; the page comes again.
                    globalsFor(pageAsks(GlobalAsk.Kind.ARTIST, j), msg)
                    // No genres (tags are looked up ahead only for your most played): Last.fm's tags for them now,
                    // weighed with your records of theirs like everywhere else.
                    val c = lastfm()
                    if (c != null && !j.has("missing") && j.optJSONArray("genres")?.length() == 0 && tagsAsked.add(Genres.artistKey(n))) {
                        historyExec.execute {
                            try {
                                val tags = Genres.fromTags(c.artistTopTags(n), n)
                                db.saveTags(Genres.artistKey(n), tags)
                                val families = Genres(collection, mapOf(Genres.artistKey(n) to tags)).of(n, null).first
                                if (families.isNotEmpty()) push("onArtist") { JSONObject(j.toString()).put("genres", JSONArray(families.map { Genres.label(it) })) }
                            } catch (_: Exception) {
                                tagsAsked.remove(Genres.artistKey(n)) // offline: next time
                            }
                        }
                    }
                }
            }
            "cards" -> statsExec.execute {
                val s = currentStats() ?: return@execute
                val j = s.cards()
                val req = msg.optInt("req")
                cardsReq = req
                push("onCards") { j.put("req", req) }
                // Covers for cards of albums nothing else looked up follow a few at a time, each put on its cards in
                // place: a long history has thousands of cards, and the whole binder again for every lot of covers
                // held the page up every few seconds.
                val covers = missingCovers(j, db.images().keys, max = Int.MAX_VALUE)
                if (covers.isNotEmpty()) historyExec.execute { cardCoversInner(covers, req) }
            }
            "rankList" -> {
                val id = msg.optString("range").ifEmpty { statsRange }
                statsExec.execute {
                    // From the Stats tab, the list counts what the tab shows (its genre, decade or vinyl filter).
                    val s = (if (msg.optBoolean("shown")) shownStats() else null) ?: currentStats() ?: return@execute
                    val j = s.rankList(
                        s.range(id), msg.optString("kind"), maxOf(0, msg.optInt("from")), msg.optInt("n", 100).coerceIn(1, 500),
                        msg.optString("artist"), msg.optString("title"), msg.optBoolean("around"),
                    )
                    push("onRankList") { j.put("req", msg.optInt("req")) }
                }
            }
            "track", "album" -> {
                val isTrack = msg.optString("t") == "track"
                val artist = msg.optString("artist")
                val title = msg.optString("title")
                val id = msg.optString("range").ifEmpty { statsRange }
                statsExec.execute {
                    val s = currentStats()
                    val r = s?.range(id)
                    val j = (if (r == null) null else if (isTrack) s.track(artist, title, r) else s.album(artist, title, r))
                        ?: JSONObject().put("missing", true).put("artist", artist).put("title", title)
                    j.put("req", msg.optInt("req"))
                    push(if (isTrack) "onTrack" else "onAlbum") { j }
                    if (!isTrack && !j.has("missing")) albumTagsFor(j)
                    globalsFor(pageAsks(if (isTrack) GlobalAsk.Kind.TRACK else GlobalAsk.Kind.ALBUM, j), msg)
                    // No cover yet: Last.fm may have one; the page comes again if so.
                    val info = j.optJSONObject(if (isTrack) "album" else "info")
                    if (info != null && info.optString("cover").isEmpty()) {
                        historyExec.execute { fetchImagesInner(listOf(Triple(info.getString("artist"), info.getString("title"), info.getString("key")))) { handle(msg) } }
                    }
                }
            }
            "search" -> {
                val q = msg.optString("q")
                statsExec.execute { push("onSearch") { currentStats()?.search(q) ?: JSONObject().put("q", q) } }
            }
            // Albums from Deezer's catalog for what was typed: the ones you never played aren't in your history.
            "searchCatalog" -> {
                val q = msg.optString("q")
                val n = ++catalogAsks
                if (q.trim().length >= 2) webExec.execute {
                    // Typed on since: the newer question is the one to answer.
                    if (n != catalogAsks) return@execute
                    val found = try {
                        deezer.searchAlbums(q.trim())
                    } catch (_: Exception) {
                        null
                    }
                    push("onSearchCatalog") { JSONObject().put("q", q).put("albums", JSONArray(found.orEmpty().map { it.toJson() })).put("error", found == null) }
                }
            }
            "explore" -> statsExec.execute {
                concertsMaybeDue()
                val s = currentStats()
                push("onExplore") { s?.explore() ?: JSONObject().put("empty", true) }
                val missing = s?.exploreNeedingImages().orEmpty()
                if (missing.isNotEmpty()) historyExec.execute { fetchImagesInner(missing) { handle(msg) } }
                val photos = s?.exploreNeedingPhotos().orEmpty()
                if (photos.isNotEmpty()) historyExec.execute { fetchPhotosInner(photos) { handle(msg) } }
            }
            "exploreList" -> statsExec.execute {
                val kind = msg.optString("kind")
                val s = currentStats()
                push("onExploreList") { (s?.exploreList(kind) ?: JSONObject().put("kind", kind).put("items", JSONArray())).put("req", msg.optInt("req")) }
                if (s == null) return@execute
                // Pictures not looked up yet: the list comes again once they are.
                val photos = s.listNeedingPhotos(kind).take(60)
                if (photos.isNotEmpty()) historyExec.execute { fetchPhotosInner(photos) { handle(msg) } }
                val covers = s.listNeedingImages(kind).take(60)
                if (covers.isNotEmpty()) historyExec.execute { fetchImagesInner(covers) { handle(msg) } }
            }
            // A record's card on its page (the record page has no stats of its own to carry it).
            "albumCard" -> statsExec.execute {
                val card = if (prefs.historyDone) currentStats()?.albumCard(msg.optString("artist"), msg.optString("title")) else null
                push("onAlbumCard") { (card ?: JSONObject()).put("id", msg.optLong("id")) }
            }
            "badges" -> statsExec.execute {
                val j = if (prefs.historyDone) currentStats()?.shelfBadges() else null
                push("onBadges") { j ?: JSONObject() }
            }
            "shelfCards" -> statsExec.execute {
                val j = if (prefs.historyDone) currentStats()?.shelfCards() else null
                push("onShelfCards") { j ?: JSONObject() }
            }
            "backupExport" -> {
                backupUi = msg.optJSONObject("ui") ?: JSONObject()
                ui?.pickBackup(true, "heavy-rotation-backup-${java.time.LocalDate.now()}.zip")
            }
            "backupImport" -> ui?.pickBackup(false, "")
            "tidalImport" -> ui?.pickTidal()
            "tidalRemove" -> historyExec.execute {
                db.clearImported()
                prefs.tidalImport = ""
                history = if (hasHistory()) History.from(db.everything, hidden()) else null
                dropStats()
                pushState()
                statsExec.execute { statsInner() }
            }
            "atlas" -> statsExec.execute {
                val s = currentStats()
                push("onAtlas") { s?.atlas() ?: JSONObject().put("empty", true) }
            }
            // You say how it was with an artist: known before ("knew"), found at their first play ("found"), or take that back.
            "markFound" -> statsExec.execute {
                val key = Text.norm(msg.optString("name"))
                if (key.isEmpty()) return@execute
                val m = HashMap(known())
                when (msg.optString("v")) {
                    "knew" -> m[key] = true
                    "found" -> m[key] = false
                    else -> m.remove(key)
                }
                knownCache = m
                file(KNOWN_FILE).write(JSONObject(m as Map<*, *>).toString())
                dropStats()
                val s = currentStats()
                push("onAtlas") { s?.atlas() ?: JSONObject().put("empty", true) }
            }
            "worldMap" -> statsExec.execute {
                val s = currentStats()
                if (s == null) {
                    push("onWorldMap") { JSONObject().put("empty", true) }
                    return@execute
                }
                val missing = synchronized(countries) { s.mapArtists().map { it.first }.filter { Text.norm(it) !in countries } }
                lookUpCountries(missing)
                push("onWorldMap") { worldMapJson(s.mapArtists()) }
            }
            "through" -> statsExec.execute {
                val s = currentStats()
                push("onThrough") { s?.throughTheYears() ?: JSONObject().put("empty", true) }
                val photos = s?.throughNeedingPhotos().orEmpty()
                if (photos.isNotEmpty()) historyExec.execute { fetchPhotosInner(photos) { handle(msg) } }
            }
            "vs" -> statsExec.execute {
                val s = currentStats()
                val j = s?.vs() ?: JSONObject().put("empty", true)
                push("onVs") { j.put("lastfm", lastfm() != null) }
                if (s != null) {
                    if (globalsDue()) historyExec.execute { globalsInner() }
                    val photos = s.vsNeedingPhotos(j)
                    if (photos.isNotEmpty()) historyExec.execute { fetchPhotosInner(photos) { handle(msg) } }
                }
            }
            "discover" -> discoverExec.execute {
                push("onDiscover") { discoverJson() }
                discoverInner(force = msg.optBoolean("again"))
                releaseExec.execute { releasesInner() }
                currentStats()?.let { if (discoverDaily(it, lastfm())) push("onDiscover") { discoverJson() } }
            }
            "relNo", "relUndo" -> discoverExec.execute {
                // A series' parts go (and come back) together.
                val titles = msg.optJSONArray("titles")?.strings()?.ifEmpty { null } ?: listOf(msg.optString("title"))
                synchronized(dstore) {
                    for (title in titles) {
                        val k = Text.albumKey(msg.optString("artist"), title)
                        if (msg.optString("t") == "relNo") dpart("relNo").put(k, System.currentTimeMillis()) else dpart("relNo").remove(k)
                    }
                }
                saveDiscover()
                push("onDiscover") { discoverJson() }
            }
            "discoverReach" -> {
                prefs.discoverReach = msg.optInt("v").coerceIn(Discover.CLOSE, Discover.FURTHER)
                discoverExec.execute {
                    push("onDiscover") { discoverJson() }
                    // The best ones now may be new: their genres, songs and photos, then the tab again.
                    val s = currentStats() ?: return@execute
                    discoverExtras(s, lastfm())
                    push("onDiscover") { discoverJson() }
                }
            }
            "discoverNo", "discoverUndo" -> discoverExec.execute {
                if (msg.optString("t") == "discoverNo") discoverRefuse(msg.optString("name")) else discoverUnrefuse(msg.optString("name"))
                push("onDiscover") { discoverJson() }
                pushState()
                currentStats()?.let { discoverExtras(it, lastfm()) }
                push("onDiscover") { discoverJson() }
            }
            "discoverClearNo" -> discoverExec.execute {
                prefs.discoverNo = "{}"
                prefs.discoverWeaker = "{}"
                push("onDiscover") { discoverJson() }
                pushState()
            }
            "discoverOn" -> {
                prefs.discoverOn = msg.optBoolean("v")
                pushState()
                discoverExec.execute {
                    push("onDiscover") { discoverJson() }
                    discoverInner(force = false)
                }
            }
            "discoverWhy" -> discoverExec.execute { discoverWhy(msg) }
            "discoverLike" -> discoverExec.execute { discoverLike(msg) }
            "playlist" -> statsExec.execute {
                val s = currentStats()
                if (msg.optString("kind") == "genre") return@execute genrePlaylist(s, msg)
                if (msg.optString("kind") == "discover") return@execute discoverExec.execute { discoverMix(msg) }
                if (msg.optString("kind") == "artistBest") return@execute discoverExec.execute { artistBest(msg) }
                if (msg.optString("kind") == "newReleases") return@execute webExec.execute { newReleasesMix() }
                if (msg.optString("kind") == "similarSongs") return@execute discoverExec.execute { similarSongsMix() }
                // A period's or an artist's highlights come as long as asked for; the page tells the answers apart by [req].
                val size = msg.optInt("size", Stats.PLAYLIST_SIZE).coerceIn(5, 200)
                push("onPlaylist") {
                    (s?.playlist(msg.optString("kind"), msg.optString("range").ifEmpty { statsRange }, size, msg.optString("artist")) ?: JSONObject().put("empty", true))
                        .put("req", msg.optInt("req"))
                }
            }
            "genre" -> {
                val key = msg.optString("key")
                genreExec.execute {
                    val j = watchedGenrePage(key)
                    push("onGenre") { j.put("key", key).put("req", msg.optInt("req")) }
                    if (j.has("broken")) android.util.Log.w("HeavyRotation", "genre page $key: ${j.optJSONArray("broken")}")
                    // Photos for its top artists, if Deezer has them; the page comes again.
                    val top = j.optJSONObject("top")?.optJSONArray("artists")
                    val need = (0 until (top?.length() ?: 0)).map { top!!.getJSONObject(it) }.filter { it.optString("photo").isEmpty() }.map { it.getString("name") }
                    val tried = db.images().keys
                    val missing = need.filter { Stats.photoKey(it) !in tried }
                    if (missing.isNotEmpty()) historyExec.execute { fetchPhotosInner(missing) { handle(msg) } }
                    // Covers of its albums and songs, also the ones you hardly play that nothing else looked up.
                    val covers = missingCovers(j, tried)
                    if (covers.isNotEmpty()) historyExec.execute { fetchImagesInner(covers) { handle(msg) } }
                }
            }
            "discography" -> {
                val name = msg.optString("name")
                val req = msg.optInt("req")
                if (name.isNotEmpty()) webExec.execute {
                    val j = try {
                        discographyJson(name)
                    } catch (_: Exception) {
                        JSONObject().put("error", "Can't reach Deezer right now. Try again later.")
                    }
                    push("onDiscography") { j.put("name", name).put("req", req) }
                }
            }
            "phoneControl" -> PhoneListener.instance?.control(msg.optString("pkg"), msg.optString("action"), msg.optLong("arg"))
            "nowAlbum" -> {
                // The tracklist of the album a phone app plays, for its Now playing page (kept for the session).
                val artist = msg.optString("artist")
                val album = msg.optString("album")
                val key = Text.albumKey(artist, album)
                webExec.execute {
                    val j = nowAlbums[key] ?: try {
                        (deezer.findAlbum(artist, album)?.toJson() ?: JSONObject().put("missing", true)).also { nowAlbums[key] = it }
                    } catch (_: Exception) {
                        JSONObject().put("error", true)
                    }
                    push("onNowAlbum") { JSONObject(j.toString()).put("q", msg.optString("q")) }
                }
            }
            "lyrics" -> {
                // A song's lyrics for Now playing (kept for the session): timed lines when LRCLIB has them.
                val artist = msg.optString("artist")
                val title = msg.optString("title")
                val key = Text.norm(artist) + "\u0000" + Text.normLoose(title)
                webExec.execute {
                    // Asked twice before it counts as unreachable: LRCLIB is often slow for a moment.
                    fun ask() = (lrclib.find(artist, title, msg.optString("album"), msg.optInt("dur"))?.toJson() ?: JSONObject().put("missing", true)).also { lyrics[key] = it }
                    val j = lyrics[key] ?: try {
                        ask()
                    } catch (_: Exception) {
                        try {
                            Thread.sleep(1500)
                            ask()
                        } catch (_: Exception) {
                            JSONObject().put("error", true)
                        }
                    }
                    push("onLyrics") { JSONObject(j.toString()).put("q", msg.optString("q")) }
                }
            }
            "dzAlbum" -> {
                val id = msg.optLong("id")
                val req = msg.optInt("req")
                if (id > 0) webExec.execute {
                    val j = try {
                        deezer.album(id).toJson()
                    } catch (_: Exception) {
                        JSONObject().put("error", true)
                    }
                    push("onDzAlbum") { j.put("dz", id).put("req", req) }
                }
            }
            "palette" -> {
                val url = msg.optString("url")
                if (url.startsWith("https://")) paletteExec.execute { paletteInner(url) }
            }
            "playlistCovers" -> historyExec.execute { playlistCovers(msg) }
            "tidy" -> statsExec.execute { push("onTidy") { tidy() ?: JSONObject().put("empty", true) } }
            "hide", "unhide" -> {
                // One item, or several in "items" ("Hide all"), so the history is rebuilt once.
                val list = msg.optJSONArray("items")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: listOf(msg)
                val show = msg.optString("t") == "unhide"
                statsExec.execute {
                    var h = hidden()
                    for (x in list) {
                        val artist = x.optString("artist")
                        val title = x.optString("title")
                        val artistWide = x.optString("kind") == "artist"
                        if (artist.isEmpty() || (!artistWide && title.isEmpty())) continue
                        h = when {
                            show && artistWide -> h.showArtist(artist)
                            show -> h.showTrack(artist, title)
                            artistWide -> h.hideArtist(artist)
                            else -> h.hideTrack(artist, title)
                        }
                    }
                    saveHidden(h)
                    rebuildHistory()
                }
            }
            "keep" -> statsExec.execute {
                saveHidden(hidden().keep(msg.optString("artist"), msg.optString("title")))
                tidyJson = null
                push("onTidy") { tidy() ?: JSONObject().put("empty", true) }
                statsInner()
            }
            "story" -> {
                val id = msg.optString("range")
                statsExec.execute {
                    val s = shownStats() ?: return@execute
                    val story = s.story(s.range(id)).put("genre", statsGenre).put("decade", statsDecade).put("onlyVinyl", statsVinyl)
                    currentStats()?.let { all -> story.put("concerts", concertsIn(all, all.range(id))) }
                    push("onStory") { story }
                }
            }
            "saveLastfm" -> {
                prefs.lastfmKey = msg.optString("key").trim()
                prefs.lastfmSecret = msg.optString("secret").trim()
                prefs.lastfmSession = ""
                prefs.lastfmPendingToken = ""
                lfError = ""
                connectLastfm()
            }
            "lastfmConnect" -> connectLastfm()
            "lastfmCheck" -> checkLastfm(manual = true)
            "saveTidal" -> {
                prefs.tidalClientId = msg.optString("clientId").trim()
                prefs.tidalSecret = msg.optString("secret").trim()
                tidal.signOut()
                tidalError = ""
                tidalBusy = true
                pushState()
                tidalExec.execute {
                    try {
                        tidal.checkApp()
                        toast("TIDAL app connected")
                    } catch (e: TidalException) {
                        tidalError = if (e.badClient || e.code == 401) "TIDAL doesn't accept this client ID and secret. Check both." else e.message ?: "TIDAL error ${e.code}"
                    } catch (_: IOException) {
                        tidalError = "Can't reach TIDAL. Check your connection."
                    } finally {
                        tidalBusy = false
                        pushState()
                    }
                }
            }
            "tidalLogin" -> if (tidal.hasApp) {
                tidalError = ""
                val url = tidal.startLogin()
                main.post { ui?.openUrl(url) }
            }
            "tidalRedirect" -> tidalExec.execute {
                tidalBusy = true
                pushState()
                try {
                    tidal.finishLogin(msg.optString("uri"))
                    tidalError = ""
                    toast("Signed in to TIDAL")
                } catch (e: TidalException) {
                    tidalError = "TIDAL sign-in didn't work: ${e.message}"
                } catch (_: IOException) {
                    tidalError = "Can't reach TIDAL. Check your connection and sign in again."
                } finally {
                    tidalBusy = false
                    pushState()
                }
            }
            "tidalSignOut" -> {
                tidal.signOut()
                pushState()
            }
            "tidalForget" -> {
                tidal.forget()
                tidalError = ""
                pushState()
            }
            "tidalOpen" -> tidalExec.execute {
                // The exact song, album or artist; TIDAL's search when there's no clear match.
                val hit = try {
                    tidal.find(msg.optString("kind"), msg.optString("artist"), msg.optString("title"))
                } catch (_: Exception) {
                    null
                }
                if (hit == null) toast("TIDAL has no exact match; opening a search instead")
                val url = hit?.link ?: msg.optString("fallback")
                main.post { ui?.openUrl(url) }
            }
            "tidalPlaylist" -> {
                val arr = msg.optJSONArray("tracks") ?: return
                val tracks = (0 until arr.length()).map { arr.getJSONObject(it) }.map { it.optString("artist") to it.optString("title") }
                val name = msg.optString("name")
                val note = msg.optString("note")
                tidalExec.execute { tidalPlaylistInner(name, note, tracks) }
            }
            "lastfmDisconnect" -> {
                prefs.lastfmSession = ""
                prefs.lastfmPendingToken = ""
                prefs.lastfmUser = ""
                needsAuth = false
                lfError = ""
                pushState()
            }
            "lastfmForget" -> {
                prefs.lastfmKey = ""
                prefs.lastfmSecret = ""
                handle(JSONObject().put("t", "lastfmDisconnect"))
            }
            "setlists" -> setlistsFor(msg.optString("name"), msg.optInt("req"))
            "saveSetlist" -> {
                prefs.setlistKey = msg.optString("key").trim()
                setlistCache.clear()
                setlistfm = null
                pushState()
            }
            "setlistForget" -> {
                prefs.setlistKey = ""
                setlistCache.clear()
                setlistfm = null
                pushState()
            }
            "saveSetlistUser" -> {
                val user = msg.optString("user").trim().removePrefix("@")
                if (user != prefs.setlistUser) {
                    prefs.setlistUser = user
                    prefs.concertsAt = 0
                    concertList = emptyList()
                    file("concerts.json").file.delete()
                }
                concertsError = ""
                pushState()
                if (user.isNotEmpty()) concertExec.execute { fetchConcertsInner() }
            }
            "concertsRefresh" -> concertExec.execute { fetchConcertsInner() }
            "coverChoice" -> statsExec.execute {
                // A record shows your copy's own picture from now on, or the album's usual cover again.
                val id = msg.optLong("id")
                prefs.ownCovers = if (msg.optBoolean("own")) prefs.ownCovers + id else prefs.ownCovers - id
                pushCollection()
                picturesArrived(null)
            }
            "concerts" -> {
                statsExec.execute {
                    push("onConcerts") { concertsPageJson() }
                    // Photos of the artists you saw that no page has looked up yet; the page comes again with them.
                    val known = db.images().keys
                    val photos = concerts().map { it.artist }.distinctBy { Text.norm(it) }.filter { Stats.photoKey(it) !in known }
                    if (photos.isNotEmpty()) historyExec.execute { fetchPhotosInner(photos) { handle(msg) } }
                }
                concertsMaybeDue(CONCERTS_PAGE_EVERY)
            }
            "saveDiscogs" -> connectDiscogs(msg.optString("token").trim(), msg.optString("user").trim().removePrefix("@"))
            "discogsDisconnect" -> syncExec.execute {
                prefs.discogsToken = ""
                prefs.discogsUser = ""
                prefs.lastSync = 0
                collection = emptyList()
                file("collection.json").delete()
                syncError = ""
                pushState()
                pushCollection()
            }
            "sync" -> sync()
            "openRelease" -> openRelease(msg.optLong("id"), msg.optBoolean("refresh"))
            "startSpin" -> {
                val a = msg.optJSONArray("tracks") ?: JSONArray()
                val idx = (0 until a.length()).map { a.optInt(it) }
                val mode = when (msg.optString("when")) {
                    "finished" -> When.FINISHED
                    "at" -> When.AT
                    else -> When.NOW
                }
                val id = msg.optLong("releaseId")
                val at = msg.optLong("at")
                scrobExec.execute { startSpinInner(id, idx, mode, at) }
            }
            "spinStop" -> scrobExec.execute { stopInner() }
            "spinFlip" -> scrobExec.execute { flipInner() }
            "spinResync" -> {
                val i = msg.optInt("i", -1)
                // From a line of the lyrics: the track has been playing that long already.
                val into = msg.optLong("at", 0L).coerceAtLeast(0L)
                scrobExec.execute {
                    spin?.resync(i, System.currentTimeMillis() - into)
                    tickInner()
                }
            }
            "flush" -> scrobExec.execute {
                val r = flushInner()
                if (r != null && r.error == null && r.waiting == 0) toast("All scrobbles sent")
            }
            "scrobbleAlbum" -> {
                val tracks = msg.optJSONArray("tracks")?.objects().orEmpty()
                val mode = if (msg.optString("when") == "at") When.AT else When.FINISHED
                scrobExec.execute { manualAlbumInner(msg.optString("artist"), msg.optString("album"), msg.optString("cover"), tracks, mode, msg.optLong("at")) }
            }
            "heardYes" -> scrobExec.execute { heardYesInner(listOf(msg.optString("id"))) }
            "heardAll" -> scrobExec.execute { heardYesInner(heard.waiting().map { it.id }) }
            "heardNo", "heardUndo" -> scrobExec.execute {
                heard.get(msg.optString("id"))?.let { h ->
                    // Undo takes back a no; a song scrobbled since (alone or with its record) stays that.
                    if (msg.optString("t") == "heardNo") {
                        if (h.status == HeardSong.NEW) h.status = HeardSong.DROPPED
                        if (offerUp?.first == h.id) dropOffer()
                    } else if (h.status == HeardSong.DROPPED) h.status = HeardSong.NEW
                    heard.save()
                }
                pushHeard()
            }
            "heardSpin" -> scrobExec.execute { heardSpinInner(msg.optString("id")) }
            "identifyStop" -> identifyStop = true
            "setDefaultTrack" -> {
                prefs.defaultTrackSec = msg.optInt("sec", 210).coerceIn(60, 900)
                pushState()
            }
        }
    }

    // ---- entry points for alarms, notification buttons and the flush job ----

    fun onSpinAction(context: Context, action: String, release: Long, side: String, done: () -> Unit) {
        ensure(context)
        scrobExec.execute {
            try {
                when (action) {
                    SpinReceiver.STOP -> stopInner()
                    SpinReceiver.FLIP -> flipInner()
                    SpinReceiver.PLAY_SIDE -> playSideInner(release, side)
                    else -> {
                        tickInner()
                        if (queue.size() > 0) flushInner()
                    }
                }
            } catch (_: Exception) {
            } finally {
                done()
            }
        }
    }

    fun flushFromJob(context: Context, done: (retry: Boolean) -> Unit) {
        ensure(context)
        scrobExec.execute {
            var retry = false
            try {
                retry = flushInner(fromJob = true)?.retry == true
            } catch (_: Exception) {
                retry = true
            } finally {
                done(retry)
            }
        }
    }

    // ---- Last.fm connection ----

    private fun lastfm(): LastfmClient? =
        if (prefs.lastfmKey.isNotEmpty() && prefs.lastfmSecret.isNotEmpty()) LastfmClient(prefs.lastfmKey, prefs.lastfmSecret) else null

    private fun connectLastfm() = scrobExec.execute {
        val c = lastfm()
        if (c == null) {
            lfError = "Enter your API key and shared secret first"
            pushState()
            return@execute
        }
        lfBusy = true
        lfError = ""
        pushState()
        try {
            val token = c.getToken()
            prefs.lastfmPendingToken = token
            val url = c.authUrl(token)
            main.post { ui?.openUrl(url) }
        } catch (e: LastfmException) {
            lfError = if (e.badKey) "Last.fm doesn't accept this API key or secret. Check both." else (e.message ?: "Last.fm error ${e.code}")
        } catch (_: IOException) {
            lfError = "Can't reach Last.fm. Check your connection."
        } finally {
            lfBusy = false
            pushState()
        }
    }

    private fun checkLastfm(manual: Boolean) = scrobExec.execute {
        val token = prefs.lastfmPendingToken
        val c = lastfm()
        if (token.isEmpty() || c == null) return@execute
        try {
            val (name, key) = c.getSession(token)
            prefs.lastfmSession = key
            prefs.lastfmUser = name
            prefs.lastfmPendingToken = ""
            lfError = ""
            needsAuth = false
            toast("Connected to Last.fm as $name")
            pushState()
            flushInner()
            syncExec.execute { refreshPlaysInner() }
        } catch (e: LastfmException) {
            if (e.code == 14) {
                if (manual) lfError = "Not allowed yet. On the Last.fm page, tap \"Yes, allow access\"."
            } else {
                prefs.lastfmPendingToken = ""
                lfError = if (e.badKey) "Last.fm doesn't accept this API key or secret." else "That approval expired. Tap Connect to try again."
            }
        } catch (_: IOException) {
            if (manual) lfError = "Can't reach Last.fm. Check your connection."
        }
        pushState()
    }

    // ---- Discogs collection ----

    private fun discogs() = prefs.discogsToken.let { if (it.isEmpty()) DiscogsClient(null, limiter = RateLimiter.anonymous) else DiscogsClient(it) }

    /** Connects a collection: with a [token] (any collection) or just a [user]name (a public one). */
    private fun connectDiscogs(token: String, user: String) {
        if (token.isEmpty() && user.isEmpty()) {
            syncError = "Type your Discogs username first"
            pushState()
            return
        }
        syncExec.execute {
            syncing = true
            syncError = ""
            syncDone = 0
            syncPage = 0
            syncPages = 0
            pushState()
            try {
                val name = if (token.isNotEmpty()) {
                    DiscogsClient(token).identity()
                } else {
                    // A public collection needs no token; its first page tells whether it is there.
                    DiscogsClient(null, limiter = RateLimiter.anonymous).collectionPage(user, 1, 1)
                    user
                }
                prefs.discogsToken = token
                prefs.discogsUser = name
                toast("Connected to Discogs as $name")
                syncInner()
            } catch (e: DiscogsException) {
                syncError = when {
                    token.isEmpty() && (e.status == 401 || e.status == 403) -> "$user's collection is private on Discogs. Make it public in your Discogs privacy settings, or connect with a token below."
                    token.isEmpty() && e.status == 404 -> "Discogs has no user called $user."
                    e.status == 401 || e.status == 403 -> "Discogs didn't accept this token. Copy it again from your Discogs developer settings."
                    else -> e.message ?: "Discogs error"
                }
            } catch (_: IOException) {
                syncError = "Can't reach Discogs. Check your connection."
            } finally {
                syncing = false
                pushState()
            }
        }
    }

    private fun sync() = syncExec.execute {
        if (prefs.discogsUser.isEmpty()) return@execute
        syncing = true
        syncError = ""
        syncDone = 0
        syncPage = 0
        syncPages = 0
        pushState()
        try {
            syncInner()
        } catch (e: DiscogsException) {
            syncError = e.message ?: "Discogs error"
        } catch (_: IOException) {
            syncError = "Can't reach Discogs. Check your connection."
        } finally {
            syncing = false
            pushState()
        }
    }

    private fun syncInner() {
        val list = discogs().collection(prefs.discogsUser) { n, page, pages ->
            syncDone = n
            syncPage = page
            syncPages = pages
            pushState()
        }
        collection = list
        dropStats()
        file("collection.json").write(CollectionItem.listToJson(list).toString())
        prefs.lastSync = System.currentTimeMillis()
        pushCollection()
        lookUpShelfCovers()
        refreshPlaysInner()
    }

    /** Play counts for the collection from the user's Last.fm top albums (all sources, all time). */
    private fun refreshPlaysInner() {
        val c = lastfm() ?: return
        val user = prefs.lastfmUser.ifEmpty { return }
        val want = collection.map { Text.albumKey(it.artist, it.title) }.toHashSet()
        if (want.isEmpty()) return
        val map = HashMap<String, Int>()
        var complete = false
        try {
            var page = 1
            var pages: Int
            do {
                val (list, n) = c.topAlbums(user, page)
                pages = n
                for (a in list) {
                    val k = Text.albumKey(a.artist, a.album)
                    if (k in want) map[k] = (map[k] ?: 0) + a.playcount
                }
                page++
            } while (page <= pages && page <= 40)
            complete = true
        } catch (_: Exception) {
            if (map.isEmpty()) return
        }
        plays = if (complete) map else plays + map
        writeCounts("plays.json", plays)
        prefs.playsUpdated = System.currentTimeMillis()
        pushCollection()
    }

    // ---- releases and track lengths ----

    private fun releaseFile(id: Long) = file("releases/$id.json")

    private fun loadRelease(id: Long): Release? = releaseFile(id).read()?.let {
        try {
            Release.fromJson(JSONObject(it))
        } catch (_: Exception) {
            null
        }
    }

    private fun openRelease(id: Long, refresh: Boolean) = lookupExec.execute {
        val cached = loadRelease(id)
        if (cached != null && !refresh) {
            push("onRelease") { releaseUi(cached) }
            return@execute
        }
        if (cached == null) push("onRelease") { JSONObject().put("id", id).put("loading", true) }
        try {
            val r = resolveRelease(id)
            releaseFile(id).write(r.toJson().toString())
            push("onRelease") { releaseUi(r) }
            if (refresh) toast("Tracklist updated")
        } catch (e: IOException) {
            val text = if (e is DiscogsException) (e.message ?: "Discogs error") else "Can't reach Discogs. Check your connection."
            if (cached != null) {
                push("onRelease") { releaseUi(cached) }
                toast(text)
            } else {
                push("onRelease") { JSONObject().put("id", id).put("error", text) }
            }
        }
    }

    /** The release from Discogs, with missing track lengths looked up on MusicBrainz and Last.fm. */
    private fun resolveRelease(id: Long): Release {
        val r = discogs().release(id)
        var tracks = r.tracks
        if (Durations.missing(tracks) > 0) {
            val mb = MusicBrainzClient()
            try {
                mb.releaseForDiscogs(id)?.let { tracks = Durations.fill(tracks, mb.tracks(it), "musicbrainz") }
            } catch (_: IOException) {
            }
            if (Durations.missing(tracks) > 0) lastfm()?.let { c ->
                try {
                    c.albumInfo(r.artist, r.title)?.let { tracks = Durations.fill(tracks, it.tracks, "lastfm") }
                } catch (_: Exception) {
                }
            }
            if (Durations.missing(tracks) > 0) {
                try {
                    for (mbid in mb.search(r.artist, r.title).take(2)) {
                        if (Durations.missing(tracks) == 0) break
                        tracks = Durations.fill(tracks, mb.tracks(mbid), "musicbrainz")
                    }
                } catch (_: IOException) {
                }
            }
        }
        return r.copy(tracks = tracks)
    }

    private fun releaseUi(r: Release): JSONObject =
        r.toJson().put("plays", plays[Text.albumKey(r.artist, r.title)] ?: 0).put("spins", spinCounts[r.id.toString()] ?: 0)

    // ---- the record on the turntable (all on scrobExec) ----

    private fun planTracks(rel: Release, idx: List<Int>): List<PlanTrack> = idx.filter { it in rel.tracks.indices }.map { i ->
        val t = rel.tracks[i]
        val est = t.durSec <= 0
        PlanTrack(t.artist, t.title, rel.title, rel.artist, if (est) prefs.defaultTrackSec else t.durSec, i + 1, t.pos, t.side, est)
    }

    /**
     * Starts scrobbling tracks [idx] of a record. [adjust] may correct the plan before anything is sent (the times of
     * songs heard from it). Returns the session when it is on the turntable now, null when it was all in the past.
     */
    private fun startSpinInner(releaseId: Long, idx: List<Int>, mode: When, at: Long, adjust: (Spin) -> Unit = {}): Spin? {
        val rel = loadRelease(releaseId)
        if (rel == null) {
            toast("Open the record again; its tracklist isn't loaded")
            return null
        }
        val tracks = planTracks(rel, idx)
        if (tracks.isEmpty()) return null
        val now = System.currentTimeMillis()
        val starts = Planner.starts(tracks, mode, at, now)
        val s = Spin(
            "$releaseId-$now", releaseId, rel.title, rel.artist, collection.firstOrNull { it.releaseId == releaseId }?.let { faceOf(it) } ?: rel.thumb.ifEmpty { rel.cover }, rel.sides,
            tracks.zip(starts).map { (t, st) -> SpinItem(t, st) }.toMutableList(),
        )
        adjust(s)
        spinCounts = spinCounts + (releaseId.toString() to (spinCounts[releaseId.toString()] ?: 0) + 1)
        writeCounts("spins.json", spinCounts)
        val live = !s.items.all { now >= it.endMs }
        if (!live) {
            // Played in the past: scrobble it all now and leave the record that is spinning alone.
            heardIntoRecord(s, live = false)
            enqueue(s, s.takeDue(now))
            flushInner()
            pushState()
            pushActivity()
        } else {
            spin?.let { old -> enqueue(old, old.stop(now)) }
            spin = s
            heardIntoRecord(s, live = true)
            tickInner()
        }
        pushCollection()
        return if (live) s else null
    }

    private fun playSideInner(releaseId: Long, side: String) {
        val rel = loadRelease(releaseId) ?: return
        // The side that just ended is still up: the same session goes on with the other side.
        val s = spin
        val now = System.currentTimeMillis()
        if (s != null && s.releaseId == releaseId && s.isOver(now)) {
            enqueue(s, s.flip(planTracks(rel, rel.tracks.indices.filter { rel.tracks[it].side == side }), now))
            heardIntoRecord(s, live = true)
            tickInner()
            return
        }
        startSpinInner(releaseId, rel.tracks.indices.filter { rel.tracks[it].side == side }, When.NOW, 0)
    }

    private fun flipInner() {
        val s = spin ?: return
        val side = s.nextSide() ?: return
        val rel = loadRelease(s.releaseId) ?: return
        val now = System.currentTimeMillis()
        enqueue(s, s.flip(planTracks(rel, rel.tracks.indices.filter { rel.tracks[it].side == side }), now))
        heardIntoRecord(s, live = true)
        tickInner()
    }

    private fun stopInner() {
        val s = spin ?: return
        enqueue(s, s.stop(System.currentTimeMillis()))
        endSpin()
        SpinNotifier.cancel(ctx)
        flushInner()
        pushState()
        pushActivity()
    }

    /** How long a record stays up after its last track: time to turn it over, or a few minutes to see it end. */
    private fun lingerMs(s: Spin) = if (s.nextSide() != null) SIDE_LINGER else END_LINGER

    /** The record whose "side is over" note went out (its id and end), so it goes out once. */
    private var sideDoneFor = ""

    /** Music started on the phone: a record whose side has ended steps aside for it. */
    fun phonePlays() {
        if (spin == null) return
        scrobExec.execute { if (spin?.isOver(System.currentTimeMillis()) == true) tickInner() }
    }

    private fun endSpin() {
        spin = null
        spinUi = null
        spinFile.delete()
        SpinReceiver.schedule(ctx, null)
    }

    /** Brings the spin up to date with the clock: queues what counts, updates now playing, plans the next wake-up. */
    private fun tickInner() {
        val s = spin
        if (s == null) {
            SpinReceiver.schedule(ctx, null)
            pushState()
            return
        }
        val now = System.currentTimeMillis()
        val due = s.takeDue(now)
        enqueue(s, due)
        val cur = s.currentIndex(now)
        val announce = cur >= 0 && cur != s.nowPlaying
        if (announce) s.nowPlaying = cur
        val linger = lingerMs(s)
        // When the last track ends, the record stays up a while to be turned over, unless the phone plays something else.
        val ended = s.isOver(now)
        if (ended && (s.isOver(now, linger) || Phone.isPlaying())) {
            endSpin()
            if (sideDoneFor != "${s.id}:${s.endMs()}") SpinNotifier.finished(ctx, s)
        } else {
            spinFile.write(s.toJson().toString())
            spinUi = s.toUiJson().toString()
            SpinReceiver.schedule(ctx, s.nextEventMs(now, linger))
            if (!ended) SpinNotifier.show(ctx, s, now)
            else if (sideDoneFor != "${s.id}:${s.endMs()}") {
                sideDoneFor = "${s.id}:${s.endMs()}"
                SpinNotifier.finished(ctx, s)
            }
        }
        // The network last: the next alarm is set even when Last.fm is slow to answer.
        if (announce) sendNowPlaying(s.items[cur], now)
        if (due.isNotEmpty()) flushInner()
        pushState()
        if (due.isNotEmpty()) pushActivity()
    }

    private fun sendNowPlaying(item: SpinItem, now: Long) {
        val c = lastfm() ?: return
        val sk = prefs.lastfmSession.ifEmpty { return }
        if (item.endMs - now < 15_000) return
        try {
            c.updateNowPlaying(sk, fixed(item.track.toScrobble(item.startMs)))
        } catch (_: Exception) {
            // Now playing is best effort; the scrobble itself goes through the queue.
        }
    }

    private fun enqueue(s: Spin, due: List<SpinItem>) {
        if (due.isEmpty()) return
        val now = System.currentTimeMillis()
        val items = due.map { it to QueuedScrobble(UUID.randomUUID().toString(), fixed(it.track.toScrobble(it.startMs)), "vinyl", now) }
        // A length the record page only guessed ("≈3:30") isn't kept as the song's.
        items.forEach { (item, q) -> if (!item.track.estimated) keepLength(q.s) }
        val added = queue.add(items.map { it.second })
        added.forEach { activity.add(it, s.cover, "waiting") }
        activity.save()
    }

    /** Sends the queue. Without a Last.fm session the scrobbles simply wait. */
    private fun flushInner(fromJob: Boolean = false): FlushResult? {
        if (queue.size() == 0) {
            flushError = ""
            return null
        }
        val c = lastfm()
        val sk = prefs.lastfmSession
        if (c == null || sk.isEmpty()) {
            flushError = "Connect Last.fm to send these"
            pushState()
            return null
        }
        val r = queue.flush({ batch -> c.scrobble(sk, batch) }) { q, status, m ->
            activity.update(q.id, status, m)
            if (status == "sent" && q.src == "vinyl") db.addVinyl(listOf(Listen(q.s.artist, q.s.track, q.s.timestamp, q.s.album)))
        }
        activity.save()
        flushError = r.error.orEmpty()
        if (r.needsAuth) {
            needsAuth = true
            prefs.lastfmSession = ""
        }
        if (r.retry && !fromJob) FlushJob.schedule(ctx)
        pushState()
        pushActivity()
        // With the app open, what Last.fm just took is in the stats soon after: fetched back, in the names Last.fm keeps.
        if (r.sent > 0 && ui != null) main.postDelayed({ refreshHistorySoon(now = true) }, 5000L)
        return r
    }

    // ---- phone scrobbling (called on the main thread by PhoneListener) ----

    /** A listen on the phone counts. In shadow mode it is only logged, to compare with Pano Scrobbler. */
    /** [s] with the user's name fixes applied (Settings → Fix names). */
    private fun fixed(s: Scrobble): Scrobble = FixRules.apply(s, FixRules.active(prefs.fixPresets, FixRules.parse(prefs.fixRules)))

    fun phoneScrobble(pkg: String, s: Scrobble) {
        val mode = Phone.mode()
        if (mode == Phone.OFF || !Phone.enabled(pkg)) return
        val app = Phone.label(ctx, pkg)
        scrobExec.execute {
            val q = QueuedScrobble(UUID.randomUUID().toString(), fixed(s), "phone", System.currentTimeMillis())
            keepLength(q.s)
            if (mode == Phone.SHADOW) {
                activity.add(q, "", "shadow", "", app)
                activity.save()
                pushActivity()
            } else {
                queue.add(listOf(q)).forEach { activity.add(it, "", "waiting", "", app) }
                activity.save()
                flushInner()
            }
        }
    }

    /** A song's real length, for the time listened in Stats: Last.fm keeps none. */
    private fun keepLength(s: Scrobble) {
        if (s.duration !in 30..7200) return
        try {
            db.saveLength(Stats.lengthKey(s.artist, s.track), s.duration)
        } catch (_: Exception) {
        }
    }

    fun phoneNowPlaying(pkg: String, s: Scrobble) {
        if (Phone.mode() != Phone.ON || !Phone.enabled(pkg)) return
        scrobExec.execute {
            val c = lastfm() ?: return@execute
            val sk = prefs.lastfmSession.ifEmpty { return@execute }
            try {
                c.updateNowPlaying(sk, fixed(s))
            } catch (_: Exception) {
                // Best effort, like for records.
            }
        }
    }

    /** Counts the page's catalog searches; one overtaken by a newer one isn't asked. */
    @Volatile private var catalogAsks = 0

    // ---- an album scrobbled by hand: heard somewhere the phone and the turntable don't see (on scrobExec) ----

    /**
     * Album [album] by [artist] was heard some time ago: its [tracks] ({title, dur in seconds, n}) back to back, over
     * just now ([mode] FINISHED) or started [at]. Each is scrobbled at the time it played; what Last.fm wouldn't take
     * (not over yet, or more than two weeks ago) is left out.
     */
    private fun manualAlbumInner(artist: String, album: String, cover: String, tracks: List<JSONObject>, mode: When, at: Long) {
        val plan = tracks.mapIndexed { i, t ->
            val dur = t.optInt("dur")
            PlanTrack(t.optString("artist").ifBlank { artist }, t.optString("title").trim(), album, artist, if (dur > 0) dur else prefs.defaultTrackSec, t.optInt("n", i + 1), "", "", dur <= 0)
        }.filter { it.title.isNotEmpty() && it.artist.isNotBlank() }
        val now = System.currentTimeMillis()
        val past = Planner.past(plan, mode, at, now)
        if (past.isEmpty()) {
            toast("Nothing to scrobble: Last.fm takes plays that are over, from the last two weeks")
            return
        }
        val items = past.map { (t, start) -> t to QueuedScrobble(UUID.randomUUID().toString(), fixed(t.toScrobble(start)), "manual", now) }
        items.forEach { (t, q) -> if (!t.estimated) keepLength(q.s) }
        queue.add(items.map { it.second }).forEach { activity.add(it, cover, "waiting", "", "By hand") }
        activity.save()
        if (past.size < plan.size) toast("${past.size} of ${plan.size} tracks scrobbled; Last.fm wouldn't take the others")
        pushActivity()
        flushInner()
    }

    // ---- heard around you: Pixel's Now Playing and Identify (the list on scrobExec, lookups on lookupExec) ----

    private val heard by lazy { HeardLog(file("heard.json")) }

    /** Now Playing's wording of a song per app ("%1$s by %2$s" in English), read from the app itself; "" if it can't be. */
    private val npFormats = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** When a "playing it on vinyl?" note last went out per record, so one record asks once. */
    private val offered = HashMap<Long, Long>()

    /** The "playing it on vinyl?" note that is up: the heard song it is about and its record. */
    private var offerUp: Pair<String, Long>? = null

    /** The note is answered (in the app, or the record was started): it goes. */
    private fun dropOffer() {
        HeardNotifier.cancel(ctx)
        offerUp = null
    }

    /** When a heard song began: Now Playing knows a song some seconds in; Identify says where in the song it listened. */
    private fun began(h: HeardSong) = if (h.src == "nowplaying") h.firstAt - Heard.LAG_MS else h.firstAt

    private fun npFormat(pkg: String): String? = npFormats.getOrPut(pkg) {
        try {
            val res = ctx.packageManager.getResourcesForApplication(pkg)
            val id = res.getIdentifier("song_format_string", "string", pkg)
            if (id != 0) res.getString(id) else ""
        } catch (_: Exception) {
            ""
        }
    }.ifEmpty { null }

    /** Whether you know artist [name]: from your history or your shelf (to read "Song by Artist" right). */
    private fun knownArtist(name: String) = (stats?.artistPlaysOf(name) ?: 0) > 0 || collection.any { Heard.sameArtist(it.artist, name) }

    /**
     * Pixel's Now Playing ([pkg]) recognised a song around the phone; [text] is how it words it ("Song by Artist"). It
     * waits in the list until you say yes or no. While the phone plays music itself, Now Playing hears that or isn't
     * what you listen to, so nothing is noted.
     */
    fun nowPlayingHeard(pkg: String, text: String) {
        if (Phone.isPlaying()) return
        val at = System.currentTimeMillis()
        scrobExec.execute {
            val formats = listOfNotNull(npFormat(pkg)) + Heard.fallbackFormats(java.util.Locale.getDefault().language)
            val (artist, title) = Heard.parse(text, formats, { knownArtist(it) }) { a, t -> (stats?.findTrack(a, t) ?: -1) >= 0 } ?: return@execute
            heardInner(artist, title, "", at, "nowplaying")
        }
    }

    /**
     * A song heard at [at]: a song of the record on the turntable only says where the needle is; anything else goes on
     * the list (or stretches the listen it continues), then its cover and its place on your shelf are looked up.
     */
    private fun heardInner(artist: String, title: String, album: String, at: Long, src: String): HeardSong? {
        spin?.let { s ->
            val start = if (src == "nowplaying") at - Heard.LAG_MS else at
            val i = spinItemFor(s, artist, title, start)
            if (i >= 0) {
                if (followRecord(s, i, start)) tickInner()
                return null
            }
        }
        val (h, isNew) = heard.add(artist, title, album.ifEmpty { stats?.albumOfSong(artist, title).orEmpty() }, at, src)
        heard.save()
        pushHeard()
        if (isNew) lookupExec.execute { placeHeard(h) }
        return h
    }

    /**
     * The track of record [s] that song [title] by [artist], begun at [start], is: the one planned around then; else
     * the next of that name still to play (a reprise shares its name); else one already counted that the plan has
     * within ten minutes of then (the plan is off). -1 if the record has no such track, or only ones played at quite
     * another time: that is a new listen, for the list.
     */
    private fun spinItemFor(s: Spin, artist: String, title: String, start: Long): Int {
        val m = s.items.indices.filter { k ->
            val it = s.items[k]
            it.state != SpinItem.SKIPPED && Heard.sameSong(it.track.title, title) &&
                (Heard.sameArtist(it.track.artist.ifEmpty { s.artist }, artist) || Heard.sameArtist(s.artist, artist))
        }
        return m.firstOrNull { start >= s.items[it].startMs - Heard.NEAR_MS && start <= s.items[it].endMs }
            ?: m.firstOrNull { s.items[it].state == SpinItem.PLANNED }
            ?: m.firstOrNull { start >= s.items[it].startMs - 10 * 60_000 && start <= s.items[it].endMs + 10 * 60_000 }
            ?: -1
    }

    /**
     * Track [i] of record [s] began at [start]: if the plan has it at another time, the plan moves to match (and true
     * comes back: the caller brings the session up to date). A track already counted may still be named a while after
     * it ended; that moves nothing.
     */
    private fun followRecord(s: Spin, i: Int, start: Long): Boolean {
        val item = s.items[i]
        val after = if (item.state == SpinItem.SCROBBLED) 90_000 else 10_000
        if (start >= item.startMs - Heard.NEAR_MS && start <= item.endMs + after) return false
        s.resync(i, start)
        return true
    }

    /**
     * Record [s] starts (or its next side does): songs on the list that were heard from it are its plays now, and
     * the note asking about it goes. On the turntable now ([live]), the latest of them that can still be playing says
     * where the needle is, before anything is sent.
     */
    private fun heardIntoRecord(s: Spin, live: Boolean) {
        val mine = heard.waiting().mapNotNull { h -> spinItemFor(s, h.artist, h.title, began(h)).takeIf { it >= 0 }?.let { h to it } }
        if (live) {
            val now = System.currentTimeMillis()
            mine.filter { (h, i) -> began(h) <= now && began(h) >= now - s.items[i].track.durSec * 1000L - 2 * 60_000 }
                .maxByOrNull { began(it.first) }?.let { (h, i) -> followRecord(s, i, began(h)) }
        }
        val from = s.items.filter { it.state != SpinItem.SKIPPED }.minOfOrNull { it.startMs } ?: return
        val to = s.endMs()
        val settled = mine.map { it.first }.filter { began(it) >= from - 60_000 && began(it) <= to + 60_000 }
        settled.forEach { it.status = HeardSong.VINYL }
        if (settled.isNotEmpty()) {
            heard.save()
            pushHeard()
        }
        if (offerUp?.second == s.releaseId) dropOffer()
    }

    /** The record on your shelf with song [title] by [artist] (the one you spin most, else the fullest), or null. */
    private fun shelfHit(artist: String, title: String): ShelfHit? {
        var best: Triple<CollectionItem, Release, Int>? = null
        // Every record of theirs whose tracklist is on the phone; of the others, a few are looked up.
        var asked = 0
        for (c in collection.filter { Heard.sameArtist(it.artist, artist) }) {
            val rel = loadRelease(c.releaseId) ?: (if (asked++ < 4) try {
                resolveRelease(c.releaseId).also { releaseFile(c.releaseId).write(it.toJson().toString()) }
            } catch (_: Exception) {
                null
            } else null) ?: continue
            val i = Heard.trackIndex(rel.tracks.map { it.title }, title)
            if (i < 0) continue
            val b = best
            val spins = spinCounts[c.releaseId.toString()] ?: 0
            if (b == null || spins > (spinCounts[b.first.releaseId.toString()] ?: 0) ||
                (spins == (spinCounts[b.first.releaseId.toString()] ?: 0) && rel.tracks.size > b.second.tracks.size)
            ) best = Triple(c, rel, i)
        }
        return best?.let { (c, rel, i) -> ShelfHit(c.releaseId, rel.title, rel.artist, faceOf(c), i, rel.tracks[i].side) }
    }

    /** A new song on the list gets its cover and its record on your shelf; if Now Playing heard it from a record, you're asked. */
    private fun placeHeard(h: HeardSong) {
        val hit = try {
            shelfHit(h.artist, h.title)
        } catch (_: Exception) {
            null
        }
        val cover = hit?.cover ?: try {
            deezer.trackCover(h.artist, h.title).orEmpty()
        } catch (_: Exception) {
            ""
        }
        scrobExec.execute {
            h.cover = cover
            h.shelf = hit
            if (hit != null && h.album.isEmpty()) h.album = hit.album
            heard.save()
            pushHeard()
            val now = System.currentTimeMillis()
            if (hit != null && h.status == HeardSong.NEW && h.src == "nowplaying" && spin == null && now - (offered[hit.releaseId] ?: 0L) > OFFER_AGAIN_MS) {
                offered[hit.releaseId] = now
                offerUp = h.id to hit.releaseId
                HeardNotifier.offer(ctx, h)
            }
        }
    }

    /** You said yes to songs [ids] on the list: scrobbled from when they started (Now Playing knows a song some seconds in). */
    private fun heardYesInner(ids: List<String>) {
        val list = ids.mapNotNull { heard.get(it) }.filter { it.status == HeardSong.NEW }
        if (list.isEmpty()) return
        val now = System.currentTimeMillis()
        for (h in list) {
            val start = if (h.src == "nowplaying") h.firstAt - Heard.LAG_MS else h.firstAt
            val q = QueuedScrobble(UUID.randomUUID().toString(), fixed(Scrobble(h.artist, h.title, h.album, timestamp = start / 1000)), "heard", now)
            queue.add(listOf(q)).forEach { activity.add(it, h.cover, "waiting", "", if (h.src == "identify") "Identify" else "Now Playing") }
            h.status = HeardSong.SCROBBLED
        }
        if (list.any { it.id == offerUp?.first }) dropOffer()
        heard.save()
        activity.save()
        pushHeard()
        flushInner()
    }

    /**
     * The record song [id] is on was playing: scrobbled from that song on (to the end of its side), from when it was
     * heard. The songs of the record heard after it say where the needle is now.
     */
    private fun heardSpinInner(id: String) {
        val h = heard.get(id)
        val hit = h?.shelf
        // The note about it goes (after a restart nothing says which one is up: then it goes too).
        val up = offerUp
        if (up == null || up.first == id || up.second == hit?.releaseId) dropOffer()
        // Answered already (in the app, while the note was still up), or nothing to go by: the list as it is.
        if (h == null || hit == null || h.status != HeardSong.NEW) {
            pushHeard()
            return
        }
        val start = began(h)
        // That record is on the turntable since (started from its page): the song is one of its plays, nothing starts anew.
        spin?.let { s ->
            val first = s.items.filter { it.state != SpinItem.SKIPPED }.minOfOrNull { it.startMs } ?: Long.MAX_VALUE
            if (s.releaseId == hit.releaseId && start >= first - 45 * 60_000) {
                h.status = HeardSong.VINYL
                heard.save()
                pushHeard()
                return
            }
        }
        val rel = loadRelease(hit.releaseId)
        if (rel == null) {
            toast("Open the record again; its tracklist isn't loaded")
            pushHeard()
            return
        }
        val side = rel.tracks.indices.filter { it >= hit.index && (hit.side.isEmpty() || rel.tracks[it].side == hit.side) }
        // The songs of that side heard after it, within the time the side takes (and a margin for lengths only guessed).
        val until = h.firstAt + planTracks(rel, side).sumOf { it.durSec * 1000L } * 5 / 4 + 5 * 60_000
        val later = heard.all().filter { x ->
            x.id != h.id && x.firstAt > h.firstAt && x.firstAt <= until && Heard.sameArtist(x.artist, h.artist) && side.any { Heard.sameSong(rel.tracks[it].title, x.title) }
        }
        // One you already scrobbled on its own isn't scrobbled again with the record.
        val sent = later.filter { it.status == HeardSong.SCROBBLED }
        val idx = side.filter { i -> sent.none { Heard.sameSong(rel.tracks[i].title, it.title) } }
        val same = later.filter { it.status == HeardSong.NEW }
        startSpinInner(hit.releaseId, idx, When.AT, start) { s ->
            // Where each of them was heard is when it began, whatever the lengths say.
            val at = HashMap<Int, Long>()
            for (x in same) {
                val i = s.items.indexOfFirst { Heard.sameSong(it.track.title, x.title) }
                if (i > 0) at[i] = began(x)
            }
            if (at.isNotEmpty()) s.anchor(at)
        }
        (same + h).forEach { it.status = HeardSong.VINYL }
        heard.save()
        pushHeard()
    }

    /** From the "playing it on vinyl?" note's button. */
    fun onHeardSpin(context: Context, id: String, done: () -> Unit) {
        ensure(context)
        scrobExec.execute {
            try {
                heardSpinInner(id)
            } catch (_: Exception) {
            } finally {
                done()
            }
        }
    }

    private fun pushHeard() {
        push("onHeard") { heard.toJson() }
        pushState()
    }

    // ---- Identify: ten seconds from the microphone, looked up by AudD ----

    private val identifyExec = serial()
    @Volatile private var identifyStop = false
    @Volatile private var identifyBusy = false

    private fun pushIdentify(state: String, extra: JSONObject.() -> Unit = {}) = push("onIdentify") { JSONObject().put("state", state).apply(extra) }

    /** Listens for [IDENTIFY_MS] and asks AudD what plays; a song found goes on the list. Asked once the microphone is allowed. */
    fun identify() {
        if (identifyBusy) return
        identifyBusy = true
        identifyStop = false
        identifyExec.execute {
            try {
                val started = System.currentTimeMillis()
                pushIdentify("listening") { put("at", started).put("ms", IDENTIFY_MS) }
                val wav = Recorder.wav(IDENTIFY_MS) { identifyStop }
                when {
                    identifyStop -> pushIdentify("idle")
                    wav == null -> pushIdentify("error") { put("msg", "The microphone isn't free. Another app may be using it.") }
                    else -> {
                        pushIdentify("looking")
                        val r = app.recordshelf.net.AudD().identify(wav)
                        if (r == null) {
                            pushIdentify("none")
                        } else {
                            // The song started that far before the recording did.
                            val start = started - r.offsetSec * 1000L
                            scrobExec.execute {
                                val h = heardInner(r.artist, r.title, r.album, start, "identify")
                                pushIdentify("found") { put("artist", r.artist).put("title", r.title).put("id", h?.id ?: "").put("vinyl", h == null) }
                            }
                        }
                    }
                }
            } catch (_: app.recordshelf.net.AudD.LimitReached) {
                pushIdentify("error") { put("msg", "AudD's free lookups for today are used up. Try again tomorrow.") }
            } catch (_: IOException) {
                pushIdentify("error") { put("msg", "Can't reach AudD. Check your connection.") }
            } catch (_: Exception) {
                pushIdentify("error") { put("msg", "Identify didn't work this time.") }
            } finally {
                identifyBusy = false
            }
        }
    }

    /**
     * Shadow mode against reality: what this app logged since shadow mode began (at most a
     * week back) next to what reached Last.fm in that time, minus the records scrobbled here.
     */
    private fun compareInner() {
        val c = lastfm()
        val user = prefs.lastfmUser
        if (c == null || user.isEmpty()) {
            compareError = "Connect Last.fm first: the comparison reads your scrobbles from there."
            pushState()
            return
        }
        comparing = true
        compareError = ""
        pushState()
        try {
            val now = System.currentTimeMillis()
            val from = maxOf(prefs.shadowSince, now - 7 * 24 * HOUR) / 1000
            val to = (now - 10 * 60_000) / 1000
            val ours = activity.listens { it.optString("src") == "phone" && it.optString("status") == "shadow" && it.optLong("ts") in from..to }
            // Records, albums scrobbled by hand and songs heard around you reached Last.fm from here, not from a music app.
            val vinyl = activity.listens { it.optString("src") in setOf("vinyl", "manual", "heard") }
            val theirs = ArrayList<Listen>()
            if (to > from) {
                var page = 1
                var pages: Int
                do {
                    val r = c.recentTracks(user, from, to, page)
                    pages = r.pages
                    theirs += r.tracks.map { Listen(it.artist, it.title, it.ts, it.album) }
                    page++
                } while (page <= pages && page <= 15)
            }
            val notVinyl = theirs.filter { t -> vinyl.none { it.key == t.key && kotlin.math.abs(it.ts - t.ts) <= 120 } }
            val r = Compare.run(ours, notVinyl)
            val out = r.toJson().put("from", from).put("to", to).put("at", now)
            compareJson = out.toString()
            file("compare.json").write(out.toString())
            push("onCompare") { out }
        } catch (e: LastfmException) {
            compareError = e.message ?: "Last.fm error ${e.code}"
        } catch (_: IOException) {
            compareError = "Can't reach Last.fm. Check your connection."
        } finally {
            comparing = false
            pushState()
        }
    }

    // ---- Last.fm history and stats ----

    private fun historySync(c: LastfmClient, user: String) = HistorySync({ from, to -> c.recentTracks(user, from, to, 1) }, db)

    /** Imports the whole history (or continues an interrupted import), then catches up and builds the stats. On [historyExec]. */
    private fun importInner() {
        val c = lastfm()
        val user = prefs.lastfmUser
        if (c == null || user.isEmpty()) {
            histError = "Connect Last.fm first"
            pushState()
            return
        }
        if (importing) return
        // Already done (a second import that was waiting in line): only a reset starts it over.
        if (prefs.historyDone && prefs.historyCursor == 0L) return
        importing = true
        cancelImport = false
        histError = ""
        val base = db.count()
        histDone = base
        histTotal = 0
        pushState()
        var tried = false
        var guarded = false
        try {
            val cursor = prefs.historyCursor.takeIf { it > 0 } ?: (System.currentTimeMillis() / 1000 + 60)
            historySync(c, user).walk(0, cursor, { cancelImport }) { p ->
                prefs.historyCursor = p.next
                if (p.next == 0L) prefs.historyDone = true
                histDone = base + p.fetched
                histTotal = base + maxOf(p.total, p.fetched)
                // Only a long import needs the foreground service (and its notification).
                if (!tried && p.next != 0L && p.total > 2000) {
                    tried = true
                    guarded = ImportService.start(ctx)
                }
                if (guarded) ImportService.progress(ctx, histDone, histTotal)
                pushState()
            }
            if (prefs.historyDone) {
                importing = false
                refreshExec.execute { refreshHistoryInner(force = true) }
            }
        } catch (e: LastfmException) {
            histError = if (e.retryable) "Last.fm is busy. The import continues where it stopped next time you open the app." else (e.message ?: "Last.fm error ${e.code}")
        } catch (_: IOException) {
            histError = "Lost the connection to Last.fm. The import continues where it stopped next time you open the app."
        } finally {
            importing = false
            if (guarded) ImportService.stop(ctx)
            histCount = db.count()
            pushState()
        }
    }

    fun isImporting() = importing

    /** Where the refresh stands ([HistorySync.refresh]), kept in the settings. */
    private val refreshState by lazy {
        object : HistorySync.RefreshState {
            override var syncedTo: Long
                get() = prefs.historySyncedTo
                set(v) { prefs.historySyncedTo = v }
            override var from: Long
                get() = prefs.refreshFrom
                set(v) { prefs.refreshFrom = v }
            override var to: Long
                get() = prefs.refreshTo
                set(v) { prefs.refreshTo = v }
            override var cursor: Long
                get() = prefs.refreshCursor
                set(v) { prefs.refreshCursor = v }
        }
    }

    /**
     * New scrobbles from Last.fm when the app comes to the front or Stats are asked for: one or two
     * small requests, at most once a minute (Last.fm asks for at most 5 a second), or [now] after a
     * scrobble from here.
     */
    private fun refreshHistorySoon(now: Boolean = false) {
        if (!prefs.historyDone || importing) return
        val t = System.currentTimeMillis()
        if (!now && t - refreshTried < 60_000L) return
        if (!refreshQueued.compareAndSet(false, true)) return
        refreshTried = t
        refreshExec.execute {
            refreshQueued.set(false)
            refreshHistoryInner()
        }
    }

    /** Fetches the scrobbles since the history was last known to be whole (see [HistorySync.refresh]), then rebuilds the stats. On [refreshExec]. */
    private fun refreshHistoryInner(force: Boolean = false) {
        historyReady.await()
        val c = lastfm()
        val user = prefs.lastfmUser
        if (c == null || user.isEmpty() || !prefs.historyDone) return
        histSyncing = true
        pushState()
        val before = db.count()
        try {
            historySync(c, user).refresh(refreshState, System.currentTimeMillis() / 1000, LATE_SEC) { f, t -> c.recentTracks(user, f, t, 1, limit = 1).total }
            prefs.historyLastSync = System.currentTimeMillis()
            histError = ""
        } catch (_: Exception) {
            // Stats work from what is stored; the next visit goes on from here.
        } finally {
            histSyncing = false
            histCount = db.count()
            pushState()
        }
        // What came in counts, also when the walk was cut short.
        if (histCount > before || force || history == null) {
            history = History.from(db.everything, hidden())
            dropStats()
            statsExec.execute { statsInner() }
            historyExec.execute { fetchTagsInner(c) }
        }
    }

    /** Artists whose tags an artist page asked Last.fm for (once each while the app runs). */
    private val tagsAsked: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    /**
     * An album page beyond the most played ([j], its tags never looked up): Last.fm's tags for the album now,
     * weighed with its artist's and your record of it, and the page again with its genres.
     */
    private fun albumTagsFor(j: JSONObject) {
        val c = lastfm() ?: return
        val info = j.optJSONObject("info") ?: return
        val key = info.optString("key")
        val artist = info.optString("artist")
        val title = info.optString("title")
        if (key.isEmpty() || key in db.tags().keys || !tagsAsked.add(key)) return
        historyExec.execute {
            try {
                db.saveTags(key, Genres.fromTags(c.albumTopTags(artist, title), artist, title))
                val kept = db.tags().filterKeys { it == key || it == Genres.artistKey(artist) }
                val families = Genres(collection, kept).of(artist, key).first
                if (families.isNotEmpty()) push("onAlbum") { JSONObject(j.toString()).put("genres", JSONArray(families.map { Genres.label(it) })) }
            } catch (_: Exception) {
                tagsAsked.remove(key) // offline: next time
            }
        }
    }

    // ---- setlist.fm ----

    /** An artist as setlist.fm knows them, with their shows (the newest first, a few to come among them). */
    private class Live(val name: String, val url: String, val shows: List<Show>)

    /** setlist.fm's answers by artist ([Text.norm]), with when they came: kept [LIVE_TTL] (null: setlist.fm doesn't know them). */
    private val setlistCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Live?>>()
    private const val LIVE_TTL = 12 * HOUR

    /** One client while the key stays, so its two requests a second hold across artists. */
    @Volatile private var setlistfm: SetlistfmClient? = null

    /** An artist page's setlists: what they play lately and their recent shows, with your plays of each song. On [webExec]. */
    private fun setlistsFor(name: String, req: Int) {
        val key = prefs.setlistKey
        val out = JSONObject().put("name", name).put("req", req)
        if (key.isEmpty()) return push("onSetlists") { out.put("needsKey", true) }
        webExec.execute {
            try {
                val live = liveFor(name)
                val plays = currentStats()?.songPlays(name).orEmpty()
                if (live == null) out.put("unknown", true) else liveJson(live, plays, out)
            } catch (_: SetlistfmClient.KeyRefused) {
                out.put("error", "setlist.fm didn't accept the API key. Check it in Settings → setlist.fm.")
            } catch (_: SetlistfmClient.Busy) {
                out.put("error", "setlist.fm is busy right now. Try again in a minute.")
            } catch (_: Exception) {
                out.put("error", "Couldn't reach setlist.fm. Check your connection.")
            }
            push("onSetlists") { out }
        }
    }

    private fun liveFor(name: String): Live? {
        val k = Text.norm(name)
        setlistCache[k]?.takeIf { System.currentTimeMillis() - it.first < LIVE_TTL }?.let { return it.second }
        val c = setlistfm ?: SetlistfmClient(prefs.setlistKey).also { setlistfm = it }
        val live = c.findArtist(name)?.let { a ->
            Live(a.name, a.url, Setlists.gather(java.time.LocalDate.now()) { p -> c.setlists(a.mbid, p) })
        }
        setlistCache[k] = System.currentTimeMillis() to live
        return live
    }

    private fun liveJson(live: Live, plays: Map<String, Int>, out: JSONObject) {
        val today = java.time.LocalDate.now()
        val lately = Setlists.lately(live.shows, today)
        fun song(title: String, cover: String) = JSONObject().put("title", title).put("cover", cover).put("plays", plays[Text.normLoose(title)] ?: 0)
        fun show(s: Show) = JSONObject().put("id", s.id).put("date", s.date.toString()).put("venue", s.venue).put("city", s.city).put("country", s.country)
            .put("tour", s.tour).put("url", s.url)
            .put("songs", JSONArray(s.songs.filter { !it.tape }.map { song(it.title, it.cover).put("with", it.with).put("encore", it.encore) }))
        out.put("artist", live.name).put("url", live.url).put("of", lately.size)
            .put("typical", JSONArray(Setlists.typical(lately).map { song(it.title, it.cover).put("n", it.shows).put("encore", it.encore) }))
            .put("upcoming", JSONArray(live.shows.filter { it.date.isAfter(today) }.sortedBy { it.date }.take(Setlists.UPCOMING_MAX).map { show(it) }))
            .put("shows", JSONArray(live.shows.filter { !it.date.isAfter(today) && it.songs.any { s -> !s.tape } }.sortedByDescending { it.date }.take(15).map { show(it) }))
    }

    // ---- your concerts, from setlist.fm ----

    /** The shows the user marked as attended on setlist.fm (their username in Settings), the newest first; kept in concerts.json. */
    @Volatile private var concertList: List<Show>? = null
    @Volatile private var concertsBusy = false
    @Volatile private var concertsError = ""
    private val concertExec = serial()
    private const val CONCERTS_EVERY = 7 * 24 * HOUR
    /** On the concerts page, a show just marked on setlist.fm comes soon: one request for 20 shows. */
    private const val CONCERTS_PAGE_EVERY = HOUR
    private const val CONCERT_PAGES = 50

    private fun concerts(): List<Show> = concertList ?: run {
        val o = file("concerts.json").read()?.let { runCatching { JSONObject(it) }.getOrNull() }
        val list = if (o != null && o.optString("user") == prefs.setlistUser) {
            o.optJSONArray("shows")?.let { a -> (0 until a.length()).mapNotNull { Show.fromJson(a.getJSONObject(it)) } }.orEmpty()
        } else {
            emptyList()
        }
        list.also { concertList = it }
    }

    /** Fetched again when Explore is opened and they are a week old, when the concerts page is and they are [every] old. */
    private fun concertsMaybeDue(every: Long = CONCERTS_EVERY) {
        if (!concertsBusy && prefs.setlistUser.isNotEmpty() && prefs.setlistKey.isNotEmpty() && System.currentTimeMillis() - prefs.concertsAt > every) {
            concertExec.execute { fetchConcertsInner() }
        }
    }

    /** All the user's attended shows from setlist.fm, page by page (20 a page, two requests a second). On [concertExec]. */
    private fun fetchConcertsInner() {
        val user = prefs.setlistUser
        if (user.isEmpty() || prefs.setlistKey.isEmpty()) return
        concertsBusy = true
        concertsError = ""
        pushState()
        try {
            val c = setlistfm ?: SetlistfmClient(prefs.setlistKey).also { setlistfm = it }
            val (first, total) = c.attended(user, 1)
            val all = ArrayList(first)
            var page = 2
            while (all.size < total && page <= CONCERT_PAGES) {
                val more = c.attended(user, page++).first
                if (more.isEmpty()) break
                all += more
            }
            if (user == prefs.setlistUser) {
                val list = all.distinctBy { it.id }.sortedByDescending { it.date }
                concertList = list
                file("concerts.json").write(JSONObject().put("user", user).put("shows", JSONArray(list.map { it.toJson() })).toString())
                prefs.concertsAt = System.currentTimeMillis()
                if (list.isEmpty()) concertsError = "setlist.fm has no concerts for \"$user\". Check the username, and that you marked shows as \"I was there\" on setlist.fm."
            }
        } catch (_: SetlistfmClient.KeyRefused) {
            concertsError = "setlist.fm didn't accept the API key."
        } catch (_: SetlistfmClient.Busy) {
            concertsError = "setlist.fm is busy right now. Try again in a minute."
        } catch (_: Exception) {
            concertsError = "Couldn't reach setlist.fm. Check your connection."
        } finally {
            concertsBusy = false
            pushState()
            statsExec.execute { push("onConcerts") { concertsPageJson() } }
        }
    }

    /** A concert for the pages: the show, its songs with your plays, and your plays of the artist in the four weeks before and after. */
    private fun concertJson(c: Show, s: Stats?, plays: Map<String, Int>): JSONObject {
        val o = JSONObject().put("id", c.id).put("date", c.date.toString()).put("artist", c.artist).put("venue", c.venue).put("city", c.city)
            .put("country", c.country).put("tour", c.tour).put("url", c.url).put("photo", s?.artistPhoto(c.artist).orEmpty())
            .put("songs", JSONArray(c.songs.filter { !it.tape }.map { JSONObject().put("title", it.title).put("cover", it.cover).put("with", it.with).put("encore", it.encore).put("plays", plays[Text.normLoose(it.title)] ?: 0) }))
        s?.aroundDay(c.artist, c.date)?.let { (before, after) ->
            o.put("before", before).put("after", after).put("running", c.date.plusDays(Stats.AFTERGLOW_DAYS + 1L).isAfter(java.time.LocalDate.now()))
        }
        return o
    }

    private fun concertsJson(list: List<Show>, s: Stats?): JSONArray {
        val plays = HashMap<String, Map<String, Int>>()
        return JSONArray(list.map { c -> concertJson(c, s, plays.getOrPut(Text.norm(c.artist)) { s?.songPlays(c.artist).orEmpty() }) })
    }

    /** The concerts page: every show, newest first. On [statsExec]. */
    private fun concertsPageJson(): JSONObject = JSONObject().put("user", prefs.setlistUser).put("busy", concertsBusy).put("error", concertsError)
        .put("shows", concertsJson(concerts(), currentStats()))

    /** The shows of [artist] you were at. */
    private fun concertsOf(artist: String, s: Stats?): JSONArray = concertsJson(concerts().filter { Text.norm(it.artist) == Text.norm(artist) }, s)

    /** The shows you were at in period [r]. */
    private fun concertsIn(s: Stats, r: app.recordshelf.history.Range): JSONArray {
        val zone = java.time.ZoneId.systemDefault()
        return concertsJson(concerts().filter { it.date.atStartOfDay(zone).toEpochSecond() in r.from until r.to }.sortedBy { it.date }, s)
    }

    /** Songs Last.fm finds similar to a song (by its key), for genre playlists: the last 60 asked. */
    private val similar: MutableMap<String, List<Triple<String, String, Double>>> = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, List<Triple<String, String, Double>>>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Triple<String, String, Double>>>?) = size > 60
    })

    /**
     * A genre's (or style's) playlist of the size and mix asked for ([app.recordshelf.history.PlaylistMix]):
     * your favorites in it, songs near them in your listening and, to mix or discover, songs Last.fm
     * finds similar to the top ones that you never played (looked up on [webExec]; without Last.fm
     * the playlist is mixed from your own). On [statsExec].
     */
    private fun genrePlaylist(s: Stats?, msg: JSONObject) {
        if (s == null) return push("onPlaylist") { JSONObject().put("empty", true) }
        val key = msg.optString("key")
        val size = msg.optInt("size", 30).coerceIn(10, 200)
        val mode = msg.optString("mode").ifEmpty { "mix" }
        val seed = msg.optLong("seed")
        val (core, near) = s.genrePicks(key)
        val label = s.genreLabel(key)
        val note = when (mode) {
            "yours" -> "Your most played $label, and songs you played around them"
            "discover" -> "Your $label favorites, and many songs like them you haven't heard"
            else -> "Your $label favorites, songs from around them, and a few new ones"
        }
        fun done(fresh: List<app.recordshelf.history.Pick>) {
            val list = app.recordshelf.history.PlaylistMix.mix(core, near, fresh, size, mode, seed)
            push("onPlaylist") {
                JSONObject().put("kind", "genre").put("key", key).put("label", label).put("size", size).put("mode", mode).put("seed", seed)
                    .put("name", "$label mix").put("note", note).put("tracks", pickJson(list))
            }
        }
        val c = lastfm()
        if (mode == "yours" || c == null || core.isEmpty()) return done(emptyList())
        webExec.execute {
            // Songs similar to the top ones, by their summed likeness, that you never played.
            val score = HashMap<String, Pair<app.recordshelf.history.Pick, Double>>()
            try {
                for (p in core.take(8)) {
                    val list = similar[p.key] ?: c.similarTracks(p.artist, p.title, 40).also { similar[p.key] = it }
                    for ((a, t, m) in list) {
                        if (a.isBlank() || t.isBlank() || s.played(a, t)) continue
                        val q = app.recordshelf.history.Pick(a, t, "", 0, "new")
                        score.merge(q.key, q to m) { x, y -> x.first to x.second + y.second }
                    }
                }
            } catch (_: Exception) {
                // Offline or Last.fm busy: what was found so far, or only your own.
            }
            done(score.values.sortedByDescending { it.second }.map { it.first })
        }
    }

    /** A check of the history against Last.fm is under way ([checkHistoryInner]). */
    @Volatile private var checking = false

    /**
     * Checks the stored history against Last.fm: all scrobbles, each year's (counts only) and your
     * most played artists' plays. [repair]: years Last.fm has more scrobbles in are fetched again
     * (what is there already is skipped) and counted once more. [quiet]: the weekly check, which
     * repairs without showing anything. On [historyExec].
     */
    private fun checkHistoryInner(repair: Boolean, quiet: Boolean = false) {
        val c = lastfm()
        val user = prefs.lastfmUser
        fun report(o: JSONObject) { if (!quiet) push("onHistoryCheck") { o } }
        if (c == null || user.isEmpty()) return report(JSONObject().put("error", "Connect Last.fm first."))
        if (!prefs.historyDone || importing) return report(JSONObject().put("error", "Your history is still being imported. Check it once that is done."))
        if (checking) return
        checking = true
        try {
            report(JSONObject().put("busy", true).put("step", "Counting your scrobbles on Last.fm…"))
            val (lastfmTotal, registered) = c.userInfo(user)
            val zone = java.time.ZoneId.systemDefault()
            fun yearOf(sec: Long) = java.time.Instant.ofEpochSecond(sec).atZone(zone).year
            fun yearStart(y: Int) = java.time.LocalDate.of(y, 1, 1).atStartOfDay(zone).toEpochSecond()
            val nowYear = java.time.LocalDate.now(zone).year
            val firstYear = listOf(registered, db.oldest()).filter { it > 0 }.minOrNull()?.let { yearOf(it) } ?: nowYear
            val years = (firstYear..nowYear).map { y -> yearStart(y) to yearStart(y + 1) - 1 }
            val sync = historySync(c, user)
            val count = { f: Long, t: Long -> c.recentTracks(user, f, t, 1, limit = 1).total }
            var stretches = sync.compare(years, count)
            var added = 0
            if (repair && stretches.any { it.missing >= HistorySync.MISSING_MIN }) {
                added = sync.repair(stretches) { s, p ->
                    report(JSONObject().put("busy", true).put("step", "Fetching ${yearOf(s.from)} again: ${p.fetched} of ${maxOf(p.total, p.fetched)}…"))
                }
                stretches = sync.compare(years, count)
                if (added > 0) {
                    histCount = db.count()
                    history = History.from(db.everything, hidden())
                    dropStats()
                    statsExec.execute { statsInner() }
                    pushState()
                }
            }
            prefs.historyChecked = System.currentTimeMillis()
            if (quiet) return
            // Your most played artists, by the names as scrobbled (before features are counted for the main artist).
            report(JSONObject().put("busy", true).put("step", "Comparing your most played artists…"))
            val raw = HashMap<String, Int>()
            db.forEach { _, a, _, _ -> raw.merge(Text.norm(a), 1, Int::plus) }
            val top = c.topArtists(user, 500)
            val differ = top.mapNotNull { (name, plays) ->
                val here = raw[Text.norm(name)] ?: 0
                if (plays - here >= maxOf(HistorySync.MISSING_MIN, plays / 20)) JSONObject().put("name", name).put("lastfm", plays).put("here", here) else null
            }
            report(
                JSONObject().put("lastfm", lastfmTotal).put("here", db.count()).put("added", added).put("checked", top.size)
                    .put("years", JSONArray(stretches.map { JSONObject().put("year", yearOf(it.from)).put("lastfm", it.lastfm).put("here", it.here) }))
                    .put("artists", JSONArray(differ.take(25))),
            )
        } catch (e: Exception) {
            report(JSONObject().put("error", "Last.fm didn't answer (${e.message ?: "offline"}). Try again in a moment."))
        } finally {
            checking = false
        }
    }

    private fun dropStats() {
        statsGen++
        stats = null
    }

    /** Whether there is any listening to make stats of: Last.fm's, or plays imported from a service's own export. */
    private fun hasHistory() = db.count() > 0 || db.importedCount() > 0

    // ---- TIDAL's own history: the export of your streams, read into the stats ----

    @Volatile private var importBusy = false
    @Volatile private var importDone = 0
    @Volatile private var importError = ""

    /**
     * The plays in TIDAL's "streaming.csv" (from the data export it sends on request) join the history: the years
     * before Last.fm, and what Last.fm missed since. They replace an earlier import, stay on this phone (Last.fm
     * takes no plays that old) and are named by the Fix names rules like any scrobble.
     */
    fun importTidal(uri: android.net.Uri) {
        if (importBusy) return
        importBusy = true
        importError = ""
        importDone = 0
        pushState()
        historyExec.execute {
            try {
                fun open() = ctx.contentResolver.openInputStream(uri)?.bufferedReader() ?: throw IOException("Couldn't open the file.")
                // Once through the whole file first: how long each song is, and whether it is TIDAL's file at all.
                val lengths = open().use { TidalImport.lengths(it) }
                val known = db.lengths()
                val rules = FixRules.active(prefs.fixPresets, FixRules.parse(prefs.fixRules))
                val batch = ArrayList<ImportedPlay>(2000)
                var ok = false
                db.beginImport()
                val s = try {
                    open().use { r ->
                        TidalImport.plays(r, lengths, { a, t -> known[Stats.lengthKey(a, t)] ?: 0 }) { p ->
                            val f = FixRules.apply(Scrobble(p.artist, p.track, p.album, timestamp = p.ts), rules)
                            if (f.artist.isBlank() || f.track.isBlank()) return@plays
                            batch += ImportedPlay(p.ts, f.artist, f.track, f.album, p.est)
                            if (batch.size == 2000) {
                                db.addImported(batch, "tidal", pending = true)
                                batch.clear()
                                importDone += 2000
                                pushState()
                            }
                        }
                    }.also {
                        db.addImported(batch, "tidal", pending = true)
                        ok = true
                    }
                } finally {
                    db.endImport(ok)
                }
                val span = db.importedSpan()
                prefs.tidalImport = JSONObject().put("plays", db.importedCount()).put("used", db.importedUsed()).put("from", span.first * 1000).put("to", span.second * 1000)
                    .put("rows", s.rows).put("short", s.short).put("videos", s.videos).put("at", System.currentTimeMillis()).toString()
                history = History.from(db.everything, hidden())
                dropStats()
                statsExec.execute { statsInner() }
                lastfm()?.let { c -> historyExec.execute { fetchTagsInner(c) } }
            } catch (e: IOException) {
                importError = e.message ?: "The file couldn't be read."
            } catch (_: Exception) {
                importError = "The file couldn't be read."
            } finally {
                importBusy = false
                pushState()
            }
        }
    }

    /** What Settings shows of the import: how many plays, from when to when, and whether one is running. */
    private fun tidalImportJson(): JSONObject {
        val o = try {
            JSONObject(prefs.tidalImport.ifEmpty { "{}" })
        } catch (_: Exception) {
            JSONObject()
        }
        return o.put("busy", importBusy).put("done", importDone).put("error", importError)
    }

    /** When [retryFreshMisses] last looked (ms). */
    @Volatile private var freshMissesAt = 0L

    /**
     * Covers none was found for, of albums played in the last two weeks, are asked again after a day (else after a
     * month): a new album is often played, its singles under its name, before Deezer lists it ("Friend World").
     */
    private fun retryFreshMisses(h: History) {
        val now = System.currentTimeMillis()
        if (now - freshMissesAt < HOUR) return
        freshMissesAt = now
        val keys = HashSet<String>()
        for (i in h.lowerBound(now / 1000 - FRESH_MISS_DAYS * 24 * 3600) until h.size) if (h.album[i] >= 0) keys += h.albumKeys[h.album[i]]
        if (keys.isNotEmpty()) runCatching { db.dropMissedImages(keys, now - 24 * HOUR) }
    }

    /** The stats for the current history, built on first use (and again on a new day). */
    private fun currentStats(): Stats? {
        val today = java.time.LocalDate.now().toEpochDay()
        stats?.takeIf { statsDay == today }?.let { return it }
        // Kept only if nothing changed meanwhile: else a slow build would put older stats over newer ones.
        val gen = statsGen
        val h = history ?: if (hasHistory()) History.from(db.everything, hidden()).also { if (statsGen == gen) history = it } else return null
        retryFreshMisses(h)
        return Stats(h, java.time.ZoneId.systemDefault(), System.currentTimeMillis() / 1000, collection, Genres(collection, db.tags()), vinylListens(), db.images(), yearsSnapshot(), globalsSnapshot(), db.lengths(), prefs.ownCovers, known())
            .also {
                if (statsGen == gen) {
                    stats = it
                    statsDay = today
                }
            }
    }

    /**
     * Every record play scrobbled from here, for the stats. Kept in the history database, apart
     * from the activity log (which only keeps the latest 2,500 of everything); filled from the log
     * the first time.
     */
    private fun vinylListens(): List<Listen> {
        if (!vinylSeeded) {
            vinylSeeded = true
            if (db.vinylCount() == 0) db.addVinyl(activity.listens { it.optString("src") == "vinyl" && it.optString("status") == "sent" })
        }
        return db.vinyl()
    }

    /** What the Stats tab and the story show: everything, or only the scrobbles of [statsGenre], [statsDecade] and/or [statsVinyl]. */
    private fun shownStats(): Stats? {
        val base = currentStats() ?: return null
        val g = statsGenre
        val d = statsDecade
        val v = statsVinyl
        if (g.isEmpty() && d == 0 && !v) return base
        val key = "$g|$d|$v"
        filteredStats?.takeIf { filteredKey == key && filteredBase === base }?.let { return it }
        val h = history ?: return base
        val inGenre = if (g.isEmpty()) null else base.inGenre(g)
        val inDecade = if (d == 0) null else base.inDecade(d)
        val onRecord = if (v) base.onRecord() else null
        val sub = h.filter { i -> (inGenre == null || inGenre(i)) && (inDecade == null || inDecade(i)) && (onRecord == null || onRecord(i)) }
        // Only the record plays among the scrobbles shown (at the same second as one of them).
        val vinyl = vinylListens().filter { v -> sub.lowerBound(v.ts).let { it < sub.size && sub.ts[it] == v.ts } }
        return Stats(sub, java.time.ZoneId.systemDefault(), System.currentTimeMillis() / 1000, collection, Genres(collection, db.tags()), vinyl, db.images(), yearsSnapshot(), globalsSnapshot(), db.lengths(), prefs.ownCovers)
            .also {
                filteredStats = it
                filteredKey = key
                filteredBase = base
            }
    }

    /**
     * Release years for the albums behind most of the history, a discography at a time from
     * Deezer (titles not found are kept as 0, not asked again). On [historyExec].
     */
    private fun yearsInner() {
        if (yearsBusy) return
        val s = currentStats() ?: return
        yearsTried = System.currentTimeMillis()
        val need = s.albumsNeedingYears()
        if (need.isEmpty()) return
        yearsBusy = true
        yearsDone = 0
        yearsTotal = need.size
        pushState()
        try {
            for ((artist, albums) in need) {
                val releases = try {
                    deezer.findArtist(artist)?.let { deezer.releases(it.id) }.orEmpty()
                } catch (_: Exception) {
                    break // offline or over the quota: the rest next time
                }
                synchronized(albumYears) { for ((title, key) in albums) albumYears[key] = Years.match(releases, title) }
                yearsDone++
                if (yearsDone % 25 == 0) {
                    saveYears()
                    pushState()
                }
                Thread.sleep(150) // Deezer allows 50 requests in 5 seconds
            }
        } finally {
            saveYears()
            yearsBusy = false
            dropStats()
            pushState()
            statsExec.execute { statsInner() }
        }
    }

    private val discoLock = Any()

    /**
     * An artist on Deezer ("artist") and their releases, kept a week on the phone so new releases show up. [pace]: a
     * pause after a lookup, for many in a row (Deezer allows 50 requests in 5 seconds).
     */
    private fun discoEntry(name: String, pace: Boolean = false): JSONObject {
        val key = Text.norm(name)
        fun read() = try {
            JSONObject(file(DISCO_FILE).read() ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }
        synchronized(discoLock) { read().optJSONObject(key) }?.takeIf { System.currentTimeMillis() - it.optLong("at") <= DISCO_TTL }?.let { return it }
        val a = deezer.findArtist(name)
        val c = JSONObject().put("at", System.currentTimeMillis())
        if (a != null) {
            c.put("artist", a.toJson()).put("releases", JSONArray(deezer.releases(a.id).map { it.toJson() }))
            // The photo comes along for free.
            if (a.photo.isNotEmpty() && Stats.photoKey(name) !in db.images().keys) db.saveImage(Stats.photoKey(name), a.photo)
        }
        synchronized(discoLock) {
            val cache = read()
            cache.put(key, c)
            // Keep the most recent lookups only.
            val keys = cache.keys().asSequence().toList()
            if (keys.size > DISCO_MAX) keys.sortedBy { cache.getJSONObject(it).optLong("at") }.take(keys.size - DISCO_MAX).forEach { cache.remove(it) }
            file(DISCO_FILE).write(cache.toString())
        }
        if (pace) Thread.sleep(250)
        return c
    }

    /**
     * An artist's discography from Deezer (kept a week on the phone, so new releases show up),
     * with your plays and your shelf. On [webExec].
     */
    private fun discographyJson(name: String): JSONObject {
        val c = discoEntry(name)
        val artist = c.optJSONObject("artist") ?: return JSONObject().put("none", true)
        val rel = c.optJSONArray("releases") ?: JSONArray()
        val releases = (0 until rel.length()).map { DzRelease.fromJson(rel.getJSONObject(it)) }
        val s = currentStats()
        val shelf = collection.associateBy { Text.albumKey(it.artist, it.title) }
        val items = Discography.json(Discography.merge(releases) { t -> s?.editionRoot(name, t) }, { t -> s?.albumPlaysOf(name, t) ?: 0 }, { t -> shelf[Text.albumKey(name, t)]?.releaseId ?: 0L })
        return JSONObject().put("artist", DzArtist.fromJson(artist).toJson()).put("items", items)
    }

    // ---- for you: music you haven't played, from what your artists point to (history/Discover) ----

    private val discoverExec = serial()
    private val topsAsked: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())
    @Volatile private var discoverBusy = false
    @Volatile private var discoverDone = 0
    @Volatile private var discoverTotal = 0
    @Volatile private var discoverError = ""
    @Volatile private var discoverTried = 0L
    private const val DISCOVER_FILE = "discover.json"

    /** Who is like an artist is looked up again after a month, their songs and genres too; the suggestions are looked through weekly. */
    private const val LIKES_TTL = 30L * 24 * HOUR
    private const val DISCOVER_EVERY = 7L * 24 * HOUR

    /** After a look that failed (offline), the next one waits this long. */
    private const val DISCOVER_RETRY = 10L * 60_000

    /** The suggestions whose genres, songs and photos are looked up ahead, and the songs in the weekly mix. */
    private const val DISCOVER_SHOWN = 20

    /** The new artists "More like them" starts from. */
    private const val LIKE_SEEDS = 8
    private const val MIX_SIZE = 30

    /**
     * What was looked up, kept in discover.json: "likes" (per artist, normed: "at" and "list", who is like them), "tops"
     * (their best-known songs), "genre", "releases" (new ones by your artists) and "at" (when all was last looked through).
     * Only touched while holding it.
     */
    private val dstore: JSONObject by lazy { runCatching { JSONObject(file(DISCOVER_FILE).read() ?: "{}") }.getOrNull() ?: JSONObject() }

    private fun dpart(name: String): JSONObject = synchronized(dstore) { dstore.optJSONObject(name) ?: JSONObject().also { dstore.put(name, it) } }

    private fun saveDiscover() = synchronized(dstore) { file(DISCOVER_FILE).write(dstore.toString()) }

    private fun fresh(o: JSONObject?, ttl: Long) = o != null && System.currentTimeMillis() - o.optLong("at") < ttl

    private fun likesFresh(name: String) = synchronized(dstore) { fresh(dpart("likes").optJSONObject(Text.norm(name)), LIKES_TTL) }

    /** The artists you said aren't for you (normed name to {name, seeds: the artists of yours that led to them}). */
    private fun discoverNo(): JSONObject = runCatching { JSONObject(prefs.discoverNo) }.getOrNull() ?: JSONObject()

    private fun discoverWeaker(): Map<String, Int> = runCatching { JSONObject(prefs.discoverWeaker) }.getOrNull()
        ?.let { o -> o.keys().asSequence().associateWith { o.optInt(it) } }.orEmpty()

    /** Who Last.fm and Deezer find like [name] (Deezer's artist [deezerId], else looked up), kept. Throws when neither answers. */
    private fun fetchLikes(c: LastfmClient?, name: String, deezerId: Long? = null) {
        var answered = false
        val lf = try {
            c?.similarArtists(name)?.also { answered = true }.orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        Thread.sleep(220) // Last.fm asks for at most 5 requests a second
        val dz = try {
            val id = deezerId?.takeIf { it > 0 } ?: deezer.findArtist(name)?.id?.also { Thread.sleep(150) }
            (if (id == null) emptyList() else deezer.related(id)).also { answered = true }
        } catch (_: Exception) {
            emptyList()
        }
        Thread.sleep(150)
        if (!answered) throw IOException("Neither Last.fm nor Deezer answered")
        val list = Discover.merge(lf, dz.map { Triple(it.name, it.fans, it.photo) })
        synchronized(dstore) { dpart("likes").put(Text.norm(name), JSONObject().put("at", System.currentTimeMillis()).put("list", JSONArray(list.map { it.toJson() }))) }
    }

    /** An artist's best-known songs on Last.fm (most played first), kept a month; null without Last.fm or if it failed. */
    private fun topsOf(c: LastfmClient?, name: String, lookUp: Boolean = true): List<String>? {
        val k = Text.norm(name)
        synchronized(dstore) { dpart("tops").optJSONObject(k)?.takeIf { fresh(it, LIKES_TTL) } }?.let { o ->
            val a = o.optJSONArray("songs") ?: JSONArray()
            return (0 until a.length()).map { a.getString(it) }
        }
        if (!lookUp || c == null) return null
        val songs = try {
            c.artistTopTracks(name, 15).map { it.first }.filter { it.isNotBlank() }
        } catch (_: Exception) {
            return null
        }
        Thread.sleep(220)
        synchronized(dstore) { dpart("tops").put(k, JSONObject().put("at", System.currentTimeMillis()).put("songs", JSONArray(songs))) }
        return songs
    }

    /** An artist's genre ("Garage punk") from their Last.fm tags, kept; "" if none is known. */
    private fun genreOf(c: LastfmClient?, name: String, lookUp: Boolean = true): String {
        val k = Text.norm(name)
        synchronized(dstore) { dpart("genre").optJSONObject(k)?.takeIf { fresh(it, LIKES_TTL) } }?.let { return it.optString("v") }
        if (!lookUp || c == null) return ""
        val v = try {
            val g = Genres(emptyList(), mapOf(Genres.artistKey(name) to Genres.fromTags(c.artistTopTags(name), name)))
            g.detail(name, null)?.let { d -> if (d.style.isNotEmpty()) g.styleLabel(d.style) else Genres.label(d.family) }.orEmpty()
        } catch (_: Exception) {
            return ""
        }
        Thread.sleep(220)
        synchronized(dstore) { dpart("genre").put(k, JSONObject().put("at", System.currentTimeMillis()).put("v", v)) }
        return v
    }

    /** The suggestions from what is kept, as far as the settings say. */
    private fun suggestions(s: Stats, max: Int = Discover.MAX): List<Suggestion> {
        val likes = HashMap<String, List<Like>>()
        synchronized(dstore) {
            val o = dpart("likes")
            for (k in o.keys()) o.optJSONObject(k)?.optJSONArray("list")?.let { a -> likes[k] = (0 until a.length()).map { Like.fromJson(a.getJSONObject(it)) } }
        }
        val no = discoverNo()
        return Discover.suggest(s.discoverSeeds(), likes, s::knows, no.keys().asSequence().toSet(), discoverWeaker(), prefs.discoverReach, max, tookSeeds())
    }

    /**
     * Looks through what your artists point to: Last.fm's similar artists and Deezer's related ones, new releases by your
     * artists, and the best suggestions' songs, genres and photos. Weekly, or now when [force]. On [discoverExec].
     */
    private fun discoverInner(force: Boolean) {
        if (discoverBusy || !prefs.discoverOn) return
        val now = System.currentTimeMillis()
        if (!force && (now - synchronized(dstore) { dstore.optLong("at") } < DISCOVER_EVERY || now - discoverTried < DISCOVER_RETRY)) return
        val s = currentStats() ?: return
        val seeds = s.discoverSeeds()
        if (seeds.isEmpty()) return
        val c = lastfm()
        discoverTried = now
        discoverBusy = true
        discoverError = ""
        discoverDone = 0
        discoverTotal = seeds.size
        pushDiscover()
        try {
            var fails = 0
            for ((i, seed) in seeds.withIndex()) {
                val name = seed.first
                val e = try {
                    discoEntry(name, pace = true).also { fails = 0 }
                } catch (_: Exception) {
                    fails++
                    null
                }
                if (!likesFresh(name)) {
                    try {
                        fetchLikes(c, name, e?.optJSONObject("artist")?.optLong("id"))
                        fails = 0
                    } catch (_: Exception) {
                        fails++
                    }
                }
                discoverDone = i + 1
                if (fails >= 4) throw IOException("offline")
                if (i % 5 == 4) pushDiscover()
            }
            synchronized(dstore) {
                dstore.remove("releases")
                dstore.put("at", now)
            }
            discoverExtras(s, c)
        } catch (_: Exception) {
            discoverError = "Couldn't reach Last.fm and Deezer. Suggestions come once you're online."
        } finally {
            saveDiscover()
            discoverBusy = false
            pushDiscover()
        }
    }

    /**
     * For the best suggestions: who is like them (for "further out"), their songs (for the mix), their genres, and
     * photos Deezer didn't bring along. On [discoverExec].
     */
    private fun discoverExtras(s: Stats, c: LastfmClient?) {
        val best = suggestions(s, DISCOVER_SHOWN)
        for (x in best.take(Discover.STEP2)) if (!likesFresh(x.name)) runCatching { fetchLikes(c, x.name) }
        for (x in best.take(Discover.mixArtists(MIX_SIZE))) topsOf(c, x.name)
        for (x in best) genreOf(c, x.name)
        saveDiscover()
        for (x in best) {
            startOf(c, x.name)
            suggestedReleases(x.name)
        }
        saveDiscover()
        val photos = s.needPhotos(best.filter { it.photo.isEmpty() }.map { it.name })
        if (photos.isNotEmpty()) historyExec.execute { fetchPhotosInner(photos) { pushDiscover() } }
    }

    private fun pushDiscover() = discoverExec.execute { push("onDiscover") { discoverJson() } }

    /** The For you tab: the suggestions (best first, with why), new releases by your artists, and how the look is going. */
    private fun discoverJson(): JSONObject {
        val o = JSONObject().put("on", prefs.discoverOn).put("reach", prefs.discoverReach).put("busy", discoverBusy)
            .put("done", discoverDone).put("total", discoverTotal).put("error", discoverError).put("no", discoverNo().length())
            .put("lastfm", lastfm() != null)
        if (!prefs.discoverOn) return o
        val s = currentStats() ?: return o
        val sugg = suggestions(s)
        o.put("built", synchronized(dstore) { dstore.optLong("at") })
        o.put("artists", JSONArray(sugg.map { suggestionJson(it, s) }))
        val found = relFound(s)
        val fans = s.releaseFans.associateBy { Text.norm(it.name) }
        fun rel(list: List<app.recordshelf.history.NewRelease>) = JSONArray(list.map { r ->
            val f = fans[Text.norm(r.artist)]
            r.toJson().put("fanPlays", f?.plays ?: 0).put("owned", f?.owned ?: 0).put("last", (f?.last ?: 0L) * 1000)
        })
        o.put("releases", rel(found.out)).put("upcoming", rel(found.soon)).put("back", rel(found.back))
        o.put("relBusy", relBusy).put("relDone", relDone).put("relTotal", relTotal).put("relArtists", fans.size)
            .put("relHidden", relHidden().size).put("relChecked", synchronized(dstore) { dpart("rel").length() })
        o.put("sugReleases", suggestedReleasesJson(sugg))
        o.put("starts", startsJson(sugg))
        o.put("recent", recentJson(s, sugg))
        o.put("gaps", gapsJson(s))
        o.put("took", tookJson(s, sugg))
        o.put("sim", simJson(s))
        o.put("mix", Discover.mixArtists(MIX_SIZE))
        return o
    }

    private fun suggestionJson(x: Suggestion, s: Stats): JSONObject {
        val most = x.because.firstOrNull()?.second ?: 1.0
        return JSONObject().put("name", x.name).put("photo", x.photo.ifEmpty { s.artistPhoto(x.name) }).put("fans", x.fans)
            .put("genre", genreOf(null, x.name, lookUp = false)).put("via", x.via)
            .put("because", JSONArray(x.because.take(5).map { (n, v) -> JSONObject().put("name", n).put("v", v / most).put("like", x.like[n] ?: 0.0) }))
    }

    /** You said [name] isn't for you: left out from now on, and the artists of yours that led to them count a little less. */
    private fun discoverRefuse(name: String) {
        val s = currentStats() ?: return
        val k = Text.norm(name)
        val no = discoverNo()
        if (no.has(k)) return
        val led = suggestions(s).firstOrNull { Text.norm(it.name) == k }?.because?.take(3)?.map { it.first }.orEmpty()
        no.put(k, JSONObject().put("name", name).put("seeds", JSONArray(led)))
        prefs.discoverNo = no.toString()
        val w = runCatching { JSONObject(prefs.discoverWeaker) }.getOrNull() ?: JSONObject()
        for (n in led) w.put(Text.norm(n), w.optInt(Text.norm(n)) + 1)
        prefs.discoverWeaker = w.toString()
    }

    /** Takes back a "not for me" (and what it did to the artists that led there). */
    private fun discoverUnrefuse(name: String) {
        val k = Text.norm(name)
        val no = discoverNo()
        val e = no.optJSONObject(k) ?: return
        no.remove(k)
        prefs.discoverNo = no.toString()
        val w = runCatching { JSONObject(prefs.discoverWeaker) }.getOrNull() ?: JSONObject()
        val led = e.optJSONArray("seeds") ?: JSONArray()
        for (i in 0 until led.length()) {
            val n = Text.norm(led.getString(i))
            val v = w.optInt(n) - 1
            if (v > 0) w.put(n, v) else w.remove(n)
        }
        prefs.discoverWeaker = w.toString()
    }

    /**
     * Artists like the ones in [msg]'s "seeds" (a period's new artists, each with its plays) that you haven't played,
     * the way the suggestions are found for your top artists. On [discoverExec].
     */
    private fun discoverLike(msg: JSONObject) {
        val s = currentStats() ?: return
        val req = msg.optInt("req")
        val a = msg.optJSONArray("seeds") ?: JSONArray()
        val seeds = (0 until a.length()).map { a.getJSONObject(it) }.map { it.optString("name") to maxOf(1.0, it.optDouble("plays", 1.0)) }
            .filter { it.first.isNotBlank() }.take(LIKE_SEEDS)
        val c = lastfm()
        var answered = 0
        for ((n, _) in seeds) {
            if (likesFresh(n)) answered++ else if (runCatching { fetchLikes(c, n) }.isSuccess) answered++
        }
        val likes = HashMap<String, List<Like>>()
        synchronized(dstore) {
            val o = dpart("likes")
            for ((n, _) in seeds) o.optJSONObject(Text.norm(n))?.optJSONArray("list")?.let { l -> likes[Text.norm(n)] = (0 until l.length()).map { Like.fromJson(l.getJSONObject(it)) } }
        }
        val sugg = Discover.suggest(seeds, likes, s::knows, discoverNo().keys().asSequence().toSet(), discoverWeaker(), prefs.discoverReach)
        for (x in sugg.take(DISCOVER_SHOWN)) genreOf(c, x.name)
        saveDiscover()
        push("onDiscoverLike") {
            JSONObject().put("req", req).put("artists", JSONArray(sugg.map { suggestionJson(it, s) }))
                .put("error", if (seeds.isNotEmpty() && answered == 0) "Couldn't reach Last.fm and Deezer. Try again once you're online." else "")
        }
    }

    /** Why one artist is suggested, and their best-known songs to start with. On [discoverExec]. */
    private fun discoverWhy(msg: JSONObject) {
        val name = msg.optString("name")
        val s = currentStats() ?: return
        val c = lastfm()
        val x = suggestions(s).firstOrNull { Text.norm(it.name) == Text.norm(name) }
        val songs = topsOf(c, name).orEmpty()
        val j = (if (x != null) suggestionJson(x, s) else JSONObject().put("name", name).put("photo", s.artistPhoto(name)).put("genre", genreOf(c, name)))
            .put("songs", JSONArray(songs.take(5))).put("req", msg.optInt("req")).put("known", s.knows(name))
        push("onDiscoverWhy") { j }
    }

    /**
     * The discovery mix: [MIX_SIZE] songs (or the size asked for) by suggested artists, two at most from each. Seed 0 is
     * the week's own mix; another seed makes another. On [discoverExec].
     */
    private fun discoverMix(msg: JSONObject) {
        val s = currentStats() ?: return push("onPlaylist") { JSONObject().put("empty", true) }
        val size = msg.optInt("size", MIX_SIZE).coerceIn(10, 100)
        val asked = msg.optLong("seed")
        val seed = if (asked == 0L) Discover.weekSeed(java.time.LocalDate.now()) else asked
        val sugg = suggestions(s)
        val c = lastfm()
        // Their songs: kept, else looked up now for the ones this mix could pick.
        val tops = HashMap<String, List<String>>()
        for ((i, x) in sugg.withIndex()) topsOf(c, x.name, lookUp = i < Discover.mixArtists(size) + 10)?.let { tops[Text.norm(x.name)] = it }
        saveDiscover()
        val list = Discover.mix(sugg, tops, size, seed)
        push("onPlaylist") {
            JSONObject().put("kind", "discover").put("seed", asked).put("size", size).put("reach", prefs.discoverReach)
                .put("name", "Discovery mix").put("note", "Songs you haven't played, by artists your listening points to")
                .put("tracks", pickJson(list))
        }
    }

    /** An artist's best-known songs on Last.fm, as a playlist to start with them. On [discoverExec]. */
    private val NEW_MIX_SIZE = 60

    /** The new-releases playlist takes the songs of this many releases at most. */
    private val NEW_MIX_RELEASES = 20

    private fun artistBest(msg: JSONObject) {
        val name = msg.optString("artist")
        val songs = topsOf(lastfm(), name).orEmpty()
        push("onPlaylist") {
            JSONObject().put("kind", "artistBest").put("artist", name).put("name", "Best of $name").put("note", "Their most played songs on Last.fm")
                .put("tracks", pickJson(songs.map { Pick(name, it, "", 0, "new") }))
        }
    }

    /**
     * The songs of what your artists released lately (For you's "New from your artists", those already out),
     * from Deezer's tracklists, one release after the other in turn so no record comes in a block. On [webExec].
     */
    private fun newReleasesMix() {
        val s = currentStats()
        val found = s?.let { relFound(it) }
        val today = java.time.LocalDate.now().toString()
        val out = found?.let { it.out + it.back }.orEmpty().filter { it.id > 0 && it.date <= today }.take(NEW_MIX_RELEASES)
        val per = out.mapNotNull { r ->
            try {
                val a = deezer.album(r.id)
                a.tracks.map { (t, _) -> Pick(r.artist, t, a.title, 0, "new", r.cover.ifEmpty { a.cover }) }
            } catch (_: Exception) {
                null
            }
        }
        val tracks = (0 until (per.maxOfOrNull { it.size } ?: 0)).flatMap { k -> per.mapNotNull { it.getOrNull(k) } }.distinctBy { it.key }.take(NEW_MIX_SIZE)
        push("onPlaylist") {
            if (tracks.isEmpty()) JSONObject().put("kind", "newReleases").put("empty", true)
            else JSONObject().put("kind", "newReleases").put("name", "New releases").put("note", "What your artists put out lately")
                .put("tracks", pickJson(tracks))
        }
    }


    // ---- new releases by your artists, albums you're missing, and what your listening lately points to (history/Releases) ----

    private val releaseExec = serial()
    private val mbz by lazy { MusicBrainzClient() }
    @Volatile private var relBusy = false
    @Volatile private var relDone = 0
    @Volatile private var relTotal = 0

    /** How long one artist's releases count as looked at: a day for the ones you care about most, else most of a week. */
    private const val REL_DAY = 20L * HOUR
    private const val REL_WEEK = 6L * 24 * HOUR

    /** Albums you're missing: looked for among this many of your most played artists, weekly, two of each at most. */
    private const val GAP_ARTISTS = 30
    private const val GAP_EVERY = 7L * 24 * HOUR

    /** Rows of artists, albums or releases on Explore hold this many. */
    private const val ROW_MAX = 15

    /**
     * Looks for new releases by your artists ([Stats.releaseFans]): their releases on Deezer, the [Releases.DAILY] you
     * care about most every day and the rest weekly; the albums MusicBrainz knows are coming for the first
     * [Releases.ANNOUNCED], weekly; and, weekly, the albums you're missing by your most played artists. Each artist is
     * kept as it is done, so the lists fill as it goes. On [releaseExec].
     */
    private fun releasesInner() {
        if (relBusy || !prefs.discoverOn) return
        val s = currentStats() ?: return
        val fans = s.releaseFans
        if (fans.isEmpty()) return
        val now = System.currentTimeMillis()
        val today = java.time.LocalDate.now()
        val due = synchronized(dstore) {
            val rel = dpart("rel")
            fans.filterIndexed { i, f -> !fresh(rel.optJSONObject(Text.norm(f.name)), if (i < Releases.DAILY) REL_DAY else REL_WEEK) }
        }
        val announcedDue = synchronized(dstore) { fans.take(Releases.ANNOUNCED).filter { !fresh(dpart("mb").optJSONObject(Text.norm(it.name)), REL_WEEK) } }
        val gapsDue = synchronized(dstore) { !fresh(dstore.optJSONObject("gaps"), GAP_EVERY) }
        if (due.isEmpty() && announcedDue.isEmpty() && !gapsDue && songsDue(today).isEmpty()) return
        relBusy = true
        relDone = 0
        relTotal = due.size + announcedDue.size
        pushDiscover()
        try {
            var fails = 0
            fun failed() {
                if (++fails >= 4) throw IOException("offline")
            }
            for (f in due) {
                val key = Text.norm(f.name)
                try {
                    val old = synchronized(dstore) { dpart("rel").optJSONObject(key) }
                    val id = old?.optLong("id")?.takeIf { it > 0 } ?: deezer.findArtist(f.name)?.id ?: 0L
                    val list = if (id > 0) Releases.keep(deezer.releases(id, maxPages = 2), today) else emptyList()
                    synchronized(dstore) {
                        dpart("rel").put(key, JSONObject().put("n", f.name).put("id", id).put("at", now).put("list", JSONArray(list.map { it.toJson() })))
                    }
                    fails = 0
                } catch (_: Exception) {
                    failed()
                }
                relDone++
                if (relDone % 10 == 0) {
                    saveDiscover()
                    pushDiscover()
                }
            }
            for (f in announcedDue) {
                val key = Text.norm(f.name)
                try {
                    val old = synchronized(dstore) { dpart("mb").optJSONObject(key) }
                    val id = old?.optString("id")?.ifEmpty { null } ?: mbz.artistId(f.name)
                    val soon = if (id.isEmpty()) emptyList() else mbz.releaseGroups(id).filter { it.date.length == 10 && it.date > today.toString() }
                    synchronized(dstore) {
                        dpart("mb").put(key, JSONObject().put("n", f.name).put("id", id).put("at", now).put("list", JSONArray(soon.map { it.toJson() })))
                    }
                    fails = 0
                } catch (_: Exception) {
                    failed()
                }
                relDone++
            }
            if (gapsDue) {
                saveDiscover()
                pushDiscover()
                gapsInner(s, today)
            }
            // The songs of new albums that may have had a release lead up to them under another name. One Deezer
            // won't list is noted and asked again a day later.
            for (id in songsDue(today)) {
                val titles = try {
                    deezer.album(id).tracks.map { it.first }
                } catch (_: Exception) {
                    null
                }
                synchronized(dstore) {
                    dpart("relSongs").put(id.toString(), JSONObject().put("at", now).put("list", JSONArray(titles.orEmpty())).put("failed", titles == null))
                }
            }
        } catch (_: Exception) {
            // Offline: what was found so far; the rest is asked next time.
        } finally {
            // Artists no longer among yours aren't kept.
            val keys = fans.map { Text.norm(it.name) }.toSet()
            synchronized(dstore) {
                for (part in listOf(dpart("rel"), dpart("mb"))) part.keys().asSequence().toList().filter { it !in keys }.forEach { part.remove(it) }
                // Songs of albums long out of the window aren't needed again.
                val songs = dpart("relSongs")
                songs.keys().asSequence().toList().filter { now - (songs.optJSONObject(it)?.optLong("at") ?: 0L) > 120 * 24 * HOUR }.forEach { songs.remove(it) }
            }
            relBusy = false
            try {
                saveDiscover()
            } catch (_: Exception) {
                // A full disk: the lists are in memory, and written the next time.
            }
            pushDiscover()
        }
    }

    /** The albums you're missing by your [GAP_ARTISTS] most played artists, from their discographies (each kept a week). */
    private fun gapsInner(s: Stats, today: java.time.LocalDate) {
        val shelf = collection.map { Text.albumKey(it.artist, it.title) }.toSet()
        val top = s.releaseFans.filter { it.plays > 0 }.sortedByDescending { it.plays }.take(GAP_ARTISTS)
        val out = JSONArray()
        var fails = 0
        for (f in top) {
            val c = try {
                discoEntry(f.name, pace = true).also { fails = 0 }
            } catch (_: Exception) {
                if (++fails >= 4) return
                continue
            }
            val a = c.optJSONArray("releases") ?: continue
            val releases = (0 until a.length()).map { DzRelease.fromJson(a.getJSONObject(it)) }
            val g = Releases.gaps(
                f.name, Discography.merge(releases) { t -> s.editionRoot(f.name, t) }, { t -> s.albumPlaysOf(f.name, t) },
                { t -> Text.albumKey(f.name, t) in shelf }, today,
            ) ?: continue
            for (m in g.missing.take(2)) out.put(m.toJson().put("artist", f.name).put("heard", g.heard).put("of", g.of).put("fanPlays", f.plays))
        }
        synchronized(dstore) { dstore.put("gaps", JSONObject().put("at", System.currentTimeMillis()).put("list", out)) }
    }

    /** New albums whose songs aren't known yet but would tell whether a release led up to them ([Releases.needSongs]). */
    private fun songsDue(today: java.time.LocalDate): List<Long> = synchronized(dstore) {
        val rel = dpart("rel")
        val have = dpart("relSongs")
        rel.keys().asSequence().toList().flatMap { k ->
            val a = rel.optJSONObject(k)?.optJSONArray("list") ?: return@flatMap emptyList()
            Releases.needSongs((0 until a.length()).map { DzRelease.fromJson(a.getJSONObject(it)) }, today).map { it.id }
        }.filter { id ->
            val e = have.optJSONObject(id.toString())
            e == null || (e.optBoolean("failed") && System.currentTimeMillis() - e.optLong("at") > 24 * HOUR)
        }.distinct()
    }

    /** An album's song titles as looked up for [Releases.find], or null (not asked yet, or Deezer wouldn't say). */
    private fun relSongs(r: DzRelease): List<String>? = synchronized(dstore) {
        dpart("relSongs").optJSONObject(r.id.toString())?.takeIf { !it.optBoolean("failed") }?.optJSONArray("list")?.strings()
    }

    /** The releases you said you don't want to see (album keys). */
    private fun relHidden(): Set<String> = synchronized(dstore) { dpart("relNo").keys().asSequence().toSet() }

    /** New releases by your artists, from what was looked up (see [releasesInner]), with your plays of each. */
    private fun relFound(s: Stats): Releases.Found {
        val today = java.time.LocalDate.now()
        val fans = s.releaseFans.associateBy { Text.norm(it.name) }
        val by = LinkedHashMap<String, List<DzRelease>>()
        synchronized(dstore) {
            val rel = dpart("rel")
            for (k in rel.keys()) {
                if (k !in fans) continue
                val e = rel.optJSONObject(k) ?: continue
                val a = e.optJSONArray("list") ?: continue
                by[e.optString("n")] = (0 until a.length()).map { DzRelease.fromJson(a.getJSONObject(it)) }
            }
            val mb = dpart("mb")
            for (k in mb.keys()) {
                if (k !in fans) continue
                val e = mb.optJSONObject(k) ?: continue
                val a = e.optJSONArray("list") ?: continue
                val name = e.optString("n")
                val have = by[name].orEmpty()
                val soon = Releases.announced((0 until a.length()).map { MbGroup.fromJson(a.getJSONObject(it)) }, have, today)
                if (soon.isNotEmpty()) by[name] = have + soon
            }
        }
        return Releases.find(by, fans, today, relHidden(), { a, t, type -> if (type == "single") s.songPlaysOf(a, t) else s.albumPlaysOf(a, t) }) { relSongs(it) }
    }

    /**
     * An album to start with for a suggested artist: their most played one on Last.fm that isn't live, remixed or a best-of,
     * with its cover (Last.fm's, else Deezer's). Kept a month. On [discoverExec].
     */
    private fun startOf(c: LastfmClient?, name: String) {
        val k = Text.norm(name)
        if (c == null || synchronized(dstore) { fresh(dpart("start").optJSONObject(k), LIKES_TTL) }) return
        val pick = try {
            c.artistTopAlbums(name, 8).firstOrNull { Releases.toStartWith(it.first) }
        } catch (_: Exception) {
            return
        }
        Thread.sleep(220)
        val cover = pick?.second?.ifEmpty { null } ?: pick?.let { runCatching { deezer.albumCover(name, it.first) }.getOrNull() }.orEmpty()
        synchronized(dstore) { dpart("start").put(k, JSONObject().put("at", System.currentTimeMillis()).put("title", pick?.first.orEmpty()).put("cover", cover)) }
    }

    /** A suggested artist's releases on Deezer (the recent ones), kept most of a week, for New from artists you might like. On [discoverExec]. */
    private fun suggestedReleases(name: String) {
        val k = Text.norm(name)
        if (synchronized(dstore) { fresh(dpart("srel").optJSONObject(k), REL_WEEK) }) return
        val list = try {
            val id = deezer.findArtist(name)?.id ?: 0L
            if (id > 0) Releases.keep(deezer.releases(id, maxPages = 1), java.time.LocalDate.now()) else emptyList()
        } catch (_: Exception) {
            return
        }
        synchronized(dstore) {
            val part = dpart("srel")
            part.put(k, JSONObject().put("n", name).put("at", System.currentTimeMillis()).put("list", JSONArray(list.map { it.toJson() })))
            // Only the latest suggestions' are kept.
            val keys = part.keys().asSequence().toList()
            if (keys.size > 120) keys.sortedBy { part.getJSONObject(it).optLong("at") }.take(keys.size - 120).forEach { part.remove(it) }
        }
    }

    /** New releases (out lately) of the best suggestions, the best suggestions' first, with who of yours they're like. */
    private fun suggestedReleasesJson(sugg: List<Suggestion>): JSONArray {
        val best = sugg.take(DISCOVER_SHOWN)
        val by = LinkedHashMap<String, List<DzRelease>>()
        synchronized(dstore) {
            val part = dpart("srel")
            for (x in best) part.optJSONObject(Text.norm(x.name))?.optJSONArray("list")?.let { a -> by[x.name] = (0 until a.length()).map { DzRelease.fromJson(a.getJSONObject(it)) } }
        }
        // How good a suggestion is stands in for how much you care about the artist.
        val top = best.firstOrNull()?.score?.takeIf { it > 0 } ?: 1.0
        val fans = best.associate { Text.norm(it.name) to Fan(it.name, it.score / top, 0, 0, 0) }
        val found = Releases.find(by, fans, java.time.LocalDate.now(), relHidden()) { relSongs(it) }
        val like = best.associate { it.name to (it.via.ifEmpty { it.because.firstOrNull()?.first.orEmpty() }) }
        return JSONArray(found.out.take(ROW_MAX).map { it.toJson().put("like", like[it.artist].orEmpty()) })
    }

    /** An album to start with for each of the best suggestions that has one, with who of yours they're like. */
    private fun startsJson(sugg: List<Suggestion>): JSONArray = synchronized(dstore) {
        val part = dpart("start")
        JSONArray(
            sugg.take(DISCOVER_SHOWN).mapNotNull { x ->
                val e = part.optJSONObject(Text.norm(x.name))?.takeIf { it.optString("title").isNotEmpty() } ?: return@mapNotNull null
                JSONObject().put("artist", x.name).put("title", e.optString("title")).put("cover", e.optString("cover"))
                    .put("like", x.via.ifEmpty { x.because.firstOrNull()?.first.orEmpty() })
            }.take(ROW_MAX),
        )
    }

    /**
     * What the artists you played most in the last two weeks point to on their own ([Stats.recentLeaders]), each a row:
     * suggestions from that artist alone, leaving out the ones the main row already shows. Rows of fewer than four go.
     */
    private fun recentJson(s: Stats, sugg: List<Suggestion>): JSONArray {
        val shown = sugg.take(12).map { Text.norm(it.name) }.toSet()
        val no = discoverNo().keys().asSequence().toSet()
        val out = JSONArray()
        for ((name, plays) in s.recentLeaders()) {
            val likes = synchronized(dstore) {
                dpart("likes").optJSONObject(Text.norm(name))?.optJSONArray("list")?.let { a -> (0 until a.length()).map { Like.fromJson(a.getJSONObject(it)) } }
            } ?: continue
            val list = Discover.suggest(listOf(name to 1.0), mapOf(Text.norm(name) to likes), s::knows, no, discoverWeaker(), prefs.discoverReach, 30)
                .filter { Text.norm(it.name) !in shown }.take(ROW_MAX)
            if (list.size >= 4) out.put(JSONObject().put("seed", name).put("plays", plays).put("artists", JSONArray(list.map { suggestionJson(it, s) })))
        }
        return out
    }

    /** The albums you're missing (see [gapsInner]) that you still haven't played or put on your shelf. */
    private fun gapsJson(s: Stats): JSONArray {
        val shelf = collection.map { Text.albumKey(it.artist, it.title) }.toSet()
        val a = synchronized(dstore) { dstore.optJSONObject("gaps")?.optJSONArray("list")?.let { JSONArray(it.toString()) } } ?: return JSONArray()
        return JSONArray(
            (0 until a.length()).map { a.getJSONObject(it) }.filter {
                val artist = it.optString("artist")
                val title = it.optString("title")
                s.albumPlaysOf(artist, title) == 0 && Text.albumKey(artist, title) !in shelf
            }.take(ROW_MAX * 2),
        )
    }

    /** Per artist of yours (normed), how many of the suggestions they led to you took up. */
    private fun tookSeeds(): Map<String, Int> = synchronized(dstore) {
        val took = dpart("took")
        val m = HashMap<String, Int>()
        for (k in took.keys()) took.optJSONObject(k)?.optJSONArray("seeds")?.let { a -> for (i in 0 until a.length()) m.merge(Text.norm(a.getString(i)), 1, Int::plus) }
        m
    }

    /**
     * The suggestions you took up: the best ones shown are noted with when and why ("shown"); one you then played
     * [Discover.TOOK_PLAYS] times or more is taken up ("took"), and from then on the artists that led to it count a
     * little more ([tookSeeds]). Taken up first the latest, with your plays of them since.
     */
    private fun tookJson(s: Stats, sugg: List<Suggestion>): JSONArray {
        val now = System.currentTimeMillis()
        var changed = false
        val out = ArrayList<JSONObject>()
        synchronized(dstore) {
            val shown = dpart("shown")
            val took = dpart("took")
            for (x in sugg.take(DISCOVER_SHOWN)) {
                val k = Text.norm(x.name)
                if (shown.has(k) || took.has(k)) continue
                shown.put(k, JSONObject().put("n", x.name).put("at", now).put("seeds", JSONArray(x.because.take(3).map { it.first })))
                changed = true
            }
            for (k in shown.keys().asSequence().toList()) {
                val e = shown.optJSONObject(k) ?: continue
                val name = e.optString("n")
                if (!s.knows(name)) {
                    if (now - e.optLong("at") > 365L * 24 * HOUR) { shown.remove(k); changed = true }
                    continue
                }
                val since = s.playsSince(name, e.optLong("at") / 1000)
                if (since >= Discover.TOOK_PLAYS) {
                    took.put(k, JSONObject(e.toString()).put("took", now))
                    shown.remove(k)
                    changed = true
                } else if (s.artistPlaysOf(name) > since) {
                    // Played before it was suggested (an older scrobble came in): not a suggestion taken up.
                    shown.remove(k)
                    changed = true
                }
            }
            for (k in took.keys()) {
                val e = took.optJSONObject(k) ?: continue
                val name = e.optString("n")
                out += JSONObject().put("name", name).put("photo", s.artistPhoto(name)).put("plays", s.artistPlaysOf(name))
                    .put("since", e.optLong("at")).put("took", e.optLong("took")).put("seeds", e.optJSONArray("seeds") ?: JSONArray())
            }
        }
        if (changed) saveDiscover()
        return JSONArray(out.sortedByDescending { it.optLong("took") }.take(ROW_MAX))
    }

    /** For the mix of songs like your favorites: three of your top songs of the month for its picture, once Last.fm's are looked up for some. */
    private fun simJson(s: Stats): JSONObject? {
        val seeds = s.recentTopSongs()
        val known = synchronized(dstore) { seeds.count { dpart("sim").has(it.key) } }
        if (known < 3) return null
        return JSONObject().put("songs", JSONArray(seeds.take(3).map { JSONObject().put("artist", it.artist).put("title", it.title).put("album", it.album).put("cover", it.cover) }))
    }

    /**
     * What changes with your listening day by day, looked up when Explore opens: who is like the artists you played most
     * in the last two weeks, and the songs Last.fm finds similar to your top songs of the month (each kept a month).
     * True if anything new came. On [discoverExec].
     */
    private fun discoverDaily(s: Stats, c: LastfmClient?): Boolean {
        if (!prefs.discoverOn) return false
        var got = false
        for ((name, _) in s.recentLeaders()) if (!likesFresh(name) && runCatching { fetchLikes(c, name) }.isSuccess) got = true
        if (c != null) {
            val now = System.currentTimeMillis()
            for (p in s.recentTopSongs()) {
                if (synchronized(dstore) { fresh(dpart("sim").optJSONObject(p.key), LIKES_TTL) }) continue
                val list = try {
                    c.similarTracks(p.artist, p.title, 50)
                } catch (_: Exception) {
                    break
                }
                Thread.sleep(220)
                synchronized(dstore) {
                    dpart("sim").put(p.key, JSONObject().put("at", now).put("list", JSONArray(list.map { (a, t, m) -> JSONArray().put(a).put(t).put(m) })))
                }
                got = true
            }
            // Songs no longer among your top ones go after a while.
            synchronized(dstore) {
                val sim = dpart("sim")
                sim.keys().asSequence().toList().filter { now - (sim.optJSONObject(it)?.optLong("at") ?: 0L) > 2 * LIKES_TTL }.forEach { sim.remove(it) }
            }
        }
        if (got) saveDiscover()
        return got
    }

    /**
     * Songs like your favorites: 30 songs you haven't played that Last.fm finds similar to your most played of the last
     * month (looked up now for those not kept yet), the most alike first, two at most of one artist. On [discoverExec].
     */
    private fun similarSongsMix() {
        val s = currentStats() ?: return push("onPlaylist") { JSONObject().put("kind", "similarSongs").put("empty", true) }
        val c = lastfm()
        val seeds = s.recentTopSongs()
        val sims = HashMap<String, List<Triple<String, String, Double>>>()
        for (p in seeds) {
            val kept = synchronized(dstore) { dpart("sim").optJSONObject(p.key)?.optJSONArray("list") }
            val list = if (kept != null) {
                (0 until kept.length()).map { kept.getJSONArray(it) }.map { Triple(it.optString(0), it.optString(1), it.optDouble(2)) }
            } else {
                try {
                    c?.similarTracks(p.artist, p.title, 50).orEmpty().also { l ->
                        synchronized(dstore) {
                            dpart("sim").put(p.key, JSONObject().put("at", System.currentTimeMillis()).put("list", JSONArray(l.map { (a, t, m) -> JSONArray().put(a).put(t).put(m) })))
                        }
                    }
                } catch (_: Exception) {
                    emptyList()
                }
            }
            sims[p.key] = list
        }
        saveDiscover()
        val list = Discover.songMix(seeds, sims, s::played, MIX_SIZE)
        push("onPlaylist") {
            if (list.isEmpty()) JSONObject().put("kind", "similarSongs").put("empty", true)
            else JSONObject().put("kind", "similarSongs").put("name", "Songs like your favorites")
                .put("note", "Songs you haven't played that Last.fm finds like your most played of the last month").put("tracks", pickJson(list))
        }
    }

    /**
     * Pictures for the songs of a playlist that have none (songs you haven't played, a setlist): the cover of
     * the album named, else of the album Deezer has the song on, else the artist's photo. Each is kept, so it
     * is asked for once. On [historyExec].
     */
    private fun playlistCovers(msg: JSONObject) {
        val items = msg.optJSONArray("items") ?: return
        val images = db.images()
        val out = JSONObject()
        fun cached(key: String, look: () -> String?): String = images[key] ?: look().orEmpty().also { db.saveImage(key, it) }
        try {
            for (i in 0 until minOf(items.length(), 60)) {
                val o = items.getJSONObject(i)
                val artist = o.optString("artist")
                val title = o.optString("title")
                val album = o.optString("album")
                if (artist.isEmpty() || title.isEmpty()) continue
                var url = if (album.isNotEmpty()) cached(Text.albumKey(artist, album)) { deezer.albumCover(artist, album) } else ""
                if (url.isEmpty()) url = cached("song:" + Text.albumKey(artist, title)) { deezer.trackCover(artist, title) }
                if (url.isEmpty()) url = cached(Stats.photoKey(artist)) { deezer.artistPhoto(artist) }
                if (url.isNotEmpty()) out.put("$artist|$title", url)
            }
        } catch (_: Exception) {
            // Offline or Deezer over its quota: what was found so far.
        }
        push("onPlaylistCovers") { JSONObject().put("q", msg.optString("q")).put("covers", out) }
    }

    /** Playlist songs as JSON, each with a picture: its album's cover, or else its artist's photo if one is known. */
    private fun pickJson(list: List<Pick>): JSONArray {
        val images = db.images()
        return JSONArray(list.map { p -> p.toJson().also { if (p.cover.isEmpty()) it.put("cover", images[Stats.photoKey(p.artist)].orEmpty()) } })
    }

    /** Computes and pushes the stats for [statsRange]. On [statsExec]. */
    private fun statsInner() {
        if (ui == null) {
            statsMissed = true
            return
        }
        val s = currentStats()
        if (s == null) {
            push("onStats") { JSONObject().put("empty", true) }
            return
        }
        val id = statsRange
        val shown = shownStats() ?: s
        // The period's artists on the map; the ones not looked up yet are, one a second, and the map follows.
        val mapTop = shown.mapArtistsIn(shown.range(id))
        val unplaced = synchronized(countries) { mapTop.map { it.first }.filter { Text.norm(it) !in countries } }
        if (unplaced.isNotEmpty()) lookUpCountries(unplaced)
        push("onStats") {
            shown.compute(shown.range(id)).put("map", worldMapJson(mapTop)).put("tidy", tidySummary()).put("concerts", concertsIn(s, shown.range(id))).put("genre", statsGenre).put("genreOptions", s.genreOptions())
                .put("decade", statsDecade).put("decadeOptions", s.decadeOptions()).put("onlyVinyl", statsVinyl)
        }
        // Release years for the decade filter, looked up now and then as the history grows.
        if (!yearsBusy && System.currentTimeMillis() - yearsTried > 6 * HOUR) historyExec.execute { yearsInner() }
        // Everyone's counts on Last.fm, for the comparisons, likewise.
        if (globalsDue() && System.currentTimeMillis() - globalsTried > 6 * HOUR) historyExec.execute { globalsInner() }
        val missing = s.albumsNeedingImages(id)
        val photos = s.artistsNeedingPhotos(id)
        if (missing.isNotEmpty() || photos.isNotEmpty()) {
            historyExec.execute {
                val covers = fetchImagesInner(missing, tell = false)
                val faces = fetchPhotosInner(photos, tell = false)
                // The page once more for both together, unless it has moved on to another period meanwhile (stepping
                // through the years, every period drawn twice over made the page stumble).
                if (covers || faces) picturesArrived { if (statsRange == id) statsInner() }
            }
        }
    }

    /** Finds the songs on TIDAL and makes the playlist there, telling the page how far it got. On [tidalExec]. */
    private fun tidalPlaylistInner(name: String, note: String, tracks: List<Pair<String, String>>) {
        fun job(state: String) = JSONObject().put("state", state).put("total", tracks.size).put("name", name)
        push("onTidalJob") { job("finding").put("done", 0).put("found", 0) }
        try {
            val r = tidal.createPlaylist(name, note, tracks) { done, found -> push("onTidalJob") { job("finding").put("done", done).put("found", found) } }
            push("onTidalJob") {
                job("done").put("link", r.link).put("added", r.added)
                    .put("missing", JSONArray(r.missing.map { JSONObject().put("artist", it.first).put("title", it.second) }))
            }
        } catch (e: TidalException) {
            if (e.authFailed) tidal.signOut()
            push("onTidalJob") { job("error").put("needsLogin", e.authFailed).put("msg", if (e.authFailed) "TIDAL asks you to sign in again." else e.message ?: "TIDAL error ${e.code}") }
            pushState()
        } catch (_: IOException) {
            push("onTidalJob") { job("error").put("msg", "Lost the connection to TIDAL. Try again.") }
        }
    }

    // ---- hiding scrobbles from the stats ----

    private fun hidden(): Hidden = hiddenCache ?: Hidden.fromJson(file(HIDDEN_FILE).read()).also { hiddenCache = it }

    /** Artists you said you knew before your history starts (true) or found at their first play (false), by [Text.norm] of the name. */
    @Volatile private var knownCache: Map<String, Boolean>? = null
    private fun known(): Map<String, Boolean> = knownCache ?: (
        try {
            file(KNOWN_FILE).read()?.let { JSONObject(it) }?.let { o -> o.keys().asSequence().associateWith { k -> o.optBoolean(k) } }
        } catch (_: Exception) {
            null
        } ?: emptyMap()
        ).also { knownCache = it }

    private fun saveHidden(h: Hidden) {
        hiddenCache = h
        file(HIDDEN_FILE).write(h.toJson().toString())
    }

    /** After the hidden list changed: the history without those scrobbles, new stats and tidy-up lists. On [statsExec]. */
    private fun rebuildHistory() {
        if (!hasHistory()) return
        history = History.from(db.everything, hidden())
        dropStats()
        push("onTidy") { tidy() ?: JSONObject().put("empty", true) }
        statsInner()
    }

    /** Suggestions of scrobbles that look like videos or apps, the most played ones without an album, and what is hidden. */
    private fun tidy(): JSONObject? {
        val h = history ?: (if (hasHistory()) History.from(db.everything, hidden()).also { history = it } else return null)
        if (tidyFor === h) tidyJson?.let { return it }
        val (sugg, other) = Junk.scan(h, hidden())
        return JSONObject()
            .put("hiddenPlays", h.hiddenPlays)
            .put("hidden", hidden().toJson())
            .put("suggestions", JSONArray(sugg.map { Junk.toJson(h, it) }))
            .put("noAlbum", JSONArray(other.map { Junk.toJson(h, it) }))
            .put("user", prefs.lastfmUser)
            .also {
                tidyJson = it
                tidyFor = h
            }
    }

    /** For the banner on the Stats tab: how many suggestions wait, with a few examples. */
    private fun tidySummary(): JSONObject {
        val t = tidy() ?: return JSONObject()
        val s = t.getJSONArray("suggestions")
        return JSONObject().put("count", s.length()).put("hiddenPlays", t.optInt("hiddenPlays"))
            .put("plays", (0 until s.length()).sumOf { s.getJSONObject(it).optInt("plays") })
            .put("examples", JSONArray((0 until minOf(3, s.length())).map { s.getJSONObject(it).optString("title") }))
    }

    /** Last.fm tags for the most played artists (weighed with Discogs' styles for those on your shelf). On [historyExec]. */
    private fun fetchTagsInner(c: LastfmClient) {
        val s = currentStats() ?: return
        // Tags kept by older versions (three, without their weights) are looked up again; then the most played albums' own.
        val kept = db.tags()
        val need = s.artistsNeedingTags(kept.filterValues { Genres.tagsCurrent(it) }.keys)
        val albums = s.albumsNeedingTags(kept.keys)
        if (need.isEmpty() && albums.isEmpty()) return
        tagsTotal = need.size + albums.size
        tagsDone = 0
        pushState()
        try {
            for (a in need) {
                db.saveTags(Genres.artistKey(a), Genres.fromTags(c.artistTopTags(a), a))
                tagsDone++
                if (tagsDone % 10 == 0) pushState()
                Thread.sleep(220)
            }
            for ((artist, title, key) in albums) {
                db.saveTags(key, Genres.fromTags(c.albumTopTags(artist, title), artist, title))
                tagsDone++
                if (tagsDone % 10 == 0) pushState()
                Thread.sleep(220)
            }
        } catch (_: Exception) {
            // Network trouble: the rest is fetched next time.
        } finally {
            tagsTotal = 0
            dropStats()
            pushState()
            statsExec.execute { statsInner() }
        }
    }

    /**
     * The albums named anywhere in page [j] that show no cover and weren't looked up yet ([tried]), as
     * (artist, album, key): an album ({title, artist, cover}) or a song's album ({album, artist, cover}). A song
     * or album without an artist of its own is [artist]'s. The first [max] of them.
     */
    private fun missingCovers(j: JSONObject, tried: Set<String>, artist: String = "", max: Int = 40): List<Triple<String, String, String>> {
        val out = LinkedHashMap<String, Triple<String, String, String>>()
        fun walk(v: Any?, depth: Int) {
            if (depth > 6 || out.size >= max) return
            when (v) {
                is JSONArray -> for (i in 0 until v.length()) walk(v.opt(i), depth + 1)
                is JSONObject -> {
                    if (v.has("cover") && v.optString("cover").isEmpty()) {
                        val album = if (v.has("album")) v.opt("album") as? String ?: "" else v.optString("title")
                        val who = v.optString("artist").ifEmpty { artist }
                        if (album.isNotEmpty() && who.isNotEmpty()) {
                            val key = Text.albumKey(who, album)
                            if (key !in tried) out.putIfAbsent(key, Triple(who, album, key))
                        }
                    }
                    for (k in v.keys()) walk(v.opt(k), depth + 1)
                }
            }
        }
        walk(j, 0)
        return out.values.toList()
    }

    /**
     * Covers of albums that aren't on your shelf, from Deezer (Last.fm's terms leave its artwork
     * out of what apps may show). "" is kept when there is none. Whether any were looked up; [tell]: the page that
     * asked is shown again then (see [picturesArrived]). On [historyExec].
     */
    private fun fetchImagesInner(missing: List<Triple<String, String, String>>, tell: Boolean = true, after: (() -> Unit)? = null): Boolean {
        val tried = db.images().keys
        var any = false
        for ((artist, album, key) in missing) {
            if (key in tried) continue
            try {
                db.saveImage(key, lookUpCover(artist, album) ?: continue)
                any = true
            } catch (_: Exception) {
                break
            }
        }
        if (any && tell) picturesArrived(after)
        return any
    }

    /** When the Cover Art Archive last failed to answer (ms). */
    @Volatile private var archiveFailedAt = 0L

    /**
     * An album's cover, "" when there is none to find. From Deezer: the album as its search finds it, else as the
     * song you played most from it leads to it, else for a single or a compilation the cover Deezer shows for that
     * song (see [Stats.albumSong]). A compilation is filed under "Various Artists" there and not under the artist
     * you played from it, and a single is often listed only with the album its song is on. An album Deezer doesn't
     * have at all (Cypress Hill's Elephants on Acid) comes from the Cover Art Archive. Null when the archive didn't
     * answer: nothing is kept then, the album is asked for again another time, and the archive is left alone for a
     * while. On [historyExec].
     */
    private fun lookUpCover(artist: String, album: String): String? {
        deezer.albumCover(artist, album)?.let { return it }
        currentStats()?.albumSong(artist, album)?.let { (song, standsIn) -> deezer.albumCoverBySong(artist, album, song, standsIn) }?.let { return it }
        if (System.currentTimeMillis() - archiveFailedAt < ARCHIVE_PAUSE) return null
        return try {
            mbz.albumCover(artist, album).orEmpty()
        } catch (_: Exception) {
            archiveFailedAt = System.currentTimeMillis()
            null
        }
    }

    /** The binder's request whose covers are being looked up: a binder opened anew starts its own. */
    @Volatile private var cardsReq = 0

    /**
     * Covers for the binder's cards, a lot at a time: each lot's finds go to the page to put on their cards
     * (onCardCovers), and the next lot waits its turn behind other lookups. On [historyExec].
     */
    private fun cardCoversInner(missing: List<Triple<String, String, String>>, req: Int) {
        if (cardsReq != req || missing.isEmpty()) return
        val known = db.images()
        val found = JSONArray()
        var looked = false
        for ((artist, album, key) in missing.take(CARD_COVER_LOT)) {
            // Looked up meanwhile (a binder opened before this one was still asking): its cover goes to the page too.
            val had = known[key]
            if (had != null) {
                if (had.isNotEmpty()) found.put(JSONObject().put("artist", artist).put("album", album).put("cover", had))
                continue
            }
            try {
                val url = lookUpCover(artist, album) ?: continue
                db.saveImage(key, url)
                looked = true
                if (url.isNotEmpty()) found.put(JSONObject().put("artist", artist).put("album", album).put("cover", url))
            } catch (_: Exception) {
                return // offline or over the quota: the next time the binder opens
            }
        }
        if (looked) refreshPictures()
        if (found.length() > 0) push("onCardCovers") { JSONObject().put("req", req).put("covers", found) }
        val rest = missing.drop(CARD_COVER_LOT)
        if (rest.isNotEmpty()) historyExec.execute { cardCoversInner(rest, req) }
    }

    /** Artist photos from Deezer for [names] not looked up yet ("" is kept when there is none). On [historyExec]. */
    private fun fetchPhotosInner(names: List<String>, tell: Boolean = true, after: (() -> Unit)? = null): Boolean {
        val tried = db.images().keys
        var any = false
        for (n in names) {
            val key = Stats.photoKey(n)
            if (key in tried) continue
            try {
                db.saveImage(key, deezer.artistPhoto(n).orEmpty())
                any = true
            } catch (_: Exception) {
                break // offline or over the quota: next time
            }
        }
        if (any && tell) picturesArrived(after)
        return any
    }

    /** The colors of a cover or photo (downloaded once, scaled down), for tinting its page. On [paletteExec]. */
    private fun paletteInner(url: String) {
        val known = synchronized(palettes) { palettes[url] }
        val c = known ?: try {
            val bytes = Http.getBytes(url) ?: return
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 48 && bounds.outHeight / (sample * 2) >= 48) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            bmp.recycle()
            val p = Palette.of(px) ?: return
            JSONObject().put("dominant", Palette.hex(p.dominant)).put("vibrant", Palette.hex(p.vibrant))
        } catch (_: Exception) {
            return
        }
        if (known == null) {
            synchronized(palettes) {
                palettes[url] = c
                while (palettes.size > PALETTES_KEPT) palettes.remove(palettes.keys.first())
                file("palettes.json").write(JSONObject(palettes as Map<*, *>).toString())
            }
        }
        push("onPalette") { JSONObject(c.toString()).put("url", url) }
    }

    // ---- pushing to the page ----

    /** Replies that came while no page was there (the app in the background): the latest of each, for [attachUi]. */
    private val missed = LinkedHashMap<String, String>()

    /** Those the page gets again anyway when it is back ([pushAll]), or that only matter at the moment. */
    private val NOT_KEPT = setOf("onState", "onCollection", "onActivity", "onCompare", "onToast", "onPalette")

    private inline fun push(fn: String, build: () -> Any) {
        if (ui == null) {
            if (fn !in NOT_KEPT) text(fn, build()).let { s -> synchronized(missed) { missed[fn] = s } }
            return
        }
        val s = text(fn, build())
        main.post { ui?.push(fn, s) }
    }

    /** A reply as text. A number JSON can't carry makes Android's toString() give null: it goes out as 0, and the log says where. */
    private fun text(fn: String, v: Any): String {
        val s: String? = v.toString()
        if (s != null) return s
        android.util.Log.w("HeavyRotation", "$fn: numbers set to 0 at ${v.finiteJson().take(8)}")
        return v.toString()
    }

    fun pushAll() {
        pushState()
        pushCollection()
        pushActivity()
        push("onHeard") { heard.toJson() }
        compareJson?.let { c -> push("onCompare") { c } }
    }

    /** The state is built on its own thread: callers include the main thread (the phone's playback reports). */
    private val stateExec = serial()
    @Volatile private var statePending = false

    /**
     * The page gets the latest state, at most every 100 ms: changes come in bursts (a song change,
     * playback reports, an import page), and each one rebuilt the state and the page's cards.
     */
    fun pushState() {
        if (ready) NowWidget.update(ctx)
        if (ui == null || statePending) return
        statePending = true
        main.postDelayed({
            stateExec.execute {
                statePending = false
                push("onState") { stateJson() }
            }
        }, 100)
    }

    /**
     * The shelf for the page, each record with the picture it shows (cover and thumb), your copy's own ("own", "ownThumb")
     * and the album's usual cover ("usual", "" if none), and which of them it shows ("face").
     */
    private fun pushCollection() = push("onCollection") {
        val sp = spinCounts
        val pl = plays
        val images = try { db.images() } catch (_: Exception) { emptyMap() }
        val own = prefs.ownCovers
        JSONArray().also { a ->
            collection.forEach { c ->
                val mine = c.releaseId in own
                val usual = c.usualCover(images)
                a.put(c.toJson().put("plays", pl[Text.albumKey(c.artist, c.title)] ?: 0).put("spins", sp[c.releaseId.toString()] ?: 0)
                    .put("cover", c.face(images, mine)).put("thumb", c.faceThumb(images, mine))
                    .put("own", c.cover.ifEmpty { c.thumb }).put("ownThumb", c.thumb.ifEmpty { c.cover }).put("usual", usual)
                    .put("face", if (mine || usual.isEmpty()) "own" else "usual"))
            }
        }
    }

    /** The picture record [c] shows (see [CollectionItem.face]); its usual cover read alone, as the widget asks often. */
    private fun faceOf(c: CollectionItem): String {
        val key = Text.albumKey(c.artist, c.title)
        val usual = try { db.image(key) } catch (_: Exception) { null }
        return c.face(if (usual != null) mapOf(key to usual) else emptyMap(), c.releaseId in prefs.ownCovers)
    }

    /** The albums' usual covers for the records on your shelf, from Deezer: once each ("" kept when it has none), then the shelf again. */
    private fun lookUpShelfCovers() = historyExec.execute {
        val tried = db.images().keys
        val missing = collection.map { Triple(it.artist, it.title, Text.albumKey(it.artist, it.title)) }.filter { it.third !in tried }.distinctBy { it.third }
        if (missing.isNotEmpty()) fetchImagesInner(missing) { pushCollection() }
    }

    private fun pushActivity() {
        NowWidget.update(ctx)
        push("onActivity") { activityJson() }
    }

    /**
     * What the home-screen widget shows: the record on the turntable, else the song the phone plays, else the last
     * one scrobbled; with today's plays and this week's (your history, or this app's scrobbles if more).
     */
    fun widgetJson(): JSONObject {
        val now = System.currentTimeMillis()
        val o = JSONObject()
        val s = spin
        val i = s?.currentIndex(now) ?: -1
        val phone = Phone.stateJson().optJSONObject("now")
        val last = activity.last()
        when {
            s != null && i >= 0 -> o.put("label", "On the turntable").put("title", s.items[i].track.title).put("artist", s.items[i].track.artist.ifEmpty { s.artist })
                .put("cover", s.cover).put("open", "spin")
            phone != null && phone.optBoolean("playing") -> {
                val key = Text.albumKey(phone.optString("artist"), phone.optString("album"))
                o.put("label", "Now playing").put("title", phone.optString("title")).put("artist", phone.optString("artist"))
                    .put("cover", if (nowCover.first == key) nowCover.second else coverOf(phone.optString("artist"), phone.optString("album"))).put("open", "now")
            }
            last != null -> o.put("label", "Last played").put("title", last.optString("track")).put("artist", last.optString("artist"))
                .put("cover", last.optString("cover").ifEmpty { coverOf(last.optString("artist"), last.optString("album")) })
            else -> o.put("label", "Heavy Rotation").put("title", "Nothing played yet").put("artist", "")
        }
        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.LocalDate.now(zone)
        val day0 = today.atStartOfDay(zone).toEpochSecond()
        val week0 = today.with(java.time.DayOfWeek.MONDAY).atStartOfDay(zone).toEpochSecond()
        val end = now / 1000 + 1
        val (d, w) = try {
            maxOf(db.countBetween(day0, end), activity.countSince(day0)) to maxOf(db.countBetween(week0, end), activity.countSince(week0))
        } catch (_: Exception) {
            activity.countSince(day0) to activity.countSince(week0)
        }
        return o.put("line", "Today ${plays(d)}, this week $w")
    }

    private fun plays(n: Int) = if (n == 1) "1 play" else "$n plays"

    /** An album's cover as the app knows it: your shelf's copy, else one looked up before. */
    private fun coverOf(artist: String, album: String): String {
        if (album.isBlank()) return ""
        val key = Text.albumKey(artist, album)
        return collection.firstOrNull { Text.albumKey(it.artist, it.title) == key }?.let { faceOf(it) }
            ?: try { db.image(key).orEmpty() } catch (_: Exception) { "" }
    }

    /**
     * The activity log with covers: a record's own, else your shelf's copy of the album, else
     * Deezer's (phone scrobbles carry none). Missing ones are looked up in the
     * background and the list comes again.
     */
    private fun activityJson(): JSONArray {
        val a = activity.toJson()
        val owned = collection.associateBy { Text.albumKey(it.artist, it.title) }
        val images = db.images()
        val own = prefs.ownCovers
        val missing = LinkedHashMap<String, Triple<String, String, String>>()
        for (i in 0 until a.length()) {
            val e = JSONObject(a.getJSONObject(i).toString())
            a.put(i, e)
            val album = e.optString("album")
            if (e.optString("cover").isNotEmpty() || album.isBlank()) continue
            val key = Text.albumKey(e.optString("artist"), album)
            val c = owned[key]?.let { it.face(images, it.releaseId in own) } ?: images[key]
            if (c == null) missing[key] = Triple(e.optString("artist"), album, key) else if (c.isNotEmpty()) e.put("cover", c)
        }
        if (missing.isNotEmpty()) historyExec.execute { fetchImagesInner(missing.values.toList().takeLast(COVER_BATCH)) { pushActivity() } }
        return a
    }

    fun toast(msg: String) = push("onToast") { JSONObject.quote(msg) }

    /** The page's own settings, sent along with "backupExport" until the file is picked. */
    @Volatile private var backupUi = JSONObject()

    /** Writes a backup (see [Backup]) to the file the user picked. */
    fun backupTo(uri: android.net.Uri) = historyExec.execute {
        try {
            val n = ctx.contentResolver.openOutputStream(uri, "w")?.use { out ->
                Backup.write(out, files, db, prefs.plain(), backupUi, BuildConfig.VERSION_NAME, System.currentTimeMillis())
            } ?: throw IOException("Couldn't open the file.")
            push("onBackup") { JSONObject().put("scrobbles", n) }
        } catch (e: Exception) {
            push("onBackup") { JSONObject().put("error", "Couldn't save the backup: ${e.message ?: e.javaClass.simpleName}") }
        }
    }

    /**
     * Puts a backup in place: history, files and settings. The app then starts again, so
     * nothing still in memory from before mixes with what was restored.
     */
    fun restoreFrom(uri: android.net.Uri) = historyExec.execute {
        try {
            // Read through first: a file cut short is turned away before anything here changes.
            ctx.contentResolver.openInputStream(uri)?.use { Backup.check(it) } ?: throw IOException("Couldn't open the file.")
            // The queue and the spinning record aren't in a backup: they go on being saved.
            JsonFile.frozen = { it.name !in Backup.SKIP }
            val r = ctx.contentResolver.openInputStream(uri)?.use { Backup.read(it, files, db) } ?: throw IOException("Couldn't open the file.")
            prefs.restorePlain(r.prefs)
            push("onRestored") { JSONObject().put("scrobbles", r.scrobbles).put("files", r.files).put("ui", r.ui) }
            // The page restarts the app once it has put its own settings back; without a page, or if
            // it doesn't, the app restarts by itself.
            main.postDelayed({ restart() }, if (ui == null) 0L else 5000L)
        } catch (e: Exception) {
            JsonFile.frozen = { false }
            push("onBackup") { JSONObject().put("error", "Couldn't restore: ${e.message ?: e.javaClass.simpleName}") }
        }
    }

    /** Starts the app afresh (after a restore), so only what was restored is in memory. */
    private fun restart() {
        try {
            ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let {
                ctx.startActivity(it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK))
            }
        } catch (_: Exception) {
            // From the background Android may not allow it; the next start is a fresh one anyway.
        }
        Runtime.getRuntime().exit(0)
    }

    /** Tracklists of albums phone apps played (by album key), the last 30. */
    private val nowAlbums: MutableMap<String, JSONObject> = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, JSONObject>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JSONObject>?) = size > 30
    })

    /** Lyrics of songs played on the phone (by artist and title), the last 40. */
    private val lyrics: MutableMap<String, JSONObject> = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, JSONObject>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JSONObject>?) = size > 40
    })

    /** The album key and cover of what a phone app is playing, once found ("" while looking or when there is none). */
    @Volatile private var nowCover = "" to ""

    /** The phone's state, with the cover of what plays right now: from your shelf, or Deezer's (looked up once). */
    private fun phoneJson(): JSONObject {
        val p = Phone.stateJson().put("comparing", comparing).put("compareError", compareError)
        val n = p.optJSONObject("now") ?: return p
        val artist = n.optString("artist")
        val album = n.optString("album")
        if (album.isBlank()) return p
        val key = Text.albumKey(artist, album)
        val known = nowCover
        if (known.first == key) {
            if (known.second.isNotEmpty()) n.put("cover", known.second)
        } else {
            nowCover = key to ""
            webExec.execute {
                val owned = collection.firstOrNull { Text.albumKey(it.artist, it.title) == key }?.let { faceOf(it) }
                val url = owned ?: db.images()[key] ?: try {
                    (deezer.albumCover(artist, album) ?: n.optString("title").takeIf { it.isNotEmpty() }?.let { deezer.trackCover(artist, it) }).orEmpty().also {
                        db.saveImage(key, it)
                        refreshPictures()
                    }
                } catch (_: Exception) {
                    ""
                }
                if (nowCover.first == key && url.isNotEmpty()) {
                    nowCover = key to url
                    pushState()
                }
            }
        }
        return p
    }

    private fun hint(key: String) = if (key.length > 4) "…" + key.takeLast(4) else ""

    private fun stateJson(): JSONObject = JSONObject()
        .put(
            "lastfm",
            JSONObject()
                .put("hasKey", prefs.lastfmKey.isNotEmpty() && prefs.lastfmSecret.isNotEmpty())
                .put("builtIn", prefs.lastfmBuiltIn)
                .put("keyHint", if (prefs.lastfmBuiltIn) "" else hint(prefs.lastfmKey))
                .put("connected", prefs.lastfmSession.isNotEmpty())
                .put("user", prefs.lastfmUser)
                .put("waiting", prefs.lastfmPendingToken.isNotEmpty())
                .put("busy", lfBusy)
                .put("error", lfError)
                .put("needsAuth", needsAuth),
        )
        .put(
            "discogs",
            JSONObject()
                .put("connected", prefs.discogsUser.isNotEmpty()).put("public", prefs.discogsToken.isEmpty())
                .put("user", prefs.discogsUser)
                .put("syncing", syncing)
                .put("done", syncDone)
                .put("page", syncPage)
                .put("pages", syncPages)
                .put("lastSync", prefs.lastSync)
                .put("count", collection.size)
                .put("error", syncError),
        )
        .put("prefs", JSONObject().put("theme", prefs.theme).put("defaultTrackSec", prefs.defaultTrackSec))
        .put("queue", JSONObject().put("waiting", queue.size()).put("oldest", queue.oldestTimestamp()).put("error", flushError))
        .put("heard", heard.waiting().size)
        .put("spin", spinUi?.let { JSONObject(it) } ?: JSONObject.NULL)
        .put("phone", phoneJson())
        .put(
            "fix",
            JSONObject().put("presets", JSONArray(FixRules.PRESETS.map { p -> JSONObject().put("id", p.id).put("label", p.label).put("example", p.example).put("on", p.id in prefs.fixPresets) }))
                .put("rules", JSONArray(prefs.fixRules)),
        )
        .put("sleep", SleepTimer.json() ?: JSONObject.NULL)
        .put(
            "history",
            JSONObject().put("count", histCount).put("done", prefs.historyDone).put("resumable", prefs.historyCursor > 0)
                .put("importing", importing).put("syncing", histSyncing).put("fetched", histDone).put("total", histTotal)
                .put("error", histError).put("lastSync", prefs.historyLastSync).put("tagsDone", tagsDone).put("tagsTotal", tagsTotal)
                .put("yearsBusy", yearsBusy).put("yearsDone", yearsDone).put("yearsTotal", yearsTotal)
                .put("globalsBusy", globalsBusy).put("globalsDone", globalsDone).put("globalsTotal", globalsTotal)
                .put("tidal", tidalImportJson()),
        )
        .put(
            "tidal",
            JSONObject().put("hasApp", tidal.hasApp).put("builtIn", prefs.tidalBuiltIn).put("loggedIn", tidal.loggedIn).put("busy", tidalBusy).put("error", tidalError)
                .put("clientHint", hint(prefs.tidalClientId)).put("redirect", TidalClient.REDIRECT),
        )
        .put(
            "setlist",
            JSONObject().put("hasKey", prefs.setlistKey.isNotEmpty()).put("builtIn", prefs.setlistBuiltIn).put("keyHint", if (prefs.setlistBuiltIn) "" else hint(prefs.setlistKey))
                .put("user", prefs.setlistUser).put("concerts", concerts().size).put("concertsAt", prefs.concertsAt).put("concertsBusy", concertsBusy).put("concertsError", concertsError),
        )
        .put("discover", JSONObject().put("on", prefs.discoverOn).put("no", discoverNo().length()))
        .put("version", BuildConfig.VERSION_NAME)

    // ---- small persisted maps ----

    private fun readCounts(name: String): Map<String, Int> = try {
        val o = JSONObject(file(name).read() ?: "{}")
        o.keys().asSequence().associateWith { o.optInt(it) }
    } catch (_: Exception) {
        emptyMap()
    }

    private fun writeCounts(name: String, m: Map<String, Int>) {
        val o = JSONObject()
        m.forEach { (k, v) -> o.put(k, v) }
        file(name).write(o.toString())
    }
}
