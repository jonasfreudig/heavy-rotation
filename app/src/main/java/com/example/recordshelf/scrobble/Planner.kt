package com.example.recordshelf.scrobble

import com.example.recordshelf.lastfm.Scrobble
import com.example.recordshelf.util.str
import org.json.JSONObject
import kotlin.math.min

/** A track as it will be scrobbled. [estimated] means its length is a guess (the default). */
data class PlanTrack(
    val artist: String,
    val title: String,
    val album: String,
    val albumArtist: String,
    val durSec: Int,
    val trackNumber: Int,
    val pos: String,
    val side: String,
    val estimated: Boolean,
) {
    fun toScrobble(startMs: Long) = Scrobble(artist, title, album, albumArtist, startMs / 1000, durSec, trackNumber)

    fun toJson(): JSONObject = JSONObject().put("artist", artist).put("title", title).put("album", album)
        .put("albumArtist", albumArtist).put("dur", durSec).put("n", trackNumber).put("pos", pos).put("side", side)
        .put("est", estimated)

    companion object {
        fun fromJson(o: JSONObject) = PlanTrack(
            o.str("artist"), o.str("title"), o.str("album"), o.str("albumArtist"), o.optInt("dur"), o.optInt("n"),
            o.str("pos"), o.str("side"), o.optBoolean("est"),
        )
    }
}

/** When the listening happened. */
enum class When { NOW, AT, FINISHED }

object Planner {
    const val DAY_MS = 86_400_000L

    /** Last.fm ignores scrobbles older than this (ignored code 3). */
    const val MAX_AGE_MS = 14 * DAY_MS

    /** Time to lift the needle and turn the record over. */
    const val FLIP_GAP_MS = 20_000L

    /**
     * Start times (ms) for [tracks] played back to back, with a short pause where the side
     * changes. NOW starts at [nowMs], AT at [atMs]; FINISHED ends at [nowMs].
     */
    fun starts(tracks: List<PlanTrack>, mode: When, atMs: Long, nowMs: Long): List<Long> {
        val gaps = tracks.indices.map { i -> if (i > 0 && tracks[i].side != tracks[i - 1].side) FLIP_GAP_MS else 0L }
        val total = tracks.sumOf { it.durSec * 1000L } + gaps.sum()
        var t = when (mode) {
            When.NOW -> nowMs
            When.AT -> atMs
            When.FINISHED -> nowMs - total
        }
        return tracks.mapIndexed { i, tr ->
            t += gaps[i]
            val s = t
            t += tr.durSec * 1000L
            s
        }
    }

    /** Last.fm's rule: a track counts once half of it (or 4 minutes, if sooner) has played. */
    fun dueMs(startMs: Long, durSec: Int) = startMs + min(durSec * 500L, 240_000L)

    fun tooOld(startMs: Long, nowMs: Long) = nowMs - startMs > MAX_AGE_MS
}
