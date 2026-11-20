# 08 — Architecture Decision Records (ADR)

## ADR-001 — Aplicación Wear OS modular

**Estado:** Aceptada.

Crear un repositorio nuevo `watch-companion` con módulos funcionales Velora, Nexora, Guardian y Smart Hub. Mantener APIs y bases de datos separadas.

**Motivo:** una instalación y experiencia coherentes, sin acoplar dominios de negocio.

## ADR-002 — BSC100S es la fuente principal de ciclismo en MVP

**Estado:** Aceptada.

El iGPSPORT BSC100S conserva su flujo actual de grabación y sincronización a Strava. Watch funciona como companion y no publica una segunda actividad.

**Motivo:** evitar duplicados y preservar el proceso que ya funciona.

## ADR-003 — Grabación dual como respaldo opt-in

**Estado:** Aceptada para fase posterior.

El Watch podrá registrar una ruta independiente y conservarla sin publicarla automáticamente en Strava.

**Motivo:** disponer de respaldo sin generar actividades duplicadas.

## ADR-004 — Fusión de tracks no destructiva

**Estado:** Aceptada como dirección futura.

Los tracks originales se preservan; la fusión crea una versión derivada, versionada y auditable. La fusión no se ejecuta automáticamente en el MVP.

**Motivo:** los sensores tienen precisión, frecuencia de muestreo y relojes distintos.

## ADR-005 — Strava continúa siendo el origen del evento de actividad BSC100S

**Estado:** Aceptada.

Velora conserva el webhook existente de Strava y recupera detalle/streams mediante la API con scopes autorizados.

**Motivo:** no duplicar integración con el dispositivo si el flujo actual ya opera correctamente.

## ADR-006 — Nexora API es la autoridad financiera

**Estado:** Aceptada.

El reloj no calcula saldos ni reglas de negocio. Solo presenta snapshots y envía acciones confirmadas al backend.

## ADR-007 — Guardian no usa root ni APIs ocultas

**Estado:** Aceptada.

Las funciones de bloqueo deben utilizar APIs oficiales y permisos legítimos de Android/Samsung. `DevicePolicyManager.lockNow()` requiere validar requisitos de administración.

## ADR-008 — Smart Hub comienza sin apertura remota de cerradura

**Estado:** Aceptada.

La primera versión consulta estados y controla acciones domésticas de bajo riesgo. Apertura remota requiere evaluación adicional de modelo, proveedor, permisos y confirmación.

## ADR-009 — OpenAPI como contrato de integración

**Estado:** Aceptada.

Los clientes del companion deben basarse en los contratos reales de Velora y Nexora. No crear endpoints especulativos ni duplicar DTOs manualmente si hay generación disponible.

## ADR-010 — La fuente de verdad se define por métrica

**Estado:** Aceptada como regla de diseño.

No existe una única fuente universal para todas las métricas:
- track GPS principal: BSC100S en modo A
- frecuencia cardíaca: fuente configurada y etiquetada (Watch o banda/BSC)
- cadencia/potencia: sensores emparejados con BSC100S cuando estén disponibles
- actividad lógica: Velora
- publicación social: flujo actual iGPSPORT → Strava

Toda métrica debe conservar `sourceType` y timestamp.
