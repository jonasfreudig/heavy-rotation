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
    val USER_AGENT = "HeavyRotation/${app.recordshelf.BuildConfig.VERSION_NAME} (+https://github.com/jonasfreudig/heavy-rotation)"

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** Spaces the calls to one service at least [gapMs] apart, across all threads (its rate limit). */
    class Pace(private val gapMs: Long) {
        private var next = 0L

        fun await() {
            val wait: Long
            synchronized(this) {
                val now = System.currentTimeMillis()
                val at = maxOf(now, next)
                next = at + gapMs
                wait = at - now
            }
            if (wait > 0) Thread.sleep(wait)
        }
    }

    /**
     * [call] again after a pause while the service says it gets too many requests ([busy]: a 429 by default),
     * up to [tries] times in all: as long as its Retry-After says (10 s at most), else 1, then 2 seconds. The
     * last answer is returned either way.
     */
    fun retryBusy(tries: Int = 3, busy: (HttpResponse) -> Boolean = { it.code == 429 }, sleep: (Long) -> Unit = { Thread.sleep(it) }, call: () -> HttpResponse): HttpResponse {
        var r = call()
        for (k in 1 until tries) {
            if (!busy(r)) return r
            val after = r.header("Retry-After")?.trim()?.toLongOrNull()?.times(1000)
            if (after != null && after > 10_000) return r
            sleep(after ?: (1000L shl (k - 1)))
            r = call()
        }
        return r
    }

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
