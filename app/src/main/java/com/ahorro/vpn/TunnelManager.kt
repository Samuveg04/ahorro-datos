package com.ahorro.vpn

import android.content.Context
import android.net.TrafficStats
import android.os.Process
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.ByteArrayInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * TunnelManager — conecta el túnel con el servidor WireGuard.
 *
 * Corregido 17 nov 2026 23:01 (lo pidió Sam):
 * - Tráfico de subida y bajada: getSessionStats() devuelve los bytes
 *   de la sesión (bajada, subida). Primero intenta las estadísticas
 *   del propio túnel WireGuard (backend.getStatistics); si no están,
 *   cae a TrafficStats del UID de la app. La base se guarda al conectar.
 * - disconnect() totalmente protegido: nunca tira el proceso.
 */
class WireGuardTunnel(private val name: String) : Tunnel {
    private var state: Tunnel.State = Tunnel.State.DOWN
    override fun getName(): String = name
    override fun onStateChange(newState: Tunnel.State) {
        state = newState
        VpnLog.info("Túnel $name -> $newState")
    }
    fun getState(): Tunnel.State = state
}

object TunnelManager {
    private var backend: Backend? = null
    private var tunnel: WireGuardTunnel? = null
    private var lastConfig: Config? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    // Base para el tráfico de la sesión (se fija al conectar).
    private var baseRx: Long = 0L
    private var baseTx: Long = 0L

    fun buildConfig(privateKey: String, serverPublicKey: String, endpoint: String): String {
        val sb = StringBuilder()
        sb.append("[Interface]\n")
        sb.append("PrivateKey = ").append(privateKey).append("\n")
        sb.append("Address = 10.8.0.2/24\n")
        sb.append("DNS = 10.8.0.1\n")
        sb.append("MTU = 1280\n")
        sb.append("[Peer]\n")
        sb.append("PublicKey = ").append(serverPublicKey).append("\n")
        sb.append("Endpoint = ").append(endpoint).append("\n")
        sb.append("AllowedIPs = 0.0.0.0/0, ::/0\n")
        sb.append("PersistentKeepalive = 25\n")
        return sb.toString()
    }

    fun connect(context: Context, wgQuickConfig: String, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        scope.launch {
            try {
                VpnLog.info("Conectando túnel WireGuard...")
                val ctx = context.applicationContext
                val b = backend ?: GoBackend(ctx).also { backend = it }
                val t = tunnel ?: WireGuardTunnel("AhorroVPN").also { tunnel = it }
                val config = Config.parse(ByteArrayInputStream(wgQuickConfig.toByteArray(Charsets.UTF_8)))
                lastConfig = config
                b.setState(t, Tunnel.State.UP, config)
                val (rx, tx) = readTotals()
                baseRx = rx
                baseTx = tx
                VpnLog.info("Túnel levantado. Estado: ${t.getState()}")
                onResult(true, "Conectado")
            } catch (e: Exception) {
                VpnLog.error("Fallo al conectar el túnel", e)
                onResult(false, e.message ?: "Error desconocido")
            }
        }
    }

    /** Totales acumulados: primero del túnel, si no del UID de la app. */
    private fun readTotals(): Pair<Long, Long> {
        return try {
            val b = backend
            val t = tunnel
            if (b != null && t != null) {
                val s = b.getStatistics(t)
                Pair(s.totalRx, s.totalTx)
            } else {
                Pair(TrafficStats.getUidRxBytes(Process.myUid()), TrafficStats.getUidTxBytes(Process.myUid()))
            }
        } catch (_: Exception) {
            Pair(TrafficStats.getUidRxBytes(Process.myUid()), TrafficStats.getUidTxBytes(Process.myUid()))
        }
    }

    /** (bajada, subida) de esta sesión, en bytes. (0, 0) si no hay túnel. */
    fun getSessionStats(): Pair<Long, Long> {
        return try {
            if (tunnel?.getState() != Tunnel.State.UP) return Pair(0L, 0L)
            val (rx, tx) = readTotals()
            Pair((rx - baseRx).coerceAtLeast(0L), (tx - baseTx).coerceAtLeast(0L))
        } catch (_: Exception) {
            Pair(0L, 0L)
        }
    }

    fun disconnect() {
        try {
            val b = backend
            val t = tunnel
            if (b == null || t == null) {
                VpnLog.info("No hay túnel que bajar.")
                return
            }
            val up = try { t.getState() == Tunnel.State.UP } catch (_: Exception) { false }
            if (!up) {
                VpnLog.info("Túnel ya estaba bajado.")
                return
            }
            scope.launch {
                runCatching {
                    VpnLog.info("Bajando túnel WireGuard...")
                    b.setState(t, Tunnel.State.DOWN, lastConfig)
                    VpnLog.info("Túnel bajado.")
                }.onFailure { e ->
                    VpnLog.error("Fallo al bajar el túnel", e as? Exception ?: Exception(e.message))
                }
            }
        } catch (e: Exception) {
            VpnLog.error("Fallo al bajar el túnel", e)
        }
    }

    fun getState(): Tunnel.State = try { tunnel?.getState() ?: Tunnel.State.DOWN } catch (_: Exception) { Tunnel.State.DOWN }
}
