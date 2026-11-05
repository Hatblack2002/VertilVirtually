# VERTIL Virtualization Engine (Android 13 · ARM64 · Sin Root)

Motor de virtualización y aislamiento de aplicaciones en espacio de usuario sin privilegios root, diseñado para Android 13 (API 33) y arquitectura ARM64, con arquitectura modular por capas, registro de paquetes virtuales, gestores de ciclo de vida de componentes y un panel de control interactivo en Jetpack Compose.

---

### User Review & Critical Decisions

> [!IMPORTANT]
> **Decisiones confirmadas**:
> - **Identidad del producto**: VERTIL Virtualization Engine (`com.vertil.virtualapp`), versión release sin sufijo `.debug`.
> - **Arquitectura modular**: Subpaquetes modulares desacoplados con capa de abstracción limpia para ART y JNI (Hook Adapter Interface).
> - **Plataforma y ABI objetivo**: Android 13 (API 33), ARM64-v8a, sin root, dispositivo de referencia Samsung Galaxy S20 FE 5G (SM-G781U).
> - **Metodología por fases**: Avance estricto por fases (Fase 0 a Fase 10) con criterios de aceptación y gates reproducibles.

---

### 1. Overview & Core Concept

- **Qué hace**: Ejecuta y aísla aplicaciones Android (invitados / guests) en entornos virtuales aislados dentro del proceso del host sin requerir root. Redirige llamadas a servicios del sistema (`ActivityManager`, `PackageManager`, `StorageManager`) mediante proxies y hooks para ofrecer entornos multicolección o multi-instancia con identidades y almacenamiento desacoplados.
- **Público objetivo**: Desarrolladores, investigadores y usuarios avanzados que requieren aislamiento de aplicaciones, múltiples cuentas independientes y análisis controlado de paquetes.
- **Valor diferencial**: Arquitectura modular con contratos explícitos entre el runtime virtual y la interfaz de usuario, compatibilidad nativa con Android 13 (Scoped Storage, PendingIntent mutability, restricciones de servicios en segundo plano) y panel de diagnóstico en tiempo real.

---

### 2. User Experience & Visual Design

#### Flujos principales del Host
1. **Dashboard & Monitor del Motor**:
   - Visualización del estado del motor VERTIL (inactivo, inicializado, ejecutando).
   - Métricas de telemetría: instancias activas, uso de memoria, estado del cargador ARM64 y backend de hooks.
   - Acceso rápido a las instancias creadas con indicador de estado (Detenido / Ejecutando / En pausa).
2. **Instalador de Paquetes Invitados**:
   - Selector de APKs (vía Photo/Document Picker o inspección de aplicaciones del sistema).
   - Extracción de manifiesto virtual: paquete, versión, actividades principales, permisos declarados y ABI nativas.
   - Creación de contenedor virtual asignando un `instanceId` e inicializando el directorio de almacenamiento aislado.
3. **Administrador de Instancias**:
   - Gestión granular: Iniciar, Forzar detención, Reiniciar proceso, Borrar caché, Restablecer identidad y Eliminar instancia.
   - Explorador de archivos virtuales del invitado (`data/data/<pkg>` y `storage/emulated/0/Android/data/<pkg>`).
4. **Consola de Diagnóstico & Registro de Eventos**:
   - Stream de logs categorizados por subsistema (`VirtualActivityManager`, `VirtualPackageManager`, `StorageLayer`, `HookAdapter`).
   - Monitor de llamadas interceptadas e inspección de errores de ciclo de vida.
5. **Configuración de Permisos del Sistema**:
   - Accesos directos a ajustes del sistema de Android 13: Notificaciones en segundo plano, optimización de batería y permisos de ejecución.

#### Identidad Visual & Tema
- **Estilo**: Consola técnica de precisión industrial (Cyber-Industrial High-Precision).
- **Paleta de Colores**:
  - Fondo primario: Negro obsidiana profundo (`#0D1117`).
  - Superficies y contenedores: Gris pizarra grafito (`#161B22` / `#21262D`).
  - Acento de estado del motor: Cian eléctrico (`#00E5FF`).
  - Alertas / Hooking: Ámbar brillante (`#FFB300`).
  - Estado activo / Éxito: Verde esmeralda (`#00E676`).
  - Error / Fallo crítico: Rojo carmesí (`#FF5252`).
- **Tipografía**: Fuentes monoespaciadas para telemetría, identificadores de paquetes y logs de diagnóstico; tipografía sans-serif limpia con pesos definidos para títulos y navegación.
- **Ergonomía**: Total compatibilidad con insets de pantalla completa (`WindowInsets.safeDrawing`), tarjetas con elevación tonal y respuesta háptica en acciones críticas.

---

### 3. Key Product Decisions & Trade-Offs

- **Aislamiento de la Interfaz vs. Runtime**:
  - *Decisión*: La UI (Jetpack Compose) interactúa exclusivamente con la capa de fachada `VirtualizationAPI` a través de un `StateFlow` reactivo, sin acceder jamás a proxies internos ni estructuras nativas.
  - *Por qué*: Mantiene el desacoplamiento total y permite probar cada subsistema de forma independiente.
- **Abstracción del backend de Hooking**:
  - *Decisión*: Se define una interfaz agnóstica `HookAdapter` (`installHook`, `removeHook`, `callOriginal`, `isSupported`) con implementaciones desacopladas (LSPlant para Java/ART y Dobby para funciones nativas C/C++).
  - *Por qué*: Evita que el motor dependa rígidamente de una biblioteca concreta o falle catastróficamente si una versión del kernel o de ART bloquea una técnica específica.
- **Manejo de Almacenamiento en Android 13**:
  - *Decisión*: Rutas de datos virtuales redirigidas a espacios de almacenamiento privado del host (`context.getExternalFilesDir("virtual/storage/...")` y `context.filesDir/virtual/data/...`).
  - *Por qué*: Cumple estrictamente con Scoped Storage de Android 13 sin requerir permisos peligrosos ni accesos directos al almacenamiento raíz.

---

### 4. Technical Architecture & Data Strategy

```text
┌────────────────────────────────────────────────────────────────────────┐
│                   VERTIL HOST APPLICATION (UI & UX)                    │
│   Dashboard  │  Guest Installer  │  Instance Manager  │  Diagnostics   │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│                          VIRTUALIZATION API                            │
│  GuestLifecycle │ GuestPackageRegistry │ GuestIdentity │ Environment  │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│                           VIRTUAL RUNTIME                              │
│ ┌───────────────────────────┐         ┌──────────────────────────────┐ │
│ │  VirtualActivityManager   │         │    VirtualPackageManager    │ │
│ ├───────────────────────────┤         ├──────────────────────────────┤ │
│ │   VirtualServiceManager   │         │    VirtualProviderManager    │ │
│ └───────────────────────────┘         └──────────────────────────────┘ │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│                    COMPATIBILITY & FRAMEWORK LAYER                     │
│  API 26–33 Adapters │ Reflection Bypass │ PendingIntent & Insets Fixes │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│                   NATIVE RUNTIME & HOOK ADAPTER                        │
│     JNI Bridge (C++)    │   ARM64 Loader   │  HookAdapter Interface   │
│                         │                  ├─ ART Backend (LSPlant)    │
│                         │                  └─ Native Backend (Dobby)   │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│                           GUEST DATA LAYER                             │
│ Virtual Filesystem │ Guest Storage Metadata │ App Isolation Containers │
└────────────────────────────────────────────────────────────────────────┘
```

#### Modelo de Datos de Virtualización
- **`GuestPackageRecord`**: Identificador de paquete, versión, ruta del APK base, firmas extraídas del invitado, lista de componentes registrados (Activities, Services, Providers).
- **`GuestInstanceRecord`**: ID único de instancia (`instance_01`), paquete asociado, estado de ejecución (`STOPPED`, `STARTING`, `RUNNING`, `CRASHED`), PID virtual, timestamp de creación y tamaño de datos en disco.
- **`VirtualEngineState`**: Estado global del subsistema nativo, arquitectura detectada (ARM64), contador de métodos interceptados y buffer circular de diagnósticos.

---

### 5. Plan de Ejecución por Fases (Milestones)

1. **Fase 0 & 1 — Modernización de Build & Identidad VERTIL**:
   - Configuración de `applicationId: "com.vertil.virtualapp"` en Gradle.
   - Sincronización de `metadata.json` y `res/values/strings.xml` con "VERTIL Virtualization Engine".
   - Establecimiento de la estructura modular y compatibilidad Android 13 (API 33).
2. **Fase 2 — Virtual Package Registry & Manifest Parser**:
   - Implementación del parser de APKs para leer metadatos, componentes declarados y recursos sin instalación en el sistema operativo.
   - Base de datos local (Room) para persistir paquetes registrados e instancias creadas.
3. **Fase 3 & 4 — Virtual Component Managers & Capa de Compatibilidad Android 13**:
   - `VirtualActivityManager`: Lanzamiento y ciclo de vida de actividades virtuales usando contenedores Stub.
   - `VirtualPackageManager`: Interceptación y resolución de intents virtuales.
   - `VirtualServiceManager` y `VirtualProviderManager`: Manejo seguro de servicios y proveedores en segundo plano.
   - Adaptadores de compatibilidad API 26–33 (manejo de `FLAG_IMMUTABLE` en `PendingIntent`, exportación explícita de componentes).
4. **Fase 5 & 6 — Capa Nativa, Abstracción de Hooking y Almacenamiento Aislado**:
   - Contratos JNI y `HookAdapter` para interceptación ART y funciones de bajo nivel.
   - `GuestStorageManager`: Resolución y aislamiento de rutas `data/data` y almacenamiento externo simulado.
5. **Fase 7 & 8 — Dashboard Host en Jetpack Compose & UX de Producción**:
   - Panel de control interactivo con métricas en vivo, gestor de instancias, consola de telemetría y diagnósticos.
   - Verificación y compilación del proyecto con `compile_applet`.
