package com.cubevg.navmap.core

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * A small HTTP/1.1 server on 127.0.0.1 only: one request per connection, answered with one JSON value, then closed.
 * [answer] gets the method and the path with its query, e.g. "/nav?ah=1", on a worker thread, and returns the
 * status code and the body. If the port is taken it tries again every 10 s. Plain JVM, so it's tested without a
 * phone.
 */
class LoopbackServer(
    private val port: Int,
    private val answer: (method: String, target: String) -> Pair<Int, Any>,
    private val onStatus: (String) -> Unit = {},
) {
    private companion object {
        const val READ_TIMEOUT_MS = 5_000
        const val MAX_HEAD_CHARS = 8 * 1024
        const val RETRY_MS = 10_000L
        val REASONS = mapOf(
            200 to "OK",
            400 to "Bad Request",
            403 to "Forbidden",
            404 to "Not Found",
            500 to "Internal Server Error",
            503 to "Service Unavailable",
        )
    }

    // Two requests at a time and a few waiting; more are dropped. NavMap sends one at a time.
    private val workers = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(8))

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var stopped = false

    /** The port it listens on, or -1 while it doesn't. With port 0 the system picks one. */
    val localPort: Int get() = server?.takeIf { it.isBound && !it.isClosed }?.localPort ?: -1

    fun start() {
        thread(name = "LoopbackServer", isDaemon = true) { listen() }
    }

    fun stop() {
        stopped = true
        server?.close()
        workers.shutdown()
    }

    private fun listen() {
        val address = InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port)
        var lastError: String? = null
        while (!stopped) {
            try {
                ServerSocket().use { s ->
                    s.reuseAddress = true
                    s.bind(address)
                    server = s
                    lastError = null
                    onStatus("Listening on 127.0.0.1:${s.localPort}")
                    while (true) {
                        val socket = s.accept()
                        try {
                            workers.execute { serve(socket) }
                        } catch (e: RejectedExecutionException) {
                            socket.close()
                        }
                    }
                }
            } catch (e: IOException) {
                if (stopped) return
                // Most likely another app holds the port.
                val error = "Can't listen on port $port: ${e.message}"
                if (error != lastError) onStatus(error)
                lastError = error
            }
            Thread.sleep(RETRY_MS)
        }
    }

    private fun serve(socket: Socket) {
        try {
            socket.use {
                it.soTimeout = READ_TIMEOUT_MS
                val parts = requestLine(BufferedInputStream(it.getInputStream()))?.split(' ')
                val (code, body) = when {
                    parts == null || parts.size != 3 -> 400 to mapOf("t" to "bad")
                    else -> try {
                        answer(parts[0], local(parts[1]))
                    } catch (e: Exception) {
                        500 to mapOf("t" to "error")
                    }
                }
                val bytes = Json.write(body).toByteArray(Charsets.US_ASCII)
                val head = "HTTP/1.1 $code ${REASONS[code] ?: "Status"}\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Cache-Control: no-store\r\n" +
                    "Connection: close\r\n\r\n"
                val out = it.getOutputStream()
                out.write(head.toByteArray(Charsets.US_ASCII) + bytes)
                out.flush()
            }
        } catch (e: IOException) {
            // The other end went away or never finished its request.
        }
    }

    /**
     * The first line of the request, once the whole head is read: closing with request bytes unread would reset
     * the connection before the reply arrives. Null if the head is cut off or too long.
     */
    private fun requestLine(input: InputStream): String? {
        val head = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0 || head.length >= MAX_HEAD_CHARS) return null
            head.append(b.toChar())
            if (b == '\n'.code && (head.endsWith("\r\n\r\n") || head.endsWith("\n\n"))) break
        }
        return head.lineSequence().first()
    }

    /** A proxy-style absolute URL names the same path. */
    private fun local(target: String): String =
        if (target.startsWith("http://")) "/" + target.removePrefix("http://").substringAfter('/', "") else target
}
