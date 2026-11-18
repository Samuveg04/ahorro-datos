package com.ahorro.vpn

/**
 * Config WireGuard GLOBAL, dentro del código, compilada en la app.
 * Es el RESPALDO: si el usuario importa su propio archivo .conf,
 * esa importada manda. Si no hay importada, se usa esta global.
 *
 * Lo pidió Sam 18 nov 2026: la app es solo para sus amigos, no
 * comercial, y cada uno importa su propio archivo de config con
 * el botón IMPORTAR .CONF. La global queda por si alguien instala
 * la app sin importar nada.
 *
 * Cómo se llena: cuando el servidor esté listo, se pega aquí dentro
 * el texto COMPLETO del sam.conf que genera el servidor
 * (crear-cliente.sh sam), entre las comillas triples. Se recompila
 * y quien no importe nada ya la tiene, sin pegar nada.
 *
 * Mientras esté vacío y no haya importada, la app NO conecta y se
 * queda en Desconectado. Eso es a propósito: sin config no hay
 * conexión, nunca se finge que está conectada.
 */
object WgConfig {
    const val CONFIG: String = """
"""
}
