package app.recordshelf.net

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class HttpResponse(val code: Int, val body: String, private val headers: Map<String, List<String>>) {
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
}

/**
 * Minimal HTTP on top of HttpURLConnection. Returns every status (callers read the error
 * bodies of the APIs); only network failures throw (IOException).
 */
object Http {
    val USER_AGENT = "HeavyRotation/${app.recordshelf.BuildConfig.VERSION_NAME} (open-source Android app)"

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    fun form(params: Map<String, String>): String = params.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }

    fun get(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 20_000) =
        request("GET", url, null, headers, timeoutMs)

    fun post(url: String, params: Map<String, String>, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 20_000) =
        request("POST", url, form(params).toByteArray(), headers + ("Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"), timeoutMs)

    /** The raw bytes of a GET (an image), or null for an error status. */
    fun getBytes(url: String, timeoutMs: Int = 20_000): ByteArray? {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", USER_AGENT)
            if (c.responseCode !in 200..299) return null
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    /** A POST with a ready body, e.g. JSON. */
    fun postBody(url: String, body: String, contentType: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 20_000) =
        request("POST", url, body.toByteArray(Charsets.UTF_8), headers + ("Content-Type" to contentType), timeoutMs)

    private fun request(method: String, url: String, body: ByteArray?, headers: Map<String, String>, timeoutMs: Int): HttpResponse {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", USER_AGENT)
            c.setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            val h = c.headerFields.entries.filter { it.key != null }.associate { it.key!! to it.value }
            return HttpResponse(code, text, h)
        } finally {
            c.disconnect()
        }
    }
}
