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
- Consulta del estado de actividad en Velora API desde `app-phone` (WC-ADR-011).
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
- Evaluación de bloqueo reforzado y política de sueño (evaluada en WC-46: solo sugerencia, `04` §4).

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
| Compatibilidad reloj ↔ teléfono | versiones distintas instaladas: se acepta la versión de protocolo anterior, una fuera de rango se muestra como «Incompatible» en ambos lados, campos desconocidos se ignoran (R-05) |
| Batería | consumo con métricas en vivo y con la app en segundo plano sin sesión; reloj y teléfono por separado |
| Accesibilidad en Wear | TalkBack lee etiqueta y valor juntos, objetivos táctiles suficientes, legible en ambiente, desplazamiento con bisel/corona |
| Data Layer sin secretos | ningún token, contraseña ni cookie viaja al reloj; los mensajes sensibles exigen nonce y caducidad |
| Importación FIT directa (respaldo) | el mismo archivo dos veces no duplica; un FIT de una salida que ya llegó por Strava se marca como posible duplicado, no se fusiona (A-01, WC-13) |

### 2.1 Cobertura actual

Qué cubre hoy cada área nueva y qué falta (R-12, WC-10). Lo automatizado corre en CI en cada PR.

| Área | Automatizado | En dispositivo / pendiente |
|---|---|---|
| Compatibilidad reloj ↔ teléfono | `ProtocolCodecTest` (versión anterior aceptada, fuera de rango → `Incompatible`, campos desconocidos, Pong antiguo sin `sync`); `LinkSessionTest` (respuesta `Incompatible` limitada a una por minuto) | Instalar versiones distintas en reloj y teléfono antes de cada release |
| Batería | — | Línea base en las salidas de campo (`10` §5); no se fijan límites hasta tenerla |
| Accesibilidad en Wear | `SummaryScreenTest`, `MetricsScreenTest` (descripciones fusionadas para TalkBack, «--» con motivo en lugar de 0) | Verificación con TalkBack en el reloj (`10`, V5). **Falta:** activar las comprobaciones automáticas de accesibilidad (ATF) en las pruebas de Compose, como ya hacen los repos de Velora |
| Data Layer sin secretos | `NoCredentialsInProtocolTest` (recorre todos los payloads), `ModeAProtocolTest` (catálogo cerrado de mensajes), `ProtocolCodecTest` (nonce y caducidad) | — |
| Garantía del modo A | `ModeAProtocolTest`, `ModeANoUploadsTest` (solo las peticiones permitidas a Velora), `ModeAContractTest` (el cliente generado solo tiene GET más `/auth`), `ModeAWatchTest` (sin `INTERNET` ni ubicación en el reloj) | Una sola actividad en Strava y en Velora por salida (`10` §4) |
| Importación FIT directa | — | Solo si WC-13 la confirma como respaldo; hoy no hay código |

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
- Si cambia el protocolo reloj ↔ teléfono: sube `schemaVersion` solo si hace falta, se sigue aceptando la versión anterior y hay prueba de compatibilidad en `ProtocolCodecTest`.
- Ningún secreto por la Data Layer: `NoCredentialsInProtocolTest` y `ModeAProtocolTest` en verde; un payload nuevo se agrega al catálogo de `ModeAProtocolTest` de forma explícita.
- Pantallas nuevas del reloj: etiquetas para TalkBack, uso en ambiente y desplazamiento con bisel/corona, con prueba de Compose.
- Cambios en sesiones de ejercicio, servicios o trabajo en segundo plano: medición de batería en el dispositivo anotada en el PR.
- En la Fase 1, las pruebas de garantía del modo A (`ModeA*`) en verde.
