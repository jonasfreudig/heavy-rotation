package com.example.recordshelf

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
import com.example.recordshelf.scrobble.Play
import com.example.recordshelf.scrobble.PlayState
import com.example.recordshelf.scrobble.PlayTracker
import com.example.recordshelf.scrobble.TrackMeta

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

    private fun dropAll() {
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
        }

        /** The session went away: for the listen that is a pause, until the app plays again. */
        fun end() {
            try {
                c.unregisterCallback(this)
            } catch (_: Exception) {
            }
            t.tracker.onState(PlayState.PAUSED, -1, mono(), wall())
            t.reschedule()
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            t.tracker.onMetadata(meta(metadata), mono(), wall())
            t.reschedule()
        }

        override fun onPlaybackStateChanged(s: PlaybackState?) {
            if (s == null) return
            t.tracker.onState(state(s), position(s), mono(), wall())
            t.reschedule()
        }

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

    /** Where playback is now; the session reports it as of its last update. -1 if unknown. */
    private fun position(s: PlaybackState): Long {
        if (s.position < 0) return -1
        if (s.state != PlaybackState.STATE_PLAYING || s.lastPositionUpdateTime <= 0) return s.position
        val dt = SystemClock.elapsedRealtime() - s.lastPositionUpdateTime
        return s.position + (dt * s.playbackSpeed).toLong()
    }
}
