# Watch Ecosystem — Documentación de arquitectura y desarrollo

**Idioma del producto:** español (México)  
**Dispositivo objetivo:** Samsung Galaxy Watch Ultra + Samsung Galaxy S26 Ultra  
**Repositorios existentes:** Velora (API, Android, Web) y Nexora (API, Android, Web)  
**Nuevo repositorio propuesto:** `watch-companion`

## Objetivo

Construir una experiencia Wear OS modular que conecte los productos existentes Velora y Nexora, y añada dos capacidades locales: seguridad del teléfono y control del hogar.

Los módulos funcionales son:

1. **Velora Watch:** ciclismo/MTB, telemetría, alertas y sincronización con Velora.
2. **Nexora Watch:** consulta financiera y captura rápida de movimientos.
3. **Watch Guardian:** automatizaciones de seguridad entre el reloj y el S26 Ultra.
4. **Smart Hub:** control de SmartThings y, sujeto a validación, TTLock.

La aplicación del reloj será un cliente especializado. No duplicará reglas de negocio ni bases de datos de Velora o Nexora.

## Documentos

| Archivo | Contenido |
|---|---|
| `00-architecture.md` | Arquitectura general, límites y contratos |
| `01-velora-watch.md` | Requisitos funcionales y técnicos de ciclismo |
| `02-activity-recording-and-reconciliation.md` | Estrategia iGPSPORT + Watch + Strava + Velora |
| `03-nexora-watch.md` | Requisitos del módulo financiero |
| `04-watch-guardian.md` | Seguridad y automatización del teléfono |
| `05-smart-hub.md` | Hogar, SmartThings y TTLock |
| `06-roadmap-and-testing.md` | Fases, criterios de aceptación y pruebas |
| `07-security-and-privacy.md` | Seguridad, permisos y privacidad |
| `08-architecture-decisions.md` | ADR y decisiones explícitas |
| `09-revision-y-auditoria.md` | Revisión de la especificación y desviaciones frente a los repos reales (2026-11-20) |
| `10-pruebas-de-campo-fase-1.md` | Protocolo, verificaciones en el reloj y plantilla de resultados de las salidas de la Fase 1 (WC-35) |

## Repositorios existentes

- `https://github.com/1franky/velora-api`
- `https://github.com/1franky/velora-android`
- `https://github.com/1franky/velora-web`
- `https://github.com/1franky/nexora-api`
- `https://github.com/1franky/nexora-android`
- `https://github.com/1franky/nexora-web`

No se deben renombrar, fusionar ni reemplazar estos repositorios para implementar el companion.

## Regla de trabajo para Codex y Claude Code

Antes de modificar código:

1. Clonar y auditar la rama principal de cada repositorio afectado.
2. Leer `README`, instrucciones locales (`AGENTS.md`, `CLAUDE.md`, `CONTRIBUTING.md`) y contratos OpenAPI reales.
3. Identificar funcionalidades existentes; no recrearlas.
4. Verificar versiones reales de Kotlin, Gradle, Android Gradle Plugin, Spring Boot y SDK.
5. Proponer cambios pequeños y compatibles.
6. Añadir pruebas antes de cambiar contratos.
7. No asumir que una función descrita aquí ya existe en el código.
8. Registrar desviaciones entre esta especificación y el estado real del repositorio.

## Alcance de la primera entrega

Decidido el 2026-11-20 (WC-ADR-012): la primera entrega es **Fase 0 + Fase 1** de `06-roadmap-and-testing.md`:

- Aplicación Wear OS instalable en Galaxy Watch Ultra (Wear OS 6).
- Comunicación fiable Watch ↔ S26 Ultra a través de `app-phone` (WC-ADR-011).
- Módulo Velora en modo iGPSPORT-primary.
- Registro local de errores y estado de sincronización.

Nexora (Fase 2), Guardian (Fase 3), Smart Hub (Fase 4) y la grabación dual (Fase 5) se publican como releases posteriores, cada uno con su propio criterio de salida.

La fusión de rutas, operaciones financieras sensibles, desbloqueo remoto y apertura remota de cerradura quedan fuera del MVP.
