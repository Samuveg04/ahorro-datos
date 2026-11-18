package com.ahorro.vpn

/**
 * Descargas — parte FUNDAMENTAL, no un extra.
 * (Texto original intacto, solo se hizo null-safe y se añadió Log.)
 *
 * Cómo funciona con el túnel WireGuard prendido: el servidor ve el
 * Content-Type y decide: video -> 360p/480p liviano, imagen -> WebP,
 * texto -> gzip/brotli, ZIP/APK/ISO -> idéntico, sin ahorro posible.
 * Un ZIP de 2 GB no se puede achicar a 200 MB y seguir siendo el mismo
 * ZIP de 2 GB: para recibir 2 GB idénticos hay que recibir 2 GB.
 */
object DownloadManager {
    data class DownloadResult(
        val name: String,
        val originalMB: Long,
        val livianoMB: Long
    ) {
        val savedPercent: Int
            get() = if (originalMB <= 0L) 0 else ((originalMB - livianoMB) * 100 / originalMB).toInt()
    }

    fun isShrinkable(contentType: String?): Boolean {
        if (contentType.isNullOrBlank()) return false
        val ct = contentType.lowercase()
        val ok = ct.startsWith("video/") || ct.startsWith("image/") || ct.startsWith("text/")
        VpnLog.info("Descarga $contentType -> achicable: $ok")
        return ok
    }
}
