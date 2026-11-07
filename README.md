# Switch2Pad (prototipo, SIN PROBAR)

App de Android (Kotlin, sin dependencias) que se conecta por BLE a un Pro Controller 2
y muestra botones y sticks en pantalla. Basada en la documentación del protocolo de
bit-axis-labs y BlueRetro.

## Cómo compilar
1. Abre la carpeta `sw2pad` en Android Studio (Koala o más nuevo) y deja que sincronice Gradle.
2. Conecta tu móvil (depuración USB) y pulsa Run.

## Antes de que funcione: 3 cosas que faltan
1. **PUBLIC_KEY y CHALLENGE** en `Protocol.kt` (16 bytes cada una). La documentación dice que
   están en el firmware de BlueRetro (github.com/darthcloud/BlueRetro) pero no los publica.
   Mientras valgan cero, la app se niega a emparejar.
2. **Handles**: la documentación habla de handles (0x000A, 0x0014, 0x001A), no de UUIDs.
   La app los busca con `instanceId`. Si no coinciden, la app imprime un volcado de todas las
   características: ajusta las constantes `H_*`.
3. **Comando de funciones (0x0C)**: el formato exacto es una suposición mía; está marcado en
   `Protocol.featureCommands`. Si el emparejamiento termina pero no llegan datos, empieza por ahí.

## Pendiente
- Calibración de sticks (leer la SPI flash); ahora se usa neutro 2048 y recorrido 1610.
- Vibración y LEDs.
- Exponerlo como gamepad para otros juegos: Android no permite inyectar un gamepad virtual
  sin root/Shizuku (uinput). Alternativas: mapear a toques en pantalla (servicio de accesibilidad)
  o usarlo solo dentro de tu propia app.

## Alternativa rápida
La misma documentación menciona una app web con Web Bluetooth que funciona en Chrome para
Android, útil para comprobar que tu mando se comporta como se describe antes de depurar esta app.

## Obtener el APK sin Android Studio (GitHub Actions)
1. Crea un repositorio en github.com y sube el contenido de esta carpeta (incluida `.github`).
2. Edita `app/src/main/java/com/example/sw2pad/Protocol.kt` y rellena PUBLIC_KEY y CHALLENGE.
3. Pestaña **Actions** > "Build APK" > espera ~3-5 min > descarga el artefacto `Switch2Pad-APK`
   (dentro está `app-debug.apk`).
4. Cópialo al móvil e instálalo (permite "instalar apps desconocidas").
