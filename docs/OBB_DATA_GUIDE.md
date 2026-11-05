# Guía: datos extra del juego (Android/obb y Android/data) hacia el espacio virtual

Directiva Fase B, requisito 2. Estado verificado del motor (grep sobre `engine/`):
**NO existe soporte OBB** — `obb` solo aparece en archivos sin relación con obb.
El guest corre en un proceso del host; si `ContextFixer` no reescribe `getObbDir()`, el juego
buscará sus OBB en el directorio obb DEL HOST. Eso define el procedimiento.

## Requisitos previos

- Juego instalado en el S20 con sus datos (SimCity: `com.ea.game.simcity2011_row` — el nombre
  exacto lo ves en el panel VERTIL, sección APPS EN EL TELÉFONO).
- Clonar la app PRIMERO (botón Clonar), sin desinstalar el juego original hasta tener las copias.
- adb con depuración USB activada.

## Procedimiento A — OBB (expediente principal)

1. Copiar los OBB del juego original a un ordenador:
   ```
   adb pull /sdcard/Android/obb/com.ea.game.simcity2011_row ./simcity-obb
   ```
   Si da Permission denied en Android 13, comprobar con `adb shell ls /sdcard/Android/obb`;
   en la mayoría de One UI el shell sí puede leer obb.
2. Subirlos al directorio obb DEL HOST VERTIL (el guest resolverá su `getObbDir()` hacia el
   host mientras el motor no fije el suyo propio — esto es lo que hay que VERIFICAR):
   ```
   adb shell mkdir -p /sdcard/Android/obb/com.vertil.virtualapp/com.ea.game.simcity2011_row
   adb push ./simcity-obb/. /sdcard/Android/obb/com.vertil.virtualapp/com.ea.game.simcity2011_row/
   ```
3. Lanzar el clon y pulsar "Capturar logcat". Buscar líneas del juego mencionando `.obb`.
4. Si el juego sigue pidiendo "descargar datos adicionales": el guest NO está resolviendo
   `getObbDir()` al host → punto real de Fase B: añadir el mapeo OBB en el motor
   (`ContextFixer`/`VEnvironment`) como fix ADAPTED, con su commit propio y el logcat adjunto.

## Procedimiento B — Android/data (partidas guardadas)

En Android 11+ ni el host ni adb pueden leer `/sdcard/Android/data/<juego>` en general
(almacenamiento restringido; One UI 5 suele bloquearlo incluso a shell). Vías reales:

| Vía | Cuándo sirve |
|---|---|
| Guardado en la nube del propio juego (EA / Google Play Juegos) | Recomendada: iniciar sesión dentro del clon y restaurar |
| Root (`su -c cp -r /sdcard/Android/data/<juego> ...`) y luego `adb push` | Solo si el S20 está rooteado |
| `adb backup` | Deprecado desde Android 12 para la mayoría de apps — no fiable |
| Copia previa en un dispositivo ≤ Android 10 | Si existe |

Dentro del clon, el juego escribirá sus datos en el almacenamiento virtual del motor
(`VEnvironment`: `<host>/virtual/data/user/0/<pkg>`). No hay que copiar nada a mano para que
el juego FUNCIONE; lo que se copia es el progreso previo del juego original.

## Qué NO hacer

- No desinstalar el juego original antes de extraer obb/datos.
- No meter OBB en el almacenamiento interno del host "a ojo": el motor hoy no mapea un
  directorio obb interno del guest (ese soporte se añadiría en Fase B si A falla).
