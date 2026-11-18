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

    companion object {
        // "App abierta" se registra una sola vez por proceso.
        // Antes se duplicaba porque onCreate se vuelve a llamar
        // al volver del diálogo de permiso VPN.
        private var openLogged = false
    }

    private var vpnOn = false
    private var serviceStarted = false
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
        if (!openLogged) {
            openLogged = true
            VpnLog.info("App abierta.")
        }

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

        // Config WireGuard: se pega el texto de sam.conf y se guarda en
        // SharedPreferences "ahorro_vpn_prefs", clave "wg_config".
        // El servicio la lee de ahí al conectar. Sin ella, no conecta.
        val edtConfig = findViewById<android.widget.EditText>(R.id.edtConfig)
        val btnSave = findViewById<MaterialButton>(R.id.btnSaveConfig)
        val btnClear = findViewById<MaterialButton>(R.id.btnClearConfig)
        val prefs = getSharedPreferences(AhorroVpnService.PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString(AhorroVpnService.KEY_WG_CONFIG, null)
        if (!saved.isNullOrBlank()) {
            edtConfig.setText(saved)
            findViewById<TextView>(R.id.txtConfigStatus).text = "Config guardada ✓"
        }
        btnSave.setOnClickListener {
            val text = edtConfig.text.toString().trim()
            if (text.isBlank()) {
                Toast.makeText(this, "Pega primero el texto de sam.conf", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!text.contains("[Interface]") || !text.contains("[Peer]")) {
                Toast.makeText(this, "Ese texto no parece una config WireGuard", Toast.LENGTH_LONG).show()
                VpnLog.warn("Config rechazada: no tiene [Interface] ni [Peer].")
                return@setOnClickListener
            }
            prefs.edit().putString(AhorroVpnService.KEY_WG_CONFIG, text).apply()
            findViewById<TextView>(R.id.txtConfigStatus).text = "Config guardada ✓"
            Toast.makeText(this, "Config guardada", Toast.LENGTH_SHORT).show()
            VpnLog.info("Config WireGuard guardada.")
        }
        btnClear.setOnClickListener {
            prefs.edit().remove(AhorroVpnService.KEY_WG_CONFIG).apply()
            edtConfig.setText("")
            findViewById<TextView>(R.id.txtConfigStatus).text = "Sin config guardada"
            Toast.makeText(this, "Config borrada", Toast.LENGTH_SHORT).show()
            VpnLog.info("Config WireGuard borrada.")
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
    }

    private fun stopVpn() {
        // Si el servicio nunca arrancó (por ejemplo, falló por falta de
        // config), NO le mandes un STOP: eso creaba una instancia nueva
        // solo para pararla y tumbaba la app. Solo actualiza la UI.
        if (serviceStarted) {
            val intent = Intent(this, AhorroVpnService::class.java)
            intent.action = AhorroVpnService.ACTION_STOP
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
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
