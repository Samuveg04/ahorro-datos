package com.ahorro.vpn

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/**
 * UNA sola app, UN solo interruptor, la config que sea.
 * Lo pidió Sam 18 nov 2026: lo mismo un archivo config local que
 * un archivo config de WireGuard, la app los ejecuta igual.
 * - Si la config trae [Interface] + [Peer] -> WireGuard (túnel al servidor).
 * - Si trae [Local] -> modo local (proxy local: todo el tráfico pasa
 *   por la app, bloqueo DNS y freno de vídeo a 0,5 Mbps, sin servidor).
 * La app detecta cuál es al importar y el interruptor arranca ese modo.
 * - La config puede venir cifrada (.ahorro): se descifra al importar.
 *
 * Diseño v7 "CONSOLA" (18 nov 2026, pedido de Sam: interfaz nueva
 * de paquete, nada de retoque del v6, nada de aspecto de IA):
 * tarjetas sólidas con borde fino, un solo acento esmeralda, todo
 * simétrico y centrado, interruptor real con perilla que se desliza,
 * registro con scroll de verdad y detalles de conexión de VPN pro.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        // "App abierta" se registra una sola vez por proceso.
        // Antes se duplicaba porque onCreate se vuelve a llamar
        // al volver del diálogo de permiso VPN.
        private var openLogged = false
        private const val PREFS = "ahorro_vpn_prefs"
        private const val KEY_WG_CONFIG = "wg_config"
        private const val KEY_LOCAL_CONFIG = "local_config"
        private const val KEY_CONFIG_TYPE = "config_type"
        private const val TYPE_WIREGUARD = "wireguard"
        private const val TYPE_LOCAL = "local"
    }

    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    /** Detecta el tipo de config por su contenido. Null si no es válida. */
    private fun detectConfigType(text: String): String? {
        return when {
            text.contains("[Local]") -> TYPE_LOCAL
            text.contains("[Interface]") && text.contains("[Peer]") -> TYPE_WIREGUARD
            else -> null
        }
    }

    /** El tipo de config activo: el guardado, o el que se deduce de lo guardado. */
    private fun getActiveType(): String? {
        val saved = prefs.getString(KEY_CONFIG_TYPE, "")?.trim().orEmpty()
        if (saved == TYPE_WIREGUARD || saved == TYPE_LOCAL) return saved
        if (prefs.getString(KEY_WG_CONFIG, "")?.trim().orEmpty().isNotBlank()) return TYPE_WIREGUARD
        if (prefs.getString(KEY_LOCAL_CONFIG, "")?.trim().orEmpty().isNotBlank()) return TYPE_LOCAL
        return null
    }

    private fun getWireGuardConfig(): String =
        prefs.getString(KEY_WG_CONFIG, "")?.trim().orEmpty()

    private fun updateConfigStatus() {
        findViewById<TextView>(R.id.txtConfigStatus)?.text = when (getActiveType()) {
            TYPE_WIREGUARD -> "Config WireGuard ✓ (archivo .conf)"
            TYPE_LOCAL -> "Config local ✓ (archivo local)"
            else -> "Sin config — importa un archivo .conf o local"
        }
    }

    private val importConfigLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) {
            VpnLog.info("Importar config cancelado.")
            return@registerForActivityResult
        }
        try {
            // Se lee como BYTES: el archivo puede venir cifrado (.ahorro)
            // o en texto plano (.conf / .txt). Si es .ahorro se descifra
            // primero y luego se detecta el tipo igual que antes.
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
            val text = if (ConfigCrypto.isEncrypted(bytes)) {
                val decrypted = ConfigCrypto.decrypt(bytes)
                if (decrypted == null) {
                    VpnLog.error("No se pudo descifrar el archivo de config.")
                    Toast.makeText(this, "No se pudo descifrar ese archivo", Toast.LENGTH_LONG).show()
                    return@registerForActivityResult
                }
                VpnLog.info("Archivo .ahorro descifrado.")
                decrypted.trim()
            } else {
                String(bytes, Charsets.UTF_8).trim()
            }
            val type = detectConfigType(text)
            if (type == null) {
                VpnLog.error("Archivo importado no es una config válida (ni WireGuard ni local).")
                Toast.makeText(this, "Ese archivo no es una config válida", Toast.LENGTH_LONG).show()
                return@registerForActivityResult
            }
            val editor = prefs.edit()
            if (type == TYPE_WIREGUARD) {
                editor.putString(KEY_WG_CONFIG, text)
                VpnLog.info("Config WireGuard importada desde archivo.")
                Toast.makeText(this, "Config WireGuard importada ✓", Toast.LENGTH_SHORT).show()
            } else {
                editor.putString(KEY_LOCAL_CONFIG, text)
                VpnLog.info("Config local importada desde archivo.")
                Toast.makeText(this, "Config local importada ✓", Toast.LENGTH_SHORT).show()
            }
            editor.putString(KEY_CONFIG_TYPE, type)
            editor.apply()
            updateConfigStatus()
            updateUi()
        } catch (e: Exception) {
            VpnLog.error("No se pudo leer el archivo de config", e)
            Toast.makeText(this, "No se pudo leer ese archivo", Toast.LENGTH_LONG).show()
        }
    }

    private var vpnOn = false
    private var serviceStarted = false
    // Modo local (sin servidor): proxy local con freno de vídeo.
    private var localOn = false
    private var localServiceStarted = false
    private var startTimeMs: Long = 0L
    private var prevRx: Long = 0L
    private var prevTx: Long = 0L
    private var prevSampleMs: Long = 0L
    // Interruptor: recorrido de la perilla en px y último estado dibujado.
    private var switchTravelPx = 0f
    private var lastSwitchOn: Boolean? = null

    private val statsHandler = Handler(Looper.getMainLooper())
    private val statsRunnable = object : Runnable {
        override fun run() {
            if (vpnOn || localOn) {
                updateStatsUi()
                updateDetails()
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
            val (rx, tx) = when {
                vpnOn -> TunnelManager.getSessionStats()
                localOn -> LocalProxyVpnService.getSessionStats()
                else -> Pair(0L, 0L)
            }
            val now = System.currentTimeMillis()
            val dtSec = ((now - prevSampleMs).coerceAtLeast(1L)) / 1000.0
            val rxRate = ((rx - prevRx).coerceAtLeast(0L) / dtSec).toLong()
            val txRate = ((tx - prevTx).coerceAtLeast(0L) / dtSec).toLong()
            prevRx = rx
            prevTx = tx
            prevSampleMs = now
            findViewById<TextView>(R.id.txtUsed)?.text = formatBytes(rx)
            findViewById<TextView>(R.id.txtSaved)?.text = formatBytes(tx)
            findViewById<TextView>(R.id.txtRateDown)?.text = formatBytes(rxRate) + "/s"
            findViewById<TextView>(R.id.txtRateUp)?.text = formatBytes(txRate) + "/s"
            findViewById<TrafficGraphView>(R.id.graphView)?.addSample(rxRate.toFloat(), txRate.toFloat())
            val elapsed = (now - startTimeMs) / 1000
            findViewById<TextView>(R.id.txtTime)?.text =
                String.format("%02d:%02d", elapsed / 60, elapsed % 60)
        } catch (_: Exception) {
        }
    }

    private fun updateDetails() {
        findViewById<TextView>(R.id.txtBlockedValue)?.text =
            "${LocalProxyVpnService.blockedCount}"
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

    // Permiso para el modo local: Android solo permite UNA vpn a la vez.
    private val localPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            VpnLog.info("Permiso VPN aceptado para el modo local.")
            startLocal()
        } else {
            VpnLog.warn("Permiso VPN denegado para el modo local.")
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

        // El interruptor: un toque conecta o desconecta.
        val switchTrack = findViewById<FrameLayout>(R.id.switchTrack)
        switchTrack?.setOnClickListener { toggleConnection() }
        // Recorrido de la perilla: ancho de pista menos perilla y márgenes.
        switchTrack?.post {
            val thumb = findViewById<ImageView>(R.id.switchThumb)
            if (switchTrack.width > 0 && thumb != null) {
                switchTravelPx = (switchTrack.width - thumb.width - dpToPx(8)).toFloat()
                applySwitchState(vpnOn || localOn, animate = false)
            }
        }

        // El registro tiene su propio scroll: al tocarlo, la pantalla
        // no debe robarle el gesto (antes el scroll estaba bloqueado).
        val logScroll = findViewById<ScrollView>(R.id.logScroll)
        logScroll?.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                v.parent.requestDisallowInterceptTouchEvent(true)
            }
            false
        }

        val txtLog = findViewById<TextView>(R.id.txtLog)
        txtLog.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Ahorro VPN log", VpnLog.getText()))
            Toast.makeText(this, "Log copiado", Toast.LENGTH_SHORT).show()
            VpnLog.info("Log copiado al portapapeles.")
        }

        // Config: un solo botón de importar, la app detecta el tipo.
        updateConfigStatus()
        findViewById<MaterialButton>(R.id.btnImportConfig)?.setOnClickListener {
            VpnLog.info("Importar config tocado.")
            importConfigLauncher.launch("*/*")
        }
        findViewById<MaterialButton>(R.id.btnClearConfig)?.setOnClickListener {
            // Borra la config activa; si queda la otra, esa pasa a ser la activa.
            val active = getActiveType()
            val editor = prefs.edit()
            if (active == TYPE_WIREGUARD) editor.remove(KEY_WG_CONFIG) else editor.remove(KEY_LOCAL_CONFIG)
            val other = if (active == TYPE_WIREGUARD) {
                prefs.getString(KEY_LOCAL_CONFIG, "")?.trim().orEmpty()
            } else {
                prefs.getString(KEY_WG_CONFIG, "")?.trim().orEmpty()
            }
            if (other.isNotBlank()) {
                editor.putString(KEY_CONFIG_TYPE, if (active == TYPE_WIREGUARD) TYPE_LOCAL else TYPE_WIREGUARD)
            } else {
                editor.remove(KEY_CONFIG_TYPE)
            }
            editor.apply()
            VpnLog.info("Config borrada.")
            Toast.makeText(this, "Config borrada", Toast.LENGTH_SHORT).show()
            updateConfigStatus()
            updateUi()
        }

        VpnLog.addListener(logListener)
        updateUi()
    }

    override fun onDestroy() {
        statsHandler.removeCallbacks(statsRunnable)
        VpnLog.removeListener(logListener)
        super.onDestroy()
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    /** Dibuja el interruptor (perilla + color de pista). Solo anima si cambió. */
    private fun applySwitchState(on: Boolean, animate: Boolean) {
        val track = findViewById<FrameLayout>(R.id.switchTrack) ?: return
        val thumb = findViewById<ImageView>(R.id.switchThumb) ?: return
        val changed = lastSwitchOn != on
        lastSwitchOn = on
        track.setBackgroundResource(
            if (on) R.drawable.bg_switch_on else R.drawable.bg_switch_off
        )
        val target = if (on) switchTravelPx else 0f
        if (animate && changed && switchTravelPx > 0f) {
            thumb.animate().translationX(target).setDuration(220).start()
        } else {
            thumb.translationX = target
        }
    }

    private fun toggleConnection() {
        if (vpnOn) {
            stopVpn()
            return
        }
        if (localOn) {
            stopLocal()
            return
        }
        VpnLog.info("Interruptor tocado: conectar.")
        when (getActiveType()) {
            TYPE_WIREGUARD -> {
                // Sin config de WireGuard no se conecta ni se finge conectado.
                if (getWireGuardConfig().isBlank()) {
                    VpnLog.error("Sin config WireGuard. Importa el archivo .conf del servidor.")
                    Toast.makeText(this, "Sin config: importa el archivo .conf del servidor", Toast.LENGTH_LONG).show()
                    return
                }
                val intent = VpnService.prepare(this)
                if (intent != null) vpnPermissionLauncher.launch(intent) else startVpn()
            }
            TYPE_LOCAL -> {
                val intent = VpnService.prepare(this)
                if (intent != null) localPermissionLauncher.launch(intent) else startLocal()
            }
            else -> {
                VpnLog.error("Sin config. Importa un archivo .conf o una config local.")
                Toast.makeText(this, "Sin config: importa un archivo .conf o local", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun resetStats() {
        prevRx = 0L
        prevTx = 0L
        prevSampleMs = System.currentTimeMillis()
        findViewById<TrafficGraphView>(R.id.graphView)?.clear()
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
        resetStats()
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

    // ---- Modo local (sin servidor): proxy local con freno de vídeo ----
    private fun startLocal() {
        val intent = Intent(this, LocalProxyVpnService::class.java)
        intent.action = LocalProxyVpnService.ACTION_START
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            localServiceStarted = true
        } catch (e: Exception) {
            VpnLog.error("No se pudo arrancar el modo local", e)
            Toast.makeText(this, "No se pudo arrancar el modo local", Toast.LENGTH_SHORT).show()
            return
        }
        localOn = true
        startTimeMs = System.currentTimeMillis()
        VpnLog.info("Modo local encendido desde la app.")
        resetStats()
        updateUi()
        statsHandler.removeCallbacks(statsRunnable)
        statsHandler.post(statsRunnable)
    }

    private fun stopLocal() {
        statsHandler.removeCallbacks(statsRunnable)
        if (localServiceStarted) {
            try {
                val intent = Intent(this, LocalProxyVpnService::class.java)
                stopService(intent)
            } catch (e: Exception) {
                VpnLog.error("No se pudo parar el modo local", e)
            }
            localServiceStarted = false
        }
        localOn = false
        VpnLog.info("Modo local apagado desde la app.")
        updateUi()
    }

    private fun updateUi() {
        try {
            val on = vpnOn || localOn
            applySwitchState(on, animate = true)
            val pill = findViewById<TextView>(R.id.txtStatusPill)
            val title = findViewById<TextView>(R.id.txtStatusTitle)
            val sub = findViewById<TextView>(R.id.txtStatusSub)
            val modeValue = findViewById<TextView>(R.id.txtModeValue)
            val serverValue = findViewById<TextView>(R.id.txtServerValue)
            val protoValue = findViewById<TextView>(R.id.txtProtoValue)
            val videoValue = findViewById<TextView>(R.id.txtVideoValue)
            val used = findViewById<TextView>(R.id.txtUsed)
            val saved = findViewById<TextView>(R.id.txtSaved)
            val time = findViewById<TextView>(R.id.txtTime)
            val rateDown = findViewById<TextView>(R.id.txtRateDown)
            val rateUp = findViewById<TextView>(R.id.txtRateUp)
            updateDetails()
            when {
                vpnOn -> {
                    pill?.text = "●  ONLINE"
                    pill?.setBackgroundResource(R.drawable.bg_pill_on)
                    title?.text = "Protegido"
                    sub?.text = "Tu IP está oculta"
                    sub?.setTextColor(getColor(R.color.lime))
                    modeValue?.text = "WireGuard"
                    serverValue?.text = "Servidor Ahorro · 10.8.0.1"
                    protoValue?.text = "WireGuard"
                    videoValue?.text = "Shaping en servidor"
                }
                localOn -> {
                    pill?.text = "●  ONLINE"
                    pill?.setBackgroundResource(R.drawable.bg_pill_on)
                    title?.text = "Modo local"
                    sub?.text = "Tu IP es la de Cuba"
                    sub?.setTextColor(getColor(R.color.gold))
                    modeValue?.text = "Local"
                    serverValue?.text = "Sin servidor"
                    protoValue?.text = "Proxy local"
                    videoValue?.text = "Freno 0,5 Mbps"
                }
                else -> {
                    pill?.text = "●  OFFLINE"
                    pill?.setBackgroundResource(R.drawable.bg_pill_off)
                    title?.text = "Desconectado"
                    sub?.text = "Tu IP está expuesta"
                    sub?.setTextColor(getColor(R.color.red))
                    modeValue?.text = when (getActiveType()) {
                        TYPE_WIREGUARD -> "WireGuard"
                        TYPE_LOCAL -> "Local"
                        else -> "Sin config"
                    }
                    serverValue?.text = "No conectado"
                    protoValue?.text = "—"
                    videoValue?.text = "—"
                    used?.text = "0 B"
                    saved?.text = "0 B"
                    time?.text = "00:00"
                    rateDown?.text = "0 B/s"
                    rateUp?.text = "0 B/s"
                }
            }
        } catch (e: Exception) {
            VpnLog.error("Fallo al actualizar la pantalla", e)
        }
        updateLogUi()
    }

    private fun updateLogUi() {
        val txtLog = findViewById<TextView>(R.id.txtLog) ?: return
        txtLog.text = VpnLog.getText()
        // El registro baja solo hasta el final, para ver lo último.
        findViewById<ScrollView>(R.id.logScroll)?.post {
            findViewById<ScrollView>(R.id.logScroll)?.fullScroll(View.FOCUS_DOWN)
        }
    }
}
