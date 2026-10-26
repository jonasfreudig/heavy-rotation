package com.example.recordshelf.history

import com.example.recordshelf.net.DzRelease
import com.example.recordshelf.util.Text
import org.json.JSONArray
import org.json.JSONObject

/**
 * An artist's releases for their page: albums, EPs, singles and compilations, editions of one
 * record folded together ("Romance" and "Romance (Deluxe Edition)", a single and its edit),
 * each with your plays and whether it's on your shelf.
 */
object Discography {
    class Entry(val release: DzRelease, val editions: List<DzRelease>)

    private val TYPES = listOf("album", "ep", "single", "compile")

    /** Folds editions together (the earliest is the record), albums first, then EPs, singles, compilations; newest first within each. */
    fun merge(releases: List<DzRelease>): List<Entry> {
        val groups = LinkedHashMap<String, MutableList<DzRelease>>()
        for (r in releases) groups.getOrPut(r.type + "|" + Text.normLoose(r.title)) { ArrayList() } += r
        return groups.values.map { eds -> eds.sortedBy { it.date.ifEmpty { "9999" } }.let { Entry(it.first(), it) } }
            .sortedWith(compareBy<Entry> { TYPES.indexOf(it.release.type).let { i -> if (i < 0) TYPES.size else i } }.thenByDescending { it.release.date })
    }

    /** [plays] and [owned] (a Discogs release id, 0 if not on the shelf) are asked per title; editions count for their record. */
    fun json(entries: List<Entry>, plays: (String) -> Int, owned: (String) -> Long): JSONArray = JSONArray(
        entries.map { e ->
            val r = e.release
            JSONObject().put("id", r.id).put("title", r.title).put("type", r.type).put("date", r.date).put("year", r.year)
                .put("cover", r.cover).put("link", r.link).put("editions", e.editions.size)
                .put("plays", e.editions.maxOf { plays(it.title) })
                .put("owned", e.editions.firstNotNullOfOrNull { owned(it.title).takeIf { id -> id > 0 } } ?: 0L)
        },
    )
}
