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
| [`10-pruebas-de-campo-fase-1.md`](docs/10-pruebas-de-campo-fase-1.md) | Protocolo de las pruebas de campo de la Fase 1 |

**Lee primero `09-revision-y-auditoria.md`**: registra dónde la especificación no coincide con el código actual de Velora y Nexora y qué decisiones están pendientes.

## Módulos

| Módulo | Qué contiene |
|---|---|
| `app-watch` | App Wear OS para el Galaxy Watch Ultra (Wear OS 6, `minSdk 36`) |
| `app-phone` | App Android en el S26 Ultra: único puente hacia Velora y Nexora (WC-ADR-011, WC-ADR-013), `minSdk 31` |
| `core:phone-communication` | Protocolo Watch ↔ Phone en Kotlin puro: sobre versionado, catálogo, validación y `LinkSession` (ping/pong y estado del enlace) |
| `core:permissions` | Permisos por módulo: qué pide cada uno, activación por el usuario, estado y revocación al desactivar |
| `core:diagnostics` | Registro técnico local en Kotlin puro: eventos con módulo, versión, operación, correlation ID, código y duración, y estado de sincronización por módulo |
| `core:session` | Almacén de sesión por backend: refresh token cifrado con AES-GCM y una llave del Android Keystore distinta por backend |
| `integration:velora-contract` | Cliente de Velora API generado del contrato fijado (solo las operaciones que usa el companion) y `VeloraSession` (login, renovación serializada y cierre); lo usa `app-phone` |
| `core:data-layer` | Transporte sobre la Wear OS Data Layer (`CapabilityClient` + `MessageClient`), servicio receptor y `deviceId` seudónimo |

Ambas apps comparten `applicationId` (`mx.watchcompanion`) y firma, porque la Data Layer solo comunica apps con el mismo id y certificado.

## Sesiones de los backends

Solo `app-phone` inicia sesión en Velora y en Nexora (WC-37), con sesiones propias e independientes entre sí y de las apps de cada producto (WC-ADR-013). La pantalla principal tiene una sección por backend y el mismo formulario de login (`BackendLogin`):

- El access token vive solo en memoria; el refresh token se guarda cifrado (`core:session`), **cada backend en su archivo y con su propia llave del Keystore** (`Backend.VELORA`, `Backend.NEXORA`). La contraseña nunca se guarda.
- Las renovaciones se serializan (`VeloraSession`, `NexoraSession`): los dos backends rotan el refresh token en cada uso y revocan el anterior, así que dos llamadas no pueden renovar a la vez.
- Cerrar la sesión de Nexora desde el teléfono la **revoca** en Nexora (`POST /auth/logout`); sin red, se borra del teléfono igual y el token vence solo.
- El contrato de Nexora no marca campos obligatorios: un login que no trae los dos tokens se trata como «Nexora no está disponible», nunca como una sesión a medias.
- Con sesión de Nexora, el reloj muestra en Resumen el disponible, la deuda de tarjetas y los gastos del mes con su hora de actualización (WC-38). No aparecen en modo ambiente y se pueden ocultar; el reloj guarda una copia cifrada que caduca a las 6 h y se borra al cerrar sesión en Nexora (WC-42).
- Desde el reloj se puede capturar un borrador de gasto o ingreso (WC-40). Solo se registra en Nexora al confirmarlo en el teléfono, con la `Idempotency-Key` que generó el reloj. Mientras tanto, el borrador queda cifrado en el teléfono con su propia llave.
- La versión release solo acepta servidores `https://`. En debug se puede usar `http://10.0.2.2` o `http://localhost` contra una API local; el campo «Servidor» solo aparece en debug.
- El cifrado real con Keystore se prueba en dispositivo o emulador: `./gradlew :core:session:connectedDebugAndroidTest`.

## Diagnóstico

`app-phone` guarda un registro técnico local (00 §8, 07 §4) y lo muestra en **Inicio → Diagnóstico**, junto con el estado de sincronización de cada módulo:

- Cada evento tiene solo módulo, versión, operación, correlation ID, resultado, código y duración. Ningún campo admite texto libre: un token, un correo, unas coordenadas o un importe no caben en ninguno, y lo que no cumple el formato se guarda como `INVALID_CODE`.
- Las solicitudes a Velora llevan el correlation ID como `X-Request-Id`: con él se encuentra la misma solicitud en los logs de Velora.
- Se guardan los últimos 200 eventos de los últimos 7 días, en `noBackupFilesDir` (no sale del teléfono). «Borrar registro» lo vacía.
- El reloj no tiene registro propio en la Fase 0; sus problemas de enlace se ven desde el teléfono.

## Reloj: pantalla Resumen

Al abrir la app, el reloj muestra el **Resumen** (01 §5): conexión con el teléfono, modo activo (en el MVP siempre el modo A, iGPSPORT principal) y estado de sincronización con Velora.

- El estado de sincronización lo informa el teléfono en cada `Pong` (`SyncSummary`): sesión cerrada o vencida, nunca sincronizado, sincronizado hace N min o error con su código. Sale de la sesión de `app-phone` y del registro de diagnóstico, así que no lleva datos personales.
- Sin enlace con el teléfono no se muestra ningún estado de sincronización: un dato viejo no se presenta como vigente.
- **Última actividad** (WC-33): el teléfono combina `GET /activities?limit=1` con el estado del conector de Strava (`GET /integrations/strava`) y la informa como *sincronizada* (con fecha y distancia), *sincronizando*, *pendiente* o *error* con su código; siempre con la hora de la consulta. Nunca se simula un éxito. En el modo A no hay `jobId` de importación (las rodadas llegan por Strava), por eso no se usa `GET /imports/{jobId}`.
- El teléfono responde el ping con el último estado conocido y, si tiene más de 2 minutos, consulta a Velora en segundo plano y se lo manda al reloj al terminar.
- Soporta modo ambiente (`AmbientLifecycleObserver`): solo quedan los textos, en gris sobre negro y sin botones.
- El bisel giratorio y la corona desplazan la pantalla; con letra grande todo sigue al alcance desplazando.

## Reloj: métricas en vivo

Desde el Resumen, **Iniciar métricas** abre la sesión de métricas en vivo del modo A (01 §3.1). Solo aparece con el módulo Velora activo y su permiso concedido.

- Es un ejercicio de ciclismo de Health Services `ExerciseClient` con solo frecuencia cardíaca: **sin GPS, sin guardar muestras y sin subir nada**. No depende del teléfono ni de internet.
- Sin lectura válida se muestra `--` con el motivo (buscando pulso, reloj fuera de la muñeca, sensor no disponible), **nunca un 0**.
- Un foreground service de tipo `health` mantiene el proceso vivo con la pantalla apagada o en ambiente. Si la app se cierra, el ejercicio sigue en Health Services y se recupera al abrirla.
- Si otra app (p. ej. Samsung Health) tiene un ejercicio en curso, no se le quita: se avisa.
- Terminar pide confirmación; al terminar se muestra la duración y nada más se conserva.
- **La duración incluye las paradas** (WC-59): el ejercicio no tiene auto-pausa, porque sin GPS el reloj no sabe si la bici está detenida. El BSC100S sí se pausa solo, así que la duración del reloj sale mayor; la que vale es la del BSC100S. Para que coincidan, pausar a mano en el reloj al detenerse.
- Mientras dura la sesión, un ícono en la esfera y en recientes (*Ongoing Activity*, WC-58) vuelve a la app con un toque.
- **Alertas hápticas por zona** (WC-31): tres pulsos cortos al subir de zona y uno largo al bajar. Para no repetirlas cuando la FC oscila en un límite, solo se baja al quedar 3 lpm por debajo y el cambio se confirma tras 5 s en la nueva zona. Usan las zonas guardadas en el reloj: funcionan en modo avión.

## Reloj: grabación de la ruta (modo B)

**Grabar ruta** (en el Resumen o en Métricas) inicia la misma sesión de métricas, con las mismas alertas, pero además enciende el GPS del reloj y guarda su propio track (WC-53). Es el respaldo del BSC100S (01 §3, modo B): **no se sube a Strava**. El reloj no tiene red: al terminar, la grabación viaja al teléfono y el teléfono la sube a Velora como **grabación secundaria** (WC-54, ADR-032 de Velora), que se adjunta sola a la actividad de la salida sin crear otra.

- Pide la **ubicación precisa** solo al tocar «Grabar ruta». Si se niega (o solo se concede la aproximada), lo explica y no graba; **Iniciar métricas** sigue funcionando sin ella.
- Cada grabación tiene un **UUID estable** y vive en `noBackupFilesDir/recordings/<uuid>/`, fuera de las copias de seguridad: un diario `points.csv` (solo-añadir, sincronizado a disco en cada lote) y, al terminar, `track.gpx`.
- **Sobrevive a un cierre de la app:** al volver a abrirla con el ejercicio en curso, sigue la misma grabación. Si el reloj se apagó, al abrir la app la grabación se cierra como **parcial** con los puntos que alcanzaron a guardarse; una línea cortada a media escritura se descarta.
- El **GPX** lleva `ele`, `time` y la FC en `gpxtpx:hr`, que es lo que lee el `GpxParser` de Velora. Cada punto lleva la lectura de FC más cercana (±5 s); lo que falta se omite, nunca se escribe un 0. Sin nombre, modelo ni id de dispositivo.
- Sin auto-pausa por ahora (WC-59): se decidirá con los datos de batería y GPS de WC-57.
- **Del reloj al teléfono:** data item `/wc/recording/<uuid>` con el GPX (00 §5). La Data Layer lo entrega cuando hay enlace; el teléfono verifica el SHA-256, lo guarda y da el acuse, y entonces el reloj borra su copia. Mientras tanto, el Resumen del reloj muestra «Rutas grabadas: N por enviar al teléfono».
- **Del teléfono a Velora:** WorkManager sube cada archivo con red (`POST /secondary-recordings`, idempotente por el UUID y el SHA-256) y consulta su trabajo hasta que Velora la tiene; entonces la borra del teléfono. Sin red o sin sesión espera y reintenta solo; un conflicto o un archivo rechazado se descarta y queda en Diagnóstico. La pantalla principal muestra cuántas rutas esperan a Velora.
- Para revisar una grabación antes de WC-54: `adb -s <reloj> shell run-as mx.watchcompanion ls no_backup/recordings` y `adb -s <reloj> exec-out run-as mx.watchcompanion cat no_backup/recordings/<uuid>/track.gpx > ruta.gpx` (solo mientras no haya llegado al teléfono). **No importarla en Velora como actividad:** crearía una segunda actividad de la misma salida.

## Zonas de frecuencia cardíaca

En el teléfono, **Inicio → Zonas de frecuencia cardíaca** (WC-32):

- **Usar la FC máxima de Velora** la trae de `GET /me` (`preferences.maxHeartRateBpm`) y calcula las zonas con la misma regla que las analíticas de Velora (`hr-zones/1`, ADR-020 de Velora): Z1 desde el 50 %, Z2 60 %, Z3 70 %, Z4 80 % y Z5 90 %. Si Velora no la tiene, se escribe a mano.
- Cada zona se puede ajustar a mano; se valida que la FC máxima esté entre 100 y 230 y que las zonas vayan de menor a mayor.
- **Guardar y enviar al reloj** las guarda en el teléfono y las manda al reloj (`HeartRateZonesConfig`). Si el reloj no está al alcance, las recibe en la siguiente comprobación de enlace: el `Pong` del teléfono siempre lleva las zonas vigentes.
- El reloj guarda la versión más reciente en un archivo local y muestra la zona en **Métricas**: funciona sin teléfono ni internet.

## Garantía del modo A: no se crea ni sube ninguna actividad

En el modo A, la única actividad es la del BSC100S (iGPSPORT → Strava → Velora). Cuatro pruebas lo vigilan en CI (WC-34); si alguien agrega una importación o una escritura, fallan:

| Prueba | Qué vigila |
|---|---|
| `ModeAContractTest` (`integration:velora-contract`) | Cada operación del cliente es `GET` o de sesión (`/auth/`), salvo una: la subida de la grabación del reloj como fuente secundaria (modo B), que por contrato nunca crea una actividad. Ninguna de importación, recuperación como actividad, reproceso ni escritura en Strava. Lee el `openapi-v1.yaml` fijado. |
| `ModeANoUploadsTest` (`app-phone`) | Corre los flujos reales del teléfono (sesión, zonas, última actividad, cierre) contra una Velora simulada y exige que cada petición esté en la lista de lecturas y sesión. |
| `ModeAProtocolTest` (`core:phone-communication`) | El catálogo de mensajes Watch ↔ Phone es una lista cerrada: un mensaje nuevo obliga a justificar que no transporta muestras ni archivos. |
| `ModeAWatchTest` (`app-watch`) | El reloj no declara `INTERNET` y activar Velora no pide ubicación. `LiveMetricsSessionTest` comprueba además que la sesión del modo A no enciende el GPS ni escribe archivos. |

Las lecturas sí están permitidas: p. ej. `GET /integrations/strava` para mostrar el estado de la sincronización.

## Contratos de los backends

`contract/<backend>/openapi-v1.yaml` es una copia del contrato del backend fijada al commit de `contract/<backend>/REF` (WC-ADR-009). El cliente se genera de esa copia en cada build: si el contrato cambia y el código no, no compila. Así el build no depende de la red y cada cambio de contrato es un PR explícito.

```bash
scripts/update-contract.sh velora <sha-de-velora-api>   # fija otro commit y descarga su contrato
scripts/update-contract.sh nexora <sha-de-nexora-api>   # Nexora: el contrato vive en develop (A-11)
scripts/update-contract.sh --check velora               # comprueba la copia (lo corre el CI, igual con nexora)
```

| Backend | Módulo | Cliente | Fijado en |
|---|---|---|---|
| Velora | `integration:velora-contract` | `VeloraClient` (generado `DefaultApi` + la subida de grabaciones secundarias, escrita a mano) | `velora-api` `main` |
| Nexora | `integration:nexora-contract` | `NexoraApi` (generado; todas las operaciones en una sola clase) | `nexora-api` `develop` (WC-36) |

Solo se generan las operaciones que usa el companion (lista `companionOperations` en el `build.gradle.kts` de cada módulo) y sus modelos. Agregar una operación es parte de la tarjeta que la necesita. `ModeAContractTest` (Velora) y `NexoraScopeTest` (Nexora) fallan si entra una escritura no justificada.

El contrato de Nexora lo exporta springdoc y no marca campos obligatorios: en los modelos generados todo es opcional, así que el código del companion valida lo que necesita (p. ej. que el login traiga los dos tokens).

## Permisos

Cada módulo pide sus permisos **solo cuando el usuario lo activa**. Si se revoca un permiso desde Ajustes, el módulo muestra qué falta al volver a la app, sin cerrarse. Al desactivar un módulo, sus permisos se revocan cuando termina el proceso (`revokeSelfPermissionsOnKill`), salvo los que siga usando otro módulo activo.

| Permiso | App | Módulo | Para qué | Desde |
|---|---|---|---|---|
| `android.permission.health.READ_HEART_RATE` | reloj | Velora | Frecuencia cardíaca en vivo y alertas por zona (WC-30, WC-31) | Fase 1 |
| `android.permission.FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_HEALTH` (normales, sin diálogo) | reloj | Velora | Mantener viva la sesión de métricas en vivo con la pantalla apagada o en ambiente (WC-30) | Fase 1 |
| `android.permission.VIBRATE` (normal, sin diálogo) | reloj | Velora | Alertas hápticas al cambiar de zona de FC (WC-31) | Fase 1 |
| `android.permission.POST_NOTIFICATIONS` (opcional) | reloj | Velora | Mostrar la notificación de la sesión, que lleva el ícono de la esfera (Ongoing Activity, WC-58). Se pide al tocar **Iniciar métricas**; si se niega, la sesión funciona igual, sin ícono | Fase 1 |
| `android.permission.ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` (opcional) | reloj | Velora | GPS del reloj para grabar la ruta en el modo B (WC-53). Se pide al tocar **Grabar ruta**; Android exige pedir las dos y sin la precisa no se graba. Se revoca al desactivar Velora | Fase 5 |
| `android.permission.FOREGROUND_SERVICE_LOCATION` (normal, sin diálogo) | reloj | Velora | Mantener el GPS con la pantalla apagada durante la grabación (WC-53) | Fase 5 |
| `WAKE_LOCK`, `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE` (normales, sin diálogo; los agrega WorkManager) | teléfono | Velora, Nexora | Subir las grabaciones del reloj con reintentos (WC-54) y sondear los avisos de Nexora (WC-41), aunque la app esté cerrada o el teléfono se reinicie | Fase 5 |
| `android.permission.INTERNET` (normal, sin diálogo) | teléfono | Velora | Llamar a Velora API por HTTPS (WC-22) | Fase 0 |
| `android.permission.POST_NOTIFICATIONS` (opcional) | teléfono | Nexora | Avisos de Nexora (pagos y mensualidades por vencer, cargos programados fallidos) como notificaciones que el sistema puentea al reloj, sin montos (WC-41). Se pide con **Permitir avisos** en la sección de Nexora; sin él no se muestra ningún aviso, pero tampoco se pierden | Fase 2 |
| `mx.watchcompanion.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | ambas | — | Permiso interno de firma que agrega AndroidX (`core-ktx`); no muestra diálogo | Fase 0 |

El reloj no declara `INTERNET`: no llama a los backends (WC-ADR-013). Guardian y Smart Hub agregan sus permisos a esta tabla en la tarjeta que los necesita.

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
- **Versionado:** al liberar se sube `versionName`/`versionCode` en `app-watch` y `app-phone` (siempre iguales en ambas apps) dentro del PR de release. Después de fusionar a `main` se etiqueta `vX.Y.Z` ([SemVer](https://semver.org/lang/es/)). La primera entrega (Fase 0 + Fase 1, WC-ADR-012) será `v0.1.0`.
- **Hotfix:** rama `hotfix/…` desde `main`, PR a `main` y, después, `main` se fusiona de vuelta a `develop`.

## Repositorios relacionados

[velora-api](https://github.com/1franky/velora-api) · [velora-android](https://github.com/1franky/velora-android) · [velora-web](https://github.com/1franky/velora-web) · [nexora-api](https://github.com/1franky/nexora-api) · [nexora-android](https://github.com/1franky/nexora-android) · [nexora-web](https://github.com/1franky/nexora-web)

Este repositorio no modifica esos proyectos; las integraciones se hacen mediante PRs acotados en cada uno.

## Idioma

Producto y documentación en español (México).
