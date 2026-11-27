# 09 — Revisión de la especificación y auditoría de repositorios

**Fecha:** 2026-11-20  
**Alcance:** documentos `00`–`08` de esta carpeta, contrastados con la rama por defecto de los seis repositorios existentes.  
**Propósito:** cumplir la regla 8 del README («registrar desviaciones entre esta especificación y el estado real del repositorio») antes de escribir código.

Cada hallazgo tiene un identificador (`R-xx` = revisión interna, `A-xx` = auditoría contra repos) que se usa en las tarjetas del tablero.

Leyenda de severidad: **Bloqueante** (impide implementar como está escrito) · **Alta** (riesgo de diseño o de cumplimiento) · **Media** (ambigüedad que generará retrabajo) · **Baja** (forma/consistencia).

---

## 1. Estado real de los repositorios auditados

| Repo | Rama por defecto | Último commit | Stack verificado | Contrato |
|---|---|---|---|---|
| `velora-api` | `main` | `c9da56c` 2026-11-17 | Java 25, Spring Boot 4.1.2, Gradle 9.9, PostGIS 18/3.7, Spring Modulith | `openapi/openapi-v1.yaml` (OpenAPI 3.1, fuente de verdad, WC-ADR-011) validado en pruebas |
| `velora-android` | `main` | `71d3efd` 2026-11-18 (v0.2.6) | AGP 9.5.1, Kotlin 2.4.21, Compose BOM 2026.09, Ktor 3.6, Room 2.8.5, WorkManager 2.12, minSdk 31, target/compile 37 | Copia fijada en `contract/openapi-v1.yaml` + `contract/REF`; cliente generado con openapi-generator 7.14.0 |
| `velora-web` | `main` | `b1fc1c4` 2026-11-17 | Next.js + TypeScript, Node 26, pnpm 12 | Consume `velora-api` |
| `nexora-api` | **`develop`** (`main` protegida, despliega) | `595897f` 2026-11-18 | Kotlin 2.3.21 (JVM 21), Spring Boot 4.1.1, PostgreSQL 16, springdoc 3.1.0 | **Sin archivo OpenAPI versionado**; solo springdoc en runtime y `openapi-contract-snapshot.json` (lista de endpoints) en pruebas |
| `nexora-android` | `main` | `9db11e1` 2026-10-05 (v1.7.0) | AGP 9.2.0, Kotlin 2.4.0, Retrofit 3.0, Room, WorkManager, minSdk 26 | DTOs escritos a mano (Retrofit), sin generación |
| `nexora-web` | **`develop`** | `25c7732` 2026-10-05 | Vite + TypeScript | Consume `nexora-api` |

Ninguno de los seis repos contiene módulos Wear OS, Data Layer ni Health Services. Todo lo de reloj es trabajo nuevo.

---

## 2. Hallazgos de la auditoría (spec vs. código)

### A-01 · Resuelto — Strava en producción

> **Actualización 2026-11-20 (WC-1):** el propietario confirma que en producción el conector de Strava **está activado y ya envía las rodadas a Velora correctamente**. El `false` es solo el valor por omisión del repositorio. Por lo tanto, el flujo de `01` §1 / `02` §1 es real y se mantiene como camino principal del modo A. La importación directa del FIT del BSC100S queda como respaldo opcional (WC-13, prioridad baja). El riesgo frente al Strava API Agreement sigue documentado en ADR-030 de Velora y no cambia el diseño del companion.

Hallazgo original:

La especificación (`01` §1, `02` §1, WC-ADR-005) trata **BSC100S → iGPSPORT → Strava → webhook → Velora** como la columna vertebral ya operativa.

Realidad en `velora-api`:
- El conector existe (ADR-030 de Velora; migraciones `V12`–`V14`; `StravaWebhookController`, `StravaSync`), pero `velora.strava.enabled=false` por omisión.
- `velora-project-kit/docs/09-strava-legal-and-data-governance.md` registra que el propietario decidió activarlo **asumiendo que incumple** el Strava API Agreement de 2026 (analítica, caché ≤ 7 días, borrado ≤ 48 h, apps competidoras).
- La estrategia de producto de Velora declara como **fuente primaria los archivos originales del dispositivo**, y Strava como integración opcional y aislada.

**Consecuencia:** el diseño del companion no debe depender de Strava como único camino. `velora-api` ya importa FIT, GPX y TCX (`POST /api/v1/activities/imports`, Garmin FIT SDK).  
**Propuesta:** añadir un ADR del companion que defina dos caminos válidos para el modo A: (a) Strava cuando el conector esté habilitado, (b) importación del FIT del BSC100S (desde iGPSPORT o desde el dispositivo) directamente a Velora. Esto responde a la pregunta 1 de `02` §9 como prioridad de la Fase 0.

### A-02 · Resuelto — Política de borrado de Strava vs. respaldo del Watch

> **Actualización 2026-11-23 (WC-7):** `02` §8.1 separa los datos de origen Watch (con su retención) de lo que viene de Strava (se borra según ADR-030 de Velora).

`02` §8 exige que «un evento Strava `delete` o `update` no elimine datos locales de respaldo sin política de retención». Velora ya marca `DELETED` en `strava_sync_items` y procesa borrados por compromiso de 48 h (`V14`).

**Propuesta:** dejar explícito en `02` que la regla solo protege datos **de origen Watch** (que no son Strava Data). Lo que provenga de Strava debe borrarse según ADR-030 de Velora.

### A-03 · Resuelto — Velora no tenía concepto de «grabación secundaria»

`02` §5 propone `activity_source_recording`, `activity_reconciliation` y `activity_canonical_track`. En Velora existen:
- `activities.source_type` ∈ {`FIT`, `GPX`, `TCX`, `STRAVA`}; no hay `WATCH_*`;
- `activity_files` (original con SHA-256), `activity_metrics` con `Source` ∈ {`DEVICE_REPORTED`, `DERIVED`};
- `DuplicateHeuristic` y `GET /activities/{id}/possible-duplicates`, que **solo advierten**.

Hoy, subir la grabación del Watch por `/activities/imports` crea **una segunda actividad**. Eso viola el criterio «una sola actividad lógica en Velora» (`01` §7).

**Propuesta:** ADR en `velora-api` para modelar grabaciones secundarias adjuntas a una actividad, en lugar de crear tablas paralelas. Hay que adaptar los nombres de `02` §5 al modelo real (`activities`, `activity_files`, `activity_metrics`, `activity_tracks`, streams `velora-streams/1`).

**Resolución (25/11/2026):** ADR-032 de Velora (WC-27), implementado en `velora-api` #57 (WC-60, contrato 1.24.0). La grabación del reloj es una **grabación secundaria**: tablas propias (`secondary_recordings` y sus series y recorrido), fuera de la analítica; se adjunta sola a la actividad que se solapa ≥ 50 % (`secondary-match/1`), llegue antes o después; un delete de la actividad la deja sin asignar; se puede recuperar como actividad si el BSC100S no grabó. El companion la graba (WC-53) y la sube por `POST /secondary-recordings` (WC-54). Los nombres de `02` §5 quedan como referencia conceptual.

### A-04 · Resuelto — Preguntas de `02` §9 ya respondidas por el código

> **Actualización 2026-11-23 (WC-7):** `02` §6 remite a ADR-030 de Velora y `02` §9 queda como tabla con respuesta o tarjeta por pregunta.

| Pregunta | Respuesta |
|---|---|
| ¿Velora ya descarga streams de Strava? | **Sí.** `HttpStravaApi.streams()` llama a `/activities/{id}/streams?key_by_type=true`; si no hay streams de tiempo, el ítem queda `NO_DATA`. |
| ¿Qué scopes tiene concedidos? | Se guardan por conexión en `strava_connections.scope`. **Producción (23/11/2026, WC-26):** `read,activity:read_all`, el mínimo que pide el conector y sin permisos de escritura. |
| Idempotencia del webhook (`02` §6) | Ya implementada de otra forma: todos los eventos se guardan (`strava_webhook_events`) y la deduplicación semántica ocurre en `strava_sync_items (user_id, strava_activity_id)`. **No hay que reespecificarla**: `02` §6 debe referenciar ADR-030 de Velora. |

### A-05 · Resuelto — Ningún backend soporta la sesión «emparejada» del reloj

> **Actualización 2026-11-20 (WC-3):** aceptado; ver WC-ADR-013.

`00` §6 pide evaluar OAuth Device Authorization o pairing de un solo uso. Ambos backends solo tienen login por email + contraseña:
- **Velora:** un único usuario propietario (ADR-010 de Velora), access token de 15 min y refresh de 30 días **de un solo uso** (reutilizarlo revoca la sesión).
- **Nexora:** JWT HS256 de 15 min y refresh opaco rotado en cada uso.

**Propuesta (simplificación):** en el MVP el reloj **nunca** tiene tokens de backend. Solo el `app-phone` inicia sesión en Velora y en Nexora, con sesiones propias e independientes, y el reloj habla exclusivamente por Data Layer. Esto ya coincide con el diagrama de `00` §3 y con «no enviar tokens por Data Layer». La sesión directa del reloj queda como evolución.

### A-06 · Resuelto — Data Layer no permite hablar con `velora-android` ni con `nexora-android`

> **Actualización 2026-11-20 (WC-4):** aprobado. `app-phone` es el puente; ver WC-ADR-011 en `08`.

La Wear OS Data Layer solo comunica apps con el **mismo `applicationId` y el mismo certificado de firma**. `velora-android` (`mx.velora.android`) y `nexora-android` (`com.nexora.android`) son apps distintas.

Por eso, la Fase 1 («comunicación con Velora Android/API para consultar estado de actividad») no es posible vía Data Layer con `velora-android`.

**Propuesta:** el puente es una app nueva `app-phone` dentro de `watch-companion`, con el mismo `applicationId` que `app-watch`. Las apps existentes no se modifican. Hay que dejarlo como ADR y corregir la redacción de `06` Fase 1.

### A-07 · Resuelto en la especificación — Nexora no tiene presupuestos

> **Actualización 2026-11-23 (WC-8):** presupuestos fuera del MVP en `03` §2 y en `00` §4; vuelven con la épica de `nexora-api` (WC-28).

`03` §2 («Alertas de presupuesto», «Presupuesto cercano al límite») y `00` §4 (`nexora-api` → «presupuestos») asumen una funcionalidad inexistente. No hay paquete `budgets`. `BUDGET_EXCEEDED` está declarada pero no se dispara (README de `nexora-api`, plan §31).

**Propuesta:** quitar presupuestos del MVP de Nexora Watch y marcarlos como dependientes de una épica de `nexora-api`.

### A-08 · Resuelto — Nexora no tiene «borradores» ni «consultar resultado de operación»

> **Actualización 2026-11-23 (WC-8):** `03` §2 adopta la propuesta (borrador local en el teléfono + `Idempotency-Key` generada en el reloj) y `03` §5 lista los endpoints reales.

Los contratos conceptuales de `03` §5 no existen. Lo que sí existe:
- `POST /api/v1/transactions` (`INCOME`/`EXPENSE`);
- filtro `IdempotencyFilter` con cabecera `Idempotency-Key` (`V7`), que repite la respuesta original ante un reintento y devuelve 409 si la misma clave llega con otro cuerpo.

**Propuesta:** el borrador vive solo en el teléfono (Room cifrado). «Confirmar» equivale a `POST /transactions` con una `Idempotency-Key` generada en el reloj. «Consultar resultado» equivale a reintentar con la misma clave. Así no se necesitan endpoints nuevos.

### A-09 · Resuelto — Nexora no tiene push

> **Actualización 2026-11-23 (WC-8):** `03` §2 especifica el sondeo con WorkManager y los tipos que se usan. Desde la auditoría, `nexora-api` agregó `SCHEDULED_CHARGE_*` y `SAT_SYNC_*`; de ellos solo `SCHEDULED_CHARGE_FAILED` llega al reloj.

Las notificaciones son bandeja in-app (`GET /api/v1/notifications`), sin FCM. Tipos activos: `PAYMENT_DUE`, `PAYMENT_DUE_SOON` e `INSTALLMENT_DUE`.

**Propuesta:** en `03` §2 especificar que `app-phone` sondea con WorkManager (por ejemplo, cada 6–12 h) y genera notificaciones locales puenteadas al reloj. Solo se usan los tipos activos.

### A-10 · Resuelto — Nexora no publicaba un OpenAPI versionado

WC-ADR-009 (OpenAPI como contrato y generación de clientes) choca con dos cosas: `nexora-api` solo expone springdoc en runtime, y `nexora-android` usa DTOs manuales.

**Propuesta:** tarea en `nexora-api` para exportar `openapi.yaml` en build/CI (rama `develop`). En el companion, replicar el patrón de `velora-android`: copia fijada (`contract/REF`) + `update-contract.sh --check` en CI.

**Resolución:** `nexora-api` #50 (WC-25) exporta `openapi/openapi-v1.yaml` con operationIds estables y una prueba que lo mantiene al día. El companion lo fija en `contract/nexora` (WC-36), genera el cliente en `integration:nexora-contract` y el CI corre `update-contract.sh --check nexora`. Queda una limitación del contrato: springdoc no marca campos obligatorios, así que los modelos generados son todos opcionales y el companion valida lo que necesita.

### A-11 · Baja — Flujo de ramas distinto en Nexora

El README de esta especificación pide «auditar la rama principal». En `nexora-api` y `nexora-web` la rama de integración es `develop`, y `main` está protegida y despliega a producción. Las tareas de integración para Nexora deben abrir PR contra `develop`.

### A-12 · Resuelto — Versiones de toolchain divergentes

> **Actualización 2026-11-20 (WC-14):** Galaxy Watch Ultra con One UI 8 Watch / Wear OS 6; ver WC-ADR-014.

`velora-android` usa AGP 9.5.1 / Kotlin 2.4.21; `nexora-android` usa AGP 9.2.0 / Kotlin 2.4.0.

**Propuesta:** `watch-companion` se alinea con `velora-android` (la más reciente y con contrato generado): catálogo `libs.versions.toml`, compileSdk/targetSdk 37. El minSdk del reloj se decide según la versión de Wear OS del Galaxy Watch Ultra (por verificar en el dispositivo).

### A-13 · Resuelto — Colisión de numeración de ADR

> **Actualización 2026-11-23 (WC-5):** los ADR del companion pasan a `WC-ADR-NNN` conservando su número (sin renumerar: el prefijo basta para distinguirlos). Las referencias a ADR de Velora llevan siempre «de Velora».

`08` usa ADR-001…010 y `velora-project-kit` usa ADR-007…031 con otros significados. Por ejemplo, «ADR-009» es el pipeline de importación en Velora y OpenAPI en el companion.

**Propuesta:** prefijo `WC-ADR-NNN` para este proyecto.

---

## 3. Hallazgos de revisión interna (consistencia de la especificación)

### R-01 · Resuelto — El alcance del «MVP» se contradice

> **Actualización 2026-11-20 (WC-2):** aceptado; ver WC-ADR-012.

El README («Alcance de la primera entrega») mete Velora, Nexora, Guardian y Smart Hub en la primera entrega. `06` los reparte en las Fases 1–4 con salidas independientes.

**Propuesta:** redefinir la primera entrega como **Fase 0 + Fase 1**, con cada fase posterior como release propio.

### R-02 · Alta — El modo dual aparece en el MVP y en V1 a la vez

`01` §2 (MVP) incluye «registrar telemetría local cuando se habilite el modo dual» e «iniciar/pausar/finalizar si modo dual o respaldo». En cambio, `02` (Estado), la tabla de `02` §3, WC-ADR-003 y `06` Fase 5 lo ubican después.

**Propuesta:** sacar del MVP de `01` todo lo que dependa del modo B.

> **Resuelto 2026-11-21 (WC-6):** el MVP de `01` §2 solo contiene funciones del modo A. La grabación del reloj, la persistencia local y el inicio/pausa/fin de grabación pasan a Evolución (modo B, Fase 5). `01` §5 y §7 separan MVP y modo B.

### R-03 · Alta — No existe fuente para «velocidad/distancia recibidas del teléfono» en el modo A

`01` §2/§5 muestra métricas «recibidas del teléfono», pero el propio `01` §4 dice que no hay API ni BLE abierto del BSC100S. En el modo A, el teléfono no tiene de dónde obtener la velocidad ni la distancia en vivo.

**Propuesta:** en el MVP, las métricas en vivo son solo las del reloj (HR, duración). La velocidad y la distancia requieren GPS del reloj (modo B) o un sensor BLE propio, a decidir en un PoC.

> **Resuelto 2026-11-21 (WC-6):** las métricas en vivo del MVP son HR, zona y duración del propio reloj, dentro de una *sesión de métricas en vivo* (`01` §3.1): ejercicio de `ExerciseClient` sin ubicación, sin persistir muestras y sin subidas. La velocidad y la distancia quedan en Evolución. También se quita el «estado BSC100S» de la pantalla Resumen, porque no hay fuente para él.

### R-04 · Media — El sobre de mensajes no cubre lo que exige Guardian/Smart Hub

`00` §5 define `messageId`, `schemaVersion`, `createdAt`, `deviceId` y `correlationId`. Sin embargo, `04` §6 exige `nonce`, expiración y `commandId`, y `05` §4 exige `expiresAt` y `confirmationRequired`. Además, la lista de mensajes no incluye resultados de comandos (p. ej. `GuardianCommand`/`GuardianCommandResult`, `SmartHomeCommandResult`).

**Propuesta:** un sobre común con campos opcionales `nonce` y `expiresAt`, y un catálogo completo de mensajes, versionado en `core/phone-communication`.

### R-05 · Media — Sin regla de compatibilidad entre versiones de reloj y teléfono

Existe `schemaVersion`, pero no se dice qué ocurre si el reloj y el teléfono tienen versiones distintas de la app.

**Propuesta:** añadir la regla (p. ej. «acepta N y N-1; rechaza con `IntegrationStatus.INCOMPATIBLE`») y su caso en la matriz de pruebas de `06`.

### R-06 · Alta — El «bloqueo normal» de Guardian no tiene una API legítima evidente

`04` §3 separa el bloqueo normal del reforzado, pero prohíbe accesibilidad (la vía de `GLOBAL_ACTION_LOCK_SCREEN`) y root. Para una app ordinaria, la única vía pública restante es `DevicePolicyManager.lockNow()` con administrador de dispositivo (`USES_POLICY_FORCE_LOCK`). Es decir, ambos modos colapsan en el mismo mecanismo.

**Propuesta:** PoC en Fase 3 para confirmar en el S26 Ultra (One UI vigente) si el administrador de dispositivo con force-lock sigue disponible para apps no-DPC, y si hay alguna rutina de Samsung invocable. Reescribir `04` §3 con el resultado.

**Resuelto (WC-43):** el administrador de dispositivo con `force-lock` funciona en el S26 Ultra (One UI 9.0) para una app normal, también en segundo plano. `04` §3 queda reescrito con un solo mecanismo.

### R-07 · Alta — Señal de sueño: disponibilidad no verificada

`04` §4 asume una «señal de posible sueño» en tiempo casi real. Hay que verificar si Health Services o el Samsung Health Sensor SDK la exponen a apps de terceros en vivo; los datos de sueño suelen ser sesiones retrospectivas.

**Propuesta:** dejarlo como investigación en Fase 3. No bloquea el bloqueo manual.

### R-08 · Media — «Teléfono lejos» por intensidad de señal

`04` §2 depende de estimar la distancia. Wear OS no expone de forma estable el RSSI del enlace con el teléfono a apps de terceros. Con `NodeClient`/`CapabilityClient` solo se sabe si hay conexión o no.

**Propuesta:** el MVP solo alerta de desconexión; la proximidad queda como investigación.

### R-09 · Alta — Smart Hub necesita un lugar seguro para secretos de proveedor

`00` §6 y `07` §3 prohíben client secrets en el APK. Sin embargo, tanto TTLock Open Platform como el OAuth de SmartThings requieren `client_secret`, y la especificación no prevé un backend para el Smart Hub.

**Propuesta:** decisión explícita entre (a) un BFF mínimo propio para Smart Hub, (b) un token personal del usuario guardado en el Keystore del teléfono, si el proveedor lo permite (verificar caducidad y alcance actuales de los PAT de SmartThings), o (c) posponer.

**Avance (WC-48):** SmartThings confirmado: PAT de 24 h (no sirve para la app), OAuth con `client_secret` y refresh de 30 días, eventos solo con webhook y API de pago desde oct 2026. Detalle en `05` §2. La decisión sigue en WC-12, tras WC-49 (TTLock).

**Avance (WC-49):** TTLock también exige `client_secret` (y la contraseña de la cuenta TTLock en MD5) y una aplicación de desarrollador aprobada. La cerradura del usuario no tiene gateway: sin él no hay estado abierto/cerrado en la nube, solo batería y aperturas sincronizadas por la app. Detalle en `05` §3.

### R-10 · Resuelto — Contradicción sobre la sesión del reloj

> **Actualización 2026-11-20 (WC-3):** `00` §6 reescrito según WC-ADR-013.

`00` §6 («el reloj debe usar una sesión emparejada y acotada») contradice el diagrama de `00` §3 y la regla de no tokens por Data Layer. Ver A-05.

### R-11 · Resuelto — `02` se titula ADR pero no aparece en `08`

> **Actualización 2026-11-23 (WC-5):** `02` deja de titularse ADR: es el desarrollo detallado de WC-ADR-002 a WC-ADR-005, que es donde quedan las decisiones. `08` lo enlaza.

**Propuesta:** listar en `08` el ADR de `02` como WC-ADR-002 (o fusionarlo) y renumerar con el prefijo de A-13.

### R-12 · Resuelto — Huecos en la matriz de pruebas y en la DoD

> **Actualización 2026-11-23 (WC-10):** `06` §2 suma las cinco áreas, §2.1 indica qué prueba cubre cada una y qué falta (ATF en Compose), y §4 amplía la DoD.

`06` no incluye:
- versión cruzada reloj/teléfono (R-05);
- consumo de batería con la app en segundo plano;
- accesibilidad en Wear (los repos Velora ya ejecutan ATF);
- una prueba de que Data Layer no transporta secretos;
- la importación FIT directa (A-01).

### R-13 · Resuelto — Privacidad de inicio/fin de ruta

> **Actualización 2026-11-23 (WC-11):** `07` §2 (Velora) incorpora la herencia de ADR-016 de Velora, la regla de no mostrar coordenadas exactas fuera de la app y el manejo del track crudo local.

Velora ya aplica privacidad de inicio y fin de recorrido (ADR-016 de Velora, migración `V7__track_privacy`). `07` debe indicar que cualquier track del Watch que llegue a Velora hereda esa política y que el companion no muestra coordenadas exactas fuera de la app.

---

## 4. Decisiones que debe tomar el propietario

1. ~~¿El modo A depende de Strava o del FIT directo?~~ **Resuelto:** Strava, operativo en producción; FIT directo como respaldo opcional. (A-01, WC-1)
2. ~~¿Primera entrega = Fase 0 + Fase 1?~~ **Resuelto:** sí, WC-ADR-012. (R-01, WC-2)
3. ~~¿El reloj sin tokens de backend en el MVP?~~ **Resuelto:** sí, WC-ADR-013. (A-05, WC-3)
4. ~~¿Puente de Data Layer?~~ **Resuelto:** `app-phone` propia, WC-ADR-011. (A-06, WC-4)
5. ¿Estrategia de secretos para Smart Hub: BFF, PAT o posponer? (R-09, WC-12)
6. ~~¿Prefijo `WC-ADR` y renumeración?~~ **Resuelto:** prefijo `WC-ADR` sin renumerar. (A-13, R-11, WC-5)

## 5. Siguientes pasos

- Convertir cada hallazgo en tarjeta del tablero (ver la columna «Hallazgo» en Notion).
- Incorporar a `00`–`08` las correcciones aceptadas, en PRs pequeños que citen el ID del hallazgo.
- Repetir esta auditoría cuando cambien `openapi-v1.yaml` de Velora o el snapshot de Nexora.
