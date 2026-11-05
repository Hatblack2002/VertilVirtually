# VERTIL — Virtualización de apps sobre el motor recuperado de VirtualApp2022

**Base:** import de [bbajie/VirtualApp2022](https://github.com/bbajie/VirtualApp2022) @ `4dd421b`
(commit `6760ba8`, rama `vertil/main`). VERTIL NO es un motor nuevo: es la modernización del
motor original (paquete `com.lody.virtual` conservado).

> **Nota de recuperación:** el entorno de trabajo se recicló el 2026-11-06 y se perdió el
> estado local anterior (incluido el import `ed39006`). Todo fue reconstruido desde el remoto
> y el upstream; el contenido del import es idéntico (1891 archivos, mismo commit base).

## Módulos

| Módulo | Papel | Estado |
|---|---|---|
| `engine/` | Motor (ex `lib/`): servidor virtual `:x`, hooks de binder, stubs `:p0..:p49`, mirror/* | RECOVERED + adaptaciones FASE A |
| `app/` | Host VERTIL de prueba (panel Fase B: clonar/lanzar/procesos/logcat) | NEW — sin compilar |
| `controlapp/` | App de control (distinción motor vs juego) | NEW — sin compilar |

## Compilar (máquina del propietario — este entorno no puede por espacio)

Requisitos: JDK 17+ (o 21), Android SDK 34, NDK 27.0.12077973, CMake/ndk-build via SDK.

```
./gradlew :engine:assembleRelease :app:assembleDebug :controlapp:assembleDebug
```

Repos: solo Google + Maven Central (jcenter/jitpack eliminados — `DEPENDENCY_PROVENANCE`).
Gate A: builds verdes + APK que arranque `VirtualCore.startup` sin crash en API 33.

## Documentación

| Doc | Contenido |
|---|---|
| [docs/FASE_B_TEST_DIRECTIVE.md](docs/FASE_B_TEST_DIRECTIVE.md) | **Directiva vigente**: app real (SimCity) en S20, requisitos 1-6, gate 30 min |
| [docs/OBB_DATA_GUIDE.md](docs/OBB_DATA_GUIDE.md) | Cómo copiar Android/obb y Android/data al espacio virtual |
| docs/REMAINING_WORK.md | Fases A-E con gates |
| docs/SOURCE_RECOVERY_MAP.md | Subsistema → origen → clasificación (REGENERAR tras reciclaje) |
| docs/ARCHITECTURE_RECONSTRUCTION.md | Arquitectura objetivo y decisiones (REGENERAR) |
| docs/DEPENDENCY_PROVENANCE.md | Procedencia de dependencias (REGENERAR) |

Los documentos marcados REGENERAR se perdieron con el reciclaje del entorno y se restaurarán
desde el contexto de la auditoría (contenido conocido; se re-verificará contra este árbol).

## Estado honesto

- FASE A pasos 1-4 y 7 ejecutados (import, eliminaciones, renombrado, AHB, exposed fuera).
- FASE A paso 5 (build moderno) COMMITADO pero **no compilado aquí** (sin espacio para deps).
- FASE A paso 6 (CMake + despacho Dobby en `IOUniformer.cpp:77`) **PENDIENTE** — hoy el
  build nativo usa el Android.mk original (ndk-build) con APP_PLATFORM 24.
- Gate A y todo el resto: **PENDIENTE de prueba real**. Nada se declara resuelto sin salida.
- Push a GitHub **PENDIENTE** (token 401). Bundle y tarball de seguridad en
  `../artefactos/` (fuera del repo).
