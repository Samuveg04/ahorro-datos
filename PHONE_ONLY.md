# Crear tu app VPN solo con el teléfono, sin Android Studio ni PC

Sam, tienes razón, si solo trabajas con el teléfono no puedes
abrir Android Studio. Pero no lo necesitas. La compilación se
hace en la nube, gratis, y tú solo subes el código desde el teléfono.

## La idea

Tú no compilas en el teléfono. GitHub compila por ti.

1. Tú subes el código desde el teléfono a GitHub.
2. GitHub Actions, que es un robot gratis en la nube, agarra ese
   código, instala Android SDK y Gradle, compila la APK.
3. Tú descargas la APK desde GitHub directo al teléfono y la instalas.

No necesitas PC, no necesitas Android Studio, no necesitas tarjeta.
GitHub es gratis.

## Paso a paso, solo con el teléfono

### 1. Crea cuenta en GitHub (si no la tienes)
- Abre Chrome en el teléfono, github.com, crear cuenta gratis.
- Instala la app GitHub desde Play Store, es más cómoda para subir archivos.

### 2. Crea el repositorio
- En GitHub, New repository, nombre `ahorro-vpn`, público, con README.
- Sube los archivos que te di: `app/`, `build.gradle.kts`, y
  `.github/workflows/build-apk.yml` que está en esta carpeta.
- Puedes subirlos desde la web de GitHub en Chrome: Add file > Upload files.
- O desde la app GitHub.

Necesitas también estos archivos base que Android Studio genera
solo, y que te dejo listados aquí para que no te falten:
- `gradlew`, `gradlew.bat`, `settings.gradle.kts`, `gradle/wrapper/`
- Si no los tienes, en GitHub crea el repo desde la plantilla
  "Android" y luego sube encima mis archivos `app/src/...`.

Truco más fácil: crea el proyecto en github.com/new con la
plantilla de Android que te da GitHub, y luego reemplaza los
archivos `MainActivity.kt`, `AhorroVpnService.kt` y
`AndroidManifest.xml` con los míos, desde el editor web de GitHub.

### 3. Dispara la compilación
- En GitHub, pestaña Actions, verás el workflow "Build APK".
- Toca Run workflow, o haz un push cualquiera y corre solo.
- Espera 3-5 minutos. El robot compila en la nube.

### 4. Descarga la APK al teléfono
- En Actions, abre la corrida que terminó en verde.
- Abajo, en Artifacts, toca `ahorro-vpn-apk`.
- Se descarga un ZIP al teléfono. Ábrelo, saca la APK.
- Toca la APK para instalar. Android te pedirá permitir
  "Instalar apps desconocidas" para Chrome / Archivos. Acepta.
- Listo, tienes tu app VPN instalada.

### 5. Prueba
- Abre la app Ahorro VPN, toca Prender VPN, acepta el permiso
  del sistema.
- Abre YouTube, Instagram, todo normal. Todo pasa por tu servidor.

## Alternativa aún más simple, sin compilar nada

Si no quieres compilar todavía, no necesitas tu propia app
para empezar. Puedes lanzar tu servicio así:

- Usa la app WireGuard, que ya existe y es gratis.
- Tu servidor es el tuyo, con tu IP, tus claves, tu AdGuard y
  tu shaping. Eso YA es tu VPN.
- Generas un código QR con tu configuración y se lo das a la
  gente. Ellos escanean el QR en WireGuard y ya están dentro
  de tu VPN, con tu ahorro.

Eso es un VPN propio sin escribir una app. Cuando quieras tu
app con tu nombre y tu logo, compilas la de arriba por GitHub
y la reemplazas. Pero el servicio ya funciona desde el primer día
con WireGuard.

## Qué NO funciona solo con el teléfono

- AIDE o apps que dicen compilar Android en el teléfono: son
  lentas, se quedan sin memoria con WireGuard, y se caen.
- Termux con Gradle: se puede, pero necesitas 8 GB libres y
  mucha paciencia. No lo recomiendo.
- GitHub Actions es el camino, porque el trabajo pesado lo hace
  la nube, no tu teléfono.
