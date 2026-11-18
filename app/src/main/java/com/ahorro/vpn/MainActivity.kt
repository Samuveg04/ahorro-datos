package com.ahorro.vpn

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/**
 * Pantalla moderna: fondo oscuro, botón grande central, 3 tarjetas.
 * IDs del layout activity_main.xml:
 *  btnToggle, txtStatus, txtStatusPill, txtUsed, txtSaved, txtTime, txtServer
 * Y pestaña de Descargas: ver DownloadManager.kt
 */
class MainActivity : AppCompatActivity() {

    private val VPN_REQUEST_CODE = 1001
    private var vpnOn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btn = findViewById<MaterialButton>(R.id.btnToggle)
        btn.setOnClickListener {
            if (!vpnOn) {
                val intent = VpnService.prepare(this)
                if (intent != null) {
                    startActivityForResult(intent, VPN_REQUEST_CODE)
                } else {
                    startVpn()
                }
            } else {
                stopVpn()
            }
        }
        updateUi()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            startVpn()
        }
    }

    private fun startVpn() {
        val intent = Intent(this, AhorroVpnService::class.java)
        intent.action = AhorroVpnService.ACTION_START
        startService(intent)
        vpnOn = true
        updateUi()
    }

    private fun stopVpn() {
        val intent = Intent(this, AhorroVpnService::class.java)
        intent.action = AhorroVpnService.ACTION_STOP
        startService(intent)
        vpnOn = false
        updateUi()
    }

    private fun updateUi() {
        val btn = findViewById<MaterialButton>(R.id.btnToggle)
        val status = findViewById<TextView>(R.id.txtStatus)
        val pill = findViewById<TextView>(R.id.txtStatusPill)
        val server = findViewById<TextView>(R.id.txtServer)
        if (vpnOn) {
            btn.text = "Desconectar"
            status.text = "Protegido • Ahorrando"
            pill.text = "Conectado"
            pill.setBackgroundResource(R.drawable.bg_pill_on)
            server.text = "Servidor Ahorro • WireGuard 10.8.0.1"
        } else {
            btn.text = "Conectar"
            status.text = "Desconectado"
            pill.text = "Apagado"
            pill.setBackgroundResource(R.drawable.bg_pill_off)
            server.text = "No conectado"
        }
    }
}
