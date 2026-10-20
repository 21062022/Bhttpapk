package com.bhttp.tunnel

import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom

fun pumpStream(i: InputStream, o: OutputStream) {
    try {
        val b = ByteArray(32768)
        while (true) {
            val n = i.read(b)
            if (n < 0) break
            o.write(b, 0, n)
            o.flush()
        }
    } catch (_: Exception) {}
}

/**
 * Puente local: acepta conexiones en 127.0.0.1 (la de SSH) y la reenvía al servidor remoto
 * utilizando el transporte elegido (BHTTP V2 o WebSocket).
 */
abstract class Bridge : Closeable {
    protected val server: ServerSocket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val localPort: Int get() = server.localPort

    @Volatile
    protected var closed = false
    protected var local: Socket? = null

    fun start() {
        Thread {
            try {
                val c = server.accept()
                local = c
                c.tcpNoDelay = true
                c.keepAlive = true
                run(c)
            } catch (e: Exception) {
                if (!closed) Bus.log("Puente: ${e.message}")
            } finally {
                close()
            }
        }.apply { isDaemon = true; name = "Bridge-Acceptor" }.start()
    }

    protected abstract fun run(c: Socket)

    override fun close() {
        closed = true
        try { server.close() } catch (_: Exception) {}
        try { local?.close() } catch (_: Exception) {}
    }
}

/**
 * Protocolo BHTTP V2 (BHP1):
 * - TCP plano sin TLS
 * - Tramas fijas de cabecera de 29 bytes: modo(1) + sesión(16) + seq(8 BE) + largo(4 BE)
 * - Ofuscación XOR con keystream SHA-256
 */
class BhttpBridge(private val host: String, private val port: Int) : Bridge() {
    private val sess = ByteArray(16).also { SecureRandom().nextBytes(it) }
    private val CHUNK_SIZE = 32768
    private val COUNT = 4

    private fun keystream(mode: Int, seq: Long, d: Int, n: Int): ByteArray {
        val prefix = ByteBuffer.allocate(26)
            .put(sess)
            .put(mode.toByte())
            .putLong(seq)
            .put(d.toByte())
            .array()
        val out = ByteArray(n)
        var off = 0
        var counter = 0
        val counterBuf = ByteBuffer.allocate(4)

        while (off < n) {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(prefix)
            counterBuf.clear()
            counterBuf.putInt(counter)
            md.update(counterBuf.array())
            val digest = md.digest()
            val take = minOf(32, n - off)
            System.arraycopy(digest, 0, out, off, take)
            off += take
            counter++
        }
        return out
    }

    private fun mask(data: ByteArray, mode: Int, seq: Long, d: Int): ByteArray {
        val ks = keystream(mode, seq, d, data.size)
        return ByteArray(data.size) { (data[it].toInt() xor ks[it].toInt()).toByte() }
    }

    private fun header(mode: Int, seq: Long, len: Int): ByteArray {
        return ByteBuffer.allocate(29)
            .put(mode.toByte())
            .put(sess)
            .putLong(seq)
            .putInt(len)
            .array()
    }

    private class BhttpConn(host: String, port: Int) {
        val socket = Socket(host, port).apply {
            tcpNoDelay = true
            keepAlive = true
            soTimeout = 12000
        }
        val out: OutputStream = socket.getOutputStream()
        val din = DataInputStream(socket.getInputStream())

        fun close() {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    override fun run(c: Socket) {
        val t1 = Thread({ upLoop(c) }, "BHTTP-Upstream").apply { isDaemon = true; start() }
        val t2 = Thread({ downLoop(c) }, "BHTTP-Downstream").apply { isDaemon = true; start() }
        t1.join()
        t2.join()
    }

    private fun upLoop(c: Socket) {
        var conn: BhttpConn? = null
        try {
            val inp = c.getInputStream()
            val buf = ByteArray(16384)
            var seq = 0L

            while (!closed) {
                val n = inp.read(buf)
                if (n < 0) break

                val frame = header(1, seq, n) + mask(buf.copyOf(n), 1, seq, 0)
                var sentOk = false

                for (attempt in 0 until 3) {
                    try {
                        if (conn == null) conn = BhttpConn(host, port)
                        conn.out.write(frame)
                        conn.out.flush()

                        // Servidor responde 5 bytes (tipo 0 + largo 0)
                        val respType = conn.din.readUnsignedByte()
                        val respLen = conn.din.readInt()
                        if (respLen > 0) conn.din.skipBytes(respLen)
                        sentOk = true
                        break
                    } catch (e: Exception) {
                        conn?.close()
                        conn = null
                        if (closed) break
                    }
                }
                if (!sentOk) break
                seq++
            }
        } catch (_: Exception) {}
        conn?.close()
        close()
    }

    private fun downLoop(c: Socket) {
        var conn: BhttpConn? = null
        try {
            val out = c.getOutputStream()
            var seq = 0L
            var sinceAck = 0
            var consecutiveFails = 0

            while (!closed) {
                try {
                    if (conn == null) conn = BhttpConn(host, port)

                    // Petición modo 3: payload de 6 bytes = chunk_size(4 BE, 32768) + 0x00 + count(1 BE, 4)
                    val req = ByteBuffer.allocate(6).putInt(CHUNK_SIZE).put(0).put(COUNT.toByte()).array()
                    conn.out.write(header(3, seq, 6) + mask(req, 3, seq, 0))
                    conn.out.flush()

                    // El servidor responde 'COUNT' tramas
                    for (i in 0 until COUNT) {
                        conn.din.readUnsignedByte() // tipo
                        val l = conn.din.readInt()
                        val body = ByteArray(l)
                        conn.din.readFully(body)

                        if (l >= 4) {
                            val dataLen = ByteBuffer.wrap(body, 0, 4).int
                            if (dataLen > 0 && dataLen <= l - 4) {
                                val maskedData = body.copyOfRange(4, 4 + dataLen)
                                val unmasked = mask(maskedData, 3, seq + i, 1)
                                out.write(unmasked)
                                out.flush()
                                sinceAck++
                            }
                        }
                    }

                    seq += COUNT
                    consecutiveFails = 0

                    // Modo 4 (ACK): cada ~8 respuestas con datos
                    if (sinceAck >= 8) {
                        conn.out.write(header(4, seq - 1, 0))
                        conn.out.flush()
                        conn.din.readUnsignedByte()
                        val l = conn.din.readInt()
                        if (l > 0) conn.din.skipBytes(l)
                        sinceAck = 0
                    }

                    // Pausa breve para evitar consumo excesivo en espera
                    Thread.sleep(100)

                } catch (e: Exception) {
                    conn?.close()
                    conn = null
                    consecutiveFails++
                    if (closed || consecutiveFails > 3) break
                    Thread.sleep(1000)
                }
            }
        } catch (_: Exception) {}
        conn?.close()
        close()
    }
}

/**
 * Modo WS (WebSocket / HTTP Custom):
 * Abre TCP al servidor, envía el payload reemplazando [host], [crlf], [lf].
 * Lee hasta \r\n\r\n y exige código "101" o "200".
 * Si no es 101/200, lanza error.
 */
class WsBridge(private val host: String, private val port: Int, private val payload: String) : Bridge() {
    override fun run(c: Socket) {
        val s = Socket(host, port).apply {
            tcpNoDelay = true
            keepAlive = true
            soTimeout = 15000
        }

        val formattedPayload = payload
            .replace("[host]", host)
            .replace("[crlf]", "\r\n")
            .replace("[lf]", "\n")
            .replace("\\r\\n", "\r\n")
            .replace("\\n", "\n")

        s.getOutputStream().apply {
            write(formattedPayload.toByteArray(Charsets.ISO_8859_1))
            flush()
        }

        val inp = s.getInputStream()
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val b = inp.read()
            if (b < 0) {
                s.close()
                throw IOException("Conexión cerrada por el servidor antes de recibir la cabecera HTTP.")
            }
            head.append(b.toChar())
            if (head.length > 16384) {
                s.close()
                throw IOException("Cabecera HTTP demasiado larga.")
            }
        }

        val firstLine = head.lineSequence().firstOrNull() ?: ""
        Bus.log("Servidor HTTP: $firstLine")

        if (!(firstLine.contains(" 101") || firstLine.contains(" 200"))) {
            s.close()
            throw IOException("El servidor respondió con código HTTP inválido: $firstLine")
        }

        s.soTimeout = 0 // Quitar timeout tras handshake exitoso

        val t1 = Thread({ pumpStream(c.getInputStream(), s.getOutputStream()); try { s.close() } catch (_: Exception) {} }, "WS-Pump-Up")
            .apply { isDaemon = true; start() }

        pumpStream(inp, c.getOutputStream())
        try { c.close() } catch (_: Exception) {}
        t1.join(2000)
    }
}
