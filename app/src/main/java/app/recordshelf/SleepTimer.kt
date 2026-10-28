package app.recordshelf

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import org.json.JSONObject

/**
 * Now playing's sleep timer: stops what plays on the phone after some minutes, or at the end of
 * the song. The music fades out first (the media volume, put back once it has stopped), then the
 * app playing gets "pause" through its media session. The time is kept in the settings and an
 * alarm wakes the app for the fade, so a closed app stops the music too.
 */
object SleepTimer {
    /** How long the music fades out: after some minutes, and at the end of a song. */
    private const val FADE_MS = 30_000L
    private const val SONG_FADE_MS = 6_000L

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var fading = false

    /**
     * What the page shows: when it stops (ms) and whether at the end of the song; null when off.
     * A time long gone counts as off (a restart takes the alarm along, and nothing plays then).
     */
    fun json(): JSONObject? = Core.prefs.sleepAt.takeIf { it > System.currentTimeMillis() - 60_000 }
        ?.let { JSONObject().put("at", it).put("song", Core.prefs.sleepSong) }

    fun start(ctx: Context, minutes: Int) = set(ctx, System.currentTimeMillis() + minutes.coerceIn(1, 600) * 60_000L, song = false)

    /** At the end of the song playing now, by its own position and length; false if those aren't known. */
    fun startEndOfSong(ctx: Context): Boolean {
        val left = songLeft() ?: return false
        set(ctx, System.currentTimeMillis() + left, song = true)
        return true
    }

    fun cancel(ctx: Context) {
        Core.prefs.sleepAt = 0
        SpinReceiver.scheduleSleep(ctx, null)
        fading = false
        Core.pushState()
    }

    private fun set(ctx: Context, at: Long, song: Boolean) {
        Core.prefs.sleepAt = at
        Core.prefs.sleepSong = song
        SpinReceiver.scheduleSleep(ctx, at - if (song) SONG_FADE_MS else FADE_MS)
        Core.pushState()
    }

    /** How long the song playing now has left (ms, at its speed); null if nothing plays or its length isn't known. */
    private fun songLeft(): Long? {
        val m = Phone.playing()?.second ?: return null
        val dur = m.optLong("dur")
        if (dur <= 0 || !m.optBoolean("playing")) return null
        val speed = m.optDouble("speed", 1.0).takeIf { it > 0 } ?: 1.0
        val pos = m.optLong("pos") + ((System.currentTimeMillis() - m.optLong("posAt")) * speed).toLong()
        return ((dur - pos) / speed).toLong().coerceAtLeast(0)
    }

    /**
     * The alarm, a little before the time: the music fades from here and then stops. A song that was
     * paused or moved meanwhile gets a new end first. [done] once it is over (the alarm keeps the
     * phone awake till then).
     */
    fun onAlarm(ctx: Context, done: () -> Unit) {
        val at = Core.prefs.sleepAt
        if (at <= 0 || fading) return done()
        if (Core.prefs.sleepSong) {
            val left = songLeft()
            if (left != null && left > SONG_FADE_MS + 3000) {
                set(ctx, System.currentTimeMillis() + left, song = true)
                return done()
            }
        }
        // Nothing plays any more (paused by hand, say): nothing to stop.
        if (Phone.playing()?.second?.optBoolean("playing") != true) {
            Core.prefs.sleepAt = 0
            Core.pushState()
            return done()
        }
        main.post { fadeThenStop(ctx, (at - System.currentTimeMillis()).coerceIn(1000L, FADE_MS), done) }
    }

    private fun fadeThenStop(ctx: Context, ms: Long, done: () -> Unit) {
        val am = ctx.getSystemService(AudioManager::class.java)
        val from = am?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
        fun volume(v: Int) = try {
            am?.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
        } catch (_: SecurityException) {
            // Do not disturb can forbid volume changes: then it only stops.
        }
        fading = true
        val steps = (ms / 1000).toInt().coerceAtLeast(1)
        var k = 0
        val step = object : Runnable {
            override fun run() {
                if (!fading) { // turned off meanwhile
                    volume(from)
                    return done()
                }
                k++
                if (k < steps) {
                    volume(from * (steps - k) / steps)
                    main.postDelayed(this, ms / steps)
                    return
                }
                Phone.playing()?.first?.let { PhoneListener.instance?.control(it, "pause", 0) }
                Core.prefs.sleepAt = 0
                Core.pushState()
                // Once the app has paused, the volume is back where it was for next time.
                main.postDelayed({
                    volume(from)
                    fading = false
                    done()
                }, 1500)
            }
        }
        main.postDelayed(step, ms / steps)
    }
}
