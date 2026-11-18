package com.ahorro.vpn

/**
 * Lista de dominios de anuncios, rastreadores y analítica.
 * Ahora es GRANDE (~7.000 dominios): la lista curada de antes
 * (~120) + una selección de la lista unificada de StevenBlack
 * (hosts, dominio público, millones de usuarios) + los
 * dominios de DNS seguro (DoH) para que Chrome y las apps no
 * se salten el DNS de la VPN.
 *
 * Solo dominios que son PURO anuncio/rastreo: nunca CDNs de
 * vídeo (googlevideo, fbcdn, tiktokcdn...), ni APIs que usan
 * las apps (graph.facebook.com NO está aquí: bloquearlo
 * rompería Facebook e Instagram). Los subdominios de
 * anuncios (ads.youtube.com, pixel.facebook.com...) SÍ están:
 * son rastreo, no contenido, y bloquearlos no rompe la app.
 *
 * La comparación es por sufijo: "doubleclick.net" bloquea
 * también "www.doubleclick.net" y "stats.doubleclick.net".
 * La búsqueda camina por las etiquetas ("a.b.c.com" ->
 * "b.c.com" -> "c.com") en un HashSet, así que es rápida
 * aunque la lista tenga miles de entradas.
 *
 * Los dominios viven en BlocklistData1.kt y BlocklistData2.kt
 * (la lista partida en dos para que cada archivo sea manejable).
 *
 * LÍMITE HONESTO: esto bloquea lo que llega por DNS. Los
 * anuncios que el propio sitio sirve desde su dominio y las
 * redirecciones que hace su JavaScript no se pueden parar
 * por DNS: para eso hace falta un bloqueador de contenido
 * en el navegador (Firefox + uBlock Origin).
 */
object Blocklist {
    private val DOMAINS: Set<String> =
        BlocklistData1.DOMAINS + BlocklistData2.DOMAINS

    fun isBlocked(host: String): Boolean {
        var h = host.lowercase().trim().trimEnd('.')
        if (h.isEmpty()) return false
        // Camina por las etiquetas: a.b.c.com -> b.c.com -> c.com.
        while (true) {
            if (DOMAINS.contains(h) || extra.contains(h)) return true
            val dot = h.indexOf('.')
            if (dot < 0) break
            h = h.substring(dot + 1)
        }
        return false
    }

    /** Dominios extra que trae la config local del usuario. */
    fun setExtra(domains: Set<String>) {
        extra.clear()
        extra.addAll(domains.map { it.lowercase().trim().trimEnd('.') }.filter { it.isNotBlank() })
    }

    private val extra = mutableSetOf<String>()
}
