package com.example.mediaplayer

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * A minimal HTTP/1.1 server (no libraries): one request per connection, enough
 * for the web control API and panel on the local network. [handler] runs on a
 * worker thread.
 */
class WebServer(private val port: Int, private val handler: (Request) -> Response) {

    class Request(
        val method: String,
        /** Decoded path segments: "/api/albums/abc/songs" -> ["api", "albums", "abc", "songs"]. */
        val segments: List<String>,
        val query: Map<String, String>,
        /** Header names lower-cased. */
        val headers: Map<String, String>,
        val body: String,
    )

    class Response(
        val status: Int,
        val contentType: String,
        val body: ByteArray = ByteArray(0),
        /** Streamed instead of [body] when set (downloads); closed after sending. */
        val stream: InputStream? = null,
        val length: Long = -1,
        val headers: Map<String, String> = emptyMap(),
    ) {
        companion object {
            fun json(text: String, status: Int = 200) =
                Response(status, "application/json; charset=utf-8", text.toByteArray())

            fun text(text: String, status: Int) = Response(status, "text/plain; charset=utf-8", text.toByteArray())
        }
    }

    private var socket: ServerSocket? = null
    private var pool: ExecutorService? = null

    /** Throws if the port can't be opened (e.g. already in use). */
    fun start() {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(port))
        socket = s
        val workers = Executors.newFixedThreadPool(6)
        pool = workers
        Thread({
            while (!s.isClosed) {
                val client = try {
                    s.accept()
                } catch (e: SocketException) {
                    break // closed by stop()
                }
                workers.execute { serve(client) }
            }
        }, "web-control-accept").apply { isDaemon = true }.start()
    }

    fun stop() {
        runCatching { socket?.close() }
        pool?.shutdownNow()
        socket = null
        pool = null
    }

    private fun serve(client: Socket) {
        client.use { sock ->
            runCatching {
                sock.soTimeout = 15_000
                serveOne(BufferedInputStream(sock.getInputStream()), sock.getOutputStream(), handler, keepAlive = false)
            }
        }
    }

    companion object {
        /** A JSON string literal. */
        fun quote(s: String): String = org.json.JSONObject.quote(s)

        /**
         * Serves requests from a stream until it ends (Bluetooth: one long-lived link, many requests).
         * Every reply carries Content-Length; one without (an unknown-length download) ends the link.
         */
        fun serveStream(input: InputStream, output: OutputStream, handler: (Request) -> Response) {
            val buffered = BufferedInputStream(input)
            while (serveOne(buffered, output, handler, keepAlive = true)) Unit
        }

        /** Reads one request and writes its reply. False when the stream ended or must be closed. */
        private fun serveOne(input: InputStream, output: OutputStream, handler: (Request) -> Response, keepAlive: Boolean): Boolean {
            val request = readRequest(input) ?: return false
            val response = runCatching { handler(request) }.getOrElse { e ->
                Response.json("""{"error":${quote(e.message ?: e.javaClass.simpleName)}}""", 500)
            }
            val keep = keepAlive && (response.stream == null || response.length >= 0)
            write(output, response, keep)
            return keep
        }

    private fun readRequest(input: InputStream): Request? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull()?.coerceIn(0, 64 * 1024) ?: 0
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        val target = parts[1]
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate {
            decode(it.substringBefore('=')) to decode(it.substringAfter('=', ""))
        }
        val segments = path.split('/').filter { it.isNotEmpty() }.map { decode(it) }
        return Request(parts[0].uppercase(), segments, query, headers, String(body, 0, read))
    }

    /** Reads a CRLF (or LF) terminated line, ASCII. Null at end of stream. */
    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString("ISO-8859-1")
            if (b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
            if (out.size() > 16 * 1024) return null
        }
        return out.toString("ISO-8859-1")
    }

    private fun write(out: OutputStream, r: Response, keepAlive: Boolean) {
        val length = if (r.stream != null) r.length else r.body.size.toLong()
        val head = StringBuilder()
            .append("HTTP/1.1 ").append(r.status).append(' ').append(reason(r.status)).append("\r\n")
            .append("Content-Type: ").append(r.contentType).append("\r\n")
            .append(if (keepAlive) "Connection: keep-alive\r\n" else "Connection: close\r\n")
            .append("Cache-Control: no-store\r\n")
        if (length >= 0) head.append("Content-Length: ").append(length).append("\r\n")
        for ((k, v) in r.headers) head.append(k).append(": ").append(v).append("\r\n")
        head.append("\r\n")
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        if (r.stream != null) {
            r.stream.use { it.copyTo(out, 64 * 1024) }
        } else {
            out.write(r.body)
        }
        out.flush()
    }

    private fun decode(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    private fun reason(code: Int) = when (code) {
        200 -> "OK"
        204 -> "No Content"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        else -> if (code >= 500) "Server Error" else "Status"
    }

    }
}
