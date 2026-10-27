package app.recordshelf

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import app.recordshelf.scrobble.Play
import app.recordshelf.scrobble.PlayState
import app.recordshelf.scrobble.PlayTracker
import app.recordshelf.scrobble.TrackMeta
import org.json.JSONArray
import org.json.JSONObject

/**
 * Watches what music apps play, through their media sessions (the same source as the
 * lock-screen player). Android only hands media sessions to an enabled notification
 * listener, which is why this is one; notifications themselves are never read.
 *
 * The system keeps this service bound while access is granted, so it runs without a
 * foreground notification. Each player gets a [PlayTracker]; what counts goes to [Core].
 */
class PhoneListener : NotificationListenerService() {

    companion object {
        @Volatile
        var instance: PhoneListener? = null
            private set

        fun component(ctx: android.content.Context) = ComponentName(ctx, PhoneListener::class.java)
    }

    private val main = Handler(Looper.getMainLooper())
    private var msm: MediaSessionManager? = null
    private val sessions = HashMap<MediaSession.Token, Session>()

    /**
     * One tracker per app, not per session: some players drop their session when paused and
     * make a new one on resume. Keeping the tracker means the listen simply continues.
     */
    private val trackers = HashMap<String, Tracked>()

    /** Per app, the session that last told something: the one the Now playing page reads and controls. */
    private val active = HashMap<String, MediaController>()
    private val onSessions = MediaSessionManager.OnActiveSessionsChangedListener { update(it) }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Core.ensure(this)
        instance = this
        Phone.connected = true
        val m = getSystemService(MediaSessionManager::class.java)
        msm = m
        try {
            m.addOnActiveSessionsChangedListener(onSessions, component(this), main)
            update(m.getActiveSessions(component(this)))
        } catch (_: SecurityException) {
            // Access was revoked in the meantime.
        }
        Core.pushState()
    }

    override fun onListenerDisconnected() {
        try {
            msm?.removeOnActiveSessionsChangedListener(onSessions)
        } catch (_: Exception) {
        }
        dropAll()
        Phone.connected = false
        if (instance === this) instance = null
        Core.pushState()
        // Ask to be bound again (the system unbinds listeners now and then, e.g. on updates).
        try {
            requestRebind(component(this))
        } catch (_: Exception) {
        }
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        dropAll()
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** The mode changed: start over with the current players. Main thread. */
    fun reset() {
        dropAll()
        try {
            msm?.getActiveSessions(component(this))?.let { update(it) }
        } catch (_: SecurityException) {
        }
    }

    private fun update(controllers: List<MediaController>?) {
        if (Phone.mode() == Phone.OFF) {
            dropAll()
            return
        }
        val list = controllers.orEmpty()
        val tokens = list.map { it.sessionToken }.toSet()
        for (t in sessions.keys.filter { it !in tokens }) sessions.remove(t)?.end()
        for (c in list) if (c.sessionToken !in sessions) sessions[c.sessionToken] = Session(c).also { it.begin() }
    }

    /**
     * Play, pause, skip, seek ([arg] ms) or jump to a queue item ([arg] its id) in app [pkg], as
     * asked on the Now playing page. Apps ignore what they don't support.
     */
    fun control(pkg: String, action: String, arg: Long) = main.post {
        val c = active[pkg] ?: sessions.values.firstOrNull { it.c.packageName == pkg }?.c ?: return@post
        val t = c.transportControls
        when (action) {
            "play" -> t.play()
            "pause" -> t.pause()
            "next" -> t.skipToNext()
            "prev" -> t.skipToPrevious()
            "seek" -> t.seekTo(arg)
            "queue" -> t.skipToQueueItem(arg)
        }
    }

    private fun dropAll() {
        active.clear()
        Phone.clearMedia()
        sessions.values.forEach { it.end() }
        sessions.clear()
        trackers.values.forEach { it.close() }
        trackers.clear()
        Phone.clearNow()
    }

    /** The listening state of one app. */
    private inner class Tracked(val pkg: String) : PlayTracker.Output {
        val tracker = PlayTracker(this)
        private val tick = Runnable {
            tracker.onTick(mono())
            reschedule()
        }

        fun reschedule() {
            main.removeCallbacks(tick)
            tracker.nextCheckIn(mono())?.let { main.postDelayed(tick, it + 200) }
        }

        fun close() {
            main.removeCallbacks(tick)
            tracker.onDestroyed(mono())
        }

        override fun nowPlaying(play: Play) = Core.phoneNowPlaying(pkg, play.toScrobble())

        override fun scrobble(play: Play) = Core.phoneScrobble(pkg, play.toScrobble())

        override fun changed() = Phone.update(this@PhoneListener, pkg, tracker, mono())
    }

    /** One media session; its events go to its app's tracker. */
    private inner class Session(val c: MediaController) : MediaController.Callback() {
        val t = trackers.getOrPut(c.packageName) { Tracked(c.packageName) }

        fun begin() {
            c.registerCallback(this, main)
            t.tracker.onMetadata(meta(c.metadata), mono(), wall())
            c.playbackState?.let { t.tracker.onState(state(it), position(it), mono(), wall()) }
            t.reschedule()
            media()
        }

        /** The app's own account of its playback goes to the Now playing page. */
        fun media() {
            active[c.packageName] = c
            Phone.media(c.packageName, mediaJson(c))
        }

        /** The session went away: for the listen that is a pause, until the app plays again. */
        fun end() {
            try {
                c.unregisterCallback(this)
            } catch (_: Exception) {
            }
            if (active[c.packageName] === c) active.remove(c.packageName)
            t.tracker.onState(PlayState.PAUSED, -1, mono(), wall())
            t.reschedule()
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            t.tracker.onMetadata(meta(metadata), mono(), wall())
            t.reschedule()
            media()
        }

        override fun onPlaybackStateChanged(s: PlaybackState?) {
            if (s == null) return
            t.tracker.onState(state(s), position(s), mono(), wall())
            t.reschedule()
            media()
        }

        override fun onQueueChanged(queue: MutableList<MediaSession.QueueItem>?) = media()

        override fun onQueueTitleChanged(title: CharSequence?) = media()

        override fun onSessionDestroyed() {
            end()
            sessions.remove(c.sessionToken)
        }
    }

    private fun mono() = SystemClock.elapsedRealtime()
    private fun wall() = System.currentTimeMillis()

    private fun meta(m: MediaMetadata?): TrackMeta? {
        m ?: return null
        val title = m.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty()
        val albumArtist = m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)?.trim().orEmpty()
        val artist = m.getString(MediaMetadata.METADATA_KEY_ARTIST)?.trim().orEmpty().ifEmpty { albumArtist }
        val album = m.getString(MediaMetadata.METADATA_KEY_ALBUM)?.trim().orEmpty()
        val dur = m.getLong(MediaMetadata.METADATA_KEY_DURATION)
        return TrackMeta(artist, title, album, albumArtist, if (dur > 0) dur else 0)
    }

    private fun state(s: PlaybackState) = when (s.state) {
        PlaybackState.STATE_PLAYING -> PlayState.PLAYING
        PlaybackState.STATE_STOPPED, PlaybackState.STATE_NONE, PlaybackState.STATE_ERROR -> PlayState.STOPPED
        // Paused, buffering, connecting, skipping, seeking: not listening right now.
        else -> PlayState.PAUSED
    }

    /**
     * What the app itself says about its playback: the exact position (as of [posAt]), what it lets
     * us do, and its queue (the songs around the current one, up to 40) with its title, such as a
     * playlist's name. Apps that work with Android Auto usually share a queue; others don't.
     */
    private fun mediaJson(c: MediaController): JSONObject {
        val o = JSONObject()
        val s = c.playbackState
        if (s != null) {
            val a = s.actions
            fun can(bit: Long) = (a and bit) != 0L
            o.put("pos", position(s)).put("posAt", wall()).put("speed", s.playbackSpeed.toDouble())
                .put("playing", s.state == PlaybackState.STATE_PLAYING)
                .put("canPlay", can(PlaybackState.ACTION_PLAY_PAUSE) || can(PlaybackState.ACTION_PLAY) || can(PlaybackState.ACTION_PAUSE))
                .put("canNext", can(PlaybackState.ACTION_SKIP_TO_NEXT)).put("canPrev", can(PlaybackState.ACTION_SKIP_TO_PREVIOUS))
                .put("canSeek", can(PlaybackState.ACTION_SEEK_TO)).put("canQueue", can(PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM))
                .put("active", s.activeQueueItemId)
        }
        c.metadata?.let { m ->
            o.put("dur", m.getLong(MediaMetadata.METADATA_KEY_DURATION))
            m.getLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER).takeIf { it > 0 }?.let { o.put("trackNo", it) }
        }
        c.queueTitle?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { o.put("queueTitle", it) }
        val q = try {
            c.queue
        } catch (_: Exception) {
            null
        }
        if (!q.isNullOrEmpty()) {
            val at = q.indexOfFirst { it.queueId == s?.activeQueueItemId }
            val from = if (at >= 0) maxOf(0, at - 3) else 0
            val items = JSONArray()
            for (i in from until minOf(q.size, from + 40)) {
                val d = q[i].description
                items.put(JSONObject().put("id", q[i].queueId).put("title", d.title?.toString().orEmpty()).put("artist", d.subtitle?.toString().orEmpty()))
            }
            o.put("queue", items).put("queueSize", q.size)
        }
        return o
    }

    /** Where playback is now; the session reports it as of its last update. -1 if unknown. */
    private fun position(s: PlaybackState): Long {
        if (s.position < 0) return -1
        if (s.state != PlaybackState.STATE_PLAYING || s.lastPositionUpdateTime <= 0) return s.position
        val dt = SystemClock.elapsedRealtime() - s.lastPositionUpdateTime
        return s.position + (dt * s.playbackSpeed).toLong()
    }
}
