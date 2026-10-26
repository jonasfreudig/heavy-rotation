package app.recordshelf.util

import java.text.Normalizer

/** Text helpers for matching names across Discogs, Last.fm and MusicBrainz. */
object Text {
    private val marks = Regex("\\p{Mn}+")
    private val brackets = Regex("\\s*[(\\[][^)\\]]*[)\\]]")
    private val nonAlnum = Regex("[^\\p{L}\\p{N}]+")
    private val spaces = Regex("\\s+")

    /** Lowercase, no accents, one kind of quote, "&" as "and", punctuation as single spaces. */
    fun norm(s: String): String {
        val n = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFKD).replace(marks, "")
            .replace("&", " and ")
            .replace(Regex("['‘’ʼ`]"), "")
        return n.replace(nonAlnum, " ").trim().replace(spaces, " ")
    }

    /** Like [norm], but drops "(Remastered 2011)", "[Live]" and " - 2004 Remaster" style additions. */
    fun normLoose(s: String): String {
        val cut = s.replace(brackets, " ").substringBefore(" - ")
        return norm(cut.ifBlank { s })
    }

    /** Key for "is this the same album" across services. */
    fun albumKey(artist: String, album: String): String =
        norm(artist).removePrefix("the ") + "|" + normLoose(album)

    /** "4:35" -> 275, "1:02:03" -> 3723; anything unreadable -> 0. */
    fun parseDuration(s: String?): Int {
        if (s.isNullOrBlank()) return 0
        val parts = s.trim().split(':', '.')
        val n = parts.map { it.trim().toIntOrNull() ?: return 0 }
        return when (n.size) {
            2 -> n[0] * 60 + n[1]
            3 -> n[0] * 3600 + n[1] * 60 + n[2]
            else -> 0
        }
    }

    fun formatDuration(sec: Int): String {
        val s = sec.coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }
}
