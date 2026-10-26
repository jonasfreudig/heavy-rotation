package app.recordshelf.scrobble

import app.recordshelf.lastfm.Scrobble
import app.recordshelf.util.Text
import kotlin.math.max
import kotlin.math.min

/** What a music app reports about the current track. [durationMs] is 0 when unknown. */
data class TrackMeta(
    val artist: String,
    val title: String,
    val album: String = "",
    val albumArtist: String = "",
    val durationMs: Long = 0,
) {
    val key get() = Text.norm(artist) + "\u0000" + Text.norm(title)
    val valid get() = artist.isNotBlank() && title.isNotBlank()
}

enum class PlayState { PLAYING, PAUSED, STOPPED }

/** One listen of a track: when it started and how long it has actually played. */
class Play(var meta: TrackMeta) {
    /** Wall-clock start, 0 until the track first plays. */
    var startWallMs = 0L
    var listenedMs = 0L
    var scrobbled = false
    var nowPlayingSent = false

    fun toScrobble() = Scrobble(
        meta.artist, meta.title, meta.album, meta.albumArtist, startWallMs / 1000,
        (meta.durationMs / 1000).toInt(),
    )
}

/**
 * Follows one media session (one app's player) and decides when a listen counts, by
 * Last.fm's rules: the track is longer than 30 seconds and has played for half its length
 * or 4 minutes, whichever comes first. Only time actually playing counts: pauses,
 * buffering and seeking forward don't. Without a known length, 4 minutes of listening
 * are needed, the one case the rule covers for every track.
 *
 * Feed it the player's metadata and state changes and call [onTick] when [nextCheckIn]
 * says so; it reports through [Output]. Times are passed in: [mono] is a monotonic clock
 * (for durations), [wall] the wall clock (for scrobble timestamps).
 */
class PlayTracker(private val out: Output) {
    interface Output {
        /** The track has played for a few seconds: worth showing as "now playing". */
        fun nowPlaying(play: Play)

        /** The listen counts. Called once per listen. */
        fun scrobble(play: Play)

        /** Anything about the current listen changed (for the UI). */
        fun changed() {}
    }

    companion object {
        const val MIN_TRACK_MS = 30_000L
        const val MAX_WAIT_MS = 240_000L
        const val NOW_PLAYING_AFTER_MS = 3_000L

        /** A jump back to within this many ms of the start, after a listen counted, is a new listen (repeat). */
        const val REPLAY_POS_MS = 4_000L

        fun thresholdMs(durationMs: Long) = if (durationMs > 0) min(durationMs / 2, MAX_WAIT_MS) else MAX_WAIT_MS

        fun eligible(m: TrackMeta) = m.valid && (m.durationMs <= 0 || m.durationMs > MIN_TRACK_MS)
    }

    var play: Play? = null
        private set
    var playing = false
        private set

    /** Monotonic time when the current playing stretch began. */
    private var since = 0L

    /** The listen set aside while the player shows nothing usable; it continues if the same track comes back. */
    private var parked: Play? = null

    fun listened(mono: Long): Long {
        val p = play ?: return 0
        return p.listenedMs + if (playing) mono - since else 0
    }

    fun onMetadata(meta: TrackMeta?, mono: Long, wall: Long) {
        settle(mono)
        if (meta == null || !meta.valid) {
            // Players clear their metadata while switching or for ads; nothing counts meanwhile.
            parked = play ?: parked
            play = null
            out.changed()
            return
        }
        if (play == null && parked?.meta?.key == meta.key) {
            play = parked
            if (playing) since = mono
        }
        parked = null
        val p = play
        if (p != null && p.meta.key == meta.key) {
            // More details about the same track (album art loaded, length known now, ...).
            p.meta = TrackMeta(
                meta.artist, meta.title, meta.album.ifEmpty { p.meta.album }, meta.albumArtist.ifEmpty { p.meta.albumArtist },
                if (meta.durationMs > 0) meta.durationMs else p.meta.durationMs,
            )
            out.changed()
            check(mono)
            return
        }
        val n = Play(meta)
        if (playing) {
            n.startWallMs = wall
            since = mono
        }
        play = n
        out.changed()
        check(mono)
    }

    fun onState(state: PlayState, posMs: Long, mono: Long, wall: Long) {
        settle(mono)
        val nowPlaying = state == PlayState.PLAYING
        val p = play
        if (p != null && nowPlaying && posMs in 0..REPLAY_POS_MS && p.startWallMs > 0 &&
            (p.scrobbled || p.listenedMs >= thresholdMs(p.meta.durationMs))
        ) {
            play = Play(p.meta)
        }
        val q = play
        if (nowPlaying && q != null && q.startWallMs == 0L) {
            val back = if (posMs > 0 && (q.meta.durationMs <= 0 || posMs < q.meta.durationMs)) posMs else 0
            q.startWallMs = wall - back
        }
        playing = nowPlaying
        since = mono
        out.changed()
        check(mono)
    }

    fun onTick(mono: Long) = settle(mono)

    /** The player went away (app closed, session ended). */
    fun onDestroyed(mono: Long) {
        settle(mono)
        play = null
        playing = false
        out.changed()
    }

    /** Milliseconds until something is due (now playing, the scrobble), or null when nothing is pending. */
    fun nextCheckIn(mono: Long): Long? {
        val p = play ?: return null
        if (!playing || !eligible(p.meta) || p.startWallMs == 0L) return null
        val l = listened(mono)
        val waits = ArrayList<Long>()
        if (!p.nowPlayingSent) waits += NOW_PLAYING_AFTER_MS - l
        if (!p.scrobbled) waits += thresholdMs(p.meta.durationMs) - l
        return waits.minOrNull()?.let { max(it, 0) }
    }

    /** Adds the playing time since the last event, then sees whether anything is due. */
    private fun settle(mono: Long) {
        val p = play
        if (p != null && playing) {
            p.listenedMs += mono - since
            since = mono
        }
        check(mono)
    }

    private fun check(mono: Long) {
        val p = play ?: return
        if (p.startWallMs == 0L || !eligible(p.meta)) return
        val l = listened(mono)
        var any = false
        if (playing && !p.nowPlayingSent && l >= NOW_PLAYING_AFTER_MS) {
            p.nowPlayingSent = true
            out.nowPlaying(p)
            any = true
        }
        if (!p.scrobbled && l >= thresholdMs(p.meta.durationMs)) {
            p.scrobbled = true
            out.scrobble(p)
            any = true
        }
        if (any) out.changed()
    }
}
