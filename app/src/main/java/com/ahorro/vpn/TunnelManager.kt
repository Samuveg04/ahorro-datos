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
 *
 * ANTES era un esqueleto vacío: connect() no hacía nada, así que la app
 * mostraba "Conectado" pero ningún paquete salía al servidor.
 *
 * AHORA usa la librería oficial (GoBackend):
 *   - Config.parse() lee la config wg-quick (la misma que genera crear-cliente.sh).
 *   - backend.setState(tunnel, UP, config) levanta el túnel. GoBackend usa
 *     su propio VpnService (declarado en AndroidManifest) para crear el TUN.
 *   - AllowedIPs 0.0.0.0/0, ::/0 = TODO el tráfico pasa por el túnel.
 *   - Todo queda en VpnLog, para verlo en la pantalla de la app.
 *
 * Las claves NO van pegadas en el código: llegan como texto wg-quick
 * desde el servidor / SharedPreferences. Si no hay config, no se conecta
 * y se registra el error en el log, en vez de dejar el teléfono sin internet.
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
        scope.launch {
            try {
                val b = backend
                val t = tunnel
                if (b != null && t != null) {
                    VpnLog.info("Bajando túnel WireGuard...")
                    b.setState(t, Tunnel.State.DOWN, lastConfig)
                    VpnLog.info("Túnel bajado.")
                }
            } catch (e: Exception) {
                VpnLog.error("Fallo al bajar el túnel", e)
            }
        }
    }

    fun getState(): Tunnel.State = tunnel?.getState() ?: Tunnel.State.DOWN
}
