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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Modo local (sin servidor), pedido por Sam 18 nov 2026:
 * "Créame un config local para ver de qué es capaz el sistema".
 *
 * Es un VpnService que SOLO intercepta DNS:
 * - El TUN tiene 10.8.0.2/32 y el DNS es 10.8.0.1 (él mismo).
 * - Solo se añade ruta para 10.8.0.1/32: el resto del tráfico NO
 *   pasa por el túnel, va directo como siempre.
 * - Cada consulta DNS se compara con Blocklist (anuncios y
 *   rastreadores). Si está en la lista, responde NXDOMAIN y la
 *   cuenta; si no, la reenvía a Cloudflare 1.1.1.1 y devuelve
 *   la respuesta tal cual.
 * - NO transcodifica video ni cambia la IP: eso necesita servidor.
 *   Lo que logra: bloqueo de anuncios (15-25% del gasto diario)
 *   y que los reproductores con calidades bajen solos a 360p
 *   cuando el tráfico se limita (eso último lo hace el servidor).
 * - Si el teléfono tiene el DNS privado activado en Ajustes, las
 *   consultas no pasan por aquí y el bloqueo no se aplica.
 * - CONFIG LOCAL: la app le pasa un archivo de config local
 *   (formato [Local] con upstream= y block=). Si no hay config,
 *   usa la lista embebida en Blocklist y Cloudflare 1.1.1.1.
 *   Un solo botón en la app ejecuta la config que sea: si es
 *   WireGuard va por el túnel, si es local va por aquí.
 */
class LocalDnsVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.ahorro.vpn.LOCAL_START"
        const val ACTION_STOP = "com.ahorro.vpn.LOCAL_STOP"
        const val CHANNEL_ID = "ahorro_local_channel"
        const val NOTIF_ID = 2
        private const val DNS_IP = "10.8.0.1"
        private const val PREFS = "ahorro_vpn_prefs"
        private const val KEY_LOCAL_CONFIG = "local_config"

        @Volatile var blockedCount: Long = 0L
        @Volatile var forwardedCount: Long = 0L
        @Volatile var running: Boolean = false
    }

    // Config local (archivo [Local]): upstream DNS y dominios extra a bloquear.
    // Si no hay config, se usan los valores por defecto.
    private var upstreamIp = "1.1.1.1"

    private var iface: ParcelFileDescriptor? = null
    private var tunOut: FileOutputStream? = null
    private var readerThread: Thread? = null
    private var executor: ExecutorService? = null
    private var stopped = false
    private var foregroundStarted = false
    private val loggedBlocked = mutableSetOf<String>()
    private var packetId = 0
    private val writeLock = Any()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startLocal()
            ACTION_STOP -> stopLocal()
        }
        // NOT_STICKY: si el sistema lo mata, que no resucite solo.
        return START_NOT_STICKY
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL_ID, "Ahorro VPN local", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Modo local: bloqueo de anuncios"
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

    private fun startLocal() {
        if (running) return
        stopped = false
        // La config local (si la hay) trae upstream DNS y dominios extra.
        loadLocalConfig()
        try {
            createChannel()
            startForeground(NOTIF_ID, notification("Bloqueando anuncios..."))
            foregroundStarted = true
        } catch (e: Exception) {
            VpnLog.error("No se pudo arrancar el foreground local", e)
        }
        val builder = Builder()
        builder.setSession("Ahorro Local")
        builder.addAddress("10.8.0.2", 32)
        builder.addDnsServer(DNS_IP)
        builder.addRoute(DNS_IP, 32)
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
        VpnLog.info("Modo local activo: bloqueando anuncios y rastreadores (sin servidor).")
        VpnLog.info("Si tu DNS privado está activado en Ajustes, apágalo para que el bloqueo funcione.")
        readerThread = thread(name = "local-dns-reader", isDaemon = true) { readLoop(vpnInterface) }
    }

    /**
     * Lee la config local guardada por la app (archivo [Local]):
     *   upstream=1.1.1.1
     *   block=dominio1.com,dominio2.com
     * Sin config, quedan los valores por defecto.
     */
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

    private fun handlePacket(packet: ByteArray) {
        if (packet.size < 20) return
        val version = (packet[0].toInt() shr 4) and 0xF
        if (version != 4) return
        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (packet.size < ihl + 8) return
        val protocol = packet[9].toInt() and 0xFF
        if (protocol != 17) return // solo UDP
        val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)
        if (dstPort != 53) return
        val udpLen = ((packet[ihl + 4].toInt() and 0xFF) shl 8) or (packet[ihl + 5].toInt() and 0xFF)
        val dnsStart = ihl + 8
        val dnsLen = minOf(udpLen - 8, packet.size - dnsStart)
        if (dnsLen < 12) return
        val dns = packet.copyOfRange(dnsStart, dnsStart + dnsLen)
        val srcIp = packet.copyOfRange(12, 16)
        val srcPort = ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)
        val name = parseQName(dns)
        if (name == null) {
            // Consulta que no se pudo leer: se reenvía tal cual, sin bloquear.
            executor?.execute { forwardDns(dns, srcIp, srcPort) }
            return
        }
        if (Blocklist.isBlocked(name)) {
            blockedCount++
            // El log no se llena con cada bloqueo: solo la primera vez por dominio.
            if (loggedBlocked.size < 50 && loggedBlocked.add(name)) {
                VpnLog.info("Bloqueado: $name")
            }
            val resp = buildNxDomain(dns)
            writePacket(srcIp, srcPort, resp)
        } else {
            executor?.execute { forwardDns(dns, srcIp, srcPort) }
        }
    }

    /** Lee el primer QNAME de la consulta DNS. Null si no se puede leer. */
    private fun parseQName(dns: ByteArray): String? {
        if (dns.size < 12) return null
        val qdCount = ((dns[4].toInt() and 0xFF) shl 8) or (dns[5].toInt() and 0xFF)
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

    /** Respuesta NXDOMAIN (no existe) con el mismo ID y la misma pregunta. */
    private fun buildNxDomain(query: ByteArray): ByteArray {
        val resp = query.copyOf()
        var flags = ((resp[2].toInt() and 0xFF) shl 8) or (resp[3].toInt() and 0xFF)
        flags = flags or 0x8000 // QR = es una respuesta
        flags = flags or 0x0080 // RA = recursión disponible
        flags = (flags and 0xFFF0) or 0x0003 // RCODE 3 = NXDOMAIN
        resp[2] = (flags shr 8).toByte()
        resp[3] = (flags and 0xFF).toByte()
        resp[6] = 0
        resp[7] = 0 // ANCOUNT
        resp[8] = 0
        resp[9] = 0 // NSCOUNT
        resp[10] = 0
        resp[11] = 0 // ARCOUNT
        return resp
    }

    private fun forwardDns(dns: ByteArray, srcIp: ByteArray, srcPort: Int) {
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
            writePacket(srcIp, srcPort, resp)
        } catch (_: Exception) {
            // Si Cloudflare no responde, no se devuelve nada: el cliente reintenta.
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    /** Envuelve la respuesta DNS en IPv4+UDP (origen 10.8.0.1:53) y la escribe en el TUN. */
    private fun writePacket(dstIp: ByteArray, dstPort: Int, dns: ByteArray) {
        val totalLen = 20 + 8 + dns.size
        val packet = ByteArray(totalLen)
        packet[0] = 0x45
        packet[1] = 0
        packet[2] = (totalLen shr 8).toByte()
        packet[3] = (totalLen and 0xFF).toByte()
        val id = (++packetId) and 0xFFFF
        packet[4] = (id shr 8).toByte()
        packet[5] = (id and 0xFF).toByte()
        packet[6] = 0x40 // Don't Fragment
        packet[7] = 0
        packet[8] = 64 // TTL
        packet[9] = 17 // UDP
        packet[10] = 0
        packet[11] = 0 // checksum, se calcula abajo
        packet[12] = 10
        packet[13] = 8
        packet[14] = 0
        packet[15] = 1 // origen 10.8.0.1
        dstIp.copyInto(packet, 16)
        val csum = ipChecksum(packet, 0, 20)
        packet[10] = (csum shr 8).toByte()
        packet[11] = (csum and 0xFF).toByte()
        packet[20] = 0
        packet[21] = 53 // puerto origen 53
        packet[22] = (dstPort shr 8).toByte()
        packet[23] = (dstPort and 0xFF).toByte()
        val udpLen = 8 + dns.size
        packet[24] = (udpLen shr 8).toByte()
        packet[25] = (udpLen and 0xFF).toByte()
        packet[26] = 0
        packet[27] = 0 // checksum UDP 0 = sin checksum (válido en IPv4)
        dns.copyInto(packet, 28)
        val out = tunOut ?: return
        synchronized(writeLock) {
            try {
                out.write(packet)
                out.flush()
            } catch (_: Exception) {
            }
        }
    }

    private fun ipChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        while (i < offset + length - 1) {
            val word = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            sum += word
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

    private fun safeStopForeground() {
        if (!foregroundStarted) return
        try {
            stopForeground(true)
        } catch (_: Exception) {
        } finally {
            foregroundStarted = false
        }
    }

    private fun stopLocal() {
        if (stopped && !running) return
        stopped = true
        running = false
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
