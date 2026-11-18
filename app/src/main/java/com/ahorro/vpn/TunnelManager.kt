package com.ahorro.vpn

/**
 * TunnelManager — conecta el TUN con el servidor WireGuard.
 *
 * NO reinventes el protocolo. Usa la librería oficial:
 *   implementation("com.wireguard.android:tunnel:1.0.20231018")
 *
 * En build.gradle.kts añade esa dependencia y usa GoBackend:
 *
 *   val backend = GoBackend(context)
 *   val config = WgQuickConfig.from("[Interface]\nPrivateKey=...\n...")
 *   backend.setState(tunnel, Tunnel.State.UP, config)
 *
 * La config es la misma wg-sam.conf que ya genera wg-setup.sh:
 *   [Interface] PrivateKey, Address 10.8.0.2/24, DNS 10.8.0.1
 *   [Peer] PublicKey del servidor, Endpoint IP:51820,
 *          AllowedIPs 0.0.0.0/0
 *
 * AllowedIPs 0.0.0.0/0 es lo que hace que TODO pase por el túnel.
 * Si pones solo una IP, solo esa IP pasaría.
 *
 * El servidor hace el ahorro (ver android-solo/server/):
 * - AdGuard: bloquea anuncios en todas las apps
 * - Shaping: video a 1 Mbps, juegos sin límite
 * - Squid: caché
 *
 * Tu app solo tiene que prender y apagar el túnel y mostrar
 * si está conectado. El trabajo pesado es del servidor.
 */
object TunnelManager {
    // Aquí va la integración real con GoBackend de WireGuard.
    // Esqueleto intencional: no pegues claves en el código,
    // guárdalas en el servidor y descárgalas con tu cuenta.
    fun connect() { /* backend.setState(...) */ }
    fun disconnect() { /* backend.setState(..., DOWN) */ }
}
