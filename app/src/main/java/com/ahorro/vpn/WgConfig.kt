package com.ahorro.vpn

/**
 * Config WireGuard GLOBAL, dentro del código, compilada en la app.
 * Lo pidió Sam 17 nov 2026 23:01: la config NO es local en la app
 * (nada de pegarla en un campo), va en el código interno y es la
 * misma para quien sea que instale la app.
 *
 * Cómo se llena: cuando el servidor esté listo, se pega aquí dentro
 * el texto COMPLETO del sam.conf que genera el servidor
 * (crear-cliente.sh sam), entre las comillas triples. Se recompila
 * y todos los usuarios ya la tienen, sin pegar nada.
 *
 * Mientras esté vacío, la app NO conecta y se queda en Desconectado.
 * Eso es a propósito: sin config no hay conexión, nunca se finge
 * que está conectada.
 */
object WgConfig {
    const val CONFIG: String = """
"""
}
