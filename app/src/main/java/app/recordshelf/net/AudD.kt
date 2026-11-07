package app.recordshelf.net

import app.recordshelf.util.str
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** A song AudD found in a recording, [offsetSec] into it (where the recording began). */
data class Recognised(val artist: String, val title: String, val album: String, val offsetSec: Int)

/**
 * Song recognition by AudD (audd.io) for Identify. Without an account AudD answers a few lookups a day (its own
 * rule, which it may change); past that it says so ([LimitReached]). The recording goes to AudD and nowhere else.
 */
class AudD(private val token: String = "") {
    class LimitReached : IOException("AudD's free lookups for today are used up")

    /** The song in [audio] (a WAV file), or null when AudD knows none. */
    fun identify(audio: ByteArray): Recognised? {
        val boundary = "----hr" + UUID.randomUUID().toString().replace("-", "")
        val body = ByteArrayOutputStream()
        fun field(name: String, value: String) {
            body.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
        }
        if (token.isNotEmpty()) field("api_token", token)
        body.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"clip.wav\"\r\nContent-Type: audio/wav\r\n\r\n".toByteArray())
        body.write(audio)
        body.write("\r\n--$boundary--\r\n".toByteArray())
        val c = URL(URL_API).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.doOutput = true
            c.setRequestProperty("User-Agent", Http.USER_AGENT)
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            c.setFixedLengthStreamingMode(body.size())
            c.outputStream.use { body.writeTo(it) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream ?: c.inputStream).bufferedReader().use { it.readText() }
            if (code !in 200..299) throw IOException("AudD answered $code")
            return parse(text)
        } finally {
            c.disconnect()
        }
    }

    companion object {
        const val URL_API = "https://api.audd.io/"

        /** AudD's answer: the song, null for no match; its errors as exceptions (901: the free lookups are used up). */
        fun parse(text: String): Recognised? {
            val o = try {
                JSONObject(text)
            } catch (_: Exception) {
                throw IOException("AudD's answer can't be read")
            }
            if (o.optString("status") == "error") {
                val e = o.optJSONObject("error")
                if (e?.optInt("error_code") == 901) throw LimitReached()
                throw IOException(e?.optString("error_message")?.ifEmpty { null } ?: "AudD couldn't look it up")
            }
            val r = when (val v = o.opt("result")) {
                is JSONObject -> v
                is JSONArray -> v.optJSONObject(0)
                else -> null
            } ?: return null
            val artist = r.str("artist").trim()
            val title = r.str("title").trim()
            if (artist.isEmpty() || title.isEmpty()) return null
            return Recognised(artist, title, r.str("album").trim(), Text2.seconds(r.str("timecode")))
        }
    }
}

private object Text2 {
    /** "01:23" -> 83; anything else -> 0. */
    fun seconds(s: String): Int {
        val p = s.trim().split(':').map { it.toIntOrNull() ?: return 0 }
        return when (p.size) {
            2 -> p[0] * 60 + p[1]
            3 -> p[0] * 3600 + p[1] * 60 + p[2]
            else -> 0
        }
    }
}
