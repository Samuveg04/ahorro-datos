package com.ahorro.vpn

import android.content.Context
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
 * Corregido 18 nov 2026 — crash al desconectar:
 * - disconnect() llamaba a setState(DOWN, lastConfig) con lastConfig null
 *   o con un backend que nunca se levantó. Ahora todo va con guardas
 *   y runCatching: si no hay nada que bajar, simplemente no hace nada.
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
                VpnLog.info("Túnel levantado. Estado: ${t.getState()}")
                onResult(true, "Conectado")
            } catch (e: Exception) {
                VpnLog.error("Fallo al conectar el túnel", e)
                onResult(false, e.message ?: "Error desconocido")
            }
        }
    }

    fun disconnect() {
        val b = backend
        val t = tunnel
        if (b == null || t == null) {
            VpnLog.info("No hay túnel que bajar.")
            return
        }
        if (t.getState() == Tunnel.State.DOWN) {
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
    }

    fun getState(): Tunnel.State = tunnel?.getState() ?: Tunnel.State.DOWN
}
