package com.ahorro.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Modo local PRO (sin servidor), pedido por Sam 18 nov 2026:
 * "que el tráfico entero, sea local o wireguard, pase x la app,
 * obligatoriamente... lo de frenar los videos, sea en app o en
 * navegador como hace el shaping, dándome ahorro real en vivo
 * sin servidor".
 *
 * A diferencia del LocalDnsVpnService viejo (que solo interceptaba
 * DNS), este servicio es un PROXY LOCAL completo:
 * - El TUN lleva ruta por defecto (0.0.0.0/0 y ::/0): TODO el
 *   tráfico del teléfono pasa por la app, obligatoriamente.
 * - TCP: pila de usuario mínima (SYN/SYN-ACK, datos, ACK, FIN,
 *   RST) con un Socket real por conexión hacia el destino.
 * - UDP: reenvío por sesión (así pasa QUIC, que usan YouTube,
 *   Instagram y TikTok para el vídeo).
 * - DNS: igual que antes, con Blocklist; además se guarda qué IP
 *   corresponde a cada dominio, para saber qué conexiones son vídeo.
 * - FRENO DE VÍDEO: las conexiones a dominios de vídeo se limitan
 *   a 0,5 Mbps (igual que el shaping del servidor). El reproductor
 *   (app o navegador) mide el ancho de banda y baja solo a una
 *   calidad más ligera. Eso es el ahorro real en vivo sin servidor.
 *
 * LÍMITE HONESTO: el freno solo baja la CALIDAD que elige el
 * reproductor (de 720p/1080p a 360p o menos). No transcodifica:
 * un vídeo que ya se ve a 360p pesa casi lo mismo, porque 360p
 * ya va a ~0,5 Mbps. El ahorro grande está en Instagram, TikTok
 * y Facebook, que ponen el vídeo en alta calidad solos.
 * - En modo WireGuard el freno lo sigue haciendo el SERVIDOR
 *   (setup-completo.sh): en el teléfono no se puede frenar el
 *   tráfico de WireGuard porque el túnel lo maneja su propio
 *   backend, no la app. El tráfico igual pasa por la app (el túnel).
 * - IPv6: se añade la ruta y los paquetes se descartan, así las
 *   apps caen a IPv4 y nada se escapa del proxy.
 */
class LocalProxyVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.ahorro.vpn.PROXY_START"
        const val ACTION_STOP = "com.ahorro.vpn.PROXY_STOP"
        const val CHANNEL_ID = "ahorro_local_channel"
        const val NOTIF_ID = 2
        private const val DNS_IP = "10.8.0.1"
        private const val PREFS = "ahorro_vpn_prefs"
        private const val KEY_LOCAL_CONFIG = "local_config"

        // Freno de vídeo: 0,5 Mbps = 62.500 B/s = 6.250 B cada 100 ms.
        private const val VIDEO_RATE_BYTES_PER_SEC = 62_500L
        private const val VIDEO_WINDOW_MS = 100L
        private const val VIDEO_BUDGET_PER_WINDOW = (VIDEO_RATE_BYTES_PER_SEC * VIDEO_WINDOW_MS / 1000).toInt()
        private const val MSS = 1400

        @Volatile var blockedCount: Long = 0L
        @Volatile var forwardedCount: Long = 0L
        @Volatile var running: Boolean = false

        private val rxBytes = AtomicLong(0L)
        private val txBytes = AtomicLong(0L)
        private val throttledBytes = AtomicLong(0L)

        fun getSessionStats(): Pair<Long, Long> = Pair(rxBytes.get(), txBytes.get())
        fun getThrottledBytes(): Long = throttledBytes.get()

        /** Dominios de vídeo, los mismos que frena el servidor. */
        private val VIDEO_SUFFIXES = setOf(
            "googlevideo.com", "youtube.com", "ytimg.com",
            "fbcdn.net", "instagram.com", "cdninstagram.com",
            "tiktokcdn.com", "tiktokv.com",
            "pinimg.com", "facebook.com",
            "streamwish.to", "voe.sx", "filemoon.sx", "mixdrop.co",
            "doodstream.com", "dood.to", "streamtape.com", "ok.ru"
        )

        fun isVideoDomain(host: String): Boolean {
            val h = host.lowercase().trim().trimEnd('.')
            for (d in VIDEO_SUFFIXES) if (h == d || h.endsWith(".$d")) return true
            return false
        }
    }

    private var upstreamIp = "1.1.1.1"
    private var iface: ParcelFileDescriptor? = null
    private var tunOut: FileOutputStream? = null
    private var readerThread: Thread? = null
    private var sweeperThread: Thread? = null
    private var executor: ExecutorService? = null
    private var stopped = false
    private var foregroundStarted = false
    private val loggedBlocked = mutableSetOf<String>()
    private val loggedThrottled = mutableSetOf<String>()
    private var packetId = 0
    private val writeLock = Any()

    // IP -> dominio (de las respuestas DNS que damos nosotros).
    private val ipToDomain = ConcurrentHashMap<String, String>()
    private val tcpConns = ConcurrentHashMap<String, TcpConn>()
    private val udpSessions = ConcurrentHashMap<String, UdpSession>()

    private class Pacer {
        var windowStart = 0L
        var windowBytes = 0
        fun pace(bytes: Int) {
            val now = System.currentTimeMillis()
            synchronized(this) {
                if (now - windowStart >= VIDEO_WINDOW_MS) {
                    windowStart = now
                    windowBytes = 0
                }
                if (windowBytes + bytes > VIDEO_BUDGET_PER_WINDOW) {
                    val wait = VIDEO_WINDOW_MS - (now - windowStart)
                    if (wait > 0) {
                        try { Thread.sleep(wait) } catch (_: Exception) {}
                    }
                    windowStart = System.currentTimeMillis()
                    windowBytes = 0
                }
                windowBytes += bytes
            }
        }
    }

    private class TcpConn(
        val key: String,
        val srcIp: ByteArray,
        val dstIp: ByteArray,
        val srcPort: Int,
        val dstPort: Int,
        val throttled: Boolean,
        val domain: String?
    ) {
        var clientSeq: Long = 0L
        var mySeq: Long = 0L
        var socket: Socket? = null
        var socketOut: java.io.OutputStream? = null
        var socketReady = false
        var closed = false
        var finSent = false
        var clientFin = false
        var lastActivity = System.currentTimeMillis()
        val pacer = Pacer()
        val pending = ArrayDeque<ByteArray>()
        var pendingBytes = 0
        fun touch() { lastActivity = System.currentTimeMillis() }
    }

    private class UdpSession(
        val key: String,
        val srcIp: ByteArray,
        val dstIp: ByteArray,
        val srcPort: Int,
        val dstPort: Int,
        val throttled: Boolean
    ) {
        var socket: DatagramSocket? = null
        var closed = false
        var lastActivity = System.currentTimeMillis()
        val pacer = Pacer()
        fun touch() { lastActivity = System.currentTimeMillis() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startProxy()
            ACTION_STOP -> stopProxy()
        }
        return START_NOT_STICKY
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL_ID, "Ahorro VPN local", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Modo local: todo el tráfico pasa por la app"
        manager.createNotificationChannel(channel)
    }

    private fun notification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Ahorro VPN — Modo local")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()
    }

    private fun startProxy() {
        if (running) return
        stopped = false
        loadLocalConfig()
        try {
            createChannel()
            startForeground(NOTIF_ID, notification("Todo el tráfico pasa por la app..."))
            foregroundStarted = true
        } catch (e: Exception) {
            VpnLog.error("No se pudo arrancar el foreground local", e)
        }
        val builder = Builder()
        builder.setSession("Ahorro Local")
        builder.addAddress("10.8.0.2", 32)
        builder.addDnsServer(DNS_IP)
        // Ruta por defecto: TODO el tráfico pasa por el TUN de la app.
        builder.addRoute("0.0.0.0", 0)
        // IPv6: ruta añadida pero paquetes descartados -> las apps caen a IPv4.
        try { builder.addRoute("::", 0) } catch (_: Exception) {}
        val vpnInterface = try {
            builder.establish()
        } catch (e: Exception) {
            VpnLog.error("No se pudo crear el TUN local", e)
            safeStopForeground()
            stopSelf()
            return
        }
        if (vpnInterface == null) {
            VpnLog.error("El sistema no creó el TUN local.")
            safeStopForeground()
            stopSelf()
            return
        }
        iface = vpnInterface
        tunOut = FileOutputStream(vpnInterface.fileDescriptor)
        executor = Executors.newCachedThreadPool()
        running = true
        VpnLog.info("Modo local PRO activo: todo el tráfico pasa por la app.")
        VpnLog.info("Freno de vídeo a 0,5 Mbps en apps y navegador (sin servidor).")
        VpnLog.info("Si tu DNS privado está activado en Ajustes, apágalo para que el bloqueo funcione.")
        VpnLog.info("En Chrome: Ajustes - Privacidad - Usar DNS seguro - Desactivado, si no Chrome se salta el bloqueo.")
        readerThread = thread(name = "local-proxy-reader", isDaemon = true) { readLoop(vpnInterface) }
        sweeperThread = thread(name = "local-proxy-sweeper", isDaemon = true) { sweepLoop() }
    }

    private fun loadLocalConfig() {
        upstreamIp = "1.1.1.1"
        val extra = mutableSetOf<String>()
        try {
            val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val text = prefs.getString(KEY_LOCAL_CONFIG, "")?.trim().orEmpty()
            for (raw in text.lines()) {
                val line = raw.trim()
                if (line.startsWith("upstream=")) {
                    val value = line.removePrefix("upstream=").trim()
                    if (value.isNotBlank()) upstreamIp = value
                } else if (line.startsWith("block=")) {
                    line.removePrefix("block=").split(",")
                        .map { it.trim().lowercase().trimStart('.') }
                        .filter { it.isNotBlank() && it.contains('.') }
                        .forEach { extra.add(it) }
                }
            }
        } catch (_: Exception) {
        }
        Blocklist.setExtra(extra)
        VpnLog.info("Config local: DNS $upstreamIp, ${extra.size} dominios extra bloqueados.")
    }

    // ---------- Bucle principal ----------

    private fun readLoop(vpnInterface: ParcelFileDescriptor) {
        val input = FileInputStream(vpnInterface.fileDescriptor)
        val buffer = ByteArray(32767)
        try {
            while (!stopped) {
                val len = try {
                    input.read(buffer)
                } catch (_: Exception) {
                    break
                }
                if (len <= 0) continue
                txBytes.addAndGet(len.toLong())
                val packet = buffer.copyOf(len)
                try {
                    handlePacket(packet)
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        } finally {
            try { input.close() } catch (_: Exception) {}
        }
    }

    private fun sweepLoop() {
        try {
            while (!stopped) {
                Thread.sleep(30_000)
                val now = System.currentTimeMillis()
                for ((_, c) in tcpConns) {
                    if (now - c.lastActivity > 120_000) closeConn(c, sendRst = false)
                }
                for ((_, s) in udpSessions) {
                    if (now - s.lastActivity > 60_000) closeUdp(s)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun handlePacket(packet: ByteArray) {
        if (packet.size < 20) return
        val version = (packet[0].toInt() shr 4) and 0xF
        if (version != 4) return // IPv6 se descarta: las apps caen a IPv4
        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (packet.size < ihl + 8) return
        when (packet[9].toInt() and 0xFF) {
            6 -> handleTcp(packet, ihl)
            17 -> handleUdp(packet, ihl)
            else -> { /* ICMP y demás se descartan */ }
        }
    }

    // ---------- Utilidades de bytes ----------

    private fun u16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun u32(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) shl 24) or
            ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or
            (b[off + 3].toLong() and 0xFF)

    private fun ipString(ip: ByteArray): String =
        "${ip[0].toInt() and 0xFF}.${ip[1].toInt() and 0xFF}.${ip[2].toInt() and 0xFF}.${ip[3].toInt() and 0xFF}"

    private fun put16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v shr 8).toByte()
        b[off + 1] = (v and 0xFF).toByte()
    }

    private fun put32(b: ByteArray, off: Int, v: Long) {
        b[off] = (v shr 24).toByte()
        b[off + 1] = (v shr 16).toByte()
        b[off + 2] = (v shr 8).toByte()
        b[off + 3] = v.toByte()
    }

    private fun ipChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        while (i < offset + length - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            if (sum > 0xFFFF) sum = (sum and 0xFFFF) + 1
            i += 2
        }
        if (i < offset + length) {
            sum += (data[i].toInt() and 0xFF) shl 8
            if (sum > 0xFFFF) sum = (sum and 0xFFFF) + 1
        }
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }

    private fun writeToTun(packet: ByteArray) {
        val out = tunOut ?: return
        rxBytes.addAndGet(packet.size.toLong())
        synchronized(writeLock) {
            try {
                out.write(packet)
                out.flush()
            } catch (_: Exception) {
            }
        }
    }

    // ---------- TCP ----------

    private fun handleTcp(packet: ByteArray, ihl: Int) {
        if (packet.size < ihl + 20) return
        val srcIp = packet.copyOfRange(12, 16)
        val dstIp = packet.copyOfRange(16, 20)
        val srcPort = u16(packet, ihl)
        val dstPort = u16(packet, ihl + 2)
        val seq = u32(packet, ihl + 4)
        val ack = u32(packet, ihl + 8)
        val dataOff = ((packet[ihl + 12].toInt() and 0xFF) shr 4) * 4
        val flags = packet[ihl + 13].toInt() and 0xFF
        val payloadStart = ihl + dataOff
        val payload = if (payloadStart < packet.size) packet.copyOfRange(payloadStart, packet.size) else ByteArray(0)
        val key = "$srcPort|${ipString(dstIp)}|$dstPort"
        val isSyn = flags and 0x02 != 0
        val isRst = flags and 0x04 != 0
        val isFin = flags and 0x01 != 0

        if (isRst) {
            tcpConns[key]?.let { closeConn(it, sendRst = false) }
            return
        }

        var conn = tcpConns[key]
        if (conn == null) {
            if (!isSyn) {
                // Paquete sin conexión (retransmisión tardía): RST para que el cliente cierre.
                sendRstRaw(srcIp, dstIp, srcPort, dstPort, ack, seq + 1)
                return
            }
            val dstStr = ipString(dstIp)
            val domain = ipToDomain[dstStr]
            val throttled = domain != null && isVideoDomain(domain)
            conn = TcpConn(key, srcIp, dstIp, srcPort, dstPort, throttled, domain)
            conn.clientSeq = seq + 1 // el SYN consume un número de secuencia
            val myIsn = (SecureRandom().nextInt().toLong() and 0x7FFFFFFF) + 1
            conn.mySeq = myIsn + 1
            tcpConns[key] = conn
            if (throttled && domain != null && loggedThrottled.size < 20 && loggedThrottled.add(domain)) {
                VpnLog.info("Freno de vídeo activo: $domain (0,5 Mbps).")
            }
            sendSynAck(conn)
            connectSocket(conn)
            return
        }

        if (conn.closed) return
        conn.touch()

        if (isSyn) {
            // SYN repetido: se reenvía el SYN-ACK.
            sendSynAck(conn)
            return
        }

        // Datos del cliente.
        if (payload.isNotEmpty()) {
            if (seq == conn.clientSeq) {
                conn.clientSeq += payload.size
                if (conn.socketReady) {
                    try {
                        conn.socketOut?.write(payload)
                        conn.socketOut?.flush()
                    } catch (_: Exception) {
                        closeConn(conn, sendRst = true)
                        return
                    }
                } else {
                    // El socket aún no conecta: se guarda y se envía al conectar.
                    synchronized(conn) {
                        if (conn.pendingBytes + payload.size <= 65536) {
                            conn.pending.addLast(payload)
                            conn.pendingBytes += payload.size
                        }
                    }
                }
                sendAck(conn)
            } else {
                // Retransmisión o duplicado: se reenvía el ACK y se descarta.
                sendAck(conn)
            }
        }

        // FIN del cliente: se confirma y se cierra la salida hacia el servidor;
        // nuestro FIN sale cuando el servidor cierre (lo manda el lector).
        if (isFin) {
            conn.clientSeq += 1
            conn.clientFin = true
            sendAck(conn)
            try { conn.socket?.shutdownOutput() } catch (_: Exception) {}
            if (!conn.socketReady) {
                // Si el socket ni siquiera conectó, no hay nada que esperar.
                closeConn(conn, sendRst = false)
            }
        }

        // ACK de nuestro FIN: la conexión terminó.
        if (conn.finSent && ack >= conn.mySeq) {
            closeConn(conn, sendRst = false)
        }
    }

    private fun connectSocket(conn: TcpConn) {
        executor?.execute {
            try {
                val socket = Socket()
                protect(socket)
                socket.connect(InetSocketAddress(ipString(conn.dstIp), conn.dstPort), 5000)
                socket.tcpNoDelay = true
                synchronized(conn) {
                    if (conn.closed) {
                        try { socket.close() } catch (_: Exception) {}
                        return@execute
                    }
                    conn.socket = socket
                    conn.socketOut = socket.getOutputStream()
                    conn.socketReady = true
                    // Vaciar lo que el cliente mandó antes de conectar.
                    while (conn.pending.isNotEmpty()) {
                        val data = conn.pending.removeFirst()
                        conn.pendingBytes -= data.size
                        try {
                            conn.socketOut?.write(data)
                        } catch (_: Exception) {
                            closeConn(conn, sendRst = true)
                            return@execute
                        }
                    }
                    try { conn.socketOut?.flush() } catch (_: Exception) {}
                    if (conn.clientFin) {
                        try { socket.shutdownOutput() } catch (_: Exception) {}
                    }
                }
                readFromSocket(conn, socket)
            } catch (_: Exception) {
                // No se pudo conectar al destino: RST al cliente.
                closeConn(conn, sendRst = true)
            }
        }
    }

    private fun readFromSocket(conn: TcpConn, socket: Socket) {
        val buf = ByteArray(MSS)
        try {
            val input = socket.getInputStream()
            while (!conn.closed) {
                val n = try {
                    input.read(buf)
                } catch (_: Exception) {
                    break
                }
                if (n <= 0) break
                val chunk = buf.copyOf(n)
                if (conn.throttled) {
                    conn.pacer.pace(chunk.size)
                    throttledBytes.addAndGet(chunk.size.toLong())
                }
                if (conn.closed) break
                sendTcpData(conn, chunk)
            }
        } catch (_: Exception) {
        }
        // El servidor cerró: nuestro FIN al cliente.
        synchronized(conn) {
            if (!conn.closed && !conn.finSent) {
                sendFin(conn)
                conn.finSent = true
            }
        }
    }

    private fun closeConn(conn: TcpConn, sendRst: Boolean) {
        if (conn.closed) return
        conn.closed = true
        tcpConns.remove(conn.key)
        if (sendRst) sendRstConn(conn)
        try { conn.socket?.close() } catch (_: Exception) {}
    }

    /** Construye un paquete IPv4+TCP y lo escribe en el TUN. */
    private fun buildTcpPacket(
        srcIp: ByteArray, dstIp: ByteArray,
        srcPort: Int, dstPort: Int,
        seq: Long, ack: Long, flags: Int,
        payload: ByteArray, withMss: Boolean
    ): ByteArray {
        val optLen = if (withMss) 4 else 0
        val tcpLen = 20 + optLen + payload.size
        val totalLen = 20 + tcpLen
        val packet = ByteArray(totalLen)
        packet[0] = 0x45
        packet[1] = 0
        put16(packet, 2, totalLen)
        val id = (++packetId) and 0xFFFF
        put16(packet, 4, id)
        packet[6] = 0x40
        packet[7] = 0
        packet[8] = 64
        packet[9] = 6 // TCP
        srcIp.copyInto(packet, 12)
        dstIp.copyInto(packet, 16)
        val csum = ipChecksum(packet, 0, 20)
        put16(packet, 10, csum)
        val t = 20
        put16(packet, t, srcPort)
        put16(packet, t + 2, dstPort)
        put32(packet, t + 4, seq)
        put32(packet, t + 8, ack)
        packet[t + 12] = (((20 + optLen) / 4) shl 4).toByte()
        packet[t + 13] = flags.toByte()
        put16(packet, t + 14, 65535) // ventana
        put16(packet, t + 16, 0) // checksum, se calcula abajo
        put16(packet, t + 18, 0) // urgente
        if (withMss) {
            packet[t + 20] = 0x02
            packet[t + 21] = 0x04
            put16(packet, t + 22, MSS)
        }
        payload.copyInto(packet, t + 20 + optLen)
        // Checksum TCP con pseudo-cabecera.
        val pseudo = ByteArray(12 + tcpLen)
        srcIp.copyInto(pseudo, 0)
        dstIp.copyInto(pseudo, 4)
        pseudo[8] = 0
        pseudo[9] = 6
        put16(pseudo, 10, tcpLen)
        packet.copyInto(pseudo, 12, t, t + tcpLen)
        put16(packet, t + 16, ipChecksum(pseudo, 0, pseudo.size))
        return packet
    }

    private fun sendSynAck(conn: TcpConn) {
        synchronized(conn) {
            val packet = buildTcpPacket(
                conn.dstIp, conn.srcIp, conn.dstPort, conn.srcPort,
                conn.mySeq - 1, conn.clientSeq, 0x12, ByteArray(0), true
            )
            writeToTun(packet)
        }
    }

    private fun sendAck(conn: TcpConn) {
        synchronized(conn) {
            if (conn.closed) return
            val packet = buildTcpPacket(
                conn.dstIp, conn.srcIp, conn.dstPort, conn.srcPort,
                conn.mySeq, conn.clientSeq, 0x10, ByteArray(0), false
            )
            writeToTun(packet)
        }
    }

    private fun sendFin(conn: TcpConn) {
        synchronized(conn) {
            if (conn.closed) return
            val packet = buildTcpPacket(
                conn.dstIp, conn.srcIp, conn.dstPort, conn.srcPort,
                conn.mySeq, conn.clientSeq, 0x11, ByteArray(0), false
            )
            conn.mySeq += 1
            writeToTun(packet)
        }
    }

    private fun sendTcpData(conn: TcpConn, chunk: ByteArray) {
        synchronized(conn) {
            if (conn.closed) return
            val packet = buildTcpPacket(
                conn.dstIp, conn.srcIp, conn.dstPort, conn.srcPort,
                conn.mySeq, conn.clientSeq, 0x18, chunk, false
            )
            conn.mySeq += chunk.size
            writeToTun(packet)
        }
    }

    private fun sendRstConn(conn: TcpConn) {
        synchronized(conn) {
            val packet = buildTcpPacket(
                conn.dstIp, conn.srcIp, conn.dstPort, conn.srcPort,
                conn.mySeq, conn.clientSeq, 0x14, ByteArray(0), false
            )
            writeToTun(packet)
        }
    }

    private fun sendRstRaw(srcIp: ByteArray, dstIp: ByteArray, srcPort: Int, dstPort: Int, seq: Long, ack: Long) {
        val packet = buildTcpPacket(dstIp, srcIp, dstPort, srcPort, seq, ack, 0x04, ByteArray(0), false)
        writeToTun(packet)
    }

    // ---------- UDP ----------

    private fun handleUdp(packet: ByteArray, ihl: Int) {
        if (packet.size < ihl + 8) return
        val srcIp = packet.copyOfRange(12, 16)
        val dstIp = packet.copyOfRange(16, 20)
        val srcPort = u16(packet, ihl)
        val dstPort = u16(packet, ihl + 2)
        val udpLen = u16(packet, ihl + 4)
        val payloadStart = ihl + 8
        val payloadLen = minOf(udpLen - 8, packet.size - payloadStart)
        if (payloadLen < 0) return
        val payload = packet.copyOfRange(payloadStart, payloadStart + payloadLen)

        if (dstPort == 53) {
            handleDns(payload, srcIp, srcPort)
            return
        }

        val key = "$srcPort|${ipString(dstIp)}|$dstPort"
        var session = udpSessions[key]
        if (session == null) {
            if (udpSessions.size >= 256) return // tope: no se aceptan más sesiones
            val dstStr = ipString(dstIp)
            val domain = ipToDomain[dstStr]
            val throttled = domain != null && isVideoDomain(domain)
            session = UdpSession(key, srcIp, dstIp, srcPort, dstPort, throttled)
            udpSessions[key] = session
            startUdpSession(session)
            if (throttled && domain != null && loggedThrottled.size < 20 && loggedThrottled.add(domain)) {
                VpnLog.info("Freno de vídeo activo: $domain (0,5 Mbps).")
            }
        }
        session.touch()
        val socket = session.socket ?: return
        try {
            socket.send(DatagramPacket(payload, payload.size, InetAddress.getByAddress(dstIp), dstPort))
        } catch (_: Exception) {
        }
    }

    private fun startUdpSession(session: UdpSession) {
        executor?.execute {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                protect(socket)
                socket.soTimeout = 5000
                session.socket = socket
                val buf = ByteArray(65507)
                while (!session.closed && !stopped) {
                    val recv = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(recv)
                    } catch (_: Exception) {
                        break
                    }
                    val data = recv.data.copyOf(recv.length)
                    session.touch()
                    if (session.throttled) {
                        session.pacer.pace(data.size)
                        throttledBytes.addAndGet(data.size.toLong())
                    }
                    if (session.closed) break
                    writeUdpPacket(session, data)
                }
            } catch (_: Exception) {
            } finally {
                closeUdp(session)
            }
        }
    }

    private fun closeUdp(session: UdpSession) {
        if (session.closed) return
        session.closed = true
        udpSessions.remove(session.key)
        try { session.socket?.close() } catch (_: Exception) {}
    }

    /** Envuelve datos UDP en IPv4+UDP (origen = el servidor remoto) y los escribe en el TUN. */
    private fun writeUdpPacket(session: UdpSession, data: ByteArray) {
        val totalLen = 20 + 8 + data.size
        val packet = ByteArray(totalLen)
        packet[0] = 0x45
        packet[1] = 0
        put16(packet, 2, totalLen)
        val id = (++packetId) and 0xFFFF
        put16(packet, 4, id)
        packet[6] = 0x40
        packet[7] = 0
        packet[8] = 64
        packet[9] = 17 // UDP
        session.dstIp.copyInto(packet, 12)
        session.srcIp.copyInto(packet, 16)
        put16(packet, 10, ipChecksum(packet, 0, 20))
        put16(packet, 20, session.dstPort)
        put16(packet, 22, session.srcPort)
        put16(packet, 24, 8 + data.size)
        put16(packet, 26, 0) // checksum UDP 0 = sin checksum (válido en IPv4)
        data.copyInto(packet, 28)
        writeToTun(packet)
    }

    // ---------- DNS ----------

    private fun handleDns(dns: ByteArray, srcIp: ByteArray, srcPort: Int) {
        if (dns.size < 12) return
        val name = parseQName(dns)
        if (name == null) {
            executor?.execute { forwardDns(dns, srcIp, srcPort, null) }
            return
        }
        if (Blocklist.isBlocked(name)) {
            blockedCount++
            if (loggedBlocked.size < 50 && loggedBlocked.add(name)) {
                VpnLog.info("Bloqueado: $name")
            }
            val resp = buildNxDomain(dns)
            writeDnsPacket(srcIp, srcPort, resp)
        } else {
            executor?.execute { forwardDns(dns, srcIp, srcPort, name) }
        }
    }

    private fun parseQName(dns: ByteArray): String? {
        if (dns.size < 12) return null
        val qdCount = u16(dns, 4)
        if (qdCount < 1) return null
        var pos = 12
        val labels = StringBuilder()
        while (pos < dns.size) {
            val len = dns[pos].toInt() and 0xFF
            if (len == 0) break
            if (len > 63 || pos + 1 + len > dns.size) return null
            if (labels.isNotEmpty()) labels.append('.')
            labels.append(String(dns, pos + 1, len, Charsets.US_ASCII))
            pos += 1 + len
        }
        if (labels.isEmpty()) return null
        return labels.toString().lowercase()
    }

    private fun buildNxDomain(query: ByteArray): ByteArray {
        val resp = query.copyOf()
        var flags = u16(resp, 2)
        flags = flags or 0x8000 // QR = es una respuesta
        flags = flags or 0x0080 // RA = recursión disponible
        flags = (flags and 0xFFF0) or 0x0003 // RCODE 3 = NXDOMAIN
        put16(resp, 2, flags)
        put16(resp, 6, 0) // ANCOUNT
        put16(resp, 8, 0) // NSCOUNT
        put16(resp, 10, 0) // ARCOUNT
        return resp
    }

    private fun forwardDns(dns: ByteArray, srcIp: ByteArray, srcPort: Int, name: String?) {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            protect(socket)
            socket.soTimeout = 3000
            val upstream = InetAddress.getByName(upstreamIp)
            socket.send(DatagramPacket(dns, dns.size, upstream, 53))
            val buf = ByteArray(4096)
            val respPacket = DatagramPacket(buf, buf.size)
            socket.receive(respPacket)
            val resp = respPacket.data.copyOf(respPacket.length)
            forwardedCount++
            if (name != null) mapDnsAnswers(resp, name)
            // Desenmascarar CNAME: si la respuesta es un alias (CNAME)
            // hacia un dominio bloqueado, se responde NXDOMAIN aunque
            // el nombre preguntado no esté en la lista.
            if (cnameBlocked(resp)) {
                blockedCount++
                if (loggedBlocked.size < 50 && name != null && loggedBlocked.add(name)) {
                    VpnLog.info("Bloqueado (CNAME): $name")
                }
                writeDnsPacket(srcIp, srcPort, buildNxDomain(dns))
            } else {
                writeDnsPacket(srcIp, srcPort, resp)
            }
        } catch (_: Exception) {
            // Si el DNS no responde, no se devuelve nada: el cliente reintenta.
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    /** Lee un nombre DNS en `pos` (con punteros de compresión).
     *  Devuelve el nombre y la posición después del campo. */
    private fun readName(msg: ByteArray, pos: Int): Pair<String, Int>? {
        val labels = StringBuilder()
        var p = pos
        var next = -1
        var jumps = 0
        try {
            while (p < msg.size) {
                val len = msg[p].toInt() and 0xFF
                if (len == 0) {
                    if (next < 0) next = p + 1
                    break
                }
                if ((len and 0xC0) == 0xC0) {
                    if (p + 1 >= msg.size) return null
                    if (next < 0) next = p + 2
                    p = ((len and 0x3F) shl 8) or (msg[p + 1].toInt() and 0xFF)
                    if (++jumps > 5) return null
                    continue
                }
                if (len > 63 || p + 1 + len > msg.size) return null
                if (labels.isNotEmpty()) labels.append('.')
                labels.append(String(msg, p + 1, len, Charsets.US_ASCII))
                p += 1 + len
            }
        } catch (_: Exception) {
            return null
        }
        if (labels.isEmpty() || next < 0) return null
        return Pair(labels.toString().lowercase(), next)
    }

    /** True si algún CNAME de la respuesta apunta a un dominio bloqueado. */
    private fun cnameBlocked(resp: ByteArray): Boolean {
        try {
            if (resp.size < 12) return false
            var pos = 12
            val qdCount = u16(resp, 4)
            repeat(qdCount) {
                val q = readName(resp, pos) ?: return false
                pos = q.second + 4 // QTYPE + QCLASS
                if (pos > resp.size) return false
            }
            val anCount = u16(resp, 6)
            repeat(anCount) {
                val n = readName(resp, pos) ?: return false
                pos = n.second
                if (pos + 10 > resp.size) return false
                val type = u16(resp, pos)
                pos += 8 // TYPE + CLASS + TTL
                val rdLen = u16(resp, pos)
                pos += 2
                if (pos + rdLen > resp.size) return false
                if (type == 5) {
                    val target = readName(resp, pos) ?: return false
                    if (Blocklist.isBlocked(target.first)) return true
                }
                pos += rdLen
            }
        } catch (_: Exception) {
        }
        return false
    }

    /** Guarda IP -> dominio de las respuestas A, para saber qué conexiones son vídeo. */
    private fun mapDnsAnswers(resp: ByteArray, name: String) {
        try {
            var pos = 12
            while (pos < resp.size && resp[pos].toInt() != 0) {
                val l = resp[pos].toInt() and 0xFF
                if (l > 63) return
                pos += 1 + l
            }
            pos += 1 + 4 // null + QTYPE/QCLASS
            val anCount = u16(resp, 6)
            repeat(anCount) {
                if (pos + 10 > resp.size) return
                if ((resp[pos].toInt() and 0xC0) == 0xC0) {
                    pos += 2
                } else {
                    while (pos < resp.size && resp[pos].toInt() != 0) {
                        val l = resp[pos].toInt() and 0xFF
                        if (l > 63) return
                        pos += 1 + l
                    }
                    pos += 1
                }
                if (pos + 10 > resp.size) return
                val type = u16(resp, pos)
                pos += 8 // TYPE + CLASS + TTL
                val rdLen = u16(resp, pos)
                pos += 2
                if (type == 1 && rdLen == 4 && pos + 4 <= resp.size) {
                    val ip = "${resp[pos].toInt() and 0xFF}.${resp[pos + 1].toInt() and 0xFF}.${resp[pos + 2].toInt() and 0xFF}.${resp[pos + 3].toInt() and 0xFF}"
                    if (ipToDomain.size > 5000) ipToDomain.clear()
                    ipToDomain[ip] = name
                }
                pos += rdLen
            }
        } catch (_: Exception) {
        }
    }

    /** Envuelve la respuesta DNS en IPv4+UDP (origen 10.8.0.1:53) y la escribe en el TUN. */
    private fun writeDnsPacket(dstIp: ByteArray, dstPort: Int, dns: ByteArray) {
        val totalLen = 20 + 8 + dns.size
        val packet = ByteArray(totalLen)
        packet[0] = 0x45
        packet[1] = 0
        put16(packet, 2, totalLen)
        val id = (++packetId) and 0xFFFF
        put16(packet, 4, id)
        packet[6] = 0x40
        packet[7] = 0
        packet[8] = 64
        packet[9] = 17 // UDP
        packet[12] = 10
        packet[13] = 8
        packet[14] = 0
        packet[15] = 1 // origen 10.8.0.1
        dstIp.copyInto(packet, 16)
        put16(packet, 10, ipChecksum(packet, 0, 20))
        put16(packet, 20, 53)
        put16(packet, 22, dstPort)
        put16(packet, 24, 8 + dns.size)
        put16(packet, 26, 0)
        dns.copyInto(packet, 28)
        writeToTun(packet)
    }

    // ---------- Parada ----------

    private fun safeStopForeground() {
        if (!foregroundStarted) return
        try {
            stopForeground(true)
        } catch (_: Exception) {
        } finally {
            foregroundStarted = false
        }
    }

    private fun stopProxy() {
        if (stopped && !running) return
        stopped = true
        running = false
        for ((_, c) in tcpConns) closeConn(c, sendRst = false)
        for ((_, s) in udpSessions) closeUdp(s)
        VpnLog.info("Modo local apagado. Anuncios bloqueados en la sesión: $blockedCount")
        try { tunOut?.close() } catch (_: Exception) {}
        tunOut = null
        try { iface?.close() } catch (_: Exception) {}
        iface = null
        try { executor?.shutdownNow() } catch (_: Exception) {}
        executor = null
        safeStopForeground()
        try { stopSelf() } catch (_: Exception) {}
    }

    override fun onDestroy() {
        stopped = true
        running = false
        for ((_, c) in tcpConns) closeConn(c, sendRst = false)
        for ((_, s) in udpSessions) closeUdp(s)
        try { tunOut?.close() } catch (_: Exception) {}
        tunOut = null
        try { iface?.close() } catch (_: Exception) {}
        iface = null
        try { executor?.shutdownNow() } catch (_: Exception) {}
        executor = null
        try { VpnLog.saveToFile(this) } catch (_: Exception) {}
        super.onDestroy()
    }
}
