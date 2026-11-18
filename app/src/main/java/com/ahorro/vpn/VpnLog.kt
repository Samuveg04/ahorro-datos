package com.ahorro.vpn

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Log de la app VPN — lo pidió Sam: la app TIENE que tener un log.
 *
 * - Guarda las últimas 200 líneas en memoria, con hora local.
 * - Escribe también en logcat (tag AhorroVPN) para verlo con adb.
 * - Guarda en un archivo log.txt en el almacenamiento interno,
 *   para compartirlo o revisarlo después de un fallo.
 * - MainActivity lo muestra en la tarjeta "Registro".
 */
object VpnLog {
    private const val TAG = "AhorroVPN"
    private const val MAX_LINES = 200
    private val lines = mutableListOf<String>()
    private val listeners = mutableListOf<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun timeNow(): String {
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    }

    @Synchronized
    fun add(level: String, message: String) {
        val line = "[${timeNow()}] $level: $message"
        lines.add(line)
        if (lines.size > MAX_LINES) lines.removeAt(0)
        if (level == "ERROR") Log.e(TAG, message) else Log.d(TAG, message)
        listeners.toList().forEach { try { it() } catch (_: Exception) {} }
    }

    fun info(message: String) = add("INFO", message)
    fun warn(message: String) = add("WARN", message)
    fun error(message: String) = add("ERROR", message)
    fun error(message: String, e: Throwable) = add("ERROR", "$message - ${e.message ?: e.toString()}")

    @Synchronized
    fun getAll(): List<String> = lines.toList()

    @Synchronized
    fun getText(): String = if (lines.isEmpty()) "Sin eventos todavia." else lines.takeLast(30).joinToString("\n")

    @Synchronized
    fun clear() {
        lines.clear()
        listeners.toList().forEach { try { it() } catch (_: Exception) {} }
    }

    fun saveToFile(context: Context) {
        try {
            val file = File(context.filesDir, "log.txt")
            file.writeText(getAll().joinToString("\n"))
            info("Log guardado en log.txt (${getAll().size} lineas)")
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo guardar log.txt", e)
        }
    }
}
