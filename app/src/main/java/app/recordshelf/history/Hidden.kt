package app.recordshelf.history

import app.recordshelf.util.Text
import org.json.JSONArray
import org.json.JSONObject

/**
 * Scrobbles the user keeps out of the stats: whole artists or single tracks (a show on HBO Max,
 * a game trailer on YouTube). Nothing changes on Last.fm. "Kept" tracks were suggested by
 * [Junk] and the user said they are music after all, so they aren't suggested again.
 */
class Hidden(
    val artists: List<String> = emptyList(),
    val tracks: List<Pair<String, String>> = emptyList(),
    val kept: List<Pair<String, String>> = emptyList(),
) {
    private val artistKeys = artists.mapTo(HashSet()) { Text.norm(it) }
    private val trackKeys = tracks.mapTo(HashSet()) { key(it.first, it.second) }
    private val keptKeys = kept.mapTo(HashSet()) { key(it.first, it.second) }

    fun hides(artist: String, title: String) = Text.norm(artist) in artistKeys || key(artist, title) in trackKeys

    fun isKept(artist: String, title: String) = key(artist, title) in keptKeys

    fun hideArtist(name: String) = if (Text.norm(name) in artistKeys) this else Hidden(artists + name, tracks, kept)

    fun hideTrack(artist: String, title: String) =
        if (key(artist, title) in trackKeys) this else Hidden(artists, tracks + (artist to title), kept.filterNot { same(it, artist, title) })

    fun keep(artist: String, title: String) = if (isKept(artist, title)) this else Hidden(artists, tracks, kept + (artist to title))

    fun showArtist(name: String) = Hidden(artists.filterNot { Text.norm(it) == Text.norm(name) }, tracks, kept)

    fun showTrack(artist: String, title: String) = Hidden(artists, tracks.filterNot { same(it, artist, title) }, kept)

    fun toJson(): JSONObject = JSONObject()
        .put("artists", JSONArray(artists))
        .put("tracks", pairs(tracks))
        .put("kept", pairs(kept))

    companion object {
        val NONE = Hidden()

        fun key(artist: String, title: String) = Text.norm(artist) + "\u0000" + Text.norm(title)

        private fun same(p: Pair<String, String>, artist: String, title: String) = key(p.first, p.second) == key(artist, title)

        private fun pairs(l: List<Pair<String, String>>) = JSONArray(l.map { JSONObject().put("artist", it.first).put("title", it.second) })

        fun fromJson(text: String?): Hidden {
            if (text.isNullOrBlank()) return NONE
            return try {
                val o = JSONObject(text)
                fun pairs(name: String): List<Pair<String, String>> {
                    val a = o.optJSONArray(name) ?: return emptyList()
                    return (0 until a.length()).map { a.getJSONObject(it) }.map { it.optString("artist") to it.optString("title") }
                }
                val a = o.optJSONArray("artists")
                Hidden((0 until (a?.length() ?: 0)).map { a!!.getString(it) }, pairs("tracks"), pairs("kept"))
            } catch (_: Exception) {
                NONE
            }
        }
    }
}

/**
 * Finds scrobbles that look like videos, apps or clips rather than music, the leftovers of a
 * scrobbler that listened to every app: "HBO Max", "Loop Hero Launch Trailer", a 20-second
 * sound on repeat. Only tracks without an album qualify, since real releases have one.
 */
object Junk {
    /** Apps and services whose name ends up as the title or artist when a scrobbler reads their player. */
    private val apps = listOf(
        "HBO Max", "Max", "Netflix", "YouTube", "YouTube Music", "YouTube Kids", "Twitch", "Disney+", "Disney Plus",
        "Prime Video", "Amazon Prime Video", "Crunchyroll", "Plex", "Jellyfin", "VLC", "Kodi", "TikTok", "Instagram",
        "Facebook", "Chrome", "Google Chrome", "Firefox", "Spotify", "TIDAL", "SoundCloud", "Audible", "Pocket Casts",
        "Google Podcasts", "Podcast Addict", "Advertisement", "Unknown", "Unknown Artist", "<unknown>", "Untitled",
    ).mapTo(HashSet()) { Text.norm(it) }

    private val videoWords = Regex(
        "\\b(trailer|teaser|gameplay|walkthrough|playthrough|livestream|let'?s play|unboxing|tutorial|podcast|full movie|official clip)\\b" +
            "|#shorts\\b|\\b(episode|folge|ep\\.?|s\\d{1,2}e)\\s*\\d+",
        RegexOption.IGNORE_CASE,
    )

    /** A track played again within this many seconds of starting, over and over, is a short clip on loop. */
    private const val CLIP_SEC = 40

    class Candidate(val artist: Int, val track: Int, val plays: Int, val last: Long, val reasons: List<String>)

    /**
     * Tracks without an album, most played first: [suggest] those that look like junk, plus the
     * most played remaining ones so the user can scan them too.
     */
    fun scan(h: History, hidden: Hidden, maxSuggestions: Int = 60, maxOthers: Int = 15): Pair<List<Candidate>, List<Candidate>> {
        val n = h.trackTitles.size
        val plays = IntArray(n)
        val withAlbum = IntArray(n)
        val quick = IntArray(n)
        val last = LongArray(n)
        for (i in 0 until h.size) {
            val t = h.track[i]
            plays[t]++
            if (h.album[i] >= 0) withAlbum[t]++
            last[t] = h.ts[i]
            if (i > 0 && h.track[i - 1] == t && h.ts[i] - h.ts[i - 1] < CLIP_SEC) quick[t]++
        }
        val sugg = ArrayList<Candidate>()
        val other = ArrayList<Candidate>()
        for (t in (0 until n).sortedByDescending { plays[it] }) {
            if (plays[t] == 0 || withAlbum[t] * 5 > plays[t]) continue // more than a fifth with an album: a release
            val a = h.trackArtist[t]
            val artist = h.artistNames[a]
            val title = h.trackTitles[t]
            if (hidden.isKept(artist, title)) continue
            val r = reasons(artist, title, quick[t])
            if (r.isNotEmpty()) {
                if (sugg.size < maxSuggestions) sugg += Candidate(a, t, plays[t], last[t], r)
            } else if (other.size < maxOthers && plays[t] >= 3) {
                other += Candidate(a, t, plays[t], last[t], emptyList())
            }
        }
        return sugg to other
    }

    /** Why a track without an album looks like something other than music; empty if it doesn't. */
    fun reasons(artist: String, title: String, quickRepeats: Int = 0): List<String> {
        val out = ArrayList<String>()
        val na = Text.norm(artist)
        val nt = Text.norm(title)
        if (nt in apps || na in apps) out += "app"
        if (videoWords.containsMatchIn(title)) out += "video"
        if (" | " in title || " | " in artist) out += "videoTitle"
        if (nt.isNotEmpty() && nt == na) out += "sameName"
        if (quickRepeats >= 3) out += "clip"
        return out
    }

    fun toJson(h: History, c: Candidate): JSONObject = JSONObject()
        .put("artist", h.artistNames[c.artist]).put("title", h.trackTitles[c.track])
        .put("plays", c.plays).put("last", c.last * 1000).put("reasons", JSONArray(c.reasons))
}
