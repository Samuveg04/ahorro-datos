package com.ahorro.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Servicio en primer plano que maneja el túnel.
 *
 * ANTES era un VpnService que creaba un TUN a mano con Builder().establish()
 * y nunca llamaba a startForeground() ni a WireGuard: en Android 8+ lo
 * mataba el sistema en segundos, y aunque no lo matara, el tráfico no
 * salía a ningún lado porque el TUN no estaba conectado al servidor.
 *
 * AHORA:
 * - Es un Service normal en primer plano (notificación obligatoria).
 * - No crea el TUN a mano: lo crea GoBackend (su VpnService está en el Manifest).
 * - Lee la config wg-quick de SharedPreferences ("wg_config"). Si no hay
 *   config, NO finge estar conectado: lo registra en VpnLog y se detiene.
 * - Todo queda en VpnLog.
 */
class AhorroVpnService : Service() {

    companion object {
        const val ACTION_START = "com.ahorro.vpn.START"
        const val ACTION_STOP = "com.ahorro.vpn.STOP"
        const val CHANNEL_ID = "ahorro_vpn_channel"
        const val NOTIF_ID = 1
        const val PREFS = "ahorro_vpn_prefs"
        const val KEY_WG_CONFIG = "wg_config"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTunnel()
            ACTION_STOP -> stopTunnel()
        }
        return START_STICKY
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL_ID, "Ahorro VPN", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Túnel VPN activo"
        manager.createNotificationChannel(channel)
    }

    private fun notification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Ahorro VPN")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()
    }

    private fun startTunnel() {
        createChannel()
        startForeground(NOTIF_ID, notification("Conectando..."))
        VpnLog.info("Servicio iniciado. Buscando config WireGuard...")
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val wgConfig = prefs.getString(KEY_WG_CONFIG, null)
        if (wgConfig.isNullOrBlank()) {
            VpnLog.error("Sin config WireGuard. Guarda tu wg_config en Ajustes antes de conectar.")
            stopForeground(true)
            stopSelf()
            return
        }
        TunnelManager.connect(this, wgConfig) { ok, msg ->
            if (ok) {
                VpnLog.info("VPN conectada: $msg")
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIF_ID, notification("Protegido - Ahorrando"))
            } else {
                VpnLog.error("No se pudo conectar: $msg")
                stopForeground(true)
                stopSelf()
            }
        }
    }

    private fun stopTunnel() {
        VpnLog.info("Desconectando VPN...")
        TunnelManager.disconnect()
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        TunnelManager.disconnect()
        VpnLog.saveToFile(this)
        super.onDestroy()
    }
}
