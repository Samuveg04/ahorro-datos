package com.ahorro.vpn

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/**
 * Pantalla moderna: fondo oscuro, botón grande central, 3 tarjetas + Registro.
 *
 * Corregido 18 nov 2026:
 * - startActivityForResult/onActivityResult (obsoleto) -> ActivityResultLauncher.
 * - startService() -> startForegroundService() (obligatorio en Android 12+).
 * - Las tarjetas txtUsed/txtSaved/txtTime ya no quedan en 0 para siempre.
 * - Nueva tarjeta "Registro": muestra el VpnLog en pantalla (lo pidió Sam).
 *   Toca el registro para copiarlo al portapapeles.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        // "App abierta" se registra una sola vez por proceso.
        // Antes se duplicaba porque onCreate se vuelve a llamar
        // al volver del diálogo de permiso VPN.
        private var openLogged = false
    }

    private var vpnOn = false
    private var serviceStarted = false
    private var startTimeMs: Long = 0L
    private val statsHandler = Handler(Looper.getMainLooper())
    private val statsRunnable = object : Runnable {
        override fun run() {
            if (vpnOn) {
                updateStatsUi()
                statsHandler.postDelayed(this, 1000)
            }
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
            else -> String.format("%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
        }
    }

    private fun updateStatsUi() {
        try {
            val (rx, tx) = TunnelManager.getSessionStats()
            findViewById<TextView>(R.id.txtUsed)?.text = formatBytes(rx)
            findViewById<TextView>(R.id.txtSaved)?.text = formatBytes(tx)
            val elapsed = (System.currentTimeMillis() - startTimeMs) / 1000
            findViewById<TextView>(R.id.txtTime)?.text =
                String.format("%02d:%02d", elapsed / 60, elapsed % 60)
        } catch (_: Exception) {
        }
    }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            VpnLog.info("Permiso VPN aceptado por el usuario.")
            startVpn()
        } else {
            VpnLog.warn("Permiso VPN denegado por el usuario.")
            Toast.makeText(this, "Permiso VPN denegado", Toast.LENGTH_SHORT).show()
        }
    }

    private val logListener = { runOnUiThread { updateLogUi() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        if (!openLogged) {
            openLogged = true
            VpnLog.info("App abierta.")
        }

        val btn = findViewById<MaterialButton>(R.id.btnToggle)
        btn.setOnClickListener {
            if (!vpnOn) {
                VpnLog.info("Botón Conectar tocado.")
                // Sin config global no se conecta ni se finge conectado.
                // Lo pidió Sam: si no hay config, no debería ni conectar.
                if (WgConfig.CONFIG.trim().isBlank()) {
                    VpnLog.error("Sin config WireGuard global. No se conecta.")
                    Toast.makeText(this, "Sin config: la app todavía no trae la config del servidor", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val intent = VpnService.prepare(this)
                if (intent != null) {
                    vpnPermissionLauncher.launch(intent)
                } else {
                    startVpn()
                }
            } else {
                stopVpn()
            }
        }

        val txtLog = findViewById<TextView>(R.id.txtLog)
        txtLog.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Ahorro VPN log", VpnLog.getText()))
            Toast.makeText(this, "Log copiado", Toast.LENGTH_SHORT).show()
            VpnLog.info("Log copiado al portapapeles.")
        }

        // Config GLOBAL: viene compilada en WgConfig, no se pega en la app.
        // Aquí solo se muestra si está incluida o pendiente.
        findViewById<TextView>(R.id.txtConfigStatus)?.text =
            if (WgConfig.CONFIG.trim().isBlank()) "Config global pendiente — todavía no trae la del servidor"
            else "Config global incluida ✓"

        VpnLog.addListener(logListener)
        updateUi()
    }

    override fun onDestroy() {
        statsHandler.removeCallbacks(statsRunnable)
        VpnLog.removeListener(logListener)
        super.onDestroy()
    }

    private fun startVpn() {
        val intent = Intent(this, AhorroVpnService::class.java)
        intent.action = AhorroVpnService.ACTION_START
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            serviceStarted = true
        } catch (e: Exception) {
            VpnLog.error("No se pudo arrancar el servicio", e)
            Toast.makeText(this, "No se pudo arrancar el servicio", Toast.LENGTH_SHORT).show()
            return
        }
        vpnOn = true
        startTimeMs = System.currentTimeMillis()
        VpnLog.info("VPN encendida desde la app.")
        updateUi()
        statsHandler.removeCallbacks(statsRunnable)
        statsHandler.post(statsRunnable)
    }

    private fun stopVpn() {
        statsHandler.removeCallbacks(statsRunnable)
        // ANTES: se mandaba el STOP con startForegroundService(). Eso crea
        // una instancia NUEVA del servicio solo para pararlo, y esa
        // instancia llamaba a stopForeground sin haber arrancado el
        // foreground — ahí se cerraba la app al tocar Desconectar.
        // AHORA: stopService() no crea ninguna instancia, solo para el
        // servicio si está corriendo. Y si nunca arrancó, no se le manda nada.
        if (serviceStarted) {
            try {
                val intent = Intent(this, AhorroVpnService::class.java)
                stopService(intent)
            } catch (e: Exception) {
                VpnLog.error("No se pudo parar el servicio", e)
            }
            serviceStarted = false
        }
        vpnOn = false
        VpnLog.info("VPN apagada desde la app.")
        updateUi()
    }

    private fun updateUi() {
        try {
            val btn = findViewById<MaterialButton>(R.id.btnToggle) ?: return
            val status = findViewById<TextView>(R.id.txtStatus)
            val pill = findViewById<TextView>(R.id.txtStatusPill)
            val server = findViewById<TextView>(R.id.txtServer)
            val used = findViewById<TextView>(R.id.txtUsed)
            val saved = findViewById<TextView>(R.id.txtSaved)
            val time = findViewById<TextView>(R.id.txtTime)
            val dial = findViewById<android.widget.FrameLayout>(R.id.dialView)
            val dialState = findViewById<TextView>(R.id.txtDialState)
            val dialIp = findViewById<TextView>(R.id.txtDialIp)
            if (vpnOn) {
                btn.text = "DESCONECTAR"
                status?.text = "Protegido - Ahorrando"
                pill?.text = "●  ONLINE"
                pill?.setBackgroundResource(R.drawable.bg_pill_on)
                dial?.setBackgroundResource(R.drawable.bg_dial_on)
                dialState?.text = "ON"
                dialState?.setTextColor(getColor(R.color.lime))
                dialIp?.text = "Tu IP está oculta"
                dialIp?.setTextColor(getColor(R.color.lime))
                server?.text = "Servidor Ahorro - WireGuard 10.8.0.1"
            } else {
                btn.text = "CONECTAR  →"
                status?.text = "Desconectado"
                pill?.text = "●  OFFLINE"
                pill?.setBackgroundResource(R.drawable.bg_pill_off)
                dial?.setBackgroundResource(R.drawable.bg_dial_off)
                dialState?.text = "OFF"
                dialState?.setTextColor(getColor(R.color.text_primary))
                dialIp?.text = "Tu IP está expuesta"
                dialIp?.setTextColor(getColor(R.color.red))
                server?.text = "No conectado"
                used?.text = "0 B"
                saved?.text = "0 B"
                time?.text = "00:00"
            }
        } catch (e: Exception) {
            VpnLog.error("Fallo al actualizar la pantalla", e)
        }
        updateLogUi()
    }

    private fun updateLogUi() {
        val txtLog = findViewById<TextView>(R.id.txtLog) ?: return
        txtLog.text = VpnLog.getText()
    }
}
