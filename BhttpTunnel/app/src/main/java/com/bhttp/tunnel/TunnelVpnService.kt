package com.bhttp.tunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import com.jcraft.jsch.JSchException
import hev.htproxy.TProxyService
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

enum class VpnStatus(val label: String) {
    DISCONNECTED("Desconectado"),
    CONNECTING("Conectando..."),
    CONNECTED("Conectado"),
    RECONNECTING("Reconectando"),
    ERROR("Error")
}

object Bus {
    @Volatile var state: VpnStatus = VpnStatus.DISCONNECTED
    @Volatile var statusDetail: String = "Desconectado"
    @Volatile var isRunning = false
    @Volatile var listener: (() -> Unit)? = null

    private val _logs = mutableListOf<String>()
    val logs: List<String> get() = synchronized(_logs) { _logs.toList() }

    fun log(m: String) {
        synchronized(_logs) {
            _logs.add(m)
            if (_logs.size > 200) _logs.removeAt(0)
        }
        listener?.invoke()
    }

    fun setState(s: VpnStatus, detail: String = s.label) {
        state = s
        statusDetail = detail
        listener?.invoke()
    }

    fun clearLogs() {
        synchronized(_logs) { _logs.clear() }
        listener?.invoke()
    }
}

class TunnelVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    private var bridge: Bridge? = null
    private var ssh: SshSocks? = null
    private var tunnelNative = false

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val userStopped = AtomicBoolean(false)
    private var workerThread: Thread? = null
    private var reconnectCount = 0

    private val backoffDelays = longArrayOf(1000L, 2000L, 4000L, 8000L, 15000L, 30000L)
    @Volatile private var forceImmediateReconnect = false

    companion object {
        const val NOTIF_ID = 1001
        const val CHANNEL_ID = "bhttp_tunnel_channel"
        const val PREVENTIVE_RECONNECT_MS = (5 * 60 + 55) * 60 * 1000L // 5h 55min
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireLocks()
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            userStopped.set(true)
            stopAll()
            stopSelf()
            return START_NOT_STICKY
        }

        userStopped.set(false)
        reconnectCount = 0
        Bus.isRunning = true
        startForeground(NOTIF_ID, createNotification("Conectando..."))

        workerThread = Thread({ runTunnelLoop() }, "Tunnel-Main-Loop").apply {
            isDaemon = true
            start()
        }

        return START_NOT_STICKY
    }

    private fun runTunnelLoop() {
        val p = getSharedPreferences("cfg", MODE_PRIVATE)
        val mode = p.getString("mode", "BHTTP") ?: "BHTTP"
        val host = p.getString("host", "")?.trim() ?: ""
        val port = p.getString("port", "443")?.trim()?.toIntOrNull() ?: 443
        val user = p.getString("user", "")?.trim() ?: ""
        val pass = p.getString("pass", "") ?: ""
        val payload = p.getString("payload", "") ?: ""

        if (host.isEmpty() || user.isEmpty() || pass.isEmpty()) {
            Bus.setState(VpnStatus.ERROR, "Datos incompletos para conectar")
            stopAll()
            stopSelf()
            return
        }

        while (!userStopped.get()) {
            val isFirstConnect = (reconnectCount == 0)
            if (isFirstConnect) {
                Bus.setState(VpnStatus.CONNECTING, "Conectando...")
            } else {
                Bus.setState(VpnStatus.RECONNECTING, "Reconectando (intento $reconnectCount)")
            }

            try {
                // Paso a: Conexión TCP real y prueba previa al host
                Bus.log("Comprobando conectividad con $host:$port ($mode)…")
                testTcpReachability(host, port)

                // Iniciar puente de transporte
                Bus.log("Iniciando transporte $mode…")
                val b: Bridge = if (mode == "BHTTP") {
                    BhttpBridge(host, port)
                } else {
                    WsBridge(host, port, payload)
                }
                b.start()
                bridge = b

                // Paso b y c: Login SSH con verificación de banner y credenciales
                Bus.log("Autenticando SSH con usuario '$user'…")
                val s = SshSocks(user, pass, "127.0.0.1", b.localPort)
                s.start(1080)
                ssh = s
                Bus.log("SSH autenticado con éxito. Banner verificado.")

                // Crear interfaz TUN y tun2socks solo si no está ya activa (mantiene TUN en reconexión)
                if (tun == null) {
                    establishVpnTun()
                }

                // Estado REAL: Conectado
                Bus.setState(VpnStatus.CONNECTED, "Conectado a $host:$port")
                updateNotification("Conectado a $host:$port")
                Bus.log("VPN BHTTP V2 ACTIVA: todo el tráfico pasa por el túnel.")
                reconnectCount = 0

                val connectTime = System.currentTimeMillis()

                // Bucle de supervisión del túnel
                while (!userStopped.get()) {
                    Thread.sleep(3000)

                    // Verificación preventiva por límite de 6h del servidor
                    if (System.currentTimeMillis() - connectTime > PREVENTIVE_RECONNECT_MS) {
                        Bus.log("Reconexión preventiva de sesión programada (5h 55min cumplidos).")
                        break
                    }

                    // Verificar estado de la sesión SSH
                    if (ssh?.alive != true) {
                        Bus.log("Conexión del túnel SSH caída o timeout alcanzado.")
                        break
                    }
                }

            } catch (e: JSchException) {
                val msg = e.message ?: ""
                if (msg.contains("Auth fail", ignoreCase = true) || msg.contains("password", ignoreCase = true)) {
                    Bus.log("Error crítico: Usuario o contraseña SSH incorrectos.")
                    Bus.setState(VpnStatus.ERROR, "Usuario o contraseña incorrectos")
                    userStopped.set(true)
                    break
                } else {
                    Bus.log("Fallo en SSH: ${e.message}")
                }
            } catch (e: Exception) {
                Bus.log("Error: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                // Cierra únicamente el puente y la sesión SSH, preservando TUN para evitar fugas de IP
                try { ssh?.close() } catch (_: Exception) {}
                ssh = null
                try { bridge?.close() } catch (_: Exception) {}
                bridge = null
            }

            if (userStopped.get()) break

            // Backoff exponencial para reconexión
            reconnectCount++
            val delayIndex = minOf(reconnectCount - 1, backoffDelays.size - 1)
            val waitMs = backoffDelays[delayIndex]

            Bus.setState(VpnStatus.RECONNECTING, "Reconectando (intento $reconnectCount en ${waitMs / 1000}s)")
            updateNotification("Reconectando (intento $reconnectCount)…")

            forceImmediateReconnect = false
            var elapsed = 0L
            while (elapsed < waitMs && !userStopped.get() && !forceImmediateReconnect) {
                Thread.sleep(200)
                elapsed += 200
            }
        }

        stopAll()
        stopSelf()
    }

    private fun testTcpReachability(host: String, port: Int) {
        val s = Socket()
        try {
            s.connect(InetSocketAddress(host, port), 10000)
        } catch (e: IOException) {
            throw IOException("No se pudo conectar a $host:$port (${e.message ?: "Conexión rechazada o timeout"})")
        } finally {
            try { s.close() } catch (_: Exception) {}
        }
    }

    private fun establishVpnTun() {
        val p = getSharedPreferences("cfg", MODE_PRIVATE)
        val dnsServer = p.getString("dns", "198.18.0.2") ?: "198.18.0.2"

        val builder = Builder()
            .setSession("BHTTP Tunnel")
            .setMtu(1500)
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(dnsServer)

        try {
            builder.addDisallowedApplication(packageName)
        } catch (_: Exception) {}

        val fd = builder.establish() ?: throw IOException("El sistema no permitió establecer la interfaz VpnService.")
        tun = fd

        // Comprobación de tun2socks nativo
        if (!TProxyService.isLoaded()) {
            Bus.log("Error crítico: La librería nativa C hev-socks5-tunnel no está cargada.")
            throw IOException("Falta tun2socks nativo en este APK.")
        }

        val configFile = File(filesDir, "tun.yml")
        configFile.writeText(
            "tunnel:\n  mtu: 1500\n  ipv4: 10.0.0.2\nsocks5:\n  port: 1080\n  address: 127.0.0.1\n" +
            "mapdns:\n  address: $dnsServer\n  port: 53\n  network: 100.64.0.0\n  netmask: 255.192.0.0\n  cache-size: 10000\n" +
            "misc:\n  log-level: warn\n"
        )

        TProxyService.TProxyStartService(configFile.absolutePath, fd.fd)
        tunnelNative = true
        Bus.log("tun2socks iniciado (hev-socks5-tunnel vinculado a la interfaz TUN).")
    }

    private fun stopAll() {
        Bus.isRunning = false
        if (Bus.state != VpnStatus.ERROR) {
            Bus.setState(VpnStatus.DISCONNECTED, "Desconectado")
        }

        if (tunnelNative) {
            try { TProxyService.TProxyStopService() } catch (_: Throwable) {}
            tunnelNative = false
        }

        try { tun?.close() } catch (_: Exception) {}
        tun = null

        try { ssh?.close() } catch (_: Exception) {}
        try { ssh?.closeSocksServer() } catch (_: Exception) {}
        ssh = null

        try { bridge?.close() } catch (_: Exception) {}
        bridge = null

        releaseLocks()
        unregisterNetworkCallback()
        Bus.log("Túnel detenido y recursos liberados.")
    }

    private fun registerNetworkCallback() {
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Bus.log("Red disponible detectada.")
                if (Bus.state == VpnStatus.RECONNECTING) {
                    forceImmediateReconnect = true
                }
            }

            override fun onLost(network: Network) {
                Bus.log("Pérdida de red detectada (WiFi/Datos desconectados).")
            }
        }

        try {
            connectivityManager?.registerNetworkCallback(request, networkCallback!!)
        } catch (_: Exception) {}
    }

    private fun unregisterNetworkCallback() {
        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (_: Exception) {}
        networkCallback = null
    }

    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BhttpTunnel::WakeLock")?.apply {
            acquire(6 * 60 * 60 * 1000L)
        }

        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wifiLock = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "BhttpTunnel::WifiLock")?.apply {
            acquire()
        }
    }

    private fun releaseLocks() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (_: Exception) {}
        wifiLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "BHTTP Tunnel Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notificación de estado del túnel VPN"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun createNotification(statusText: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("BHTTP Tunnel")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(statusText: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(NOTIF_ID, createNotification(statusText))
    }

    override fun onRevoke() {
        userStopped.set(true)
        stopAll()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        userStopped.set(true)
        stopAll()
        super.onDestroy()
    }
}
