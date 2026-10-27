package app.recordshelf

import android.content.Context
import android.content.SharedPreferences
import app.recordshelf.tidal.TidalStore

/**
 * Settings, plus the user's keys and tokens in a separate file ("secrets") that is kept out
 * of Android backups (see res/xml/backup_rules.xml).
 */
class Prefs(ctx: Context) : TidalStore {
    private val app: SharedPreferences = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
    private val sec: SharedPreferences = ctx.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    private fun s(p: SharedPreferences, k: String, def: String = "") = p.getString(k, def) ?: def
    private fun put(p: SharedPreferences, k: String, v: String) = p.edit().putString(k, v).apply()

    /** The plain settings (never the keys and tokens), for a backup. */
    fun plain(): Map<String, *> = app.all

    /** Puts back settings from a backup ([Backup.prefsJson]), written at once: the app restarts right after. */
    @android.annotation.SuppressLint("ApplySharedPref")
    fun restorePlain(o: org.json.JSONObject) {
        val e = app.edit().clear()
        for (k in o.keys()) {
            val x = o.optJSONObject(k) ?: continue
            when (x.optString("t")) {
                "s" -> e.putString(k, x.optString("v"))
                "l" -> e.putLong(k, x.optLong("v"))
                "i" -> e.putInt(k, x.optInt("v"))
                "b" -> e.putBoolean(k, x.optBoolean("v"))
                "f" -> e.putFloat(k, x.optDouble("v").toFloat())
            }
        }
        e.commit()
    }

    var theme: String
        get() = s(app, "theme", "system")
        set(v) = put(app, "theme", v)

    /** Length used for tracks no source knows. */
    var defaultTrackSec: Int
        get() = app.getInt("defaultTrackSec", 210)
        set(v) = app.edit().putInt("defaultTrackSec", v).apply()

    var discogsUser: String
        get() = s(app, "discogsUser")
        set(v) = put(app, "discogsUser", v)

    var lastSync: Long
        get() = app.getLong("lastSync", 0)
        set(v) = app.edit().putLong("lastSync", v).apply()

    var lastfmUser: String
        get() = s(app, "lastfmUser")
        set(v) = put(app, "lastfmUser", v)

    var playsUpdated: Long
        get() = app.getLong("playsUpdated", 0)
        set(v) = app.edit().putLong("playsUpdated", v).apply()

    /** Phone scrobbling: "off", "shadow" (log only, to compare with Pano) or "on". */
    var phoneMode: String
        get() = s(app, "phoneMode", "off")
        set(v) = put(app, "phoneMode", v)

    /** Music apps seen on the phone: {package: {label, on, seen, last}}. */
    var phoneApps: String
        get() = s(app, "phoneApps", "")
        set(v) = put(app, "phoneApps", v)

    /** When shadow mode was switched on; the comparison starts there. */
    var shadowSince: Long
        get() = app.getLong("shadowSince", 0)
        set(v) = app.edit().putLong("shadowSince", v).apply()

    /** Where an unfinished history import continues (Unix seconds, going back in time); 0 when none is pending. */
    var historyCursor: Long
        get() = app.getLong("historyCursor", 0)
        set(v) = app.edit().putLong("historyCursor", v).apply()

    /** The full history import finished once; after that only new scrobbles are fetched. */
    var historyDone: Boolean
        get() = app.getBoolean("historyDone", false)
        set(v) = app.edit().putBoolean("historyDone", v).apply()

    var historyLastSync: Long
        get() = app.getLong("historyLastSync", 0)
        set(v) = app.edit().putLong("historyLastSync", v).apply()

    /** Everything Last.fm had up to this time (Unix seconds) is stored; 0 when not known yet. */
    var historySyncedTo: Long
        get() = app.getLong("historySyncedTo", 0)
        set(v) = app.edit().putLong("historySyncedTo", v).apply()

    /** A refresh under way: the stretch it walks ([refreshFrom]..[refreshTo]) and where it goes on from (0: none). */
    var refreshCursor: Long
        get() = app.getLong("refreshCursor", 0)
        set(v) = app.edit().putLong("refreshCursor", v).apply()
    var refreshFrom: Long
        get() = app.getLong("refreshFrom", 0)
        set(v) = app.edit().putLong("refreshFrom", v).apply()
    var refreshTo: Long
        get() = app.getLong("refreshTo", 0)
        set(v) = app.edit().putLong("refreshTo", v).apply()

    /** The user paused the import: it waits for them, not for the app to come back to the front. */
    var importPaused: Boolean
        get() = app.getBoolean("importPaused", false)
        set(v) = app.edit().putBoolean("importPaused", v).apply()

    /** Whether the user entered their own Last.fm key; otherwise the app's own (if the build has one) is used. */
    private val ownLastfm get() = s(sec, "lastfmKey").isNotEmpty()

    /** The app's own Last.fm key is in use (the user only signs in). */
    val lastfmBuiltIn get() = !ownLastfm && BuildConfig.LASTFM_KEY.isNotEmpty() && BuildConfig.LASTFM_SECRET.isNotEmpty()

    var lastfmKey: String
        get() = if (ownLastfm) s(sec, "lastfmKey") else BuildConfig.LASTFM_KEY
        set(v) = put(sec, "lastfmKey", v)

    var lastfmSecret: String
        get() = if (ownLastfm) s(sec, "lastfmSecret") else BuildConfig.LASTFM_SECRET
        set(v) = put(sec, "lastfmSecret", v)

    var lastfmSession: String
        get() = s(sec, "lastfmSession")
        set(v) = put(sec, "lastfmSession", v)

    /** auth.getToken result while the user is approving access in the browser. */
    var lastfmPendingToken: String
        get() = s(sec, "lastfmPendingToken")
        set(v) = put(sec, "lastfmPendingToken", v)

    var discogsToken: String
        get() = s(sec, "discogsToken")
        set(v) = put(sec, "discogsToken", v)

    // TIDAL: the app's own TIDAL app (if the build has one) or the user's, and their login.
    private val ownTidal get() = s(sec, "tidalClientId").isNotEmpty()

    /** The app's own TIDAL app is in use (the user only signs in). */
    val tidalBuiltIn get() = !ownTidal && BuildConfig.TIDAL_CLIENT_ID.isNotEmpty() && BuildConfig.TIDAL_SECRET.isNotEmpty()

    override var tidalClientId: String
        get() = if (ownTidal) s(sec, "tidalClientId") else BuildConfig.TIDAL_CLIENT_ID
        set(v) = put(sec, "tidalClientId", v)

    override var tidalSecret: String
        get() = if (ownTidal) s(sec, "tidalSecret") else BuildConfig.TIDAL_SECRET
        set(v) = put(sec, "tidalSecret", v)

    override var tidalAccess: String
        get() = s(sec, "tidalAccess")
        set(v) = put(sec, "tidalAccess", v)

    override var tidalAccessExp: Long
        get() = sec.getLong("tidalAccessExp", 0)
        set(v) = sec.edit().putLong("tidalAccessExp", v).apply()

    override var tidalRefresh: String
        get() = s(sec, "tidalRefresh")
        set(v) = put(sec, "tidalRefresh", v)

    /** PKCE verifier and state while the user is signing in to TIDAL in the browser. */
    override var tidalVerifier: String
        get() = s(sec, "tidalVerifier")
        set(v) = put(sec, "tidalVerifier", v)

    override var tidalState: String
        get() = s(sec, "tidalState")
        set(v) = put(sec, "tidalState", v)
}
