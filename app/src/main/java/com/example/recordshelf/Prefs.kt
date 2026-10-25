package com.example.recordshelf

import android.content.Context
import android.content.SharedPreferences

/**
 * Settings, plus the user's keys and tokens in a separate file ("secrets") that is kept out
 * of Android backups (see res/xml/backup_rules.xml).
 */
class Prefs(ctx: Context) {
    private val app: SharedPreferences = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
    private val sec: SharedPreferences = ctx.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    private fun s(p: SharedPreferences, k: String, def: String = "") = p.getString(k, def) ?: def
    private fun put(p: SharedPreferences, k: String, v: String) = p.edit().putString(k, v).apply()

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

    var lastfmKey: String
        get() = s(sec, "lastfmKey")
        set(v) = put(sec, "lastfmKey", v)

    var lastfmSecret: String
        get() = s(sec, "lastfmSecret")
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
}
