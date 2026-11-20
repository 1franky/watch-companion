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
- Alertas de presupuesto.
- Estado de sincronización y fecha de última actualización.

Cada dato debe mostrar su fecha/hora de actualización. No presentar una cifra cacheada como actual.

### Movimientos rápidos

Permitir crear un borrador de:
- gasto
- ingreso

Campos mínimos:
- importe
- cuenta
- categoría opcional
- nota opcional
- fecha/hora

En el MVP, el reloj puede capturar el borrador y enviarlo al teléfono para confirmación. La confirmación final debe invocar el endpoint oficial de Nexora API y devolver el resultado al reloj.

### Notificaciones

- Próxima fecha límite de tarjeta.
- Pago pendiente.
- Compra o movimiento relevante si Nexora ya dispone de eventos para ello.
- Presupuesto cercano al límite.

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

## 5. Contratos conceptuales

Adaptar a endpoints reales:

- obtener resumen financiero
- obtener tarjetas y próximos pagos
- obtener movimientos recientes
- crear borrador de transacción
- confirmar transacción
- consultar resultado de operación

No inventar rutas ni DTOs antes de revisar OpenAPI y el flujo de autenticación de Nexora.

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
- Una operación no confirmada no se registra en el backend.
- Reintentar un envío no crea dos movimientos.
- La app funciona en modo desconectado mostrando estado de datos cacheados, sin permitir acciones que aparenten éxito.
- No se filtran datos sensibles en logs ni notificaciones.
