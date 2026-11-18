# Crear tu propia app VPN Android — cómo se hace

Sam, esto es lo que pediste: no usar la app WireGuard de otro,
sino crear TU app VPN, por la cual pase el tráfico COMPLETO del móvil.

## Cómo funciona una app VPN en Android, en simple

Android tiene una pieza que se llama `VpnService`. Es la única forma
legal que Android da para agarrar todo el tráfico del teléfono.

Funciona así:

1. Tú tocas el interruptor en TU app.
2. Tu app le pide permiso a Android: `VpnService.prepare()`.
   Android te muestra el cartel del sistema: "¿Permitir que esta
   app cree una conexión VPN?" Tú aceptas una sola vez.
3. Tu app crea un `VpnService` y le dice a Android:
   "Crea una interfaz virtual TUN con IP 10.8.0.2, y manda TODO
   el tráfico ahí, ruta 0.0.0.0/0, DNS 10.8.0.1".
4. A partir de ahí, Android mete TODO el tráfico de TODAS las apps
   dentro de ese TUN: YouTube, Instagram, TikTok, Facebook,
   WhatsApp, juegos, IAs, navegador, descargas. Todo.
5. Tu servicio lee paquetes IP de ese TUN, los cifra y los manda
   por UDP al servidor, puerto 51820.
6. El servidor los descifra, los manda a internet, hace el ahorro
   (bloquea anuncios, limita video, cachea), y te devuelve la
   respuesta por el mismo túnel.
7. Tu servicio escribe la respuesta dentro del TUN, y Android
   se la entrega a la app que la pidió.
8. Tocas el interruptor de nuevo, el servicio se para, el TUN
   se cierra, y el tráfico vuelve a ir directo.

La app en sí NO comprime. La app solo agarra el tráfico y lo
manda al servidor. El ahorro lo hace el servidor, antes de
devolverte la respuesta. Por eso necesitas las dos partes:
la app en el teléfono y el servidor fuera.

## No lo escribas desde cero

No escribas el protocolo VPN desde cero. Usa la librería oficial
de WireGuard para Android, que ya hace el cifrado y el túnel bien.

Tu app solo tiene que hacer 3 cosas:
- La pantalla con el interruptor.
- Llamar a `VpnService` y a WireGuard.
- Mostrar estado y cuántos MB llevas ahorrados.

El protocolo, el cifrado, el manejo de paquetes TCP/UDP, eso
lo hace la librería WireGuard (wg-go). Si lo intentas hacer a mano
con un socket UDP simple, te vas a enredar con TCP, DNS, paquetes
fragmentados, y la app se va a caer.

## Estructura del proyecto en Android Studio

```
app/
  build.gradle.kts        — dependencias, incluye WireGuard
  AndroidManifest.xml     — permisos VPN + ForegroundService
  MainActivity.kt         — pantalla, interruptor, permiso
  AhorroVpnService.kt     — el VpnService, crea el TUN
  TunnelManager.kt        — conecta WireGuard con tu servidor
```

Lenguaje: Kotlin. Android Studio es gratis, no necesitas tarjeta.
Para instalar la APK en tu teléfono no necesitas Play Store,
la instalas directo desde el APK.

## Permisos que necesita

- `android.permission.INTERNET`
- `android.permission.FOREGROUND_SERVICE`
- `android.permission.BIND_VPN_SERVICE` (en el servicio)
- `android.permission.ACCESS_NETWORK_STATE`

Sin `BIND_VPN_SERVICE` Android no te deja crear el túnel.

## Servidor

El servidor es el mismo de android-solo:
- WireGuard en el puerto 51820.
- AdGuard Home para bloquear anuncios en todas las apps.
- Shaping para video.
- Squid para caché.

Tu app se conecta a ese servidor. Un servidor, muchos teléfonos,
cada uno con su clave.

## iPhone, por qué Android primero

En iPhone se hace con `NetworkExtension` y `PacketTunnelProvider`
en Swift, pero Apple te pide cuenta de desarrollador de pago
(99 dólares al año) para instalar la app fuera de la App Store.
En Android lo haces gratis y lo instalas por APK. Por eso
empezamos por Android, como dijiste.
