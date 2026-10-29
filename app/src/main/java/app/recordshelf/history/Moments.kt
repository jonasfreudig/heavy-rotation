package app.recordshelf.history

import kotlin.math.abs

/** A photo on the phone: its id in the phone's media store, when it was taken (s) and its size. */
data class Photo(val id: Long, val ts: Long, val w: Int, val h: Int)

/**
 * The photos of a period worth a place in its story. Photos taken within [BURST_SEC] of each
 * other are one moment (a dinner, a gig, a walk); a moment counts more while music was playing
 * (most of all when a song was under way as the photo was taken) and the more photos it has.
 * The best ones are taken, spread over the period, never two too close together.
 */
object Moments {
    const val BURST_SEC = 20 * 60L
    const val MAX = 5

    /** Moments at least this far apart, or a share of the period for long ones. */
    private const val GAP_MIN = 6 * 3600L

    /**
     * Up to [max] of [photos], taken over a period [spanSec] long, in the order they were taken. [playing] tells
     * whether a song was under way at a time, [near] whether anything was played around then.
     */
    fun pick(photos: List<Photo>, spanSec: Long, playing: (Long) -> Boolean, near: (Long) -> Boolean, max: Int = MAX): List<Photo> {
        if (photos.isEmpty() || max <= 0) return emptyList()
        val bursts = ArrayList<MutableList<Photo>>()
        for (p in photos.sortedBy { it.ts }) {
            if (bursts.isNotEmpty() && p.ts - bursts.last().last().ts <= BURST_SEC) bursts.last() += p else bursts += mutableListOf(p)
        }
        // Each moment's photo: one taken while a song played if there is one, else the middle one.
        val moments = bursts.map { b ->
            val withSong = b.filter { playing(it.ts) }
            val from = withSong.ifEmpty { b }
            val music = if (withSong.isNotEmpty()) 3.0 else if (b.any { near(it.ts) }) 1.5 else 0.0
            from[from.size / 2] to music + minOf(2.0, b.size / 4.0)
        }
        val gap = maxOf(GAP_MIN, spanSec / (max * 3))
        val chosen = ArrayList<Photo>()
        for ((photo, _) in moments.sortedWith(compareByDescending<Pair<Photo, Double>> { it.second }.thenBy { it.first.ts })) {
            if (chosen.size >= max) break
            if (chosen.none { abs(it.ts - photo.ts) < gap }) chosen += photo
        }
        return chosen.sortedBy { it.ts }
    }
}
