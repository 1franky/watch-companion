# 06 — Roadmap, entregas y pruebas

## 1. Fases propuestas

### Fase 0 — Auditoría e infraestructura

- Auditar los seis repositorios y sus ramas principales.
- Obtener contratos OpenAPI reales de Velora y Nexora.
- Crear `watch-companion`.
- Configurar proyecto Wear OS, firma debug y CI.
- Probar instalación en Galaxy Watch Ultra.
- Probar comunicación Watch ↔ S26 Ultra.
- Implementar permisos y estado de conectividad.

**Salida:** aplicación mínima instalable y pairing fiable.

### Fase 1 — Velora Watch MVP

- Implementar modo iGPSPORT-primary.
- UI de métricas y estado.
- Heart rate y alertas locales.
- Consulta del estado de actividad en Velora API desde `app-phone` (ADR-011).
- No duplicar publicación a Strava.
- Pruebas de una ruta real.

**Salida:** el reloj complementa al BSC100S sin crear actividades duplicadas.

### Fase 2 — Nexora Watch MVP

- Snapshot financiero de solo lectura.
- Próximos pagos.
- Movimientos recientes.
- Borrador de gasto/ingreso y confirmación desde teléfono.
- Notificaciones discretas.

**Salida:** consulta financiera y captura controlada.

### Fase 3 — Watch Guardian

- Bloqueo manual.
- Verificación de resultado.
- Alertas de desconexión.
- Evaluación de bloqueo reforzado y política de sueño.

**Salida:** automatizaciones permitidas, verificables y reversibles.

### Fase 4 — Smart Hub

- Integración SmartThings.
- Estado de cerradura TTLock si la API lo permite.
- Escenas domésticas.
- Evaluación separada de operaciones de acceso.

**Salida:** control doméstico no crítico y seguro.

### Fase 5 — Velora grabación dual y reconciliación

- Grabación GPS del Watch con ExerciseClient.
- Persistencia local robusta.
- Subida de archivo a Velora.
- Asociación con actividad Strava.
- Motor de reconciliación versionado.
- UI de revisión de diferencias.

**Salida:** respaldo y fusión opcional sin destruir datos originales.

## 2. Matriz de pruebas

| Área | Pruebas mínimas |
|---|---|
| Pairing | conexión, desconexión, re-pair, reloj fuera de rango |
| Wear OS | pantalla ambiente, suspensión, reinicio, permisos revocados |
| GPS | señal débil, pérdida GPS, ruta larga, ahorro de batería |
| HR | sensor no disponible, lecturas nulas, picos y pérdida de señal |
| Offline | sin teléfono, sin internet, reintento, duplicado |
| Strava | webhook repetido, create/update/delete, token revocado |
| Velora | importación idempotente, fuente duplicada, datos parciales |
| Nexora | snapshot obsoleto, transacción duplicada, backend caído |
| Guardian | comando expirado, acción no permitida, confirmación ausente |
| Smart Hub | proveedor caído, dispositivo offline, comando duplicado |
| Seguridad | logs, almacenamiento, revocación, expiración de sesión |

## 3. Pruebas de campo de ciclismo

Realizar al menos estas salidas:
1. Ruta corta urbana.
2. MTB con desnivel.
3. Ruta larga (varias horas).
4. Ruta con pérdida de señal móvil.
5. Ruta con batería del reloj baja.
6. Ruta donde BSC100S y Watch registren simultáneamente en modo B.

Comparar:
- distancia
- duración en movimiento
- tiempo transcurrido
- desnivel positivo
- continuidad del track
- frecuencia cardíaca
- batería consumida
- tiempo de sincronización

No definir tolerancias finales sin obtener una línea base de rutas reales.

## 4. Definition of Done

- Código compilable y pruebas automatizadas aprobadas.
- Permisos documentados.
- No hay secretos en repositorio.
- Errores y estados offline visibles.
- Migraciones compatibles.
- Contratos OpenAPI actualizados si cambiaron.
- Evidencia de pruebas en dispositivo físico.
- README de instalación y depuración.
- Cambios pequeños y revisables en PR.
