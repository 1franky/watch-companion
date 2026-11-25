# 00 — Arquitectura general

## 1. Principios

- **Separación de dominios:** Velora y Nexora conservan APIs, persistencia, autenticación y reglas de negocio independientes.
- **Watch como cliente:** el reloj no se convierte en backend ni fuente maestra de información financiera.
- **Phone bridge:** el S26 Ultra coordina tareas que requieren capacidades del teléfono, integraciones Android o conectividad adicional.
- **Offline-first donde sea necesario:** las actividades deportivas deben sobrevivir a desconexiones y fallos de red.
- **Privacidad por módulo:** permisos de salud, ubicación, finanzas y hogar se solicitan por separado y solo cuando se necesitan.
- **No duplicar efectos:** operaciones y actividades deben tener claves de idempotencia y estados explícitos.
- **Degradación segura:** si una integración no está disponible, informar y conservar datos locales; nunca simular éxito.

## 2. Repositorio nuevo

Crear `watch-companion`, aplicación Android modular con destino Wear OS. Evaluar si conviene incluir el companion de teléfono en el mismo repositorio Gradle o mantener un módulo móvil separado. No modificar los seis repositorios existentes sin una tarea de integración concreta.

Estructura inicial sugerida (adaptar al estado real de Android Gradle Plugin y Kotlin):

```text
watch-companion/
  app-watch/
  app-phone/
  core/
    common/
    design-system/
    network/
    security/
    datastore/
    phone-communication/
    permissions/
    notifications/
  feature/
    velora/
    nexora/
    guardian/
    smart-home/
  integration/
    velora-contract/
    nexora-contract/
  docs/
  gradle/
```

Los nombres son una propuesta; no son una exigencia para crear módulos vacíos sin uso.

## 3. Vista lógica

```mermaid
flowchart TB
  subgraph WATCH["Galaxy Watch Ultra — Wear OS"]
    UI["Wear UI"]
    V["Velora Watch"]
    N["Nexora Watch"]
    G["Guardian UI"]
    H["Smart Hub UI"]
    LOCAL["Local storage / outbox"]
    SENSORS["Health Services / GPS"]
  end

  subgraph PHONE["Galaxy S26 Ultra — Android"]
    BRIDGE["Phone bridge"]
    AUTH["Credential broker"]
    AUTO["Automation coordinator"]
    CACHE["Encrypted local cache"]
    INTEGRATIONS["Android / Samsung integrations"]
  end

  subgraph BACKENDS["Backends separados"]
    VA["Velora API"]
    NA["Nexora API"]
  end

  STRAVA["Strava"]
  IGP["iGPSPORT BSC100S"]
  ST["SmartThings"]
  TT["TTLock — sujeto a validación"]

  UI --> V
  UI --> N
  UI --> G
  UI --> H
  V --> SENSORS
  V --> LOCAL
  N --> LOCAL
  G --> BRIDGE
  H --> BRIDGE
  LOCAL <--> BRIDGE
  BRIDGE --> AUTH
  BRIDGE --> CACHE
  BRIDGE --> AUTO
  AUTO --> INTEGRATIONS
  BRIDGE --> VA
  BRIDGE --> NA
  IGP --> STRAVA
  STRAVA --> VA
  ST --> INTEGRATIONS
  TT --> INTEGRATIONS
```

## 4. Responsabilidades por sistema

| Sistema | Responsabilidad |
|---|---|
| `velora-api` | Actividades, ingestión de fuentes, normalización, analítica, rutas y deduplicación |
| `velora-android` | Experiencia Android existente de Velora; no duplicar su lógica |
| `velora-web` | Visualización y análisis de actividades |
| `nexora-api` | Cuentas, tarjetas, MSI/MCI, transacciones, pagos, notificaciones y reglas financieras (sin presupuestos todavía, A-07) |
| `nexora-android` | Experiencia Android existente de Nexora |
| `nexora-web` | Experiencia web existente de Nexora |
| `watch-companion` | UI Wear OS, sensores, acciones rápidas y comunicación con el teléfono |
| S26 Ultra | Puente de integraciones y automatizaciones autorizadas |

## 5. Comunicación Watch ↔ Phone

Usar Wear OS Data Layer API para sincronizar mensajes, estados y pequeñas cargas entre dispositivos emparejados. No tratar Data Layer como sustituto de HTTPS hacia los backends.

Los mensajes se definen en `core/phone-communication` (`Envelope`, `Payload`, `ProtocolCodec`) y se serializan como JSON con discriminador `type`. El nombre de `type` es parte del contrato: no se renombra, se agrega uno nuevo.

### Archivos: grabaciones del modo B (WC-54)

Un archivo no viaja como mensaje (catálogo cerrado y límite de tamaño). El reloj publica un *data item* `/wc/recording/<uuid>` con el GPX como *Asset* y los campos `schema`, `clientRecordingId`, `completeness` y `sha256` (`RecordingTransfer`, en `core:data-layer`). Google Play services lo entrega cuando hay enlace, aunque alguna app esté cerrada. El teléfono guarda el archivo, verifica el SHA-256 y **borra el data item**: ese borrado es el acuse, y el reloj borra entonces su copia. La subida a Velora es aparte, con WorkManager.

### Sobre común

| Campo | Obligatorio | Uso |
|---|---|---|
| `messageId` | sí | UUID del mensaje |
| `schemaVersion` | sí | versión del protocolo |
| `createdAt` | sí | ms epoch UTC |
| `deviceId` | sí | identificador seudónimo del emisor |
| `correlationId` | si responde a otro | une solicitud y respuesta |
| `nonce` | en mensajes sensibles | un solo uso; el receptor rechaza repeticiones |
| `expiresAt` | en mensajes sensibles | ms epoch; un mensaje caducado no se ejecuta |

Un mensaje **sensible** es el que produce un efecto (bloquear el teléfono, ejecutar una escena, confirmar un movimiento). Sin `nonce` y `expiresAt` no se codifica ni se acepta.

### Compatibilidad de versiones

Se acepta la versión actual del protocolo y la anterior (N y N-1). Fuera de ese rango, el receptor descarta el mensaje y responde `IntegrationStatus(PHONE_LINK, INCOMPATIBLE)`, que el usuario ve como «actualiza la app en el reloj o en el teléfono». Los campos opcionales desconocidos se ignoran.

### Catálogo

| Mensaje | Dirección | Sensible | Estado |
|---|---|---|---|
| `Ping` / `Pong` | ambas | no | implementado (Fase 0) |
| `IntegrationStatus` | ambas | no | implementado (Fase 0) |
| `WorkoutStatus` | reloj → teléfono | no | Fase 1 |
| `ActivitySyncStatus` | teléfono → reloj | no | Fase 1 |
| `HeartRateZones` | teléfono → reloj | no | Fase 1 |
| `FinanceSnapshotRequest` / `FinanceSnapshotResponse` | reloj ↔ teléfono | no | Fase 2 |
| `QuickTransactionDraft` | reloj → teléfono | no (la confirmación ocurre en el teléfono) | Fase 2 |
| `TransactionResult` | teléfono → reloj | no | Fase 2 |
| `GuardianCommand` / `GuardianCommandResult` | reloj ↔ teléfono | sí / no | Fase 3 |
| `GuardianEvent` | ambas | no | Fase 3 |
| `SmartHomeCommand` / `SmartHomeCommandResult` | reloj ↔ teléfono | sí / no | Fase 4 |
| `WatchTelemetryBatch` | reloj → teléfono | no | Fase 5 |

Cada mensaje se agrega al código en la tarjeta que lo necesita, no antes.

No enviar credenciales ni tokens de acceso en mensajes de Data Layer. El teléfono obtiene credenciales de forma segura y realiza las llamadas remotas cuando sea la opción más segura.

## 6. Backend y autenticación

- Velora y Nexora deben conservar emisores, audiencias y scopes independientes.
- No compartir refresh tokens entre módulos.
- El reloj **no tiene tokens de backend** en el MVP (WC-ADR-013): solo `app-phone` se autentica contra Velora y Nexora, y el reloj se comunica únicamente por Data Layer. Una sesión propia del reloj (OAuth Device Authorization o pairing de un solo uso) queda como evolución y requiere soporte nuevo en los backends.
- No guardar secretos de cliente OAuth en el APK.
- Si se añade BFF/bridge, que sea mínimo y no se convierta en una API de negocio compartida.
- El teléfono debe aplicar WorkManager para trabajos diferidos y reintentos; no depender de ejecución perpetua en background.

## 7. Contratos

Consumir OpenAPI publicado por cada API y generar clientes cuando el proceso actual lo permita. No copiar DTOs manualmente si existe generación estable. Versionar cambios incompatibles.

Antes de implementar, revisar las especificaciones OpenAPI reales y la autenticación ya existente en ambos backends.

## 8. Observabilidad

Registrar eventos técnicos sin datos financieros ni coordenadas exactas innecesarias:

- módulo y versión
- estado de conectividad
- operación y correlation ID
- resultado y código de error
- duración
- estado de sincronización

Para actividades, conservar auditoría de fuentes y transformaciones. No registrar tokens, secretos, datos bancarios completos ni muestras biométricas en logs.
