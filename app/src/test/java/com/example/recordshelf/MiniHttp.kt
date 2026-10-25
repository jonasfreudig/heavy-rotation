package com.example.recordshelf

import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList

/** Just enough HTTP/1.1 to stand in for Discogs, Last.fm and MusicBrainz: one request per connection. */
class MiniHttp {
    class Req(val method: String, val path: String, val query: String, val headers: Map<String, String>, val body: String) {
        /** Query (GET) or form body (POST), decoded. */
        val params: Map<String, String> get() = parse(if (method == "POST") body else query)
    }

    class Res(val status: Int, val body: String, val headers: Map<String, String> = emptyMap())

    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val base get() = "http://127.0.0.1:${socket.localPort}"
    val requests = CopyOnWriteArrayList<Req>()

    @Volatile
    var handler: (Req) -> Res = { Res(404, "") }

    fun start(): MiniHttp {
        val t = Thread {
            while (!socket.isClosed) {
                val conn = try {
                    socket.accept()
                } catch (_: Exception) {
                    break
                }
                conn.use { serve(it) }
            }
        }
        t.isDaemon = true
        t.start()
        return this
    }

    private fun serve(conn: Socket) {
        val input = BufferedInputStream(conn.getInputStream())
        fun line(): String {
            val sb = StringBuilder()
            while (true) {
                val ch = input.read()
                if (ch < 0 || ch == 10) break
                if (ch != 13) sb.append(ch.toChar())
            }
            return sb.toString()
        }
        val start = line().split(" ")
        val method = start.getOrElse(0) { "GET" }
        val target = start.getOrElse(1) { "/" }
        val headers = HashMap<String, String>()
        while (true) {
            val h = line()
            if (h.isEmpty()) break
            val i = h.indexOf(':')
            if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
        }
        val len = headers["content-length"]?.toInt() ?: 0
        val body = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = input.read(body, read, len - read)
            if (n < 0) break
            read += n
        }
        val req = Req(method, target.substringBefore('?'), target.substringAfter('?', ""), headers, body.toString(Charsets.UTF_8))
        requests += req
        val r = handler(req)
        val bytes = r.body.toByteArray(Charsets.UTF_8)
        val head = StringBuilder("HTTP/1.1 ${r.status} X\r\nContent-Length: ${bytes.size}\r\nContent-Type: application/json; charset=utf-8\r\nConnection: close\r\n")
        r.headers.forEach { (k, v) -> head.append("$k: $v\r\n") }
        head.append("\r\n")
        val out = conn.getOutputStream()
        out.write(head.toString().toByteArray())
        out.write(bytes)
        out.flush()
    }

    fun stop() = socket.close()

    companion object {
        fun parse(s: String): Map<String, String> = s.split('&').filter { it.isNotEmpty() }.associate {
            val i = it.indexOf('=')
            URLDecoder.decode(it.substring(0, i), "UTF-8") to URLDecoder.decode(it.substring(i + 1), "UTF-8")
        }

        fun fixture(name: String): String =
            MiniHttp::class.java.classLoader!!.getResource("fixtures/$name")!!.readText(Charsets.UTF_8)
    }
}
