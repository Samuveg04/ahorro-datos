package com.ahorro.vpn

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Log de la app VPN — lo pidió Sam: la app TIENE que tener un log.
 * Corregido 18 nov 2026:
 * - saveToFile() ya NO llama a info() dentro: eso disparaba los listeners
 *   durante onDestroy() y podía re-entrar y tumbar la app al desconectar.
 */
object VpnLog {
    private const val TAG = "AhorroVPN"
    private const val MAX_LINES = 200
    private val lines = mutableListOf<String>()
    private val listeners = mutableListOf<() -> Unit>()
    private var crashHandlerInstalled = false

    /**
     * Atrapa cualquier cierre inesperado y deja la causa escrita:
     * si la app se cierra sola, al abrirla de nuevo el registro muestra
     * el error exacto (hilo + traza), y queda en crash.txt / log.txt.
     * La app sigue cerrándose igual (no se esconde el fallo), pero
     * la próxima vez se sabe POR QUÉ.
     */
    @Synchronized
    fun installCrashHandler(context: Context) {
        if (crashHandlerInstalled) return
        crashHandlerInstalled = true
        val appContext = context.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                val trace = Log.getStackTraceString(e)
                try {
                    File(appContext.filesDir, "crash.txt")
                        .writeText("Crash en hilo ${thread.name}:\n$trace")
                } catch (_: Exception) {}
                try { add("ERROR", "La app se cerró: ${e.message}\n$trace") } catch (_: Exception) {}
                try { saveToFile(appContext) } catch (_: Exception) {}
            } catch (_: Exception) {}
            try { prev?.uncaughtException(thread, e) } catch (_: Exception) {}
        }
    }

    /** Lee la traza del último cierre (si la hubo) y la borra. */
    fun loadLastCrash(context: Context): String? {
        return try {
            val f = File(context.filesDir, "crash.txt")
            if (!f.exists()) null else {
                val t = f.readText()
                try { f.delete() } catch (_: Exception) {}
                t
            }
        } catch (_: Exception) { null }
    }

    fun addListener(listener: () -> Unit) {
        if (!listeners.contains(listener)) listeners.add(listener)
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
            Log.d(TAG, "Log guardado en log.txt (${getAll().size} lineas)")
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo guardar log.txt", e)
        }
    }
}
