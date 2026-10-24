package com.nshd.jarvis

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Minimal RFC 6455 WebSocket client (text/binary, fragmentation, ping/pong, close) with no dependencies.
 * wss:// uses the platform TLS stack with hostname verification. All callbacks arrive on the reader thread.
 */
class WsClient(private val url: String, private val headers: Map<String, String> = emptyMap(), private val listener: Listener) {
    interface Listener {
        fun onOpen()
        fun onMessage(text: String)
        fun onClosed(code: Int, reason: String)
        fun onError(t: Throwable)
    }

    @Volatile private var sock: Socket? = null
    @Volatile private var out: OutputStream? = null
    @Volatile private var closed = false
    @Volatile private var closeSent = false
    private val sendLock = Any()
    private val rnd = SecureRandom()

    fun connect() { Thread({ run() }, "ws-reader").apply { isDaemon = true; start() } }

    val isOpen get() = out != null && !closed

    private fun run() {
        try {
            val uri = URI(url)
            val secure = uri.scheme == "wss"
            val host = uri.host ?: throw IllegalArgumentException("bad url")
            val port = if (uri.port > 0) uri.port else if (secure) 443 else 80
            val raw = Socket()
            raw.connect(InetSocketAddress(host, port), 15000)
            raw.tcpNoDelay = true
            val s: Socket = if (secure) {
                val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, port, true) as SSLSocket
                ssl.startHandshake()
                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.session)) throw SSLPeerUnverifiedException("hostname mismatch")
                ssl
            } else raw
            sock = s
            val input = BufferedInputStream(s.getInputStream(), 16384)
            val os = s.getOutputStream()

            val keyBytes = ByteArray(16); rnd.nextBytes(keyBytes)
            val key = Base64.getEncoder().encodeToString(keyBytes)
            val path = (uri.rawPath.takeIf { !it.isNullOrEmpty() } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
            val sb = StringBuilder()
            sb.append("GET ").append(path).append(" HTTP/1.1\r\n")
            sb.append("Host: ").append(host).append(if (uri.port > 0) ":$port" else "").append("\r\n")
            sb.append("Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\n")
            sb.append("Sec-WebSocket-Key: ").append(key).append("\r\n")
            for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
            sb.append("\r\n")
            os.write(sb.toString().toByteArray(Charsets.ISO_8859_1)); os.flush()

            // read the HTTP response head, byte by byte so no frame bytes are consumed
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) {
                val b = input.read(); if (b < 0) throw EOFException("closed during handshake")
                head.append(b.toChar()); if (head.length > 16384) throw IllegalStateException("handshake too long")
            }
            val lines = head.toString().split("\r\n")
            if (!lines[0].contains(" 101")) throw IllegalStateException("Handshake failed: " + lines[0])
            val accept = lines.firstOrNull { it.startsWith("Sec-WebSocket-Accept:", ignoreCase = true) }?.substringAfter(':')?.trim()
            val expect = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.ISO_8859_1)))
            if (accept != expect) throw IllegalStateException("Bad Sec-WebSocket-Accept")
            out = os
            listener.onOpen()
            readLoop(input)
        } catch (t: Throwable) {
            if (!closed) { closed = true; listener.onError(t) }
        } finally { try { sock?.close() } catch (_: Exception) {} }
    }

    private fun readFully(i: BufferedInputStream, n: Int): ByteArray {
        val b = ByteArray(n); var off = 0
        while (off < n) { val r = i.read(b, off, n - off); if (r < 0) throw EOFException(); off += r }
        return b
    }

    private fun readLoop(i: BufferedInputStream) {
        var fragOp = 0
        var frag = ByteArrayOutputStream()
        while (!closed) {
            val b0 = i.read(); if (b0 < 0) break
            val b1 = i.read(); if (b1 < 0) break
            val fin = (b0 and 0x80) != 0
            val op = b0 and 0x0F
            val masked = (b1 and 0x80) != 0
            var len = (b1 and 0x7F).toLong()
            if (len == 126L) { val e = readFully(i, 2); len = (((e[0].toInt() and 0xFF) shl 8) or (e[1].toInt() and 0xFF)).toLong() }
            else if (len == 127L) { val e = readFully(i, 8); len = 0; for (k in 0 until 8) len = (len shl 8) or (e[k].toLong() and 0xFF) }
            if (len > 32L * 1024 * 1024) throw IllegalStateException("frame too large")
            val mask = if (masked) readFully(i, 4) else null
            val payload = readFully(i, len.toInt())
            if (mask != null) for (k in payload.indices) payload[k] = (payload[k].toInt() xor mask[k % 4].toInt()).toByte()
            when (op) {
                0 -> { frag.write(payload); if (fin) { listener.onMessage(frag.toByteArray().toString(Charsets.UTF_8)); frag = ByteArrayOutputStream(); fragOp = 0 } }
                1, 2 -> {
                    if (fin) listener.onMessage(payload.toString(Charsets.UTF_8))
                    else { fragOp = op; frag = ByteArrayOutputStream(); frag.write(payload) }
                }
                8 -> {
                    val code = if (payload.size >= 2) ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF) else 1005
                    val reason = if (payload.size > 2) String(payload, 2, payload.size - 2, Charsets.UTF_8) else ""
                    if (!closeSent) sendFrame(8, payload.copyOf(minOf(payload.size, 2)))
                    closed = true; listener.onClosed(code, reason); return
                }
                9 -> sendFrame(10, payload)
                else -> {}
            }
        }
        if (!closed) { closed = true; listener.onClosed(1006, "connection lost") }
    }

    private fun sendFrame(op: Int, payload: ByteArray) {
        synchronized(sendLock) {
            val os = out ?: return
            if (op == 8) closeSent = true
            val n = payload.size
            val header = ByteArrayOutputStream(14)
            header.write(0x80 or op)
            when {
                n < 126 -> header.write(0x80 or n)
                n < 65536 -> { header.write(0x80 or 126); header.write(n shr 8); header.write(n and 0xFF) }
                else -> { header.write(0x80 or 127); for (k in 7 downTo 0) header.write(((n.toLong() shr (8 * k)) and 0xFF).toInt()) }
            }
            val mask = ByteArray(4); rnd.nextBytes(mask)
            header.write(mask)
            val body = ByteArray(n) { (payload[it].toInt() xor mask[it % 4].toInt()).toByte() }
            try { os.write(header.toByteArray()); os.write(body); os.flush() } catch (t: Throwable) { if (!closed) { closed = true; listener.onError(t) } }
        }
    }

    fun sendText(s: String) { if (!closed) sendFrame(1, s.toByteArray(Charsets.UTF_8)) }

    fun close(code: Int = 1000) {
        if (closed) return
        val p = byteArrayOf((code shr 8).toByte(), (code and 0xFF).toByte())
        sendFrame(8, p)
        Thread({ try { Thread.sleep(400) } catch (_: Exception) {}; closed = true; try { sock?.close() } catch (_: Exception) {} }, "ws-close").apply { isDaemon = true; start() }
    }
}
