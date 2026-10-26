package app.recordshelf.scrobble

import app.recordshelf.discogs.Track
import app.recordshelf.util.Text

/** A track from another source (MusicBrainz, Last.fm): its number/position, title and length. */
data class SourceTrack(val number: String, val title: String, val durSec: Int)

/** Fills in track lengths that Discogs doesn't have. */
object Durations {
    fun missing(tracks: List<Track>) = tracks.count { it.durSec <= 0 }

    /**
     * Gives tracks without a length the length of the matching [source] track: same title,
     * then same title without "(Live)"-style additions, then same position, then (when both
     * lists are equally long) the same place in the list.
     */
    fun fill(tracks: List<Track>, source: List<SourceTrack>, src: String): List<Track> {
        if (source.isEmpty() || missing(tracks) == 0) return tracks
        val used = BooleanArray(source.size)
        val strict = source.map { Text.norm(it.title) }
        val loose = source.map { Text.normLoose(it.title) }
        fun free(k: Int) = !used[k] && source[k].durSec > 0
        return tracks.mapIndexed { i, t ->
            if (t.durSec > 0) return@mapIndexed t
            val title = Text.norm(t.title)
            val looseTitle = Text.normLoose(t.title)
            var k = source.indices.firstOrNull { free(it) && strict[it] == title }
                ?: source.indices.firstOrNull { free(it) && loose[it] == looseTitle }
                ?: source.indices.firstOrNull { free(it) && t.pos.isNotEmpty() && source[it].number.equals(t.pos, ignoreCase = true) }
                ?: -1
            if (k < 0 && source.size == tracks.size && free(i)) k = i
            if (k < 0) t else {
                used[k] = true
                t.copy(durSec = source[k].durSec, durSrc = src)
            }
        }
    }
}
