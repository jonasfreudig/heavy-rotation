package com.example.recordshelf.scrobble

import com.example.recordshelf.util.objects
import com.example.recordshelf.util.str
import com.example.recordshelf.util.strings
import com.example.recordshelf.util.toJsonArray
import org.json.JSONArray
import org.json.JSONObject

class SpinItem(val track: PlanTrack, var startMs: Long, var state: Int = PLANNED) {
    val endMs get() = startMs + track.durSec * 1000L
    val dueMs get() = Planner.dueMs(startMs, track.durSec)

    fun toJson(): JSONObject = JSONObject().put("t", track.toJson()).put("start", startMs).put("state", state)

    companion object {
        const val PLANNED = 0
        const val SCROBBLED = 1
        const val SKIPPED = 2

        fun fromJson(o: JSONObject) = SpinItem(PlanTrack.fromJson(o.optJSONObject("t") ?: JSONObject()), o.optLong("start"), o.optInt("state"))
    }
}

/**
 * A record on the turntable: its tracks on a schedule. Tracks are handed out for scrobbling
 * once Last.fm's rule says they count ([takeDue]); the schedule can be corrected while
 * playing ([resync]), cut short ([stop]) or continued with the next side ([flip]).
 * Everything takes the current time, so the logic runs the same after the app was killed.
 */
class Spin(
    val id: String,
    val releaseId: Long,
    val album: String,
    val artist: String,
    val cover: String,
    /** All sides of the record in order, to know which side comes next. */
    val sides: List<String>,
    val items: MutableList<SpinItem>,
    /** Index of the track last sent as "now playing". */
    var nowPlaying: Int = -1,
) {
    private fun live() = items.filter { it.state != SpinItem.SKIPPED }

    fun endMs(): Long = live().maxOfOrNull { it.endMs } ?: 0L

    /** The track playing at [now] (the latest one, if a flip made two overlap), or -1. */
    fun currentIndex(now: Long): Int =
        items.indexOfLast { it.state != SpinItem.SKIPPED && now >= it.startMs && now < it.endMs }

    fun isOver(now: Long) = items.none { it.state == SpinItem.PLANNED } && now >= endMs()

    /** Marks and returns the tracks that count as listened at [now]. */
    fun takeDue(now: Long): List<SpinItem> =
        items.filter { it.state == SpinItem.PLANNED && now >= it.dueMs }.onEach { it.state = SpinItem.SCROBBLED }

    /** The next moment something changes: a track starts, ends or starts to count. */
    fun nextEventMs(now: Long): Long? {
        val times = ArrayList<Long>()
        for (it in items) {
            if (it.state == SpinItem.PLANNED) times += it.dueMs
            if (it.state != SpinItem.SKIPPED) {
                times += it.startMs
                times += it.endMs
            }
        }
        return times.filter { it > now }.minOrNull()
    }

    /** Ends the session: tracks past the half-way mark count, the rest are dropped. */
    fun stop(now: Long): List<SpinItem> {
        val due = takeDue(now)
        items.filter { it.state == SpinItem.PLANNED }.forEach { it.state = SpinItem.SKIPPED }
        return due
    }

    /** The record was turned over: what has played counts, the rest of this side is dropped, [tracks] start at [now]. */
    fun flip(tracks: List<PlanTrack>, now: Long): List<SpinItem> {
        val due = stop(now)
        var t = now
        for (tr in tracks) {
            items += SpinItem(tr, t)
            t += tr.durSec * 1000L
        }
        nowPlaying = -1
        return due
    }

    /**
     * "This track is playing now": track [index] starts at [now] and the ones after it follow.
     * Earlier tracks not counted yet are placed just before, so they count right away.
     * Tracks already sent keep their time on Last.fm.
     */
    fun resync(index: Int, now: Long) {
        if (index !in items.indices) return
        var t = now
        var prevSide: String? = null
        for (i in index until items.size) {
            val it = items[i]
            if (it.state == SpinItem.SKIPPED) continue
            if (prevSide != null && it.track.side != prevSide) t += Planner.FLIP_GAP_MS
            it.startMs = t
            t += it.track.durSec * 1000L
            prevSide = it.track.side
        }
        var b = now
        for (i in index - 1 downTo 0) {
            val it = items[i]
            if (it.state != SpinItem.PLANNED) break
            b -= it.track.durSec * 1000L
            it.startMs = b
        }
        nowPlaying = -1
    }

    /** The side after the last one in this session, if the record has one. */
    fun nextSide(): String? {
        val last = (live().lastOrNull() ?: items.lastOrNull())?.track?.side ?: return null
        val i = sides.indexOf(last)
        return if (i >= 0 && i + 1 < sides.size) sides[i + 1] else null
    }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("release", releaseId).put("album", album).put("artist", artist)
        .put("cover", cover).put("sides", sides.toJsonArray()).put("nowPlaying", nowPlaying)
        .put("items", JSONArray().also { a -> items.forEach { a.put(it.toJson()) } })

    /** What the UI shows: the schedule with each track's state. */
    fun toUiJson(): JSONObject = JSONObject().put("id", id).put("releaseId", releaseId).put("album", album).put("artist", artist)
        .put("cover", cover).put("nextSide", nextSide() ?: JSONObject.NULL)
        .put(
            "items",
            JSONArray().also { a ->
                items.forEach {
                    a.put(
                        JSONObject().put("title", it.track.title).put("artist", it.track.artist).put("pos", it.track.pos)
                            .put("side", it.track.side).put("dur", it.track.durSec).put("start", it.startMs).put("est", it.track.estimated)
                            .put("state", arrayOf("planned", "scrobbled", "skipped")[it.state.coerceIn(0, 2)]),
                    )
                }
            },
        )

    companion object {
        fun fromJson(o: JSONObject) = Spin(
            o.str("id"), o.optLong("release"), o.str("album"), o.str("artist"), o.str("cover"),
            o.optJSONArray("sides")?.strings().orEmpty(),
            o.optJSONArray("items")?.objects().orEmpty().map { SpinItem.fromJson(it) }.toMutableList(),
            o.optInt("nowPlaying", -1),
        )
    }
}
