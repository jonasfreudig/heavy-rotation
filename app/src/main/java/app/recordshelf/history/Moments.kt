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

    /** Which photos a story may show: taken while a song played, around music, or at any time. */
    val MODES = setOf("playing", "near", "any")

    /** Pictures saved from these apps aren't ones you took. */
    private val APPS = listOf("whatsapp", "telegram", "instagram", "messenger", "signal", "download", "facebook", "snapchat")
    private val STORAGE_TOP = Regex("^/storage/(emulated/\\d+|[^/]+)/")

    /**
     * The folder of a photo from the storage's top ("DCIM/Camera"): [path] is its relative path, or with
     * [whole] (before Android 10) the file's whole path.
     */
    fun folderOf(path: String, whole: Boolean): String =
        (if (whole) path.substringBeforeLast('/', "") else path).replace(STORAGE_TOP, "").trim('/')

    /** Whether [folder] is the camera's (DCIM, or Pictures for some phones), not screenshots or another app's. */
    fun byCamera(folder: String): Boolean {
        val p = "/" + folder.lowercase().trim('/') + "/"
        if ("screenshot" in p || "screen record" in p || "screenrecord" in p) return false
        return "/dcim/" in p || ("/pictures/" in p && APPS.none { it in p })
    }

    /** Moments at least this far apart, or a share of the period for long ones. */
    private const val GAP_MIN = 6 * 3600L

    /**
     * Up to [max] of [photos], taken over a period [spanSec] long, in the order they were taken. [playing] tells
     * whether a song was under way at a time, [near] how close to music it was (1 playing, down to 0 for none).
     */
    fun pick(photos: List<Photo>, spanSec: Long, playing: (Long) -> Boolean, near: (Long) -> Double, max: Int = MAX): List<Photo> {
        if (photos.isEmpty() || max <= 0) return emptyList()
        val bursts = ArrayList<MutableList<Photo>>()
        for (p in photos.sortedBy { it.ts }) {
            if (bursts.isNotEmpty() && p.ts - bursts.last().last().ts <= BURST_SEC) bursts.last() += p else bursts += mutableListOf(p)
        }
        // Each moment's photo: one taken while a song played if there is one, else the one closest to music, else
        // the middle one. Moments with a song playing count most, then the closer to music the more.
        val moments = bursts.map { b ->
            val withSong = b.filter { playing(it.ts) }
            val closest = b.maxBy { near(it.ts) }
            val photo = when {
                withSong.isNotEmpty() -> withSong[withSong.size / 2]
                near(closest.ts) > 0 -> closest
                else -> b[b.size / 2]
            }
            val music = if (withSong.isNotEmpty()) 3.0 else 2.0 * near(closest.ts)
            photo to music + minOf(2.0, b.size / 4.0)
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
