# REMAINING_WORK.md — Trabajo restante con gates de aceptación

Actualizado 2026-11-06 (directiva Fase B del propietario + reciclaje del entorno). Cada fase
tiene gate verificable; **ninguna fase se da por terminada sin su gate**. Nada se declara
resuelto sin salida real pegada.

## Fase A — Resurrección del build (ESTADO: pasos ejecutados, gates pendientes)

Hecho (commits en vertil/main):
1. `6760ba8` import VirtualApp2022 @ 4dd421b (rama huérfana; verificado: 1891 archivos, 0 keystores en historial).
2. `a53e8b1` eliminaciones per IMPLEMENTATION_REMOVAL_REPORT §2.3 (launcher/, app/ VirtualXposed, app-plugin/, local.properties, mapping.txt — 1224 archivos, −169.706 líneas).
3. `c175357` renombrado lib/ → engine/ (paquete com.lody.virtual intacto).
4. `c96ddaa` free_reflection → AndroidHiddenApiBypass 6.2 en `VirtualCore.startup()`.
5. `fcad00a` exposed/Xposed-in-guest fuera de `VClientImpl`.
6. `41cf6a9` build moderno: AGP 8.5.2, Gradle 8.9, compileSdk 34/minSdk 24/targetSdk 28, ndk-build reactivado (APP_PLATFORM 24), repos Google+Central.

Pendiente Fase A:
- **Gate A**: `./gradlew :engine:assembleRelease :app:assembleDebug` verdes desde checkout
  limpio + APK que arranque `VirtualCore.startup` sin crash en emulador API 33 — en máquina
  del propietario (este entorno no puede compilar: sin espacio para deps Gradle).
- Paso 6 completo: migración Android.mk → CMake y despacho `hook_function()` de
  `IOUniformer.cpp:77` → Dobby (hoy compila el Android.mk original).
- Push de `vertil/main`, tag y rama recovery a GitHub (token 401 — pendiente token nuevo).

## Fase B — Prueba con app real y pesada (REDIRIGIDA por directiva del propietario)

La prueba principal NO es una app mínima: es un juego real (SimCity BuildIt) instalado en el
S20 del propietario. Requisitos 1-6 en `docs/FASE_B_TEST_DIRECTIVE.md`:
lista+clonar (sourceDir+splits), guía obb/data, lanzar+estado :p0..:p49, logcat filtrado
antes de corregir (1 commit por fix siguiendo ANDROID13_MIGRATION_REPORT), app de control,
y **gate B: 30 minutos con el juego abierto sin crash en el S20**.

Pendiente Fase B: compilación del host, prueba del controlapp clonado, clon de SimCity,
mapeo OBB en el motor si el Procedimiento A falla, fixes Android 13 que vayan apareciendo
con logcat como evidencia.

## Fase C — Identidad e interfaz VERTIL (sin cambios)

1. Host final (Compose) consumiendo SOLO la API pública del motor; dashboard, instalador,
   gestor de instancias, consola de logs.
2. applicationId `com.vertil.virtualapp`, tema Cyber-Industrial.
3. Prohibido en :app: internals del motor, persistencia propia, dependencias de red.
Gate C: flujo completo instalar→ejecutar→detener→eliminar en dispositivo.

## Fase D — Validación de independencia

Build offline (solo Google+Central ya garantizado por settings.gradle FAIL_ON_PROJECT_REPOS),
inspección de APK (0 Firebase/Gemini), NOTICE de licencias, 30 min de estabilidad.
Gate D: informe firmado con salidas pegadas.

## Fase E — Decisiones diferidas (requieren autorización)

- Xposed-in-guest (LSPlant, LGPL-3.0) — BLOCKED hoy (exposed ya retirado, commit `fcad00a`).
- Soporte 34/35/36 con fixes BBR (ArtMethod offsets, ServiceConnection API 37).
- Distribución: sideload (targetSdk 28 por diseño del motor; incompatible con Play).

## Riesgos

| Riesgo | Mitigación |
|---|---|
| Sin compilación verificable en el entorno de trabajo | Compila el propietario; bundles+tarballs en artefactos/ para no perder estado |
| El parseo de clúster usa el PackageParser de plataforma (eliminado en API 34) | S20 = API 33 OK; para 34+ portar PackageParserFix de BlackBoxReborn (Fase B/E) |
| Regresiones al subir AGP (merger con ~50 stubs) | Gate A en checkout limpio; `tools:node` explícito si el merger se queja |

## Regla de oro (sin cambios)

Antes de escribir una clase nueva: buscar su equivalente en `engine/` y en los fixes de
BlackBoxReborn. Solo si no existe → NEW con justificación escrita. Antes de declarar
IMPLEMENTED: gate ejecutado con salida real.
