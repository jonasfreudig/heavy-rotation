package app.recordshelf.scrobble

import app.recordshelf.util.Text
import app.recordshelf.util.objects
import app.recordshelf.util.str
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** A scrobble reduced to what the comparison needs. [ts] is Unix seconds. */
data class Listen(val artist: String, val title: String, val ts: Long, val album: String = "") {
    /** Worked out once: comparisons ask for it over and over. */
    val key = Text.norm(artist) + "\u0000" + Text.norm(title)

    fun toJson(): JSONObject = JSONObject().put("artist", artist).put("title", title).put("ts", ts).put("album", album)

    companion object {
        fun fromJson(o: JSONObject) = Listen(o.str("artist"), o.str("title"), o.optLong("ts"), o.str("album"))
    }
}

/**
 * Shadow mode against what really reached Last.fm (from Pano Scrobbler): [matched] listens
 * both saw, [renamed] pairs logged at the same time under different names, and what only
 * one side has.
 */
data class CompareResult(
    val matched: Int,
    val renamed: List<Pair<Listen, Listen>>,
    val onlyTheirs: List<Listen>,
    val onlyOurs: List<Listen>,
) {
    val theirs get() = matched + renamed.size + onlyTheirs.size
    val ours get() = matched + renamed.size + onlyOurs.size

    fun toJson(): JSONObject = JSONObject().put("matched", matched)
        .put("renamed", JSONArray().also { a -> renamed.forEach { (o, t) -> a.put(JSONObject().put("ours", o.toJson()).put("theirs", t.toJson())) } })
        .put("onlyTheirs", JSONArray().also { a -> onlyTheirs.forEach { a.put(it.toJson()) } })
        .put("onlyOurs", JSONArray().also { a -> onlyOurs.forEach { a.put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject) = CompareResult(
            o.optInt("matched"),
            o.optJSONArray("renamed")?.objects().orEmpty().map {
                Listen.fromJson(it.optJSONObject("ours") ?: JSONObject()) to Listen.fromJson(it.optJSONObject("theirs") ?: JSONObject())
            },
            o.optJSONArray("onlyTheirs")?.objects().orEmpty().map { Listen.fromJson(it) },
            o.optJSONArray("onlyOurs")?.objects().orEmpty().map { Listen.fromJson(it) },
        )
    }
}

object Compare {
    /**
     * Pairs each of [ours] with one of [theirs]: first the same artist and title within
     * [sameSec] seconds (closest first), then anything logged within [renameSec] seconds,
     * which means the two scrobblers named the track differently.
     */
    fun run(ours: List<Listen>, theirs: List<Listen>, sameSec: Long = 300, renameSec: Long = 90): CompareResult {
        val o = ours.sortedBy { it.ts }
        val t = theirs.sortedBy { it.ts }
        val usedO = BooleanArray(o.size)
        val usedT = BooleanArray(t.size)
        var matched = 0
        fun closest(x: Listen, window: Long, sameName: Boolean): Int {
            var best = -1
            var bestD = Long.MAX_VALUE
            for (j in t.indices) {
                if (usedT[j]) continue
                if (sameName && t[j].key != x.key) continue
                val d = abs(t[j].ts - x.ts)
                if (d <= window && d < bestD) {
                    best = j
                    bestD = d
                }
            }
            return best
        }
        for (i in o.indices) {
            val j = closest(o[i], sameSec, sameName = true)
            if (j >= 0) {
                usedO[i] = true
                usedT[j] = true
                matched++
            }
        }
        val renamed = ArrayList<Pair<Listen, Listen>>()
        for (i in o.indices) {
            if (usedO[i]) continue
            val j = closest(o[i], renameSec, sameName = false)
            if (j >= 0) {
                usedO[i] = true
                usedT[j] = true
                renamed += o[i] to t[j]
            }
        }
        return CompareResult(matched, renamed, t.filterIndexed { j, _ -> !usedT[j] }, o.filterIndexed { i, _ -> !usedO[i] })
    }
}
