package app.recordshelf.scrobble

import app.recordshelf.lastfm.Scrobble
import org.json.JSONArray
import org.json.JSONObject

/**
 * Names fixed before they're scrobbled, like Pano Scrobbler's edits: ready-made ones the user
 * switches on ("Remastered" off song titles and the like) and their own rules. A rule looks at
 * the artist, the song or the album: when it is ([how] "is") the text, the whole name becomes
 * [to]; when it "contains" the text, or a regular expression "matches" part of it, that part is
 * replaced by [to] (nothing: taken out). [artist], if set, limits a rule to one artist.
 */
object FixRules {
    data class Rule(
        val id: String,
        val field: String,
        val how: String,
        val find: String,
        val to: String,
        val artist: String = "",
        val on: Boolean = true,
    ) {
        fun toJson(): JSONObject = JSONObject().put("id", id).put("field", field).put("how", how).put("find", find)
            .put("to", to).put("artist", artist).put("on", on)

        companion object {
            fun fromJson(o: JSONObject) = Rule(
                o.optString("id"), o.optString("field", "title"), o.optString("how", "contains"), o.optString("find"),
                o.optString("to"), o.optString("artist"), o.optBoolean("on", true),
            )
        }
    }

    data class Preset(val id: String, val label: String, val example: String, val rules: List<Rule>)

    private const val REMASTER =
        """\s*[(\[][^)\]]*\bremaster(ed)?\b[^)\]]*[)\]]|\s+-\s+(\d{4}\s+)?(digital(ly)?\s+)?remaster(ed)?(\s+\d{4})?(\s+version)?\s*$"""
    private const val EXPLICIT = """\s*[(\[](explicit|clean)(\s+version)?[)\]]|\s+-\s+explicit\s*$"""

    /** The ready-made fixes, all off until the user switches them on. */
    val PRESETS = listOf(
        Preset("remaster", "Take \"Remastered\" off songs and albums", "Heroes - 2017 Remaster → Heroes", listOf(
            Rule("remaster-t", "title", "matches", REMASTER, ""),
            Rule("remaster-a", "album", "matches", REMASTER, ""),
        )),
        Preset("explicit", "Take \"Explicit\" and \"Clean\" off", "Song (Explicit) → Song", listOf(
            Rule("explicit-t", "title", "matches", EXPLICIT, ""),
            Rule("explicit-a", "album", "matches", EXPLICIT, ""),
        )),
        Preset("feat", "Take \"feat. …\" off song titles", "Song (feat. Someone) → Song", listOf(
            Rule("feat-t", "title", "matches", """\s*[(\[](feat\.?|ft\.?|featuring)\s[^)\]]*[)\]]|\s+(feat\.|ft\.)\s.*$""", ""),
        )),
        Preset("single", "Take \"- Single\" and \"- EP\" off albums", "Heroes - Single → Heroes", listOf(
            Rule("single-a", "album", "matches", """\s+-\s+(single|ep)\s*$""", ""),
        )),
        Preset("topic", "Take \"- Topic\" off YouTube artists", "Artist - Topic → Artist", listOf(
            Rule("topic-r", "artist", "matches", """\s+-\s+topic\s*$""", ""),
        )),
    )

    /** One name through one rule; a rule that would leave nothing leaves the name as it was. */
    fun applyTo(v: String, r: Rule): String {
        if (r.find.isEmpty() || v.isEmpty()) return v
        val out = when (r.how) {
            "is" -> if (v.trim().equals(r.find.trim(), ignoreCase = true)) r.to else v
            "contains" -> v.replace(r.find, r.to, ignoreCase = true)
            else -> try {
                Regex(r.find, RegexOption.IGNORE_CASE).replace(v, r.to)
            } catch (_: Exception) {
                v // A pattern that isn't one changes nothing.
            }
        }.replace(SPACES, " ").trim()
        return out.ifEmpty { v }
    }

    private val SPACES = Regex("\\s{2,}")

    /** [s] with every rule in [rules] applied in order, each rule's artist checked against the name as it came. */
    fun apply(s: Scrobble, rules: List<Rule>): Scrobble {
        var out = s
        for (r in rules) {
            if (!r.on || (r.artist.isNotBlank() && !s.artist.trim().equals(r.artist.trim(), ignoreCase = true))) continue
            out = when (r.field) {
                "artist" -> out.copy(artist = applyTo(out.artist, r), albumArtist = applyTo(out.albumArtist, r))
                "album" -> out.copy(album = applyTo(out.album, r))
                else -> out.copy(track = applyTo(out.track, r))
            }
        }
        return out
    }

    /** The rules in force: the switched-on presets first, then the user's own. */
    fun active(presetsOn: Set<String>, own: List<Rule>): List<Rule> =
        PRESETS.filter { it.id in presetsOn }.flatMap { it.rules } + own.filter { it.on }

    fun parse(json: String): List<Rule> = try {
        JSONArray(json).let { a -> (0 until a.length()).map { Rule.fromJson(a.getJSONObject(it)) } }
    } catch (_: Exception) {
        emptyList()
    }

    fun toJson(rules: List<Rule>): String = JSONArray(rules.map { it.toJson() }).toString()
}
