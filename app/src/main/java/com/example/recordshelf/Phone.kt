package com.example.recordshelf

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationManagerCompat
import com.example.recordshelf.scrobble.PlayTracker
import com.example.recordshelf.scrobble.TrackMeta
import org.json.JSONArray
import org.json.JSONObject

/**
 * Phone scrobbling settings and status: the mode, which apps count, whether Android gave
 * access, and what is playing right now (for the UI). The listening itself happens in
 * [PhoneListener]; what counts goes through [Core.phoneScrobble].
 */
object Phone {
    const val OFF = "off"
    const val SHADOW = "shadow"
    const val ON = "on"
    const val TIDAL = "com.aspiro.tidal"
    const val PANO = "com.arn.scrobble"

    private val KNOWN = mapOf(
        TIDAL to "TIDAL", "com.spotify.music" to "Spotify", "com.google.android.apps.youtube.music" to "YouTube Music",
        "deezer.android.app" to "Deezer", "com.apple.android.music" to "Apple Music", "com.amazon.mp3" to "Amazon Music",
        "com.qobuz.music" to "Qobuz", "com.soundcloud.android" to "SoundCloud", "com.bandcamp.android" to "Bandcamp",
        "com.google.android.youtube" to "YouTube", "com.maxmpz.audioplayer" to "Poweramp",
    )

    @Volatile
    var connected = false

    @Volatile
    private var now: JSONObject? = null
    private var nowPkg = ""
    private val labels = HashMap<String, String>()

    fun mode(): String = Core.prefs.phoneMode

    @Synchronized
    fun apps(): JSONObject = try {
        JSONObject(Core.prefs.phoneApps)
    } catch (_: Exception) {
        JSONObject().put(TIDAL, JSONObject().put("label", "TIDAL").put("on", true))
    }

    fun enabled(pkg: String) = apps().optJSONObject(pkg)?.optBoolean("on") == true

    @Synchronized
    fun setEnabled(ctx: Context, pkg: String, on: Boolean) {
        val a = apps()
        val o = a.optJSONObject(pkg) ?: JSONObject().put("label", label(ctx, pkg))
        a.put(pkg, o.put("on", on))
        Core.prefs.phoneApps = a.toString()
        now?.let { if (it.optString("pkg") == pkg) it.put("on", on) }
    }

    /** Remembers an app that played something, so it can be switched on in Settings. */
    @Synchronized
    private fun noteSeen(ctx: Context, pkg: String, meta: TrackMeta) {
        val a = apps()
        val last = "${meta.artist} — ${meta.title}"
        val o = a.optJSONObject(pkg)
        if (o != null && o.optString("last") == last) return
        a.put(pkg, (o ?: JSONObject().put("label", label(ctx, pkg)).put("on", false)).put("last", last).put("seen", System.currentTimeMillis()))
        Core.prefs.phoneApps = a.toString()
    }

    fun label(ctx: Context, pkg: String): String = synchronized(labels) {
        labels.getOrPut(pkg) {
            KNOWN[pkg] ?: try {
                val pm = ctx.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (_: Exception) {
                pkg
            }
        }
    }

    @Volatile
    var access = false
        private set

    @Volatile
    private var pano = false

    /** Re-reads what can change outside the app: notification access, Pano installed. */
    fun refresh(ctx: Context) {
        access = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
        pano = try {
            ctx.packageManager.getPackageInfo(PANO, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    /** A tracker changed (main thread): refresh the "playing now" card. */
    fun update(ctx: Context, pkg: String, tracker: PlayTracker, mono: Long) {
        val p = tracker.play
        if (p == null) {
            if (nowPkg == pkg) clearNow()
            return
        }
        noteSeen(ctx, pkg, p.meta)
        // A paused app doesn't take the card from one that is playing.
        if (!tracker.playing && nowPkg.isNotEmpty() && nowPkg != pkg && now?.optBoolean("playing") == true) return
        now = JSONObject().put("pkg", pkg).put("app", label(ctx, pkg)).put("artist", p.meta.artist).put("title", p.meta.title)
            .put("album", p.meta.album).put("dur", p.meta.durationMs).put("listened", tracker.listened(mono))
            .put("at", System.currentTimeMillis()).put("playing", tracker.playing)
            .put("threshold", PlayTracker.thresholdMs(p.meta.durationMs)).put("scrobbled", p.scrobbled)
            .put("eligible", PlayTracker.eligible(p.meta)).put("on", enabled(pkg))
        nowPkg = pkg
        Core.pushState()
    }

    fun clearNow() {
        now = null
        nowPkg = ""
        Core.pushState()
    }

    fun stateJson(): JSONObject {
        val a = apps()
        val list = JSONArray()
        a.keys().asSequence().map { it to a.getJSONObject(it) }
            .sortedWith(compareByDescending<Pair<String, JSONObject>> { it.second.optBoolean("on") }.thenByDescending { it.second.optLong("seen") })
            .forEach { (pkg, o) -> list.put(JSONObject(o.toString()).put("pkg", pkg)) }
        return JSONObject().put("mode", mode()).put("access", access || connected).put("connected", connected)
            .put("pano", pano).put("apps", list).put("now", now ?: JSONObject.NULL)
            .put("since", Core.prefs.shadowSince)
    }
}
