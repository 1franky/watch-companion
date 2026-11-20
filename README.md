# watch-companion

Aplicación Wear OS modular para **Samsung Galaxy Watch Ultra** con puente en **Galaxy S26 Ultra**, que conecta los productos existentes **Velora** (ciclismo/MTB) y **Nexora** (finanzas personales) y añade dos módulos locales: **Watch Guardian** (seguridad del teléfono) y **Smart Hub** (hogar).

> Estado: **Fase 0 — infraestructura.**
>
> Backlog: tablero en Notion **Backlog Watch Companion** (tarjetas `WC-n`, enlazadas a los hallazgos de `docs/09`).

## Documentación

Toda la especificación vive en [`docs/`](docs/README.md):

| Archivo | Contenido |
|---|---|
| [`00-architecture.md`](docs/00-architecture.md) | Arquitectura general, límites y contratos |
| [`01-velora-watch.md`](docs/01-velora-watch.md) | Requisitos de ciclismo |
| [`02-activity-recording-and-reconciliation.md`](docs/02-activity-recording-and-reconciliation.md) | iGPSPORT + Watch + Strava + Velora |
| [`03-nexora-watch.md`](docs/03-nexora-watch.md) | Módulo financiero |
| [`04-watch-guardian.md`](docs/04-watch-guardian.md) | Seguridad del teléfono |
| [`05-smart-hub.md`](docs/05-smart-hub.md) | SmartThings y TTLock |
| [`06-roadmap-and-testing.md`](docs/06-roadmap-and-testing.md) | Fases, criterios y pruebas |
| [`07-security-and-privacy.md`](docs/07-security-and-privacy.md) | Seguridad y privacidad |
| [`08-architecture-decisions.md`](docs/08-architecture-decisions.md) | ADR |
| [`09-revision-y-auditoria.md`](docs/09-revision-y-auditoria.md) | Revisión y desviaciones frente a los repos reales |

**Lee primero `09-revision-y-auditoria.md`**: registra dónde la especificación no coincide con el código actual de Velora y Nexora y qué decisiones están pendientes.

## Módulos

| Módulo | Qué contiene |
|---|---|
| `app-watch` | App Wear OS para el Galaxy Watch Ultra (Wear OS 6, `minSdk 36`) |
| `app-phone` | App Android en el S26 Ultra: único puente hacia Velora y Nexora (ADR-011, ADR-013), `minSdk 31` |
| `core:phone-communication` | Protocolo Watch ↔ Phone en Kotlin puro: sobre versionado, catálogo, validación y `LinkSession` (ping/pong y estado del enlace) |
| `core:permissions` | Permisos por módulo: qué pide cada uno, activación por el usuario, estado y revocación al desactivar |
| `core:data-layer` | Transporte sobre la Wear OS Data Layer (`CapabilityClient` + `MessageClient`), servicio receptor y `deviceId` seudónimo |

Ambas apps comparten `applicationId` (`mx.watchcompanion`) y firma, porque la Data Layer solo comunica apps con el mismo id y certificado.

## Permisos

Cada módulo pide sus permisos **solo cuando el usuario lo activa**. Si se revoca un permiso desde Ajustes, el módulo muestra qué falta al volver a la app, sin cerrarse. Al desactivar un módulo, sus permisos se revocan cuando termina el proceso (`revokeSelfPermissionsOnKill`), salvo los que siga usando otro módulo activo.

| Permiso | App | Módulo | Para qué | Desde |
|---|---|---|---|---|
| `android.permission.health.READ_HEART_RATE` | reloj | Velora | Frecuencia cardíaca en vivo y alertas por zona (WC-30, WC-31) | Fase 1 |
| `mx.watchcompanion.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | ambas | — | Permiso interno de firma que agrega AndroidX (`core-ktx`); no muestra diálogo | Fase 0 |

`INTERNET` llegará a `app-phone` con el cliente de Velora (WC-21/WC-22); el reloj no lo necesita porque no llama a los backends (ADR-013). La ubicación (modo B, WC-53), las notificaciones de Nexora (WC-41), Guardian y Smart Hub agregan sus permisos a esta tabla en la tarjeta que los necesita.

## Probar el enlace en dispositivos

1. Activa la depuración por ADB en el S26 Ultra y la depuración inalámbrica en el Galaxy Watch Ultra.
2. Instala **las dos apps desde la misma computadora**: en debug, la firma es la llave de depuración local y ambas deben coincidir.
   ```bash
   ./gradlew :app-phone:installDebug :app-watch:installDebug   # con los dos dispositivos conectados por ADB
   ```
3. Abre Watch Companion en ambos: cada uno busca al otro y muestra «Conectado a …» con la latencia, o la causa si no lo encuentra.

## Desarrollo

Requiere JDK 21 y el SDK de Android con la plataforma 37 (`local.properties` con `sdk.dir`).

```bash
./gradlew assembleDebug        # APK de reloj y teléfono
./gradlew testDebugUnitTest :core:phone-communication:test   # pruebas (JVM y Robolectric)
./gradlew lintDebug            # lint: las advertencias fallan el build
```

## Flujo de ramas y versiones

- **`develop`** es la rama por defecto y de integración. Todo trabajo sale de una rama `feature/WC-n-…` (o `docs/…`, `fix/…`) creada desde `develop` y vuelve por PR a `develop`. El CI debe pasar.
- **`main`** solo recibe releases: un PR `develop` → `main` cuando hay funcionalidad lista para liberar. Está protegida: sin push directo (ni para administradores), PR obligatorio y CI en verde.
- **Versionado:** al liberar se sube `versionName`/`versionCode` en `app-watch` y `app-phone` (siempre iguales en ambas apps) dentro del PR de release. Después de fusionar a `main` se etiqueta `vX.Y.Z` ([SemVer](https://semver.org/lang/es/)). La primera entrega (Fase 0 + Fase 1, ADR-012) será `v0.1.0`.
- **Hotfix:** rama `hotfix/…` desde `main`, PR a `main` y, después, `main` se fusiona de vuelta a `develop`.

## Repositorios relacionados

[velora-api](https://github.com/1franky/velora-api) · [velora-android](https://github.com/1franky/velora-android) · [velora-web](https://github.com/1franky/velora-web) · [nexora-api](https://github.com/1franky/nexora-api) · [nexora-android](https://github.com/1franky/nexora-android) · [nexora-web](https://github.com/1franky/nexora-web)

Este repositorio no modifica esos proyectos; las integraciones se hacen mediante PRs acotados en cada uno.

## Idioma

Producto y documentación en español (México).
