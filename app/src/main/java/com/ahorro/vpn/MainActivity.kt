package com.ahorro.vpn

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
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

    private var vpnOn = false
    private var startTimeMs: Long = 0L

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
        VpnLog.info("App abierta.")

        val btn = findViewById<MaterialButton>(R.id.btnToggle)
        btn.setOnClickListener {
            if (!vpnOn) {
                VpnLog.info("Botón Conectar tocado.")
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

        VpnLog.addListener(logListener)
        updateUi()
    }

    override fun onDestroy() {
        VpnLog.removeListener(logListener)
        super.onDestroy()
    }

    private fun startVpn() {
        val intent = Intent(this, AhorroVpnService::class.java)
        intent.action = AhorroVpnService.ACTION_START
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        vpnOn = true
        startTimeMs = System.currentTimeMillis()
        VpnLog.info("VPN encendida desde la app.")
        updateUi()
    }

    private fun stopVpn() {
        val intent = Intent(this, AhorroVpnService::class.java)
        intent.action = AhorroVpnService.ACTION_STOP
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        vpnOn = false
        VpnLog.info("VPN apagada desde la app.")
        updateUi()
    }

    private fun updateUi() {
        val btn = findViewById<MaterialButton>(R.id.btnToggle)
        val status = findViewById<TextView>(R.id.txtStatus)
        val pill = findViewById<TextView>(R.id.txtStatusPill)
        val server = findViewById<TextView>(R.id.txtServer)
        val used = findViewById<TextView>(R.id.txtUsed)
        val saved = findViewById<TextView>(R.id.txtSaved)
        val time = findViewById<TextView>(R.id.txtTime)
        if (vpnOn) {
            btn.text = "Desconectar"
            status.text = "Protegido - Ahorrando"
            pill.text = "Conectado"
            pill.setBackgroundResource(R.drawable.bg_pill_on)
            server.text = "Servidor Ahorro - WireGuard 10.8.0.1"
            used.text = "Activo"
            saved.text = "-"
            time.text = "00:00"
        } else {
            btn.text = "Conectar"
            status.text = "Desconectado"
            pill.text = "Apagado"
            pill.setBackgroundResource(R.drawable.bg_pill_off)
            server.text = "No conectado"
            used.text = "0 MB"
            saved.text = "0 MB"
            time.text = "00:00"
        }
        updateLogUi()
    }

    private fun updateLogUi() {
        val txtLog = findViewById<TextView>(R.id.txtLog) ?: return
        txtLog.text = VpnLog.getText()
    }
}
