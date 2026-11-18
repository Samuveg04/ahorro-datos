package com.ahorro.vpn

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * El corazón de la app.
 *
 * Qué hace:
 * 1. Crea una interfaz TUN virtual con Builder().
 *    - address 10.8.0.2/24: IP de tu teléfono DENTRO del túnel.
 *    - addRoute 0.0.0.0/0: TODO el tráfico va al túnel, no solo web.
 *    - addDnsServer 10.8.0.1: DNS del servidor, que bloquea anuncios.
 *    - addDisallowedApplication: aquí puedes EXCLUIR apps si quieres,
 *      por ejemplo el banco. Si no pones nada, TODO pasa por el túnel.
 * 2. establish() devuelve un ParcelFileDescriptor: ahí entran y salen
 *    los paquetes IP de todas las apps.
 * 3. Un hilo lee paquetes del TUN y los manda al servidor por el
 *    túnel WireGuard. Otro hilo escribe las respuestas de vuelta al TUN.
 *
 * IMPORTANTE: este esqueleto usa la librería WireGuard para el
 * cifrado y el transporte. No intentes mandar los paquetes IP
 * crudos por un socket UDP simple: TCP se rompe, DNS se rompe,
 * y la app se cae. WireGuard (wg-go) ya resuelve eso.
 *
 * En TunnelManager.kt va la conexión real con WireGuard.
 * Aquí solo está la creación del TUN, que es lo que Android te pide.
 */
class AhorroVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.ahorro.vpn.START"
        const val ACTION_STOP = "com.ahorro.vpn.STOP"
    }

    private var tun: ParcelFileDescriptor? = null
    private var running = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTunnel()
            ACTION_STOP -> stopTunnel()
        }
        return START_STICKY
    }

    private fun startTunnel() {
        if (running) return
        // Crea el TUN. Esto es lo que hace que TODO el tráfico
        // del móvil pase por tu app.
        val builder = Builder()
            .addAddress("10.8.0.2", 24)
            .addRoute("0.0.0.0", 0) // <-- TODO, no solo web
            .addDnsServer("10.8.0.1") // DNS del servidor, con bloqueo
            .setMtu(1280)
            .setSession("Ahorro VPN Cuba")
            // .addDisallowedApplication("com.android.bank") // si quieres excluir algo

        tun = builder.establish()
        running = true

        // Aquí TunnelManager conecta WireGuard con el servidor:
        // TunnelManager.connect(
        //   tunFd = tun!!.fd,
        //   privateKey = "TU_CLAVE_PRIVADA_DEL_TELEFONO",
        //   serverPublicKey = "CLAVE_PUBLICA_DEL_SERVIDOR",
        //   endpoint = "IP_DEL_SERVIDOR:51820"
        // )
        //
        // WireGuard lee del TUN, cifra, manda por UDP al servidor,
        // recibe la respuesta, descifra y escribe de vuelta al TUN.
        // Android se encarga de entregar cada paquete a su app.

        // Aviso en primer plano para que Android no mate el servicio.
        // En producción usa startForeground con notificación.
    }

    private fun stopTunnel() {
        running = false
        try { tun?.close() } catch (e: Exception) {}
        tun = null
        // TunnelManager.disconnect()
        stopSelf()
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }
}
