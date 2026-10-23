# AI Box Launcher

Launcher limpio para CarPlay AI Box (Android). Pantalla de inicio oscura, reloj grande, cuadrícula de apps y **apertura automática** de la app que elijas.

## Interfaz (estilo tablero)
- **Barra lateral:** Inicio, Mapas, Música y Teléfono.
- **Reloj circular** con anillo de segundos.
- **Tarjeta de música** con título, artista, carátula y botones anterior / play-pausa / siguiente.
- **Tarjeta de fecha** y **5 accesos favoritos redondos**.
- **Botones grandes:** CarPlay, Android Auto (se detectan por nombre entre las apps instaladas), Apps (cajón con todas) y Tema (4 colores de acento).
- Mantén pulsada una app: abrir al encender / agregar a favoritas.

## Clima
Usa Open-Meteo (gratis, sin clave) para **Ambato**, se actualiza cada 15 minutos y necesita internet en el AI Box. Para otra ciudad cambia `WEATHER_CITY`, `WEATHER_LAT` y `WEATHER_LON` en `MainActivity.kt`.

## Obtener el APK sin Android Studio (GitHub Actions)
1. Crea una cuenta gratis en github.com y un repositorio nuevo.
2. Sube el contenido de esta carpeta (incluida `.github/workflows/build-apk.yml`).
3. Entra a la pestaña **Actions**, abre "Build APK" y espera ~5 min.
4. Descarga **AIBoxLauncher-apk** (dentro hay `app-debug.apk`).

## Música en la tarjeta
La primera vez toca **"Dar acceso a notificaciones"** y activa *AI Box Launcher* en la lista. Sin ese permiso Android no deja leer qué suena; la tarjeta seguirá abriendo tu app de música.

## Cómo funciona la carga automática
1. El launcher se declara como pantalla de **Inicio (HOME)**, así Android lo abre solo al encender.
2. Por defecto, **CarPlay** se abre solo 5 segundos después de encender (si se detecta). Puedes elegir otra app con pulsación larga. Hay botón **Cancelar**.
3. Para volver al Android del box, sal de CarPlay y regresarás al launcher.
3. Un `BootReceiver` refuerza el arranque en equipos que lo permiten.

## Compilar
1. Abre la carpeta en **Android Studio** (Hedgehog o más nuevo).
2. `Build > Build APK(s)` (o `./gradlew assembleDebug` si agregas el wrapper).
3. El APK queda en `app/build/outputs/apk/`.

## Instalar en el AI Box
- Copia el APK por USB / `adb install app-debug.apk`.
- Pulsa el botón Inicio y elige **AI Box Launcher > Siempre**.
- Si el equipo ofrece "Launcher predeterminado" en Ajustes > Aplicaciones, selecciónalo ahí.

## Personalizar
- Colores: bloque "Paleta" al inicio de `MainActivity.kt`.
- Segundos de espera: `AUTO_DELAY_SECONDS`.
- Vista previa del diseño: abre `preview.html` en un navegador.

## Notas
- Android 10+ restringe abrir apps desde segundo plano; por eso el método principal es ser el launcher por defecto.
- Algunos firmwares de AI Box traen un launcher propio bloqueado; en ese caso el launcher se usa abriéndolo como app o con ADB: `adb shell cmd package set-home-activity com.aibox.launcher/.MainActivity`.
- El código no se compiló en este entorno (sin Android SDK); si Gradle marca algún error de versión, ajusta las versiones en `build.gradle.kts`.
