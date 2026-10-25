package com.example.recordshelf.net

import com.example.recordshelf.util.Text
import org.json.JSONObject
import java.io.IOException

/** Artist photos from Deezer's public catalog, which needs no account. */
class DeezerClient(private val base: String = "https://api.deezer.com") {

    /** A square photo (500 px) of the artist with this name, the most followed one if several share it; null if none. */
    fun artistPhoto(name: String): String? {
        val r = Http.get("$base/search/artist?q=${Http.enc(name).replace("+", "%20")}&limit=10")
        if (r.code !in 200..299) throw IOException("Deezer answered ${r.code}")
        val o = JSONObject(r.body)
        // Deezer reports errors (like its request quota) with status 200.
        o.optJSONObject("error")?.let { throw IOException("Deezer: ${it.optString("message")}") }
        val data = o.optJSONArray("data") ?: return null
        return (0 until data.length()).map { data.getJSONObject(it) }
            .filter { Text.norm(it.optString("name")) == Text.norm(name) && hasPhoto(it.optString("picture_big")) }
            .maxByOrNull { it.optLong("nb_fan") }?.optString("picture_big")
    }

    /** Artists without a photo come with an empty image id: ".../images/artist//500x500-...". */
    private fun hasPhoto(url: String) = url.startsWith("https://") && !url.contains("/artist//")
}
