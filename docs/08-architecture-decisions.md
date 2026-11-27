# 08 — Architecture Decision Records (ADR)

Los ADR de este proyecto se citan como **`WC-ADR-NNN`** para no confundirlos con los de Velora o Nexora, que usan su propia numeración: esos se citan siempre con el proyecto, por ejemplo «ADR-030 de Velora» (A-13, WC-5). Un ADR aceptado no se renumera; si se reemplaza, se marca como sustituido y se agrega uno nuevo.

Las decisiones de grabación (WC-ADR-002 a WC-ADR-005) se desarrollan en `02-activity-recording-and-reconciliation.md`.

## WC-ADR-001 — Aplicación Wear OS modular

**Estado:** Aceptada.

Crear un repositorio nuevo `watch-companion` con módulos funcionales Velora, Nexora, Guardian y Smart Hub. Mantener APIs y bases de datos separadas.

**Motivo:** una instalación y experiencia coherentes, sin acoplar dominios de negocio.

## WC-ADR-002 — BSC100S es la fuente principal de ciclismo en MVP

**Estado:** Aceptada.

El iGPSPORT BSC100S conserva su flujo actual de grabación y sincronización a Strava. Watch funciona como companion y no publica una segunda actividad.

**Motivo:** evitar duplicados y preservar el proceso que ya funciona.

## WC-ADR-003 — Grabación dual como respaldo opt-in

**Estado:** Aceptada para fase posterior.

El Watch podrá registrar una ruta independiente y conservarla sin publicarla automáticamente en Strava.

**Motivo:** disponer de respaldo sin generar actividades duplicadas.

## WC-ADR-004 — Fusión de tracks no destructiva

**Estado:** Aceptada como dirección futura.

Los tracks originales se preservan; la fusión crea una versión derivada, versionada y auditable. La fusión no se ejecuta automáticamente en el MVP.

**Motivo:** los sensores tienen precisión, frecuencia de muestreo y relojes distintos.

## WC-ADR-005 — Strava continúa siendo el origen del evento de actividad BSC100S

**Estado:** Aceptada.

Velora conserva el webhook existente de Strava y recupera detalle/streams mediante la API con scopes autorizados.

**Motivo:** no duplicar integración con el dispositivo si el flujo actual ya opera correctamente.

**Verificado (2026-11-20):** el conector está activado en producción y las rodadas del BSC100S llegan a Velora vía Strava. En el repositorio, `velora.strava.enabled` sigue en `false` como valor por omisión. El riesgo contractual con Strava está registrado en ADR-030 de Velora. La importación directa del FIT del dispositivo queda como respaldo opcional, no como camino principal.

## WC-ADR-006 — Nexora API es la autoridad financiera

**Estado:** Aceptada.

El reloj no calcula saldos ni reglas de negocio. Solo presenta snapshots y envía acciones confirmadas al backend.

## WC-ADR-007 — Guardian no usa root ni APIs ocultas

**Estado:** Aceptada.

Las funciones de bloqueo deben utilizar APIs oficiales y permisos legítimos de Android/Samsung. `DevicePolicyManager.lockNow()` requiere validar requisitos de administración.

## WC-ADR-008 — Smart Hub comienza sin apertura remota de cerradura

**Estado:** Aceptada.

La primera versión consulta estados y controla acciones domésticas de bajo riesgo. Apertura remota requiere evaluación adicional de modelo, proveedor, permisos y confirmación.

## WC-ADR-009 — OpenAPI como contrato de integración

**Estado:** Aceptada.

Los clientes del companion deben basarse en los contratos reales de Velora y Nexora. No crear endpoints especulativos ni duplicar DTOs manualmente si hay generación disponible.

## WC-ADR-010 — La fuente de verdad se define por métrica

**Estado:** Aceptada como regla de diseño.

No existe una única fuente universal para todas las métricas:
- track GPS principal: BSC100S en modo A
- frecuencia cardíaca: fuente configurada y etiquetada (Watch o banda/BSC)
- cadencia/potencia: sensores emparejados con BSC100S cuando estén disponibles
- actividad lógica: Velora
- publicación social: flujo actual iGPSPORT → Strava

Toda métrica debe conservar `sourceType` y timestamp.

## WC-ADR-011 — `app-phone` propia como puente Watch ↔ backends

**Estado:** Aceptada (2026-11-20).

La Wear OS Data Layer solo comunica apps con el mismo `applicationId` y el mismo certificado de firma. `velora-android` (`mx.velora.android`) y `nexora-android` (`com.nexora.android`) no pueden actuar como puente del reloj.

Decisión:
- `watch-companion` incluye dos apps: `app-watch` (Wear OS) y `app-phone` (Android, S26 Ultra), con el mismo `applicationId` y la misma firma, tanto en debug como en release.
- `app-phone` es el único componente que llama a Velora API y Nexora API, con sesiones propias e independientes por backend.
- `velora-android` y `nexora-android` no se modifican para el companion; siguen siendo las apps completas de cada producto.

**Consecuencias:**
- El usuario inicia sesión en Velora y en Nexora también dentro de `app-phone`. Son sesiones separadas de las apps existentes y se pueden revocar por separado.
- La Fase 1 consulta el estado de las actividades directamente en Velora API desde `app-phone`, no a través de `velora-android`.
- Perder la clave de firma rompe la comunicación entre las dos apps: hay que guardarla igual que las de las apps existentes.

**Motivo:** es la única opción soportada por la plataforma sin acoplar el companion a las apps existentes.

## WC-ADR-012 — Primera entrega = Fase 0 + Fase 1

**Estado:** Aceptada (2026-11-20).

La primera entrega incluye la infraestructura (Fase 0) y Velora Watch en modo A (Fase 1). Cada módulo posterior se publica como release propio.

**Motivo:** el README y `06` se contradecían; entregar cuatro módulos a la vez retrasa la validación en dispositivo real.

## WC-ADR-013 — El reloj no guarda tokens de backend en el MVP

**Estado:** Aceptada (2026-11-20).

- Solo `app-phone` inicia sesión en Velora y Nexora (sesiones independientes, refresh tokens cifrados con Android Keystore).
- El reloj pide datos y envía acciones por Data Layer; nunca recibe access ni refresh tokens.
- Si el teléfono no está disponible, el reloj muestra datos locales con su antigüedad y no simula operaciones remotas.

**Motivo:** ningún backend soporta hoy device flow ni pairing; ambos usan login por contraseña con refresh rotatorio (en Velora, de un solo uso). Mantener una sola sesión por backend evita carreras de renovación y reduce la superficie de ataque.

## WC-ADR-014 — Versiones mínimas de plataforma

**Estado:** Aceptada (2026-11-20).

- `app-watch`: `minSdk 36` (Wear OS 6 / One UI 8 Watch, versión verificada en el Galaxy Watch Ultra del propietario).
- `app-phone`: `minSdk 31`, igual que `velora-android`.
- `compileSdk`/`targetSdk 37` y catálogo de versiones alineado con `velora-android` (AGP 9.5.x, Kotlin 2.4.21, Gradle 9.9.x).

## WC-ADR-015 — Smart Hub se pospone

**Estado:** Aceptada (2026-11-26). Resuelve R-09 y WC-12.

**Decisión:** no se implementa Smart Hub por ahora: ni BFF, ni credenciales del usuario en el teléfono, ni PAT. WC-50, WC-51 y WC-52 quedan en espera.

**Motivo** (spikes WC-48 y WC-49, `05` §2 y §3):
- **SmartThings:** el PAT caduca a las 24 h; OAuth exige `client_secret` (fuera del APK) y un servidor para eventos; la API es de pago desde oct 2026 (plan personal US$4.99/mes). La cuenta del propietario solo tiene televisores y sensores de luz: sin luces, enchufes ni escenas que justifiquen el costo.
- **TTLock:** exige `client_secret`, una aplicación de desarrollador aprobada y la contraseña de la cuenta TTLock. La cerradura (LL747) no tiene gateway, así que la nube no conoce su estado: WC-52 mostraría datos de días.
- Un BFF agregaría un servicio, su secreto y su mantenimiento solo para eso; poner secretos del usuario en el teléfono amplía la superficie de ataque sin un caso de uso que lo pague.

**Se retoma cuando** se cumpla alguna de estas condiciones, y entonces se vuelve a decidir entre BFF mínimo (separado de Velora y Nexora) o credenciales propias del usuario cifradas en el teléfono:
- hay luces, enchufes o escenas en SmartThings que valga la pena controlar desde el reloj y se acepta el costo de la API, o
- se instala un gateway TTLock y se quiere ver el estado de la cerradura.

WC-ADR-008 (sin apertura remota) sigue vigente cuando se retome.

