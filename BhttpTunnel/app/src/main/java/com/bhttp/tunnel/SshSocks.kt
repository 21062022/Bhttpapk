package com.bhttp.tunnel

import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Cliente SSH (JSch) con túnel por el puente local + Servidor SOCKS5 local (127.0.0.1:1080).
 */
class SshSocks(
    private val user: String,
    private val pass: String,
    private val host: String,
    private val port: Int
) : Closeable {

    private var session: Session? = null
    private var ss: ServerSocket? = null

    val alive: Boolean
        get() = session?.isConnected == true

    /**
     * Inicia la conexión SSH hacia el puente local.
     * Si la autenticación falla, lanza JSchException con "Auth fail" para detener reintentos inútiles.
     */
    fun start(socksPort: Int) {
        val jsch = JSch()
        val s = jsch.getSession(user, host, port)
        s.setPassword(pass)
        s.setConfig("StrictHostKeyChecking", "no")
        s.setConfig("PreferredAuthentications", "password,keyboard-interactive")
        s.serverAliveInterval = 10000 // 10 segundos
        s.serverAliveCountMax = 3     // 3 intentos = desconexión en ~30s
        s.timeout = 20000

        // Conecta al SSH remoto a través del puente
        s.connect(20000)
        session = s

        val serverBanner = s.serverVersion
        Bus.log("Banner SSH recibido: $serverBanner")
        if (!serverBanner.startsWith("SSH-2.0-")) {
            throw IOException("El servidor no es un servidor SSH válido (Banner: $serverBanner)")
        }

        // Inicia el servidor SOCKS5 local
        if (ss == null || ss?.isClosed == true) {
            ss = ServerSocket(socksPort, 128, InetAddress.getByName("127.0.0.1"))
            Thread({
                try {
                    while (!ss!!.isClosed) {
                        val client = ss!!.accept()
                        Thread({ handleSocksClient(client) }, "SOCKS-Worker").apply {
                            isDaemon = true
                            start()
                        }
                    }
                } catch (_: Exception) {}
            }, "SOCKS5-Server").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun handleSocksClient(client: Socket) {
        try {
            client.tcpNoDelay = true
            val inp = DataInputStream(client.getInputStream())
            val out = client.getOutputStream()

            // Handshake SOCKS5
            val ver = inp.readUnsignedByte()
            if (ver != 5) {
                client.close()
                return
            }

            val nmethods = inp.readUnsignedByte()
            inp.skipBytes(nmethods)
            out.write(byteArrayOf(5, 0)) // No authentication required
            out.flush()

            // Request details
            val ver2 = inp.readUnsignedByte()
            val cmd = inp.readUnsignedByte()
            inp.readUnsignedByte() // RSV

            if (ver2 != 5 || cmd != 1) { // 1 = CONNECT
                out.write(byteArrayOf(5, 7, 0, 1, 0, 0, 0, 0, 0, 0)) // Command not supported
                client.close()
                return
            }

            val dstAddrType = inp.readUnsignedByte()
            val dstHost: String = when (dstAddrType) {
                1 -> { // IPv4
                    val b = ByteArray(4)
                    inp.readFully(b)
                    InetAddress.getByAddress(b).hostAddress ?: ""
                }
                3 -> { // Domain name
                    val len = inp.readUnsignedByte()
                    val b = ByteArray(len)
                    inp.readFully(b)
                    String(b)
                }
                4 -> { // IPv6
                    val b = ByteArray(16)
                    inp.readFully(b)
                    InetAddress.getByAddress(b).hostAddress ?: ""
                }
                else -> {
                    client.close()
                    return
                }
            }

            val dstPort = (inp.readUnsignedByte() shl 8) or inp.readUnsignedByte()

            val currentSession = session
            if (currentSession == null || !currentSession.isConnected) {
                out.write(byteArrayOf(5, 1, 0, 1, 0, 0, 0, 0, 0, 0))
                client.close()
                return
            }

            // Abre canal direct-tcpip sobre la sesión SSH
            val channel = currentSession.openChannel("direct-tcpip") as ChannelDirectTCPIP
            channel.setHost(dstHost)
            channel.setPort(dstPort)

            val cin = channel.inputStream
            val cout = channel.outputStream

            try {
                channel.connect(15000)
            } catch (e: Exception) {
                out.write(byteArrayOf(5, 4, 0, 1, 0, 0, 0, 0, 0, 0)) // Host unreachable
                client.close()
                return
            }

            // Respuesta SOCKS5 éxito
            out.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            out.flush()

            // Bombear datos bidireccionalmente
            val t = Thread({
                pumpStream(client.getInputStream(), cout)
                try { channel.disconnect() } catch (_: Exception) {}
            }, "SOCKS-Pump-Up").apply { isDaemon = true; start() }

            pumpStream(cin, out)
            try { client.close() } catch (_: Exception) {}
            try { channel.disconnect() } catch (_: Exception) {}
            t.join(1000)

        } catch (_: Exception) {
            try { client.close() } catch (_: Exception) {}
        }
    }

    override fun close() {
        try { session?.disconnect() } catch (_: Exception) {}
        session = null
    }

    fun closeSocksServer() {
        try { ss?.close() } catch (_: Exception) {}
        ss = null
    }
}
