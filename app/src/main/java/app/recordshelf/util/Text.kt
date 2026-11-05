package app.recordshelf.util

import java.text.Normalizer

/** Text helpers for matching names across Discogs, Last.fm and MusicBrainz. */
object Text {
    private val marks = Regex("\\p{Mn}+")
    private val brackets = Regex("\\s*[(\\[][^)\\]]*[)\\]]")
    private val nonAlnum = Regex("[^\\p{L}\\p{N}]+")
    private val spaces = Regex("\\s+")
    private val quotes = Regex("['‘’ʼ`]")
    private val bandOrGuest = """\s*,\s+|\s+(?:&|and|\+)\s+the\s+|\s*\(?\b(?:feat\.?|ft\.|featuring)\s+"""
    private val firstOfTwo = Regex("""$bandOrGuest|\s+(?:&|and|\+)\s+""", RegexOption.IGNORE_CASE)
    private val firstOfBand = Regex(bandOrGuest, RegexOption.IGNORE_CASE)

    /** Lowercase, no accents, one kind of quote, "&" as "and", punctuation as single spaces. */
    fun norm(s: String): String {
        val n = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFKD).replace(marks, "")
            .replace("&", " and ")
            .replace(quotes, "")
        // Names made only of symbols ("!!!", "+/-") keep themselves, or they would all be one artist.
        return n.replace(nonAlnum, " ").trim().replace(spaces, " ").ifEmpty { s.lowercase().trim() }
    }

    /** Like [norm], but drops "(Remastered 2011)", "[Live]" and " - 2004 Remaster" style additions. */
    fun normLoose(s: String): String {
        val cut = s.replace(brackets, " ").substringBefore(" - ")
        return norm(cut.ifBlank { s })
    }

    /**
     * The first artist of a credit naming several: "D'Angelo" for "D'Angelo & The Vanguard", "D'Angelo, The Vanguard"
     * or "D'Angelo feat. Q-Tip", which a catalog often files under the first alone. With [pairs], "A & B" too (for an
     * album or a song, whose title still has to match). Null for a single name. Only worth asking once the whole
     * credit found nothing: "Tyler, The Creator" and "Simon & Garfunkel" are one artist each.
     */
    fun leadArtist(credit: String, pairs: Boolean = false): String? {
        val m = (if (pairs) firstOfTwo else firstOfBand).find(credit) ?: return null
        return credit.substring(0, m.range.first).trim().takeIf { it.isNotEmpty() }
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
