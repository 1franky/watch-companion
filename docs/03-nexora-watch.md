# 03 — Nexora Watch: finanzas desde la muñeca

## 1. Objetivo

Extender Nexora al Galaxy Watch Ultra para consultar información financiera y capturar borradores de movimientos con interacción mínima.

Nexora API conserva toda regla financiera. El reloj nunca calcula saldos, intereses, MSI/MCI, fechas de corte ni fechas límite de pago como fuente de verdad.

## 2. Funcionalidades MVP

### Dashboard de solo lectura

- Saldo disponible o saldo consolidado según la definición actual de Nexora.
- Deuda total de tarjetas.
- Próximo pago y fecha límite.
- Gastos del periodo.
- Estado de sincronización y fecha de última actualización.

**Presupuestos: fuera del MVP (A-07).** `nexora-api` no tiene presupuestos; `BUDGET_EXCEEDED` existe en el enum de notificaciones pero nada la emite. Las alertas de presupuesto vuelven a este documento cuando exista la épica en `nexora-api` (WC-28).

Cada dato debe mostrar su fecha/hora de actualización. No presentar una cifra cacheada como actual.

### Movimientos rápidos

Permitir capturar en el reloj un borrador de:
- gasto
- ingreso

Campos mínimos:
- importe
- cuenta
- categoría opcional
- nota opcional
- fecha/hora

Nexora no tiene borradores en el backend (A-08), y no hacen falta:
1. El reloj captura el borrador y genera una `Idempotency-Key` (UUID) **en ese momento**.
2. El borrador viaja por la Data Layer al teléfono, que lo guarda **solo en local y cifrado** y pide confirmación en pantalla.
3. «Confirmar» en el teléfono es `POST /api/v1/transactions` (`INCOME`/`EXPENSE`) con esa `Idempotency-Key`.
4. El resultado (creado, rechazado con su código de error, o pendiente por falta de red) vuelve al reloj. Hasta tener respuesta 2xx, el reloj muestra el borrador como **pendiente**, nunca como registrado.
5. «Consultar el resultado» es reintentar el mismo `POST` con la misma clave: `nexora-api` devuelve la respuesta original si ya se aplicó, y 409 si la clave llega con otro cuerpo (`IdempotencyFilter`). Un reintento nunca crea dos movimientos.

Cancelar un borrador antes de confirmarlo lo borra del teléfono; nada llegó al backend.

### Notificaciones

Nexora no tiene push (A-09): sus notificaciones son una bandeja que se consulta con `GET /api/v1/notifications`. `app-phone` la **sondea con WorkManager** (cada 6–12 h, con red y sin ahorro de batería) y publica notificaciones locales que el sistema puentea al reloj. Una notificación ya mostrada no se repite (se recuerda su `id`).

Tipos que se muestran en el MVP, solo de los que `nexora-api` emite hoy:

| Tipo | Uso en el reloj |
|---|---|
| `PAYMENT_DUE_SOON` | Fecha límite de tarjeta próxima |
| `PAYMENT_DUE` | Pago de tarjeta que vence |
| `INSTALLMENT_DUE` | Mensualidad MSI/MCI pendiente |
| `SCHEDULED_CHARGE_FAILED` | Un cargo programado no se pudo aplicar |

No se usan: `BUDGET_EXCEEDED`, `UNUSUAL_EXPENSE` y `PAYMENT_OVERDUE` (declarados pero sin emisor), `SCHEDULED_CHARGE_POSTED` (informativo, sin acción) y `SAT_SYNC_*` (de la web). Un tipo desconocido se ignora sin fallar.

No incluir números completos de cuenta, referencias bancarias o información sensible en la pantalla bloqueada.

## 3. Funciones posteriores

- Consultar compras MSI/MCI y mensualidades pendientes.
- Consultar fechas de corte y pago por tarjeta.
- Marcar recordatorios como atendidos (no equivale a registrar un pago).
- Accesos rápidos a cuentas favoritas.
- Widgets/complications de información no sensible y configurable.

## 4. Arquitectura

- Consumir Nexora API usando el contrato OpenAPI vigente.
- No conectar el módulo directamente a la base de datos.
- No duplicar entidades de negocio en Room; cachear snapshots con fecha de expiración.
- Las operaciones mutables deben ser idempotentes y confirmar resultado del backend.
- No ejecutar operaciones financieras críticas desde una notificación sin una pantalla de confirmación.

## 5. Endpoints de Nexora que usa el companion

Revisados en `nexora-api` (`develop`, 2026-11-23). Los DTO exactos salen del contrato fijado en el companion (WC-36, a partir de WC-25); esta tabla no los reespecifica.

| Necesidad | Endpoint real | Notas |
|---|---|---|
| Resumen financiero, próximos pagos y movimientos recientes | `GET /api/v1/dashboard?month=yyyy-MM&recentTransactionsLimit=N` | Una sola llamada: disponible, patrimonio, ingresos/gastos del mes, deuda de tarjetas, `upcomingPayments` y `recentTransactions` (N entre 1 y 50) |
| Tarjetas y su próximo corte/pago | `GET /api/v1/credit-cards`, `GET /api/v1/credit-cards/{id}` | Solo si el dashboard no basta |
| Cuentas y categorías para el borrador | `GET /api/v1/accounts`, `GET /api/v1/categories` | Se cachean en el teléfono |
| Confirmar un movimiento | `POST /api/v1/transactions` + cabecera `Idempotency-Key` | `INCOME`/`EXPENSE`; ver §2 Movimientos rápidos |
| Consultar el resultado | El mismo `POST` con la misma clave | Respuesta original si ya se aplicó; 409 si cambió el cuerpo |
| Notificaciones | `GET /api/v1/notifications?unreadOnly=true` | Sondeo desde `app-phone` |
| Sesión | `POST /api/v1/auth/login`, `/refresh`, `/logout` | Solo en `app-phone`, sesión propia (WC-ADR-013, WC-37) |

`GET /api/v1/transactions` no está paginado: el reloj no lo usa; los movimientos recientes salen del dashboard.

## 6. Seguridad

- Bloquear vistas sensibles cuando el reloj esté desbloqueado de forma insuficiente o el usuario lo solicite.
- No mostrar saldos en notificaciones por defecto.
- Evitar persistir snapshots más allá de la ventana necesaria.
- Cifrar almacenamiento local cuando contenga datos privados.
- Revocar pairing y sesiones desde el teléfono.
- No almacenar credenciales de Nexora en texto plano.

## 7. Criterios de aceptación

- Los saldos coinciden con Nexora API en una consulta online.
- El reloj indica claramente cuándo los datos están desactualizados.
- Una operación no confirmada en el teléfono no se registra en el backend.
- Reintentar un envío (misma `Idempotency-Key`) no crea dos movimientos.
- Ninguna pantalla ni notificación habla de presupuestos mientras `nexora-api` no los tenga.
- La app funciona en modo desconectado mostrando estado de datos cacheados, sin permitir acciones que aparenten éxito.
- No se filtran datos sensibles en logs ni notificaciones.
