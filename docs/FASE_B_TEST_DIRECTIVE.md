# FASE B — Directiva de prueba con app real y pesada (vinculante)

Fuente: instrucciones del propietario, 2026-11-06. Cambia el objetivo de la Fase B: la prueba
principal NO es una app mínima, es una app real y pesada instalada en su teléfono
(un juego como SimCity BuildIt).

## Requisitos de la directiva

1. **Lista de apps instaladas + Clonar**: el host muestra las apps del teléfono y un botón
   "Clonar al espacio virtual" que instala en el motor desde la ruta del APK (`sourceDir`)
   y todos sus splits. Se declara `QUERY_ALL_PACKAGES` (es sideload, sin Play).
   → Implementado en `app/` (commit `a93043f`): `MainActivity` + `AppInstaller`
   (staging propio del host → `VirtualCore.installPackage(dir)`; el motor ya copia splits,
   `VAppManagerService` L196-234).
2. **Datos extra (Android/obb, Android/data)**: ver `docs/OBB_DATA_GUIDE.md` para cómo se
   copian al espacio virtual y qué es experimental.
3. **Botón de lanzar + estado de procesos** `:p0..:p49` → `MainActivity` (Lanzar vía
   `getLaunchIntent`; procesos vía `ActivityManager.getRunningAppProcesses()` filtrando
   `<host>:p*`).
4. **Diagnóstico antes de corregir**: cuando falle, capturar el logcat completo filtrado por
   el paquete del juego y por VERTIL, y comunicar la causa exacta ANTES de corregir.
   Un commit por corrección, siguiendo `docs/ANDROID13_MIGRATION_REPORT.md`.
   → Botón "Capturar logcat" (`LogcatCapture`); paquete objetivo = mantener pulsado un clon.
5. **App de control**: `:controlapp` se clona PRIMERO. Clon OK + juego KO = problema del juego
   (splits/obb/anti-cheat); control KO = problema del motor.
6. **Nada se declara resuelto sin prueba del propietario** en su S20. La prueba final incluye
   **30 minutos con el juego abierto sin crash**.

## Estado honesto (2026-11-06)

| Ítem | Estado |
|---|---|
| Código de los requisitos 1, 3, 4, 5 | COMMITADO (`a93043f`, `45389c4`) — **SIN COMPILAR** (entorno sin espacio para deps Gradle; regla del propietario: no instalar nada pesado) |
| Requisito 2 (obb/data) | DOCUMENTADO (`docs/OBB_DATA_GUIDE.md`) — soporte obb NO existe en el motor (grep verificado) |
| Gate B (S20 + SimCity 30 min) | **PENDIENTE** — no se declara resuelto |
| Push de vertil/main a GitHub | **PENDIENTE** — token fine-grained anterior devolvió 401 Bad credentials |
