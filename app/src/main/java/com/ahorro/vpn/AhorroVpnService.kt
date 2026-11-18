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
 * Corregido 18 nov 2026 — crash al desconectar:
 * - stopTunnel() era llamado 2 veces (desde ACTION_STOP y desde onDestroy),
 *   y stopForeground() sin haber arrancado el foreground tumba el proceso.
 *   Ahora hay un flag `stopped` y `foregroundStarted`, y todo va con try/catch.
 * - onDestroy() ya NO llama a TunnelManager.disconnect() si ya se paró.
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

    private var foregroundStarted = false
    private var stopped = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                stopped = false
                startTunnel()
            }
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
        try {
            createChannel()
            startForeground(NOTIF_ID, notification("Conectando..."))
            foregroundStarted = true
        } catch (e: Exception) {
            VpnLog.error("No se pudo arrancar el foreground", e)
        }
        VpnLog.info("Servicio iniciado. Buscando config WireGuard...")
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val wgConfig = prefs.getString(KEY_WG_CONFIG, null)
        if (wgConfig.isNullOrBlank()) {
            VpnLog.error("Sin config WireGuard. Guarda tu wg_config en Ajustes antes de conectar.")
            safeStopForeground()
            stopSelf()
            return
        }
        TunnelManager.connect(this, wgConfig) { ok, msg ->
            if (ok) {
                VpnLog.info("VPN conectada: $msg")
                try {
                    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    manager.notify(NOTIF_ID, notification("Protegido - Ahorrando"))
                } catch (_: Exception) {}
            } else {
                VpnLog.error("No se pudo conectar: $msg")
                safeStopForeground()
                stopSelf()
            }
        }
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

    private fun stopTunnel() {
        if (stopped) return
        stopped = true
        VpnLog.info("Desconectando VPN...")
        try {
            TunnelManager.disconnect()
        } catch (e: Exception) {
            VpnLog.error("Error al bajar el túnel", e)
        }
        safeStopForeground()
        try {
            stopSelf()
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        if (!stopped) {
            stopped = true
            try {
                TunnelManager.disconnect()
            } catch (_: Exception) {}
        }
        try {
            VpnLog.saveToFile(this)
        } catch (_: Exception) {}
        super.onDestroy()
    }
}
