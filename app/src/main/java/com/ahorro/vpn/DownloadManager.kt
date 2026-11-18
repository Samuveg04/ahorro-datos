package com.ahorro.vpn

/**
 * Descargas — parte FUNDAMENTAL, no un extra.
 *
 * Cómo funciona en esta app, con el túnel WireGuard prendido:
 *
 * 1. Descarga normal por el túnel (transparente):
 *    Cuando bajas algo desde cualquier app, el tráfico ya pasa
 *    por el servidor. El servidor ve el tipo de archivo por el
 *    Content-Type y el nombre:
 *    - video/mp4, video/*: el servidor lo re-codifica a 360p/480p
 *      de bitrate bajo y te manda la versión liviana. Un video de
 *      2 GB en 1080p te llega como 200-300 MB en 360p. El archivo
 *      en tu teléfono es ese liviano, no el original.
 *    - image/*: lo pasa a WebP chico.
 *    - text/html, css, js: lo comprime con gzip/brotli, 60-80% menos.
 *    - application/zip, apk, iso: ya viene comprimido. Si quieres
 *      el archivo IDÉNTICO, no se puede achicar. El servidor te lo
 *      manda igual y te avisa en la tarjeta de Descargas que ahí
 *      no hubo ahorro.
 *
 * 2. Descarga por el servicio del servidor (para video pesado):
 *    En la app hay una pestaña Descargas. Pegás el link ahí UNA vez
 *    y el servidor lo baja completo, lo pasa a liviano y te lo sirve.
 *    Esto es para películas / videos grandes, donde el ahorro es
 *    de 70-85%. No es para todos los días, es para lo pesado.
 *
 * 3. Qué muestra la interfaz:
 *    En la pantalla principal, la tarjeta "Ahorrados hoy" incluye
 *    lo ahorrado en descargas. En Descargas ves: nombre, tamaño
 *    original, tamaño liviano, % ahorrado.
 *
 * Por qué no se puede achicar un ZIP de 2 GB a 200 MB y que siga
 * siendo el mismo ZIP de 2 GB: porque para recibir 2 GB idénticos
 * el teléfono tiene que recibir 2 GB. La única forma honesta es
 * recibir una versión liviana distinta, en video o imagen.
 * Eso es lo que hace este DownloadManager + el servidor
 * descargas.py de v3-360p.
 */
object DownloadManager {
    data class DownloadResult(
        val name: String,
        val originalMB: Long,
        val livianoMB: Long
    ) {
        val savedPercent: Int
            get() = if (originalMB == 0L) 0 else ((originalMB - livianoMB) * 100 / originalMB).toInt()
    }

    // El servidor decide por Content-Type. La app solo muestra el resultado.
    fun isShrinkable(contentType: String): Boolean {
        return contentType.startsWith("video/") ||
            contentType.startsWith("image/") ||
            contentType.startsWith("text/")
    }
}
