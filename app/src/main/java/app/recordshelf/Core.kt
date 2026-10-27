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
import app.recordshelf.history.Genres
import app.recordshelf.history.Global
import app.recordshelf.history.GlobalAsk
import app.recordshelf.history.GlobalTop
import app.recordshelf.history.Globals
import app.recordshelf.history.Hidden
import app.recordshelf.history.History
import app.recordshelf.history.HistorySync
import app.recordshelf.history.Junk
import app.recordshelf.history.Stats
import app.recordshelf.history.Years
import app.recordshelf.lastfm.LastfmClient
import app.recordshelf.lastfm.LastfmException
import app.recordshelf.lastfm.Scrobble
import app.recordshelf.mb.MusicBrainzClient
import app.recordshelf.net.DeezerClient
import app.recordshelf.net.DzArtist
import app.recordshelf.net.DzRelease
import app.recordshelf.net.Http
import app.recordshelf.scrobble.ActivityLog
import app.recordshelf.scrobble.Compare
import app.recordshelf.scrobble.Durations
import app.recordshelf.scrobble.FlushResult
import app.recordshelf.scrobble.Listen
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
    }

    private const val HOUR = 3_600_000L

    /** Last.fm takes scrobbles up to two weeks late (a phone that was offline, a record logged afterwards). */
    private const val LATE_SEC = 14 * 24 * 3600L
    private const val HIDDEN_FILE = "hidden.json"
    private const val DISCO_FILE = "discographies.json"
    private const val YEARS_FILE = "album_years.json"
    private const val GLOBALS_FILE = "global_counts.json"

    /** Discographies are looked up again after a week, so new releases show up. */
    private const val DISCO_TTL = 7L * 24 * HOUR
    private const val DISCO_MAX = 150

    /** Album covers looked up for the activity list at a time (newest first). */
    private const val COVER_BATCH = 40

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
    /** Network work for the history: import, sync, tags, covers. */
    private val historyExec = serial()

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

    /** Deezer lookups for pages (discographies, tracklists), apart from the history work. */
    private val webExec = serial()

    /** The Stats tab shows one genre's music when set (a genre key; "" for everything), and/or one decade's (e.g. 1970; 0 for all). */
    @Volatile private var statsGenre = ""
    @Volatile private var statsDecade = 0
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
        stats?.withPictures(images, g)
        filteredStats?.withPictures(images, g)
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
                // Covers once fetched from Last.fm go (its terms leave artwork out); Deezer's come instead.
                db.dropImagesFrom("lastfm")
                histCount = db.count()
                // Settings back without their history (Android's cloud backup leaves the database out): import again.
                if (histCount == 0 && (prefs.historyDone || prefs.historyCursor > 0)) {
                    prefs.historyDone = false
                    prefs.historyCursor = 0
                    prefs.refreshCursor = 0
                    prefs.historySyncedTo = 0
                }
                pushState()
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
        // An import that was interrupted continues; a finished one picks up new scrobbles now and then.
        if (prefs.historyCursor > 0 && !importing && !prefs.importPaused) historyExec.execute { importInner() }
        else if (prefs.historyDone && System.currentTimeMillis() - prefs.historyLastSync > HOUR / 2) historyExec.execute { refreshHistoryInner() }
    }

    /** Messages from the page (see app.js). Main thread. */
    fun handle(msg: JSONObject) {
        when (msg.optString("t")) {
            "hello" -> {
                Phone.refresh(ctx)
                pushAll()
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
            "comparePano" -> syncExec.execute { compareInner() }
            "historyImport" -> {
                prefs.importPaused = false
                historyExec.execute { importInner() }
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
                // Stepping through periods quickly: only the last one asked for is worked out.
                val ask = ++statsAsks
                statsExec.execute { if (ask == statsAsks) statsInner() }
            }
            "artist" -> {
                val name = msg.optString("name")
                val id = msg.optString("range").ifEmpty { statsRange }
                statsExec.execute {
                    val s = currentStats()
                    val j = s?.artist(name, s.range(id)) ?: JSONObject().put("name", name).put("missing", true)
                    push("onArtist") { j.put("req", msg.optInt("req")) }
                    // No photo yet: Deezer may have one; the page comes again if so.
                    val n = j.optString("name")
                    if (j.optString("photo").isEmpty() && Stats.photoKey(n) !in db.images().keys) historyExec.execute { fetchPhotosInner(listOf(n)) { handle(msg) } }
                    // Everyone's counts on Last.fm, for "you vs everyone"; the page comes again.
                    globalsFor(pageAsks(GlobalAsk.Kind.ARTIST, j), msg)
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
            "explore" -> statsExec.execute {
                val s = currentStats()
                push("onExplore") { s?.explore() ?: JSONObject().put("empty", true) }
                val missing = s?.exploreNeedingImages().orEmpty()
                if (missing.isNotEmpty()) historyExec.execute { fetchImagesInner(missing) { handle(msg) } }
                val photos = s?.exploreNeedingPhotos().orEmpty()
                if (photos.isNotEmpty()) historyExec.execute { fetchPhotosInner(photos) { handle(msg) } }
            }
            "badges" -> statsExec.execute {
                val j = if (prefs.historyDone) currentStats()?.shelfBadges() else null
                push("onBadges") { j ?: JSONObject() }
            }
            "backupExport" -> {
                backupUi = msg.optJSONObject("ui") ?: JSONObject()
                ui?.pickBackup(true, "heavy-rotation-backup-${java.time.LocalDate.now()}.zip")
            }
            "backupImport" -> ui?.pickBackup(false, "")
            "atlas" -> statsExec.execute {
                val s = currentStats()
                push("onAtlas") { s?.atlas() ?: JSONObject().put("empty", true) }
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
            "playlist" -> statsExec.execute {
                val s = currentStats()
                push("onPlaylist") { s?.playlist(msg.optString("kind"), msg.optString("range").ifEmpty { statsRange }) ?: JSONObject().put("empty", true) }
            }
            "genre" -> {
                val key = msg.optString("key")
                statsExec.execute {
                    val s = currentStats()
                    val j = s?.genrePage(key) ?: JSONObject().put("missing", true)
                    push("onGenre") { j.put("key", key).put("req", msg.optInt("req")) }
                    // Photos for its top artists, if Deezer has them; the page comes again.
                    val top = j.optJSONObject("top")?.optJSONArray("artists")
                    val need = (0 until (top?.length() ?: 0)).map { top!!.getJSONObject(it) }.filter { it.optString("photo").isEmpty() }.map { it.getString("name") }
                    val tried = db.images().keys
                    val missing = need.filter { Stats.photoKey(it) !in tried }
                    if (missing.isNotEmpty()) historyExec.execute { fetchPhotosInner(missing) { handle(msg) } }
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
                    push("onStory") { s.story(s.range(id)).put("genre", statsGenre).put("decade", statsDecade) }
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
                if (hit == null) toast("No exact match on TIDAL, so here's a search")
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
                scrobExec.execute {
                    spin?.resync(i, System.currentTimeMillis())
                    tickInner()
                }
            }
            "flush" -> scrobExec.execute {
                val r = flushInner()
                if (r != null && r.error == null && r.waiting == 0) toast("All scrobbles sent")
            }
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

    private fun startSpinInner(releaseId: Long, idx: List<Int>, mode: When, at: Long) {
        val rel = loadRelease(releaseId)
        if (rel == null) {
            toast("Open the record again; its tracklist isn't loaded")
            return
        }
        val tracks = planTracks(rel, idx)
        if (tracks.isEmpty()) return
        val now = System.currentTimeMillis()
        val starts = Planner.starts(tracks, mode, at, now)
        val s = Spin(
            "$releaseId-$now", releaseId, rel.title, rel.artist, rel.thumb.ifEmpty { rel.cover }, rel.sides,
            tracks.zip(starts).map { (t, st) -> SpinItem(t, st) }.toMutableList(),
        )
        spinCounts = spinCounts + (releaseId.toString() to (spinCounts[releaseId.toString()] ?: 0) + 1)
        writeCounts("spins.json", spinCounts)
        if (s.items.all { now >= it.endMs }) {
            // Played in the past: scrobble it all now and leave the record that is spinning alone.
            enqueue(s, s.takeDue(now))
            flushInner()
            pushState()
            pushActivity()
        } else {
            spin?.let { old -> enqueue(old, old.stop(now)) }
            spin = s
            tickInner()
        }
        pushCollection()
    }

    private fun playSideInner(releaseId: Long, side: String) {
        val rel = loadRelease(releaseId) ?: return
        startSpinInner(releaseId, rel.tracks.indices.filter { rel.tracks[it].side == side }, When.NOW, 0)
    }

    private fun flipInner() {
        val s = spin ?: return
        val side = s.nextSide() ?: return
        val rel = loadRelease(s.releaseId) ?: return
        val now = System.currentTimeMillis()
        enqueue(s, s.flip(planTracks(rel, rel.tracks.indices.filter { rel.tracks[it].side == side }), now))
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
        if (s.isOver(now)) {
            endSpin()
            SpinNotifier.finished(ctx, s)
        } else {
            spinFile.write(s.toJson().toString())
            spinUi = s.toUiJson().toString()
            SpinReceiver.schedule(ctx, s.nextEventMs(now))
            SpinNotifier.show(ctx, s, now)
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
            c.updateNowPlaying(sk, item.track.toScrobble(item.startMs))
        } catch (_: Exception) {
            // Now playing is best effort; the scrobble itself goes through the queue.
        }
    }

    private fun enqueue(s: Spin, due: List<SpinItem>) {
        if (due.isEmpty()) return
        val now = System.currentTimeMillis()
        val added = queue.add(due.map { QueuedScrobble(UUID.randomUUID().toString(), it.track.toScrobble(it.startMs), "vinyl", now) })
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
        return r
    }

    // ---- phone scrobbling (called on the main thread by PhoneListener) ----

    /** A listen on the phone counts. In shadow mode it is only logged, to compare with Pano Scrobbler. */
    fun phoneScrobble(pkg: String, s: Scrobble) {
        val mode = Phone.mode()
        if (mode == Phone.OFF || !Phone.enabled(pkg)) return
        val app = Phone.label(ctx, pkg)
        scrobExec.execute {
            val q = QueuedScrobble(UUID.randomUUID().toString(), s, "phone", System.currentTimeMillis())
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

    fun phoneNowPlaying(pkg: String, s: Scrobble) {
        if (Phone.mode() != Phone.ON || !Phone.enabled(pkg)) return
        scrobExec.execute {
            val c = lastfm() ?: return@execute
            val sk = prefs.lastfmSession.ifEmpty { return@execute }
            try {
                c.updateNowPlaying(sk, s)
            } catch (_: Exception) {
                // Best effort, like for records.
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
            val vinyl = activity.listens { it.optString("src") == "vinyl" }
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
                refreshHistoryInner(force = true)
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

    /** Fetches the scrobbles since the history was last known to be whole (see [HistorySync.refresh]), then rebuilds the stats. On [historyExec]. */
    private fun refreshHistoryInner(force: Boolean = false) {
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
            history = History.from(db, hidden())
            dropStats()
            statsExec.execute { statsInner() }
            fetchTagsInner(c)
        }
    }

    private fun dropStats() {
        statsGen++
        stats = null
    }

    /** The stats for the current history, built on first use (and again on a new day). */
    private fun currentStats(): Stats? {
        val today = java.time.LocalDate.now().toEpochDay()
        stats?.takeIf { statsDay == today }?.let { return it }
        // Kept only if nothing changed meanwhile: else a slow build would put older stats over newer ones.
        val gen = statsGen
        val h = history ?: if (db.count() > 0) History.from(db, hidden()).also { if (statsGen == gen) history = it } else return null
        return Stats(h, java.time.ZoneId.systemDefault(), System.currentTimeMillis() / 1000, collection, Genres(collection, db.tags()), vinylListens(), db.images(), yearsSnapshot(), globalsSnapshot())
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

    /** What the Stats tab and the story show: everything, or only the scrobbles of [statsGenre] and/or [statsDecade]. */
    private fun shownStats(): Stats? {
        val base = currentStats() ?: return null
        val g = statsGenre
        val d = statsDecade
        if (g.isEmpty() && d == 0) return base
        val key = "$g|$d"
        filteredStats?.takeIf { filteredKey == key && filteredBase === base }?.let { return it }
        val h = history ?: return base
        val inGenre = if (g.isEmpty()) null else base.inGenre(g)
        val inDecade = if (d == 0) null else base.inDecade(d)
        val sub = h.filter { i -> (inGenre == null || inGenre(i)) && (inDecade == null || inDecade(i)) }
        // Only the record plays among the scrobbles shown (at the same second as one of them).
        val vinyl = vinylListens().filter { v -> sub.lowerBound(v.ts).let { it < sub.size && sub.ts[it] == v.ts } }
        return Stats(sub, java.time.ZoneId.systemDefault(), System.currentTimeMillis() / 1000, collection, Genres(collection, db.tags()), vinyl, db.images(), yearsSnapshot(), globalsSnapshot())
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

    /**
     * An artist's discography from Deezer (kept a week on the phone, so new releases show up),
     * with your plays and your shelf. On [webExec].
     */
    private fun discographyJson(name: String): JSONObject {
        val key = Text.norm(name)
        val cache = try {
            JSONObject(file(DISCO_FILE).read() ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }
        var c = cache.optJSONObject(key)
        if (c == null || System.currentTimeMillis() - c.optLong("at") > DISCO_TTL) {
            val a = deezer.findArtist(name)
            c = JSONObject().put("at", System.currentTimeMillis())
            if (a != null) {
                c.put("artist", a.toJson()).put("releases", JSONArray(deezer.releases(a.id).map { it.toJson() }))
                // The photo comes along for free.
                if (a.photo.isNotEmpty() && Stats.photoKey(name) !in db.images().keys) db.saveImage(Stats.photoKey(name), a.photo)
            }
            cache.put(key, c)
            // Keep the most recent lookups only.
            val keys = cache.keys().asSequence().toList()
            if (keys.size > DISCO_MAX) keys.sortedBy { cache.getJSONObject(it).optLong("at") }.take(keys.size - DISCO_MAX).forEach { cache.remove(it) }
            file(DISCO_FILE).write(cache.toString())
        }
        val artist = c.optJSONObject("artist") ?: return JSONObject().put("none", true)
        val rel = c.optJSONArray("releases") ?: JSONArray()
        val releases = (0 until rel.length()).map { DzRelease.fromJson(rel.getJSONObject(it)) }
        val s = currentStats()
        val shelf = collection.associateBy { Text.albumKey(it.artist, it.title) }
        val items = Discography.json(Discography.merge(releases), { t -> s?.albumPlaysOf(name, t) ?: 0 }, { t -> shelf[Text.albumKey(name, t)]?.releaseId ?: 0L })
        return JSONObject().put("artist", DzArtist.fromJson(artist).toJson()).put("items", items)
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
        push("onStats") {
            shown.compute(shown.range(id)).put("tidy", tidySummary()).put("genre", statsGenre).put("genreOptions", s.genreOptions())
                .put("decade", statsDecade).put("decadeOptions", s.decadeOptions())
        }
        // Release years for the decade filter, looked up now and then as the history grows.
        if (!yearsBusy && System.currentTimeMillis() - yearsTried > 6 * HOUR) historyExec.execute { yearsInner() }
        // Everyone's counts on Last.fm, for the comparisons, likewise.
        if (globalsDue() && System.currentTimeMillis() - globalsTried > 6 * HOUR) historyExec.execute { globalsInner() }
        val missing = s.albumsNeedingImages(id)
        if (missing.isNotEmpty()) historyExec.execute { fetchImagesInner(missing) }
        val photos = s.artistsNeedingPhotos(id)
        if (photos.isNotEmpty()) historyExec.execute { fetchPhotosInner(photos) }
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

    private fun saveHidden(h: Hidden) {
        hiddenCache = h
        file(HIDDEN_FILE).write(h.toJson().toString())
    }

    /** After the hidden list changed: the history without those scrobbles, new stats and tidy-up lists. On [statsExec]. */
    private fun rebuildHistory() {
        if (db.count() == 0) return
        history = History.from(db, hidden())
        dropStats()
        push("onTidy") { tidy() ?: JSONObject().put("empty", true) }
        statsInner()
    }

    /** Suggestions of scrobbles that look like videos or apps, the most played ones without an album, and what is hidden. */
    private fun tidy(): JSONObject? {
        val h = history ?: (if (db.count() > 0) History.from(db, hidden()).also { history = it } else return null)
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

    /** Last.fm tags for the most played artists Discogs can't give genres for. On [historyExec]. */
    private fun fetchTagsInner(c: LastfmClient) {
        val s = currentStats() ?: return
        val need = s.artistsNeedingTags(db.tags().keys)
        if (need.isEmpty()) return
        tagsTotal = need.size
        tagsDone = 0
        pushState()
        try {
            for (a in need) {
                db.saveTags(Genres.artistKey(a), Genres.fromTags(c.artistTopTags(a), a))
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
     * Covers of albums that aren't on your shelf, from Deezer (Last.fm's terms leave its artwork
     * out of what apps may show). "" is kept when there is none. On [historyExec].
     */
    private fun fetchImagesInner(missing: List<Triple<String, String, String>>, after: (() -> Unit)? = null) {
        val tried = db.images().keys
        var any = false
        for ((artist, album, key) in missing) {
            if (key in tried) continue
            try {
                db.saveImage(key, deezer.albumCover(artist, album).orEmpty())
                any = true
                Thread.sleep(120) // Deezer allows 50 requests in 5 seconds
            } catch (_: Exception) {
                break
            }
        }
        if (any) picturesArrived(after)
    }

    /** Artist photos from Deezer for [names] not looked up yet ("" is kept when there is none). On [historyExec]. */
    private fun fetchPhotosInner(names: List<String>, after: (() -> Unit)? = null) {
        val tried = db.images().keys
        var any = false
        for (n in names) {
            val key = Stats.photoKey(n)
            if (key in tried) continue
            try {
                db.saveImage(key, deezer.artistPhoto(n).orEmpty())
                any = true
                Thread.sleep(120) // Deezer allows 50 requests in 5 seconds
            } catch (_: Exception) {
                break // offline or over the quota: next time
            }
        }
        if (any) picturesArrived(after)
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
                while (palettes.size > 400) palettes.remove(palettes.keys.first())
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
            if (fn !in NOT_KEPT) build().toString().let { s -> synchronized(missed) { missed[fn] = s } }
            return
        }
        val s = build().toString()
        main.post { ui?.push(fn, s) }
    }

    fun pushAll() {
        pushState()
        pushCollection()
        pushActivity()
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
        if (ui == null || statePending) return
        statePending = true
        main.postDelayed({
            stateExec.execute {
                statePending = false
                push("onState") { stateJson() }
            }
        }, 100)
    }

    private fun pushCollection() = push("onCollection") {
        val sp = spinCounts
        val pl = plays
        JSONArray().also { a ->
            collection.forEach { c ->
                a.put(c.toJson().put("plays", pl[Text.albumKey(c.artist, c.title)] ?: 0).put("spins", sp[c.releaseId.toString()] ?: 0))
            }
        }
    }

    private fun pushActivity() = push("onActivity") { activityJson() }

    /**
     * The activity log with covers: a record's own, else your shelf's copy of the album, else
     * Deezer's (phone scrobbles carry none). Missing ones are looked up in the
     * background and the list comes again.
     */
    private fun activityJson(): JSONArray {
        val a = activity.toJson()
        val owned = collection.associateBy { Text.albumKey(it.artist, it.title) }
        val images = db.images()
        val missing = LinkedHashMap<String, Triple<String, String, String>>()
        for (i in 0 until a.length()) {
            val e = JSONObject(a.getJSONObject(i).toString())
            a.put(i, e)
            val album = e.optString("album")
            if (e.optString("cover").isNotEmpty() || album.isBlank()) continue
            val key = Text.albumKey(e.optString("artist"), album)
            val c = owned[key]?.let { it.cover.ifEmpty { it.thumb } } ?: images[key]
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
                val owned = collection.firstOrNull { Text.albumKey(it.artist, it.title) == key }?.let { it.cover.ifEmpty { it.thumb } }
                val url = owned ?: db.images()[key] ?: try {
                    deezer.albumCover(artist, album).orEmpty().also {
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
        .put("spin", spinUi?.let { JSONObject(it) } ?: JSONObject.NULL)
        .put("phone", phoneJson())
        .put(
            "history",
            JSONObject().put("count", histCount).put("done", prefs.historyDone).put("resumable", prefs.historyCursor > 0)
                .put("importing", importing).put("syncing", histSyncing).put("fetched", histDone).put("total", histTotal)
                .put("error", histError).put("lastSync", prefs.historyLastSync).put("tagsDone", tagsDone).put("tagsTotal", tagsTotal)
                .put("yearsBusy", yearsBusy).put("yearsDone", yearsDone).put("yearsTotal", yearsTotal)
                .put("globalsBusy", globalsBusy).put("globalsDone", globalsDone).put("globalsTotal", globalsTotal),
        )
        .put(
            "tidal",
            JSONObject().put("hasApp", tidal.hasApp).put("builtIn", prefs.tidalBuiltIn).put("loggedIn", tidal.loggedIn).put("busy", tidalBusy).put("error", tidalError)
                .put("clientHint", hint(prefs.tidalClientId)).put("redirect", TidalClient.REDIRECT),
        )
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
