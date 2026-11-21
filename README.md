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
| `core:diagnostics` | Registro técnico local en Kotlin puro: eventos con módulo, versión, operación, correlation ID, código y duración, y estado de sincronización por módulo |
| `core:session` | Almacén de sesión por backend: refresh token cifrado con AES-GCM y una llave del Android Keystore distinta por backend |
| `integration:velora-contract` | Cliente de Velora API generado del contrato fijado (solo las operaciones que usa el companion) y `VeloraSession` (login, renovación serializada y cierre); lo usa `app-phone` |
| `core:data-layer` | Transporte sobre la Wear OS Data Layer (`CapabilityClient` + `MessageClient`), servicio receptor y `deviceId` seudónimo |

Ambas apps comparten `applicationId` (`mx.watchcompanion`) y firma, porque la Data Layer solo comunica apps con el mismo id y certificado.

## Sesiones de los backends

Solo `app-phone` inicia sesión en Velora (y, desde la Fase 2, en Nexora), con sesiones propias e independientes de las apps de cada producto (ADR-013):

- El access token vive solo en memoria; el refresh token se guarda cifrado (`core:session`). La contraseña nunca se guarda.
- Las renovaciones se serializan: en Velora el refresh token es de un solo uso y reutilizarlo revoca toda la sesión.
- La versión release solo acepta servidores `https://`. En debug se puede usar `http://10.0.2.2` o `http://localhost` contra una API local; el campo «Servidor» solo aparece en debug.
- El cifrado real con Keystore se prueba en dispositivo o emulador: `./gradlew :core:session:connectedDebugAndroidTest`.

## Diagnóstico

`app-phone` guarda un registro técnico local (00 §8, 07 §4) y lo muestra en **Inicio → Diagnóstico**, junto con el estado de sincronización de cada módulo:

- Cada evento tiene solo módulo, versión, operación, correlation ID, resultado, código y duración. Ningún campo admite texto libre: un token, un correo, unas coordenadas o un importe no caben en ninguno, y lo que no cumple el formato se guarda como `INVALID_CODE`.
- Las solicitudes a Velora llevan el correlation ID como `X-Request-Id`: con él se encuentra la misma solicitud en los logs de Velora.
- Se guardan los últimos 200 eventos de los últimos 7 días, en `noBackupFilesDir` (no sale del teléfono). «Borrar registro» lo vacía.
- El reloj no tiene registro propio en la Fase 0; sus problemas de enlace se ven desde el teléfono.

## Contratos de los backends

`contract/<backend>/openapi-v1.yaml` es una copia del contrato del backend fijada al commit de `contract/<backend>/REF` (ADR-009). El cliente se genera de esa copia en cada build: si el contrato cambia y el código no, no compila. Así el build no depende de la red y cada cambio de contrato es un PR explícito.

```bash
scripts/update-contract.sh velora <sha-de-velora-api>   # fija otro commit y descarga su contrato
scripts/update-contract.sh --check velora               # comprueba la copia (lo corre el CI)
```

Solo se generan las operaciones que usa el companion (lista `companionOperations` en `integration/velora-contract/build.gradle.kts`) y sus modelos. Agregar una operación es parte de la tarjeta que la necesita.

## Permisos

Cada módulo pide sus permisos **solo cuando el usuario lo activa**. Si se revoca un permiso desde Ajustes, el módulo muestra qué falta al volver a la app, sin cerrarse. Al desactivar un módulo, sus permisos se revocan cuando termina el proceso (`revokeSelfPermissionsOnKill`), salvo los que siga usando otro módulo activo.

| Permiso | App | Módulo | Para qué | Desde |
|---|---|---|---|---|
| `android.permission.health.READ_HEART_RATE` | reloj | Velora | Frecuencia cardíaca en vivo y alertas por zona (WC-30, WC-31) | Fase 1 |
| `android.permission.INTERNET` (normal, sin diálogo) | teléfono | Velora | Llamar a Velora API por HTTPS (WC-22) | Fase 0 |
| `mx.watchcompanion.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | ambas | — | Permiso interno de firma que agrega AndroidX (`core-ktx`); no muestra diálogo | Fase 0 |

El reloj no declara `INTERNET`: no llama a los backends (ADR-013). La ubicación (modo B, WC-53), las notificaciones de Nexora (WC-41), Guardian y Smart Hub agregan sus permisos a esta tabla en la tarjeta que los necesita.

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
